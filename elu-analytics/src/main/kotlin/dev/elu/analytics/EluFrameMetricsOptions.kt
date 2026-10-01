package dev.elu.analytics

/** Optional public-API frame measurements on API26+. Remote long_tasks also must allow them. */
public class EluFrameMetricsOptions @JvmOverloads constructor(
    public val enabled: Boolean = false,
    /** Supplemental process age at an observed frame; not startup completion or TTID. */
    public val processAgeAtFirstObservedFrame: Boolean = false,
)
