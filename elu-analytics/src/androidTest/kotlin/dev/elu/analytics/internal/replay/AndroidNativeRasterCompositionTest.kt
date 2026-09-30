package dev.elu.analytics.internal.replay

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlin.math.roundToInt
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
        val firstActivity = rule.activity
        lateinit var firstRoot: ViewGroup
        lateinit var firstView: ComposeView
        lateinit var firstRegistry: AnnotatedRootRegistry
        var secondActivity: NativeAppCompatTestActivity? = null
        var secondRoot: ViewGroup? = null; var secondView: ComposeView? = null
        var secondHost: View? = null
        rule.runOnUiThread {
            firstView = ComposeView(firstActivity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { Box {
                    // The original full-window ComposeView imposes exact minimum constraints.
                    // This real parent lets the declared region keep its intended 64dp size.
                    EluAnnotatedReplayRoot(emptyList(), Modifier.size(64.dp).background(Color.Red)) {
                        val view = LocalView.current; SideEffect { host = view }; Box(Modifier.size(16.dp).background(Color.Blue))
                    }
                } }
            }
            firstActivity.setContentView(firstView)
            firstRoot = firstActivity.findViewById(android.R.id.content)
        }
        fun ready(view: View?) = view != null && view.isAttachedToWindow && view.hasWindowFocus() &&
            view.isLaidOut && !view.isLayoutRequested
        fun originalFacts(stage: String, activity: ComponentActivity, expectedRoot: ViewGroup, view: () -> View?): String {
            return try { rule.runOnUiThread {
                fun state(value: View?): String = if (value == null) "exists=false" else
                    "exists=true,attached=${value.isAttachedToWindow},focused=${value.hasWindowFocus()}," +
                        "laidOut=${value.isLaidOut},layoutRequested=${value.isLayoutRequested}," +
                        "width=${value.width},height=${value.height},measuredWidth=${value.measuredWidth}," +
                        "measuredHeight=${value.measuredHeight},visibility=${value.visibility}," +
                        "windowVisibility=${value.windowVisibility},shown=${value.isShown}," +
                        "alphaOne=${value.alpha == 1f},transitionAlphaOne=${value.transitionAlpha == 1f}," +
                        "matrixIdentity=${value.matrix.isIdentity},animationMatrixIdentity=${value.animationMatrix?.isIdentity != false}," +
                        "animationAbsent=${value.animation == null},clipToOutline=${value.clipToOutline}"
                val selected = view()
                val parent = expectedRoot.parent as? View
                "stage=$stage,activityFinishing=${activity.isFinishing},activityDestroyed=${activity.isDestroyed}," +
                    "activityState=${activity.lifecycle.currentState.ordinal}," +
                    "secureWindow=${activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0};host={${state(selected)}};root={${state(expectedRoot)}};" +
                    "decor={${state(activity.window.peekDecorView())}};" +
                    "parentClass=${parent?.javaClass?.name?.take(160)},parent={${state(parent)}};" +
                    "rootParamsClass=${expectedRoot.layoutParams?.javaClass?.name?.take(160)}," +
                    "rootParamsWidth=${expectedRoot.layoutParams?.width},rootParamsHeight=${expectedRoot.layoutParams?.height};" +
                    "firstCompose={${state(firstView)}},hasComposition=${firstView.hasComposition};" +
                    "secondCompose={${state(secondView)}},hasComposition=${secondView?.hasComposition};" +
                    "firstOriginalRootRetained=${firstActivity.findViewById<View>(android.R.id.content) === firstRoot}," +
                    "hostParentFirst=${selected?.parent === firstView},hostParentSecond=${selected?.parent === secondView}," +
                    "hostRootIsDecor=${selected != null && selected.rootView === activity.window.peekDecorView()}," +
                    "selectedContentIsExpected=${activity.findViewById<View>(android.R.id.content) === expectedRoot}"
            } } catch (_: Throwable) { "stage=$stage;readinessFactsUnavailable=true" }
        }
        var stage = "first-readiness"
        var diagnosticActivity: ComponentActivity = firstActivity
        var diagnosticRoot: ViewGroup = firstRoot
        var diagnosticView: () -> View? = { if (this::host.isInitialized) host else null }
        var beforeActionFacts = "unobserved"
        var diagnosticRig: Rig? = null
        fun awaitReady(phase: String, activity: ComponentActivity, expectedRoot: ViewGroup,
            declared: Boolean = true, view: () -> View?) {
            stage = phase; diagnosticActivity = activity; diagnosticRoot = expectedRoot; diagnosticView = view
            rule.waitUntil(5_000) { rule.runOnUiThread {
                activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    !activity.isFinishing && !activity.isDestroyed && ready(view())
            } }
            rule.runOnUiThread {
                val selected = checkNotNull(view())
                assertSame(expectedRoot, activity.findViewById<View>(android.R.id.content))
                assertTrue(expectedRoot.width > 0 && expectedRoot.height > 0)
                assertTrue(selected.width > 0 && selected.height > 0)
                val original = AnnotatedRootRegistry.fromHost(selected)
                if (declared) {
                    val region = checkNotNull(checkNotNull(original).bindings().single { it.intent == null }.read())
                    val expected = (64f * selected.resources.displayMetrics.density).roundToInt().toFloat()
                    assertEquals(expected, region.topRightX - region.topLeftX, .01f)
                    assertEquals(expected, region.bottomLeftY - region.topLeftY, .01f)
                } else assertNull(original)
            }
            beforeActionFacts = originalFacts(phase, activity, expectedRoot, view)
        }
        var primary: Throwable? = null
        try {
            // Each Activity keeps its own framework content root. A's composition survives
            // its stopped lifecycle; global Compose idleness is not selected-root readiness.
            awaitReady("first", firstActivity, firstRoot) { if (this::host.isInitialized) host else null }
            rule.runOnUiThread { firstRegistry = checkNotNull(AnnotatedRootRegistry.fromHost(host)) }
            Rig(traceNativeStart = true).use { rig ->
                diagnosticRig = rig; stage = "initial-A-evaluation"
                assertEquals(NativeReplayCompositionEvaluation.ACTIVE, rig.composition.reevaluate().get(5, TimeUnit.SECONDS))
                until { rig.waits.get() == 1 }
                var secondAdmitted = false // Main-thread only; no empty pre-install B admission.
                val callbacks = object : Application.ActivityLifecycleCallbacks {
                    fun original(activity: Activity) = activity === firstActivity ||
                        (secondAdmitted && activity === secondActivity)
                    override fun onActivityResumed(activity: Activity) { if (original(activity)) rig.resumed(activity) }
                    override fun onActivityPaused(activity: Activity) { if (original(activity)) rig.withdrawing(activity) }
                    override fun onActivityDestroyed(activity: Activity) { if (original(activity)) rig.withdrawing(activity) }
                    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                }
                rule.runOnUiThread { firstActivity.application.registerActivityLifecycleCallbacks(callbacks) }
                try {
                    stage = "launch-original-B"
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val second = instrumentation.startActivitySync(Intent(instrumentation.context, NativeAppCompatTestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeAppCompatTestActivity
                    secondActivity = second
                    rule.runOnUiThread {
                        assertSame(firstActivity.application, second.application)
                        val content = ComposeView(second).apply {
                            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                            setContent { Box {
                                EluAnnotatedReplayRoot(emptyList(), Modifier.size(64.dp).background(Color.Green)) {
                                    val view = LocalView.current; SideEffect { secondHost = view }
                                    Box(Modifier.size(16.dp).background(Color.Blue))
                                }
                            } }
                        }
                        secondView = content; second.setContentView(content)
                        secondRoot = second.findViewById(android.R.id.content)
                        assertNotSame(firstRoot, secondRoot)
                    }
                    awaitReady("second", second, checkNotNull(secondRoot)) { secondHost }
                    rule.waitUntil(5_000) { rule.runOnUiThread { firstActivity.lifecycle.currentState == Lifecycle.State.CREATED } }
                    val waitsBeforeB = rig.waits.get()
                    rule.runOnUiThread {
                        assertFalse(firstActivity.isDestroyed)
                        assertSame(firstRoot, firstActivity.findViewById<View>(android.R.id.content))
                        assertEquals(Lifecycle.State.RESUMED, second.lifecycle.currentState)
                        secondAdmitted = true; rig.resumed(second)
                    }
                    stage = "B-capture"
                    // The resume callback can coalesce onto the withdrawn pause evaluation.
                    // Observe the retained automatic B evaluation's real collector first.
                    until { rig.waits.get() >= 2 && rig.waits.get() > waitsBeforeB && rig.composition.recordingStarted() }
                    rule.runOnUiThread {
                        assertEquals(Lifecycle.State.CREATED, firstActivity.lifecycle.currentState)
                        assertEquals(Lifecycle.State.RESUMED, second.lifecycle.currentState)
                        assertSame(secondRoot, second.findViewById<View>(android.R.id.content))
                    }
                    stage = "B-evaluation"
                    assertEquals(NativeReplayCompositionEvaluation.ACTIVE, rig.composition.reevaluate().get(5, TimeUnit.SECONDS))
                    stage = "retire-A-return-original"
                    rule.runOnUiThread {
                        assertSame(firstRegistry, AnnotatedRootRegistry.fromHost(host))
                        firstRegistry.close(); assertNull(AnnotatedRootRegistry.fromHost(host))
                        second.finish()
                    }
                    awaitReady("returned-first", firstActivity, firstRoot, declared = false) { host }
                    rule.waitUntil(5_000) { rule.runOnUiThread { second.isDestroyed } }
                    stage = "returned-A-evaluation"
                    assertEquals(NativeReplayCompositionEvaluation.INACTIVE, rig.composition.reevaluate(force = true).get(5, TimeUnit.SECONDS))
                    assertEquals(0, rig.wireframeFactories.get())
                    assertTrue(rig.rows().isEmpty()); assertTrue(rig.owner.storedPreparedReplayForTesting().get().isEmpty())
                } finally { rule.runOnUiThread { firstActivity.application.unregisterActivityLifecycleCallbacks(callbacks) } }
            }
        } catch (failure: Throwable) {
            primary = failure
            // Failure only: original pre-action facts and current fixed views, no text/tree/pixels.
            failure.addSuppressed(AssertionError("beforeAction={$beforeActionFacts};failure={${originalFacts(stage, diagnosticActivity, diagnosticRoot, diagnosticView)}}"))
            diagnosticRig?.let { rig -> failure.addSuppressed(AssertionError("boundedNativeStart=${rig.nativeTrace.joinToString("")}")) }
            throw failure
        } finally {
            try {
                rule.runOnUiThread {
                    try { secondActivity?.let { if (!it.isDestroyed) it.finish() } }
                    finally { try { firstView.disposeComposition() } finally { secondView?.disposeComposition() } }
                }
                secondActivity?.let { original -> rule.waitUntil(5_000) { rule.runOnUiThread { original.isDestroyed } } }
            } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }

    @Test fun actualOversizedRegisteredRootRefusesBeforeProducingACandidate() {
        rule.runOnUiThread {
            val original = ComposeView(rule.activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                // Reproduce exact incoming constraints overriding the preferred 64dp size.
                setContent { EluAnnotatedReplayRoot(emptyList(), Modifier.size(64.dp).background(Color.Red)) {
                    val view = LocalView.current; SideEffect { host = view }
                    Box(Modifier.size(16.dp).background(Color.Blue))
                } }
            }
            rule.activity.setContentView(original, ViewGroup.LayoutParams(16_385, 1))
        }
        rule.waitUntil(5_000) { rule.runOnUiThread {
            this::host.isInitialized && host.isAttachedToWindow && host.hasWindowFocus() &&
                host.isLaidOut && !host.isLayoutRequested
        } }
        rule.runOnUiThread {
            val original = checkNotNull(AnnotatedRootRegistry.fromHost(host))
            val region = checkNotNull(original.bindings().single { it.intent == null }.read())
            assertEquals(16_385f, region.topRightX - region.topLeftX, .01f)
            assertEquals(1f, region.bottomLeftY - region.topLeftY, .01f)
            assertTrue(16_385 > AndroidAnnotatedReplayCollector.MAX_VIEWPORT_EDGE)
            val collector = AndroidAnnotatedReplayCollector(original)
            // Controlled time isolates the real geometry guard, not emulator performance.
            assertEquals("viewport-limit", assertThrows(IllegalStateException::class.java) {
                collector.prepareBinding(rule.activity.window, { true }) { 0L }
            }.message)
            assertEquals("viewport-limit", assertThrows(IllegalStateException::class.java) {
                collector.capture(rule.activity.window, { true }) { 1_000_000_000L }
            }.message)
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

    private inner class Rig(child: Boolean = true, traceNativeStart: Boolean = false) : AutoCloseable {
        val nativeTrace = CopyOnWriteArrayList<String>()
        private val nativeObserver = if (traceNativeStart) BoundedNativeStartObserver.create { record ->
            if (nativeTrace.size < 64) nativeTrace += String(record, Charsets.US_ASCII)
        } else BoundedNativeStartObserver.NONE
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
                }, nativeStartObserver = nativeObserver)
            composition.ready().get(5, TimeUnit.SECONDS)
        }
        fun rows() = owner.storedNativeRasterForTesting().get()
        fun resumed(activity: Activity) { lifecycle.resumed(activity) }
        fun withdrawing(activity: Activity) { lifecycle.withdrawing(activity) }
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
