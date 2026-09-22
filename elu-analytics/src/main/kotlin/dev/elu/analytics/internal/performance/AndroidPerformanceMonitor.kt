package dev.elu.analytics.internal.performance

import android.os.Debug
import android.os.Handler
import android.os.SystemClock
import dev.elu.analytics.EluPerformanceOptions
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Optional process sampler; no thread or timer exists when local collection is disabled. */
internal class AndroidPerformanceMonitor(
    options: EluPerformanceOptions,
    main: Handler,
    context: () -> NativePerformanceContext?,
    emit: (NativePerformanceContext, Map<String, Any>) -> Unit,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "elu-performance").apply { isDaemon = true }
    }
    private val sampler = NativePerformanceSampler(options, context, SystemClock::elapsedRealtimeNanos,
        { main.post(it) }, { main.removeCallbacks(it) },
        { Debug.getPss().takeIf { it > 0 && it <= Long.MAX_VALUE / 1024 }?.times(1024) }, emit)
    private var scheduled: ScheduledFuture<*>? = null
    private var closed = false

    @Synchronized fun foreground(active: Boolean) {
        if (closed) return
        if (!active) {
            scheduled?.cancel(false); scheduled = null
            sampler.suspend()
        } else if (scheduled == null) {
            scheduled = worker.scheduleWithFixedDelay({ runCatching { sampler.tick() } }, 0, 100, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true; scheduled?.cancel(false); scheduled = null
        sampler.close(); worker.shutdownNow()
    }
}
