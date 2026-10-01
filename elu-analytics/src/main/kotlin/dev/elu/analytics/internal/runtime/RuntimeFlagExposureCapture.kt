package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.flags.FlagJsonValue
import dev.elu.analytics.internal.flags.FlagReadResult

/** Original cache read plus its client/facade lease; the queue re-reads it in the event transaction. */
internal class RuntimeFlagExposureCapture private constructor(
    val key: String,
    val read: FlagReadResult,
    private val usedBootstrap: Boolean,
    val isCurrent: () -> Boolean,
) {
    private val metadata = checkNotNull(when (read) {
        is FlagReadResult.Found -> read.metadata
        is FlagReadResult.CacheMiss -> read.metadata
        else -> null
    })
    private val value: Any? = (read as? FlagReadResult.Found)?.let {
        when (val raw = it.value.platform()) {
            is String, is Boolean -> raw
            is Number -> raw.toDouble() != 0.0
            else -> false
        }
    }
    val digest = RuntimeFlagExposureState.digest(key, value)

    fun properties(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "\$feature_flag" to key,
        "\$feature_flag_payload" to (read as? FlagReadResult.Found)?.payload?.platform(),
        "\$feature_flag_request_id" to metadata.requestId,
        "\$feature_flag_evaluated_at" to metadata.evaluatedAt.toEpochMillisFloor(),
        "\$feature_flag_bootstrapped_response" to null,
        "\$feature_flag_bootstrapped_payload" to null,
        "\$used_bootstrap_value" to usedBootstrap,
    ).apply {
        if (read is FlagReadResult.Found) put("\$feature_flag_response", value)
        else put("\$feature_flag_error", "flag_missing")
    }

    companion object {
        fun from(key: String, read: FlagReadResult, usedBootstrap: Boolean, isCurrent: () -> Boolean): RuntimeFlagExposureCapture? {
            val metadata = when (read) {
                is FlagReadResult.Found -> read.metadata
                is FlagReadResult.CacheMiss -> read.metadata
                else -> null
            } ?: return null
            if (metadata.requestId.isEmpty()) return null
            return RuntimeFlagExposureCapture(key, read, usedBootstrap, isCurrent)
        }

        private fun FlagJsonValue.platform(): Any? = when (this) {
            is FlagJsonValue.BooleanValue -> value
            is FlagJsonValue.StringValue -> value
            is FlagJsonValue.NumberValue -> value
            is FlagJsonValue.ObjectValue -> members.associate { it.key to it.value.platform() }
            is FlagJsonValue.ArrayValue -> values.map { it.platform() }
            FlagJsonValue.NullValue -> null
        }
    }
}
