package dev.elu.analytics.internal.replay

import java.util.Collections

/** Pure ordered values, not capture/durability proof. Future integration must join the original queue commit. */
internal class NativeReplayV2Buffer(
    minimumDurationSeconds: Int,
    private val maximumLogicalEvents: Int = 10_000,
    private val maximumEstimatedBytes: Long = NativeReplayFrameBuffer.MAXIMUM_ESTIMATED_BYTES,
) {
    enum class Offer { ACCEPTED, FLUSH_REQUIRED, UNARMED }
    private val minimumNanoseconds: Long
    private var entries: List<NativeReplayV2Entry> = emptyList()
    private var firstContinuous: Long? = null
    private var lastContinuous: Long? = null
    private var lastFlushContinuous: Long? = null
    private var lastTimestamp: Long? = null
    private var lastGeometryTimestamp: Long? = null
    private var nextOrdinal = 0L
    private var firstCommitted = false
    private var activeGesture = false
    private var ready = false
    private var terminal = false
    private var sealedPrefix: List<NativeReplayV2Entry>? = null

    init {
        require(minimumDurationSeconds in 0..3_600 && maximumLogicalEvents in 2..10_000 &&
            maximumEstimatedBytes in 512..NativeReplayFrameBuffer.MAXIMUM_ESTIMATED_BYTES)
        minimumNanoseconds = minimumDurationSeconds.toLong() * 1_000_000_000L
    }

    val nextFrameOrdinal: Long get() = if (firstCommitted) nextOrdinal + entries.count { it is NativeReplayV2Geometry }
        else if (entries.isEmpty()) 0 else 1
    val isReady: Boolean get() = ready && !terminal && sealedPrefix == null
    val bufferedEntries: List<NativeReplayV2Entry> get() = entries

    /** FLUSH_REQUIRED retains no part of the proposed entry. Retry only after the original sealed prefix commits. */
    fun offer(entry: NativeReplayV2Entry, continuous: Long): Offer {
        try {
            checked(!terminal && sealedPrefix == null, NativeReplayBufferFailure.WITHDRAWN)
            if (entry is NativeReplayInteraction && !firstCommitted) return Offer.UNARMED
            if (entry is NativeReplayInteraction && !activeGesture && entry !is NativeReplayInteraction.Start) return Offer.UNARMED
            val finalGesture = when (entry) {
                is NativeReplayInteraction.Start -> { checked(!activeGesture, NativeReplayBufferFailure.FRAME_ORDER); true }
                is NativeReplayInteraction.End, is NativeReplayInteraction.Cancel -> false
                else -> activeGesture
            }
            val isTerminalEvent = entry is NativeReplayInteraction.End || entry is NativeReplayInteraction.Cancel
            val firstTimestamp = if (entry is NativeReplayInteraction.Moves) entry.points.first().timestamp else entry.timestamp
            checked(firstTimestamp in 1..253_402_300_799_999L && entry.timestamp in firstTimestamp..253_402_300_799_999L &&
                continuous >= 0 && lastTimestamp?.let { firstTimestamp >= it } != false &&
                lastContinuous?.let { continuous >= it } != false, NativeReplayBufferFailure.INVALID_CLOCK)
            if (entry is NativeReplayV2Geometry) {
                checked(lastGeometryTimestamp?.let { entry.timestamp - it >= 200 } != false,
                    NativeReplayBufferFailure.INVALID_CLOCK)
                checked(entry.frame.ordinal == nextFrameOrdinal && entry.frame.ordinal < MAX_REPLAY_SAFE_INTEGER,
                    NativeReplayBufferFailure.FRAME_ORDER)
                checked(entry.frame.nodes.size <= NativeReplayFrameBuffer.MAXIMUM_FRAME_NODES,
                    NativeReplayBufferFailure.BUFFER_LIMIT)
            }
            if (ready && !isTerminalEvent) return Offer.FLUSH_REQUIRED
            val candidate = ArrayList(entries)
            if (!firstCommitted && candidate.size == 2) candidate[1] = entry else candidate.add(entry)
            val reserve = firstCommitted && !isTerminalEvent
            if (!fits(candidate, reserve)) {
                if (firstCommitted && entries.isNotEmpty() && fits(listOf(entry), reserve)) {
                    ready = true
                    return Offer.FLUSH_REQUIRED
                }
                // Before minimum eligibility, pressure cannot shorten the initial duration.
                throw NativeReplayBufferException(NativeReplayBufferFailure.BUFFER_LIMIT)
            }
            entries = Collections.unmodifiableList(candidate)
            activeGesture = finalGesture
            if (firstContinuous == null) firstContinuous = continuous
            lastContinuous = continuous
            lastTimestamp = entry.timestamp
            if (entry is NativeReplayV2Geometry) lastGeometryTimestamp = entry.timestamp
            ready = ready || if (firstCommitted) continuous - checkNotNull(lastFlushContinuous) >= NativeReplayFrameBuffer.FLUSH_NANOSECONDS
                else minimumNanoseconds == 0L || entries.size == 2 && continuous - checkNotNull(firstContinuous) >= minimumNanoseconds
            return Offer.ACCEPTED
        } catch (error: Throwable) { withdraw(); throw error }
    }

    fun beginSealing(): List<NativeReplayV2Entry> {
        try {
            checked(isReady && entries.isNotEmpty(), NativeReplayBufferFailure.WITHDRAWN)
            return entries.also { sealedPrefix = it }
        } catch (error: Throwable) { withdraw(); throw error }
    }

    /** Local drain neither creates a terminal event nor supplies permission to retain an unfinished gesture. */
    fun beginDraining(): List<NativeReplayV2Entry>? {
        checked(!terminal && sealedPrefix == null, NativeReplayBufferFailure.WITHDRAWN)
        if (entries.isEmpty() || (!firstCommitted && !ready)) return null
        ready = true
        return beginSealing()
    }

    /** The exact prefix identity is necessary, not sufficient proof: only original known durable admission may call this. */
    fun committed(prefix: List<NativeReplayV2Entry>) {
        try {
            checked(!terminal && prefix === sealedPrefix && prefix.isNotEmpty(), NativeReplayBufferFailure.FRAME_ORDER)
            val lastFrame = prefix.filterIsInstance<NativeReplayV2Geometry>().lastOrNull()
            if (lastFrame != null) nextOrdinal = lastFrame.frame.ordinal + 1
            checked(firstCommitted || lastFrame != null, NativeReplayBufferFailure.FRAME_ORDER)
            firstCommitted = true
            lastFlushContinuous = checkNotNull(lastContinuous)
            entries = emptyList(); sealedPrefix = null; ready = false
        } catch (error: Throwable) { withdraw(); throw error }
    }

    fun withdraw() {
        terminal = true; entries = emptyList(); sealedPrefix = null; ready = false; activeGesture = false
    }

    private fun fits(candidate: List<NativeReplayV2Entry>, reserveTerminal: Boolean): Boolean {
        var frames = 0; var nodes = 0; var events = if (reserveTerminal) 1 else 0
        var bytes = if (reserveTerminal) 256L else 0L
        for (entry in candidate) {
            when (entry) {
                is NativeReplayV2Geometry -> {
                    frames += 1; nodes += entry.frame.nodes.size
                    events += if (entry.frame.ordinal == 0L) 2 else 1
                    bytes += 256L + entry.frame.nodes.size * 512L
                    for (node in entry.frame.nodes) (node.kind as? NativeMaskedKind.ReadableText)?.text?.let {
                        bytes += it.utf8Bytes + it.value.length * 2L
                    }
                }
                is NativeReplayInteraction.Moves -> { events += entry.points.size; bytes += 128L + entry.points.size * 128L }
                is NativeReplayInteraction -> { events += 1; bytes += 128L }
            }
            if (frames > NativeReplayFrameBuffer.MAXIMUM_FRAMES || nodes > NativeReplayFrameBuffer.MAXIMUM_NODES ||
                events > maximumLogicalEvents || bytes > maximumEstimatedBytes) return false
        }
        return true
    }

    private fun checked(value: Boolean, failure: NativeReplayBufferFailure) {
        if (!value) throw NativeReplayBufferException(failure)
    }
}
