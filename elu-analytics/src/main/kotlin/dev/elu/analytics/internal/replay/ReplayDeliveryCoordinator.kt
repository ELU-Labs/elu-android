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

    init { require(maximumRequests in 1..100); require(deadlineMillis in 1..600_000) }

    fun flush(): SdkFuture<ReplayDeliveryPass> = synchronized(lock) {
        running?.let { if (!closed) flushWanted = true; return@synchronized it }
        if (closed) return@synchronized SdkFuture.completedFuture(ReplayDeliveryPass(0, 0))
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
                val classified = response?.let { ReplayResponseClassifier.classify(it, claim.row.prepared, wallNow(), delay) }
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
                if (outcome is ReplayDeliveryOutcome.Retry && current(intent)) scheduleWake(outcome.delayMillis, intent)
                if (outcome is ReplayDeliveryOutcome.Retry && outcome.endpointCooldown) break
                if (outcome == ReplayDeliveryOutcome.Blocked(ReplayBlockKind.UNAUTHORIZED)) break
            }
            if (attempted == maximumRequests && current(intent)) scheduleWake(1, intent)
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

    private fun backoff(attempt: Int): Long {
        val unit = jitter()
        require(unit.isFinite() && unit >= 0 && unit < 1)
        val base = minOf(60_000L, 1_000L shl minOf(maxOf(attempt - 1, 0), 6))
        return (base * (0.5 + unit)).toLong().coerceIn(1, REPLAY_MAX_RETRY_MILLIS)
    }

    private fun scheduleWake(delay: Long, intent: Long) {
        synchronized(lock) {
            if (closed || generation != intent) return
            val at = Math.addExact(monotonicMillis(), delay)
            if (wakeAt?.let { it <= at } == true) return
            runCatching { wake?.cancel() }
            wakeAt = at
            val token = Any()
            wakeToken = token
            val scheduled = scheduler.schedule(delay) {
                synchronized(lock) {
                    if (!closed && generation == intent && wakeToken === token) {
                        wake = null; wakeAt = null; wakeToken = null
                        flush()
                    }
                }
            }
            if (wakeToken === token) wake = scheduled else runCatching { scheduled.cancel() }

        }
    }
}
