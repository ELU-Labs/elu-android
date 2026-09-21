package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1AuthorizedConfig
import dev.elu.analytics.internal.config.V1ChannelAuthorizationStatus
import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1PrivacyPlatform
import dev.elu.analytics.internal.config.V1ReplayCompression
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.runtime.RuntimePlatform
import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater

internal enum class NativeReplaySealingFailure { INVALID_BINDING, REQUEST_LIMIT, COMPRESSION }
internal class NativeReplaySealingException(val failure: NativeReplaySealingFailure) : IllegalArgumentException(failure.name)

/** Immutable descriptive binding only. Neither construction nor sealing grants collection or queue admission. */
internal class NativeReplaySealer(
    replayId: String,
    identity: IdentityState,
    authorization: V1AuthorizedConfig,
    privacy: ByteArray,
    profile: NativeMaskingProfile,
    versions: RuntimeVersions,
    limits: NativeWireframeEncoder.Limits = NativeWireframeEncoder.Limits(),
) {
    private val replayId: String
    private val sessionId: String
    private val originalIdentity: Value
    private val contextRevision: Long
    private val originalPrivacy: Value
    private val originalVersions: Value
    private val protocolGeneration: String
    private val maximumRequestBytes: Int
    private var encoder = NativeWireframeEncoder(limits, maskingProfile = profile)

    init {
        CoreStateCodec.encodeIdentity(identity)
        RuntimeRecordCodec.encodeBatchVersions(versions)
        val pair = V1ReplayTransport(CODEC, V1ReplayCompression.GZIP)
        val session = identity.session
        binding(session != null && !identity.optedOut && authorization.schemaVersion == 2 &&
            authorization.captureAuthorization.status == V1ChannelAuthorizationStatus.AUTHORIZED &&
            authorization.replayAuthorization.status == V1ChannelAuthorizationStatus.AUTHORIZED &&
            authorization.negotiatedReplayTransport == pair && pair in authorization.replayCapabilities.advertisedTransports &&
            versions.platform == RuntimePlatform.ANDROID && privacy.size in 1..32_768)
        val ownedPrivacy = privacy.copyOf()
        val document = ReplayJson.parse(ownedPrivacy)
        binding(V1StrictCanonicalJson.canonicalBytes(document).contentEquals(ownedPrivacy))
        val original = V1ConfigJson.parseEffectivePrivacy(ownedPrivacy.toString(Charsets.UTF_8))
        val members = (document as? Value.ObjectValue)?.members ?: throw NativeReplaySealingException(NativeReplaySealingFailure.INVALID_BINDING)
        binding(V1StrictCanonicalJson.sha256(Value.ObjectValue(members.filter { it.first != "effectivePolicyHash" })) == original.effectivePolicyHash &&
            original == authorization.effectivePrivacy && original.policyRevision == authorization.privacy.revision &&
            original.contextRevision == identity.contextRevision && original.captureAllowed && original.replayAllowed &&
            original.replaySampled && original.maskingValidated && original.replaySessionEligible && !original.identityOptedOut &&
            original.replayBudgetRemainingSeconds > 0 && original.replayTransport?.codec == CODEC &&
            original.replayTransport.compression == V1ReplayCompression.GZIP && original.replayTransport.advertised &&
            original.effectiveMasking.secureInputsMasked &&
            (profile === NativeMaskingProfile.blanketMask() ||
                profile === NativeMaskingProfile.select(authorization.privacy.masking, V1PrivacyPlatform.ANDROID)) &&
            original.effectiveMasking.text == profile.textMasking &&
            profile.compatibility(authorization.privacy.masking, V1PrivacyPlatform.ANDROID) == NativeMaskingCompatibility.COMPATIBLE)
        this.replayId = checkedString(replayId, 256)
        sessionId = checkedString(checkNotNull(session).id, 256)
        originalIdentity = obj("anonymousId" to text(checkedString(identity.anonymousId, 256)),
            "userId" to (identity.userId?.let { text(checkedString(it, 512)) } ?: Value.NullValue),
            "revision" to integer(identity.revision))
        contextRevision = checkedInteger(identity.contextRevision)
        originalPrivacy = obj("policyRevision" to text(checkedString(original.policyRevision, 128)),
            "effectivePolicyHash" to text(original.effectivePolicyHash), "maskingProfileHash" to text(profile.hash),
            "appliedBeforeSerialization" to Value.BooleanValue(true), "secureInputsMasked" to Value.BooleanValue(true),
            "platformFallbackApplied" to Value.BooleanValue(original.effectiveMasking.platformFallbackApplied))
        val versionFields = mutableListOf("schemaVersion" to integer(2), "contractVersion" to text("2.0.0"),
            "platform" to text("android"), "runtime" to obj("name" to text(checkedString(versions.runtime.name, 256)),
                "version" to text(checkedString(versions.runtime.version, 64))),
            "facade" to obj("name" to text(checkedString(versions.facade.name, 256)),
                "version" to text(checkedString(versions.facade.version, 64))))
        versions.build?.let { versionFields += "build" to text(checkedString(it, 128)) }
        originalVersions = Value.ObjectValue(versionFields.toList())
        protocolGeneration = checkedString(checkNotNull(authorization.replayCapabilities.replayProtocolGeneration), 128)
        maximumRequestBytes = minOf(authorization.limits.replayChunkBytes, MAX_REPLAY_REQUEST_BYTES)
        binding(maximumRequestBytes > 0)
    }

    /** Only a fully validated prepared request commits encoder history; limits and binding never change. */
    @Synchronized
    fun seal(snapshots: List<NativeMaskedSnapshot>): PreparedReplayRequest {
        val next = encoder.fork()
        val chunk = next.encode(snapshots)
        val chunkId = "chunk_" + digest(V1StrictCanonicalJson.canonicalBytes(obj(
            "domain" to text("elu-native-replay-chunk-v1"), "replayId" to text(replayId), "sequence" to integer(chunk.sequence))))
        val startedAt = timestamp(chunk.firstTimestamp)
        val endedAt = timestamp(chunk.lastTimestamp)
        fun value(payload: String) = obj("schemaVersion" to integer(2), "replayId" to text(replayId),
            "sessionId" to text(sessionId), "chunkId" to text(chunkId), "sequence" to integer(chunk.sequence),
            "startedAt" to text(startedAt), "endedAt" to text(endedAt), "identity" to originalIdentity,
            "contextRevision" to integer(contextRevision), "codec" to text(CODEC), "compression" to text("gzip"),
            "contentEncoding" to text("base64"), "payload" to text(payload), "privacy" to originalPrivacy, "versions" to originalVersions)
        val overhead = V1StrictCanonicalJson.canonicalBytes(envelope(value(""), "request_" + "0".repeat(64))).size
        requestLimit(overhead < maximumRequestBytes)
        val compressedLimit = (maximumRequestBytes - overhead) / 4 * 3
        val compressed = gzip(chunk.bytes, compressedLimit)
        val chunkValue = value(ReplayBase64.encode(compressed))
        val canonicalChunk = V1StrictCanonicalJson.canonicalBytes(chunkValue)
        val material = "elu-sdk-replay-request-v2".toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            ByteBuffer.allocate(4).putInt(canonicalChunk.size).array() + canonicalChunk
        val requestId = "request_" + digest(material)
        val body = V1StrictCanonicalJson.canonicalBytes(envelope(chunkValue, requestId))
        requestLimit(body.size <= maximumRequestBytes)
        val prepared = PreparedReplayRequest.parse(body, protocolGeneration, maximumRequestBytes)
        encoder = next
        return prepared
    }

    private companion object {
        const val CODEC = "elu-native-wireframe-v1"
        fun binding(allowed: Boolean) { if (!allowed) throw NativeReplaySealingException(NativeReplaySealingFailure.INVALID_BINDING) }
        fun requestLimit(allowed: Boolean) { if (!allowed) throw NativeReplaySealingException(NativeReplaySealingFailure.REQUEST_LIMIT) }
        fun obj(vararg pairs: Pair<String, Value>) = Value.ObjectValue(pairs.toList())
        fun text(value: String) = Value.StringValue(value)
        fun checkedInteger(value: Long): Long { binding(value in 0..MAX_REPLAY_SAFE_INTEGER); return value }
        fun integer(value: Long) = Value.NumberValue(checkedInteger(value).toString())
        fun envelope(chunk: Value, requestId: String) = obj("schemaVersion" to integer(2), "requestId" to text(requestId), "chunk" to chunk)
        fun digest(bytes: ByteArray) = ReplayJson.digest(bytes).removePrefix("sha256:")
        fun checkedString(value: String, maximum: Int): String {
            binding(value.codePointCount(0, value.length) in 1..maximum)
            var index = 0
            while (index < value.length) {
                val unit = value[index++]
                if (Character.isHighSurrogate(unit)) {
                    binding(index < value.length && Character.isLowSurrogate(value[index])); index++
                } else binding(!Character.isLowSurrogate(unit))
            }
            return value
        }
        fun timestamp(milliseconds: Long): String {
            nativeRequire(milliseconds in 1..253_402_300_799_999L, NativeEncodingFailure.INVALID_TIMESTAMP)
            val zone = TimeZone.getTimeZone("UTC")
            val calendar = GregorianCalendar(zone, Locale.ROOT).apply { gregorianChange = Date(Long.MIN_VALUE) }
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                this.calendar = calendar; timeZone = zone; isLenient = false
            }
            return format.format(Date(milliseconds)).also { V1ConfigJson.parseExactTimestamp(it) }
        }
        /** API23-safe deterministic gzip: zero mtime, no optional fields, level6, Unix OS byte. */
        fun gzip(input: ByteArray, maximumBytes: Int): ByteArray {
            requestLimit(input.size in 1..16_777_216 && maximumBytes >= 18)
            val output = ByteArrayOutputStream()
            fun append(bytes: ByteArray, count: Int = bytes.size) {
                requestLimit(count <= maximumBytes - output.size()); output.write(bytes, 0, count)
            }
            append(byteArrayOf(31, -117, 8, 0, 0, 0, 0, 0, 0, 3))
            val deflater = Deflater(6, true)
            try {
                deflater.setInput(input); deflater.finish()
                val block = ByteArray(65_536)
                while (!deflater.finished()) {
                    val count = deflater.deflate(block)
                    if (count <= 0) throw NativeReplaySealingException(NativeReplaySealingFailure.COMPRESSION)
                    append(block, count)
                }
            } finally { deflater.end() }
            val crc = CRC32().apply { update(input) }.value
            val trailer = ByteArray(8)
            for (index in 0..3) {
                trailer[index] = (crc ushr (index * 8)).toByte()
                trailer[index + 4] = (input.size.toLong() ushr (index * 8)).toByte()
            }
            append(trailer)
            return output.toByteArray()
        }
    }
}
