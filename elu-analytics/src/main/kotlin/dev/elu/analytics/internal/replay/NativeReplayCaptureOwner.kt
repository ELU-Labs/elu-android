package dev.elu.analytics.internal.replay

import android.os.Build
import android.view.View
import dev.elu.analytics.internal.runtime.RuntimeCaptureClock
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeVersions
import dev.elu.analytics.internal.runtime.NativeStartTrace
import dev.elu.analytics.internal.runtime.NativeStartPhase
import dev.elu.analytics.internal.runtime.NativeCaptureStage
import dev.elu.analytics.internal.runtime.NativeCaptureFailureKind
import java.lang.ref.WeakReference
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


/** Closed indices in the private CAPTURE_PROFILE vector; never a View/type/content identity. */
internal enum class NativeCollectorStage {
    BEFORE_COLLECT, CONFIG_MATERIALIZE, ROOT_PRIME, ANCESTOR_SCAN, ANCESTOR_GEOMETRY,
    ROOT_GEOMETRY, TREE_GEOMETRY, GROUP_CLIP, CHILD_ORDER, PROJECTION_ID,
    FINAL_ROOT, FINAL_ANCESTORS, REVALIDATE, SNAPSHOT_COMMIT,
}

/**
 * Main-callback counters only. No clock, View, authority, callback or I/O is owned here.
 * Intervals use existing withinPass samples and include intervening authority checks.
 * configGap therefore bounds the guarded materialization interval, not pure parser CPU.
 * Fixed saturation is diagnostic only and never changes capture admission.
 */
internal class NativeCapturePassProfile {
    private var origin = 0L
    private var previous = 0L
    private var stage = NativeCollectorStage.BEFORE_COLLECT
    var position = 0 // 0 outside read, 1 pre-check, 2 getter, 3 post-check
    private var mask = 1
    private var samples = 0
    private var reads = 0
    private var nodes = 0
    private var ancestors = 0
    private var projections = 0
    private var elapsed = 0
    private var maxGap = 0
    private var maxMask = 0
    private var configGap = 0
    private var pendingConfig = false
    private var flags = 0 // 1 counter/time saturation; 2 reversed observed sample

    fun begin(now: Long) {
        origin = now; previous = now; stage = NativeCollectorStage.BEFORE_COLLECT
        position = 0; mask = 1; samples = 0; reads = 0; nodes = 0; ancestors = 0
        projections = 0; elapsed = 0; maxGap = 0; maxMask = 0; configGap = 0
        pendingConfig = false; flags = 0
    }
    fun mark(value: NativeCollectorStage) { stage = value; mask = mask or (1 shl value.ordinal) }
    fun configMaterialization() { mark(NativeCollectorStage.CONFIG_MATERIALIZE); pendingConfig = true }
    private fun increment(value: Int, maximum: Int): Int =
        if (value < maximum) value + 1 else { flags = flags or 1; value }
    fun returned() { reads = increment(reads, 65535) }
    fun node() { nodes = increment(nodes, 9999) }
    fun ancestor() { ancestors = increment(ancestors, 64) }
    fun projection() { projections = increment(projections, 9999) }
    private fun micros(value: Long): Int = if (value / 1000 > 999999) {
        flags = flags or 1; 999999
    } else (value / 1000).toInt()
    /** Called exactly once per already-existing withinPass clock sample, before its original predicates. */
    fun sample(now: Long) {
        samples = increment(samples, 65535)
        if (now < previous || now < origin) { flags = flags or 2; return }
        val gap = micros(now - previous)
        elapsed = micros(now - origin)
        if (gap > maxGap) { maxGap = gap; maxMask = mask }
        if (pendingConfig) { configGap = gap; pendingConfig = false }
        previous = now; mask = 1 shl stage.ordinal
    }
    /** Detached only after the pass exits; at most twelve closed integers. */
    fun values(): List<Int> = listOf(stage.ordinal, position, samples, reads, nodes, ancestors,
        projections, elapsed, maxGap, configGap, maxMask, flags)
}

// This is a scheduling interval, never an extension of a pass or source deadline.
internal const val NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS = 1_000L
internal const val NATIVE_REPLAY_MAXIMUM_DEADLINE_RETRY_MILLIS = 30_000L

internal enum class NativeReplayCaptureOutcome { SETTLED, SETTLED_PASS_DEADLINE, SETTLED_ROOT_CHANGED, QUARANTINED }

/** Detached closed values only; no source, error, root, identity, profile or executable capability. */
internal data class NativeReplayCaptureCompletion(
    val stage: NativeCaptureStage,
    val frames: Int,
    val failure: NativeCaptureFailureKind?,
    val outcome: NativeReplayCaptureOutcome,
)

/** Synthetic implementations exercise ordering only; they cannot issue source or physical authority. */
internal interface NativeReplayCapturePlatform {
    val apiLevel: Int
    fun privacyWitness(): () -> Boolean = { true }
    fun createCollector(): NativeReplayCaptureCollector
    fun createCollector(profile: NativeCapturePassProfile): NativeReplayCaptureCollector = createCollector()
    fun createCollector(masking: NativeMaskingProfile, profile: NativeCapturePassProfile?): NativeReplayCaptureCollector =
        if (profile == null) createCollector() else createCollector(profile)
    fun createCollector(protocol: NativeReplayProtocol, masking: NativeMaskingProfile, profile: NativeCapturePassProfile?): NativeReplayCaptureCollector =
        createCollector(masking, profile)
    fun awaitNext(withdrawn: CountDownLatch): Boolean
    fun awaitTouch(withdrawn: CountDownLatch, wake: NativeReplayCaptureWake, delayNanos: Long): Boolean = awaitNext(withdrawn)
}

internal fun interface NativeReplayCaptureCollector {
    fun collect(root: Any, ordinal: Long, timestamp: Long, fence: NativeCollectionFence,
        current: () -> Boolean, unresolvedBlockRules: Boolean): NativeMaskedSnapshot
    fun touchProjection(frame: NativeMaskedSnapshot): NativeTouchProjection? = null
    /** Construct only; the run stores this handle before calling install on main. */
    fun touch(window: Any, root: Any, fence: NativeCollectionFence, current: () -> Boolean,
        fresh: () -> Boolean, unresolved: () -> Boolean, clock: RuntimeCaptureClock, wake: () -> Unit): NativeReplayCaptureTouch =
        error("Native touch projection unsupported")
}

