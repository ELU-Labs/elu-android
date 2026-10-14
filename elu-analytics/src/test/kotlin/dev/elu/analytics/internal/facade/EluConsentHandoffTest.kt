package dev.elu.analytics.internal.facade

import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class EluConsentHandoffTest {
    private class Target {
        var denied = false
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val sink = Proxy.newProxyInstance(EluFacadeSink::class.java.classLoader, arrayOf(EluFacadeSink::class.java)) { _, method, args ->
            if (method.name == "isOptedOut") denied else {
                calls += method.name to args.orEmpty().toList()
                if (method.name == "optOut") denied = true
                if (method.name == "optIn") denied = false
                null
            }
        } as EluFacadeSink
    }

    @Test fun `latest complete opt in is copied and delivered once before start`() {
        val handoff = EluConsentHandoff(); val target = Target()
        val properties = mutableMapOf<String, Any>("source" to "settings")
        handoff.optOut(); handoff.optIn("accepted", properties); properties["source"] = "changed"
        handoff.install(target.sink) {
            assertSame(target.sink, handoff.sink)
            assertEquals(listOf("optIn" to listOf("accepted", mapOf("source" to "settings"))), target.calls)
        }
        assertFalse(handoff.isOptedOut())
        handoff.optOut()
        assertTrue(handoff.isOptedOut())
        assertEquals(listOf("optIn", "optOut"), target.calls.map { it.first })
    }

    @Test fun `failed start preserves denial for retry and duplicate setup cannot replace target`() {
        val handoff = EluConsentHandoff(); handoff.optOut()
        try { handoff.install(Target().sink) { error("startup rejected") }; fail() } catch (_: IllegalStateException) { }
        assertNull(handoff.sink); assertTrue(handoff.isOptedOut())
        val target = Target(); handoff.install(target.sink) { assertTrue(target.denied) }
        try { handoff.install(Target().sink) { fail() }; fail() } catch (_: IllegalStateException) { }
        assertSame(target.sink, handoff.sink)
    }

    @Test fun `publication and concurrent consent cannot lose the latest denial`() {
        val handoff = EluConsentHandoff(); val target = Target()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val denied = CountDownLatch(1)
        handoff.optIn(null, null)
        val setup = Thread { handoff.install(target.sink) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val withdrawal = Thread { handoff.optOut(); denied.countDown() }
        try {
            setup.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); withdrawal.start()
            assertFalse(denied.await(100, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        setup.join(5_000); withdrawal.join(5_000)
        assertFalse(setup.isAlive); assertFalse(withdrawal.isAlive)
        assertTrue(handoff.isOptedOut())
        assertEquals(listOf("optIn", "optOut"), target.calls.map { it.first })
    }
}
