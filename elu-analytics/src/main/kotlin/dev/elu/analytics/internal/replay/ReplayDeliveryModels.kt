package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1ParsedConfig
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.core.IdentityState
import java.net.URI
import dev.elu.analytics.internal.concurrent.SdkFuture

internal const val REPLAY_MAX_RESPONSE_BYTES = 65_536
internal const val REPLAY_MAX_RETRY_MILLIS = 86_400_000L
internal const val REPLAY_UNKNOWN_ATTEMPT_DELAY_MILLIS = 30_000L

/** Trusted composition supplies actual current privacy facts; this callback grants no authority. */
internal fun interface ReplayDeliveryPrivacy {
    fun current(config: V1ParsedConfig, identity: IdentityState, wallEpochMillis: Long): String?
}

internal enum class ReplayDeliveryFormat { WIREFRAME, RASTER }
internal enum class ReplayDeliverySupport { WIREFRAME_ONLY, INCLUDING_RASTER }
internal data class ReplayDeliveryPolicy(val privacy: ReplayDeliveryPrivacy, val retention: ReplayMaskingRetention,
    val support: ReplayDeliverySupport = ReplayDeliverySupport.WIREFRAME_ONLY)

/** Derived by the serialized owner from its validated endpoint and exact installation credential. */
internal data class ReplayDeliveryAuthorization(
    val endpoint: URI,
    val siteKey: String,
    val siteId: String,
    val transport: V1ReplayTransport,
    val protocolGeneration: String,
    val credentialWitness: String,
    val scopeWitness: String,
    val format: ReplayDeliveryFormat = ReplayDeliveryFormat.WIREFRAME,
    val sourceIssuedAt: String = "",
    val policyRevision: String = "",
    val effectivePolicyHash: String = "",
    val maximumRequestBytes: Int = MAX_REPLAY_REQUEST_BYTES,
)

/** Only an exact persisted owner/nonce/digest match can execute or resolve this token. */
internal class ReplayDeliveryClaim private constructor(
    internal val owner: String,
    internal val nonce: String,
    private val payload: Payload,
    val authorization: ReplayDeliveryAuthorization,
    val attemptCount: Int,
    internal val originalSource: dev.elu.analytics.internal.config.V2ConfigAuthorityWitness?,
) {
    private sealed interface Payload {
        data class Wireframe(val row: ReplayStoredChunk) : Payload
        data class Raster(val row: NativeRasterStoredChunk) : Payload
    }
    internal constructor(owner: String, nonce: String, row: ReplayStoredChunk,
        authorization: ReplayDeliveryAuthorization, attemptCount: Int) :
        this(owner, nonce, Payload.Wireframe(row), authorization, attemptCount, null) {
        require(authorization.format == ReplayDeliveryFormat.WIREFRAME)
    }
    val format get() = authorization.format
    // Existing callers remain typed wireframe consumers; raster code uses the closed alternative.
    val row: ReplayStoredChunk get() = (payload as Payload.Wireframe).row
    val rasterRow: NativeRasterStoredChunk get() = (payload as Payload.Raster).row
    val ordinal get() = when (val p = payload) { is Payload.Wireframe -> p.row.ordinal; is Payload.Raster -> p.row.ordinal }
    val digest get() = when (val p = payload) { is Payload.Wireframe -> p.row.prepared.digest; is Payload.Raster -> p.row.request.digest }
    val replayId get() = when (val p = payload) { is Payload.Wireframe -> p.row.prepared.replayId; is Payload.Raster -> p.row.request.replayId }
    fun copyBody(): ByteArray = when (val p = payload) { is Payload.Wireframe -> p.row.prepared.copyBytes(); is Payload.Raster -> p.row.request.copyBytes() }
    fun classify(response: ReplayTransportResponse, now: Long, retry: Long): ReplayDeliveryOutcome = when (val p = payload) {
        is Payload.Wireframe -> ReplayResponseClassifier.classify(response, p.row.prepared, now, retry)
        is Payload.Raster -> when (val outcome = NativeRasterResponseClassifier.classify(response, p.row.request, now, retry)) {
            NativeRasterResponseOutcome.Accepted -> ReplayDeliveryOutcome.Accepted
            NativeRasterResponseOutcome.RejectedTooLarge -> ReplayDeliveryOutcome.Blocked(ReplayBlockKind.RASTER_TOO_LARGE)
            is NativeRasterResponseOutcome.IdentityConflict -> ReplayDeliveryOutcome.Blocked(when (outcome.scope) {
                NativeRasterConflictScope.REQUEST -> ReplayBlockKind.RASTER_REQUEST
                NativeRasterConflictScope.CHUNK -> ReplayBlockKind.RASTER_CHUNK
                NativeRasterConflictScope.SEQUENCE -> ReplayBlockKind.RASTER_SEQUENCE
            })
            is NativeRasterResponseOutcome.CredentialBlocked -> ReplayDeliveryOutcome.Blocked(
                if (outcome.status == 401) ReplayBlockKind.UNAUTHORIZED else ReplayBlockKind.FORBIDDEN)
            NativeRasterResponseOutcome.ProtocolBlocked -> ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL)
            is NativeRasterResponseOutcome.Retry -> ReplayDeliveryOutcome.Retry(outcome.delayMillis, outcome.endpointCooldown)
        }
    }
    companion object {
        internal fun raster(owner: String, nonce: String, row: NativeRasterStoredChunk,
            authorization: ReplayDeliveryAuthorization, attemptCount: Int,
            source: dev.elu.analytics.internal.config.V2ConfigAuthorityWitness): ReplayDeliveryClaim {
            require(authorization.format == ReplayDeliveryFormat.RASTER)
            return ReplayDeliveryClaim(owner, nonce, Payload.Raster(row), authorization, attemptCount, source)
        }
    }
}

