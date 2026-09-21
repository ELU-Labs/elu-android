package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.replay.NativeReplayComposition
import dev.elu.analytics.internal.replay.awaitExact

import dev.elu.analytics.EluEuGuard
import dev.elu.analytics.EluVersion
import dev.elu.analytics.internal.config.V2ConfigAuthorityGate
import dev.elu.analytics.internal.config.V2ConfigAuthorityWitness
import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ConfigStatus
import dev.elu.analytics.internal.config.V1MalformedConfigException
import dev.elu.analytics.internal.config.V1ParsedConfig
import dev.elu.analytics.internal.config.V1UnsupportedConfigSchemaException
import dev.elu.analytics.internal.runtime.delivery.AndroidBatchDeliveryAdapter
import dev.elu.analytics.internal.runtime.delivery.BatchDeliveryClock
import dev.elu.analytics.internal.runtime.delivery.BatchDeliveryPassResult
import dev.elu.analytics.internal.runtime.delivery.BatchDeliveryStop
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPTransport
import dev.elu.analytics.internal.runtime.delivery.BatchJitterSource
import dev.elu.analytics.internal.runtime.delivery.BatchRetryScheduler
import dev.elu.analytics.internal.runtime.delivery.BatchScheduledTask
import dev.elu.analytics.internal.runtime.delivery.HttpURLConnectionBatchTransport
import dev.elu.analytics.internal.runtime.delivery.RuntimeBatchDeliveryCoordinator
import dev.elu.analytics.internal.runtime.delivery.RuntimeQueueOwnerDeliveryQueue
import dev.elu.analytics.internal.runtime.delivery.ScheduledExecutorBatchRetryScheduler
import dev.elu.analytics.internal.runtime.delivery.SystemBatchDeliveryClock
import dev.elu.analytics.internal.runtime.delivery.V1BatchAuthorizationSnapshot
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Private diagnostic candidate only. Closed fields cannot carry customer or config payloads. */
internal enum class RuntimeStartupPhase {
    OPEN_BEGIN, OPEN_READY, OPEN_FAILED, CAPTURE_CALL, BUFFERED, DISPATCHED, DROP,
    LANE_FAILED, AUTHORITY_BEGIN, AUTHORITY_RESULT, FLAGS_RESULT, IDENTITY_READY,
    TRANSITION, CAPTURE_FIRST, CAPTURE_SECOND, PRIVACY_CONSUMED,
}

internal enum class RuntimeStartupLane { PENDING, ENABLED, BLOCKED, OPTED_OUT, UNAUTHORIZED, CLOSED }
internal enum class RuntimeStartupDrop { BLOCKED, OPTED_OUT, UNAUTHORIZED, BUFFER_FULL, CLOSED, INVALID_INPUT, RESERVED_PROPERTY, STORAGE }

internal data class RuntimeStartupObservation(
    val phase: RuntimeStartupPhase,
    val lane: RuntimeStartupLane? = null,
    val buffered: Int? = null,
    val drop: RuntimeStartupDrop? = null,
    val dropCount: Int? = null,
    val configPresent: Boolean? = null,
    val authorityActivated: Boolean? = null,
    val authorityReason: RuntimeCaptureAuthorityTerminalReason? = null,
    val flagsAllowed: Boolean? = null,
    val captureAccepted: Boolean? = null,
    val captureRejection: RuntimeCaptureRejection? = null,
    val deviceInEuTimezone: Boolean? = null,
    val optedOut: Boolean? = null,
    val captureAllowed: Boolean? = null,
)

internal fun interface RuntimeStartupObserver {
    fun observe(observation: RuntimeStartupObservation)
    companion object { val NONE = RuntimeStartupObserver { } }
}

/** Even observation construction failures cannot replace an original SDK result or exception. */
internal fun observeStartup(observer: RuntimeStartupObserver, observation: () -> RuntimeStartupObservation) {
    if (observer === RuntimeStartupObserver.NONE) return
    try { observer.observe(observation()) } catch (_: Throwable) { }
}

