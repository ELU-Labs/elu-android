package dev.elu.analytics.internal.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClock
import dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClockReading

/** Public OS queries only. No completion listener, Intent, trace, or description is accessed. */
@android.annotation.TargetApi(35)
internal class AndroidStartupAccess(context: Context) : RuntimeDiagnosticsClock {
    private val resolver = context.applicationContext.contentResolver
    private val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val process = NativeStartupProcess(Process.myPid(), Process.myUid(), Application.getProcessName())

    override fun read(): RuntimeDiagnosticsClockReading? = runCatching {
        RuntimeDiagnosticsClockReading(Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT).toLong(),
            System.currentTimeMillis(), SystemClock.uptimeNanos(), SystemClock.elapsedRealtimeNanos()).takeIf { it.valid() }
    }.getOrNull()

    fun records(): List<NativeStartupRecord>? = runCatching {
        val original = manager?.getHistoricalProcessStartReasons(NativeStartupObservation.MAXIMUM_RECORDS) ?: return null
        if (original.size > NativeStartupObservation.MAXIMUM_RECORDS) return null
        original.map { record ->
            val times = record.startupTimestamps
            NativeStartupRecord(record.pid, record.realUid, record.packageUid, record.processName ?: "",
                record.reason, record.startType, record.startupState,
                times[ApplicationStartInfo.START_TIMESTAMP_LAUNCH] ?: -1L,
                times[ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME])
        }
    }.getOrNull()
}