internal sealed interface ReplayDeliveryOutcome {
    data object Accepted : ReplayDeliveryOutcome
    data object RejectedTooLarge : ReplayDeliveryOutcome
    data class Retry(val delayMillis: Long, val endpointCooldown: Boolean = false) : ReplayDeliveryOutcome
    data class Blocked(val kind: ReplayBlockKind) : ReplayDeliveryOutcome
}
internal enum class ReplayBlockKind { UNAUTHORIZED, FORBIDDEN, PROTOCOL, CONFLICT,
    RASTER_REQUEST, RASTER_CHUNK, RASTER_SEQUENCE, RASTER_TOO_LARGE, RASTER_RETIRE }
internal enum class ReplayDeliveryCommit { COMMITTED, STALE }

internal class ReplayTransportResponse(val status: Int, body: ByteArray, val retryAfter: String? = null) {
    private val bytes = body.copyOf()
    init { require(status in 100..599); require(bytes.size <= REPLAY_MAX_RESPONSE_BYTES); require(retryAfter == null || retryAfter.length <= 256) }
    fun copyBody() = bytes.copyOf()
}

/**
 * Start only enrolls asynchronous work and must return promptly without network I/O. Settlement
 * completes after physical cleanup, including cancellation; cancel must not cancel settlement.
 * An eventual concrete transport must preserve these semantics before any activation.
 */
internal interface ReplayTransportOperation {
    val settlement: SdkFuture<ReplayTransportResponse>
    fun cancel()
}
internal fun interface ReplayDeliveryTransport {
    /** Worker must call authorizeIo immediately before I/O, without an intervening async hop. */
    fun start(claim: ReplayDeliveryClaim, authorizeIo: () -> Boolean): ReplayTransportOperation
}

internal interface ReplayDeliveryQueue {
    fun claim(): ReplayDeliveryClaim?
    fun nextWakeDelayMillis(): Long?
    fun dispatch(claim: ReplayDeliveryClaim, transport: ReplayDeliveryTransport): ReplayTransportOperation?
    fun commit(claim: ReplayDeliveryClaim, outcome: ReplayDeliveryOutcome): ReplayDeliveryCommit
    fun abandon(claim: ReplayDeliveryClaim)
}

internal fun interface ReplayDeliveryScheduledTask { fun cancel() }
internal fun interface ReplayDeliveryScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): ReplayDeliveryScheduledTask
}
