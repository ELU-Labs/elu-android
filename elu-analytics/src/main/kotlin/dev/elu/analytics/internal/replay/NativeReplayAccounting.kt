package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.concurrent.SdkFuture

import dev.elu.analytics.internal.config.V2ConfigAuthorityWitness
import dev.elu.analytics.internal.runtime.RuntimeCaptureAuthorityState
import dev.elu.analytics.internal.runtime.RuntimeReplayStoredRow

/** Storage observations and receipts are descriptive; neither grants recorder permission. */
internal class NativeReplaySessionObservation private constructor(
    private val owner: Any,
    val source: V2ConfigAuthorityWitness,
    val capture: RuntimeCaptureAuthorityState.Authorized,
    val session: NativeReplaySessionState.Session,
    val currentSelected: Boolean,
) {
    fun belongsTo(value: Any): Boolean = owner === value
    companion object {
        fun issue(owner: Any, source: V2ConfigAuthorityWitness, capture: RuntimeCaptureAuthorityState.Authorized,
            session: NativeReplaySessionState.Session, currentSelected: Boolean) =
            NativeReplaySessionObservation(owner, source, capture, session, currentSelected)
    }
}

internal class NativeReplayStartReceipt private constructor(
    private val owner: Any,
    val namespaceHash: String,
    val streamId: String,
    val key: NativeReplaySessionState.Key,
    val firstStartAt: String,
    val epoch: String,
    val replayId: String,
    val anchor: NativeReplayClockAnchor,
) {
    fun belongsTo(value: Any): Boolean = owner === value
    companion object {
        fun issue(owner: Any, namespaceHash: String, streamId: String, key: NativeReplaySessionState.Key,
            firstStartAt: String, epoch: String, replayId: String, anchor: NativeReplayClockAnchor) =
            NativeReplayStartReceipt(owner, namespaceHash, streamId, key, firstStartAt, epoch, replayId, anchor)
    }
}

/** Original raw nanoseconds never rebase on later wall observations or refresh rounding. */
internal data class NativeReplayClockAnchor(
    val key: NativeReplaySessionState.Key,
    val firstStartAt: String,
    val originNanoseconds: Long,
    val consumedAtOrigin: Long,
    val lastNanoseconds: Long = originNanoseconds,
) {
    data class Sample(val anchor: NativeReplayClockAnchor, val elapsedMicroseconds: Long)
    fun observe(now: Long, retainedFloor: Long): Sample? {
        val elapsed = elapsedMicrosecondsOrInvalid(now, retainedFloor)
        if (elapsed < 0) return null
        return Sample(copy(lastNanoseconds = now), elapsed)
    }

    /** Pure arithmetic. Guards need the elapsed value, not an unused copied anchor. */
    internal fun elapsedMicrosecondsOrInvalid(now: Long, retainedFloor: Long): Long {
        if (now < 0 || originNanoseconds < 0 || now < lastNanoseconds || now < originNanoseconds ||
            consumedAtOrigin !in 0..NativeReplaySessionState.MAXIMUM_MICROSECONDS ||
            retainedFloor !in 0..NativeReplaySessionState.MAXIMUM_MICROSECONDS) return -1L
        val delta = now - originNanoseconds
        val rounded = delta / 1_000 + if (delta % 1_000 == 0L) 0L else 1L
        val elapsed = minOf(NativeReplaySessionState.MAXIMUM_MICROSECONDS,
            consumedAtOrigin + minOf(rounded, NativeReplaySessionState.MAXIMUM_MICROSECONDS))
        return maxOf(retainedFloor, elapsed)
    }
}

internal object NativeReplayAccounting {
    const val KEY = "native_authority"
    fun row(value: NativeReplaySessionState): RuntimeReplayStoredRow = RuntimeReplayStoredRow(KEY, 1, value.encoded())
    fun read(row: RuntimeReplayStoredRow): NativeReplaySessionState {
        require(row.key == KEY && row.storageSchemaVersion == 1L)
        return NativeReplaySessionState.decode(row.payload)
    }
}

