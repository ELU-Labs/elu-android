package dev.elu.analytics.internal.config

import android.os.SystemClock

internal interface V2ConfigClock {
    fun wallNowEpochMillis(): Long

    /** Sleep-inclusive elapsed time; do not use uptime or process CPU time. */
    fun monotonicNowNanos(): Long
}

internal object AndroidV2ConfigClock : V2ConfigClock {
    override fun wallNowEpochMillis(): Long = System.currentTimeMillis()

    override fun monotonicNowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** Raw data and its retained clock deadline; no channel authority is conveyed. */
internal data class V2ConfigLeaseSnapshot(
    val body: String,
    val expiresAt: V1ExactTimestamp,
    val monotonicDeadlineNanos: Long,
)

internal enum class V2ConfigSourceFailure {
    TRANSPORT,
    HTTP,
    CONFIG,
    INVALID_VALIDITY,
    CLOCK,
    CLOSED,
}

internal sealed interface V2ConfigSourceResult {
    /** Validated data, including disabled/revoked documents. This grants no channel authority. */
    data class Document(val body: String, val status: V1ConfigStatus) : V2ConfigSourceResult

    /** The caller must withdraw its previously supplied configuration. */
    data class Unavailable(
        val reason: V2ConfigSourceFailure,
        val configRejection: V1ConfigRejection? = null,
    ) : V2ConfigSourceResult

