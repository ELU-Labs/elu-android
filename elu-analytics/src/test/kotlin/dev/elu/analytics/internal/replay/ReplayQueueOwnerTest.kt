package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import dev.elu.analytics.internal.flags.FlagDurableStore
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplayQueueOwnerTest {
    @Test fun `canonical stack has no replay proof and default owner cannot append`() = Rig(proven = false).use { rig ->
        rig.activate()
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
        assertTrue(rig.rows().isEmpty())
    }
    @Test fun `storage-only browser fixture round trip above one MiB uses bounded segments`() = Rig().use { rig ->
        rig.activate()
        val request = rig.request { it.put("payload", Base64.getEncoder().encodeToString(ByteArray(1_100_000))) }
        assertTrue(rig.append(request) is ReplayAppendResult.Stored)
        val row = rig.rows().single()
        assertArrayEquals(request.copyBytes(), row.prepared.copyBytes())
        assertEquals("replay-v2-generation-1", row.captureProtocolGeneration)
        assertTrue(rig.backing.replayRows.values.all { it.payload.size <= REPLAY_SEGMENT_BYTES })
        assertTrue(rig.backing.replayRows.keys.count { it.startsWith("body/") } > 4)
        assertEquals(0, rig.owner.snapshot().get().queuedCount)
        assertEquals(request.byteCount.toLong(), rig.state().bytes)
    }
    @Test fun `both independent schema migration orders preserve existing rows and reopen`() {
        for (flagsFirst in listOf(true, false)) Rig().use { rig ->
            // Rig only initializes core; migration order remains explicit.
            if (flagsFirst) rig.owner.ensureFeatureFlagRuntime().get()
            rig.owner.ensurePreparedReplayStorage().get()
            assertEquals(if (flagsFirst) 4 else 3, rig.backing.databaseSchemaVersion)
            if (!flagsFirst) rig.owner.ensureFeatureFlagRuntime().get()
            assertEquals(4, rig.backing.databaseSchemaVersion)
            assertTrue(rig.backing.flagRows.containsKey(RUNTIME_FLAG_AUTHORITY_KEY))
            val before = rig.backing.flagRows[RUNTIME_FLAG_AUTHORITY_KEY]!!.payload.copyOf()
            rig.owner.ensurePreparedReplayStorage().get(); rig.owner.ensureFeatureFlagRuntime().get()
            assertArrayEquals(before, rig.backing.flagRows[RUNTIME_FLAG_AUTHORITY_KEY]!!.payload)
            rig.activate(); assertTrue(rig.append() is ReplayAppendResult.Stored)
            rig.reopen(); assertEquals(1, rig.rows().size)
        }
    }
    @Test fun `shared count rejects newest event and replay without advancing event stream`() = Rig(countLimit = 1).use { rig ->
        rig.activate(); assertTrue(rig.append() is ReplayAppendResult.Stored)
        val before = rig.owner.snapshot().get()
        assertEquals(RuntimeCaptureRejection.QUEUE_LIMIT, (rig.owner.capture(rig.event()).get() as RuntimeCaptureResult.Rejected).reason)
        assertEquals(before, rig.owner.snapshot().get())
        val result = rig.append(rig.request { it.put("chunkId", "next").put("sequence", 2) })
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.COUNT_LIMIT), result)
    }
    @Test fun `existing events count against replay admission`() = Rig(countLimit = 1).use { rig ->
        rig.activate(); val capture = rig.owner.capture(rig.event()).get(); assertTrue(capture.toString(), capture is RuntimeCaptureResult.Accepted)
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.COUNT_LIMIT), rig.append())
    }
    @Test fun `exact duplicate preserves ordinal and conflicts cover both semantic scopes`() = Rig().use { rig ->
        rig.activate(); assertEquals(ReplayAppendResult.Stored(0, false), rig.append())
        assertEquals(ReplayAppendResult.Stored(0, true), rig.append())
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("payload", "YQ==") },
            { it.put("chunkId", "other") },
            { it.put("sequence", 2) })
        variants.forEach { assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.CONFLICT), rig.append(rig.request(it))) }
        assertEquals(1, rig.rows().size); assertEquals(1L, rig.state().nextOrdinal)
    }
    @Test fun `withdrawal before and during SQL denies and does not publish stale success`() = Rig().use { rig ->
        rig.activate()
        rig.onReplayWrite = { rig.driver.onBackground() }
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
        assertTrue(rig.rows().isEmpty())
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
    }
    @Test fun `withdrawal at commit prevents success while keeping exact committed bytes sealed`() = Rig().use { rig ->
        rig.activate()
        rig.afterTransaction = { rig.driver.onBackground() }
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
        assertEquals(1, rig.rows().size)
    }
    @Test fun `ambiguous committed append returns same result without duplicate allocation`() = Rig().use { rig ->
        rig.activate(); rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        assertEquals(ReplayAppendResult.Stored(0, false), rig.append())
        assertEquals(1L, rig.state().nextOrdinal); assertEquals(1, rig.rows().size)
    }
    @Test fun `ambiguous rollback retries with original bytes and fresh source check`() = Rig().use { rig ->
        rig.activate(); rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.ROLLBACK
        assertEquals(ReplayAppendResult.Stored(0, false), rig.append())
        assertEquals(1L, rig.state().nextOrdinal)
    }
    @Test fun `known rollback retries once while divergent commit poisons owner`() {
        Rig().use { rig ->
            rig.activate(); rig.backing.failNextKnownCommit = ProvenNotCommittedRuntimeTransactionException("rolled back")
            assertTrue(rig.append() is ReplayAppendResult.Stored)
        }
        Rig().use { rig ->
            rig.activate(); rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE
            try { rig.append(); fail("divergence") } catch (_: java.util.concurrent.ExecutionException) { }
            try { rig.owner.snapshot().get(); fail("poison") } catch (_: java.util.concurrent.ExecutionException) { }
        }
    }
    @Test fun `exact protocol generation change purges before new admission`() = Rig().use { rig ->
        rig.activate(); rig.append()
        rig.renew { it.getJSONObject("capabilities").getJSONObject("replay").put("replayProtocolGeneration", "replay-v2-generation-2") }
        rig.reconcile(); assertTrue(rig.rows().isEmpty()); assertEquals(1L, rig.state().nextOrdinal)
    }
    @Test fun `source expiry preserves bytes while explicit disable purges`() = Rig().use { rig ->
        rig.activate(); rig.append()
        rig.driver.onBackground()
        assertFalse(rig.reconcile()); assertEquals(1, rig.rows().size)
        rig.renew { it.getJSONObject("features").put("replay", false) }
        assertFalse(rig.reconcile()); assertTrue(rig.rows().isEmpty())
    }
    @Test fun `removed pair and incomparable masking purge but identity context changes preserve sealed bytes`() = Rig().use { rig ->
        rig.activate(); rig.append()
        rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("plan" to "new"), "2026-08-05T00:01:06.000Z")).get()
        assertEquals(1, rig.rows().size)
        assertTrue(rig.reconcile(ReplayMaskingRetention { _, _ -> true }))
        assertEquals(1, rig.rows().size)
        rig.reconcile(ReplayMaskingRetention { _, _ -> false }); assertTrue(rig.rows().isEmpty())
    }
    @Test fun `clock rollback poisons replay owner even after wall recovery`() = Rig().use { rig ->
        rig.activate(); rig.append(); val original = rig.clock.wall
        rig.ownerWall = original - 1
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.CLOCK), rig.append())
        rig.ownerWall = original
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.CLOCK), rig.append())
        assertEquals(1, rig.rows().size)
    }
    @Test fun `corrupt missing or extra body segments fail reopen without repairing`() {
        for (missing in listOf(true, false)) Rig().use { rig ->
            rig.activate(); rig.append()
            val key = rig.backing.replayRows.keys.first { it.startsWith("body/") }
            if (missing) rig.backing.replayRows.remove(key)
            else rig.backing.replayRows[key + "extra"] = RuntimeReplayStoredRow(key + "extra", 1, byteArrayOf(1))
            try { rig.reopen(); fail("corruption") } catch (_: java.util.concurrent.ExecutionException) { }
            assertTrue(rig.backing.replayRows.isNotEmpty())
        }
    }
    @Test fun `opt out purges replay atomically without changing event accounting`() = Rig().use { rig ->
        rig.activate(); rig.append()
        rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, "2026-08-05T00:01:06.000Z")).get()
        assertTrue(rig.rows().isEmpty()); assertEquals(0, rig.owner.snapshot().get().queuedCount)
    }

    @Test fun `retention expiry deletes without a source token and ambiguous purge keeps ordinal`() = Rig().use { rig ->
        rig.activate(); rig.append(); rig.driver.onBackground()
        rig.ownerWall = Instant.parse("2026-08-12T00:01:00Z").toEpochMilli()
        rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        assertEquals(1, rig.owner.expirePreparedReplay().get())
        assertTrue(rig.rows().isEmpty()); assertEquals(1L, rig.state().nextOrdinal)
    }
    @Test fun `identity and session changes cannot admit an earlier prepared chunk`() = Rig().use { rig ->
        rig.activate()
        val original = rig.request()
        rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("plan" to "new"), "2026-08-05T00:01:06.000Z")).get()
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append(original))
        assertTrue(rig.rows().isEmpty())
    }
    @Test fun `durable same-boundary conflict cannot recover until newer issuance`() = Rig().use { rig ->
        rig.activate(); rig.append()
        val original = V1ConfigJson.parseConfig(rig.body)
        val conflict = V1ConfigJson.parseConfig(JSONObject(rig.body).put("revision", "conflict").toString())
        rig.backing.connection().use { db -> db.transaction { tx ->
            assertFalse(ReplayQueueStore.reconcile(tx, conflict, rig.namespace, rig.clock.wall, false, setOf(PAIR), setOf(ReplayFixtures.GENERATION), ReplayMaskingRetention { _, _ -> true }))
            assertFalse(ReplayQueueStore.reconcile(tx, original, rig.namespace, rig.clock.wall, false, setOf(PAIR), setOf(ReplayFixtures.GENERATION), ReplayMaskingRetention { _, _ -> true }))
        } }
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
        assertEquals(1, rig.rows().size)
        rig.renew { }; rig.reconcile()
        assertEquals(ReplayAppendResult.Stored(0, true), rig.append())
    }
    @Test fun `shared byte budget counts exact complete requests in both directions`() = Rig().use { rig ->
        rig.activate()
        val request = rig.request()
        rig.backing.connection().use { db -> db.transaction { tx ->
            val before = checkNotNull(ReplayQueueStore.state(tx))
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.BYTE_LIMIT),
                ReplayQueueStore.append(tx, request, ReplayFixtures.profile(), rig.namespace, before.siteId, before.protocol,
                    rig.clock.wall, 100, request.byteCount.toLong() - 1, MAX_REPLAY_REQUEST_BYTES) { rig.clock.wall })
        } }
        assertTrue(rig.append() is ReplayAppendResult.Stored)
        // Config queue budget is a complete canonical request budget; no segment overhead is counted.
        assertEquals(request.byteCount.toLong(), rig.state().bytes)
    }

    @Test fun `prepared session policy privacy and profile bind actual current authority`() = Rig().use { rig ->
        rig.activate()
        listOf<(JSONObject) -> Unit>(
            { it.put("sessionId", "different-session") },
            { it.getJSONObject("privacy").put("policyRevision", "different-policy") },
            { it.getJSONObject("privacy").put("effectivePolicyHash", "sha256:" + "0".repeat(64)) },
            { it.put("startedAt", "2026-08-05T00:00:00Z") },
        ).forEach { change ->
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append(rig.request(change)))
        }
        val untrusted = ReplayMaskingProfile.parse("{\"masking\":\"none\"}".toByteArray())
        val selfConsistent = rig.request { it.getJSONObject("privacy").put("maskingProfileHash", untrusted.hash) }
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY),
            rig.owner.appendPreparedReplay(selfConsistent, untrusted, rig.privacy()).get())
        assertTrue(rig.rows().isEmpty())
    }
    @Test fun `matching receipt and proven pair do not replace trusted masking admission`() = Rig(allowProfile = false).use { rig ->
        rig.activate()
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
    }
    @Test fun `expiry preserves on invalid wall or backward observation after startedAt including reopen`() {
        for (wall in listOf(-1L, Long.MAX_VALUE, Instant.parse("2026-08-05T00:01:05Z").toEpochMilli())) Rig().use { rig ->
            rig.activate(); rig.append(); rig.reopen(); rig.ownerWall = wall
            assertEquals(0, rig.owner.expirePreparedReplay().get()); assertEquals(1, rig.rows().size)
            rig.reopen()
            rig.ownerWall = Instant.parse("2026-08-12T00:01:00Z").toEpochMilli()
            assertEquals(0, rig.owner.expirePreparedReplay().get()); assertEquals(1, rig.rows().size)
        }
    }

    @Test fun `prepared generation cannot be restamped after same pair and privacy refresh`() = Rig().use { rig ->
        rig.activate(); val old = rig.request()
        rig.renew { it.getJSONObject("capabilities").getJSONObject("replay").put("replayProtocolGeneration", "replay-v2-generation-2") }
        rig.reconcile()
        assertEquals(ReplayFixtures.GENERATION, old.captureProtocolGeneration)
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append(old))
        assertTrue(rig.append(rig.request()) is ReplayAppendResult.Stored)
        assertEquals("replay-v2-generation-2", rig.rows().single().prepared.captureProtocolGeneration)
    }
    @Test fun `active lifecycle alone cannot authorize idle or maximum expired sessions`() {
        for (maximum in listOf(false, true)) Rig(maximumExpiredSession = maximum).use { rig ->
            rig.activate()
            if (!maximum) {
                rig.renew { it.getJSONObject("session").put("idleTimeoutSeconds", 60) }
                rig.reconcile()
                rig.clock.wall += 61_000
            }
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
            assertTrue(rig.rows().isEmpty())
        }
    }

    @Test fun `unsupported generation purges old rows and never gains authority from an unchanged pair`() = Rig().use { rig ->
        rig.activate(); rig.append()
        rig.renew { it.getJSONObject("capabilities").getJSONObject("replay").put("replayProtocolGeneration", "unsupported-generation") }
        assertFalse(rig.reconcile()); assertTrue(rig.rows().isEmpty())
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
    }
    @Test fun `no locally supported generation keeps proven-pair storage closed`() = Rig(supported = emptySet()).use { rig ->
        rig.activate(); assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append())
    }

    @Test fun `session and trusted profile are rechecked after validation before first write`() {
        for (expireSession in listOf(true, false)) Rig().use { rig ->
            rig.activate()
            rig.renew { it.getJSONObject("session").put("idleTimeoutSeconds", 60) }; rig.reconcile()
            val request = rig.request()
            rig.profileChecks = 0
            rig.onReplayStateRead = {
                if (expireSession) rig.clock.wall += 61_000 else rig.profileCurrent = false
            }
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append(request))
            assertTrue(rig.rows().isEmpty())
        }
    }
    @Test fun `late clock withdrawal rolls back data but persists clock denial separately`() = Rig().use { rig ->
        rig.activate(); val request = rig.request()
        rig.onReplayWrite = { rig.ownerWall = rig.clock.wall - 1 }
        assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.append(request))
        assertTrue(rig.rows().isEmpty()); assertTrue(rig.state().clockDenied)
        rig.ownerWall = rig.clock.wall; rig.reopen()
        assertEquals(0, rig.owner.expirePreparedReplay().get())
        assertTrue(rig.state().clockDenied)
    }

    private class Rig(private val proven: Boolean = true, private val countLimit: Int = 100, private val allowProfile: Boolean = true, private val maximumExpiredSession: Boolean = false, private val supported: Set<String> = setOf(ReplayFixtures.GENERATION, "replay-v2-generation-2")) : AutoCloseable {
        val clock = Clock(); val worker = Worker(); val gate = V2ConfigAuthorityGate()
        var body = ReplayFixtures.resource("contracts/v2/fixtures/config-enabled.json")
        val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, Scheduler(), worker)
        val backing = FakeRuntimeQueueBacking()
        @Volatile var onReplayWrite: (() -> Unit)? = null
        @Volatile var onReplayStateRead: (() -> Unit)? = null
        @Volatile var profileCurrent = true
        @Volatile var profileChecks = 0
        @Volatile var afterTransaction: (() -> Unit)? = null
        @Volatile var ownerWall: Long? = null
        val namespace = RuntimeSiteNamespace.digest(KEY)
        var owner = open()
        private fun open(): RuntimeQueueOwner = RuntimeQueueOwner.open("replay-" + UUID.randomUUID(), RuntimeQueueLimits(countLimit, MAX_RUNTIME_QUEUE_BYTES),
            databaseFactory = {
                val db = backing.connection()
                object : RuntimeQueueDatabase by db {
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
                        var wroteReplay = false
                        val result = db.transaction { tx -> block(object : RuntimeQueueTransaction by tx {
                            override fun readReplayRow(key: String): RuntimeReplayStoredRow? {
                                val result = tx.readReplayRow(key)
                                if (key == "state" && profileChecks > 0) onReplayStateRead?.also { onReplayStateRead = null }?.invoke()
                                return result
                            }
                            override fun putReplayRow(row: RuntimeReplayStoredRow) {
                                tx.putReplayRow(row); wroteReplay = true
                                onReplayWrite?.also { onReplayWrite = null }?.invoke()
                            }
                        }) }
                        if (wroteReplay) afterTransaction?.also { afterTransaction = null }?.invoke()
                        return result
                    }
                }
            }, legacyStateLoader = { initial(maximumExpiredSession) }, trustedSiteKey = KEY,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = ownerWall ?: clock.wall
                override fun elapsedRealtimeNanos() = clock.nanos
            },
            // Synthetic existing browser fixture pair: storage tests only, no native proof claim.
            readbackProvenReplayTransports = if (proven) setOf(PAIR) else emptySet(),
            supportedReplayProtocolGenerations = supported,
            replayMaskingAdmission = ReplayMaskingAdmission { profile, config ->
                profileChecks++
                allowProfile && profileCurrent && profile.hash == ReplayFixtures.profile().hash &&
                    config.privacy.masking == V1ConfigJson.parseConfig(ReplayFixtures.resource("contracts/v2/fixtures/config-enabled.json")).privacy?.masking
            },
        ).get().also { it.bindConfigurationGate(gate).get() }
        fun activate() {
            owner.ensurePreparedReplayStorage().get(); driver.start(); worker.runNext()
            assertTrue(owner.submitCaptureAuthority(body, privacy()).get() is RuntimeCaptureAuthorityUpdateResult.Activated)
            reconcile()
        }
        fun privacy(): String {
            val config = V1ConfigJson.parseConfig(body)
            return PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(config.privacy),
                checkNotNull(config.features), checkNotNull(config.replayCapabilities), owner.snapshot().get().state.identity,
                false, "2026-08-05T00:01:06.000Z", PrivacyReplayInput(true, true, true, 100, PAIR))))
        }
        fun request(change: (JSONObject) -> Unit = {}): PreparedReplayRequest {
            val privacy = JSONObject(privacy())
            return PreparedReplayRequest.parse(ReplayFixtures.bytes {
                it.getJSONObject("privacy").put("effectivePolicyHash", privacy.getString("effectivePolicyHash"))
                    .put("platformFallbackApplied", privacy.getJSONObject("effectiveMasking").getBoolean("platformFallbackApplied"))
                change(it)
            }, checkNotNull(V1ConfigJson.parseConfig(body).replayCapabilities?.replayProtocolGeneration))
        }
        fun append(request: PreparedReplayRequest = request()) = owner.appendPreparedReplay(request, ReplayFixtures.profile(), privacy()).get()
        fun rows() = owner.storedPreparedReplayForTesting().get()
        fun state() = backing.connection().use { it.transaction { tx -> checkNotNull(ReplayQueueStore.state(tx)) } }
        fun reconcile(retention: ReplayMaskingRetention = ReplayMaskingRetention { _, _ -> true }) = owner.reconcilePreparedReplay(privacy(), retention).get()
        fun renew(change: (JSONObject) -> Unit) {
            val json = JSONObject(body); json.put("issuedAt", "2026-08-05T00:01:05.000Z"); json.put("revision", "config-next")
            change(json); body = json.toString(); driver.onBackground(); driver.onForeground(); worker.runNext()
            owner.submitCaptureAuthority(body, privacy()).get()
        }
        fun reopen() { owner.closeAsync().get(); owner = open() }
        fun event() = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "event", "2026-08-05T00:01:06.000Z", emptyMap(), StandaloneRuntime.defaultVersions())
        override fun close() { runCatching { owner.closeAsync().get() }; driver.close() }
    }
    private class Clock : V2ConfigClock {
        var wall = Instant.parse("2026-08-05T00:01:06Z").toEpochMilli(); var nanos = 1L
        override fun wallNowEpochMillis() = wall
        override fun monotonicNowNanos() = nanos
    }
    private class Worker : V2ConfigLifecycleWorker {
        val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.add(task) }
        override fun interruptCurrent() = Unit
        override fun close() = Unit
        fun runNext() = tasks.removeFirst().invoke()
    }
    private class Scheduler : V2ConfigLifecycleScheduler {
        override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
        override fun close() = Unit
    }
    private companion object {
        val KEY = "elu_pk_live_" + "A".repeat(26)
        val PAIR = V1ReplayTransport("elu-browser-dom-v1", V1ReplayCompression.GZIP)
        fun initial(maximumExpiredSession: Boolean = false): PersistedCoreState {
            val chunk = ReplayFixtures.request().getJSONObject("chunk"); val identity = chunk.getJSONObject("identity")
            return PersistedCoreState(identity = IdentityState(revision = identity.getLong("revision"), contextRevision = chunk.getLong("contextRevision"),
                anonymousId = identity.getString("anonymousId"), userId = identity.getString("userId"), groups = emptyMap(), superProperties = emptyMap(),
                session = SessionState(chunk.getString("sessionId"), if (maximumExpiredSession) "2026-08-04T00:01:05.001Z" else "2026-08-05T00:01:00.000Z", "2026-08-05T00:01:05.000Z", 1800,
                    lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null), optedOut = false, updatedAt = "2026-08-05T00:01:05.000Z"),
                stream = StreamState(streamId = "stream_replay", nextSequence = 0), flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
        }
    }
}
