package dev.elu.analytics

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EluFacadeSafetyTest {
    @Test
    fun `every facade call is safe before setup`() {
        Elu.capture("event", mapOf("value" to 1))
        Elu.identify("user", mapOf("plan" to "test"))
        Elu.reset()
        Elu.alias("alias")
        Elu.screen("Home", mapOf("source" to "test"))
        Elu.captureException(IllegalStateException("test"), mapOf("handled" to true))
        Elu.register(mapOf("plan" to "test"))
        Elu.unregister("plan")
        Elu.setPersonProperties(mapOf("role" to "tester"))
        Elu.group("organization", "acme", mapOf("tier" to "test"))
        Elu.setPersonPropertiesForFlags(mapOf("role" to "tester"))
        Elu.setGroupPropertiesForFlags("organization", mapOf("tier" to "test"))
        Elu.reloadFeatureFlags()
        Elu.onFeatureFlagsLoaded { error("must not fire before setup") }
        Elu.flush()

        assertNull(Elu.distinctId())
        assertNull(Elu.getFeatureFlag("flag"))
        assertNull(Elu.getFeatureFlagPayload("flag"))
        assertFalse(Elu.isFeatureEnabled("flag"))
    }

    @Test
    fun `concurrent pre-setup calls never throw`() {
        val workers = 8
        val pool = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(workers) { worker ->
            pool.execute {
                start.await()
                repeat(250) { index ->
                    try {
                        Elu.capture("event-$worker-$index")
                        Elu.identify("user-$worker")
                        Elu.group("worker", worker.toString())
                        Elu.reset()
                        Elu.flush()
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()

        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.toString(), failures.isEmpty())
    }
}
