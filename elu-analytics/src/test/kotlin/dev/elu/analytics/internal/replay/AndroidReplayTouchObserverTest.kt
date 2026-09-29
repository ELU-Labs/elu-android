package dev.elu.analytics.internal.replay

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AndroidReplayTouchObserverTest {
    private val id = UUID.fromString("10000000-0000-4000-8000-000000000001")
    private fun projection(generation: Long = 1) = NativeTouchProjection(generation,
        NativeMaskedSnapshot(generation - 1, 1000, NativeViewport(100, 100), listOf(
            NativeMaskedNode(id, NativeMaskedKind.Rectangle, NativeRect(0.0, 0.0, 100.0, 100.0),
                NativeRect(0.0, 0.0, 100.0, 100.0)))))
    private fun input(action: NativeTouchAction, direct: Boolean = true, history: Int = 0,
        pointer: Int = 0, down: Long = 100) = NativeTouchInput(action, pointer, down,
        List(history + 1) { NativeTouchLocation(10.0 + it, 10.0) }, direct)
    private fun proof(p: NativeTouchProjection, count: Int = 1) = List(count) { NativeProjectedTouch(p, id, 10 + it, 10) }
    private fun observe(core: NativeTouchObservationCore, p: NativeTouchProjection, action: NativeTouchAction,
        wall: Long, continuous: Long = wall * 1_000_000L) = core.observe(input(action), proof(p), wall, continuous)

    @Test fun `unarmed down and already running move cannot be adopted`() {
        val p = projection(); val core = NativeTouchObservationCore()
        observe(core, p, NativeTouchAction.DOWN, 1000)
        assertFalse(core.arm(p)); observe(core, p, NativeTouchAction.UP, 1100)
        assertTrue(core.drain().isEmpty()); assertTrue(core.arm(p))
        observe(core, p, NativeTouchAction.MOVE, 1200)
        assertFalse(core.arm(p)); assertTrue(core.drain().isEmpty())
        observe(core, p, NativeTouchAction.UP, 1300)
        assertTrue(core.arm(p))
    }

    @Test fun `same values with a foreign projection instance never start`() {
        val p = projection(); val other = projection(); val core = NativeTouchObservationCore()
        assertTrue(core.arm(p)); core.observe(input(NativeTouchAction.DOWN), proof(other), 1000, 0)
        assertTrue(core.drain().isEmpty())
    }

    @Test fun `monotonic cadence and wall cadence both limit movement across drains`() {
        val p = projection(); val core = NativeTouchObservationCore(); assertTrue(core.arm(p))
        observe(core, p, NativeTouchAction.DOWN, 1000, 0)
        observe(core, p, NativeTouchAction.MOVE, 1100, 100_000_000)
        observe(core, p, NativeTouchAction.MOVE, 1300, 110_000_000) // Wall jump cannot accelerate sampling.
        val first = core.drain(); assertEquals(2, first.size)
        assertEquals(1, (first.last().interaction as NativeReplayInteraction.Moves).points.size)
        observe(core, p, NativeTouchAction.MOVE, 1350, 200_000_000)
        observe(core, p, NativeTouchAction.MOVE, 1400, 300_000_000) // Wall gap only50.
        observe(core, p, NativeTouchAction.UP, 1450, 350_000_000)
        val rest = core.drain(); assertEquals(2, rest.size)
        assertEquals(1350L, (rest.first().interaction as NativeReplayInteraction.Moves).points.single().timestamp)
        assertTrue(rest.last().interaction is NativeReplayInteraction.End)
    }

    @Test fun `private history on a rate dropped sample flushes then cancels and suppresses through lift`() {
        val p = projection(); val core = NativeTouchObservationCore(); assertTrue(core.arm(p))
        observe(core, p, NativeTouchAction.DOWN, 1000)
        observe(core, p, NativeTouchAction.MOVE, 1100)
        core.observe(input(NativeTouchAction.MOVE, history = 1), listOf(null, proof(p).single()), 1101, 1_101_000_000)
        observe(core, p, NativeTouchAction.MOVE, 1300)
        observe(core, p, NativeTouchAction.UP, 1400)
        val rows = core.drain(); assertEquals(3, rows.size)
        assertTrue(rows[0].interaction is NativeReplayInteraction.Start)
        assertTrue(rows[1].interaction is NativeReplayInteraction.Moves)
        assertTrue(rows[2].interaction is NativeReplayInteraction.Cancel)
        assertTrue(core.arm(p))
    }

    @Test fun `multiple pointer or obscured and unsupported observations cancel without coordinates`() {
        for (action in listOf(NativeTouchAction.MOVE, NativeTouchAction.UNSUPPORTED, NativeTouchAction.CANCEL)) {
            val p = projection(); val core = NativeTouchObservationCore(); core.arm(p)
            observe(core, p, NativeTouchAction.DOWN, 1000)
            core.observe(input(action, direct = false), null, 1100, 1_100_000_000)
            val rows = core.drain(); assertEquals(2, rows.size)
            assertEquals(NativeReplayInteraction.Cancel(1100), rows.last().interaction)
        }
    }

    @Test fun `pointer identity and down time cannot cross a gesture`() {
        for (facts in listOf(input(NativeTouchAction.MOVE, pointer = 1), input(NativeTouchAction.MOVE, down = 200))) {
            val p = projection(); val core = NativeTouchObservationCore(); core.arm(p)
            observe(core, p, NativeTouchAction.DOWN, 1000)
            core.observe(facts, proof(p), 1100, 1_100_000_000)
            assertTrue(core.drain().last().interaction is NativeReplayInteraction.Cancel)
        }
    }

    @Test fun `target mismatch cancels instead of transferring the primary pointer`() {
        val p = projection(); val core = NativeTouchObservationCore(); core.arm(p)
        observe(core, p, NativeTouchAction.DOWN, 1000)
        core.observe(input(NativeTouchAction.MOVE), listOf(NativeProjectedTouch(p, UUID.randomUUID(), 10, 10)), 1100, 1_100_000_000)
        assertTrue(core.drain().last().interaction is NativeReplayInteraction.Cancel)
    }

    @Test fun `mailbox reserves terminal capacity and remains bounded until drained`() {
        val p = projection(); val core = NativeTouchObservationCore(4); core.arm(p)
        observe(core, p, NativeTouchAction.DOWN, 1000)
        observe(core, p, NativeTouchAction.MOVE, 1100)
        observe(core, p, NativeTouchAction.MOVE, 1200)
        observe(core, p, NativeTouchAction.MOVE, 1300)
        observe(core, p, NativeTouchAction.UP, 1400)
        val rows = core.drain(); assertEquals(3, rows.size)
        assertEquals(2, (rows[1].interaction as NativeReplayInteraction.Moves).points.size)
        assertEquals(NativeReplayInteraction.Cancel(1300), rows.last().interaction)
    }

    @Test fun `coalesced history is only a privacy check and generates one observed move`() {
        val p = projection(); val core = NativeTouchObservationCore(); core.arm(p)
        observe(core, p, NativeTouchAction.DOWN, 1000)
        core.observe(input(NativeTouchAction.MOVE, history = 32), proof(p, 33), 1100, 1_100_000_000)
        val move = core.drain().last().interaction as NativeReplayInteraction.Moves
        assertEquals(1, move.points.size); assertEquals(42, move.points.single().x)
    }

    @Test fun `withdrawal clears coordinates and cannot be rearmed`() {
        val p = projection(); val core = NativeTouchObservationCore(); core.arm(p)
        observe(core, p, NativeTouchAction.DOWN, 1000); observe(core, p, NativeTouchAction.MOVE, 1100)
        core.withdraw(); assertTrue(core.drain().isEmpty()); assertFalse(core.arm(projection(2)))
    }

    @Test fun `clock reversal drops all pending observations`() {
        for (wallReversal in listOf(true, false)) {
            val p = projection(); val core = NativeTouchObservationCore(); core.arm(p)
            observe(core, p, NativeTouchAction.DOWN, 1000, 100)
            observe(core, p, NativeTouchAction.MOVE, if (wallReversal) 999 else 1100, if (wallReversal) 200 else 99)
            assertTrue(core.drain().isEmpty()); assertFalse(core.arm(p))
        }
    }

    @Test fun `new projection cannot overtake an undrained original boundary`() {
        val p = projection(); val core = NativeTouchObservationCore(); core.arm(p)
        observe(core, p, NativeTouchAction.DOWN, 1000); observe(core, p, NativeTouchAction.UP, 1100)
        assertFalse(core.arm(projection(2))); assertEquals(2, core.drain().size)
        assertTrue(core.arm(projection(2)))
    }
    @Test fun `work budget excludes host dispatch but sums both SDK segments`() {
        val budget = NativeTouchWorkBudget(callbackLimit = 2, windowLimit = 20, windowNanos = 1000)
        assertTrue(budget.begin(0)); assertTrue(budget.within(1)); assertTrue(budget.pause(1))
        assertTrue(budget.resume(10_000)) // Long app dispatch is not SDK observation work.
        assertTrue(budget.within(10_001)); assertFalse(budget.within(10_002))
        budget.finish(10_002)
        assertEquals(3L, budget.totalCharged) // Non-preemptible overrun is charged, never hidden.
    }

    @Test fun `aggregate rolling work refuses before another projection and ages exact charges`() {
        val budget = NativeTouchWorkBudget(callbackLimit = 2, windowLimit = 20, windowNanos = 1000)
        for (index in 0 until 10) {
            val start = index * 3L
            assertTrue(budget.begin(start)); assertTrue(budget.pause(start + 2)); budget.finish(start + 2)
        }
        assertFalse(budget.begin(30)); assertEquals(20L, budget.totalCharged)
        assertFalse(budget.begin(1001))
        assertTrue(budget.begin(1002)); assertTrue(budget.pause(1004)); budget.finish(1004)
        assertEquals(22L, budget.totalCharged)
    }

    @Test fun `work clock reversal permanently denies without unmeasured fallback`() {
        val budget = NativeTouchWorkBudget()
        assertTrue(budget.begin(100)); assertFalse(budget.within(99)); budget.finish(101)
        assertFalse(budget.begin(1_000_000_000)); assertEquals(0L, budget.totalCharged)
    }

    @Test fun `budget refusal suppresses through lift even after aggregate refill`() {
        val p = projection(); val core = NativeTouchObservationCore(); core.arm(p)
        observe(core, p, NativeTouchAction.DOWN, 1000)
        assertTrue(core.needsProjection(NativeTouchAction.MOVE))
        core.observe(NativeTouchInput(NativeTouchAction.MOVE, -1, 0, emptyList(), false), null, 1100, 1_100_000_000)
        assertFalse(core.needsProjection(NativeTouchAction.MOVE)); assertFalse(core.arm(p))
        assertEquals(NativeReplayInteraction.Cancel(1100), core.drain().last().interaction)
        observe(core, p, NativeTouchAction.MOVE, 3000)
        assertFalse(core.needsProjection(NativeTouchAction.MOVE))
        core.observe(NativeTouchInput(NativeTouchAction.UP, -1, 0, emptyList(), false), null, 3100, 3_100_000_000)
        assertTrue(core.needsProjection(NativeTouchAction.DOWN))
    }

    @Test fun `unarmed suppressed and terminal actions require no full event or hierarchy projection`() {
        val p = projection(); val core = NativeTouchObservationCore()
        assertFalse(core.needsProjection(NativeTouchAction.DOWN)); core.arm(p)
        assertTrue(core.needsProjection(NativeTouchAction.DOWN))
        observe(core, p, NativeTouchAction.DOWN, 1000)
        assertFalse(core.needsProjection(NativeTouchAction.CANCEL))
        assertFalse(core.needsProjection(NativeTouchAction.UNSUPPORTED))
        core.observe(input(NativeTouchAction.CANCEL), null, 1100, 1_100_000_000)
        core.drain(); observe(core, p, NativeTouchAction.DOWN, 1200)
        core.observe(input(NativeTouchAction.MOVE, false), null, 1300, 1_300_000_000)
        assertFalse(core.needsProjection(NativeTouchAction.MOVE)); assertFalse(core.needsProjection(NativeTouchAction.UP))
    }

}
