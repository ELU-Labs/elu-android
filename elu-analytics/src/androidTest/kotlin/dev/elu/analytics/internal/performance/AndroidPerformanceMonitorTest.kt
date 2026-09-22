package dev.elu.analytics.internal.performance

import android.os.Handler
import android.os.Looper
import dev.elu.analytics.EluPerformanceOptions
import dev.elu.analytics.internal.config.V1CapturePerformance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPerformanceMonitorTest {
    @Test
    fun realMainLooperStallAndPssSampleStopInBackground() {
        val main = Handler(Looper.getMainLooper())
        val context = AtomicReference(NativePerformanceContext(Any(), 0, 0, "session", 0, Any(),
            V1CapturePerformance(memory = true, longTasks = true, sampleIntervalMillis = 5_000)))
        val samples = LinkedBlockingQueue<Map<String, Any>>()
        val enteredMain = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val monitor = AndroidPerformanceMonitor(
            EluPerformanceOptions(enabled = true, sampleIntervalMillis = 5_000, mainThreadStallThresholdMillis = 100),
            main, { context.get() }, { _, properties -> samples.offer(properties) },
        )
        try {
            main.post { enteredMain.countDown(); releaseMain.await(2, TimeUnit.SECONDS) }
            assertTrue(enteredMain.await(5, TimeUnit.SECONDS))
            monitor.foreground(true)
            // The sampler worker continues while the actual main looper is blocked.
            Thread.sleep(500)
            releaseMain.countDown()
            val sample = samples.poll(10, TimeUnit.SECONDS)
            assertNotNull("Expected a real process/main-looper sample", sample)
            requireNotNull(sample)
            assertEquals("android", sample["\$performance_platform"])
            assertTrue((sample["\$memory_process_pss_bytes"] as Number).toLong() > 0)
            assertTrue((sample["\$main_thread_stall_count"] as Number).toLong() >= 1)
            assertTrue((sample["\$main_thread_stall_max_ms"] as Number).toLong() >= 100)
            monitor.foreground(false)
            samples.clear()
            assertNull("No samples are allowed while backgrounded", samples.poll(6, TimeUnit.SECONDS))
        } finally {
            releaseMain.countDown()
            monitor.close()
        }
    }
}
