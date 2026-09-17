package dev.elu.analytics.internal.replay

import java.util.Collections

internal enum class NativeReplayBufferFailure { WITHDRAWN, FRAME_ORDER, INVALID_CLOCK, BUFFER_LIMIT }
internal class NativeReplayBufferException(val failure: NativeReplayBufferFailure) : IllegalArgumentException(failure.name)

/** Single-worker masked-value buffer. This is neither collection nor queue authority. */
internal class NativeReplayFrameBuffer(minimumDurationSeconds: Int) {
    private val minimumNanoseconds: Long
    private var frames: List<NativeMaskedSnapshot> = emptyList()
    private var firstContinuous: Long? = null
    private var lastContinuous: Long? = null
    private var lastFlushContinuous: Long? = null
    private var lastTimestamp: Long? = null
    private var nextOrdinal = 0L
    private var firstChunkCommitted = false
    private var ready = false
    private var terminal = false
    private var sealedPrefix: List<NativeMaskedSnapshot>? = null

    init {
        require(minimumDurationSeconds in 0..3_600) { "Invalid native minimum duration" }
        minimumNanoseconds = minimumDurationSeconds.toLong() * 1_000_000_000L
    }

    val nextFrameOrdinal: Long
        get() = if (firstChunkCommitted) nextOrdinal + frames.size else if (frames.isEmpty()) 0L else 1L
    val isReady: Boolean get() = ready && !terminal && sealedPrefix == null
    val bufferedFrames: List<NativeMaskedSnapshot> get() = frames

    fun append(frame: NativeMaskedSnapshot, continuous: Long) {
        try {
            checked(!terminal && !ready && sealedPrefix == null, NativeReplayBufferFailure.WITHDRAWN)
            checked(frame.ordinal == nextFrameOrdinal && frame.ordinal < MAX_REPLAY_SAFE_INTEGER,
                NativeReplayBufferFailure.FRAME_ORDER)
            checked(frame.timestamp in 1..253_402_300_799_999L && continuous >= 0 &&
                lastTimestamp?.let { frame.timestamp >= it } != false &&
                lastContinuous?.let { continuous >= it } != false, NativeReplayBufferFailure.INVALID_CLOCK)
            checked(frame.nodes.size <= MAXIMUM_FRAME_NODES, NativeReplayBufferFailure.BUFFER_LIMIT)
            val candidate = ArrayList(frames)
            if (!firstChunkCommitted && candidate.size == 2) candidate[1] = frame else candidate.add(frame)
            checked(candidate.size <= MAXIMUM_FRAMES, NativeReplayBufferFailure.BUFFER_LIMIT)
            var nodes = 0
            for (snapshot in candidate) {
                checked(snapshot.nodes.size <= MAXIMUM_NODES - nodes, NativeReplayBufferFailure.BUFFER_LIMIT)
                nodes += snapshot.nodes.size
            }
            // Fixed-size masked geometry/style/UUID values only. This charge is not a heap measurement.
            checked(nodes * 512L + candidate.size * 256L <= MAXIMUM_ESTIMATED_BYTES,
                NativeReplayBufferFailure.BUFFER_LIMIT)
            frames = Collections.unmodifiableList(candidate)
            if (firstContinuous == null) firstContinuous = continuous
            lastContinuous = continuous
            lastTimestamp = frame.timestamp
            ready = if (firstChunkCommitted) continuous - checkNotNull(lastFlushContinuous) >= FLUSH_NANOSECONDS
            else minimumNanoseconds == 0L || frames.size == 2 && continuous - checkNotNull(firstContinuous) >= minimumNanoseconds
        } catch (error: Throwable) {
            withdraw()
            throw error
        }
    }

    /** Retain this exact immutable prefix through sealing and its original durable admission. */
    fun beginSealing(): List<NativeMaskedSnapshot> {
        try {
            checked(isReady && frames.isNotEmpty(), NativeReplayBufferFailure.WITHDRAWN)
            return frames.also { sealedPrefix = it }
        } catch (error: Throwable) {
            withdraw()
            throw error
        }
    }

    /** Only the known committed original prefix advances ordinals. Unknown admission must withdraw. */
    fun committed(prefix: List<NativeMaskedSnapshot>) {
        try {
            checked(!terminal && prefix === sealedPrefix && prefix.isNotEmpty(), NativeReplayBufferFailure.FRAME_ORDER)
            val last = prefix.last()
            checked(last.ordinal < MAX_REPLAY_SAFE_INTEGER && lastContinuous != null, NativeReplayBufferFailure.FRAME_ORDER)
            nextOrdinal = last.ordinal + 1
            firstChunkCommitted = true
            lastFlushContinuous = lastContinuous
            frames = emptyList()
            sealedPrefix = null
            ready = false
        } catch (error: Throwable) {
            withdraw()
            throw error
        }
    }

    fun withdraw() {
        terminal = true
        ready = false
        sealedPrefix = null
        frames = emptyList()
    }

    private fun checked(allowed: Boolean, failure: NativeReplayBufferFailure) {
        if (!allowed) throw NativeReplayBufferException(failure)
    }

    companion object {
        const val MAXIMUM_FRAMES = 16
        const val MAXIMUM_NODES = 16_384
        const val MAXIMUM_FRAME_NODES = 9_999
        const val MAXIMUM_ESTIMATED_BYTES = 8_388_608L
        const val FLUSH_NANOSECONDS = 10_000_000_000L
    }
}
