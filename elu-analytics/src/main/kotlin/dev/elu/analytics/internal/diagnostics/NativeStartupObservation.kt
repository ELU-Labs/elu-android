package dev.elu.analytics.internal.diagnostics

import dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClockReading
import dev.elu.analytics.internal.runtime.RuntimeDiagnosticsEpoch

/** Detached public ApplicationStartInfo scalars. Names stay local and are never serialized. */
internal data class NativeStartupRecord(
    val pid: Int,
    val realUid: Int,
    val packageUid: Int,
    val processName: String,
    val reason: Int,
    val type: Int,
    val state: Int,
    val launchUptimeNanos: Long,
    val firstFrameUptimeNanos: Long?,
)

internal data class NativeStartupProcess(val pid: Int, val uid: Int, val name: String)
internal data class NativeStartupContext(val epoch: RuntimeDiagnosticsEpoch, val consentRevision: Long)

internal data class NativeStartupMeasurement(
    val epoch: RuntimeDiagnosticsEpoch,
    val launchUptimeNanos: Long,
    val firstFrameUptimeNanos: Long,
    val reason: Int,
    val type: Int,
) {
    init {
        require(launchUptimeNanos >= epoch.startedUptimeNanos && firstFrameUptimeNanos >= launchUptimeNanos)
        require(firstFrameUptimeNanos - launchUptimeNanos <= NativeStartupObservation.MAXIMUM_LAUNCH_NANOS)
        require(reason in setOf(6, 7, 11) && type in 1..3)
    }
    fun properties(): Map<String, Any> = mapOf(
        "\$diagnostic_platform" to "android",
        "\$diagnostic_source" to "application_start_info",
        "\$launch_start_uptime_ns" to launchUptimeNanos,
        "\$launch_first_frame_uptime_ns" to firstFrameUptimeNanos,
        "\$launch_duration_ms" to (firstFrameUptimeNanos - launchUptimeNanos) / 1_000_000.0,
        "\$launch_reason" to reason,
        "\$launch_type" to type,
    )
}

/**
 * One bounded observation per process owner. An old completed history row cannot establish a
 * current-process launch: the exact unique record must first lack first-frame data, then gain it.
 * This does not replace the application's ApplicationStartInfo completion listener.
 */
internal class NativeStartupObservation(
    private val process: NativeStartupProcess,
    private val epoch: RuntimeDiagnosticsEpoch,
    private val began: RuntimeDiagnosticsClockReading,
) {
    init {
        require(process.pid > 0 && process.uid >= 0 && process.name.isNotBlank() && process.name.length <= 512)
        require(began.continues(epoch))
    }
    private var candidate: NativeStartupRecord? = null
    private var last = began
    private var polls = 0
    var finished: Boolean = false
        private set

    fun observe(records: List<NativeStartupRecord>, now: RuntimeDiagnosticsClockReading,
        currentEpoch: RuntimeDiagnosticsEpoch?, foreground: Boolean): NativeStartupMeasurement? {
        if (finished) return null
        polls++
        if (!foreground || currentEpoch != epoch || !epoch.launchTimings || !now.continues(epoch) ||
            now.bootCount != began.bootCount || now.uptimeNanos < last.uptimeNanos ||
            now.elapsedNanos < last.elapsedNanos || now.wallMillis < last.wallMillis ||
            now.elapsedNanos - began.elapsedNanos > MAXIMUM_OBSERVATION_NANOS || polls > MAXIMUM_POLLS ||
            records.size > MAXIMUM_RECORDS) return stop()
        last = now
        val matching = records.filter { it.pid == process.pid && it.realUid == process.uid &&
            it.packageUid == process.uid && it.processName == process.name }
        if (matching.size != 1) return stop()
        val record = matching.single()
        if (record.reason !in ACTIVITY_REASONS || record.launchUptimeNanos < epoch.startedUptimeNanos ||
            record.launchUptimeNanos > began.uptimeNanos || record.launchUptimeNanos <= 0 ||
            now.uptimeNanos - record.launchUptimeNanos > MAXIMUM_LAUNCH_NANOS) return stop()
        val first = candidate
        if (first == null) {
            if (record.state != STATE_STARTED || record.firstFrameUptimeNanos != null) return stop()
            candidate = record
            return null
        }
        if (record.copy(type = first.type, state = first.state, firstFrameUptimeNanos = first.firstFrameUptimeNanos) != first)
            return stop()
        if (record.state == STATE_STARTED && record.firstFrameUptimeNanos == null) return null
        val frame = record.firstFrameUptimeNanos
        if (record.state != STATE_FIRST_FRAME || frame == null || record.type !in 1..3 ||
            frame < began.uptimeNanos || frame < record.launchUptimeNanos || frame > now.uptimeNanos)
            return stop()
        finished = true
        return NativeStartupMeasurement(epoch, record.launchUptimeNanos, frame, record.reason, record.type)
    }

    fun cancel() { stop() }
    private fun stop(): Nothing? { finished = true; candidate = null; return null }

    internal companion object {
        const val MAXIMUM_RECORDS = 16
        const val MAXIMUM_POLLS = 101
        const val MAXIMUM_OBSERVATION_NANOS = 10_000_000_000L
        const val MAXIMUM_LAUNCH_NANOS = 30_000_000_000L
        private const val STATE_STARTED = 0
        private const val STATE_FIRST_FRAME = 2
        private val ACTIVITY_REASONS = setOf(6, 7, 11)
    }
}
