package dev.elu.analytics.internal.facade

import dev.elu.analytics.EluFeatureFlagResult
import dev.elu.analytics.internal.runtime.NativeStartTrace
import dev.elu.analytics.internal.runtime.NativeStartPhase

import dev.elu.analytics.internal.config.V2ConfigAuthorityGate
import dev.elu.analytics.internal.config.V2ConfigAuthorityWitness
import dev.elu.analytics.internal.config.V1FlagAuthorizationResolution
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.flags.FlagCacheLeaseToken
import dev.elu.analytics.internal.flags.FlagJsonValue
import dev.elu.analytics.internal.flags.FlagReadResult
import dev.elu.analytics.internal.flags.FlagReloadResult
import dev.elu.analytics.internal.runtime.RuntimeAppendRejection
import dev.elu.analytics.internal.runtime.RuntimeAppendResult
import dev.elu.analytics.internal.runtime.RuntimeCaptureAuthorityTerminalReason
import dev.elu.analytics.internal.runtime.RuntimeCaptureAuthorityUpdateResult
import dev.elu.analytics.internal.runtime.RuntimeCaptureRejection
import dev.elu.analytics.internal.runtime.RuntimeCaptureResult
import dev.elu.analytics.internal.runtime.RuntimeLocalStateChange
import dev.elu.analytics.internal.runtime.RuntimeMutationChange
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeRecordDraft
import dev.elu.analytics.internal.runtime.RuntimeVersions
import dev.elu.analytics.internal.runtime.RuntimeWallTimestamps
import dev.elu.analytics.internal.runtime.StandaloneRuntime
import dev.elu.analytics.internal.runtime.RuntimeStartupObserver
import dev.elu.analytics.internal.runtime.RuntimeStartupObservation
import dev.elu.analytics.internal.runtime.RuntimeStartupPhase
import dev.elu.analytics.internal.runtime.RuntimeStartupLane
import dev.elu.analytics.internal.runtime.RuntimeStartupDrop
import dev.elu.analytics.internal.runtime.observeStartup
import java.util.Date
import java.util.EnumMap
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The feature-flag operations the facade needs. The owned flag client conforms to it; the facade
 * depends on injected operations while the internal standalone stack owns transport and lifetime.
 */
internal interface FacadeFlagClient : AutoCloseable {
    fun applyConfiguration(configBody: String?): SdkFuture<V1FlagAuthorizationResolution>

    fun reload(): SdkFuture<FlagReloadResult>

    fun read(key: String): SdkFuture<FlagReadResult>

    /** Checks the original client-owned wall/monotonic cache lease without extending it. */
    fun isCacheLeaseCurrent(token: FlagCacheLeaseToken): Boolean
}

/** The owned runtime pieces one facade drives. All three share the owner's single storage lane. */
internal class StandaloneStack(
    val runtime: StandaloneRuntime,
    val owner: RuntimeQueueOwner,
    val flags: FacadeFlagClient?,
)

/** Why the facade discarded a call. Counted per reason for the whole life of the facade. */
internal enum class EluFacadeDropReason {
    BLOCKED,
    OPTED_OUT,
    UNAUTHORIZED,
    BUFFER_FULL,
    CLOSED,
    INVALID_INPUT,
    RESERVED_PROPERTY,
    STORAGE,
}

/** Why the facade is refusing state-changing calls; each maps onto the drop reason it counts. */
internal enum class EluFacadeDisabledReason(val drop: EluFacadeDropReason) {
    BLOCKED(EluFacadeDropReason.BLOCKED),
    OPTED_OUT(EluFacadeDropReason.OPTED_OUT),
    UNAUTHORIZED(EluFacadeDropReason.UNAUTHORIZED),
}

internal sealed interface EluFacadeState {
    /** No configuration decision yet: state-changing calls are held, getters return defaults. */
    data object Pending : EluFacadeState

    data object Enabled : EluFacadeState

    data class Disabled(val reason: EluFacadeDisabledReason) : EluFacadeState

    data object Closed : EluFacadeState
}

internal data class EluFacadeDiagnostics(
    val state: EluFacadeState,
    /** State-changing calls held for the first configuration decision. */
    val buffered: Int,
    /** Calls discarded since construction, by reason. */
    val dropped: Map<EluFacadeDropReason, Int>,
    /** Flag listener invocations that threw; the error never reaches the caller. */
    val listenerErrors: Int,
    val flagReloadInFlight: Boolean,
)

/**
 * Maps the public surface onto the ELU standalone runtime and the owned flag client.
 *
 * Call semantics by phase, matching the browser facade this mirrors:
 *
 * - `pending`: no configuration decision has been applied. State-changing calls are held in order
 *   up to [PRE_INIT_BUFFER_LIMIT] and later calls are dropped as [EluFacadeDropReason.BUFFER_FULL];
 *   getters return their documented defaults.
 * - `enabled`: executable capture authority exists and the identity is not opted out. Calls map
 *   onto the runtime through one serialized lane, in call order.
 * - `disabled`: the region policy blocks the device, the identity is opted out, or there is no
 *   executable capture authority. Activity calls are discarded with that reason. Feature flags
 *   use their independent current configuration/privacy authorization. `reset` still applies,
 *   while preserving the visitor's consent choice.
 *
 * Identity continuity with the embedded runtime is deliberately NOT implemented here. With this
 * runtime selected, a fresh install starts a fresh ELU identity: nothing in this file reads the
 * previous runtime's storage. Reading that storage requires a separately qualified migration.
 *
 * Every entry point returns without waiting on storage or the network: work is submitted to one
 * lane thread, and synchronous getters read the projections that lane publishes.
 */
