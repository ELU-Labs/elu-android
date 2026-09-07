package dev.elu.analytics.internal.facade

import dev.elu.analytics.internal.config.V1FlagAuthorizationResolution
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.flags.FlagJsonValue
import dev.elu.analytics.internal.flags.FlagReadResult
import dev.elu.analytics.internal.flags.FlagReloadResult
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
import java.util.Date
import java.util.EnumMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The feature-flag operations the facade needs. The owned flag client conforms to it; the facade
 * never names that client, so the client keeps its release boundary of having no production
 * construction and no concrete transport.
 */
internal interface FacadeFlagClient : AutoCloseable {
    fun applyConfiguration(configBody: String?): CompletableFuture<V1FlagAuthorizationResolution>

    fun reload(): CompletableFuture<FlagReloadResult>

    fun read(key: String): CompletableFuture<FlagReadResult>
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
    val buffered: Int,
    val dropped: Map<EluFacadeDropReason, Int>,
    val listenerErrors: Int,
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
 *   executable capture authority. Every activity call is accepted and discarded with that reason —
 *   nothing is stored, nothing is sent, no flag request leaves. `reset` still applies, because it
 *   is how a device leaves an opted-out identity.
 *
 * Identity continuity with the embedded runtime is deliberately NOT implemented here. With this
 * runtime selected, a fresh install starts a fresh ELU identity: nothing in this file reads the
 * previous runtime's storage. Reading that storage is separate work with its own review, and the
 * selector must not be mistaken for a migration.
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
) : EluFacadeSink, AutoCloseable {
    private val lane: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "elu-facade").apply { isDaemon = true }
        }

    // Lane-confined.
    private val buffer = ArrayDeque<Operation>()
    private val listeners = mutableListOf<() -> Unit>()
    private val observedFlagKeys = LinkedHashSet<String>()
    private val exposures = HashSet<String>()
    private var exposureIdentityRevision: Long? = null
    private var stack: StandaloneStack? = null
    private var configDocument: String? = null
    private var cachedPersonProperties: String? = null
    private val listenerErrors = AtomicInteger()

    // Counted from any thread: reserved-property removals are accounted at call time.
    private val dropCounts = EnumMap<EluFacadeDropReason, Int>(EluFacadeDropReason::class.java)
    private val projectionLock = Any()
    private var pendingIdentityOperations = 0

    @Volatile private var state: EluFacadeState = EluFacadeState.Pending

    @Volatile private var closed = false

    @Volatile private var identity: IdentityState? = null

    /** Non-null while a queued identity call has not settled, so getters follow call order. */
    @Volatile private var projectedIdentity: ProjectedIdentity? = null

    @Volatile private var flagProjection: Map<String, FlagEntry> = emptyMap()

    @Volatile private var flagsLoaded = false

    // ---- lifecycle -----------------------------------------------------------

    /** Opens the owned runtime on the facade lane. Calls made before it completes are held. */
    fun start() {
        submit {
            if (closed || stack != null) return@submit
            try {
                val opened = open()
                stack = opened
                syncIdentity(opened.owner.snapshot().await().state.identity)
            } catch (_: Throwable) {
                // Storage the facade cannot open fails closed: nothing is captured this run.
                countDrop(EluFacadeDropReason.STORAGE)
                transition(EluFacadeState.Disabled(EluFacadeDisabledReason.UNAUTHORIZED))
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
        submit {
            if (closed) return@submit
            configDocument = configBody
            renewAuthority()
        }
    }

    fun state(): EluFacadeState = state

    /** Completes once every call submitted before it has run. */
    fun settled(): Future<Unit> = lane.submit<Unit> { }

    fun diagnostics(): Future<EluFacadeDiagnostics> =
        lane.submit<EluFacadeDiagnostics> {
            EluFacadeDiagnostics(
                state = state,
                buffered = buffer.size,
                dropped = synchronized(dropCounts) { EnumMap(dropCounts) },
                listenerErrors = listenerErrors.get(),
            )
        }

    override fun close() {
        try {
            lane.execute {
                if (closed) return@execute
                closed = true
                countDrops(EluFacadeDropReason.CLOSED, buffer.size)
                buffer.clear()
                state = EluFacadeState.Closed
                stack?.let { open ->
                    runCatching { open.flags?.close() }
                    runCatching { open.runtime.close() }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Already closing.
        }
        lane.shutdown()
    }

    // ---- events --------------------------------------------------------------

    override fun capture(
        event: String,
        properties: Map<String, Any>?,
        timestamp: Date,
    ) {
        if (event.isEmpty()) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val eventProperties = withoutReservedKeys(properties)
        // The caller's timestamp, so a call held while pending is not re-stamped at release.
        val occurredAt = RuntimeWallTimestamps.rfc3339(timestamp.time)
        dispatch(OperationKind.ACTIVITY) {
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
        dispatch(OperationKind.ACTIVITY) {
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
        dispatch(OperationKind.ACTIVITY) {
            val runtime = requireStack().runtime
            captureThrough { runtime.captureException(error, exceptionProperties, occurredAt) }
        }
    }

    // ---- identity ------------------------------------------------------------

    override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
    ) {
        if (!isUsableIdentifier(distinctId) || distinctId == "distinct_id") {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        val set = userProperties?.toMap().orEmpty()
        val projected = projectIdentity(ProjectedIdentity(distinctId))
        dispatch(OperationKind.ACTIVITY, projected) { identifyOnLane(distinctId, set) }
    }

    override fun alias(alias: String) {
        if (!isUsableIdentifier(alias)) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        dispatch(OperationKind.ACTIVITY) {
            val canonicalId = persistedDistinctId()
            if (alias == canonicalId) {
                // Aliasing an id to itself is the identify transition for that id.
                identifyOnLane(alias, emptyMap())
            } else {
                appendMutations(listOf(RuntimeMutationChange.LinkAlias(alias, canonicalId)))
            }
        }
    }

    override fun reset() {
        // The owner mints the replacement anonymous id, so the facade cannot name it in advance;
        // until the reset commits, the getter reports no identity rather than the ended one.
        val projected = projectIdentity(ProjectedIdentity(null))
        dispatch(OperationKind.RESET, projected) {
            applyLocalChange(RuntimeLocalStateChange.ResetIdentity(now()))
            if (identity?.optedOut == true) {
                applyLocalChange(RuntimeLocalStateChange.SetOptedOut(false, now()))
            }
            cachedPersonProperties = null
            clearFlags()
            if (state is EluFacadeState.Enabled) reloadFlags()
        }
    }

    override fun distinctId(): String? {
        if (state !is EluFacadeState.Enabled) return null
        projectedIdentity?.let { return it.distinctId }
        val current = identity ?: return null
        return current.userId ?: current.anonymousId
    }

    // ---- properties ----------------------------------------------------------

    override fun register(properties: Map<String, Any>) {
        val superProperties = withoutReservedKeys(properties)
        if (superProperties.isEmpty()) return
        if (superProperties.keys.any { it.isEmpty() }) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        dispatch(OperationKind.ACTIVITY) {
            applyLocalChange(RuntimeLocalStateChange.RegisterSuperProperties(superProperties, now()))
        }
    }

    override fun unregister(key: String) {
        if (key.isEmpty()) {
            countDrop(EluFacadeDropReason.INVALID_INPUT)
            return
        }
        if (isReservedPropertyKey(key)) return
        dispatch(OperationKind.ACTIVITY) {
            // Unregistering an absent key would still advance the context revision.
            if (identity?.superProperties?.containsKey(key) != true) return@dispatch
            applyLocalChange(RuntimeLocalStateChange.UnregisterSuperProperties(listOf(key), now()))
        }
    }

    override fun setPersonProperties(properties: Map<String, Any>) {
        if (properties.isEmpty()) return
        val set = properties.toMap()
        dispatch(OperationKind.ACTIVITY) { appendPersonProperties(set, reloadFlags = true) }
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
        dispatch(OperationKind.ACTIVITY) {
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
            appendMutations(changes)
            reloadFlags()
        }
    }

    // ---- feature flags -------------------------------------------------------

    override fun getFeatureFlag(key: String): Any? = readFlag(key, expose = true)?.value

    override fun getFeatureFlagPayload(key: String): Any? = readFlag(key, expose = false)?.payload

    override fun isFeatureEnabled(key: String): Boolean = isTruthyVariant(readFlag(key, expose = true)?.value)

    override fun reloadFeatureFlags(completion: (() -> Unit)?) {
        // A reload is a command, not a state change: it is never replayed from the hold buffer.
        submit {
            if (state !is EluFacadeState.Enabled) {
                countDrop(currentDropReason())
                return@submit
            }
            reloadFlags()
            completion?.let { deliver(it) }
        }
    }

    override fun onFeatureFlagsLoaded(callback: () -> Unit) {
        submit {
            listeners += callback
            // Add-and-fire on one lane: a listener either made this load's snapshot or missed it
            // and replays once. Later loads legitimately re-fire every listener in order.
            if (state is EluFacadeState.Enabled && flagsLoaded) deliver(callback)
        }
    }

    override fun setPersonPropertiesForFlags(properties: Map<String, Any>) {
        if (properties.isEmpty()) return
        val set = properties.toMap()
        dispatch(OperationKind.ACTIVITY) {
            applyLocalChange(RuntimeLocalStateChange.SetFlagPersonProperties(set, now()))
            reloadFlags()
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
        dispatch(OperationKind.ACTIVITY) {
            applyLocalChange(RuntimeLocalStateChange.SetFlagGroupProperties(type, set, now()))
            reloadFlags()
        }
    }

    // ---- transport -----------------------------------------------------------

    override fun flush() {
        submit {
            if (state !is EluFacadeState.Enabled) {
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
        RESET,
    }

    private class Operation(
        val kind: OperationKind,
        val projected: Boolean,
        val onDropped: (() -> Unit)?,
        val run: () -> Unit,
    )

    private fun dispatch(
        kind: OperationKind,
        projected: Boolean = false,
        onDropped: (() -> Unit)? = null,
        run: () -> Unit,
    ) {
        val operation = Operation(kind, projected, onDropped, run)
        val accepted =
            submit {
                if (closed) {
                    discard(operation, EluFacadeDropReason.CLOSED)
                    return@submit
                }
                if (state is EluFacadeState.Pending) {
                    if (buffer.size >= bufferLimit) {
                        // Drop the newest, never the oldest: the held calls are an ordered identity
                        // history, and a coherent prefix is worth more than a recent fragment.
                        discard(operation, EluFacadeDropReason.BUFFER_FULL)
                        return@submit
                    }
                    buffer.addLast(operation)
                    return@submit
                }
                execute(operation)
            }
        if (!accepted) discard(operation, EluFacadeDropReason.CLOSED)
    }

    private fun execute(operation: Operation) {
        val current = state
        try {
            when {
                current is EluFacadeState.Closed -> discard(operation, EluFacadeDropReason.CLOSED, settle = false)
                current is EluFacadeState.Pending ->
                    discard(operation, EluFacadeDropReason.UNAUTHORIZED, settle = false)
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
            if (operation.projected) settleProjection()
        }
    }

    private fun discard(
        operation: Operation,
        reason: EluFacadeDropReason,
        settle: Boolean = true,
    ) {
        countDrop(reason)
        operation.onDropped?.invoke()
        if (settle && operation.projected) settleProjection()
    }

    private fun submit(block: () -> Unit): Boolean =
        try {
            lane.execute {
                try {
                    block()
                } catch (error: Throwable) {
                    countDrop(classify(error))
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    private fun transition(next: EluFacadeState) {
        val previous = state
        state = next
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
    private fun renewAuthority() {
        val open = stack ?: return
        val document = configDocument ?: return
        val result = open.runtime.applyConfiguration(document).await()
        open.flags?.let { client -> runCatching { client.applyConfiguration(document).await() } }
        syncIdentity(open.owner.snapshot().await().state.identity)
        val wasEnabled = state is EluFacadeState.Enabled
        val next =
            when (result) {
                is RuntimeCaptureAuthorityUpdateResult.Activated -> EluFacadeState.Enabled
                is RuntimeCaptureAuthorityUpdateResult.Terminated ->
                    EluFacadeState.Disabled(disabledReasonFor(result.authority.reason))
            }
        transition(next)
        if (next is EluFacadeState.Enabled && !wasEnabled) reloadFlags()
    }

    private fun disabledReasonFor(reason: RuntimeCaptureAuthorityTerminalReason): EluFacadeDisabledReason =
        when {
            reason == RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED -> EluFacadeDisabledReason.BLOCKED
            identity?.optedOut == true -> EluFacadeDisabledReason.OPTED_OUT
            else -> EluFacadeDisabledReason.UNAUTHORIZED
        }

    private fun captureThrough(send: () -> Future<RuntimeCaptureResult>) {
        when (val first = send().await()) {
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
                when (val second = send().await()) {
                    is RuntimeCaptureResult.Accepted -> syncIdentity(second.snapshot.state.identity)
                    is RuntimeCaptureResult.Rejected -> countDrop(dropReasonFor(second.reason))
                }
            }
        }
    }

    private fun appendMutations(changes: List<RuntimeMutationChange>) {
        val open = stack ?: return
        val occurredAt = now()
        val drafts = changes.map { change -> RuntimeRecordDraft.Mutation(occurredAt, change, versions) }
        when (val result = open.owner.appendMutations(drafts).await()) {
            is RuntimeAppendResult.Accepted ->
                syncIdentity(result.snapshot.state.identity)
            is RuntimeAppendResult.Rejected ->
                countDrop(EluFacadeDropReason.STORAGE)
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
    ) {
        if (userId != persistedDistinctId()) {
            appendMutations(listOf(RuntimeMutationChange.Identify(userId, set, emptyMap())))
            cachedPersonProperties = personPropertiesKey(userId, set)
            reloadFlags()
            return
        }
        if (set.isNotEmpty()) appendPersonProperties(set, reloadFlags = true)
    }

    private fun appendPersonProperties(
        set: Map<String, Any?>,
        reloadFlags: Boolean,
    ) {
        val key = personPropertiesKey(persistedDistinctId(), set)
        // Repeating exactly the previous person-property call for the same identity changes nothing.
        if (cachedPersonProperties == key) return
        appendMutations(
            listOf(
                RuntimeMutationChange.SetPersonProperties(
                    set = set,
                    setOnce = emptyMap(),
                    unset = emptyList(),
                ),
            ),
        )
        cachedPersonProperties = key
        if (reloadFlags) reloadFlags()
    }

    private fun persistedDistinctId(): String {
        val current = identity ?: return ""
        return current.userId ?: current.anonymousId
    }

    private fun personPropertiesKey(
        distinctId: String,
        set: Map<String, Any?>,
    ): String = "$distinctId $set"

    private fun syncIdentity(next: IdentityState) {
        identity = next
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
    )

    private fun readFlag(
        key: String,
        expose: Boolean,
    ): FlagEntry? {
        if (key.isEmpty()) return null
        if (state !is EluFacadeState.Enabled || !flagsLoaded) return null
        val entry = flagProjection[key]
        submit {
            // The owned client resolves one key at a time, so a key is read once here and then
            // refreshed on every later load.
            if (observedFlagKeys.add(key)) resolveFlag(key)
            if (expose) reportExposure(key, entry)
        }
        return entry?.takeIf { it.present }
    }

    /** Reports `$feature_flag_called` once per flag key and reported value for one identity. */
    private fun reportExposure(
        key: String,
        entry: FlagEntry?,
    ) {
        if (entry == null) return
        val ledgerKey = "$key ${entry.value}"
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
            val runtime = requireStack().runtime
            captureThrough { runtime.capture(FEATURE_FLAG_CALLED_EVENT, properties, occurredAt) }
        }
    }

    /** Runs one reload and republishes the flags this facade has been asked about. */
    private fun reloadFlags() {
        val client = stack?.flags ?: return
        repeat(MAX_FLAG_RELOAD_ATTEMPTS) {
            if (state !is EluFacadeState.Enabled || closed) return
            val result =
                try {
                    client.reload().await()
                } catch (error: Throwable) {
                    countDrop(classify(error))
                    return
                }
            when (result) {
                is FlagReloadResult.Updated -> {
                    observedFlagKeys.forEach { key -> resolveFlag(key) }
                    flagsLoaded = true
                    fireFlagListeners()
                    return
                }
                // The reload was superseded by a context or configuration change; the next attempt
                // evaluates the current witness.
                FlagReloadResult.Stale -> Unit
                else -> {
                    // A load that failed still ends the "flags have not loaded" phase, so getters
                    // report their documented defaults instead of blocking on a retry.
                    flagsLoaded = true
                    fireFlagListeners()
                    return
                }
            }
        }
    }

    private fun resolveFlag(key: String) {
        val client = stack?.flags ?: return
        val read =
            try {
                client.read(key).await()
            } catch (error: Throwable) {
                countDrop(classify(error))
                return
            }
        val entry =
            when (read) {
                is FlagReadResult.Found ->
                    FlagEntry(
                        present = true,
                        value = publicFlagValue(platformValue(read.value)),
                        payload = read.payload?.let(::platformValue),
                    )
                // Every other outcome is "no value for this key from a usable cache".
                else -> FlagEntry(present = false, value = null, payload = null)
            }
        flagProjection = LinkedHashMap(flagProjection).apply { put(key, entry) }
    }

    private fun clearFlags() {
        flagProjection = emptyMap()
        flagsLoaded = false
        observedFlagKeys.clear()
        exposures.clear()
    }

    private fun fireFlagListeners() {
        listeners.toList().forEach { listener -> deliver(listener) }
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
