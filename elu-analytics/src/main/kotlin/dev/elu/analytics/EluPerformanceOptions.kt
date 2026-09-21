package dev.elu.analytics

/** Optional native sampling; remote configuration and collection consent must also allow it. */
public class EluPerformanceOptions @JvmOverloads constructor(
    public val enabled: Boolean = false,
    public val memory: Boolean = true,
    public val mainThreadStalls: Boolean = true,
    public val sampleIntervalMillis: Long = 30_000,
    public val mainThreadStallThresholdMillis: Long = 250,
) {
    init {
        require(sampleIntervalMillis in 5_000..Int.MAX_VALUE.toLong())
        require(mainThreadStallThresholdMillis in 100..60_000)
    }
}
