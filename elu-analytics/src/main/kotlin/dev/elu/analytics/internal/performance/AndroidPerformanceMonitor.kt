package dev.elu.analytics.internal.performance

import android.os.Debug
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import dev.elu.analytics.EluPerformanceOptions
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Optional process sampler; no thread or timer exists when local collection is disabled. */
internal class AndroidPerformanceMonitor(
    options: EluPerformanceOptions,
    main: Handler,
    context: () -> NativePerformanceContext?,
    emit: (NativePerformanceContext, Map<String, Any>) -> Unit,
    activityLifecycle: NativePerformanceActivityLifecycle,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "elu-performance").apply { isDaemon = true }
    }
    private val frames = if (options.frameMetrics && Build.VERSION.SDK_INT >= 26)
        NativeFrameMetrics(options.processAgeAtFirstObservedFrame) else null
    private val frameOwner = if (Build.VERSION.SDK_INT >= 26 && frames != null) {
        NativeFrameMetricsOwner(activityLifecycle, context,
            AndroidFrameMetricsAccess(main, options.processAgeAtFirstObservedFrame), frames)
    } else null
    private val sampler = NativePerformanceSampler(options, context, SystemClock::elapsedRealtimeNanos,
        { main.post(it) }, { main.removeCallbacks(it) },
        { Debug.getPss().takeIf { it > 0 && it <= Long.MAX_VALUE / 1024 }?.times(1024) }, emit,
        { frames?.drain(it).orEmpty() }, { frames?.clear() })
    private var scheduled: ScheduledFuture<*>? = null
    private var closed = false
    private val closeResult = object : SdkFuture<Unit>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }

    @Synchronized fun foreground(active: Boolean) {
        if (closed) return
        if (!active) {
            scheduled?.cancel(false); scheduled = null
            sampler.suspend()
            frameOwner?.refresh()
        } else if (scheduled == null) {
            scheduled = worker.scheduleWithFixedDelay({ runCatching { frameOwner?.refresh(); sampler.tick() } }, 0, 100, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun closeAndWait(): SdkFuture<Unit> {
        if (closed) return closeResult
        closed = true; scheduled?.cancel(false); scheduled = null
        sampler.close(); worker.shutdownNow()
        val frameClose = frameOwner?.closeAndWait() ?: SdkFuture.completedFuture(Unit)
        Thread({
            val workerFailure = try {
                if (worker.awaitTermination(5, TimeUnit.SECONDS)) null
                else IllegalStateException("Performance worker did not settle")
            } catch (error: InterruptedException) { Thread.currentThread().interrupt(); error }
            frameClose.whenComplete { _, error ->
                val failure = workerFailure ?: error
                if (failure == null) closeResult.complete(Unit) else closeResult.completeExceptionally(failure)
            }
        }, "elu-performance-close").apply { isDaemon = true }.start()
        return closeResult
    }

    override fun close() { closeAndWait() }
}
