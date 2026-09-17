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
) {
    val key get() = key(ordinal)
    fun row() = RuntimeReplayStoredRow(key, 1, ReplayJson.encode(
        "ordinal" to ReplayJson.number(ordinal), "digest" to ReplayJson.text(digest),
        "state" to ReplayJson.text(state.name), "owner" to ReplayJson.text(owner), "nonce" to ReplayJson.text(nonce),
        "attempts" to ReplayJson.number(attempts.toLong()), "delayMillis" to ReplayJson.number(delayMillis),
        "notBeforeMillis" to ReplayJson.number(notBeforeMillis),
        "endpointCooldown" to dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value.BooleanValue(endpointCooldown), "blockKind" to ReplayJson.text(blockKind),
        "credentialWitness" to ReplayJson.text(credentialWitness), "scopeWitness" to ReplayJson.text(scopeWitness),
        "protocolGeneration" to ReplayJson.text(protocolGeneration)))
    companion object {
        fun key(ordinal: Long) = "delivery/" + ordinal.toString().padStart(16, '0')
        fun decode(row: RuntimeReplayStoredRow): ReplayDeliveryMetadata {
            require(row.storageSchemaVersion == 1L && row.payload.size in 1..4096)
            val v = ReplayJson.parse(row.payload).obj(setOf("ordinal", "digest", "state", "owner", "nonce", "attempts",
                "delayMillis", "notBeforeMillis", "endpointCooldown", "blockKind", "credentialWitness", "scopeWitness", "protocolGeneration"))
            val attempts = v.number("attempts").also { require(it in 1..31) }.toInt()
            val value = ReplayDeliveryMetadata(v.number("ordinal"), v.hash("digest"), ReplayDeliveryState.valueOf(v.string("state", 1, 16)),
                v.string("owner", 36, 36), v.string("nonce", 36, 36), attempts,
                v.number("delayMillis").also { require(it <= REPLAY_MAX_RETRY_MILLIS) }, v.number("notBeforeMillis"),
                v.boolean("endpointCooldown"), v.string("blockKind", 0, 16), v.string("credentialWitness", 0, 71), v.string("scopeWitness", 0, 71), v.string("protocolGeneration", 0, 128))
            java.util.UUID.fromString(value.owner); java.util.UUID.fromString(value.nonce)
            require(value.credentialWitness.matches(Regex("sha256:[a-f0-9]{64}")) && value.scopeWitness.matches(Regex("sha256:[a-f0-9]{64}")))
            require(value.protocolGeneration.isNotEmpty())
            if (value.state == ReplayDeliveryState.BLOCKED) ReplayBlockKind.valueOf(value.blockKind)
            else require(value.blockKind.isEmpty())
            if (value.state != ReplayDeliveryState.RETRY) require(value.delayMillis == 0L && value.notBeforeMillis == 0L && !value.endpointCooldown)
            require(value.key == row.key && value.row().payload.contentEquals(row.payload))
            return value
        }
    }
}
