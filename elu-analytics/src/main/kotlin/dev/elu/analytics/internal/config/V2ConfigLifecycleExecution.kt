package dev.elu.analytics.internal.config

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun interface V2ConfigLifecycleTask {
    fun cancel()
}

/** Schedules asynchronously, never inline. The driver owns and closes this scheduler. */
internal interface V2ConfigLifecycleScheduler : AutoCloseable {
    fun schedule(delayNanos: Long, task: () -> Unit): V2ConfigLifecycleTask
}

/** A canceled physical fetch must still run its completion before another fetch is admitted. */
internal interface V2ConfigLifecycleWorker : AutoCloseable {
    fun execute(task: () -> Unit)
    fun interruptCurrent()
}

internal class ScheduledV2ConfigLifecycleScheduler : V2ConfigLifecycleScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "elu-config-timers").apply { isDaemon = true }
    }

    override fun schedule(delayNanos: Long, task: () -> Unit): V2ConfigLifecycleTask {
        require(delayNanos > 0)
        val future = executor.schedule(task, delayNanos, TimeUnit.NANOSECONDS)
        return V2ConfigLifecycleTask { future.cancel(false) }
    }

    override fun close() { executor.shutdownNow() }
}

internal class ThreadedV2ConfigLifecycleWorker : V2ConfigLifecycleWorker {
    private val current = AtomicReference<Thread?>()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "elu-config-fetch").apply { isDaemon = true }
    }

    override fun execute(task: () -> Unit) {
        executor.execute {
            val thread = Thread.currentThread()
            current.set(thread)
            try { task() } finally {
                current.compareAndSet(thread, null)
                Thread.interrupted()
            }
        }
    }

    override fun interruptCurrent() { current.get()?.interrupt() }
    override fun close() { executor.shutdownNow() }
}
