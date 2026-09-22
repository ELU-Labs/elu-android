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

internal data class ReplayDeliveryPolicy(val privacy: ReplayDeliveryPrivacy, val retention: ReplayMaskingRetention)

/** Derived by the serialized owner from its validated endpoint and exact installation credential. */
internal data class ReplayDeliveryAuthorization(
    val endpoint: URI,
    val siteKey: String,
    val siteId: String,
    val transport: V1ReplayTransport,
    val protocolGeneration: String,
    val credentialWitness: String,
    val scopeWitness: String,
)

/** Only an exact persisted owner/nonce/digest match can execute or resolve this token. */
internal class ReplayDeliveryClaim internal constructor(
    internal val owner: String,
    internal val nonce: String,
    val row: ReplayStoredChunk,
    val authorization: ReplayDeliveryAuthorization,
    val attemptCount: Int,
)

internal sealed interface ReplayDeliveryOutcome {
    data object Accepted : ReplayDeliveryOutcome
    data object RejectedTooLarge : ReplayDeliveryOutcome
    data class Retry(val delayMillis: Long, val endpointCooldown: Boolean = false) : ReplayDeliveryOutcome
    data class Blocked(val kind: ReplayBlockKind) : ReplayDeliveryOutcome
}
internal enum class ReplayBlockKind { UNAUTHORIZED, FORBIDDEN, PROTOCOL, CONFLICT }
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
