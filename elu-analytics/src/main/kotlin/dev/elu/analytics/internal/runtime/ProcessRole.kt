package dev.elu.analytics.internal.runtime

/** Where the current process stands relative to the app's default process. */
internal enum class ProcessRole {
    /** The app's default process: the only one that may own the runtime. */
    DEFAULT,

    /** A process started for a component with its own `android:process`. */
    SECONDARY,

    /** No usable process name. The SDK proceeds, so a single-process app is never left idle. */
    UNKNOWN,
}

/** Process-name sources in the order they are trusted. Each may return null or throw. */
internal class ProcessNameSources(
    val sdkInt: Int,
    /** `Application.getProcessName()`; consulted on API 28 and later only. */
    val applicationProcessName: () -> String?,
    /** The running-process entry for this pid, as reported by the system. */
    val runningProcessName: () -> String?,
    /** Raw `/proc/self/cmdline` contents. */
    val commandLine: () -> String?,
)

/** Pure classification, kept separate from the Android lookups so it runs on the JVM. */
internal object ProcessRoleResolver {
    const val APPLICATION_PROCESS_NAME_API = 28

    fun resolve(defaultProcessName: String?, sources: ProcessNameSources): ProcessRole {
        val expected = defaultProcessName?.trim()?.takeIf { it.isNotEmpty() } ?: return ProcessRole.UNKNOWN
        val exact = (if (sources.sdkInt >= APPLICATION_PROCESS_NAME_API) read(sources.applicationProcessName) else null)
            ?: read(sources.runningProcessName)
        if (exact != null) return if (exact == expected) ProcessRole.DEFAULT else ProcessRole.SECONDARY
        val commandLine = commandLineName(read(sources.commandLine)) ?: return ProcessRole.UNKNOWN
        return when {
            commandLine == expected -> ProcessRole.DEFAULT
            // The kernel argument area can cut a long name short; a cut name proves nothing.
            expected.startsWith(commandLine) -> ProcessRole.UNKNOWN
            else -> ProcessRole.SECONDARY
        }
    }

    /** The process name is the first NUL-terminated argument. */
    internal fun commandLineName(raw: String?): String? =
        raw?.substringBefore('\u0000')?.trim()?.takeIf { it.isNotEmpty() }

    private fun read(source: () -> String?): String? =
        try {
            source()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
}
