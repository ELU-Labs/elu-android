package dev.elu.analytics.internal.performance

/** Bounded scalar aggregates only. No Window, Activity, frame object or content is retained. */
internal class NativeFrameMetrics(private val includeProcessAge: Boolean = false) {
    private var current: NativePerformanceContext? = null
    private var count = 0L
    private var totalNanos = 0L
    private var maximumNanos = 0L
    private var slowCount = 0L
    private var firstDrawCount = 0L
    private var droppedCount = 0L
    private var deadlineCount = 0L
    private var deadlineMissCount = 0L
    private var firstProcessAge: Long? = null
    private var processAgeObserved = false

    /** The owner proves a single original registration and reads only public metric scalars. */
    @Synchronized fun record(
        original: NativePerformanceContext,
        registeredAtNanos: Long,
        frameStartedAtNanos: Long,
        observedAtNanos: Long,
        durationNanos: Long,
        firstDraw: Boolean,
        deadlineNanos: Long?,
        droppedReports: Int,
        processAgeMillis: Long?,
    ) {
        if (!original.policy.longTasks) { clear(); return }
        if (current != original) { clear(); current = original }
        // INTENDED_VSYNC_TIMESTAMP and these owner clocks share System.nanoTime's base.
        // A queued report for a pre-registration frame cannot enter a newly granted context.
        if (frameStartedAtNanos < registeredAtNanos || observedAtNanos < frameStartedAtNanos ||
            observedAtNanos - frameStartedAtNanos < 0 || durationNanos < 0 ||
            durationNanos > observedAtNanos - frameStartedAtNanos || droppedReports < 0) return
        if (count >= MAXIMUM_FRAMES) return
        val duration = durationNanos.coerceAtMost(MAXIMUM_DURATION_NANOS)
        count++
        totalNanos += duration
        maximumNanos = maxOf(maximumNanos, duration)
        droppedCount = (droppedCount + droppedReports.toLong()).coerceAtMost(MAXIMUM_FRAMES)
        if (firstDraw) firstDrawCount++
        else {
            if (durationNanos > SLOW_THRESHOLD_NANOS) slowCount++
            deadlineNanos?.takeIf { it > 0 }?.let { deadline ->
                deadlineCount++
                if (durationNanos >= deadline) deadlineMissCount++
            }
        }
        if (includeProcessAge && !processAgeObserved) {
            processAgeObserved = true
            firstProcessAge = processAgeMillis?.takeIf { it in 0..MAXIMUM_PROCESS_AGE_MILLIS }
        }
    }

    /** Joins the existing performance sample interval, without extending any session clock. */
    @Synchronized fun drain(original: NativePerformanceContext): Map<String, Any> {
        if (current != original || !original.policy.longTasks) { clear(); return emptyMap() }
        if (count == 0L) return emptyMap()
        val values = linkedMapOf<String, Any>(
            "\$frame_count" to count,
            "\$frame_duration_total_ms" to totalNanos.toDouble() / NANOS_PER_MILLI,
            "\$frame_duration_max_ms" to maximumNanos.toDouble() / NANOS_PER_MILLI,
            "\$frame_slow_count" to slowCount,
            "\$frame_slow_threshold_ms" to SLOW_THRESHOLD_NANOS.toDouble() / NANOS_PER_MILLI,
            "\$frame_first_draw_count" to firstDrawCount,
            "\$frame_metrics_dropped_count" to droppedCount,
        )
        if (deadlineCount > 0) {
            values["\$frame_deadline_observed_count"] = deadlineCount
            values["\$frame_deadline_miss_count"] = deadlineMissCount
        }
        firstProcessAge?.let { values["\$process_age_at_first_observed_frame_ms"] = it }
        clearCounts()
        return values
    }

    /** Withdrawal abandons unsent values; the one process-age attempt is never renewed. */
    @Synchronized fun clear() { current = null; clearCounts() }

    private fun clearCounts() {
        count = 0; totalNanos = 0; maximumNanos = 0; slowCount = 0; firstDrawCount = 0
        droppedCount = 0; deadlineCount = 0; deadlineMissCount = 0; firstProcessAge = null
    }

    internal companion object {
        const val SLOW_THRESHOLD_NANOS = 16_666_667L
        const val MAXIMUM_FRAMES = 1_000_000L
        private const val MAXIMUM_DURATION_NANOS = 60_000_000_000L
        private const val MAXIMUM_PROCESS_AGE_MILLIS = 86_400_000L
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
