package dev.elu.analytics.internal.diagnostics

import dev.elu.analytics.internal.runtime.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class NativeExceptionIntakeTest {
    private val owned = mutableListOf<NativeExceptionIntake>()
    @After fun close() { owned.forEach { it.close().get(3, TimeUnit.SECONDS); it.joinClosedWriter() } }
    private class Clock : RuntimeCaptureClock {
        @Volatile var wall = 1000L
        @Volatile var mono = 1_000_000L
        override fun wallNowEpochMillis() = wall
        override fun elapsedRealtimeNanos() = mono
    }
    private fun reservation() = RuntimeExceptionReservation("11111111-1111-1111-1111-111111111111", "a".repeat(64),
        "stream", "anon", 2, 3, "b".repeat(64), 1000, 2000)
    private class Spool : NativeExceptionSpool {
        @Volatile var report: RuntimeExceptionReport? = null
        var beforePublish: () -> Unit = {}
        var publications = 0
        override fun read() = report
        override fun publish(report: RuntimeExceptionReport, mayPublish: () -> Boolean): Boolean {
            beforePublish()
            if (!mayPublish()) return false
            publications++; this.report = report; return true
        }
        override fun clear() { report = null }
    }
    @Test fun `one atomic claim records only detached type with no waiting`() {
        val spool = Spool(); val clock = Clock()
        val intake = NativeExceptionIntake(reservation(), spool, NativeExceptionPolicyLease("b".repeat(64)), clock, clock.mono, 1_000_000_000, sourceIsCurrent = { true })
        owned += intake; intake.start()
        val observation = NativeExceptionObservation.from(IllegalArgumentException("SECRET"))
        val threads = List(16) { Thread { intake.offer(observation) }.apply { start() } }
        threads.forEach { it.join(2000); assertFalse(it.isAlive) }
        intake.reportSettlement.get(2, TimeUnit.SECONDS); intake.close().get(2, TimeUnit.SECONDS)
        assertEquals(1, spool.publications)
        val bytes = spool.report!!.encode().toString(Charsets.UTF_8)
        assertFalse(bytes.contains("SECRET")); assertFalse(bytes.contains("stack")); assertFalse(bytes.contains("thread"))
        assertEquals("java.lang.IllegalArgumentException", spool.report!!.type)
        assertEquals(spool.report, RuntimeExceptionReport.decode(spool.report!!.encode()))
    }
    @Test fun `publication rechecks original wall monotonic and revoked permission after blocked IO`() {
        for (change in listOf<(Clock, NativeExceptionPolicyLease) -> Unit>(
            { clock, _ -> clock.wall = 2000 }, { clock, _ -> clock.wall = 999 },
            { clock, _ -> clock.mono += 1_000_000_000 }, { clock, _ -> clock.mono-- }, { _, policy -> policy.revoke() })) {
            val clock = Clock(); val policy = NativeExceptionPolicyLease("b".repeat(64)); val spool = Spool()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            spool.beforePublish = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            val intake = NativeExceptionIntake(reservation(), spool, policy, clock, clock.mono, 1_000_000_000, sourceIsCurrent = { true })
            owned += intake; intake.start()
            try {
                intake.offer(NativeExceptionObservation.from(Error("PRIVATE")))
                assertTrue(entered.await(2, TimeUnit.SECONDS)); assertFalse(intake.settlement.isDone)
                change(clock, policy)
            } finally { release.countDown() }
            intake.reportSettlement.get(2, TimeUnit.SECONDS); intake.close().get(2, TimeUnit.SECONDS)
            assertFalse(intake.published); assertNull(spool.report)
        }
    }
    @Test fun `close completion remains pending for original blocked writer and no post-close publication`() {
        val clock = Clock(); val spool = Spool(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        spool.beforePublish = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        val intake = NativeExceptionIntake(reservation(), spool, NativeExceptionPolicyLease("b".repeat(64)), clock, clock.mono, 1_000_000_000, sourceIsCurrent = { true })
        owned += intake; intake.start()
        try {
            intake.offer(NativeExceptionObservation.from(Error()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(intake.close().isDone)
            assertFalse(intake.settlement.cancel(true))
        } finally { release.countDown() }
        intake.reportSettlement.get(2, TimeUnit.SECONDS); intake.close().get(2, TimeUnit.SECONDS)
        assertNull(spool.report)
    }
    @Test fun `IO failure is retained and closes writer without falsely acknowledging publication`() {
        val spool = Spool().apply { beforePublish = { throw java.io.IOException("injected") } }
        val clock = Clock(); val intake = NativeExceptionIntake(reservation(), spool, NativeExceptionPolicyLease("b".repeat(64)), clock, clock.mono, 1_000_000_000, sourceIsCurrent = { true })
        owned += intake; intake.start(); intake.offer(NativeExceptionObservation.from(Error()))
        intake.reportSettlement.get(2, TimeUnit.SECONDS); intake.close().get(2, TimeUnit.SECONDS)
        assertTrue(intake.failure is java.io.IOException); assertFalse(intake.published)
        intake.offer(NativeExceptionObservation.from(Error()))
        assertEquals(0, spool.publications)
    }
    @Test fun `publication observation timeout retains original work and later acknowledgement is not cancellation`() {
        val clock = Clock(); val spool = Spool(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        spool.beforePublish = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val intake = NativeExceptionIntake(reservation(), spool, NativeExceptionPolicyLease("b".repeat(64)), clock,
            clock.mono, 1_000_000_000, sourceIsCurrent = { true })
        owned += intake; intake.start()
        try {
            assertEquals(NativeExceptionPublication.UNCONFIRMED, intake.offerAndObserve(NativeExceptionObservation.from(Error())))
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertFalse(intake.reportSettlement.isDone)
            assertFalse(intake.reportSettlement.cancel(true)); assertFalse(intake.settlement.isDone)
            assertEquals(NativeExceptionPublication.NOT_ADMITTED, intake.offerAndObserve(NativeExceptionObservation.from(Error())))
        } finally { release.countDown() }
        intake.reportSettlement.get(2, TimeUnit.SECONDS)
        assertTrue(intake.published); assertNotNull(spool.report)
    }
    @Test fun `interrupted publication observation restores flag without abandoning original held write`() {
        val clock = Clock(); val spool = Spool(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        spool.beforePublish = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val intake = NativeExceptionIntake(reservation(), spool, NativeExceptionPolicyLease("b".repeat(64)), clock,
            clock.mono, 1_000_000_000, sourceIsCurrent = { true })
        owned += intake; intake.start()
        try {
            Thread.currentThread().interrupt()
            assertEquals(NativeExceptionPublication.UNCONFIRMED, intake.offerAndObserve(NativeExceptionObservation.from(Error())))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertFalse(intake.reportSettlement.isDone)
            intake.invalidate()
        } finally { release.countDown() }
        intake.reportSettlement.get(2, TimeUnit.SECONDS); assertNull(spool.report)
    }
    @Test fun `original admission snapshot cannot adopt a replacement arm and completed results stay per arm`() {
        val clock = Clock(); val spool = Spool(); val intake = NativeExceptionIntake(reservation(), spool,
            NativeExceptionPolicyLease("b".repeat(64)), clock, clock.mono, 1_000_000_000, sourceIsCurrent = { true })
        owned += intake; intake.start()
        val old = checkNotNull(intake.snapshotForPublication())
        intake.invalidate(); intake.reportSettlement.get(2, TimeUnit.SECONDS)
        intake.rearm(reservation().copy(id = "22222222-2222-2222-2222-222222222222"), NativeExceptionPolicyLease("b".repeat(64)),
            clock.mono, 1_000_000_000, sourceIsCurrent = { true })
        assertFalse(old.allowsObservation()); old.offer(NativeExceptionObservation.from(Error()))
        assertNull(spool.report); assertTrue(intake.allowsObservation())
        // Real writer completion is observed under the exact new arm; neither this call nor
        // an old snapshot can consume another future reservation.
        val outcome = intake.offerAndObserve(NativeExceptionObservation.from(IllegalStateException()))
        assertTrue(outcome == NativeExceptionPublication.PUBLISHED || outcome == NativeExceptionPublication.UNCONFIRMED)
        intake.reportSettlement.get(2, TimeUnit.SECONDS)
        assertEquals("22222222-2222-2222-2222-222222222222", spool.report!!.reservation.id)
        assertEquals(1, spool.publications)
    }
    @Test fun `canonical bounded state refuses extra or changed namespace and malformed report`() {
        val state = RuntimeExceptionState("stream", reservation())
        assertEquals(state, RuntimeExceptionState.decode(state.encode()))
        for (bytes in listOf(state.encode() + byteArrayOf(32), ByteArray(4097), "{}".toByteArray()))
            assertThrows(RuntimeQueueCorruptionException::class.java) { RuntimeExceptionState.decode(bytes) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeExceptionReport(reservation(), 2000, "Type", false) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeExceptionReport(reservation(), 1000, "x".repeat(257), false) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeExceptionState("foreign", reservation()) }
    }
    @Test fun `physical join outlives completed future and preserves joining worker interruption`() {
        val clock = Clock(); val intake = NativeExceptionIntake(reservation(), Spool(),
            NativeExceptionPolicyLease("b".repeat(64)), clock, clock.mono, 1_000_000_000, sourceIsCurrent = { true })
        owned += intake; intake.start()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val joined = CountDownLatch(1)
        val writer = java.util.concurrent.atomic.AtomicReference<Thread>()
        val interruptPreserved = java.util.concurrent.atomic.AtomicBoolean(false)
        intake.settlement.whenComplete { _, _ ->
            writer.set(Thread.currentThread()); entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
        }
        val joining = Thread {
            Thread.currentThread().interrupt()
            intake.joinClosedWriter()
            interruptPreserved.set(Thread.currentThread().isInterrupted)
            joined.countDown()
        }
        try {
            intake.close(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(intake.settlement.isDone); assertTrue(writer.get().isAlive)
            joining.start()
            assertFalse(joined.await(100, TimeUnit.MILLISECONDS))
            release.countDown(); assertTrue(joined.await(2, TimeUnit.SECONDS))
            assertFalse(writer.get().isAlive); assertTrue(interruptPreserved.get())
        } finally { release.countDown(); if (joining.state != Thread.State.NEW) joining.join(3_000) }
        assertFalse(joining.isAlive)
    }
}
