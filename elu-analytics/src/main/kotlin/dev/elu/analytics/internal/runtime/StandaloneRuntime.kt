package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluEuGuard
import dev.elu.analytics.EluVersion
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

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
) : AutoCloseable {
    private val transport: BatchHTTPTransport = transportFactory()
    private val deliveryQueue = RuntimeQueueOwnerDeliveryQueue(owner)
    private val deliveryExecutor: ExecutorService = newSingleThreadExecutor("elu-runtime-delivery")
    private val controlExecutor: ExecutorService = newSingleThreadExecutor("elu-runtime-control")
    private val lifecycleAdapter = AndroidBatchDeliveryAdapter(deliveryExecutor) { flush() }
    private val deliveryLock = Any()
    private var coordinator: RuntimeBatchDeliveryCoordinator? = null
    private var closed = false
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
    fun applyConfiguration(configBody: String?): Future<RuntimeCaptureAuthorityUpdateResult> =
        submitControl {
            val parsed = parseConfig(configBody)
            val privacyBody =
                parsed?.takeIf { config -> config.status == V1ConfigStatus.ENABLED }?.let { config ->
                    val identity = owner.snapshot().await().state.identity
                    val state =
                        PrivacyStateProjector.project(
                            PrivacyProjectionInput(
                                policy = checkNotNull(config.privacy),
                                features = checkNotNull(config.features),
                                replayCapabilities = checkNotNull(config.replayCapabilities),
                                identity = identity,
                                deviceInEuTimezone = deviceInEuTimezone(),
                                evaluatedAt = RuntimeWallTimestamps.rfc3339(wallClock()),
                            ),
                        )
                    PrivacyStateProjector.encode(state)
                }
            val result = owner.submitCaptureAuthority(configBody, privacyBody).await()
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
                    )
                }
                is RuntimeCaptureAuthorityUpdateResult.Terminated -> retireDelivery()
            }
            result
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

    fun captureException(
        throwable: Throwable,
        properties: Map<String, Any?> = emptyMap(),
        occurredAt: String = now(),
    ): Future<RuntimeCaptureResult> =
        submitCapture(ExceptionSerializer.command(throwable, occurredAt, versions, properties))

    /** Persists the background transition first, then schedules one bounded delivery pass. */
    fun markBackgrounded(occurredAt: String = now()): Future<RuntimeAppendResult> {
        val result = owner.markBackgrounded(occurredAt)
        lifecycleAdapter.onBackgrounded()
        return result
    }

    fun markForegrounded(): Boolean = lifecycleAdapter.onForegrounded()

    /** Triggers delivery immediately; without an activated authority nothing is sent. */
    fun flush(): CompletableFuture<BatchDeliveryPassResult> {
        val active = synchronized(deliveryLock) { coordinator }
        return active?.trigger()
            ?: CompletableFuture.completedFuture(BatchDeliveryPassResult(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE))
    }

    fun lifecycleSink(): RuntimeLifecycleSink = LifecycleSink()

    fun hasDeliveryAuthorization(): Boolean = synchronized(deliveryLock) { coordinator != null }

    override fun close() {
        synchronized(deliveryLock) {
            if (closed) return
            closed = true
            coordinator?.close()
            coordinator = null
            flushTimer?.cancel()
            flushTimer = null
        }
        controlExecutor.shutdown()
        deliveryExecutor.shutdown()
        scheduler.shutdownNow()
        try {
            owner.closeAsync()
        } catch (_: IllegalStateException) {
            // The owner was already closing; nothing else to release.
        }
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

    private fun installDelivery(authorization: V1BatchAuthorizationSnapshot) {
        val replacement =
            RuntimeBatchDeliveryCoordinator(
                authorization = authorization,
                queue = deliveryQueue,
                transport = transport,
                clock = deliveryClock,
                scheduler = retryScheduler,
                jitter = jitter,
                executor = deliveryExecutor,
            )
        val previous =
            synchronized(deliveryLock) {
                if (closed) {
                    replacement.close()
                    return
                }
                coordinator.also { coordinator = replacement }
            }
        previous?.close()
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
