package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Pure unadvertised v2 encoder. Descriptive inputs never supply native observation or queue authority. */
internal class NativeWireframeV2Encoder(
    private val limits: NativeWireframeEncoder.Limits = NativeWireframeEncoder.Limits(),
    firstNodeId: Long = MINIMUM_NODE_ID,
    private val maskingProfile: NativeMaskingProfile = NativeMaskingProfile.blanketMask(),
) {
    private data class LiveNode(val id: Long, val value: NativeMaskedNode)
    private data class State(
        val nextSequence: Long = 0,
        val nextFrameOrdinal: Long = 0,
        val lastTimestamp: Long? = null,
        val viewport: NativeViewport? = null,
        val live: List<LiveNode> = emptyList(),
        val retired: Set<UUID> = emptySet(),
        val allocatedIds: Int = 1,
        val nextNodeId: Long?,
        val rendererBudget: Int = 0,
        val activeTarget: Long? = null,
        val lastMoveTimestamp: Long? = null,
        val lastGeometryTimestamp: Long? = null,
    )

    private val rootId = firstNodeId
    private var state: State

    init {
        nativeRequire(firstNodeId in MINIMUM_NODE_ID..MAXIMUM_SAFE_INTEGER, NativeEncodingFailure.COUNTER_EXHAUSTED)
        state = State(nextNodeId = if (firstNodeId == MAXIMUM_SAFE_INTEGER) null else firstNodeId + 1)
    }

    /** Independent transaction candidate for the outer sealer; no mutable collection is shared. */
    internal fun fork(): NativeWireframeV2Encoder = NativeWireframeV2Encoder(limits, rootId, maskingProfile).also { copy ->
        // LiveNode, NativeMaskedNode/geometry/style/kind, UUID and viewport are immutable values.
        // encode replaces State and collections; copying both containers also prevents aliasing.
        copy.state = state.copy(live = state.live.toList(), retired = state.retired.toSet())
    }

    /** Fork before sealing. A failure leaves this original state unchanged, including gesture/rate history. */
    fun encode(entries: List<NativeReplayV2Entry>): NativeEncodedChunk {
        nativeRequire(entries.isNotEmpty() && entries.size <= limits.events, NativeEncodingFailure.EVENT_LIMIT)
        val owned = entries.toList()
        nativeRequire(state.nextSequence < MAXIMUM_SAFE_INTEGER, NativeEncodingFailure.COUNTER_EXHAUSTED)
        var next = state
        val output = Writer(limits)
        var representations = 0
        for (entry in owned) {
            if (entry is NativeReplayInteraction) {
                // An initial snapshot in this same speculative candidate cannot arm touches.
                // A successful earlier encode still needs the original owner's durable commit.
                interactionRequire(state.viewport != null, NativeInteractionFailure.GESTURE)
                next = appendInteraction(output, entry, next)
                continue
            }
            val frame = (entry as NativeReplayV2Geometry).frame
            validate(frame, next)
            val previous = next.live.associateBy { it.value.identity }
            val identities = frame.nodes.map { it.identity }.toSet()
            val retired = next.retired + next.live.filter { it.value.identity !in identities }.map { it.value.identity }
            var allocated = next.allocatedIds
            var nextId = next.nextNodeId
            val current = frame.nodes.map { node ->
                val old = previous[node.identity]
                if (old != null) LiveNode(old.id, node) else {
                    nativeRequire(node.identity !in retired, NativeEncodingFailure.RETIRED_IDENTITY)
                    nativeRequire(allocated < limits.lifetimeIds, NativeEncodingFailure.NODE_LIMIT)
                    val id = nextId ?: throw NativeEncodingException(NativeEncodingFailure.COUNTER_EXHAUSTED)
                    nextId = if (id == MAXIMUM_SAFE_INTEGER) null else id + 1
                    allocated += 1
                    LiveNode(id, node)
                }
            }
            // A real cancellation must precede target removal/masking. Never manufacture one here.
            interactionRequire(next.activeTarget == null || current.any {
                it.id == next.activeTarget && lawful(it.value)
            }, NativeInteractionFailure.TARGET)
            val survivors = next.live.filter { it.value.identity in identities }
            val added = current.filter { it.value.identity !in previous }
            val byIdentity = current.associateBy { it.value.identity }
            val unchanged = survivors.all { byIdentity[it.value.identity] == it }
            val suffixOrder = current.map { it.id } == survivors.map { it.id } + added.map { it.id }
            val first = next.viewport == null
            val mutationCost = added.sumOf { generatedNodeCost(it.value.kind) }
            // Preserve the v1 geometry grammar: changed existing leaves require FullSnapshot.
            val full = first || !unchanged || !suffixOrder || current == next.live ||
                next.rendererBudget + mutationCost > 1_000_000
            val count = if (full) current.size + 1 else added.size
            nativeRequire(count <= limits.representations - representations, NativeEncodingFailure.REPRESENTATION_LIMIT)
            representations += count
            if (first) output.event(V1StrictCanonicalJson.canonicalBytes(obj(
                "type" to integer(4), "timestamp" to integer(frame.timestamp),
                "data" to obj("codec" to string(CODEC), "width" to integer(frame.viewport.width.toLong()),
                    "height" to integer(frame.viewport.height.toLong())),
            )))
            val rendererBudget = if (full) {
                appendFull(output, frame, current)
                2 + current.sumOf { generatedNodeCost(it.value.kind) }
            } else {
                appendMutation(output, frame, added, next.live.filter { it.value.identity !in identities })
                next.rendererBudget + mutationCost
            }
            next = next.copy(nextFrameOrdinal = next.nextFrameOrdinal + 1, lastTimestamp = frame.timestamp,
                viewport = frame.viewport, live = current, retired = retired, allocatedIds = allocated,
                nextNodeId = nextId, rendererBudget = rendererBudget, lastGeometryTimestamp = frame.timestamp)
        }
        val bytes = output.finish()
        val first = owned.first().let { if (it is NativeReplayInteraction.Moves) it.points.first().timestamp else it.timestamp }
        val chunk = NativeEncodedChunk(next.nextSequence, first, owned.last().timestamp,
            output.eventCount, representations, bytes)
        state = next.copy(nextSequence = next.nextSequence + 1)
        return chunk
    }

    private fun lawful(node: NativeMaskedNode): Boolean = maskingProfile.readsText &&
        (node.kind === NativeMaskedKind.Rectangle || node.kind is NativeMaskedKind.ReadableText && node.kind.text.value != MASK) &&
        node.geometry == NativeGeometryKind.VISIBLE_CLIP && node.clip.width > 0 && node.clip.height > 0

    private fun target(point: NativeReplayTouchPoint, previous: State): LiveNode {
        val viewport = previous.viewport
        interactionRequire(viewport != null && point.x < viewport.width && point.y < viewport.height,
            NativeInteractionFailure.POINT)
        val node = previous.live.singleOrNull { it.value.identity == point.identity }
        interactionRequire(node != null && lawful(node.value), NativeInteractionFailure.TARGET)
        val c = checkNotNull(node).value.clip
        interactionRequire(point.x.toDouble() >= c.x && point.x.toDouble() < c.x + c.width &&
            point.y.toDouble() >= c.y && point.y.toDouble() < c.y + c.height, NativeInteractionFailure.POINT)
        return node
    }

    private fun appendInteraction(out: Writer, event: NativeReplayInteraction, previous: State): State {
        interactionRequire(previous.viewport != null && previous.lastTimestamp != null,
            NativeInteractionFailure.GESTURE)
        val first = if (event is NativeReplayInteraction.Moves) event.points.first().timestamp else event.timestamp
        interactionRequire(first >= checkNotNull(previous.lastTimestamp), NativeInteractionFailure.ORDER)
        var active = previous.activeTarget
        var moveTime = previous.lastMoveTimestamp
        val data = when (event) {
            is NativeReplayInteraction.Start -> {
                interactionRequire(active == null, NativeInteractionFailure.GESTURE)
                val id = target(event.point, previous).id; active = id
                pointer(7, id, event.point)
            }
            is NativeReplayInteraction.End -> {
                interactionRequire(active != null, NativeInteractionFailure.GESTURE)
                val id = target(event.point, previous).id; active = null
                pointer(9, id, event.point)
            }
            is NativeReplayInteraction.Cancel -> {
                interactionRequire(active != null, NativeInteractionFailure.GESTURE)
                active = null
                obj("source" to integer(2), "type" to integer(10), "id" to integer(rootId), "pointerType" to integer(2))
            }
            is NativeReplayInteraction.Moves -> {
                interactionRequire(active != null, NativeInteractionFailure.GESTURE)
                val positions = event.points.map { point ->
                    interactionRequire(moveTime == null || point.timestamp - checkNotNull(moveTime) >= 100,
                        NativeInteractionFailure.ORDER)
                    val id = target(point, previous).id; active = id; moveTime = point.timestamp
                    obj("id" to integer(id), "x" to integer(point.x.toLong()), "y" to integer(point.y.toLong()),
                        "timeOffset" to integer(point.timestamp - event.timestamp))
                }
                obj("source" to integer(6), "positions" to Value.ArrayValue(positions))
            }
        }
        out.event(V1StrictCanonicalJson.canonicalBytes(obj("data" to data, "timestamp" to integer(event.timestamp),
            "type" to integer(3))), if (event is NativeReplayInteraction.Moves) event.points.size else 1)
        return previous.copy(lastTimestamp = event.timestamp, activeTarget = active, lastMoveTimestamp = moveTime)
    }

    private fun pointer(type: Long, id: Long, point: NativeReplayTouchPoint) =
        obj("source" to integer(2), "type" to integer(type), "id" to integer(id),
            "x" to integer(point.x.toLong()), "y" to integer(point.y.toLong()), "pointerType" to integer(2))

    private fun validate(frame: NativeMaskedSnapshot, previous: State) {
        nativeRequire(frame.ordinal == previous.nextFrameOrdinal && frame.ordinal < MAXIMUM_SAFE_INTEGER,
            NativeEncodingFailure.FRAME_ORDER)
        nativeRequire(frame.timestamp in 1..MAXIMUM_SAFE_INTEGER &&
            (previous.lastTimestamp == null || frame.timestamp >= previous.lastTimestamp), NativeEncodingFailure.INVALID_TIMESTAMP)
        // The v2 decoder applies this wire-clock ceiling to every full/mutation, across chunks.
        nativeRequire(previous.lastGeometryTimestamp?.let { frame.timestamp - it >= 200 } != false,
            NativeEncodingFailure.INVALID_TIMESTAMP)
        nativeRequire(previous.viewport == null || frame.viewport == previous.viewport, NativeEncodingFailure.INVALID_VIEWPORT)
        nativeRequire(frame.nodes.size < limits.liveNodes, NativeEncodingFailure.NODE_LIMIT)
        val seen = HashSet<UUID>()
        for (node in frame.nodes) {
            nativeRequire(node.kind !is NativeMaskedKind.ReadableText || maskingProfile.readsText, NativeEncodingFailure.INVALID_TEXT)
            nativeRequire(seen.add(node.identity), NativeEncodingFailure.DUPLICATE_IDENTITY)
            val c = node.clip
            val b = node.bounds
            nativeRequire(c.x >= 0 && c.y >= 0 && c.x + c.width <= frame.viewport.width &&
                c.y + c.height <= frame.viewport.height, NativeEncodingFailure.INVALID_GEOMETRY)
            if (c.width > 0 && c.height > 0) {
                nativeRequire(c.x >= b.x && c.y >= b.y && c.x + c.width <= b.x + b.width &&
                    c.y + c.height <= b.y + b.height, NativeEncodingFailure.INVALID_GEOMETRY)
            }
        }
    }

    private fun generatedNodeCost(kind: NativeMaskedKind): Int = when (kind) {
        NativeMaskedKind.Text, is NativeMaskedKind.ReadableText, NativeMaskedKind.Placeholder -> 1
        NativeMaskedKind.Rectangle, is NativeMaskedKind.Input -> 0
    }

    private fun obj(vararg fields: Pair<String, Value>) = Value.ObjectValue(fields.toList())
    private fun integer(value: Long) = Value.NumberValue(value.toString())
    private fun number(value: Double) = Value.NumberValue(value.toString())
    private fun string(value: String) = Value.StringValue(value)
    private fun rectangle(r: NativeRect) = obj("x" to number(r.x), "y" to number(r.y),
        "width" to number(r.width), "height" to number(r.height))
    private fun wireframe(node: LiveNode): Value {
        val n = node.value
        val b = n.bounds
        val fields = mutableListOf("id" to integer(node.id), "x" to number(b.x), "y" to number(b.y),
            "width" to number(b.width), "height" to number(b.height), "clip" to rectangle(n.clip))
        if (n.geometry == NativeGeometryKind.LAYOUT_BOUNDS) fields += "geometry" to string("layout-bounds")
        when (val kind = n.kind) {
            NativeMaskedKind.Rectangle -> fields += "type" to string("rectangle")
            NativeMaskedKind.Text -> fields.addAll(listOf("type" to string("text"), "text" to string(MASK)))
            is NativeMaskedKind.ReadableText -> fields.addAll(listOf("type" to string("text"), "text" to string(kind.text.value)))
            is NativeMaskedKind.Input -> fields.addAll(listOf("type" to string("input"),
                "inputType" to string(if (kind.secure) "password" else "text"), "disabled" to Value.BooleanValue(true),
                "value" to string(MASK)))
            NativeMaskedKind.Placeholder -> fields.addAll(listOf("type" to string("placeholder"), "label" to string(PLACEHOLDER)))
        }
        val style = mutableListOf<Pair<String, Value>>()
        n.style.color?.let { style += "color" to string(it.wireValue) }
        n.style.backgroundColor?.let { style += "backgroundColor" to string(it.wireValue) }
        n.style.fontSize?.let { style += "fontSize" to number(it) }
        n.style.fontFamily?.let { style += "fontFamily" to string(it.wireValue) }
        if (style.isNotEmpty()) fields += "style" to Value.ObjectValue(style)
        return Value.ObjectValue(fields)
    }

    private fun appendFull(out: Writer, frame: NativeMaskedSnapshot, nodes: List<LiveNode>) {
        out.beginEvent()
        out.text("{\"data\":{\"initialOffset\":{\"left\":0,\"top\":0},\"wireframes\":[{\"childWireframes\":[")
        nodes.forEachIndexed { index, node ->
            if (index > 0) out.text(",")
            out.append(V1StrictCanonicalJson.canonicalBytes(wireframe(node)))
        }
        out.text("],\"height\":${frame.viewport.height},\"id\":$rootId,\"type\":\"div\",\"width\":${frame.viewport.width},\"x\":0,\"y\":0}]},\"timestamp\":${frame.timestamp},\"type\":2}")
    }

    private fun appendMutation(out: Writer, frame: NativeMaskedSnapshot, added: List<LiveNode>, removed: List<LiveNode>) {
        out.beginEvent()
        out.text("{\"data\":{\"adds\":[")
        added.forEachIndexed { index, node ->
            if (index > 0) out.text(",")
            out.text("{\"parentId\":$rootId,\"wireframe\":")
            out.append(V1StrictCanonicalJson.canonicalBytes(wireframe(node)))
            out.text("}")
        }
        out.text("],\"removes\":[")
        removed.forEachIndexed { index, node ->
            if (index > 0) out.text(",")
            out.text("{\"id\":${node.id},\"parentId\":$rootId}")
        }
        out.text("],\"source\":0,\"updates\":[]},\"timestamp\":${frame.timestamp},\"type\":3}")
    }

    private class Writer(private val limits: NativeWireframeEncoder.Limits) {
        private val output = ByteArrayOutputStream().apply { write('['.code) }
        var eventCount = 0
            private set
        fun text(value: String) = append(value.toByteArray(Charsets.UTF_8))
        fun append(bytes: ByteArray) {
            nativeRequire(bytes.size < limits.decodedBytes - output.size(), NativeEncodingFailure.BYTE_LIMIT)
            output.write(bytes)
        }
        private var logicalCount = 0
        fun beginEvent(logicalUnits: Int = 1) {
            nativeRequire(logicalUnits in 1..limits.events && logicalCount <= limits.events - logicalUnits, NativeEncodingFailure.EVENT_LIMIT)
            logicalCount += logicalUnits
            if (eventCount > 0) text(",")
            eventCount += 1
        }
        fun event(bytes: ByteArray, logicalUnits: Int = 1) { beginEvent(logicalUnits); append(bytes) }
        fun finish(): ByteArray {
            nativeRequire(output.size() < limits.decodedBytes, NativeEncodingFailure.BYTE_LIMIT)
            output.write(']'.code)
            return output.toByteArray()
        }
    }

    companion object {
        const val CODEC = "elu-native-wireframe-v2"
        const val MINIMUM_NODE_ID: Long = 10_000_000
        const val MAXIMUM_SAFE_INTEGER: Long = 9_007_199_254_740_991
        const val MASK = "[masked]"
        const val PLACEHOLDER = "Content hidden"
    }
}
