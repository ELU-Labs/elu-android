package dev.elu.analytics.internal.performance

import dev.elu.analytics.EluPerformanceOptions
import dev.elu.analytics.internal.config.V1CapturePerformance

/** Original foreground/identity/configuration context, never permission by itself. */
internal data class NativePerformanceContext(
    val source: Any,
    val identityRevision: Long,
    val contextRevision: Long,
    val sessionId: String,
    val intentRevision: Long,
    val lifecycleEpoch: Any,
    val policy: V1CapturePerformance,
)

/** One outstanding main-thread probe and a bounded aggregate. No event payloads or stack traces. */
internal class NativePerformanceSampler(
    private val options: EluPerformanceOptions,
    private val context: () -> NativePerformanceContext?,
    private val monotonicNanos: () -> Long,
    private val postMain: (Runnable) -> Boolean,
    private val cancelMain: (Runnable) -> Unit,
    private val processPssBytes: () -> Long?,
    private val emit: (NativePerformanceContext, Map<String, Any>) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var current: NativePerformanceContext? = null
    private var windowStart = 0L
    private var lastTime = 0L
    private var pending: Runnable? = null
    private var count = 0L
    private var totalMillis = 0L
    private var maximumMillis = 0L
    private var sampleIndex = 0L

    /** Production calls on one bounded worker every 100ms, never from the main thread. */
    fun tick() {
        val observed = if (options.enabled) context() else null
        val now = monotonicNanos()
        if (now < 0) { suspend(); return }
        var sample: Pair<NativePerformanceContext, MutableMap<String, Any>>? = null
        synchronized(lock) {
            if (closed) return
            if (observed == null || observed != current || now < lastTime) {
                clearLocked()
                current = observed
                windowStart = now
            }
            lastTime = now
            val active = current ?: return
            val memory = options.memory && active.policy.memory
            val stalls = options.mainThreadStalls && active.policy.longTasks
            if (!memory && !stalls) return
            val interval = maxOf(options.sampleIntervalMillis, active.policy.sampleIntervalMillis.toLong())
            if (now - windowStart >= interval * NANOS_PER_MILLI) {
                val values = linkedMapOf<String, Any>(
                    "\$performance_platform" to "android",
                    "\$app_foreground" to true,
                    "\$performance_sample_index" to sampleIndex++,
                    "\$performance_sample_interval_ms" to interval,
                )
                if (stalls) {
                    values["\$main_thread_stall_count"] = count
                    values["\$main_thread_stall_total_ms"] = totalMillis
                    values["\$main_thread_stall_max_ms"] = maximumMillis
                    values["\$main_thread_stall_threshold_ms"] = options.mainThreadStallThresholdMillis
                }
                count = 0; totalMillis = 0; maximumMillis = 0; windowStart = now
                sample = active to values
            }
            if (stalls && pending == null) {
                val started = now
                lateinit var probe: Runnable
                probe = Runnable {
                    val finished = monotonicNanos()
                    val stillCurrent = context()
                    synchronized(lock) {
                        if (pending !== probe) return@synchronized
                        pending = null
                        if (closed || active != current || active != stillCurrent || finished < started) return@synchronized
                        if (finished < lastTime) { clearLocked(); lastTime = finished; return@synchronized }
                        lastTime = finished
                        val delayMillis = (finished - started) / NANOS_PER_MILLI
                        if (delayMillis >= options.mainThreadStallThresholdMillis) {
                            count = (count + 1).coerceAtMost(MAXIMUM_COUNT)
                            totalMillis = (totalMillis + delayMillis.coerceAtMost(MAXIMUM_DELAY_MILLIS)).coerceAtMost(MAXIMUM_TOTAL_MILLIS)
                            maximumMillis = maxOf(maximumMillis, delayMillis.coerceAtMost(MAXIMUM_DELAY_MILLIS))
                        }
                    }
                }
                pending = probe
                if (!postMain(probe)) pending = null
            }
        }
        sample?.let { (original, values) ->
            if (options.memory && original.policy.memory) {
                runCatching { processPssBytes() }.getOrNull()?.takeIf { it > 0 }?.let { values["\$memory_process_pss_bytes"] = it }
            }
            val hasMetric = values.containsKey("\$main_thread_stall_count") || values.containsKey("\$memory_process_pss_bytes")
            if (hasMetric && context() == original) emit(original, values.toMap())
        }
    }

    private fun clearLocked() {
        pending?.let(cancelMain)
        pending = null; current = null; count = 0; totalMillis = 0; maximumMillis = 0
        sampleIndex = 0
    }

    fun suspend() = synchronized(lock) { clearLocked() }

    override fun close() = synchronized(lock) { closed = true; clearLocked() }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAXIMUM_DELAY_MILLIS = 86_400_000L
        const val MAXIMUM_COUNT = 21_474_836L
        const val MAXIMUM_TOTAL_MILLIS = 1_855_425_830_400_000L
    }
}
