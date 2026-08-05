package dev.elu.analytics.internal.runtime.delivery

import dev.elu.analytics.internal.runtime.MAX_RUNTIME_DELIVERY_BYTES
import dev.elu.analytics.internal.runtime.RuntimeAcknowledgement
import dev.elu.analytics.internal.runtime.RuntimeQueuedRecord
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CompletableFuture
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
 */
internal class RuntimeBatchDeliveryCoordinator(
    private val authorization: V1BatchAuthorizationSnapshot,
    private val queue: RuntimeDeliveryQueue,
    private val transport: BatchHTTPTransport,
    private val clock: BatchDeliveryClock,
    private val scheduler: BatchRetryScheduler,
    private val jitter: BatchJitterSource,
    private val executor: Executor,
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
    fun trigger(): CompletableFuture<BatchDeliveryPassResult> {
        val future = CompletableFuture<BatchDeliveryPassResult>()
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
                    } else if (pendingRetryRecords?.firstOrNull()?.let { isAged(it, now) } == true) {
                        retryTask?.cancel()
                        retryTask = null
                        val records = pendingRetryRecords
                        pendingRetryRecords = null
                        followUpRequested = false
                        state = CoordinatorState.RUNNING
                        submitPass(future, records)
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
        future: CompletableFuture<BatchDeliveryPassResult>?,
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
        future: CompletableFuture<BatchDeliveryPassResult>?,
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
            if (isAged(head, now)) {
                resolvedRecords += acknowledgeExact(listOf(head))
                localResolutions += 1
                retryAttempt = 0
                blockedRequestId = null
                continue
            }

            val packed =
                if (retryRecords == null) {
                    V1BatchRequestCodec.packLargest(
                        queued,
                        now,
                        authorization.eventBatchCount,
                        authorization.eventBatchBytes,
                    )
                } else {
                    BatchPackingResult.Packed(V1BatchRequestCodec.encodeExact(queued, now))
                }
            if (packed is BatchPackingResult.OversizedHead) {
                resolvedRecords += acknowledgeReferences(listOf(packed.reference), head.streamId)
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
        val firstAgedIndex = records.indexOfFirst { record -> isAged(record, requestWall) }
        if (firstAgedIndex == 0) {
            if (!localBudget.take()) return DeliveryStep.Bounded()
            return DeliveryStep.Restart(acknowledgeExact(listOf(records.first())))
        }
        val candidate = if (firstAgedIndex > 0) records.take(firstAgedIndex) else records
        val stoppedBeforeAgedRecord = candidate.size != records.size
        val request = V1BatchRequestCodec.encodeExact(candidate, requestWall)
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
                    retryStep(request.records.drop(resolvedPrefixCount), delay, resolved, responseMonotonic)
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
                    if (!localBudget.take()) return DeliveryStep.Bounded()
                    DeliveryStep.Success(acknowledgeExact(candidate))
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
                    DeliveryStep.Protocol(request.requestId)
                } else {
                    val delay =
                        try {
                            RetryAfterParser.parseDelayMillis(response.retryAfter, responseWall.epochMillis)
                        } catch (_: BatchProtocolException) {
                            return DeliveryStep.Protocol(request.requestId)
                        }
                    retryStep(candidate, delay, 0, responseMonotonic)
                }
            }
            response.status in 500..599 -> {
                if (!validTransportError(response, request.requestId)) {
                    DeliveryStep.Protocol(request.requestId)
                } else {
                    val delay =
                        try {
                            response.retryAfter?.let {
                                RetryAfterParser.parseDelayMillis(it, responseWall.epochMillis)
                            } ?: 0
                        } catch (_: BatchProtocolException) {
                            return DeliveryStep.Protocol(request.requestId)
                        }
                    retryStep(candidate, delay, 0, responseMonotonic)
                }
            }
            else -> DeliveryStep.Permanent(request.requestId)
        }
        return if (stoppedBeforeAgedRecord && step is DeliveryStep.Success) {
            DeliveryStep.Restart(step.resolvedRecords)
        } else {
            step
        }
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
        val delayMillis = nanosToMillisCeiling(waitNanos)
        val ageDelayMillis = ageBoundaryDelayMillis(step.records.first(), nowWall)
        val wakeDelayMillis = minOf(delayMillis, ageDelayMillis)
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
            if (records?.firstOrNull()?.let { isAged(it, nowWall) } == true) {
                retryTask = null
                pendingRetryRecords = null
                followUpRequested = false
                state = CoordinatorState.RUNNING
                submitPass(null, records)
                return
            }
            val remaining = retryDeadlineNanos - clock.monotonicNowNanos()
            if (remaining > 0) {
                val retryDelayMillis = nanosToMillisCeiling(remaining)
                val ageDelayMillis = records?.firstOrNull()?.let { ageBoundaryDelayMillis(it, nowWall) } ?: Long.MAX_VALUE
                val nextDelayMillis = minOf(retryDelayMillis, ageDelayMillis)
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
        if (resolution.acknowledgement.references.isEmpty()) return 0
        return acknowledgeReferences(
            resolution.acknowledgement.references,
            resolution.acknowledgement.streamId,
        )
    }

    private fun acknowledgeExact(records: List<RuntimeQueuedRecord>): Int =
        acknowledgeReferences(records.map(::referenceOf), records.first().streamId)

    private fun acknowledgeReferences(
        references: List<dev.elu.analytics.internal.runtime.RuntimeRecordReference>,
        streamId: String,
    ): Int =
        when (val result = queue.acknowledge(RuntimeAcknowledgement(streamId, references))) {
            is DeliveryQueueAcknowledgement.Deleted -> result.count
            DeliveryQueueAcknowledgement.AlreadyApplied -> 0
            DeliveryQueueAcknowledgement.Empty -> 0
        }

    private fun isAged(
        record: RuntimeQueuedRecord,
        now: BatchWallInstant,
    ): Boolean =
        dev.elu.analytics.internal.runtime.RuntimeRecordCodec.compareElapsedSeconds(
            now.rfc3339,
            occurredAtOf(record),
            MAX_RUNTIME_RECORD_AGE_SECONDS,
        ) >= 0

    private fun isAuthorizationExpired(now: BatchWallInstant): Boolean =
        dev.elu.analytics.internal.runtime.RuntimeRecordCodec.compareTimestamps(now.rfc3339, authorization.expiresAt) >= 0

    private fun ageBoundaryDelayMillis(
        record: RuntimeQueuedRecord,
        now: BatchWallInstant,
    ): Long =
        dev.elu.analytics.internal.runtime.RuntimeRecordCodec.elapsedBoundaryDelayMillisCeiling(
            now.rfc3339,
            occurredAtOf(record),
            MAX_RUNTIME_RECORD_AGE_SECONDS,
        )

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
        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val MAX_BACKOFF_ATTEMPT = 6
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
