package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.V1FlagAuthorizationResolution
import dev.elu.analytics.internal.config.V1FlagProjectionRejection
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

/** Raw-byte, asynchronous boundary. This internal runtime intentionally ships no concrete conformer. */
internal fun interface FlagTransport {
    fun send(request: FlagTransportRequest): CompletionStage<ByteArray>
}

internal data class FlagTransportRequest(
    val endpoint: URI,
    val canonicalBody: ByteArray,
)

internal interface FlagClock {
    fun wallNowEpochMillis(): Long

    /** Sleep-inclusive monotonic nanoseconds. */
    fun monotonicNowNanos(): Long
}

internal fun interface FlagOpaqueIdSource {
    fun next(): String
}

/**
 * Internal, deliberately unwired serialized client. The existing runtime owner remains the only
 * SQLite authority; transport never executes inside its transactions.
 */
internal class AndroidFeatureFlagClient(
    private val owner: RuntimeQueueOwner,
    private val versions: RuntimeVersions,
    private val transport: FlagTransport,
    private val clock: FlagClock,
    private val requestIds: FlagOpaqueIdSource,
    private val storeEpochs: FlagOpaqueIdSource,
) : AutoCloseable {
    private val lane: ExecutorService =
        Executors.newSingleThreadExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "elu-feature-flags").apply { isDaemon = true }
            },
        )

    /** Touched only on [lane]. */
    private var initializationFailure: Throwable? = null
    private var closed: Boolean = false
    private var clockFailed: Boolean = false
    private var lastWallEpochMillis: Long? = null
    private var lastMonotonicNanos: Long? = null
    private var configLease: ConfigLease? = null
    private var cacheLease: CacheLease? = null
    private var inFlight: InFlight? = null
    private var pending: Pending? = null

    init {
        // Construction is explicit and internal. Ordinary SDK startup never reaches this call.
        lane.execute {
            try {
                owner.ensureFeatureFlagRuntime().await()
            } catch (error: Throwable) {
                initializationFailure = error
            }
        }
    }

    fun applyConfiguration(configBody: String?): CompletableFuture<V1FlagAuthorizationResolution> {
        val result = CompletableFuture<V1FlagAuthorizationResolution>()
        execute(result) {
            if (closed) {
                result.complete(
                    V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.STALE),
                )
                return@execute
            }
            val failure = initializationFailure
            if (failure != null) throw failure
            val sample =
                try {
                    sampleClock()
                } catch (_: Throwable) {
                    configLease = null
                    cacheLease = null
                    result.complete(
                        V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.STORAGE),
                    )
                    return@execute
                }
            val resolution = owner.applyFeatureFlagConfiguration(configBody, sample.wallEpochMillis).await()
            when (resolution) {
                is V1FlagAuthorizationResolution.Allowed -> {
                    val retained = configLease
                    if (retained?.authorization != resolution.authorization) {
                        invalidateRunning(FlagReloadResult.Stale)
                        pending?.complete(FlagReloadResult.Stale)
                        pending = null
                        cacheLease = null
                        val deadline = deadlineFor(resolution.authorization.witness.configExpiresAt, sample)
                            ?: throw FlagProtocolException("Flag config has no positive monotonic lifetime")
                        configLease = ConfigLease(resolution.authorization, deadline)
                    }
                }
                is V1FlagAuthorizationResolution.Restricted -> {
                    configLease = null
                    cacheLease = null
                    invalidateRunning(FlagReloadResult.Stale)
                    pending?.complete(FlagReloadResult.Stale)
                    pending = null
                    if (resolution.reason == V1FlagProjectionRejection.STORAGE) poisonClock()
                }
            }
            result.complete(resolution)
        }
        return result
    }

    fun reload(): CompletableFuture<FlagReloadResult> {
        val result = CompletableFuture<FlagReloadResult>()
        execute(result) { considerReload(result) }
        return result
    }

    fun read(key: String): CompletableFuture<FlagReadResult> {
        val result = CompletableFuture<FlagReadResult>()
        execute(result) {
            if (initializationFailure != null || closed || clockFailed) {
                result.complete(FlagReadResult.Missing)
                return@execute
            }
            val sample =
                try {
                    sampleClock()
                } catch (_: Throwable) {
                    result.complete(FlagReadResult.Missing)
                    return@execute
                }
            val lease = usableConfigLease(sample)
            if (lease == null) {
                result.complete(FlagReadResult.Missing)
                return@execute
            }
            if (!usableCacheLease(sample, lease)) {
                result.complete(FlagReadResult.Missing)
                return@execute
            }
            val read =
                try {
                    owner.readFeatureFlag(versions, key, sample.wallEpochMillis).await()
                } catch (_: Throwable) {
                    FlagReadResult.Missing
                }
            when (read) {
                is FlagReadResult.Found -> {
                    if (!installCacheLease(read.cacheLeaseToken, read.responseExpiresAt, sample, lease)) {
                        result.complete(FlagReadResult.Missing)
                        return@execute
                    }
                }
                is FlagReadResult.CacheMiss -> {
                    installCacheLease(read.cacheLeaseToken, read.responseExpiresAt, sample, lease)
                    result.complete(FlagReadResult.Missing)
                    return@execute
                }
                is FlagReadResult.Restricted -> {
                    if (read.reason == FlagRestrictionReason.WALL_ROLLBACK) poisonClock()
                    result.complete(FlagReadResult.Missing)
                    return@execute
                }
                FlagReadResult.Terminal -> {
                    poisonClock()
                    result.complete(FlagReadResult.Missing)
                    return@execute
                }
                FlagReadResult.Missing -> Unit
            }
            result.complete(read)
        }
        return result
    }

    override fun close() {
        val rejected = FlagReloadResult.Failed("client-closed")
        try {
            lane.execute {
                if (closed) return@execute
                closed = true
                pending?.complete(rejected)
                pending = null
                inFlight?.complete(rejected)
                inFlight = null
                lane.shutdown()
            }
        } catch (_: RejectedExecutionException) {
            // Already closed.
        }
    }

    private fun considerReload(target: CompletableFuture<FlagReloadResult>) {
        if (closed) {
            target.complete(FlagReloadResult.Failed("client-closed"))
            return
        }
        initializationFailure?.let {
            target.complete(FlagReloadResult.Failed("storage-unavailable"))
            return
        }
        val sample =
            try {
                sampleClock()
            } catch (_: Throwable) {
                target.complete(FlagReloadResult.Failed("clock-unavailable"))
                return
            }
        val lease = usableConfigLease(sample)
        if (lease == null) {
            target.complete(FlagReloadResult.Failed("authorization-unavailable"))
            return
        }
        val snapshot =
            try {
                owner.snapshotFeatureFlagReload(versions).await()
            } catch (_: Throwable) {
                null
            }
        if (snapshot == null) {
            target.complete(FlagReloadResult.Failed("authorization-unavailable"))
            return
        }
        val running = inFlight
        if (running != null) {
            if (!running.invalidated && running.snapshot == snapshot) {
                running.targets += target
                return
            }
            running.invalidate(FlagReloadResult.Stale)
            val queued = pending
            if (queued != null && queued.snapshot == snapshot) {
                queued.targets += target
            } else {
                queued?.complete(FlagReloadResult.Stale)
                pending = Pending(snapshot, mutableListOf(target))
            }
            return
        }
        startReload(snapshot, mutableListOf(target))
    }

    private fun startReload(
        snapshotHint: FlagReloadWitnessSnapshot,
        targets: MutableList<CompletableFuture<FlagReloadResult>>,
    ) {
        val sample =
            try {
                sampleClock()
            } catch (_: Throwable) {
                targets.complete(FlagReloadResult.Failed("clock-unavailable"))
                startPendingIfPresent()
                return
            }
        val lease = usableConfigLease(sample)
        if (lease == null) {
            targets.complete(FlagReloadResult.Failed("authorization-unavailable"))
            startPendingIfPresent()
            return
        }
        val requestId: String
        val storeEpoch: String
        try {
            requestId = requestIds.next()
            storeEpoch = storeEpochs.next()
        } catch (_: Throwable) {
            targets.complete(FlagReloadResult.Failed("identifier-unavailable"))
            startPendingIfPresent()
            return
        }
        val begun =
            try {
                owner.beginFeatureFlagReload(
                    versions,
                    requestId,
                    storeEpoch,
                    sample.wallEpochMillis,
                ).await()
            } catch (_: Throwable) {
                targets.complete(FlagReloadResult.Failed("storage-unavailable"))
                startPendingIfPresent()
                return
            }
        when (begun) {
            is FlagBeginResult.Restricted -> {
                if (begun.reason == FlagRestrictionReason.WALL_ROLLBACK) poisonClock()
                targets.complete(FlagReloadResult.Failed("restricted:${begun.reason.name.lowercase()}"))
                startPendingIfPresent()
            }
            FlagBeginResult.Terminal -> {
                targets.complete(FlagReloadResult.Terminal)
                startPendingIfPresent()
            }
            is FlagBeginResult.Begun -> {
                val actualSnapshot = FlagReloadWitnessSnapshot(begun.request.authorization, begun.request.witness)
                // The hint is only an arbitration read. Begin's transaction is the authority.
                if (actualSnapshot != snapshotHint) {
                    pending?.let { queued ->
                        if (queued.snapshot == actualSnapshot) {
                            targets += queued.targets
                            pending = null
                        }
                    }
                }
                val preSendSample =
                    try {
                        sampleClock()
                    } catch (_: Throwable) {
                        targets.complete(FlagReloadResult.Failed("clock-unavailable"))
                        startPendingIfPresent()
                        return
                    }
                if (usableConfigLease(preSendSample) == null) {
                    targets.complete(FlagReloadResult.Stale)
                    startPendingIfPresent()
                    return
                }
                val preSend =
                    try {
                        owner.authorizeFeatureFlagSend(
                            begun.request,
                            versions,
                            preSendSample.wallEpochMillis,
                        ).await()
                    } catch (_: Throwable) {
                        null
                    }
                when (preSend) {
                    FlagPreSendResult.Current -> Unit
                    is FlagPreSendResult.Restricted -> {
                        if (preSend.reason == FlagRestrictionReason.WALL_ROLLBACK) poisonClock()
                        targets.complete(FlagReloadResult.Restricted(preSend.reason))
                        startPendingIfPresent()
                        return
                    }
                    FlagPreSendResult.Stale -> {
                        targets.complete(FlagReloadResult.Stale)
                        startPendingIfPresent()
                        return
                    }
                    FlagPreSendResult.Terminal -> {
                        targets.complete(FlagReloadResult.Terminal)
                        startPendingIfPresent()
                        return
                    }
                    null -> {
                        targets.complete(FlagReloadResult.Failed("storage-unavailable"))
                        startPendingIfPresent()
                        return
                    }
                }
                val running = InFlight(actualSnapshot, begun.request, targets)
                inFlight = running
                val stage =
                    try {
                        transport.send(
                            FlagTransportRequest(
                                begun.request.authorization.witness.endpoint,
                                begun.request.request.canonicalBytes.copyOf(),
                            ),
                        )
                    } catch (_: Throwable) {
                        null
                    }
                if (stage == null) {
                    finish(running, FlagReloadResult.Failed("transport-failure"))
                    return
                }
                stage.whenComplete { bytes, error ->
                    enqueueTransportCompletion(running, bytes, error)
                }
            }
        }
    }

    private fun enqueueTransportCompletion(
        running: InFlight,
        bytes: ByteArray?,
        error: Throwable?,
    ) {
        try {
            lane.execute { completeTransport(running, bytes, error) }
        } catch (_: RejectedExecutionException) {
            running.complete(FlagReloadResult.Failed("client-closed"))
        }
    }

    private fun completeTransport(
        running: InFlight,
        bytes: ByteArray?,
        error: Throwable?,
    ) {
        if (inFlight !== running || closed) return
        if (running.invalidated) {
            finish(running, FlagReloadResult.Stale)
            return
        }
        if (error != null || bytes == null) {
            finish(running, FlagReloadResult.Failed("transport-failure"))
            return
        }
        val response =
            try {
                FlagCodec.decodeResponse(bytes)
            } catch (_: FlagProtocolException) {
                finish(running, FlagReloadResult.Failed("protocol-failure"))
                return
            }
        val commitSample =
            try {
                sampleClock()
            } catch (_: Throwable) {
                finish(running, FlagReloadResult.Failed("clock-unavailable"))
                return
            }
        val lease = usableConfigLease(commitSample)
        if (lease == null) {
            finish(running, FlagReloadResult.Stale)
            return
        }
        val committed =
            try {
                owner.commitFeatureFlagReload(
                    running.begun,
                    versions,
                    response,
                    commitSample.wallEpochMillis,
                ).await()
            } catch (_: Throwable) {
                FlagReloadResult.Failed("storage-unavailable")
            }
        if (committed !is FlagReloadResult.Updated) {
            if (
                committed is FlagReloadResult.Restricted &&
                committed.reason == FlagRestrictionReason.WALL_ROLLBACK
            ) {
                running.complete(committed)
                poisonClock()
            }
            finish(running, committed)
            return
        }
        val responseDeadline = deadlineFor(response.expiresAt, commitSample)
        if (responseDeadline == null) {
            finish(running, FlagReloadResult.Stale)
            return
        }
        val candidateDeadline = minOf(lease.deadlineNanos, responseDeadline)
        val finalSample =
            try {
                sampleClock()
            } catch (_: Throwable) {
                finish(running, FlagReloadResult.Failed("clock-unavailable"))
                return
            }
        if (usableConfigLease(finalSample) == null) {
            finish(running, FlagReloadResult.Stale)
            return
        }
        val finalized =
            try {
                owner.finalizeFeatureFlagReload(
                    running.begun,
                    versions,
                    response,
                    finalSample.wallEpochMillis,
                ).await()
            } catch (_: Throwable) {
                FlagReloadResult.Failed("storage-unavailable")
            }
        if (finalized is FlagReloadResult.Updated) {
            val token = finalized.cacheLeaseToken
            if (token == null) {
                finish(running, FlagReloadResult.Stale)
                return
            }
            if (cacheLease?.token != token) cacheLease = CacheLease(token, candidateDeadline)
            if (finalSample.monotonicNanos >= candidateDeadline) {
                expireCacheLease(token, lease, finalSample)
                finish(running, FlagReloadResult.Stale)
                return
            }
        }
        if (
            finalized is FlagReloadResult.Restricted &&
            finalized.reason == FlagRestrictionReason.WALL_ROLLBACK
        ) {
            running.complete(finalized)
            poisonClock()
        }
        finish(running, finalized)
    }

    private fun finish(running: InFlight, result: FlagReloadResult) {
        if (inFlight !== running) return
        inFlight = null
        running.complete(result)
        startPendingIfPresent()
    }

    private fun startPendingIfPresent() {
        if (inFlight != null) return
        val queued = pending ?: return
        pending = null
        // Refresh the hint before allocating; context/config may have changed again while awaiting.
        val refreshed =
            try {
                owner.snapshotFeatureFlagReload(versions).await()
            } catch (_: Throwable) {
                null
            }
        if (refreshed == null) {
            queued.complete(FlagReloadResult.Failed("authorization-unavailable"))
            return
        }
        startReload(refreshed, queued.targets)
    }

    private fun sampleClock(): ClockSample {
        if (clockFailed) throw IllegalStateException("Flag clock is permanently unavailable")
        val wall: Long
        val monotonic: Long
        try {
            wall = clock.wallNowEpochMillis()
            monotonic = clock.monotonicNowNanos()
        } catch (error: Throwable) {
            poisonClock()
            throw error
        }
        val previousWall = lastWallEpochMillis
        val previousMonotonic = lastMonotonicNanos
        if (
            wall !in -FLAG_MAX_SAFE_INTEGER..FLAG_MAX_SAFE_INTEGER ||
            monotonic < 0L ||
            (previousWall != null && wall < previousWall) ||
            (previousMonotonic != null && monotonic < previousMonotonic)
        ) {
            poisonClock()
            throw IllegalStateException("Flag clock regressed or left its supported domain")
        }
        lastWallEpochMillis = wall
        lastMonotonicNanos = monotonic
        return ClockSample(wall, monotonic)
    }

    /**
     * A monotonic lease is an owner-local validity ceiling, but observing that ceiling must still
     * commit the exact durable authorization transition before this owner reports it unavailable.
     */
    private fun usableConfigLease(sample: ClockSample): ConfigLease? {
        val lease = configLease ?: return null
        if (sample.monotonicNanos < lease.deadlineNanos) return lease
        val restriction =
            try {
                owner.expireFeatureFlagAuthorization(lease.authorization, sample.wallEpochMillis).await()
            } catch (_: Throwable) {
                // Fail closed for this call, but retain the expired lease so a later call retries
                // the required durable transition rather than treating an uncommitted observation
                // as final.
                return null
            }
        if (restriction == V1FlagProjectionRejection.STORAGE) poisonClock()
        configLease = null
        cacheLease = null
        invalidateRunning(FlagReloadResult.Stale)
        pending?.complete(FlagReloadResult.Stale)
        pending = null
        return null
    }

    /** Commits an exact cache-generation expiry before the monotonic deadline becomes a miss. */
    private fun usableCacheLease(
        sample: ClockSample,
        authorization: ConfigLease,
    ): Boolean {
        val current = cacheLease ?: return true
        if (sample.monotonicNanos < current.deadlineNanos) return true
        return expireCacheLease(current.token, authorization, sample)
    }

    private fun expireCacheLease(
        token: FlagCacheLeaseToken,
        authorization: ConfigLease,
        sample: ClockSample,
    ): Boolean {
        val expired =
            try {
                owner.expireFeatureFlagCache(
                    authorization.authorization,
                    versions,
                    token,
                    sample.wallEpochMillis,
                ).await()
            } catch (_: Throwable) {
                // Retain the expired token so a later call retries the durable transition.
                return false
            }
        cacheLease = null
        when (expired) {
            FlagCacheExpiryStoreResult.Expired,
            FlagCacheExpiryStoreResult.Stale,
            -> Unit
            is FlagCacheExpiryStoreResult.Restricted -> {
                if (expired.reason == V1FlagProjectionRejection.STORAGE) poisonClock()
                if (expired.reason == V1FlagProjectionRejection.EXPIRED) configLease = null
            }
            FlagCacheExpiryStoreResult.Terminal -> {
                configLease = null
                poisonClock()
            }
        }
        return false
    }

    private fun deadlineFor(expiry: FlagExactInstant, sample: ClockSample): Long? {
        val now = FlagExactInstant.fromEpochMillis(sample.wallEpochMillis)
        val remaining = expiry.elapsedNanosecondsFloorSince(now) ?: return null
        if (remaining <= 0L) return null
        return saturatingAdd(sample.monotonicNanos, remaining)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun installCacheLease(
        token: FlagCacheLeaseToken,
        responseExpiresAt: FlagExactInstant,
        sample: ClockSample,
        authorization: ConfigLease,
    ): Boolean {
        val retained = cacheLease
        if (retained?.token == token) return sample.monotonicNanos < retained.deadlineNanos
        val responseDeadline = deadlineFor(responseExpiresAt, sample) ?: return false
        val deadline = minOf(authorization.deadlineNanos, responseDeadline)
        if (sample.monotonicNanos >= deadline) return false
        cacheLease = CacheLease(token, deadline)
        return true
    }

    private fun invalidateRunning(result: FlagReloadResult) {
        inFlight?.invalidate(result)
    }

    private fun poisonClock() {
        if (clockFailed) return
        clockFailed = true
        configLease = null
        cacheLease = null
        invalidateRunning(FlagReloadResult.Stale)
        pending?.complete(FlagReloadResult.Stale)
        pending = null
    }

    private fun <T> execute(
        result: CompletableFuture<T>,
        block: () -> Unit,
    ) {
        try {
            lane.execute {
                try {
                    block()
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            result.completeExceptionally(error)
        }
    }

    private fun <T> Future<T>.await(): T = get()

    private data class ClockSample(
        val wallEpochMillis: Long,
        val monotonicNanos: Long,
    )

    private data class ConfigLease(
        val authorization: FlagAuthorizationSnapshot,
        val deadlineNanos: Long,
    )

    private data class CacheLease(
        val token: FlagCacheLeaseToken,
        val deadlineNanos: Long,
    )

    private class InFlight(
        val snapshot: FlagReloadWitnessSnapshot,
        val begun: FlagBegunRequest,
        val targets: MutableList<CompletableFuture<FlagReloadResult>>,
    ) {
        var invalidated: Boolean = false
            private set

        fun invalidate(result: FlagReloadResult) {
            invalidated = true
            complete(result)
        }

        fun complete(result: FlagReloadResult) {
            targets.forEach { it.complete(result) }
            targets.clear()
        }
    }

    private class Pending(
        val snapshot: FlagReloadWitnessSnapshot,
        val targets: MutableList<CompletableFuture<FlagReloadResult>>,
    ) {
        fun complete(result: FlagReloadResult) {
            targets.forEach { it.complete(result) }
            targets.clear()
        }
    }

    private fun MutableList<CompletableFuture<FlagReloadResult>>.complete(result: FlagReloadResult) {
        forEach { it.complete(result) }
        clear()
    }
}
