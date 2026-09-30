package dev.elu.analytics.internal.replay

import android.view.View
import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import dev.elu.analytics.EluReplayPrivateRegion
import dev.elu.analytics.compose.EluAnnotatedReplayRoot
import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.flags.FlagDurableStore
import dev.elu.analytics.internal.runtime.*
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real original collector, physical enrollment and SQLite. Authored; device execution remains separate. */
@SdkSuppress(minSdkVersion = 29)
class AndroidNativeRasterDeliveryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var host: View
    private lateinit var registry: AnnotatedRootRegistry
    private lateinit var collector: AndroidAnnotatedReplayCollector
    private var frameClock = 1_000_000_000L
    private var wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
    private val key = "elu_pk_live_AAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val namespace = RuntimeSiteNamespace.digest(key)
    private fun install() {
        rule.setContent { EluAnnotatedReplayRoot(emptyList(), Modifier.size(64.dp).background(Color.Red)) {
            val view = LocalView.current; SideEffect { host = view }; Box(Modifier.size(16.dp).background(Color.Blue))
        } }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.runOnIdle { host.isAttachedToWindow && host.hasWindowFocus() && host.isLaidOut && !host.isLayoutRequested } }
        rule.runOnIdle { registry = checkNotNull(AnnotatedRootRegistry.fromHost(host)); collector = AndroidAnnotatedReplayCollector(registry) }
    }
    private fun frame(current: () -> Boolean) = rule.runOnIdle {
        frameClock += 1_000_000_000L; val time = frameClock
        collector.capture(rule.activity.window, current) { time }
    }
    private fun initial() = PersistedCoreState(identity = IdentityState(revision = 1, contextRevision = 1,
        anonymousId = "raster-anon", userId = null, groups = emptyMap(), superProperties = emptyMap(),
        session = SessionState("raster-session", "2026-08-05T00:01:00Z", "2026-08-05T00:01:00Z", 1800,
            lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null), optedOut = false, updatedAt = "2026-08-05T00:01:00Z"),
        stream = StreamState(streamId = "raster-stream", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))


    @Test fun actualFrameClaimAndExactAckUseOriginalStoredBytesAndEndpoint() {
        install(); val dir = directory()
        try { Rig(dir).use { h ->
            val first = h.request(); val second = h.request(); h.stop()
            val claim = checkNotNull(h.queue.claim())
            assertEquals(ReplayDeliveryFormat.RASTER, claim.format)
            assertEquals("https://ingest.elu.dev/v3/replay", claim.authorization.endpoint.toString())
            assertArrayEquals(first.copyBytes(), claim.copyBody())
            val op = Op(); var io: (() -> Boolean)? = null
            val physical = checkNotNull(h.queue.dispatch(claim, ReplayDeliveryTransport { same, guard -> assertSame(claim, same); io = guard; op }))
            assertTrue(checkNotNull(io).invoke()); assertFalse(checkNotNull(io).invoke())
            op.result.complete(ack(claim.rasterRow.request)); physical.settlement.get(3, TimeUnit.SECONDS)
            assertEquals(ReplayDeliveryCommit.COMMITTED, h.queue.commit(claim, claim.classify(ack(claim.rasterRow.request), wall, 1)))
            assertArrayEquals(second.copyBytes(), h.owner.storedNativeRasterForTesting().get().single().request.copyBytes())
        } } finally { dir.deleteRecursively() }
    }

    @Test fun actualLostAckReopenRetriesOriginalPrefixAfterUnknownAttemptDelay() {
        install(); val dir = directory(); lateinit var original: ByteArray
        try {
            Rig(dir).use { h -> original = h.request().copyBytes(); h.request(); h.stop(); assertNotNull(h.queue.claim()) }
            Rig(dir, start = false).use { h ->
                assertNull(h.queue.claim()); assertEquals(30_000L, h.queue.nextWakeDelayMillis())
                h.advance(30_000)
                val retried = checkNotNull(h.queue.claim()); assertArrayEquals(original, retried.copyBody())
                assertEquals(2, retried.attemptCount)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun actualPermanentRefusalBlocksRetainedSuffixAndAlreadySealedAppend() {
        install(); val dir = directory()
        try { Rig(dir).use { h ->
            h.request(); h.request(); val notAppended = h.request(append = false)
            val originals = h.owner.storedNativeRasterForTesting().get().map { it.request.copyBytes() }
            val claim = checkNotNull(h.queue.claim())
            val blocked = claim.classify(conflict(claim.rasterRow.request), wall, 1)
            assertEquals(ReplayDeliveryOutcome.Blocked(ReplayBlockKind.RASTER_SEQUENCE), blocked)
            assertEquals(ReplayDeliveryCommit.COMMITTED, h.queue.commit(claim, blocked))
            assertTrue(h.owner.appendNativeRaster(notAppended, checkNotNull(h.admission), checkNotNull(h.use)).get() is NativeReplayAppendOutcome.Rejected)
            assertNull(h.queue.claim())
            val retained = h.owner.storedNativeRasterForTesting().get(); assertEquals(2, retained.size)
            originals.zip(retained).forEach { (bytes, row) -> assertArrayEquals(bytes, row.request.copyBytes()) }
        }
            Rig(dir, start = false).use { assertNull(it.queue.claim()); assertEquals(2, it.owner.storedNativeRasterForTesting().get().size) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun wholeEpochExpiryWaitsForOriginalCaptureAccountingAndPhysicalUse() {
        install(); val dir = directory()
        try { Rig(dir).use { h ->
            h.request(); h.request(); h.advance(REPLAY_RETENTION_SECONDS * 1000 - 1000)
            assertEquals(0, h.owner.expirePreparedReplay().get())
            assertEquals(2, h.owner.storedNativeRasterForTesting().get().size)
            assertFalse(checkNotNull(h.use).isCurrent())
            h.stop()
            assertTrue(h.owner.storedNativeRasterForTesting().get().isEmpty())
        } } finally { dir.deleteRecursively() }
    }

    @Test fun optInCannotRevivePendingRowsBeforeOriginalCaptureCleanup() {
        install(); val dir = directory()
        try { Rig(dir).use { h ->
            h.request(); h.request()
            val now = RuntimeWallTimestamps.rfc3339(wall)
            h.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, now)).get()
            assertEquals(2, h.owner.storedNativeRasterForTesting().get().size)
            h.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, now)).get()
            assertNull(h.queue.claim()); assertNull(h.queue.nextWakeDelayMillis())
            h.stop(); assertTrue(h.owner.storedNativeRasterForTesting().get().isEmpty())
        } } finally { dir.deleteRecursively() }
    }

    @Test fun actualAckCommitAndRollbackReconcileWithoutDeletingAnotherOriginalRow() {
        install()
        for (committed in listOf(false, true)) {
            val dir = directory()
            try { Rig(dir).use { h ->
                h.request(); val second = h.request(); h.stop(); val claim = checkNotNull(h.queue.claim())
                h.ackFault = committed
                assertEquals(ReplayDeliveryCommit.COMMITTED, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
                assertArrayEquals(second.copyBytes(), h.owner.storedNativeRasterForTesting().get().single().request.copyBytes())
                assertNull(h.ackFault)
            } } finally { dir.deleteRecursively() }
        }
    }

    @Test fun originalSourceWithdrawalRefusesAckAndLateOriginalConflictSurvivesCloseJoin() {
        install(); val dir = directory()
        try { Rig(dir).use { h ->
            h.request(); h.stop(); val claim = checkNotNull(h.queue.claim()); val op = Op()
            val original = checkNotNull(h.queue.dispatch(claim, ReplayDeliveryTransport { _, _ -> op }))
            h.driver.onBackground()
            assertEquals(ReplayDeliveryCommit.STALE, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
            val close = h.beginClose(); assertFalse(close.isDone)
            op.result.complete(conflict(claim.rasterRow.request)); original.settlement.get(3, TimeUnit.SECONDS)
            close.get(3, TimeUnit.SECONDS)
        }
            Rig(dir, start = false).use { assertNull(it.queue.claim()); assertEquals(1, it.owner.storedNativeRasterForTesting().get().size) }
        } finally { dir.deleteRecursively() }
    }

    private fun directory() = File(rule.activity.cacheDir, "raster-delivery-" + UUID.randomUUID())
    private class Op : ReplayTransportOperation {
        val result = dev.elu.analytics.internal.concurrent.SdkFuture<ReplayTransportResponse>()
        override val settlement get() = result
        override fun cancel() = Unit
    }
    private fun ack(r: NativeRasterStoredRequest) = ReplayTransportResponse(200, JSONObject().put("schemaVersion", 3)
        .put("requestId", r.requestId).put("replayId", r.replayId).put("chunkId", r.chunkId).put("sequence", r.sequence)
        .put("result", "accepted").toString().toByteArray())
    private fun conflict(r: NativeRasterStoredRequest) = ReplayTransportResponse(409, JSONObject().put("schemaVersion", 3)
        .put("requestId", r.requestId).put("status", 409).put("code", "replay-identity-conflict")
        .put("disposition", "permanent").put("conflictScope", "sequence").toString().toByteArray())

    private inner class Rig(dir: File, start: Boolean = true) : AutoCloseable {
        private val file = File(dir, "queue.sqlite")
        private val tasks = ArrayDeque<() -> Unit>()
        private val clock = object : V2ConfigClock {
            override fun wallNowEpochMillis() = wall
            override fun monotonicNowNanos() = frameClock
        }
        val gate = V2ConfigAuthorityGate()
        private val source = V2ConfigSource("https://elu.dev", key, V2ConfigTransport { V2ConfigHttpResponse(200, fixture().toString()) },
            clock, format = V2ConfigFormat.NATIVE_V3)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, object : V2ConfigLifecycleScheduler {
            override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
            override fun close() = Unit
        }, object : V2ConfigLifecycleWorker {
            override fun execute(task: () -> Unit) { tasks.add(task) }
            override fun interruptCurrent() = Unit
            override fun close() = Unit
        })
        @Volatile var ackFault: Boolean? = null
        private var deleting = false
        private val faults = object : AndroidRuntimeDatabaseFaults {
            override fun beforeCommit() { if (deleting && ackFault == false) { deleting = false; ackFault = null; error("ACK rollback") } }
            override fun afterCommit() { if (deleting && ackFault == true) { deleting = false; ackFault = null; error("ACK commit response lost") }; deleting = false }
        }
        val owner = RuntimeQueueOwner.open("raster-delivery-" + UUID.randomUUID(), RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
            databaseFactory = {
                val db = AndroidSQLiteRuntimeDatabase.open(file, faults)
                object : RuntimeQueueDatabase by db {
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = db.transaction { tx ->
                        block(object : RuntimeQueueTransaction by tx {
                            override fun deleteReplayRow(key: String): Boolean = tx.deleteReplayRow(key).also { if (key.startsWith("chunk/")) deleting = true }
                        })
                    }
                }
            }, legacyStateLoader = { initial() }, trustedSiteKey = key,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = wall
                override fun elapsedRealtimeNanos() = frameClock
            }).get()
        private val lifecycle = NativeReplayLifecycle()
        private var selection: NativeReplaySelection? = null
        private val authority = NativeReplayAuthority(owner, NativeReplayCapabilities(rasterSupported = true), deviceInEuTimezone = { false })
        private var enrollment: NativeReplayCaptureEnrollment? = null
        var use: NativeReplayCapturePhysicalUse? = null
        var admission: NativeRasterCaptureAdmission? = null
        private var sealer: NativeRasterSealer? = null
        private var stopped = false
        private var closing: java.util.concurrent.Future<Unit>? = null
        val queue: ReplayDeliveryQueue
        init {
            owner.bindConfigurationGate(gate).get(); driver.start(); tasks.removeFirst().invoke()
            val base = checkNotNull(gate.snapshot()?.body); val parsed = V1ConfigJson.parseConfig(base)
            val privacy = PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(parsed.privacy),
                checkNotNull(parsed.features), checkNotNull(parsed.replayCapabilities), owner.snapshot().get().state.identity,
                false, RuntimeWallTimestamps.rfc3339(wall))))
            assertTrue(owner.submitCaptureAuthority(base, privacy).get() is RuntimeCaptureAuthorityUpdateResult.Activated)
            if (start) {
                lifecycle.resumed(rule.activity); selection = checkNotNull(lifecycle.select(rule.activity, host).get())
                val seed = frame { true }; val token = seed.sourceIdentity; seed.close()
                val prepared = checkNotNull(authority.prepareRaster(checkNotNull(selection), token).get())
                enrollment = checkNotNull(owner.enrollNativeReplayCapture().get()); use = checkNotNull(enrollment!!.takePhysicalUse())
                val permit = checkNotNull(authority.startRaster(prepared, checkNotNull(use)).get())
                admission = checkNotNull(authority.captureAdmission(permit, checkNotNull(use)).get())
                sealer = NativeRasterSealer(permit.replayId, permit.identity, permit.sealingPolicy(), StandaloneRuntime.defaultVersions(), token, checkNotNull(admission)::isCurrent)
            }
            queue = owner.openReplayDeliveryQueue(ReplayDeliveryPolicy(ReplayDeliveryPrivacy { config, identity, now ->
                PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(config.privacy),
                    checkNotNull(config.features), checkNotNull(config.replayCapabilities), identity, false, RuntimeWallTimestamps.rfc3339(now))))
            }, ReplayMaskingRetention { _, _ -> true }, ReplayDeliverySupport.INCLUDING_RASTER)).get()
        }
        fun advance(millis: Long) { wall += millis; frameClock += millis * 1_000_000 }
        fun request(append: Boolean = true): NativeRasterPreparedRequest {
            wall += 1000
            val request = checkNotNull(sealer).seal(frame(checkNotNull(admission)::isCurrent), wall)
            if (append) assertTrue(owner.appendNativeRaster(request, checkNotNull(admission), checkNotNull(use)).get() is NativeReplayAppendOutcome.Committed)
            return request
        }
        fun stop() {
            if (stopped) return
            use?.settle()
            assertEquals(NativeReplayAuthorityStop.SETTLED, authority.stop().get(3, TimeUnit.SECONDS))
            enrollment?.let { assertEquals(NativeReplayCaptureFinish.SETTLED, owner.finishNativeReplayCapture(it).get()) }
            use = null; enrollment = null; stopped = true
        }
        fun beginClose(): java.util.concurrent.Future<Unit> = closing ?: owner.closeAsync().also { closing = it }
        override fun close() { stop(); authority.close(); selection?.close(); driver.close(); beginClose().get(3, TimeUnit.SECONDS) }
    }
    private fun fixture(): JSONObject {
        val base = JSONObject(BASE)
        base.getJSONObject("privacy").getJSONObject("replay").put("sampleRate", 1)
        base.getJSONObject("privacy").getJSONObject("masking").put("images", "allow")
        base.getJSONObject("capabilities").getJSONObject("replay").put("replayProtocolGeneration", "protocol-generation-v2")
            .put("transports", JSONArray().put(JSONObject().put("codec", "elu-native-wireframe-v2").put("compression", "gzip")))
        val privacy = base.getJSONObject("privacy")
        val limits = JSONObject().put("requestBytes", 5_242_880).put("decodedPayloadBytes", 2_800_000).put("pngBytes", 2_097_152)
            .put("imageEdgePixels", 2_048).put("imagePixels", 1_048_576).put("viewportEdge", 16_384)
            .put("minimumFrameIntervalSeconds", 1).put("framesPerChunk", 1)
        fun declarations() = JSONObject().put("declaredRegionsAllowed", true).put("inputCoverage", "declared-regions")
            .put("automaticInputDiscovery", false).put("unknownContentClassification", false).put("redactionBoundary", "before-encoding")
            .put("requiredBindingBehavior", "deny-incomplete-or-stale").put("maskingProfileHash", NativeV3ConfigParser.PROFILE_HASH)
        val material = declarations().put("schemaVersion", 1).put("policyRevision", privacy.getString("revision"))
            .put("basePolicyRevision", privacy.getString("revision")).put("basePrivacy", privacy).put("replayAudience", "all-devices").put("limits", limits)
        val hash = V1StrictCanonicalJson.sha256("elu-native-raster-effective-policy-v1\u0000".toByteArray() +
            V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(material.toString())))
        val raster = JSONObject().put("schemaVersion", 1).put("endpoint", "https://ingest.elu.dev/v3/replay")
            .put("replayContractVersion", "3.0.0").put("replaySchemaVersion", 3).put("ackSchemaVersion", 3)
            .put("replayProtocolGeneration", "native-raster-generation-v1").put("codec", "elu-native-raster-v1").put("compression", "gzip")
            .put("platforms", JSONArray().put("android").put("ios"))
            .put("privacy", declarations().put("schemaVersion", 1).put("revision", privacy.getString("revision")).put("effectivePolicyHash", hash))
            .put("limits", limits)
        return JSONObject().put("schemaVersion", 3).put("configV2", base).put("raster", raster)
    }
    companion object { private val BASE = """{
    "schemaVersion": 2,
    "revision": "config-v2-2026-08-05-1",
    "issuedAt": "2026-08-05T00:00:00.000Z",
    "expiresAt": "2026-08-05T00:05:00.000Z",
    "status": "enabled",
    "site": {
        "id": "site_demo"
    },
    "endpoints": {
        "events": "https://ingest.elu.dev/v1/events",
        "replay": "https://ingest.elu.dev/v2/replay",
        "flags": "https://ingest.elu.dev/v1/flags",
        "assets": "https://assets.elu.dev/sdk/"
    },
    "privacy": {
        "schemaVersion": 1,
        "revision": "privacy-1",
        "capture": {
            "enabled": true
        },
        "replay": {
            "enabled": true,
            "sampleRate": 0.25,
            "minimumDurationSeconds": 2,
            "maximumDurationSeconds": 3600
        },
        "masking": {
            "text": "sensitive",
            "inputs": "all",
            "images": "block",
            "secureInputsMasked": true,
            "platformRules": [
                {
                    "platform": "browser",
                    "action": "mask",
                    "targetDialect": "elu-css-selector-v1",
                    "target": ".elu-mask"
                },
                {
                    "platform": "browser",
                    "action": "block",
                    "targetDialect": "elu-css-selector-v1",
                    "target": ".elu-block"
                }
            ]
        },
        "regionPolicy": {
            "mode": "block-eu-on-device",
            "evaluator": "elu-eu-timezone-v1"
        }
    },
    "features": {
        "capture": true,
        "replay": true,
        "flags": true,
        "assets": true
    },
    "capabilities": {
        "events": {
            "contractVersion": "1.0.0",
            "schemaVersion": 1
        },
        "mutations": {
            "contractVersion": "1.0.0",
            "schemaVersion": 1
        },
        "flags": {
            "contractVersion": "1.0.0",
            "schemaVersion": 1
        },
        "replay": {
            "replayContractVersion": "2.0.0",
            "replaySchemaVersion": 2,
            "replayProtocolGeneration": "replay-v2-generation-1",
            "transports": [
                {
                    "codec": "elu-browser-dom-v1",
                    "compression": "gzip"
                }
            ]
        }
    },
    "session": {
        "idleTimeoutSeconds": 1800,
        "maximumDurationSeconds": 86400
    },
    "limits": {
        "eventBatchCount": 100,
        "eventBatchBytes": 1048576,
        "replayChunkBytes": 5242880,
        "queueBytes": 16777216
    }
}""" }
}
