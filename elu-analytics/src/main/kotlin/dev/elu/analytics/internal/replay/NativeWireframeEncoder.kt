package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value
import java.io.ByteArrayOutputStream
import java.util.UUID

/** One serialized owner per logical replay. This candidate codec is unregistered. */
internal class NativeWireframeEncoder(
    private val limits: Limits = Limits(),
    firstNodeId: Long = MINIMUM_NODE_ID,
) {
    internal data class Limits(
        val liveNodes: Int = 10_000,
        val lifetimeIds: Int = 100_000,
        val representations: Int = 100_000,
        val events: Int = 10_000,
        val decodedBytes: Int = 16_777_216,
    ) {
        init {
            nativeRequire(liveNodes in 1..10_000 && lifetimeIds in 1..100_000 && liveNodes <= lifetimeIds &&
                representations in 1..100_000 && events in 1..10_000 && decodedBytes in 2..16_777_216,
                NativeEncodingFailure.INVALID_LIMITS)
        }
    }

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
    )

    private val rootId = firstNodeId
    private var state: State

    init {
        nativeRequire(firstNodeId in MINIMUM_NODE_ID..MAXIMUM_SAFE_INTEGER, NativeEncodingFailure.COUNTER_EXHAUSTED)
        state = State(nextNodeId = if (firstNodeId == MAXIMUM_SAFE_INTEGER) null else firstNodeId + 1)
    }

    /** Independent transaction candidate for the outer sealer; no mutable collection is shared. */
    internal fun fork(): NativeWireframeEncoder = NativeWireframeEncoder(limits, rootId).also { copy ->
        // LiveNode, NativeMaskedNode/geometry/style/kind, UUID and viewport are immutable values.
        // encode replaces State and collections; copying both containers also prevents aliasing.
        copy.state = state.copy(live = state.live.toList(), retired = state.retired.toSet())
    }

    /** Candidate state is committed only after every frame and final byte limit succeeds. */
    fun encode(snapshots: List<NativeMaskedSnapshot>): NativeEncodedChunk {
        nativeRequire(snapshots.isNotEmpty() && snapshots.size <= limits.events, NativeEncodingFailure.EVENT_LIMIT)
        val frames = snapshots.toList()
        nativeRequire(state.nextSequence < MAXIMUM_SAFE_INTEGER, NativeEncodingFailure.COUNTER_EXHAUSTED)
        var next = state
        val output = Writer(limits)
        var representations = 0
        for (frame in frames) {
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
            val survivors = next.live.filter { it.value.identity in identities }
            val added = current.filter { it.value.identity !in previous }
            val byIdentity = current.associateBy { it.value.identity }
            val unchanged = survivors.all { byIdentity[it.value.identity] == it }
            val suffixOrder = current.map { it.id } == survivors.map { it.id } + added.map { it.id }
            val first = next.viewport == null
            val mutationCost = added.sumOf { generatedNodeCost(it.value.kind) }
            // The actual renderer appends replacements. Existing changes, reordering and middle
            // insertion require FullSnapshot; only removals and suffix additions use mutations.
            val full = first || !unchanged || !suffixOrder || current == next.live ||
                next.rendererBudget + mutationCost > 1_000_000
            val count = if (full) current.size + 1 else added.size
            nativeRequire(count <= limits.representations - representations, NativeEncodingFailure.REPRESENTATION_LIMIT)
            representations += count
            if (first) output.event(V1StrictCanonicalJson.canonicalBytes(obj(
                "type" to integer(4), "timestamp" to integer(frame.timestamp),
                "data" to obj("width" to integer(frame.viewport.width.toLong()), "height" to integer(frame.viewport.height.toLong())),
            )))
            val rendererBudget = if (full) {
                appendFull(output, frame, current)
                2 + current.sumOf { generatedNodeCost(it.value.kind) }
            } else {
                appendMutation(output, frame, added, next.live.filter { it.value.identity !in identities })
                next.rendererBudget + mutationCost
            }
            next = State(next.nextSequence, next.nextFrameOrdinal + 1, frame.timestamp, frame.viewport,
                current, retired, allocated, nextId, rendererBudget)
        }
        val bytes = output.finish()
        val chunk = NativeEncodedChunk(next.nextSequence, frames.first().timestamp, frames.last().timestamp,
            output.eventCount, representations, bytes)
        state = next.copy(nextSequence = next.nextSequence + 1)
        return chunk
    }

    private fun validate(frame: NativeMaskedSnapshot, previous: State) {
        nativeRequire(frame.ordinal == previous.nextFrameOrdinal && frame.ordinal < MAXIMUM_SAFE_INTEGER,
            NativeEncodingFailure.FRAME_ORDER)
        nativeRequire(frame.timestamp in 1..MAXIMUM_SAFE_INTEGER &&
            (previous.lastTimestamp == null || frame.timestamp >= previous.lastTimestamp), NativeEncodingFailure.INVALID_TIMESTAMP)
        nativeRequire(previous.viewport == null || frame.viewport == previous.viewport, NativeEncodingFailure.INVALID_VIEWPORT)
        nativeRequire(frame.nodes.size < limits.liveNodes, NativeEncodingFailure.NODE_LIMIT)
        val seen = HashSet<UUID>()
        for (node in frame.nodes) {
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
        NativeMaskedKind.Text, NativeMaskedKind.Placeholder -> 1
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

    private class Writer(private val limits: Limits) {
        private val output = ByteArrayOutputStream().apply { write('['.code) }
        var eventCount = 0
            private set
        fun text(value: String) = append(value.toByteArray(Charsets.UTF_8))
        fun append(bytes: ByteArray) {
            nativeRequire(bytes.size < limits.decodedBytes - output.size(), NativeEncodingFailure.BYTE_LIMIT)
            output.write(bytes)
        }
        fun beginEvent() {
            nativeRequire(eventCount < limits.events, NativeEncodingFailure.EVENT_LIMIT)
            if (eventCount > 0) text(",")
            eventCount += 1
        }
        fun event(bytes: ByteArray) { beginEvent(); append(bytes) }
        fun finish(): ByteArray {
            nativeRequire(output.size() < limits.decodedBytes, NativeEncodingFailure.BYTE_LIMIT)
            output.write(']'.code)
            return output.toByteArray()
        }
    }

    companion object {
        const val MINIMUM_NODE_ID: Long = 10_000_000
        const val MAXIMUM_SAFE_INTEGER: Long = 9_007_199_254_740_991
        const val MASK = "[masked]"
        const val PLACEHOLDER = "Content hidden"
    }
}
