package dev.elu.analytics.internal.replay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.elu.analytics.compose.EluAnnotatedReplayRoot
import dev.elu.analytics.compose.EluReplayBlock
import dev.elu.analytics.compose.EluReplayMask
import dev.elu.analytics.EluReplayPrivateRegion
import dev.elu.analytics.EluAnnotatedReplayRootScope
import dev.elu.analytics.EluReplayGeometryReader
import dev.elu.analytics.EluReplayRegionGeometry
import dev.elu.analytics.R
import androidx.compose.ui.platform.LocalView
import android.view.View
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.filters.SdkSuppress
import kotlin.math.ceil
import kotlin.math.floor

/** Original mounted UI only. These tests do not grant SDK authority or qualify capture latency. */
@SdkSuppress(minSdkVersion = 29)
class AndroidComposeReplayCollectorTest {
    @Test fun originalMainPostCheckDiscardsActualCandidateAndReleasesCollector() {
        install { BasicText("ordinary") }
        val lifecycle = NativeReplayLifecycle(); lifecycle.resumed(rule.activity)
        val selected = checkNotNull(lifecycle.select(rule.activity, host).get())
        val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
        var actual: AnnotatedRasterCandidate? = null
        try {
            val result = selected.consumeOriginalWindow(allowed::get) { _, window, current ->
                val frame = collector.capture(window as android.view.Window, current) { 10_000_000_000L }
                actual = frame
                allowed.set(false) // Original withdrawal after actual draw, before final main validation.
                NativeReplayCollectionAttempt.RasterCaptured(frame, 10_000_000_000L, 1_000L)
            }.get()
            assertNull(result)
            assertEquals("closed-frame", assertThrows(IllegalStateException::class.java) { checkNotNull(actual).encodePng() }.message)
            rule.runOnIdle { collector.capture(rule.activity.window, { true }) { 11_000_000_000L }.close() }
        } finally { actual?.close(); selected.closeAndWait().get() }
    }

    @Test fun terminalCleanupFailureSurvivesEncodingAndLaterNoOpClose() {
        install { BasicText("ordinary") }
        val original = capture(); val source = original.sourceIdentity; original.close()
        val pixels = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val releases = java.util.concurrent.atomic.AtomicInteger()
        val expected = IllegalStateException("original-release-failure")
        // Controlled cleanup fault on an internal candidate; no SDK authority or runtime is forged.
        val frame = AnnotatedRasterCandidate.validated(pixels, source, { true }, {
            releases.incrementAndGet(); throw expected
        })
        assertSame(expected, assertThrows(IllegalStateException::class.java) { frame.encodePng() })
        assertTrue(pixels.isRecycled); assertTrue(frame.hasCleanupFailure()); assertEquals(1, releases.get())
        frame.close()
        assertTrue(frame.hasCleanupFailure()); assertEquals(1, releases.get())
        assertThrows(IllegalStateException::class.java) { frame.encodePng() }
        assertTrue(frame.hasCleanupFailure())
    }

