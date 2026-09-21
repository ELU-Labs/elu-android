package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.concurrent.SdkFuture

import dev.elu.analytics.internal.config.V1ChannelAuthorizationStatus
import dev.elu.analytics.internal.config.V1ChannelAuthorizationReason
import dev.elu.analytics.internal.config.V2ConfigAuthorityGate
import dev.elu.analytics.internal.config.V2ConfigAuthorityWitness
import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ConfigManager
import dev.elu.analytics.internal.config.V1ConfigRejection
import dev.elu.analytics.internal.config.V1ConfigResolution
import dev.elu.analytics.internal.config.V1ConfigStatus
import dev.elu.analytics.internal.config.V1ConfigUpdateResult
import dev.elu.analytics.internal.config.V1ExactTimestamp
import dev.elu.analytics.internal.config.V1FlagAuthorizationResolution
import dev.elu.analytics.internal.config.V1FlagProjectionRejection
import dev.elu.analytics.internal.config.V1MalformedConfigException
import dev.elu.analytics.internal.config.V1ParsedConfigBoundary
import dev.elu.analytics.internal.config.V1UnsupportedConfigSchemaException
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.JsonValues
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.SessionState
import dev.elu.analytics.internal.core.UuidCoreIdentifierGenerator
import dev.elu.analytics.internal.flags.FlagBeginResult
import dev.elu.analytics.internal.flags.FlagCacheExpiryStoreResult
import dev.elu.analytics.internal.flags.FlagCacheLeaseToken
import dev.elu.analytics.internal.flags.FlagCommitStoreResult
import dev.elu.analytics.internal.flags.FlagConfigStoreResult
import dev.elu.analytics.internal.flags.FlagDurableStore
import dev.elu.analytics.internal.flags.FlagFinalizeStoreResult
import dev.elu.analytics.internal.flags.FlagLeaseExpiryStoreResult
import dev.elu.analytics.internal.flags.FlagReadResult
import dev.elu.analytics.internal.flags.FlagReloadResult
import dev.elu.analytics.internal.flags.FlagReloadWitnessSnapshot
import dev.elu.analytics.internal.flags.FlagPreSendResult
import dev.elu.analytics.internal.flags.FlagResponse
import dev.elu.analytics.internal.flags.FlagRestrictionReason
import dev.elu.analytics.internal.replay.*
import dev.elu.analytics.internal.config.V1ReplayTransport
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

internal data class RuntimeQueueLimits(
    val maximumCount: Int,
    val maximumBytes: Long,
) {
    init {
        require(maximumCount in 1..MAX_RUNTIME_QUEUE_RECORDS) {
            "maximumCount must be in 1..$MAX_RUNTIME_QUEUE_RECORDS"
        }
        require(maximumBytes in 1..MAX_RUNTIME_QUEUE_BYTES) {
            "maximumBytes must be in 1..$MAX_RUNTIME_QUEUE_BYTES"
        }
    }
}

internal data class RuntimeQueueSnapshot(
    val state: PersistedCoreState,
    val queuedCount: Int,
    val queuedBytes: Long,
    /** The first queued sequence, or nextSequence when the queue is empty. */
    val headSequence: Long,
)

internal enum class RuntimeAppendRejection {
    AUTHORIZATION_UNAVAILABLE,
    COUNT_LIMIT,
    BYTE_LIMIT,
    RECORD_TOO_LARGE,
}

internal sealed interface RuntimeAppendResult {
    data class Accepted(
        val records: List<RuntimeQueuedRecord>,
        val snapshot: RuntimeQueueSnapshot,
    ) : RuntimeAppendResult

    data class Rejected(
        val reason: RuntimeAppendRejection,
        val snapshot: RuntimeQueueSnapshot,
    ) : RuntimeAppendResult
}

internal sealed interface RuntimeAcknowledgementResult {
    val snapshot: RuntimeQueueSnapshot

    data class Deleted(
        val count: Int,
        override val snapshot: RuntimeQueueSnapshot,
    ) : RuntimeAcknowledgementResult

    data class AlreadyApplied(override val snapshot: RuntimeQueueSnapshot) : RuntimeAcknowledgementResult

    data class Empty(override val snapshot: RuntimeQueueSnapshot) : RuntimeAcknowledgementResult
}

internal class RuntimeAcknowledgementMismatchException(message: String) :
    IllegalArgumentException(message)

internal class RuntimeQueueHeadTooLargeException(
    val headBytes: Int,
    val maximumBytes: Long,
) : IllegalStateException(
        "Queued head requires $headBytes bytes but the bounded peek permits $maximumBytes bytes",
    )

/**
 * Installation-scoped serialized owner for state and ordered records.
 *
 * Every operation, including initialization and reopen, runs on [executor]. No public SDK facade
 * references this type.
 */
