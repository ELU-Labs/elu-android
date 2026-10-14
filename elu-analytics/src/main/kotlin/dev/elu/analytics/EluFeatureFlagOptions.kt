package dev.elu.analytics

/** Per-read flag behavior. Fresh reads never request a reload or extend a cached value's expiry. */
public data class EluFeatureFlagOptions @JvmOverloads constructor(
    /** Whether a lawful read may report `$feature_flag_called`. */
    public val sendEvent: Boolean = true,
    /** Require a value received from the endpoint by this SDK instance, for the current projection. */
    public val fresh: Boolean = false,
)
