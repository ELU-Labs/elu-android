package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.runtime.RuntimePlatform
import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.nio.ByteBuffer

internal enum class NativeRasterSealingFailure {
    INVALID_BINDING, WITHDRAWN, SOURCE_MISMATCH, INVALID_TIMESTAMP, CHANGED_VIEWPORT,
    REQUEST_LIMIT, COMPRESSION, SEQUENCE_EXHAUSTED,
}
internal class NativeRasterSealingException(val failure: NativeRasterSealingFailure) : IllegalArgumentException(failure.name)

/** Descriptive only. The future resolver must independently prove the explicit declared-region grant. */
internal data class NativeRasterPolicyBinding(
    val policyRevision: String,
    val effectivePolicyHash: String,
    val contextRevision: Long,
    val maximumRequestBytes: Int,
) {
    init {
        rasterString(policyRevision, 128)
        rasterRequire(effectivePolicyHash.matches(Regex("sha256:[a-f0-9]{64}")), NativeRasterSealingFailure.INVALID_BINDING)
        rasterRequire(contextRevision in 0..MAX_REPLAY_SAFE_INTEGER && maximumRequestBytes in 1..MAX_REPLAY_REQUEST_BYTES,
            NativeRasterSealingFailure.INVALID_BINDING)
    }
}

/** Original immutable request bytes; no public JSON, PNG or bitmap submission interface. */
internal class NativeRasterPreparedRequest internal constructor(
    body: ByteArray,
    val requestId: String,
    val chunkId: String,
    val replayId: String,
    val sessionId: String,
    val sequence: Long,
    val timestamp: Long,
    val contextRevision: Long,
    val effectivePolicyHash: String,
    val width: Int,
    val height: Int,
    internal val sourceIdentity: AnnotatedRasterSourceIdentity,
    private val originalCaptureCurrent: () -> Boolean,
) {
    internal fun originalCaptureIsCurrent(): Boolean = sourceIdentity.isCurrent() && originalCaptureCurrent()
    private val bytes = body.copyOf()
    val digest: String = ReplayJson.digest(bytes)
    val byteCount: Int get() = bytes.size
    val codec: String get() = NativeRasterSealer.CODEC
    val captureProtocolGeneration: String get() = NativeRasterSealer.GENERATION
    fun copyBytes(): ByteArray = bytes.copyOf()
    internal fun clearRejected() { bytes.fill(0) }
}

/**
 * Dormant serial epoch. Keep a fork speculative until the original queue reports a known commit;
 * retries use the prepared request's original bytes. This grants no collection or SQL admission.
 */
internal class NativeRasterSealer {
    private val replayId: String
    private val sessionId: String
    private val identity: Value
    private val versions: Value
    private val privacy: Value
    private val policy: NativeRasterPolicyBinding
    private val sourceIdentity: AnnotatedRasterSourceIdentity
    // Retains the original source/identity/privacy/local-intent witness; never replaced per frame.
    private val sourceIsCurrent: () -> Boolean
    private var nextSequence = 0L
    private var lastTimestamp: Long? = null
    private var viewport: Pair<Int, Int>? = null

