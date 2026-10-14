package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.runtime.RuntimeReplayStoredRow

internal enum class ReplayDeliveryState { CLAIMED, ENROLLED, RETRY, BLOCKED }

/** Bounded metadata in the existing generic replay table; never contains request/body credentials. */
internal data class ReplayDeliveryMetadata(
    val ordinal: Long,
    val digest: String,
    val state: ReplayDeliveryState,
    val owner: String,
    val nonce: String,
    val attempts: Int,
    val delayMillis: Long = 0,
    val notBeforeMillis: Long = 0,
    val endpointCooldown: Boolean = false,
    val blockKind: String = "",
    val credentialWitness: String = "",
    val scopeWitness: String = "",
    val protocolGeneration: String = "",
    val raster: Boolean = false,
    val replayId: String = "",
    val sourceIssuedAt: String = "",
) {
    val key get() = key(ordinal)
    fun row(): RuntimeReplayStoredRow {
        val legacy = ReplayJson.parse(ReplayJson.encode(
        "ordinal" to ReplayJson.number(ordinal), "digest" to ReplayJson.text(digest),
        "state" to ReplayJson.text(state.name), "owner" to ReplayJson.text(owner), "nonce" to ReplayJson.text(nonce),
        "attempts" to ReplayJson.number(attempts.toLong()), "delayMillis" to ReplayJson.number(delayMillis),
        "notBeforeMillis" to ReplayJson.number(notBeforeMillis),
        "endpointCooldown" to dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value.BooleanValue(endpointCooldown), "blockKind" to ReplayJson.text(blockKind),
        "credentialWitness" to ReplayJson.text(credentialWitness), "scopeWitness" to ReplayJson.text(scopeWitness),
        "protocolGeneration" to ReplayJson.text(protocolGeneration))) as dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value.ObjectValue
        val value = if (!raster) legacy else dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value.ObjectValue(
            legacy.members + listOf("replayId" to ReplayJson.text(replayId), "sourceIssuedAt" to ReplayJson.text(sourceIssuedAt)))
        return RuntimeReplayStoredRow(key, if (raster) 2 else 1,
            dev.elu.analytics.internal.config.V1StrictCanonicalJson.canonicalBytes(value))
    }
    companion object {
        fun key(ordinal: Long) = "delivery/" + ordinal.toString().padStart(16, '0')
        fun decode(row: RuntimeReplayStoredRow): ReplayDeliveryMetadata {
            require(row.storageSchemaVersion in setOf(1L, 2L) && row.payload.size in 1..4096)
            val raster = row.storageSchemaVersion == 2L
            val v = ReplayJson.parse(row.payload).obj(setOf("ordinal", "digest", "state", "owner", "nonce", "attempts",
                "delayMillis", "notBeforeMillis", "endpointCooldown", "blockKind", "credentialWitness", "scopeWitness", "protocolGeneration") +
                if (raster) setOf("replayId", "sourceIssuedAt") else emptySet())
            val retirement = raster && v.string("state", 1, 16) == ReplayDeliveryState.BLOCKED.name &&
                v.string("blockKind", 0, 16) == ReplayBlockKind.RASTER_RETIRE.name
            val attempts = v.number("attempts").also { require(if (retirement) it == 0L else it in 1..31) }.toInt()
            val value = ReplayDeliveryMetadata(v.number("ordinal"), v.hash("digest"), ReplayDeliveryState.valueOf(v.string("state", 1, 16)),
                v.string("owner", if (retirement) 0 else 36, if (retirement) 0 else 36),
                v.string("nonce", if (retirement) 0 else 36, if (retirement) 0 else 36), attempts,
                v.number("delayMillis").also { require(it <= REPLAY_MAX_RETRY_MILLIS) }, v.number("notBeforeMillis"),
                v.boolean("endpointCooldown"), v.string("blockKind", 0, 16), v.string("credentialWitness", 0, 71), v.string("scopeWitness", 0, 71), v.string("protocolGeneration", 0, 128), raster,
                if (raster) v.string("replayId", 1, 256) else "",
                if (raster) v.string("sourceIssuedAt", 1, 128) else "")
            if (retirement) require(value.credentialWitness.isEmpty() && value.scopeWitness.isEmpty())
            else {
                java.util.UUID.fromString(value.owner); java.util.UUID.fromString(value.nonce)
                require(value.credentialWitness.matches(Regex("sha256:[a-f0-9]{64}")) && value.scopeWitness.matches(Regex("sha256:[a-f0-9]{64}")))
            }
            require(value.protocolGeneration.isNotEmpty())
            if (raster) {
                require(value.protocolGeneration == NativeRasterSealer.GENERATION)
                dev.elu.analytics.internal.config.V1ConfigJson.parseExactTimestamp(value.sourceIssuedAt)
            }
            if (value.state == ReplayDeliveryState.BLOCKED) {
                val block = ReplayBlockKind.valueOf(value.blockKind)
                require(block != ReplayBlockKind.RASTER_RETIRE || retirement)
                require(raster || block in setOf(ReplayBlockKind.UNAUTHORIZED, ReplayBlockKind.FORBIDDEN, ReplayBlockKind.PROTOCOL, ReplayBlockKind.CONFLICT))
            }
            else require(value.blockKind.isEmpty())
            if (value.state != ReplayDeliveryState.RETRY) require(value.delayMillis == 0L && value.notBeforeMillis == 0L && !value.endpointCooldown)
            require(value.key == row.key && value.row().payload.contentEquals(row.payload))
            return value
        }
    }
}
