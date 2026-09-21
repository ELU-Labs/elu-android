package dev.elu.analytics.internal.replay

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.Switch
import android.widget.ToggleButton
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal enum class NativeCollectionFailure {
    NOT_MAIN_THREAD, REENTRANT, WITHDRAWN, INVALID_ROOT, UNSUPPORTED_GEOMETRY,
    UNRESOLVED_BLOCK_RULE, TREE_CHANGED, NODE_LIMIT, DEPTH_LIMIT, PROJECTION_LIMIT,
}

/** Failure-only detail. It never admits an ancestor or changes the original collection refusal. */
internal enum class NativeAncestorFailure { FRAMEWORK_ACTION_BAR, OTHER }
internal class NativeCollectionException @JvmOverloads constructor(
    val failure: NativeCollectionFailure,
    val ancestorFailure: NativeAncestorFailure? = null,
) : IllegalStateException(failure.name)

/** Called only after the original ancestry predicate has already refused. No View getter runs here. */
internal fun unsupportedNativeAncestor(type: Class<*>): NativeCollectionException = NativeCollectionException(
    NativeCollectionFailure.UNSUPPORTED_GEOMETRY,
    if (type.name == "com.android.internal.widget.ActionBarOverlayLayout" &&
        type.classLoader === View::class.java.classLoader) NativeAncestorFailure.FRAMEWORK_ACTION_BAR
    else NativeAncestorFailure.OTHER,
)

/** Local intake withdrawal only. Original source/session authority is supplied separately by the owner. */
internal class NativeCollectionFence {
    private val monitor = Any()
    private var withdrawn = false

    fun withdraw() = synchronized(monitor) { withdrawn = true }
    fun isCurrent(): Boolean = synchronized(monitor) { !withdrawn }

    /** Only in-memory state assignment may run here; never call a View getter or external callback. */
    internal fun <T> commit(value: T, update: () -> Unit): T = synchronized(monitor) {
        if (withdrawn) throw NativeCollectionException(NativeCollectionFailure.WITHDRAWN)
        update()
        value
    }
}

internal enum class NativeViewRestriction { MASK, BLOCK }

internal class NativeViewAnnotation(view: View, val restriction: NativeViewRestriction) {
    private val reference = WeakReference(view)
    fun appliesTo(view: View): Boolean = reference.get() === view
}

/** An owned rectangle only; no drawable pixels, colors or provider callbacks are sampled. */
internal data class NativeReplayOutlineRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Owned observation only. Uncertainty never supplies a guessed cached outline rectangle. */
internal data class NativeReplayOutlineObservation(
    val clip: NativeReplayOutlineRect? = null,
    val geometry: NativeGeometryKind = NativeGeometryKind.VISIBLE_CLIP,
)

/**
 * Framework BOUNDS/PADDED_BOUNDS have closed rectangular semantics. The actual original boot
 * DecorView with an exact ColorDrawable background is represented as explicitly uncertain layout:
 * public Drawable bounds cannot prove its cached RenderNode outline. No provider callback runs.
 */
internal fun observeNativeReplayOutline(
    view: View, originalWindowDecor: View?, check: () -> Unit,
): NativeReplayOutlineObservation = observeNativeReplayOutlineProfiled(view, originalWindowDecor, check, null)

/** Same two live checks and profile samples; only the getter lambda is inlined. */
private inline fun <T> guardedNativeViewRead(
    profile: NativeCapturePassProfile?, check: () -> Unit, getter: () -> T,
): T {
    profile?.position = 1
    check()
    profile?.position = 2
    val value = getter()
    profile?.returned(); profile?.position = 3
    check()
    profile?.position = 0
    return value
}

