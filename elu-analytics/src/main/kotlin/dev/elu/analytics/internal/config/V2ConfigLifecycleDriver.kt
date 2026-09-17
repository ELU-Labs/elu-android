package dev.elu.analytics.internal.config

internal enum class V2ConfigLifecycleUpdateKind { CONFIGURATION, APPLICATION_SUSPENSION }

/**
 * An ordered notification, deliberately carrying no retainable raw configuration. A deferred
 * consumer must call consume at the final synchronous application of the decision. It returns false
 * when superseded; true may deliver null to withdraw. The consumer must not block or suspend. If it queues more work, that work must consume
 * the token again before changing authority; queuing a retained raw string is insufficient. All
 * downstream channel operations must still enforce transaction-current privacy/identity/expiry.
 */
internal class V2ConfigLifecycleUpdate internal constructor(
    val sequence: Long,
    val kind: V2ConfigLifecycleUpdateKind = V2ConfigLifecycleUpdateKind.CONFIGURATION,
    private val consumeCurrent: ((String?) -> Unit) -> Boolean,
) {
    fun consume(consumer: (String?) -> Unit): Boolean = consumeCurrent(consumer)
}

/** One use, one fixed local event, and no generic capture or delivery authority. */
internal class V2ConfigApplicationBackgrounded internal constructor(
    val occurredAt: String,
    private val original: V2ConfigLifecycleUpdate,
    private val withdrawal: V2ConfigLifecycleUpdate,
    private val isBoundaryCurrent: (() -> Boolean) -> Boolean,
) {
    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    internal fun authorizes(witness: V2ConfigAuthorityWitness?): Boolean =
        claimed.get() && witness != null && isBoundaryCurrent {
            witness.matchesApplicationBackgrounded(original, withdrawal)
        }
}

internal enum class V2ConfigLifecyclePhase { NEW, FOREGROUND, BACKGROUND, CLOSED }

internal data class V2ConfigLifecycleDiagnostics(
    val phase: V2ConfigLifecyclePhase,
    val fetchInFlight: Boolean,
    val pendingRefresh: Boolean,
    val sequence: Long,
    val retryDelayNanos: Long,
    val terminalFailure: String?,
)

/**
 * Exclusive lifecycle owner of a source. Timers never wait for its blocking network worker.
 * Background/close withdraw immediately; foreground starts a fresh attempt. A noncooperative
 * canceled fetch keeps its physical slot until completion, and cannot publish across that fence.
 * This class is internal and unconstructed by production startup; it does not select a runtime.
 *
 * The listener only notifies/enqueues a V2ConfigLifecycleUpdate. Its consume callback is serialized
 * with withdrawal and checks the source's exact retained lease at consumption time. The eventual
 * runtime composition must carry this token through its last asynchronous boundary and retain
 * its independent transaction-current authority checks.
 */
