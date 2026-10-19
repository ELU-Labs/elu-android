package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.ExecutorService

internal data class ReplayDeliveryPass(val attempted: Int, val committed: Int)

/** Bounded, coalesced delivery on a dedicated worker. It never occupies the capture owner lane. */
internal class ReplayDeliveryCoordinator(
    private val queue: ReplayDeliveryQueue,
    private val transport: ReplayDeliveryTransport,
    private val executor: ExecutorService,
    private val scheduler: ReplayDeliveryScheduler,
    private val wallNow: () -> Long,
    private val monotonicMillis: () -> Long,
    private val jitter: () -> Double = { Math.random() },
    private val maximumRequests: Int = 11,
    private val deadlineMillis: Long = 30_000,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val closeResult = object : SdkFuture<Unit>() {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
    }
    private var generation = 0L
    private var running: SdkFuture<ReplayDeliveryPass>? = null
    private var active: ReplayTransportOperation? = null
    private var wake: ReplayDeliveryScheduledTask? = null
    private var wakeAt: Long? = null
    private var flushWanted = false
    private var wakeToken: Any? = null
    private var transientStreak = 0
    private var transientUntil: Long? = null

    init { require(maximumRequests in 1..100); require(deadlineMillis in 1..600_000) }

    fun flush(): SdkFuture<ReplayDeliveryPass> = flush(afterTransientPause = false)

    /** Read-only test observation of consecutive transient failures. */
    internal fun transientStreakForTesting(): Int = synchronized(lock) { transientStreak }

    private fun flush(afterTransientPause: Boolean): SdkFuture<ReplayDeliveryPass> = synchronized(lock) {
        running?.let { if (!closed) flushWanted = true; return@synchronized it }
        if (closed) return@synchronized SdkFuture.completedFuture(ReplayDeliveryPass(0, 0))
        // After a transient failure, other triggers fold into the pending pause instead of sending
        // again. Only the pause wake itself, or an elapsed pause, ends it.
        val until = transientUntil
        if (until != null && !afterTransientPause) {
            val remaining = until - monotonicMillis()
            if (remaining in 1..MAXIMUM_TRANSIENT_PAUSE_MILLIS) {
                // Withdrawal may have cancelled the pause wake; the deferred trigger rearms it.
                scheduleWake(remaining, generation, transientPause = true)
                return@synchronized SdkFuture.completedFuture(ReplayDeliveryPass(0, 0))
            }
        }
        transientUntil = null
        val result = SdkFuture<ReplayDeliveryPass>()
        running = result
        val intent = generation
        try { executor.execute { runPass(intent, result) } }
        catch (error: Exception) { running = null; result.completeExceptionally(error) }
        result
    }

    /** Logical withdrawal cancels physical work but retains occupancy until actual settlement. */
    fun withdraw() {
        val operation = synchronized(lock) {
            generation++
            flushWanted = false
            runCatching { wake?.cancel() }; wake = null; wakeAt = null; wakeToken = null
            active
        }
        runCatching { operation?.cancel() }
    }

    override fun close() { closeAndWait() }

    /** Joins the actual current pass, including a pass started by a retry timer. */
    fun closeAndWait(): SdkFuture<Unit> {
        var idle = false
        val operation = synchronized(lock) {
            if (closed) return closeResult
            closed = true; generation++; flushWanted = false
            runCatching { wake?.cancel() }; wake = null; wakeAt = null; wakeToken = null
            idle = running == null
            if (idle) executor.shutdown()
            active
        }
        runCatching { operation?.cancel() }
        if (idle) closeResult.complete(Unit)
        return closeResult
    }

    private fun current(intent: Long) = synchronized(lock) { !closed && generation == intent }

    private fun runPass(intent: Long, result: SdkFuture<ReplayDeliveryPass>) {
        var attempted = 0
        var committed = 0
        var pending: ReplayDeliveryClaim? = null
        var failure: Exception? = null
        var interrupted = false
        var paused = false
        try {
            while (attempted < maximumRequests && current(intent)) {
                val claim = queue.claim()
                if (claim == null) {
                    queue.nextWakeDelayMillis()?.let { scheduleWake(it, intent) }
                    break
                }
                pending = claim
                if (!current(intent)) { queue.abandon(claim); break }
                val operation = queue.dispatch(claim, transport)
                if (operation == null) { queue.abandon(claim); break }
                attempted++
                val cancel = synchronized(lock) { active = operation; closed || generation != intent }
                if (cancel) runCatching { operation.cancel() }
                var deadline: ReplayDeliveryScheduledTask? = null
                val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
                var response: ReplayTransportResponse? = null
                try {
                    deadline = scheduler.schedule(deadlineMillis) {
                        timedOut.set(true)
                        runCatching { operation.cancel() }
                    }
                } catch (_: Exception) {
                    timedOut.set(true); runCatching { operation.cancel() }
                }
                // Cancellation never releases this slot. Only the transport's actual settlement can.
                try {
                    while (true) {
                        try { response = operation.settlement.get(); break }
                        catch (_: InterruptedException) {
                            interrupted = true; timedOut.set(true); runCatching { operation.cancel() }
                        }
                        catch (_: java.util.concurrent.ExecutionException) { break }
                    }
                } finally {
                    runCatching { deadline?.cancel() }
                    synchronized(lock) { if (active === operation) active = null }
                }
                val delay = backoff(claim.attemptCount)
                val classified = response?.let { claim.classify(it, wallNow(), delay) }
                // Physical refusal belongs to the original attempt even after logical withdrawal.
                // Only success/deletion is downgraded when its authority or deadline is gone.
                val outcome = when {
                    classified is ReplayDeliveryOutcome.Blocked -> classified
                    !current(intent) || timedOut.get() || classified == null -> ReplayDeliveryOutcome.Retry(delay)
                    else -> classified
                }
                val applied = queue.commit(claim, outcome)
                if (applied == ReplayDeliveryCommit.COMMITTED) committed++
                else queue.abandon(claim)
                pending = null
                if (outcome is ReplayDeliveryOutcome.Retry) {
                    // A transient failure ends the pass. The next pass waits a jittered, growing
                    // pause and then takes the next eligible head, so queued recordings take turns
                    // instead of all being resent.
                    if (current(intent)) pauseAfterTransientFailure(intent)
                    paused = true
                    break
                }
                if (outcome == ReplayDeliveryOutcome.Accepted || outcome == ReplayDeliveryOutcome.RejectedTooLarge)
                    synchronized(lock) { transientStreak = 0 }
                if (outcome == ReplayDeliveryOutcome.Blocked(ReplayBlockKind.UNAUTHORIZED)) break
            }
            if (!paused && attempted == maximumRequests && current(intent)) scheduleWake(1, intent)
        } catch (error: Exception) { failure = error }
        finally {
            // If enrollment threw or authority withdrew, release only the exact logical claim.
            // An active physical operation is never abandoned until its settlement above.
            pending?.let { claim -> if (synchronized(lock) { active == null }) runCatching { queue.abandon(claim) } }
            var completesClose = false
            val again = synchronized(lock) {
                if (running === result) {
                    // Capture this pass's close ownership before completing its dependents.
                    // A dependent may start/close another pass after running is cleared.
                    completesClose = closed
                    running = null
                }
                val wanted = !closed && flushWanted
                flushWanted = false
                if (closed) executor.shutdown()
                wanted
            }
            if (failure == null) result.complete(ReplayDeliveryPass(attempted, committed))
            else result.completeExceptionally(failure)
            // Complete outside the monitor: dependents may close the queue or other owners.
            if (completesClose) {
                if (failure == null) closeResult.complete(Unit)
                else closeResult.completeExceptionally(failure)
            }
            if (again) flush()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val MAXIMUM_TRANSIENT_PAUSE_MILLIS = 60_000L
    }

    private fun backoff(attempt: Int): Long {
        val unit = jitter()
        require(unit.isFinite() && unit >= 0 && unit < 1)
        val base = minOf(60_000L, 1_000L shl minOf(maxOf(attempt - 1, 0), 6))
        return (base * (0.5 + unit)).toLong().coerceIn(1, REPLAY_MAX_RETRY_MILLIS)
    }

    private fun pauseAfterTransientFailure(intent: Long) {
        val pause = synchronized(lock) {
            transientStreak = minOf(transientStreak + 1, 7)
            val base = minOf(MAXIMUM_TRANSIENT_PAUSE_MILLIS, 1_000L shl (transientStreak - 1))
            val sample = jitter()
            val unit = if (sample.isFinite()) sample.coerceIn(0.0, 1.0) else 0.5
            (base * (0.5 + 0.5 * unit)).toLong().coerceAtLeast(1).also { transientUntil = Math.addExact(monotonicMillis(), it) }
        }
        scheduleWake(pause, intent, transientPause = true)
    }

    private fun scheduleWake(delay: Long, intent: Long, transientPause: Boolean = false) {
        synchronized(lock) {
            if (closed || generation != intent) return
            val at = Math.addExact(monotonicMillis(), delay)
            // The pause replaces any earlier wake; otherwise the earliest wake wins.
            if (!transientPause && wakeAt?.let { it <= at } == true) return
            runCatching { wake?.cancel() }
            wakeAt = at
            val token = Any()
            wakeToken = token
            val scheduled = scheduler.schedule(delay) {
                synchronized(lock) {
                    if (!closed && generation == intent && wakeToken === token) {
                        wake = null; wakeAt = null; wakeToken = null
                        // The pause wake itself marks the end of the transient pause.
                        flush(afterTransientPause = transientPause)
                    }
                }
            }
            if (wakeToken === token) wake = scheduled else runCatching { scheduled.cancel() }

        }
    }
}
