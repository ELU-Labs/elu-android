package dev.elu.analytics.compose.internal

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import dev.elu.analytics.EluAnnotatedReplayBinding
import dev.elu.analytics.EluAnnotatedReplayRootScope
import dev.elu.analytics.EluReplayGeometryReader
import dev.elu.analytics.EluReplayPrivateRegion
import dev.elu.analytics.EluReplayRegionGeometry
import java.lang.ref.WeakReference

internal class ComposeReplayNode(
    var owner: EluAnnotatedReplayRootScope,
    var intent: EluReplayPrivateRegion?,
) : Modifier.Node(), GlobalPositionAwareModifierNode {
    var coordinates: LayoutCoordinates? = null; private set
    var serial: Long = 0; private set
    private var binding: EluAnnotatedReplayBinding? = null
    private fun changed() { check(serial != Long.MAX_VALUE); serial++; binding?.invalidate() }
    private fun bind() {
        if (binding != null) return
        val weak = WeakReference(this)
        val reader = EluReplayGeometryReader { weak.get()?.readCurrentGeometry() }
        binding = intent?.let { owner.bindPrivate(it, reader) } ?: owner.bindRoot(reader)
    }
    override fun onAttach() { bind(); changed() }
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) { this.coordinates = coordinates; bind(); changed() }
    override fun onReset() { coordinates = null; changed(); binding?.close(); binding = null }
    override fun onDetach() { coordinates = null; changed(); binding?.close(); binding = null }
    fun rebind(nextOwner: EluAnnotatedReplayRootScope, nextIntent: EluReplayPrivateRegion?) {
        if (owner === nextOwner && intent === nextIntent) return
        coordinates = null; changed(); binding?.close(); binding = null
        owner = nextOwner; intent = nextIntent
        if (isAttached) bind()
        changed()
    }

    /** Actual current LayoutCoordinates, not notification-only cached rectangles. Main only. */
    private fun readCurrentGeometry(): EluReplayRegionGeometry? {
        if (!isAttached) return null
        val original = coordinates ?: return null
        if (!original.isAttached) return null
        val generation = serial
        val parent = original.parentLayoutCoordinates
        val size = original.size
        if (size.width <= 0 || size.height <= 0) return null
        val a = original.localToWindow(Offset.Zero)
        val b = original.localToWindow(Offset(size.width.toFloat(), 0f))
        val d = original.localToWindow(Offset(0f, size.height.toFloat()))
        val e = original.localToWindow(Offset(size.width.toFloat(), size.height.toFloat()))
        if (!isAttached || coordinates !== original || !original.isAttached || serial != generation ||
            original.parentLayoutCoordinates !== parent || original.size != size) return null
        return EluReplayRegionGeometry(original, parent, generation, a.x, a.y, b.x, b.y, d.x, d.y, e.x, e.y)
    }
}

private data class RegionElement(val owner: EluAnnotatedReplayRootScope, val intent: EluReplayPrivateRegion?) :
    ModifierNodeElement<ComposeReplayNode>() {
    override fun create() = ComposeReplayNode(owner, intent)
    override fun update(node: ComposeReplayNode) {
        node.rebind(owner, intent)
    }
    override fun InspectorInfo.inspectableProperties() { name = "eluDeclaredRegion" }
}

internal fun Modifier.replayRegion(owner: EluAnnotatedReplayRootScope, intent: EluReplayPrivateRegion?) =
    then(RegionElement(owner, intent))