    /** An older or canceled response must not overwrite or withdraw a newer decision. */
    data object Superseded : V2ConfigSourceResult
}

/**
 * Owned v2 configuration acquisition behind the public runtime lifecycle.
 *
 * This blocking source retains only an in-memory document lease and the manager's ordering
 * boundary. Consumers must still authorize against their current durable privacy and identity.
 * Replay capability proofs remain empty. No disk or provider config can revive a failed fetch.
 *
 * The next composition layer owns background execution, renewal, and an independent expiry
 * task that supplies null to the facade even if a fetch hangs. It must ignore Superseded results,
 * pass Document bodies (including restrictions) through, and withdraw on Unavailable. Retained
 * strings are data, not reusable authority: currentDocument must be checked before handoff.
 */
internal class V2ConfigSource(
    configHost: String,
    siteKey: String,
    transport: V2ConfigTransport? = null,
    private val clock: V2ConfigClock = AndroidV2ConfigClock,
    debuggable: Boolean = false,
) : AutoCloseable {
    private val endpoint = V2ConfigEndpoint.build(configHost, siteKey, debuggable)
    private val transport = transport ?: HttpURLConnectionV2ConfigTransport(debuggable = debuggable)
    private val manager = V1ConfigManager()
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var clockFailed = false
    private var lastWall = 0L
    private var lastMonotonic = 0L
    private var hasClockSample = false
    private var lease: Lease? = null
    /** Retained across withdrawal so re-reading identical data cannot renew a spent lease. */
    private var leaseBoundary: LeaseBoundary? = null

    /** Invoked off the main thread. Concurrent older completions are ignored. */
    fun refresh(): V2ConfigSourceResult {
        val attempt = synchronized(lock) {
            if (closed) return unavailable(V2ConfigSourceFailure.CLOSED)
            val sample = sampleClock() ?: return unavailable(V2ConfigSourceFailure.CLOCK)
            expireLease(sample)
            generation = Math.incrementExact(generation)
            Attempt(generation, sample)
        }
        val response = try {
            transport.fetch(endpoint)
        } catch (_: Exception) {
            return synchronized(lock) {
                if (closed || attempt.generation != generation) V2ConfigSourceResult.Superseded
                else unavailable(V2ConfigSourceFailure.TRANSPORT)
            }
        }
        return synchronized(lock) {
            if (closed || attempt.generation != generation) return@synchronized V2ConfigSourceResult.Superseded
            val sample = sampleClock() ?: return@synchronized unavailable(V2ConfigSourceFailure.CLOCK)
            expireLease(sample)
            if (response.status != 200) return@synchronized unavailable(V2ConfigSourceFailure.HTTP)
            val body = response.body
            if (body == null || body.toByteArray(Charsets.UTF_8).size > V2_CONFIG_MAXIMUM_RESPONSE_BYTES) {
                manager.install(null, sample.wall)
                return@synchronized unavailable(V2ConfigSourceFailure.CONFIG, V1ConfigRejection.MALFORMED)
            }
            val parsed = try {
                V1ConfigJson.parseConfig(body)
            } catch (_: V1UnsupportedConfigSchemaException) {
                return@synchronized rejectDocument(body, sample, V1ConfigRejection.UNSUPPORTED_SCHEMA)
            } catch (_: V1MalformedConfigException) {
                return@synchronized rejectDocument(body, sample, V1ConfigRejection.MALFORMED)
            }
            if (parsed.schemaVersion != V2_CONFIG_SCHEMA_VERSION) {
                manager.install(null, sample.wall)
                return@synchronized unavailable(V2ConfigSourceFailure.CONFIG, V1ConfigRejection.UNSUPPORTED_SCHEMA)
            }
            val now = V1ExactTimestamp.fromEpochMillis(sample.wall)
            val issued = parsed.issuedAtInstant
            val maximumExpiry = V1ExactTimestamp.fromEpochSecondAndFraction(
                Math.addExact(issued.epochWholeSecond, MAXIMUM_VALIDITY_SECONDS),
                issued.fractionalDigits,
            )
            if (issued > now || issued.isLeapSecond || parsed.expiresAtInstant.isLeapSecond ||
                parsed.expiresAtInstant > maximumExpiry
            ) {
                manager.install(null, sample.wall)
                return@synchronized unavailable(V2ConfigSourceFailure.INVALID_VALIDITY)
            }
            when (val result = manager.install(body, sample.wall)) {
                is V1ConfigUpdateResult.Rejected -> {
                    if (result.reason == V1ConfigRejection.STALE) V2ConfigSourceResult.Superseded
                    else unavailable(V2ConfigSourceFailure.CONFIG, result.reason)
                }
                is V1ConfigUpdateResult.Enabled,
                is V1ConfigUpdateResult.Inactive,
                -> {
                    val remaining = parsed.expiresAtInstant.elapsedNanosecondsFloorSince(now)
                    if (remaining == null || remaining <= 0) {
                        return@synchronized unavailable(V2ConfigSourceFailure.CONFIG, V1ConfigRejection.EXPIRED)
                    }
                    val deadline = try {
                        val atReceipt = Math.addExact(sample.monotonic, remaining)
                        val fromRequest = parsed.expiresAtInstant.elapsedNanosecondsFloorSince(
                            V1ExactTimestamp.fromEpochMillis(attempt.started.wall),
                        ) ?: return@synchronized unavailable(V2ConfigSourceFailure.INVALID_VALIDITY)
                        minOf(atReceipt, Math.addExact(attempt.started.monotonic, fromRequest))
                    } catch (_: ArithmeticException) {
                        return@synchronized unavailable(V2ConfigSourceFailure.CLOCK)
                    }
                    // Re-reading identical data cannot extend its existing monotonic lease.
                    val prior = leaseBoundary?.takeIf { it.semanticHash == parsed.configSemanticHash }
                    val boundedDeadline = minOf(deadline, prior?.deadline ?: deadline)
                    leaseBoundary = LeaseBoundary(parsed.configSemanticHash, boundedDeadline)
                    if (sample.monotonic >= boundedDeadline) {
                        return@synchronized unavailable(V2ConfigSourceFailure.CONFIG, V1ConfigRejection.EXPIRED)
                    }
                    lease = Lease(body, parsed.expiresAtInstant, boundedDeadline)
                    V2ConfigSourceResult.Document(body, parsed.status)
                }
            }
        }
    }

    /** Returns no document at/after wall or sleep-inclusive monotonic expiry, or after close. */
    fun currentDocument(): String? = currentLeaseSnapshot()?.body

    /** The lifecycle owner uses this exact retained deadline for its independent expiry timer. */
    fun currentLeaseSnapshot(): V2ConfigLeaseSnapshot? = synchronized(lock) {
        if (closed) return@synchronized null
        if (!sampleClockState()) return@synchronized null
        expireLease(lastWall, lastMonotonic)
        lease?.snapshot
    }

    /** Cancels outstanding decisions without resetting the ordering or consumed-lease witnesses. */
    fun withdraw() {
        synchronized(lock) {
            if (closed) return
            generation = Math.incrementExact(generation)
            lease = null
        }
    }

    /**
     * Withdraws the ordinary document immediately, retaining only a live predicate for the one
     * Application Backgrounded boundary. It cannot supply config or authorize another channel.
     * Another source operation, close, rollback or either original expiry invalidates it.
     */
    internal fun withdrawForApplicationBackgrounded(expected: V2ConfigLeaseSnapshot): (() -> Boolean)? = synchronized(lock) {
        if (closed) return@synchronized null
        val sample = sampleClock()
        if (sample != null) expireLease(sample)
        val retained = lease
        val matches = sample != null && retained != null && retained.body == expected.body &&
            retained.expiresAt.compareTo(expected.expiresAt) == 0 && retained.deadline <= expected.monotonicDeadlineNanos
        generation = Math.incrementExact(generation)
        val withdrawalGeneration = generation
        lease = null
        if (!matches) return@synchronized null
        // Same-document renewal reparses expiry and may tighten the monotonic bound
        // without a new lifecycle publication. Compare the exact expiry value. Retain that stricter bound; a later deadline is never admitted.
        val boundaryDeadline = checkNotNull(retained).deadline
        val current: () -> Boolean = {
            synchronized(lock) {
                if (closed || generation != withdrawalGeneration) false
                else {
                    val now = sampleClock()
                    now != null && V1ExactTimestamp.fromEpochMillis(now.wall) < expected.expiresAt &&
                        now.monotonic < boundaryDeadline
                }
            }
        }
        current
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation = Math.incrementExact(generation)
            lease = null
        }
    }