/** A fresh per-stack sink, with no worker, clock, callback, or SDK entry point. */
internal class BoundedRuntimeStartupObserver(private val write: (String) -> Unit) : RuntimeStartupObserver {
    private val emitted = java.util.concurrent.atomic.AtomicInteger()
    override fun observe(observation: RuntimeStartupObservation) {
        try {
            // Saturating admission: even a failed write consumes its one diagnostic slot.
            while (true) {
                val count = emitted.get()
                if (count >= 128) return
                if (emitted.compareAndSet(count, count + 1)) break
            }
            with(observation) {
                write("startup-observation-v1 phase=$phase lane=$lane buffered=$buffered drop=$drop dropCount=$dropCount" +
                    " configPresent=$configPresent authorityActivated=$authorityActivated authorityReason=$authorityReason" +
                    " flagsAllowed=$flagsAllowed captureAccepted=$captureAccepted captureRejection=$captureRejection" +
                    " deviceInEuTimezone=$deviceInEuTimezone optedOut=$optedOut captureAllowed=$captureAllowed")
            }
        } catch (_: Throwable) { }
    }
}

/** Millisecond RFC 3339 wall-clock instants in the exact form the queue owner validates. */
internal object RuntimeWallTimestamps {
    fun rfc3339(epochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
    }
}

internal object ThreadLocalRandomBatchJitter : BatchJitterSource {
    override fun nextUnitDouble(): Double = ThreadLocalRandom.current().nextDouble()
}

/** Application-level signals the lifecycle emitters feed into the runtime. */
internal interface RuntimeLifecycleSink {
    fun applicationForegrounded(
        occurredAt: String,
        fromBackground: Boolean,
    )

    fun applicationBackgrounded(occurredAt: String)

    fun screenViewed(
        name: String,
        occurredAt: String,
    )
}

/**
 * Composes the queue owner, privacy projection, capture authority, and batch delivery into one
 * standalone event runtime. Nothing in the public facade constructs this type; it is reachable
 * only from tests until the engine handshake is proven.
 *
 * Capture and lifecycle entry points never block the caller: they submit to the owner lane or a
 * runtime-owned executor and return a future. Delivery runs on its own executor so a durable
 * peek or acknowledgement can wait on the owner without deadlocking it.
 */
