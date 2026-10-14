package dev.elu.analytics

/** Optional diagnostics. Collection is disabled unless explicitly enabled and authorized. */
public class EluDiagnosticsOptions @JvmOverloads constructor(
    public val enabled: Boolean = false,
    /**
     * Observe an in-progress Activity launch reaching its first frame on Android 15 (API 35)+.
     * Requires existing consent/identity continuity and current server performance permission.
     * Already completed launches, unknown history and unavailable OS measurements are omitted.
     */
    public val launchTimings: Boolean = false,
) {
    /**
     * Best-effort, type-only uncaught JVM reports on API 23+. Requires enabled, persistent
     * storage, consent and current server permission with no suppression rules. The original
     * handler is always called; no native-crash, ANR, message or stack capture is implied.
     */
    public val crashReports: Boolean get() = reportUncaught
    private var reportUncaught = false

    public constructor(enabled: Boolean, launchTimings: Boolean = false, crashReports: Boolean) : this(enabled, launchTimings) {
        reportUncaught = crashReports
    }
}