private fun observeNativeReplayOutlineProfiled(
    view: View, originalWindowDecor: View?, check: () -> Unit, profile: NativeCapturePassProfile?,
): NativeReplayOutlineObservation {
    if (Looper.myLooper() !== Looper.getMainLooper()) throw NativeCollectionException(NativeCollectionFailure.NOT_MAIN_THREAD)
    if (!guardedNativeViewRead(profile, check) { view.clipToOutline }) return NativeReplayOutlineObservation()
    val provider = guardedNativeViewRead(profile, check) { view.outlineProvider }
    if (provider == null) return NativeReplayOutlineObservation()
    val width = guardedNativeViewRead(profile, check) { view.width }; val height = guardedNativeViewRead(profile, check) { view.height }
    val rect = when {
        provider === ViewOutlineProvider.BOUNDS -> NativeReplayOutlineRect(0, 0, width, height)
        provider === ViewOutlineProvider.PADDED_BOUNDS -> {
            val left = guardedNativeViewRead(profile, check) { view.paddingLeft }; val top = guardedNativeViewRead(profile, check) { view.paddingTop }
            val right = guardedNativeViewRead(profile, check) { view.paddingRight }; val bottom = guardedNativeViewRead(profile, check) { view.paddingBottom }
            if (min(min(left, right), min(top, bottom)) < 0) throw NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
            NativeReplayOutlineRect(left, top, width - right, height - bottom)
        }
        provider === ViewOutlineProvider.BACKGROUND -> {
            val background = guardedNativeViewRead(profile, check) { view.background }
            if (background == null) NativeReplayOutlineRect(0, 0, width, height)
            else if (Build.VERSION.SDK_INT >= 29 && view === originalWindowDecor &&
                view.javaClass.name == "com.android.internal.policy.DecorView" &&
                view.javaClass.classLoader === View::class.java.classLoader &&
                background.javaClass === ColorDrawable::class.java) {
                check()
                return NativeReplayOutlineObservation(geometry = NativeGeometryKind.LAYOUT_BOUNDS)
            } else throw NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
        }
        else -> throw NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
    }
    if (rect.left >= rect.right || rect.top >= rect.bottom) throw NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
    check()
    return NativeReplayOutlineObservation(rect)
}

/**
 * One-shot geometry collector. Authorized sensitive profiles read bounded text from known framework
 * widgets after all inherited privacy restrictions; drawable appearance and accessibility data are never read.
 * Unknown rendering is an opaque placeholder. Continuous capture and permission belong to the owner.
 * Public transition-matrix/alpha observation is unavailable before API29; legacy framework transitions
 * remain a separate integration gate. Construction requires the native recorder authority.
 */