internal class V2ConfigLifecycleDriver(
    private val source: V2ConfigSource,
    private val listener: (V2ConfigLifecycleUpdate) -> Unit,
    private val clock: V2ConfigClock = AndroidV2ConfigClock,
    private val scheduler: V2ConfigLifecycleScheduler = ScheduledV2ConfigLifecycleScheduler(),
    private val worker: V2ConfigLifecycleWorker = ThreadedV2ConfigLifecycleWorker(),
) : AutoCloseable {
    private val lock = Any()
    private var phase = V2ConfigLifecyclePhase.NEW
    private var epoch = 0L
    private var sequence = 0L
    private var attemptNumber = 0L
    private var inFlight: Attempt? = null
    private var pendingRefresh = false
    private var refreshTask: V2ConfigLifecycleTask? = null
    private var expiryTask: V2ConfigLifecycleTask? = null
    private var refreshGeneration = 0L
    private var expiryGeneration = 0L
    private var published: V2ConfigLeaseSnapshot? = null
    private var publishedUpdate: V2ConfigLifecycleUpdate? = null
    private var hasPublished = false
    private var retryDelay = SECOND
    private var terminalFailure: String? = null

    fun start() = onForeground()

    fun onForeground() = synchronized(lock) {
        if (phase == V2ConfigLifecyclePhase.CLOSED || phase == V2ConfigLifecyclePhase.FOREGROUND) return@synchronized
        phase = V2ConfigLifecyclePhase.FOREGROUND
        epoch = Math.incrementExact(epoch)
        retryDelay = SECOND
        source.withdraw()
        pendingRefresh = true
        startFetch()
    }

    fun onBackground() = background(null, null)

    /** Caller must already hold its owner lock; enqueue reentrantly, never wait for storage. */
    fun onApplicationBackgrounded(occurredAt: String, enqueue: (V2ConfigApplicationBackgrounded) -> Unit) =
        background(occurredAt, enqueue)

    private fun background(occurredAt: String?, enqueue: ((V2ConfigApplicationBackgrounded) -> Unit)?) = synchronized(lock) {
        if (phase == V2ConfigLifecyclePhase.CLOSED || phase == V2ConfigLifecyclePhase.BACKGROUND) return@synchronized
        val original = publishedUpdate
        val retained = published
        val wasForeground = phase == V2ConfigLifecyclePhase.FOREGROUND
        // Only a currently live published lease can be suspended. Invalid/expired/clock-failed
        // absence remains a configuration withdrawal, including when its notice was deferred.
        val current = if (wasForeground && original != null && retained != null) source.currentLeaseSnapshot() else null
        val applicationSuspension = current != null && retained != null && current.body == retained.body &&
            current.expiresAt.compareTo(retained.expiresAt) == 0 && current.monotonicDeadlineNanos <= retained.monotonicDeadlineNanos &&
            remaining(current) > 0
        phase = V2ConfigLifecyclePhase.BACKGROUND
        epoch = Math.incrementExact(epoch)
        val boundaryEpoch = epoch
        pendingRefresh = false
        cancelTimers()
        val boundaryCurrent = if (wasForeground && occurredAt != null && enqueue != null &&
            original != null && retained != null
        ) source.withdrawForApplicationBackgrounded(retained) else { source.withdraw(); null }
        worker.interruptCurrent()
        publish(null, force = true, kind = if (applicationSuspension) V2ConfigLifecycleUpdateKind.APPLICATION_SUSPENSION
            else V2ConfigLifecycleUpdateKind.CONFIGURATION, beforeNotify = { withdrawal ->
            if (boundaryCurrent != null && original != null && occurredAt != null && enqueue != null) {
                enqueue(V2ConfigApplicationBackgrounded(occurredAt, original, withdrawal) { witnessCurrent ->
                    synchronized(lock) {
                        phase == V2ConfigLifecyclePhase.BACKGROUND && epoch == boundaryEpoch &&
                            publishedUpdate === withdrawal && boundaryCurrent() && witnessCurrent()
                    }
                })
            }
        })
    }

    fun refresh() = synchronized(lock) {
        if (phase != V2ConfigLifecyclePhase.FOREGROUND) return@synchronized
        checkPublishedLease()
        if (phase != V2ConfigLifecyclePhase.FOREGROUND) return@synchronized
        cancelRefresh()
        pendingRefresh = true
        startFetch()
    }

    fun diagnostics(): V2ConfigLifecycleDiagnostics = synchronized(lock) {
        V2ConfigLifecycleDiagnostics(phase, inFlight != null, pendingRefresh, sequence, retryDelay, terminalFailure)
    }

    override fun close() = synchronized(lock) {
        if (phase == V2ConfigLifecyclePhase.CLOSED) return@synchronized
        phase = V2ConfigLifecyclePhase.CLOSED
        epoch = Math.incrementExact(epoch)
        pendingRefresh = false
        cancelTimers()
        source.close()
        worker.close()
        scheduler.close()
        publish(null, force = true)
    }

    private fun startFetch() {
        if (phase != V2ConfigLifecyclePhase.FOREGROUND || !pendingRefresh || inFlight != null) return
        pendingRefresh = false
        cancelRefresh()
        val attempt = Attempt(Math.incrementExact(attemptNumber).also { attemptNumber = it }, epoch)
        inFlight = attempt
        try {
            worker.execute {
                val allowed = synchronized(lock) { phase == V2ConfigLifecyclePhase.FOREGROUND && epoch == attempt.epoch }
                val result = if (allowed) {
                    try { source.refresh() } catch (_: Exception) {
                        V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.TRANSPORT)
                    }
                } else V2ConfigSourceResult.Superseded
                completed(attempt, result)
            }
        } catch (_: Exception) {
            inFlight = null
            fail("worker-unavailable")
        }
    }

    private fun completed(attempt: Attempt, result: V2ConfigSourceResult) = synchronized(lock) {
        if (inFlight != attempt) return@synchronized
        inFlight = null
        if (phase != V2ConfigLifecyclePhase.FOREGROUND || epoch != attempt.epoch) {
            // A worker may have entered refresh just after background's source withdrawal.
            // Clear any such unpublished lease before allowing a pending foreground attempt.
            source.withdraw()
            startFetch()
            return@synchronized
        }
        val current = source.currentLeaseSnapshot()
        if (result is V2ConfigSourceResult.Unavailable || current == null || remaining(current) <= 0) {
            publish(null)
            if (phase == V2ConfigLifecyclePhase.FOREGROUND) scheduleRetry()
        } else {
            retryDelay = SECOND
            publish(current)
            if (phase == V2ConfigLifecyclePhase.FOREGROUND) {
                scheduleExpiry(current)
                scheduleRenewal(current)
            }
        }
        if (pendingRefresh) startFetch()
    }

    private fun consume(expected: Long, consumer: (String?) -> Unit): Boolean = synchronized(lock) {
        if (sequence != expected) return@synchronized false
        checkPublishedLease()
        if (sequence != expected) return@synchronized false
        val raw = if (phase == V2ConfigLifecyclePhase.FOREGROUND) {
            val current = source.currentLeaseSnapshot()
            if (published != null && (current == null || current.body != published?.body)) return@synchronized false
            published?.body
        } else null
        consumer(raw)
        true
    }

    private fun checkPublishedLease() {
        val installed = published ?: return
        if (remaining(installed) <= 0 || source.currentLeaseSnapshot() == null) publish(null)
    }

    private fun publish(snapshot: V2ConfigLeaseSnapshot?, force: Boolean = false,
        kind: V2ConfigLifecycleUpdateKind = V2ConfigLifecycleUpdateKind.CONFIGURATION,
        beforeNotify: ((V2ConfigLifecycleUpdate) -> Unit)? = null) {
        if (!force && hasPublished && published?.body == snapshot?.body && publishedUpdate?.kind == kind) return
        published = snapshot
        hasPublished = true
        if (snapshot == null) cancelExpiry()
        sequence = Math.incrementExact(sequence)
        val noticeSequence = sequence
        val update = V2ConfigLifecycleUpdate(noticeSequence, kind) { consumer -> consume(noticeSequence, consumer) }
        publishedUpdate = update
        try {
            beforeNotify?.invoke(update)
            listener(update)
        } catch (_: Exception) {
            fail("listener-failed")
        }
    }

    private fun scheduleExpiry(snapshot: V2ConfigLeaseSnapshot) {
        cancelExpiry()
        val delay = remaining(snapshot)
        if (delay <= 0) { publish(null); scheduleRetry(); return }
        val generation = expiryGeneration
        try {
            expiryTask = scheduler.schedule(delay) {
                synchronized(lock) {
                    if (phase != V2ConfigLifecyclePhase.FOREGROUND || generation != expiryGeneration) return@synchronized
                    expiryTask = null
                    val installed = published ?: return@synchronized
                    if (remaining(installed) <= 0 || source.currentLeaseSnapshot() == null) {
                        publish(null)
                        if (inFlight == null) scheduleRetry()
                    } else scheduleExpiry(installed)
                }
            }
        } catch (_: Exception) { fail("expiry-scheduler-unavailable") }
    }

    private fun scheduleRenewal(snapshot: V2ConfigLeaseSnapshot) {
        val left = remaining(snapshot)
        val delay = maxOf(SECOND, left - minOf(MINUTE, left / 5))
        if (delay < left) scheduleRefresh(delay)
    }

    private fun scheduleRetry() {
        if (phase != V2ConfigLifecyclePhase.FOREGROUND) return
        val delay = retryDelay
        retryDelay = minOf(MINUTE, retryDelay * 2)
        scheduleRefresh(delay)
    }

    private fun scheduleRefresh(delay: Long) {
        if (phase != V2ConfigLifecyclePhase.FOREGROUND) return
        cancelRefresh()
        val generation = refreshGeneration
        try {
            refreshTask = scheduler.schedule(delay) {
                synchronized(lock) {
                    if (phase != V2ConfigLifecyclePhase.FOREGROUND || generation != refreshGeneration) return@synchronized
                    refreshTask = null
                    checkPublishedLease()
                    if (phase != V2ConfigLifecyclePhase.FOREGROUND) return@synchronized
                    pendingRefresh = true
                    startFetch()
                }
            }
        } catch (_: Exception) { fail("refresh-scheduler-unavailable") }
    }

    private fun remaining(snapshot: V2ConfigLeaseSnapshot): Long = try {
        val now = clock.monotonicNowNanos()
        val wall = V1ExactTimestamp.fromEpochMillis(clock.wallNowEpochMillis())
        val wallRemaining = snapshot.expiresAt.elapsedNanosecondsFloorSince(wall) ?: 0
        if (now < 0 || now >= snapshot.monotonicDeadlineNanos) 0
        else minOf(wallRemaining, snapshot.monotonicDeadlineNanos - now)
    } catch (_: Exception) { 0 }

    private fun cancelRefresh() {
        refreshGeneration = Math.incrementExact(refreshGeneration)
        refreshTask?.cancel()
        refreshTask = null
    }

    private fun cancelExpiry() {
        expiryGeneration = Math.incrementExact(expiryGeneration)
        expiryTask?.cancel()
        expiryTask = null
    }

    private fun cancelTimers() { cancelRefresh(); cancelExpiry() }

    private fun fail(reason: String) {
        if (terminalFailure != null) return
        terminalFailure = reason
        phase = V2ConfigLifecyclePhase.CLOSED
        epoch = Math.incrementExact(epoch)
        pendingRefresh = false
        cancelTimers()
        source.close()
        worker.close()
        scheduler.close()
        if (reason != "listener-failed") publish(null, force = true)
        else { published = null; sequence = Math.incrementExact(sequence) }
    }

    private data class Attempt(val number: Long, val epoch: Long)
    private companion object {
        const val SECOND = 1_000_000_000L
        const val MINUTE = 60 * SECOND
    }
}
