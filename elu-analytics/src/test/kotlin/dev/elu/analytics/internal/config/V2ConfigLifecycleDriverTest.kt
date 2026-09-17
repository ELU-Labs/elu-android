package dev.elu.analytics.internal.config

import java.io.IOException
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConfigLifecycleDriverTest {
    @Test
    fun `start is pending then publishes an opaque consumable decision and schedules renewal and expiry`() {
        val rig = Rig()
        rig.driver.start()
        rig.driver.start()
        assertEquals(1, rig.worker.queued.size)
        assertTrue(rig.updates.isEmpty())
        rig.worker.runNext()
        assertEquals(listOf(rig.body), rig.deliveries)
        assertEquals(listOf(192 * SECOND, 240 * SECOND), rig.scheduler.activeDelays())
        assertFalse(rig.driver.diagnostics().fetchInFlight)
        rig.driver.close()
    }

    @Test
    fun `independent expiry withdraws while an uncooperative renewal is still blocked`() {
        val rig = Rig()
        rig.driver.start()
        rig.worker.runNext()
        rig.advance(192 * SECOND)
        rig.scheduler.runDue()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        rig.fetch = {
            entered.countDown()
            check(released.await(2, TimeUnit.SECONDS))
            V2ConfigHttpResponse(503, null)
        }
        val thread = Thread { rig.worker.runNext() }
        thread.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            rig.advance(48 * SECOND)
            rig.scheduler.runDue()
            assertNull(rig.deliveries.last())
            assertTrue(rig.driver.diagnostics().fetchInFlight)
            assertEquals(2, rig.calls)
        } finally {
            released.countDown()
            thread.join(2_000)
            rig.driver.close()
        }
    }

    @Test
    fun `background cancels timers and late fetch and foreground waits for the physical slot`() {
        val rig = Rig()
        rig.driver.start()
        rig.worker.runNext()
        val oldNotice = rig.updates.last()
        rig.driver.refresh()
        assertEquals(1, rig.worker.queued.size)
        rig.driver.onBackground()
        assertNull(rig.deliveries.last())
        assertTrue(rig.scheduler.activeDelays().isEmpty())
        assertFalse(oldNotice.consume { error("old enabled callback") })
        rig.driver.onForeground()
        assertEquals(1, rig.worker.queued.size)
        rig.worker.runNext() // canceled queued attempt releases its physical slot
        assertEquals(1, rig.worker.queued.size)
        assertEquals(1, rig.calls)
        rig.worker.runNext()
        assertEquals(2, rig.calls)
        assertEquals(rig.body, rig.deliveries.last())
        rig.driver.close()
    }

    @Test
    fun `background during blocking transport prevents that response and replays one pending foreground request`() {
        val rig = Rig()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        rig.fetch = {
            entered.countDown()
            check(released.await(2, TimeUnit.SECONDS))
            V2ConfigHttpResponse(200, rig.body)
        }
        rig.driver.start()
        val thread = Thread { rig.worker.runNext() }
        thread.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            rig.driver.onBackground()
            rig.driver.onForeground()
            rig.driver.refresh()
            assertEquals(1, rig.calls)
            assertTrue(rig.worker.queued.isEmpty())
            released.countDown()
            thread.join(2_000)
            assertNull(rig.deliveries.last())
            rig.fetch = { V2ConfigHttpResponse(200, rig.body) }
            assertEquals(1, rig.worker.queued.size)
            rig.worker.runNext()
            assertEquals(2, rig.calls)
            assertEquals(rig.body, rig.deliveries.last())
        } finally {
            released.countDown()
            thread.join(2_000)
            rig.driver.close()
        }
    }

    @Test
    fun `delayed notifications revalidate generation expiry and terminal close when consumed`() {
        val rig = Rig(consumeImmediately = false)
        rig.driver.start()
        rig.worker.runNext()
        val first = rig.updates.single()
        rig.advance(240 * SECOND)
        assertFalse(first.consume { error("expired callback") })
        assertTrue(rig.updates.last().consume { assertNull(it) })
        rig.driver.close()
        val closed = rig.updates.last()
        assertFalse(first.consume { error("closed callback") })
        assertTrue(closed.consume { assertNull(it) })
        rig.driver.onForeground()
        rig.driver.refresh()
        assertEquals(V2ConfigLifecyclePhase.CLOSED, rig.driver.diagnostics().phase)
        assertTrue(rig.worker.queued.isEmpty())
    }

    @Test
    fun `failed renewal withdraws retries exponentially within one minute and success resets retry`() {
        val rig = Rig()
        rig.driver.start()
        rig.worker.runNext()
        rig.fetch = { throw IOException("offline") }
        rig.driver.refresh()
        rig.worker.runNext()
        assertNull(rig.deliveries.last())
        listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L).forEach { seconds ->
            assertEquals(listOf(seconds * SECOND), rig.scheduler.activeDelays())
            rig.advance(seconds * SECOND)
            rig.scheduler.runDue()
            assertEquals(1, rig.worker.queued.size)
            rig.worker.runNext()
        }
        rig.body = rig.config().put("issuedAt", Instant.ofEpochMilli(rig.clock.wall).toString())
            .put("expiresAt", Instant.ofEpochMilli(rig.clock.wall + 300_000).toString()).toString()
        rig.fetch = { V2ConfigHttpResponse(200, rig.body) }
        rig.driver.refresh()
        rig.worker.runNext()
        assertEquals(SECOND, rig.driver.diagnostics().retryDelayNanos)
        assertEquals(rig.body, rig.deliveries.last())
        rig.driver.close()
    }

    @Test
    fun `canceled timer callbacks cannot start fetches or withdraw a renewed decision`() {
        val rig = Rig()
        rig.driver.start()
        rig.worker.runNext()
        val canceled = rig.scheduler.tasks.toList()
        rig.driver.onBackground()
        rig.driver.onForeground()
        rig.worker.runNext()
        val sequence = rig.driver.diagnostics().sequence
        canceled.forEach { it.task() }
        assertEquals(sequence, rig.driver.diagnostics().sequence)
        assertTrue(rig.worker.queued.isEmpty())
        assertEquals(rig.body, rig.deliveries.last())
        rig.driver.close()
    }

    @Test
    fun `same document renewals and background cannot extend the retained monotonic lease`() {
        val rig = Rig()
        rig.driver.start()
        rig.worker.runNext()
        rig.clock.nanos += 120 * SECOND // wall clock stalls
        rig.driver.onBackground()
        rig.driver.onForeground()
        rig.worker.runNext()
        assertEquals(120 * SECOND, rig.scheduler.activeDelays().maxOrNull())
        rig.clock.nanos += 120 * SECOND
        rig.scheduler.runDue()
        assertNull(rig.deliveries.last())
        rig.driver.close()
    }

    @Test
    fun `disabled configuration is delivered as exact data and still renewed`() {
        val rig = Rig()
        rig.body = JSONObject().put("schemaVersion", 2).put("revision", "disabled").put("status", "disabled")
            .put("reason", "test").put("issuedAt", "2026-08-05T00:00:00Z").put("expiresAt", "2026-08-05T00:05:00Z").toString()
        rig.driver.start()
        rig.worker.runNext()
        assertEquals(rig.body, rig.deliveries.last())
        assertEquals(2, rig.scheduler.activeDelays().size)
        rig.driver.close()
    }

    @Test
    fun `scheduler or worker failure closes the source and invalidates enabled notifications`() {
        listOf(false, true).forEach { workerFailure ->
            val rig = Rig()
            if (workerFailure) rig.worker.rejected = true else rig.scheduler.rejected = true
            rig.driver.start()
            if (!workerFailure) rig.worker.runNext()
            assertEquals(V2ConfigLifecyclePhase.CLOSED, rig.driver.diagnostics().phase)
            assertTrue(rig.driver.diagnostics().terminalFailure != null)
            assertNull(rig.deliveries.last())
            assertNull(rig.source.currentDocument())
        }
    }

    @Test fun `same-document renewal still suspends its current lease without extending deadline`() {
        val rig = Rig()
        try {
            rig.driver.start(); rig.worker.runNext()
            val original = rig.updates.last()
            rig.clock.nanos += 60 * SECOND
            rig.driver.refresh(); rig.worker.runNext()
            // The driver deduplicates identical bodies, but the source reparses exact expiry.
            assertTrue(original === rig.updates.last())
            rig.driver.onBackground()
            assertEquals(V2ConfigLifecycleUpdateKind.APPLICATION_SUSPENSION, rig.updates.last().kind)
            rig.driver.onForeground(); rig.worker.runNext()
            assertEquals(180 * SECOND, rig.scheduler.activeDelays().maxOrNull())
            rig.clock.nanos += 180 * SECOND
            rig.scheduler.runDue()
            assertEquals(V2ConfigLifecycleUpdateKind.CONFIGURATION, rig.updates.last().kind)
            assertNull(rig.deliveries.last())
        } finally { rig.driver.close() }
    }

    @Test fun `suspension null followed by failed refresh publishes permanent withdrawal`() {
        for (failure in listOf("malformed", "http", "transport")) {
            val rig = Rig()
            try {
                rig.driver.start(); rig.worker.runNext()
                rig.driver.onBackground()
                val suspension = rig.updates.last()
                assertEquals(V2ConfigLifecycleUpdateKind.APPLICATION_SUSPENSION, suspension.kind)
                assertTrue(suspension.consume { assertNull(it) })
                rig.fetch = {
                    when (failure) {
                        "malformed" -> V2ConfigHttpResponse(200, "{")
                        "http" -> V2ConfigHttpResponse(503, null)
                        else -> throw IOException("offline")
                    }
                }
                rig.driver.onForeground(); rig.worker.runNext()
                val withdrawal = rig.updates.last()
                assertEquals(failure, V2ConfigLifecycleUpdateKind.CONFIGURATION, withdrawal.kind)
                assertTrue(failure, withdrawal.sequence > suspension.sequence)
                assertFalse(suspension.consume { error("suspension survived failure") })
                assertTrue(withdrawal.consume { assertNull(it) })
            } finally { rig.driver.close() }
        }
    }

    @Test fun `absent expired clock-failed or closed lease cannot become suspension`() {
        for (mode in listOf("absent", "expired", "clock", "source-closed")) {
            val rig = Rig()
            try {
                if (mode != "absent") { rig.driver.start(); rig.worker.runNext() }
                when (mode) {
                    "expired" -> rig.clock.nanos += 240 * SECOND
                    "clock" -> rig.clock.wall -= 1
                    "source-closed" -> rig.source.close()
                }
                rig.driver.onBackground()
                assertEquals(mode, V2ConfigLifecycleUpdateKind.CONFIGURATION, rig.updates.last().kind)
                assertTrue(rig.updates.last().consume { assertNull(it) })
            } finally { rig.driver.close() }
        }
    }

    @Test fun `close after live suspension replaces its kind and invalidates original token`() {
        val rig = Rig()
        rig.driver.start(); rig.worker.runNext(); rig.driver.onBackground()
        val suspension = rig.updates.last()
        assertEquals(V2ConfigLifecycleUpdateKind.APPLICATION_SUSPENSION, suspension.kind)
        rig.driver.close()
        assertEquals(V2ConfigLifecycleUpdateKind.CONFIGURATION, rig.updates.last().kind)
        assertFalse(suspension.consume { error("closed suspension survived") })
        assertTrue(rig.updates.last().consume { assertNull(it) })
    }

    private class Rig(consumeImmediately: Boolean = true) {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        val worker = ManualWorker()
        val updates = mutableListOf<V2ConfigLifecycleUpdate>()
        val deliveries = mutableListOf<String?>()
        var body = config().toString()
        var calls = 0
        var fetch: () -> V2ConfigHttpResponse = { V2ConfigHttpResponse(200, body) }
        val source = V2ConfigSource("https://elu.dev", "elu_pk_live_${"A".repeat(26)}", V2ConfigTransport { calls++; fetch() }, clock)
        val driver = V2ConfigLifecycleDriver(source, { update ->
            updates.add(update)
            if (consumeImmediately) update.consume { deliveries.add(it) }
        }, clock, scheduler, worker)
        fun advance(nanos: Long) { clock.nanos += nanos; clock.wall += nanos / 1_000_000 }
        fun config(): JSONObject = JSONObject(checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/v2/fixtures/config-enabled.json")).bufferedReader().use { it.readText() })
    }

    private class FakeClock : V2ConfigClock {
        @Volatile var wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
        @Volatile var nanos = 100L
        override fun wallNowEpochMillis(): Long = wall
        override fun monotonicNowNanos(): Long = nanos
    }

    private class ManualWorker : V2ConfigLifecycleWorker {
        val queued = ArrayDeque<() -> Unit>()
        var rejected = false
        override fun execute(task: () -> Unit) { check(!rejected); queued.add(task) }
        override fun interruptCurrent() = Unit // models a transport ignoring cancellation
        override fun close() { queued.clear() }
        fun runNext() { queued.removeFirst().invoke() }
    }

    private class ManualScheduler(private val clock: FakeClock) : V2ConfigLifecycleScheduler {
        data class Entry(val deadline: Long, val task: () -> Unit, var canceled: Boolean = false)
        val tasks = mutableListOf<Entry>()
        var rejected = false
        override fun schedule(delayNanos: Long, task: () -> Unit): V2ConfigLifecycleTask {
            check(!rejected)
            check(delayNanos > 0)
            val entry = Entry(clock.nanos + delayNanos, task)
            tasks.add(entry)
            return V2ConfigLifecycleTask { entry.canceled = true }
        }
        override fun close() { tasks.forEach { it.canceled = true } }
        fun activeDelays(): List<Long> = tasks.filterNot { it.canceled }.map { it.deadline - clock.nanos }.sorted()
        fun runDue() {
            while (true) {
                val next = tasks.firstOrNull { !it.canceled && it.deadline <= clock.nanos } ?: return
                next.canceled = true
                next.task()
            }
        }
    }

    private companion object { const val SECOND = 1_000_000_000L }
}
