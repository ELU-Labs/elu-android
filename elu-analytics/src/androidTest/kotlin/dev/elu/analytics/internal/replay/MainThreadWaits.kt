package dev.elu.analytics.internal.replay

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bound for fixture waits on the main thread. A stuck wait fails here, not at the CI job limit. */
internal const val MAIN_THREAD_WAIT_SECONDS = 10L

/**
 * Instrumentation.waitForIdleSync() with a bound. The platform call waits, with no limit, until
 * the main queue is idle. A window that redraws every frame never lets it idle: before API 24 the
 * stock indeterminate ProgressBar can do this, because its AnimatedVectorDrawable invalidates
 * itself from draw() on the main thread.
 */
internal fun waitForMainIdle(what: String, seconds: Long = MAIN_THREAD_WAIT_SECONDS) {
    check(Looper.myLooper() !== Looper.getMainLooper()) { "Main-thread idle cannot be awaited on the main thread" }
    val idle = CountDownLatch(1)
    val handler = MessageQueue.IdleHandler { idle.countDown(); false }
    val queue = Looper.getMainLooper().queue
    queue.addIdleHandler(handler)
    Handler(Looper.getMainLooper()).post { } // Wakes the queue, as waitForIdleSync() does.
    if (!idle.await(seconds, TimeUnit.SECONDS)) {
        queue.removeIdleHandler(handler)
        throw AssertionError("Main thread did not go idle within ${seconds}s $what (API ${Build.VERSION.SDK_INT}). " +
            "Something keeps it busy, such as a window that redraws every frame.")
    }
}

/** Instrumentation.runOnMainSync() with a bound. A failure inside [block] is rethrown here. */
internal fun runOnMainBounded(what: String, seconds: Long = MAIN_THREAD_WAIT_SECONDS, block: () -> Unit) {
    check(Looper.myLooper() !== Looper.getMainLooper()) { "A bounded main-thread call cannot run on the main thread" }
    val done = CountDownLatch(1)
    var failure: Throwable? = null
    check(Handler(Looper.getMainLooper()).post {
        try { block() } catch (error: Throwable) { failure = error } finally { done.countDown() }
    }) { "Main thread is not accepting work: $what" }
    if (!done.await(seconds, TimeUnit.SECONDS)) {
        throw AssertionError("Main thread did not run $what within ${seconds}s (API ${Build.VERSION.SDK_INT})")
    }
    failure?.let { throw it }
}