    private fun rejectDocument(body: String, sample: ClockSample, fallback: V1ConfigRejection): V2ConfigSourceResult {
        val result = manager.install(body, sample.wall)
        return if (result is V1ConfigUpdateResult.Rejected && result.reason == V1ConfigRejection.STALE) {
            V2ConfigSourceResult.Superseded
        } else {
            unavailable(V2ConfigSourceFailure.CONFIG, (result as? V1ConfigUpdateResult.Rejected)?.reason ?: fallback)
        }
    }

    private fun unavailable(reason: V2ConfigSourceFailure, rejection: V1ConfigRejection? = null): V2ConfigSourceResult.Unavailable {
        lease = null
        return V2ConfigSourceResult.Unavailable(reason, rejection)
    }

    // Only retained refresh attempts need an owned sample object. The hot lease
    // observation uses these primitive floors under the same original lock.
    private fun sampleClock(): ClockSample? =
        if (sampleClockState()) ClockSample(lastWall, lastMonotonic) else null

    private fun sampleClockState(): Boolean {
        if (clockFailed) return false
        val wall: Long
        val monotonic: Long
        try {
            wall = clock.wallNowEpochMillis()
            monotonic = clock.monotonicNowNanos()
        } catch (_: Exception) {
            clockFailed = true
            lease = null
            return false
        }
        if (monotonic < 0 ||
            (hasClockSample && (wall < lastWall || monotonic < lastMonotonic))
        ) {
            clockFailed = true
            lease = null
            return false
        }
        lastWall = wall
        lastMonotonic = monotonic
        hasClockSample = true
        return true
    }

    private fun expireLease(sample: ClockSample) = expireLease(sample.wall, sample.monotonic)

    private fun expireLease(wall: Long, monotonic: Long) {
        val current = lease ?: return
        if (current.expiresAt <= V1ExactTimestamp.fromEpochMillis(wall) || monotonic >= current.deadline) {
            lease = null
        }
    }

    private data class ClockSample(val wall: Long, val monotonic: Long)

    private data class Attempt(val generation: Long, val started: ClockSample)

    private data class Lease(
        val body: String,
        val expiresAt: V1ExactTimestamp,
        val deadline: Long,
    ) {
        // All three fields are immutable. Reuse conveys data only; every return
        // still follows fresh clock/expiry checks and the current lease field.
        val snapshot = V2ConfigLeaseSnapshot(body, expiresAt, deadline)
    }

    private data class LeaseBoundary(val semanticHash: String, val deadline: Long)

    private companion object {
        const val MAXIMUM_VALIDITY_SECONDS = 600L
    }
}
