package dev.elu.analytics

/** One identity-bound flag value and its payload; null from the getter means unavailable. */
public data class EluFeatureFlagResult(
    public val key: String,
    public val enabled: Boolean,
    public val variant: String?,
    public val payload: Any?,
)
