package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.runtime.*
import java.util.Collections
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Local evidence only. Remote advertisement cannot populate either default. */
internal class NativeReplayCapabilities(
    transports: Set<V1ReplayTransport> = emptySet(),
    generations: Set<String> = emptySet(),
) {
    private val transports = Collections.unmodifiableSet(HashSet(transports))
    private val generations = Collections.unmodifiableSet(HashSet(generations))
    internal fun hasLocalEvidence(): Boolean =
        V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP) in transports && generations.isNotEmpty()
    fun transport(config: V1ParsedConfig): V1ReplayTransport? {
        val expected = V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)
        val capabilities = config.replayCapabilities ?: return null
        val generation = capabilities.replayProtocolGeneration ?: return null
        return expected.takeIf { expected in transports && expected in capabilities.advertisedTransports && generation in generations }
    }
}

/** Immutable full privacy bytes bound to the one original durable input. No recorder authority. */
internal class NativeReplayPrivacyProjection private constructor(
    val input: NativeReplayProjectionInput,
    val body: String,
    val transport: V1ReplayTransport,
    val protocolGeneration: String,
) {
    companion object {
        fun issue(input: NativeReplayProjectionInput, body: String, transport: V1ReplayTransport, generation: String) =
            NativeReplayPrivacyProjection(input, V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(body)).toString(Charsets.UTF_8), transport, generation)
    }
}

internal class NativeReplayPreparedProjection private constructor(
    private val owner: Any,
    val input: NativeReplayProjectionInput,
    val privacy: NativeReplayPrivacyProjection,
) {
    fun belongsTo(value: Any) = owner === value
    fun isCurrent() = input.isCurrent()
    companion object {
        fun issue(owner: Any, input: NativeReplayProjectionInput, privacy: NativeReplayPrivacyProjection) =
            NativeReplayPreparedProjection(owner, input, privacy)
    }
}

internal class NativeReplayStartedProjection private constructor(
    val receipt: NativeReplayStartReceipt,
    val guard: NativeReplayGuard,
) {
    companion object {
        fun issue(receipt: NativeReplayStartReceipt, guard: NativeReplayGuard) = NativeReplayStartedProjection(receipt, guard)
    }
}

internal class NativeReplayPreparedAuthority private constructor(
    private val owner: Any,
    internal val invocation: Any,
    internal val projection: NativeReplayPreparedProjection,
    internal val selection: NativeReplaySelection,
) {
    fun belongsTo(value: Any) = owner === value
    fun isCurrent() = projection.isCurrent() && selection.isCurrent()
    companion object {
        fun issue(owner: Any, invocation: Any, projection: NativeReplayPreparedProjection, selection: NativeReplaySelection) =
            NativeReplayPreparedAuthority(owner, invocation, projection, selection)
    }
}

/** Original interval permission; only an exact enrolled physical use can obtain append admission. */
internal class NativeReplayPermit private constructor(
    internal val prepared: NativeReplayPreparedAuthority,
    internal val started: NativeReplayStartedProjection,
    private val current: () -> Boolean,
) {
    val replayId: String get() = started.receipt.replayId
    fun isCurrent(): Boolean = current() && prepared.selection.isCurrent() && started.guard.isCurrent() && current()
    companion object {
        fun issue(prepared: NativeReplayPreparedAuthority, started: NativeReplayStartedProjection, current: () -> Boolean) =
            NativeReplayPermit(prepared, started, current)
    }
}

internal enum class NativeReplayAuthorityStop { SETTLED, UNRESOLVED, PHYSICAL_WORK_PENDING }

internal sealed interface NativeReplayAppendOutcome {
    data class Rejected(val reason: ReplayAppendRejection) : NativeReplayAppendOutcome
    data class Committed(val stored: ReplayAppendResult.Stored) : NativeReplayAppendOutcome
    data class CommittedThenWithdrawn(val stored: ReplayAppendResult.Stored) : NativeReplayAppendOutcome
}

/** Queue-issued original admission. Descriptive sealing inputs never replace its synchronous guard. */
internal class NativeReplayCaptureAdmission private constructor(
    private val owner: Any,
    internal val permit: NativeReplayPermit,
    internal val use: NativeReplayCapturePhysicalUse,
    val authorization: V1AuthorizedConfig,
) {
    private val localPrivacy = NativeViewPrivacy.snapshot()
    internal val input get() = permit.prepared.projection.input
    internal val receipt get() = permit.started.receipt
    internal val source get() = input.observation.source
    val identity get() = input.identity
    val privacy get() = permit.prepared.projection.privacy.body.toByteArray(Charsets.UTF_8)
    val profile get() = NativeMaskingProfile.select(checkNotNull(input.config.privacy).masking, V1PrivacyPlatform.ANDROID)
    val minimumDurationSeconds get() = checkNotNull(input.config.privacy).replay.minimumDurationSeconds
    // Immutable description of this original input; isCurrent still checks every live authority.
    val hasUnresolvedBlockRules = checkNotNull(input.config.privacy).masking.platformRules.any {
        it.platform == V1PrivacyPlatform.ANDROID && it.action == V1PlatformRuleAction.BLOCK
    }
    // A proof about these exact immutable guard/receipt references, not a cached
    // authorization decision. Foreign or unproved pairs keep both live checks.
    private val inputGuardCovered = permit.started.guard.coversUnstartedInput(input.guard, receipt)
    fun belongsTo(value: Any) = owner === value
    fun isCurrent() = localPrivacy.isCurrent() && use.isCurrent() && permit.isCurrent() &&
        (inputGuardCovered || input.isCurrent()) && use.isCurrent() && localPrivacy.isCurrent()
    companion object {
        fun issue(owner: Any, permit: NativeReplayPermit, use: NativeReplayCapturePhysicalUse, authorization: V1AuthorizedConfig) =
            NativeReplayCaptureAdmission(owner, permit, use, authorization)
    }
}