/** A retained guard owns no queue, executor, Activity or mutable caller state. */
internal class NativeReplayScope(private val clock: dev.elu.analytics.internal.runtime.RuntimeCaptureClock) {
    private val monitor = Any()
    private var generation: Any = Any()
    private var intent: Any = Any()
    private var closed = false
    private var reconciling = false
    private var identity: dev.elu.analytics.internal.core.IdentityState? = null
    private var capture: RuntimeCaptureAuthorityState.Authorized? = null
    // Keep sampled floors primitive; all clock reads and monitor boundaries stay live.
    private var lastWall = 0L
    private var lastContinuous = 0L
    private var hasClockSample = false
    private var pending: NativeReplaySessionState.Key? = null

    // Parsed immutable values only; live authority, clocks and identity are still
    // checked in their original order on every use under the same monitor.
    private val timestampCache = arrayOfNulls<Pair<String, dev.elu.analytics.internal.config.V1ExactTimestamp>>(3)
    private fun exactTimestamp(value: String, slot: Int): dev.elu.analytics.internal.config.V1ExactTimestamp {
        val cached = timestampCache[slot]
        if (cached?.first == value) return cached.second
        val parsed = dev.elu.analytics.internal.config.V1ConfigJson.parseExactTimestamp(value)
        timestampCache[slot] = value to parsed
        return parsed
    }

    private fun key(value: dev.elu.analytics.internal.core.IdentityState?, authority: RuntimeCaptureAuthorityState.Authorized?) =
        value?.session?.let { session -> authority?.let { NativeReplaySessionState.Key(it.configSiteId, session.id, session.startedAt) } }

    // Same three immutable key fields, without allocating a temporary Key for
    // every property boundary. The caller still holds the original monitor.
    private fun matchesKey(value: dev.elu.analytics.internal.core.IdentityState?,
        authority: RuntimeCaptureAuthorityState.Authorized?, expected: NativeReplaySessionState.Key): Boolean {
        val session = value?.session ?: return false
        return authority != null && authority.configSiteId == expected.siteId &&
            session.id == expected.sessionId && session.startedAt == expected.sessionStartedAt
    }

    fun publish(value: dev.elu.analytics.internal.core.IdentityState?, authority: RuntimeCaptureAuthorityState.Authorized?) = synchronized(monitor) {
        val prior = identity
        if (capture !== authority || key(prior, capture) != key(value, authority) || prior?.revision != value?.revision ||
            prior?.contextRevision != value?.contextRevision || prior?.optedOut != value?.optedOut ||
            prior?.session?.lifecycle != value?.session?.lifecycle) generation = Any()
        if (prior?.revision != value?.revision || prior?.contextRevision != value?.contextRevision ||
            prior?.optedOut != value?.optedOut || prior?.session?.id != value?.session?.id ||
            prior?.session?.startedAt != value?.session?.startedAt || prior?.session?.lifecycle != value?.session?.lifecycle) intent = Any()
        if (prior?.session?.id != value?.session?.id || prior?.session?.startedAt != value?.session?.startedAt) { hasClockSample = false }
        identity = value; capture = authority
    }

    fun beforeIdentityWrite(value: dev.elu.analytics.internal.core.IdentityState) = synchronized(monitor) {
        val prior = identity
        if (key(prior, capture) != key(value, capture) || prior?.revision != value.revision ||
            prior?.contextRevision != value.contextRevision || prior?.optedOut != value.optedOut ||
            prior?.session?.lifecycle != value.session?.lifecycle) { generation = Any(); intent = Any() }
    }

