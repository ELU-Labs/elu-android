package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.PersistedCoreState
import java.security.MessageDigest
import org.json.JSONObject

internal const val RUNTIME_EXCEPTION_SCHEMA_OFFSET = 48
internal const val MAX_RUNTIME_EXCEPTION_BYTES = 4096

/** One original namespace reservation. A row is coverage, never authority to capture. */
internal data class RuntimeExceptionReservation(
    val id: String,
    val namespace: String,
    val streamId: String,
    val anonymousId: String,
    val identityRevision: Long,
    val contextRevision: Long,
    val policyHash: String,
    val issuedWall: Long,
    val expiresWall: Long,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        require(namespace.matches(Regex("[0-9a-f]{64}")) && policyHash.matches(Regex("[0-9a-f]{64}")))
        RuntimePersonState(streamId, anonymousId)
        require(identityRevision >= 0 && contextRevision >= 0 && issuedWall >= 0 && expiresWall > issuedWall)
    }
    fun matches(state: PersistedCoreState): Boolean = streamId == state.stream.streamId &&
        anonymousId == state.identity.anonymousId && identityRevision == state.identity.revision &&
        contextRevision == state.identity.contextRevision && !state.identity.optedOut
    fun json(): JSONObject = JSONObject().put("id", id).put("namespace", namespace).put("streamId", streamId)
        .put("anonymousId", anonymousId).put("identityRevision", identityRevision).put("contextRevision", contextRevision)
        .put("policyHash", policyHash).put("issuedWall", issuedWall).put("expiresWall", expiresWall)
    companion object {
        fun read(value: JSONObject) = RuntimeExceptionReservation(value.getString("id"), value.getString("namespace"),
            value.getString("streamId"), value.getString("anonymousId"), value.getLong("identityRevision"),
            value.getLong("contextRevision"), value.getString("policyHash"), value.getLong("issuedWall"), value.getLong("expiresWall"))
    }
}

internal data class RuntimeExceptionState(
    val streamId: String,
    val reservation: RuntimeExceptionReservation? = null,
    val consumedDigest: String? = null,
) {
    init {
        RuntimePersonState(streamId, streamId)
        require(reservation == null || reservation.streamId == streamId)
        require(consumedDigest == null || (reservation != null && consumedDigest.matches(Regex("[0-9a-f]{64}"))))
    }
    fun encode(): ByteArray = JSONObject().put("streamId", streamId)
        .put("reservation", reservation?.json() ?: JSONObject.NULL)
        .put("consumedDigest", consumedDigest ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
        .also { require(it.size <= MAX_RUNTIME_EXCEPTION_BYTES) }
    companion object {
        fun decode(bytes: ByteArray): RuntimeExceptionState = try {
            require(bytes.size in 1..MAX_RUNTIME_EXCEPTION_BYTES)
            val value = JSONObject(String(bytes, Charsets.UTF_8))
            RuntimeExceptionState(value.getString("streamId"),
                if (value.isNull("reservation")) null else RuntimeExceptionReservation.read(value.getJSONObject("reservation")),
                if (value.isNull("consumedDigest")) null else value.getString("consumedDigest")).also {
                require(it.encode().contentEquals(bytes)) { "Noncanonical exception state" }
            }
        } catch (error: Exception) { throw RuntimeQueueCorruptionException("Invalid exception state", error) }
    }
}

/** No message, stack, cause, thread, OS identifier or inherited customer context. */
internal data class RuntimeExceptionReport(
    val reservation: RuntimeExceptionReservation,
    val occurredWall: Long,
    val type: String,
    val typeTruncated: Boolean,
) {
    init {
        require(occurredWall >= reservation.issuedWall && occurredWall < reservation.expiresWall)
        require(type.isNotEmpty() && type.length <= 256 && type.none { Character.isISOControl(it) })
        require(!Character.isHighSurrogate(type.last()))
    }
    fun encode(): ByteArray = JSONObject().put("version", 1).put("reservation", reservation.json())
        .put("occurredWall", occurredWall).put("type", type).put("typeTruncated", typeTruncated)
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_RUNTIME_EXCEPTION_BYTES) }
    fun digest(): String = exceptionDigest(encode())
    fun properties(): Map<String, Any?> = mapOf(
        "\$exception_type" to type,
        "\$exception_list" to listOf(mapOf("type" to type,
            "mechanism" to mapOf("type" to "android_uncaught", "handled" to false))),
        "\$exception_type_truncated" to typeTruncated,
        "\$exception_observed_at" to RuntimeWallTimestamps.rfc3339(occurredWall),
    )
    companion object {
        fun decode(bytes: ByteArray): RuntimeExceptionReport = try {
            require(bytes.size in 1..MAX_RUNTIME_EXCEPTION_BYTES)
            val value = JSONObject(String(bytes, Charsets.UTF_8)); require(value.getInt("version") == 1)
            RuntimeExceptionReport(RuntimeExceptionReservation.read(value.getJSONObject("reservation")),
                value.getLong("occurredWall"), value.getString("type"), value.getBoolean("typeTruncated")).also {
                require(it.encode().contentEquals(bytes)) { "Noncanonical exception report" }
            }
        } catch (error: Exception) { throw RuntimeQueueCorruptionException("Invalid exception report", error) }
    }
}

internal fun exceptionDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest("elu-native-exception-v1\u0000".toByteArray(Charsets.UTF_8) + bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Used only by the original queue importer, never exposed on the facade. */
internal data class RuntimeExceptionImport(val report: RuntimeExceptionReport, val isCurrent: () -> Boolean)
