package dev.elu.analytics.internal.performance

import dev.elu.analytics.internal.config.V1CapturePerformance
import org.junit.Assert.*
import org.junit.Test

class NativeFrameMetricsTest {
    private val context = NativePerformanceContext(Any(), 1, 2, "session", 3, Any(), V1CapturePerformance(false, true, 5_000))

    private fun add(frames: NativeFrameMetrics, c: NativePerformanceContext = context,
        start: Long = 1_000_000_000, registered: Long = 900_000_000, duration: Long = 20_000_000,
        observed: Long = 2_000_000_000, first: Boolean = false, deadline: Long? = null,
        dropped: Int = 0, age: Long? = 1_000) =
        frames.record(c, registered, start, observed, duration, first, deadline, dropped, age)

    @Test fun `only numeric aggregates survive and first draws are not slow-frame or deadline misses`() {
        val frames = NativeFrameMetrics(true)
        add(frames, first = true, duration = 100_000_000, deadline = 16_000_000, dropped = 2)
        add(frames, duration = 10_000_000, deadline = 16_000_000)
        add(frames, duration = 20_000_000, deadline = 20_000_000)
        val values = frames.drain(context)
        assertEquals(3L, values["\$frame_count"])
        assertEquals(130.0, values["\$frame_duration_total_ms"])
        assertEquals(100.0, values["\$frame_duration_max_ms"])
        assertEquals(1L, values["\$frame_slow_count"])
        assertEquals(1L, values["\$frame_first_draw_count"])
        assertEquals(2L, values["\$frame_deadline_observed_count"])
        assertEquals(1L, values["\$frame_deadline_miss_count"])
        assertEquals(2L, values["\$frame_metrics_dropped_count"])
        assertEquals(1_000L, values["\$process_age_at_first_observed_frame_ms"])
        assertTrue(values.values.all { it is Number })
        assertTrue(frames.drain(context).isEmpty())
    }

    @Test fun `pre-registration queued and impossible reports cannot enter a new interval`() {
        val frames = NativeFrameMetrics()
        add(frames, registered = 1_000_000_001)
        add(frames, observed = 999_999_999)
        add(frames, duration = -1)
        add(frames, duration = 1_000_000_001)
        add(frames, dropped = -1)
        assertTrue(frames.drain(context).isEmpty())
        add(frames, registered = 1_000_000_000)
        assertEquals(1L, frames.drain(context)["\$frame_count"])
    }

    @Test fun `every authority transition discards old aggregates and denied policy emits nothing`() {
        val changes = listOf(context.copy(source = Any()), context.copy(identityRevision = 9),
            context.copy(contextRevision = 9), context.copy(sessionId = "other"),
            context.copy(intentRevision = 9), context.copy(lifecycleEpoch = Any()))
        for (next in changes) {
            val frames = NativeFrameMetrics(); add(frames)
            assertTrue(frames.drain(next).isEmpty())
            add(frames, c = next)
            assertEquals(1L, frames.drain(next)["\$frame_count"])
        }
        val frames = NativeFrameMetrics(); add(frames)
        val denied = context.copy(policy = V1CapturePerformance(true, false, 5_000))
        add(frames, c = denied)
        assertTrue(frames.drain(denied).isEmpty()); assertTrue(frames.drain(context).isEmpty())
    }

    @Test fun `missing deadline and process age remain absent and one age attempt does not renew`() {
        val frames = NativeFrameMetrics(true)
        add(frames, age = null, deadline = -1)
        val first = frames.drain(context)
        assertFalse(first.containsKey("\$frame_deadline_observed_count"))
        assertFalse(first.containsKey("\$process_age_at_first_observed_frame_ms"))
        frames.clear(); add(frames, age = 3_000)
        assertFalse(frames.drain(context).containsKey("\$process_age_at_first_observed_frame_ms"))
        val disabled = NativeFrameMetrics(); add(disabled)
        assertFalse(disabled.drain(context).containsKey("\$process_age_at_first_observed_frame_ms"))
    }

    @Test fun `extreme inputs saturate numeric resource accounting`() {
        val frames = NativeFrameMetrics()
        add(frames, duration = Long.MAX_VALUE, start = 0, registered = 0, observed = Long.MAX_VALUE, dropped = Int.MAX_VALUE)
        val values = frames.drain(context)
        assertEquals(60_000.0, values["\$frame_duration_max_ms"])
        assertEquals(NativeFrameMetrics.MAXIMUM_FRAMES, values["\$frame_metrics_dropped_count"])
    }
}