internal object AndroidNativeReplayCapturePlatform : NativeReplayCapturePlatform {
    override val apiLevel get() = Build.VERSION.SDK_INT
    override fun privacyWitness(): () -> Boolean = NativeViewPrivacy.snapshot()::isCurrent
    override fun createCollector(): NativeReplayCaptureCollector {
        // Called only inside the already-selected original main callback.
        val collector = AndroidViewReplayCollector()
        return NativeReplayCaptureCollector { root, ordinal, timestamp, fence, current, unresolved ->
            collector.collect(root as View, ordinal, timestamp, fence, current, unresolved)
        }
    }
    override fun createCollector(profile: NativeCapturePassProfile): NativeReplayCaptureCollector {
        val collector = AndroidViewReplayCollector(profile = profile)
        return NativeReplayCaptureCollector { root, ordinal, timestamp, fence, current, unresolved ->
            collector.collect(root as View, ordinal, timestamp, fence, current, unresolved)
        }
    }
    override fun createCollector(masking: NativeMaskingProfile, profile: NativeCapturePassProfile?): NativeReplayCaptureCollector {
        val collector = AndroidViewReplayCollector(profile = profile, maskingProfile = masking)
        return NativeReplayCaptureCollector { root, ordinal, timestamp, fence, current, unresolved ->
            collector.collect(root as View, ordinal, timestamp, fence, current, unresolved)
        }
    }
    override fun createCollector(protocol: NativeReplayProtocol, masking: NativeMaskingProfile, profile: NativeCapturePassProfile?): NativeReplayCaptureCollector {
        if (protocol == NativeReplayProtocol.V1) return createCollector(masking, profile)
        val collector = AndroidViewReplayCollector(profile = profile, maskingProfile = masking, retainTouchWitnesses = true)
        return object : NativeReplayCaptureCollector {
            override fun collect(root: Any, ordinal: Long, timestamp: Long, fence: NativeCollectionFence,
                current: () -> Boolean, unresolvedBlockRules: Boolean) =
                collector.collect(root as View, ordinal, timestamp, fence, current, unresolvedBlockRules)
            override fun touchProjection(frame: NativeMaskedSnapshot) = collector.touchProjection(frame)
            override fun touch(window: Any, root: Any, fence: NativeCollectionFence, current: () -> Boolean,
                fresh: () -> Boolean, unresolved: () -> Boolean, clock: RuntimeCaptureClock, wake: () -> Unit) =
                AndroidReplayTouchObserver(window as android.view.Window, root as View, collector, fence, current, unresolved,
                    wallClock = clock::wallNowEpochMillis, continuousClock = clock::elapsedRealtimeNanos,
                    freshIntakeAllowed = fresh, wake = wake)
        }
    }
    override fun awaitTouch(withdrawn: CountDownLatch, wake: NativeReplayCaptureWake, delayNanos: Long) = wake.await(withdrawn, delayNanos)
    override fun awaitNext(withdrawn: CountDownLatch) = !withdrawn.await(NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
}

/** One coalesced wake for the existing capture thread, never a per-event task or an authority. */
internal class NativeReplayCaptureWake {
    private val monitor = java.lang.Object()
    private var pending = false
    fun signal() = synchronized(monitor) { if (!pending) { pending = true; monitor.notifyAll() } }
    fun await(withdrawn: CountDownLatch, delayNanos: Long): Boolean = synchronized(monitor) {
        require(delayNanos in 0..1_000_000_000L)
        val started = System.nanoTime()
        var remaining = delayNanos
        while (!pending && withdrawn.count != 0L && remaining > 0) {
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
            val elapsed = System.nanoTime() - started
            if (elapsed < 0) return@synchronized false
            remaining = (delayNanos - elapsed).coerceAtLeast(0)
        }
        pending = false
        withdrawn.count != 0L
    }
}

/**
 * Private physical interval. Caller exclusively borrows authority/selection until finished, then
 * disposes their resources separately. Neither watcher close nor cancellation is settlement.
 * Only the original stack composition constructs this owner; API23-28 and unsupported geometry remain denied.
 */
internal class NativeReplayCaptureOwner private constructor(
    private val fence: NativeReplayCaptureFence,
    private val completion: SdkFuture<NativeReplayCaptureOutcome>,
    private val completionObservation: AtomicReference<NativeReplayCaptureCompletion?>,
    private val collectorCurrent: AtomicReference<(() -> Boolean)?>,
    @Suppress("unused") private val lifetime: Any,
) : AutoCloseable {
    fun withdraw() = fence.withdraw()
    fun stop(): Future<NativeReplayCaptureOutcome> { withdraw(); return completion }
    /** Stops new reads synchronously; accepted frames still need their original authority. */
    fun stopRecording(): Future<NativeReplayCaptureOutcome> { fence.stopFresh(); return completion }
    internal fun settleStop(): Future<NativeReplayCaptureOutcome> =
        if (fence.gracefulStopRequested()) completion else stop()
    fun recordingStarted(): Boolean = runCatching {
        !completion.isDone && fence.mayCollect() && collectorCurrent.get()?.invoke() == true &&
            fence.mayCollect() && !completion.isDone
    }.getOrDefault(false)
    fun finished(): SdkFuture<NativeReplayCaptureOutcome> = completion
    // The candidate may be prepared before complete(), but cannot be observed before original settlement.
    fun completedDiagnostic(): NativeReplayCaptureCompletion? =
        if (completion.isDone) completionObservation.get() else null
    override fun close() = withdraw()

    companion object {
        fun start(
            queue: RuntimeQueueOwner,
            authority: NativeReplayAuthority,
            prepared: NativeReplayPreparedAuthority,
            versions: RuntimeVersions,
            platform: NativeReplayCapturePlatform = AndroidNativeReplayCapturePlatform,
            nativeStartTrace: NativeStartTrace = NativeStartTrace.NONE,
            /** Restrictive local recording switch, never a source or privacy grant. */
            freshIntakeAllowed: () -> Boolean = { true },
            /** Restrictive source/lifecycle intent remains required even while a local tail drains. */
            intakeCurrent: () -> Boolean = { true },
            /** A post-commit hint only; no immutable request or raw frame is exposed. */
            onCommitted: () -> Unit = {},
        ): NativeReplayCaptureOwner? {
            // No persistable guard, enrollment, clock sample or thread on unsupported API levels.
            if ((platform.apiLevel < 29 || !authority.belongsTo(queue, prepared)).also { nativeStartTrace.mark(NativeStartPhase.CAPTURE_OWNER_ELIGIBLE, !it) }) return null
            return launch(freshIntakeAllowed, intakeCurrent) { fence, completion, observation, collectorCurrent ->
                val run = NativeReplayCaptureRun(queue, authority, prepared, versions, platform, fence, completion,
                    observation, collectorCurrent, onCommitted, nativeStartTrace);
                { run.execute() }
            }
        }

        fun startRaster(queue: RuntimeQueueOwner, authority: NativeReplayAuthority,
            prepared: NativeRasterPreparedAuthority, binding: AnnotatedCaptureBinding,
            versions: RuntimeVersions, platform: NativeReplayCapturePlatform = AndroidNativeReplayCapturePlatform,
            freshIntakeAllowed: () -> Boolean = { true }, intakeCurrent: () -> Boolean = { true },
            onCommitted: () -> Unit = {}): NativeReplayCaptureOwner? {
            if (platform.apiLevel < 29 || !authority.belongsTo(queue, prepared) ||
                binding.sourceIdentity !== prepared.projection.sourceIdentity || !binding.isCurrent()) return null
            return launch(freshIntakeAllowed, intakeCurrent) { fence, completion, observation, collectorCurrent ->
                val run = NativeRasterCaptureRun(queue, authority, prepared, binding, versions, platform,
                    fence, completion, observation, collectorCurrent, onCommitted);
                { run.execute() }
            }
        }
        private fun launch(fresh: () -> Boolean, intake: () -> Boolean,
            makeRun: (NativeReplayCaptureFence, SdkFuture<NativeReplayCaptureOutcome>,
                AtomicReference<NativeReplayCaptureCompletion?>, AtomicReference<(() -> Boolean)?>) -> (() -> Unit)): NativeReplayCaptureOwner {
            val lifetime = Any()
            val fence = NativeReplayCaptureFence(WeakReference(lifetime), fresh, intake)
            val completion = object : SdkFuture<NativeReplayCaptureOutcome>() {
                override fun cancel(mayInterruptIfRunning: Boolean) = false
            }
            val observation = AtomicReference<NativeReplayCaptureCompletion?>()
            val collectorCurrent = AtomicReference<(() -> Boolean)?>()
            val owner = NativeReplayCaptureOwner(fence, completion, observation, collectorCurrent, lifetime)
            val execute = makeRun(fence, completion, observation, collectorCurrent)
            val thread = Thread({ execute() }, "elu-native-capture").apply { isDaemon = true }
            try { thread.start() }
            catch (_: Throwable) {
                fence.withdraw()
                // Thread.NEW proves no enrollment; uncertain start remains pending under this same handle.
                if (thread.state == Thread.State.NEW) {
                    observation.set(NativeReplayCaptureCompletion(NativeCaptureStage.BEFORE_LOOP, 0,
                        NativeCaptureFailureKind.OTHER, NativeReplayCaptureOutcome.SETTLED))
                    completion.complete(NativeReplayCaptureOutcome.SETTLED)
                }
            }
            return owner
        }
    }
}

/** This local fence owns no queue, authority, task or native root. */
private class NativeReplayCaptureFence(private val lifetime: WeakReference<Any>, private val freshIntakeAllowed: () -> Boolean,
    private val intakeCurrent: () -> Boolean) {
    private val monitor = Any()
    val collection = NativeCollectionFence()
    val withdrawn = CountDownLatch(1) // wakes both local stop and restrictive withdrawal
    val wake = NativeReplayCaptureWake()
    @Volatile private var restricted = false
    @Volatile private var stopping = false
    fun isCurrent() = lifetime.get() != null && !restricted && intakeCurrent() && !restricted
    fun mayCollect() = isCurrent() && !stopping && freshIntakeAllowed() && collection.isCurrent() && !stopping
    fun gracefulStopRequested() = (stopping || !freshIntakeAllowed()) && !restricted
    fun stopFresh() {
        synchronized(monitor) { stopping = true }
        // No fence monitor is held while joining the collector's commit lock.
        collection.withdraw(); withdrawn.countDown(); wake.signal()
    }
    fun withdraw() { synchronized(monitor) { restricted = true }; stopFresh() }
    fun acceptFrame(action: () -> Unit): Boolean = synchronized(monitor) {
        if (stopping || !isCurrent() || !freshIntakeAllowed()) false else { action(); true }
    }
}

/** A deadline carries no frame; only the exact original collection callback can create it. */
internal sealed class NativeReplayCollectionAttempt {
    /** Owned payloads must be disposed before an original main post-check drops the result. */
    open fun discard() = Unit
    class RasterDiscovery(val discovery: AnnotatedRootDiscovery) : NativeReplayCollectionAttempt()
    class RasterCaptured(val frame: AnnotatedRasterCandidate, val continuous: Long, val timestamp: Long,
        private val onCleanupFailure: () -> Unit = {}) : NativeReplayCollectionAttempt() {
        override fun discard() {
            try { frame.close() } catch (error: Throwable) { onCleanupFailure(); throw error }
        }
    }
    class Captured(val frame: NativeMaskedSnapshot, val continuous: Long,
        val projection: NativeTouchProjection? = null, val preceding: List<NativeTouchObservation> = emptyList(),
        val activeTouch: Boolean = false) : NativeReplayCollectionAttempt()
    class TouchBoundary(val observations: List<NativeTouchObservation>, val active: Boolean = false) : NativeReplayCollectionAttempt()
    object CollectorDeadline : NativeReplayCollectionAttempt()
    object UnsupportedGeometry : NativeReplayCollectionAttempt()
    object LocalStop : NativeReplayCollectionAttempt()
}

private class NativeReplayLocalStop : IllegalStateException()
private class NativeReplayRootBoundary : IllegalStateException()

/** One independent run, never the handle; all main and durable Futures are observed exactly once. */
private class NativeReplayCaptureRun(
    private val queue: RuntimeQueueOwner,
    private val authority: NativeReplayAuthority,
    private val prepared: NativeReplayPreparedAuthority,
    private val versions: RuntimeVersions,
    private val platform: NativeReplayCapturePlatform,
    private val fence: NativeReplayCaptureFence,
    private val completion: SdkFuture<NativeReplayCaptureOutcome>,
    private val completionObservation: AtomicReference<NativeReplayCaptureCompletion?>,
    private val collectorCurrent: AtomicReference<(() -> Boolean)?>,
    private val onCommitted: () -> Unit,
    private val nativeStartTrace: NativeStartTrace,
) {
    private val selection = prepared.selection
    private val clock: RuntimeCaptureClock = queue.nativeReplayCaptureClock()
    private val privacyCurrent = platform.privacyWitness()

    private fun local(): Boolean = privacyCurrent() && fence.isCurrent() && authority.belongsTo(queue, prepared) &&
        selection.isCurrent() && fence.isCurrent()
    private fun current(permit: NativeReplayPermit, admission: NativeReplayCaptureAdmission): Boolean =
        local() && admission.permit === permit && admission.isCurrent() && local()
    private fun requireCurrent(value: Boolean) {
        if (!value && selection.observedRootBoundary()) throw NativeReplayRootBoundary()
        check(value) { "Native capture withdrawn" }
    }

    fun execute() {
        nativeStartTrace.mark(NativeStartPhase.CAPTURE_THREAD_ENTERED)
        var enrollment: NativeReplayCaptureEnrollment? = null
        var physicalUse: NativeReplayCapturePhysicalUse? = null
        var pendingRequest: PreparedReplayRequest? = null
        var buffer: NativeReplayFrameBuffer? = null
        var interactionBuffer: NativeReplayV2Buffer? = null
        val touch = AtomicReference<NativeReplayCaptureTouch?>()
        val touchFence = NativeCollectionFence()
        var acceptedProjection: NativeTouchProjection? = null
        var touchActive = false
        var touchArmed = false
        var initialCommitted = false
        var lastGeometryContinuous: Long? = null
        var lastGeometryWall: Long? = null
        var startSubmitted = false
        var diagnosticStage = NativeCaptureStage.BEFORE_LOOP
        var completedFrames = 0
        var passFailure: NativeCaptureFailureKind? = null
        var retryablePassDeadline = false
        var recoverRoot = false
        var originalPermit: NativeReplayPermit? = null
        var completedFailure: NativeCaptureFailureKind? = null
        fun complete(outcome: NativeReplayCaptureOutcome) {
            collectorCurrent.set(null)
            completionObservation.set(NativeReplayCaptureCompletion(diagnosticStage, completedFrames, completedFailure, outcome))
            completion.complete(outcome)
        }
        val passProfile = if (nativeStartTrace === NativeStartTrace.NONE) null else NativeCapturePassProfile()
        try {
            // Local selection/lifetime only until original physical accounting is reserved.
            requireCurrent(local() && fence.mayCollect())
            nativeStartTrace.mark(NativeStartPhase.ENROLL_BEGIN)
            enrollment = queue.enrollNativeReplayCapture().awaitExact()
            nativeStartTrace.mark(NativeStartPhase.ENROLL_RESULT, enrollment != null)
            if (enrollment == null) error("Native capture occupied")
            requireCurrent(local())
            physicalUse = enrollment.takePhysicalUse()
            nativeStartTrace.mark(NativeStartPhase.PHYSICAL_USE_RESULT, physicalUse != null)
            if (physicalUse == null) error("Native physical use unavailable")
            requireCurrent(local() && prepared.isCurrent() && local())
            startSubmitted = true
            nativeStartTrace.mark(NativeStartPhase.START_BEGIN)
            val permit = authority.start(prepared, physicalUse).awaitExact().also { nativeStartTrace.mark(NativeStartPhase.START_RESULT, it != null) } ?: error("Native start denied")
            originalPermit = permit
            requireCurrent(local() && permit.isCurrent() && local())
            nativeStartTrace.mark(NativeStartPhase.ADMISSION_BEGIN)
            val admission = authority.captureAdmission(permit, physicalUse).awaitExact().also { nativeStartTrace.mark(NativeStartPhase.ADMISSION_RESULT, it != null) } ?: error("Native admission denied")
            requireCurrent(current(permit, admission))
            requireCurrent(!admission.hasUnresolvedBlockRules)
            val protocol = checkNotNull(NativeReplayProtocol.match(admission.authorization.negotiatedReplayTransport,
                admission.authorization.replayCapabilities.replayProtocolGeneration))
            val frames = NativeReplayFrameBuffer(admission.minimumDurationSeconds).also { buffer = it }
            val interactions = if (protocol == NativeReplayProtocol.V2)
                NativeReplayV2Buffer(admission.minimumDurationSeconds).also { interactionBuffer = it } else null
            val sealer = NativeReplaySealer(permit.replayId, admission.identity, admission.authorization,
                admission.privacy, admission.profile, versions)
            requireCurrent(current(permit, admission))
            nativeStartTrace.mark(NativeStartPhase.CAPTURE_LOOP_READY)
            fun appendSealed() {
                requireCurrent(current(permit, admission))
                diagnosticStage = NativeCaptureStage.DURABLE_APPEND
                when (queue.appendNativeReplay(checkNotNull(pendingRequest), admission, physicalUse).awaitExact()) {
                    is NativeReplayAppendOutcome.Committed -> {
                        pendingRequest = null
                        runCatching { onCommitted() }
                    }
                    is NativeReplayAppendOutcome.CommittedThenWithdrawn -> {
                        pendingRequest = null
                        runCatching { onCommitted() }
                        error("Native append committed after withdrawal")
                    }
                    is NativeReplayAppendOutcome.Rejected -> {
                        pendingRequest = null
                        error("Native append rejected")
                    }
                }
                requireCurrent(current(permit, admission))
            }
            fun sealPrefix(prefix: List<NativeMaskedSnapshot>) {
                requireCurrent(current(permit, admission))
                diagnosticStage = NativeCaptureStage.SEAL_REQUEST
                pendingRequest = sealer.seal(prefix)
                appendSealed()
                diagnosticStage = NativeCaptureStage.COMMIT_FRAME
                frames.committed(prefix)
            }
            fun sealInteractions(prefix: List<NativeReplayV2Entry>) {
                requireCurrent(current(permit, admission))
                diagnosticStage = NativeCaptureStage.SEAL_REQUEST
                pendingRequest = sealer.sealV2(prefix)
                appendSealed()
                diagnosticStage = NativeCaptureStage.COMMIT_FRAME
                checkNotNull(interactions).committed(prefix)
                initialCommitted = true
                if (!touchArmed && fence.mayCollect()) {
                    val projection = checkNotNull(acceptedProjection)
                    val armed = selection.consumeOriginalWindow({ current(permit, admission) }, fence::gracefulStopRequested) { _, _, rootCurrent ->
                        requireCurrent(rootCurrent() && current(permit, admission) && fence.mayCollect())
                        // A contact observed before this exact known commit is never adopted.
                        touchArmed = checkNotNull(touch.get()).arm(projection)
                        NativeReplayCollectionAttempt.TouchBoundary(emptyList(), checkNotNull(touch.get()).active())
                    }.awaitExact()
                    requireCurrent(armed is NativeReplayCollectionAttempt.TouchBoundary && current(permit, admission))
                }
            }
            fun offer(entry: NativeReplayV2Entry, continuous: Long) {
                requireCurrent(current(permit, admission))
                val original = checkNotNull(interactions)
                when (original.offer(entry, continuous)) {
                    NativeReplayV2Buffer.Offer.UNARMED -> error("Original v2 interaction is unarmed")
                    NativeReplayV2Buffer.Offer.FLUSH_REQUIRED -> {
                        sealInteractions(original.beginSealing())
                        requireCurrent(current(permit, admission))
                        check(original.offer(entry, continuous) == NativeReplayV2Buffer.Offer.ACCEPTED)
                    }
                    NativeReplayV2Buffer.Offer.ACCEPTED -> Unit
                }
            }
            fun offerObserved(rows: List<NativeTouchObservation>, continuous: Long) {
                for (row in rows) {
                    check(row.projection === acceptedProjection) { "Interaction preceded its exact accepted geometry" }
                    offer(row.interaction, continuous)
                }
            }
            var discardTail = false
            var viewport: NativeViewport? = null
            var collector: NativeReplayCaptureCollector? = null // implementation retains only weak View projections
            // Keep the existing capped exponential cadence across this original owner's attempts.
            // Successful frames do not reset it; no session/config/whole-run deadline is extended.
            var collectorRetryDelayMillis = NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS
            captureLoop@ while (true) {
                if (fence.gracefulStopRequested()) break
                diagnosticStage = NativeCaptureStage.LOOP_CURRENT
                requireCurrent(current(permit, admission))
                val ordinal = interactions?.nextFrameOrdinal ?: frames.nextFrameOrdinal
                passFailure = null
                var geometryFailure: NativeCollectionException? = null
                var beforeGeometry: List<NativeTouchObservation> = emptyList()
                diagnosticStage = NativeCaptureStage.ROOT_COLLECT
                val captured = selection.consumeOriginalWindow({ current(permit, admission) }, fence::gracefulStopRequested) { root, window, rootCurrent ->
                    try {
                        fun requireCollection(allowed: Boolean) {
                            if (!allowed && fence.gracefulStopRequested() && current(permit, admission))
                                throw NativeReplayLocalStop()
                            requireCurrent(allowed)
                        }
                        requireCollection(fence.mayCollect() && current(permit, admission) && rootCurrent())
                        val continuous = clock.elapsedRealtimeNanos()
                        passProfile?.begin(continuous)
                        requireCurrent(continuous >= 0)
                        val timestamp = clock.wallNowEpochMillis()
                        requireCurrent(timestamp in 1..253_402_300_799_999L)
                        fun withinPass(): Boolean {
                            // rootCurrent includes this same current(permit, admission) check.
                            if (!fence.mayCollect() || !rootCurrent()) return false
                            val now = clock.elapsedRealtimeNanos()
                            passProfile?.sample(now)
                            if (now < continuous) {
                                passFailure = NativeCaptureFailureKind.PASS_CLOCK_REVERSED
                                return false
                            }
                            if (now - continuous > MAXIMUM_PASS_NANOSECONDS) {
                                passFailure = NativeCaptureFailureKind.PASS_DEADLINE
                                return false
                            }
                            return rootCurrent()
                        }
                        requireCollection(withinPass())
                        diagnosticStage = NativeCaptureStage.COLLECTOR
                        val originalCollector = collector ?: platform.createCollector(protocol, admission.profile, passProfile).also { collector = it }
                        requireCollection(withinPass())
                        if (interactions != null && touch.get() == null) {
                            val originalTouch = originalCollector.touch(window, root, touchFence,
                                { current(permit, admission) }, fence::mayCollect,
                                { admission.hasUnresolvedBlockRules }, clock, fence.wake::signal)
                            check(touch.compareAndSet(null, originalTouch)) // Reachable before any setter side effect.
                            checkNotNull(enrollment).retainOriginalTouch(checkNotNull(physicalUse), originalTouch)
                            originalTouch.install()
                            requireCollection(withinPass())
                        }
                        if (interactions != null && initialCommitted && !touchArmed) {
                            touchArmed = checkNotNull(touch.get()).arm(checkNotNull(acceptedProjection))
                            requireCollection(withinPass())
                        }
                        if (interactions != null && lastGeometryContinuous != null) {
                            val active = checkNotNull(touch.get()).active()
                            val interval = if (active) 200_000_000L else 1_000_000_000L
                            if (continuous - checkNotNull(lastGeometryContinuous) < interval ||
                                timestamp - checkNotNull(lastGeometryWall) < 200) {
                                val rows = checkNotNull(touch.get()).drain()
                                requireCollection(withinPass())
                                return@consumeOriginalWindow NativeReplayCollectionAttempt.TouchBoundary(rows, active)
                            }
                        }
                        collectorCurrent.compareAndSet(null) { fence.mayCollect() && current(permit, admission) }
                        passProfile?.configMaterialization()
                        val unresolvedBlockRules = admission.hasUnresolvedBlockRules
                        // Recheck pending points against the original serialized projection BEFORE a
                        // successful collector pass replaces its current weak hierarchy witnesses.
                        val preceding = if (interactions != null) checkNotNull(touch.get()).drain() else emptyList()
                        beforeGeometry = preceding
                        requireCollection(withinPass())
                        val frame = try {
                            originalCollector.collect(root, ordinal, timestamp, fence.collection, ::withinPass,
                                unresolvedBlockRules)
                        } catch (error: NativeCollectionException) {
                            if (error.failure == NativeCollectionFailure.WITHDRAWN && fence.gracefulStopRequested() &&
                                current(permit, admission)) return@consumeOriginalWindow NativeReplayCollectionAttempt.LocalStop
                            if (error.failure == NativeCollectionFailure.UNSUPPORTED_GEOMETRY) {
                                // Geometry retry never overrides the original pass clock/deadline.
                                // The collector may throw without sampling its current callback.
                                if (!withinPass()) throw error
                                geometryFailure = error
                                requireCurrent(rootCurrent() && current(permit, admission) && rootCurrent())
                                // No failed frame/ordinal escapes; selection still performs its full postcheck.
                                return@consumeOriginalWindow NativeReplayCollectionAttempt.UnsupportedGeometry
                            }
                            if (error.failure == NativeCollectionFailure.WITHDRAWN && selection.observedRootBoundary())
                                throw NativeReplayRootBoundary()
                            // Only this known collector timeout may retain the original accepted buffer.
                            // The collector has not returned/committed a frame; no seal or append ran.
                            if (completedFrames == 0 || error.failure != NativeCollectionFailure.WITHDRAWN ||
                                passFailure != NativeCaptureFailureKind.PASS_DEADLINE || pendingRequest != null) throw error
                            requireCurrent(rootCurrent() && current(permit, admission) && rootCurrent())
                            nativeStartTrace.captureFailed(NativeCaptureStage.COLLECTOR, completedFrames,
                                NativeCaptureFailureKind.PASS_DEADLINE)
                            if (passProfile != null) nativeStartTrace.captureProfile(passProfile)
                            // Selection still executes its exact root/current-authority postvalidation.
                            return@consumeOriginalWindow NativeReplayCollectionAttempt.CollectorDeadline
                        }
                        requireCollection(withinPass())
                        if (viewport != null && viewport != frame.viewport) throw NativeReplayRootBoundary()
                        val projection = if (interactions != null) checkNotNull(originalCollector.touchProjection(frame)) else null
                        val rows = if (projection != null) preceding + checkNotNull(touch.get()).handoff(projection, continuous) else emptyList()
                        requireCollection(withinPass())
                        NativeReplayCollectionAttempt.Captured(frame, continuous, projection, rows,
                            interactions != null && checkNotNull(touch.get()).active())
                    } catch (_: NativeReplayLocalStop) { NativeReplayCollectionAttempt.LocalStop }
                }.awaitExact() ?: run { requireCurrent(false); error("Native collection denied") }
                requireCurrent(current(permit, admission))
                if (captured is NativeReplayCollectionAttempt.TouchBoundary) {
                    offerObserved(captured.observations, clock.elapsedRealtimeNanos())
                    touchActive = captured.active
                    if (interactions?.isReady == true) sealInteractions(interactions.beginSealing())
                    val interval = if (touchActive) 200_000_000L else 1_000_000_000L
                    val elapsed = clock.elapsedRealtimeNanos() - checkNotNull(lastGeometryContinuous)
                    requireCurrent(elapsed >= 0 && current(permit, admission))
                    // If monotonic time is due but the wire clock has not advanced, do not busy poll.
                    val delay = if (elapsed >= interval) interval else interval - elapsed
                    if (!platform.awaitTouch(fence.withdrawn, fence.wake, delay)) break
                    continue
                }
                if (captured === NativeReplayCollectionAttempt.LocalStop) {
                    // Local stop prevented exact View/privacy postvalidation of this callback.
                    // Discard both its unaccepted frame and the unsealed tail; pure source checks
                    // cannot establish an unobserved Window/privacy change on the main thread.
                    frames.withdraw(); interactions?.withdraw()
                    discardTail = true
                    break
                }
                if (fence.gracefulStopRequested()) break
                if (captured === NativeReplayCollectionAttempt.CollectorDeadline ||
                    captured === NativeReplayCollectionAttempt.UnsupportedGeometry) {
                    // This exact main callback already revalidated the old descriptor. Preserve its
                    // drained start/movement order even when no new geometry could be collected.
                    offerObserved(beforeGeometry, clock.elapsedRealtimeNanos())
                    if (interactions?.isReady == true) sealInteractions(interactions.beginSealing())
                    // Same fence, enrollment, selection, sealer and buffer. Wait off main in the
                    // existing one-second cancellable ticks, checking original authority each time.
                    var remaining = collectorRetryDelayMillis
                    while (remaining > 0) {
                        requireCurrent(current(permit, admission))
                        if (!platform.awaitNext(fence.withdrawn)) {
                            if (fence.gracefulStopRequested()) break@captureLoop
                            geometryFailure?.let { throw it }
                            requireCurrent(false)
                        }
                        requireCurrent(current(permit, admission))
                        remaining -= NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS
                    }
                    collectorRetryDelayMillis = minOf(NATIVE_REPLAY_MAXIMUM_DEADLINE_RETRY_MILLIS,
                        collectorRetryDelayMillis * 2)
                    continue
                }
                check(captured is NativeReplayCollectionAttempt.Captured)
                diagnosticStage = NativeCaptureStage.FRAME_APPEND
                if (viewport != null && viewport != captured.frame.viewport) throw NativeReplayRootBoundary()
                viewport = captured.frame.viewport
                if (interactions == null) {
                    if (!fence.acceptFrame { frames.append(captured.frame, captured.continuous) }) break
                } else {
                    offerObserved(captured.preceding, captured.continuous)
                    requireCurrent(current(permit, admission) && fence.mayCollect())
                    offer(NativeReplayV2Geometry(captured.frame), captured.continuous)
                    if (!fence.acceptFrame { acceptedProjection = checkNotNull(captured.projection) }) {
                        interactions.withdraw(); discardTail = true; break
                    }
                    touchActive = captured.activeTouch
                    lastGeometryContinuous = captured.continuous; lastGeometryWall = captured.frame.timestamp
                }
                if (completedFrames < Int.MAX_VALUE) completedFrames += 1
                if (interactions?.isReady == true) sealInteractions(interactions.beginSealing())
                if (interactions == null && frames.isReady) {
                    diagnosticStage = NativeCaptureStage.SEAL_PREFIX
                    sealPrefix(frames.beginSealing())
                }
                requireCurrent(current(permit, admission))
                diagnosticStage = NativeCaptureStage.WAIT_NEXT
                if (interactions == null) {
                    if (!platform.awaitNext(fence.withdrawn)) break
                } else if (!platform.awaitTouch(fence.withdrawn, fence.wake,
                    if (touchActive) 200_000_000L else 1_000_000_000L)) break
            }
            if (fence.gracefulStopRequested() && !discardTail) {
                requireCurrent(current(permit, admission))
                if (interactions == null) frames.beginDraining()?.let { prefix -> sealPrefix(prefix) }
                else {
                    val tail = selection.consumeOriginalWindow({ current(permit, admission) }) { _, _, rootCurrent ->
                        requireCurrent(rootCurrent() && current(permit, admission))
                        val rows = checkNotNull(touch.get()).stopAndDrain()
                        requireCurrent(rootCurrent() && current(permit, admission))
                        NativeReplayCollectionAttempt.TouchBoundary(rows)
                    }.awaitExact()
                    requireCurrent(tail is NativeReplayCollectionAttempt.TouchBoundary && current(permit, admission))
                    offerObserved((tail as NativeReplayCollectionAttempt.TouchBoundary).observations, clock.elapsedRealtimeNanos())
                    interactions.beginDraining()?.let { sealInteractions(it) }
                }
            }
        } catch (error: Throwable) {
            // Selection is intentionally excluded from this restrictive-only source check.
            // The hint grants no authority and is published only after original settlement.
            recoverRoot = runCatching {
                error is NativeReplayRootBoundary && passFailure == null && pendingRequest == null &&
                    privacyCurrent() && fence.mayCollect() && authority.belongsTo(queue, prepared) &&
                    originalPermit?.started?.guard?.isCurrent() == true && privacyCurrent() && fence.mayCollect()
            }.getOrDefault(false)
            // Only closed diagnostic values leave this catch; the original error is never serialized.
            val diagnosticFailure = if (error is NativeCollectionException) when (error.failure) {
                NativeCollectionFailure.NOT_MAIN_THREAD -> NativeCaptureFailureKind.NOT_MAIN_THREAD
                NativeCollectionFailure.REENTRANT -> NativeCaptureFailureKind.REENTRANT
                NativeCollectionFailure.WITHDRAWN -> passFailure ?: NativeCaptureFailureKind.WITHDRAWN
                NativeCollectionFailure.INVALID_ROOT -> NativeCaptureFailureKind.INVALID_ROOT
                NativeCollectionFailure.UNSUPPORTED_GEOMETRY -> when (error.ancestorFailure) {
                    NativeAncestorFailure.FRAMEWORK_ACTION_BAR -> NativeCaptureFailureKind.GEOMETRY_ACTION_BAR_ANCESTOR
                    NativeAncestorFailure.OTHER -> NativeCaptureFailureKind.GEOMETRY_UNKNOWN_ANCESTOR
                    null -> NativeCaptureFailureKind.UNSUPPORTED_GEOMETRY
                }
                NativeCollectionFailure.UNRESOLVED_BLOCK_RULE -> NativeCaptureFailureKind.UNRESOLVED_BLOCK_RULE
                NativeCollectionFailure.TREE_CHANGED -> NativeCaptureFailureKind.TREE_CHANGED
                NativeCollectionFailure.NODE_LIMIT -> NativeCaptureFailureKind.NODE_LIMIT
                NativeCollectionFailure.DEPTH_LIMIT -> NativeCaptureFailureKind.DEPTH_LIMIT
                NativeCollectionFailure.PROJECTION_LIMIT -> NativeCaptureFailureKind.PROJECTION_LIMIT
            } else NativeCaptureFailureKind.OTHER
            // Only a known, zero-frame collector timeout may be tried under a fresh authority.
            // No partial frame, seal, append, privacy refusal or unknown failure is retryable.
            retryablePassDeadline = diagnosticStage == NativeCaptureStage.COLLECTOR && completedFrames == 0 &&
                diagnosticFailure == NativeCaptureFailureKind.PASS_DEADLINE
            completedFailure = diagnosticFailure
            nativeStartTrace.captureFailed(diagnosticStage, completedFrames, diagnosticFailure)
            if (passProfile != null) nativeStartTrace.captureProfile(passProfile)
            // No suffix, retry or regenerated request follows an uncertain main/seal/append result.
            fence.withdraw()
        }
        collectorCurrent.set(null)
        fence.withdraw()
        buffer?.withdraw(); interactionBuffer?.withdraw(); touchFence.withdraw()
        val originalTouch = touch.get()
        if (originalTouch != null) {
            try {
                originalTouch.withdrawIntake()
                selection.closeOriginalTouchObserver(originalTouch).awaitExact()
                checkNotNull(enrollment).originalTouchSettled(checkNotNull(physicalUse), originalTouch)
            }
            catch (_: Throwable) {
                // Original cleanup failed; never mark physical completion or release this enrollment.
                enrollment?.quarantine(retaining = pendingRequest)
                complete(NativeReplayCaptureOutcome.QUARANTINED)
                return
            }
        }
        if (enrollment == null) { complete(NativeReplayCaptureOutcome.SETTLED); return }
        try {
            if (settleNativeCapture(queue, authority, enrollment, physicalUse, startSubmitted)) {
                // Expose the hint only after the original physical and durable accounting settled.
                complete(if (recoverRoot) NativeReplayCaptureOutcome.SETTLED_ROOT_CHANGED
                    else if (retryablePassDeadline) NativeReplayCaptureOutcome.SETTLED_PASS_DEADLINE
                    else NativeReplayCaptureOutcome.SETTLED)
                return
            }
        } catch (error: Throwable) {
            // Only resources and the exact immutable uncertain request enter queue-owned quarantine.
        }
        enrollment.quarantine(retaining = pendingRequest)
        complete(NativeReplayCaptureOutcome.QUARANTINED)
    }

    private companion object { const val MAXIMUM_PASS_NANOSECONDS = 50_000_000L }
}

/** Called only after every original main, encoding and append operation physically returned. */
private fun settleNativeCapture(queue: RuntimeQueueOwner, authority: NativeReplayAuthority,
    enrollment: NativeReplayCaptureEnrollment, use: NativeReplayCapturePhysicalUse?, started: Boolean): Boolean {
    if (use == null) enrollment.cancelUnused() else use.settle()
    if (started && authority.stop().awaitExact() != NativeReplayAuthorityStop.SETTLED) return false
    return queue.finishNativeReplayCapture(enrollment).awaitExact() == NativeReplayCaptureFinish.SETTLED
}

/** Raster branch of the same physical capture owner; no observer, timer, transport or worker of its own. */
private class NativeRasterCaptureRun(
    private val queue: RuntimeQueueOwner, private val authority: NativeReplayAuthority,
    private val prepared: NativeRasterPreparedAuthority, private val binding: AnnotatedCaptureBinding,
    private val versions: RuntimeVersions, private val platform: NativeReplayCapturePlatform,
    private val fence: NativeReplayCaptureFence, private val completion: SdkFuture<NativeReplayCaptureOutcome>,
    private val observation: AtomicReference<NativeReplayCaptureCompletion?>,
    private val collectorCurrent: AtomicReference<(() -> Boolean)?>, private val onCommitted: () -> Unit,
) {
    private val selected = prepared.selection
    private val clock = queue.nativeReplayCaptureClock()
    private val privacy = platform.privacyWitness()

    fun execute() {
        var enrollment: NativeReplayCaptureEnrollment? = null
        var use: NativeReplayCapturePhysicalUse? = null
        var started = false
        var pending: NativeRasterPreparedRequest? = null
        var appendSubmitted = false
        // Set inside the main callback before handing a candidate to its fallible final check.
        val candidate = AtomicReference<AnnotatedRasterCandidate?>()
        var failed = false
        var cleanupFailed = false
        var frames = 0
        var stage = NativeCaptureStage.BEFORE_LOOP
        var recoverRoot = false
        fun local() = fence.isCurrent() && privacy() && authority.belongsTo(queue, prepared) && selected.isCurrent()
        try {
            check(local() && fence.mayCollect() && binding.isCurrent())
            enrollment = queue.enrollNativeReplayCapture().awaitExact()
            val originalEnrollment = checkNotNull(enrollment)
            check(local() && fence.mayCollect())
            use = checkNotNull(originalEnrollment.takePhysicalUse())
            val originalUse = checkNotNull(use)
            check(local() && prepared.isCurrent())
            started = true
            val permit = checkNotNull(authority.startRaster(prepared, originalUse).awaitExact())
            val admission = checkNotNull(authority.captureAdmission(permit, originalUse).awaitExact())
            fun current() = local() && binding.isCurrent() && permit.isCurrent() && admission.isCurrent() && local()
            check(current())
            var sealer = NativeRasterSealer(permit.replayId, permit.identity, permit.sealingPolicy(), versions,
                binding.sourceIdentity, ::current)
            var minimumFork: NativeRasterSealer? = null
            var firstContinuous: Long? = null
            var previousContinuous: Long? = null
            val minimum = checkNotNull(admission.input.config.privacy).replay.minimumDurationSeconds.toLong() * 1_000_000_000L
            fun append() {
                stage = NativeCaptureStage.DURABLE_APPEND
                check(current())
                appendSubmitted = true
                when (queue.appendNativeRaster(checkNotNull(pending), admission, originalUse).awaitExact()) {
                    is NativeReplayAppendOutcome.Committed -> { pending = null; frames++; runCatching { onCommitted() } }
                    is NativeReplayAppendOutcome.CommittedThenWithdrawn -> { pending = null; frames++; error("raster-committed-after-withdrawal") }
                    is NativeReplayAppendOutcome.Rejected -> { pending?.clearRejected(); pending = null; error("raster-append-refused") }
                }
                appendSubmitted = false
                check(current())
            }
            while (!fence.gracefulStopRequested()) {
                check(current() && fence.mayCollect())
                stage = NativeCaptureStage.COLLECTOR
                val result = selected.consumeOriginalWindow(::current, fence::gracefulStopRequested) { _, window, rootCurrent ->
                    check(rootCurrent() && current() && fence.mayCollect())
                    val continuous = clock.elapsedRealtimeNanos()
                    val timestamp = clock.wallNowEpochMillis()
                    check(continuous >= 0 && timestamp in 1..253_402_300_799_999L)
                    val frame = binding.capture(window as android.view.Window,
                        { rootCurrent() && current() && fence.mayCollect() }, clock::elapsedRealtimeNanos)
                    if (!candidate.compareAndSet(null, frame)) {
                        try { frame.close() } catch (error: Throwable) { cleanupFailed = true; throw error }
                        error("outstanding-raster-candidate")
                    }
                    check(frame.sourceIdentity === binding.sourceIdentity && binding.isCurrent())
                    NativeReplayCollectionAttempt.RasterCaptured(frame, continuous, timestamp) { cleanupFailed = true }
                }.awaitExact()
                if (result === NativeReplayCollectionAttempt.LocalStop) break
                val actual = checkNotNull(result as? NativeReplayCollectionAttempt.RasterCaptured)
                check(candidate.get() === actual.frame && current() && fence.mayCollect())
                val previous = previousContinuous
                check(previous == null || actual.continuous >= previous && actual.continuous - previous >= 1_000_000_000L)
                previousContinuous = actual.continuous
                collectorCurrent.set { current() && fence.mayCollect() }
                try {
                    val first = firstContinuous
                    if (first == null) {
                        firstContinuous = actual.continuous
                        stage = NativeCaptureStage.SEAL_REQUEST
                        val fork = sealer.fork()
                        pending = fork.seal(actual.frame, actual.timestamp)
                        if (minimum == 0L) { append(); sealer = fork } else minimumFork = fork
                    } else if (minimumFork != null && actual.continuous - first < minimum) {
                        // A real intermediate sample is discarded, never encoded or advanced.
                        actual.frame.close()
                    } else {
                        minimumFork?.let { append(); sealer = it; minimumFork = null }
                        check(current() && fence.mayCollect())
                        stage = NativeCaptureStage.SEAL_REQUEST
                        val fork = sealer.fork()
                        pending = fork.seal(actual.frame, actual.timestamp)
                        append(); sealer = fork
                    }
                } finally {
                    try { actual.frame.close() }
                    catch (error: Throwable) { cleanupFailed = true; throw error }
                    finally {
                        if (actual.frame.hasCleanupFailure()) cleanupFailed = true
                        candidate.compareAndSet(actual.frame, null)
                    }
                }
                if (!platform.awaitNext(fence.withdrawn)) break
            }
            // A stop cannot prove a minimum from elapsed time alone. Drop an unqualified first frame.
            pending?.clearRejected(); pending = null
        } catch (_: Throwable) {
            failed = true
            if (!appendSubmitted) { pending?.clearRejected(); pending = null }
            recoverRoot = runCatching {
                pending == null && fence.mayCollect() && privacy() &&
                    (selected.observedRootBoundary() || !binding.isCurrent())
            }.getOrDefault(false)
        }
        collectorCurrent.set(null); fence.withdraw()
        if (binding.hasCleanupFailure()) cleanupFailed = true
        val remainingCandidate = candidate.getAndSet(null)
        try { remainingCandidate?.close() } catch (_: Throwable) { cleanupFailed = true }
        finally { if (remainingCandidate?.hasCleanupFailure() == true) cleanupFailed = true }
        fun quarantine() {
            val request = pending
            if (request == null) enrollment?.quarantine() else enrollment?.quarantineRaster(request)
        }
        var outcome = NativeReplayCaptureOutcome.SETTLED
        if (cleanupFailed) { quarantine(); outcome = NativeReplayCaptureOutcome.QUARANTINED }
        else if (enrollment != null) {
            try {
                if (!settleNativeCapture(queue, authority, checkNotNull(enrollment), use, started)) {
                    quarantine(); outcome = NativeReplayCaptureOutcome.QUARANTINED
                }
            } catch (_: Throwable) { quarantine(); outcome = NativeReplayCaptureOutcome.QUARANTINED }
        }
        if (outcome != NativeReplayCaptureOutcome.QUARANTINED) {
            pending?.clearRejected(); pending = null
            if (recoverRoot) outcome = NativeReplayCaptureOutcome.SETTLED_ROOT_CHANGED
        }
        observation.set(NativeReplayCaptureCompletion(stage, frames, if (failed) NativeCaptureFailureKind.OTHER else null, outcome))
        completion.complete(outcome)
    }
}