    /** Same-owner database recovery blocks reads without minting new source or clock tokens. */
    fun suspendForReconciliation() = synchronized(monitor) { reconciling = true }
    fun resumeAfterReconciliation(value: dev.elu.analytics.internal.core.IdentityState?, authority: RuntimeCaptureAuthorityState.Authorized?) = synchronized(monitor) {
        publish(value, authority)
        reconciling = false
    }
    fun invalidate() = synchronized(monitor) { generation = Any(); intent = Any() }
    fun close() = synchronized(monitor) { closed = true; generation = Any(); intent = Any() }
    fun sameIntent(value: Any) = synchronized(monitor) { !closed && !reconciling && intent === value }
    fun pendingDenial(): NativeReplaySessionState.Key? = synchronized(monitor) { pending }
    fun resolved(key: NativeReplaySessionState.Key) = synchronized(monitor) {
        if (pending == key) {
            // A durable restriction settles the write obligation, never the old guard's denial.
            // A replaced actual session already has independent tokens and must remain available.
            if (key(identity, capture) == key) { generation = Any(); intent = Any() }
            pending = null
        }
    }

    fun issue(source: V2ConfigAuthorityWitness, authority: RuntimeCaptureAuthorityState.Authorized,
        session: NativeReplaySessionState.Session, anchor: NativeReplayClockAnchor? = null): NativeReplayGuard? {
        val token = synchronized(monitor) { if (closed || reconciling || capture !== authority || key(identity, capture) != session.key) null else generation to intent }
            ?: return null
        return NativeReplayGuard(this, token.first, token.second, source, authority, session, anchor).takeIf { it.isCurrent() }
    }

    internal fun check(token: Any, source: V2ConfigAuthorityWitness, authority: RuntimeCaptureAuthorityState.Authorized,
        original: NativeReplaySessionState.Session, anchor: NativeReplayClockAnchor?): Boolean {
        // Source consume may enter its own lifecycle lock. Never nest it inside this monitor.
        if (synchronized(monitor) { closed || reconciling || generation !== token }) return false
        if (!source.isCurrent()) return false
        val allowed = synchronized(monitor) {
            if (closed || reconciling || generation !== token || capture !== authority || !matchesKey(identity, capture, original.key) ||
                pending == original.key || original.clockDenied || original.interrupted) return@synchronized false
            val current = identity ?: return@synchronized false
            val session = current.session ?: return@synchronized false
            if (current.optedOut || session.lifecycle != dev.elu.analytics.internal.core.SessionLifecycle.ACTIVE) return@synchronized false
            val wall: Long; val continuous: Long
            try { wall = clock.wallNowEpochMillis(); continuous = clock.elapsedRealtimeNanos() }
            catch (_: Exception) { pending = original.key; return@synchronized false }
            // Sampling and floor comparison share one monitor, including concurrent consumers.
            if (wall < 1 || continuous < 0 || (hasClockSample && wall < lastWall) ||
                (hasClockSample && continuous < lastContinuous)) {
                pending = original.key; return@synchronized false
            }
            lastWall = wall; lastContinuous = continuous; hasClockSample = true
            if (anchor != null) {
                val anchorElapsed = anchor.elapsedMicrosecondsOrInvalid(continuous, original.elapsedFloorMicroseconds)
                val first = original.firstStartAt
                val wallNanos = first?.let { dev.elu.analytics.internal.config.V1ExactTimestamp.fromEpochMillis(wall)
                    .elapsedNanosecondsFloorSince(exactTimestamp(it, 0)) }
                if (anchorElapsed < 0 || wallNanos == null) { pending = original.key; return@synchronized false }
                val wallMicroseconds = wallNanos / 1_000 + if (wallNanos % 1_000 == 0L) 0 else 1
                if (maxOf(anchorElapsed, wallMicroseconds) >= original.maximumDurationSeconds * 1_000_000L) return@synchronized false
            }
            val elapsed = continuous - authority.monotonicStartedAt
            val now = dev.elu.analytics.internal.config.V1ExactTimestamp.fromEpochMillis(wall)
            if (elapsed < 0 || elapsed >= authority.monotonicBudget || now >= authority.configExpiresAt) return@synchronized false
            val start = exactTimestamp(session.startedAt, 1)
            val activity = exactTimestamp(session.lastActivityAt, 2)
            val idle = now.elapsedNanosecondsFloorSince(activity) ?: return@synchronized false
            val age = now.elapsedNanosecondsFloorSince(start) ?: return@synchronized false
            idle < minOf(session.timeoutSeconds, authority.idleTimeoutSeconds) * 1_000_000_000L &&
                age < authority.maximumDurationSeconds * 1_000_000_000L &&
                !closed && !reconciling && generation === token && capture === authority && pending != original.key
        }
        return allowed && source.isCurrent() && synchronized(monitor) {
            !closed && !reconciling && generation === token && capture === authority && pending != original.key
        }
    }
}

