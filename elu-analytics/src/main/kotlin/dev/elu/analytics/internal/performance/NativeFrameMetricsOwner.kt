package dev.elu.analytics.internal.performance

import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.atomic.AtomicBoolean

internal data class NativeFrameMeasurement(
    val startedAtNanos: Long, val observedAtNanos: Long, val durationNanos: Long,
    val firstDraw: Boolean, val deadlineNanos: Long?, val droppedReports: Int,
    val processAgeMillis: Long?,
)

internal interface NativeFrameMetricsAccess {
    fun onMain(action: () -> Unit)
    fun nanoTime(): Long
    fun watch(activity: Any, current: () -> Boolean, emit: (NativeFrameMeasurement) -> Unit): AutoCloseable
}

internal class NativeFrameWatchAcquisitionFailure(val cleanup: AutoCloseable, cause: Throwable) : RuntimeException(cause)

/** One original Activity/window listener; close completion joins its physical removal. */
internal class NativeFrameMetricsOwner(
    private val lifecycle: NativePerformanceActivityLifecycle,
    private val context: () -> NativePerformanceContext?,
    private val access: NativeFrameMetricsAccess,
    private val aggregate: NativeFrameMetrics,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val pendingMain = AtomicBoolean(false)
    private val closeResult = object : SdkFuture<Unit>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }
    @Volatile private var lease: Lease? = null
    private var failure: Throwable? = null // main only
    private val subscription = lifecycle.observe(::refresh)

    private class Lease(val selection: NativePerformanceActivityLifecycle.Selection,
        val context: NativePerformanceContext) {
        val active = AtomicBoolean(true)
        var registeredAt = Long.MAX_VALUE // main only; no report before successful registration
        var watcher: AutoCloseable? = null // main only
    }

    /** Coalesced with the existing bounded performance timer; never queues one task per frame. */
    fun refresh() {
        if (closed.get() || !pendingMain.compareAndSet(false, true)) return
        try { access.onMain {
            pendingMain.set(false)
            try { reconcile() } catch (error: Throwable) { failAndClose(error) }
        } } catch (error: Throwable) {
            pendingMain.set(false); closed.set(true); lease?.active?.set(false)
            subscription.close(); aggregate.clear(); closeResult.completeExceptionally(error)
        }
    }

    private fun current(original: Lease): Boolean = !closed.get() && original.active.get() &&
        lifecycle.isCurrent(original.selection) && context() == original.context

    private fun reconcile() {
        if (closed.get()) { removeLease(); settleClose(); return }
        val selected = lifecycle.current()
        val observed = context()?.takeIf { it.policy.longTasks }
        val existing = lease
        if (existing != null && selected?.generation === existing.selection.generation &&
            observed == existing.context && current(existing)) return
        removeLease(); aggregate.clear()
        if (selected == null || observed == null || !lifecycle.isCurrent(selected)) return
        val activity = selected.activity.get() ?: return
        val acquired = Lease(selected, observed)
        lease = acquired // reserve before the fallible physical registration
        try {
            acquired.watcher = access.watch(activity, { current(acquired) }) { frame ->
                if (current(acquired)) {
                    aggregate.record(acquired.context, acquired.registeredAt, frame.startedAtNanos,
                        frame.observedAtNanos, frame.durationNanos, frame.firstDraw, frame.deadlineNanos,
                        frame.droppedReports, frame.processAgeMillis)
                    if (!current(acquired)) aggregate.clear()
                }
            }
            acquired.registeredAt = access.nanoTime()
            if (!current(acquired)) removeLease()
        } catch (error: NativeFrameWatchAcquisitionFailure) {
            acquired.watcher = error.cleanup
            throw error
        }
    }

    private fun removeLease() {
        val original = lease ?: return
        original.active.set(false)
        original.watcher?.close() // Keep the original handle when removal cannot be proven.
        lease = null
    }

    private fun failAndClose(error: Throwable) {
        failure = failure ?: error
        closed.set(true); lease?.active?.set(false); subscription.close(); aggregate.clear()
        try { removeLease() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
        settleClose()
    }

    private fun settleClose() {
        val error = failure
        if (error != null) closeResult.completeExceptionally(error)
        else if (lease == null) closeResult.complete(Unit)
    }

    fun closeAndWait(): SdkFuture<Unit> {
        if (!closed.compareAndSet(false, true)) return closeResult
        lease?.active?.set(false)
        subscription.close(); aggregate.clear()
        try { access.onMain {
            try { removeLease(); settleClose() } catch (error: Throwable) { failAndClose(error) }
        } } catch (error: Throwable) { closeResult.completeExceptionally(error) }
        return closeResult
    }

    override fun close() { closeAndWait() }
}
