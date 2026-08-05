package dev.elu.analytics.internal.runtime.delivery

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Non-blocking Android lifecycle boundary for one bounded coordinator trigger.
 * The injected executor is owned by the SDK runtime; no persistent scheduler is added here.
 */
internal class AndroidBatchDeliveryAdapter(
    private val executor: Executor,
    private val trigger: () -> Unit,
) {
    private val scheduled = AtomicBoolean(false)

    fun onBackgrounded(): Boolean = scheduleOnce()

    fun onForegrounded(): Boolean = scheduleOnce()

    private fun scheduleOnce(): Boolean {
        if (!scheduled.compareAndSet(false, true)) return false
        return try {
            executor.execute {
                try {
                    trigger()
                } finally {
                    scheduled.set(false)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            scheduled.set(false)
            false
        }
    }
}
