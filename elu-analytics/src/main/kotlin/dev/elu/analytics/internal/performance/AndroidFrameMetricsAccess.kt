package dev.elu.analytics.internal.performance

import android.annotation.TargetApi
import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.lang.ref.WeakReference

/** Constructed only on API26+. Public numeric metrics only; no View or content reads. */
@TargetApi(26)
internal class AndroidFrameMetricsAccess(
    private val main: Handler,
    private val includeProcessAge: Boolean,
) : NativeFrameMetricsAccess {
    override fun onMain(action: () -> Unit) {
        if (Looper.myLooper() === main.looper) action()
        else check(main.post(action)) { "Frame metrics main thread is unavailable" }
    }

    override fun nanoTime(): Long = System.nanoTime()

    override fun watch(activity: Any, current: () -> Boolean, emit: (NativeFrameMeasurement) -> Unit): AutoCloseable {
        check(Looper.myLooper() === main.looper)
        val originalActivity = activity as Activity
        val weak = WeakReference(originalActivity)
        val originalWindow = originalActivity.window
        fun stillCurrent(): Boolean = current() && weak.get()?.let { !it.isDestroyed && it.window === originalWindow } == true
        check(stillCurrent())
        val listener = Window.OnFrameMetricsAvailableListener { window, metrics, dropped ->
            // Framework reuses metrics. Copy only fixed scalars within this callback.
            runCatching {
                if (window !== originalWindow || !stillCurrent()) return@runCatching
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val started = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                val firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)
                val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else null
                val observed = System.nanoTime()
                val age = if (includeProcessAge) {
                    val now = SystemClock.elapsedRealtime(); val start = Process.getStartElapsedRealtime()
                    (now - start).takeIf { start >= 0 && now >= start }
                } else null
                if (firstDraw !in 0..1 || !stillCurrent()) return@runCatching
                emit(NativeFrameMeasurement(started, observed, duration, firstDraw == 1L, deadline, dropped, age))
            }
        }
        val cleanup = AutoCloseable {
            check(Looper.myLooper() === main.looper)
            originalWindow.removeOnFrameMetricsAvailableListener(listener)
        }
        try { originalWindow.addOnFrameMetricsAvailableListener(listener, main) }
        catch (error: Throwable) { throw NativeFrameWatchAcquisitionFailure(cleanup, error) }
        return cleanup
    }
}