internal class NativeReplayGuard internal constructor(
    private val scope: NativeReplayScope,
    private val token: Any,
    private val intent: Any,
    private val source: V2ConfigAuthorityWitness,
    private val capture: RuntimeCaptureAuthorityState.Authorized,
    private val session: NativeReplaySessionState.Session,
    private val anchor: NativeReplayClockAnchor?,
) {
    fun isCurrent(): Boolean = scope.check(token, source, capture, session, anchor)
    internal fun intentIsCurrent(): Boolean = source.isCurrent() && scope.sameIntent(intent)

    /**
     * Immutable implication only, never a currentness result. The unanchored input
     * reads the same live scope/source/capture and only its key and denial flags
     * from its retained session. A matching started guard adds the anchor budget.
     * A later generation, close, reconciliation or source change is still checked
     * by isCurrent on every use; no live value is retained by this relationship.
     */
    internal fun coversUnstartedInput(input: NativeReplayGuard, receipt: NativeReplayStartReceipt): Boolean =
        scope === input.scope && token === input.token && intent === input.intent &&
            source === input.source && capture === input.capture && session.key == input.session.key &&
            input.anchor == null && !input.session.clockDenied && !input.session.interrupted &&
            anchor != null && anchor === receipt.anchor && anchor.key == session.key &&
            anchor.firstStartAt == session.firstStartAt && session.key == receipt.key &&
            session.firstStartAt == receipt.firstStartAt && session.activeEpoch == receipt.epoch
}

/** Original durable inputs; projections return detached values, never mutable retained containers. */
internal class NativeReplayProjectionInput private constructor(
    private val owner: Any,
    val observation: NativeReplaySessionObservation,
    private val identityJson: String,
    val guard: NativeReplayGuard,
    val evaluatedAt: String,
) {
    val identity get() = dev.elu.analytics.internal.core.CoreStateCodec.decodeIdentity(org.json.JSONObject(identityJson))
    val config get() = dev.elu.analytics.internal.config.V1ConfigJson.parseConfig(observation.source.body)
    fun belongsTo(value: Any) = owner === value
    fun isCurrent() = guard.isCurrent()
    companion object {
        fun issue(owner: Any, observation: NativeReplaySessionObservation,
            identity: dev.elu.analytics.internal.core.IdentityState, guard: NativeReplayGuard, evaluatedAt: String) =
            NativeReplayProjectionInput(owner, observation,
                dev.elu.analytics.internal.core.CoreStateCodec.encodeIdentity(identity).toString(), guard, evaluatedAt)
    }
}

/** Resource-only retention. It has no owner, worker, source, Activity or callback reference. */
internal class NativeReplayCaptureResources(
    database: dev.elu.analytics.internal.runtime.RuntimeQueueDatabase?,
    lease: dev.elu.analytics.internal.runtime.RuntimeOwnershipLease?,
) {
    private var database = database
    private var lease = lease
    private var prepared: PreparedReplayRequest? = null
    private var quarantined = false
    @Synchronized fun updateDatabase(value: dev.elu.analytics.internal.runtime.RuntimeQueueDatabase?) {
        if (value != null || !quarantined) database = value
    }
    @Synchronized fun quarantine(retaining: PreparedReplayRequest?) {
        if (prepared == null) prepared = retaining
        if (!quarantined) { quarantined = true; synchronized(retained) { retained.add(this) } }
    }
    @Synchronized fun released() { if (!quarantined) { database = null; lease = null; prepared = null } }
    private companion object { val retained = mutableListOf<NativeReplayCaptureResources>() }
}

