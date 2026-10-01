package dev.elu.analytics

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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
        Elu.reset(true)
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
        Elu.stopSessionRecording()
        Elu.startSessionRecording()
        assertFalse(Elu.sessionRecordingStarted())

        assertNull(Elu.distinctId())
        assertNull(Elu.getFeatureFlag("flag"))
        assertNull(Elu.getFeatureFlagPayload("flag"))
        assertFalse(Elu.isFeatureEnabled("flag"))
        val options = EluFeatureFlagOptions(sendEvent = false, fresh = true)
        assertNull(Elu.getFeatureFlag("flag", options))
        assertNull(Elu.getFeatureFlagResult("flag", options))
        assertNull(Elu.isFeatureEnabled("flag", options))
        assertEquals(true, Elu.isFeatureEnabled("flag", options, true))
        assertEquals(false, Elu.isFeatureEnabled("flag", options, false))
    }

    @Test
    fun `Java overloads retain the original primitive signature and additive nullable reads`() {
        val facade = Elu::class.java
        val key = String::class.java
        val options = EluFeatureFlagOptions::class.java
        assertEquals(java.lang.Boolean.TYPE, facade.getMethod("isFeatureEnabled", key).returnType)
        assertEquals(java.lang.Boolean::class.java, facade.getMethod("isFeatureEnabled", key, options).returnType)
        assertEquals(java.lang.Boolean::class.java,
            facade.getMethod("isFeatureEnabled", key, options, java.lang.Boolean::class.java).returnType)
        assertEquals(Any::class.java, facade.getMethod("getFeatureFlag", key, options).returnType)
        assertEquals(EluFeatureFlagResult::class.java, facade.getMethod("getFeatureFlagResult", key, options).returnType)
        assertEquals(EluFeatureFlagOptions(), options.getConstructor().newInstance())
        assertEquals(EluFeatureFlagOptions(false), options.getConstructor(java.lang.Boolean.TYPE).newInstance(false))
        assertEquals(EluFeatureFlagOptions(false, true),
            options.getConstructor(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE).newInstance(false, true))
    }

    @Test
    fun `public consent survives pre-setup calls and reset`() {
        try {
            Elu.optOut()
            assertTrue(Elu.isOptedOut())
            Elu.reset()
            Elu.optIn(captureEventName = "")
            assertTrue(Elu.isOptedOut())
            Elu.optIn(captureEventName = null)
            assertFalse(Elu.isOptedOut())
        } finally { Elu.optIn(captureEventName = null) }
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
                        Elu.stopSessionRecording()
                        Elu.startSessionRecording()
                        assertFalse(Elu.sessionRecordingStarted())
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
