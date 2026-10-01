package dev.elu.analytics.internal.diagnostics

import dev.elu.analytics.EluDiagnosticsOptions
import dev.elu.analytics.internal.runtime.*
import org.junit.Assert.*
import org.junit.Test

class NativeStartupObservationTest {
    private val epoch = RuntimeDiagnosticsEpoch("epoch-a", "stream-a", 2, 5, 1000, 1_000_000_000L, 1_000_000_000L, true)
    private val began = RuntimeDiagnosticsClockReading(5, 3000, 3_000_000_000L, 3_000_000_000L)
    private val end = RuntimeDiagnosticsClockReading(5, 3100, 3_100_000_000L, 3_100_000_000L)
    private val process = NativeStartupProcess(100, 200, "private.package")
    private val initial = NativeStartupRecord(100, 200, 200, process.name, 6, 0, 0, 2_900_000_000L, null)
    private val complete = initial.copy(type = 1, state = 2, firstFrameUptimeNanos = 3_050_000_000L)
    private fun observer() = NativeStartupObservation(process, epoch, began)

    @Test fun `options are disabled and observed transition contains only bounded numeric facts`() {
        assertFalse(EluDiagnosticsOptions().enabled); assertFalse(EluDiagnosticsOptions().launchTimings)
        val observer = observer()
        assertNull(observer.observe(listOf(initial), began, epoch, true))
        val measurement = observer.observe(listOf(complete), end, epoch, true)!!
        assertTrue(observer.finished)
        assertEquals(150.0, measurement.properties()["\$launch_duration_ms"])
        assertEquals(setOf("\$diagnostic_platform", "\$diagnostic_source", "\$launch_start_uptime_ns",
            "\$launch_first_frame_uptime_ns", "\$launch_duration_ms", "\$launch_reason", "\$launch_type"), measurement.properties().keys)
        assertFalse(measurement.properties().values.contains(process.name))
        assertNull(observer.observe(listOf(complete), end, epoch, true))
    }

    @Test fun `already complete history absent rows and duplicate current process are omitted`() {
        for (rows in listOf(emptyList(), listOf(complete), listOf(initial, initial),
            listOf(initial, complete))) {
            val observer = observer()
            assertNull(observer.observe(rows, began, epoch, true)); assertTrue(observer.finished)
        }
    }

    @Test fun `current process tuple reason and timestamp must match at both observations`() {
        val changes = listOf<(NativeStartupRecord) -> NativeStartupRecord>(
            { it.copy(pid = 101) }, { it.copy(realUid = 201) }, { it.copy(packageUid = 201) },
            { it.copy(processName = "other") }, { it.copy(reason = 10) }, { it.copy(reason = 11) },
            { it.copy(launchUptimeNanos = it.launchUptimeNanos + 1) }, { it.copy(type = 0) },
            { it.copy(state = 1) }, { it.copy(firstFrameUptimeNanos = began.uptimeNanos - 1) },
            { it.copy(firstFrameUptimeNanos = end.uptimeNanos + 1) })
        for (change in changes) {
            val observer = observer(); observer.observe(listOf(initial), began, epoch, true)
            assertNull(observer.observe(listOf(change(complete)), end, epoch, true)); assertTrue(observer.finished)
        }
    }

    @Test fun `missing consent epoch boot continuity or foreground permanently cancels observation`() {
        for ((clock, current, foreground) in listOf(
            Triple(end, null, true), Triple(end, epoch.copy(id = "new"), true), Triple(end, epoch, false),
            Triple(end.copy(bootCount = 6), epoch, true), Triple(end.copy(wallMillis = 2999), epoch, true),
            Triple(end.copy(uptimeNanos = began.uptimeNanos - 1), epoch, true),
            Triple(end.copy(elapsedNanos = began.elapsedNanos - 1), epoch, true))) {
            val observer = observer(); observer.observe(listOf(initial), began, epoch, true)
            assertNull(observer.observe(listOf(complete), clock, current, foreground)); assertTrue(observer.finished)
            assertNull(observer.observe(listOf(complete), end, epoch, true))
        }
    }

    @Test fun `unknown earlier interval future start service and excessive history are refused`() {
        for (rows in listOf(listOf(initial.copy(launchUptimeNanos = epoch.startedUptimeNanos - 1)),
            listOf(initial.copy(launchUptimeNanos = began.uptimeNanos + 1)), listOf(initial.copy(reason = 10)),
            List(NativeStartupObservation.MAXIMUM_RECORDS + 1) { initial })) {
            val observer = observer(); assertNull(observer.observe(rows, began, epoch, true)); assertTrue(observer.finished)
        }
    }

    @Test fun `retries have a monotonic deadline and a count ceiling even if clock stops`() {
        val timed = observer(); timed.observe(listOf(initial), began, epoch, true)
        val later = began.copy(uptimeNanos = began.uptimeNanos + 10_000_000_001L,
            elapsedNanos = began.elapsedNanos + 10_000_000_001L)
        assertNull(timed.observe(listOf(initial), later, epoch, true)); assertTrue(timed.finished)
        val bounded = observer()
        repeat(NativeStartupObservation.MAXIMUM_POLLS + 1) { bounded.observe(listOf(initial), began, epoch, true) }
        assertTrue(bounded.finished)
    }

    @Test fun `durable state requires canonical complete finite bounded fields`() {
        val state = RuntimeDiagnosticsState(epoch, 2_900_000_000L)
        assertEquals(state, RuntimeDiagnosticsState.decode(state.encode()))
        assertEquals(RuntimeDiagnosticsState(), RuntimeDiagnosticsState.decode(RuntimeDiagnosticsState().encode()))
        for (bad in listOf(String(state.encode()).replace("\"identityRevision\":2", "\"identityRevision\":2.0"),
            String(state.encode()).replace("\"bootCount\":5", "\"bootCount\":-1"),
            String(state.encode()).dropLast(1) + ",\"unknown\":true}", "{}", "{\"epoch\":null}")) {
            assertThrows(RuntimeQueueCorruptionException::class.java) { RuntimeDiagnosticsState.decode(bad.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { RuntimeDiagnosticsState(null, 1) }
    }
}