    @Test fun rootResizeAndRestorePermanentlyRetireOriginalSource() {
        var size by mutableStateOf(100)
        rule.setContent { EluAnnotatedReplayRoot(emptyList(), Modifier.size(size.dp).background(Color.White)) {
            val original = LocalView.current; SideEffect { host = original }; BasicText("ordinary")
        } }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.runOnIdle { host.isAttachedToWindow && host.hasWindowFocus() && !host.isLayoutRequested } }
        rule.runOnIdle { registry = checkNotNull(AnnotatedRootRegistry.fromHost(host)); collector = AndroidAnnotatedReplayCollector(registry) }
        val first = capture(); val original = first.sourceIdentity; first.close()
        rule.runOnIdle { size = 110 }; rule.waitForIdle()
        assertFalse(original.isCurrent())
        val changed = capture(); val second = changed.sourceIdentity; changed.close()
        assertNotSame(original, second)
        rule.runOnIdle { size = 100 }; rule.waitForIdle()
        assertFalse(second.isCurrent()); assertFalse(original.isCurrent())
        val restored = capture(); assertNotSame(original, restored.sourceIdentity); restored.close()
    }

    @Test fun discoveryIsNoPixelAndRejectsForeignPresentTagInsteadOfReportingAbsent() {
        install { BasicText("ordinary") }
        rule.runOnIdle {
            val binding = discoverAnnotatedRoot(host, rule.activity.window, { true })
            assertTrue(binding is AnnotatedRootDiscovery.Bound)
            val frame = collector.capture(rule.activity.window, { true }) { 10_000_000_000L }; frame.close()
            val original = host.getTag(R.id.elu_annotated_replay_root)
            try {
                host.setTag(R.id.elu_annotated_replay_root, Any())
                assertSame(AnnotatedRootDiscovery.Unavailable, discoverAnnotatedRoot(host, rule.activity.window, { true }))
            } finally { host.setTag(R.id.elu_annotated_replay_root, original) }
        }
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var host: View
    private lateinit var registry: AnnotatedRootRegistry
    private lateinit var collector: AndroidAnnotatedReplayCollector
    private var nextAttempt = 1_000_000_000L

    private fun install(required: () -> List<EluReplayPrivateRegion> = { emptyList() },
        modifier: Modifier = Modifier.size(160.dp, 220.dp).background(Color.White),
        content: @Composable () -> Unit) {
        rule.setContent {
            EluAnnotatedReplayRoot(required(), modifier) {
                val original = LocalView.current
                SideEffect { host = original }
                content()
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.runOnUiThread {
            this::host.isInitialized && host.isAttachedToWindow && host.hasWindowFocus() &&
                host.isLaidOut && !host.isLayoutRequested
        } }
        rule.runOnIdle {
            assertTrue(rule.activity.window.decorView.hasWindowFocus())
            registry = checkNotNull(AnnotatedRootRegistry.fromHost(host))
            collector = AndroidAnnotatedReplayCollector(registry)
        }
    }

    // Fixed monotonic clock isolates privacy/ownership assertions from cold renderer timing.
    // The real 50 ms pass budget and actual useful-device fit require separate qualification.
    private fun capture(current: () -> Boolean = { true }) = rule.runOnIdle {
        val original = nextAttempt
        nextAttempt += AndroidAnnotatedReplayCollector.INTERVAL_NANOS
        collector.capture(rule.activity.window, current) { original }
    }
    private fun pixels(frame: AnnotatedRasterCandidate): IntArray {
        val png = frame.encodePng()
        var bitmap: Bitmap? = null
        try {
            DeclaredRegionPngEncoder.validate(png, frame.width, frame.height)
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            bitmap = decoded
            return IntArray(decoded.width * decoded.height).also {
                decoded.getPixels(it, 0, decoded.width, 0, 0, decoded.width, decoded.height)
            }
        } finally { png.fill(0); bitmap?.recycle() }
    }
    private fun sample(): IntArray = capture().use { pixels(it) }

    @Test fun actualWindowViewportKeepsOriginalCoordinatesAndRedactsOverlappingRegions() {
        assertOriginalWindowCapture(requireDownsampling = false)
    }

    /** Focused API36 device case: a small window is a failure, not downsampling evidence. */
    @Test @SdkSuppress(minSdkVersion = 36)
    fun fullHdOriginalWindowActuallyDownsamplesBeforeAllocationAndRetainsState() {
        assertOriginalWindowCapture(requireDownsampling = true)
    }

    private fun assertOriginalWindowCapture(requireDownsampling: Boolean) {
        val first = EluReplayPrivateRegion.create(); val second = EluReplayPrivateRegion.create()
        var counter by mutableIntStateOf(0)
        install({ listOf(first, second) }, Modifier.fillMaxSize().background(Color.White)) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.graphicsLayer { translationX = .25f; translationY = .75f }) {
                    EluReplayMask(first) { Box(Modifier.size(60.dp).background(Color.Magenta)) }
                }
                Box(Modifier.offset(30.dp, 30.dp).graphicsLayer { translationX = .75f; translationY = .25f }) {
                    EluReplayBlock(second) { Box(Modifier.size(60.dp).background(Color.Magenta)) }
                }
                Box(Modifier.align(Alignment.BottomEnd).size(24.dp)
                    .background(if (counter == 0) Color.Red else Color.Blue)
                    .clickable { counter++ }.testTag("window-counter"))
            }
        }
        val geometry = rule.runOnIdle { registry.bindings().map { it.intent to checkNotNull(it.read()) } }
        val root = geometry.single { it.first == null }.second
        val left = ceil(root.topLeftX.toDouble()); val top = ceil(root.topLeftY.toDouble())
        val width = floor(root.topRightX.toDouble()).toInt() - left.toInt()
        val height = floor(root.bottomLeftY.toDouble()).toInt() - top.toInt()
        if (requireDownsampling) assertTrue("actual viewport must exceed the image cap: ${width}x$height",
            width.toLong() * height > AndroidAnnotatedReplayCollector.MAX_PIXELS ||
                width > AndroidAnnotatedReplayCollector.MAX_EDGE || height > AndroidAnnotatedReplayCollector.MAX_EDGE)
        fun inspect(frame: AnnotatedRasterCandidate, publicColor: Int): Int {
            assertEquals(width, frame.viewportWidth); assertEquals(height, frame.viewportHeight)
            assertTrue(frame.width in 1..minOf(width, 2048) && frame.height in 1..minOf(height, 2048))
            assertTrue(frame.width.toLong() * frame.height <= 1_048_576)
            if (requireDownsampling) assertTrue(frame.width < width || frame.height < height)
            android.util.Log.i("EluRasterTest", "viewport=${frame.viewportWidth}x${frame.viewportHeight};image=${frame.width}x${frame.height}")
            val output = pixels(frame)
            try {
                assertTrue(output.all { android.graphics.Color.alpha(it) == 255 })
                assertTrue(output.any { it == android.graphics.Color.WHITE })
                assertTrue(output.any { it == publicColor })
                assertFalse(output.any { it == android.graphics.Color.MAGENTA })
                // Independent expected exclusion from actual mounted window corners.
                val sx = (frame.width.toFloat() / width).toDouble()
                val sy = (frame.height.toFloat() / height).toDouble()
                val margin = if (frame.width != width || frame.height != height) 1 else 0
                geometry.filter { it.first != null }.forEach { (_, region) ->
                    val l = (floor((region.topLeftX - left) * sx).toInt() - margin).coerceIn(0, frame.width)
                    val t = (floor((region.topLeftY - top) * sy).toInt() - margin).coerceIn(0, frame.height)
                    val r = (ceil((region.topRightX - left) * sx).toInt() + margin).coerceIn(0, frame.width)
                    val b = (ceil((region.bottomLeftY - top) * sy).toInt() + margin).coerceIn(0, frame.height)
                    for (y in t until b) for (x in l until r) {
                        assertEquals("redaction at $x,$y", AndroidAnnotatedReplayCollector.PLACEHOLDER, output[y * frame.width + x])
                    }
                }
                return output.contentHashCode()
            } finally { output.fill(0) }
        }
        val original = capture(); val source = original.sourceIdentity
        val before = original.use { inspect(it, android.graphics.Color.RED) }
        rule.onNodeWithTag("window-counter").performClick()
        assertEquals(1, rule.runOnIdle { counter })
        capture().use {
            assertSame(source, it.sourceIdentity)
            assertNotEquals(before, inspect(it, android.graphics.Color.BLUE))
        }
    }

    @Test fun originalChildStateScrollAndActualInputRemainOnTheOriginalHost() {
        val private = EluReplayPrivateRegion.create()
        var instance: Any? = null
        lateinit var readOffset: () -> Int
        lateinit var readInputLength: () -> Int
        install({ listOf(private) }) {
            val identity = remember { Any() }
            var counter by remember { mutableIntStateOf(0) }
            val input = remember { mutableStateOf("") }
            val scroll = rememberScrollState()
            val focus = LocalFocusManager.current
            // Read the original state on demand; child recomposition/layout need not rerun this effect.
            SideEffect {
                instance = identity
                readOffset = { scroll.value }
                readInputLength = { input.value.length }
            }
            Column {
                Box(Modifier.fillMaxWidth().height(28.dp)
                    .background(if (counter == 0) Color.Red else Color.Blue)
                    .clickable { counter++ }.testTag("counter")) {
                    BasicText("COUNT $counter", Modifier.testTag("counter-text"))
                }
                EluReplayMask(private) {
                    BasicTextField(input.value, { input.value = it }, Modifier.fillMaxWidth().height(36.dp).testTag("input"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                }
                Column(Modifier.fillMaxWidth().height(100.dp).verticalScroll(scroll).testTag("scroll")) {
                    repeat(8) { index ->
                        Box(Modifier.fillMaxWidth().height(45.dp)
                            .background(if (index % 2 == 0) Color.Green else Color.Yellow)) { BasicText("ROW $index") }
                    }
                }
            }
        }
        val firstInstance = rule.runOnIdle { instance }
        val initial = sample()
        try {
            rule.onNodeWithTag("counter").performClick()
            rule.onNodeWithTag("counter-text", useUnmergedTree = true).assertTextEquals("COUNT 1")
            val changed = sample()
            try { assertFalse(initial.contentEquals(changed)) } finally { changed.fill(0) }
            rule.onNodeWithTag("input").performClick().apply {
                performTextInput("PRIVATE INPUT 123")
                performImeAction()
            }
            rule.waitForIdle()
            assertEquals(17, rule.runOnIdle { readInputLength() })
            val beforeScroll = sample()
            try {
                rule.onNodeWithTag("scroll").performTouchInput { swipeUp(durationMillis = 500) }
                rule.waitForIdle()
                assertTrue(rule.runOnIdle { readOffset() } > 0)
                val afterScroll = sample()
                try { assertFalse(beforeScroll.contentEquals(afterScroll)) } finally { afterScroll.fill(0) }
            } finally { beforeScroll.fill(0) }
            assertSame(firstInstance, rule.runOnIdle { instance })
        } finally { initial.fill(0) }
    }

    @Test fun requiredIntentSurvivesMissingBindingAndDuplicateBindingRefuses() {
        val intent = EluReplayPrivateRegion.create()
        var mode by mutableIntStateOf(1)
        install({ listOf(intent) }) {
            Column {
                if (mode != 0) EluReplayMask(intent) { Box(Modifier.size(30.dp).background(Color.Magenta)) }
                if (mode == 2) EluReplayBlock(intent) { Box(Modifier.size(30.dp).background(Color.Magenta)) }
            }
        }
        capture().close()
        rule.runOnIdle { mode = 0 }; rule.waitForIdle()
        assertSame(intent, rule.runOnIdle { registry.intents.snapshot().single() })
        assertThrows(IllegalStateException::class.java) { capture() }
        rule.runOnIdle { mode = 2 }; rule.waitForIdle()
        assertThrows(IllegalStateException::class.java) { capture() }
        rule.runOnIdle { mode = 1 }; rule.waitForIdle()
        capture().close()
    }

    @Test fun oneCandidateOwnsOutputAndPolicyWithdrawalPreventsEncoding() {
        val extra = EluReplayPrivateRegion.create()
        var declared by mutableStateOf(false)
        install({ if (declared) listOf(extra) else emptyList() }) { BasicText("ORIGINAL") }
        val original = capture()
        try {
            assertThrows(IllegalStateException::class.java) { capture() }
            rule.runOnIdle { declared = true }; rule.waitForIdle()
            assertThrows(IllegalStateException::class.java) { original.encodePng() }
        } finally { original.close() }
        assertThrows(IllegalStateException::class.java) { original.encodePng() }
        assertThrows(IllegalStateException::class.java) { capture() }
        rule.runOnIdle { declared = false }; rule.waitForIdle()
        capture().close()
    }

    @Test fun successfulEncodingClosesOriginalPixelsAndReleasesExactlyOnce() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidAnnotatedReplayCollector.PLACEHOLDER)
        var releases = 0
        val frame = AnnotatedRasterCandidate.validated(bitmap, AnnotatedRasterSourceIdentity(), { true }, { releases++ })
        val encoded = frame.encodePng()
        try {
            DeclaredRegionPngEncoder.validate(encoded, 2, 2)
            assertTrue(bitmap.isRecycled)
            assertEquals(1, releases)
            assertThrows(IllegalStateException::class.java) { frame.encodePng() }
            frame.close()
            frame.close()
            assertEquals(1, releases)
        } finally { encoded.fill(0); frame.close() }
    }

    @Test fun actualOverlappingWrappersRedactUnionAndClippedOffscreenBindingIsAllowed() {
        val first = EluReplayPrivateRegion.create(); val second = EluReplayPrivateRegion.create()
        var offscreen by mutableStateOf(false)
        install({ listOf(first, second) }) {
            Box {
                EluReplayMask(first) { Box(Modifier.size(60.dp).background(Color.Magenta)) }
                Box(Modifier.offset(x = if (offscreen) 1000.dp else 30.dp, y = 30.dp)
                    .graphicsLayer { translationX = .75f; translationY = .75f }) {
                    EluReplayBlock(second) { Box(Modifier.size(60.dp).background(Color.Magenta)) }
                }
            }
        }
        val overlapping = sample()
        try {
            assertTrue(overlapping.any { it == AndroidAnnotatedReplayCollector.PLACEHOLDER })
            assertFalse(overlapping.any { it == android.graphics.Color.MAGENTA })
        } finally { overlapping.fill(0) }
        rule.runOnIdle { offscreen = true }; rule.waitForIdle()
        val outside = sample()
        try { assertFalse(outside.any { it == android.graphics.Color.MAGENTA }) } finally { outside.fill(0) }
        assertEquals(2, rule.runOnIdle { registry.intents.snapshot().size })
    }

    @Test fun originalDrawPolicyMutationRefusesBeforeAnyCandidateCanEncode() {
        val intent = EluReplayPrivateRegion.create()
        var armed = false
        install(modifier = Modifier.size(120.dp).drawWithContent {
            if (armed) { armed = false; registry.declare(listOf(intent)) }
            drawContent()
        }) { BasicText("ORIGINAL") }
        rule.runOnIdle { armed = true }
        assertThrows(IllegalStateException::class.java) { capture() }
        rule.runOnIdle { registry.declare(emptyList()) }
        capture().close()
    }

    @Test fun actualRemeasureDuringOriginalDrawInvalidatesThePrivacyPlan() {
        val intent = EluReplayPrivateRegion.create()
        var remeasure: Remeasurement? = null
        var armed = false
        var shift = 0
        var measures = 0
        val binding = object : RemeasurementModifier {
            override fun onRemeasurementAvailable(remeasurement: Remeasurement) { remeasure = remeasurement }
        }
        install({ listOf(intent) }, Modifier.size(140.dp).drawWithContent {
            if (armed) { armed = false; shift = 12; checkNotNull(remeasure).forceRemeasure() }
            drawContent()
        }) {
            Box(Modifier.then(binding).layout { measurable, constraints ->
                measures++
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.place(shift, 0) }
            }) { EluReplayMask(intent) { Box(Modifier.size(40.dp).background(Color.Magenta)) } }
        }
        capture().close()
        val before = rule.runOnIdle { measures }
        rule.runOnIdle { armed = true }
        assertThrows(IllegalStateException::class.java) { capture() }
        assertTrue(rule.runOnIdle { measures } > before)
        rule.waitForIdle()
        capture().close()
    }

    @Test fun supplementaryActualNativeInputMasksTheWholeOutputWithoutReadingItsValue() {
        install {
            AndroidView(factory = { EditText(it).apply { setText("NATIVE PRIVATE 456") } }, modifier = Modifier.size(100.dp, 40.dp))
        }
        val output = sample()
        try { assertTrue(output.all { it == AndroidAnnotatedReplayCollector.PLACEHOLDER }) } finally { output.fill(0) }
    }

    @Test fun nativeSurfaceAndSecureWindowRefuseWithoutGrantingFallback() {
        var surface by mutableStateOf(false)
        install { if (surface) AndroidView(factory = { SurfaceView(it) }, modifier = Modifier.size(40.dp)) else BasicText("ORIGINAL") }
        capture().close()
        rule.runOnIdle { surface = true }; rule.waitForIdle()
        assertThrows(IllegalStateException::class.java) { capture() }
        rule.runOnIdle { surface = false }; rule.waitForIdle()
        rule.runOnIdle { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try { assertThrows(IllegalStateException::class.java) { capture() } }
        finally { rule.runOnIdle { rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }

    @Test fun currentnessDeadlineReversalAndRootCloseRefuse() {
        install { BasicText("ORIGINAL") }
        assertThrows(IllegalStateException::class.java) { capture { false } }
        rule.runOnIdle {
            for (sequence in listOf(listOf(1L, 50_000_002L), listOf(2L, 1L))) {
                val readings = sequence.iterator()
                assertThrows(IllegalStateException::class.java) {
                    collector.capture(rule.activity.window, { true }) { readings.next() }
                }
            }
        }
        val frame = capture()
        rule.runOnIdle { registry.close() }
        try { assertThrows(IllegalStateException::class.java) { frame.encodePng() } } finally { frame.close() }
        assertThrows(IllegalStateException::class.java) { capture() }
    }

    @Test fun closeAndFailedDrawDoNotResetTheOneSecondAttemptCadence() {
        var permitted = true
        install { BasicText("ORIGINAL") }
        rule.runOnIdle {
            fun attempt(at: Long) = collector.capture(rule.activity.window, { permitted }) { at }
            attempt(1_000_000_000L).close()
            assertThrows(IllegalStateException::class.java) { attempt(1_999_999_999L) }
            attempt(2_000_000_000L).close()
            registry.declare(listOf(EluReplayPrivateRegion.create()))
            assertThrows(IllegalStateException::class.java) { attempt(3_000_000_000L) }
            registry.declare(emptyList())
            assertThrows(IllegalStateException::class.java) { attempt(3_999_999_999L) }
            attempt(4_000_000_000L).close()
            permitted = false
            assertThrows(IllegalStateException::class.java) { attempt(5_000_000_000L) }
        }
    }

    @Test fun prepareDoesNotAttachAndDisplacedCloseNeverClearsAnotherOwner() {
        rule.runOnIdle {
            val original = View(rule.activity)
            val scope = EluAnnotatedReplayRootScope.prepare(original)
            assertNull(original.getTag(R.id.elu_annotated_replay_root))
            assertNull(AnnotatedRootRegistry.fromHost(original))
            assertTrue(scope.attach())
            assertSame(scope.registry, AnnotatedRootRegistry.fromHost(original))
            val foreign = Any()
            original.setTag(R.id.elu_annotated_replay_root, foreign)
            assertFalse(scope.attach())
            assertThrows(IllegalStateException::class.java) { scope.registry.host() }
            scope.close()
            assertSame(foreign, original.getTag(R.id.elu_annotated_replay_root))
        }
    }

    @Test fun competingRootsDenyBothAndOnlyOriginalOwnerClearsItsTag() {
        rule.runOnIdle {
            val original = View(rule.activity)
            val first = EluAnnotatedReplayRootScope.prepare(original)
            val second = EluAnnotatedReplayRootScope.prepare(original)
            assertTrue(first.attach())
            assertFalse(second.attach())
            assertNull(AnnotatedRootRegistry.fromHost(original))
            assertFalse(first.attach())
            second.close()
            assertSame(first.registry, original.getTag(R.id.elu_annotated_replay_root))
            first.close()
            assertNull(original.getTag(R.id.elu_annotated_replay_root))
            EluAnnotatedReplayRootScope.prepare(original).use { assertTrue(it.attach()) }
            assertNull(original.getTag(R.id.elu_annotated_replay_root))
        }
    }

    @Test fun bindingCloseCannotRetireRequiredIntentOrReviveAClosedScope() {
        rule.runOnIdle {
            val original = View(rule.activity)
            val scope = EluAnnotatedReplayRootScope.prepare(original)
            val intent = EluReplayPrivateRegion.create()
            scope.declareRequired(listOf(intent))
            assertTrue(scope.attach())
            val binding = scope.bindPrivate(intent, EluReplayGeometryReader { null })
            assertEquals(1, scope.registry.bindings().size)
            binding.close()
            assertTrue(scope.registry.bindings().isEmpty())
            assertSame(intent, scope.registry.intents.snapshot().single())
            scope.close()
            assertFalse(scope.attach())
            binding.invalidate()
            assertThrows(IllegalStateException::class.java) { scope.registry.bindings() }
        }
    }

    @Test fun freshReaderParentChangesDuringDrawRefuseEvenWithoutAnInvalidationNotification() {
        install { BasicText("ORIGINAL") }
        var reads = 0
        lateinit var replacement: AnnotatedGeometryBinding
        rule.runOnIdle {
            val original = registry.bindings().single { it.intent == null }
            val geometry = checkNotNull(original.read())
            original.close()
            val firstParent = Any(); val secondParent = Any()
            replacement = registry.bind(null, EluReplayGeometryReader {
                reads++
                EluReplayRegionGeometry(geometry.coordinateIdentity,
                    if (reads == 1) firstParent else secondParent, geometry.generation,
                    geometry.topLeftX, geometry.topLeftY, geometry.topRightX, geometry.topRightY,
                    geometry.bottomLeftX, geometry.bottomLeftY, geometry.bottomRightX, geometry.bottomRightY)
            })
        }
        try {
            assertThrows(IllegalStateException::class.java) { capture() }
            assertTrue(rule.runOnIdle { reads } >= 2)
            capture().close()
        } finally { rule.runOnIdle { replacement.close() } }
    }

    @Test fun throwingDeclaredReaderRefusesAndDoesNotPoisonCandidateOwnership() {
        install { BasicText("ORIGINAL") }
        var broken = true
        lateinit var replacement: AnnotatedGeometryBinding
        rule.runOnIdle {
            val original = registry.bindings().single { it.intent == null }
            val geometry = checkNotNull(original.read())
            original.close()
            replacement = registry.bind(null, EluReplayGeometryReader {
                if (broken) error("fixed-reader-failure") else geometry
            })
        }
        try {
            assertThrows(IllegalStateException::class.java) { capture() }
            rule.runOnIdle { broken = false }
            capture().close()
        } finally { rule.runOnIdle { replacement.close() } }
    }
}