internal class StandaloneRuntime(
    private val owner: RuntimeQueueOwner,
    private val siteKey: String,
    private val versions: RuntimeVersions = defaultVersions(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val deliveryClock: BatchDeliveryClock = SystemBatchDeliveryClock,
    /** Owned by the runtime: shut down on [close] even when supplied by the caller. */
    private val scheduler: ScheduledExecutorService = newScheduler(),
    private val retryScheduler: BatchRetryScheduler = ScheduledExecutorBatchRetryScheduler(scheduler),
    private val jitter: BatchJitterSource = ThreadLocalRandomBatchJitter,
    transportFactory: () -> BatchHTTPTransport = { HttpURLConnectionBatchTransport() },
    private val deviceInEuTimezone: () -> Boolean = EluEuGuard::isEuTimezone,
    private val flushDelayMillis: Long = DEFAULT_FLUSH_DELAY_MILLIS,
    private val configurationGate: V2ConfigAuthorityGate? = null,
    private val nativeReplay: NativeReplayComposition? = null,
    private val startupObserver: RuntimeStartupObserver = RuntimeStartupObserver.NONE,
    private val nativeStartTrace: NativeStartTrace = NativeStartTrace.NONE,
) : AutoCloseable {
    private val transport: BatchHTTPTransport = transportFactory()
    private val deliveryQueue = RuntimeQueueOwnerDeliveryQueue(owner)
    private val deliveryExecutor: ExecutorService = newSingleThreadExecutor("elu-runtime-delivery")
    private val controlExecutor: ExecutorService = newSingleThreadExecutor("elu-runtime-control")
    private val lifecycleAdapter = AndroidBatchDeliveryAdapter(deliveryExecutor) { flush() }
    private val deliveryLock = Any()
    private var coordinator: RuntimeBatchDeliveryCoordinator? = null
    private var closed = false
    private val consentRestricted = AtomicBoolean(false)
    private val closeResult = object : SdkFuture<Unit>() {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
    }
    private val flushTimerArmed = AtomicBoolean(false)
    private var flushTimer: BatchScheduledTask? = null

    init {
        require(
            siteKey.length in 1..512 &&
                siteKey.all { character ->
                    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "._~-"
                },
        ) { "siteKey must be a non-empty authorization-header-safe value" }
        require(flushDelayMillis >= 0) { "flushDelayMillis must be non-negative" }
    }

    /**
     * Validates one raw config document, projects the effective privacy state for the current
     * identity witness, and activates or terminates capture authority on the owner lane. An
     * activated authority also installs a delivery coordinator bound to that config's endpoint,
     * expiry, and batch limits; anything else retires delivery.
     */
    fun applyConfiguration(configBody: String?): Future<RuntimeCaptureAuthorityUpdateResult> {
        nativeReplay?.withdrawAll()
        return submitControl {
            val configurationWitness = configurationGate?.snapshotFor(configBody)
            val parsed = parseConfig(configBody)
            val privacyBody =
                parsed?.takeIf { config -> config.status == V1ConfigStatus.ENABLED }?.let { config ->
                    val identity = owner.snapshot().await().state.identity
                    var observedEuTimezone: Boolean? = null
                    val state =
                        PrivacyStateProjector.project(
                            PrivacyProjectionInput(
                                policy = checkNotNull(config.privacy),
                                features = checkNotNull(config.features),
                                replayCapabilities = checkNotNull(config.replayCapabilities),
                                identity = identity,
                                deviceInEuTimezone = deviceInEuTimezone().also { observedEuTimezone = it },
                                evaluatedAt = RuntimeWallTimestamps.rfc3339(wallClock()),
                            ),
                        )
                    observeStartup(startupObserver) { RuntimeStartupObservation(RuntimeStartupPhase.PRIVACY_CONSUMED,
                        deviceInEuTimezone = observedEuTimezone, optedOut = identity.optedOut, captureAllowed = state.captureAllowed) }
                    PrivacyStateProjector.encode(state)
                }
            val result = owner.submitCaptureAuthority(configBody, privacyBody).await()
            observeStartup(startupObserver) { RuntimeStartupObservation(RuntimeStartupPhase.AUTHORITY_RESULT,
                authorityActivated = result is RuntimeCaptureAuthorityUpdateResult.Activated,
                authorityReason = (result as? RuntimeCaptureAuthorityUpdateResult.Terminated)?.authority?.reason) }
            when (result) {
                is RuntimeCaptureAuthorityUpdateResult.Activated -> {
                    val config = checkNotNull(parsed)
                    val limits = checkNotNull(config.limits)
                    installDelivery(
                        V1BatchAuthorizationSnapshot(
                            siteKey = siteKey,
                            eventsEndpoint = URI(checkNotNull(config.endpoints).events),
                            expiresAt = config.expiresAt,
                            eventBatchCount = limits.eventBatchCount,
                            eventBatchBytes = limits.eventBatchBytes,
                        ),
                        configurationWitness,
                    )
                }
                is RuntimeCaptureAuthorityUpdateResult.Terminated -> retireDelivery()
            }
            result
        }
    }

    fun capture(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        occurredAt: String = now(),
    ): Future<RuntimeCaptureResult> =
        submitCapture(
            RuntimeCaptureCommand(
                kind = RuntimeEventKind.CAPTURE,
                name = name,
                occurredAt = occurredAt,
                properties = properties,
                versions = versions,
            ),
        )

    fun screen(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        occurredAt: String = now(),
    ): Future<RuntimeCaptureResult> {
        val merged = LinkedHashMap<String, Any?>(properties)
        merged[SCREEN_NAME_PROPERTY] = name
        return submitCapture(
            RuntimeCaptureCommand(
                kind = RuntimeEventKind.SCREEN,
                name = name,
                occurredAt = occurredAt,
                properties = Collections.unmodifiableMap(merged),
                versions = versions,
            ),
        )
    }

    internal fun capturePerformance(properties: Map<String, Any>, expectation: RuntimeCaptureExpectation): Future<RuntimeCaptureResult> =
        submitCapture(RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "\$performance_sample", now(), properties, versions, expectation))

    fun captureException(
        throwable: Throwable,
        properties: Map<String, Any?> = emptyMap(),
        occurredAt: String = now(),
    ): Future<RuntimeCaptureResult> =
        submitCapture(ExceptionSerializer.command(throwable, occurredAt, versions, properties))

    /** Withdraw and queue the fixed boundary in owner-before-driver order; never wait here. */
    internal fun applicationBackgrounded(
        driver: dev.elu.analytics.internal.config.V2ConfigLifecycleDriver,
        occurredAt: String,
    ): Future<RuntimeAppendResult>? {
        nativeReplay?.withdrawFresh()
        val result = owner.applicationBackgrounded(driver, occurredAt, versions)
        if (result != null) lifecycleAdapter.onBackgrounded()
        return result
    }

    /** Persists the background transition first, then schedules one bounded delivery pass. */
    fun markBackgrounded(occurredAt: String = now()): Future<RuntimeAppendResult> {
        nativeReplay?.withdrawFresh()
        val result = owner.markBackgrounded(occurredAt)
        lifecycleAdapter.onBackgrounded()
        return result
    }

    fun markForegrounded(): Boolean = lifecycleAdapter.onForegrounded()

    /** Triggers delivery immediately; without an activated authority nothing is sent. */
    fun flush(): SdkFuture<BatchDeliveryPassResult> {
        nativeReplay?.flushSealed()
        val active = synchronized(deliveryLock) { coordinator }
        return active?.trigger()
            ?: SdkFuture.completedFuture(BatchDeliveryPassResult(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE))
    }

    fun lifecycleSink(): RuntimeLifecycleSink = LifecycleSink()

    fun hasDeliveryAuthorization(): Boolean = synchronized(deliveryLock) { coordinator != null }

    /** Local fences never supply recording permission; the native owner rechecks actual authority. */
    internal fun withdrawNativeReplay(restrictive: Boolean) {
        if (restrictive) nativeReplay?.withdrawAll() else nativeReplay?.withdrawFresh()
    }

    internal fun reevaluateNativeReplay(force: Boolean = false, originalAcceptance: () -> Boolean) {
        nativeReplay.also { nativeStartTrace.mark(NativeStartPhase.NATIVE_PRESENT, it != null) }?.reevaluate(force, originalAcceptance)
    }

    override fun close() { closeAndWait() }

    /** Retains exact native completion before queue close; no facade/main thread waits here. */
    internal fun closeAndWait(): SdkFuture<Unit> {
        synchronized(deliveryLock) {
            if (closed) return closeResult
            closed = true
            coordinator?.close(); coordinator = null
            flushTimer?.cancel(); flushTimer = null
        }
        nativeReplay?.withdrawAll()
        val originalNativeClose = nativeReplay?.closeAndWait()
        deliveryExecutor.shutdown(); scheduler.shutdownNow()
        try {
            controlExecutor.execute {
                var failure: Throwable? = null
                fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                } }
                attempt { originalNativeClose?.awaitExact() }
                // Attempt close even after a settled native quarantine: queue retains resources,
                // reports uncertainty and terminates its worker without releasing the lease.
                attempt { owner.closeAsync().awaitExact() }
                controlExecutor.shutdown()
                if (failure == null) closeResult.complete(Unit) else closeResult.completeExceptionally(checkNotNull(failure))
            }
        } catch (error: Throwable) { closeResult.completeExceptionally(error) }
        return closeResult
    }

    private fun submitCapture(command: RuntimeCaptureCommand): Future<RuntimeCaptureResult> {
        val result = owner.capture(command)
        armFlushTimer()
        return result
    }

    private fun armFlushTimer() {
        if (!flushTimerArmed.compareAndSet(false, true)) return
        val task =
            try {
                retryScheduler.schedule(
                    flushDelayMillis,
                    Runnable {
                        flushTimerArmed.set(false)
                        flush()
                    },
                )
            } catch (_: Exception) {
                flushTimerArmed.set(false)
                return
            }
        synchronized(deliveryLock) {
            if (closed) {
                task.cancel()
                flushTimerArmed.set(false)
            } else {
                flushTimer = task
            }
        }
    }

    /** Stop scheduling promptly; every composed send also checks its original source witness. */
    internal fun configurationChanged() { nativeReplay?.withdrawAll(); retireDelivery() }

    internal fun restrictForConsent() {
        consentRestricted.set(true)
        nativeReplay?.withdrawAll()
        retireDelivery()
    }

    /** Only the facade calls this after its durable opt-in and latest intent still agree. */
    internal fun restoreConsent() { consentRestricted.set(false) }

    private fun installDelivery(
        authorization: V1BatchAuthorizationSnapshot,
        witness: V2ConfigAuthorityWitness?,
    ) {
        val replacement =
            RuntimeBatchDeliveryCoordinator(
                authorization = authorization,
                queue = deliveryQueue,
                transport = BatchHTTPTransport { request ->
                    if (consentRestricted.get()) throw java.io.IOException("Collection consent withdrawn")
                    if (configurationGate != null && (witness?.isCurrent() != true || !owner.authorizeCurrentDelivery(witness.body, deviceInEuTimezone).await() || !witness.isCurrent())) {
                        throw java.io.IOException("Event configuration or privacy changed before dispatch")
                    }
                    transport.execute(request)
                },
                clock = deliveryClock,
                scheduler = retryScheduler,
                jitter = jitter,
                executor = deliveryExecutor,
            )
        val previous =
            synchronized(deliveryLock) {
                if (closed || consentRestricted.get()) {
                    replacement.close()
                    return
                }
                coordinator.also { coordinator = replacement }
            }
        previous?.close()
        // A foreground flush may have completed while its asynchronous config refresh was
        // unavailable. Wake the retained queue after installation, without requiring a new capture.
        // Each send still rechecks the original source witness, current privacy and expiry.
        if (configurationGate == null || witness?.isCurrent() == true) {
            synchronized(deliveryLock) {
                if (!closed && coordinator === replacement) replacement.trigger()
            }
        }
    }

    private fun retireDelivery() {
        val previous = synchronized(deliveryLock) { coordinator.also { coordinator = null } }
        previous?.close()
    }

    private fun parseConfig(configBody: String?): V1ParsedConfig? =
        try {
            V1ConfigJson.parseConfig(configBody)
        } catch (_: V1MalformedConfigException) {
            null
        } catch (_: V1UnsupportedConfigSchemaException) {
            null
        }

    private fun now(): String = RuntimeWallTimestamps.rfc3339(wallClock())

    private fun <T> submitControl(block: () -> T): Future<T> =
        try {
            controlExecutor.submit(Callable(block))
        } catch (error: RejectedExecutionException) {
            throw IllegalStateException("Standalone runtime is closed", error)
        }

    private fun <T> Future<T>.await(): T =
        try {
            get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }

    private inner class LifecycleSink : RuntimeLifecycleSink {
        override fun applicationForegrounded(
            occurredAt: String,
            fromBackground: Boolean,
        ) {
            capture(APPLICATION_OPENED_EVENT, mapOf(FROM_BACKGROUND_PROPERTY to fromBackground), occurredAt)
            lifecycleAdapter.onForegrounded()
        }

        override fun applicationBackgrounded(occurredAt: String) {
            // The event is ordered before the background transition so the session it lands
            // in is the one being suspended; the same-instant background then wins.
            capture(APPLICATION_BACKGROUNDED_EVENT, emptyMap(), occurredAt)
            markBackgrounded(occurredAt)
        }

        override fun screenViewed(
            name: String,
            occurredAt: String,
        ) {
            screen(name, emptyMap(), occurredAt)
        }
    }

    internal companion object {
        const val DEFAULT_FLUSH_DELAY_MILLIS: Long = 10_000L
        const val SCREEN_NAME_PROPERTY: String = "\$screen_name"
        const val APPLICATION_OPENED_EVENT: String = "Application Opened"
        const val APPLICATION_BACKGROUNDED_EVENT: String = "Application Backgrounded"
        const val FROM_BACKGROUND_PROPERTY: String = "from_background"

        fun defaultVersions(): RuntimeVersions =
            RuntimeVersions(
                platform = RuntimePlatform.ANDROID,
                runtime = RuntimeVersionComponent("elu-android", EluVersion.NAME),
                facade = RuntimeVersionComponent("Elu", EluVersion.FACADE_VERSION.toString()),
            )

        private fun newScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "elu-runtime-scheduler").apply { isDaemon = true }
            }

        private fun newSingleThreadExecutor(name: String): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, name).apply { isDaemon = true }
            }
    }
}