/** All waits stay on this private serial worker, never main or the queue owner lane. */
internal class NativeReplayAuthority(
    private val queue: RuntimeQueueOwner,
    private val capabilities: NativeReplayCapabilities = NativeReplayCapabilities(),
    private val deviceInEuTimezone: () -> Boolean,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "elu-native-authority").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val monitor = Any()
    private val owner = Any()
    private var invocation: Any = Any()
    private var closed = false
    private val closeResult = object : SdkFuture<NativeReplayAuthorityStop>() {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
    }
    private val permitEpoch = java.util.concurrent.atomic.AtomicReference<Any?>(invocation)
    private var active: NativeReplayPermit? = null
    private var receipt: NativeReplayStartReceipt? = null
    private var captureUse: NativeReplayCapturePhysicalUse? = null

    /** Local scope only: this never observes a persistable source/clock guard. */
    internal fun belongsTo(queue: RuntimeQueueOwner, prepared: NativeReplayPreparedAuthority): Boolean =
        this.queue === queue && prepared.belongsTo(owner) && current(prepared.invocation)

    private fun current(token: Any): Boolean = synchronized(monitor) { !closed && invocation === token }
    fun withdraw() = synchronized(monitor) { invocation = Any(); permitEpoch.set(invocation) }

    fun prepare(selection: NativeReplaySelection, nativeStartTrace: NativeStartTrace = NativeStartTrace.NONE): Future<NativeReplayPreparedAuthority?> {
        nativeStartTrace.mark(NativeStartPhase.PREPARE_BEGIN)
        val token = synchronized(monitor) { if (closed) null else Any().also { invocation = it; permitEpoch.set(it) } }
            .also { nativeStartTrace.mark(NativeStartPhase.PREPARE_OPEN, it != null) }
            ?: return SdkFuture.completedFuture(null)
        return submit {
            if ((receipt != null || !current(token) || !selection.validateCurrent().awaitExact()).also { nativeStartTrace.mark(NativeStartPhase.PREPARE_INITIAL_GUARD, !it) }) return@submit null
            if ((!current(token) || !selection.isCurrent()).also { nativeStartTrace.mark(NativeStartPhase.PREPARE_SELECTION_CURRENT, !it) }) return@submit null
            val input = queue.observeNativeReplayProjection().awaitExact().also { nativeStartTrace.mark(NativeStartPhase.PROJECTION_INPUT_RESULT, it != null) } ?: return@submit null
            if ((!current(token) || !selection.isCurrent() || !input.isCurrent()).also { nativeStartTrace.mark(NativeStartPhase.PROJECTION_INPUT_CURRENT, !it) }) return@submit null
            val projection = PrivacyStateProjector.projectNative(input, capabilities, deviceInEuTimezone()).also { nativeStartTrace.mark(NativeStartPhase.PRIVACY_PROJECTION_RESULT, it != null) } ?: return@submit null
            if ((!current(token) || !selection.isCurrent() || !input.isCurrent()).also { nativeStartTrace.mark(NativeStartPhase.PRIVACY_PROJECTION_CURRENT, !it) }) return@submit null
            val prepared = queue.prepareNativeReplayProjection(input, projection).awaitExact().also { nativeStartTrace.mark(NativeStartPhase.PREPARATION_RESULT, it != null) } ?: return@submit null
            if ((!current(token) || !prepared.isCurrent() || !selection.validateCurrent().awaitExact()).also { nativeStartTrace.mark(NativeStartPhase.PREPARATION_VALIDATED, !it) }) return@submit null
            if ((!current(token) || !prepared.isCurrent() || !selection.isCurrent()).also { nativeStartTrace.mark(NativeStartPhase.PREPARATION_CURRENT, !it) }) return@submit null
            NativeReplayPreparedAuthority.issue(owner, token, prepared, selection)
        }
    }

    fun start(prepared: NativeReplayPreparedAuthority, physicalUse: NativeReplayCapturePhysicalUse? = null): Future<NativeReplayPermit?> = submit {
        val token = prepared.invocation
        if (!prepared.belongsTo(owner) || receipt != null || active != null || captureUse != null) return@submit null
        // Retain the exact enrolled use before a source observation can create a denial or
        // close the queue. Its original settlement lane remains usable after logical close.
        if (physicalUse != null) {
            if (!queue.nativeReplayCaptureMatches(physicalUse)) return@submit null
            captureUse = physicalUse
        }
        if (!current(token) || !prepared.isCurrent()) return@submit null
        if (!prepared.selection.validateCurrent().awaitExact()) return@submit null
        if (receipt != null || active != null || captureUse !== physicalUse || !current(token) || !prepared.isCurrent()) return@submit null
        if (physicalUse != null && !physicalUse.isCurrent()) return@submit null
        val started = queue.beginNativeReplayAuthority(prepared.projection, physicalUse).awaitExact() ?: return@submit null
        // Retain the exact returned receipt before any selection, clock or cancellation check.
        receipt = started.receipt
        if (!current(token) || !started.guard.isCurrent() || !prepared.selection.validateCurrent().awaitExact() ||
            !current(token) || !prepared.selection.isCurrent() || !started.guard.isCurrent()) {
            stopOnWorker(); return@submit null
        }
        val epoch = permitEpoch // Retained permits must not retain this owner/worker through a closure.
        val permit = NativeReplayPermit.issue(prepared, started) { epoch.get() === token }
        synchronized(monitor) {
            if (!closed && invocation === token) active = permit
        }
        if (active !== permit) { stopOnWorker(); null } else permit
    }

    fun captureAdmission(permit: NativeReplayPermit, physicalUse: NativeReplayCapturePhysicalUse): Future<NativeReplayCaptureAdmission?> = submit {
        if (active !== permit || captureUse !== physicalUse || receipt !== permit.started.receipt || !permit.isCurrent()) return@submit null
        val result = queue.makeNativeReplayCaptureAdmission(permit, physicalUse).awaitExact() ?: return@submit null
        if (active !== permit || captureUse !== physicalUse || !permit.isCurrent() || !result.isCurrent()) null else result
    }

    fun stop(): Future<NativeReplayAuthorityStop> {
        withdraw()
        return submit { stopOnWorker() }
    }

    private fun stopOnWorker(): NativeReplayAuthorityStop {
        val outcome = settleOnWorker()
        if (synchronized(monitor) { closed } && outcome != NativeReplayAuthorityStop.PHYSICAL_WORK_PENDING) {
            worker.shutdown()
            // This is a disposition, not permission to release quarantined queue resources.
            closeResult.complete(outcome)
        }
        return outcome
    }

    private fun settleOnWorker(): NativeReplayAuthorityStop {
        active = null
        return try {
            captureUse?.let { use ->
                // Quarantine may make queue accounting unreadable before physical completion.
                // Keep this original worker/close receipt pending until that exact use ends.
                if (!use.enrollment.physicalIsFinished()) return NativeReplayAuthorityStop.PHYSICAL_WORK_PENDING
                val outcome = queue.stopNativeReplayCaptureAccounting(use).awaitExact()
                if (outcome != NativeReplayAuthorityStop.SETTLED) return outcome
                captureUse = null; receipt = null
                if (synchronized(monitor) { closed }) worker.shutdown()
                return NativeReplayAuthorityStop.SETTLED
            }
            receipt?.let { queue.stopNativeReplayAccounting(it).awaitExact() }
            queue.flushNativeReplayClockDenial().awaitExact()
            receipt = null
            if (synchronized(monitor) { closed }) worker.shutdown()
            NativeReplayAuthorityStop.SETTLED
        } catch (_: Exception) { NativeReplayAuthorityStop.UNRESOLVED }
    }

    override fun close() { closeAndWait() }

    /** Repeated close returns one noncancelable outcome; pending physical use cannot complete it. */
    fun closeAndWait(): SdkFuture<NativeReplayAuthorityStop> {
        synchronized(monitor) {
            if (closed) return closeResult
            closed = true; invocation = Any(); permitEpoch.set(null)
        }
        // Original queued work is not interrupted; its exact begin/stop result must be observed.
        // The physical owner's eventual stop retries this same worker after settling its use.
        val submitted = submit { stopOnWorker() }
        submitted.whenComplete { _, error ->
            if (error != null) closeResult.complete(NativeReplayAuthorityStop.UNRESOLVED)
        }
        return closeResult
    }

    private fun <T> submit(block: () -> T): SdkFuture<T> {
        val result = object : SdkFuture<T>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }
        try { worker.execute { try { result.complete(block()) } catch (error: Throwable) { result.completeExceptionally(error) } } }
        catch (error: java.util.concurrent.RejectedExecutionException) { result.completeExceptionally(error) }
        return result
    }
}

/** Never abandon a queued durable command because the waiting thread was interrupted. */
internal fun <T> Future<T>.awaitExact(): T {
    var interrupted = false
    try {
        while (true) try { return get() }
        catch (_: InterruptedException) { interrupted = true }
        catch (error: ExecutionException) { throw error.cause ?: error }
    } finally { if (interrupted) Thread.currentThread().interrupt() }
}
