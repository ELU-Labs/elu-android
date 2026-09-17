package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ExactTimestamp
import dev.elu.analytics.internal.config.V1ReplayCompression
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal const val MAX_REPLAY_REQUEST_BYTES = 5_242_880
internal const val REPLAY_RETENTION_SECONDS = 604_800L
internal const val REPLAY_SEGMENT_BYTES = 262_144
internal const val MAX_REPLAY_SAFE_INTEGER = 9_007_199_254_740_991L

/** Validates the closed outer envelope only. It neither decodes nor certifies an inner codec. */
internal class PreparedReplayRequest private constructor(
    private val bytes: ByteArray,
    /** Capture-time generation is immutable storage metadata and is never added to wire bytes. */
    val captureProtocolGeneration: String,
    val requestId: String,
    val replayId: String,
    val sessionId: String,
    val chunkId: String,
    val sequence: Long,
    val startedAt: String,
    val endedAt: String,
    val anonymousId: String,
    val userId: String?,
    val identityRevision: Long,
    val contextRevision: Long,
    val transport: V1ReplayTransport,
    val policyRevision: String,
    val effectivePolicyHash: String,
    val maskingProfileHash: String,
    val platform: String,
    val platformFallbackApplied: Boolean,
) {
    val byteCount: Int get() = bytes.size
    val digest: String = ReplayJson.digest(bytes)
    val startedAtInstant: V1ExactTimestamp = V1ConfigJson.parseExactTimestamp(startedAt)
    val endedAtInstant: V1ExactTimestamp = V1ConfigJson.parseExactTimestamp(endedAt)
    fun copyBytes(): ByteArray = bytes.copyOf()
    fun expiredAt(wallEpochMillis: Long): Boolean = V1ExactTimestamp.fromEpochMillis(wallEpochMillis) >=
        V1ExactTimestamp.fromEpochSecondAndFraction(
            Math.addExact(startedAtInstant.epochWholeSecond, REPLAY_RETENTION_SECONDS),
            startedAtInstant.fractionalDigits, startedAtInstant.isLeapSecond)

    companion object {
        fun parse(input: ByteArray, captureProtocolGeneration: String, maximumBytes: Int = MAX_REPLAY_REQUEST_BYTES): PreparedReplayRequest {
            require(captureProtocolGeneration.codePointCount(0, captureProtocolGeneration.length) in 1..128)
            V1StrictCanonicalJson.canonicalize(ReplayJson.text(captureProtocolGeneration))
            require(maximumBytes in 1..MAX_REPLAY_REQUEST_BYTES)
            require(input.size in 1..maximumBytes) { "Replay request size is outside its bound" }
            val bytes = input.copyOf()
            val root = ReplayJson.parse(bytes).obj(setOf("schemaVersion", "requestId", "chunk"))
            require(root.number("schemaVersion") == 2L)
            require(V1StrictCanonicalJson.canonicalBytes(root).contentEquals(bytes)) { "Replay request must already be canonical" }
            val chunk = root.get("chunk").obj(setOf("schemaVersion", "replayId", "sessionId", "chunkId", "sequence",
                "startedAt", "endedAt", "identity", "contextRevision", "codec", "compression", "contentEncoding",
                "payload", "privacy", "versions"))
            require(chunk.number("schemaVersion") == 2L)
            val requestId = root.string("requestId", 72, 72)
            require(requestId.matches(Regex("request_[a-f0-9]{64}")))
            val chunkBytes = V1StrictCanonicalJson.canonicalBytes(chunk)
            val material = "elu-sdk-replay-request-v2".toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                ByteBuffer.allocate(4).putInt(chunkBytes.size).array() + chunkBytes
            require(requestId == "request_" + ReplayJson.digest(material).removePrefix("sha256:")) { "Replay request ID mismatch" }
            val startedAt = chunk.string("startedAt", 1, 128)
            val endedAt = chunk.string("endedAt", 1, 128)
            val started = V1ConfigJson.parseExactTimestamp(startedAt)
            val ended = V1ConfigJson.parseExactTimestamp(endedAt)
            require(ended >= started) { "Replay interval is inverted" }
            val identity = chunk.get("identity").obj(setOf("anonymousId", "userId", "revision"))
            val user = identity.get("userId").let { if (it === V1StrictCanonicalJson.Value.NullValue) null else identity.string("userId", 1, 512) }
            val codec = chunk.string("codec", 5, 68)
            require(codec.matches(Regex("elu-[a-z0-9][a-z0-9.-]{0,63}")))
            val compression = V1ReplayCompression.entries.singleOrNull { it.wireValue == chunk.string("compression", 1, 4) }
                ?: throw IllegalArgumentException("Unsupported replay compression")
            require(chunk.string("contentEncoding", 1, 6) == "base64")
            val payload = chunk.string("payload", 1, MAX_REPLAY_REQUEST_BYTES)
            require(payload.length % 4 == 0 && payload.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' })
            ReplayBase64.decode(payload)
            val privacy = chunk.get("privacy").obj(setOf("policyRevision", "effectivePolicyHash", "maskingProfileHash",
                "appliedBeforeSerialization", "secureInputsMasked", "platformFallbackApplied"))
            require(privacy.boolean("appliedBeforeSerialization") && privacy.boolean("secureInputsMasked"))
            privacy.boolean("platformFallbackApplied")
            val effectiveHash = privacy.hash("effectivePolicyHash")
            val maskingHash = privacy.hash("maskingProfileHash")
            val versions = chunk.get("versions").obj(setOf("schemaVersion", "contractVersion", "platform", "runtime", "facade"), setOf("build"))
            require(versions.number("schemaVersion") == 2L && versions.string("contractVersion", 5, 5) == "2.0.0")
            val platform = versions.string("platform", 1, 7)
            require(platform in setOf("browser", "android", "ios"))
            val runtime = versions.get("runtime").obj(setOf("name", "version"))
            require(runtime.string("name", 1, 256).matches(Regex("elu-[a-z0-9-]+")))
            runtime.string("version", 1, 64)
            val facade = versions.get("facade").obj(setOf("name", "version"))
            require(facade.string("name", 1, 256).matches(Regex("[A-Za-z][A-Za-z0-9._-]+")))
            facade.string("version", 1, 64)
            if (versions.member("build") != null) versions.string("build", 1, 128)
            return PreparedReplayRequest(bytes, captureProtocolGeneration, requestId, chunk.string("replayId", 1, 256), chunk.string("sessionId", 1, 256),
                chunk.string("chunkId", 1, 256), chunk.number("sequence"), startedAt, endedAt,
                identity.string("anonymousId", 1, 256), user, identity.number("revision"), chunk.number("contextRevision"),
                V1ReplayTransport(codec, compression), privacy.string("policyRevision", 1, 128), effectiveHash, maskingHash, platform, privacy.boolean("platformFallbackApplied"))
        }
    }
}

/** Shared strict storage JSON primitives; no diagnostic includes payload bytes. */
internal object ReplayJson {
    fun parse(bytes: ByteArray): V1StrictCanonicalJson.Value {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(!text.startsWith('\uFEFF'))
        return V1StrictCanonicalJson.parse(text)
    }
    fun digest(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun encode(vararg pairs: Pair<String, V1StrictCanonicalJson.Value>): ByteArray =
        V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(pairs.toList()))
    fun text(value: String) = V1StrictCanonicalJson.Value.StringValue(value)
    fun number(value: Long) = V1StrictCanonicalJson.Value.NumberValue(value.toString())
}

internal fun V1StrictCanonicalJson.Value.obj(required: Set<String>, optional: Set<String> = emptySet()): V1StrictCanonicalJson.Value.ObjectValue {
    val value = this as? V1StrictCanonicalJson.Value.ObjectValue ?: throw IllegalArgumentException("Expected replay object")
    val names = value.members.map { it.first }.toSet()
    require(names.containsAll(required) && (names - required - optional).isEmpty()) { "Replay object fields do not match schema" }
    return value
}
internal fun V1StrictCanonicalJson.Value.ObjectValue.get(key: String): V1StrictCanonicalJson.Value =
    member(key) ?: throw IllegalArgumentException("Missing replay field")
internal fun V1StrictCanonicalJson.Value.ObjectValue.string(key: String, minimum: Int, maximum: Int): String {
    val value = (get(key) as? V1StrictCanonicalJson.Value.StringValue)?.value ?: throw IllegalArgumentException("Expected replay string")
    require(value.codePointCount(0, value.length) in minimum..maximum)
    return value
}
internal fun V1StrictCanonicalJson.Value.ObjectValue.number(key: String): Long {
    val value = get(key) as? V1StrictCanonicalJson.Value.NumberValue ?: throw IllegalArgumentException("Expected replay integer")
    val canonical = V1StrictCanonicalJson.canonicalize(value)
    val number = canonical.toLongOrNull() ?: throw IllegalArgumentException("Expected replay integer")
    require(number in 0..MAX_REPLAY_SAFE_INTEGER)
    return number
}
internal fun V1StrictCanonicalJson.Value.ObjectValue.boolean(key: String): Boolean =
    (get(key) as? V1StrictCanonicalJson.Value.BooleanValue)?.value ?: throw IllegalArgumentException("Expected replay Boolean")
internal fun V1StrictCanonicalJson.Value.ObjectValue.hash(key: String): String = string(key, 71, 71).also {
    require(it.matches(Regex("sha256:[a-f0-9]{64}")))
}

/** Canonical RFC 4648 without java.util.Base64, which requires Android API 26. */
internal object ReplayBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun encode(bytes: ByteArray): String = buildString((bytes.size + 2) / 3 * 4) {
        var index = 0
        while (index < bytes.size) {
            val a = bytes[index++].toInt() and 255
            val b = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
            val c = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
            append(ALPHABET[a ushr 2]); append(ALPHABET[((a and 3) shl 4) or (if (b < 0) 0 else b ushr 4)])
            append(if (b < 0) '=' else ALPHABET[((b and 15) shl 2) or (if (c < 0) 0 else c ushr 6)])
            append(if (c < 0) '=' else ALPHABET[c and 63])
        }
    }
    fun decode(text: String): ByteArray {
        require(text.isNotEmpty() && text.length % 4 == 0)
        val padding = if (text.endsWith("==")) 2 else if (text.endsWith("=")) 1 else 0
        val result = ByteArray(text.length / 4 * 3 - padding)
        var output = 0
        for (index in text.indices step 4) {
            var bits = 0
            for (part in 0..3) {
                val position = index + part
                val value = if (position >= text.length - padding) {
                    require(text[position] == '='); 0
                } else ALPHABET.indexOf(text[position]).also { require(it >= 0) }
                bits = (bits shl 6) or value
            }
            if (output < result.size) result[output++] = (bits ushr 16).toByte()
            if (output < result.size) result[output++] = (bits ushr 8).toByte()
            if (output < result.size) result[output++] = bits.toByte()
        }
        require(encode(result) == text) { "Noncanonical base64" }
        return result
    }
}