internal class RuntimeQueueOwner private constructor(
    private val ownershipKey: String,
    private val limits: RuntimeQueueLimits,
    private val databaseFactory: () -> RuntimeQueueDatabase,
    private val legacyStateLoader: () -> PersistedCoreState,
    private val identifiers: CoreIdentifierGenerator,
    private val executor: ExecutorService,
    private val workerThread: Thread,
    private val leaseFactory: () -> RuntimeOwnershipLease,
    private val trustedSiteKey: String?,
    private val captureClock: RuntimeCaptureClock,
    private val readbackProvenReplayTransports: Set<V1ReplayTransport>,
    private val replayMaskingAdmission: ReplayMaskingAdmission,
    private val supportedReplayProtocolGenerations: Set<String>,
    private val startupMigrationCompleter: ((PersistedCoreState) -> PersistedCoreState)?,
    private val assertStartupCurrent: () -> Unit,
    private val startupHistoryLoader: ((PersistedCoreState) -> List<RuntimeStoredRecord>)?,
) {
    private var database: RuntimeQueueDatabase? = null
        set(value) { field = value; nativeCaptureResources?.updateDatabase(value) }
    private var lease: RuntimeOwnershipLease? = null
    private val nativeScope = NativeReplayScope(captureClock)
    private var nativeScopeReconciliation = false
    private var loaded: LoadedSnapshot? = null
        set(value) {
            if (!nativeScopeReconciliation) nativeScope.publish(value?.state?.identity, captureAuthority as? RuntimeCaptureAuthorityState.Authorized)
            field = value
        }
    private var poison: Throwable? = null
        set(value) {
            if (value != null) { nativeScope.close(); nativeCaptureEnrollment?.retainQuarantine() }
            field = value
        }
    private val lifecycleLock = Any()
    private var acceptingTasks: Boolean = true
    private val configManager =
        V1ConfigManager(
            readbackProvenReplayTransports = readbackProvenReplayTransports,
            trustedFlagSiteKey = trustedSiteKey,
            trustedFlagNamespaceDigest = trustedSiteKey?.let(RuntimeSiteNamespace::digest),
        )
    private val ownerNamespaceHash: String? = trustedSiteKey?.let(RuntimeSiteNamespace::digest)
    private var pinnedConfigSiteId: String? = null
    private var captureAuthority: RuntimeCaptureAuthorityState = RuntimeCaptureAuthorityState.Absent
        set(value) {
            nativeScope.publish(loaded?.state?.identity, value as? RuntimeCaptureAuthorityState.Authorized)
            field = value
        }
    private var authorityEpoch: Long = 0
    private var configurationGate: V2ConfigAuthorityGate? = null
    private var pendingCaptureConfiguration: V2ConfigAuthorityWitness? = null
    private var captureConfiguration: V2ConfigAuthorityWitness? = null
    private var flagConfiguration: V2ConfigAuthorityWitness? = null

    /** Bound once before standalone startup; legacy injected tests keep their existing seam. */
    internal fun bindConfigurationGate(gate: V2ConfigAuthorityGate): Future<Unit> = submit {
        check(configurationGate == null) { "Configuration gate is already bound" }
        configurationGate = gate
        captureAuthority = RuntimeCaptureAuthorityState.Absent
    }

    private fun flagConfigurationIsCurrent(): Boolean =
        configurationGate == null || flagConfiguration?.isCurrent() == true

    private fun currentFlagAuthorization() =
        configManager.flagAuthorizationForTransaction().takeIf { flagConfigurationIsCurrent() }

    private fun <T> currentFlagResult(witness: V2ConfigAuthorityWitness?, fallback: T, value: T): T {
        if (configurationGate == null) return value
        var result = fallback
        witness?.consume { if (flagConfiguration === witness) result = value }
        return result
    }

    /** A durable wall-floor violation poisons only the feature-flag authority for this owner life. */
    private var featureFlagClockPoisoned: Boolean = false

    /** Blocking adapters must never wait for a task while already running on this worker. */
    fun isCurrentThreadWorker(): Boolean = Thread.currentThread() === workerThread

    fun snapshot(): Future<RuntimeQueueSnapshot> =
        submit {
            assertUsable()
            requireLoaded().publicSnapshot
        }

    fun appendEvents(
        sessionUpdate: RuntimeEventSessionUpdate,
        drafts: List<RuntimeRecordDraft.Event>,
    ): Future<RuntimeAppendResult> {
        require(drafts.size in 1..MAX_RUNTIME_APPEND_RECORDS) {
            "Event append must contain 1..$MAX_RUNTIME_APPEND_RECORDS events"
        }
        return submit { appendOnWorker(AppendRequest.Events(sessionUpdate, drafts.toList())) }
    }

    fun appendMutations(drafts: List<RuntimeRecordDraft.Mutation>): Future<RuntimeAppendResult> {
        require(drafts.size in 1..MAX_RUNTIME_APPEND_RECORDS) {
            "Mutation append must contain 1..$MAX_RUNTIME_APPEND_RECORDS mutations"
        }
        return submit(revokeNative = true) {
            appendOnWorker(AppendRequest.Mutations(drafts.toList())).also { result ->
                if (result is RuntimeAppendResult.Accepted) invalidateAuthorizedContext()
            }
        }
    }

    fun applyLocal(change: RuntimeLocalStateChange): Future<RuntimeAppendResult> =
        submit(revokeNative = true) {
            appendOnWorker(AppendRequest.Local(change)).also { result ->
                if (result is RuntimeAppendResult.Accepted && change !is RuntimeLocalStateChange.MarkBackgrounded) {
                    invalidateAuthorizedContext()
                }
            }
        }

    /** Local flag context only, admitted against transaction-current flag/source authority. */
    internal fun applyFlagContext(change: RuntimeLocalStateChange): Future<RuntimeAppendResult> {
        require(change is RuntimeLocalStateChange.SetFlagPersonProperties ||
            change is RuntimeLocalStateChange.SetFlagGroupProperties || change is RuntimeLocalStateChange.SetFlagGroup ||
            change is RuntimeLocalStateChange.ResetGroups || change is RuntimeLocalStateChange.ResetFlagPersonProperties ||
            change is RuntimeLocalStateChange.ResetFlagGroupProperties)
        return submit(revokeNative = true) {
            assertUsable()
            val witness = flagConfiguration
            val authorization = currentFlagAuthorization()
            fun rejected() = RuntimeAppendResult.Rejected(
                RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE, requireLoaded().publicSnapshot)
            if (configurationGate == null || witness == null || authorization == null || featureFlagClockPoisoned) {
                return@submit rejected()
            }
            val result = try {
                appendOnWorker(AppendRequest.Local(change)) { transaction, before ->
                    if (featureFlagClockPoisoned || flagConfiguration !== witness || !witness.isCurrent() ||
                        currentFlagAuthorization() != authorization) false
                    else {
                        val restriction = FlagDurableStore.contextChangeRestriction(transaction, authorization,
                            before.state, captureClock.wallNowEpochMillis())
                        if (restriction == V1FlagProjectionRejection.STORAGE) featureFlagClockPoisoned = true
                        restriction == null && witness.isCurrent()
                    }
                }
            } catch (_: FlagContextWithdrawn) { rejected() }
            if (result is RuntimeAppendResult.Accepted) invalidateAuthorizedContext()
            currentFlagResult(witness, rejected(), result)
        }
    }

    /** Raw config/privacy validation and activation execute on this queue's one owner lane. */
    fun submitCaptureAuthority(
        configBody: String?,
        effectivePrivacyBody: String?,
    ): Future<RuntimeCaptureAuthorityUpdateResult> =
        submit(revokeNative = true) {
            val witness = configurationGate?.snapshotFor(configBody)
            if (configurationGate != null && witness == null) {
                return@submit terminateAuthority(RuntimeCaptureAuthorityTerminalReason.STALE, null, null, null)
            }
            pendingCaptureConfiguration = witness
            try { activateCaptureAuthorityOnWorker(configBody, effectivePrivacyBody) }
            finally { pendingCaptureConfiguration = null }
        }

    /**
     * Queued events retain their original identity/session. Delivery checks current collection
     * privacy and source authority, without requiring a new capture session for those old rows.
     */
    internal fun authorizeCurrentDelivery(
        configBody: String?,
        deviceInEuTimezone: () -> Boolean,
    ): Future<Boolean> = submit {
        assertUsable()
        val witness = configurationGate?.snapshotFor(configBody)
        if (configurationGate != null && witness == null) return@submit false
        val parsed = try { V1ConfigJson.parseConfig(configBody) } catch (_: Exception) { return@submit false }
        if (parsed.status != V1ConfigStatus.ENABLED) return@submit false
        val allowed = database().transaction { transaction ->
            val identity = requireCurrent(transaction).state.identity
            val now = captureClock.wallNowEpochMillis()
            val privacy = PrivacyStateProjector.project(PrivacyProjectionInput(
                policy = checkNotNull(parsed.privacy), features = checkNotNull(parsed.features),
                replayCapabilities = checkNotNull(parsed.replayCapabilities), identity = identity,
                deviceInEuTimezone = deviceInEuTimezone(), evaluatedAt = RuntimeWallTimestamps.rfc3339(now),
            ))
            val resolution = configManager.authorize(PrivacyStateProjector.encode(privacy), identity, now)
            resolution is V1ConfigResolution.Authorized &&
                resolution.config.configSemanticHash == parsed.configSemanticHash &&
                resolution.config.captureAuthorization.status == V1ChannelAuthorizationStatus.AUTHORIZED &&
                resolution.config.endpoints.events != null && !identity.optedOut
        }
        var current = configurationGate == null && allowed
        witness?.consume { current = allowed }
        current
    }

    /** Creates and consumes capture admission inside this one serialized command. */
    fun capture(command: RuntimeCaptureCommand): Future<RuntimeCaptureResult> {
        val copy =
            command.copy(
                properties = Collections.unmodifiableMap(LinkedHashMap(command.properties)),
            )
        return submit { captureOnWorker(copy) }
    }

    /** Fixed lifecycle transition; preserve the existing owner-before-driver publication lock order. */
    internal fun applicationBackgrounded(
        driver: dev.elu.analytics.internal.config.V2ConfigLifecycleDriver,
        occurredAt: String,
        versions: RuntimeVersions,
    ): Future<RuntimeAppendResult>? = synchronized(lifecycleLock) {
        if (!acceptingTasks || poison != null) {
            driver.onBackground()
            return@synchronized null
        }
        // The callback only submits reentrantly. It cannot wait for the queue worker or take
        // the driver lock before this owner lock; native publication takes these in this order.
        driver.onApplicationBackgrounded(occurredAt) { boundary ->
            captureApplicationBackgrounded(boundary, versions)
        }
        markBackgrounded(occurredAt)
    }

    /** One fixed lifecycle event; this is never an alternate path for customer capture. */
    internal fun captureApplicationBackgrounded(
        boundary: dev.elu.analytics.internal.config.V2ConfigApplicationBackgrounded,
        versions: RuntimeVersions,
    ): Future<RuntimeCaptureResult> {
        check(Thread.holdsLock(lifecycleLock)) { "Background boundary requires the original owner lock" }
        return submit {
            if (!boundary.claim() || configurationGate == null) {
                RuntimeCaptureResult.Rejected(RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED, requireLoaded().publicSnapshot)
            } else captureOnWorker(RuntimeCaptureCommand(
                kind = RuntimeEventKind.CAPTURE,
                name = StandaloneRuntime.APPLICATION_BACKGROUNDED_EVENT,
                occurredAt = boundary.occurredAt,
                properties = emptyMap(),
                versions = versions,
            ), boundary)
        }
    }

    fun registerSuperProperties(
        properties: Map<String, Any?>,
        occurredAt: String,
    ): Future<RuntimeAppendResult> =
        applyLocal(
            RuntimeLocalStateChange.RegisterSuperProperties(
                Collections.unmodifiableMap(LinkedHashMap(properties)),
                occurredAt,
            ),
        )

    fun unregisterSuperProperties(
        keys: List<String>,
        occurredAt: String,
    ): Future<RuntimeAppendResult> =
        applyLocal(RuntimeLocalStateChange.UnregisterSuperProperties(keys.toList(), occurredAt))

    fun markBackgrounded(occurredAt: String): Future<RuntimeAppendResult> =
        applyLocal(RuntimeLocalStateChange.MarkBackgrounded(occurredAt))

    internal fun captureAuthorityForTesting(): Future<RuntimeCaptureAuthorityState> =
        submit { captureAuthority }

    internal fun pinnedConfigSiteForTesting(): Future<String?> =
        submit { pinnedConfigSiteId }

    internal fun replaceCaptureAuthorityForTesting(authority: RuntimeCaptureAuthorityState): Future<Unit> =
        submit { captureAuthority = authority }

    /** Explicit internal-only activation of the additive database schema. */
    internal fun ensureFeatureFlagRuntime(): Future<Unit> =
        submit {
            assertUsable()
            val siteKey = trustedSiteKey ?: throw IllegalStateException("Feature flags require an exact trusted site key")
            val namespace = ownerNamespaceHash ?: throw IllegalStateException("Feature flags require a site namespace")
            database().ensureFlagSchema(FlagDurableStore.uninitializedAuthorityRow(siteKey, namespace))
        }

    /** Storage-only activation. The canonical standalone stack never invokes this seam. */
    internal fun ensurePreparedReplayStorage(): Future<Unit> = submit {
        assertUsable()
        val namespace = ownerNamespaceHash ?: error("Replay storage requires a trusted site namespace")
        val initial = ReplayStoredState(namespace).row()
        try { database().ensureReplaySchema(initial) }
        catch (uncertain: AmbiguousRuntimeCommitException) {
            reopenValidated(uncertain) ?: corrupt("Runtime core disappeared during replay migration")
            database().ensureReplaySchema(initial)
        }
        database().transaction { ReplayQueueStore.validate(it, namespace) }
        Unit
    }

    private val nativeOwnerToken = Any()
    @Volatile private var nativeAccountingActivated = false
    private var nativeAnchor: NativeReplayClockAnchor? = null
    private var nativeReceipt: NativeReplayStartReceipt? = null
    private var nativeSettlementUncertain = false
    private var nativeCaptureEnrollment: NativeReplayCaptureEnrollment? = null
    private var nativeCaptureResources: NativeReplayCaptureResources? = null

    /** The physical loop samples the very same clock as this original queue owner. */
    internal fun nativeReplayCaptureClock(): RuntimeCaptureClock = captureClock

    /** Read-only original-use binding, including logical withdrawal while physical work is pending. */
    internal fun nativeReplayCaptureMatches(use: NativeReplayCapturePhysicalUse): Boolean = synchronized(lifecycleLock) {
        use.enrollment.belongsTo(nativeOwnerToken) && nativeCaptureEnrollment === use.enrollment &&
            use.enrollment.matches(use) && !use.enrollment.isQuarantined() && !use.enrollment.physicalIsFinished()
    }

    internal fun enrollNativeReplayCapture(): Future<NativeReplayCaptureEnrollment?> = submitNative {
        assertUsable()
        if (nativeCaptureEnrollment != null || nativeReceipt != null || nativeSettlementUncertain ||
            !database().transaction { it.nativeReplaySchemaPresent() }) return@submitNative null
        val resources = NativeReplayCaptureResources(database, lease)
        val enrollment = NativeReplayCaptureEnrollment.issue(nativeOwnerToken, resources)
        synchronized(lifecycleLock) {
            if (!acceptingTasks || poison != null) return@synchronized null
            nativeCaptureResources = resources; nativeCaptureEnrollment = enrollment
            enrollment
        }
    }

    private fun requireNativeCaptureUse(use: NativeReplayCapturePhysicalUse, intake: Boolean = true) {
        check(use.enrollment.belongsTo(nativeOwnerToken) && nativeCaptureEnrollment === use.enrollment &&
            use.enrollment.matches(use) && !use.enrollment.isQuarantined() && (!intake || use.isCurrent())) {
            "Native capture use is stale"
        }
    }

    /** Only an original enrolled receipt may enter this lane after logical close. */
    private fun <T> submitNativeSettlement(enrollment: NativeReplayCaptureEnrollment, block: () -> T): Future<T> =
        synchronized(lifecycleLock) {
            check(enrollment.belongsTo(nativeOwnerToken) && nativeCaptureEnrollment === enrollment) { "Foreign native enrollment" }
            val result = object : dev.elu.analytics.internal.concurrent.SdkFuture<T>() {
                override fun cancel(mayInterruptIfRunning: Boolean) = false
            }
            executor.execute {
                try { assertWorkerThread(); result.complete(block()) }
                catch (error: Throwable) { result.completeExceptionally(error) }
            }
            result
        }

    internal fun finishNativeReplayCapture(enrollment: NativeReplayCaptureEnrollment): Future<NativeReplayCaptureFinish> = synchronized(lifecycleLock) {
        if (!enrollment.belongsTo(nativeOwnerToken) || nativeCaptureEnrollment !== enrollment)
            return@synchronized dev.elu.analytics.internal.concurrent.SdkFuture.completedFuture(NativeReplayCaptureFinish.STALE)
        if (enrollment.isQuarantined())
            return@synchronized dev.elu.analytics.internal.concurrent.SdkFuture.completedFuture(NativeReplayCaptureFinish.ACCOUNTING_PENDING)
        submitNativeSettlement(enrollment) {
            if (synchronized(lifecycleLock) { nativeCaptureEnrollment !== enrollment })
                return@submitNativeSettlement NativeReplayCaptureFinish.STALE
            if (enrollment.isQuarantined()) return@submitNativeSettlement NativeReplayCaptureFinish.ACCOUNTING_PENDING
            if (!enrollment.physicalIsFinished()) return@submitNativeSettlement NativeReplayCaptureFinish.PHYSICAL_WORK_PENDING
            if (nativeReceipt != null || nativeSettlementUncertain) return@submitNativeSettlement NativeReplayCaptureFinish.ACCOUNTING_PENDING
            try {
                assertUsable()
                flushNativeReplayDenialOnWorker()
                val state = database().transaction { nativeState(it) }
                if (state.session?.activeEpoch != null || nativeScope.pendingDenial() != null)
                    return@submitNativeSettlement NativeReplayCaptureFinish.ACCOUNTING_PENDING
                enrollment.proveAccountingFinished()
                val released = synchronized(lifecycleLock) {
                    if (nativeCaptureEnrollment !== enrollment || !enrollment.releaseIfFinished()) false
                    else { nativeCaptureEnrollment = null; nativeCaptureResources = null; true }
                }
                if (!released) return@submitNativeSettlement NativeReplayCaptureFinish.ACCOUNTING_PENDING
                // Terminal arbitration and exact slot clearing precede callbacks, outside locks.
                enrollment.notifyReleased()
                NativeReplayCaptureFinish.SETTLED
            } catch (error: Throwable) { enrollment.quarantine(); throw error }
        }
    }

    internal fun stopNativeReplayCaptureAccounting(use: NativeReplayCapturePhysicalUse): Future<NativeReplayAuthorityStop> =
        submitNativeSettlement(use.enrollment) {
            requireNativeCaptureUse(use, intake = false)
            if (!use.enrollment.physicalIsFinished()) return@submitNativeSettlement NativeReplayAuthorityStop.PHYSICAL_WORK_PENDING
            try {
                assertUsable()
                nativeReceipt?.let { stopNativeOnWorker(it, use) }
                flushNativeReplayDenialOnWorker()
                if (nativeReceipt != null || nativeSettlementUncertain) NativeReplayAuthorityStop.UNRESOLVED
                else NativeReplayAuthorityStop.SETTLED
            } catch (error: Throwable) { use.enrollment.quarantine(); throw error }
        }

    /** Optional storage initialization. No replay sampling or start happens here. */
    internal fun ensureNativeReplayAccounting(): Future<Unit> = submitNative {
        assertUsable()
        val namespace = checkNotNull(ownerNamespaceHash)
        val initial = NativeReplayAccounting.row(NativeReplaySessionState(namespace, requireLoaded().state.stream.streamId))
        try { database().ensureNativeReplaySchema(initial) }
        catch (uncertain: AmbiguousRuntimeCommitException) {
            reopenValidated(uncertain) ?: corrupt("Runtime core disappeared during native migration")
            database().ensureNativeReplaySchema(initial)
        }
        database().transaction { ReplayQueueStore.validate(it, namespace) }
        nativeAccountingActivated = true
        Unit
    }

    /** Read-only values with an original owner/source binding; not recorder permission. */
    internal fun observeNativeReplaySession(): Future<NativeReplaySessionObservation?> = submitNative { observeNativeOnWorker() }

    private fun observeNativeOnWorker(): NativeReplaySessionObservation? {
        assertUsable()
        flushNativeReplayDenialOnWorker()
        val source = captureConfiguration ?: return null
        val capture = captureAuthority as? RuntimeCaptureAuthorityState.Authorized ?: return null
        val config = nativeCurrentConfig(requireLoaded(), source, capture) ?: return null
        val key = nativeKey(requireLoaded(), capture)
        if (nativeReceipt?.key?.let { it != key } == true) return null
        var observation: NativeReplaySessionObservation? = null
        nativeTransaction {
            val current = requireCurrent(it)
            if (nativeCurrentConfig(current, source, capture) == null || nativeKey(current, capture) != key) throw ReplaySourceWithdrawn()
            val before = nativeState(it)
            if (before.session?.activeEpoch != null && !before.session.interrupted && before.session.key != key) return@nativeTransaction Unit
            val after = observeNativeState(before, key, config)
            if (after != before) it.putReplayRow(NativeReplayAccounting.row(after))
            val session = checkNotNull(after.session)
            observation = NativeReplaySessionObservation.issue(nativeOwnerToken, source, capture, session,
                session.selected(checkNotNull(config.privacy).replay.sampleRate))
            if (nativeCurrentConfig(requireCurrent(it), source, capture) == null || nativeKey(requireCurrent(it), capture) != key) throw ReplaySourceWithdrawn()
        }
        var result: NativeReplaySessionObservation? = null
        // Match dispatch lock order: lifecycle intent linearizes before source consumption.
        synchronized(lifecycleLock) {
            if (acceptingTasks && poison == null) source.consume {
                if (acceptingTasks && poison == null && captureAuthority === capture && captureConfiguration === source) result = observation
            }
        }
        return result
    }

    /** Opaque actual session/identity facts, never inferred from caller eligibility. */
    internal fun observeNativeReplayProjection(): Future<NativeReplayProjectionInput?> = submitNative {
        val observation = observeNativeOnWorker() ?: return@submitNative null
        issueNativeProjectionInput(observation)
    }

    private fun issueNativeProjectionInput(observation: NativeReplaySessionObservation): NativeReplayProjectionInput? {
        val identity = requireLoaded().state.identity
        if (nativeCurrentConfig(requireLoaded(), observation.source, observation.capture) == null ||
            nativeKey(requireLoaded(), observation.capture) != observation.session.key) return null
        val guard = nativeScope.issue(observation.source, observation.capture, observation.session) ?: return null
        val value = NativeReplayProjectionInput.issue(nativeOwnerToken, observation, identity, guard, nativeWall())
        return value.takeIf { it.isCurrent() }
    }

    /** Deliberate full-hash handoff under the original source, without adopting the gate's latest token. */
    internal fun prepareNativeReplayProjection(input: NativeReplayProjectionInput,
        projection: NativeReplayPrivacyProjection): Future<NativeReplayPreparedProjection?> = submitNative {
        assertUsable()
        flushNativeReplayDenialOnWorker()
        if (!input.belongsTo(nativeOwnerToken) || projection.input !== input || !input.isCurrent() || nativeReceipt != null) return@submitNative null
        val observation = input.observation
        val source = observation.source
        val old = observation.capture
        val current = requireLoaded()
        val config = nativeCurrentConfig(current, source, old) ?: return@submitNative null
        if (nativeKey(current, old) != observation.session.key || projection.transport !in readbackProvenReplayTransports ||
            projection.protocolGeneration !in supportedReplayProtocolGenerations ||
            projection.protocolGeneration != config.replayCapabilities?.replayProtocolGeneration ||
            projection.transport.codec != "elu-native-wireframe-v1" ||
            NativeMaskingProfile.select(checkNotNull(config.privacy).masking, dev.elu.analytics.internal.config.V1PrivacyPlatform.ANDROID).compatibility(config.privacy?.masking,
                dev.elu.analytics.internal.config.V1PrivacyPlatform.ANDROID) != NativeMaskingCompatibility.COMPATIBLE) return@submitNative null
        val privacy = try { V1ConfigJson.parseEffectivePrivacy(projection.body) } catch (_: Exception) { return@submitNative null }
        val session = observation.session
        if (!privacy.replayAllowed || !privacy.maskingValidated || !privacy.replaySessionEligible ||
            privacy.replaySampled != observation.currentSelected || privacy.replayBudgetRemainingSeconds != session.remainingWholeSeconds ||
            session.clockDenied || session.interrupted || session.activeEpoch != null || session.remainingWholeSeconds <= 0 ||
            privacy.effectiveMasking.text != NativeMaskingProfile.select(checkNotNull(config.privacy).masking, dev.elu.analytics.internal.config.V1PrivacyPlatform.ANDROID).textMasking ||
            privacy.effectiveMasking.inputs != dev.elu.analytics.internal.config.V1TextMasking.ALL ||
            privacy.effectiveMasking.images != dev.elu.analytics.internal.config.V1ImageMasking.BLOCK) return@submitNative null
        if (!input.isCurrent()) return@submitNative null
        pendingCaptureConfiguration = source
        val activation = try { activateCaptureAuthorityOnWorker(source.body, projection.body) }
            finally { pendingCaptureConfiguration = null }
        val installed = (activation as? RuntimeCaptureAuthorityUpdateResult.Activated)?.authority ?: return@submitNative null
        // Native projection cannot renew the original capture clock interval either.
        val elapsed = installed.monotonicStartedAt - old.monotonicStartedAt
        if (elapsed < 0 || elapsed >= old.monotonicBudget || !input.guard.intentIsCurrent() ||
            installed.decisionHash != privacy.effectivePolicyHash || nativeKey(requireLoaded(), installed) != observation.session.key) return@submitNative null
        val retained = installed.copy(monotonicStartedAt = old.monotonicStartedAt,
            monotonicBudget = minOf(old.monotonicBudget, elapsed + installed.monotonicBudget))
        captureAuthority = retained
        val replacement = NativeReplaySessionObservation.issue(nativeOwnerToken, source, retained, session, observation.currentSelected)
        val next = issueNativeProjectionInput(replacement) ?: return@submitNative null
        var result: NativeReplayPreparedProjection? = null
        synchronized(lifecycleLock) {
            if (acceptingTasks && poison == null && input.guard.intentIsCurrent()) source.consume {
                if (acceptingTasks && captureAuthority === retained && captureConfiguration === source) {
                    result = NativeReplayPreparedProjection.issue(nativeOwnerToken, next, projection)
                }
            }
        }
        result
    }

    /** Legacy permission-only start or an exact queue-enrolled physical start. */
    internal fun beginNativeReplayAuthority(prepared: NativeReplayPreparedProjection,
        physicalUse: NativeReplayCapturePhysicalUse? = null): Future<NativeReplayStartedProjection?> = submitNative {
        assertUsable()
        if (physicalUse != null) requireNativeCaptureUse(physicalUse)
        if (!prepared.belongsTo(nativeOwnerToken) || !prepared.isCurrent()) return@submitNative null
        val started = beginNativeOnWorker(prepared.input.observation, physicalUse, prepared.input.guard) ?: return@submitNative null
        val session = database().transaction { nativeState(it).session } ?: run {
            stopNativeOnWorker(started); return@submitNative null
        }
        val original = prepared.input.observation
        val guard = nativeScope.issue(original.source, original.capture, session, started.anchor)
        if (guard == null || !prepared.isCurrent()) { stopNativeOnWorker(started); return@submitNative null }
        NativeReplayStartedProjection.issue(started, guard)
    }

    internal fun flushNativeReplayClockDenial(): Future<Unit> = submitNative {
        assertUsable(); flushNativeReplayDenialOnWorker()
    }

    /** Includes the initial read in quarantine policy; only exact matching scope can be restricted. */
    private fun flushNativeReplayDenialOnWorker() {
        val key = nativeScope.pendingDenial() ?: return
        val previousUncertain = nativeSettlementUncertain
        nativeSettlementUncertain = true
        try {
            if (poison != null) throw IllegalStateException("Native denial storage is poisoned")
            nativeTransaction { tx ->
                val state = nativeState(tx)
                if (state.session?.key == key) {
                    val denied = state.denyClock(key)
                    if (denied != state) tx.putReplayRow(NativeReplayAccounting.row(denied))
                }
            }
            // Exact committed restriction or authoritative nonmatching session; neither samples clocks.
            nativeScope.resolved(key)
            nativeSettlementUncertain = previousUncertain
        } catch (error: Throwable) {
            nativeSettlementUncertain = true
            throw error
        }
    }

    /** One exact epoch and first-start candidate survives all transaction reconciliation. */
    internal fun beginNativeReplayAccounting(observation: NativeReplaySessionObservation): Future<NativeReplayStartReceipt?> =
        submitNative { beginNativeOnWorker(observation) }

    private fun beginNativeOnWorker(observation: NativeReplaySessionObservation, physicalUse: NativeReplayCapturePhysicalUse? = null, originalGuard: NativeReplayGuard? = null): NativeReplayStartReceipt? {
        assertUsable()
        flushNativeReplayDenialOnWorker()
        if (!observation.belongsTo(nativeOwnerToken) || nativeReceipt != null || nativeSettlementUncertain) return null
        if (physicalUse == null && nativeCaptureEnrollment != null) return null
        if (physicalUse != null) requireNativeCaptureUse(physicalUse)
        val source = observation.source
        val capture = observation.capture
        val key = observation.session.key
        val current = requireLoaded()
        val config = nativeCurrentConfig(current, source, capture) ?: return null
        if (nativeKey(current, capture) != key) return null
        val epoch = java.util.UUID.randomUUID().toString()
        var candidate: NativeReplayStartReceipt? = null
        var originalState: NativeReplaySessionState? = null
        var preparedState: NativeReplaySessionState? = null
        try {
            nativeTransaction { tx ->
                val disk = requireCurrent(tx)
                if (originalGuard?.isCurrent() == false || physicalUse?.isCurrent() == false || nativeCurrentConfig(disk, source, capture) == null || nativeKey(disk, capture) != key) throw ReplaySourceWithdrawn()
                val before = nativeState(tx)
                if (originalState == null) {
                    val original = before.session ?: return@nativeTransaction Unit
                    if (original.key != key || original.samplingHash != observation.session.samplingHash ||
                        original.originalSampleRate != observation.session.originalSampleRate || original.firstStartAt != observation.session.firstStartAt) return@nativeTransaction Unit
                    originalState = before
                    val observed = observeNativeState(before, key, config)
                    val wall = checkNotNull(observed.session).observedWallAt
                    val continuous = captureClock.elapsedRealtimeNanos()
                    if (continuous < 0) {
                        preparedState = observed.denyClock(key)
                    } else {
                        val begun = observed.begin(epoch, wall, checkNotNull(config.privacy).replay.sampleRate)
                        val session = checkNotNull(begun.session)
                        if (session.activeEpoch != epoch) preparedState = begun
                        else {
                            val allocated = begun.allocateReplayId()
                            if (ReplayQueueStore.headers(tx).any { it.replayId == allocated.replayId }) corrupt("Native replay identifier collision")
                            val first = checkNotNull(session.firstStartAt)
                            val anchor = nativeAnchor?.takeIf { it.key == key && it.firstStartAt == first }
                                ?: NativeReplayClockAnchor(key, first, continuous, session.elapsedFloorMicroseconds)
                            candidate = NativeReplayStartReceipt.issue(nativeOwnerToken, checkNotNull(ownerNamespaceHash),
                                disk.state.stream.streamId, key, first, epoch, allocated.replayId, anchor)
                            nativeReceipt = candidate
                            nativeSettlementUncertain = true
                            preparedState = allocated.state
                        }
                    }
                } else if (before != originalState) corrupt("Native start candidate changed before retry")
                val after = checkNotNull(preparedState)
                if (after != before) tx.putReplayRow(NativeReplayAccounting.row(after))
                if (originalGuard?.isCurrent() == false || physicalUse?.isCurrent() == false || nativeCurrentConfig(requireCurrent(tx), source, capture) == null || nativeKey(requireCurrent(tx), capture) != key) throw ReplaySourceWithdrawn()
            }
        } catch (error: Throwable) {
            // A proved rollback/withdrawal has no matching active epoch. Uncertain storage is
            // retained below; close cannot release the only process lease protecting it.
            if (poison == null) settleNativeCandidateAfterFailure(candidate)
            throw error
        }
        val result = candidate ?: return null
        nativeSettlementUncertain = false
        nativeAnchor = result.anchor
        var currentResult: NativeReplayStartReceipt? = null
        if (nativeCurrentConfig(requireLoaded(), source, capture) != null && nativeKey(requireLoaded(), capture) == key) {
            synchronized(lifecycleLock) {
                if (acceptingTasks && poison == null) source.consume {
                    if (acceptingTasks && poison == null && originalGuard?.isCurrent() != false && physicalUse?.isCurrent() != false && captureAuthority === capture && captureConfiguration === source) currentResult = result
                }
            }
        }
        if (currentResult == null) stopNativeOnWorker(result)
        return currentResult
    }

    /** Exact original receipt; source withdrawal does not authorize losing consumed time. */
    internal fun stopNativeReplayAccounting(receipt: NativeReplayStartReceipt): Future<Boolean> = submitNative {
        assertUsable()
        stopNativeOnWorker(receipt)
    }

    private fun stopNativeOnWorker(receipt: NativeReplayStartReceipt, physicalUse: NativeReplayCapturePhysicalUse? = null): Boolean {
        val capture = nativeCaptureEnrollment
        if (capture != null && (!capture.physicalIsFinished() || physicalUse == null || physicalUse.enrollment !== capture)) return false
        if (!receipt.belongsTo(nativeOwnerToken) || receipt !== nativeReceipt ||
            receipt.namespaceHash != ownerNamespaceHash || receipt.streamId != requireLoaded().state.stream.streamId) return false
        nativeSettlementUncertain = true
        var matched = false
        nativeTransaction { tx ->
            val before = nativeState(tx)
            val session = before.session
            // Match every original field before either clock is sampled.
            if (session?.key != receipt.key || session.firstStartAt != receipt.firstStartAt || session.activeEpoch != receipt.epoch) return@nativeTransaction Unit
            val anchor = nativeAnchor?.takeIf { it.key == receipt.key && it.firstStartAt == receipt.firstStartAt } ?: receipt.anchor
            val sample = anchor.observe(captureClock.elapsedRealtimeNanos(), session.elapsedFloorMicroseconds)
            val stopped = before.stop(receipt.key, receipt.firstStartAt, receipt.epoch,
                runCatching { nativeWall() }.getOrNull(), sample?.elapsedMicroseconds)
            matched = stopped.matched
            if (stopped.state != before) tx.putReplayRow(NativeReplayAccounting.row(stopped.state))
            nativeAnchor = sample?.anchor ?: anchor
        }
        // A committed exact stop or authoritative nonmatching current ledger proves this
        // original receipt no longer owns a durable epoch. Neither is new authority.
        nativeReceipt = null
        nativeSettlementUncertain = false
        return matched
    }

    private fun settleNativeCandidateAfterFailure(candidate: NativeReplayStartReceipt?) {
        if (candidate == null) return
        try { stopNativeOnWorker(candidate) } catch (error: Throwable) { nativeSettlementUncertain = true; throw error }
    }

    private fun nativeState(tx: RuntimeQueueTransaction): NativeReplaySessionState {
        check(tx.nativeReplaySchemaPresent()) { "Native replay accounting is not initialized" }
        val state = NativeReplayAccounting.read(tx.readReplayRow(NativeReplayAccounting.KEY)
            ?: corrupt("Missing native replay accounting"))
        val current = requireCurrent(tx)
        if (state.namespaceHash != ownerNamespaceHash || state.streamId != current.state.stream.streamId) corrupt("Native accounting scope mismatch")
        return state
    }

    private fun nativeKey(current: LoadedSnapshot, capture: RuntimeCaptureAuthorityState.Authorized): NativeReplaySessionState.Key {
        val session = checkNotNull(current.state.identity.session)
        return NativeReplaySessionState.Key(capture.configSiteId, session.id, session.startedAt)
    }

    private fun nativeCurrentConfig(current: LoadedSnapshot, source: V2ConfigAuthorityWitness,
        capture: RuntimeCaptureAuthorityState.Authorized): dev.elu.analytics.internal.config.V1ParsedConfig? {
        if (!synchronized(lifecycleLock) { acceptingTasks } || configurationGate == null || captureConfiguration !== source || captureAuthority !== capture ||
            !source.isCurrent() || captureAuthorityRejection(current) != null) return null
        val config = runCatching { V1ConfigJson.parseConfig(source.body) }.getOrNull() ?: return null
        if (config.schemaVersion != 2 || config.status != V1ConfigStatus.ENABLED || config.configSemanticHash != capture.configSemanticHash ||
            config.siteId != capture.configSiteId || config.features?.capture != true || config.features?.replay != true ||
            config.privacy?.capture?.enabled != true || config.privacy.replay.enabled != true ||
            !replaySessionIsCurrent(current.state.identity, checkNotNull(config.session), captureClock.wallNowEpochMillis())) return null
        return config
    }

    private fun nativeWall(): String = RuntimeWallTimestamps.rfc3339(captureClock.wallNowEpochMillis())

    private fun observeNativeState(before: NativeReplaySessionState, key: NativeReplaySessionState.Key,
        config: dev.elu.analytics.internal.config.V1ParsedConfig): NativeReplaySessionState {
        val session = before.session?.takeIf { it.key == key }
        var state = before
        val first = session?.firstStartAt
        var elapsed: Long? = null
        if (first != null) {
            val continuous = captureClock.elapsedRealtimeNanos()
            val anchor = nativeAnchor?.takeIf { it.key == key && it.firstStartAt == first }
                ?: NativeReplayClockAnchor(key, first, continuous, session.elapsedFloorMicroseconds)
            val sample = anchor.observe(continuous, session.elapsedFloorMicroseconds)
            if (sample == null) state = state.denyClock(key) else { nativeAnchor = sample.anchor; elapsed = sample.elapsedMicroseconds }
        }
        val policy = checkNotNull(config.privacy).replay
        return state.observe(key, policy.sampleRate, policy.maximumDurationSeconds, nativeWall(), nativeReceipt?.epoch, elapsed)
    }

    /** Unlike event transactions, this keeps the exact returned start receipt after a commit.
     * Reopen is same-owner recovery and must never mark that candidate epoch interrupted. */
    private fun <T> nativeTransaction(operation: (RuntimeQueueTransaction) -> T): T {
        var attempts = 0
        while (attempts++ < MAX_RECONCILIATION_ATTEMPTS) {
            var before: String? = null; var after: String? = null
            var value: T? = null
            try {
                return database().transaction { tx ->
                    requireCurrent(tx)
                    ReplayQueueStore.validate(tx, ownerNamespaceHash)
                    before = ReplayQueueStore.fingerprint(tx)
                    val result = operation(tx); value = result
                    after = ReplayQueueStore.fingerprint(tx)
                    result
                }
            } catch (rolledBack: ProvenNotCommittedRuntimeTransactionException) {
                if (rolledBack.cause is ReplaySourceWithdrawn || attempts == MAX_RECONCILIATION_ATTEMPTS) throw rolledBack
            } catch (uncertain: AmbiguousRuntimeCommitException) {
                try {
                    reopenValidated(uncertain, holdNativeScope = true) ?: corrupt("Runtime core disappeared during native commit")
                    val actual = database().transaction { ReplayQueueStore.fingerprint(it) }
                    when {
                        after != null && actual == after -> { @Suppress("UNCHECKED_CAST") return value as T }
                        before != null && actual == before -> if (attempts == MAX_RECONCILIATION_ATTEMPTS) throw uncertain
                        else -> poisonAndThrow(RuntimeQueueCorruptionException("Native commit matched neither exact state", uncertain))
                    }
                } finally { resumeNativeScopeAfterReconciliation() }
            }
        }
        error("Native accounting reconciliation exhausted")
    }

    private fun interruptNativeEpochOnOpen() {
        if (!database().transaction { it.nativeReplaySchemaPresent() }) return
        nativeAccountingActivated = true
        nativeTransaction { tx ->
            val state = nativeState(tx)
            val session = state.session
            if (session?.firstStartAt != null && !session.interrupted) {
                tx.putReplayRow(NativeReplayAccounting.row(state.copy(session = session.copy(interrupted = true))))
            }
        }
    }

    /** An accounting handoff cannot interrupt an in-flight commit or discard its receipt. */
    private fun <T> submitNative(block: () -> T): Future<T> = synchronized(lifecycleLock) {
        check(acceptingTasks) { "Runtime queue owner is closing" }
        val result = object : dev.elu.analytics.internal.concurrent.SdkFuture<T>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        }
        executor.execute {
            try { assertWorkerThread(); result.complete(block()) }
            catch (error: Throwable) { result.completeExceptionally(error) }
        }
        result
    }

    private var replayClockPoisoned = false
    private class ReplaySourceWithdrawn : IllegalStateException("Replay source was withdrawn")

    /** Preserve sealed bytes for unavailable config; an explicit source-current restriction purges. */
    internal fun reconcilePreparedReplay(
        effectivePrivacyBody: String?,
        retention: ReplayMaskingRetention = ReplayMaskingRetention { _, _ -> false },
    ): Future<Boolean> = submit {
        assertUsable()
        val witness = configurationGate?.snapshot() ?: return@submit false
        val body = witness.body ?: return@submit false
        val parsed = try { V1ConfigJson.parseConfig(body) } catch (_: Exception) { return@submit false }
        replayTransaction(witness, false) { tx ->
            val current = requireCurrent(tx)
            val now = captureClock.wallNowEpochMillis()
            if (!replayClockIsCurrent(tx, now)) return@replayTransaction false
            val resolution = configManager.authorize(effectivePrivacyBody, current.state.identity, now)
            val reason = (resolution as? V1ConfigResolution.Authorized)?.config?.replayAuthorization?.reason
            val restrictivePrivacy = reason in setOf(V1ChannelAuthorizationReason.IDENTITY_OPTED_OUT,
                V1ChannelAuthorizationReason.DECISION_NOT_ALLOWED, V1ChannelAuthorizationReason.REGION_POLICY_CONFLICT)
            ReplayQueueStore.reconcile(tx, parsed, checkNotNull(ownerNamespaceHash), now,
                current.state.identity.optedOut || restrictivePrivacy, readbackProvenReplayTransports, supportedReplayProtocolGenerations, retention)
        }
    }

    internal fun makeNativeReplayCaptureAdmission(permit: NativeReplayPermit,
        use: NativeReplayCapturePhysicalUse): Future<NativeReplayCaptureAdmission?> = submitNative {
        assertUsable(); requireNativeCaptureUse(use)
        val input = permit.prepared.projection.input
        if (!input.belongsTo(nativeOwnerToken) || nativeReceipt !== permit.started.receipt || !permit.isCurrent() || !input.isCurrent()) return@submitNative null
        val source = input.observation.source
        val parsed = nativeCurrentConfig(requireLoaded(), source, input.observation.capture) ?: return@submitNative null
        val masking = parsed.privacy?.masking ?: return@submitNative null
        if (NativeMaskingProfile.select(masking, dev.elu.analytics.internal.config.V1PrivacyPlatform.ANDROID).compatibility(masking, dev.elu.analytics.internal.config.V1PrivacyPlatform.ANDROID) != NativeMaskingCompatibility.COMPATIBLE) return@submitNative null
        val privacy = permit.prepared.projection.privacy.body
        val config = (configManager.authorize(privacy, input.identity, captureClock.wallNowEpochMillis()) as? V1ConfigResolution.Authorized)?.config
            ?: return@submitNative null
        if (config.configSemanticHash != parsed.configSemanticHash || config.effectivePrivacy?.effectivePolicyHash != input.observation.capture.decisionHash ||
            config.negotiatedReplayTransport != permit.prepared.projection.privacy.transport ||
            config.replayCapabilities.replayProtocolGeneration != permit.prepared.projection.privacy.protocolGeneration) return@submitNative null
        val admission = NativeReplayCaptureAdmission.issue(nativeOwnerToken, permit, use, config)
        val reconciled = replayTransaction(source, false) { tx ->
            if (!admission.isCurrent()) throw ReplaySourceWithdrawn()
            val now = captureClock.wallNowEpochMillis()
            if (!replayClockIsCurrent(tx, now)) return@replayTransaction false
            val result = ReplayQueueStore.reconcile(tx, parsed, checkNotNull(ownerNamespaceHash), now, false,
                readbackProvenReplayTransports, supportedReplayProtocolGenerations, ReplayMaskingRetention { profile, required ->
                    NativeMaskingProfile.retention(profile.copyBytes(), required.privacy?.masking,
                        dev.elu.analytics.internal.config.V1PrivacyPlatform.ANDROID) == NativeMaskingRetention.COMPATIBLE
                })
            if (!admission.isCurrent()) throw ReplaySourceWithdrawn()
            result
        }
        synchronized(lifecycleLock) {
            admission.takeIf { reconciled && acceptingTasks && poison == null && it.isCurrent() && acceptingTasks }
        }
    }

    internal fun appendNativeReplay(request: PreparedReplayRequest, admission: NativeReplayCaptureAdmission,
        use: NativeReplayCapturePhysicalUse): Future<NativeReplayAppendOutcome> = submitNative {
        assertUsable(); requireNativeCaptureUse(use)
        if (!admission.belongsTo(nativeOwnerToken) || admission.use !== use || !admission.isCurrent())
            return@submitNative NativeReplayAppendOutcome.Rejected(ReplayAppendRejection.AUTHORITY)
        try {
            var withdrawnAfterCommit = false
            val result = appendPreparedReplayOnWorker(request, ReplayMaskingProfile.parse(admission.profile.canonicalBytes),
                admission.permit.prepared.projection.privacy.body, admission) { withdrawnAfterCommit = true }
            synchronized(lifecycleLock) {
                when (result) {
                    is ReplayAppendResult.Rejected -> NativeReplayAppendOutcome.Rejected(result.reason)
                    is ReplayAppendResult.Stored -> if (withdrawnAfterCommit || !acceptingTasks || !admission.isCurrent() || !acceptingTasks)
                        NativeReplayAppendOutcome.CommittedThenWithdrawn(result) else NativeReplayAppendOutcome.Committed(result)
                }
            }
        } catch (error: Throwable) {
            if (poison != null) use.enrollment.quarantine(retaining = request)
            throw error
        }
    }

    private fun nativeAppendIsCurrent(tx: RuntimeQueueTransaction, request: PreparedReplayRequest,
        admission: NativeReplayCaptureAdmission): Boolean {
        if (!admission.belongsTo(nativeOwnerToken) || !admission.isCurrent()) return false
        requireNativeCaptureUse(admission.use)
        val receipt = admission.receipt
        if (receipt !== nativeReceipt || !receipt.belongsTo(nativeOwnerToken)) return false
        val disk = requireCurrent(tx); val session = nativeState(tx).session ?: return false
        return receipt.namespaceHash == ownerNamespaceHash && receipt.streamId == disk.state.stream.streamId &&
            session.key == receipt.key && session.firstStartAt == receipt.firstStartAt && session.activeEpoch == receipt.epoch &&
            !session.clockDenied && !session.interrupted && session.remainingMicroseconds > 0 &&
            request.transport.codec == "elu-native-wireframe-v1" &&
            !request.startedAtInstant.isLeapSecond && request.startedAtInstant.fractionalDigits.length <= 3 &&
            !request.endedAtInstant.isLeapSecond && request.endedAtInstant.fractionalDigits.length <= 3 &&
            request.replayId == receipt.replayId && request.startedAtInstant >= V1ConfigJson.parseExactTimestamp(receipt.firstStartAt) &&
            request.captureProtocolGeneration == admission.permit.prepared.projection.privacy.protocolGeneration &&
            request.effectivePolicyHash == admission.input.observation.capture.decisionHash &&
            nativeCurrentConfig(disk, admission.source, admission.input.observation.capture) != null && admission.isCurrent()
    }

    /** Admission uses current source, capture identity and an actual locally proven replay pair. */
    internal fun appendPreparedReplay(request: PreparedReplayRequest, profile: ReplayMaskingProfile,
        effectivePrivacyBody: String?): Future<ReplayAppendResult> = submit {
        assertUsable()
        if (request.transport.codec == "elu-native-wireframe-v1") return@submit ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
        appendPreparedReplayOnWorker(request, profile, effectivePrivacyBody)
    }

    private fun appendPreparedReplayOnWorker(request: PreparedReplayRequest, profile: ReplayMaskingProfile,
        effectivePrivacyBody: String?, nativeAdmission: NativeReplayCaptureAdmission? = null,
        committedThenWithdrawn: () -> Unit = {}): ReplayAppendResult {
        val denied = ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
        val witness = nativeAdmission?.source ?: configurationGate?.snapshot() ?: return denied
        val parsed = try { V1ConfigJson.parseConfig(witness.body) } catch (_: Exception) { return denied }
        val result = replayTransaction<ReplayAppendResult>(witness, denied, publishCommitted = { value, current ->
            if (current && (nativeAdmission == null || nativeAdmission.isCurrent())) value
            else if (nativeAdmission != null && value is ReplayAppendResult.Stored) { committedThenWithdrawn(); value }
            else denied
        }) { tx ->
            if (nativeAdmission != null && !nativeAppendIsCurrent(tx, request, nativeAdmission)) return@replayTransaction denied
            val current = requireCurrent(tx)
            val now = captureClock.wallNowEpochMillis()
            if (!replayClockIsCurrent(tx, now)) return@replayTransaction ReplayAppendResult.Rejected(ReplayAppendRejection.CLOCK)
            val config = (configManager.authorize(effectivePrivacyBody, current.state.identity, now) as? V1ConfigResolution.Authorized)?.config
                ?: return@replayTransaction denied
            val identity = current.state.identity
            if (captureAuthorityRejection(current) != null || config.schemaVersion != 2 || config.configSemanticHash != parsed.configSemanticHash ||
                config.replayAuthorization.status != V1ChannelAuthorizationStatus.AUTHORIZED || config.endpoints.replay == null ||
                config.negotiatedReplayTransport != request.transport ||
                config.replayCapabilities.replayProtocolGeneration != request.captureProtocolGeneration ||
                request.captureProtocolGeneration !in supportedReplayProtocolGenerations || request.policyRevision != config.privacy.revision ||
                request.effectivePolicyHash != config.effectivePrivacy?.effectivePolicyHash || request.maskingProfileHash != profile.hash || request.platformFallbackApplied != config.effectivePrivacy?.effectiveMasking?.platformFallbackApplied ||
                request.anonymousId != identity.anonymousId || request.userId != identity.userId || request.identityRevision != identity.revision ||
                request.contextRevision != identity.contextRevision || request.sessionId != identity.session?.id ||
                identity.session?.lifecycle != SessionLifecycle.ACTIVE || !replaySessionIsCurrent(identity, config.session, now) ||
                request.startedAtInstant < V1ConfigJson.parseExactTimestamp(checkNotNull(identity.session).startedAt) ||
                request.endedAtInstant > V1ExactTimestamp.fromEpochMillis(now)) {
                return@replayTransaction denied
            }
            fun profileCurrent() = if (nativeAdmission == null) replayMaskingAdmission.mayCapture(profile, config)
                else profile.copyBytes().contentEquals(nativeAdmission.profile.canonicalBytes) &&
                    nativeAdmission.profile.compatibility(config.privacy.masking,
                        dev.elu.analytics.internal.config.V1PrivacyPlatform.ANDROID) == NativeMaskingCompatibility.COMPATIBLE
            if (!profileCurrent()) return@replayTransaction denied
            val state = ReplayQueueStore.state(tx) ?: return@replayTransaction denied
            if (state.issuedAt != config.issuedAt || state.semanticHash != config.configSemanticHash) return@replayTransaction denied
            fun admissionNow(): Long? {
                if (!witness.isCurrent() || (nativeAdmission != null && !nativeAppendIsCurrent(tx, request, nativeAdmission))) return null
                val latestNow = captureClock.wallNowEpochMillis()
                if (!replayClockIsCurrent(tx, latestNow)) return null
                val latest = (configManager.authorize(effectivePrivacyBody, identity, latestNow) as? V1ConfigResolution.Authorized)?.config
                if (latest != config || captureAuthorityRejection(current) != null ||
                    !replaySessionIsCurrent(identity, config.session, latestNow) ||
                    !profileCurrent() || request.expiredAt(latestNow)) return null
                return latestNow
            }
            val appended = ReplayQueueStore.append(tx, request, profile, checkNotNull(ownerNamespaceHash), config.siteId,
                config.replayCapabilities.replayProtocolGeneration ?: return@replayTransaction denied, now,
                limits.maximumCount, minOf(limits.maximumBytes, config.limits.queueBytes.toLong()), config.limits.replayChunkBytes,
                ::admissionNow)
            if (appended is ReplayAppendResult.Stored && admissionNow() == null) throw ReplaySourceWithdrawn()
            appended
        }
        // If a failed final admission rolled back the row mutation, retain its clock denial in a
        // separate delete-free metadata transaction. This cannot authorize capture or egress.
        if (replayClockPoisoned) replayTransaction(null, Unit) { tx ->
            val state = checkNotNull(ReplayQueueStore.state(tx))
            if (!state.clockDenied) tx.putReplayRow(state.copy(clockDenied = true).row())
        }
        return result
    }

    /** Local retention maintenance can only delete; it does not need network authority. */
    internal fun expirePreparedReplay(): Future<Int> = submit {
        assertUsable()
        replayTransaction(null, 0) { tx ->
            val now = captureClock.wallNowEpochMillis()
            if (!replayClockIsCurrent(tx, now)) 0 else ReplayQueueStore.expire(tx, now)
        }
    }

    /** Inspection only; no scheduler or send authority is returned by this storage checkpoint. */
    internal fun storedPreparedReplayForTesting(): Future<List<ReplayStoredChunk>> = submit {
        assertUsable()
        database().transaction { tx ->
            requireCurrent(tx)
            ReplayQueueStore.validate(tx, ownerNamespaceHash)
            if (!tx.replaySchemaPresent()) emptyList() else ReplayQueueStore.headers(tx).map { ReplayQueueStore.read(tx, it) }
        }
    }

    private val replayDeliveryOwner = java.util.UUID.randomUUID().toString()
    private var replayDeliveryBound = false
    // Physical occupancy is independent of logical source/lifecycle authority.
    private var replayPhysicalOperation: ReplayTransportOperation? = null
    private var replayPhysicalClaim: ReplayDeliveryClaim? = null
    private var replayIoConsumed = false
    private var replayDeliveryObservedWall = 0L

    /** One internal delivery binding per existing installation owner; canonical stack never calls it. */
    internal fun openReplayDeliveryQueue(policy: ReplayDeliveryPolicy): Future<ReplayDeliveryQueue> = submit {
        assertUsable()
        check(!replayDeliveryBound) { "Replay delivery is already bound" }
        replayDeliveryBound = true
        object : ReplayDeliveryQueue {
            private fun <T> onOwner(submission: () -> Future<T>): T {
                check(!isCurrentThreadWorker()) { "Replay delivery must run off the runtime owner lane" }
                val future = submission()
                var interrupted = false
                try {
                    // A queued owner command may already have claimed or enrolled physical I/O.
                    // Observe that exact command's outcome before restoring cancellation intent.
                    while (true) {
                        try { return future.get() }
                        catch (_: InterruptedException) { interrupted = true }
                        catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
                    }
                } finally { if (interrupted) Thread.currentThread().interrupt() }
            }
            override fun claim() = onOwner { claimReplayDelivery(policy) }
            override fun nextWakeDelayMillis() = onOwner { nextReplayDeliveryWake(policy) }
            override fun dispatch(claim: ReplayDeliveryClaim, transport: ReplayDeliveryTransport) =
                onOwner { dispatchReplayDelivery(claim, policy, transport) }
            override fun commit(claim: ReplayDeliveryClaim, outcome: ReplayDeliveryOutcome) =
                onOwner { commitReplayDelivery(claim, outcome, policy) }
            override fun abandon(claim: ReplayDeliveryClaim) {
                onOwner { commitReplayDelivery(claim, ReplayDeliveryOutcome.Retry(REPLAY_UNKNOWN_ATTEMPT_DELAY_MILLIS), policy) }
            }
        }
    }

    private fun replayOwnerIsLive(): Boolean = synchronized(lifecycleLock) { acceptingTasks && poison == null }
    private fun replayElapsedMillis(): Long = (captureClock.elapsedRealtimeNanos() / 1_000_000).also {
        require(it in 0..MAX_REPLAY_SAFE_INTEGER - REPLAY_MAX_RETRY_MILLIS)
    }

    private fun resolveReplayDelivery(tx: RuntimeQueueTransaction, witness: V2ConfigAuthorityWitness,
        policy: ReplayDeliveryPolicy, reconcile: Boolean): ReplayDeliveryAuthorization? {
        if (!replayOwnerIsLive() || !witness.isCurrent()) return null
        val parsed = try { V1ConfigJson.parseConfig(witness.body) } catch (_: Exception) { return null }
        val identity = requireCurrent(tx).state.identity
        val now = captureClock.wallNowEpochMillis()
        if (!replayClockIsCurrent(tx, now)) return null
        replayDeliveryObservedWall = maxOf(replayDeliveryObservedWall, now, checkNotNull(ReplayQueueStore.state(tx)).wallFloor)
        val privacy = try { policy.privacy.current(parsed, identity, now) } catch (_: Exception) { null }
        val fresh = (configManager.authorize(privacy, identity, now) as? V1ConfigResolution.Authorized)?.config
        val restrictive = fresh?.replayAuthorization?.reason in setOf(V1ChannelAuthorizationReason.IDENTITY_OPTED_OUT,
            V1ChannelAuthorizationReason.DECISION_NOT_ALLOWED, V1ChannelAuthorizationReason.REGION_POLICY_CONFLICT)
        if (reconcile) ReplayQueueStore.reconcile(tx, parsed, checkNotNull(ownerNamespaceHash), now,
            identity.optedOut || restrictive, readbackProvenReplayTransports, supportedReplayProtocolGenerations, policy.retention)
        val allowed = configManager.authorizeSealedReplayDelivery(privacy, identity, now) ?: return null
        val state = ReplayQueueStore.state(tx) ?: return null
        if (state.poisoned || state.clockDenied || state.protocol != allowed.protocolGeneration ||
            state.issuedAt != parsed.issuedAt || state.semanticHash != parsed.configSemanticHash ||
            allowed.config.configSemanticHash != parsed.configSemanticHash ||
            allowed.protocolGeneration !in supportedReplayProtocolGenerations) return null
        val siteKey = trustedSiteKey ?: return null
        val credential = ReplayJson.digest(("elu-replay-credential-v2\u0000" + siteKey).toByteArray(Charsets.UTF_8))
        val scope = ReplayJson.digest(ReplayJson.encode("namespace" to ReplayJson.text(checkNotNull(ownerNamespaceHash)),
            "siteId" to ReplayJson.text(allowed.config.siteId), "endpoint" to ReplayJson.text(allowed.endpoint.toASCIIString())))
        return ReplayDeliveryAuthorization(allowed.endpoint, siteKey, allowed.config.siteId, allowed.transport,
            allowed.protocolGeneration, credential, scope)
    }

    private fun claimReplayDelivery(policy: ReplayDeliveryPolicy): Future<ReplayDeliveryClaim?> = submit {
        assertUsable()
        val witness = configurationGate?.snapshot() ?: return@submit null
        val claimed = replayTransaction<ReplayDeliveryClaim?>(witness, null) { tx ->
            val authority = resolveReplayDelivery(tx, witness, policy, true) ?: return@replayTransaction null
            val now = captureClock.wallNowEpochMillis()
            val claim = ReplayQueueStore.claim(tx, replayDeliveryOwner, authority, now, replayElapsedMillis()) {
                resolveReplayDelivery(tx, witness, policy, false) == authority
            }
            if (claim != null && resolveReplayDelivery(tx, witness, policy, false) != authority) throw ReplaySourceWithdrawn()
            claim
        }
        if (claimed != null && !replayPublicationIsCurrent(claimed, witness, policy)) {
            replayTransaction(null, ReplayDeliveryCommit.STALE) { tx ->
                ReplayQueueStore.commit(tx, claimed, ReplayDeliveryOutcome.Retry(REPLAY_UNKNOWN_ATTEMPT_DELAY_MILLIS), replayElapsedMillis())
            }
            persistReplayDeliveryClockDenial()
            return@submit null
        }
        synchronized(lifecycleLock) { claimed.takeIf { acceptingTasks && poison == null } }
    }

    private fun nextReplayDeliveryWake(policy: ReplayDeliveryPolicy): Future<Long?> = submit {
        assertUsable()
        val witness = configurationGate?.snapshot() ?: return@submit null
        replayTransaction<Long?>(witness, null) { tx ->
            val authority = resolveReplayDelivery(tx, witness, policy, false) ?: return@replayTransaction null
            ReplayQueueStore.nextWakeDelay(tx, replayDeliveryOwner, authority, replayElapsedMillis())
        }
    }

    private fun replayClaimIsCurrent(tx: RuntimeQueueTransaction, claim: ReplayDeliveryClaim,
        witness: V2ConfigAuthorityWitness, policy: ReplayDeliveryPolicy): Boolean {
        if (claim.owner != replayDeliveryOwner || !ReplayQueueStore.matches(tx, claim)) return false
        val authority = resolveReplayDelivery(tx, witness, policy, true) ?: return false
        if (authority != claim.authorization || !ReplayQueueStore.matches(tx, claim)) return false
        val parsed = try { V1ConfigJson.parseConfig(witness.body) } catch (_: Exception) { return false }
        return !claim.row.prepared.expiredAt(captureClock.wallNowEpochMillis()) &&
            policy.retention.mayRetain(claim.row.maskingProfile, parsed) &&
            resolveReplayDelivery(tx, witness, policy, false) == authority
    }

    private fun dispatchReplayDelivery(claim: ReplayDeliveryClaim, policy: ReplayDeliveryPolicy,
        transport: ReplayDeliveryTransport): Future<ReplayTransportOperation?> = submit {
        assertUsable()
        val witness = configurationGate?.snapshot() ?: return@submit null
        val allowed = replayTransaction(witness, false) { tx ->
            replayClaimIsCurrent(tx, claim, witness, policy) && ReplayQueueStore.enroll(tx, claim)
        }
        if (!allowed || !replayPublicationIsCurrent(claim, witness, policy)) {
            persistReplayDeliveryClockDenial()
            return@submit null
        }
        var operation: OwnerReplayOperation? = null
        // Enrollment is synchronous/nonblocking. Transport owns asynchronous network work and its
        // physical settlement. Neither lifecycle lock is held across SQLite or network I/O.
        synchronized(lifecycleLock) {
            if (acceptingTasks && poison == null && replayPhysicalOperation == null) witness.consume {
                operation = OwnerReplayOperation(transport.start(claim) { authorizeReplayIo(claim, policy) })
                replayPhysicalOperation = operation
                replayPhysicalClaim = claim
                replayIoConsumed = false
            }
        }
        operation?.let { started ->
            started.transport.settlement.whenComplete { response, failure ->
                // This internal cleanup command remains admissible after acceptingTasks=false.
                // Close awaits started.settlement, so the executor and installation lease still exist.
                try {
                    executor.execute {
                        try {
                            assertUsable()
                            if (response != null) {
                                val outcome = when (response.status) {
                                    401 -> ReplayDeliveryOutcome.Blocked(ReplayBlockKind.UNAUTHORIZED)
                                    403 -> ReplayDeliveryOutcome.Blocked(ReplayBlockKind.FORBIDDEN)
                                    else -> ReplayResponseClassifier.classify(response, claim.row.prepared,
                                        captureClock.wallNowEpochMillis(), REPLAY_UNKNOWN_ATTEMPT_DELAY_MILLIS)
                                }
                                if (outcome is ReplayDeliveryOutcome.Blocked) {
                                    replayTransaction(null, ReplayDeliveryCommit.STALE) { tx ->
                                        ReplayQueueStore.commit(tx, claim, outcome, 0)
                                    }
                                }
                            }
                            synchronized(lifecycleLock) {
                                if (replayPhysicalOperation === started) { replayPhysicalOperation = null; replayPhysicalClaim = null }
                            }
                            if (failure != null) started.settlement.completeExceptionally(failure)
                            else if (response != null) started.settlement.complete(response)
                            else started.settlement.completeExceptionally(IllegalStateException("Replay transport settled without a result"))
                        } catch (error: Throwable) {
                            // A proven permanent refusal must not become a retry because its durable
                            // write failed. Retain the exclusive owner/settlement barrier and deny all
                            // further commands. No logical cancellation can release this poisoned lease.
                            quarantineReplayFailure(error)
                        }
                    }
                } catch (error: Throwable) { quarantineReplayFailure(error) }
            }
        }
        operation
    }

    private class OwnerReplayOperation(val transport: ReplayTransportOperation) : ReplayTransportOperation {
        override val settlement = dev.elu.analytics.internal.concurrent.SdkFuture<ReplayTransportResponse>()
        override fun cancel() = transport.cancel()
    }

    private fun authorizeReplayIo(claim: ReplayDeliveryClaim, policy: ReplayDeliveryPolicy): Boolean {
        check(!isCurrentThreadWorker()) { "Replay transport I/O authorization must run on its own worker" }
        return try {
            submit {
                val witness = configurationGate?.snapshot() ?: return@submit false
                val allowed = replayTransaction(witness, false) { tx -> replayClaimIsCurrent(tx, claim, witness, policy) }
                val finalPolicy = allowed && replayPublicationIsCurrent(claim, witness, policy)
                persistReplayDeliveryClockDenial()
                var current = false
                synchronized(lifecycleLock) {
                    if (finalPolicy && acceptingTasks && poison == null && replayPhysicalClaim === claim &&
                        replayPhysicalOperation != null && !replayIoConsumed) witness.consume { current = true; replayIoConsumed = true }
                }
                current
            }.get()
        } catch (_: Exception) { false }
    }

    private fun commitReplayDelivery(claim: ReplayDeliveryClaim, outcome: ReplayDeliveryOutcome,
        policy: ReplayDeliveryPolicy): Future<ReplayDeliveryCommit> = submit {
        assertUsable()
        if (claim.owner != replayDeliveryOwner || !replayOwnerIsLive()) return@submit ReplayDeliveryCommit.STALE
        val deletes = outcome == ReplayDeliveryOutcome.Accepted || outcome == ReplayDeliveryOutcome.RejectedTooLarge
        val witness = if (deletes) configurationGate?.snapshot() ?: return@submit ReplayDeliveryCommit.STALE else null
        val committed = replayTransaction(witness, ReplayDeliveryCommit.STALE) { tx ->
            if (!replayOwnerIsLive() || (witness != null && !replayClaimIsCurrent(tx, claim, witness, policy))) return@replayTransaction ReplayDeliveryCommit.STALE
            val result = ReplayQueueStore.commit(tx, claim, outcome, replayElapsedMillis())
            if (!replayOwnerIsLive() || (witness != null && !witness.isCurrent())) throw ReplaySourceWithdrawn()
            result
        }
        val current = witness == null || replayPublicationIsCurrent(claim, witness, policy)
        persistReplayDeliveryClockDenial()
        synchronized(lifecycleLock) { if (current && acceptingTasks && poison == null) committed else ReplayDeliveryCommit.STALE }
    }

    /** Same owner-lane durable identity, sampled again after SQL before outward publication. */
    private fun replayPublicationIsCurrent(claim: ReplayDeliveryClaim, witness: V2ConfigAuthorityWitness,
        policy: ReplayDeliveryPolicy): Boolean = try {
        val now = captureClock.wallNowEpochMillis()
        val validWall = try {
            now in replayDeliveryObservedWall..MAX_REPLAY_SAFE_INTEGER &&
                V1ConfigJson.parseExactTimestamp(RuntimeWallTimestamps.rfc3339(now)).toEpochMillisFloor() == now
        } catch (_: Exception) { false }
        if (!validWall) replayClockPoisoned = true
        if (replayClockPoisoned || !replayOwnerIsLive() || !witness.isCurrent()) false
        else {
            val parsed = V1ConfigJson.parseConfig(witness.body)
            val identity = requireLoaded().state.identity
            val privacy = policy.privacy.current(parsed, identity, now)
            val allowed = configManager.authorizeSealedReplayDelivery(privacy, identity, now)
            val expected = claim.authorization
            allowed != null && allowed.config.configSemanticHash == parsed.configSemanticHash &&
                allowed.config.siteId == expected.siteId && allowed.endpoint == expected.endpoint &&
                allowed.transport == expected.transport && allowed.protocolGeneration == expected.protocolGeneration &&
                allowed.protocolGeneration in supportedReplayProtocolGenerations &&
                !claim.row.prepared.expiredAt(now) && policy.retention.mayRetain(claim.row.maskingProfile, parsed)
        }
    } catch (_: Exception) { false }

    private fun persistReplayDeliveryClockDenial() {
        if (replayClockPoisoned) replayTransaction(null, Unit) { tx ->
            val state = checkNotNull(ReplayQueueStore.state(tx))
            if (!state.clockDenied) tx.putReplayRow(state.copy(clockDenied = true).row())
        }
    }

    private fun replaySessionIsCurrent(identity: dev.elu.analytics.internal.core.IdentityState,
        configuration: dev.elu.analytics.internal.config.V1SessionConfiguration, now: Long): Boolean = try {
        val session = identity.session
        if (session == null || session.lifecycle != SessionLifecycle.ACTIVE) false
        else {
            validateStoredSession(session, identity.updatedAt)
            val timestamp = RuntimeWallTimestamps.rfc3339(now)
            RuntimeRecordCodec.compareElapsedSeconds(timestamp, session.lastActivityAt, 0) >= 0 &&
                RuntimeRecordCodec.compareElapsedSeconds(timestamp, session.lastActivityAt,
                    minOf(session.timeoutSeconds, configuration.idleTimeoutSeconds)) < 0 &&
                RuntimeRecordCodec.compareElapsedSeconds(timestamp, session.startedAt,
                    minOf(session.maximumDurationSeconds, configuration.maximumDurationSeconds)) < 0
        }
    } catch (_: Exception) { false }

    private fun replayClockIsCurrent(tx: RuntimeQueueTransaction, now: Long): Boolean {
        val state = ReplayQueueStore.state(tx) ?: return false
        val validWall = try {
            now >= 0 && now <= MAX_REPLAY_SAFE_INTEGER &&
                V1ConfigJson.parseExactTimestamp(RuntimeWallTimestamps.rfc3339(now)).toEpochMillisFloor() == now
        } catch (_: Exception) { false }
        if (state.clockDenied || !validWall || now < state.wallFloor) {
            replayClockPoisoned = true
            if (!state.clockDenied) tx.putReplayRow(state.copy(clockDenied = true).row())
        }
        return !replayClockPoisoned
    }

    /** Never holds the source lifecycle lock across SQLite. Revalidate again after commit. */
    private fun <T> replayTransaction(witness: V2ConfigAuthorityWitness?, denied: T,
        publishCommitted: (T, Boolean) -> T = { value, current -> if (current) value else denied },
        operation: (RuntimeQueueTransaction) -> T): T {
        var attempts = 0
        while (attempts++ < MAX_RECONCILIATION_ATTEMPTS) {
            if (witness?.isCurrent() == false) return denied
            if (!database().transaction { it.replaySchemaPresent() }) return denied
            var before: String? = null
            var after: String? = null
            var result: T = denied
            try {
                result = database().transaction { tx ->
                    requireCurrent(tx)
                    ReplayQueueStore.validate(tx, ownerNamespaceHash)
                    before = ReplayQueueStore.fingerprint(tx)
                    if (witness?.isCurrent() == false) throw ReplaySourceWithdrawn()
                    val value = operation(tx)
                    result = value
                    after = ReplayQueueStore.fingerprint(tx)
                    if (witness?.isCurrent() == false) throw ReplaySourceWithdrawn()
                    value
                }
                var current = witness == null
                witness?.consume { current = true }
                return publishCommitted(result, current)
            } catch (_: ReplaySourceWithdrawn) { return denied }
            catch (rolledBack: ProvenNotCommittedRuntimeTransactionException) {
                if (rolledBack.cause is ReplaySourceWithdrawn || witness?.isCurrent() == false) return denied
                if (attempts == MAX_RECONCILIATION_ATTEMPTS) throw rolledBack
            } catch (uncertain: AmbiguousRuntimeCommitException) {
                try {
                    reopenValidated(uncertain, holdNativeScope = true) ?: corrupt("Runtime core disappeared during replay commit")
                    val actual = database().transaction { ReplayQueueStore.fingerprint(it) }
                    when {
                        after != null && actual == after -> {
                            resumeNativeScopeAfterReconciliation()
                            var current = witness == null
                            witness?.consume { current = true }
                            return publishCommitted(result, current)
                        }
                        before != null && actual == before -> if (attempts == MAX_RECONCILIATION_ATTEMPTS) throw uncertain
                        else -> poisonAndThrow(RuntimeQueueCorruptionException("Replay commit matched neither exact state", uncertain))
                    }
                } finally { resumeNativeScopeAfterReconciliation() }
            }
        }
        return denied
    }

    internal fun applyFeatureFlagConfiguration(
        configBody: String?,
        wallNowEpochMillis: Long,
    ): Future<V1FlagAuthorizationResolution> =
        applyFeatureFlagConfiguration(configBody, { wallNowEpochMillis })

    /** Samples the owned clock after this operation reaches the serialized storage lane. */
    internal fun applyFeatureFlagConfiguration(
        configBody: String?,
        readWallNowEpochMillis: () -> Long,
    ): Future<V1FlagAuthorizationResolution> =
        submit {
            assertUsable()
            if (featureFlagClockPoisoned) {
                return@submit V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.STORAGE)
            }
            val witness = configurationGate?.snapshotFor(configBody)
            if (configurationGate != null && witness == null) {
                flagConfiguration = null
                return@submit V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.STALE)
            }
            flagConfiguration = null
            val wallNowEpochMillis = readWallNowEpochMillis()
            val prepared = configManager.prepareFlagConfiguration(configBody, wallNowEpochMillis)
            try {
                when (
                    val stored =
                        database().transaction { transaction ->
                            FlagDurableStore.applyConfiguration(transaction, prepared, wallNowEpochMillis)
                        }
                ) {
                    is FlagConfigStoreResult.Allowed -> {
                        var resolution: V1FlagAuthorizationResolution =
                            V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.STALE)
                        val publish = {
                            resolution = configManager.commitFlagConfiguration(prepared, stored.barrierGeneration)
                            flagConfiguration = witness
                        }
                        if (configurationGate == null) publish()
                        else if (witness?.consume(publish) != true) configManager.rejectFlagConfiguration(prepared)
                        resolution
                    }
                    is FlagConfigStoreResult.Restricted -> {
                        if (stored.reason == V1FlagProjectionRejection.STORAGE) featureFlagClockPoisoned = true
                        configManager.rejectFlagConfiguration(prepared)
                        V1FlagAuthorizationResolution.Restricted(stored.reason)
                    }
                    FlagConfigStoreResult.Terminal -> {
                        configManager.rejectFlagConfiguration(prepared)
                        V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.TERMINAL)
                    }
                }
            } catch (error: Throwable) {
                configManager.rejectFlagConfiguration(prepared)
                throw error
            }
        }

    /** Commits an active owner's monotonic lease expiry before retiring its local snapshot. */
    internal fun expireFeatureFlagAuthorization(
        expected: dev.elu.analytics.internal.config.V1FlagAuthorizationSnapshot,
        wallNowEpochMillis: Long,
    ): Future<V1FlagProjectionRejection> =
        expireFeatureFlagAuthorization(expected, { wallNowEpochMillis })

    /** Samples the owned clock after this operation reaches the serialized storage lane. */
    internal fun expireFeatureFlagAuthorization(
        expected: dev.elu.analytics.internal.config.V1FlagAuthorizationSnapshot,
        readWallNowEpochMillis: () -> Long,
    ): Future<V1FlagProjectionRejection> =
        submit {
            assertUsable()
            if (featureFlagClockPoisoned) return@submit V1FlagProjectionRejection.STORAGE
            when (
                val stored =
                    database().transaction { transaction ->
                        FlagDurableStore.expireAuthorizationLease(transaction, expected, readWallNowEpochMillis())
                    }
            ) {
                FlagLeaseExpiryStoreResult.Expired -> {
                    configManager.retireFlagAuthorization(expected, terminal = false)
                    V1FlagProjectionRejection.EXPIRED
                }
                is FlagLeaseExpiryStoreResult.Restricted -> {
                    if (stored.reason == V1FlagProjectionRejection.STORAGE) featureFlagClockPoisoned = true
                    stored.reason
                }
                FlagLeaseExpiryStoreResult.Stale -> V1FlagProjectionRejection.STALE
                FlagLeaseExpiryStoreResult.Terminal -> {
                    configManager.retireFlagAuthorization(expected, terminal = true)
                    V1FlagProjectionRejection.TERMINAL
                }
            }
        }

    internal fun beginFeatureFlagReload(
        versions: RuntimeVersions,
        requestId: String,
        replacementStoreEpoch: String,
        wallNowEpochMillis: Long,
    ): Future<FlagBeginResult> =
        beginFeatureFlagReload(versions, requestId, replacementStoreEpoch, { wallNowEpochMillis })

    /** Samples the owned clock after this operation reaches the serialized storage lane. */
    internal fun beginFeatureFlagReload(
        versions: RuntimeVersions,
        requestId: String,
        replacementStoreEpoch: String,
        readWallNowEpochMillis: () -> Long,
    ): Future<FlagBeginResult> =
        submit {
            assertUsable()
            if (featureFlagClockPoisoned) {
                return@submit FlagBeginResult.Restricted(FlagRestrictionReason.WALL_ROLLBACK, null)
            }
            val sourceWitness = flagConfiguration
            val authorization = currentFlagAuthorization()
            database().transaction { transaction ->
                val current = requireCurrent(transaction)
                if (!flagConfigurationIsCurrent()) return@transaction FlagBeginResult.Restricted(FlagRestrictionReason.STALE, null)
                FlagDurableStore.begin(
                    transaction,
                    authorization,
                    current.state,
                    versions,
                    requestId,
                    replacementStoreEpoch,
                    readWallNowEpochMillis(),
                ).also { result ->
                    if (
                        result is FlagBeginResult.Restricted &&
                        result.reason == FlagRestrictionReason.WALL_ROLLBACK
                    ) {
                        featureFlagClockPoisoned = true
                    }
                }
            }.let { currentFlagResult(sourceWitness, FlagBeginResult.Restricted(FlagRestrictionReason.STALE, null), it) }
        }

    internal fun authorizeFeatureFlagSend(
        begun: dev.elu.analytics.internal.flags.FlagBegunRequest,
        versions: RuntimeVersions,
        wallNowEpochMillis: Long,
    ): Future<FlagPreSendResult> =
        authorizeFeatureFlagSend(begun, versions, { wallNowEpochMillis })

    /** Samples the owned clock after this operation reaches the serialized storage lane. */
    internal fun authorizeFeatureFlagSend(
        begun: dev.elu.analytics.internal.flags.FlagBegunRequest,
        versions: RuntimeVersions,
        readWallNowEpochMillis: () -> Long,
    ): Future<FlagPreSendResult> =
        submit {
            assertUsable()
            if (featureFlagClockPoisoned) {
                return@submit FlagPreSendResult.Restricted(FlagRestrictionReason.WALL_ROLLBACK)
            }
            val sourceWitness = flagConfiguration
            val authorization = currentFlagAuthorization()
                ?: return@submit FlagPreSendResult.Stale
            database().transaction { transaction ->
                val current = requireCurrent(transaction)
                if (!flagConfigurationIsCurrent()) return@transaction FlagPreSendResult.Stale
                FlagDurableStore.authorizeSend(
                    transaction,
                    begun,
                    authorization,
                    current.state,
                    versions,
                    readWallNowEpochMillis(),
                ).also { result ->
                    if (
                        result is FlagPreSendResult.Restricted &&
                        result.reason == FlagRestrictionReason.WALL_ROLLBACK
                    ) {
                        featureFlagClockPoisoned = true
                    }
                }
            }.let { currentFlagResult(sourceWitness, FlagPreSendResult.Stale, it) }
        }

    /** Read-only owner-lane hint used solely for same-witness in-flight arbitration. */
    internal fun snapshotFeatureFlagReload(versions: RuntimeVersions): Future<FlagReloadWitnessSnapshot?> =
        submit {
            assertUsable()
            val sourceWitness = flagConfiguration
            val authorization = currentFlagAuthorization() ?: return@submit null
            database().transaction { transaction ->
                val current = requireCurrent(transaction)
                if (!flagConfigurationIsCurrent()) return@transaction null
                FlagReloadWitnessSnapshot(authorization, FlagDurableStore.snapshotWitness(current.state, versions))
            }.let { currentFlagResult(sourceWitness, null, it) }
        }

    internal fun commitFeatureFlagReload(
        begun: dev.elu.analytics.internal.flags.FlagBegunRequest,
        versions: RuntimeVersions,
        response: FlagResponse,
        wallNowEpochMillis: Long,
    ): Future<FlagReloadResult> =
        commitFeatureFlagReload(begun, versions, response, { wallNowEpochMillis })

    /** Samples the owned clock after this operation reaches the serialized storage lane. */
    internal fun commitFeatureFlagReload(
        begun: dev.elu.analytics.internal.flags.FlagBegunRequest,
        versions: RuntimeVersions,
        response: FlagResponse,
        readWallNowEpochMillis: () -> Long,
    ): Future<FlagReloadResult> =
        submit {
            assertUsable()
            if (featureFlagClockPoisoned) {
                return@submit FlagReloadResult.Restricted(FlagRestrictionReason.WALL_ROLLBACK)
            }
            val sourceWitness = flagConfiguration
            val authorization = currentFlagAuthorization()
                ?: return@submit FlagReloadResult.Stale
            when (
                val committed = database().transaction { transaction ->
                    val current = requireCurrent(transaction)
                    if (!flagConfigurationIsCurrent()) return@transaction FlagCommitStoreResult.Stale
                    FlagDurableStore.commit(
                        transaction,
                        begun,
                        authorization,
                        current.state,
                        versions,
                        response,
                        readWallNowEpochMillis(),
                    )
                }
            ) {
                FlagCommitStoreResult.Updated ->
                    FlagReloadResult.Updated(response.flagsRevision, begun.token.requestGeneration)
                FlagCommitStoreResult.Stale -> FlagReloadResult.Stale
                is FlagCommitStoreResult.Restricted -> {
                    if (committed.reason == V1FlagProjectionRejection.STORAGE) featureFlagClockPoisoned = true
                    FlagReloadResult.Restricted(committed.reason.toFlagRestrictionReason())
                }
                FlagCommitStoreResult.Terminal -> FlagReloadResult.Terminal
            }.let { currentFlagResult(sourceWitness, FlagReloadResult.Stale, it) }
        }

    internal fun finalizeFeatureFlagReload(
        begun: dev.elu.analytics.internal.flags.FlagBegunRequest,
        versions: RuntimeVersions,
        response: FlagResponse,
        wallNowEpochMillis: Long,
    ): Future<FlagReloadResult> =
        finalizeFeatureFlagReload(begun, versions, response, { wallNowEpochMillis })

    /** Samples the owned clock after this operation reaches the serialized storage lane. */
    internal fun finalizeFeatureFlagReload(
        begun: dev.elu.analytics.internal.flags.FlagBegunRequest,
        versions: RuntimeVersions,
        response: FlagResponse,
        readWallNowEpochMillis: () -> Long,
    ): Future<FlagReloadResult> =
        submit {
            assertUsable()
            if (featureFlagClockPoisoned) {
                return@submit FlagReloadResult.Restricted(FlagRestrictionReason.WALL_ROLLBACK)
            }
            val sourceWitness = flagConfiguration
            val authorization = currentFlagAuthorization()
                ?: return@submit FlagReloadResult.Stale
            when (
                val finalized = database().transaction { transaction ->
                    val current = requireCurrent(transaction)
                    if (!flagConfigurationIsCurrent()) return@transaction FlagFinalizeStoreResult.Stale
                    FlagDurableStore.finalizeCommit(
                        transaction,
                        begun,
                        authorization,
                        current.state,
                        versions,
                        response,
                        readWallNowEpochMillis(),
                    )
                }
            ) {
                is FlagFinalizeStoreResult.Current ->
                    FlagReloadResult.Updated(
                        response.flagsRevision,
                        begun.token.requestGeneration,
                        finalized.cacheLeaseToken,
                    )
                FlagFinalizeStoreResult.Stale -> FlagReloadResult.Stale
                is FlagFinalizeStoreResult.Restricted -> {
                    if (finalized.reason == FlagRestrictionReason.WALL_ROLLBACK) featureFlagClockPoisoned = true
                    FlagReloadResult.Restricted(finalized.reason)
                }
                FlagFinalizeStoreResult.Terminal -> FlagReloadResult.Terminal
            }.let { currentFlagResult(sourceWitness, FlagReloadResult.Stale, it) }
        }

    internal fun expireFeatureFlagCache(
        expected: dev.elu.analytics.internal.config.V1FlagAuthorizationSnapshot,
        versions: RuntimeVersions,
        token: FlagCacheLeaseToken,
        wallNowEpochMillis: Long,
    ): Future<FlagCacheExpiryStoreResult> =
        expireFeatureFlagCache(expected, versions, token, { wallNowEpochMillis })

    /** Samples the owned clock after this operation reaches the serialized storage lane. */
    internal fun expireFeatureFlagCache(
        expected: dev.elu.analytics.internal.config.V1FlagAuthorizationSnapshot,
        versions: RuntimeVersions,
        token: FlagCacheLeaseToken,
        readWallNowEpochMillis: () -> Long,
    ): Future<FlagCacheExpiryStoreResult> =
        submit {
            assertUsable()
            if (featureFlagClockPoisoned) {
                return@submit FlagCacheExpiryStoreResult.Restricted(V1FlagProjectionRejection.STORAGE)
            }
            database().transaction { transaction ->
                val current = requireCurrent(transaction)
                FlagDurableStore.expireCacheLease(
                    transaction,
                    expected,
                    current.state,
                    versions,
                    token,
                    readWallNowEpochMillis(),
                ).also { result ->
                    if (
                        result is FlagCacheExpiryStoreResult.Restricted &&
                        result.reason == V1FlagProjectionRejection.STORAGE
                    ) {
                        featureFlagClockPoisoned = true
                    }
                }
            }
        }

    internal fun readFeatureFlag(
        versions: RuntimeVersions,
        key: String,
        wallNowEpochMillis: Long,
    ): Future<FlagReadResult> =
        readFeatureFlag(versions, key, { wallNowEpochMillis })

    /** Samples the owned clock after this operation reaches the serialized storage lane. */
    internal fun readFeatureFlag(
        versions: RuntimeVersions,
        key: String,
        readWallNowEpochMillis: () -> Long,
    ): Future<FlagReadResult> =
        submit {
            assertUsable()
            if (featureFlagClockPoisoned) {
                return@submit FlagReadResult.Restricted(FlagRestrictionReason.WALL_ROLLBACK)
            }
            val sourceWitness = flagConfiguration
            val authorization = currentFlagAuthorization()
                ?: return@submit FlagReadResult.Missing
            database().transaction { transaction ->
                val current = requireCurrent(transaction)
                if (!flagConfigurationIsCurrent()) return@transaction FlagReadResult.Missing
                FlagDurableStore.read(transaction, authorization, current.state, versions, key, readWallNowEpochMillis())
                    .also { result ->
                        if (
                            result is FlagReadResult.Restricted &&
                            result.reason == FlagRestrictionReason.WALL_ROLLBACK
                        ) {
                            featureFlagClockPoisoned = true
                        }
                    }
            }.let { currentFlagResult(sourceWitness, FlagReadResult.Missing, it) }
        }

    /** Bounded FIFO read. A first record larger than [maximumBytes] fails explicitly. */
    fun peek(
        maximumCount: Int,
        maximumBytes: Long,
    ): Future<List<RuntimeQueuedRecord>> {
        require(maximumCount > 0) { "maximumCount must be positive" }
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        return submit { peekOnWorker(maximumCount, maximumBytes) }
    }

    /** Deletes only an exact ordered prefix identified by sequence, kind, and immutable ID. */
    fun acknowledge(acknowledgement: RuntimeAcknowledgement): Future<RuntimeAcknowledgementResult> {
        val copy = acknowledgement.copy(references = acknowledgement.references.toList())
        validateAcknowledgement(copy)
        return submit { acknowledgeOnWorker(copy) }
    }

    /** One-way original-owner restriction after unresolved borrowed native cleanup. No SQL or release. */
    internal fun retainNativeReplayCleanupFailure(): Future<Unit> = submitNative {
        nativeScope.close()
        nativeSettlementUncertain = true
    }

    fun closeAsync(): Future<Unit> {
        val completion = object : dev.elu.analytics.internal.concurrent.SdkFuture<Unit>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        val (capture, replay) = synchronized(lifecycleLock) {
            if (!acceptingTasks) throw IllegalStateException("Runtime queue owner is already closing")
            acceptingTasks = false; nativeScope.close()
            nativeCaptureEnrollment?.withdraw()
            nativeCaptureEnrollment to replayPhysicalOperation
        }
        val barriers = mutableListOf<dev.elu.analytics.internal.concurrent.SdkFuture<*>>()
        capture?.let { barriers += it.settlement }
        replay?.let { operation ->
            val settled = dev.elu.analytics.internal.concurrent.SdkFuture<Unit>()
            operation.settlement.whenComplete { _, _ -> settled.complete(Unit) }
            barriers += settled
        }
        // Native quarantine completes exceptionally only after physical cleanup. The HTTP
        // barrier still includes its original durable receipt; quarantine cannot skip it.
        dev.elu.analytics.internal.concurrent.SdkFuture.allOf(*barriers.toTypedArray()).whenComplete { _, _ ->
            val schedulingFailure = synchronized(lifecycleLock) {
                try {
                    executor.execute {
                        try {
                            if (capture?.isQuarantined() == true) finishQuarantinedNativeCloseOnWorker(capture)
                            else finishCloseOnWorker()
                            completion.complete(Unit)
                        } catch (error: Throwable) { completion.completeExceptionally(error) }
                    }
                    executor.shutdown()
                    null
                } catch (error: Throwable) { error }
            }
            schedulingFailure?.let { completion.completeExceptionally(it) }
        }
        runCatching { replay?.cancel() }
        return completion
    }

    private fun finishQuarantinedNativeCloseOnWorker(enrollment: NativeReplayCaptureEnrollment): Nothing {
        assertWorkerThread()
        check(enrollment === nativeCaptureEnrollment && enrollment.isQuarantined() && enrollment.physicalIsFinished())
        // Enrollment already retained only the original resource holder. Never perform SQL,
        // close the connection/lease, remove installation ownership, or report successful close.
        nativeSettlementUncertain = true
        database = null; lease = null; loaded = null
        throw IllegalStateException("Native capture accounting remains quarantined after physical shutdown")
    }

    private fun finishCloseOnWorker() {
        assertWorkerThread()
        try {
            flushNativeReplayDenialOnWorker()
            nativeReceipt?.let {
                // Unrelated queue poison cannot settle an already committed native epoch.
                // Keep its original installation occupancy until a durable stop is proved.
                nativeSettlementUncertain = true
                if (poison == null) stopNativeOnWorker(it)
            }
            if (nativeSettlementUncertain) {
                quarantineNativeResources()
                throw IllegalStateException("Native accounting settlement is unresolved")
            }
            closeResources()
        } catch (error: Throwable) {
            if (nativeSettlementUncertain) quarantineNativeResources()
            throw error
        } finally {
            if (!nativeSettlementUncertain) synchronized(OWNERSHIP_KEYS) { OWNERSHIP_KEYS.remove(ownershipKey) }
        }
    }

    private fun initialize() {
        assertWorkerThread()
        lease = leaseFactory()
        database = databaseFactory()
        val existing =
            try {
                database().transaction { transaction ->
                    loadValidated(transaction, validatePayloads = true)?.let { snapshot ->
                        normalizeLegacyOptedOutSession(transaction, snapshot)
                    }
                }
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                val reopened = reopenValidated(ambiguous)
                if (reopened == null) {
                    null
                } else {
                    database().transaction { transaction ->
                        val current = loadValidated(transaction, validatePayloads = true)
                            ?: corrupt("Runtime core disappeared during legacy normalization")
                        normalizeLegacyOptedOutSession(transaction, current)
                    }
                }
            }
        if (existing != null) {
            loaded = existing
            completePendingStartupMigration()
            interruptNativeEpochOnOpen()
            return
        }

        assertStartupCurrent()
        val imported = canonicalState(normalizeLegacyOptedOutSession(legacyStateLoader()))
        validateStateInvariants(imported.first)
        val candidate =
            LoadedSnapshot(
                state = imported.first,
                stateJson = imported.second,
                queuedCount = 0,
                queuedBytes = 0,
                headSequence = imported.first.stream.nextSequence,
            )
        repeat(MAX_RECONCILIATION_ATTEMPTS) { attempt ->
            try {
                database().transaction { transaction ->
                    val raced = loadValidated(transaction, validatePayloads = true)
                    if (raced != null) {
                        loaded = raced
                    } else {
                        assertStartupCurrent()
                        transaction.insertCore(candidate.storedCore())
                        assertStartupCurrent()
                        loaded = candidate
                    }
                }
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                val reopened = reopenValidated(ambiguous)
                if (reopened == null) {
                    if (attempt == MAX_RECONCILIATION_ATTEMPTS - 1) throw ambiguous
                    return@repeat
                }
                loaded = reopened
            }
            // Completion has its own bounded commit reconciliation; the initial insertion
            // loop must not retry a completion/source failure as though insertion were uncertain.
            completePendingStartupMigration()
            assertStartupCurrent()
            check(!Thread.currentThread().isInterrupted) { "Runtime startup was interrupted" }
            return
        }
    }

    /** A pending aggregate cannot escape initialization or be mistaken for fresh state. */
    private fun completePendingStartupMigration() {
        if (loaded?.state?.startupMigration == null) return
        val complete = startupMigrationCompleter
            ?: corrupt("Startup migration is pending without its source adapter")
        repeat(MAX_RECONCILIATION_ATTEMPTS) { attempt ->
            try {
                val after = database().transaction { transaction ->
                    val before = loadValidated(transaction, validatePayloads = true)
                        ?: corrupt("Runtime core disappeared during startup migration")
                    val checkpoint = before.state.startupMigration ?: return@transaction before
                    if (before.queuedCount != 0 || before.queuedBytes != 0L) {
                        corrupt("Pending startup migration contains queued history")
                    }
                    check(!Thread.currentThread().isInterrupted) { "Startup migration was interrupted" }
                    assertStartupCurrent()
                    // Bounded app-private source observation and conversion only. No network,
                    // source writes or deletes. All imported rows and the completion ledger commit together.
                    val completed = complete(before.state)
                    val marker = completed.identity.migration
                        ?: corrupt("Startup migration completion lacks its marker")
                    val ledger = completed.startupHistory
                    val importedCount = ledger?.records?.size ?: 0
                    if (ledger != null && (ledger.sourceFingerprint != checkpoint.sourceFingerprint ||
                            ledger.sourceSchema != checkpoint.sourceSchema || importedCount !in 1..1000)) {
                        corrupt("Startup history ledger is not bound to its captured source")
                    }
                    val revision = if (ledger == null) before.state.identity.revision else importedCount.toLong()
                    val contextRevision = if (ledger == null) before.state.identity.contextRevision else importedCount.toLong()
                    if (marker.sourceSchema != checkpoint.sourceSchema || completed != before.state.copy(
                            startupMigration = null, startupHistory = ledger,
                            stream = before.state.stream.copy(nextSequence = importedCount.toLong()),
                            identity = before.state.identity.copy(migration = marker,
                                revision = revision, contextRevision = contextRevision),
                        )) corrupt("Startup migration completion changed captured state")
                    val canonical = canonicalState(completed)
                    validateStateInvariants(canonical.first)
                    val originals = if (ledger == null) emptyList() else {
                        val loader = startupHistoryLoader ?: corrupt("Startup history is pending without its record adapter")
                        loader(canonical.first)
                    }
                    if (originals.size != importedCount || originals.size > limits.maximumCount) {
                        corrupt("Startup history count does not match its bounded ledger")
                    }
                    var bytes = 0L
                    val records = originals.mapIndexed { index, row ->
                        if (row.sequence != index.toLong() || row.internalPayload.size > MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES) {
                            corrupt("Startup history row exceeds its bounded initial sequence")
                        }
                        val copied = row.copy(internalPayload = row.internalPayload.copyOf())
                        validateStoredRecord(copied, canonical.first)
                        bytes = Math.addExact(bytes, copied.accountedBytes.toLong())
                        if (bytes > limits.maximumBytes) corrupt("Startup history exceeds the bounded destination queue")
                        copied
                    }
                    records.forEach { transaction.insertRecord(it) }
                    val next = before.copy(state = canonical.first, stateJson = canonical.second,
                        queuedCount = records.size, queuedBytes = bytes,
                        headSequence = if (records.isEmpty()) canonical.first.stream.nextSequence else 0L)
                    transaction.updateCore(next.storedCore())
                    assertStartupCurrent()
                    check(!Thread.currentThread().isInterrupted) { "Startup migration was interrupted" }
                    next
                }
                // If cancellation raced the physical commit, startup still refuses publication.
                // A later owner may use the committed completed destination without source lookup.
                assertStartupCurrent()
                check(!Thread.currentThread().isInterrupted) { "Startup migration was interrupted" }
                loaded = after
                return
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                val reopened = reopenValidated(ambiguous)
                    ?: corrupt("Runtime core disappeared after ambiguous startup completion")
                loaded = reopened
                if (reopened.state.startupMigration == null) {
                    assertStartupCurrent()
                    check(!Thread.currentThread().isInterrupted) { "Startup migration was interrupted" }
                    return
                }
                if (attempt == MAX_RECONCILIATION_ATTEMPTS - 1) throw ambiguous
            }
        }
    }

    private fun normalizeLegacyOptedOutSession(
        transaction: RuntimeQueueTransaction,
        snapshot: LoadedSnapshot,
    ): LoadedSnapshot {
        if (!snapshot.state.identity.optedOut || snapshot.state.identity.session == null) return snapshot
        val normalizedState = normalizeLegacyOptedOutSession(snapshot.state)
        val canonical = canonicalState(normalizedState)
        val normalized = snapshot.copy(state = canonical.first, stateJson = canonical.second)
        transaction.updateCore(normalized.storedCore())
        return normalized
    }

    private fun normalizeLegacyOptedOutSession(state: PersistedCoreState): PersistedCoreState =
        if (state.identity.optedOut && state.identity.session != null) {
            state.copy(identity = state.identity.copy(session = null))
        } else {
            state
        }

    private fun activateCaptureAuthorityOnWorker(
        configBody: String?,
        effectivePrivacyBody: String?,
    ): RuntimeCaptureAuthorityUpdateResult {
        assertUsable()
        requireCaptureRuntime()
        val nowEpochMillis = captureClock.wallNowEpochMillis()
        val parsedBoundary =
            try {
                V1ConfigJson.parseConfigBoundary(configBody)
            } catch (_: V1MalformedConfigException) {
                null
            } catch (_: V1UnsupportedConfigSchemaException) {
                null
            }
        return when (val update = configManager.install(configBody, nowEpochMillis)) {
            is V1ConfigUpdateResult.Enabled -> {
                val previousAuthority = captureAuthority
                // install() has accepted a new executable candidate. Revoke the old authority
                // before the first SQLite read or clock sample that can fail. Only a complete
                // activation below may replace this non-executable latch.
                captureAuthority =
                    RuntimeCaptureAuthorityState.Pending(
                        trustedConfigBoundary =
                            parsedBoundary?.let { boundary ->
                                boundary.issuedAtInstant to boundary.configSemanticHash
                            },
                    )
                activateEnabledCaptureAuthority(
                    effectivePrivacyBody = effectivePrivacyBody,
                    parsedBoundary = parsedBoundary,
                    previousAuthority = previousAuthority,
                )
            }
            is V1ConfigUpdateResult.Inactive ->
                terminateAuthority(
                    reason =
                        if (update.status == V1ConfigStatus.REVOKED) {
                            RuntimeCaptureAuthorityTerminalReason.REVOKED
                        } else {
                            RuntimeCaptureAuthorityTerminalReason.DISABLED
                        },
                    boundary = parsedBoundary,
                )
            is V1ConfigUpdateResult.Rejected ->
                terminateAuthority(
                    reason = update.reason.toTerminalReason(),
                    boundary = parsedBoundary,
                )
        }
    }

    private fun activateEnabledCaptureAuthority(
        effectivePrivacyBody: String?,
        parsedBoundary: V1ParsedConfigBoundary?,
        previousAuthority: RuntimeCaptureAuthorityState,
    ): RuntimeCaptureAuthorityUpdateResult {
        // This origin is intentionally adjacent to the authoritative wall sample. The earlier
        // wall read was only for config installation ordering; it grants no lease time.
        val monotonicStartedAt = captureClock.elapsedRealtimeNanos()
        val authoritativeWallMillis = captureClock.wallNowEpochMillis()
        val exactNow = V1ExactTimestamp.fromEpochMillis(authoritativeWallMillis)
        val stage =
            database().transaction { transaction ->
            val current = requireCurrent(transaction)
            val resolution = configManager.authorize(effectivePrivacyBody, current.state.identity, authoritativeWallMillis)
            if (resolution is V1ConfigResolution.Rejected) {
                return@transaction CaptureAuthorityActivationStage.Terminate(
                    reason = resolution.reason.toTerminalReason(),
                    boundary = parsedBoundary,
                    policySourceHash = null,
                    contextRevision = current.state.identity.contextRevision,
                    pinnedSiteId = null,
                )
            }
            val config = (resolution as V1ConfigResolution.Authorized).config
            val effectivePrivacy = config.effectivePrivacy
            val pinned = pinnedConfigSiteId
            if (pinned != null && pinned != config.siteId) {
                return@transaction CaptureAuthorityActivationStage.Terminate(
                    reason = RuntimeCaptureAuthorityTerminalReason.SITE_CHANGED,
                    boundary = parsedBoundary,
                    policySourceHash = config.policySourceHash,
                    contextRevision = current.state.identity.contextRevision,
                    pinnedSiteId = null,
                )
            }

            val privacyTerminalReason =
                when (config.captureAuthorization.status) {
                    V1ChannelAuthorizationStatus.RESTRICTED -> RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED
                    V1ChannelAuthorizationStatus.INVALID ->
                        if (
                            config.captureAuthorization.reason == V1ChannelAuthorizationReason.CONTEXT_REVISION_MISMATCH &&
                            effectivePrivacy != null &&
                            effectivePrivacy.contextRevision < current.state.identity.contextRevision
                        ) {
                            RuntimeCaptureAuthorityTerminalReason.STALE
                        } else {
                            RuntimeCaptureAuthorityTerminalReason.MALFORMED
                        }
                    V1ChannelAuthorizationStatus.AUTHORIZED ->
                        if (effectivePrivacy == null || config.endpoints.events == null || current.state.identity.optedOut) {
                            RuntimeCaptureAuthorityTerminalReason.MALFORMED
                        } else {
                            null
                        }
                }
            if (privacyTerminalReason != null) {
                return@transaction CaptureAuthorityActivationStage.Terminate(
                    reason = privacyTerminalReason,
                    boundary = parsedBoundary,
                    policySourceHash = config.policySourceHash,
                    contextRevision =
                        if (privacyTerminalReason == RuntimeCaptureAuthorityTerminalReason.STALE) {
                            effectivePrivacy?.contextRevision
                        } else {
                            current.state.identity.contextRevision
                        },
                    // The config itself is fully validated. Publish its owner-lifetime site pin
                    // only after the read transaction and post-sample both complete.
                    pinnedSiteId = config.siteId,
                )
            }
            checkNotNull(effectivePrivacy)

            val previousTerminal = previousAuthority as? RuntimeCaptureAuthorityState.Terminal
            if (
                previousTerminal != null &&
                previousTerminal.reason == RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED &&
                previousTerminal.trustedConfigBoundary?.second == config.configSemanticHash &&
                (previousTerminal.contextRevision ?: Long.MAX_VALUE) >= effectivePrivacy.contextRevision
            ) {
                // At one immutable config and one decision witness, restriction dominates. A
                // loosening needs either a newer config or a higher transaction-current context.
                return@transaction CaptureAuthorityActivationStage.RestoreTerminal(previousTerminal, config.siteId)
            }

            if (exactNow >= config.expiresAtInstant) {
                return@transaction CaptureAuthorityActivationStage.Terminate(
                    RuntimeCaptureAuthorityTerminalReason.EXPIRED,
                    parsedBoundary,
                    config.policySourceHash,
                    effectivePrivacy.contextRevision,
                    config.siteId,
                )
            }
            val durableFloor = durableWallFloor(transaction, current)
            val wallRemaining = config.expiresAtInstant.elapsedNanosecondsFloorSince(exactNow)
            val declaredLifetime = config.expiresAtInstant.elapsedNanosecondsFloorSince(config.issuedAtInstant)
            val durableRemaining = config.expiresAtInstant.elapsedNanosecondsFloorSince(durableFloor)
            if (wallRemaining == null || declaredLifetime == null || durableRemaining == null) {
                return@transaction CaptureAuthorityActivationStage.Terminate(
                    RuntimeCaptureAuthorityTerminalReason.MALFORMED,
                    parsedBoundary,
                    config.policySourceHash,
                    effectivePrivacy.contextRevision,
                    config.siteId,
                )
            }
            val monotonicBudget = maxOf(0L, minOf(wallRemaining, declaredLifetime, durableRemaining))
            if (monotonicBudget == 0L) {
                return@transaction CaptureAuthorityActivationStage.Terminate(
                    RuntimeCaptureAuthorityTerminalReason.EXPIRED,
                    parsedBoundary,
                    config.policySourceHash,
                    effectivePrivacy.contextRevision,
                    config.siteId,
                )
            }

            val authority =
                RuntimeCaptureAuthorityState.Authorized(
                    ownerEpoch = 0L,
                    configIssuedAt = config.issuedAtInstant,
                    configExpiresAt = config.expiresAtInstant,
                    configSemanticHash = config.configSemanticHash,
                    policySourceHash = config.policySourceHash,
                    decisionHash = effectivePrivacy.effectivePolicyHash,
                    ownerNamespaceHash = checkNotNull(ownerNamespaceHash),
                    configSiteId = config.siteId,
                    streamId = current.state.stream.streamId,
                    identityRevision = current.state.identity.revision,
                    contextRevision = current.state.identity.contextRevision,
                    identityOptedOut = current.state.identity.optedOut,
                    monotonicStartedAt = monotonicStartedAt,
                    monotonicBudget = monotonicBudget,
                    idleTimeoutSeconds = config.session.idleTimeoutSeconds,
                    maximumDurationSeconds = config.session.maximumDurationSeconds,
                )
            CaptureAuthorityActivationStage.Activate(authority, config.siteId)
        }

        // A read-only SQLite transaction can still complete ambiguously. Nothing above mutates
        // the published authority or site pin; an exception therefore leaves Pending installed.
        val monotonicCompletedAt = captureClock.elapsedRealtimeNanos()
        return when (stage) {
            is CaptureAuthorityActivationStage.Activate -> {
                if (leaseExpired(stage.authority.monotonicStartedAt, monotonicCompletedAt, stage.authority.monotonicBudget)) {
                    pinnedConfigSiteId = stage.pinnedSiteId
                    terminateAuthority(
                        RuntimeCaptureAuthorityTerminalReason.EXPIRED,
                        parsedBoundary,
                        stage.authority.policySourceHash,
                        stage.authority.contextRevision,
                    )
                } else {
                    val authority = stage.authority.copy(ownerEpoch = nextAuthorityEpoch())
                    pinnedConfigSiteId = stage.pinnedSiteId
                    var published = false
                    val publish = {
                        captureConfiguration = pendingCaptureConfiguration
                        captureAuthority = authority
                        published = true
                    }
                    if (configurationGate == null) publish()
                    else pendingCaptureConfiguration?.consume(publish)
                    if (published) RuntimeCaptureAuthorityUpdateResult.Activated(authority)
                    else terminateAuthority(RuntimeCaptureAuthorityTerminalReason.STALE, null, null, null)
                }
            }
            is CaptureAuthorityActivationStage.Terminate -> {
                stage.pinnedSiteId?.let { pinnedConfigSiteId = it }
                terminateAuthority(
                    stage.reason,
                    stage.boundary,
                    stage.policySourceHash,
                    stage.contextRevision,
                )
            }
            is CaptureAuthorityActivationStage.RestoreTerminal -> {
                pinnedConfigSiteId = stage.pinnedSiteId
                captureAuthority = stage.authority
                RuntimeCaptureAuthorityUpdateResult.Terminated(stage.authority)
            }
        }
    }

    private fun durableWallFloor(
        transaction: RuntimeQueueTransaction,
        current: LoadedSnapshot,
    ): V1ExactTimestamp {
        val timestamps = ArrayList<V1ExactTimestamp>()
        timestamps += V1ConfigJson.parseExactTimestamp(current.state.identity.updatedAt)
        current.state.identity.session?.let { session ->
            timestamps += V1ConfigJson.parseExactTimestamp(session.startedAt)
            timestamps += V1ConfigJson.parseExactTimestamp(session.lastActivityAt)
            session.backgroundedAt?.let { timestamps += V1ConfigJson.parseExactTimestamp(it) }
        }
        if (current.queuedCount > 0) {
            val tailSequence = Math.subtractExact(current.state.stream.nextSequence, 1L)
            val tail = transaction.readRecord(tailSequence) ?: corrupt("Queue tail is missing")
            val queued = validateStoredRecord(tail, current.state)
            val occurredAt =
                when (queued) {
                    is RuntimeQueuedRecord.Event -> queued.record.occurredAt
                    is RuntimeQueuedRecord.Mutation -> queued.envelope.mutation.occurredAt
                }
            timestamps += V1ConfigJson.parseExactTimestamp(occurredAt)
        }
        return timestamps.maxOrNull() ?: V1ConfigJson.parseExactTimestamp(current.state.identity.updatedAt)
    }

    private fun captureOnWorker(command: RuntimeCaptureCommand,
        backgroundBoundary: dev.elu.analytics.internal.config.V2ConfigApplicationBackgrounded? = null,
    ): RuntimeCaptureResult {
        assertUsable()
        requireCaptureRuntime()
        if (!isValidCaptureCommand(command)) {
            return RuntimeCaptureResult.Rejected(RuntimeCaptureRejection.EVENT_INVALID, requireLoaded().publicSnapshot)
        }
        val captureProperties =
            try {
                JsonValues.objectValue(command.properties, "capture.properties").also { normalized ->
                    requireCaptureUnicode(normalized, "capture.properties")
                }
            } catch (_: IllegalArgumentException) {
                return RuntimeCaptureResult.Rejected(RuntimeCaptureRejection.EVENT_INVALID, requireLoaded().publicSnapshot)
            }

        var provenNotCommittedRetryUsed = false
        while (true) {
            var prepared: PreparedAppend? = null
            try {
                val outcome =
                    database().transaction { transaction ->
                        val before = requireCurrent(transaction)
                        validateAppendBoundaries(transaction, before)
                        captureAuthorityRejection(before, backgroundBoundary)?.let { rejection ->
                            return@transaction CaptureCommit(
                                RuntimeCaptureResult.Rejected(rejection, before.publicSnapshot),
                                published = null,
                            )
                        }
                        val authority = captureAuthority as RuntimeCaptureAuthorityState.Authorized
                        val session = planCaptureSession(before.state, command.occurredAt, authority)
                        val mergedProperties =
                            LinkedHashMap(before.state.identity.superProperties).apply {
                                putAll(captureProperties)
                            }
                        val draft =
                            RuntimeRecordDraft.Event(
                                kind = command.kind,
                                name = command.name,
                                occurredAt = command.occurredAt,
                                expectedSessionId = session.id,
                                properties = mergedProperties,
                                versions = command.versions,
                            )
                        val created =
                            prepareAppend(
                                before,
                                AppendRequest.Events(
                                    RuntimeEventSessionUpdate.Replace(before.state.identity.session?.id, session),
                                    listOf(draft),
                                ),
                                ReplayQueueStore.state(transaction),
                            )
                        if (created.rejection != null) {
                            return@transaction CaptureCommit(
                                RuntimeCaptureResult.Rejected(RuntimeCaptureRejection.QUEUE_LIMIT, before.publicSnapshot),
                                published = null,
                            )
                        }
                        prepared = created
                        // This is the final check after SQLite has begun its transaction and
                        // immediately before the first queue/core write.
                        captureAuthorityRejection(before, backgroundBoundary)?.let { rejection ->
                            return@transaction CaptureCommit(
                                RuntimeCaptureResult.Rejected(rejection, before.publicSnapshot),
                                published = null,
                            )
                        }
                        commitPreparedAppend(transaction, created)
                        val record = created.publicRecords.single() as RuntimeQueuedRecord.Event
                        CaptureCommit(
                            RuntimeCaptureResult.Accepted(record, created.after.publicSnapshot),
                            published = created.after,
                        )
                    }
                outcome.published?.let { loaded = it }
                return outcome.result
            } catch (invalid: IllegalArgumentException) {
                return RuntimeCaptureResult.Rejected(RuntimeCaptureRejection.EVENT_INVALID, requireLoaded().publicSnapshot)
            } catch (proven: ProvenNotCommittedRuntimeTransactionException) {
                if (provenNotCommittedRetryUsed) throw proven
                provenNotCommittedRetryUsed = true
                // The next loop iteration rebuilds admission and the complete candidate against
                // transaction-current state. No prepared record or session plan is reused.
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                val candidate = prepared ?: throw ambiguous
                val reopened = reopenValidated(ambiguous)
                    ?: poisonAndThrow(RuntimeQueueCorruptionException("Runtime core disappeared after an ambiguous capture", ambiguous))
                when {
                    viewsEqual(reopened, candidate.after) && recordsMatch(candidate.records) -> {
                        loaded = reopened
                        val record = candidate.publicRecords.single() as RuntimeQueuedRecord.Event
                        return RuntimeCaptureResult.Accepted(record, reopened.publicSnapshot)
                    }
                    viewsEqual(reopened, candidate.before) && recordsAbsent(candidate.records) && !provenNotCommittedRetryUsed -> {
                        loaded = reopened
                        provenNotCommittedRetryUsed = true
                        // The loop rebuilds admission and the session/event candidate, including
                        // fresh wall/monotonic and identity/context checks.
                    }
                    else -> throw ambiguous
                }
            }
        }
    }

    private fun captureAuthorityRejection(snapshot: LoadedSnapshot,
        backgroundBoundary: dev.elu.analytics.internal.config.V2ConfigApplicationBackgrounded? = null,
    ): RuntimeCaptureRejection? {
        val identity = snapshot.state.identity
        if (identity.optedOut) return RuntimeCaptureRejection.OPTED_OUT
        val authority = captureAuthority
        if (authority === RuntimeCaptureAuthorityState.Absent) return RuntimeCaptureRejection.AUTHORITY_ABSENT
        if (authority is RuntimeCaptureAuthorityState.Pending) return RuntimeCaptureRejection.AUTHORITY_PENDING
        if (authority is RuntimeCaptureAuthorityState.Terminal) {
            return if (authority.reason == RuntimeCaptureAuthorityTerminalReason.STALE) {
                RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED
            } else {
                RuntimeCaptureRejection.AUTHORITY_TERMINAL
            }
        }
        authority as RuntimeCaptureAuthorityState.Authorized
        if (configurationGate != null && (if (backgroundBoundary == null) captureConfiguration?.isCurrent() != true
            else !backgroundBoundary.authorizes(captureConfiguration))) {
            return RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED
        }
        if (
            authority.ownerNamespaceHash != ownerNamespaceHash ||
            authority.configSiteId != pinnedConfigSiteId ||
            authority.streamId != snapshot.state.stream.streamId ||
            authority.identityRevision != identity.revision ||
            authority.contextRevision != identity.contextRevision ||
            authority.identityOptedOut != identity.optedOut
        ) {
            return RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED
        }
        val wallNow = V1ExactTimestamp.fromEpochMillis(captureClock.wallNowEpochMillis())
        if (
            wallNow >= authority.configExpiresAt ||
            leaseExpired(
                authority.monotonicStartedAt,
                captureClock.elapsedRealtimeNanos(),
                authority.monotonicBudget,
            )
        ) {
            terminateAuthority(
                RuntimeCaptureAuthorityTerminalReason.EXPIRED,
                boundary = null,
                policySourceHash = authority.policySourceHash,
                contextRevision = authority.contextRevision,
            )
            return RuntimeCaptureRejection.AUTHORITY_EXPIRED
        }
        return null
    }

    /** Budgets are bounded below Long.MAX_VALUE; negative deltas therefore fail closed. */
    private fun leaseExpired(
        startedAt: Long,
        completedAt: Long,
        budget: Long,
    ): Boolean {
        val elapsed = completedAt - startedAt
        return elapsed < 0L || elapsed >= budget
    }

    private fun planCaptureSession(
        state: PersistedCoreState,
        occurredAt: String,
        authority: RuntimeCaptureAuthorityState.Authorized,
    ): SessionState {
        requireTimestampNotBefore(occurredAt, state.identity.updatedAt, "Capture occurredAt")
        val current = state.identity.session
        if (current == null) return newCaptureSession(occurredAt, authority.idleTimeoutSeconds, null)

        validateStoredSession(current, state.identity.updatedAt)
        requireTimestampNotBefore(occurredAt, current.lastActivityAt, "Capture occurredAt")
        val effectiveTimeout = minOf(current.timeoutSeconds, authority.idleTimeoutSeconds)
        val idleExpired = RuntimeRecordCodec.compareElapsedSeconds(occurredAt, current.lastActivityAt, effectiveTimeout) >= 0
        val maximumExpired =
            RuntimeRecordCodec.compareElapsedSeconds(
                occurredAt,
                current.startedAt,
                authority.maximumDurationSeconds,
            ) >= 0
        if (idleExpired || maximumExpired) {
            return newCaptureSession(occurredAt, authority.idleTimeoutSeconds, current.id)
        }
        return current.copy(
            lastActivityAt = occurredAt,
            timeoutSeconds = effectiveTimeout,
            lifecycle = SessionLifecycle.ACTIVE,
            backgroundedAt = null,
        )
    }

    private fun newCaptureSession(
        occurredAt: String,
        timeoutSeconds: Int,
        excluding: String?,
    ): SessionState {
        val id = nextSessionId(excluding)
        return SessionState(
            id = id,
            startedAt = occurredAt,
            lastActivityAt = occurredAt,
            timeoutSeconds = timeoutSeconds,
            lifecycle = SessionLifecycle.ACTIVE,
            backgroundedAt = null,
        )
    }

    private fun nextSessionId(excluding: String?): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = identifiers.next("session_")
            val length = candidate.codePointCount(0, candidate.length)
            if (length in 1..256 && candidate != excluding) return candidate
        }
        throw IllegalStateException("Identifier generator could not create a session ID")
    }

    private fun isValidCaptureCommand(command: RuntimeCaptureCommand): Boolean {
        // Diagnostic events are runtime-internal and never admitted through a capture command.
        if (command.kind == RuntimeEventKind.DIAGNOSTIC) return false
        val nameLength = command.name.codePointCount(0, command.name.length)
        if (nameLength !in 1..512) return false
        if (!hasWellFormedUnicode(command.name)) return false
        return try {
            V1ConfigJson.parseExactTimestamp(command.occurredAt)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun requireCaptureUnicode(value: Any?, path: String) {
        when (value) {
            null, is Boolean, is Number -> Unit
            is String -> require(hasWellFormedUnicode(value)) { "$path contains an unpaired Unicode surrogate" }
            is Map<*, *> ->
                value.forEach { (key, child) ->
                    require(key is String && hasWellFormedUnicode(key)) {
                        "$path contains an invalid object key"
                    }
                    requireCaptureUnicode(child, "$path.$key")
                }
            is List<*> -> value.forEachIndexed { index, child -> requireCaptureUnicode(child, "$path[$index]") }
            else -> throw IllegalArgumentException("$path contains a non-JSON value")
        }
    }

    private fun hasWellFormedUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(character) -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun recordsAbsent(records: List<RuntimeStoredRecord>): Boolean =
        database().transaction { transaction -> records.all { transaction.readRecord(it.sequence) == null } }

    private fun requireCaptureRuntime() {
        check(trustedSiteKey != null && ownerNamespaceHash != null) {
            "Capture authority requires a trusted constructor site key"
        }
    }

    private fun terminateAuthority(
        reason: RuntimeCaptureAuthorityTerminalReason,
        boundary: V1ParsedConfigBoundary?,
        policySourceHash: String? = null,
        contextRevision: Long? = null,
    ): RuntimeCaptureAuthorityUpdateResult.Terminated {
        val previous = captureAuthority
        val previousBoundary =
            (previous as? RuntimeCaptureAuthorityState.Authorized)?.let {
                it.configIssuedAt to it.configSemanticHash
            } ?: (previous as? RuntimeCaptureAuthorityState.Terminal)?.trustedConfigBoundary
                ?: (previous as? RuntimeCaptureAuthorityState.Pending)?.trustedConfigBoundary
        val candidateBoundary = boundary?.let { it.issuedAtInstant to it.configSemanticHash }
        val trustedBoundary =
            when {
                candidateBoundary == null -> previousBoundary
                previousBoundary == null -> candidateBoundary
                candidateBoundary.first > previousBoundary.first -> candidateBoundary
                candidateBoundary.first < previousBoundary.first -> previousBoundary
                candidateBoundary.second == previousBoundary.second -> candidateBoundary
                else -> previousBoundary
            }
        val selectedCandidateBoundary = candidateBoundary != null && trustedBoundary == candidateBoundary
        val previousPolicy =
            (previous as? RuntimeCaptureAuthorityState.Authorized)?.policySourceHash
                ?: (previous as? RuntimeCaptureAuthorityState.Terminal)?.policySourceHash
        val retainedPolicy = if (selectedCandidateBoundary) policySourceHash else policySourceHash ?: previousPolicy
        val previousContext =
            (previous as? RuntimeCaptureAuthorityState.Authorized)?.contextRevision
                ?: (previous as? RuntimeCaptureAuthorityState.Terminal)?.contextRevision
        val terminal =
            RuntimeCaptureAuthorityState.Terminal(
                ownerEpoch = nextAuthorityEpoch(),
                trustedConfigBoundary = trustedBoundary,
                policySourceHash = retainedPolicy,
                contextRevision =
                    if (selectedCandidateBoundary) contextRevision else contextRevision ?: previousContext,
                reason = reason,
            )
        captureAuthority = terminal
        return RuntimeCaptureAuthorityUpdateResult.Terminated(terminal)
    }

    private fun invalidateAuthorizedContext() {
        if (captureAuthority !is RuntimeCaptureAuthorityState.Authorized) return
        terminateAuthority(
            RuntimeCaptureAuthorityTerminalReason.STALE,
            boundary = null,
        )
    }

    private fun nextAuthorityEpoch(): Long =
        try {
            Math.addExact(authorityEpoch, 1L).also { authorityEpoch = it }
        } catch (error: ArithmeticException) {
            throw IllegalStateException("Capture authority epoch exhausted", error)
        }

    private fun V1ConfigRejection.toTerminalReason(): RuntimeCaptureAuthorityTerminalReason =
        when (this) {
            V1ConfigRejection.EXPIRED -> RuntimeCaptureAuthorityTerminalReason.EXPIRED
            V1ConfigRejection.STALE -> RuntimeCaptureAuthorityTerminalReason.STALE
            V1ConfigRejection.CONFLICT -> RuntimeCaptureAuthorityTerminalReason.CONFLICT
            V1ConfigRejection.INACTIVE -> RuntimeCaptureAuthorityTerminalReason.DISABLED
            V1ConfigRejection.MALFORMED,
            V1ConfigRejection.UNSUPPORTED_SCHEMA,
            V1ConfigRejection.UNAUTHORIZED,
            -> RuntimeCaptureAuthorityTerminalReason.MALFORMED
        }

    private class FlagContextWithdrawn : IllegalStateException("Flag context authority withdrawn")

    private fun appendOnWorker(
        request: AppendRequest,
        localAdmission: ((RuntimeQueueTransaction, LoadedSnapshot) -> Boolean)? = null,
    ): RuntimeAppendResult {
        assertUsable()
        if (request.recordCount > MAX_RUNTIME_QUEUE_RECORDS) {
            return RuntimeAppendResult.Rejected(RuntimeAppendRejection.COUNT_LIMIT, requireLoaded().publicSnapshot)
        }
        var prepared: PreparedAppend? = null
        var attempts = 0
        while (attempts < MAX_RECONCILIATION_ATTEMPTS) {
            try {
                val candidate = prepared
                if (candidate == null) {
                    val outcome =
                        database().transaction { transaction ->
                            val before = requireCurrent(transaction)
                            validateAppendBoundaries(transaction, before)
                            if ((localAdmission != null && !localAdmission(transaction, before)) ||
                                (configurationGate != null && request !is AppendRequest.Local && captureAuthorityRejection(before) != null)) {
                                return@transaction AppendCommit(
                                    RuntimeAppendResult.Rejected(RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE, before.publicSnapshot),
                                    published = null,
                                )
                            }
                            val created = prepareAppend(before, request, ReplayQueueStore.state(transaction))
                            if (created.rejection != null) {
                                AppendCommit(
                                    RuntimeAppendResult.Rejected(created.rejection, before.publicSnapshot),
                                    published = null,
                                )
                            } else {
                                if ((localAdmission != null && !localAdmission(transaction, before)) ||
                                    (configurationGate != null && request !is AppendRequest.Local && captureAuthorityRejection(before) != null)) {
                                    return@transaction AppendCommit(
                                        RuntimeAppendResult.Rejected(RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE, before.publicSnapshot),
                                        published = null,
                                    )
                                }
                                prepared = created
                                commitPreparedAppend(transaction, created)
                                if (localAdmission != null && !localAdmission(transaction, before)) throw FlagContextWithdrawn()
                                AppendCommit(
                                    RuntimeAppendResult.Accepted(created.publicRecords, created.after.publicSnapshot),
                                    published = created.after,
                                )
                            }
                        }
                    outcome.published?.let { loaded = it }
                    return outcome.result
                }
                val rejected = database().transaction { transaction ->
                    val current = requireCurrent(transaction)
                    if (!viewsEqual(current, candidate.before)) {
                        corrupt("Runtime state changed before append reconciliation")
                    }
                    validateAppendBoundaries(transaction, current)
                    if ((localAdmission != null && !localAdmission(transaction, current)) ||
                        (configurationGate != null && request !is AppendRequest.Local && captureAuthorityRejection(current) != null)) {
                        return@transaction RuntimeAppendResult.Rejected(RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE, current.publicSnapshot)
                    }
                    commitPreparedAppend(transaction, candidate)
                    if (localAdmission != null && !localAdmission(transaction, current)) throw FlagContextWithdrawn()
                    null
                }
                if (rejected != null) return rejected
                loaded = candidate.after
                return RuntimeAppendResult.Accepted(candidate.publicRecords, candidate.after.publicSnapshot)
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                attempts += 1
                val candidate = prepared
                val reopened = reopenValidated(ambiguous)
                    ?: poisonAndThrow(RuntimeQueueCorruptionException("Runtime core disappeared after an ambiguous append", ambiguous))
                if (candidate != null && candidate.rejection == null) {
                    when {
                        viewsEqual(reopened, candidate.after) && recordsMatch(candidate.records) -> {
                            loaded = reopened
                            return RuntimeAppendResult.Accepted(candidate.publicRecords, reopened.publicSnapshot)
                        }
                        viewsEqual(reopened, candidate.before) -> {
                            if (attempts == MAX_RECONCILIATION_ATTEMPTS) throw ambiguous
                            prepared = candidate
                        }
                        else ->
                            poisonAndThrow(
                                RuntimeQueueCorruptionException(
                                    "Ambiguous append reopened to neither the before nor after state",
                                    ambiguous,
                                ),
                            )
                    }
                } else if (attempts == MAX_RECONCILIATION_ATTEMPTS) {
                    throw ambiguous
                }
            }
        }
        throw IllegalStateException("Append reconciliation attempts exhausted")
    }

    private fun prepareAppend(
        before: LoadedSnapshot,
        request: AppendRequest,
        replay: ReplayStoredState? = null,
    ): PreparedAppend {
        val countAfter = before.queuedCount.toLong() + request.recordCount
        if (request.recordCount > 0 && countAfter + (replay?.count ?: 0L) > limits.maximumCount) {
            return PreparedAppend.rejected(before, RuntimeAppendRejection.COUNT_LIMIT)
        }
        val nextSequence =
            try {
                Math.addExact(before.state.stream.nextSequence, request.recordCount.toLong())
            } catch (error: ArithmeticException) {
                throw IllegalStateException("Runtime sequence exhausted", error)
            }
        val records = ArrayList<RuntimeStoredRecord>(request.recordCount)
        var bytesAfter = before.queuedBytes
        var transitionedState = before.state

        fun addRecord(
            state: PersistedCoreState,
            sequence: Long,
            draft: RuntimeRecordDraft,
        ): PreparedAppend? {
            val record = encodeDraft(state, sequence, draft)
            if (record.internalPayload.size > MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES) {
                return PreparedAppend.rejected(before, RuntimeAppendRejection.RECORD_TOO_LARGE)
            }
            bytesAfter = Math.addExact(bytesAfter, record.accountedBytes.toLong())
            if (bytesAfter + (replay?.bytes ?: 0L) > currentQueueByteLimit()) {
                return PreparedAppend.rejected(before, RuntimeAppendRejection.BYTE_LIMIT)
            }
            records += record
            return null
        }

        when (request) {
            is AppendRequest.Events -> {
                transitionedState = applyEventSessionUpdate(before.state, request.sessionUpdate)
                val canonicalEventState = canonicalState(transitionedState).first
                validateStateInvariants(canonicalEventState)
                validateEventCausality(
                    lowerBound = before.state.identity.updatedAt,
                    state = canonicalEventState,
                    drafts = request.drafts,
                )
                request.drafts.forEachIndexed { index, draft ->
                    val sequence = Math.addExact(before.state.stream.nextSequence, index.toLong())
                    addRecord(canonicalEventState, sequence, draft)?.let { return it }
                }
                transitionedState = canonicalEventState
            }
            is AppendRequest.Mutations -> {
                request.drafts.forEachIndexed { index, draft ->
                    transitionedState = canonicalState(applyMutation(transitionedState, draft)).first
                    validateStateInvariants(transitionedState)
                    val sequence = Math.addExact(before.state.stream.nextSequence, index.toLong())
                    addRecord(transitionedState, sequence, draft)?.let { return it }
                }
            }
            is AppendRequest.Local -> {
                transitionedState = canonicalState(applyLocalChange(before.state, request.change)).first
                validateStateInvariants(transitionedState)
            }
        }

        val committedState =
            transitionedState.copy(
                stream = transitionedState.stream.copy(nextSequence = nextSequence),
            )
        val canonicalCommitted = canonicalState(committedState)
        val after =
            LoadedSnapshot(
                state = canonicalCommitted.first,
                stateJson = canonicalCommitted.second,
                queuedCount = countAfter.toInt(),
                queuedBytes = bytesAfter,
                headSequence = if (before.queuedCount == 0 && records.isNotEmpty()) records.first().sequence else before.headSequence,
            )
        return PreparedAppend(before, after, records, rejection = null)
    }

    private fun currentQueueByteLimit(): Long {
        val body = configurationGate?.snapshot()?.body ?: return limits.maximumBytes
        val configured = try { V1ConfigJson.parseConfig(body).limits?.queueBytes?.toLong() } catch (_: Exception) { null }
        return minOf(limits.maximumBytes, configured ?: limits.maximumBytes)
    }

    private fun commitPreparedAppend(
        transaction: RuntimeQueueTransaction,
        candidate: PreparedAppend,
    ) {
        nativeScope.beforeIdentityWrite(candidate.after.state.identity)
        if (candidate.after.state.identity.optedOut) ReplayQueueStore.purge(transaction)
        if (candidate.before.state.identity.contextRevision != candidate.after.state.identity.contextRevision) {
            transaction.invalidateCurrentFlagCache()
        }
        candidate.records.forEach { record ->
            if (transaction.readRecord(record.sequence) != null) {
                corrupt("Runtime append target sequence is already occupied")
            }
            transaction.insertRecord(record)
        }
        transaction.updateCore(candidate.after.storedCore())
    }

    private fun encodeDraft(
        state: PersistedCoreState,
        sequence: Long,
        draft: RuntimeRecordDraft,
    ): RuntimeStoredRecord {
        val identity = state.identity
        return when (draft) {
            is RuntimeRecordDraft.Event -> {
                val session = identity.session
                    ?: throw IllegalArgumentException("Events require a persisted post-transition session")
                if (draft.expectedSessionId != session.id) {
                    throw IllegalArgumentException(
                        "Event session expectation does not match the persisted post-transition session",
                    )
                }
                val event =
                    RuntimeEventRecord(
                        eventId = RuntimeRecordIdentity.recordId(state.stream.streamId, sequence, RuntimeRecordKind.EVENT),
                        streamId = state.stream.streamId,
                        sequence = sequence,
                        contextRevision = identity.contextRevision,
                        kind = draft.kind,
                        name = draft.name,
                        occurredAt = draft.occurredAt,
                        identity = RuntimeEventIdentity(identity.anonymousId, identity.userId, identity.revision),
                        sessionId = session.id,
                        properties = draft.properties,
                        groups = identity.groups,
                        versions = draft.versions,
                    )
                val payload = RuntimeRecordCodec.encodeEvent(event)
                val canonical = RuntimeRecordCodec.decodeEvent(payload)
                val accountedBytes = RuntimeRecordCodec.encodeBatchRecord(canonical).size
                RuntimeStoredRecord(
                    sequence,
                    state.stream.streamId,
                    RuntimeRecordKind.EVENT,
                    canonical.eventId,
                    payload,
                    accountedBytes,
                )
            }
            is RuntimeRecordDraft.Mutation -> {
                val envelope =
                    RuntimeMutationEnvelope(
                        streamId = state.stream.streamId,
                        versions = draft.versions,
                        mutation =
                            RuntimeMutationRecord(
                                mutationId =
                                    RuntimeRecordIdentity.recordId(
                                        state.stream.streamId,
                                        sequence,
                                        RuntimeRecordKind.MUTATION,
                                    ),
                                sequence = sequence,
                                contextRevision = identity.contextRevision,
                                occurredAt = draft.occurredAt,
                                subject =
                                    RuntimeMutationSubject(
                                        identity.anonymousId,
                                        identity.userId,
                                        identity.revision,
                                    ),
                                change = draft.change,
                            ),
                    )
                val payload = RuntimeRecordCodec.encodeMutation(envelope)
                val canonical = RuntimeRecordCodec.decodeMutation(payload)
                val accountedBytes = RuntimeRecordCodec.encodeBatchRecord(canonical).size
                RuntimeStoredRecord(
                    sequence,
                    state.stream.streamId,
                    RuntimeRecordKind.MUTATION,
                    canonical.mutation.mutationId,
                    payload,
                    accountedBytes,
                )
            }
        }
    }

    private fun peekOnWorker(
        maximumCount: Int,
        maximumBytes: Long,
    ): List<RuntimeQueuedRecord> {
        assertUsable()
        return database().transaction { transaction ->
            val current = requireCurrent(transaction)
            if (current.queuedCount == 0) return@transaction emptyList()
            val requested = minOf(maximumCount, current.queuedCount, MAX_RUNTIME_DELIVERY_RECORDS)
            val byteLimit = minOf(maximumBytes, MAX_RUNTIME_DELIVERY_BYTES)
            val out = ArrayList<RuntimeQueuedRecord>(requested)
            var expected = current.headSequence
            var bytes = 0L
            while (out.size < requested) {
                val row = transaction.readRecord(expected)
                    ?: corrupt("Queue prefix contains a sequence gap")
                val nextBytes = Math.addExact(bytes, row.accountedBytes.toLong())
                if (nextBytes > byteLimit) {
                    if (out.isEmpty()) {
                        throw RuntimeQueueHeadTooLargeException(row.accountedBytes, byteLimit)
                    }
                    break
                }
                out += validateStoredRecord(row, current.state)
                bytes = nextBytes
                expected = Math.addExact(expected, 1L)
            }
            Collections.unmodifiableList(out)
        }
    }

    private fun acknowledgeOnWorker(acknowledgement: RuntimeAcknowledgement): RuntimeAcknowledgementResult {
        assertUsable()
        val references = acknowledgement.references
        var prepared: PreparedAcknowledgement? = null
        repeat(MAX_RECONCILIATION_ATTEMPTS) { attempt ->
            try {
                val outcome =
                    database().transaction { transaction ->
                        val before = requireCurrent(transaction)
                        if (acknowledgement.streamId != before.state.stream.streamId) {
                            throw RuntimeAcknowledgementMismatchException(
                                "Acknowledgement stream does not match this runtime namespace",
                            )
                        }
                        // This runs before AlreadyApplied: even retired imported UUIDs must match
                        // the permanent ledger; ordinary identities retain their original derivation.
                        references.forEach { reference ->
                            val imported = before.state.startupHistory?.records?.let { entries ->
                                if (reference.sequence < entries.size.toLong()) entries[reference.sequence.toInt()] else null
                            }
                            val expectedId = imported?.recordId ?: RuntimeRecordIdentity.recordId(
                                acknowledgement.streamId, reference.sequence, reference.kind)
                            if (reference.recordId != expectedId || imported != null &&
                                (imported.sequence != reference.sequence || imported.kind != reference.kind.wireValue)) {
                                throw RuntimeAcknowledgementMismatchException("Acknowledgement identity does not match its immutable stream ledger")
                            }
                        }
                        if (references.isEmpty()) {
                            return@transaction AcknowledgementCommit(
                                RuntimeAcknowledgementResult.Empty(before.publicSnapshot),
                                published = null,
                            )
                        }
                        val last = references.last().sequence
                        if (last < before.headSequence) {
                            return@transaction AcknowledgementCommit(
                                RuntimeAcknowledgementResult.AlreadyApplied(before.publicSnapshot),
                                published = null,
                            )
                        }
                        if (references.first().sequence < before.headSequence) {
                            throw RuntimeAcknowledgementMismatchException("Acknowledgement overlaps the current queue head")
                        }
                        if (references.first().sequence != before.headSequence) {
                            throw RuntimeAcknowledgementMismatchException("Acknowledgement does not begin at the queue head")
                        }
                        var removedBytes = 0L
                        references.forEach { reference ->
                            val row = transaction.readRecord(reference.sequence)
                                ?: throw RuntimeAcknowledgementMismatchException(
                                    "Acknowledgement extends beyond the queued prefix",
                                )
                            if (
                                row.sequence != reference.sequence || row.kind != reference.kind ||
                                row.recordId != reference.recordId
                            ) {
                                throw RuntimeAcknowledgementMismatchException(
                                    "Acknowledgement reference does not exactly match the queued prefix",
                                )
                            }
                            validateStoredRecord(row, before.state)
                            removedBytes = Math.addExact(removedBytes, row.accountedBytes.toLong())
                            if (!transaction.deleteRecord(reference.sequence)) {
                                corrupt("Verified acknowledgement row disappeared during deletion")
                            }
                        }
                        val remainingCount = before.queuedCount - references.size
                        val remainingBytes = Math.subtractExact(before.queuedBytes, removedBytes)
                        val head = if (remainingCount == 0) before.state.stream.nextSequence else Math.addExact(last, 1L)
                        val after = before.copy(queuedCount = remainingCount, queuedBytes = remainingBytes, headSequence = head)
                        val candidate = PreparedAcknowledgement(before, after, references)
                        prepared = candidate
                        transaction.updateCore(after.storedCore())
                        AcknowledgementCommit(
                            RuntimeAcknowledgementResult.Deleted(references.size, after.publicSnapshot),
                            published = after,
                        )
                    }
                outcome.published?.let { loaded = it }
                return outcome.result
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                val candidate = prepared
                val reopened = reopenValidated(ambiguous)
                    ?: poisonAndThrow(RuntimeQueueCorruptionException("Runtime core disappeared after an ambiguous acknowledgement"))
                if (candidate == null) {
                    loaded = reopened
                    if (attempt == MAX_RECONCILIATION_ATTEMPTS - 1) throw ambiguous
                    return@repeat
                }
                when {
                    viewsEqual(reopened, candidate.after) -> {
                        loaded = reopened
                        return RuntimeAcknowledgementResult.Deleted(candidate.references.size, reopened.publicSnapshot)
                    }
                    viewsEqual(reopened, candidate.before) -> {
                        if (attempt == MAX_RECONCILIATION_ATTEMPTS - 1) throw ambiguous
                    }
                    else ->
                        poisonAndThrow(
                            RuntimeQueueCorruptionException(
                                "Ambiguous acknowledgement reopened to neither the before nor after state",
                                ambiguous,
                            ),
                        )
                }
            }
        }
        throw IllegalStateException("Acknowledgement reconciliation attempts exhausted")
    }

    private fun requireCurrent(transaction: RuntimeQueueTransaction): LoadedSnapshot {
        val disk = loadValidated(transaction, validatePayloads = false)
            ?: corrupt("Runtime core state is missing")
        val memory = requireLoaded()
        if (!viewsEqual(disk, memory)) corrupt("Runtime state changed outside its installation owner")
        return disk
    }

    private fun loadValidated(
        transaction: RuntimeQueueTransaction,
        validatePayloads: Boolean,
    ): LoadedSnapshot? {
        val core = transaction.readCore()
        if (core == null) {
            if (validatePayloads) {
                transaction.scanRecords { corrupt("Queue rows exist without core state") }
            }
            return null
        }
        if (core.queueCount !in 0..MAX_RUNTIME_QUEUE_RECORDS.toLong()) corrupt("Stored queue count is outside the supported range")
        if (core.queueBytes !in 0..MAX_RUNTIME_QUEUE_BYTES) corrupt("Stored queue bytes are outside the supported range")
        val state = CoreStateCodec.decode(core.stateJson)
        val canonicalState = CoreStateCodec.encode(state)
        if (!canonicalState.contentEquals(core.stateJson)) corrupt("Stored core state is not canonical")
        validateStateInvariants(state)
        ReplayQueueStore.validateNative(transaction, ownerNamespaceHash, state.stream.streamId)
        val count = core.queueCount.toInt()
        if (core.queueCount > state.stream.nextSequence) {
            corrupt("Stored queue count exceeds the allocated sequence range")
        }
        val head = Math.subtractExact(state.stream.nextSequence, core.queueCount)
        val loaded = LoadedSnapshot(state, core.stateJson.copyOf(), count, core.queueBytes, head)
        if (validatePayloads) {
            validateAllRecords(transaction, loaded)
            ReplayQueueStore.validate(transaction, ownerNamespaceHash)
        }
        return loaded
    }

    private fun validateAllRecords(
        transaction: RuntimeQueueTransaction,
        snapshot: LoadedSnapshot,
    ) {
        var expected = snapshot.headSequence
        var observedCount = 0
        var observedBytes = 0L
        transaction.scanRecords { row ->
            if (observedCount == MAX_RUNTIME_QUEUE_RECORDS) corrupt("Queue exceeds the supported record count")
            if (row.sequence != expected) corrupt("Queue contains a sequence gap")
            validateStoredRecord(row, snapshot.state)
            observedCount += 1
            observedBytes = Math.addExact(observedBytes, row.accountedBytes.toLong())
            if (observedBytes > MAX_RUNTIME_QUEUE_BYTES) corrupt("Queue exceeds the supported byte count")
            expected = Math.addExact(expected, 1L)
        }
        if (observedCount != snapshot.queuedCount || observedBytes != snapshot.queuedBytes) {
            corrupt("Stored queue counters do not match streamed queue rows")
        }
        if (expected != snapshot.state.stream.nextSequence) corrupt("Queue validation did not end at nextSequence")
    }

    private fun validateStoredRecord(
        row: RuntimeStoredRecord,
        state: PersistedCoreState,
    ): RuntimeQueuedRecord {
        if (
            row.internalPayload.isEmpty() ||
            row.internalPayload.size > MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES ||
            row.accountedBytes <= 0
        ) {
            corrupt("Queue row byte accounting is invalid")
        }
        if (row.sequence < 0 || row.streamId != state.stream.streamId) corrupt("Queue row stream metadata is invalid")
        val decoded = RuntimeRecordCodec.decodeQueued(row.kind, row.internalPayload, row.accountedBytes)
        if (
            decoded.sequence != row.sequence || decoded.recordId != row.recordId ||
            decoded.streamId != row.streamId || decoded.kind != row.kind
        ) {
            corrupt("Queue row metadata does not match its payload")
        }
        val imported = state.startupHistory?.records?.let { entries ->
            if (row.sequence < entries.size.toLong()) entries[row.sequence.toInt()] else null
        }
        if (imported == null) {
            val expectedRecordId = RuntimeRecordIdentity.recordId(row.streamId, row.sequence, row.kind)
            if (row.recordId != expectedRecordId) corrupt("Queue row record identity is not stream/sequence derived")
        } else {
            if (imported.sequence != row.sequence || imported.recordId != row.recordId || imported.kind != row.kind.wireValue ||
                imported.payloadSha256 != MessageDigest.getInstance("SHA-256").digest(row.internalPayload).joinToString("") { "%02x".format(it) }) {
                corrupt("Imported queue row does not match its immutable migration ledger")
            }
            val occurredAt = when (decoded) {
                is RuntimeQueuedRecord.Event -> decoded.record.occurredAt
                is RuntimeQueuedRecord.Mutation -> decoded.envelope.mutation.occurredAt
            }
            if (occurredAt != imported.occurredAt || decoded is RuntimeQueuedRecord.Event && decoded.record.sessionId != imported.sourceSessionId) {
                corrupt("Imported queue row lost its captured source time or session")
            }
        }
        when (decoded) {
            is RuntimeQueuedRecord.Event -> {
                if (decoded.record.identity.revision > decoded.record.contextRevision) {
                    corrupt("Event identity revision exceeds its context revision")
                }
            }
            is RuntimeQueuedRecord.Mutation -> {
                if (decoded.envelope.mutation.subject.identityRevision > decoded.envelope.mutation.contextRevision) {
                    corrupt("Mutation identity revision exceeds its context revision")
                }
            }
        }
        val canonical =
            when (decoded) {
                is RuntimeQueuedRecord.Event -> RuntimeRecordCodec.encodeEvent(decoded.record)
                is RuntimeQueuedRecord.Mutation -> RuntimeRecordCodec.encodeMutation(decoded.envelope)
            }
        if (!canonical.contentEquals(row.internalPayload)) corrupt("Queue internal payload is not canonical")
        val accountedBytes = RuntimeRecordCodec.encodeBatchRecord(decoded).size
        if (accountedBytes != row.accountedBytes) {
            corrupt("Queue accounted bytes do not match the canonical V1BatchRecord")
        }
        return decoded
    }

    private fun recordsMatch(records: List<RuntimeStoredRecord>): Boolean {
        if (records.isEmpty()) return true
        return database().transaction { transaction ->
            records.all { expected ->
                transaction.readRecord(expected.sequence)?.let { stored ->
                    storedRecordsEqual(stored, expected)
                } == true
            }
        }
    }

    private fun validateAppendBoundaries(
        transaction: RuntimeQueueTransaction,
        snapshot: LoadedSnapshot,
    ) {
        val nextSequence = snapshot.state.stream.nextSequence
        if (transaction.readRecord(nextSequence) != null) {
            corrupt("Queue contains an unaccounted append-target row")
        }
        if (snapshot.queuedCount == 0) return
        val head = transaction.readRecord(snapshot.headSequence)
            ?: corrupt("Queue head is missing")
        validateStoredRecord(head, snapshot.state)
        val tailSequence = Math.subtractExact(nextSequence, 1L)
        if (tailSequence != snapshot.headSequence) {
            val tail = transaction.readRecord(tailSequence)
                ?: corrupt("Queue tail is missing")
            validateStoredRecord(tail, snapshot.state)
        }
    }

    private fun reopenValidated(cause: Throwable, holdNativeScope: Boolean = false): LoadedSnapshot? {
        assertWorkerThread()
        // Retain original native tokens/floors only across verified same-owner reconciliation.
        // Concurrent consumers deny throughout the uncertain database interval.
        nativeScopeReconciliation = true
        nativeScope.suspendForReconciliation()
        // Generic callers still have record/view proof to perform after this read. They
        // cannot revive a retained native guard; only explicit held paths prove continuity.
        if (!holdNativeScope) nativeScope.invalidate()
        return try {
            try { database?.close() } catch (closeError: Throwable) { cause.addSuppressed(closeError) }
            database = null
            loaded = null
            database = databaseFactory()
            database().transaction { transaction -> loadValidated(transaction, validatePayloads = true) }
                .also { reopened -> loaded = reopened }
        } catch (error: Throwable) {
            error.addSuppressed(cause)
            poisonAndThrow(error)
        } finally {
            if (!holdNativeScope) resumeNativeScopeAfterReconciliation()
        }
    }

    private fun resumeNativeScopeAfterReconciliation() {
        if (!nativeScopeReconciliation) return
        nativeScopeReconciliation = false
        nativeScope.resumeAfterReconciliation(loaded?.state?.identity, captureAuthority as? RuntimeCaptureAuthorityState.Authorized)
    }

    private fun canonicalState(state: PersistedCoreState): Pair<PersistedCoreState, ByteArray> {
        val bytes = CoreStateCodec.encode(state)
        return CoreStateCodec.decode(bytes) to bytes
    }

    private fun applyEventSessionUpdate(
        state: PersistedCoreState,
        update: RuntimeEventSessionUpdate,
    ): PersistedCoreState =
        when (update) {
            RuntimeEventSessionUpdate.Preserve -> {
                val session = state.identity.session
                    ?: throw IllegalArgumentException("Events require a persisted session")
                validateEventSession(session, state.identity.updatedAt)
                state
            }
            is RuntimeEventSessionUpdate.Replace -> {
                validateSessionTransition(state, update)
                state.copy(
                    identity =
                        state.identity.copy(
                            session = update.session,
                            updatedAt = update.session.lastActivityAt,
                        ),
                )
            }
        }

    private fun applyMutation(
        state: PersistedCoreState,
        draft: RuntimeRecordDraft.Mutation,
    ): PersistedCoreState {
        val identity = state.identity
        requireTimestampNotBefore(draft.occurredAt, identity.updatedAt, "Mutation occurredAt")
        val nextContextRevision = increment(identity.contextRevision, "identity context revision")
        return when (val change = draft.change) {
            is RuntimeMutationChange.Identify -> {
                val identityChanged = identity.userId != change.userId
                state.copy(
                    identity =
                        identity.copy(
                            revision =
                                if (identityChanged) increment(identity.revision, "identity revision") else identity.revision,
                            contextRevision = nextContextRevision,
                            userId = change.userId,
                            updatedAt = draft.occurredAt,
                        ),
                    flagContext =
                        state.flagContext.copy(
                            personProperties =
                                applyProperties(
                                    state.flagContext.personProperties,
                                    change.set,
                                    change.setOnce,
                                    emptyList(),
                                ),
                        ),
                )
            }
            is RuntimeMutationChange.LinkAlias ->
                state.copy(
                    identity =
                        identity.copy(
                            contextRevision = nextContextRevision,
                            updatedAt = draft.occurredAt,
                        ),
                )
            is RuntimeMutationChange.SetPersonProperties ->
                state.copy(
                    identity =
                        identity.copy(
                            contextRevision = nextContextRevision,
                            updatedAt = draft.occurredAt,
                        ),
                    flagContext =
                        state.flagContext.copy(
                            personProperties =
                                applyProperties(
                                    state.flagContext.personProperties,
                                    change.set,
                                    change.setOnce,
                                    change.unset,
                                ),
                        ),
                )
            is RuntimeMutationChange.AssociateGroup -> {
                val previousKey = identity.groups[change.groupType]
                val groups = LinkedHashMap(identity.groups).apply { put(change.groupType, change.groupKey) }
                val groupProperties =
                    if (previousKey != null && previousKey != change.groupKey) {
                        LinkedHashMap(state.flagContext.groupProperties).apply { remove(change.groupType) }
                    } else {
                        state.flagContext.groupProperties
                    }
                state.copy(
                    identity =
                        identity.copy(
                            contextRevision = nextContextRevision,
                            groups = groups,
                            updatedAt = draft.occurredAt,
                        ),
                    flagContext = state.flagContext.copy(groupProperties = groupProperties),
                )
            }
            is RuntimeMutationChange.SetGroupProperties -> {
                require(identity.groups[change.groupType] == change.groupKey) {
                    "Group properties mutation must match the persisted group association"
                }
                val properties =
                    applyProperties(
                        state.flagContext.groupProperties[change.groupType].orEmpty(),
                        change.set,
                        change.setOnce,
                        change.unset,
                    )
                val groupProperties =
                    LinkedHashMap(state.flagContext.groupProperties).apply {
                        put(change.groupType, properties)
                    }
                state.copy(
                    identity =
                        identity.copy(
                            contextRevision = nextContextRevision,
                            updatedAt = draft.occurredAt,
                        ),
                    flagContext = state.flagContext.copy(groupProperties = groupProperties),
                )
            }
        }
    }

    private fun applyLocalChange(
        state: PersistedCoreState,
        change: RuntimeLocalStateChange,
    ): PersistedCoreState =
        when (change) {
            is RuntimeLocalStateChange.SetOptedOut ->
                state.copy(
                    identity =
                        state.identity.copy(
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            optedOut = change.optedOut,
                            session = if (change.optedOut) null else state.identity.session,
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                )
            is RuntimeLocalStateChange.ResetGroups ->
                state.copy(
                    identity =
                        state.identity.copy(
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            groups = emptyMap(),
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                    flagContext = state.flagContext.copy(groupProperties = emptyMap()),
                )
            is RuntimeLocalStateChange.ResetIdentity ->
                state.copy(
                    identity =
                        state.identity.copy(
                            revision = increment(state.identity.revision, "identity revision"),
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            anonymousId = nextAnonymousId(state.identity.anonymousId),
                            userId = null,
                            groups = emptyMap(),
                            superProperties = emptyMap(),
                            session = null,
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                    flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
                )
            is RuntimeLocalStateChange.RegisterSuperProperties -> {
                require(change.properties.keys.none { it.isEmpty() }) { "Super-property names must not be empty" }
                state.copy(
                    identity =
                        state.identity.copy(
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            superProperties =
                                LinkedHashMap(state.identity.superProperties).apply {
                                    putAll(change.properties)
                                },
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                )
            }
            is RuntimeLocalStateChange.RegisterSuperPropertiesOnce -> {
                val current = state.identity.superProperties
                val selected = change.properties.filter { (key, _) ->
                    !current.containsKey(key) || when (val fallback = change.defaultValue) {
                        null -> current[key] == null
                        is Number -> (current[key] as? Number)?.toDouble() == fallback.toDouble()
                        is String, is Boolean -> current[key] == fallback
                        else -> false
                    }
                }
                if (selected.isEmpty()) state else applyLocalChange(state,
                    RuntimeLocalStateChange.RegisterSuperProperties(selected, change.occurredAt))
            }
            is RuntimeLocalStateChange.UnregisterSuperProperties -> {
                require(change.keys.distinct().size == change.keys.size) {
                    "Super-property unregister keys must be unique"
                }
                require(change.keys.none { it.isEmpty() }) { "Super-property names must not be empty" }
                state.copy(
                    identity =
                        state.identity.copy(
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            superProperties =
                                LinkedHashMap(state.identity.superProperties).apply {
                                    change.keys.forEach(::remove)
                                },
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                )
            }
            is RuntimeLocalStateChange.ResetFlagPersonProperties -> state.copy(
                identity = state.identity.copy(contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                    updatedAt = checkedLocalTimestamp(state, change)),
                flagContext = state.flagContext.copy(personProperties = emptyMap()))
            is RuntimeLocalStateChange.ResetFlagGroupProperties -> state.copy(
                identity = state.identity.copy(contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                    updatedAt = checkedLocalTimestamp(state, change)),
                flagContext = state.flagContext.copy(groupProperties = if (change.groupType == null) emptyMap()
                    else state.flagContext.groupProperties.filterKeys { it != change.groupType }))
            is RuntimeLocalStateChange.SetFlagPersonProperties -> {
                val normalized = JsonValues.objectValue(applyProperties(state.flagContext.personProperties,
                    change.properties, change.setOnce, emptyList()), "flagContext.personProperties")
                state.copy(
                    identity =
                        state.identity.copy(
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                    flagContext =
                        state.flagContext.copy(
                            personProperties =
                                LinkedHashMap(state.flagContext.personProperties).apply {
                                    putAll(normalized)
                                },
                        ),
                )
            }
            is RuntimeLocalStateChange.SetFlagGroup -> {
                require(change.groupType.isNotEmpty() && change.groupKey.isNotEmpty()) { "Flag group identifiers must not be empty" }
                val previous = state.identity.groups[change.groupType]
                val groups = LinkedHashMap(state.identity.groups).apply { put(change.groupType, change.groupKey) }
                val known = LinkedHashMap(state.flagContext.groupProperties)
                if (previous != null && previous != change.groupKey) known.remove(change.groupType)
                if (change.properties != null) {
                    require(known.containsKey(change.groupType) || known.size < MAX_FLAG_CONTEXT_GROUP_TYPES) {
                        "Flag group properties exceed the supported group types"
                    }
                    val normalized = JsonValues.objectValue(change.properties, "flagContext.groupProperties")
                    known[change.groupType] = LinkedHashMap(known[change.groupType].orEmpty()).apply { putAll(normalized) }
                }
                state.copy(identity = state.identity.copy(
                    contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                    groups = groups, updatedAt = checkedLocalTimestamp(state, change)),
                    flagContext = state.flagContext.copy(groupProperties = known))
            }
            is RuntimeLocalStateChange.SetFlagGroupProperties -> {
                require(change.groupType.isNotEmpty()) { "Flag group type must not be empty" }
                val known = state.flagContext.groupProperties
                require(known.containsKey(change.groupType) || known.size < MAX_FLAG_CONTEXT_GROUP_TYPES) {
                    "Flag group properties may describe at most $MAX_FLAG_CONTEXT_GROUP_TYPES group types"
                }
                val normalized = JsonValues.objectValue(change.properties, "flagContext.groupProperties")
                val merged = LinkedHashMap(known[change.groupType].orEmpty()).apply { putAll(normalized) }
                state.copy(
                    identity =
                        state.identity.copy(
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                    flagContext =
                        state.flagContext.copy(
                            groupProperties = LinkedHashMap(known).apply { put(change.groupType, merged) },
                        ),
                )
            }
            is RuntimeLocalStateChange.MarkBackgrounded -> {
                require(!state.identity.optedOut) { "An opted-out runtime cannot background a session" }
                val session = state.identity.session ?: return state
                requireTimestampNotBefore(change.occurredAt, state.identity.updatedAt, "Background occurredAt")
                requireTimestampNotBefore(change.occurredAt, session.lastActivityAt, "Background occurredAt")
                if (session.lifecycle == SessionLifecycle.BACKGROUND && session.backgroundedAt == change.occurredAt) {
                    state
                } else {
                    state.copy(
                        identity =
                            state.identity.copy(
                                session =
                                    session.copy(
                                        lifecycle = SessionLifecycle.BACKGROUND,
                                        backgroundedAt = change.occurredAt,
                                    ),
                                updatedAt = change.occurredAt,
                            ),
                    )
                }
            }
        }

    private fun checkedLocalTimestamp(
        state: PersistedCoreState,
        change: RuntimeLocalStateChange,
    ): String {
        requireTimestampNotBefore(change.occurredAt, state.identity.updatedAt, "Local change occurredAt")
        return change.occurredAt
    }

    private fun validateSessionTransition(
        state: PersistedCoreState,
        update: RuntimeEventSessionUpdate.Replace,
    ) {
        val current = state.identity.session
        require(current?.id == update.expectedCurrentSessionId) {
            "Persisted current session no longer matches the replacement expectation"
        }
        val session = update.session
        validateEventSession(session, session.lastActivityAt)
        requireTimestampNotBefore(session.lastActivityAt, state.identity.updatedAt, "Session lastActivityAt")
        current?.let {
            validateStoredSession(current, state.identity.updatedAt)
            if (current.id == session.id) {
                require(session.startedAt == current.startedAt) {
                    "An existing session may not change its start timestamp"
                }
                requireTimestampNotBefore(
                    session.lastActivityAt,
                    current.lastActivityAt,
                    "Session lastActivityAt",
                )
                val effectiveTimeoutSeconds = minOf(current.timeoutSeconds, session.timeoutSeconds)
                require(
                    RuntimeRecordCodec.compareElapsedSeconds(
                        session.lastActivityAt,
                        current.lastActivityAt,
                        effectiveTimeoutSeconds,
                    ) < 0,
                ) { "An expired session may not be revived by replacement" }
            } else {
                val currentBoundary = current.backgroundedAt ?: current.lastActivityAt
                requireTimestampNotBefore(session.startedAt, currentBoundary, "Replacement session startedAt")
            }
        }
    }

    private fun validateEventSession(
        session: SessionState,
        identityUpdatedAt: String,
    ) {
        validateStoredSession(session, identityUpdatedAt)
        require(session.lifecycle == SessionLifecycle.ACTIVE) {
            "Event session replacement must be active"
        }
        require(session.backgroundedAt == null) {
            "An active event session may not retain a background timestamp"
        }
    }

    private fun validateStoredSession(
        session: SessionState,
        identityUpdatedAt: String,
    ) {
        requireTimestampNotBefore(session.lastActivityAt, session.startedAt, "Session lastActivityAt")
        requireTimestampNotBefore(identityUpdatedAt, session.lastActivityAt, "Identity updatedAt")
        require(
            RuntimeRecordCodec.compareElapsedSeconds(
                session.lastActivityAt,
                session.startedAt,
                session.maximumDurationSeconds,
            ) < 0,
        ) { "Session exceeds its maximum duration" }
        when (session.lifecycle) {
            SessionLifecycle.ACTIVE ->
                require(session.backgroundedAt == null) {
                    "An active session may not retain a background timestamp"
                }
            SessionLifecycle.BACKGROUND -> {
                val backgroundedAt =
                    requireNotNull(session.backgroundedAt) {
                        "A background session requires a background timestamp"
                    }
                requireTimestampNotBefore(backgroundedAt, session.lastActivityAt, "Session backgroundedAt")
                requireTimestampNotBefore(identityUpdatedAt, backgroundedAt, "Identity updatedAt")
            }
        }
    }

    private fun validateEventCausality(
        lowerBound: String,
        state: PersistedCoreState,
        drafts: List<RuntimeRecordDraft.Event>,
    ) {
        val session = state.identity.session
            ?: throw IllegalArgumentException("Events require a persisted post-transition session")
        var previousOccurredAt = lowerBound
        drafts.forEach { draft ->
            requireTimestampNotBefore(draft.occurredAt, previousOccurredAt, "Event occurredAt")
            requireTimestampNotBefore(
                session.lastActivityAt,
                draft.occurredAt,
                "Session lastActivityAt",
            )
            previousOccurredAt = draft.occurredAt
        }
    }

    private fun requireTimestampNotBefore(
        candidate: String,
        current: String,
        label: String,
    ) {
        require(RuntimeRecordCodec.compareTimestamps(candidate, current) >= 0) {
            "$label may not move persisted time backward"
        }
    }

    private fun applyProperties(
        current: Map<String, Any?>,
        set: Map<String, Any?>,
        setOnce: Map<String, Any?>,
        unset: List<String>,
    ): Map<String, Any?> =
        LinkedHashMap(current).apply {
            unset.forEach(::remove)
            setOnce.forEach { (key, value) -> if (!containsKey(key)) put(key, value) }
            putAll(set)
        }

    private fun nextAnonymousId(excluding: String): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = identifiers.next("anon_")
            val length = candidate.codePointCount(0, candidate.length)
            if (length in 1..256 && candidate != excluding) return candidate
        }
        throw IllegalStateException("Identifier generator could not rotate the anonymous ID")
    }

    private fun validateStateInvariants(state: PersistedCoreState) {
        if (state.identity.revision > state.identity.contextRevision) {
            corrupt("Persisted identity revision exceeds its context revision")
        }
    }

    private fun increment(
        value: Long,
        label: String,
    ): Long =
        try {
            Math.addExact(value, 1L)
        } catch (error: ArithmeticException) {
            throw IllegalStateException("$label exhausted", error)
        }

    private fun validateAcknowledgement(acknowledgement: RuntimeAcknowledgement) {
        val streamLength = acknowledgement.streamId.codePointCount(0, acknowledgement.streamId.length)
        require(streamLength in 1..256) { "Acknowledgement stream ID length must be in 1..256" }
        require(acknowledgement.references.size <= MAX_RUNTIME_DELIVERY_RECORDS) {
            "Acknowledgement exceeds the bounded delivery prefix"
        }
        validateReferences(acknowledgement.streamId, acknowledgement.references)
    }

    private fun validateReferences(
        streamId: String,
        references: List<RuntimeRecordReference>,
    ) {
        var previous: Long? = null
        references.forEach { reference ->
            require(reference.sequence >= 0) { "Acknowledgement sequence must be non-negative" }
            val length = reference.recordId.codePointCount(0, reference.recordId.length)
            require(length in 1..256) { "Acknowledgement record ID length must be in 1..256" }
            require(reference.recordId == RuntimeRecordIdentity.recordId(streamId, reference.sequence, reference.kind) ||
                Regex("[a-f0-9]{8}-[a-f0-9]{4}-7[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}").matches(reference.recordId)) {
                "Acknowledgement record identity is not bound to its stream and sequence"
            }
            previous?.let { expected ->
                val next =
                    try {
                        Math.addExact(expected, 1L)
                    } catch (error: ArithmeticException) {
                        throw IllegalArgumentException("Acknowledgement sequence exhausted", error)
                    }
                require(reference.sequence == next) { "Acknowledgement references must be contiguous and ordered" }
            }
            previous = reference.sequence
        }
    }

    private fun viewsEqual(
        left: LoadedSnapshot,
        right: LoadedSnapshot,
    ): Boolean =
        left.queuedCount == right.queuedCount &&
            left.queuedBytes == right.queuedBytes &&
            left.headSequence == right.headSequence &&
            left.stateJson.contentEquals(right.stateJson)

    private fun storedRecordsEqual(
        left: RuntimeStoredRecord,
        right: RuntimeStoredRecord,
    ): Boolean =
        left.sequence == right.sequence && left.streamId == right.streamId && left.kind == right.kind &&
            left.recordId == right.recordId && left.accountedBytes == right.accountedBytes &&
            left.internalPayload.contentEquals(right.internalPayload)

    private fun assertUsable() {
        assertWorkerThread()
        poison?.let { throw IllegalStateException("Runtime queue owner is poisoned", it) }
        requireLoaded()
    }

    private fun assertWorkerThread() {
        check(Thread.currentThread() === workerThread) { "Runtime storage accessed outside its dedicated worker" }
    }

    private fun requireLoaded(): LoadedSnapshot = loaded ?: throw IllegalStateException("Runtime queue is not initialized")

    private fun database(): RuntimeQueueDatabase = database ?: throw IllegalStateException("Runtime database is not open")

    /** Mark unusable without closing SQLite while its transaction is still unwinding. */
    private fun corrupt(message: String): Nothing {
        val error = RuntimeQueueCorruptionException(message)
        poison = error
        nativeCaptureEnrollment?.notifyQuarantineIfPhysicallyFinished()
        throw error
    }

    private fun quarantineReplayFailure(error: Throwable) {
        val enrollment = synchronized(lifecycleLock) { poison = error; nativeCaptureEnrollment }
        enrollment?.notifyQuarantineIfPhysicallyFinished()
    }

    private fun poisonAndThrow(error: Throwable): Nothing {
        poison = error
        nativeCaptureEnrollment?.notifyQuarantineIfPhysicallyFinished()
        try {
            database?.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        database = null
        loaded = null
        throw error
    }

    /** Resource-only process quarantine. No owner/self, callbacks or database work retained. */
    private fun quarantineNativeResources() {
        synchronized(NATIVE_QUARANTINED_RESOURCES) {
            if (database != null || lease != null) NATIVE_QUARANTINED_RESOURCES += database to lease
        }
        database = null; lease = null; loaded = null
    }

    private fun closeResources() {
        try {
            database?.close()
        } finally {
            database = null
            loaded = null
            lease?.close()
            lease = null
        }
    }

    private fun <T> submit(revokeNative: Boolean = false, block: () -> T): Future<T> =
        synchronized(lifecycleLock) {
            if (!acceptingTasks) throw IllegalStateException("Runtime queue owner is closing")
            if (revokeNative) nativeScope.invalidate()
            try {
                executor.submit(Callable { assertWorkerThread(); block() })
            } catch (error: RejectedExecutionException) {
                throw IllegalStateException("Runtime queue worker is unavailable", error)
            }
        }

    private sealed interface AppendRequest {
        val recordCount: Int

        data class Events(
            val sessionUpdate: RuntimeEventSessionUpdate,
            val drafts: List<RuntimeRecordDraft.Event>,
        ) : AppendRequest {
            override val recordCount: Int = drafts.size
        }

        data class Mutations(val drafts: List<RuntimeRecordDraft.Mutation>) : AppendRequest {
            override val recordCount: Int = drafts.size
        }

        data class Local(val change: RuntimeLocalStateChange) : AppendRequest {
            override val recordCount: Int = 0
        }
    }

    private data class LoadedSnapshot(
        val state: PersistedCoreState,
        val stateJson: ByteArray,
        val queuedCount: Int,
        val queuedBytes: Long,
        val headSequence: Long,
    ) {
        val publicSnapshot: RuntimeQueueSnapshot
            get() = RuntimeQueueSnapshot(state, queuedCount, queuedBytes, headSequence)

        fun storedCore(): RuntimeStoredCore = RuntimeStoredCore(stateJson.copyOf(), queuedCount.toLong(), queuedBytes)
    }

    private data class PreparedAppend(
        val before: LoadedSnapshot,
        val after: LoadedSnapshot,
        val records: List<RuntimeStoredRecord>,
        val rejection: RuntimeAppendRejection?,
    ) {
        val publicRecords: List<RuntimeQueuedRecord>
            get() =
                Collections.unmodifiableList(
                    records.map { row ->
                        RuntimeRecordCodec.decodeQueued(row.kind, row.internalPayload, row.accountedBytes)
                    },
                )

        companion object {

            fun rejected(
                before: LoadedSnapshot,
                rejection: RuntimeAppendRejection,
            ): PreparedAppend = PreparedAppend(before, before, emptyList(), rejection)
        }
    }

    private data class PreparedAcknowledgement(
        val before: LoadedSnapshot,
        val after: LoadedSnapshot,
        val references: List<RuntimeRecordReference>,
    )

    private data class AppendCommit(
        val result: RuntimeAppendResult,
        val published: LoadedSnapshot?,
    )

    private data class AcknowledgementCommit(
        val result: RuntimeAcknowledgementResult,
        val published: LoadedSnapshot?,
    )

    private data class CaptureCommit(
        val result: RuntimeCaptureResult,
        val published: LoadedSnapshot?,
    )

    private sealed interface CaptureAuthorityActivationStage {
        data class Activate(
            val authority: RuntimeCaptureAuthorityState.Authorized,
            val pinnedSiteId: String,
        ) : CaptureAuthorityActivationStage

        data class Terminate(
            val reason: RuntimeCaptureAuthorityTerminalReason,
            val boundary: V1ParsedConfigBoundary?,
            val policySourceHash: String?,
            val contextRevision: Long?,
            val pinnedSiteId: String?,
        ) : CaptureAuthorityActivationStage

        data class RestoreTerminal(
            val authority: RuntimeCaptureAuthorityState.Terminal,
            val pinnedSiteId: String,
        ) : CaptureAuthorityActivationStage
    }

    internal companion object {
        private val NATIVE_QUARANTINED_RESOURCES = mutableListOf<Pair<RuntimeQueueDatabase?, RuntimeOwnershipLease?>>()

        private val OWNERSHIP_KEYS = mutableSetOf<String>()
        private const val MAX_RECONCILIATION_ATTEMPTS = 3
        private const val MAX_ID_GENERATION_ATTEMPTS = 8

        /** The ceiling the persisted state codec enforces on flag-context group types. */
        private const val MAX_FLAG_CONTEXT_GROUP_TYPES = 64

        /** Asynchronously opens an internal runtime on its dedicated storage worker. */
        internal fun open(
            ownershipKey: String,
            limits: RuntimeQueueLimits,
            databaseFactory: () -> RuntimeQueueDatabase,
            legacyStateLoader: () -> PersistedCoreState,
            identifiers: CoreIdentifierGenerator = UuidCoreIdentifierGenerator,
            leaseFactory: () -> RuntimeOwnershipLease = { RuntimeOwnershipLease { } },
            trustedSiteKey: String? = null,
            captureClock: RuntimeCaptureClock = JvmRuntimeCaptureClock,
            readbackProvenReplayTransports: Set<V1ReplayTransport> = emptySet(),
            replayMaskingAdmission: ReplayMaskingAdmission = ReplayMaskingAdmission { _, _ -> false },
            supportedReplayProtocolGenerations: Set<String> = emptySet(),
            startupMigrationCompleter: ((PersistedCoreState) -> PersistedCoreState)? = null,
            assertStartupCurrent: () -> Unit = {},
            startupHistoryLoader: ((PersistedCoreState) -> List<RuntimeStoredRecord>)? = null,
        ): Future<RuntimeQueueOwner> {
            require(ownershipKey.isNotEmpty()) { "ownershipKey must not be empty" }
            lateinit var worker: Thread
            val executor =
                Executors.newSingleThreadExecutor(
                    ThreadFactory { runnable ->
                        Thread(runnable, "elu-runtime-storage").apply {
                            isDaemon = true
                            worker = this
                        }
                    },
                )
            return executor.submit(
                Callable {
                    var claimedOwnership = false
                    var owner: RuntimeQueueOwner? = null
                    try {
                        synchronized(OWNERSHIP_KEYS) {
                            if (!OWNERSHIP_KEYS.add(ownershipKey)) {
                                throw RuntimeQueueOwnershipException(
                                    "A runtime queue owner already holds this installation namespace",
                                )
                            }
                            claimedOwnership = true
                        }
                        owner =
                            RuntimeQueueOwner(
                                ownershipKey,
                                limits,
                                databaseFactory,
                                legacyStateLoader,
                                identifiers,
                                executor,
                                worker,
                                leaseFactory,
                                trustedSiteKey,
                                captureClock,
                                Collections.unmodifiableSet(LinkedHashSet(readbackProvenReplayTransports)),
                                replayMaskingAdmission,
                                Collections.unmodifiableSet(LinkedHashSet(supportedReplayProtocolGenerations)),
                                startupMigrationCompleter,
                                assertStartupCurrent,
                                startupHistoryLoader,
                            )
                        owner.initialize()
                        owner
                    } catch (error: Throwable) {
                        try {
                            owner?.closeResources()
                        } catch (closeError: Throwable) {
                            error.addSuppressed(closeError)
                        } finally {
                            if (claimedOwnership) {
                                synchronized(OWNERSHIP_KEYS) { OWNERSHIP_KEYS.remove(ownershipKey) }
                            }
                            executor.shutdown()
                        }
                        throw error
                    }
                },
            )
        }

        internal fun clearOwnershipForTesting() {
            synchronized(OWNERSHIP_KEYS) { OWNERSHIP_KEYS.clear() }
        }
    }
}

private fun V1FlagProjectionRejection.toFlagRestrictionReason(): FlagRestrictionReason =
    when (this) {
        V1FlagProjectionRejection.MISSING -> FlagRestrictionReason.MISSING
        V1FlagProjectionRejection.MALFORMED -> FlagRestrictionReason.MALFORMED
        V1FlagProjectionRejection.UNSUPPORTED_SCHEMA -> FlagRestrictionReason.UNSUPPORTED_SCHEMA
        V1FlagProjectionRejection.EXPIRED -> FlagRestrictionReason.CONFIG_EXPIRED
        V1FlagProjectionRejection.INACTIVE -> FlagRestrictionReason.DISABLED
        V1FlagProjectionRejection.REVOKED -> FlagRestrictionReason.REVOKED
        V1FlagProjectionRejection.FLAGS_DISABLED -> FlagRestrictionReason.FLAGS_DISABLED
        V1FlagProjectionRejection.UNAUTHORIZED -> FlagRestrictionReason.UNAUTHORIZED
        V1FlagProjectionRejection.STALE -> FlagRestrictionReason.STALE
        V1FlagProjectionRejection.CONFLICT -> FlagRestrictionReason.CONFLICT
        V1FlagProjectionRejection.STORAGE -> FlagRestrictionReason.WALL_ROLLBACK
        V1FlagProjectionRejection.TERMINAL -> FlagRestrictionReason.TERMINAL
    }
