package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.runtime.RuntimeReplayStoredRow

/** Opaque canonical metadata. Its hash is an integrity receipt, never proof of masking. */
internal class ReplayMaskingProfile private constructor(private val bytes: ByteArray) {
    val hash = ReplayJson.digest(bytes)
    fun copyBytes() = bytes.copyOf()
    companion object {
        fun parse(input: ByteArray): ReplayMaskingProfile {
            require(input.size in 1..65_536)
            val bytes = input.copyOf()
            require(ReplayJson.parse(bytes) is V1StrictCanonicalJson.Value.ObjectValue)
            require(V1StrictCanonicalJson.canonicalBytes(ReplayJson.parse(bytes)).contentEquals(bytes))
            return ReplayMaskingProfile(bytes)
        }
    }
}

internal data class ReplayStoredChunk(
    val ordinal: Long,
    val siteId: String,
    val captureProtocolGeneration: String,
    val prepared: PreparedReplayRequest,
    val maskingProfile: ReplayMaskingProfile,
)

internal data class ReplayStoredState(
    val namespaceHash: String,
    val siteId: String = "",
    val issuedAt: String = "",
    val semanticHash: String = "",
    val protocol: String = "",
    val nextOrdinal: Long = 0,
    val count: Long = 0,
    val bytes: Long = 0,
    val wallFloor: Long = 0,
    val poisoned: Boolean = false,
    val clockDenied: Boolean = false,
    // Absent only for legacy unattempted rows; preserve their exact canonical encoding.
    val deliveryMetadataCount: Long? = null,
    val rasterStorage: Boolean = false,
    val rasterSource: NativeRasterSourceLedger? = null,
) {
    init { require(rasterStorage || rasterSource == null) }
    fun row(): RuntimeReplayStoredRow = RuntimeReplayStoredRow("state", if (rasterStorage) 2 else 1, V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(listOf(
        "namespaceHash" to ReplayJson.text(namespaceHash), "siteId" to ReplayJson.text(siteId),
        "issuedAt" to ReplayJson.text(issuedAt), "semanticHash" to ReplayJson.text(semanticHash),
        "protocol" to ReplayJson.text(protocol), "nextOrdinal" to ReplayJson.number(nextOrdinal),
        "count" to ReplayJson.number(count), "bytes" to ReplayJson.number(bytes), "wallFloor" to ReplayJson.number(wallFloor), "poisoned" to V1StrictCanonicalJson.Value.BooleanValue(poisoned),
        "clockDenied" to V1StrictCanonicalJson.Value.BooleanValue(clockDenied)) +
        listOfNotNull(deliveryMetadataCount?.let { "deliveryMetadataCount" to ReplayJson.number(it) }) +
        if (rasterStorage) listOf("rasterSource" to (rasterSource?.value() ?: V1StrictCanonicalJson.Value.NullValue)) else emptyList())))
    companion object {
        fun decode(row: RuntimeReplayStoredRow): ReplayStoredState {
            require(row.key == "state" && row.storageSchemaVersion in setOf(1L, 2L) && row.payload.size in 1..4096)
            val value = ReplayJson.parse(row.payload).obj(setOf("namespaceHash", "siteId", "issuedAt", "semanticHash", "protocol",
                "nextOrdinal", "count", "bytes", "wallFloor", "poisoned", "clockDenied") +
                if (row.storageSchemaVersion == 2L) setOf("rasterSource") else emptySet(), setOf("deliveryMetadataCount"))
            require(V1StrictCanonicalJson.canonicalBytes(value).contentEquals(row.payload))
            return ReplayStoredState(value.string("namespaceHash", 64, 64).also { require(it.matches(Regex("[a-f0-9]{64}"))) }, value.string("siteId", 0, 256),
                value.string("issuedAt", 0, 128), value.string("semanticHash", 0, 71), value.string("protocol", 0, 128),
                value.number("nextOrdinal"), value.number("count"), value.number("bytes"), value.number("wallFloor"), value.boolean("poisoned"), value.boolean("clockDenied"),
                if (value.member("deliveryMetadataCount") == null) null else value.number("deliveryMetadataCount"),
                row.storageSchemaVersion == 2L, value.member("rasterSource")?.takeUnless { it === V1StrictCanonicalJson.Value.NullValue }
                    ?.let(NativeRasterSourceLedger::decode))
        }
    }
}

internal data class ReplayStoredHeader(
    val ordinal: Long, val siteId: String, val generation: String,
    val requestId: String, val replayId: String, val chunkId: String, val sequence: Long,
    val length: Int, val digest: String, val profile: ReplayMaskingProfile?, val raster: Boolean = false,
) {
    init { require((profile == null) == raster); if (raster) require(generation == NativeRasterSealer.GENERATION) }
    val key: String get() = "chunk/" + ordinal.toString().padStart(16, '0')
    val segmentCount: Int get() = (length + REPLAY_SEGMENT_BYTES - 1) / REPLAY_SEGMENT_BYTES
    fun segmentKey(index: Int) = "body/" + ordinal.toString().padStart(16, '0') + "/" + index.toString().padStart(3, '0')
    fun row() = RuntimeReplayStoredRow(key, if (raster) 2 else 1, V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(listOf(
        "ordinal" to ReplayJson.number(ordinal), "siteId" to ReplayJson.text(siteId), "generation" to ReplayJson.text(generation),
        "requestId" to ReplayJson.text(requestId), "replayId" to ReplayJson.text(replayId), "chunkId" to ReplayJson.text(chunkId),
        "sequence" to ReplayJson.number(sequence), "length" to ReplayJson.number(length.toLong()), "digest" to ReplayJson.text(digest)) +
        listOfNotNull(profile?.let { "profile" to ReplayJson.text(ReplayBase64.encode(it.copyBytes())) }))))
    companion object {
        fun decode(row: RuntimeReplayStoredRow): ReplayStoredHeader {
            require(row.storageSchemaVersion in setOf(1L, 2L) && row.payload.size in 1..REPLAY_SEGMENT_BYTES)
            val raster = row.storageSchemaVersion == 2L
            val value = ReplayJson.parse(row.payload).obj(setOf("ordinal", "siteId", "generation", "requestId", "replayId", "chunkId",
                "sequence", "length", "digest") + if (raster) emptySet() else setOf("profile"))
            val profile = if (raster) null else {
                val encodedProfile = value.string("profile", 1, 87_384)
                ReplayMaskingProfile.parse(ReplayBase64.decode(encodedProfile)).also {
                    require(ReplayBase64.encode(it.copyBytes()) == encodedProfile)
                }
            }
            val length = value.number("length")
            require(length in 1..MAX_REPLAY_REQUEST_BYTES.toLong())
            val header = ReplayStoredHeader(value.number("ordinal"), value.string("siteId", 1, 256), value.string("generation", 1, 128),
                value.string("requestId", 72, 72), value.string("replayId", 1, 256), value.string("chunkId", 1, 256),
                value.number("sequence"), length.toInt(), value.hash("digest"), profile, raster)
            require(header.key == row.key && header.row().payload.contentEquals(row.payload))
            return header
        }
    }
}