internal class AndroidViewReplayCollector(
    private val maximumNodes: Int = 9_999,
    private val maximumDepth: Int = 64,
    private val maximumProjectionIds: Int = 99_999,
    private val newProjection: () -> UUID = UUID::randomUUID,
    private val profile: NativeCapturePassProfile? = null,
    private val maskingProfile: NativeMaskingProfile = NativeMaskingProfile.blanketMask(),
) {
    private data class Projection(val view: WeakReference<View>, val id: UUID)
    private data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double) {
        val right get() = x + width
        val bottom get() = y + height
        fun intersect(other: Bounds): Bounds {
            val left = max(x, other.x)
            val top = max(y, other.y)
            return Bounds(left, top, max(0.0, min(right, other.right) - left), max(0.0, min(bottom, other.bottom) - top))
        }
        fun wire(density: Double) = NativeRect(x / density, y / density, width / density, height / density)
    }
    private data class Geometry(val bounds: Bounds, val explicitClip: Bounds?, val hidden: Boolean, val elevation: Float = 0f, val translationZ: Float = 0f, val kind: NativeGeometryKind = NativeGeometryKind.VISIBLE_CLIP)
    private data class GroupClip(val children: Boolean, val padding: Boolean, val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class Observation(val view: View, val parent: Any?, val geometry: Geometry, val groupClip: GroupClip?, val children: List<View>?)
    private data class Work(val view: View, val parent: ViewGroup?, val clip: Bounds, val blocked: Boolean, val masked: Boolean, val depth: Int, val geometry: NativeGeometryKind)

    private var projections = emptyList<Projection>()
    private var issued = emptySet<UUID>()
    private var collecting = false

    init {
        require(maximumNodes in 1..9_999 && maximumDepth in 1..64 && maximumProjectionIds in 1..99_999)
    }

    fun collect(
        root: View,
        ordinal: Long,
        timestamp: Long,
        fence: NativeCollectionFence,
        isCurrent: () -> Boolean,
        unresolvedBlockRules: Boolean,
        annotations: List<NativeViewAnnotation> = emptyList(),
    ): NativeMaskedSnapshot {
        if (Looper.myLooper() !== Looper.getMainLooper()) fail(NativeCollectionFailure.NOT_MAIN_THREAD)
        if (collecting) fail(NativeCollectionFailure.REENTRANT)
        collecting = true
        try {
            profile?.mark(NativeCollectorStage.ROOT_PRIME)
            require(ordinal >= 0 && timestamp in 1..9_007_199_254_740_991L && annotations.size <= 128)
            if (unresolvedBlockRules) fail(NativeCollectionFailure.UNRESOLVED_BLOCK_RULE)
            val localPrivacy = NativeViewPrivacy.snapshot()
            if (localPrivacy.overflow) fail(NativeCollectionFailure.UNRESOLVED_BLOCK_RULE)
            val rules = annotations.toList() + localPrivacy.annotations
            fun check() {
                if (!localPrivacy.isCurrent() || !fence.isCurrent() || !isCurrent() || !fence.isCurrent()) fail(NativeCollectionFailure.WITHDRAWN)
            }
            val readGuard: () -> Unit = ::check
            check()
            val token = guardedNativeViewRead(profile, readGuard) { root.windowToken } ?: fail(NativeCollectionFailure.INVALID_ROOT)
            val rootParent = guardedNativeViewRead(profile, readGuard) { root.parent }
            val originalWindowDecor = guardedNativeViewRead(profile, readGuard) { root.rootView }
            val density = guardedNativeViewRead(profile, readGuard) { root.resources.displayMetrics.density }.toDouble()
            if (!density.isFinite() || density <= 0) fail(NativeCollectionFailure.INVALID_ROOT)
            val rootWidth = guardedNativeViewRead(profile, readGuard) { root.width }
            val rootHeight = guardedNativeViewRead(profile, readGuard) { root.height }
            val logicalWidth = rootWidth / density
            val logicalHeight = rootHeight / density
            if (logicalWidth <= 0 || logicalHeight <= 0 || logicalWidth > 16_384 || logicalHeight > 16_384) {
                fail(NativeCollectionFailure.INVALID_ROOT)
            }
            val viewport = NativeViewport(ceil(logicalWidth).toInt(), ceil(logicalHeight).toInt())
            val origin = IntArray(2)
            guardedNativeViewRead(profile, readGuard) { root.getLocationInWindow(origin) }
            val exactRoot = Bounds(0.0, 0.0, rootWidth.toDouble(), rootHeight.toDouble())

            fun bounds(view: View): Bounds {
                val location = IntArray(2)
                guardedNativeViewRead(profile, readGuard) { view.getLocationInWindow(location) }
                val width = guardedNativeViewRead(profile, readGuard) { view.width }
                val height = guardedNativeViewRead(profile, readGuard) { view.height }
                if (width < 0 || height < 0) fail(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
                return Bounds(location[0].toDouble() - origin[0], location[1].toDouble() - origin[1], width.toDouble(), height.toDouble())
            }
            fun geometry(view: View, stage: NativeCollectorStage = NativeCollectorStage.TREE_GEOMETRY): Geometry {
                profile?.mark(stage)
                if (!guardedNativeViewRead(profile, readGuard) { view.isAttachedToWindow } || guardedNativeViewRead(profile, readGuard) { view.windowToken } !== token) {
                    fail(NativeCollectionFailure.TREE_CHANGED)
                }
                val alpha = guardedNativeViewRead(profile, readGuard) { view.alpha }
                val visibility = guardedNativeViewRead(profile, readGuard) { view.visibility }
                val windowVisibility = guardedNativeViewRead(profile, readGuard) { view.windowVisibility }
                if (alpha == 0f || visibility != View.VISIBLE || windowVisibility != View.VISIBLE) {
                    return Geometry(bounds(view), null, true)
                }
                val elevation = guardedNativeViewRead(profile, readGuard) { view.elevation }; val translationZ = guardedNativeViewRead(profile, readGuard) { view.translationZ }
                if (!elevation.isFinite() || !translationZ.isFinite() || !(elevation + translationZ).isFinite() ||
                    (Build.VERSION.SDK_INT < 29 && (elevation != 0f || translationZ != 0f)) ||
                    alpha != 1f || !guardedNativeViewRead(profile, readGuard) { view.matrix.isIdentity } ||
                    guardedNativeViewRead(profile, readGuard) { view.animation } != null || guardedNativeViewRead(profile, readGuard) { view.layerType } != View.LAYER_TYPE_NONE) {
                    fail(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
                }
                if (Build.VERSION.SDK_INT >= 29 &&
                    (guardedNativeViewRead(profile, readGuard) { view.transitionAlpha } != 1f || guardedNativeViewRead(profile, readGuard) { view.animationMatrix }?.isIdentity == false)) {
                    fail(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
                }
                if (view is ViewGroup && (guardedNativeViewRead(profile, readGuard) { view.layoutTransition?.isRunning } == true ||
                        guardedNativeViewRead(profile, readGuard) { view.layoutAnimation } != null)) {
                    fail(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
                }
                val box = bounds(view)
                val clip = guardedNativeViewRead(profile, readGuard) { view.clipBounds }
                val explicitClip = clip?.let {
                    if (it.width() < 0 || it.height() < 0) fail(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
                    Bounds(box.x + it.left, box.y + it.top, it.width().toDouble(), it.height().toDouble())
                }
                val outlineObservation = observeNativeReplayOutlineProfiled(view, originalWindowDecor, ::check, profile)
                val outline = outlineObservation.clip?.let {
                    Bounds(box.x + it.left, box.y + it.top, it.right.toDouble() - it.left, it.bottom.toDouble() - it.top)
                }
                return Geometry(box, if (outline == null) explicitClip else explicitClip?.intersect(outline) ?: outline,
                    false, elevation, translationZ, outlineObservation.geometry)
            }
            fun groupClip(view: ViewGroup): GroupClip {
                profile?.mark(NativeCollectorStage.GROUP_CLIP)
                return GroupClip(guardedNativeViewRead(profile, readGuard) { view.clipChildren }, guardedNativeViewRead(profile, readGuard) { view.clipToPadding },
                    guardedNativeViewRead(profile, readGuard) { view.paddingLeft }, guardedNativeViewRead(profile, readGuard) { view.paddingTop }, guardedNativeViewRead(profile, readGuard) { view.paddingRight }, guardedNativeViewRead(profile, readGuard) { view.paddingBottom })
            }
            fun children(view: ViewGroup): List<View> {
                profile?.mark(NativeCollectorStage.CHILD_ORDER)
                val count = guardedNativeViewRead(profile, readGuard) { view.childCount }
                if (count < 0 || count > maximumNodes) fail(NativeCollectionFailure.NODE_LIMIT)
                val indices = HashSet<Int>()
                val ordered = (0 until count).map { position ->
                    val index = if (Build.VERSION.SDK_INT >= 29) guardedNativeViewRead(profile, readGuard) { view.getChildDrawingOrder(position) } else position
                    if (index !in 0 until count || !indices.add(index)) fail(NativeCollectionFailure.TREE_CHANGED)
                    val child = guardedNativeViewRead(profile, readGuard) { view.getChildAt(index) } ?: fail(NativeCollectionFailure.TREE_CHANGED)
                    val z = guardedNativeViewRead(profile, readGuard) { child.elevation } + guardedNativeViewRead(profile, readGuard) { child.translationZ }
                    if (!z.isFinite()) fail(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
                    child to z
                }
                // Android draws lower Z first, retaining child drawing order for equal Z. Shadows are
                // not serialized; this is the ordering of the blanket-masked rectangular content.
                return ordered.sortedWith { a, b ->
                    if (a.second < b.second) -1 else if (a.second > b.second) 1 else 0
                }.map { it.first }
            }
            val groupClips = IdentityHashMap<ViewGroup, GroupClip>()
            fun ownClip(parent: ViewGroup?, g: Geometry, inherited: Bounds): Bounds {
                var result = inherited
                if (parent != null && (groupClips[parent] ?: fail(NativeCollectionFailure.TREE_CHANGED)).children) result = result.intersect(g.bounds)
                if (g.explicitClip != null) result = result.intersect(g.explicitClip)
                return result
            }
            fun childClip(view: ViewGroup, g: Geometry, inherited: Bounds): Bounds {
                val sampled = groupClips[view] ?: fail(NativeCollectionFailure.TREE_CHANGED)
                if (!sampled.padding) return inherited
                val left = sampled.left
                val top = sampled.top
                val right = sampled.right
                val bottom = sampled.bottom
                if (left == 0 && top == 0 && right == 0 && bottom == 0) return inherited
                if (min(min(left, right), min(top, bottom)) < 0) fail(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
                return inherited.intersect(Bounds(g.bounds.x + left, g.bounds.y + top,
                    max(0.0, g.bounds.width - left - right), max(0.0, g.bounds.height - top - bottom)))
            }
            fun blocked(view: View) = rules.any { it.restriction == NativeViewRestriction.BLOCK && it.appliesTo(view) }
            fun masked(view: View) = rules.any { it.restriction == NativeViewRestriction.MASK && it.appliesTo(view) }
            fun textKind(view: TextView, hidden: Boolean): NativeMaskedKind {
                val supported = view.javaClass in setOf(TextView::class.java, Button::class.java,
                    CheckBox::class.java, RadioButton::class.java, Switch::class.java, ToggleButton::class.java)
                if (!maskingProfile.readsText || hidden || !supported) return NativeMaskedKind.Text
                // Input classification happens before the first text getter, including a password
                // transformation installed on an otherwise ordinary framework TextView.
                if (guardedNativeViewRead(profile, readGuard) { view.inputType } != InputType.TYPE_NULL ||
                    guardedNativeViewRead(profile, readGuard) { view.transformationMethod } != null) return NativeMaskedKind.Text
                // The stock Material theme draws readable secondary text at alpha 0x8a.
                // Keep transparent/faint strings private while accepting at least half-opacity
                // text; view/ancestor opacity and full glyph layout remain separately guarded.
                if (android.graphics.Color.alpha(guardedNativeViewRead(profile, readGuard) { view.currentTextColor }) < 128 ||
                    guardedNativeViewRead(profile, readGuard) { view.textSize } <= 0f ||
                    guardedNativeViewRead(profile, readGuard) { view.scrollX } != 0 ||
                    guardedNativeViewRead(profile, readGuard) { view.scrollY } != 0) return NativeMaskedKind.Text
                val layout = guardedNativeViewRead(profile, readGuard) { view.layout } ?: return NativeMaskedKind.Text
                val availableWidth = guardedNativeViewRead(profile, readGuard) { view.width - view.compoundPaddingLeft - view.compoundPaddingRight }
                val availableHeight = guardedNativeViewRead(profile, readGuard) { view.height - view.compoundPaddingTop - view.compoundPaddingBottom }
                if (guardedNativeViewRead(profile, readGuard) { layout.height } > availableHeight) return NativeMaskedKind.Text
                val lines = guardedNativeViewRead(profile, readGuard) { layout.lineCount }
                if (lines !in 1..NativeReplayText.MAXIMUM_UTF8_BYTES) return NativeMaskedKind.Text
                for (line in 0 until lines) if (guardedNativeViewRead(profile, readGuard) {
                        layout.getEllipsisCount(line) != 0 || layout.getLineWidth(line) > availableWidth
                    }) return NativeMaskedKind.Text
                val text = guardedNativeViewRead(profile, readGuard) { view.text } ?: return NativeMaskedKind.Text
                // Spans may replace or hide their underlying string. Never invoke custom
                // CharSequence methods, even on an exact framework TextView.
                if (text.javaClass !== String::class.java) return NativeMaskedKind.Text
                val length = guardedNativeViewRead(profile, readGuard) { text.length }
                if (length > NativeReplayText.MAXIMUM_UTF8_BYTES) return NativeMaskedKind.Placeholder
                if (guardedNativeViewRead(profile, readGuard) { layout.getLineEnd(lines - 1) } < length) return NativeMaskedKind.Text
                val detached = guardedNativeViewRead(profile, readGuard) { text.toString() }
                return try { NativeMaskedKind.ReadableText(NativeReplayText.read(detached)) }
                    catch (_: NativeEncodingException) { NativeMaskedKind.Placeholder }
            }

            // Inspect ancestry, not sibling contents. ViewRootImpl is a non-View terminal parent.
            profile?.mark(NativeCollectorStage.ANCESTOR_SCAN)
            val ancestors = ArrayList<View>()
            val ancestrySeen = IdentityHashMap<View, Boolean>()
            var ancestor = rootParent as? View
            var frameworkActionBar: ViewGroup? = null
            while (ancestor != null) {
                if (ancestors.size >= maximumDepth || ancestrySeen.put(ancestor, true) != null) {
                    fail(NativeCollectionFailure.DEPTH_LIMIT)
                }
                val exactActionBar = Build.VERSION.SDK_INT >= 29 && ancestor is ViewGroup &&
                    ancestor.javaClass.name == "com.android.internal.widget.ActionBarOverlayLayout" &&
                    ancestor.javaClass.classLoader === View::class.java.classLoader &&
                    ancestor === rootParent && root.javaClass === FrameLayout::class.java &&
                    guardedNativeViewRead(profile, readGuard) { root.id } == android.R.id.content
                if (exactActionBar) frameworkActionBar = ancestor as ViewGroup
                else if (!knownContainer(ancestor) && !(ancestor.javaClass.name == "com.android.internal.policy.DecorView" &&
                        ancestor.javaClass.classLoader === View::class.java.classLoader)) {
                    throw unsupportedNativeAncestor(ancestor.javaClass)
                }
                ancestors.add(ancestor)
                profile?.ancestor()
                ancestor = guardedNativeViewRead(profile, readGuard) { ancestor!!.parent } as? View
            }
            if (frameworkActionBar != null && (ancestors.lastOrNull() !== originalWindowDecor ||
                    originalWindowDecor.javaClass.name != "com.android.internal.policy.DecorView" ||
                    originalWindowDecor.javaClass.classLoader !== View::class.java.classLoader)) {
                fail(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
            }
            var inherited = exactRoot
            var ancestorBlocked = false
            var ancestorMasked = false
            // Framework decoration may paint above content. Observe actual layout coordinates and
            // every known clip, but never claim unobscured pixels or inspect ActionBar siblings.
            var inheritedGeometry = if (frameworkActionBar != null) NativeGeometryKind.LAYOUT_BOUNDS
                else NativeGeometryKind.VISIBLE_CLIP
            val observations = ArrayList<Observation>()
            for (view in ancestors.asReversed()) {
                val parent = guardedNativeViewRead(profile, readGuard) { view.parent }
                val sampledClip = (view as? ViewGroup)?.let { groupClip(it).also { clip -> groupClips[it] = clip } }
                val g = geometry(view, NativeCollectorStage.ANCESTOR_GEOMETRY)
                if (g.hidden) fail(NativeCollectionFailure.INVALID_ROOT)
                ancestorBlocked = ancestorBlocked || blocked(view)
                ancestorMasked = ancestorMasked || masked(view)
                if (g.kind == NativeGeometryKind.LAYOUT_BOUNDS) inheritedGeometry = NativeGeometryKind.LAYOUT_BOUNDS
                observations.add(Observation(view, parent, g, sampledClip, null))
                if (parent !is View) inherited = inherited.intersect(g.bounds)
                inherited = ownClip(parent as? ViewGroup, g, inherited)
                if (view is ViewGroup) inherited = childClip(view, g, inherited)
            }
            val originalRootGeometry = geometry(root, NativeCollectorStage.ROOT_GEOMETRY)

            val previous = IdentityHashMap<View, UUID>()
            for (projection in projections) projection.view.get()?.let { previous[it] = projection.id }
            val visited = IdentityHashMap<View, Boolean>()
            val nextProjections = ArrayList<Projection>()
            val nextIssued = HashSet(issued)
            val nodes = ArrayList<NativeMaskedNode>()
            val stack = ArrayList<Work>()
            stack.add(Work(root, rootParent as? ViewGroup, inherited, ancestorBlocked, ancestorMasked, 1, inheritedGeometry))
            var inspected = 0
            while (stack.isNotEmpty()) {
                check()
                val work = stack.removeAt(stack.lastIndex)
                val view = work.view
                profile?.node()
                if (++inspected > maximumNodes) fail(NativeCollectionFailure.NODE_LIMIT)
                if (work.depth > maximumDepth) fail(NativeCollectionFailure.DEPTH_LIMIT)
                val sampledParent = guardedNativeViewRead(profile, readGuard) { view.parent }
                if (visited.put(view, true) != null || sampledParent !== if (view === root) rootParent else work.parent) {
                    fail(NativeCollectionFailure.TREE_CHANGED)
                }
                val knownContainer = knownContainer(view)
                val sampledClip = (view as? ViewGroup)?.takeIf { knownContainer }?.let {
                    groupClip(it).also { clip -> groupClips[it] = clip }
                }
                val g = geometry(view)
                if (g.hidden) {
                    if (view === root) fail(NativeCollectionFailure.INVALID_ROOT)
                    observations.add(Observation(view, sampledParent, g, sampledClip, null))
                    continue
                }
                val geometryKind = if (work.geometry == NativeGeometryKind.LAYOUT_BOUNDS || g.kind == NativeGeometryKind.LAYOUT_BOUNDS)
                    NativeGeometryKind.LAYOUT_BOUNDS else NativeGeometryKind.VISIBLE_CLIP
                var clip = ownClip(work.parent, g, work.clip)
                val localBlocked = work.blocked || blocked(view)
                val localMasked = work.masked || masked(view)
                val exactClass = view.javaClass
                val kind = when {
                    localBlocked || view is ImageView || view is WebView -> NativeMaskedKind.Placeholder
                    view is EditText -> NativeMaskedKind.Input(secureInput(guardedNativeViewRead(profile, readGuard) { view.inputType }))
                    view is TextView -> textKind(view, localMasked || g.bounds != clip.intersect(g.bounds).intersect(exactRoot))
                    exactClass === View::class.java || knownContainer -> NativeMaskedKind.Rectangle
                    else -> NativeMaskedKind.Placeholder
                }
                val id = previous[view] ?: run {
                    profile?.mark(NativeCollectorStage.PROJECTION_ID); profile?.projection()
                    guardedNativeViewRead(profile, readGuard) { newProjection() }
                }.also {
                    if (nextIssued.size >= maximumProjectionIds || !nextIssued.add(it)) fail(NativeCollectionFailure.PROJECTION_LIMIT)
                }
                nextProjections.add(Projection(WeakReference(view), id))
                clip = clip.intersect(g.bounds).intersect(exactRoot)
                if (clip.width == 0.0 || clip.height == 0.0) {
                    clip = Bounds(clip.x.coerceIn(0.0, exactRoot.width), clip.y.coerceIn(0.0, exactRoot.height), 0.0, 0.0)
                }
                val wireBounds = g.bounds.wire(density)
                nodes.add(NativeMaskedNode(id, kind, wireBounds, containedClip(clip.wire(density), wireBounds, exactRoot.wire(density)),
                    if (kind === NativeMaskedKind.Placeholder) NativeStyle(backgroundColor = NativeSolidColor(255, 255, 255)) else NativeStyle(), geometryKind))
                var observedChildren: List<View>? = null
                if (!localBlocked && knownContainer) {
                    val group = view as ViewGroup
                    // Never pass the node's own visible rectangle here: unclipped child overflow is legitimate.
                    val descendantsClip = childClip(group, g, ownClip(work.parent, g, work.clip))
                    val ordered = children(group)
                    observedChildren = ordered
                    val count = ordered.size
                    if (count < 0 || count > maximumNodes - inspected - stack.size) fail(NativeCollectionFailure.NODE_LIMIT)
                    for (index in count - 1 downTo 0) {
                        stack.add(Work(ordered[index], group, descendantsClip, false, localMasked, work.depth + 1, geometryKind))
                    }
                }
                observations.add(Observation(view, sampledParent, g, sampledClip, observedChildren))
            }
            profile?.mark(NativeCollectorStage.FINAL_ROOT)
            val finalOrigin = IntArray(2)
            guardedNativeViewRead(profile, readGuard) { root.getLocationInWindow(finalOrigin) }
            val finalRoot = geometry(root, NativeCollectorStage.FINAL_ROOT)
            if (guardedNativeViewRead(profile, readGuard) { root.parent } !== rootParent || guardedNativeViewRead(profile, readGuard) { root.width } != rootWidth || guardedNativeViewRead(profile, readGuard) { root.height } != rootHeight ||
                guardedNativeViewRead(profile, readGuard) { root.resources.displayMetrics.density }.toDouble() != density || !origin.contentEquals(finalOrigin) ||
                finalRoot.hidden || finalRoot != originalRootGeometry) {
                fail(NativeCollectionFailure.TREE_CHANGED)
            }
            if (guardedNativeViewRead(profile, readGuard) { root.rootView } !== originalWindowDecor ||
                frameworkActionBar != null && guardedNativeViewRead(profile, readGuard) { root.id } != android.R.id.content) fail(NativeCollectionFailure.TREE_CHANGED)
            profile?.mark(NativeCollectorStage.FINAL_ANCESTORS)
            var finalAncestor = guardedNativeViewRead(profile, readGuard) { root.parent } as? View
            for (originalAncestor in ancestors) {
                if (finalAncestor !== originalAncestor) {
                    fail(NativeCollectionFailure.TREE_CHANGED)
                }
                finalAncestor = guardedNativeViewRead(profile, readGuard) { originalAncestor.parent } as? View
            }
            if (finalAncestor != null) fail(NativeCollectionFailure.TREE_CHANGED)
            // A later geometry callback must not silently replace an earlier sampled sibling or clip.
            // All View calls stay outside the final local withdrawal lock.
            profile?.mark(NativeCollectorStage.REVALIDATE)
            for (observed in observations) {
                if (guardedNativeViewRead(profile, readGuard) { observed.view.parent } !== observed.parent || geometry(observed.view, NativeCollectorStage.REVALIDATE) != observed.geometry) {
                    fail(NativeCollectionFailure.TREE_CHANGED)
                }
                if (observed.groupClip != null && groupClip(observed.view as ViewGroup) != observed.groupClip) {
                    fail(NativeCollectionFailure.TREE_CHANGED)
                }
                if (observed.children != null) {
                    val current = children(observed.view as ViewGroup)
                    if (current.size != observed.children.size || current.indices.any { current[it] !== observed.children[it] }) {
                        fail(NativeCollectionFailure.TREE_CHANGED)
                    }
                }
            }
            profile?.mark(NativeCollectorStage.SNAPSHOT_COMMIT)
            val snapshot = NativeMaskedSnapshot(ordinal, timestamp, viewport, nodes)
            check()
            return fence.commit(snapshot) {
                projections = nextProjections
                issued = nextIssued
            }
        } finally {
            collecting = false
        }
    }

    private fun knownContainer(view: View): Boolean {
        val type = view.javaClass
        return type === FrameLayout::class.java || type === LinearLayout::class.java ||
            type === ScrollView::class.java || type === HorizontalScrollView::class.java
    }

    /** Floating division cannot widen a clip by an ulp beyond the encoder's exact containment boundary. */
    private fun containedClip(clip: NativeRect, bounds: NativeRect, viewport: NativeRect): NativeRect {
        if (clip.width == 0.0 || clip.height == 0.0) return clip
        val right = min(bounds.x + bounds.width, viewport.width)
        val bottom = min(bounds.y + bounds.height, viewport.height)
        fun length(origin: Double, proposed: Double, limit: Double): Double {
            var value = max(0.0, min(proposed, limit - origin))
            if (origin + value > limit && value > 0) {
                value = java.lang.Double.longBitsToDouble(java.lang.Double.doubleToRawLongBits(value) - 1)
            }
            return value
        }
        return NativeRect(clip.x, clip.y, length(clip.x, clip.width, right), length(clip.y, clip.height, bottom))
    }

    private fun secureInput(flags: Int): Boolean {
        val family = flags and InputType.TYPE_MASK_CLASS
        val variation = flags and InputType.TYPE_MASK_VARIATION
        return family == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
            family == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    }

    private fun fail(failure: NativeCollectionFailure): Nothing = throw NativeCollectionException(failure)
}
