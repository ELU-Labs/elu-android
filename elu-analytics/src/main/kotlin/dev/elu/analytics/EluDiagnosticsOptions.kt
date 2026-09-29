package dev.elu.analytics

/** Optional numeric OS diagnostics. Collection is disabled unless explicitly enabled. */
public class EluDiagnosticsOptions @JvmOverloads constructor(
    public val enabled: Boolean = false,
    /**
     * Observe an in-progress Activity launch reaching its first frame on Android 15 (API 35)+.
     * Requires existing consent/identity continuity and current server performance permission.
     * Already completed launches, unknown history and unavailable OS measurements are omitted.
     */
    public val launchTimings: Boolean = false,
)