/** Private diagnostic candidate. These records contain no SDK identity or customer values. */
internal enum class NativeStartPhase {
    SINK_READY, LIMIT_REACHED, FACADE_LIFECYCLE, FACADE_EPOCH, FACADE_INTAKE,
    FACADE_NOT_PENDING, FACADE_NOT_CLOSED, RUNTIME_PRESENT, NATIVE_PRESENT,
    COMPOSITION_CREATED, LIFECYCLE_CHANGED, REEVALUATE_REQUEST, ORIGINAL_ACCEPTANCE,
    COMPOSITION_AVAILABLE, EVALUATION_COALESCED, EVALUATION_BEGIN, READY_RETURNED,
    EVALUATION_QUARANTINED, ACCEPTANCE_REFUSED, LOCAL_CURRENT_REFUSED, IDENTITY_RETURNED,
    ACTIVE_REUSED, ATTEMPT_ELIGIBLE, IDENTITY_ELIGIBLE, ROOT_SELECTION_BEGIN, ROOT_SELECTION_RESULT,
    PREPARE_BEGIN, PREPARE_OPEN, PREPARE_INITIAL_GUARD, PREPARE_SELECTION_CURRENT,
    PROJECTION_INPUT_RESULT, PROJECTION_INPUT_CURRENT, PRIVACY_PROJECTION_RESULT,
    PRIVACY_PROJECTION_CURRENT, PREPARATION_RESULT, PREPARATION_VALIDATED,
    PREPARATION_CURRENT, PREPARE_RESULT, PREPARE_POSTCHECK, CAPTURE_OWNER_RESULT,
    CAPTURE_OWNER_ELIGIBLE, CAPTURE_THREAD_ENTERED, ENROLL_BEGIN, ENROLL_RESULT,
    PHYSICAL_USE_RESULT, START_BEGIN, START_RESULT, ADMISSION_BEGIN, ADMISSION_RESULT,
    CAPTURE_LOOP_READY, CAPTURE_FAILED, CAPTURE_PROFILE, EVALUATION_ACTIVE, EVALUATION_FAILED,
}

