package dev.elu.analytics.internal.replay

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NativeReplayInteractionTest {
    private val identity = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private fun point(time: Long) = NativeReplayTouchPoint(identity, 10, 20, time)

    @Test fun `coalescer bounds ten samples and retains none of a required flush retry`() {
        val c = NativeReplayMoveCoalescer()
        repeat(10) { assertEquals(NativeReplayMoveCoalescer.Offer.ACCEPTED, c.offer(point(1_000L + it * 100))) }
        assertEquals(NativeReplayMoveCoalescer.Offer.FLUSH_REQUIRED, c.offer(point(2_000)))
        val first = checkNotNull(c.drain())
        assertEquals(10, first.points.size); assertEquals(1_000L, first.points.first().timestamp)
        assertEquals(1_900L, first.timestamp)
        assertEquals(NativeReplayMoveCoalescer.Offer.ACCEPTED, c.offer(point(2_000)))
        assertEquals(listOf(point(2_000)), checkNotNull(c.drain()).points)
    }

    @Test fun `rate floor survives drains and throttle never advances accepted time`() {
        val c = NativeReplayMoveCoalescer()
        c.offer(point(1_000)); c.drain()
        assertEquals(NativeReplayMoveCoalescer.Offer.THROTTLED, c.offer(point(1_099)))
        assertNull(c.drain())
        assertEquals(NativeReplayMoveCoalescer.Offer.ACCEPTED, c.offer(point(1_100)))
        assertEquals(NativeInteractionFailure.ORDER,
            assertThrows(NativeInteractionException::class.java) { c.offer(point(1_099)) }.failure)
    }

    @Test fun `sparse batch flushes before offset exceeds nine hundred milliseconds`() {
        val c = NativeReplayMoveCoalescer(); c.offer(point(1_000))
        assertEquals(NativeReplayMoveCoalescer.Offer.FLUSH_REQUIRED, c.offer(point(1_901)))
        assertEquals(listOf(point(1_000)), checkNotNull(c.drain()).points)
        assertEquals(NativeReplayMoveCoalescer.Offer.ACCEPTED, c.offer(point(1_901)))
    }

    @Test fun `batches own immutable bounded samples and reject invalid clocks or positions`() {
        val input = mutableListOf(point(1_000), point(1_100))
        val moves = NativeReplayInteraction.Moves(input); input.clear()
        assertEquals(2, moves.points.size)
        assertThrows(UnsupportedOperationException::class.java) { (moves.points as MutableList<NativeReplayTouchPoint>).clear() }
        for (bad in listOf(emptyList(), listOf(point(1_000), point(1_099)), listOf(point(1_000), point(1_901))))
            assertThrows(NativeInteractionException::class.java) { NativeReplayInteraction.Moves(bad) }
        assertThrows(NativeInteractionException::class.java) { NativeReplayTouchPoint(identity, -1, 0, 1_000) }
        assertThrows(NativeInteractionException::class.java) { NativeReplayTouchPoint(identity, 0, 16_384, 1_000) }
        assertThrows(NativeInteractionException::class.java) { point(0) }
        assertThrows(NativeInteractionException::class.java) { NativeReplayInteraction.Cancel(0) }
    }

    @Test fun `withdrawal discards buffered coordinates and prevents later reuse`() {
        val c = NativeReplayMoveCoalescer(); c.offer(point(1_000)); c.withdraw()
        assertEquals(NativeInteractionFailure.WITHDRAWN,
            assertThrows(NativeInteractionException::class.java) { c.drain() }.failure)
        assertThrows(NativeInteractionException::class.java) { c.offer(point(1_100)) }
    }
}
