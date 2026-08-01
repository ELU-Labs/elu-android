package dev.elu.analytics

import android.content.SharedPreferences
import com.posthog.PostHog
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Per-PostHog-session visual-replay wall-clock budget (`replayMaxMinutes`).
 * Start stamps live in SharedPreferences keyed by session id (pruned to the
 * most recent 5) so a relaunch inside the same PostHog session resumes the
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
        if (vetoed) return
        val budgetMs = maxMinutes * 60_000L
        if (budgetMs <= 0L) return

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
        if (sessionId == null || !replayActive) {
            // Replay may still be waiting on remote opt-in/sampling — re-check.
            pending = executor.schedule(::check, RETRY_SECONDS, TimeUnit.SECONDS)
            return
        }

        val key = KEY_PREFIX + sessionId
        val now = System.currentTimeMillis()
        var start = prefs.getLong(key, 0L)
        if (start <= 0L) {
            start = now
            stamp(key, now)
        }
        val remainingMs = budgetMs - (now - start)
        if (remainingMs <= 0L) {
            try {
                PostHog.stopSessionReplay()
            } catch (t: Throwable) {
                // never propagate into the host app
            }
            // Stopped for this run; next launch re-arms with a fresh session.
        } else {
            pending = executor.schedule(::check, remainingMs + 250L, TimeUnit.MILLISECONDS)
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
        const val RETRY_SECONDS = 60L
    }
}
