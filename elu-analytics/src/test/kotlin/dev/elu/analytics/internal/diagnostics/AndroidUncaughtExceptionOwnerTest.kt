package dev.elu.analytics.internal.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidUncaughtExceptionOwnerTest {
    @Test
    fun `construction is inert and verified installation delegates exact original arguments once`() {
        val calls = ArrayList<Pair<Thread, Throwable>>()
        val previous = Thread.UncaughtExceptionHandler { thread, throwable -> calls += thread to throwable }
        val registry = Registry(previous)
        val observations = ArrayList<NativeExceptionObservation>()
        val owner = AndroidUncaughtExceptionOwner(registry) { observations.add(it) }
        assertEquals(0, registry.reads)
        assertEquals(0, registry.writes)

        assertTrue(owner.install())
        val wrapper = registry.handler!!
        assertFalse(owner.install())
        val thread = Thread()
        val throwable = IllegalStateException("private")
        wrapper.uncaughtException(thread, throwable)
        assertEquals(1, observations.size)
        assertEquals(IllegalStateException::class.java.name, observations.single().type)
        assertEquals(1, calls.size)
        assertSame(thread, calls.single().first)
        assertSame(throwable, calls.single().second)
        assertTrue(owner.close())
        assertSame(previous, registry.handler)
        wrapper.uncaughtException(thread, throwable)
        assertEquals(1, observations.size)
        assertEquals(2, calls.size)
    }

    @Test
    fun `no original handler refuses without inventing a fallback`() {
        val registry = Registry(null)
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertFalse(owner.install())
        assertEquals(0, registry.writes)
        assertTrue(owner.close())
        assertFalse(owner.install())
        assertEquals(0, offers)
    }

    @Test
    fun `unreadable initial registry never attempts a registration`() {
        val registry = Registry(Thread.UncaughtExceptionHandler { _, _ -> })
        registry.read = { throw AssertionError("read unavailable") }
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertFalse(owner.install())
        assertEquals(0, registry.writes)
        assertTrue(owner.close())
        assertEquals(0, offers)
    }

    @Test
    fun `callback synchronously published by setter delegates but cannot observe before verification`() {
        val thread = Thread()
        val throwable = Throwable()
        var delegates = 0
        val registry = Registry(Thread.UncaughtExceptionHandler { t, e ->
            assertSame(thread, t); assertSame(throwable, e); delegates += 1
        })
        registry.write = { handler ->
            registry.handler = handler
            handler.uncaughtException(thread, throwable)
        }
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertTrue(owner.install())
        assertEquals(1, delegates)
        assertEquals(0, offers)
        registry.handler!!.uncaughtException(thread, throwable)
        assertEquals(2, delegates)
        assertEquals(1, offers)
        registry.write = { registry.handler = it }
        assertTrue(owner.close())
    }

    @Test
    fun `setter failure before publication leaves original in place and no retry install`() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        val registry = Registry(previous)
        registry.write = { throw AssertionError("set refused") }
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertFalse(owner.install())
        assertSame(previous, registry.handler)
        assertTrue(owner.close())
        assertFalse(owner.install())
        assertEquals(1, registry.writes)
        assertEquals(0, offers)
    }

    @Test
    fun `close from a synchronously published original callback cannot reactivate after setter returns`() {
        lateinit var owner: AndroidUncaughtExceptionOwner
        var delegates = 0
        var cleanupObserved = false
        val previous = Thread.UncaughtExceptionHandler { _, _ ->
            delegates += 1
            cleanupObserved = owner.close()
        }
        val registry = Registry(previous)
        registry.write = { handler ->
            registry.handler = handler
            if (handler !== previous) handler.uncaughtException(Thread(), Throwable())
        }
        var offers = 0
        owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertFalse(owner.install())
        assertTrue(cleanupObserved)
        assertSame(previous, registry.handler)
        assertEquals(1, delegates)
        assertEquals(0, offers)
        assertTrue(owner.close())
    }

    @Test
    fun `published then throwing setter retains inert original wrapper for retryable cleanup`() {
        var delegates = 0
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegates += 1 }
        val registry = Registry(previous)
        registry.write = { handler ->
            if (handler === previous) throw AssertionError("restore refused")
            registry.handler = handler
            throw AssertionError("set failed after publication")
        }
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertFalse(owner.install())
        val originalWrapper = registry.handler!!
        assertFalse(owner.close())
        originalWrapper.uncaughtException(Thread(), Throwable())
        assertEquals(1, delegates)
        assertSame(originalWrapper, registry.handler)
        registry.write = { registry.handler = it }
        assertTrue(owner.close())
        assertSame(previous, registry.handler)
        assertFalse(owner.install())
        assertEquals(0, offers)
    }

    @Test
    fun `verification read failure retains same cleanup handle and never admits`() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        val registry = Registry(previous)
        registry.read = {
            if (registry.reads > 1) throw AssertionError("verification unavailable")
            registry.handler
        }
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertFalse(owner.install())
        val originalWrapper = registry.handler!!
        originalWrapper.uncaughtException(Thread(), Throwable())
        assertFalse(owner.close())
        registry.read = { registry.handler }
        assertTrue(owner.close())
        assertSame(previous, registry.handler)
        assertEquals(0, offers)
    }

    @Test
    fun `displaced retained wrapper is permanently inert and close preserves later handler`() {
        var delegates = 0
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegates += 1 }
        val foreign = Thread.UncaughtExceptionHandler { _, _ -> fail("Must delegate original, not displaced registry handler") }
        val registry = Registry(previous)
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertTrue(owner.install())
        val originalWrapper = registry.handler!!
        registry.handler = foreign
        originalWrapper.uncaughtException(Thread(), Throwable())
        assertEquals(0, offers)
        assertEquals(1, delegates)
        assertTrue(owner.close())
        assertSame(foreign, registry.handler)
        registry.handler = originalWrapper // An external wrapper retaining/reinstalling it cannot revive it.
        originalWrapper.uncaughtException(Thread(), Throwable())
        assertEquals(0, offers)
        assertEquals(2, delegates)
        assertTrue(owner.close())
        assertSame(previous, registry.handler)
    }

    @Test
    fun `registry read error during callback denies all later offers and still delegates`() {
        var delegates = 0
        val registry = Registry(Thread.UncaughtExceptionHandler { _, _ -> delegates += 1 })
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertTrue(owner.install())
        val wrapper = registry.handler!!
        registry.read = { throw AssertionError("registry unavailable") }
        wrapper.uncaughtException(Thread(), Throwable())
        registry.read = { registry.handler }
        wrapper.uncaughtException(Thread(), Throwable())
        assertEquals(2, delegates)
        assertTrue(owner.close())
        assertEquals(0, offers)
    }

    @Test
    fun `admission failure does not replace original throwable or swallow original handler failure`() {
        val originalFailure = AssertionError("original handler failure")
        val throwable = Throwable()
        val thread = Thread()
        var delegates = 0
        val registry = Registry(Thread.UncaughtExceptionHandler { t, e ->
            assertSame(thread, t); assertSame(throwable, e); delegates += 1
            throw originalFailure
        })
        val owner = AndroidUncaughtExceptionOwner(registry) { throw AssertionError("SDK admission failure") }
        assertTrue(owner.install())
        val wrapper = registry.handler!!
        repeat(2) {
            try {
                wrapper.uncaughtException(thread, throwable)
                fail("Original failure must propagate")
            } catch (actual: AssertionError) {
                assertSame(originalFailure, actual)
            }
        }
        assertEquals(2, delegates)
        assertTrue(owner.close())
    }

    @Test
    fun `admission reentry suppresses only nested observation and delegates each original pair`() {
        val thread = Thread()
        val outer = Throwable()
        val nested = Throwable()
        val delegated = ArrayList<Throwable>()
        val registry = Registry(Thread.UncaughtExceptionHandler { t, e -> assertSame(thread, t); delegated += e })
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) {
            offers += 1
            registry.handler!!.uncaughtException(thread, nested)
        }
        assertTrue(owner.install())
        registry.handler!!.uncaughtException(thread, outer)
        assertEquals(1, offers)
        assertEquals(listOf(nested, outer), delegated)
        assertTrue(owner.close())
    }

    @Test
    fun `bounded original handler reentry cannot recursively report`() {
        val thread = Thread()
        val outer = Throwable()
        val nested = Throwable()
        val delegated = ArrayList<Throwable>()
        lateinit var wrapper: Thread.UncaughtExceptionHandler
        val registry = Registry(Thread.UncaughtExceptionHandler { t, e ->
            assertSame(thread, t); delegated += e
            if (e === outer) wrapper.uncaughtException(thread, nested)
        })
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertTrue(owner.install())
        wrapper = registry.handler!!
        wrapper.uncaughtException(thread, outer)
        assertEquals(1, offers)
        assertEquals(listOf(outer, nested), delegated)
        assertTrue(owner.close())
    }

    @Test
    fun `concurrent callback delegates without waiting and close does not claim admission join`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegates = AtomicInteger()
        val offers = AtomicInteger()
        val childFailure = AtomicReference<Throwable?>()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegates.incrementAndGet() }
        val registry = Registry(previous)
        val owner = AndroidUncaughtExceptionOwner(registry) {
            offers.incrementAndGet(); entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        assertTrue(owner.install())
        val wrapper = registry.handler!!
        val child = Thread {
            try {
                wrapper.uncaughtException(Thread.currentThread(), Throwable())
            } catch (failure: Throwable) {
                childFailure.set(failure)
            }
        }.apply { isDaemon = true }
        try {
            child.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            wrapper.uncaughtException(Thread.currentThread(), Throwable())
            assertEquals(1, delegates.get())
            assertEquals(1, offers.get())
            assertTrue(owner.close())
            assertSame(previous, registry.handler)
            assertTrue(child.isAlive) // Registration cleanup is not a join of arbitrary injected code.
            wrapper.uncaughtException(Thread.currentThread(), Throwable())
            assertEquals(2, delegates.get())
            assertEquals(1, offers.get())
        } finally {
            release.countDown()
            child.join(2_000)
            owner.close()
        }
        assertFalse(child.isAlive)
        assertEquals(null, childFailure.get())
        assertEquals(3, delegates.get())
    }

    @Test
    fun `close before install is inert and terminal`() {
        val registry = Registry(Thread.UncaughtExceptionHandler { _, _ -> })
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertTrue(owner.close())
        assertFalse(owner.install())
        assertEquals(0, registry.reads)
        assertEquals(0, registry.writes)
        assertEquals(0, offers)
    }

    @Test
    fun `close failure before restore is retryable without ever reactivating observation`() {
        var delegates = 0
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegates += 1 }
        val registry = Registry(previous)
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertTrue(owner.install())
        val wrapper = registry.handler!!
        registry.write = { throw AssertionError("restore refused") }
        assertFalse(owner.close())
        wrapper.uncaughtException(Thread(), Throwable())
        assertEquals(1, delegates)
        assertSame(wrapper, registry.handler)
        registry.write = { registry.handler = it }
        assertTrue(owner.close())
        assertTrue(owner.close())
        assertSame(previous, registry.handler)
        assertEquals(0, offers)
    }

    @Test
    fun `close failure after restore is reported conservatively and next close observes completion`() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        val registry = Registry(previous)
        var offers = 0
        val owner = AndroidUncaughtExceptionOwner(registry) { offers += 1 }
        assertTrue(owner.install())
        registry.write = {
            registry.handler = it
            throw AssertionError("restore acknowledgement failed")
        }
        assertFalse(owner.close())
        assertSame(previous, registry.handler)
        val writes = registry.writes
        assertTrue(owner.close())
        assertEquals(writes, registry.writes)
        assertEquals(0, offers)
    }

    @Test
    fun `original admission snapshot survives source replacement without adopting newer permission`() {
        val registry = Registry(Thread.UncaughtExceptionHandler { _, _ -> })
        var snapshots = 0; var originalOffers = 0; var latestOffers = 0
        var current = true
        val old = object : NativeUncaughtExceptionAdmission {
            override fun allowsObservation() = current
            override fun offer(observation: NativeExceptionObservation) { originalOffers += 1 }
        }
        val boundary = object : NativeUncaughtExceptionAdmission {
            override fun snapshot(): NativeUncaughtExceptionAdmission? { snapshots += 1; current = false; return old }
            override fun offer(observation: NativeExceptionObservation) { latestOffers += 1 }
        }
        val owner = AndroidUncaughtExceptionOwner(registry, boundary)
        assertTrue(owner.install()); registry.handler!!.uncaughtException(Thread(), Error())
        assertEquals(1, snapshots); assertEquals(0, originalOffers); assertEquals(0, latestOffers)
        assertTrue(owner.close())
    }

    @Test
    fun `restoration failure leaves retained wrapper unable even to query admission but still delegates exactly once`() {
        val calls = ArrayList<Pair<Thread, Throwable>>()
        val registry = Registry(Thread.UncaughtExceptionHandler { t, e -> calls += t to e })
        var reads = 0; var offers = 0
        val boundary = object : NativeUncaughtExceptionAdmission {
            override fun allowsObservation(): Boolean { reads += 1; return true }
            override fun offer(observation: NativeExceptionObservation) { offers += 1 }
        }
        val owner = AndroidUncaughtExceptionOwner(registry, boundary)
        assertTrue(owner.install()); val wrapper = registry.handler!!
        registry.write = { throw IllegalStateException("restoration failed") }
        assertFalse(owner.close())
        val thread = Thread(); val error = Error("PRIVATE")
        wrapper.uncaughtException(thread, error)
        assertEquals(0, reads); assertEquals(0, offers); assertEquals(1, calls.size)
        assertSame(thread, calls.single().first); assertSame(error, calls.single().second)
        registry.write = { registry.handler = it }; assertTrue(owner.close())
    }

    private class Registry(@Volatile var handler: Thread.UncaughtExceptionHandler?) : NativeUncaughtExceptionRegistry {
        var reads = 0
        var writes = 0
        var read: () -> Thread.UncaughtExceptionHandler? = { handler }
        var write: (Thread.UncaughtExceptionHandler) -> Unit = { handler = it }

        override fun current(): Thread.UncaughtExceptionHandler? {
            reads += 1
            return read()
        }

        override fun replace(handler: Thread.UncaughtExceptionHandler) {
            writes += 1
            write(handler)
        }
    }
}
