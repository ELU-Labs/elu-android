package dev.elu.analytics.internal.diagnostics

import dev.elu.analytics.internal.concurrent.SdkFuture
import dev.elu.analytics.internal.runtime.RuntimeCaptureClock
import dev.elu.analytics.internal.runtime.RuntimeExceptionReport
import dev.elu.analytics.internal.runtime.RuntimeExceptionReservation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Original closed-policy lifetime supplied by the future config consumer, not a JSON pass flag.
 * This internal seam is currently uninstalled. Revocation is monotonic and does not call SDK code.
 */
internal class NativeExceptionPolicyLease(val policyHash: String) {
    private val live = AtomicBoolean(true)
    init { require(policyHash.matches(Regex("[0-9a-f]{64}"))) }
    fun revoke() { live.set(false) }
    fun isCurrent(): Boolean = live.get()
}

/** Single fixed namespace slot. All operations run off the crash callback under the queue lease. */
internal interface NativeExceptionSpool {
    fun read(): RuntimeExceptionReport?
    /** Check original permission again immediately before atomic publication; never overwrite. */
    fun publish(report: RuntimeExceptionReport, mayPublish: () -> Boolean): Boolean
    /** Must establish physical absence durably before the SQL reservation/marker can be retired. */
    fun clear()
}

/**
 * A prestarted single writer with one atomic claim. No queue/SQLite/network or waiting in offer.
 * The completion signals the writer's final block, not actual Thread termination: synchronous
 * listeners can still hold it alive. The original queue close worker must joinClosedWriter before
 * releasing resources. A stalled syscall or listener retains the original queue/file lease.
 */
internal class NativeExceptionIntake(
    reservation: RuntimeExceptionReservation,
    private val spool: NativeExceptionSpool,
    policy: NativeExceptionPolicyLease,
    private val clock: RuntimeCaptureClock,
    startedNanos: Long,
    budgetNanos: Long,
    sourceIsCurrent: () -> Boolean,
) : NativeUncaughtExceptionAdmission {
    private class Arm(val reservation: RuntimeExceptionReservation, val policy: NativeExceptionPolicyLease,
        val started: Long, val budget: Long, val sourceIsCurrent: () -> Boolean) {
        val accepting = AtomicBoolean(true)
        val claimed = AtomicBoolean(false)
        val completion = object : SdkFuture<Unit>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }
        init { require(started >= 0 && budget > 0) }
    }
    private data class Work(val arm: Arm, val report: RuntimeExceptionReport)
    private val arm = AtomicReference(Arm(reservation, policy, startedNanos, budgetNanos, sourceIsCurrent))
    private val stopping = AtomicBoolean(false)
    private val pending = AtomicReference<Work?>()
    val settlement = object : SdkFuture<Unit>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }
    val reportSettlement: SdkFuture<Unit> get() = arm.get().completion
    @Volatile var published = false
        private set
    @Volatile var failure: Throwable? = null
        private set
    private val writer = Thread({ writeLoop() }, "elu-exception-writer").apply { isDaemon = true }

    /** Owner retains this handle before start, including a throwing start. */
    fun start() {
        try { writer.start() }
        catch (error: Throwable) { stopping.set(true); invalidate(); settlement.complete(Unit); throw error }
    }
    /** Withdrawal is separate from permanent shutdown. Empty arms settle without creating work. */
    fun invalidate() {
        val original = arm.get()
        original.accepting.set(false)
        if (!original.claimed.get()) original.completion.complete(Unit)
    }
    /** Called only after original SQL reservation + physical empty-slot retirement, on owner lane. */
    fun rearm(reservation: RuntimeExceptionReservation, policy: NativeExceptionPolicyLease, started: Long, budget: Long,
        sourceIsCurrent: () -> Boolean) {
        val previous = arm.get()
        check(!stopping.get() && !settlement.isDone && !previous.accepting.get() && previous.completion.isDone)
        check(pending.get() == null)
        published = false; failure = null
        check(arm.compareAndSet(previous, Arm(reservation, policy, started, budget, sourceIsCurrent)))
    }
    fun close(): SdkFuture<Unit> {
        stopping.set(true); invalidate(); LockSupport.unpark(writer)
        return settlement
    }
    /** Only the original queue close worker calls this; never the crash callback or this writer. */
    internal fun joinClosedWriter() {
        check(stopping.get() && Thread.currentThread() !== writer)
        var interrupted = false
        try {
            while (writer.isAlive) {
                try { writer.join() }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    private fun current(original: Arm): Boolean {
        if (stopping.get() || arm.get() !== original || !original.accepting.get() || !original.policy.isCurrent() ||
            !original.sourceIsCurrent()) return false
        val wall = clock.wallNowEpochMillis()
        val nowNanos = clock.elapsedRealtimeNanos()
        if (nowNanos < original.started) return false
        val elapsed = nowNanos - original.started
        return wall >= original.reservation.issuedWall && wall < original.reservation.expiresWall &&
            elapsed >= 0 && elapsed < original.budget
    }
    override fun offer(observation: NativeExceptionObservation) {
        val original = arm.get()
        if (!current(original) || !original.claimed.compareAndSet(false, true)) return
        var submitted = false
        try {
            val report = RuntimeExceptionReport(original.reservation, clock.wallNowEpochMillis(), observation.type, observation.typeTruncated)
            if (!current(original)) return
            val work = Work(original, report)
            check(pending.compareAndSet(null, work))
            submitted = true
            // close may have observed an empty mailbox after the first permission check.
            // Retract that late offer; if already taken, the original writer checks again.
            if (!current(original) && pending.compareAndSet(work, null)) submitted = false
            LockSupport.unpark(writer)
        } finally {
            if (!submitted) { original.accepting.set(false); original.completion.complete(Unit) }
        }
    }
    private fun writeLoop() {
        try {
            while (true) {
                val work = pending.getAndSet(null)
                if (work != null) {
                    try {
                        if (current(work.arm)) published = spool.publish(work.report) { current(work.arm) }
                    } catch (error: Throwable) { failure = error }
                    finally { work.arm.accepting.set(false); work.arm.completion.complete(Unit) }
                }
                if (stopping.get() && pending.get() == null) return
                if (pending.get() == null) LockSupport.park(this)
            }
        } finally { invalidate(); settlement.complete(Unit) }
    }
}
