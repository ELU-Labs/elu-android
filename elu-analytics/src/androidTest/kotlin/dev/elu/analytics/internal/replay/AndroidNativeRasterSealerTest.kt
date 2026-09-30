package dev.elu.analytics.internal.replay

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import dev.elu.analytics.EluAnnotatedReplayRootScope
import dev.elu.analytics.EluReplayGeometryReader
import dev.elu.analytics.EluReplayRegionGeometry
import dev.elu.analytics.compose.EluAnnotatedReplayRoot
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.SessionState
import dev.elu.analytics.internal.runtime.RuntimePlatform
import dev.elu.analytics.internal.runtime.RuntimeVersionComponent
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual one-shot collector candidates. No queue/authority installation or device-latency claim. */
@SdkSuppress(minSdkVersion = 29)
class AndroidNativeRasterSealerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var host: View
    private lateinit var registry: AnnotatedRootRegistry
    private lateinit var collector: AndroidAnnotatedReplayCollector
    private var nextAttempt = 1_000_000_000L
    private var width by mutableStateOf(80)
    private var noisy by mutableStateOf(false)
    private val instant = 1_704_067_200_123L
    private val policyHash = "sha256:" + "a".repeat(64)
    private val time = "2024-01-01T00:00:00.123Z"

    private fun install(windowSized: Boolean = false) {
        rule.setContent {
            val dimensions = if (windowSized) Modifier.fillMaxSize() else Modifier.size(width.dp, 80.dp)
            EluAnnotatedReplayRoot(emptyList(), dimensions.background(Color.Red).drawWithContent {
                drawContent()
                if (noisy) repeat(32) { y -> repeat(32) { x ->
                    val seed = (x + y * 32) * 1103515245 + 12345
                    drawRect(Color(0xff000000.toInt() or (seed and 0x00ffffff)),
                        Offset(x * size.width / 32, y * size.height / 32), Size(size.width / 32 + 1, size.height / 32 + 1))
                } }
            }) {
                val view = LocalView.current
                SideEffect { host = view }
                Box(Modifier.size(16.dp).background(Color.Blue))
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            registry = checkNotNull(AnnotatedRootRegistry.fromHost(host))
            collector = AndroidAnnotatedReplayCollector(registry)
        }
    }
    // Controlled monotonic time isolates frame/ownership behavior from cold renderer speed.
    private fun awaitOriginalHostReady(view: View) {
        // Compose idleness does not imply that the real Window has regained focus.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.runOnIdle {
                view.rootView === rule.activity.window.peekDecorView() && view.isAttachedToWindow &&
                    view.hasWindowFocus() && view.isLaidOut && !view.isLayoutRequested && view.width > 0 && view.height > 0
            }
        }
    }
    private fun capture(current: () -> Boolean = { true }): AnnotatedRasterCandidate {
        awaitOriginalHostReady(host)
        return rule.runOnIdle {
            val at = nextAttempt; nextAttempt += 1_000_000_000L
            collector.capture(rule.activity.window, current) { at }
        }
    }
    private fun identity() = IdentityState(revision = 3, contextRevision = 7, anonymousId = "anon-raster", userId = "user-raster",
        groups = emptyMap(), superProperties = emptyMap(),
        session = SessionState("session-raster", time, time, 1800, lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null),
        optedOut = false, updatedAt = time)
    private fun versions() = RuntimeVersions(platform = RuntimePlatform.ANDROID,
        runtime = RuntimeVersionComponent("elu-android", "1.0.0"), facade = RuntimeVersionComponent("EluAnalytics", "1.0.0"), build = "raster-test")
    private fun sealer(frame: AnnotatedRasterCandidate, maximum: Int = MAX_REPLAY_REQUEST_BYTES,
        current: () -> Boolean = { true }, snapshot: IdentityState = identity(), runtime: RuntimeVersions = versions(),
        replayId: String = "raster-epoch") =
        NativeRasterSealer(replayId, snapshot, NativeRasterPolicyBinding("policy-1", policyHash, 7, maximum),
            runtime, frame.sourceIdentity, current)
    private fun chunk(request: NativeRasterPreparedRequest) = JSONObject(request.copyBytes().toString(Charsets.UTF_8)).getJSONObject("chunk")
    private fun payload(request: NativeRasterPreparedRequest): JSONArray {
        val bytes = ReplayBase64.decode(chunk(request).getString("payload"))
        return try { GZIPInputStream(ByteArrayInputStream(bytes)).use { JSONArray(it.readBytes().toString(Charsets.UTF_8)) } }
        finally { bytes.fill(0) }
    }
    private fun refusal(expected: NativeRasterSealingFailure, action: () -> Unit) {
        try { action(); fail("expected $expected") }
        catch (error: NativeRasterSealingException) { assertEquals(expected, error.failure) }
    }

    @Test fun actualFrameProducesCanonicalSchemaThreeAndExactDeclaredWitness() {
        install(); val frame = capture(); val request = sealer(frame).seal(frame, instant)
        val root = JSONObject(request.copyBytes().toString(Charsets.UTF_8)); val chunk = root.getJSONObject("chunk")
        assertEquals(3, root.getInt("schemaVersion")); assertEquals(3, chunk.getInt("schemaVersion"))
        assertEquals(0L, request.sequence); assertEquals("session-raster", request.sessionId)
        assertEquals("elu-native-raster-v1", request.codec); assertEquals("native-raster-generation-v1", request.captureProtocolGeneration)
        assertEquals(time, chunk.getString("startedAt")); assertEquals(time, chunk.getString("endedAt"))
        assertEquals("anon-raster", chunk.getJSONObject("identity").getString("anonymousId"))
        assertEquals("user-raster", chunk.getJSONObject("identity").getString("userId"))
        assertEquals(3L, chunk.getJSONObject("identity").getLong("revision")); assertEquals(7L, chunk.getLong("contextRevision"))
        assertEquals("android", chunk.getJSONObject("versions").getString("platform"))
        assertEquals("2.0.0", chunk.getJSONObject("versions").getString("contractVersion"))
        val privacy = chunk.getJSONObject("privacy")
        assertEquals(setOf("schemaVersion", "policyRevision", "effectivePolicyHash", "maskingProfileHash", "inputCoverage",
            "automaticInputDiscovery", "unknownContentClassification", "appliedBeforeSerialization", "requiredRegionsRedacted",
            "platformFallbackApplied"), privacy.keys().asSequence().toSet())
        assertEquals(1, privacy.getInt("schemaVersion")); assertEquals(policyHash, privacy.getString("effectivePolicyHash"))
        assertEquals(NativeRasterSealer.PROFILE_HASH, privacy.getString("maskingProfileHash"))
        assertEquals("declared-regions", privacy.getString("inputCoverage"))
        assertFalse(privacy.getBoolean("automaticInputDiscovery")); assertFalse(privacy.getBoolean("unknownContentClassification"))
        assertTrue(privacy.getBoolean("appliedBeforeSerialization")); assertTrue(privacy.getBoolean("requiredRegionsRedacted"))
        assertFalse(privacy.getBoolean("platformFallbackApplied")); assertFalse(privacy.has("secureInputsMasked"))
        assertArrayEquals(request.copyBytes(), V1StrictCanonicalJson.canonicalBytes(ReplayJson.parse(request.copyBytes())))
        val events = payload(request); assertEquals(1, events.length())
        val actual = events.getJSONObject(0); assertEquals("frame", actual.getString("type")); assertEquals(instant, actual.getLong("timestamp"))
        assertEquals(frame.viewportWidth, actual.getJSONObject("viewport").getInt("width"))
        assertEquals(frame.viewportHeight, actual.getJSONObject("viewport").getInt("height"))
        val png = ReplayBase64.decode(actual.getJSONObject("image").getString("png"))
        try { DeclaredRegionPngEncoder.validate(png, frame.width, frame.height) } finally { png.fill(0) }
        assertThrows(IllegalStateException::class.java) { frame.encodePng() }
    }

    /** Requires the actual large API36 window, not an injected bitmap or hidden oversized child. */
    @Test @SdkSuppress(minSdkVersion = 36)
    fun fullHdActualFrameSealsSmallerPngWithOriginalViewportAndOneShotEpoch() {
        install(windowSized = true)
        val frame = capture()
        frame.use {
            assertTrue("actual viewport must require downsampling: ${frame.viewportWidth}x${frame.viewportHeight}",
                frame.viewportWidth.toLong() * frame.viewportHeight > AndroidAnnotatedReplayCollector.MAX_PIXELS ||
                    frame.viewportWidth > AndroidAnnotatedReplayCollector.MAX_EDGE || frame.viewportHeight > AndroidAnnotatedReplayCollector.MAX_EDGE)
            assertTrue(frame.width < frame.viewportWidth || frame.height < frame.viewportHeight)
            assertTrue(frame.width in 1..2048 && frame.height in 1..2048)
            assertTrue(frame.width.toLong() * frame.height <= 1_048_576)
            android.util.Log.i("EluRasterTest", "sealerViewport=${frame.viewportWidth}x${frame.viewportHeight};image=${frame.width}x${frame.height}")
            val original = sealer(frame)
            val request = original.seal(frame, instant)
            val actual = payload(request).getJSONObject(0)
            val viewport = actual.getJSONObject("viewport"); val image = actual.getJSONObject("image")
            assertEquals(frame.viewportWidth, viewport.getInt("width")); assertEquals(frame.viewportHeight, viewport.getInt("height"))
            assertEquals(frame.width, image.getInt("width")); assertEquals(frame.height, image.getInt("height"))
            assertEquals(frame.viewportWidth, request.width); assertEquals(frame.viewportHeight, request.height)
            val png = ReplayBase64.decode(image.getString("png"))
            try { DeclaredRegionPngEncoder.validate(png, frame.width, frame.height) } finally { png.fill(0) }
            assertArrayEquals(request.copyBytes(), V1StrictCanonicalJson.canonicalBytes(ReplayJson.parse(request.copyBytes())))
            assertEquals(0L, request.sequence)
            assertThrows(IllegalStateException::class.java) { frame.encodePng() }
            capture().use { next ->
                assertSame(frame.sourceIdentity, next.sourceIdentity)
                val second = original.seal(next, instant + 1_000)
                assertEquals(1L, second.sequence)
                assertEquals(request.width, second.width); assertEquals(request.height, second.height)
            }
        }
    }

    @Test fun requestHashUsesTheExactV3DomainAndOriginalBytesSurviveCallerCopies() {
        install(); val frame = capture(); val request = sealer(frame).seal(frame, instant)
        val root = ReplayJson.parse(request.copyBytes()).obj(setOf("schemaVersion", "requestId", "chunk"))
        val bytes = V1StrictCanonicalJson.canonicalBytes(root.get("chunk"))
        fun hash(domain: String) = "request_" + ReplayJson.digest(domain.toByteArray() + byteArrayOf(0) +
            ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes).removePrefix("sha256:")
        assertEquals(hash("elu-sdk-replay-request-v3"), request.requestId)
        assertNotEquals(hash("elu-sdk-replay-request-v2"), request.requestId)
        val copy = request.copyBytes(); copy.fill(0)
        assertEquals(ReplayJson.digest(request.copyBytes()), request.digest)
        assertNotEquals(0.toByte(), request.copyBytes()[0])
        assertThrows(IllegalArgumentException::class.java) { PreparedReplayRequest.parse(request.copyBytes(), request.captureProtocolGeneration) }
    }

    @Test fun forkIsSpeculativeAndIdenticalActualFramesRetainDeterministicOriginalRequests() {
        install(); val first = capture(); val original = sealer(first); val proposed = original.fork()
        val accepted = proposed.seal(first, instant)
        assertArrayEquals(accepted.copyBytes(), original.seal(capture(), instant).copyBytes())
        assertArrayEquals(proposed.seal(capture(), instant + 1_000).copyBytes(), original.seal(capture(), instant + 1_000).copyBytes())
    }

    @Test fun timestampRefusalsConsumeCandidatesWithoutAdvancingSequenceOrAdjustingWallTime() {
        install(); val seed = capture(); val original = sealer(seed); seed.close()
        for (bad in listOf(0L, -1L, 253_402_300_800_000L)) {
            val frame = capture(); refusal(NativeRasterSealingFailure.INVALID_TIMESTAMP) { original.seal(frame, bad) }
            assertThrows(IllegalStateException::class.java) { frame.encodePng() }
        }
        assertEquals(0L, original.seal(capture(), instant).sequence)
        for (bad in listOf(instant - 1, instant, instant + 999)) {
            refusal(NativeRasterSealingFailure.INVALID_TIMESTAMP) { original.seal(capture(), bad) }
        }
        val second = original.seal(capture(), instant + 1_000)
        assertEquals(1L, second.sequence); assertEquals(instant + 1_000, second.timestamp)
    }

    @Test fun requestByteBoundaryAndFailedPreparationLeaveAnIndependentOriginalEpoch() {
        install(); val seed = capture(); val clean = sealer(seed); val exact = clean.seal(seed, instant)
        val next = capture(); val limited = sealer(next, exact.byteCount); next.close()
        rule.runOnIdle { noisy = true }; rule.waitForIdle()
        val tooLarge = capture()
        refusal(NativeRasterSealingFailure.REQUEST_LIMIT) { limited.seal(tooLarge, instant) }
        assertThrows(IllegalStateException::class.java) { tooLarge.encodePng() }
        rule.runOnIdle { noisy = false }; rule.waitForIdle()
        assertArrayEquals(exact.copyBytes(), limited.seal(capture(), instant).copyBytes())
        val same = capture(); assertArrayEquals(exact.copyBytes(), sealer(same, exact.byteCount).seal(same, instant).copyBytes())
        val below = capture()
        refusal(NativeRasterSealingFailure.REQUEST_LIMIT) { sealer(below, exact.byteCount - 1).seal(below, instant) }
        // A failed fork must not consume the original's initial sequence.
        val frame = capture(); val untouched = sealer(frame); frame.close()
        val trial = untouched.fork(); refusal(NativeRasterSealingFailure.INVALID_TIMESTAMP) { trial.seal(capture(), 0) }
        assertEquals(0L, untouched.seal(capture(), instant).sequence)
    }

    @Test fun sourceWithdrawalAtEverySealerHandoffKeepsHistoryUnadvanced() {
        install(); val seed = capture(); var checks = 0; var stopAt = Int.MAX_VALUE
        val original = sealer(seed, current = { ++checks < stopAt }); seed.close()
        for (handoff in 1..5) {
            checks = 0; stopAt = handoff
            val frame = capture()
            refusal(NativeRasterSealingFailure.WITHDRAWN) { original.seal(frame, instant) }
            assertEquals(handoff, checks)
            assertThrows(IllegalStateException::class.java) { frame.encodePng() }
        }
        checks = 0; stopAt = Int.MAX_VALUE
        assertEquals(0L, original.seal(capture(), instant).sequence)
    }

    @Test fun withdrawalOfOriginalCollectorPermissionIsNotReplacedBySealerPermission() {
        install(); var allowed = true
        val frame = capture { allowed }; val original = sealer(frame)
        allowed = false
        assertThrows(IllegalStateException::class.java) { original.seal(frame, instant) }
        allowed = true
        assertEquals(0L, original.seal(capture { allowed }, instant).sequence)
    }

    @Test fun changedViewportRetiresOriginalSourceAndRestoringSizeRequiresANewEpoch() {
        install(); val first = capture(); val token = first.sourceIdentity; val original = sealer(first)
        original.seal(first, instant)
        rule.runOnIdle { width = 100 }; rule.waitForIdle()
        val changed = capture(); val changedToken = changed.sourceIdentity
        assertNotSame(token, changedToken); assertFalse(token.isCurrent())
        refusal(NativeRasterSealingFailure.WITHDRAWN) { original.seal(changed, instant + 1_000) }
        assertThrows(IllegalStateException::class.java) { changed.encodePng() }
        rule.runOnIdle { width = 80 }; rule.waitForIdle()
        val restored = capture()
        assertNotSame(token, restored.sourceIdentity); assertNotSame(changedToken, restored.sourceIdentity)
        assertFalse(token.isCurrent()); assertFalse(changedToken.isCurrent())
        refusal(NativeRasterSealingFailure.WITHDRAWN) { original.seal(restored, instant + 1_000) }
        assertThrows(IllegalStateException::class.java) { restored.encodePng() }
        val fresh = capture()
        val request = sealer(fresh, replayId = "raster-epoch-after-resize").seal(fresh, instant + 2_000)
        assertEquals("raster-epoch-after-resize", request.replayId); assertEquals(0L, request.sequence)
    }

    @Test fun foreignActualRootCannotSupplyPixelsUnderAnotherLiveSourceClosure() {
        install(); val originalFrame = capture(); val original = sealer(originalFrame); originalFrame.close()
        lateinit var foreign: View
        lateinit var scope: EluAnnotatedReplayRootScope
        rule.runOnIdle {
            foreign = View(rule.activity).apply { setBackgroundColor(android.graphics.Color.GREEN) }
            rule.activity.addContentView(foreign, ViewGroup.LayoutParams(40, 40))
            scope = EluAnnotatedReplayRootScope.prepare(foreign); scope.declareRequired(emptyList()); assertTrue(scope.attach())
        }
        rule.waitForIdle()
        val coordinates = Any()
        val binding = rule.runOnIdle { scope.bindRoot(EluReplayGeometryReader {
            val at = IntArray(2); foreign.getLocationInWindow(at)
            val x = at[0].toFloat(); val y = at[1].toFloat(); val w = foreign.width.toFloat(); val h = foreign.height.toFloat()
            EluReplayRegionGeometry(coordinates, foreign.parent, 0, x, y, x+w, y, x, y+h, x+w, y+h)
        }) }
        try {
            awaitOriginalHostReady(foreign)
            val frame = rule.runOnIdle { AndroidAnnotatedReplayCollector(scope.registry).capture(rule.activity.window, { true }) { 1L } }
            assertNotSame(originalFrame.sourceIdentity, frame.sourceIdentity)
            refusal(NativeRasterSealingFailure.SOURCE_MISMATCH) { original.seal(frame, instant) }
            assertThrows(IllegalStateException::class.java) { frame.encodePng() }
        } finally { rule.runOnIdle { binding.close(); scope.close(); (foreign.parent as ViewGroup).removeView(foreign) } }
        assertEquals(0L, original.seal(capture(), instant).sequence)
    }

    @Test fun rootBindingCloseAndEquivalentRebindCannotReviveAnOldSourceToken() {
        install(); val frame = capture(); val original = sealer(frame); val oldToken = frame.sourceIdentity
        lateinit var replacement: AnnotatedGeometryBinding
        rule.runOnIdle {
            val root = registry.bindings().single { it.intent == null }; val geometry = checkNotNull(root.read())
            root.close(); replacement = registry.bind(null, EluReplayGeometryReader { geometry })
        }
        try {
            assertFalse(oldToken.isCurrent())
            refusal(NativeRasterSealingFailure.WITHDRAWN) { original.seal(frame, instant) }
            val fresh = capture(); assertNotSame(oldToken, fresh.sourceIdentity)
            assertEquals(0L, sealer(fresh).seal(fresh, instant).sequence)
        } finally { rule.runOnIdle { replacement.close() } }
    }

    @Test fun originalScopeCloseWithdrawsPreparedFrameAndCannotBeCuredByCurrentClosure() {
        install(); val frame = capture(); val original = sealer(frame)
        rule.runOnIdle { registry.close() }
        refusal(NativeRasterSealingFailure.WITHDRAWN) { original.seal(frame, instant) }
        assertThrows(IllegalStateException::class.java) { frame.encodePng() }
    }

    @Test fun closedPolicyAndOriginalIdentityPlatformRequirementsCannotUseAnOldAutomaticWitness() {
        install(); val frame = capture()
        try {
            for (hash in listOf("", policyHash + "0", "sha256:" + "A".repeat(64))) {
                refusal(NativeRasterSealingFailure.INVALID_BINDING) { NativeRasterPolicyBinding("p", hash, 7, 1024) }
            }
            for (limit in listOf(0, MAX_REPLAY_REQUEST_BYTES + 1)) {
                refusal(NativeRasterSealingFailure.INVALID_BINDING) { NativeRasterPolicyBinding("p", policyHash, 7, limit) }
            }
            for (revision in listOf("", "\ud800", "x".repeat(129))) {
                refusal(NativeRasterSealingFailure.INVALID_BINDING) { NativeRasterPolicyBinding(revision, policyHash, 7, 1024) }
            }
            refusal(NativeRasterSealingFailure.INVALID_BINDING) { NativeRasterPolicyBinding("p", policyHash, MAX_REPLAY_SAFE_INTEGER + 1, 1024) }
            refusal(NativeRasterSealingFailure.INVALID_BINDING) { sealer(frame, snapshot = identity().copy(contextRevision = 8)) }
            refusal(NativeRasterSealingFailure.INVALID_BINDING) { sealer(frame, snapshot = identity().copy(session = null)) }
            refusal(NativeRasterSealingFailure.INVALID_BINDING) { sealer(frame, snapshot = identity().copy(optedOut = true)) }
            refusal(NativeRasterSealingFailure.WITHDRAWN) { sealer(frame, current = { false }) }
            refusal(NativeRasterSealingFailure.INVALID_BINDING) { sealer(frame, runtime = versions().copy(platform = RuntimePlatform.IOS)) }
        } finally { frame.close() }
    }
}
