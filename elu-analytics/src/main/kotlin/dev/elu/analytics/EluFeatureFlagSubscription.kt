package dev.elu.analytics

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancel or close when no longer needed. Cancellation prevents callbacks that have not begun;
 * one already admitted may finish. No finalizer is required for correctness.
 */
public class EluFeatureFlagSubscription internal constructor(
    private val cancellation: FeatureFlagCancellation,
    remove: () -> Unit,
) : Closeable {
    private var remove: (() -> Unit)? = remove

    public fun cancel() {
        cancellation.cancel()
        val action = synchronized(this) { remove.also { remove = null } }
        action?.invoke()
    }

    override fun close() = cancel()

    internal companion object {
        fun inactive() = EluFeatureFlagSubscription(FeatureFlagCancellation().also { it.cancel() }) {}
    }
}

/** The atomic read is callback admission; client code never runs under an SDK lock. */
internal class FeatureFlagCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel(): Boolean = cancelled.compareAndSet(false, true)
    fun admit(): Boolean = !cancelled.get()
}