/** Closed failure-only observations; none of these values carries capture authority. */
internal enum class NativeCaptureStage {
    BEFORE_LOOP, LOOP_CURRENT, ROOT_COLLECT, COLLECTOR, FRAME_APPEND,
    SEAL_PREFIX, SEAL_REQUEST, DURABLE_APPEND, COMMIT_FRAME, WAIT_NEXT,
}
internal enum class NativeCaptureFailureKind {
    NOT_MAIN_THREAD, REENTRANT, WITHDRAWN, INVALID_ROOT, UNSUPPORTED_GEOMETRY,
    UNRESOLVED_BLOCK_RULE, TREE_CHANGED, NODE_LIMIT, DEPTH_LIMIT, PROJECTION_LIMIT, OTHER,
    GEOMETRY_ACTION_BAR_ANCESTOR, GEOMETRY_UNKNOWN_ANCESTOR,
    PASS_CLOCK_REVERSED, PASS_DEADLINE,
}
internal data class NativeCaptureFailureRecord(
    val stage: NativeCaptureStage,
    val frames: Int,
    val failure: NativeCaptureFailureKind,
    val profile: List<Int>? = null,
)

/** The callback is diagnostic only. Even a throwing sink cannot escape a call site. */
internal class NativeStartTrace private constructor(
    private val record: (NativeStartPhase, Boolean?, NativeCaptureFailureRecord?) -> Unit,
) {
    fun mark(phase: NativeStartPhase, result: Boolean? = null) {
        try { record(phase, result, null) } catch (_: Throwable) { }
    }
    fun captureFailed(stage: NativeCaptureStage, frames: Int, failure: NativeCaptureFailureKind) {
        try {
            check(frames >= 0)
            record(NativeStartPhase.CAPTURE_FAILED, null, NativeCaptureFailureRecord(stage, frames, failure))
        } catch (_: Throwable) { }
    }
    fun captureProfile(profile: dev.elu.analytics.internal.replay.NativeCapturePassProfile) {
        try {
            val values = profile.values()
            val maxima = listOf(13, 3, 65535, 65535, 9999, 64, 9999, 999999, 999999, 999999, 16383, 3)
            check(values.size == maxima.size && values.indices.all { values[it] in 0..maxima[it] })
            record(NativeStartPhase.CAPTURE_PROFILE, null, NativeCaptureFailureRecord(
                NativeCaptureStage.COLLECTOR, 0, NativeCaptureFailureKind.OTHER, values.toList()))
        } catch (_: Throwable) { }
    }
    companion object {
        val NONE = NativeStartTrace { _, _, _ -> }
        internal fun create(record: (NativeStartPhase, Boolean?) -> Unit) =
            NativeStartTrace { phase, result, _ -> record(phase, result) }
        internal fun captureAware(record: (NativeStartPhase, Boolean?, NativeCaptureFailureRecord?) -> Unit) =
            NativeStartTrace(record)
    }
}

