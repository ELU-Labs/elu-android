package dev.elu.analytics.internal.runtime.delivery

import dev.elu.analytics.internal.runtime.MAX_RUNTIME_DELIVERY_BYTES
import dev.elu.analytics.internal.runtime.RuntimeAcknowledgement
import dev.elu.analytics.internal.runtime.RuntimeQueuedRecord
import java.io.IOException
import java.util.Collections
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.floor

internal enum class BatchDeliveryStop {
    IDLE,
    DRAINED,
    BOUNDED,
    RETRY_SCHEDULED,
    AUTHORIZATION_UNAVAILABLE,
    PERMANENT_FAILURE,
    PROTOCOL_FAILURE,
    INTERNAL_FAILURE,
    COALESCED,
    CLOSED,
}

internal data class BatchDeliveryPassResult(
    val stop: BatchDeliveryStop,
    val networkRequests: Int = 0,
    val resolvedRecords: Int = 0,
)

/**
 * One serialized delivery state machine. Nothing in the public facade instantiates this type.
 * Queue ownership, authorization, clocks, scheduling, randomness, and HTTP are all injected.
 *
 * Every record is sent, however old: the server stores a terminal `record-too-old` outcome for a
 * record past the maximum age and moves its stream on. The only records resolved without the
 * server's outcome are a head too large for the authorized batch size and a head the server
 * refuses with `413` on its own; before either is deleted, [DeliveryStreams.rotate] moves delivery
 * to a new stream so the server is never left waiting on a sequence that will not arrive.
 */