internal enum class NativeReplayCaptureFinish { SETTLED, PHYSICAL_WORK_PENDING, ACCOUNTING_PENDING, STALE }

/** Issued only by the queue. Physical completion and durable accounting completion are independent. */
internal class NativeReplayCaptureEnrollment private constructor(
    private val owner: Any,
    private val resources: NativeReplayCaptureResources,
) {
    private val monitor = Any()
    private var taken = false
    private var originalUse: NativeReplayCapturePhysicalUse? = null
    private var physicalFinished = false
    private var intakeClosed = false
    private var accountingFinished = false
    private var quarantined = false
    private var released = false
    internal val settlement = object : dev.elu.analytics.internal.concurrent.SdkFuture<Unit>() {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
    }
    fun belongsTo(value: Any) = owner === value
    fun takePhysicalUse(): NativeReplayCapturePhysicalUse? = synchronized(monitor) {
        if (taken || physicalFinished || intakeClosed || released || quarantined) null
        else { taken = true; NativeReplayCapturePhysicalUse.issue(this).also { originalUse = it } }
    }
    fun cancelUnused() {
        synchronized(monitor) { if (!taken && !released) { physicalFinished = true; intakeClosed = true } }
        notifyQuarantineIfPhysicallyFinished()
    }
    internal fun withdraw() = synchronized(monitor) { intakeClosed = true }
    internal fun matches(use: NativeReplayCapturePhysicalUse) = synchronized(monitor) { originalUse === use }
    internal fun isCurrent(use: NativeReplayCapturePhysicalUse) = synchronized(monitor) { originalUse === use && taken && !physicalFinished && !intakeClosed && !released && !quarantined }
    internal fun physicalIsFinished() = synchronized(monitor) { physicalFinished }
    internal fun isQuarantined() = synchronized(monitor) { quarantined }
    internal fun physicalFinished(use: NativeReplayCapturePhysicalUse) {
        synchronized(monitor) { if (originalUse === use) { physicalFinished = true; intakeClosed = true } }
        notifyQuarantineIfPhysicallyFinished()
    }
    fun quarantine(retaining: PreparedReplayRequest? = null) {
        retainQuarantine(retaining)
        notifyQuarantineIfPhysicallyFinished()
    }
    internal fun retainQuarantine(retaining: PreparedReplayRequest? = null) {
        synchronized(monitor) {
            // Release and quarantine have one terminal decision. This only moves local
            // resource references; no database, callback or executor work occurs here.
            if (released) return
            quarantined = true; intakeClosed = true
            resources.quarantine(retaining)
        }
    }
    internal fun notifyQuarantineIfPhysicallyFinished() {
        val terminal = synchronized(monitor) { quarantined && physicalFinished }
        if (terminal) settlement.completeExceptionally(IllegalStateException("Native capture accounting is quarantined"))
    }
    internal fun proveAccountingFinished() = synchronized(monitor) { accountingFinished = true }
    internal fun releaseIfFinished(): Boolean = synchronized(monitor) {
        if (released || quarantined || !physicalFinished || !accountingFinished) false
        else { released = true; resources.released(); true }
    }
    /** Queue calls only after clearing this exact slot, outside all ownership locks. */
    internal fun notifyReleased() {
        check(synchronized(monitor) { released })
        settlement.complete(Unit)
    }
    companion object {
        fun issue(owner: Any, resources: NativeReplayCaptureResources) = NativeReplayCaptureEnrollment(owner, resources)
    }
}

/** No public or test-created capability. Only an original enrollment can issue its one physical use. */
internal class NativeReplayCapturePhysicalUse private constructor(internal val enrollment: NativeReplayCaptureEnrollment) {
    fun isCurrent() = enrollment.isCurrent(this)
    fun settle() = enrollment.physicalFinished(this)
    companion object { fun issue(enrollment: NativeReplayCaptureEnrollment) = NativeReplayCapturePhysicalUse(enrollment) }
}
