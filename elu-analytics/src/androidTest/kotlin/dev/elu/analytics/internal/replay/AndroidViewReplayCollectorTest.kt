package dev.elu.analytics.internal.replay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AlphaAnimation
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/** Test APK host only; its declaration belongs to androidTest, never the shipping manifest. */
class NativeCollectorTestActivity : Activity()

class AndroidViewReplayCollectorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: Activity

    @Before fun start() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.context, NativeCollectorTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        // This private fixture selects a rectangular window. Production must never change host clipping.
        instrumentation.runOnMainSync { activity.window.decorView.clipToOutline = false }
    }

    @After fun finish() {
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun main(block: (FrameLayout) -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            val root = FrameLayout(activity)
            try {
                activity.setContentView(root)
                root.layout(0, 0, 600, 600)
                block(root)
            } catch (error: Throwable) {
                var ancestor: View? = root
                while (ancestor != null) {
                    val view = ancestor
                    android.util.Log.e("ELUCollectorFixture", "geometry ${view.javaClass.name} alpha=${view.alpha} matrix=${view.matrix.isIdentity} elevation=${view.elevation} z=${view.translationZ} outline=${view.clipToOutline} animation=${view.animation != null} layer=${view.layerType}")
                    ancestor = view.parent as? View
                }
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun add(parent: ViewGroup, view: View, x: Int = 20, y: Int = 20, width: Int = 80, height: Int = 40) {
        parent.addView(view, ViewGroup.LayoutParams(width, height))
        view.layout(x, y, x + width, y + height)
    }

    @Test fun transformedOrdinaryViewNeverLeaksUnderlyingText() = main { root ->
        val transformed = TextView(activity).apply {
            text = "PRIVATE_TRANSFORMED_VALUE"
            transformationMethod = object : android.text.method.TransformationMethod {
                override fun getTransformation(source: CharSequence?, view: View?) = "Hidden"
                override fun onFocusChanged(view: View?, source: CharSequence?, focused: Boolean, direction: Int, rectangle: Rect?) = Unit
            }
        }
        add(root, transformed)
        val snapshot = AndroidViewReplayCollector(maskingProfile = NativeMaskingProfile.sensitiveMask())
            .collect(root, 0, 1000, NativeCollectionFence(), { true }, false)
        assertTrue(snapshot.nodes.any { it.kind == NativeMaskedKind.Text })
        assertFalse(snapshot.nodes.any { it.kind is NativeMaskedKind.ReadableText })
    }

    @Test fun sensitiveTextRemainsReadableWhileInputsAndBlockedDescendantsStayHidden() = main { root ->
        val ordinary = TextView(activity).apply { text = "Readable ordinary text" }; add(root, ordinary, width = 500, height = 80)
        ordinary.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        ordinary.layout(20, 20, 520, 100)
        val input = EditText(activity).apply { setText("PRIVATE_INPUT") }; add(root, input, y = 110)
        val blocked = FrameLayout(activity); add(root, blocked, y = 140)
        val trap = TrapText(activity); add(blocked, trap, 0, 0); trap.armed = true
        try {
        dev.elu.analytics.Elu.blockView(blocked)
        val collector = AndroidViewReplayCollector(maskingProfile = NativeMaskingProfile.sensitiveMask())
        val first = collector.collect(root, 0, 1000, NativeCollectionFence(), { true }, false)
        assertTrue(first.nodes.any { (it.kind as? NativeMaskedKind.ReadableText)?.text?.value == "Readable ordinary text" })
        assertTrue(first.nodes.any { it.kind is NativeMaskedKind.Input })
        assertFalse(first.nodes.any { (it.kind as? NativeMaskedKind.ReadableText)?.text?.value?.contains("PRIVATE") == true })
        ordinary.text = "Updated ordinary text"
        ordinary.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        ordinary.layout(20, 20, 520, 100)
        val second = collector.collect(root, 1, 1001, NativeCollectionFence(), { true }, false)
        assertTrue(second.nodes.any { (it.kind as? NativeMaskedKind.ReadableText)?.text?.value == "Updated ordinary text" })
        dev.elu.analytics.Elu.maskView(root)
        val masked = collector.collect(root, 2, 1002, NativeCollectionFence(), { true }, false)
        assertFalse(masked.nodes.any { it.kind is NativeMaskedKind.ReadableText })
        } finally { trap.armed = false }
    }

    @Test fun layoutUncertaintyRequiresExactOriginalBootDecorAndPropagatesThroughBlockedPlaceholder() = main { root ->
        if (Build.VERSION.SDK_INT < 29) return@main
        val decor = activity.window.decorView
        val oldProvider = decor.outlineProvider; val oldBackground = decor.background
        try {
            decor.background = ColorDrawable(Color.WHITE)
            decor.outlineProvider = ViewOutlineProvider.BACKGROUND; decor.clipToOutline = true
            denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { observeNativeReplayOutline(decor, root) {} }
            denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { observeNativeReplayOutline(decor, null) {} }
            assertEquals(NativeGeometryKind.LAYOUT_BOUNDS, observeNativeReplayOutline(decor, decor) {}.geometry)
            val group = FrameLayout(activity); add(root, group)
            val trap = TrapText(activity); add(group, trap, 0, 0, 20, 20); trap.armed = true
            try {
                val result = frame(root, annotations = listOf(NativeViewAnnotation(group, NativeViewRestriction.BLOCK)))
                assertEquals(2, result.nodes.size)
                assertTrue(result.containsLayoutBounds)
                assertTrue(result.nodes.all { it.geometry == NativeGeometryKind.LAYOUT_BOUNDS })
                assertEquals(NativeMaskedKind.Placeholder, result.nodes.last().kind)
                assertFalse(String(NativeWireframeEncoder().encode(listOf(result)).bytes).contains("PRIVATE"))
            } finally { trap.armed = false } // Framework rendering after collection may lawfully read text.
        } finally { decor.clipToOutline = false; decor.outlineProvider = oldProvider; decor.background = oldBackground }
    }

    @Test fun nonDecorCannotClaimLayoutAndUnknownDrawableSubclassStaysDenied() = main { root ->
        root.clipToOutline = true; root.outlineProvider = ViewOutlineProvider.BACKGROUND
        root.background = ColorDrawable(Color.WHITE)
        denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { observeNativeReplayOutline(root, root) {} }
        if (Build.VERSION.SDK_INT >= 29) {
            val decor = activity.window.decorView; val old = decor.background
            try {
                decor.background = object : ColorDrawable(Color.WHITE) {}
                decor.clipToOutline = true
                denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { observeNativeReplayOutline(decor, decor) {} }
            } finally { decor.clipToOutline = false; decor.background = old }
        }
    }

    @Test fun outlineModeChangeDuringCollectionDiscardsOriginalFrame() = main { root ->
        if (Build.VERSION.SDK_INT < 29) return@main
        val decor = activity.window.decorView; val old = decor.background
        try {
            decor.background = ColorDrawable(Color.WHITE); decor.clipToOutline = true
            val collector = AndroidViewReplayCollector(newProjection = { decor.clipToOutline = false; UUID.randomUUID() })
            denied(NativeCollectionFailure.TREE_CHANGED) { frame(root, collector) }
            val recovered = frame(root, collector)
            assertFalse(recovered.containsLayoutBounds)
        } finally { decor.clipToOutline = false; decor.background = old }
    }

    private fun frame(root: View, collector: AndroidViewReplayCollector = AndroidViewReplayCollector(),
                      ordinal: Long = 0, fence: NativeCollectionFence = NativeCollectionFence(),
                      annotations: List<NativeViewAnnotation> = emptyList()) =
        collector.collect(root, ordinal, 1_788_883_200_000L + ordinal * 1_000, fence, { true }, false, annotations)

    private fun denied(expected: NativeCollectionFailure, block: () -> Unit) {
        try { block(); fail("Expected $expected") } catch (error: NativeCollectionException) { assertEquals(expected, error.failure) }
    }

    private class TrapText(context: Context) : TextView(context) {
        var armed = false
        override fun getText(): CharSequence { check(!armed) { "raw text accessed" }; return super.getText() }
        override fun getHint(): CharSequence? { check(!armed) { "hint accessed" }; return super.getHint() }
        override fun getContentDescription(): CharSequence? { check(!armed) { "accessibility accessed" }; return super.getContentDescription() }
    }
    private class TrapInput(context: Context) : EditText(context) {
        var armed = false
        var onNumericRead: (() -> Unit)? = null
        override fun getText(): Editable { check(!armed) { "raw input accessed" }; return super.getText() }
        override fun getHint(): CharSequence? { check(!armed) { "input hint accessed" }; return super.getHint() }
        override fun getContentDescription(): CharSequence? { check(!armed); return super.getContentDescription() }
        override fun getInputType(): Int { onNumericRead?.invoke(); return super.getInputType() }
    }
    private class OpaqueContainer(context: Context) : FrameLayout(context)
    private class GeometryTrap(context: Context) : View(context) {
        override fun getLocationInWindow(outLocation: IntArray) { error("opaque descendant visited") }
    }

    @Test fun plantedContentAndOverriddenGettersNeverReachMaskedBytes() = main { root ->
        val label = TrapText(activity).apply { text = "PRIVATE_LABEL_MARKER"; hint = "PRIVATE_HINT_MARKER"; contentDescription = "PRIVATE_A11Y_MARKER" }
        val input = TrapInput(activity).apply { setText("PRIVATE_PASSWORD_MARKER"); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        add(root, label); add(root, input, y = 80)
        label.armed = true; input.armed = true
        try {
            val captured = frame(root)
            assertEquals(NativeMaskedKind.Text, captured.nodes[1].kind)
            assertEquals(NativeMaskedKind.Input(true), captured.nodes[2].kind)
            val bytes = NativeWireframeEncoder().encode(listOf(captured)).bytes.toString(Charsets.UTF_8)
            assertFalse(bytes.contains("PRIVATE_")); assertTrue(bytes.contains("[masked]"))
            export("android-views-privacy-0.json", bytes.toByteArray())
        } finally { label.armed = false; input.armed = false }
    }

    @Test fun opaqueAndBlockedContainersDoNotVisitDescendants() = main { root ->
        val opaque = OpaqueContainer(activity); add(root, opaque); add(opaque, GeometryTrap(activity))
        val blocked = FrameLayout(activity); add(root, blocked, y = 100); add(blocked, GeometryTrap(activity))
        add(root, ImageView(activity).apply { contentDescription = "PRIVATE_IMAGE" }, y = 200)
        val captured = frame(root, annotations = listOf(NativeViewAnnotation(blocked, NativeViewRestriction.BLOCK)))
        assertEquals(4, captured.nodes.size)
        assertTrue(captured.nodes.drop(1).all { it.kind === NativeMaskedKind.Placeholder })
    }

    @Test fun webViewIsOpaqueWithoutVisitingProviderChildren() = main { root ->
        if (Build.VERSION.SDK_INT >= 26) assumeNotNull(WebView.getCurrentWebViewPackage())
        val web = WebView(activity)
        try {
            add(root, web, y = 300)
            val captured = frame(root)
            assertEquals(2, captured.nodes.size)
            assertEquals(NativeMaskedKind.Placeholder, captured.nodes.last().kind)
        } finally { web.destroy() }
    }

    @Test fun unresolvedBlockRulesDenyBeforeAnyRootGetter() {
        instrumentation.runOnMainSync {
            denied(NativeCollectionFailure.UNRESOLVED_BLOCK_RULE) {
                AndroidViewReplayCollector().collect(GeometryTrap(activity), 0, 1, NativeCollectionFence(), { true }, true)
            }
        }
    }

    @Test fun dpCoordinatesUseOneDensityAndUnroundedRootClip() = main { root ->
        root.layout(0, 0, 503, 497)
        val child = View(activity); add(root, child, 13, 17, 73, 91)
        val density = root.resources.displayMetrics.density.toDouble()
        val captured = frame(root)
        assertEquals(kotlin.math.ceil(503 / density).toInt(), captured.viewport.width)
        assertEquals(kotlin.math.ceil(497 / density).toInt(), captured.viewport.height)
        assertEquals(503 / density, captured.nodes.first().clip.width, 0.000000001)
        assertEquals(13 / density, captured.nodes[1].bounds.x, 0.000000001)
        assertEquals(73 / density, captured.nodes[1].bounds.width, 0.000000001)
        assertTrue(captured.viewport.width - captured.nodes.first().clip.width < 1.0)
    }

    @Test fun parentClipChildrenAndMiddleClipChildrenHaveIndependentMeaning() = main { root ->
        val middle = FrameLayout(activity); add(root, middle, 100, 100, 100, 100)
        val child = View(activity).apply { setBackgroundColor(Color.RED) }; add(middle, child, 80, 10, 80, 40)
        val density = root.resources.displayMetrics.density.toDouble()
        for (parentClip in listOf(false, true)) for (middleClip in listOf(false, true)) {
            root.clipChildren = parentClip; middle.clipChildren = middleClip
            root.clipToPadding = false; middle.clipToPadding = false
            val captured = frame(root)
            val node = captured.nodes.last()
            assertEquals(180 / density, node.bounds.x, 0.000000001)
            assertEquals((if (parentClip) 20 else 80) / density, node.clip.width, 0.000000001)
            // Actual static fixture pixels independently confirm whether middle's outer edge clips its child.
            val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                assertEquals(if (parentClip) Color.TRANSPARENT else Color.RED, bitmap.getPixel(230, 125))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun clipToPaddingAppliesOnlyWithNonzeroPaddingAndOwnClipRemainsIndependent() = main { root ->
        root.clipChildren = false
        val middle = FrameLayout(activity); add(root, middle, 100, 100, 100, 100)
        middle.clipChildren = false; middle.clipToPadding = true
        val child = View(activity); add(middle, child, -20, 10, 180, 40)
        val density = root.resources.displayMetrics.density.toDouble()
        assertEquals(180 / density, frame(root).nodes.last().clip.width, 0.000000001)
        middle.setPadding(10, 0, 10, 0)
        assertEquals(80 / density, frame(root).nodes.last().clip.width, 0.000000001)
        middle.clipToPadding = false; middle.clipBounds = Rect(30, 0, 70, 100)
        assertEquals(40 / density, frame(root).nodes.last().clip.width, 0.000000001)
        child.clipBounds = Rect(0, 0, 1, 1)
        assertEquals(0.0, frame(root).nodes.last().clip.width, 0.0)
    }

    @Test fun nativeScrollUsesObservedCoordinatesAndNoRawScrollEvents() = main { root ->
        val scroll = ScrollView(activity); add(root, scroll, 20, 20, 200, 100)
        val content = FrameLayout(activity); add(scroll, content, 0, 0, 200, 600)
        val child = TextView(activity); add(content, child, 10, 150, 60, 40)
        val collector = AndroidViewReplayCollector(); val encoder = NativeWireframeEncoder()
        val first = frame(root, collector)
        scroll.scrollTo(0, 100)
        val location = IntArray(2); child.getLocationInWindow(location)
        val rootLocation = IntArray(2); root.getLocationInWindow(rootLocation)
        val second = frame(root, collector, 1)
        assertEquals((location[1] - rootLocation[1]) / root.resources.displayMetrics.density.toDouble(), second.nodes.last().bounds.y, 0.000000001)
        assertEquals(first.nodes.last().identity, second.nodes.last().identity)
        export("android-views-scroll-0.json", encoder.encode(listOf(first)).bytes)
        val bytes = encoder.encode(listOf(second)).bytes
        assertFalse(bytes.toString(Charsets.UTF_8).contains("scrollOffset"))
        export("android-views-scroll-1.json", bytes)
    }

    @Test fun movementAndBringToFrontPreserveIdentityAndEncodeAFullRebuild() = main { root ->
        val a = TextView(activity); val b = TextView(activity)
        add(root, a); add(root, b, x = 40, y = 30)
        val collector = AndroidViewReplayCollector(); val encoder = NativeWireframeEncoder()
        val first = frame(root, collector); a.bringToFront(); a.layout(50, 20, 130, 60)
        val second = frame(root, collector, 1)
        assertEquals(first.nodes[1].identity, second.nodes[2].identity)
        assertEquals(first.nodes[2].identity, second.nodes[1].identity)
        export("android-views-reorder-0.json", encoder.encode(listOf(first)).bytes)
        export("android-views-reorder-1.json", encoder.encode(listOf(second)).bytes)
    }

    @Test fun deadlineGuardFailureKeepsCommittedProjectionAndDoesNotCommitNewIdentity() = main { root ->
        add(root, TextView(activity))
        var generated = 0; var deadline = false; var failNextProjection = false
        val candidate = UUID(0, 3)
        val collector = AndroidViewReplayCollector(newProjection = {
            generated++
            if (failNextProjection) { deadline = true; candidate }
            else if (generated >= 3) candidate else UUID(0, generated.toLong())
        })
        val fence = NativeCollectionFence(); val first = frame(root, collector, fence = fence)
        assertEquals(2, generated); add(root, TextView(activity)); failNextProjection = true
        denied(NativeCollectionFailure.WITHDRAWN) {
            collector.collect(root, 1, 1_788_883_201_000L, fence, { !deadline }, false)
        }
        assertTrue(fence.isCurrent()); assertEquals(3, generated)
        deadline = false; failNextProjection = false
        val next = frame(root, collector, 1, fence)
        assertEquals(4, generated) // Failed candidate was neither cached nor entered into issued IDs.
        assertEquals(first.nodes.map { it.identity }, next.nodes.take(2).map { it.identity })
        assertEquals(candidate, next.nodes.last().identity)
    }

    @Test fun successfulOmissionRetiresProjectionButFailedFrameDoesNot() = main { root ->
        val child = TextView(activity); add(root, child)
        val collector = AndroidViewReplayCollector()
        val first = frame(root, collector)
        child.alpha = 0.5f
        denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root, collector, 1) }
        child.alpha = 1f
        assertEquals(first.nodes[1].identity, frame(root, collector, 1).nodes[1].identity)
        child.visibility = View.INVISIBLE; frame(root, collector, 2)
        child.visibility = View.VISIBLE
        assertNotEquals(first.nodes[1].identity, frame(root, collector, 3).nodes[1].identity)
    }

    @Test fun collectorDoesNotRetainRemovedViews() {
        lateinit var weak: WeakReference<View>
        val collector = AndroidViewReplayCollector()
        main { root ->
            val child = View(activity); add(root, child); frame(root, collector)
            weak = WeakReference(child); root.removeView(child)
        }
        instrumentation.waitForIdleSync()
        // Destroy the private host so Android's rendering/lifecycle queues release their own references.
        instrumentation.runOnMainSync { activity.finish() }
        var destroyed = false
        repeat(40) {
            if (!destroyed) {
                instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
                if (!destroyed) Thread.sleep(50)
            }
        }
        assertTrue("Private fixture Activity did not destroy", destroyed)
        instrumentation.waitForIdleSync()
        // Keep the collector live; never fetch the referent into an ART register during the GC loop.
        synchronized(collector) {
            val before = android.os.Debug.getRuntimeStat("art.gc.gc-count")
            repeat(5) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(50) }
            val after = android.os.Debug.getRuntimeStat("art.gc.gc-count")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Retention fixture GC count: $before -> $after") })
            assertNull(weak.get())
        }
    }

    @Test fun withdrawalDuringNumericGetterDiscardsWithoutCommittingProjection() = main { root ->
        val input = TrapInput(activity); add(root, input)
        var generated = 0
        val collector = AndroidViewReplayCollector(newProjection = { generated++; UUID.randomUUID() })
        val fence = NativeCollectionFence()
        input.onNumericRead = { Thread { fence.withdraw() }.also { it.start(); it.join() } }
        try { denied(NativeCollectionFailure.WITHDRAWN) { frame(root, collector, fence = fence) } }
        finally { input.onNumericRead = null }
        val afterFailure = generated
        frame(root, collector)
        assertEquals(afterFailure + 2, generated)
    }

    @Test fun finalCommitFenceAndReentrantCollectFailClosed() = main { root ->
        val input = TrapInput(activity); add(root, input)
        val collector = AndroidViewReplayCollector()
        input.onNumericRead = { denied(NativeCollectionFailure.REENTRANT) { frame(root, collector) } }
        try { frame(root, collector) } finally { input.onNumericRead = null }
        val fence = NativeCollectionFence()
        fence.withdraw()
        denied(NativeCollectionFailure.WITHDRAWN) { fence.commit("unreachable") { fail("withdrawn commit ran") } }
    }

    @Test fun rootAndAncestorWithdrawalGeometryAreChecked() = main { root ->
        val middle = FrameLayout(activity); add(root, middle, width = 200, height = 200)
        add(middle, TextView(activity))
        root.alpha = 0f
        denied(NativeCollectionFailure.INVALID_ROOT) { frame(middle) }
        root.alpha = 1f; root.rotation = 10f
        denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(middle) }
        root.rotation = 0f
        val blocked = frame(middle, annotations = listOf(NativeViewAnnotation(root, NativeViewRestriction.BLOCK)))
        assertEquals(1, blocked.nodes.size); assertEquals(NativeMaskedKind.Placeholder, blocked.nodes.single().kind)
        root.removeView(middle)
        denied(NativeCollectionFailure.INVALID_ROOT) { frame(middle) }
    }

    @Test fun transformsAlphaUnknownOutlineAndAnimationAreRejected() = main { root ->
        val child = TextView(activity); add(root, child)
        val changes = listOf<Pair<() -> Unit, () -> Unit>>(
            { child.rotation = 10f } to { child.rotation = 0f },
            { child.alpha = 0.5f } to { child.alpha = 1f },
            {
                child.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 8f)
                }
                child.clipToOutline = true
            } to { child.clipToOutline = false; child.outlineProvider = ViewOutlineProvider.BACKGROUND },
            { child.animation = AlphaAnimation(0f, 1f) } to { child.clearAnimation() },
        )
        for ((apply, clear) in changes) {
            apply()
            try { denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root) } } finally { clear() }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            child.transitionAlpha = 0.5f
            denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root) }
            child.transitionAlpha = 1f
            child.animationMatrix = Matrix().apply { setTranslate(1f, 2f) }
            denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root) }
            child.animationMatrix = null
        }
    }

    @Test fun boundedNodesDepthAndProjectionCollisionsDoNotResetHistory() = main { root ->
        val middle = FrameLayout(activity); add(root, middle); add(middle, View(activity))
        denied(NativeCollectionFailure.NODE_LIMIT) { frame(root, AndroidViewReplayCollector(maximumNodes = 2)) }
        denied(NativeCollectionFailure.DEPTH_LIMIT) { frame(root, AndroidViewReplayCollector(maximumDepth = 2)) }
        val id = UUID.randomUUID()
        denied(NativeCollectionFailure.PROJECTION_LIMIT) { frame(root, AndroidViewReplayCollector(newProjection = { id })) }
        val collector = AndroidViewReplayCollector(maximumProjectionIds = 3)
        frame(root, collector); root.removeAllViews(); frame(root, collector, 1)
        add(root, View(activity))
        denied(NativeCollectionFailure.PROJECTION_LIMIT) { frame(root, collector, 2) }
    }

    @Test fun nonMainInvocationIsRejected() {
        denied(NativeCollectionFailure.NOT_MAIN_THREAD) { frame(View(activity)) }
    }

    @Test fun roundedWindowIsDeniedWithoutChangingItsOutline() = main { root ->
        val decor = activity.window.decorView
        val originalProvider = decor.outlineProvider
        decor.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 12f)
        }
        decor.clipToOutline = true
        try {
            denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root) }
            assertTrue(decor.clipToOutline)
        } finally { decor.clipToOutline = false; decor.outlineProvider = originalProvider }
    }

    @Test fun customAncestorCannotClaimFrameworkTraversal() = main { root ->
        val custom = OpaqueContainer(activity); add(root, custom, width = 200, height = 200)
        val selected = FrameLayout(activity); add(custom, selected)
        denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(selected) }
    }

    @Test fun laterGetterCannotMoveRemoveReparentReorderOrRevealEarlierSibling() = main { root ->
        for (mutation in 0..4) {
            root.removeAllViews()
            val holder = FrameLayout(activity); add(root, holder, width = 200, height = 200)
            val earlier = View(activity); add(root, earlier)
            val later = TrapInput(activity); add(root, later, y = 100)
            if (mutation == 4) earlier.visibility = View.INVISIBLE
            later.onNumericRead = {
                later.onNumericRead = null
                when (mutation) {
                    0 -> earlier.layout(40, 20, 120, 60)
                    1 -> root.removeView(earlier)
                    2 -> { root.removeView(earlier); add(holder, earlier) }
                    3 -> earlier.bringToFront()
                    4 -> earlier.visibility = View.VISIBLE
                }
            }
            try { denied(NativeCollectionFailure.TREE_CHANGED) { frame(root) } }
            finally { later.onNumericRead = null }
        }
    }

    @Test fun laterGetterCannotChangePreviouslyUsedClipping() = main { root ->
        for (mutation in 0..2) {
            root.removeAllViews(); root.setPadding(0, 0, 0, 0)
            root.clipChildren = true; root.clipToPadding = true
            add(root, View(activity))
            val later = TrapInput(activity); add(root, later, y = 100)
            later.onNumericRead = {
                later.onNumericRead = null
                when (mutation) {
                    0 -> root.clipChildren = false
                    1 -> root.clipToPadding = false
                    2 -> root.setPadding(10, 10, 10, 10)
                }
            }
            try { denied(NativeCollectionFailure.TREE_CHANGED) { frame(root) } }
            finally { later.onNumericRead = null }
        }
    }

    @Test fun fractionalDensityClipRemainsInsideEncoderBounds() = main { root ->
        val metrics = root.resources.displayMetrics
        val originalDensity = metrics.density
        metrics.density = 2.5f
        try {
            val child = View(activity).apply { clipBounds = Rect(1, 0, 3, 40) }
            add(root, child, x = 0, width = 3)
            val captured = frame(root)
            val node = captured.nodes.last()
            assertTrue(node.clip.x + node.clip.width <= node.bounds.x + node.bounds.width)
            assertTrue(NativeWireframeEncoder().encode(listOf(captured)).bytes.isNotEmpty())
        } finally { metrics.density = originalDensity }
    }

    @Test fun finiteDepthPreservesStableSiblingPaintOrderAndIdentity() = main { root ->
        val children = listOf(View(activity), View(activity), View(activity), View(activity))
        children.forEachIndexed { index, child -> add(root, child, x = 20 + index * 40) }
        if (Build.VERSION.SDK_INT < 29) {
            children[0].elevation = 1f
            denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root) }
            return@main
        }
        children[0].elevation = 2f
        children[1].elevation = -0.0f
        children[2].elevation = 1f; children[2].translationZ = 1f
        children[3].elevation = 1f
        val collector = AndroidViewReplayCollector()
        val before = frame(root, collector)
        val density = root.resources.displayMetrics.density.toDouble()
        assertEquals(listOf(60.0, 140.0, 20.0, 100.0).map { it / density }, before.nodes.drop(1).map { it.bounds.x })
        children[1].translationZ = 3f
        val after = frame(root, collector, 1)
        assertEquals(listOf(140.0, 20.0, 100.0, 60.0).map { it / density }, after.nodes.drop(1).map { it.bounds.x })
        assertEquals(before.nodes.associate { it.bounds.x to it.identity }, after.nodes.associate { it.bounds.x to it.identity })
        children.forEach { it.elevation = 0f; it.translationZ = 0f }
        children[0].translationZ = -0.0f
        val tied = frame(root, collector, 2)
        assertEquals(listOf(20.0, 60.0, 100.0, 140.0).map { it / density }, tied.nodes.drop(1).map { it.bounds.x })
    }

    @Test fun laterProjectionCannotChangeAnAlreadyObservedDepthWithoutDiscard() = main { root ->
        if (Build.VERSION.SDK_INT < 29) return@main
        val earlier = View(activity); val later = View(activity)
        add(root, earlier, x = 20); add(root, later, x = 100)
        var calls = 0
        val collector = AndroidViewReplayCollector(newProjection = {
            if (++calls == 3) earlier.translationZ = 1f
            UUID.randomUUID()
        })
        denied(NativeCollectionFailure.TREE_CHANGED) { frame(root, collector) }
        earlier.translationZ = 0f
        assertEquals(3, frame(root, collector).nodes.size)
        assertEquals(6, calls) // No projection from the failed frame was committed.
    }

    @Test fun trustedRectangleProvidersProjectBoundsAndPaddingWithoutReadingContent() = main { root ->
        val child = TextView(activity); add(root, child, x = 20, y = 20, width = 80, height = 40)
        child.text = "PRIVATE_RECTANGLE_CONTENT"
        child.outlineProvider = ViewOutlineProvider.BOUNDS; child.clipToOutline = true
        val fullSnapshot = frame(root)
        val full = fullSnapshot.nodes.last()
        assertEquals(full.bounds.x, full.clip.x, 0.0)
        assertEquals(full.bounds.y, full.clip.y, 0.0)
        // Fractional-density division may require one conservative ULP to keep exact containment.
        for ((expected, actual) in listOf(full.bounds.width to full.clip.width, full.bounds.height to full.clip.height)) {
            assertTrue(actual <= expected)
            assertTrue(expected - actual <= Math.ulp(expected))
        }
        assertTrue(full.clip.x + full.clip.width <= full.bounds.x + full.bounds.width)
        assertTrue(full.clip.y + full.clip.height <= full.bounds.y + full.bounds.height)
        assertEquals(2, NativeWireframeEncoder().encode(listOf(fullSnapshot)).eventCount)
        child.setPadding(3, 4, 5, 6)
        child.outlineProvider = ViewOutlineProvider.PADDED_BOUNDS
        val padded = frame(root).nodes.last()
        val density = root.resources.displayMetrics.density.toDouble()
        assertEquals(23.0 / density, padded.clip.x, 0.000001)
        assertEquals(24.0 / density, padded.clip.y, 0.000001)
        assertEquals(72.0 / density, padded.clip.width, 0.000001)
        assertEquals(30.0 / density, padded.clip.height, 0.000001)
        assertFalse(String(NativeWireframeEncoder().encode(listOf(frame(root))).bytes).contains("PRIVATE_RECTANGLE_CONTENT"))
    }

    @Test fun customOutlineCannotSpoofIdentityOrInvokeItsCallbackDuringCollection() = main { root ->
        val child = View(activity); add(root, child)
        var callbacks = 0; var equalityCalls = 0
        val custom = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { callbacks++; outline.setRect(0, 0, view.width, view.height) }
            override fun equals(other: Any?): Boolean { equalityCalls++; return true }
            override fun hashCode(): Int = 1
        }
        child.outlineProvider = custom; child.clipToOutline = true
        callbacks = 0; equalityCalls = 0
        denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root) }
        assertEquals(0, callbacks); assertEquals(0, equalityCalls)
    }

    @Test fun outlineGetterWithdrawalDiscardsAtTheOriginalFence() = main { root ->
        val fence = NativeCollectionFence()
        val child = object : View(activity) {
            override fun getOutlineProvider(): ViewOutlineProvider {
                fence.withdraw()
                return ViewOutlineProvider.BOUNDS
            }
        }
        add(root, child); child.clipToOutline = true
        denied(NativeCollectionFailure.WITHDRAWN) { frame(root, fence = fence) }
    }

    @Test fun currentBackgroundBoundsAreNotAcceptedAsACachedOutlineReceipt() = main { root ->
        val child = View(activity); add(root, child)
        val background = ColorDrawable()
        child.background = background; child.clipToOutline = true
        background.bounds = Rect(0, 0, 80, 40)
        denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root) }
        // Actual framework Drawable invalidation precedes its bounds update; public NEW bounds do
        // not prove the OLD cached outline copied by View.invalidateDrawable during this callback.
        val standalone = ColorDrawable()
        standalone.bounds = Rect(0, 0, 80, 40)
        var invalidatedBounds: Rect? = null
        standalone.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { invalidatedBounds = who.copyBounds() }
            override fun scheduleDrawable(who: Drawable, what: Runnable, whenAt: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        standalone.bounds = Rect(5, 5, 70, 30)
        assertEquals(Rect(0, 0, 80, 40), invalidatedBounds)
        assertEquals(Rect(5, 5, 70, 30), standalone.copyBounds())
    }

    @Test fun opaqueLayerCompositionStillDeniesInsteadOfGuessingPaint() = main { root ->
        val child = View(activity); add(root, child)
        for (type in listOf(View.LAYER_TYPE_HARDWARE, View.LAYER_TYPE_SOFTWARE)) {
            child.setLayerType(type, null)
            denied(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) { frame(root) }
        }
    }

    private fun export(name: String, bytes: ByteArray) {
        if (InstrumentationRegistry.getArguments().getString("eluExportFixtures") != "true") return
        val directory = File(instrumentation.context.filesDir, "native-collector-fixtures")
        check(directory.isDirectory || directory.mkdir())
        File(directory, name).writeBytes(bytes)
    }
}
