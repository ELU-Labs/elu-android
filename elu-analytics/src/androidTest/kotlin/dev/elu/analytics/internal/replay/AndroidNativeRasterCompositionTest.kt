package dev.elu.analytics.internal.replay

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import dev.elu.analytics.compose.EluAnnotatedReplayRoot
import dev.elu.analytics.internal.concurrent.SdkFuture
import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.facade.AndroidStandaloneStack
import dev.elu.analytics.internal.runtime.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real original root, collector and SQLite. Controlled clock tests ordering, not device latency. */
@SdkSuppress(minSdkVersion = 29)
class AndroidNativeRasterCompositionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var host: View
    private val key = "elu_pk_live_AAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val origin = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
    private fun install() {
        rule.setContent { EluAnnotatedReplayRoot(emptyList(), Modifier.size(64.dp).background(Color.Red)) {
            val view = LocalView.current; SideEffect { host = view }; Box(Modifier.size(16.dp).background(Color.Blue))
        } }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.runOnIdle { host.isAttachedToWindow && host.hasWindowFocus() && host.isLaidOut && !host.isLayoutRequested } }
    }
    private fun until(action: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!action()) { check(System.nanoTime() < deadline) { "original composition condition" }; Thread.sleep(10) }
    }

    @Test fun realSamplesMeetMinimumBeforeOriginalPrefixAndSameOwnerAck() {
        install(); Rig().use { rig ->
            assertEquals(NativeReplayCompositionEvaluation.ACTIVE, rig.composition.reevaluate().get(5, TimeUnit.SECONDS))
            until { rig.waits.get() == 1 }
            assertTrue(rig.rows().isEmpty())
            rig.next(); until { rig.waits.get() == 2 }; assertTrue(rig.rows().isEmpty())
            rig.next(); until { rig.waits.get() == 3 }
            val original = rig.rows(); assertEquals(listOf(0L, 1L), original.map { it.request.sequence })
            assertEquals(listOf(origin, origin + 2_000), original.map { it.request.timestamp })
            val bodies = original.map { it.request.copyBytes() }
            rig.ack.set(true); rig.now.addAndGet(60_000)
            rig.composition.flushSealed().get(5, TimeUnit.SECONDS)
            until { rig.rows().isEmpty() }
            assertTrue(rig.sent.any { it.contentEquals(bodies[0]) })
            assertTrue(rig.sent.any { it.contentEquals(bodies[1]) })
            assertTrue(rig.owner.storedPreparedReplayForTesting().get().isEmpty())
        }
    }

    @Test fun stopBeforeMinimumDoesNotManufactureAnElapsedPrefix() {
        install(); Rig().use { rig ->
            rig.composition.reevaluate().get(5, TimeUnit.SECONDS); until { rig.waits.get() == 1 }
            rig.now.addAndGet(60_000) // Time alone, no new original frame.
            rig.composition.closeAndWait().get(5, TimeUnit.SECONDS)
            assertTrue(rig.rows().isEmpty())
        }
    }

    @Test fun sameDeclaredRootCannotDowngradeAfterScopeRemoval() {
        install(); Rig().use { rig ->
            rig.composition.reevaluate().get(5, TimeUnit.SECONDS); until { rig.waits.get() == 1 }
            rule.runOnIdle { checkNotNull(AnnotatedRootRegistry.fromHost(host)).close() }
            rig.next()
            until { !rig.composition.recordingStarted() }
            assertEquals(NativeReplayCompositionEvaluation.INACTIVE, rig.composition.reevaluate(force = true).get(5, TimeUnit.SECONDS))
            assertEquals(0, rig.wireframeFactories.get()); assertTrue(rig.rows().isEmpty())
            assertTrue(rig.owner.storedPreparedReplayForTesting().get().isEmpty())
        }
    }

    @Test fun returningToEarlierActualDeclaredRootCannotDowngradeAfterAnotherRoot() {
        lateinit var firstRoot: ViewGroup; lateinit var secondRoot: FrameLayout
        lateinit var parent: ViewGroup; lateinit var firstView: ComposeView; lateinit var secondView: ComposeView
        lateinit var firstRegistry: AnnotatedRootRegistry
        var secondHost: View? = null; var index = -1
        rule.runOnIdle {
            firstView = ComposeView(rule.activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { EluAnnotatedReplayRoot(emptyList(), Modifier.size(64.dp).background(Color.Red)) {
                    val view = LocalView.current; SideEffect { host = view }; Box(Modifier.size(16.dp).background(Color.Blue))
                } }
            }
            rule.activity.setContentView(firstView)
            firstRoot = rule.activity.findViewById(android.R.id.content)
            parent = firstRoot.parent as ViewGroup; index = parent.indexOfChild(firstRoot)
            secondView = ComposeView(rule.activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { EluAnnotatedReplayRoot(emptyList(), Modifier.size(64.dp).background(Color.Green)) {
                    val view = LocalView.current; SideEffect { secondHost = view }; Box(Modifier.size(16.dp).background(Color.Blue))
                } }
            }
            secondRoot = FrameLayout(rule.activity).apply {
                id = android.R.id.content
                addView(secondView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        fun ready(view: View?) = view != null && view.isAttachedToWindow && view.hasWindowFocus() &&
            view.isLaidOut && !view.isLayoutRequested
        fun replace(old: ViewGroup, next: ViewGroup) {
            val params = old.layoutParams
            parent.removeView(old); parent.addView(next, index, params)
        }
        try {
            rule.waitForIdle(); rule.waitUntil(5_000) { rule.runOnIdle { ready(host) } }
            rule.runOnIdle { firstRegistry = checkNotNull(AnnotatedRootRegistry.fromHost(host)) }
            Rig().use { rig ->
                assertEquals(NativeReplayCompositionEvaluation.ACTIVE, rig.composition.reevaluate().get(5, TimeUnit.SECONDS))
                until { rig.waits.get() == 1 }
                rule.runOnIdle { replace(firstRoot, secondRoot) }
                rule.waitForIdle(); rule.waitUntil(5_000) { rule.runOnIdle { ready(secondHost) } }
                assertEquals(NativeReplayCompositionEvaluation.ACTIVE, rig.composition.reevaluate(force = true).get(5, TimeUnit.SECONDS))
                until { rig.waits.get() >= 2 }
                rule.runOnIdle {
                    // The original A composition remains alive while B is selected; remove only A's scope.
                    assertSame(firstRegistry, AnnotatedRootRegistry.fromHost(host))
                    firstRegistry.close(); assertNull(AnnotatedRootRegistry.fromHost(host))
                    replace(secondRoot, firstRoot)
                }
                rule.waitForIdle(); rule.waitUntil(5_000) { rule.runOnIdle { ready(host) } }
                assertEquals(NativeReplayCompositionEvaluation.INACTIVE, rig.composition.reevaluate(force = true).get(5, TimeUnit.SECONDS))
                assertEquals(0, rig.wireframeFactories.get())
                assertTrue(rig.rows().isEmpty()); assertTrue(rig.owner.storedPreparedReplayForTesting().get().isEmpty())
            }
        } finally {
            rule.runOnIdle {
                if (secondRoot.parent === parent) replace(secondRoot, firstRoot)
                firstView.disposeComposition(); secondView.disposeComposition()
            }
        }
    }

    @Test fun missingServerChildCannotStartRasterOrFallBackToWireframe() {
        install(); Rig(child = false).use { rig ->
            assertEquals(NativeReplayCompositionEvaluation.INACTIVE, rig.composition.reevaluate().get(5, TimeUnit.SECONDS))
            assertEquals(0, rig.waits.get()); assertEquals(0, rig.wireframeFactories.get())
            assertTrue(rig.rows().isEmpty()); assertTrue(rig.owner.storedPreparedReplayForTesting().get().isEmpty())
        }
    }

    @Test fun originalFactorySelectsExactlyOneConfigFormatAndClosesOriginalSource() {
        for ((enabled, expected) in listOf(false to "/v2/", true to "/v3/")) {
            val endpoints = CopyOnWriteArrayList<String>(); val received = CountDownLatch(1)
            val facade = AndroidStandaloneStack.facade(rule.activity.application, key,
                persistence = dev.elu.analytics.EluPersistenceMode.MEMORY,
                declaredRegionReplayEnabled = enabled,
                configurationTransport = V2ConfigTransport { endpoint ->
                    endpoints += endpoint.path; received.countDown(); V2ConfigHttpResponse(403, null)
                })
            try { facade.start(); assertTrue(received.await(5, TimeUnit.SECONDS)) }
            finally { facade.closeAndWait().get(5, TimeUnit.SECONDS) }
            assertEquals(1, endpoints.size); assertTrue(endpoints.single().contains(expected))
        }
    }

    private inner class Rig(child: Boolean = true) : AutoCloseable {
        val now = AtomicLong(origin)
        val waits = AtomicInteger(); val wireframeFactories = AtomicInteger(); private val step = Semaphore(0)
        val ack = AtomicBoolean(); val sent = CopyOnWriteArrayList<ByteArray>()
        private val io = Executors.newSingleThreadExecutor()
        private val dir = File(rule.activity.cacheDir, "raster-composition-" + UUID.randomUUID())
        private val configClock = object : V2ConfigClock {
            override fun wallNowEpochMillis() = now.get()
            override fun monotonicNowNanos() = 1_000_000_000L + (now.get() - origin) * 1_000_000L
        }
        private val gate = V2ConfigAuthorityGate()
        private val wire = fixture().also { if (!child) it.remove("raster") }.toString()
        private val source = V2ConfigSource("https://elu.dev", key,
            V2ConfigTransport { V2ConfigHttpResponse(200, wire) }, configClock, format = V2ConfigFormat.NATIVE_V3)
        private val tasks = ArrayDeque<() -> Unit>()
        private val driver = V2ConfigLifecycleDriver(source, gate::update, configClock, object : V2ConfigLifecycleScheduler {
            override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
            override fun close() = Unit
        }, object : V2ConfigLifecycleWorker {
            override fun execute(task: () -> Unit) { tasks.add(task) }
            override fun interruptCurrent() = Unit
            override fun close() = Unit
        })
        private val protocols = AndroidStandaloneStack.installedNativeReplayProtocols
        val owner = RuntimeQueueOwner.open("raster-composition-" + UUID.randomUUID(), RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
            readbackProvenReplayTransports = protocols.map { it.transport }.toSet(),
            supportedReplayProtocolGenerations = protocols.map { it.generation }.toSet(),
            databaseFactory = { AndroidSQLiteRuntimeDatabase.open(File(dir, "queue.sqlite")) }, legacyStateLoader = { initial() }, trustedSiteKey = key,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = now.get()
                override fun elapsedRealtimeNanos() = configClock.monotonicNowNanos()
            }).get()
        private val lifecycle = NativeReplayLifecycle()
        private val platform = object : NativeReplayCapturePlatform by AndroidNativeReplayCapturePlatform {
            override fun createCollector(): NativeReplayCaptureCollector { wireframeFactories.incrementAndGet(); error("wireframe fallback") }
            override fun createCollector(protocol: NativeReplayProtocol, masking: NativeMaskingProfile,
                profile: NativeCapturePassProfile?): NativeReplayCaptureCollector = createCollector()
            override fun awaitNext(withdrawn: CountDownLatch): Boolean {
                waits.incrementAndGet()
                while (!withdrawn.await(10, TimeUnit.MILLISECONDS)) if (step.tryAcquire()) return true
                return false
            }
        }
        val composition: NativeReplayComposition
        init {
            owner.bindConfigurationGate(gate).get(); driver.start(); tasks.removeFirst().invoke()
            val base = checkNotNull(gate.snapshot()?.body); val parsed = V1ConfigJson.parseConfig(base)
            val privacy = PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(parsed.privacy),
                checkNotNull(parsed.features), checkNotNull(parsed.replayCapabilities), owner.snapshot().get().state.identity,
                false, RuntimeWallTimestamps.rfc3339(now.get()))))
            assertTrue(owner.submitCaptureAuthority(base, privacy).get() is RuntimeCaptureAuthorityUpdateResult.Activated)
            lifecycle.resumed(rule.activity)
            composition = NativeReplayComposition(owner, lifecycle, NativeReplayCapabilities(protocols.map { it.transport }.toSet(),
                protocols.map { it.generation }.toSet(), rasterSupported = true), StandaloneRuntime.defaultVersions(), { false }, { true },
                platform, ReplayDeliveryTransport { claim, authorize ->
                    val future = SdkFuture<ReplayTransportResponse>()
                    val canceled = AtomicBoolean()
                    val operation = object : ReplayTransportOperation {
                        override val settlement = future
                        override fun cancel() { canceled.set(true) }
                    }
                    io.execute {
                        try {
                            check(!canceled.get() && authorize())
                            sent += claim.copyBody()
                            if (!ack.get()) throw java.io.IOException("lost-ack")
                            val request = claim.rasterRow.request
                            val body = JSONObject().put("schemaVersion", 3).put("requestId", request.requestId)
                                .put("replayId", request.replayId).put("chunkId", request.chunkId)
                                .put("sequence", request.sequence).put("result", "accepted").toString().toByteArray()
                            future.complete(ReplayTransportResponse(200, body))
                        } catch (failure: Throwable) { future.completeExceptionally(failure) }
                    }
                    operation
                })
            composition.ready().get(5, TimeUnit.SECONDS)
        }
        fun rows() = owner.storedNativeRasterForTesting().get()
        fun next() { now.addAndGet(1_000); step.release() }
        override fun close() {
            try { composition.closeAndWait().get(5, TimeUnit.SECONDS) }
            finally {
                driver.close(); owner.closeAsync().get(5, TimeUnit.SECONDS)
                io.shutdown(); assertTrue(io.awaitTermination(5, TimeUnit.SECONDS)); sent.forEach { it.fill(0) }; dir.deleteRecursively()
            }
        }
    }

    private fun initial() = PersistedCoreState(identity = IdentityState(revision = 1, contextRevision = 1,
        anonymousId = "raster-anon", userId = null, groups = emptyMap(), superProperties = emptyMap(),
        session = SessionState("raster-session", "2026-08-05T00:01:00Z", "2026-08-05T00:01:00Z", 1800,
            lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null), optedOut = false, updatedAt = "2026-08-05T00:01:00Z"),
        stream = StreamState(streamId = "raster-stream", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))

    private fun fixture(minimum: Int = 2): JSONObject {
        val base = JSONObject(BASE)
        base.getJSONObject("privacy").getJSONObject("replay").put("sampleRate", 1).put("minimumDurationSeconds", minimum)
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