    constructor(replayId: String, snapshot: IdentityState, policy: NativeRasterPolicyBinding,
        versions: RuntimeVersions, sourceIdentity: AnnotatedRasterSourceIdentity, sourceIsCurrent: () -> Boolean) {
        CoreStateCodec.encodeIdentity(snapshot)
        RuntimeRecordCodec.encodeBatchVersions(versions)
        rasterRequire(sourceIdentity.isCurrent() && sourceIsCurrent(), NativeRasterSealingFailure.WITHDRAWN)
        rasterRequire(snapshot.session != null && !snapshot.optedOut && snapshot.revision in 0..MAX_REPLAY_SAFE_INTEGER &&
            snapshot.contextRevision == policy.contextRevision && versions.platform == RuntimePlatform.ANDROID,
            NativeRasterSealingFailure.INVALID_BINDING)
        this.replayId = rasterString(replayId, 256)
        sessionId = rasterString(checkNotNull(snapshot.session).id, 256)
        this.policy = policy; this.sourceIdentity = sourceIdentity; this.sourceIsCurrent = sourceIsCurrent
        identity = obj("anonymousId" to text(rasterString(snapshot.anonymousId, 256)),
            "userId" to (snapshot.userId?.let { text(rasterString(it, 512)) } ?: Value.NullValue),
            "revision" to number(snapshot.revision))
        privacy = obj("schemaVersion" to number(1), "policyRevision" to text(policy.policyRevision),
            "effectivePolicyHash" to text(policy.effectivePolicyHash), "maskingProfileHash" to text(PROFILE_HASH),
            "inputCoverage" to text("declared-regions"), "automaticInputDiscovery" to Value.BooleanValue(false),
            "unknownContentClassification" to Value.BooleanValue(false), "appliedBeforeSerialization" to Value.BooleanValue(true),
            "requiredRegionsRedacted" to Value.BooleanValue(true), "platformFallbackApplied" to Value.BooleanValue(false))
        val fields = mutableListOf("schemaVersion" to number(2), "contractVersion" to text("2.0.0"),
            "platform" to text("android"),
            "runtime" to obj("name" to text(rasterString(versions.runtime.name, 256)), "version" to text(rasterString(versions.runtime.version, 64))),
            "facade" to obj("name" to text(rasterString(versions.facade.name, 256)), "version" to text(rasterString(versions.facade.version, 64))))
        versions.build?.let { fields += "build" to text(rasterString(it, 128)) }
        this.versions = Value.ObjectValue(fields.toList())
    }

    private constructor(original: NativeRasterSealer) {
        replayId = original.replayId; sessionId = original.sessionId; identity = original.identity
        versions = original.versions; privacy = original.privacy; policy = original.policy
        sourceIdentity = original.sourceIdentity; sourceIsCurrent = original.sourceIsCurrent
        nextSequence = original.nextSequence; lastTimestamp = original.lastTimestamp; viewport = original.viewport
    }
    @Synchronized fun fork(): NativeRasterSealer = NativeRasterSealer(this)

