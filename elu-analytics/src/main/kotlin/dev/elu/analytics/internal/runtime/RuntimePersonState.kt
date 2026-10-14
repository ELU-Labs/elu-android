package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState

/** Installation metadata, never a customer super-property or independently writable identity. */
internal data class RuntimePersonState(val streamId: String, val deviceId: String, val processingEnabled: Boolean = false) {
    init {
        requireIdentifier(streamId)
        requireIdentifier(deviceId)
    }

    fun processes(identity: IdentityState, mode: EluPersonProfilesMode): Boolean = when (mode) {
        EluPersonProfilesMode.NEVER -> false
        EluPersonProfilesMode.ALWAYS -> true
        EluPersonProfilesMode.IDENTIFIED_ONLY -> processingEnabled || identity.userId != null || identity.groups.isNotEmpty()
    }

    fun stamps(identity: IdentityState, mode: EluPersonProfilesMode): Map<String, Any> = mapOf(
        "\$device_id" to deviceId,
        "\$is_identified" to (identity.userId != null),
        "\$process_person_profile" to processes(identity, mode),
    )

    fun encode(): ByteArray = org.json.JSONObject().put("streamId", streamId).put("deviceId", deviceId)
        .put("processingEnabled", processingEnabled).toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 4096) }

    companion object {
        private fun requireIdentifier(value: String) {
            require(value.codePointCount(0, value.length) in 1..256)
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (Character.isHighSurrogate(char)) {
                    require(index < value.length && Character.isLowSurrogate(value[index++]))
                } else require(!Character.isLowSurrogate(char))
            }
        }

        fun initial(state: PersistedCoreState) = RuntimePersonState(state.stream.streamId, state.identity.anonymousId)

        fun decode(bytes: ByteArray): RuntimePersonState = try {
            require(bytes.size in 1..4096)
            val value = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            RuntimePersonState(value.getString("streamId"), value.getString("deviceId"), value.getBoolean("processingEnabled")).also {
                require(it.encode().contentEquals(bytes)) { "Noncanonical person state" }
            }
        } catch (error: Exception) { throw RuntimeQueueCorruptionException("Invalid person state", error) }
    }
}

internal const val RUNTIME_PERSON_SCHEMA_OFFSET = 30
