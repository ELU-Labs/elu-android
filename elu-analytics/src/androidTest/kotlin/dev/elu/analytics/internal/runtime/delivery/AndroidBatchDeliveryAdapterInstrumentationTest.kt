package dev.elu.analytics.internal.runtime.delivery

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBatchDeliveryAdapterInstrumentationTest {
    @Test
    fun mainThreadBackgroundCallbackNeverRunsOrWaitsForDeliveryWork() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val ranOffMain = AtomicBoolean(false)
        val adapter =
            AndroidBatchDeliveryAdapter(executor) {
                ranOffMain.set(Thread.currentThread() !== Looper.getMainLooper().thread)
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                finished.countDown()
            }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstAccepted = AtomicBoolean(false)

        instrumentation.runOnMainSync {
            firstAccepted.set(adapter.onBackgrounded())
            assertFalse(adapter.onForegrounded())
        }

        assertTrue(firstAccepted.get())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(ranOffMain.get())
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
    }
}
