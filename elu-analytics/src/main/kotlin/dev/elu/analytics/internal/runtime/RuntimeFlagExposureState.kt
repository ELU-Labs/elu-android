package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.PersistedCoreState
import java.security.MessageDigest
import java.util.Collections

internal const val RUNTIME_EXPOSURE_SCHEMA_OFFSET = 36
internal const val MAX_RUNTIME_FLAG_EXPOSURES = 4096
internal const val MAX_RUNTIME_EXPOSURE_STATE_BYTES = 288_768

/** Anonymous-visitor reports, committed with their accepted queue events; never customer input. */
internal class RuntimeFlagExposureState private constructor(
    val streamId: String,
    val anonymousId: String,
    digests: List<String>,
) {
    val digests: List<String> = Collections.unmodifiableList(digests.toList())

    init {
        RuntimePersonState(streamId, anonymousId) // The same closed owned-identifier validation.
        require(digests.size <= MAX_RUNTIME_FLAG_EXPOSURES)
        require(digests == digests.distinct().sorted())
        require(digests.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    fun contains(digest: String) = digests.binarySearch(digest) >= 0

    /** Saturation preserves previous reports; evicting them would permit duplicate exposure. */
    fun adding(digest: String): RuntimeFlagExposureState? {
        if (contains(digest)) return this
        if (digests.size == MAX_RUNTIME_FLAG_EXPOSURES) return null
        return RuntimeFlagExposureState(streamId, anonymousId, (digests + digest).sorted())
    }

    fun encode(): ByteArray = org.json.JSONObject().put("streamId", streamId).put("anonymousId", anonymousId)
        .put("digests", org.json.JSONArray(digests)).toString().toByteArray(Charsets.UTF_8)
        .also { require(it.size <= MAX_RUNTIME_EXPOSURE_STATE_BYTES) }

    override fun equals(other: Any?): Boolean = other is RuntimeFlagExposureState &&
        streamId == other.streamId && anonymousId == other.anonymousId && digests == other.digests
    override fun hashCode(): Int = 31 * (31 * streamId.hashCode() + anonymousId.hashCode()) + digests.hashCode()

    companion object {
        fun initial(state: PersistedCoreState) = RuntimeFlagExposureState(state.stream.streamId, state.identity.anonymousId, emptyList())

        fun decode(bytes: ByteArray): RuntimeFlagExposureState = try {
            require(bytes.size in 1..MAX_RUNTIME_EXPOSURE_STATE_BYTES)
            val value = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            val array = value.getJSONArray("digests")
            require(array.length() <= MAX_RUNTIME_FLAG_EXPOSURES)
            RuntimeFlagExposureState(value.getString("streamId"), value.getString("anonymousId"),
                List(array.length()) { array.getString(it) }).also {
                require(it.encode().contentEquals(bytes)) { "Noncanonical exposure state" }
            }
        } catch (error: Exception) { throw RuntimeQueueCorruptionException("Invalid exposure state", error) }

        fun digest(key: String, value: Any?): String {
            require(key.isNotEmpty() && key.codePointCount(0, key.length) <= 256)
            val type = when (value) { null -> "missing"; is Boolean -> "boolean"; is String -> "string"; else -> error("Unsupported flag value") }
            val encoded = org.json.JSONArray().put(key).put(type).put(value ?: org.json.JSONObject.NULL).toString()
            return MessageDigest.getInstance("SHA-256")
                .digest(("elu-native-flag-exposure-v1\u0000" + encoded).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