    /** Original wall milliseconds are never adjusted to satisfy pacing. All paths consume the frame. */
    @Synchronized fun seal(frame: AnnotatedRasterCandidate, timestamp: Long): NativeRasterPreparedRequest {
        var png: ByteArray? = null
        var payload: ByteArray? = null
        var compressed: ByteArray? = null
        var canonicalChunk: ByteArray? = null
        var material: ByteArray? = null
        var body: ByteArray? = null
        var prepared: NativeRasterPreparedRequest? = null
        var accepted = false
        var primary: Throwable? = null
        try {
            checkSource()
            rasterRequire(frame.sourceIdentity === sourceIdentity, NativeRasterSealingFailure.SOURCE_MISMATCH)
            rasterRequire(nextSequence <= MAX_REPLAY_SAFE_INTEGER, NativeRasterSealingFailure.SEQUENCE_EXHAUSTED)
            rasterRequire(timestamp in 1..253_402_300_799_999L &&
                lastTimestamp?.let { timestamp >= it && timestamp - it >= 1_000 } != false,
                NativeRasterSealingFailure.INVALID_TIMESTAMP)
            rasterRequire(viewport == null || viewport == Pair(frame.width, frame.height), NativeRasterSealingFailure.CHANGED_VIEWPORT)
            val time = NativeReplaySealer.timestamp(timestamp)
            val image = frame.encodePng(); png = image
            checkSource()
            val encodedPayload = canonical(Value.ArrayValue(listOf(obj("schemaVersion" to number(1), "type" to text("frame"),
                "timestamp" to number(timestamp), "image" to obj("width" to number(frame.width.toLong()),
                    "height" to number(frame.height.toLong()), "png" to text(ReplayBase64.encode(image))),
                "viewport" to obj("width" to number(frame.width.toLong()), "height" to number(frame.height.toLong()))))))
            payload = encodedPayload
            rasterRequire(encodedPayload.size <= MAX_PAYLOAD_BYTES, NativeRasterSealingFailure.REQUEST_LIMIT)
            checkSource()
            val chunkId = "chunk_" + digest(canonical(obj("domain" to text("elu-native-raster-chunk-v1"),
                "replayId" to text(replayId), "sequence" to number(nextSequence))))
            fun chunk(content: String) = obj("schemaVersion" to number(3), "replayId" to text(replayId),
                "sessionId" to text(sessionId), "chunkId" to text(chunkId), "sequence" to number(nextSequence),
                "startedAt" to text(time), "endedAt" to text(time), "identity" to identity,
                "contextRevision" to number(policy.contextRevision), "codec" to text(CODEC), "compression" to text("gzip"),
                "contentEncoding" to text("base64"), "payload" to text(content), "privacy" to privacy, "versions" to versions)
            val overhead = canonical(envelope(chunk(""), "request_" + "0".repeat(64))).size
            rasterRequire(overhead < policy.maximumRequestBytes, NativeRasterSealingFailure.REQUEST_LIMIT)
            val zipped = try {
                NativeReplaySealer.gzip(encodedPayload, (policy.maximumRequestBytes - overhead) / 4 * 3)
            } catch (error: NativeReplaySealingException) {
                throw NativeRasterSealingException(if (error.failure == NativeReplaySealingFailure.REQUEST_LIMIT)
                    NativeRasterSealingFailure.REQUEST_LIMIT else NativeRasterSealingFailure.COMPRESSION)
            }
            compressed = zipped
            val value = chunk(ReplayBase64.encode(zipped))
            val chunkBytes = canonical(value); canonicalChunk = chunkBytes
            val hashInput = "elu-sdk-replay-request-v3".toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                ByteBuffer.allocate(4).putInt(chunkBytes.size).array() + chunkBytes
            material = hashInput
            val requestId = "request_" + digest(hashInput)
            val requestBytes = canonical(envelope(value, requestId)); body = requestBytes
            rasterRequire(requestBytes.size <= policy.maximumRequestBytes, NativeRasterSealingFailure.REQUEST_LIMIT)
            checkSource()
            // Copy the original predicates, not this sealer or the already-consumed pixel owner.
            val originalFrameCurrent = frame.publicationGuard()
            val originalSourceCurrent = sourceIsCurrent
            val result = NativeRasterPreparedRequest(requestBytes, requestId, chunkId, replayId, sessionId,
                nextSequence, timestamp, policy.contextRevision, policy.effectivePolicyHash, frame.width, frame.height, sourceIdentity,
                { originalFrameCurrent() && originalSourceCurrent() })
            prepared = result
            // Settle original frame cleanup before any state advance; encodePng already did this on success.
            frame.close()
            checkSource()
            nextSequence++; lastTimestamp = timestamp; viewport = Pair(frame.width, frame.height)
            accepted = true
            return result
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            png?.fill(0); payload?.fill(0); compressed?.fill(0); canonicalChunk?.fill(0); material?.fill(0); body?.fill(0)
            if (!accepted) prepared?.clearRejected()
            try { frame.close() } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    private fun checkSource() = rasterRequire(sourceIdentity.isCurrent() && sourceIsCurrent(), NativeRasterSealingFailure.WITHDRAWN)
    internal companion object {
        const val CODEC = "elu-native-raster-v1"
        const val GENERATION = "native-raster-generation-v1"
        const val PROFILE_HASH = "sha256:e374338e6100edcad1d11de079f6bdfc24df043107628b83a559bb3e87206422"
        const val MAX_PAYLOAD_BYTES = 2_800_000
        private fun obj(vararg fields: Pair<String, Value>) = Value.ObjectValue(fields.toList())
        private fun text(value: String) = Value.StringValue(value)
        private fun number(value: Long) = Value.NumberValue(value.toString())
        private fun canonical(value: Value) = V1StrictCanonicalJson.canonicalBytes(value)
        private fun digest(value: ByteArray) = ReplayJson.digest(value).removePrefix("sha256:")
        private fun envelope(chunk: Value, requestId: String) = obj("schemaVersion" to number(3), "requestId" to text(requestId), "chunk" to chunk)
    }
}

private fun rasterRequire(allowed: Boolean, failure: NativeRasterSealingFailure) {
    if (!allowed) throw NativeRasterSealingException(failure)
}
private fun rasterString(value: String, maximum: Int): String {
    rasterRequire(value.codePointCount(0, value.length) in 1..maximum, NativeRasterSealingFailure.INVALID_BINDING)
    var index = 0
    while (index < value.length) {
        val unit = value[index++]
        if (Character.isHighSurrogate(unit)) {
            rasterRequire(index < value.length && Character.isLowSurrogate(value[index]), NativeRasterSealingFailure.INVALID_BINDING)
            index++
        } else rasterRequire(!Character.isLowSurrogate(unit), NativeRasterSealingFailure.INVALID_BINDING)
    }
    return value
}
