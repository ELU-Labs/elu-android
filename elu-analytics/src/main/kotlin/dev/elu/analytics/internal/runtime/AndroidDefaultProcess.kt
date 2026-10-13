package dev.elu.analytics.internal.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StrictMode
import java.io.FileInputStream

/**
 * Android runs `Application.onCreate` in every process of an app. Only the default process may
 * open the runtime: its installation namespace is held under an exclusive file lock, so a
 * secondary process (a `:remote` or `:sync` service) that opened it first would leave the UI
 * process without analytics for its whole lifetime.
 */
internal object AndroidDefaultProcess {
    private const val COMMAND_LINE = "/proc/self/cmdline"
    private const val COMMAND_LINE_LIMIT = 512

    // A process keeps its name for its whole lifetime, so the first answer stays the answer.
    @Volatile private var resolved: ProcessRole? = null

    /** True only when this process is proven not to be the app's default process. */
    fun isSecondary(context: Context): Boolean = role(context) == ProcessRole.SECONDARY

    fun role(context: Context): ProcessRole {
        resolved?.let { return it }
        val role = try {
            val application = context.applicationContext ?: context
            ProcessRoleResolver.resolve(defaultProcessName(application), sources(application))
        } catch (_: Throwable) {
            ProcessRole.UNKNOWN
        }
        resolved = role
        return role
    }

    /** `android:process` on `<application>` renames the default process; the package name is its fallback. */
    private fun defaultProcessName(context: Context): String? =
        context.applicationInfo?.processName?.takeIf { it.isNotBlank() } ?: context.packageName

    private fun sources(context: Context) = ProcessNameSources(
        sdkInt = Build.VERSION.SDK_INT,
        applicationProcessName = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else null
        },
        runningProcessName = { runningProcessName(context) },
        commandLine = ::readCommandLine,
    )

    private fun runningProcessName(context: Context): String? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val pid = Process.myPid()
        return manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }

    private fun readCommandLine(): String? {
        // Setup normally runs on the main thread; this small procfs read must not trip a disk-read policy.
        val policy = StrictMode.allowThreadDiskReads()
        try {
            FileInputStream(COMMAND_LINE).use { input ->
                val buffer = ByteArray(COMMAND_LINE_LIMIT)
                var length = 0
                while (length < buffer.size) {
                    val read = input.read(buffer, length, buffer.size - length)
                    if (read < 0) break
                    length += read
                }
                return String(buffer, 0, length, Charsets.UTF_8)
            }
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }
}