internal class RuntimeBatchDeliveryCoordinator(
    private val authorization: V1BatchAuthorizationSnapshot,
    private val queue: RuntimeDeliveryQueue,
    private val transport: BatchHTTPTransport,
    private val clock: BatchDeliveryClock,
    private val scheduler: BatchRetryScheduler,
    private val jitter: BatchJitterSource,
    private val executor: Executor,
    /** Shared by every coordinator of one runtime; see [DeliveryStreams]. */
    private val streams: DeliveryStreams = DeliveryStreams(MemoryDeliveryStreamStore()),
    private val maximumNetworkRequestsPerPass: Int = 11,
    private val maximumLocalResolutionsPerPass: Int = 64,
) : AutoCloseable {
    private val stateLock = Any()
    private var state = CoordinatorState.IDLE
    private var followUpRequested = false
    private var retryTask: BatchScheduledTask? = null
    private var retryDeadlineNanos: Long = 0
    private var pendingRetryRecords: List<RuntimeQueuedRecord>? = null
    private var retryAttempt: Int = 0
    private var blockedRequestId: String? = null
    private var authorizationBlocked = false

    init {
        require(maximumNetworkRequestsPerPass in 1..64)
        require(maximumLocalResolutionsPerPass in 1..1_000)
    }

    /** Returns immediately when a running or delayed pass already owns delivery. */
    fun trigger(): SdkFuture<BatchDeliveryPassResult> {
        val future = SdkFuture<BatchDeliveryPassResult>()
        synchronized(stateLock) {
            when (state) {
                CoordinatorState.CLOSED -> future.complete(BatchDeliveryPassResult(BatchDeliveryStop.CLOSED))
                CoordinatorState.WAITING -> {
                    val now = clock.wallNow()
                    if (isAuthorizationExpired(now)) {
                        retryTask?.cancel()
                        retryTask = null
                        pendingRetryRecords = null
                        followUpRequested = false
                        state = CoordinatorState.IDLE
                        future.complete(BatchDeliveryPassResult(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE))
                    } else {
                        followUpRequested = true
                        future.complete(BatchDeliveryPassResult(BatchDeliveryStop.COALESCED))
                    }
                }
                CoordinatorState.RUNNING -> {
                    followUpRequested = true
                    future.complete(BatchDeliveryPassResult(BatchDeliveryStop.COALESCED))
                }
                CoordinatorState.IDLE -> {
                    state = CoordinatorState.RUNNING
                    submitPass(future)
                }
            }
        }
        return future
    }

    override fun close() {
        synchronized(stateLock) {
            if (state == CoordinatorState.CLOSED) return
            state = CoordinatorState.CLOSED
            followUpRequested = false
            retryTask?.cancel()
            retryTask = null
            pendingRetryRecords = null
        }
    }

    private fun submitPass(
        future: SdkFuture<BatchDeliveryPassResult>?,
        retryRecords: List<RuntimeQueuedRecord>? = null,
    ) {
        try {
            executor.execute { executePass(future, retryRecords) }
        } catch (error: RejectedExecutionException) {
            state = CoordinatorState.IDLE
            future?.complete(BatchDeliveryPassResult(BatchDeliveryStop.INTERNAL_FAILURE))
        }
    }

    private fun executePass(
        future: SdkFuture<BatchDeliveryPassResult>?,
        retryRecords: List<RuntimeQueuedRecord>? = null,
    ) {
        val result =
            try {
                runBoundedPass(retryRecords)
            } catch (_: Exception) {
                BatchDeliveryPassResult(BatchDeliveryStop.INTERNAL_FAILURE)
            }

        synchronized(stateLock) {
            if (state == CoordinatorState.RUNNING) {
                val mayFollowUp =
                    result.stop == BatchDeliveryStop.IDLE ||
                        result.stop == BatchDeliveryStop.DRAINED ||
                        result.stop == BatchDeliveryStop.BOUNDED
                if (followUpRequested && mayFollowUp) {
                    followUpRequested = false
                    submitPass(null)
                } else {
                    followUpRequested = false
                    state = CoordinatorState.IDLE
                }
            }
        }
        future?.complete(result)
    }

    private fun runBoundedPass(initialRetryRecords: List<RuntimeQueuedRecord>?): BatchDeliveryPassResult {
        var networkRequests = 0
        var resolvedRecords = 0
        var localResolutions = 0
        var retryRecords = initialRetryRecords

        while (
            networkRequests < maximumNetworkRequestsPerPass &&
            localResolutions < maximumLocalResolutionsPerPass
        ) {
            val now = clock.wallNow()
            if (authorizationBlocked || isAuthorizationExpired(now)) {
                return BatchDeliveryPassResult(
                    BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE,
                    networkRequests,
                    resolvedRecords,
                )
            }
            val queued =
                if (retryRecords == null) {
                    queue.peek(authorization.eventBatchCount, MAX_RUNTIME_DELIVERY_BYTES)
                } else {
                    val expected = checkNotNull(retryRecords)
                    val current = queue.peek(expected.size, MAX_RUNTIME_DELIVERY_BYTES)
                    if (current.map(::referenceOf) != expected.map(::referenceOf)) {
                        retryAttempt = 0
                        retryRecords = null
                        continue
                    }
                    expected
                }
            if (queued.isEmpty()) {
                return BatchDeliveryPassResult(
                    if (networkRequests == 0 && resolvedRecords == 0) BatchDeliveryStop.IDLE else BatchDeliveryStop.DRAINED,
                    networkRequests,
                    resolvedRecords,
                )
            }
            val head = queued.first()
            val streamId = streams.wireStreamId(head.streamId)
            val packed =
                if (retryRecords == null) {
                    V1BatchRequestCodec.packLargest(
                        queued,
                        now,
                        authorization.eventBatchCount,
                        authorization.eventBatchBytes,
                        streamId,
                    )
                } else {
                    BatchPackingResult.Packed(V1BatchRequestCodec.encodeExact(queued, now, streamId))
                }
            if (packed is BatchPackingResult.OversizedHead) {
                // Larger than any request this authorization allows: it can never be sent.
                resolvedRecords += discardHead(head)
                localResolutions += 1
                retryAttempt = 0
                blockedRequestId = null
                continue
            }
            val request = (packed as BatchPackingResult.Packed).request

            val networkBudget = NetworkBudget(maximumNetworkRequestsPerPass - networkRequests)
            val localBudget = LocalResolutionBudget(maximumLocalResolutionsPerPass - localResolutions)
            val step = deliver(request.records, networkBudget, localBudget)
            networkRequests += networkBudget.used
            localResolutions += localBudget.used
            resolvedRecords += step.resolvedRecords
            when (step) {
                is DeliveryStep.Success -> {
                    retryAttempt = 0
                    retryRecords = null
                    blockedRequestId = null
                }
                is DeliveryStep.Restart -> {
                    retryAttempt = 0
                    retryRecords = null
                    blockedRequestId = null
                }
                is DeliveryStep.Retry -> {
                    if (step.resolvedRecords > 0) retryAttempt = 0
                    return scheduleRetry(step, networkRequests, resolvedRecords)
                }
                is DeliveryStep.AuthorizationUnavailable -> {
                    authorizationBlocked = true
                    return BatchDeliveryPassResult(
                        BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE,
                        networkRequests,
                        resolvedRecords,
                    )
                }
                is DeliveryStep.Permanent -> {
                    blockedRequestId = step.requestId
                    return BatchDeliveryPassResult(BatchDeliveryStop.PERMANENT_FAILURE, networkRequests, resolvedRecords)
                }
                is DeliveryStep.Protocol -> {
                    blockedRequestId = step.requestId
                    return BatchDeliveryPassResult(BatchDeliveryStop.PROTOCOL_FAILURE, networkRequests, resolvedRecords)
                }
                is DeliveryStep.Bounded -> {
                    return BatchDeliveryPassResult(BatchDeliveryStop.BOUNDED, networkRequests, resolvedRecords)
                }
            }
        }
        return BatchDeliveryPassResult(BatchDeliveryStop.BOUNDED, networkRequests, resolvedRecords)
    }

    private fun deliver(
        records: List<RuntimeQueuedRecord>,
        networkBudget: NetworkBudget,
        localBudget: LocalResolutionBudget,
    ): DeliveryStep {
        val requestWall = clock.wallNow()
        if (isAuthorizationExpired(requestWall)) return DeliveryStep.AuthorizationUnavailable()
        val candidate = records
        // Looked up for every request: a discard earlier in this pass may have moved the stream.
        val request = V1BatchRequestCodec.encodeExact(candidate, requestWall, streams.wireStreamId(candidate.first().streamId))
        if (blockedRequestId == request.requestId) return DeliveryStep.Protocol(request.requestId)
        if (!networkBudget.take()) return DeliveryStep.Bounded()
        val response =
            try {
                transport.execute(
                    BatchHTTPRequest(
                        authorization.eventsEndpoint,
                        request.requestId,
                        "Bearer ${authorization.siteKey}",
                        request.bodyBytes(),
                    ),
                )
            } catch (_: IOException) {
                return retryStep(candidate, retryAfterMillis = 0, resolvedRecords = 0)
            } catch (_: Exception) {
                return DeliveryStep.Protocol(request.requestId)
            }
        val responseWall = clock.wallNow()
        val responseMonotonic = clock.monotonicNowNanos()

        val step = when {
            response.status in 200..299 -> {
                val resolution =
                    try {
                        V1BatchResponseCodec.parseAcknowledgement(response.bodyBytes(), request)
                    } catch (_: BatchProtocolException) {
                        return DeliveryStep.Protocol(request.requestId)
                    }
                if (resolution.hasRetryableRecords) {
                    val delay =
                        try {
                            response.retryAfter?.let {
                                RetryAfterParser.parseDelayMillis(it, responseWall.epochMillis)
                            } ?: 0
                        } catch (_: BatchProtocolException) {
                            return DeliveryStep.Protocol(request.requestId)
                        }
                    val resolvedPrefixCount = resolution.acknowledgement.references.size
                    val resolved = acknowledgeResolution(resolution)
                    val remaining = request.records.drop(resolvedPrefixCount)
                    val waitingHead = remaining.first()
                    if (
                        resolution.retryableCode == STREAM_GAP_CODE &&
                        streams.streamGapAnswered(
                            request.sourceStreamId,
                            request.streamId,
                            waitingHead.sequence,
                            waitingHead.recordId,
                            responseWall.epochMillis,
                        )
                    ) {
                        // Moved to a new stream: send the same head there now.
                        DeliveryStep.Restart(resolved)
                    } else {
                        retryStep(remaining, delay, resolved, responseMonotonic)
                    }
                } else {
                    if (response.retryAfter == null) {
                        DeliveryStep.Success(acknowledgeResolution(resolution))
                    } else {
                        DeliveryStep.Protocol(request.requestId)
                    }
                }
            }
            response.status == 413 -> {
                if (response.retryAfter != null) return DeliveryStep.Protocol(request.requestId)
                if (!validTransportError(response, request.requestId)) return DeliveryStep.Protocol(request.requestId)
                if (candidate.size == 1) {
                    // Refused on its own: no smaller request can carry it.
                    if (!localBudget.take()) return DeliveryStep.Bounded()
                    DeliveryStep.Success(discardHead(candidate.single()))
                } else {
                    val firstSize = (candidate.size + 1) / 2
                    when (val first = deliver(candidate.take(firstSize), networkBudget, localBudget)) {
                        is DeliveryStep.Success -> {
                            deliver(candidate.drop(firstSize), networkBudget, localBudget)
                                .withAdditionalResolvedRecords(first.resolvedRecords)
                        }
                        else -> first
                    }
                }
            }
            response.status == 401 || response.status == 403 -> {
                if (response.retryAfter != null || !validTransportError(response, request.requestId)) {
                    DeliveryStep.Protocol(request.requestId)
                } else {
                    DeliveryStep.AuthorizationUnavailable()
                }
            }
            response.status == 429 -> {
                if (!validTransportError(response, request.requestId) || response.retryAfter == null) {
                    foreignRetry(response, candidate, responseWall, responseMonotonic)
                } else {
                    val delay =
                        try {
                            RetryAfterParser.parseDelayMillis(response.retryAfter, responseWall.epochMillis)
                        } catch (_: BatchProtocolException) {
                            return foreignRetry(response, candidate, responseWall, responseMonotonic)
                        }
                    retryStep(candidate, delay, 0, responseMonotonic)
                }
            }
            response.status in 500..599 -> {
                if (!validTransportError(response, request.requestId)) {
                    foreignRetry(response, candidate, responseWall, responseMonotonic)
                } else {
                    val delay =
                        try {
                            response.retryAfter?.let {
                                RetryAfterParser.parseDelayMillis(it, responseWall.epochMillis)
                            } ?: 0
                        } catch (_: BatchProtocolException) {
                            return foreignRetry(response, candidate, responseWall, responseMonotonic)
                        }
                    retryStep(candidate, delay, 0, responseMonotonic)
                }
            }
            else -> DeliveryStep.Permanent(request.requestId)
        }
        return step
    }

    private fun validTransportError(
        response: BatchHTTPResponse,
        requestId: String,
    ): Boolean =
        try {
            V1BatchResponseCodec.validateTransportError(response.bodyBytes(), response.status, requestId)
            true
        } catch (_: BatchProtocolException) {
            false
        }

    /**
     * HTTP status is authoritative for 429 and 5xx. When the response is not the validated error
     * object (for example an intermediary's plain-text, HTML, empty or oversized body, or an
     * unreadable Retry-After), the same records are retried with backoff instead of blocking the
     * request. Every other status still fails closed.
     */
    private fun foreignRetry(
        response: BatchHTTPResponse,
        records: List<RuntimeQueuedRecord>,
        responseWall: BatchWallInstant,
        responseMonotonicNanos: Long,
    ): DeliveryStep.Retry =
        retryStep(
            records,
            RetryAfterParser.foreignDelayMillis(response.retryAfter, responseWall.epochMillis),
            resolvedRecords = 0,
            responseMonotonicNanos = responseMonotonicNanos,
        )

    private fun retryStep(
        records: List<RuntimeQueuedRecord>,
        retryAfterMillis: Long,
        resolvedRecords: Int,
        responseMonotonicNanos: Long = clock.monotonicNowNanos(),
    ): DeliveryStep.Retry =
        DeliveryStep.Retry(
            Collections.unmodifiableList(records.toList()),
            retryAfterMillis,
            responseMonotonicNanos,
            resolvedRecords,
        )

    private fun scheduleRetry(
        step: DeliveryStep.Retry,
        networkRequests: Int,
        resolvedRecords: Int,
    ): BatchDeliveryPassResult {
        val nowWall = clock.wallNow()
        val nowMonotonic = clock.monotonicNowNanos()
        if (isAuthorizationExpired(nowWall)) {
            authorizationBlocked = true
            return BatchDeliveryPassResult(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE, networkRequests, resolvedRecords)
        }
        val backoffMillis = backoffMillis(retryAttempt)
        retryAttempt = minOf(retryAttempt + 1, MAX_BACKOFF_ATTEMPT)
        val backoffNanos = millisToNanos(backoffMillis)
        val retryAfterNanos = millisToNanos(step.retryAfterMillis)
        val elapsedSinceResponse = elapsedNanos(step.responseMonotonicNanos, nowMonotonic)
        val remainingRetryAfter = subtractFloor(retryAfterNanos, elapsedSinceResponse)
        val waitNanos = maxOf(backoffNanos, remainingRetryAfter)
        val wakeDelayMillis = nanosToMillisCeiling(waitNanos)
        val expirationDelay = positiveDifferenceSaturated(authorization.expiresAtEpochMillisFloor, nowWall.epochMillis)
        if (expirationDelay <= 0 || wakeDelayMillis >= expirationDelay) {
            authorizationBlocked = true
            return BatchDeliveryPassResult(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE, networkRequests, resolvedRecords)
        }

        synchronized(stateLock) {
            if (state != CoordinatorState.RUNNING) {
                return BatchDeliveryPassResult(BatchDeliveryStop.CLOSED, networkRequests, resolvedRecords)
            }
            state = CoordinatorState.WAITING
            retryDeadlineNanos = nowMonotonic + waitNanos
            pendingRetryRecords = step.records
            retryTask =
                try {
                    scheduler.schedule(wakeDelayMillis, Runnable(::onRetryTimer))
                } catch (_: Exception) {
                    state = CoordinatorState.RUNNING
                    pendingRetryRecords = null
                    return BatchDeliveryPassResult(BatchDeliveryStop.INTERNAL_FAILURE, networkRequests, resolvedRecords)
                }
        }
        return BatchDeliveryPassResult(BatchDeliveryStop.RETRY_SCHEDULED, networkRequests, resolvedRecords)
    }

    private fun onRetryTimer() {
        synchronized(stateLock) {
            if (state != CoordinatorState.WAITING) return
            val nowWall = clock.wallNow()
            if (isAuthorizationExpired(nowWall)) {
                retryTask = null
                pendingRetryRecords = null
                followUpRequested = false
                authorizationBlocked = true
                state = CoordinatorState.IDLE
                return
            }
            val records = pendingRetryRecords
            val remaining = retryDeadlineNanos - clock.monotonicNowNanos()
            if (remaining > 0) {
                val nextDelayMillis = nanosToMillisCeiling(remaining)
                val expirationDelay =
                    positiveDifferenceSaturated(authorization.expiresAtEpochMillisFloor, nowWall.epochMillis)
                if (expirationDelay <= 0 || nextDelayMillis >= expirationDelay) {
                    retryTask = null
                    pendingRetryRecords = null
                    followUpRequested = false
                    authorizationBlocked = true
                    state = CoordinatorState.IDLE
                    return
                }
                retryTask =
                    try {
                        scheduler.schedule(nextDelayMillis, Runnable(::onRetryTimer))
                    } catch (_: Exception) {
                        state = CoordinatorState.IDLE
                        pendingRetryRecords = null
                        followUpRequested = false
                        null
                    }
                return
            }
            retryTask = null
            pendingRetryRecords = null
            followUpRequested = false
            state = CoordinatorState.RUNNING
            submitPass(null, records)
        }
    }

    private fun acknowledgeResolution(resolution: BatchAcknowledgementResolution): Int {
        val references = resolution.acknowledgement.references
        if (references.isEmpty()) return 0
        val resolved = acknowledgeReferences(references, resolution.acknowledgement.streamId)
        streams.resolvedThrough(resolution.acknowledgement.streamId, references.last().sequence)
        return resolved
    }

    /**
     * Deletes a head that can never be sent. The new stream is recorded first: if the process
     * dies before the delete, the head is offered again on the new stream and refused again.
     */
    private fun discardHead(record: RuntimeQueuedRecord): Int {
        streams.rotate(record.streamId)
        return acknowledgeReferences(listOf(referenceOf(record)), record.streamId)
    }

    private fun acknowledgeReferences(
        references: List<dev.elu.analytics.internal.runtime.RuntimeRecordReference>,
        streamId: String,
    ): Int =
        when (val result = queue.acknowledge(RuntimeAcknowledgement(streamId, references))) {
            is DeliveryQueueAcknowledgement.Deleted -> result.count
            DeliveryQueueAcknowledgement.AlreadyApplied -> 0
            DeliveryQueueAcknowledgement.Empty -> 0
        }

    private fun isAuthorizationExpired(now: BatchWallInstant): Boolean =
        dev.elu.analytics.internal.runtime.RuntimeRecordCodec.compareTimestamps(now.rfc3339, authorization.expiresAt) >= 0

    private fun backoffMillis(attempt: Int): Long {
        val exponent = minOf(attempt, MAX_BACKOFF_ATTEMPT)
        val base = minOf(BASE_BACKOFF_MILLIS * (1L shl exponent), MAX_BACKOFF_MILLIS)
        val unit = jitter.nextUnitDouble()
        if (!unit.isFinite() || unit < 0 || unit >= 1) throw IllegalStateException("Jitter source returned an invalid value")
        return base / 2L + floor((base - base / 2L) * unit).toLong()
    }

    private fun millisToNanos(milliseconds: Long): Long =
        if (milliseconds >= Long.MAX_VALUE / NANOS_PER_MILLI) Long.MAX_VALUE else milliseconds * NANOS_PER_MILLI

    /** Signed subtraction is intentionally wrap-safe for System.nanoTime intervals below 2^63 ns. */
    private fun elapsedNanos(
        earlier: Long,
        later: Long,
    ): Long = (later - earlier).coerceAtLeast(0)

    private fun subtractFloor(
        value: Long,
        elapsed: Long,
    ): Long = if (elapsed >= value) 0 else value - elapsed

    private fun positiveDifferenceSaturated(
        later: Long,
        earlier: Long,
    ): Long = if (later <= earlier) 0 else if (earlier < 0 && later > Long.MAX_VALUE + earlier) Long.MAX_VALUE else later - earlier

    private fun nanosToMillisCeiling(nanos: Long): Long =
        if (nanos == 0L) 0 else 1L + (nanos - 1L) / NANOS_PER_MILLI

    private enum class CoordinatorState {
        IDLE,
        RUNNING,
        WAITING,
        CLOSED,
    }

    private class NetworkBudget(
        private val maximum: Int,
    ) {
        var used: Int = 0
            private set

        fun take(): Boolean {
            if (used >= maximum) return false
            used += 1
            return true
        }
    }

    private class LocalResolutionBudget(
        private val maximum: Int,
    ) {
        var used: Int = 0
            private set

        fun take(): Boolean {
            if (used >= maximum) return false
            used += 1
            return true
        }
    }

    private sealed interface DeliveryStep {
        val resolvedRecords: Int

        data class Success(override val resolvedRecords: Int) : DeliveryStep

        data class Restart(override val resolvedRecords: Int) : DeliveryStep

        data class Retry(
            val records: List<RuntimeQueuedRecord>,
            val retryAfterMillis: Long,
            val responseMonotonicNanos: Long,
            override val resolvedRecords: Int,
        ) : DeliveryStep

        data class AuthorizationUnavailable(override val resolvedRecords: Int = 0) : DeliveryStep

        data class Permanent(
            val requestId: String,
            override val resolvedRecords: Int = 0,
        ) : DeliveryStep

        data class Protocol(
            val requestId: String,
            override val resolvedRecords: Int = 0,
        ) : DeliveryStep

        data class Bounded(override val resolvedRecords: Int = 0) : DeliveryStep
    }

    private fun DeliveryStep.withAdditionalResolvedRecords(additionalResolvedRecords: Int): DeliveryStep {
        if (additionalResolvedRecords == 0) return this
        val totalResolvedRecords = Math.addExact(resolvedRecords, additionalResolvedRecords)
        return when (this) {
            is DeliveryStep.Success -> copy(resolvedRecords = totalResolvedRecords)
            is DeliveryStep.Restart -> copy(resolvedRecords = totalResolvedRecords)
            is DeliveryStep.Retry -> copy(resolvedRecords = totalResolvedRecords)
            is DeliveryStep.AuthorizationUnavailable -> copy(resolvedRecords = totalResolvedRecords)
            is DeliveryStep.Permanent -> copy(resolvedRecords = totalResolvedRecords)
            is DeliveryStep.Protocol -> copy(resolvedRecords = totalResolvedRecords)
            is DeliveryStep.Bounded -> copy(resolvedRecords = totalResolvedRecords)
        }
    }

    private companion object {
        /** The retryable outcome code for a record past the stream's next expected sequence. */
        const val STREAM_GAP_CODE = "stream-gap"
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val MAX_BACKOFF_ATTEMPT = 6
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
