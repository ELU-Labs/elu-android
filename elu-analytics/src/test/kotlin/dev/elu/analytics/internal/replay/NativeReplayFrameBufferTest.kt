package dev.elu.analytics.internal.replay

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NativeReplayFrameBufferTest {
    private val identity = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private fun frame(ordinal: Long, timestamp: Long = 1_000L, nodes: Int = 1): NativeMaskedSnapshot =
        NativeMaskedSnapshot(ordinal, timestamp, NativeViewport(100, 200), List(nodes) {
            NativeMaskedNode(if (it == 0) identity else UUID(0, it.toLong() + 1), NativeMaskedKind.Rectangle,
                NativeRect(0.0, 0.0, 10.0, 10.0), NativeRect(0.0, 0.0, 10.0, 10.0))
        })
    private fun fails(expected: NativeReplayBufferFailure, action: () -> Unit) {
        try { action(); fail("Expected $expected") } catch (error: NativeReplayBufferException) { assertEquals(expected, error.failure) }
    }
    private fun commitInitial(buffer: NativeReplayFrameBuffer, continuous: Long = 0) {
        buffer.append(frame(0), continuous)
        buffer.committed(buffer.beginSealing())
    }

    @Test fun `positive minimum retains original initial and only the latest without consuming ordinals`() {
        val buffer = NativeReplayFrameBuffer(5)
        val initial = frame(0, 1001); buffer.append(initial, 100)
        for (second in 1L..4) {
            assertEquals(1L, buffer.nextFrameOrdinal)
            buffer.append(frame(1, 1001 + second), 100 + second * 1_000_000_000)
            assertFalse(buffer.isReady); assertEquals(2, buffer.bufferedFrames.size)
        }
        val latest = frame(1, 1006); buffer.append(latest, 5_000_000_100)
        assertTrue(buffer.isReady)
        val prefix = buffer.beginSealing()
        assertSame(initial, prefix[0]); assertSame(latest, prefix[1])
        assertEquals(listOf(0L, 1L), prefix.map { it.ordinal })
        assertEquals(listOf(1001L, 1006L), prefix.map { it.timestamp })
        val encoded = NativeWireframeEncoder().encode(prefix)
        assertEquals(1001L, encoded.firstTimestamp); assertEquals(1006L, encoded.lastTimestamp)
        buffer.committed(prefix); assertEquals(2L, buffer.nextFrameOrdinal)
    }
    @Test fun `zero minimum seals the original first frame and does not duplicate it`() {
        val buffer = NativeReplayFrameBuffer(0); val original = frame(0, 1234)
        buffer.append(original, 9); assertTrue(buffer.isReady)
        val prefix = buffer.beginSealing(); assertEquals(1, prefix.size); assertSame(original, prefix.single())
        buffer.committed(prefix); assertEquals(1L, buffer.nextFrameOrdinal)
        buffer.append(frame(1, 1235), 10); assertFalse(buffer.isReady)
    }
    @Test fun `positive minimum uses first continuous instant including exact boundary`() {
        val buffer = NativeReplayFrameBuffer(1)
        buffer.append(frame(0), 40)
        buffer.append(frame(1), 1_000_000_039); assertFalse(buffer.isReady)
        buffer.append(frame(1), 1_000_000_040); assertTrue(buffer.isReady)
    }
    @Test fun `wall jumps cannot satisfy positive minimum but equal clocks are legal`() {
        val buffer = NativeReplayFrameBuffer(1)
        buffer.append(frame(0, 1000), 0)
        buffer.append(frame(1, 1_000_000), 0); assertFalse(buffer.isReady)
        buffer.append(frame(1, 1_000_000), 1_000_000_000); assertTrue(buffer.isReady)
    }
    @Test fun `later chunks wait ten seconds from last committed sample and preserve every retained ordinal`() {
        val buffer = NativeReplayFrameBuffer(0); commitInitial(buffer, 100)
        for (i in 1L..9) { buffer.append(frame(i, 1000 + i), 100 + i * 1_000_000_000); assertFalse(buffer.isReady) }
        buffer.append(frame(10, 1010), 10_000_000_100); assertTrue(buffer.isReady)
        val prefix = buffer.beginSealing(); assertEquals((1L..10).toList(), prefix.map { it.ordinal })
        buffer.committed(prefix); assertEquals(11L, buffer.nextFrameOrdinal)
        buffer.append(frame(11, 1011), 19_999_999_999); assertFalse(buffer.isReady)
        buffer.append(frame(12, 1012), 20_000_000_100); assertTrue(buffer.isReady)
    }
    @Test fun `boundaries reject unsupported duration without silently lowering minimum`() {
        for (minimum in listOf(-1, 3601, Int.MAX_VALUE)) {
            try { NativeReplayFrameBuffer(minimum); fail() } catch (_: IllegalArgumentException) { }
        }
        val buffer = NativeReplayFrameBuffer(3600)
        buffer.append(frame(0), 0); buffer.append(frame(1), 3_600_000_000_000); assertTrue(buffer.isReady)
    }
    @Test fun `chronology failures discard and permanently deny later suffix`() {
        for (kind in 0..3) {
            val buffer = NativeReplayFrameBuffer(5); buffer.append(frame(0, 2000), 100)
            val value = when (kind) { 0 -> frame(1, 1999); 1 -> frame(1, 0); 2 -> frame(1, 253_402_300_800_000L); else -> frame(1, 2001) }
            fails(NativeReplayBufferFailure.INVALID_CLOCK) { buffer.append(value, if (kind == 3) 99 else 101) }
            assertTrue(buffer.bufferedFrames.isEmpty()); assertFalse(buffer.isReady)
            fails(NativeReplayBufferFailure.WITHDRAWN) { buffer.append(frame(1, 3000), 500) }
        }
    }
    @Test fun `negative continuous clocks fail before retention and large valid anchors do not overflow`() {
        fails(NativeReplayBufferFailure.INVALID_CLOCK) { NativeReplayFrameBuffer(0).append(frame(0), -1) }
        val buffer = NativeReplayFrameBuffer(1)
        buffer.append(frame(0), Long.MAX_VALUE - 1_000_000_000)
        buffer.append(frame(1), Long.MAX_VALUE); assertTrue(buffer.isReady)
    }
    @Test fun `skipped repeated and exhausted ordinals never create an unsealed suffix`() {
        for (ordinal in listOf(-1L, 1L, MAX_REPLAY_SAFE_INTEGER, Long.MAX_VALUE)) {
            fails(NativeReplayBufferFailure.FRAME_ORDER) { NativeReplayFrameBuffer(0).append(frame(ordinal), 0) }
        }
        val buffer = NativeReplayFrameBuffer(0); commitInitial(buffer)
        fails(NativeReplayBufferFailure.FRAME_ORDER) { buffer.append(frame(2), 1) }
        assertTrue(buffer.bufferedFrames.isEmpty())
    }
    @Test fun `exact next ordinal at safe integer ceiling is rejected without counter refill`() {
        val buffer = NativeReplayFrameBuffer(0); commitInitial(buffer)
        // Reach a counter boundary without fabricating trillions of prior frames. All append/commit code is real.
        NativeReplayFrameBuffer::class.java.getDeclaredField("nextOrdinal").also {
            it.isAccessible = true; it.setLong(buffer, MAX_REPLAY_SAFE_INTEGER - 1)
        }
        buffer.append(frame(MAX_REPLAY_SAFE_INTEGER - 1), NativeReplayFrameBuffer.FLUSH_NANOSECONDS)
        val prefix = buffer.beginSealing(); buffer.committed(prefix)
        assertEquals(MAX_REPLAY_SAFE_INTEGER, buffer.nextFrameOrdinal)
        fails(NativeReplayBufferFailure.FRAME_ORDER) {
            buffer.append(frame(MAX_REPLAY_SAFE_INTEGER), NativeReplayFrameBuffer.FLUSH_NANOSECONDS + 1)
        }
        assertTrue(buffer.bufferedFrames.isEmpty())
        assertEquals(MAX_REPLAY_SAFE_INTEGER - 1, prefix.single().ordinal)
    }
    @Test fun `per-frame and aggregate node ceilings do not trim original initial`() {
        fails(NativeReplayBufferFailure.BUFFER_LIMIT) { NativeReplayFrameBuffer(1).append(frame(0, nodes = 10_000), 0) }
        val buffer = NativeReplayFrameBuffer(1)
        buffer.append(frame(0, nodes = 9_999), 0)
        fails(NativeReplayBufferFailure.BUFFER_LIMIT) { buffer.append(frame(1, nodes = 9_999), 1_000_000_000) }
        assertTrue(buffer.bufferedFrames.isEmpty())
    }
    @Test fun `estimated byte limit is checked independently of node count`() {
        val buffer = NativeReplayFrameBuffer(1)
        buffer.append(frame(0, nodes = 8192), 0)
        fails(NativeReplayBufferFailure.BUFFER_LIMIT) { buffer.append(frame(1, nodes = 8192), 1_000_000_000) }
        val fitting = NativeReplayFrameBuffer(1)
        fitting.append(frame(0, nodes = 8191), 0); fitting.append(frame(1, nodes = 8192), 1_000_000_000)
        assertEquals(16_383, fitting.beginSealing().sumOf { it.nodes.size })
    }
    @Test fun `latest replacement releases previous masked values before aggregate charge`() {
        val buffer = NativeReplayFrameBuffer(3)
        buffer.append(frame(0, nodes = 7000), 0)
        buffer.append(frame(1, nodes = 7000), 1_000_000_000)
        val newest = frame(1, nodes = 9000); buffer.append(newest, 3_000_000_000)
        assertSame(newest, buffer.beginSealing()[1])
    }
    @Test fun `sixteen small frames are bounded even when cadence has not elapsed`() {
        val buffer = NativeReplayFrameBuffer(0); commitInitial(buffer)
        for (i in 1L..16) buffer.append(frame(i), i)
        assertEquals(16, buffer.bufferedFrames.size)
        fails(NativeReplayBufferFailure.BUFFER_LIMIT) { buffer.append(frame(17), 17) }
        assertTrue(buffer.bufferedFrames.isEmpty())
    }
    @Test fun `readiness and sealing forbid intake instead of changing a retained prefix`() {
        for (sealing in listOf(false, true)) {
            val buffer = NativeReplayFrameBuffer(0); buffer.append(frame(0), 0)
            val prefix = if (sealing) buffer.beginSealing() else buffer.bufferedFrames
            fails(NativeReplayBufferFailure.WITHDRAWN) { buffer.append(frame(1), 1) }
            assertEquals(listOf(0L), prefix.map { it.ordinal })
            fails(NativeReplayBufferFailure.FRAME_ORDER) { buffer.committed(prefix) }
        }
    }
    @Test fun `only exact sealed prefix may commit and stale or late commits cannot revive`() {
        val buffer = NativeReplayFrameBuffer(0); buffer.append(frame(0), 0)
        val prefix = buffer.beginSealing()
        fails(NativeReplayBufferFailure.FRAME_ORDER) { buffer.committed(ArrayList(prefix)) }
        fails(NativeReplayBufferFailure.FRAME_ORDER) { buffer.committed(prefix) }
        assertTrue(buffer.bufferedFrames.isEmpty())
        val withdrawn = NativeReplayFrameBuffer(0); withdrawn.append(frame(0), 0)
        val pending = withdrawn.beginSealing(); withdrawn.withdraw()
        fails(NativeReplayBufferFailure.FRAME_ORDER) { withdrawn.committed(pending) }
    }
    @Test fun `double sealing and premature sealing are terminal`() {
        val early = NativeReplayFrameBuffer(1); early.append(frame(0), 0)
        fails(NativeReplayBufferFailure.WITHDRAWN) { early.beginSealing() }; assertTrue(early.bufferedFrames.isEmpty())
        val twice = NativeReplayFrameBuffer(0); twice.append(frame(0), 0); twice.beginSealing()
        fails(NativeReplayBufferFailure.WITHDRAWN) { twice.beginSealing() }
    }
    @Test fun `published prefix list is immutable and committed prefix survives buffer reuse`() {
        val buffer = NativeReplayFrameBuffer(0); buffer.append(frame(0), 0)
        val prefix = buffer.beginSealing()
        try { (prefix as MutableList).clear(); fail() } catch (_: UnsupportedOperationException) { }
        buffer.committed(prefix); buffer.append(frame(1), 1)
        assertEquals(0L, prefix.single().ordinal)
        buffer.withdraw(); assertEquals(0L, prefix.single().ordinal)
    }
}
