package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.runtime.delivery.RetryAfterParser
import dev.elu.analytics.internal.runtime.delivery.V1BatchResponseCodec

/** Frozen v2 ACK/conflict plus the existing v1 operational error profile; no partial trust. */
internal object ReplayResponseClassifier {
    fun classify(response: ReplayTransportResponse, request: PreparedReplayRequest, now: Long,
        retryDelayMillis: Long): ReplayDeliveryOutcome {
        if (response.status == 401) return ReplayDeliveryOutcome.Blocked(ReplayBlockKind.UNAUTHORIZED)
        if (response.status == 403) return ReplayDeliveryOutcome.Blocked(ReplayBlockKind.FORBIDDEN)
        return try {
            val body = response.copyBody()
            if (response.status == 200) {
                require(response.retryAfter == null)
                val ack = ReplayJson.parse(body).obj(setOf("schemaVersion", "requestId", "replayId", "chunkId", "sequence", "result"))
                require(ack.number("schemaVersion") == 2L && ack.string("requestId", 72, 72) == request.requestId &&
                    ack.string("replayId", 1, 256) == request.replayId && ack.string("chunkId", 1, 256) == request.chunkId &&
                    ack.number("sequence") == request.sequence && ack.string("result", 1, 16) == "accepted")
                ReplayDeliveryOutcome.Accepted
            } else if (response.status == 409) {
                require(response.retryAfter == null)
                val error = ReplayJson.parse(body).obj(setOf("schemaVersion", "requestId", "status", "code", "disposition", "conflictScope"))
                require(error.number("schemaVersion") == 2L && error.number("status") == 409L &&
                    error.string("requestId", 72, 72) == request.requestId && error.string("code", 1, 64) == "replay-identity-conflict" &&
                    error.string("disposition", 1, 64) == "permanent" && error.string("conflictScope", 1, 16) in setOf("request", "chunk", "sequence"))
                ReplayDeliveryOutcome.Blocked(ReplayBlockKind.CONFLICT)
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
