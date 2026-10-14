package dev.elu.analytics.internal.replay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import dev.elu.analytics.EluReplayRegionGeometry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Pure size/mask arithmetic. Original viewport coordinates never become image coordinates. */
internal data class AnnotatedRasterDimensions private constructor(
    val viewportWidth: Int, val viewportHeight: Int, val imageWidth: Int, val imageHeight: Int,
) {
    val scaleX: Float = imageWidth.toFloat() / viewportWidth
    val scaleY: Float = imageHeight.toFloat() / viewportHeight
    private val scaled: Boolean get() = imageWidth != viewportWidth || imageHeight != viewportHeight

    /** Same Float factors as Canvas; outward integer clipping happens before any transform. */
    fun mask(left: Double, top: Double, right: Double, bottom: Double): AnnotatedRasterMask? {
        check(listOf(left, top, right, bottom).all { it.isFinite() } && right >= left && bottom >= top)
        val l = left.coerceIn(0.0, viewportWidth.toDouble())
        val t = top.coerceIn(0.0, viewportHeight.toDouble())
        val r = right.coerceIn(0.0, viewportWidth.toDouble())
        val b = bottom.coerceIn(0.0, viewportHeight.toDouble())
        if (r <= l || b <= t) return null
        // Device-pixel margin covers rounding/sampling edges; it does not license paint
        // outside the customer's mandatory clipping wrapper or unsupported effects.
        val margin = if (scaled) 1 else 0
        return AnnotatedRasterMask(
            (floor(l * scaleX.toDouble()).toInt() - margin).coerceIn(0, imageWidth),
            (floor(t * scaleY.toDouble()).toInt() - margin).coerceIn(0, imageHeight),
            (ceil(r * scaleX.toDouble()).toInt() + margin).coerceIn(0, imageWidth),
            (ceil(b * scaleY.toDouble()).toInt() + margin).coerceIn(0, imageHeight))
    }

    companion object {
        fun fit(width: Int, height: Int): AnnotatedRasterDimensions {
            check(width in 1..AndroidAnnotatedReplayCollector.MAX_VIEWPORT_EDGE &&
                height in 1..AndroidAnnotatedReplayCollector.MAX_VIEWPORT_EDGE) { "viewport-limit" }
            val scale = minOf(1.0, AndroidAnnotatedReplayCollector.MAX_EDGE.toDouble() / width,
                AndroidAnnotatedReplayCollector.MAX_EDGE.toDouble() / height,
                sqrt(AndroidAnnotatedReplayCollector.MAX_PIXELS.toDouble() / (width.toLong() * height)))
            val imageWidth = maxOf(1, floor(width * scale).toInt())
            val imageHeight = maxOf(1, floor(height * scale).toInt())
            check(imageWidth in 1..minOf(width, AndroidAnnotatedReplayCollector.MAX_EDGE) &&
                imageHeight in 1..minOf(height, AndroidAnnotatedReplayCollector.MAX_EDGE) &&
                imageWidth.toLong() * imageHeight <= AndroidAnnotatedReplayCollector.MAX_PIXELS) { "image-limit" }
            return AnnotatedRasterDimensions(width, height, imageWidth, imageHeight)
        }
    }
}

