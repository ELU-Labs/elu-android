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

internal enum class NativeReplayCaptureOutcome { SETTLED, SETTLED_PASS_DEADLINE, QUARANTINED }

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
    fun createCollector(): NativeReplayCaptureCollector
    fun createCollector(profile: NativeCapturePassProfile): NativeReplayCaptureCollector = createCollector()
    fun createCollector(masking: NativeMaskingProfile, profile: NativeCapturePassProfile?): NativeReplayCaptureCollector =
        if (profile == null) createCollector() else createCollector(profile)
    fun awaitNext(withdrawn: CountDownLatch): Boolean
}

internal fun interface NativeReplayCaptureCollector {
    fun collect(root: Any, ordinal: Long, timestamp: Long, fence: NativeCollectionFence,
        current: () -> Boolean, unresolvedBlockRules: Boolean): NativeMaskedSnapshot
}

internal object AndroidNativeReplayCapturePlatform : NativeReplayCapturePlatform {
    override val apiLevel get() = Build.VERSION.SDK_INT
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
    override fun awaitNext(withdrawn: CountDownLatch) = !withdrawn.await(NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
}

/**
 * Private physical interval. Caller exclusively borrows authority/selection until finished, then
 * disposes their resources separately. Neither watcher close nor cancellation is settlement.
 * No public stack constructs this owner; API23-28 and unsupported window geometry remain denied.
 */
internal class NativeReplayCaptureOwner private constructor(
    private val fence: NativeReplayCaptureFence,
    private val completion: SdkFuture<NativeReplayCaptureOutcome>,
    private val completionObservation: AtomicReference<NativeReplayCaptureCompletion?>,
    @Suppress("unused") private val lifetime: Any,
) : AutoCloseable {
    fun withdraw() = fence.withdraw()
    fun stop(): Future<NativeReplayCaptureOutcome> { withdraw(); return completion }
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
            /** A post-commit hint only; no immutable request or raw frame is exposed. */
            onCommitted: () -> Unit = {},
        ): NativeReplayCaptureOwner? {
            // No persistable guard, enrollment, clock sample or thread on unsupported API levels.
            if ((platform.apiLevel < 29 || !authority.belongsTo(queue, prepared)).also { nativeStartTrace.mark(NativeStartPhase.CAPTURE_OWNER_ELIGIBLE, !it) }) return null
            val lifetime = Any()
            val fence = NativeReplayCaptureFence(WeakReference(lifetime))
            val completion = object : SdkFuture<NativeReplayCaptureOutcome>() {
                override fun cancel(mayInterruptIfRunning: Boolean) = false
            }
            val observation = AtomicReference<NativeReplayCaptureCompletion?>()
            val owner = NativeReplayCaptureOwner(fence, completion, observation, lifetime)
            val run = NativeReplayCaptureRun(queue, authority, prepared, versions, platform, fence, completion, observation, onCommitted, nativeStartTrace)
            val thread = Thread({ run.execute() }, "elu-native-capture").apply { isDaemon = true }
            try { thread.start() }
            catch (error: Throwable) {
                fence.withdraw()
                // Thread.NEW proves no enrollment can have begun; any uncertain start stays pending.
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
private class NativeReplayCaptureFence(private val lifetime: WeakReference<Any>) {
    val collection = NativeCollectionFence()
    val withdrawn = CountDownLatch(1)
    fun isCurrent() = lifetime.get() != null && collection.isCurrent() && withdrawn.count != 0L
    fun withdraw() { collection.withdraw(); withdrawn.countDown() }
}

/** A deadline carries no frame; only the exact original collection callback can create it. */
private sealed class NativeReplayCollectionAttempt {
    class Captured(val frame: NativeMaskedSnapshot, val continuous: Long) : NativeReplayCollectionAttempt()
    object CollectorDeadline : NativeReplayCollectionAttempt()
}

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
    private val onCommitted: () -> Unit,
    private val nativeStartTrace: NativeStartTrace,
) {
    private val selection = prepared.selection
    private val clock: RuntimeCaptureClock = queue.nativeReplayCaptureClock()

    private fun local(): Boolean = fence.isCurrent() && authority.belongsTo(queue, prepared) &&
        selection.isCurrent() && fence.isCurrent()
    private fun current(permit: NativeReplayPermit, admission: NativeReplayCaptureAdmission): Boolean =
        local() && admission.permit === permit && admission.isCurrent() && local()
    private fun requireCurrent(value: Boolean) { check(value) { "Native capture withdrawn" } }

    fun execute() {
        nativeStartTrace.mark(NativeStartPhase.CAPTURE_THREAD_ENTERED)
        var enrollment: NativeReplayCaptureEnrollment? = null
        var physicalUse: NativeReplayCapturePhysicalUse? = null
        var pendingRequest: PreparedReplayRequest? = null
        var buffer: NativeReplayFrameBuffer? = null
        var startSubmitted = false
        var diagnosticStage = NativeCaptureStage.BEFORE_LOOP
        var completedFrames = 0
        var passFailure: NativeCaptureFailureKind? = null
        var retryablePassDeadline = false
        var completedFailure: NativeCaptureFailureKind? = null
        fun complete(outcome: NativeReplayCaptureOutcome) {
            completionObservation.set(NativeReplayCaptureCompletion(diagnosticStage, completedFrames, completedFailure, outcome))
            completion.complete(outcome)
        }
        val passProfile = if (nativeStartTrace === NativeStartTrace.NONE) null else NativeCapturePassProfile()
        try {
            // Local selection/lifetime only until original physical accounting is reserved.
            requireCurrent(local())
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
            requireCurrent(local() && permit.isCurrent() && local())
            nativeStartTrace.mark(NativeStartPhase.ADMISSION_BEGIN)
            val admission = authority.captureAdmission(permit, physicalUse).awaitExact().also { nativeStartTrace.mark(NativeStartPhase.ADMISSION_RESULT, it != null) } ?: error("Native admission denied")
            requireCurrent(current(permit, admission))
            requireCurrent(!admission.hasUnresolvedBlockRules)
            val frames = NativeReplayFrameBuffer(admission.minimumDurationSeconds).also { buffer = it }
            val sealer = NativeReplaySealer(permit.replayId, admission.identity, admission.authorization,
                admission.privacy, admission.profile, versions)
            requireCurrent(current(permit, admission))
            nativeStartTrace.mark(NativeStartPhase.CAPTURE_LOOP_READY)
            var collector: NativeReplayCaptureCollector? = null // implementation retains only weak View projections
            // Keep the existing capped exponential cadence across this original owner's attempts.
            // Successful frames do not reset it; no session/config/whole-run deadline is extended.
            var collectorRetryDelayMillis = NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS
            while (true) {
                diagnosticStage = NativeCaptureStage.LOOP_CURRENT
                requireCurrent(current(permit, admission))
                val ordinal = frames.nextFrameOrdinal
                passFailure = null
                diagnosticStage = NativeCaptureStage.ROOT_COLLECT
                val captured = selection.consumeOriginalRoot<NativeReplayCollectionAttempt>({ current(permit, admission) }) { root, rootCurrent ->
                    requireCurrent(current(permit, admission) && rootCurrent())
                    val continuous = clock.elapsedRealtimeNanos()
                    passProfile?.begin(continuous)
                    requireCurrent(continuous >= 0)
                    val timestamp = clock.wallNowEpochMillis()
                    requireCurrent(timestamp in 1..253_402_300_799_999L)
                    fun withinPass(): Boolean {
                        // rootCurrent includes this same current(permit, admission) check.
                        if (!rootCurrent()) return false
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
                    requireCurrent(withinPass())
                    diagnosticStage = NativeCaptureStage.COLLECTOR
                    val originalCollector = collector ?: platform.createCollector(admission.profile, passProfile).also { collector = it }
                    requireCurrent(withinPass())
                    passProfile?.configMaterialization()
                    val unresolvedBlockRules = admission.hasUnresolvedBlockRules
                    val frame = try {
                        originalCollector.collect(root, ordinal, timestamp, fence.collection, ::withinPass,
                            unresolvedBlockRules)
                    } catch (error: NativeCollectionException) {
                        // Only this known collector timeout may retain the original accepted buffer.
                        // The collector has not returned/committed a frame; no seal or append ran.
                        if (completedFrames == 0 || error.failure != NativeCollectionFailure.WITHDRAWN ||
                            passFailure != NativeCaptureFailureKind.PASS_DEADLINE || pendingRequest != null) throw error
                        requireCurrent(rootCurrent() && current(permit, admission) && rootCurrent())
                        nativeStartTrace.captureFailed(NativeCaptureStage.COLLECTOR, completedFrames,
                            NativeCaptureFailureKind.PASS_DEADLINE)
                        if (passProfile != null) nativeStartTrace.captureProfile(passProfile)
                        // Selection still executes its exact root/current-authority postvalidation.
                        return@consumeOriginalRoot NativeReplayCollectionAttempt.CollectorDeadline
                    }
                    requireCurrent(withinPass())
                    NativeReplayCollectionAttempt.Captured(frame, continuous)
                }.awaitExact() ?: error("Native collection denied")
                requireCurrent(current(permit, admission))
                if (captured === NativeReplayCollectionAttempt.CollectorDeadline) {
                    // Same fence, enrollment, selection, sealer and buffer. Wait off main in the
                    // existing one-second cancellable ticks, checking original authority each time.
                    var remaining = collectorRetryDelayMillis
                    while (remaining > 0) {
                        requireCurrent(current(permit, admission))
                        requireCurrent(platform.awaitNext(fence.withdrawn))
                        requireCurrent(current(permit, admission))
                        remaining -= NATIVE_REPLAY_CAPTURE_INTERVAL_MILLIS
                    }
                    collectorRetryDelayMillis = minOf(NATIVE_REPLAY_MAXIMUM_DEADLINE_RETRY_MILLIS,
                        collectorRetryDelayMillis * 2)
                    continue
                }
                check(captured is NativeReplayCollectionAttempt.Captured)
                diagnosticStage = NativeCaptureStage.FRAME_APPEND
                frames.append(captured.frame, captured.continuous)
                if (completedFrames < Int.MAX_VALUE) completedFrames += 1
                if (frames.isReady) {
                    diagnosticStage = NativeCaptureStage.SEAL_PREFIX
                    val prefix = frames.beginSealing()
                    requireCurrent(current(permit, admission))
                    diagnosticStage = NativeCaptureStage.SEAL_REQUEST
                    pendingRequest = sealer.seal(prefix)
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
                    diagnosticStage = NativeCaptureStage.COMMIT_FRAME
                    frames.committed(prefix)
                }
                requireCurrent(current(permit, admission))
                diagnosticStage = NativeCaptureStage.WAIT_NEXT
                if (!platform.awaitNext(fence.withdrawn)) break
            }
        } catch (error: Throwable) {
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
        fence.withdraw()
        buffer?.withdraw()
        if (enrollment == null) { complete(NativeReplayCaptureOutcome.SETTLED); return }
        try {
            // All submitted main/CPU/durable work is finished before marking physical completion.
            if (physicalUse == null) enrollment.cancelUnused() else physicalUse.settle()
            if (startSubmitted && authority.stop().awaitExact() != NativeReplayAuthorityStop.SETTLED) {
                enrollment.quarantine(retaining = pendingRequest)
                complete(NativeReplayCaptureOutcome.QUARANTINED)
                return
            }
            // No-start still needs this exact lane to flush any original prepared-guard denial.
            if (queue.finishNativeReplayCapture(enrollment).awaitExact() == NativeReplayCaptureFinish.SETTLED) {
                // Expose the hint only after the original physical and durable accounting settled.
                complete(if (retryablePassDeadline) NativeReplayCaptureOutcome.SETTLED_PASS_DEADLINE
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
