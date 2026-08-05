package dev.elu.analytics

import android.content.SharedPreferences
import com.posthog.PostHog
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Per-runtime-session visual-replay wall-clock budget (`replayMaxMinutes`).
 * Start stamps live in SharedPreferences keyed by session id (pruned to the
 * most recent 5) so a relaunch inside the same runtime session resumes the
 * remaining budget instead of restarting it. Events keep flowing after the
 * budget stops replay — only the visual stream is cut.
 *
 * All entry points must run on the SDK executor.
 */
internal class EluReplayBudget(
    private val prefs: SharedPreferences,
    private val executor: ScheduledExecutorService,
) {
    @Volatile var maxMinutes: Int = 0

    /** Set when a privacy tightening stopped replay for the rest of the run — never restart. */
    @Volatile var vetoed: Boolean = false

    private var pending: ScheduledFuture<*>? = null

    fun veto() {
        vetoed = true
        pending?.cancel(false)
        pending = null
    }

    fun check() {
        pending?.cancel(false)
        pending = null
        if (vetoed || maxMinutes <= 0) return

        val sessionId =
            try {
                PostHog.getSessionId()?.toString()
            } catch (t: Throwable) {
                null
            }
        val replayActive =
            try {
                PostHog.isSessionReplayActive()
            } catch (t: Throwable) {
                false
            }
        val now = System.currentTimeMillis()
        val key = sessionId?.let { KEY_PREFIX + it }
        val persistedStart = key?.let { prefs.getLong(it, 0L) } ?: 0L
        when (
            val decision =
                EluReplayBudgetPolicy.decide(
                    maxMinutes = maxMinutes,
                    vetoed = vetoed,
                    sessionId = sessionId,
                    replayActive = replayActive,
                    nowMs = now,
                    persistedStartMs = persistedStart,
                )
        ) {
            EluReplayBudgetPolicy.Decision.Disabled -> Unit
            EluReplayBudgetPolicy.Decision.Stop -> {
                try {
                    PostHog.stopSessionReplay()
                } catch (t: Throwable) {
                    // never propagate into the host app
                }
            }
            is EluReplayBudgetPolicy.Decision.Retry -> {
                pending = executor.schedule(::check, decision.delayMs, TimeUnit.MILLISECONDS)
            }
            is EluReplayBudgetPolicy.Decision.Schedule -> {
                if (decision.startAtMs != null && key != null) stamp(key, decision.startAtMs)
                pending = executor.schedule(::check, decision.delayMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun stamp(
        key: String,
        now: Long,
    ) {
        try {
            val editor = prefs.edit()
            prefs.all
                .mapNotNull { (k, v) -> if (k.startsWith(KEY_PREFIX)) (v as? Long)?.let { k to it } else null }
                .sortedByDescending { it.second }
                .drop(MAX_STAMPS - 1)
                .forEach { editor.remove(it.first) }
            editor.putLong(key, now).apply()
        } catch (t: Throwable) {
            // stamp is best-effort; worst case the budget restarts on relaunch
        }
    }

    private companion object {
        const val KEY_PREFIX = "elu.recBudget."
        const val MAX_STAMPS = 5
    }
}
