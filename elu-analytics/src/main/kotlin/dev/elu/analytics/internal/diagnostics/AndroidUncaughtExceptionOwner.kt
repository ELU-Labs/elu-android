package dev.elu.analytics.internal.diagnostics

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dormant until explicitly installed; no runtime caller, worker, storage, or network integration.
 * Retains the exact original handler before any registration side effect. Construction is inert
 * so the caller already owns this cleanup handle if a setter fails after changing the registry.
 *
 * Installation is attempted once. Observed displacement or an unreadable registry permanently disables
 * observation. A retained wrapper still delegates, and close restores only while it is current.
 * Registration operations require the host serialization contract in [NativeUncaughtExceptionRegistry].
 * They cannot atomically restore in the face of arbitrary concurrent third-party registration.
 */
internal class AndroidUncaughtExceptionOwner(
    private val registry: NativeUncaughtExceptionRegistry,
    private val admission: NativeUncaughtExceptionAdmission,
) {
    private val lifecycle = Any()
    private val stopped = AtomicBoolean(false)
    private val accepting = AtomicBoolean(false)
    private val observing = AtomicBoolean(false)
    private var attempted = false
    private var registration: Registration? = null

    /** False is never an installation or cleanup proof; call close even after a failed attempt. */
    fun install(): Boolean = synchronized(lifecycle) {
        if (attempted || stopped.get()) return@synchronized false
        attempted = true
        val previous =
            try {
                registry.current()
            } catch (_: Throwable) {
                stopped.set(true)
                return@synchronized false
            }
        if (previous == null) {
            // Do not invent ThreadGroup fallback, termination, or a no-op original handler.
            stopped.set(true)
            return@synchronized false
        }
        val original = Registration(previous)
        registration = original
        try {
            registry.replace(original.handler)
            if (registry.current() === original.handler && !stopped.get()) {
                accepting.set(true)
                if (!stopped.get()) return@synchronized true
            }
        } catch (_: Throwable) {
            // The setter may already have published the wrapper. Its original handle is retained.
        }
        stopped.set(true)
        accepting.set(false)
        restoreIfOwned(original)
        false
    }

    /**
     * Disarms first. True confirms no registration was attempted, or a registry read no longer
     * found this wrapper, not that
     * already-entered injected admission or host handler code has finished. False retains this
     * same original registration for a later close retry; no replacement owner or task is made.
     */
    fun close(): Boolean {
        stopped.set(true)
        accepting.set(false)
        return synchronized(lifecycle) {
            val original = registration ?: return@synchronized true
            restoreIfOwned(original)
        }
    }

    private fun restoreIfOwned(original: Registration): Boolean =
        try {
            if (registry.current() === original.handler) {
                registry.replace(original.previous)
                registry.current() !== original.handler
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }

    private inner class Registration(val previous: Thread.UncaughtExceptionHandler) {
        val handler = Thread.UncaughtExceptionHandler { thread, throwable -> observe(this, thread, throwable) }
    }

    private fun observe(original: Registration, thread: Thread, throwable: Throwable) {
        var entered = false
        try {
            // One observer at a time, without waiting. Nested/concurrent invocations still each
            // delegate their exact arguments. The guard also covers original-handler reentry.
            if (!observing.compareAndSet(false, true)) return
            entered = true
            if (stopped.get() || !accepting.get()) return
            try {
                if (registry.current() !== original.handler) {
                    stopped.set(true)
                    accepting.set(false)
                    return
                }
                val observation = NativeExceptionObservation.from(throwable)
                if (!stopped.get() && accepting.get()) admission.offer(observation)
            } catch (_: Throwable) {
                // Never format, inspect or report an SDK failure from a dying thread.
                // A failed registry read also withdraws observation until normal explicit setup.
                stopped.set(true)
                accepting.set(false)
            }
        } finally {
            try {
                original.previous.uncaughtException(thread, throwable)
            } finally {
                if (entered) observing.set(false)
            }
        }
    }
}