internal data class AnnotatedRasterMask(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Original-host collector. Production invocation requires the original SDK permit. */
internal class AndroidAnnotatedReplayCollector(private val registry: AnnotatedRootRegistry) {
    companion object {
        const val PLACEHOLDER = -11971747 // opaque, content-independent #49535d
        const val MAX_PIXELS = 1_048_576
        const val MAX_EDGE = 2_048
        const val MAX_VIEWPORT_EDGE = 16_384
        const val PASS_NANOS = 50_000_000L
        const val INTERVAL_NANOS = 1_000_000_000L
    }
    private val occupied = AtomicBoolean()
    private val cleanupFailed = AtomicBoolean()
    internal fun hasCleanupFailure(): Boolean = cleanupFailed.get()
    private var lastAttempt: Long? = null // Main-thread only; close does not reset cadence.

    private class Checks(val current: () -> Boolean, val clock: () -> Long) {
        val started = clock()
        private var previous = started
        fun check() {
            val now = clock()
            kotlin.check(started >= 0 && now >= previous && now - started <= PASS_NANOS && current()) { "capture-withdrawn-or-late" }
            previous = now
        }
        fun <T> read(block: () -> T): T { check(); val value = block(); check(); return value }
    }
    private data class Witness(val binding: AnnotatedGeometryBinding, val geometry: EluReplayRegionGeometry,
        val rect: RectF, val bindingGeneration: Long)
    private data class NativeInput(val view: View, val parent: Any?)
    private data class Plan(val host: View, val parent: Any?, val decor: View, val token: Any,
        val version: Long, val policyVersion: Long, val width: Int, val height: Int,
        val screenX: Int, val screenY: Int, val viewport: Rect, val witnesses: List<Witness>,
        val dimensions: AnnotatedRasterDimensions, val inputs: List<NativeInput>, val masks: List<Rect>)

    /** Validates the original registration without allocating pixels or issuing permission. */
    internal fun prepareBinding(window: Window, current: () -> Boolean,
        clock: () -> Long = SystemClock::elapsedRealtimeNanos): AnnotatedCaptureBinding {
        AnnotatedRootRegistry.main()
        val c = Checks(current, clock)
        val original = plan(window, c)
        val root = original.witnesses.single { it.binding.intent == null }
        val source = registry.sourceIdentity(root.binding, root.geometry, window, original.decor, original.token)
        c.check()
        return AnnotatedCaptureBinding(this, source, registry, original.policyVersion)
    }

    fun capture(window: Window, current: () -> Boolean,
        clock: () -> Long = SystemClock::elapsedRealtimeNanos): AnnotatedRasterCandidate {
        AnnotatedRootRegistry.main()
        if (Build.VERSION.SDK_INT < 29) error("unsupported-platform")
        check(!cleanupFailed.get()) { "unsettled-collector-cleanup" }
        check(occupied.compareAndSet(false, true)) { "outstanding-candidate" }
        var bitmap: Bitmap? = null
        var transferred = false
        var primary: Throwable? = null
        try {
            val checks = Checks(current, clock)
            checks.check()
            lastAttempt?.let {
                check(checks.started >= it && checks.started - it >= INTERVAL_NANOS) { "capture-cadence" }
            }
            // Failed draws also consume this attempt; refusal is not permission to spin the UI.
            lastAttempt = checks.started
            val original = plan(window, checks)
            checks.check()
            val owned = Bitmap.createBitmap(original.dimensions.imageWidth, original.dimensions.imageHeight, Bitmap.Config.ARGB_8888)
            bitmap = owned
            checks.check()
            owned.eraseColor(PLACEHOLDER)
            val canvas = Canvas(owned)
            check(!canvas.isHardwareAccelerated)
            val saved = canvas.save()
            try {
                original.masks.forEach { checks.check(); canvas.clipOutRect(it) }
                canvas.scale(original.dimensions.scaleX, original.dimensions.scaleY)
                canvas.translate(-original.viewport.left.toFloat(), -original.viewport.top.toFloat())
                checks.read { original.host.draw(canvas) }
            } finally { canvas.restoreToCount(saved) }
            validate(original, plan(window, checks))
            // Validated retained output, not a claim about temporary renderer allocations.
            canvas.drawColor(PLACEHOLDER, PorterDuff.Mode.DST_OVER)
            val paint = Paint().apply { color = PLACEHOLDER; style = Paint.Style.FILL; isAntiAlias = false }
            original.masks.forEach { checks.check(); canvas.drawRect(it, paint) }
            val row = IntArray(owned.width)
            try {
                for (y in 0 until owned.height) {
                    checks.check(); owned.getPixels(row, 0, owned.width, 0, y, owned.width, 1)
                    check(row.all { Color.alpha(it) == 255 }) { "nonopaque-output" }
                    for (mask in original.masks) if (y in mask.top until mask.bottom) {
                        for (x in mask.left until mask.right) check(row[x] == PLACEHOLDER) { "unredacted-output" }
                    }
                }
            } finally { row.fill(0) }
            validate(original, plan(window, checks)); checks.check()
            owned.setHasAlpha(false)
            val acceptedPolicy = original.policyVersion
            val root = original.witnesses.single { it.binding.intent == null }
            val source = registry.sourceIdentity(root.binding, root.geometry, window, original.decor, original.token)
            checks.check()
            val result = AnnotatedRasterCandidate.validated(owned, source,
                { current() && source.isCurrent() && registry.policyCurrent(acceptedPolicy) }, { occupied.set(false) },
                viewportWidth = original.viewport.width(), viewportHeight = original.viewport.height())
            bitmap = null; transferred = true
            return result
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            try { bitmap?.let { AnnotatedRasterCandidate.clear(it) } }
            catch (cleanup: Throwable) {
                cleanupFailed.set(true)
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
            finally { if (!transferred) occupied.set(false) }
        }
    }

    private fun plan(window: Window, c: Checks): Plan {
        c.check(); if (Build.VERSION.SDK_INT < 29) error("unsupported-platform")
        val version = registry.version(); val policy = registry.policyVersion()
        val view = c.read { registry.host() }
        val decor = checkNotNull(c.read { window.peekDecorView() })
        check(c.read { window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE } == 0) { "secure-window" }
        check(c.read { view.rootView } === decor && c.read { view.hasWindowFocus() }) { "foreign-or-unfocused-host" }
        var ancestor: View? = view; var count = 0
        while (ancestor != null) {
            val item = ancestor; check(++count <= 64)
            check(c.read { item.isAttachedToWindow && item.isShown && item.alpha == 1f && item.transitionAlpha == 1f &&
                item.matrix.isIdentity && (item.animationMatrix?.isIdentity != false) && item.animation == null }) { "unsupported-native-ancestor" }
            if (item is ViewGroup) check(c.read { item.layoutTransition?.isRunning != true }) { "native-transition" }
            if (item === decor) break
            ancestor = c.read { item.parent as? View }
        }
        check(ancestor === decor) { "detached-host" }
        check(c.read { view.isLaidOut && !view.isLayoutRequested && view.width > 0 && view.height > 0 }) { "unready-host" }
        val token = checkNotNull(c.read { view.windowToken })
        val at = IntArray(2); c.read { view.getLocationInWindow(at) }
        val screen = IntArray(2); c.read { view.getLocationOnScreen(screen) }
        val required = registry.intents.snapshot()
        val nodes = registry.bindings()
        check(nodes.count { it.intent == null } == 1) { "missing-or-duplicate-root" }
        check(nodes.filter { it.intent != null }.all { node -> required.any { it === node.intent } }) { "undeclared-binding" }
        required.forEach { intent -> check(nodes.count { it.intent === intent } == 1) { "missing-or-duplicate-region" } }
        val witnesses = nodes.map { binding ->
            check(binding.owner === registry)
            val generation = binding.generation
            val geometry = checkNotNull(c.read { binding.read() }) { "unplaced-region" }
            check(!binding.isClosed && binding.generation == generation) { "stale-region" }
            Witness(binding, geometry, rectangle(geometry, at), generation)
        }
        val viewport = inward(witnesses.single { it.binding.intent == null }.rect)
        val dimensions = AnnotatedRasterDimensions.fit(viewport.width(), viewport.height())
        val width = c.read { view.width }; val height = c.read { view.height }
        check(viewport.left >= 0 && viewport.top >= 0 &&
            viewport.right <= width && viewport.bottom <= height) { "viewport-limit" }
        val visible = Rect(); val globalOffset = Point()
        check(c.read { view.getGlobalVisibleRect(visible, globalOffset) })
        check(visible.contains(Rect(viewport).apply { offset(globalOffset.x, globalOffset.y) })) { "clipped-native-root" }
        val inputs = mutableListOf<NativeInput>()
        val pending = ArrayDeque<View>(); pending.add(view); var visited = 0
        while (pending.isNotEmpty()) {
            c.check(); val child = pending.removeLast(); check(++visited <= 2048)
            if (!c.read { child.isShown } || c.read { child.alpha } == 0f) continue
            check(child !is SurfaceView && child !is TextureView) { "unsupported-native-surface" }
            if (child is EditText || (child is TextView && c.read { child.inputType } != 0)) {
                inputs += NativeInput(child, c.read { child.parent }); continue
            }
            if (child is ViewGroup) {
                val children = c.read { child.childCount }; check(children <= 2048 - visited - pending.size)
                repeat(children) { pending.add(c.read { child.getChildAt(it) }) }
            }
        }
        val masks = witnesses.filter { it.binding.intent != null }.mapNotNull {
            val clipped = RectF(it.rect)
            // The supported annotation integration confines private paint to its clipping wrapper.
            if (!clipped.intersect(RectF(viewport))) null
            else dimensions.mask(clipped.left.toDouble() - viewport.left, clipped.top.toDouble() - viewport.top,
                clipped.right.toDouble() - viewport.left, clipped.bottom.toDouble() - viewport.top)?.let { mask ->
                Rect(mask.left, mask.top, mask.right, mask.bottom)
            }
        }.toMutableList()
        if (inputs.isNotEmpty()) masks += Rect(0, 0, dimensions.imageWidth, dimensions.imageHeight)
        check(masks.size <= 64 && registry.current(version) && registry.policyCurrent(policy)) { "stale-or-excess-regions" }
        c.check()
        return Plan(view, c.read { view.parent }, decor, token, version, policy, width, height,
            screen[0], screen[1], viewport, witnesses, dimensions, inputs, masks)
    }

    private fun validate(old: Plan, fresh: Plan) {
        check(old.host === fresh.host && old.parent === fresh.parent && old.decor === fresh.decor && old.token === fresh.token &&
            old.version == fresh.version && old.policyVersion == fresh.policyVersion && old.width == fresh.width && old.height == fresh.height &&
            old.screenX == fresh.screenX && old.screenY == fresh.screenY && old.viewport == fresh.viewport && old.masks == fresh.masks &&
            old.dimensions == fresh.dimensions &&
            old.witnesses.size == fresh.witnesses.size && old.inputs.size == fresh.inputs.size) { "stale-plan" }
        old.witnesses.zip(fresh.witnesses).forEach { (a, b) ->
            check(a.binding === b.binding && a.bindingGeneration == b.bindingGeneration &&
                sameGeometry(a.geometry, b.geometry) && a.rect == b.rect) { "stale-region" }
        }
        old.inputs.zip(fresh.inputs).forEach { (a, b) -> check(a.view === b.view && a.parent === b.parent) { "stale-input" } }
    }
    private fun sameGeometry(a: EluReplayRegionGeometry, b: EluReplayRegionGeometry): Boolean =
        a.coordinateIdentity === b.coordinateIdentity && a.parentIdentity === b.parentIdentity && a.generation == b.generation &&
            a.topLeftX == b.topLeftX && a.topLeftY == b.topLeftY && a.topRightX == b.topRightX && a.topRightY == b.topRightY &&
            a.bottomLeftX == b.bottomLeftX && a.bottomLeftY == b.bottomLeftY &&
            a.bottomRightX == b.bottomRightX && a.bottomRightY == b.bottomRightY

    private fun rectangle(value: EluReplayRegionGeometry, origin: IntArray): RectF {
        val ax = value.topLeftX; val ay = value.topLeftY
        val bx = value.topRightX; val by = value.topRightY
        val dx = value.bottomLeftX; val dy = value.bottomLeftY
        val ex = value.bottomRightX; val ey = value.bottomRightY
        check(value.generation >= 0 && listOf(ax, ay, bx, by, dx, dy, ex, ey).all { it.isFinite() } &&
            abs(ay - by) < .01f && abs(ax - dx) < .01f && abs(bx - ex) < .01f && abs(dy - ey) < .01f &&
            bx > ax && dy > ay) { "unsupported-transform" }
        // Include all corners at fractional edges, including harmless numerical differences.
        return RectF(minOf(ax, bx, dx, ex) - origin[0], minOf(ay, by, dy, ey) - origin[1],
            maxOf(ax, bx, dx, ex) - origin[0], maxOf(ay, by, dy, ey) - origin[1])
    }
    private fun inward(r: RectF) = Rect(ceil(r.left).toInt(), ceil(r.top).toInt(), floor(r.right).toInt(), floor(r.bottom).toInt())
}

/** Original weak registry plus scalar/atomic currentness only; no pixels, window, grant or issuer. */
internal class AnnotatedCaptureBinding internal constructor(
    private val collector: AndroidAnnotatedReplayCollector,
    val sourceIdentity: AnnotatedRasterSourceIdentity,
    private val registry: AnnotatedRootRegistry,
    private val policyRevision: Long,
) {
    fun isCurrent(): Boolean = sourceIdentity.isCurrent() && registry.policyCurrent(policyRevision) && !collector.hasCleanupFailure()
    fun hasCleanupFailure(): Boolean = collector.hasCleanupFailure()
    fun capture(window: Window, current: () -> Boolean, clock: () -> Long): AnnotatedRasterCandidate {
        check(isCurrent()) { "withdrawn-annotated-binding" }
        // Return ownership directly. The original capture run retains this candidate before
        // any additional fallible source/post-check, then rejects a foreign source there.
        return collector.capture(window, { isCurrent() && current() }, clock)
    }
}

internal sealed interface AnnotatedRootDiscovery {
    data object Absent : AnnotatedRootDiscovery
    data object Unavailable : AnnotatedRootDiscovery
    class Bound(val binding: AnnotatedCaptureBinding) : AnnotatedRootDiscovery
}

/** Main-only original-tree discovery. A present invalid tag is never absence. */
internal fun discoverAnnotatedRoot(root: View, window: Window, current: () -> Boolean,
    clock: () -> Long = SystemClock::elapsedRealtimeNanos): AnnotatedRootDiscovery {
    AnnotatedRootRegistry.main()
    val start = clock()
    var previous = start
    fun checkCurrent() {
        val now = clock()
        check(start >= 0 && now >= previous && now - start <= AndroidAnnotatedReplayCollector.PASS_NANOS && current())
        previous = now
    }
    return try {
        val pending = ArrayDeque<Pair<View, Int>>(); pending.add(root to 0)
        var count = 0
        var registry: AnnotatedRootRegistry? = null
        while (pending.isNotEmpty()) {
            checkCurrent(); val (view, depth) = pending.removeLast()
            check(++count <= 2048 && depth <= 64)
            val tag = view.getTag(dev.elu.analytics.R.id.elu_annotated_replay_root); checkCurrent()
            if (tag != null) {
                check(registry == null)
                registry = checkNotNull(AnnotatedRootRegistry.fromHost(view))
            }
            if (view is ViewGroup) {
                val size = view.childCount; checkCurrent(); check(size <= 2048 - count - pending.size)
                repeat(size) { checkCurrent(); pending.add(view.getChildAt(it) to depth + 1); checkCurrent() }
            }
        }
        checkCurrent()
        val original = registry
        if (original == null) AnnotatedRootDiscovery.Absent
        else AnnotatedRootDiscovery.Bound(AndroidAnnotatedReplayCollector(original).prepareBinding(window, { checkCurrent(); true }, clock))
    } catch (_: Throwable) { AnnotatedRootDiscovery.Unavailable }
}
