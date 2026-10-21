package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.runtime.delivery.RetryAfterParser
import dev.elu.analytics.internal.runtime.delivery.V1BatchResponseCodec

/** Frozen v2 ACK plus the existing v1 operational error profile; no partial trust. */
internal object ReplayResponseClassifier {
    fun classify(response: ReplayTransportResponse, request: PreparedReplayRequest, now: Long,
        retryDelayMillis: Long): ReplayDeliveryOutcome {
        val closed = classifyClosed(response, request, now, retryDelayMillis)
        // A validated error object keeps its exact semantics. Any other 429/5xx (plain text, HTML,
        // empty, oversized, or with an unreadable Retry-After) retries this row only and never
        // pauses the endpoint.
        if (closed != PROTOCOL_BLOCKED || !RetryAfterParser.isRetryableStatus(response.status)) return closed
        val delay = RetryAfterParser.foreignDelayMillis(response.retryAfter, now)
        return ReplayDeliveryOutcome.Retry(minOf(REPLAY_MAX_RETRY_MILLIS, maxOf(retryDelayMillis, delay)))
    }

    private val PROTOCOL_BLOCKED = ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL)

    private fun classifyClosed(response: ReplayTransportResponse, request: PreparedReplayRequest, now: Long,
        retryDelayMillis: Long): ReplayDeliveryOutcome {
        if (response.status == 401) return ReplayDeliveryOutcome.Blocked(ReplayBlockKind.UNAUTHORIZED)
        if (response.status == 403) return ReplayDeliveryOutcome.Blocked(ReplayBlockKind.FORBIDDEN)
        // A wireframe 409, even with a valid identity-conflict body, is a protocol hold: nothing is
        // deleted, and the queue offers the same bytes again after the hold until the row expires.
        if (response.status == 409) return PROTOCOL_BLOCKED
        return try {
            val body = response.copyBody()
            if (response.status == 200) {
                require(response.retryAfter == null)
                val ack = ReplayJson.parse(body).obj(setOf("schemaVersion", "requestId", "replayId", "chunkId", "sequence", "result"))
                require(ack.number("schemaVersion") == 2L && ack.string("requestId", 72, 72) == request.requestId &&
                    ack.string("replayId", 1, 256) == request.replayId && ack.string("chunkId", 1, 256) == request.chunkId &&
                    ack.number("sequence") == request.sequence && ack.string("result", 1, 16) == "accepted")
                ReplayDeliveryOutcome.Accepted
            } else {
                // The existing parser is strict/duplicate-safe and binds optional requestId.
                V1BatchResponseCodec.validateTransportError(body, response.status, request.requestId)
                when {
                    response.status == 413 -> {
                        require(response.retryAfter == null)
                        ReplayDeliveryOutcome.RejectedTooLarge
                    }
                    response.status == 429 || response.status in 500..599 -> {
                        val header = response.retryAfter
                        if (response.status == 429) require(header != null)
                        val delay = header?.let { RetryAfterParser.parseDelayMillis(it, now) } ?: 0
                        ReplayDeliveryOutcome.Retry(minOf(REPLAY_MAX_RETRY_MILLIS, maxOf(retryDelayMillis, delay)), response.status == 429)
                    }
                    else -> ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL)
                }
            }
        } catch (_: Exception) { ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL) }
    }
}
