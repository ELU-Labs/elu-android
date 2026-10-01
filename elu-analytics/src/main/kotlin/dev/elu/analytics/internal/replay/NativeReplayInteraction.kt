package dev.elu.analytics.internal.replay

import java.util.Collections
import java.util.UUID

internal enum class NativeInteractionFailure { TIMESTAMP, POINT, ORDER, CAPACITY, TARGET, GESTURE, WITHDRAWN }
internal class NativeInteractionException(val failure: NativeInteractionFailure) : IllegalArgumentException(failure.name)
internal fun interactionRequire(value: Boolean, failure: NativeInteractionFailure) {
    if (!value) throw NativeInteractionException(failure)
}

/** Detached descriptive values only. Neither construction nor encoding proves a current native hit. */
internal sealed interface NativeReplayV2Entry { val timestamp: Long }
internal data class NativeReplayV2Geometry(val frame: NativeMaskedSnapshot) : NativeReplayV2Entry {
    override val timestamp: Long get() = frame.timestamp
}

internal data class NativeReplayTouchPoint(val identity: UUID, val x: Int, val y: Int, val timestamp: Long) {
    init {
        interactionRequire(timestamp in 1..253_402_300_799_999L, NativeInteractionFailure.TIMESTAMP)
        // Encoded viewport and positive leaf clip containment are checked again by the encoder.
        interactionRequire(x in 0..16_383 && y in 0..16_383, NativeInteractionFailure.POINT)
    }
}

internal sealed interface NativeReplayInteraction : NativeReplayV2Entry {
    data class Start(val point: NativeReplayTouchPoint) : NativeReplayInteraction {
        override val timestamp: Long get() = point.timestamp
    }
    data class End(val point: NativeReplayTouchPoint) : NativeReplayInteraction {
        override val timestamp: Long get() = point.timestamp
    }
    data class Cancel(override val timestamp: Long) : NativeReplayInteraction {
        init { interactionRequire(timestamp in 1..253_402_300_799_999L, NativeInteractionFailure.TIMESTAMP) }
    }
    class Moves(points: List<NativeReplayTouchPoint>) : NativeReplayInteraction {
        val points: List<NativeReplayTouchPoint>
        override val timestamp: Long get() = this.points.last().timestamp
        init {
            interactionRequire(points.size in 1..10, NativeInteractionFailure.CAPACITY)
            this.points = Collections.unmodifiableList(ArrayList(points))
            interactionRequire(this.points.size in 1..10, NativeInteractionFailure.CAPACITY)
            interactionRequire(this.points.zipWithNext().all { (a, b) -> b.timestamp - a.timestamp >= 100 } &&
                timestamp - this.points.first().timestamp <= 900, NativeInteractionFailure.ORDER)
        }
    }
}

/** One serialized owner. No framework objects, asynchronous tasks, clocks or authority live here. */
internal class NativeReplayMoveCoalescer {
    enum class Offer { ACCEPTED, THROTTLED, FLUSH_REQUIRED }
    private var points = emptyList<NativeReplayTouchPoint>()
    private var lastAccepted: Long? = null
    private var withdrawn = false

    fun offer(point: NativeReplayTouchPoint): Offer {
        interactionRequire(!withdrawn, NativeInteractionFailure.WITHDRAWN)
        val last = lastAccepted
        interactionRequire(last == null || point.timestamp >= last, NativeInteractionFailure.ORDER)
        if (last != null && point.timestamp - last < 100) return Offer.THROTTLED
        if (points.size == 10 || points.firstOrNull()?.let { point.timestamp - it.timestamp > 900 } == true) {
            return Offer.FLUSH_REQUIRED // Caller must flush before retrying this same lawful sample.
        }
        points = points + point
        lastAccepted = point.timestamp
        return Offer.ACCEPTED
    }

    fun drain(): NativeReplayInteraction.Moves? {
        interactionRequire(!withdrawn, NativeInteractionFailure.WITHDRAWN)
        if (points.isEmpty()) return null
        return NativeReplayInteraction.Moves(points).also { points = emptyList() }
    }

    fun withdraw() { withdrawn = true; points = emptyList() }
}
