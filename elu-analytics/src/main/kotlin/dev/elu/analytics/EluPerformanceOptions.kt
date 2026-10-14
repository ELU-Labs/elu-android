package dev.elu.analytics

/** Optional native sampling; remote configuration and collection consent must also allow it. */
public class EluPerformanceOptions @JvmOverloads constructor(
    public val enabled: Boolean = false,
    public val memory: Boolean = true,
    public val mainThreadStalls: Boolean = true,
    public val sampleIntervalMillis: Long = 30_000,
    public val mainThreadStallThresholdMillis: Long = 250,
) {
    private var collectFrames = false
    private var collectProcessAge = false
    public val frameMetrics: Boolean get() = collectFrames
    public val processAgeAtFirstObservedFrame: Boolean get() = collectProcessAge

    /** Additive opt-in; original constructor signatures remain available. */
    public constructor(
        frameMetrics: EluFrameMetricsOptions,
        enabled: Boolean = false,
        memory: Boolean = true,
        mainThreadStalls: Boolean = true,
        sampleIntervalMillis: Long = 30_000,
        mainThreadStallThresholdMillis: Long = 250,
    ) : this(enabled, memory, mainThreadStalls, sampleIntervalMillis, mainThreadStallThresholdMillis) {
        collectFrames = frameMetrics.enabled
        collectProcessAge = frameMetrics.processAgeAtFirstObservedFrame
    }

    init {
        require(sampleIntervalMillis in 5_000..Int.MAX_VALUE.toLong())
        require(mainThreadStallThresholdMillis in 100..60_000)
    }
}
