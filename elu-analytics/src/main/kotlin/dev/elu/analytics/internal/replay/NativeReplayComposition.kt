package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.runtime.NativeStartTrace
import dev.elu.analytics.internal.runtime.NativeStartPhase
import dev.elu.analytics.internal.runtime.BoundedNativeStartObserver
import dev.elu.analytics.internal.runtime.NativeCaptureStage
import dev.elu.analytics.internal.runtime.NativeCaptureFailureKind

import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.runtime.PrivacyStateProjector
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.lang.ref.WeakReference
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class NativeReplayCompositionEvaluation { ACTIVE, INACTIVE, WITHDRAWN, QUARANTINED }

internal data class NativeReplayCompletedCaptureSnapshot(
    val completionCount: Int,
    val stage: NativeCaptureStage,
    val frames: Int,
    val failure: NativeCaptureFailureKind?,
    val outcome: NativeReplayCaptureOutcome,
)

/**
 * Private construction only. The local intent predicate cannot supply source, session, sampling,
 * budget, profile or physical permission. All of those still come from the original queue/authority.
 * No caller waits on main, the facade lane or the queue owner lane.
 */
internal class NativeReplayComposition(
    private val queue: RuntimeQueueOwner,
    private val lifecycle: NativeReplayLifecycle,
    private val capabilities: NativeReplayCapabilities,
    private val versions: RuntimeVersions,
    private val deviceInEuTimezone: () -> Boolean,
    private val intakeAllowed: () -> Boolean,
    private val platform: NativeReplayCapturePlatform = AndroidNativeReplayCapturePlatform,
    private val transport: ReplayDeliveryTransport = NativeReplayHttpRouter(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "elu-native-composition").apply { isDaemon = true }
    },
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "elu-native-delivery-timer").apply { isDaemon = true }
    },
    private val nativeStartObserver: BoundedNativeStartObserver = BoundedNativeStartObserver.NONE,
) : AutoCloseable {
    private val monitor = Any()
    private var closed = false
    private var intent: Any = Any()
    private var requested = false
    private var forceRequested = false
    private var requestedAcceptance: () -> Boolean = { true }
    private var evaluation: SdkFuture<NativeReplayCompositionEvaluation>? = null
    private var capture: NativeReplayCaptureOwner? = null
    private var captureDiagnosticPublished = false // monitor; reset only when publishing a new original capture
    @Volatile private var latestCompletedCapture: NativeReplayCompletedCaptureSnapshot? = null
    private val authority = if (capabilities.hasLocalEvidence()) NativeReplayAuthority(queue, capabilities, deviceInEuTimezone) else null
    private var delivery: ReplayDeliveryCoordinator? = null
    // Restrictive local intent only. This never replaces the queue's source/policy/I/O guard.
    private val deliveryEpoch = AtomicReference<Any?>()
    private var subscription: AutoCloseable? = null
    private var selection: NativeReplaySelection? = null // worker only; contains weak native references
    private var lastAttempt: IdentityKey? = null // a scheduling cache, never authority
    private var deadlineRetry: ScheduledFuture<*>? = null // monitor; at most one pending timer
    private var deadlineRetryToken: Any? = null // monitor; withdrawal defeats already-queued wakes
    private var captureAttempt: Any? = null // worker only; a prior completion cannot schedule for its replacement
    private var retryIdentity: IdentityKey? = null // worker only
    private var retryIntent: Any? = null // worker only
    private var retryDelayMillis = NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS // worker only
    private var retryFailure: Throwable? = null // worker only; original failure remains observable at close
    private var quarantined = false // worker only; cannot be reset by a newer config/session
    private val readyResult = noncancelable<Unit>()
    private val closeResult = noncancelable<Unit>()

    init {
        nativeStartObserver.global.mark(NativeStartPhase.COMPOSITION_CREATED, authority != null)
        if (authority == null) readyResult.complete(Unit)
        else try {
            worker.execute {
                try {
                    queue.ensurePreparedReplayStorage().awaitExact()
                    queue.ensureNativeReplayAccounting().awaitExact()
                    if (!isClosed()) {
                        val binding = queue.openReplayDeliveryQueue(
                            PrivacyStateProjector.nativeSealedDeliveryPolicy(capabilities, deviceInEuTimezone)).awaitExact()
                        val clock = queue.nativeReplayCaptureClock()
                        val guardedTransport = ReplayDeliveryTransport { claim, authorizeIo ->
                            val original = deliveryEpoch.get()
                            transport.start(claim) {
                                original != null && deliveryEpoch.get() === original && authorizeIo() &&
                                    deliveryEpoch.get() === original
                            }
                        }
                        val coordinator = ReplayDeliveryCoordinator(binding, guardedTransport,
                            Executors.newSingleThreadExecutor { task -> Thread(task, "elu-native-delivery").apply { isDaemon = true } },
                            ReplayDeliveryScheduler { delay, action ->
                                val future = timer.schedule(action, delay, TimeUnit.MILLISECONDS)
                                ReplayDeliveryScheduledTask { future.cancel(false); Unit }
                            }, clock::wallNowEpochMillis, { clock.elapsedRealtimeNanos() / 1_000_000L })
                        synchronized(monitor) { delivery = coordinator }
                        val weak = WeakReference(this)
                        subscription = lifecycle.observeChanges {
                            weak.get()?.let { original ->
                                original.nativeStartObserver.global.mark(NativeStartPhase.LIFECYCLE_CHANGED)
                                original.withdrawFresh(); original.reevaluate(force = true) }
                        }
                    }
                    readyResult.complete(Unit)
                } catch (error: Throwable) { readyResult.completeExceptionally(error) }
            }
        } catch (error: Throwable) { readyResult.completeExceptionally(error) }
    }

    fun ready(): SdkFuture<Unit> = readyResult
    private fun isClosed() = synchronized(monitor) { closed }
    private fun current(original: Any): Boolean = synchronized(monitor) { !closed && intent === original } &&
        intakeAllowed() && synchronized(monitor) { !closed && intent === original }

    /** Synchronous local intake withdrawal. Physical work and all submitted results remain owned. */
    fun withdrawFresh() = withdraw(includeDelivery = false)

    /** A config/consent restriction also cancels delivery, without losing its original physical receipt. */
    fun withdrawAll() = withdraw(includeDelivery = true)

    private fun withdraw(includeDelivery: Boolean) {
        val old: NativeReplayCaptureOwner?; val sending: ReplayDeliveryCoordinator?
        synchronized(monitor) {
            intent = Any(); requested = false; forceRequested = true
            deadlineRetryToken = null; deadlineRetry?.cancel(false); deadlineRetry = null
            if (includeDelivery) deliveryEpoch.set(null)
            old = capture; sending = if (includeDelivery) delivery else null
        }
        old?.withdraw(); authority?.withdraw(); sending?.withdraw()
    }

    /** Call only after the original public acceptance/source publication fence permits reevaluation. */
    fun reevaluate(force: Boolean = false, originalAcceptance: () -> Boolean = { true }): SdkFuture<NativeReplayCompositionEvaluation> {
        nativeStartObserver.global.mark(NativeStartPhase.REEVALUATE_REQUEST, force)
        if (!originalAcceptance().also { nativeStartObserver.global.mark(NativeStartPhase.ORIGINAL_ACCEPTANCE, it) }) return SdkFuture.completedFuture(NativeReplayCompositionEvaluation.WITHDRAWN)
        val result: SdkFuture<NativeReplayCompositionEvaluation>
        val original: Any; val useForce: Boolean; val acceptance: () -> Boolean
        synchronized(monitor) {
            if ((closed || authority == null).also { nativeStartObserver.global.mark(NativeStartPhase.COMPOSITION_AVAILABLE, !it) }) return SdkFuture.completedFuture(NativeReplayCompositionEvaluation.INACTIVE)
            requested = true; forceRequested = forceRequested || force; requestedAcceptance = originalAcceptance
            evaluation?.let { nativeStartObserver.global.mark(NativeStartPhase.EVALUATION_COALESCED); return it }
            result = noncancelable(); evaluation = result
            // Queueing is the first acceptance boundary; a later worker cannot adopt a newer intent.
            original = intent; useForce = forceRequested; forceRequested = false; requested = false
            acceptance = requestedAcceptance
        }
        try { worker.execute { runEvaluation(result, original, useForce, acceptance) } }
        catch (error: Throwable) {
            synchronized(monitor) { if (evaluation === result) evaluation = null }
            result.completeExceptionally(error)
        }
        return result
    }

    /** Sealed delivery is independent of fresh sampling, session eligibility and remaining time. */
    fun flushSealed(): SdkFuture<ReplayDeliveryPass> =
        synchronized(monitor) { if (closed || deliveryEpoch.get() == null) null else delivery }?.flush()
            ?: SdkFuture.completedFuture(ReplayDeliveryPass(0, 0))

    private fun runEvaluation(result: SdkFuture<NativeReplayCompositionEvaluation>, original: Any, force: Boolean, acceptance: () -> Boolean) {
        val nativeStartTrace = nativeStartObserver.begin()
        // Original caller acceptance is restrictive only and survives every asynchronous handoff.
        fun accepted(): Boolean = acceptance() && current(original) && acceptance()
        var value = NativeReplayCompositionEvaluation.WITHDRAWN
        var failure: Throwable? = null
        try {
            readyResult.awaitExact()
            nativeStartTrace.mark(NativeStartPhase.READY_RETURNED)
            if (quarantined) { nativeStartTrace.mark(NativeStartPhase.EVALUATION_QUARANTINED); value = NativeReplayCompositionEvaluation.QUARANTINED }
            else if (!acceptance()) { nativeStartTrace.mark(NativeStartPhase.ACCEPTANCE_REFUSED); value = NativeReplayCompositionEvaluation.WITHDRAWN }
            else if (!accepted()) { nativeStartTrace.mark(NativeStartPhase.LOCAL_CURRENT_REFUSED); retireFresh() }
            else {
                synchronized(monitor) { if (!closed && intent === original) deliveryEpoch.compareAndSet(null, Any()) }
                flushSealed()
                val identity = queue.snapshot().awaitExact().state.identity
                nativeStartTrace.mark(NativeStartPhase.IDENTITY_RETURNED)
                if (!accepted()) retireFresh()
                else {
                    val key = IdentityKey(identity)
                    val running = synchronized(monitor) { capture }
                    if (!force && key == lastAttempt && running?.finished()?.isDone == false && selection?.isCurrent() == true) {
                        nativeStartTrace.mark(NativeStartPhase.ACTIVE_REUSED)
                        value = NativeReplayCompositionEvaluation.ACTIVE
                    } else {
                        retireFresh()
                        if ((!quarantined && accepted() && (force || key != lastAttempt)).also { nativeStartTrace.mark(NativeStartPhase.ATTEMPT_ELIGIBLE, it) }) {
                            // Any newly admitted attempt supersedes the prior scheduling hint,
                            // including a forced same-identity attempt and a physically late wake.
                            synchronized(monitor) {
                                deadlineRetryToken = null; deadlineRetry?.cancel(false); deadlineRetry = null
                            }
                            val attempt = Any().also { captureAttempt = it }
                            lastAttempt = key
                            if ((!identity.optedOut && identity.session?.lifecycle == SessionLifecycle.ACTIVE).also { nativeStartTrace.mark(NativeStartPhase.IDENTITY_ELIGIBLE, it) }) {
                                nativeStartTrace.mark(NativeStartPhase.ROOT_SELECTION_BEGIN)
                                val selected = lifecycle.selectCurrent().awaitExact()
                                selection = selected // retain before any source preparation or final check
                                nativeStartTrace.mark(NativeStartPhase.ROOT_SELECTION_RESULT, selected != null)
                                // No root admitted authority or capture. A later original hint may
                                // observe usable facts for this same identity without a forced retry.
                                if (selected == null) lastAttempt = null
                                if (selected != null && accepted()) {
                                    val prepared = checkNotNull(authority).prepare(selected, nativeStartTrace).awaitExact()
                                    nativeStartTrace.mark(NativeStartPhase.PREPARE_RESULT, prepared != null)
                                    if ((prepared != null && accepted() && selected.isCurrent()).also { nativeStartTrace.mark(NativeStartPhase.PREPARE_POSTCHECK, it) }) {
                                        val weak = WeakReference(this)
                                        val opened = NativeReplayCaptureOwner.start(queue, authority, checkNotNull(prepared), versions, platform, nativeStartTrace) {
                                            weak.get()?.flushSealed()
                                        }
                                        synchronized(monitor) { capture = opened; captureDiagnosticPublished = false }
                                        nativeStartTrace.mark(NativeStartPhase.CAPTURE_OWNER_RESULT, opened != null)
                                        if (opened != null) {
                                            opened.finished().whenComplete { outcome, _ ->
                                                weak.get()?.let {
                                                    it.publishCompletedCapture(opened)
                                                    if (outcome == NativeReplayCaptureOutcome.SETTLED_PASS_DEADLINE)
                                                        it.retrySettledDeadline(opened, key, attempt, original, acceptance)
                                                    else if (acceptance() && it.current(original) && acceptance())
                                                        it.reevaluate(originalAcceptance = acceptance)
                                                }
                                            }
                                            if (accepted()) value = NativeReplayCompositionEvaluation.ACTIVE
                                        }
                                    }
                                }
                            }
                        }
                        if (value != NativeReplayCompositionEvaluation.ACTIVE) {
                            retireFresh()
                            value = if (quarantined) NativeReplayCompositionEvaluation.QUARANTINED
                                else if (accepted()) NativeReplayCompositionEvaluation.INACTIVE
                                else NativeReplayCompositionEvaluation.WITHDRAWN
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            nativeStartTrace.mark(NativeStartPhase.EVALUATION_FAILED)
            failure = error
            quarantined = true
            try { retireFresh() } catch (cleanup: Throwable) { if (error !== cleanup) error.addSuppressed(cleanup) }
        }
        val again = synchronized(monitor) {
            if (evaluation === result) evaluation = null
            (if (!closed && requested) requestedAcceptance else null).also { requested = false }
        }
        nativeStartTrace.mark(NativeStartPhase.EVALUATION_ACTIVE, failure == null && value == NativeReplayCompositionEvaluation.ACTIVE)
        if (failure == null) result.complete(value) else result.completeExceptionally(failure)
        if (again != null) reevaluate(originalAcceptance = again)
    }

    /** Only this worker waits: physical collection, exact accounting, then original watcher disposal. */
    private fun retireFresh() {
        val old = synchronized(monitor) { capture }
        if (old != null) {
            if (old.stop().awaitExact() == NativeReplayCaptureOutcome.QUARANTINED) quarantined = true
            // An original completion listener may still be executing on another thread. Publish
            // before replacement, so that its late callback cannot lose or overwrite this result.
            publishCompletedCapture(old)
            synchronized(monitor) { if (capture === old) capture = null }
        }
        authority?.stop()?.awaitExact()?.let { if (it != NativeReplayAuthorityStop.SETTLED) quarantined = true }
        val selected = selection
        if (selected != null) {
            try { selected.closeAndWait().awaitExact(); selection = null }
            catch (_: Throwable) { quarantined = true } // retain original selection; no replacement is possible
        }
        if (quarantined) queue.retainNativeReplayCleanupFailure().awaitExact()
    }

    private fun publishCompletedCapture(original: NativeReplayCaptureOwner) {
        val observed = original.completedDiagnostic() ?: return
        synchronized(monitor) {
            if (capture !== original || captureDiagnosticPublished) return
            val previous = latestCompletedCapture?.completionCount ?: 0
            val count = if (previous < Int.MAX_VALUE) previous + 1 else Int.MAX_VALUE
            latestCompletedCapture = NativeReplayCompletedCaptureSnapshot(count, observed.stage,
                observed.frames, observed.failure, observed.outcome)
            captureDiagnosticPublished = true
        }
    }

    /** A delayed scheduling opportunity only; every retry reacquires root and queue authority. */
    private fun retrySettledDeadline(opened: NativeReplayCaptureOwner, key: IdentityKey,
        attempt: Any, original: Any, acceptance: () -> Boolean) {
        fun accepted() = acceptance() && current(original) && acceptance()
        try { worker.execute {
            try {
            if (captureAttempt !== attempt || !accepted() || quarantined) return@execute
            // A public reevaluation may already have retired this exact completed owner.
            if (capture === opened) retireFresh()
            if (capture != null || selection != null || quarantined || lastAttempt != key || !accepted()) return@execute
            if (retryIdentity != key || retryIntent !== original) {
                retryIdentity = key; retryIntent = original
                retryDelayMillis = NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS
            }
            val weak = WeakReference(this)
            val token = Any()
            synchronized(monitor) {
                if (closed || intent !== original || deadlineRetryToken != null) return@synchronized
                deadlineRetryToken = token
                try {
                    deadlineRetry = timer.schedule({ weak.get()?.resumeDeadlineRetry(token, key, attempt, original, acceptance) },
                        retryDelayMillis, TimeUnit.MILLISECONDS)
                    retryDelayMillis = minOf(NATIVE_REPLAY_MAXIMUM_DEADLINE_RETRY_MILLIS, retryDelayMillis * 2)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    deadlineRetryToken = null // No timer, no retry, and no authority is granted.
                }
            }
            } catch (error: Throwable) { failDeadlineRetry(error) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Close owns remaining settlement. */ }
    }

    private fun resumeDeadlineRetry(token: Any, key: IdentityKey, attempt: Any, original: Any, acceptance: () -> Boolean) {
        try { worker.execute {
            try {
            val originalWake = synchronized(monitor) {
                if (closed || intent !== original || deadlineRetryToken !== token) false
                else { deadlineRetryToken = null; deadlineRetry = null; true }
            }
            if (!originalWake || captureAttempt !== attempt || quarantined || capture != null || selection != null || lastAttempt != key ||
                !acceptance() || !current(original) || !acceptance()) return@execute
            val identity = queue.snapshot().awaitExact().state.identity
            if (IdentityKey(identity) != key || !acceptance() || !current(original) || !acceptance()) return@execute
            // Remove only this transient scheduling memo. Normal reevaluation still obtains
            // fresh root/selection, config, session, privacy, enrollment and capture admission.
            lastAttempt = null
            reevaluate(originalAcceptance = acceptance)
            } catch (error: Throwable) { failDeadlineRetry(error) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* A late wake cannot revive close. */ }
    }

    /** Same one-way quarantine as evaluation failure; preserve the first cause for close. */
    private fun failDeadlineRetry(error: Throwable) {
        quarantined = true
        val original = retryFailure
        if (original == null) retryFailure = error else if (original !== error) original.addSuppressed(error)
        synchronized(monitor) {
            deadlineRetryToken = null; deadlineRetry?.cancel(false); deadlineRetry = null
        }
        try { retireFresh() } catch (cleanup: Throwable) {
            if (cleanup !== error) error.addSuppressed(cleanup)
        }
    }

    override fun close() { closeAndWait() }
    fun closeAndWait(): SdkFuture<Unit> {
        val old: NativeReplayCaptureOwner?; val sending: ReplayDeliveryCoordinator?
        synchronized(monitor) {
            if (closed) return closeResult
            closed = true; intent = Any(); requested = false; deliveryEpoch.set(null)
            deadlineRetryToken = null; deadlineRetry?.cancel(false); deadlineRetry = null
            old = capture; sending = delivery
        }
        old?.withdraw(); authority?.withdraw(); sending?.closeAndWait()
        try {
            worker.execute {
                var failure: Throwable? = retryFailure
                fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                } }
                attempt { subscription?.close(); subscription = null }
                attempt { readyResult.awaitExact() }
                attempt { retireFresh() }
                attempt { if (authority?.closeAndWait()?.awaitExact() == NativeReplayAuthorityStop.UNRESOLVED) {
                    quarantined = true; queue.retainNativeReplayCleanupFailure().awaitExact()
                } }
                attempt { synchronized(monitor) { delivery }?.closeAndWait()?.awaitExact() }
                attempt { (transport as? AutoCloseable)?.close() }
                timer.shutdown(); worker.shutdown()
                if (quarantined && failure == null) failure = IllegalStateException("Native replay cleanup is quarantined")
                if (failure == null) closeResult.complete(Unit) else closeResult.completeExceptionally(checkNotNull(failure))
            }
        } catch (error: Throwable) { closeResult.completeExceptionally(error) }
        return closeResult
    }

    private data class IdentityKey(val revision: Long, val contextRevision: Long, val sessionId: String?,
        val startedAt: String?, val lifecycle: SessionLifecycle?, val backgroundedAt: String?, val optedOut: Boolean) {
        constructor(value: IdentityState) : this(value.revision, value.contextRevision, value.session?.id,
            value.session?.startedAt, value.session?.lifecycle, value.session?.backgroundedAt, value.optedOut)
    }
    private companion object {
        fun <T> noncancelable() = object : SdkFuture<T>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }
    }
}

/** Every physical request receives an exact constructor-bound client; no endpoint or credential rebinding. */
private class NativeReplayHttpRouter : ReplayDeliveryTransport, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Flight?>()
    override fun start(claim: ReplayDeliveryClaim, authorizeIo: () -> Boolean): ReplayTransportOperation {
        check(!closed.get()) { "Native delivery is closed" }
        val flight = Flight(OkHttpReplayTransport(claim.authorization.siteKey, claim.authorization.endpoint))
        if (!active.compareAndSet(null, flight)) { flight.client.close(); error("Native delivery is occupied") }
        if (closed.get()) flight.cancel()
        flight.start(claim, authorizeIo)
        return flight
    }
    override fun close() { closed.set(true); active.get()?.cancel() }
    private inner class Flight(val client: OkHttpReplayTransport) : ReplayTransportOperation {
        private val canceled = AtomicBoolean(false)
        private val physical = AtomicReference<ReplayTransportOperation?>()
        override val settlement = object : SdkFuture<ReplayTransportResponse>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        override fun cancel() { canceled.set(true); physical.get()?.cancel() }
        fun start(claim: ReplayDeliveryClaim, authorizeIo: () -> Boolean) {
            val original = try { client.start(claim) { !closed.get() && !canceled.get() && authorizeIo() } }
                catch (error: Throwable) { settle(null, error); return }
            physical.set(original)
            // Once enrolled, only the original physical settlement may finish the wrapper.
            original.settlement.whenComplete { response, error -> settle(response, error) }
            if (closed.get() || canceled.get()) runCatching { original.cancel() }
        }
        private fun settle(response: ReplayTransportResponse?, error: Throwable?) {
            try { client.close() } catch (_: Throwable) { return } // original occupancy/client retained on unknown cleanup
            active.compareAndSet(this, null)
            if (error != null) settlement.completeExceptionally(error)
            else if (response == null) settlement.completeExceptionally(IllegalStateException("Replay response is absent"))
            else settlement.complete(response)
        }
    }
}