internal class StandaloneFacade(
    private val open: () -> StandaloneStack,
    /** Delivers customer callbacks; production posts them to the main thread. */
    private val deliverCallback: (Runnable) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val bufferLimit: Int = PRE_INIT_BUFFER_LIMIT,
    private val versions: RuntimeVersions = StandaloneRuntime.defaultVersions(),
    private val configurationGate: V2ConfigAuthorityGate? = null,
    private val onOpened: () -> Unit = {},
    private val onCloseRequested: () -> Unit = {},
    private val startupObserver: RuntimeStartupObserver = RuntimeStartupObserver.NONE,
    private val nativeStartTrace: NativeStartTrace = NativeStartTrace.NONE,
    private val lane: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "elu-facade").apply { isDaemon = true }
    },
) : EluFacadeSink, AutoCloseable {
    // Serializes acceptance with queue insertion, never a storage wait.
    private val consentIntakeLock = Any()

    // Lane-confined.
    private val buffer = ArrayDeque<Operation>()
    private val listeners = mutableListOf<() -> Unit>()
    private val observedFlagKeys = LinkedHashSet<String>()
    private val exposures = HashSet<String>()
    private val reloadCompletions = mutableListOf<() -> Unit>()
    private var exposureIdentityRevision: Long? = null
    /** Identifies the loaded flags; a reset abandons the reload started for the previous one. */
    @Volatile private var flagGeneration = 0L
    private var flagReloadGeneration: Long? = null
    private var flagReloadAttempts = 0
    @Volatile private var stack: StandaloneStack? = null
    private var configDocument: String? = null
    private var hasConfigDecision = false
    @Volatile private var appliedConfiguration: V2ConfigAuthorityWitness? = null
    @Volatile private var flagsAuthorized = false
    @Volatile private var flagConfiguration: V2ConfigAuthorityWitness? = null
    private var appliedFlagConfigBody: String? = null
    private val closeRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private var cachedPersonProperties: String? = null
    private val listenerErrors = AtomicInteger()

    // Counted from any thread: reserved-property removals are accounted at call time.
    private val dropCounts = EnumMap<EluFacadeDropReason, Int>(EluFacadeDropReason::class.java)
    private val projectionLock = Any()
    private var pendingIdentityOperations = 0
    @Volatile private var pendingFlagOperations = 0
    @Volatile private var flagIntentRevision = 0L
    private var nativeIntentEpoch: Any = Any()
    private var pendingNativeOperations = 0
    private var nativeLifecycleEligible = true
    private var executingNativeEpoch: Any? = null // facade lane only
    private var nativeSessionRefreshNeeded = false // facade lane only
    private val closeResult = object : SdkFuture<Unit>() {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
    }

    @Volatile private var state: EluFacadeState = EluFacadeState.Pending

    @Volatile private var closed = false
    @Volatile private var requestedOptOut = false
    private var consentIntentRevision = 0L // guarded by projectionLock
    @Volatile private var performanceConfiguration: dev.elu.analytics.internal.config.V1CapturePerformance? = null

    @Volatile private var identity: IdentityState? = null

    /** Non-null while a queued identity call has not settled, so getters follow call order. */
    @Volatile private var projectedIdentity: ProjectedIdentity? = null

    @Volatile private var flagProjection: Map<String, FlagEntry> = emptyMap()

    @Volatile private var flagsLoaded = false
    @Volatile private var loadedCacheToken: FlagCacheLeaseToken? = null

    /** Called on the existing facade lane only; no authority getter is sampled. */
    private fun observeLane(phase: RuntimeStartupPhase) = observeStartup(startupObserver) {
        val observedLane = when (val current = state) {
            EluFacadeState.Pending -> RuntimeStartupLane.PENDING
            EluFacadeState.Enabled -> RuntimeStartupLane.ENABLED
            EluFacadeState.Closed -> RuntimeStartupLane.CLOSED
            is EluFacadeState.Disabled -> RuntimeStartupLane.valueOf(current.reason.name)
        }
        RuntimeStartupObservation(phase, lane = observedLane, buffered = buffer.size)
    }

    // ---- lifecycle -----------------------------------------------------------

    /** Opens the owned runtime on the facade lane. Calls made before it completes are held. */
    fun start() {
        submit {
            if (closed || closeRequested.get() || stack != null) return@submit
            try {
                observeLane(RuntimeStartupPhase.OPEN_BEGIN)
                val opened = open()
                stack = opened
                syncIdentity(opened.owner.snapshot().await().state.identity)
                if (requestedOptOut) opened.runtime.restrictForConsent()
                onOpened()
                observeLane(RuntimeStartupPhase.OPEN_READY)
            } catch (error: Throwable) {
                observeLane(RuntimeStartupPhase.OPEN_FAILED)
                // Storage the facade cannot open fails closed: nothing is captured this run.
                closeRequested.set(true)
                countDrop(EluFacadeDropReason.STORAGE)
                transition(EluFacadeState.Disabled(EluFacadeDisabledReason.UNAUTHORIZED))
                runCatching { onCloseRequested() }
                val held = buffer.toList(); buffer.clear()
                held.forEach { discard(it, EluFacadeDropReason.CLOSED) }
                val opened = stack
                runCatching { opened?.flags?.close() }
                val originalClose = opened?.runtime?.closeAndWait()
                if (originalClose == null) closeResult.completeExceptionally(error)
                else originalClose.whenComplete { _, cleanup ->
                    if (cleanup != null && cleanup !== error) error.addSuppressed(cleanup)
                    closeResult.completeExceptionally(error)
                }
                stack = null
                lane.shutdown()
                return@submit
            }
            renewAuthority()
        }
    }

    /**
     * Applies one configuration document. The first decision releases the held calls in order and
     * then loads flags once, so the initial evaluation sees the identity those calls established.
     */
    fun applyConfiguration(configBody: String?) {
        val token = acceptNativeChange(restrictive = true)
        val accepted = submit {
            try {
                if (!closed) {
                    configDocument = configBody; hasConfigDecision = true
                    renewAuthority(token?.epoch)
                }
            } finally { settleNativeChange(token, onLane = true) }
        }
        if (!accepted) settleNativeChange(token, onLane = false)
    }

    /** Gate publication already revokes the old source; this original local epoch never renews it. */
    internal fun configurationChanged() {
        val token = acceptNativeChange(restrictive = true)
        val accepted = submit {
            try { if (!closed) { hasConfigDecision = true; renewAuthority(token?.epoch) } }
            finally { settleNativeChange(token, onLane = true) }
        }
        if (!accepted) settleNativeChange(token, onLane = false)
    }

    /** Actual lifecycle facts still come from the sole observer. True is only a reevaluation request. */
    internal fun nativeReplayLifecycleChanged(eligible: Boolean) {
        nativeStartTrace.mark(NativeStartPhase.FACADE_LIFECYCLE, eligible)
        val token = acceptNativeChange(restrictive = true, lifecycleEligible = eligible) ?: return
        val accepted = submit {
            try { if (eligible && nativeEpochIsCurrent(token.epoch)) renewAuthority(token.epoch) }
            finally { settleNativeChange(token, onLane = true) }
        }
        if (!accepted) settleNativeChange(token, onLane = false)
    }

    internal fun nativeReplayIntakeAllowed(): Boolean = synchronized(projectionLock) {
        !closeRequested.get() && !closed && !isOptedOut() && pendingNativeOperations == 0 && nativeLifecycleEligible
    }

    fun state(): EluFacadeState = if (isOptedOut() && state !is EluFacadeState.Closed) {
        EluFacadeState.Disabled(EluFacadeDisabledReason.OPTED_OUT)
    } else if (state is EluFacadeState.Enabled && !hasCurrentConfiguration()) {
        EluFacadeState.Disabled(EluFacadeDisabledReason.UNAUTHORIZED)
    } else state

    private fun hasCurrentConfiguration(): Boolean =
        !closeRequested.get() && (configurationGate == null || appliedConfiguration?.isCurrent() == true)

    private fun hasCurrentFlags(): Boolean = !closeRequested.get() && !isOptedOut() && flagsAuthorized &&
        (configurationGate == null || flagConfiguration?.isCurrent() == true)

    /** Completes once every call submitted before it has run. */
    fun settled(): Future<Unit> = lane.submit<Unit> { }

    fun diagnostics(): Future<EluFacadeDiagnostics> =
        lane.submit<EluFacadeDiagnostics> {
            EluFacadeDiagnostics(
                state = state,
                buffered = buffer.size,
                dropped = synchronized(dropCounts) { EnumMap(dropCounts) },
                listenerErrors = listenerErrors.get(),
                flagReloadInFlight = flagReloadGeneration != null,
            )
        }

    override fun close() { closeAndWait() }

    internal fun closeAndWait(): SdkFuture<Unit> {
        if (!closeRequested.compareAndSet(false, true)) return closeResult
        synchronized(projectionLock) { nativeIntentEpoch = Any() }
        stack?.runtime?.withdrawNativeReplay(restrictive = true)
        runCatching { onCloseRequested() }
        configurationGate?.close()
        try {
            lane.execute {
                closed = true
                val held = buffer.toList(); buffer.clear()
                held.forEach { discard(it, EluFacadeDropReason.CLOSED) }
                state = EluFacadeState.Closed
                val opened = stack
                var flagFailure: Throwable? = null
                try { opened?.flags?.close() } catch (error: Throwable) { flagFailure = error }
                val originalClose = opened?.runtime?.closeAndWait()
                if (originalClose == null) {
                    if (flagFailure == null) closeResult.complete(Unit) else closeResult.completeExceptionally(checkNotNull(flagFailure))
                } else originalClose.whenComplete { _, error ->
                    if (error == null && flagFailure == null) closeResult.complete(Unit)
                    else closeResult.completeExceptionally(error ?: checkNotNull(flagFailure))
                }
            }
        } catch (error: RejectedExecutionException) { closeResult.completeExceptionally(error) }
        lane.shutdown()
        return closeResult
    }

    // ---- events --------------------------------------------------------------

    override fun capture(
        event: String,
        properties: Map<String, Any>?,
        timestamp: Date,
    ) {
        observeStartup(startupObserver) { RuntimeStartupObservation(RuntimeStartupPhase.CAPTURE_CALL) }
        if (event.isEmpty()) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val eventProperties = withoutReservedKeys(properties)
        // The caller's timestamp, so a call held while pending is not re-stamped at release.
        val occurredAt = RuntimeWallTimestamps.rfc3339(timestamp.time)
        dispatch(OperationKind.ACTIVITY, affectsFlags = hasContextMutation(eventProperties)) {
            val runtime = requireStack().runtime
            captureThrough { runtime.capture(event, eventProperties, occurredAt) }
        }
    }

    override fun screen(
        name: String,
        properties: Map<String, Any>?,
    ) {
        if (name.isEmpty()) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val screenProperties = withoutReservedKeys(properties)
        val occurredAt = now()
        dispatch(OperationKind.ACTIVITY, affectsFlags = hasContextMutation(screenProperties)) {
            val runtime = requireStack().runtime
            captureThrough { runtime.screen(name, screenProperties, occurredAt) }
        }
    }

    override fun captureException(
        error: Throwable,
        properties: Map<String, Any>?,
    ) {
        val exceptionProperties = withoutReservedKeys(properties)
        val occurredAt = now()
        dispatch(OperationKind.ACTIVITY, affectsFlags = hasContextMutation(exceptionProperties)) {
            val runtime = requireStack().runtime
            captureThrough { runtime.captureException(error, exceptionProperties, occurredAt) }
        }
    }

    // ---- identity ------------------------------------------------------------

    override fun identify(distinctId: String, userProperties: Map<String, Any>?) = identify(distinctId, userProperties, null)

    override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
        userPropertiesOnce: Map<String, Any>?,
    ) {
        if (!isUsableIdentifier(distinctId) || distinctId == "distinct_id") {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val set = userProperties?.toMap().orEmpty()
        val setOnce = userPropertiesOnce?.toMap().orEmpty()
        val occurredAt = now()
        val projected = projectIdentity(ProjectedIdentity(distinctId))
        dispatch(OperationKind.ACTIVITY, projected, affectsFlags = true) { identifyOnLane(distinctId, set, occurredAt, setOnce) }
    }

    override fun alias(alias: String) {
        if (!isUsableIdentifier(alias)) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val occurredAt = now()
        dispatch(OperationKind.ACTIVITY, affectsFlags = true) {
            val canonicalId = persistedDistinctId()
            if (alias == canonicalId) {
                // Aliasing an id to itself is the identify transition for that id.
                identifyOnLane(alias, emptyMap(), occurredAt)
            } else {
                appendMutations(listOf(RuntimeMutationChange.LinkAlias(alias, canonicalId)), occurredAt)
            }
        }
    }

    override fun reset() {
        // The owner mints the replacement anonymous id, so the facade cannot name it in advance;
        // until the reset commits, the getter reports no identity rather than the ended one.
        val occurredAt = now()
        val projected = projectIdentity(ProjectedIdentity(null))
        dispatch(OperationKind.RESET, projected, affectsFlags = true) {
            applyLocalChange(RuntimeLocalStateChange.ResetIdentity(occurredAt))
            cachedPersonProperties = null
            clearFlags()
            if (state is EluFacadeState.Enabled) startFlagReload()
        }
    }

    override fun optOut() = synchronized(consentIntakeLock) {
        val intent = synchronized(projectionLock) {
            consentIntentRevision = Math.incrementExact(consentIntentRevision)
            requestedOptOut = true
            consentIntentRevision
        }
        // Withdrawal is immediate; a queued config refresh cannot reinstall delivery while the
        // consent transaction waits for the facade/storage lanes.
        stack?.runtime?.restrictForConsent()
        acceptNativeChange(restrictive = true)?.let { settleNativeChange(it, onLane = false) }
        dispatch(OperationKind.CONSENT, affectsFlags = true) {
            if (!synchronized(projectionLock) { consentIntentRevision == intent }) return@dispatch
            if (identity?.optedOut != true) applyLocalChange(RuntimeLocalStateChange.SetOptedOut(true, now()))
            else renewAuthority()
            clearFlags()
        }
    }

    override fun optIn(captureEventName: String?, properties: Map<String, Any>?) {
        if (captureEventName != null && !isUsableIdentifier(captureEventName)) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val eventProperties = withoutReservedKeys(properties)
        synchronized(consentIntakeLock) {
            val intent = synchronized(projectionLock) {
                consentIntentRevision = Math.incrementExact(consentIntentRevision)
                consentIntentRevision
            }
            dispatch(OperationKind.CONSENT, affectsFlags = true) {
                if (!synchronized(projectionLock) { consentIntentRevision == intent }) return@dispatch
                if (identity?.optedOut == true) applyLocalChange(RuntimeLocalStateChange.SetOptedOut(false, now()))
                if (identity?.optedOut == false) synchronized(consentIntakeLock) {
                    synchronized(projectionLock) {
                        if (consentIntentRevision == intent) {
                            requestedOptOut = false
                            stack?.runtime?.restoreConsent()
                        }
                    }
                }
                renewAuthority()
                if (!isOptedOut() && state is EluFacadeState.Enabled && captureEventName != null) {
                    captureThrough { requireStack().runtime.capture(captureEventName, eventProperties, now()) }
                }
            }
        }
    }

    override fun viewPrivacyChanged() {
        acceptNativeChange(restrictive = true)?.let { settleNativeChange(it, onLane = false) }
    }

    override fun isOptedOut(): Boolean = requestedOptOut || identity?.optedOut == true

    override fun distinctId(): String? {
        if (isOptedOut() || state !is EluFacadeState.Enabled || !hasCurrentConfiguration()) return null
        projectedIdentity?.let { return it.distinctId }
        val current = identity ?: return null
        return current.userId ?: current.anonymousId
    }

    /** Detached original context only; every sample is rechecked on the facade and storage lanes. */
    internal fun performanceContext(): dev.elu.analytics.internal.performance.NativePerformanceContext? = synchronized(projectionLock) {
        if (closed || closeRequested.get() || isOptedOut() || !nativeLifecycleEligible ||
            pendingNativeOperations != 0 || pendingFlagOperations != 0 || pendingIdentityOperations != 0 ||
            !hasCurrentCapture()) return null
        val policy = performanceConfiguration ?: return null
        if (!policy.memory && !policy.longTasks) return null
        val current = identity ?: return null
        val session = current.session ?: return null
        if (session.lifecycle != dev.elu.analytics.internal.core.SessionLifecycle.ACTIVE || session.backgroundedAt != null) return null
        val now = wallClock()
        val lastActivity = java.time.Instant.parse(session.lastActivityAt).toEpochMilli()
        val started = java.time.Instant.parse(session.startedAt).toEpochMilli()
        if (now < lastActivity || now - lastActivity >= session.timeoutSeconds * 1_000L ||
            now < started || now - started >= session.maximumDurationSeconds * 1_000L) return null
        val source = if (configurationGate == null) configDocument ?: return null
            else appliedConfiguration?.takeIf { it.isCurrent() }?.token ?: return null
        dev.elu.analytics.internal.performance.NativePerformanceContext(source, current.revision, current.contextRevision,
            session.id, flagIntentRevision, nativeIntentEpoch, policy)
    }

    internal fun capturePerformance(original: dev.elu.analytics.internal.performance.NativePerformanceContext, properties: Map<String, Any>) {
        val detached = properties.toMap()
        submit {
            if (performanceContext() != original) return@submit
            // Do not retry an old aggregate through a new identity, session, or configuration.
            val result = requireStack().runtime.capturePerformance(detached,
                dev.elu.analytics.internal.runtime.RuntimeCaptureExpectation(original.identityRevision,
                    original.contextRevision, original.sessionId) { performanceContext() == original }).await()
            if (result is RuntimeCaptureResult.Accepted) syncIdentity(result.snapshot.state.identity)
        }
    }

    // ---- properties ----------------------------------------------------------

    override fun register(properties: Map<String, Any>) {
        val superProperties = withoutReservedKeys(properties)
        if (superProperties.isEmpty()) return
        if (superProperties.keys.any { it.isEmpty() }) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val occurredAt = now()
        dispatch(OperationKind.ACTIVITY, affectsFlags = true) {
            applyLocalChange(RuntimeLocalStateChange.RegisterSuperProperties(superProperties, occurredAt))
        }
    }

    override fun registerOnce(properties: Map<String, Any>, defaultValue: Any?) {
        val selected = withoutReservedKeys(properties)
        if (selected.isEmpty()) return
        if (selected.keys.any { it.isEmpty() }) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val occurredAt = now()
        dispatch(OperationKind.ACTIVITY, affectsFlags = true) {
            applyLocalChange(RuntimeLocalStateChange.RegisterSuperPropertiesOnce(selected, defaultValue, occurredAt))
        }
    }

    override fun unregister(key: String) {
        if (key.isEmpty()) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        if (isReservedPropertyKey(key)) return
        val occurredAt = now()
        dispatch(OperationKind.ACTIVITY, affectsFlags = true) {
            // Unregistering an absent key would still advance the context revision.
            if (identity?.superProperties?.containsKey(key) != true) return@dispatch
            applyLocalChange(RuntimeLocalStateChange.UnregisterSuperProperties(listOf(key), occurredAt))
        }
    }

    override fun setPersonProperties(properties: Map<String, Any>) = setPersonProperties(properties, emptyMap())

    override fun setPersonProperties(properties: Map<String, Any>, propertiesOnce: Map<String, Any>) {
        if (properties.isEmpty() && propertiesOnce.isEmpty()) return
        val set = properties.toMap()
        val setOnce = propertiesOnce.toMap()
        val occurredAt = now()
        dispatch(OperationKind.PERSON_GROUP_CONTEXT, affectsFlags = true) {
            if (hasCurrentCapture()) appendPersonProperties(set, reloadFlags = true, occurredAt = occurredAt, setOnce = setOnce)
            else applyFlagContext(RuntimeLocalStateChange.SetFlagPersonProperties(set, occurredAt, setOnce))
        }
    }

    override fun group(
        type: String,
        key: String,
        properties: Map<String, Any>?,
    ) {
        if (!isUsableIdentifier(type) || !isUsableIdentifier(key)) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val groupProperties = properties?.toMap()
        val occurredAt = now()
        dispatch(OperationKind.PERSON_GROUP_CONTEXT, affectsFlags = true) {
            if (!hasCurrentCapture()) {
                applyFlagContext(RuntimeLocalStateChange.SetFlagGroup(type, key, groupProperties, occurredAt))
                return@dispatch
            }
            val changes = mutableListOf<RuntimeMutationChange>()
            if (identity?.groups?.get(type) != key) {
                changes += RuntimeMutationChange.AssociateGroup(type, key)
            }
            if (groupProperties != null) {
                changes +=
                    RuntimeMutationChange.SetGroupProperties(
                        groupType = type,
                        groupKey = key,
                        set = groupProperties,
                        setOnce = emptyMap(),
                        unset = emptyList(),
                    )
            }
            if (changes.isEmpty()) return@dispatch
            // The group mutation also enters the flag evaluation context, so the reload below
            // evaluates against the group the caller just described.
            appendMutations(changes, occurredAt)
            startFlagReload()
        }
    }

    override fun getGroups(): Map<String, String> {
        if (isOptedOut() || projectedIdentity != null || pendingFlagOperations != 0 || (!hasCurrentCapture() && !hasCurrentFlags())) return emptyMap()
        return java.util.Collections.unmodifiableMap(LinkedHashMap(identity?.groups.orEmpty()))
    }

    override fun resetGroups() {
        val occurredAt = now()
        dispatch(OperationKind.PERSON_GROUP_CONTEXT, affectsFlags = true) {
            val change = RuntimeLocalStateChange.ResetGroups(occurredAt)
            if (hasCurrentCapture()) applyLocalChange(change) else applyFlagContext(change)
        }
    }

    // ---- feature flags -------------------------------------------------------

    override fun getFeatureFlag(key: String): Any? = readFlag(key, expose = true)?.value

    override fun getFeatureFlagResult(key: String): EluFeatureFlagResult? = readFlag(key, expose = true)?.let {
        EluFeatureFlagResult(key, isTruthyVariant(it.value), it.value as? String, it.payload)
    }

    override fun getFeatureFlagPayload(key: String): Any? = readFlag(key, expose = false)?.payload

    override fun isFeatureEnabled(key: String): Boolean = isTruthyVariant(readFlag(key, expose = true)?.value)

    override fun reloadFeatureFlags(completion: (() -> Unit)?) {
        // A reload is a command, not a state change: it is never replayed from the hold buffer.
        submit {
            if (!hasCurrentFlags()) {
                countDrop(currentDropReason())
                return@submit
            }
            completion?.let { reloadCompletions += it }
            startFlagReload()
            // Without a flag client there is nothing to load, so the completion runs now.
            if (flagReloadGeneration == null) finishFlagReload()
        }
    }

    override fun onFeatureFlagsLoaded(callback: () -> Unit) {
        submit {
            listeners += callback
            // Add-and-fire on one lane: a listener either made this load's snapshot or missed it
            // and replays once. Later loads legitimately re-fire every listener in order.
            if (hasCurrentFlags() && flagsLoaded) deliverFlagCallback(callback)
        }
    }

    override fun setPersonPropertiesForFlags(properties: Map<String, Any>) {
        if (properties.isEmpty()) return
        val set = properties.toMap()
        val occurredAt = now()
        dispatch(OperationKind.FLAG_CONTEXT, affectsFlags = true) {
            applyFlagContext(RuntimeLocalStateChange.SetFlagPersonProperties(set, occurredAt))
            startFlagReload()
        }
    }

    override fun resetPersonPropertiesForFlags() {
        val occurredAt = now()
        dispatch(OperationKind.FLAG_CONTEXT, affectsFlags = true) {
            applyFlagContext(RuntimeLocalStateChange.ResetFlagPersonProperties(occurredAt))
        }
    }

    override fun resetGroupPropertiesForFlags(type: String?) {
        if (type != null && !isUsableIdentifier(type)) { countDrop(EluFacadeDropReason.INVALID_INPUT); return }
        val occurredAt = now()
        dispatch(OperationKind.FLAG_CONTEXT, affectsFlags = true) {
            applyFlagContext(RuntimeLocalStateChange.ResetFlagGroupProperties(type, occurredAt))
        }
    }

    override fun setGroupPropertiesForFlags(
        type: String,
        properties: Map<String, Any>,
    ) {
        if (!isUsableIdentifier(type)) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val set = properties.toMap()
        val occurredAt = now()
        dispatch(OperationKind.FLAG_CONTEXT, affectsFlags = true) {
            applyFlagContext(RuntimeLocalStateChange.SetFlagGroupProperties(type, set, occurredAt))
            startFlagReload()
        }
    }

    // ---- transport -----------------------------------------------------------

    override fun flush() {
        submit {
            if (isOptedOut() || state !is EluFacadeState.Enabled) {
                countDrop(currentDropReason())
                return@submit
            }
            // Delivery runs on the runtime's own executor; the lane never waits for the network.
            stack?.runtime?.flush()
        }
    }

    // ---- dispatch ------------------------------------------------------------

    private enum class OperationKind {
        ACTIVITY,
        CONSENT,
        RESET,
        FLAG_CONTEXT,
        PERSON_GROUP_CONTEXT,
    }

    private class Operation(
        val kind: OperationKind,
        val projected: Boolean,
        val flagIntent: Boolean,
        val nativeChange: NativeChange?,
        val nativeEpoch: Any,
        val onDropped: (() -> Unit)?,
        val run: () -> Unit,
    ) { val settled = java.util.concurrent.atomic.AtomicBoolean(false) }

    private class NativeChange(val epoch: Any) { val settled = java.util.concurrent.atomic.AtomicBoolean(false) }

    private fun hasContextMutation(properties: Map<String, Any?>): Boolean =
        properties.containsKey("\$set") || properties.containsKey("\$set_once")

    private fun acceptNativeChange(restrictive: Boolean, lifecycleEligible: Boolean? = null): NativeChange? {
        val token = synchronized(projectionLock) {
            if (closeRequested.get() || closed) return null
            if (lifecycleEligible != null) {
                if (nativeLifecycleEligible == lifecycleEligible) return null
                nativeLifecycleEligible = lifecycleEligible
            }
            if (restrictive) nativeIntentEpoch = Any()
            pendingNativeOperations += 1
            NativeChange(nativeIntentEpoch)
        }
        stack?.runtime?.withdrawNativeReplay(restrictive)
        return token
    }

    private fun nativeEpochIsCurrent(epoch: Any): Boolean = synchronized(projectionLock) {
        !closeRequested.get() && !closed && nativeIntentEpoch === epoch
    }

    private fun requestNativeEvaluation(epoch: Any, force: Boolean) {
        if (nativeEpochIsCurrent(epoch).also { nativeStartTrace.mark(NativeStartPhase.FACADE_EPOCH, it) } &&
            nativeReplayIntakeAllowed().also { nativeStartTrace.mark(NativeStartPhase.FACADE_INTAKE, it) } &&
            (state !is EluFacadeState.Pending).also { nativeStartTrace.mark(NativeStartPhase.FACADE_NOT_PENDING, it) } &&
            (state !is EluFacadeState.Closed).also { nativeStartTrace.mark(NativeStartPhase.FACADE_NOT_CLOSED, it) })
            stack?.runtime.also { nativeStartTrace.mark(NativeStartPhase.RUNTIME_PRESENT, it != null) }?.reevaluateNativeReplay(force) {
            nativeEpochIsCurrent(epoch) && nativeReplayIntakeAllowed()
        }
    }

    private fun settleNativeChange(token: NativeChange?, onLane: Boolean) {
        if (token == null || !token.settled.compareAndSet(false, true)) return
        val last = synchronized(projectionLock) { pendingNativeOperations -= 1; pendingNativeOperations == 0 }
        if (!last) return
        val action = { requestNativeEvaluation(token.epoch, force = true) }
        if (onLane) action() else submit(action)
    }

    private fun settleOperation(operation: Operation, onLane: Boolean) {
        if (!operation.settled.compareAndSet(false, true)) return
        if (operation.projected) settleProjection()
        if (operation.flagIntent) settleFlagIntent(onLane)
        settleNativeChange(operation.nativeChange, onLane)
    }

    private fun dispatch(
        kind: OperationKind,
        projected: Boolean = false,
        affectsFlags: Boolean = false,
        onDropped: (() -> Unit)? = null,
        run: () -> Unit,
    ) {
        if (affectsFlags) synchronized(projectionLock) {
            pendingFlagOperations += 1
            flagIntentRevision = Math.incrementExact(flagIntentRevision)
        }
        val nativeChange = if (affectsFlags) acceptNativeChange(restrictive = false) else null
        val nativeEpoch = synchronized(projectionLock) { nativeIntentEpoch }
        val operation = Operation(kind, projected, affectsFlags, nativeChange, nativeChange?.epoch ?: nativeEpoch, onDropped, run)
        val accepted =
            submit {
                if (closed) {
                    discard(operation, EluFacadeDropReason.CLOSED)
                    return@submit
                }
                if (state is EluFacadeState.Pending && kind != OperationKind.CONSENT) {
                    if (buffer.size >= bufferLimit) {
                        // Drop the newest, never the oldest: the held calls are an ordered identity
                        // history, and a coherent prefix is worth more than a recent fragment.
                        discard(operation, EluFacadeDropReason.BUFFER_FULL)
                        return@submit
                    }
                    buffer.addLast(operation)
                    observeLane(RuntimeStartupPhase.BUFFERED)
                    return@submit
                }
                execute(operation)
            }
        if (!accepted) discard(operation, EluFacadeDropReason.CLOSED, onLane = false)
    }

    private fun execute(operation: Operation) {
        observeLane(RuntimeStartupPhase.DISPATCHED)
        val current = state
        val previousNativeEpoch = executingNativeEpoch
        executingNativeEpoch = operation.nativeEpoch
        try {
            when {
                closeRequested.get() -> discard(operation, EluFacadeDropReason.CLOSED, settle = false)
                configurationGate != null && !hasCurrentConfiguration() && operation.kind == OperationKind.ACTIVITY ->
                    discard(operation, EluFacadeDropReason.UNAUTHORIZED, settle = false)
                operation.kind == OperationKind.FLAG_CONTEXT && !hasCurrentFlags() ->
                    discard(operation, EluFacadeDropReason.UNAUTHORIZED, settle = false)
                operation.kind == OperationKind.PERSON_GROUP_CONTEXT && !hasCurrentCapture() && !hasCurrentFlags() ->
                    discard(operation, EluFacadeDropReason.UNAUTHORIZED, settle = false)
                current is EluFacadeState.Closed -> discard(operation, EluFacadeDropReason.CLOSED, settle = false)
                current is EluFacadeState.Pending && operation.kind != OperationKind.CONSENT ->
                    discard(operation, EluFacadeDropReason.UNAUTHORIZED, settle = false)
                isOptedOut() && operation.kind == OperationKind.ACTIVITY ->
                    discard(operation, EluFacadeDropReason.OPTED_OUT, settle = false)
                current is EluFacadeState.Disabled && operation.kind == OperationKind.ACTIVITY ->
                    discard(operation, current.reason.drop, settle = false)
                else ->
                    try {
                        operation.run()
                    } catch (error: Throwable) {
                        countDrop(classify(error))
                        operation.onDropped?.invoke()
                    }
            }
        } finally {
            try {
                if (nativeSessionRefreshNeeded && !closeRequested.get()) renewAuthority(operation.nativeEpoch)
            } finally {
                settleOperation(operation, onLane = true)
                requestNativeEvaluation(operation.nativeEpoch, force = false)
                executingNativeEpoch = previousNativeEpoch
            }
        }
    }

    private fun discard(
        operation: Operation,
        reason: EluFacadeDropReason,
        settle: Boolean = true,
        onLane: Boolean = true,
    ) {
        countDrop(reason)
        try { operation.onDropped?.invoke() }
        finally { if (settle) settleOperation(operation, onLane) }
    }

    private fun settleFlagIntent(onLane: Boolean) {
        val settled = synchronized(projectionLock) {
            pendingFlagOperations -= 1
            pendingFlagOperations == 0
        }
        if (!settled) return
        val apply = { invalidateFlagProjection(); startFlagReload() }
        // Successful/discarded lane work must settle before the next reload command. Only a
        // rejected external submission needs to queue cleanup; it may not touch lane-owned maps.
        if (onLane) apply() else submit(apply)
    }

    private fun flagIntentIsCurrent(revision: Long): Boolean =
        pendingFlagOperations == 0 && revision == flagIntentRevision && projectedIdentity == null

    private fun submit(block: () -> Unit): Boolean =
        try {
            lane.execute {
                try {
                    block()
                } catch (error: Throwable) {
                    countDrop(classify(error))
                    observeLane(RuntimeStartupPhase.LANE_FAILED)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    private fun transition(next: EluFacadeState) {
        val previous = state
        state = next
        observeLane(RuntimeStartupPhase.TRANSITION)
        if (previous !is EluFacadeState.Pending) return
        if (next is EluFacadeState.Pending || next is EluFacadeState.Closed) return
        val released = buffer.toList()
        buffer.clear()
        // Held calls carry the identity, groups and flag context the first evaluation must use, so
        // they all run before the initial flag load the caller of this method starts.
        released.forEach { execute(it) }
    }

    // ---- runtime mapping -----------------------------------------------------

    /**
     * Re-submits the current configuration so capture authority is derived from the present
     * identity witness, then derives the facade phase from the runtime's decision. Every identity,
     * super-property, group and flag-context change advances the context revision the authority is
     * bound to, so each of them renews before the next capture can proceed.
     */
    private fun renewAuthority(nativeEpoch: Any? = executingNativeEpoch ?: synchronized(projectionLock) { nativeIntentEpoch }) {
        val open = stack ?: return
        if (!hasConfigDecision && (configurationGate == null || configurationGate.snapshot() == null)) return
        val witness = configurationGate?.snapshot()
        if (witness?.isApplicationSuspended == true) {
            // Gate withdrawal already makes every retained source witness unusable. Retire
            // scheduling and projections without feeding a nonexistent document to the permanent
            // invalid-config barrier. A new source token must pass full validation to resume.
            open.runtime.configurationChanged()
            appliedConfiguration = null
            appliedFlagConfigBody = null
            flagConfiguration = null
            flagsAuthorized = false
            invalidateFlagProjection()
            nativeSessionRefreshNeeded = false
            transition(EluFacadeState.Disabled(EluFacadeDisabledReason.UNAUTHORIZED))
            return
        }
        val document = if (configurationGate == null) configDocument else witness?.body
        observeStartup(startupObserver) { RuntimeStartupObservation(RuntimeStartupPhase.AUTHORITY_BEGIN, configPresent = document != null) }
        val result = open.runtime.applyConfiguration(document).await()
        val allowed = result is RuntimeCaptureAuthorityUpdateResult.Activated &&
            (configurationGate == null || witness?.isCurrent() == true)
        performanceConfiguration = if (allowed && document != null)
            runCatching { dev.elu.analytics.internal.config.V1ConfigJson.parseConfig(document).capturePerformance }.getOrNull() else null
        appliedConfiguration = if (allowed) witness else null
        if (appliedFlagConfigBody != document ||
            (configurationGate != null && flagConfiguration?.token !== witness?.token)) invalidateFlagProjection()
        appliedFlagConfigBody = document
        val flagResult = open.flags?.let { client -> runCatching { client.applyConfiguration(document).await() }.getOrNull() }
        flagsAuthorized = flagResult is V1FlagAuthorizationResolution.Allowed &&
            (configurationGate == null || witness?.isCurrent() == true)
        observeStartup(startupObserver) { RuntimeStartupObservation(RuntimeStartupPhase.FLAGS_RESULT, flagsAllowed = flagsAuthorized) }
        flagConfiguration = if (flagsAuthorized) witness else null
        if (!flagsAuthorized) invalidateFlagProjection()
        syncIdentity(open.owner.snapshot().await().state.identity)
        observeLane(RuntimeStartupPhase.IDENTITY_READY)
        val wasEnabled = state is EluFacadeState.Enabled
        val next =
            when (result) {
                is RuntimeCaptureAuthorityUpdateResult.Activated -> if (allowed) EluFacadeState.Enabled
                    else EluFacadeState.Disabled(EluFacadeDisabledReason.UNAUTHORIZED)
                is RuntimeCaptureAuthorityUpdateResult.Terminated ->
                    EluFacadeState.Disabled(disabledReasonFor(result.authority.reason))
            }
        nativeSessionRefreshNeeded = false
        transition(next)
        if (flagsAuthorized && (!wasEnabled || !flagsLoaded)) startFlagReload()
        nativeEpoch?.let { requestNativeEvaluation(it, force = true) }
    }

    private fun disabledReasonFor(reason: RuntimeCaptureAuthorityTerminalReason): EluFacadeDisabledReason =
        when {
            reason == RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED -> EluFacadeDisabledReason.BLOCKED
            identity?.optedOut == true -> EluFacadeDisabledReason.OPTED_OUT
            else -> EluFacadeDisabledReason.UNAUTHORIZED
        }

    private fun captureThrough(send: () -> Future<RuntimeCaptureResult>) {
        val first = send().await()
        observeStartup(startupObserver) { RuntimeStartupObservation(RuntimeStartupPhase.CAPTURE_FIRST,
            captureAccepted = first is RuntimeCaptureResult.Accepted, captureRejection = (first as? RuntimeCaptureResult.Rejected)?.reason) }
        when (first) {
            is RuntimeCaptureResult.Accepted -> syncIdentity(first.snapshot.state.identity)
            is RuntimeCaptureResult.Rejected -> {
                if (!isAuthorityRejection(first.reason)) {
                    countDrop(dropReasonFor(first.reason))
                    return
                }
                // Authority was withdrawn under this facade (expiry, or a context change from a
                // concurrent owner); one renewal decides whether the call proceeds or is dropped.
                renewAuthority()
                if (state !is EluFacadeState.Enabled) {
                    countDrop(currentDropReason())
                    return
                }
                val second = send().await()
                observeStartup(startupObserver) { RuntimeStartupObservation(RuntimeStartupPhase.CAPTURE_SECOND,
                    captureAccepted = second is RuntimeCaptureResult.Accepted, captureRejection = (second as? RuntimeCaptureResult.Rejected)?.reason) }
                when (second) {
                    is RuntimeCaptureResult.Accepted -> syncIdentity(second.snapshot.state.identity)
                    is RuntimeCaptureResult.Rejected -> countDrop(dropReasonFor(second.reason))
                }
            }
        }
    }

    // Identity/context chronology belongs to the accepted API call, just like capture timestamps.
    // Sampling it after a held lane resumes would make the immediately following event look stale.
    private fun appendMutations(changes: List<RuntimeMutationChange>, occurredAt: String) {
        val open = stack ?: return
        val drafts = changes.map { change -> RuntimeRecordDraft.Mutation(occurredAt, change, versions) }
        when (val result = open.owner.appendMutations(drafts).await()) {
            is RuntimeAppendResult.Accepted ->
                syncIdentity(result.snapshot.state.identity)
            is RuntimeAppendResult.Rejected ->
                countDrop(if (result.reason == RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE) EluFacadeDropReason.UNAUTHORIZED else EluFacadeDropReason.STORAGE)
        }
        renewAuthority()
    }

    private fun hasCurrentCapture(): Boolean = state is EluFacadeState.Enabled && hasCurrentConfiguration()

    private fun applyFlagContext(change: RuntimeLocalStateChange) {
        // The unbound injected facade remains a local test/compatibility seam. Production always
        // uses the source-bound owner operation and its durable flag authority transaction.
        if (configurationGate == null) { applyLocalChange(change); return }
        when (val result = requireStack().owner.applyFlagContext(change).await()) {
            is RuntimeAppendResult.Accepted -> syncIdentity(result.snapshot.state.identity)
            is RuntimeAppendResult.Rejected -> countDrop(
                if (result.reason == RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE) EluFacadeDropReason.UNAUTHORIZED
                else EluFacadeDropReason.STORAGE)
        }
        renewAuthority()
    }

    private fun applyLocalChange(change: RuntimeLocalStateChange) {
        val open = stack ?: return
        when (val result = open.owner.applyLocal(change).await()) {
            is RuntimeAppendResult.Accepted ->
                syncIdentity(result.snapshot.state.identity)
            is RuntimeAppendResult.Rejected ->
                countDrop(EluFacadeDropReason.STORAGE)
        }
        renewAuthority()
    }

    private fun identifyOnLane(
        userId: String,
        set: Map<String, Any?>,
        occurredAt: String,
        setOnce: Map<String, Any?> = emptyMap(),
    ) {
        if (userId != persistedDistinctId()) {
            appendMutations(listOf(RuntimeMutationChange.Identify(userId, set, setOnce)), occurredAt)
            cachedPersonProperties = personPropertiesKey(userId, set, setOnce)
            startFlagReload()
            return
        }
        if (set.isNotEmpty() || setOnce.isNotEmpty()) appendPersonProperties(set, reloadFlags = true, occurredAt = occurredAt, setOnce = setOnce)
    }

    private fun appendPersonProperties(
        set: Map<String, Any?>,
        reloadFlags: Boolean,
        occurredAt: String,
        setOnce: Map<String, Any?> = emptyMap(),
    ) {
        val key = personPropertiesKey(persistedDistinctId(), set, setOnce)
        // Repeating exactly the previous person-property call for the same identity changes nothing.
        if (cachedPersonProperties == key) return
        appendMutations(
            listOf(
                RuntimeMutationChange.SetPersonProperties(
                    set = set,
                    setOnce = setOnce,
                    unset = emptyList(),
                ),
            ),
            occurredAt,
        )
        cachedPersonProperties = key
        if (reloadFlags) startFlagReload()
    }

    private fun persistedDistinctId(): String {
        val current = identity ?: return ""
        return current.userId ?: current.anonymousId
    }

    private fun personPropertiesKey(
        distinctId: String,
        set: Map<String, Any?>,
        setOnce: Map<String, Any?> = emptyMap(),
    ): String = "$distinctId\u0000$set\u0000$setOnce"

    private fun syncIdentity(next: IdentityState) {
        val previous = identity
        identity = next
        val priorSession = previous?.session
        val currentSession = next.session
        if (priorSession?.id != currentSession?.id || priorSession?.startedAt != currentSession?.startedAt ||
            priorSession?.lifecycle != currentSession?.lifecycle || priorSession?.backgroundedAt != currentSession?.backgroundedAt) {
            nativeSessionRefreshNeeded = true
        }
        if (previous != null && (previous.revision != next.revision || previous.contextRevision != next.contextRevision)) {
            invalidateFlagProjection()
        }
        if (exposureIdentityRevision != next.revision) {
            // Exposure is reported once per key and value for one identity; a new identity starts
            // a new ledger.
            exposures.clear()
            exposureIdentityRevision = next.revision
        }
    }

    private fun requireStack(): StandaloneStack = checkNotNull(stack) { "The standalone runtime is not open" }

    // ---- flag snapshot, listeners and exposure -------------------------------

    private class FlagEntry(
        val present: Boolean,
        val value: Any?,
        val payload: Any?,
        val cacheLeaseToken: FlagCacheLeaseToken?,
    )

    private fun readFlag(
        key: String,
        expose: Boolean,
    ): FlagEntry? {
        if (key.isEmpty()) return null
        val intent = flagIntentRevision
        if (!flagsLoaded || !flagIntentIsCurrent(intent) || !hasCurrentFlags()) return null
        val generation = flagGeneration
        val entry = flagProjection[key]?.takeIf(::entryIsCurrent)
        submit {
            // The owned client resolves one key at a time, so a key is read once here and then
            // refreshed on every later load.
            if (observedFlagKeys.add(key)) resolveFlag(key)
            if (expose && generation == flagGeneration && flagIntentIsCurrent(intent)) reportExposure(key, entry)
        }
        return entry?.takeIf { it.present && generation == flagGeneration && flagIntentIsCurrent(intent) && hasCurrentFlags() && entryIsCurrent(it) }
    }

    private fun entryIsCurrent(entry: FlagEntry): Boolean =
        entry.cacheLeaseToken?.let { stack?.flags?.isCacheLeaseCurrent(it) } == true

    /** Reports `$feature_flag_called` once per flag key and reported value for one identity. */
    private fun reportExposure(
        key: String,
        entry: FlagEntry?,
    ) {
        val intent = flagIntentRevision
        if (entry == null || !flagIntentIsCurrent(intent) || !hasCurrentFlags() || !entryIsCurrent(entry)) return
        val ledgerKey = "$key\u0000${entry.value}"
        if (!exposures.add(ledgerKey)) return
        val identityRevision = exposureIdentityRevision
        val properties = LinkedHashMap<String, Any?>()
        properties["\$feature_flag"] = key
        if (entry.present) properties["\$feature_flag_response"] = entry.value
        properties["\$feature_flag_payload"] = entry.payload
        if (!entry.present) properties["\$feature_flag_error"] = "flag_missing"
        val occurredAt = now()
        dispatch(
            kind = OperationKind.ACTIVITY,
            onDropped = {
                // A discarded exposure was never reported, so the next read of that value reports
                // it again as long as the identity that recorded it still stands.
                if (exposureIdentityRevision == identityRevision) exposures.remove(ledgerKey)
            },
        ) {
            if (flagIntentIsCurrent(intent) && entryIsCurrent(entry) && exposureIdentityRevision == identityRevision) {
                val runtime = requireStack().runtime
                captureThrough { runtime.capture(FEATURE_FLAG_CALLED_EVENT, properties, occurredAt) }
            } else exposures.remove(ledgerKey)
        }
    }

    /**
     * Starts one reload. Concurrent requests join the reload already running, and the lane never
     * waits for it: the request leaves the client on its own lane and the outcome comes back as
     * another lane task, so captures behind it are not held for a network round trip.
     */
    private fun startFlagReload() {
        if (closed || pendingFlagOperations != 0 || !hasCurrentFlags()) return
        if (stack?.flags == null || flagReloadGeneration != null) return
        flagReloadGeneration = flagGeneration
        flagReloadAttempts = 0
        beginFlagReloadAttempt()
    }

    private fun beginFlagReloadAttempt() {
        val client = stack?.flags
        val generation = flagReloadGeneration
        if (client == null || generation == null) return
        flagReloadAttempts += 1
        val intent = flagIntentRevision
        val pending =
            try {
                client.reload()
            } catch (error: Throwable) {
                countDrop(classify(error))
                finishFlagReload()
                return
            }
        pending.whenComplete { result, error ->
            submit { if (flagIntentIsCurrent(intent)) settleFlagReload(generation, result, error) }
        }
    }

    private fun settleFlagReload(
        generation: Long,
        result: FlagReloadResult?,
        error: Throwable?,
    ) {
        // A reload the identity outlived is discarded: its flags belong to the identity that ended.
        if (generation != flagReloadGeneration) return
        if (error != null) {
            countDrop(classify(error))
            finishFlagReload()
            return
        }
        if (closed || !hasCurrentFlags()) {
            finishFlagReload()
            return
        }
        when (result) {
            is FlagReloadResult.Updated -> {
                loadedCacheToken = result.cacheLeaseToken
                if (loadedCacheToken?.let { stack?.flags?.isCacheLeaseCurrent(it) } != true) {
                    finishFlagReload()
                    return
                }
                observedFlagKeys.forEach { key -> resolveFlag(key) }
                flagsLoaded = true
                // Listeners see the new snapshot before the reload's own completion runs.
                fireFlagListeners()
                finishFlagReload()
            }
            // The reload was superseded by a context or configuration change; the next attempt
            // evaluates the current witness.
            FlagReloadResult.Stale ->
                if (flagReloadAttempts < MAX_FLAG_RELOAD_ATTEMPTS) {
                    beginFlagReloadAttempt()
                } else {
                    finishFlagReload()
                }
            else -> {
                // A load that failed still ends the "flags have not loaded" phase, so getters
                // report their documented defaults instead of waiting for a retry.
                flagsLoaded = true
                fireFlagListeners()
                finishFlagReload()
            }
        }
    }

    private fun finishFlagReload() {
        flagReloadGeneration = null
        val completions = reloadCompletions.toList()
        reloadCompletions.clear()
        completions.forEach { completion -> deliverFlagCallback(completion) }
    }

    private fun resolveFlag(key: String) {
        val client = stack?.flags ?: return
        val intent = flagIntentRevision
        if (!flagIntentIsCurrent(intent)) return
        val read =
            try {
                client.read(key).await()
            } catch (error: Throwable) {
                countDrop(classify(error))
                return
            }
        if (!flagIntentIsCurrent(intent) || !hasCurrentFlags()) return
        val entry =
            when (read) {
                is FlagReadResult.Found ->
                    FlagEntry(
                        present = true,
                        value = publicFlagValue(platformValue(read.value)),
                        payload = read.payload?.let(::platformValue),
                        cacheLeaseToken = read.cacheLeaseToken,
                    )
                // Every other outcome is "no value for this key from a usable cache".
                else -> FlagEntry(present = false, value = null, payload = null, cacheLeaseToken = loadedCacheToken)
            }
        flagProjection = LinkedHashMap(flagProjection).apply { put(key, entry) }
    }

    private fun clearFlags() {
        observedFlagKeys.clear()
        invalidateFlagProjection()
    }

    private fun invalidateFlagProjection() {
        flagProjection = emptyMap()
        flagsLoaded = false
        loadedCacheToken = null
        flagGeneration = Math.incrementExact(flagGeneration)
        flagReloadGeneration = null
        flagReloadAttempts = 0
        reloadCompletions.clear()
    }

    private fun fireFlagListeners() {
        listeners.toList().forEach { listener -> deliverFlagCallback(listener) }
    }

    private fun deliverFlagCallback(callback: () -> Unit) {
        val witness = flagConfiguration
        val intent = flagIntentRevision
        val generation = flagGeneration
        val cacheToken = loadedCacheToken
        deliver {
            if ((cacheToken == null || stack?.flags?.isCacheLeaseCurrent(cacheToken) == true) &&
                generation == flagGeneration && flagIntentIsCurrent(intent) && hasCurrentFlags() &&
                (configurationGate == null || witness?.isCurrent() == true)) callback()
        }
    }

    private fun deliver(callback: () -> Unit) {
        deliverCallback(
            Runnable {
                try {
                    callback()
                } catch (_: Throwable) {
                    // A customer callback that throws never reaches the caller of the facade.
                    listenerErrors.incrementAndGet()
                }
            },
        )
    }

    // ---- identity projection -------------------------------------------------

    private class ProjectedIdentity(val distinctId: String?)

    private fun projectIdentity(projection: ProjectedIdentity): Boolean {
        if (state !is EluFacadeState.Enabled) return false
        synchronized(projectionLock) {
            pendingIdentityOperations += 1
            projectedIdentity = projection
        }
        return true
    }

    private fun settleProjection() {
        synchronized(projectionLock) {
            if (pendingIdentityOperations > 0) pendingIdentityOperations -= 1
            if (pendingIdentityOperations == 0) projectedIdentity = null
        }
    }

    // ---- accounting and conversion -------------------------------------------

    private fun countDrop(reason: EluFacadeDropReason) = countDrops(reason, 1)

    private fun countDrops(
        reason: EluFacadeDropReason,
        count: Int,
    ) {
        if (count <= 0) return
        synchronized(dropCounts) { dropCounts[reason] = (dropCounts[reason] ?: 0) + count }
        observeStartup(startupObserver) { RuntimeStartupObservation(RuntimeStartupPhase.DROP,
            drop = RuntimeStartupDrop.valueOf(reason.name), dropCount = synchronized(dropCounts) { dropCounts[reason] }) }
    }

    private fun currentDropReason(): EluFacadeDropReason =
        when (val current = state) {
            is EluFacadeState.Disabled -> current.reason.drop
            is EluFacadeState.Closed -> EluFacadeDropReason.CLOSED
            else -> EluFacadeDropReason.UNAUTHORIZED
        }

    private fun classify(error: Throwable): EluFacadeDropReason =
        if (error is IllegalArgumentException) EluFacadeDropReason.INVALID_INPUT else EluFacadeDropReason.STORAGE

    /**
     * Removes the properties the runtime derives from identity and session state, so a customer
     * value can never shadow the identity an event is attributed to.
     */
    private fun withoutReservedKeys(properties: Map<String, Any>?): Map<String, Any?> {
        if (properties.isNullOrEmpty()) return emptyMap()
        val kept = LinkedHashMap<String, Any?>(properties.size)
        var removed = 0
        properties.forEach { (key, value) ->
            if (isReservedPropertyKey(key)) removed += 1 else kept[key] = value
        }
        countDrops(EluFacadeDropReason.RESERVED_PROPERTY, removed)
        return kept
    }

    private fun now(): String = RuntimeWallTimestamps.rfc3339(wallClock())

    private fun <T> Future<T>.await(): T =
        try {
            get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }

    internal companion object {
        /** Calls held before the first configuration decision, matching the mobile contract. */
        const val PRE_INIT_BUFFER_LIMIT = 100
        const val FEATURE_FLAG_CALLED_EVENT = "\$feature_flag_called"
        private const val MAX_FLAG_RELOAD_ATTEMPTS = 3

        private val RESERVED_PROPERTY_KEYS =
            setOf(
                "distinct_id",
                "\$device_id",
                "\$user_id",
                "\$anon_distinct_id",
                "\$session_id",
                "\$window_id",
                "\$groups",
                "\$is_identified",
            )

        /** Prefix of the version properties the runtime stamps on every record. */
        private const val RESERVED_PROPERTY_PREFIX = "\$elu_"

        fun isReservedPropertyKey(key: String): Boolean =
            key in RESERVED_PROPERTY_KEYS || key.startsWith(RESERVED_PROPERTY_PREFIX)

        fun isUsableIdentifier(value: String): Boolean = value.isNotEmpty() && value.length <= 512

        fun isAuthorityRejection(reason: RuntimeCaptureRejection): Boolean =
            reason == RuntimeCaptureRejection.AUTHORITY_ABSENT ||
                reason == RuntimeCaptureRejection.AUTHORITY_PENDING ||
                reason == RuntimeCaptureRejection.AUTHORITY_TERMINAL ||
                reason == RuntimeCaptureRejection.AUTHORITY_EXPIRED ||
                reason == RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED

        fun dropReasonFor(reason: RuntimeCaptureRejection): EluFacadeDropReason =
            when (reason) {
                RuntimeCaptureRejection.OPTED_OUT -> EluFacadeDropReason.OPTED_OUT
                RuntimeCaptureRejection.EVENT_INVALID -> EluFacadeDropReason.INVALID_INPUT
                RuntimeCaptureRejection.QUEUE_LIMIT -> EluFacadeDropReason.STORAGE
                else -> EluFacadeDropReason.UNAUTHORIZED
            }

        /**
         * Projects one stored flag value onto the public domain: a string is the variant, a boolean
         * is the enabled state, a number is enabled when non-zero, and anything else is disabled.
         */
        fun publicFlagValue(value: Any?): Any? =
            when (value) {
                is String -> value
                is Boolean -> value
                is Long -> value != 0L
                is Double -> value != 0.0
                else -> false
            }

        fun isTruthyVariant(value: Any?): Boolean =
            when (value) {
                is Boolean -> value
                is String -> value.isNotEmpty()
                else -> false
            }

        fun platformValue(value: FlagJsonValue): Any? =
            when (value) {
                is FlagJsonValue.StringValue -> value.value
                is FlagJsonValue.BooleanValue -> value.value
                is FlagJsonValue.NumberValue -> value.canonical.toLongOrNull() ?: value.value
                is FlagJsonValue.ArrayValue -> value.values.map(::platformValue)
                is FlagJsonValue.ObjectValue ->
                    LinkedHashMap<String, Any?>(value.members.size).apply {
                        value.members.forEach { member -> put(member.key, platformValue(member.value)) }
                    }
                FlagJsonValue.NullValue -> null
            }
    }
}
