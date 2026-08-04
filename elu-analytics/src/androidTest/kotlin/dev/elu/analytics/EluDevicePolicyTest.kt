package dev.elu.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EluDevicePolicyTest {
    @Test
    fun eluOwnedPreferenceNamespaceSurvivesReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("dev.elu.analytics.instrumentation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        assertTrue(prefs.edit().putLong("elu.firstLaunchAt", 1234L).commit())

        val reopened = context.getSharedPreferences("dev.elu.analytics.instrumentation", Context.MODE_PRIVATE)
        assertEquals(1234L, reopened.getLong("elu.firstLaunchAt", 0L))
        reopened.edit().clear().commit()
    }

    @Test
    fun privacyGuardAndFacadeRemainSafeAcrossDeviceThreads() {
        assertTrue(EluEuGuard.isEuTimezone("Europe/Paris"))
        assertFalse(EluEuGuard.isEuTimezone("America/Los_Angeles"))

        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) { worker ->
            pool.execute {
                repeat(100) { index -> Elu.capture("instrumentation-$worker-$index") }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
    }
}
