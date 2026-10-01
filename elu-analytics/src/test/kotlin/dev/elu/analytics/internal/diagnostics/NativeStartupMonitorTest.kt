package dev.elu.analytics.internal.diagnostics

import dev.elu.analytics.internal.runtime.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class NativeStartupMonitorTest {
    private val epoch = RuntimeDiagnosticsEpoch("e", "s", 0, 1, 1000, 1_000_000_000, 1_000_000_000, true)
    private val reading = RuntimeDiagnosticsClockReading(1, 3000, 3_000_000_000, 3_000_000_000)
    private val context = NativeStartupContext(epoch, 0)
    private val process = NativeStartupProcess(1, 2, "private")
    private val record = NativeStartupRecord(1, 2, 2, "private", 6, 0, 0, 2_000_000_000, null)

    @Test fun `close joins the original in-flight query and its late result cannot emit`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val emitted = AtomicInteger()
        val monitor = NativeStartupMonitor(process, RuntimeDiagnosticsClock { reading }, {
            entered.countDown()
            var settled = false
            while (!settled) try { release.await(); settled = true } catch (_: InterruptedException) { }
            listOf(record)
        }, { context }, { _, _ -> emitted.incrementAndGet() })
        monitor.foreground(true)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val close = monitor.closeAndWait(); assertFalse(close.isDone)
        release.countDown(); close.get(2, TimeUnit.SECONDS)
        assertEquals(0, emitted.get())
    }

    @Test fun `initial OS clock query cannot hold withdrawal lock and close joins its physical return`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val queries = AtomicInteger()
        val monitor = NativeStartupMonitor(process, RuntimeDiagnosticsClock {
            entered.countDown()
            var settled = false
            while (!settled) try { release.await(); settled = true } catch (_: InterruptedException) { }
            reading
        }, { queries.incrementAndGet(); listOf(record) }, { context }, { _, _ -> fail("withdrawn clock emitted") })
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            monitor.foreground(true); assertTrue(entered.await(2, TimeUnit.SECONDS))
            // The caller must return its settlement handle while the original OS read is held.
            val closing = caller.submit<java.util.concurrent.Future<Unit>> { monitor.closeAndWait() }.get(1, TimeUnit.SECONDS)
            assertFalse(closing.isDone)
            release.countDown(); closing.get(2, TimeUnit.SECONDS)
            assertEquals(0, queries.get())
        } finally { release.countDown(); monitor.closeAndWait().get(2, TimeUnit.SECONDS); caller.shutdownNow() }
    }

    @Test fun `background during original binder query prevents later observation and never restarts`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val monitor = NativeStartupMonitor(process, RuntimeDiagnosticsClock { reading }, {
            calls.incrementAndGet(); entered.countDown()
            try { release.await() } catch (_: InterruptedException) { }
            listOf(record)
        }, { context }, { _, _ -> fail("background emitted") })
        monitor.foreground(true); assertTrue(entered.await(2, TimeUnit.SECONDS))
        monitor.foreground(false); release.countDown(); monitor.foreground(true)
        monitor.closeAndWait().get(2, TimeUnit.SECONDS)
        assertEquals(1, calls.get())
    }

    @Test fun `consent revision replacement during query invalidates the original result`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val current = AtomicReference(context)
        val monitor = NativeStartupMonitor(process, RuntimeDiagnosticsClock { reading }, {
            entered.countDown(); release.await(); listOf(record)
        }, current::get, { _, _ -> fail("new consent relabeled old observation") })
        monitor.foreground(true); assertTrue(entered.await(2, TimeUnit.SECONDS))
        current.set(context.copy(consentRevision = 1)); release.countDown()
        monitor.closeAndWait().get(2, TimeUnit.SECONDS)
    }
    @Test fun `observed first frame waits boundedly for current delivery authority without another OS query`() {
        val queries = AtomicInteger(); val now = AtomicReference(reading)
        val waiting = CountDownLatch(1); val emitted = CountDownLatch(1); val ready = AtomicBoolean(false)
        val emissions = AtomicInteger()
        val monitor = NativeStartupMonitor(process, RuntimeDiagnosticsClock { now.get() }, {
            if (queries.incrementAndGet() == 1) listOf(record) else {
                now.set(reading.copy(wallMillis = 5000, uptimeNanos = 5_000_000_000, elapsedNanos = 5_000_000_000))
                listOf(record.copy(state = 2, type = 1, firstFrameUptimeNanos = 4_000_000_000))
            }
        }, { context }, { original, measurement ->
            assertEquals(context, original); assertEquals(4_000_000_000, measurement.firstFrameUptimeNanos)
            emissions.incrementAndGet(); emitted.countDown()
        }, { waiting.countDown(); ready.get() })
        try {
            monitor.foreground(true); assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertEquals(0, emissions.get()); ready.set(true)
            assertTrue(emitted.await(2, TimeUnit.SECONDS)); assertEquals(1, emissions.get())
            assertEquals(2, queries.get())
        } finally { monitor.closeAndWait().get(2, TimeUnit.SECONDS) }
    }

    @Test fun `pending first frame is discarded when consent changes before authority arrives`() {
        val queries = AtomicInteger(); val now = AtomicReference(reading)
        val waiting = CountDownLatch(1); val releaseReadiness = CountDownLatch(1)
        val withdrawn = CountDownLatch(1); val ready = AtomicBoolean(false)
        val current = AtomicReference<NativeStartupContext?>(context); val emissions = AtomicInteger()
        val monitor = NativeStartupMonitor(process, RuntimeDiagnosticsClock { now.get() }, {
            if (queries.incrementAndGet() == 1) listOf(record) else {
                now.set(reading.copy(wallMillis = 5000, uptimeNanos = 5_000_000_000, elapsedNanos = 5_000_000_000))
                listOf(record.copy(state = 2, type = 1, firstFrameUptimeNanos = 4_000_000_000))
            }
        }, { current.get().also { if (it == null) withdrawn.countDown() } }, { _, _ -> emissions.incrementAndGet() },
            {
                // Establish the unavailable-authority observation before notifying the
                // caller. A grant racing the callback return must not rewrite that result.
                val observedReady = ready.get()
                waiting.countDown()
                check(releaseReadiness.await(2, TimeUnit.SECONDS))
                observedReady
            })
        try {
            monitor.foreground(true); assertTrue(waiting.await(2, TimeUnit.SECONDS))
            current.set(null); ready.set(true); releaseReadiness.countDown()
            assertTrue(withdrawn.await(2, TimeUnit.SECONDS))
            assertEquals(0, emissions.get()); assertEquals(2, queries.get())
        } finally {
            releaseReadiness.countDown()
            monitor.closeAndWait().get(2, TimeUnit.SECONDS)
        }
    }

}