/** One bounded private stream per stack. No timer, worker, clock, SDK call or flush. */
internal class BoundedNativeStartObserver private constructor(private val append: (ByteArray) -> Unit) {
    private var sequence = 0
    private var evaluation = 0
    private var bytes = 0
    private var disabled = false
    private var writing = false
    val global = NativeStartTrace.captureAware { phase, result, failure -> emit(0, phase, result, failure) }

    @Synchronized fun begin(): NativeStartTrace {
        if (disabled) return NativeStartTrace.NONE
        if (writing) { disabled = true; return NativeStartTrace.NONE }
        return try {
            val original = ++evaluation // Never an SDK/session/owner identity.
            val trace = NativeStartTrace.captureAware { phase, result, failure -> emit(original, phase, result, failure) }
            trace.mark(NativeStartPhase.EVALUATION_BEGIN)
            if (disabled) NativeStartTrace.NONE else trace
        } catch (_: Throwable) { disabled = true; NativeStartTrace.NONE }
    }

    @Synchronized private fun emit(original: Int, phase: NativeStartPhase, result: Boolean?, failure: NativeCaptureFailureRecord?) {
        if (disabled) return
        if (writing) { disabled = true; return }
        try {
            // Reserve the final record for an explicit bound marker. Failed/partial writes
            // consume admission and permanently disable this stream; they are never retried.
            val terminal = sequence == MAXIMUM_RECORDS - 1
            val selected = if (terminal) NativeStartPhase.LIMIT_REACHED else phase
            val selectedEvaluation = if (terminal) 0 else original
            val selectedResult = if (terminal) null else result
            val detail = if (terminal || failure == null) "" else if (failure.profile != null) {
                check(selected == NativeStartPhase.CAPTURE_PROFILE && selectedResult == null && original > 0)
                ",\"p\":[${failure.profile.joinToString(",")}]"
            } else {
                check(selected == NativeStartPhase.CAPTURE_FAILED && selectedResult == null && original > 0 && failure.frames >= 0)
                ",\"stage\":\"${failure.stage}\",\"frames\":${failure.frames},\"failure\":\"${failure.failure}\""
            }
            val raw = ("{\"schemaVersion\":1,\"sequence\":${sequence + 1},\"evaluation\":$selectedEvaluation," +
                "\"phase\":\"$selected\",\"result\":$selectedResult$detail}\n").toByteArray(Charsets.US_ASCII)
            check(raw.size <= MAXIMUM_RECORD_BYTES && bytes <= MAXIMUM_BYTES - raw.size)
            sequence += 1; bytes += raw.size; writing = true
            try { append(raw) } finally { writing = false }
            if (terminal) disabled = true
        } catch (_: Throwable) { writing = false; disabled = true }
    }

