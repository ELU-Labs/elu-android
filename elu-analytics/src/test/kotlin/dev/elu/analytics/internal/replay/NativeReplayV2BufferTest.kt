package dev.elu.analytics.internal.replay

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NativeReplayV2BufferTest {
    private val id = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val time = 1_788_883_200_000L
    private fun point(offset: Long = 0) = NativeReplayTouchPoint(id, 5, 5, time + offset)
    private fun frame(ordinal: Long, offset: Long = 0, nodes: Int = 1) = NativeReplayV2Geometry(
        NativeMaskedSnapshot(ordinal, time + offset, NativeViewport(100, 100), List(nodes) {
            NativeMaskedNode(UUID(id.mostSignificantBits, id.leastSignificantBits + it), NativeMaskedKind.Rectangle,
                NativeRect(0.0, 0.0, 10.0, 10.0), NativeRect(0.0, 0.0, 10.0, 10.0))
        }))
    private fun commitInitial(buffer: NativeReplayV2Buffer) {
        assertEquals(NativeReplayV2Buffer.Offer.ACCEPTED, buffer.offer(frame(0), 0))
        buffer.committed(buffer.beginSealing()) // Test drives a pure value seam, not a real queue commit.
    }
    private fun fails(reason: NativeReplayBufferFailure, block: () -> Unit) =
        assertEquals(reason, assertThrows(NativeReplayBufferException::class.java) { block() }.failure)

    @Test fun `initial minimum is actual elapsed time and gestures wait for exact committed prefix`() {
        val b = NativeReplayV2Buffer(2); b.offer(frame(0), 0)
        assertEquals(NativeReplayV2Buffer.Offer.UNARMED, b.offer(NativeReplayInteraction.Start(point(100)), 100_000_000))
        b.offer(frame(1, 1_000), 1_000_000_000); assertFalse(b.isReady); assertNull(b.beginDraining())
        b.offer(frame(1, 2_000), 2_000_000_000); assertTrue(b.isReady)
        val prefix = b.beginSealing(); assertEquals(listOf(0L, 1L), prefix.map { (it as NativeReplayV2Geometry).frame.ordinal })
        b.committed(prefix)
        // A gesture that began before arm cannot enter as a move or end; a fresh down is required.
        assertEquals(NativeReplayV2Buffer.Offer.UNARMED, b.offer(NativeReplayInteraction.Moves(listOf(point(2_100))), 2_100_000_000))
        assertEquals(NativeReplayV2Buffer.Offer.UNARMED, b.offer(NativeReplayInteraction.End(point(2_100)), 2_100_000_000))
        assertEquals(NativeReplayV2Buffer.Offer.ACCEPTED, b.offer(NativeReplayInteraction.Start(point(2_100)), 2_100_000_000))
    }

    @Test fun `a copied prefix cannot arm and held sealing cannot collect more entries`() {
        val b = NativeReplayV2Buffer(0); b.offer(frame(0), 0); val prefix = b.beginSealing()
        val copy = prefix.toList(); assertNotSame(prefix, copy)
        fails(NativeReplayBufferFailure.FRAME_ORDER) { b.committed(copy) }
        assertTrue(b.bufferedEntries.isEmpty())
        fails(NativeReplayBufferFailure.WITHDRAWN) { b.offer(NativeReplayInteraction.Start(point()), 0) }
        val other = NativeReplayV2Buffer(0); other.offer(frame(0), 0); other.beginSealing()
        fails(NativeReplayBufferFailure.WITHDRAWN) { other.offer(frame(1, 1), 1) }
    }

    @Test fun `sixteen active geometry frames request early seal and retain space for real cancel`() {
        val b = NativeReplayV2Buffer(0); commitInitial(b)
        b.offer(NativeReplayInteraction.Start(point(1)), 1_000_000)
        for (ordinal in 1L..16L) assertEquals(NativeReplayV2Buffer.Offer.ACCEPTED,
            b.offer(frame(ordinal, ordinal * 200), ordinal * 200_000_000))
        assertFalse(b.isReady)
        assertEquals(NativeReplayV2Buffer.Offer.FLUSH_REQUIRED, b.offer(frame(17, 3_400), 3_400_000_000))
        assertEquals(16, b.bufferedEntries.count { it is NativeReplayV2Geometry })
        assertEquals(NativeReplayV2Buffer.Offer.ACCEPTED,
            b.offer(NativeReplayInteraction.Cancel(time + 3_400), 3_400_000_000))
        val prefix = b.beginSealing(); assertTrue(prefix.last() is NativeReplayInteraction.Cancel)
        b.committed(prefix); assertEquals(17L, b.nextFrameOrdinal)
        assertEquals(NativeReplayV2Buffer.Offer.ACCEPTED, b.offer(frame(17, 3_400), 3_400_000_000))
    }

    @Test fun `logical movement count triggers flush without retaining or discarding the proposed batch`() {
        val b = NativeReplayV2Buffer(0, maximumLogicalEvents = 4); commitInitial(b)
        b.offer(NativeReplayInteraction.Start(point(1)), 1_000_000)
        val moves = NativeReplayInteraction.Moves(listOf(point(100), point(200), point(300)))
        assertEquals(NativeReplayV2Buffer.Offer.FLUSH_REQUIRED, b.offer(moves, 300_000_000))
        assertEquals(1, b.bufferedEntries.size); b.committed(b.beginSealing())
        assertEquals(NativeReplayV2Buffer.Offer.ACCEPTED, b.offer(moves, 300_000_000))
        assertEquals(NativeReplayV2Buffer.Offer.ACCEPTED, b.offer(NativeReplayInteraction.Cancel(time + 301), 301_000_000))
        val tail = checkNotNull(b.beginDraining()); assertSame(moves, tail.first())
        assertEquals(2, tail.size) // Three samples plus real cancel consume exactly four logical events.
    }

    @Test fun `byte and node pressure early seals after arm but never shortens initial minimum`() {
        val b = NativeReplayV2Buffer(0, maximumEstimatedBytes = 1_100); commitInitial(b)
        b.offer(frame(1, 200), 200_000_000)
        assertEquals(NativeReplayV2Buffer.Offer.FLUSH_REQUIRED, b.offer(frame(2, 400), 400_000_000))
        assertEquals(1, b.bufferedEntries.size)
        val initial = NativeReplayV2Buffer(2, maximumEstimatedBytes = 1_100)
        initial.offer(frame(0), 0)
        fails(NativeReplayBufferFailure.BUFFER_LIMIT) { initial.offer(frame(1, 1_000), 1_000_000_000) }
        assertFalse(initial.isReady); assertTrue(initial.bufferedEntries.isEmpty())
        val nodes = NativeReplayV2Buffer(0); commitInitial(nodes)
        nodes.offer(frame(1, 200, 8_000), 200_000_000)
        assertEquals(NativeReplayV2Buffer.Offer.FLUSH_REQUIRED, nodes.offer(frame(2, 400, 8_400), 400_000_000))
        assertEquals(8_000, (nodes.bufferedEntries.single() as NativeReplayV2Geometry).frame.nodes.size)
    }

    @Test fun `encoded order includes earliest movement sample and clocks never roll back`() {
        val b = NativeReplayV2Buffer(0); commitInitial(b)
        b.offer(NativeReplayInteraction.Start(point(200)), 200_000_000)
        fails(NativeReplayBufferFailure.INVALID_CLOCK) {
            b.offer(NativeReplayInteraction.Moves(listOf(point(100), point(300))), 300_000_000)
        }
        val other = NativeReplayV2Buffer(0); commitInitial(other)
        other.offer(NativeReplayInteraction.Start(point(200)), 200_000_000)
        fails(NativeReplayBufferFailure.INVALID_CLOCK) { other.offer(NativeReplayInteraction.Cancel(time + 201), 199_999_999) }
    }

    @Test fun `local drain adds no fake end and withdrawal never reopens an armed gesture`() {
        val b = NativeReplayV2Buffer(0); commitInitial(b)
        b.offer(NativeReplayInteraction.Start(point(1)), 1_000_000)
        val tail = checkNotNull(b.beginDraining()); assertEquals(1, tail.size)
        assertTrue(tail.single() is NativeReplayInteraction.Start)
        b.withdraw(); assertTrue(b.bufferedEntries.isEmpty())
        fails(NativeReplayBufferFailure.WITHDRAWN) { b.committed(tail) }
    }

    @Test fun `elapsed time does not authorize a geometry wire timestamp inside the rate floor`() {
        val b = NativeReplayV2Buffer(0); commitInitial(b)
        fails(NativeReplayBufferFailure.INVALID_CLOCK) { b.offer(frame(1, 199), 2_000_000_000) }
        assertTrue(b.bufferedEntries.isEmpty())
        val valid = NativeReplayV2Buffer(0); commitInitial(valid)
        assertEquals(NativeReplayV2Buffer.Offer.ACCEPTED, valid.offer(frame(1, 200), 2_000_000_000))
    }
}
