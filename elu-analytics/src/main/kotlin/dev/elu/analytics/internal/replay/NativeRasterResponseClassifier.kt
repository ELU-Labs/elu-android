package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.runtime.delivery.RetryAfterParser
import dev.elu.analytics.internal.runtime.delivery.V1BatchResponseCodec

internal enum class NativeRasterConflictScope { REQUEST, CHUNK, SEQUENCE }

internal sealed interface NativeRasterResponseOutcome {
    data object Accepted : NativeRasterResponseOutcome
    data class IdentityConflict(val scope: NativeRasterConflictScope) : NativeRasterResponseOutcome
    data object RejectedTooLarge : NativeRasterResponseOutcome
    data class Retry(val delayMillis: Long, val endpointCooldown: Boolean = false) : NativeRasterResponseOutcome
    data class CredentialBlocked(val status: Int) : NativeRasterResponseOutcome
    data object ProtocolBlocked : NativeRasterResponseOutcome
}

/**
 * Classifies only the response to one original schema3 request. No result resolves a queue claim.
 * The existing response value is bounded; duplicate headers and physical refusal preservation
 * remain the original transport owner's responsibility, not facts reconstructed here.
 */
internal object NativeRasterResponseClassifier {
    fun classify(response: ReplayTransportResponse, request: NativeRasterPreparedRequest, now: Long,
        retryDelayMillis: Long): NativeRasterResponseOutcome {
        if (response.status == 401 || response.status == 403) {
            return NativeRasterResponseOutcome.CredentialBlocked(response.status)
        }
        val body = response.copyBody()
        return try {
            if (response.status == 200) {
                require(response.retryAfter == null)
                val ack = ReplayJson.parse(body).obj(setOf("schemaVersion", "requestId", "replayId", "chunkId", "sequence", "result"))
                require(ack.number("schemaVersion") == 3L &&
                    ack.string("requestId", 72, 72) == request.requestId &&
                    ack.string("replayId", 1, 256) == request.replayId &&
                    ack.string("chunkId", 1, 256) == request.chunkId &&
                    ack.number("sequence") == request.sequence && ack.string("result", 1, 16) == "accepted")
                NativeRasterResponseOutcome.Accepted
            } else if (response.status == 409) {
                require(response.retryAfter == null)
                val conflict = ReplayJson.parse(body).obj(setOf("schemaVersion", "requestId", "status", "code", "disposition", "conflictScope"))
                require(conflict.number("schemaVersion") == 3L && conflict.number("status") == 409L &&
                    conflict.string("requestId", 72, 72) == request.requestId &&
                    conflict.string("code", 1, 64) == "replay-identity-conflict" &&
                    conflict.string("disposition", 1, 64) == "permanent")
                val scope = when (conflict.string("conflictScope", 1, 16)) {
                    "request" -> NativeRasterConflictScope.REQUEST
                    "chunk" -> NativeRasterConflictScope.CHUNK
                    "sequence" -> NativeRasterConflictScope.SEQUENCE
                    else -> return NativeRasterResponseOutcome.ProtocolBlocked
                }
                NativeRasterResponseOutcome.IdentityConflict(scope)
            } else {
                V1BatchResponseCodec.validateTransportError(body, response.status, request.requestId)
                when {
                    response.status == 413 -> {
                        require(response.retryAfter == null)
                        NativeRasterResponseOutcome.RejectedTooLarge
                    }
                    response.status == 429 || response.status in 500..599 -> {
                        val header = response.retryAfter
                        if (response.status == 429) require(header != null)
                        val delay = header?.let { RetryAfterParser.parseDelayMillis(it, now) } ?: 0L
                        NativeRasterResponseOutcome.Retry(
                            minOf(REPLAY_MAX_RETRY_MILLIS, maxOf(0L, retryDelayMillis, delay)), response.status == 429)
                    }
                    else -> NativeRasterResponseOutcome.ProtocolBlocked
                }
            }
        } catch (_: Exception) { NativeRasterResponseOutcome.ProtocolBlocked }
        finally { body.fill(0) }
    }
}
