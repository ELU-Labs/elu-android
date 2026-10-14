package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import java.nio.ByteBuffer

/** Durable facts only. Neither this parser nor a restored record creates a producer permission. */
internal class NativeRasterStoredRequest private constructor(
    private val bytes: ByteArray,
    val requestId: String, val replayId: String, val sessionId: String, val chunkId: String,
    val sequence: Long, val timestamp: Long, val anonymousId: String, val userId: String?,
    val identityRevision: Long, val contextRevision: Long, val policyRevision: String,
    val effectivePolicyHash: String,
) {
    val digest = ReplayJson.digest(bytes)
    val byteCount get() = bytes.size
    fun copyBytes() = bytes.copyOf()
    fun expiredAt(now: Long): Boolean = now >= timestamp && now - timestamp >= REPLAY_RETENTION_SECONDS * 1000

    companion object {
        fun parse(input: ByteArray): NativeRasterStoredRequest {
            require(input.size in 1..MAX_REPLAY_REQUEST_BYTES)
            val root = ReplayJson.parse(input).obj(setOf("schemaVersion", "requestId", "chunk"))
            require(root.number("schemaVersion") == 3L && V1StrictCanonicalJson.canonicalBytes(root).contentEquals(input))
            val chunk = root.get("chunk").obj(setOf("schemaVersion", "replayId", "sessionId", "chunkId", "sequence",
                "startedAt", "endedAt", "identity", "contextRevision", "codec", "compression", "contentEncoding",
                "payload", "privacy", "versions"))
            require(chunk.number("schemaVersion") == 3L && chunk.string("codec", 1, 68) == NativeRasterSealer.CODEC &&
                chunk.string("compression", 1, 4) == "gzip" && chunk.string("contentEncoding", 1, 6) == "base64")
            val requestId = root.string("requestId", 72, 72)
            val canonical = V1StrictCanonicalJson.canonicalBytes(chunk)
            val material = "elu-sdk-replay-request-v3".toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                ByteBuffer.allocate(4).putInt(canonical.size).array() + canonical
            try { require(requestId == "request_" + ReplayJson.digest(material).removePrefix("sha256:")) }
            finally { canonical.fill(0); material.fill(0) }
            val start = chunk.string("startedAt", 1, 128)
            require(chunk.string("endedAt", 1, 128) == start)
            val time = V1ConfigJson.parseExactTimestamp(start)
            require(!time.isLeapSecond && time.epochWholeSecond >= 0 && time.fractionalDigits.length <= 3)
            val timestamp = Math.addExact(Math.multiplyExact(time.epochWholeSecond, 1000), time.fractionalDigits.padEnd(3, '0').toLong())
            require(timestamp in 1..253_402_300_799_999L && NativeReplaySealer.timestamp(timestamp) == start)
            val identity = chunk.get("identity").obj(setOf("anonymousId", "userId", "revision"))
            val user = if (identity.get("userId") === V1StrictCanonicalJson.Value.NullValue) null else identity.string("userId", 1, 512)
            val privacy = chunk.get("privacy").obj(setOf("schemaVersion", "policyRevision", "effectivePolicyHash", "maskingProfileHash",
                "inputCoverage", "automaticInputDiscovery", "unknownContentClassification", "appliedBeforeSerialization",
                "requiredRegionsRedacted", "platformFallbackApplied"))
            require(privacy.number("schemaVersion") == 1L && privacy.hash("maskingProfileHash") == NativeRasterSealer.PROFILE_HASH &&
                privacy.string("inputCoverage", 1, 32) == "declared-regions" && !privacy.boolean("automaticInputDiscovery") &&
                !privacy.boolean("unknownContentClassification") && privacy.boolean("appliedBeforeSerialization") &&
                privacy.boolean("requiredRegionsRedacted") && !privacy.boolean("platformFallbackApplied"))
            val versions = chunk.get("versions").obj(setOf("schemaVersion", "contractVersion", "platform", "runtime", "facade"), setOf("build"))
            require(versions.number("schemaVersion") == 2L && versions.string("contractVersion", 5, 5) == "2.0.0" &&
                versions.string("platform", 1, 7) == "android")
            val runtime = versions.get("runtime").obj(setOf("name", "version"))
            require(runtime.string("name", 1, 256).matches(Regex("elu-[a-z0-9-]+"))); runtime.string("version", 1, 64)
            val facade = versions.get("facade").obj(setOf("name", "version"))
            require(facade.string("name", 1, 256).matches(Regex("[A-Za-z][A-Za-z0-9._-]+"))); facade.string("version", 1, 64)
            if (versions.member("build") != null) versions.string("build", 1, 128)
            // Bounded canonical outer validation only. Never inflate a restored body.
            ReplayBase64.decode(chunk.string("payload", 1, MAX_REPLAY_REQUEST_BYTES)).fill(0)
            return NativeRasterStoredRequest(input.copyOf(), requestId, chunk.string("replayId", 1, 256),
                chunk.string("sessionId", 1, 256), chunk.string("chunkId", 1, 256), chunk.number("sequence"), timestamp,
                identity.string("anonymousId", 1, 256), user, identity.number("revision"), chunk.number("contextRevision"),
                privacy.string("policyRevision", 1, 128), privacy.hash("effectivePolicyHash"))
        }
    }
}

internal data class NativeRasterStoredChunk(val ordinal: Long, val siteId: String, val request: NativeRasterStoredRequest)

/** Full-wrapper ordering is independent of the embedded legacy config ordering. */
internal data class NativeRasterSourceLedger(val issuedAt: String, val semanticHash: String, val conflicted: Boolean = false) {
    init { V1ConfigJson.parseExactTimestamp(issuedAt); require(semanticHash.matches(Regex("sha256:[a-f0-9]{64}"))) }
    fun value() = V1StrictCanonicalJson.Value.ObjectValue(listOf("issuedAt" to ReplayJson.text(issuedAt),
        "semanticHash" to ReplayJson.text(semanticHash), "conflicted" to V1StrictCanonicalJson.Value.BooleanValue(conflicted)))
    fun observe(issued: String, hash: String): Pair<NativeRasterSourceLedger, Boolean> {
        val order = V1ConfigJson.parseExactTimestamp(issued).compareTo(V1ConfigJson.parseExactTimestamp(issuedAt))
        if (order < 0) return this to false
        if (order > 0) return NativeRasterSourceLedger(issued, hash) to true
        val next = copy(conflicted = conflicted || semanticHash != hash)
        return next to !next.conflicted
    }
    companion object {
        fun decode(value: V1StrictCanonicalJson.Value): NativeRasterSourceLedger {
            val item = value.obj(setOf("issuedAt", "semanticHash", "conflicted"))
            return NativeRasterSourceLedger(item.string("issuedAt", 1, 128), item.hash("semanticHash"), item.boolean("conflicted"))
        }
    }
}

internal fun nativeRasterBaseMayRetain(config: V1ParsedConfig): Boolean =
    config.status == V1ConfigStatus.ENABLED && config.features?.capture == true && config.features.replay &&
        config.privacy?.capture?.enabled == true && config.privacy.replay.enabled &&
        config.privacy.region.mode != V1RegionPolicyMode.BLOCK && config.privacy.masking.text == V1TextMasking.SENSITIVE &&
        config.privacy.masking.images == V1ImageMasking.ALLOW &&
        config.privacy.masking.platformRules.none { it.platform != V1PrivacyPlatform.BROWSER }
