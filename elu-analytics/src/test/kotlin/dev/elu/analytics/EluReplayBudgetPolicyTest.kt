package dev.elu.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class EluReplayBudgetPolicyTest {
    @Test
    fun `unlimited or vetoed replay has no budget work`() {
        assertEquals(EluReplayBudgetPolicy.Decision.Disabled, decide(maxMinutes = 0))
        assertEquals(EluReplayBudgetPolicy.Decision.Disabled, decide(maxMinutes = 5, vetoed = true))
    }

    @Test
    fun `inactive replay retries without stamping`() {
        assertEquals(
            EluReplayBudgetPolicy.Decision.Retry(delayMs = 60_000L),
            decide(maxMinutes = 5, replayActive = false),
        )
        assertEquals(
            EluReplayBudgetPolicy.Decision.Retry(delayMs = 60_000L),
            decide(maxMinutes = 5, sessionId = null),
        )
    }

    @Test
    fun `new session is stamped and scheduled for full budget`() {
        assertEquals(
            EluReplayBudgetPolicy.Decision.Schedule(startAtMs = 1_000L, delayMs = 300_250L),
            decide(maxMinutes = 5, nowMs = 1_000L, persistedStartMs = 0L),
        )
    }

    @Test
    fun `existing session resumes remaining budget`() {
        assertEquals(
            EluReplayBudgetPolicy.Decision.Schedule(startAtMs = null, delayMs = 30_250L),
            decide(maxMinutes = 1, nowMs = 40_000L, persistedStartMs = 10_000L),
        )
    }

    @Test
    fun `exhausted session stops replay`() {
        assertEquals(
            EluReplayBudgetPolicy.Decision.Stop,
            decide(maxMinutes = 1, nowMs = 70_000L, persistedStartMs = 10_000L),
        )
    }

    private fun decide(
        maxMinutes: Int,
        vetoed: Boolean = false,
        sessionId: String? = "session-1",
        replayActive: Boolean = true,
        nowMs: Long = 1_000L,
        persistedStartMs: Long = 0L,
    ) = EluReplayBudgetPolicy.decide(maxMinutes, vetoed, sessionId, replayActive, nowMs, persistedStartMs)
}
