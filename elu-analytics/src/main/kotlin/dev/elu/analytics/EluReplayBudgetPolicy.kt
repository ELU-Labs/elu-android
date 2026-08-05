package dev.elu.analytics

/** Pure replay-budget decisions; runtime and persistence effects stay in the platform adapter. */
internal object EluReplayBudgetPolicy {
    sealed interface Decision {
        data object Disabled : Decision

        data object Stop : Decision

        data class Retry(val delayMs: Long) : Decision

        data class Schedule(
            val startAtMs: Long?,
            val delayMs: Long,
        ) : Decision
    }

    fun decide(
        maxMinutes: Int,
        vetoed: Boolean,
        sessionId: String?,
        replayActive: Boolean,
        nowMs: Long,
        persistedStartMs: Long,
    ): Decision {
        if (vetoed || maxMinutes <= 0) return Decision.Disabled
        if (sessionId == null || !replayActive) return Decision.Retry(RETRY_MS)

        val budgetMs = maxMinutes * 60_000L
        val startMs = persistedStartMs.takeIf { it > 0L } ?: nowMs
        val remainingMs = budgetMs - (nowMs - startMs)
        if (remainingMs <= 0L) return Decision.Stop
        return Decision.Schedule(
            startAtMs = startMs.takeIf { persistedStartMs <= 0L },
            delayMs = remainingMs + SCHEDULE_SLOP_MS,
        )
    }

    private const val RETRY_MS = 60_000L
    private const val SCHEDULE_SLOP_MS = 250L
}