    companion object {
        const val MAXIMUM_RECORDS = 1024
        const val MAXIMUM_RECORD_BYTES = 192
        const val MAXIMUM_BYTES = 262144
        val NONE = BoundedNativeStartObserver { }.also { it.disabled = true }
        fun create(append: (ByteArray) -> Unit): BoundedNativeStartObserver = try {
            BoundedNativeStartObserver(append).also { it.global.mark(NativeStartPhase.SINK_READY) }
        } catch (_: Throwable) { NONE }
    }
}

/** Process-lifetime ownership of the one private diagnostic path's uncertain close. */
internal class NativeStartDescriptorOwner {
    // Never probed, replaced, retried or cleared. Original process teardown owns final release.
    private var uncertain: java.io.FileDescriptor? = null

    @Synchronized fun <T> use(
        open: () -> java.io.FileDescriptor,
        body: (java.io.FileDescriptor) -> T,
        close: (java.io.FileDescriptor) -> Unit,
    ): T {
        check(uncertain == null) { "Diagnostic descriptor is unresolved" }
        val original = open()
        var primary: Throwable? = null
        try { return body(original) }
        catch (error: Throwable) { primary = error; throw error }
        finally {
            try { close(original) }
            catch (error: Throwable) {
                uncertain = original
                if (primary == null) throw error
            }
        }
    }
}
