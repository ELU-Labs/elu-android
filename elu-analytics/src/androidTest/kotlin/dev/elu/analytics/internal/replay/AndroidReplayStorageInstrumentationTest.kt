package dev.elu.analytics.internal.replay

import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.flags.FlagDurableStore
import dev.elu.analytics.internal.runtime.*
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Device-only schema/segment evidence. It does not negotiate, decode or send an inner codec. */
class AndroidReplayStorageInstrumentationTest {
    @Test fun bothMigrationOrdersRetainExactPreparedRequestBeyondOneMiB() {
        for (flagsFirst in listOf(true, false)) {
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "replay-storage-test-" + UUID.randomUUID())
            val file = File(directory, "queue.sqlite")
            val key = "elu_pk_live_" + "A".repeat(26)
            val namespace = RuntimeSiteNamespace.digest(key)
            val initial = ReplayStoredState(namespace).row()
            val flag = FlagDurableStore.uninitializedAuthorityRow(key, namespace)
            val profile = ReplayMaskingProfile.parse("{\"fixture\":true}".toByteArray())
            val request = prepared(profile)
            assertTrue(request.byteCount > 1_048_576)
            try {
                AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                    db.transaction { tx -> tx.insertCore(RuntimeStoredCore(CoreStateCodec.encode(PersistedCoreState(
                        identity = IdentityState(revision = 1, contextRevision = 1, anonymousId = "anon_fixture", userId = null,
                            groups = emptyMap(), superProperties = emptyMap(), session = null, optedOut = false, updatedAt = "2026-08-05T00:00:00Z"),
                        stream = StreamState(streamId = "stream_fixture", nextSequence = 0),
                        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))), 0, 0)) }
                    if (flagsFirst) db.ensureFlagSchema(flag)
                    db.ensureReplaySchema(initial)
                    if (!flagsFirst) db.ensureFlagSchema(flag)
                    db.transaction { tx ->
                        // Storage fixture only: no live source/producer authorizes this test insert.
                        tx.putReplayRow(ReplayStoredState(namespace, "fixture-site", "2026-08-05T00:00:00Z",
                            "sha256:" + "0".repeat(64), "fixture-generation").row())
                        assertEquals(ReplayAppendResult.Stored(0, false), ReplayQueueStore.append(tx, request, profile,
                            namespace, "fixture-site", "fixture-generation", 1_785_888_060_000L,
                            MAX_RUNTIME_QUEUE_RECORDS, MAX_RUNTIME_QUEUE_BYTES, MAX_REPLAY_REQUEST_BYTES) { 1_785_888_060_000L })
                    }
                }
                AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                    db.ensureReplaySchema(initial); db.ensureFlagSchema(flag)
                    db.transaction { tx ->
                        assertArrayEquals(flag.payload, tx.readFlagRow(RUNTIME_FLAG_AUTHORITY_KEY)!!.payload)
                        ReplayQueueStore.validate(tx, namespace)
                        val stored = ReplayQueueStore.read(tx, ReplayQueueStore.headers(tx).single())
                        assertArrayEquals(request.copyBytes(), stored.prepared.copyBytes())
                        tx.scanReplayRows("body/") { assertTrue(it.payload.size <= REPLAY_SEGMENT_BYTES) }
                    }
                }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun nativeMetadataMigrationOrdersRetainExactStateWithFlags() {
        for (flagsFirst in listOf(true, false)) withNativeFile { file, key, namespace ->
            val native = NativeReplaySessionState(namespace, "stream_native_fixture")
            val row = NativeReplayAccounting.row(native)
            val flag = FlagDurableStore.uninitializedAuthorityRow(key, namespace)
            AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                db.transaction { it.insertCore(nativeCore()) }
                if (flagsFirst) db.ensureFlagSchema(flag)
                db.ensureReplaySchema(ReplayStoredState(namespace).row())
                db.ensureNativeReplaySchema(row)
                if (!flagsFirst) db.ensureFlagSchema(flag)
                db.ensureNativeReplaySchema(row)
                db.transaction { tx ->
                    assertTrue(tx.nativeReplaySchemaPresent())
                    assertArrayEquals(row.payload, tx.readReplayRow(NativeReplayAccounting.KEY)!!.payload)
                    assertArrayEquals(flag.payload, tx.readFlagRow(RUNTIME_FLAG_AUTHORITY_KEY)!!.payload)
                    assertEquals(0L, ReplayQueueStore.validate(tx, namespace)!!.count)
                    assertEquals(0L, tx.readCore()!!.queueBytes)
                }
            }
            AndroidSQLiteRuntimeDatabase.open(file).use { db -> db.transaction { tx ->
                ReplayQueueStore.validate(tx, namespace)
                assertArrayEquals(row.payload, tx.readReplayRow(NativeReplayAccounting.KEY)!!.payload)
            } }
        }
    }

    @Test fun nativeMigrationRollbackAndLostCommitResultPreserveExactMarkerAndRow() {
        for (lostResponse in listOf(false, true)) withNativeFile { file, _, namespace ->
            var armed = false
            val faults = object : AndroidRuntimeDatabaseFaults {
                override fun beforeCommit() { if (armed && !lostResponse) { armed = false; throw java.io.IOException("native migration rollback") } }
                override fun afterCommit() { if (armed && lostResponse) { armed = false; throw java.io.IOException("native commit response lost") } }
            }
            val row = NativeReplayAccounting.row(NativeReplaySessionState(namespace, "stream_native_fixture"))
            AndroidSQLiteRuntimeDatabase.open(file, faults).use { db ->
                db.transaction { it.insertCore(nativeCore()) }; db.ensureReplaySchema(ReplayStoredState(namespace).row())
                armed = true
                var failed = false
                try { db.ensureNativeReplaySchema(row) } catch (_: Exception) { failed = true }
                assertTrue(failed)
            }
            AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                db.transaction { tx ->
                    assertEquals(lostResponse, tx.nativeReplaySchemaPresent())
                    if (lostResponse) assertArrayEquals(row.payload, tx.readReplayRow(NativeReplayAccounting.KEY)!!.payload)
                    else assertNull(tx.readReplayRow(NativeReplayAccounting.KEY))
                }
                db.ensureNativeReplaySchema(row)
                db.transaction { tx -> ReplayQueueStore.validate(tx, namespace) }
            }
        }
    }

    @Test fun nativeFutureMissingAndForeignMetadataCannotBeReseededOnReopen() {
        for (mode in 0..3) withNativeFile { file, _, namespace ->
            val row = NativeReplayAccounting.row(NativeReplaySessionState(namespace, "stream_native_fixture"))
            var expected: ByteArray? = null
            AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                db.transaction { it.insertCore(nativeCore()) }; db.ensureReplaySchema(ReplayStoredState(namespace).row())
                db.ensureNativeReplaySchema(row)
                db.transaction { tx ->
                    when (mode) {
                        0 -> tx.deleteReplayRow(NativeReplayAccounting.KEY)
                        1 -> tx.putReplayRow(row.copy(storageSchemaVersion = 2))
                        2 -> tx.putReplayRow(row.copy(payload = "{\"schemaVersion\":2}".toByteArray()))
                        3 -> tx.putReplayRow(NativeReplayAccounting.row(NativeReplaySessionState("a".repeat(64), "stream_native_fixture")))
                    }
                    expected = tx.readReplayRow(NativeReplayAccounting.KEY)?.payload?.copyOf()
                }
            }
            AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                var failed = false
                try { db.ensureNativeReplaySchema(row) } catch (_: Exception) { failed = true }
                assertTrue(failed)
                db.transaction { tx ->
                    assertTrue(tx.nativeReplaySchemaPresent())
                    assertArrayEquals(expected, tx.readReplayRow(NativeReplayAccounting.KEY)?.payload)
                }
            }
        }
    }

    @Test fun nativeCanonicalFalseCapAndFirstWindowSurviveRealSQLiteTransactions() = withNativeFile { file, _, namespace ->
        val key = NativeReplaySessionState.Key("fixture-site", "session_fixture", "2026-08-05T00:00:00Z")
        var value = NativeReplaySessionState(namespace, "stream_native_fixture").observe(key, 0.0, 0,
            "2026-08-05T00:00:01Z", null, null)
        AndroidSQLiteRuntimeDatabase.open(file).use { db ->
            db.transaction { it.insertCore(nativeCore()) }; db.ensureReplaySchema(ReplayStoredState(namespace).row())
            db.ensureNativeReplaySchema(NativeReplayAccounting.row(value))
        }
        AndroidSQLiteRuntimeDatabase.open(file).use { db -> db.transaction { tx ->
            value = NativeReplayAccounting.read(tx.readReplayRow(NativeReplayAccounting.KEY)!!)
            val restricted = value.observe(key, 1.0, 100, "2026-08-05T00:00:02Z", null, null)
            tx.putReplayRow(NativeReplayAccounting.row(restricted))
            assertFalse(restricted.session!!.originalSelected)
            assertEquals(0, restricted.session!!.maximumDurationSeconds)
            assertNull(restricted.begin(UUID.randomUUID().toString(), "2026-08-05T00:00:02Z", 1.0).session!!.activeEpoch)
            ReplayQueueStore.validate(tx, namespace)
        } }
    }

    @Test fun freshOwnerPreservesFirstWindowAndInterruptsEvenCleanInactiveGap() {
        for (wasActive in listOf(false, true)) withNativeFile { file, siteKey, namespace ->
            val key = NativeReplaySessionState.Key("fixture-site", "session_fixture", "2026-08-05T00:00:00Z")
            val epoch = UUID.randomUUID().toString()
            val begun = NativeReplaySessionState(namespace, "stream_native_fixture")
                .observe(key, 1.0, 60, "2026-08-05T00:00:01Z", null, null)
                .begin(epoch, "2026-08-05T00:00:01Z", 1.0).allocateReplayId().state
            val original = if (wasActive) begun else begun.stop(key, "2026-08-05T00:00:01Z", epoch,
                "2026-08-05T00:00:02Z", 1_000_000).state
            val core = nativeCore(SessionState(key.sessionId, key.sessionStartedAt, "2026-08-05T00:00:02Z", 1800,
                lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null))
            AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                db.transaction { it.insertCore(core) }; db.ensureReplaySchema(ReplayStoredState(namespace).row())
                db.ensureNativeReplaySchema(NativeReplayAccounting.row(original))
            }
            val owner = AndroidRuntimeQueue.openForTesting(file, RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
                legacyStateLoader = { error("Existing core must not be replaced") }, trustedSiteKey = siteKey).get(5, java.util.concurrent.TimeUnit.SECONDS)
            owner.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS)
            AndroidSQLiteRuntimeDatabase.open(file).use { db -> db.transaction { tx ->
                val retained = NativeReplayAccounting.read(tx.readReplayRow(NativeReplayAccounting.KEY)!!)
                assertTrue(retained.session!!.interrupted)
                assertEquals(original.session!!.firstStartAt, retained.session!!.firstStartAt)
                assertEquals(original.nextReplayOrdinal, retained.nextReplayOrdinal)
                assertEquals(original.session!!.elapsedFloorMicroseconds, retained.session!!.elapsedFloorMicroseconds)
                ReplayQueueStore.validate(tx, namespace)
            } }
        }
    }

    private fun withNativeFile(test: (File, String, String) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "native-accounting-test-" + UUID.randomUUID())
        val key = "elu_pk_live_" + "N".repeat(26)
        try { test(File(directory, "queue.sqlite"), key, RuntimeSiteNamespace.digest(key)) }
        finally { directory.deleteRecursively() }
    }

    private fun nativeCore(session: SessionState? = null) = RuntimeStoredCore(CoreStateCodec.encode(PersistedCoreState(
        identity = IdentityState(revision = 1, contextRevision = 1, anonymousId = "anon_fixture", userId = null,
            groups = emptyMap(), superProperties = emptyMap(), session = session, optedOut = false, updatedAt = session?.lastActivityAt ?: "2026-08-05T00:00:00Z"),
        stream = StreamState(streamId = "stream_native_fixture", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))), 0, 0)

    private fun prepared(profile: ReplayMaskingProfile): PreparedReplayRequest {
        val chunk = JSONObject("""{"schemaVersion":2,"replayId":"replay_fixture","sessionId":"session_fixture",
            "chunkId":"chunk_fixture","sequence":0,"startedAt":"2026-08-05T00:00:00Z","endedAt":"2026-08-05T00:00:01Z",
            "identity":{"anonymousId":"anon_fixture","userId":null,"revision":1},"contextRevision":1,
            "codec":"elu-browser-dom-v1","compression":"none","contentEncoding":"base64",
            "privacy":{"policyRevision":"fixture","effectivePolicyHash":"sha256:${"0".repeat(64)}",
            "maskingProfileHash":"${profile.hash}","appliedBeforeSerialization":true,"secureInputsMasked":true,"platformFallbackApplied":false},
            "versions":{"schemaVersion":2,"contractVersion":"2.0.0","platform":"browser","runtime":{"name":"elu-js","version":"fixture"},
            "facade":{"name":"window.elu","version":"fixture"}}}""")
        chunk.put("payload", ReplayBase64.encode(ByteArray(1_100_000) { (it % 251).toByte() }))
        val chunkBytes = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(chunk.toString()))
        val material = "elu-sdk-replay-request-v2".toByteArray() + byteArrayOf(0) + ByteBuffer.allocate(4).putInt(chunkBytes.size).array() + chunkBytes
        val root = JSONObject().put("schemaVersion", 2).put("chunk", chunk)
            .put("requestId", "request_" + ReplayJson.digest(material).removePrefix("sha256:"))
        return PreparedReplayRequest.parse(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(root.toString())), "fixture-generation")
    }
}
