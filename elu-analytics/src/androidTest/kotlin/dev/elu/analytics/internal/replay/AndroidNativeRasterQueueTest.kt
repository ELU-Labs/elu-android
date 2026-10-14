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
class AndroidNativeRasterQueueTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var host: View
    private lateinit var registry: AnnotatedRootRegistry
    private lateinit var collector: AndroidAnnotatedReplayCollector
    private var frameClock = 1_000_000_000L
    private val wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
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

    @Test fun actualOriginalCollectorAppendDuplicateAndReopenRetainExactBytes() = originalFlow(false)
    @Test fun newRequiredIntentAfterSealRefusesAppendWithoutRetainingPixels() = originalFlow(true)

    @Test fun actualAppendLostCommitResultReopensOriginalBytesWithoutNewFrame() = originalFlow(false, true)
    @Test fun actualAppendRollbackRetriesOriginalBytesWithoutNewFrame() = originalFlow(false, false)
    @Test fun originalSourceWithdrawalAfterKnownCommitReportsRetainedOriginalBytes() = originalFlow(false, withdrawAtCommit = true)
    @Test fun equivalentWrapperReplacementCannotAdoptAnAlreadySealedFrame() = originalFlow(false, beforeAppend = BeforeAppend.REPLACE)
    @Test fun originalSourceBackgroundWithdrawalDeniesSealedFrame() = originalFlow(false, beforeAppend = BeforeAppend.BACKGROUND)
    @Test fun originalIdentityContextChangeDeniesSealedFrame() = originalFlow(false, beforeAppend = BeforeAppend.CONTEXT)

    private enum class BeforeAppend { REPLACE, BACKGROUND, CONTEXT }
    private fun originalFlow(changeIntent: Boolean, lostCommit: Boolean? = null, withdrawAtCommit: Boolean = false,
        beforeAppend: BeforeAppend? = null) {
        install()
        val dir = File(rule.activity.cacheDir, "raster-owner-" + UUID.randomUUID()); val file = File(dir, "queue.sqlite")
        val tasks = ArrayDeque<() -> Unit>()
        val clock = object : V2ConfigClock { override fun wallNowEpochMillis() = wall; override fun monotonicNowNanos() = 1L }
        var wire = fixture().toString(); val gate = V2ConfigAuthorityGate()
        val source = V2ConfigSource("https://elu.dev", key, V2ConfigTransport { V2ConfigHttpResponse(200, wire) }, clock,
            format = V2ConfigFormat.NATIVE_V3)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, object : V2ConfigLifecycleScheduler {
            override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
            override fun close() = Unit
        }, object : V2ConfigLifecycleWorker {
            override fun execute(task: () -> Unit) { tasks.add(task) }; override fun interruptCurrent() = Unit; override fun close() = Unit
        })
        var armed = false
        val originalSourceCurrent = AtomicBoolean(true)
        val faults = object : AndroidRuntimeDatabaseFaults {
            override fun beforeCommit() { if (armed && lostCommit == false) { armed = false; error("original append rollback") } }
            override fun afterCommit() {
                if (armed && withdrawAtCommit) { armed = false; originalSourceCurrent.set(false) }
                if (armed && lostCommit == true) { armed = false; error("original append lost response") }
            }
        }
        val owner = RuntimeQueueOwner.open("raster-owned-" + UUID.randomUUID(), RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
            databaseFactory = { AndroidSQLiteRuntimeDatabase.open(file, faults) }, legacyStateLoader = { initial() }, trustedSiteKey = key,
            captureClock = object : RuntimeCaptureClock { override fun wallNowEpochMillis() = wall; override fun elapsedRealtimeNanos() = 1L }).get()
        var original: ByteArray? = null
        try {
            owner.bindConfigurationGate(gate).get(); driver.start(); tasks.removeFirst().invoke()
            val base = checkNotNull(gate.snapshot()?.body); val parsed = V1ConfigJson.parseConfig(base)
            val privacy = PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(parsed.privacy),
                checkNotNull(parsed.features), checkNotNull(parsed.replayCapabilities), owner.snapshot().get().state.identity,
                false, RuntimeWallTimestamps.rfc3339(wall))))
            assertTrue(owner.submitCaptureAuthority(base, privacy).get() is RuntimeCaptureAuthorityUpdateResult.Activated)
            val lifecycle = NativeReplayLifecycle(); lifecycle.resumed(rule.activity)
            val selection = checkNotNull(lifecycle.select(rule.activity, host).get())
            val authority = NativeReplayAuthority(owner, NativeReplayCapabilities(rasterSupported = true), deviceInEuTimezone = { false })
            var use: NativeReplayCapturePhysicalUse? = null; var enrollment: NativeReplayCaptureEnrollment? = null
            try {
                val seed = frame { true }; val token = seed.sourceIdentity; seed.close()
                val prepared = checkNotNull(authority.prepareRaster(selection, token).get())
                enrollment = checkNotNull(owner.enrollNativeReplayCapture().get()); use = checkNotNull(enrollment.takePhysicalUse())
                val permit = checkNotNull(authority.startRaster(prepared, use).get())
                val admission = checkNotNull(authority.captureAdmission(permit, use).get())
                val candidate = frame(admission::isCurrent)
                val request = NativeRasterSealer(permit.replayId, permit.identity, permit.sealingPolicy(),
                    StandaloneRuntime.defaultVersions(), token, { admission.isCurrent() && originalSourceCurrent.get() }).seal(candidate, wall)
                assertThrows(IllegalStateException::class.java) { candidate.encodePng() }
                if (changeIntent) rule.runOnIdle { registry.declare(listOf(EluReplayPrivateRegion.create())) }
                when (beforeAppend) {
                    BeforeAppend.REPLACE -> { wire += " "; driver.refresh(); tasks.removeFirst().invoke() }
                    BeforeAppend.BACKGROUND -> driver.onBackground()
                    BeforeAppend.CONTEXT -> owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(
                        mapOf("plan" to "changed"), "2026-08-05T00:01:00Z")).get()
                    null -> Unit
                }
                armed = lostCommit != null || withdrawAtCommit
                val outcome = owner.appendNativeRaster(request, admission, use).get()
                if (changeIntent || beforeAppend != null) {
                    assertEquals(NativeReplayAppendOutcome.Rejected(ReplayAppendRejection.AUTHORITY), outcome)
                    assertTrue(owner.storedNativeRasterForTesting().get().isEmpty())
                } else {
                    if (withdrawAtCommit) {
                        assertTrue(outcome is NativeReplayAppendOutcome.CommittedThenWithdrawn)
                        assertEquals(NativeReplayAppendOutcome.Rejected(ReplayAppendRejection.AUTHORITY),
                            owner.appendNativeRaster(request, admission, use).get())
                    } else {
                        assertTrue(outcome is NativeReplayAppendOutcome.Committed)
                        assertTrue((owner.appendNativeRaster(request, admission, use).get() as NativeReplayAppendOutcome.Committed).stored.duplicate)
                    }
                    original = request.copyBytes()
                    assertArrayEquals(original, owner.storedNativeRasterForTesting().get().single().request.copyBytes())
                    assertTrue(owner.storedPreparedReplayForTesting().get().isEmpty())
                }
            } finally {
                use?.settle(); assertEquals(NativeReplayAuthorityStop.SETTLED, authority.stop().get(3, TimeUnit.SECONDS))
                enrollment?.let { assertEquals(NativeReplayCaptureFinish.SETTLED, owner.finishNativeReplayCapture(it).get()) }
                authority.close(); selection.close()
            }
        } finally { driver.close(); owner.closeAsync().get(3, TimeUnit.SECONDS) }
        try { AndroidSQLiteRuntimeDatabase.open(file).use { db -> db.transaction { tx ->
            ReplayQueueStore.validate(tx, namespace)
            val rows = ReplayQueueStore.headers(tx)
            if (changeIntent || beforeAppend != null) assertTrue(rows.isEmpty()) else assertArrayEquals(original,
                ReplayQueueStore.readRaster(tx, rows.single()).request.copyBytes())
            assertNull(NativeReplayAccounting.read(checkNotNull(tx.readReplayRow(NativeReplayAccounting.KEY))).session?.activeEpoch)
        } } } finally { dir.deleteRecursively() }
    }

    @Test fun rasterMarkerAndStateCommitTogetherAcrossLostCommitAndRollback() {
        for (committed in listOf(false, true)) {
            val dir = File(rule.activity.cacheDir, "raster-migration-" + UUID.randomUUID()); val file = File(dir, "queue.sqlite")
            var armed = false
            val fault = object : AndroidRuntimeDatabaseFaults {
                override fun beforeCommit() { if (armed && !committed) { armed = false; error("rollback") } }
                override fun afterCommit() { if (armed && committed) { armed = false; error("lost response") } }
            }
            try {
                AndroidSQLiteRuntimeDatabase.open(file, fault).use { db ->
                    db.transaction { it.insertCore(RuntimeStoredCore(CoreStateCodec.encode(initial()), 0, 0)) }
                    db.ensureReplaySchema(ReplayStoredState(namespace).row())
                    db.ensureNativeReplaySchema(NativeReplayAccounting.row(NativeReplaySessionState(namespace, "raster-stream")))
                    armed = true; assertThrows(Exception::class.java) { db.ensureNativeRasterReplaySchema() }
                }
                AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                    db.transaction { tx -> assertEquals(committed, tx.nativeRasterReplaySchemaPresent())
                        assertEquals(committed, ReplayStoredState.decode(checkNotNull(tx.readReplayRow("state"))).rasterStorage) }
                    db.ensureNativeRasterReplaySchema(); db.ensureNativeRasterReplaySchema()
                    db.transaction { tx -> ReplayQueueStore.validate(tx, namespace); assertTrue(tx.nativeRasterReplaySchemaPresent()) }
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun allFourteenNativeFamiliesMigrateAndLaterFeaturesPreserveOriginalRows() {
        for (offset in listOf(0, 6, 24, 30, 36, 42, 48)) for (flagsBefore in listOf(false, true)) {
            val dir = File(rule.activity.cacheDir, "raster-family-" + UUID.randomUUID())
            val file = File(dir, "queue.sqlite")
            val coreBytes = CoreStateCodec.encode(initial())
            val accounting = NativeReplayAccounting.row(NativeReplaySessionState(namespace, "raster-stream"))
            val flags = FlagDurableStore.uninitializedAuthorityRow(key, namespace)
            val originalBase = if (flagsBefore) 6 else 5
            try {
                AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                    db.transaction { it.insertCore(RuntimeStoredCore(coreBytes, 0, 0)) }
                    db.ensureReplaySchema(ReplayStoredState(namespace).row())
                    db.ensureNativeReplaySchema(accounting)
                    if (flagsBefore) db.ensureFlagSchema(flags)
                    if (offset == 24) db.ensureDiagnosticsSchema()
                    if (offset >= 30) db.ensurePersonSchema()
                    if (offset >= 36) db.ensureExposureSchema()
                    if (offset >= 42) db.ensureCaptureRateSchema()
                    if (offset >= 48) db.ensureExceptionSchema()
                }
                // The old no-audience family is a real supported historical schema, not a new marker hole.
                if (offset == 0) SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    db.execSQL("DROP TABLE replay_audience")
                    db.rawQuery("PRAGMA user_version = $originalBase", null).use { cursor -> while (cursor.moveToNext()) { } }
                }
                AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                    db.ensureNativeRasterReplaySchema()
                    db.transaction { tx ->
                        assertTrue(tx.nativeRasterReplaySchemaPresent())
                        assertArrayEquals(coreBytes, tx.readCore()!!.stateJson)
                        assertArrayEquals(accounting.payload, tx.readReplayRow(NativeReplayAccounting.KEY)!!.payload)
                        ReplayQueueStore.validate(tx, namespace)
                    }
                }
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    assertEquals(originalBase + offset + 128, db.version)
                }
                AndroidSQLiteRuntimeDatabase.open(file).use { db ->
                    if (!flagsBefore) db.ensureFlagSchema(flags)
                    db.ensureReplayAudienceSchema(); db.ensureDiagnosticsSchema(); db.ensurePersonSchema()
                    db.ensureExposureSchema(); db.ensureCaptureRateSchema(); db.ensureExceptionSchema()
                    db.ensureNativeRasterReplaySchema()
                    db.transaction { tx ->
                        ReplayQueueStore.validate(tx, namespace)
                        assertArrayEquals(coreBytes, tx.readCore()!!.stateJson)
                        assertArrayEquals(accounting.payload, tx.readReplayRow(NativeReplayAccounting.KEY)!!.payload)
                        assertArrayEquals(flags.payload, tx.readFlagRow(flags.key)!!.payload)
                    }
                }
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { assertEquals(182, it.version) }
            } finally { dir.deleteRecursively() }
        }
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
