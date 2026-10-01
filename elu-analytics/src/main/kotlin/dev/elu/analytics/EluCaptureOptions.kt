package dev.elu.analytics

import java.util.Date

/** Optional capture inputs. The SDK detaches the timestamp and JSON maps when capture is called. */
public data class EluCaptureOptions @JvmOverloads constructor(
    /** Event time; null uses the time of the capture call. Existing timestamp validation applies. */
    public val timestamp: Date? = null,
    /** Person properties applied after the event is accepted, using the same ordered operation. */
    public val set: Map<String, Any>? = null,
    /** Person properties applied only when absent, after event acceptance. */
    public val setOnce: Map<String, Any>? = null,
)
