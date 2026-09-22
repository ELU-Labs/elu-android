package dev.elu.analytics.internal.facade

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import dev.elu.analytics.internal.config.V1ReplayCompression
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.config.AndroidV2ConfigClock
import dev.elu.analytics.internal.config.V2ConfigAuthorityGate
import dev.elu.analytics.internal.config.V2ConfigLifecycleDriver
import dev.elu.analytics.internal.config.V2ConfigSource
import dev.elu.analytics.internal.flags.AndroidFeatureFlagClient
import dev.elu.analytics.internal.flags.FlagClock
import dev.elu.analytics.internal.flags.FlagOpaqueIdSource
import dev.elu.analytics.internal.flags.V2ConfigBoundFlagTransport
import dev.elu.analytics.EluEuGuard
import dev.elu.analytics.internal.replay.NativeReplayCapabilities
import dev.elu.analytics.internal.replay.NativeReplayComposition
import dev.elu.analytics.internal.core.SystemCoreEpochClock
import dev.elu.analytics.internal.runtime.AndroidProcessLifecycle
import dev.elu.analytics.internal.runtime.StandaloneLifecycleBinding
import dev.elu.analytics.internal.runtime.AndroidRuntimeQueue
import dev.elu.analytics.internal.runtime.RuntimeLifecycleSink
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.StandaloneRuntime
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns the runtime and resources behind the public Elu facade. */
internal object AndroidStandaloneStack {
    private val LIMITS = RuntimeQueueLimits(maximumCount = 10_000, maximumBytes = 16_777_216)

    fun facade(appContext: Context, siteKey: String, configHost: String = "https://elu.dev",
        performanceOptions: dev.elu.analytics.EluPerformanceOptions = dev.elu.analytics.EluPerformanceOptions()): StandaloneFacade {
        // Capture fresh identity chronology before Elu.setup can publish this facade.
        val freshIdentityStartedAt = SystemCoreEpochClock.nowEpochMillis()
        val mainThread = Handler(Looper.getMainLooper())
        val application = appContext as? Application
        val gate = V2ConfigAuthorityGate()
        val flagTransport = V2ConfigBoundFlagTransport(siteKey, gate)
        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val source = V2ConfigSource(configHost, siteKey, debuggable = debuggable)
        val runtimeRef = AtomicReference<StandaloneRuntime?>()
        val performanceRef = AtomicReference<dev.elu.analytics.internal.performance.AndroidPerformanceMonitor?>()
        val closing = AtomicBoolean(false)
        val notifications = Executors.newSingleThreadExecutor { task ->
            Thread(task, "elu-config-application").apply { isDaemon = true }
        }
        lateinit var facade: StandaloneFacade
        lateinit var lifecycle: StandaloneLifecycleBinding
        val driver = V2ConfigLifecycleDriver(source, listener = { token ->
            // Only the source token is published under the lifecycle lock. Storage, cancellation
            // and facade work run afterward; every consumer checks the token again at use.
            gate.update(token)
            try {
                notifications.execute {
                    flagTransport.retireSuperseded()
                    runtimeRef.get()?.configurationChanged()
                    facade.configurationChanged()
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { gate.close() }
        })
        facade = StandaloneFacade(
            open = {
                check(!closing.get()) { "Standalone stack is closed" }
                // One explicit private component capability selection reaches both original owners.
                val nativeReplayTransports = setOf(V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP))
                val nativeReplayGenerations = setOf("protocol-generation-v1")
                val owner = AndroidRuntimeQueue.open(appContext, siteKey, LIMITS, freshIdentityStartedAt,
                    readbackProvenReplayTransports = nativeReplayTransports,
                    supportedReplayProtocolGenerations = nativeReplayGenerations,
                    assertStartupCurrent = { check(!closing.get()) { "Standalone stack is closed" } }).get()
                var native: NativeReplayComposition? = null
                try {
                    owner.bindConfigurationGate(gate).get()
                    native = NativeReplayComposition(owner, AndroidProcessLifecycle.nativeObserved,
                        NativeReplayCapabilities(
                            transports = nativeReplayTransports,
                            generations = nativeReplayGenerations,
                        ), StandaloneRuntime.defaultVersions(), EluEuGuard::isEuTimezone,
                        facade::nativeReplayIntakeAllowed)
                    // Preparation concerns storage only and must finish before runtime publication.
                    // Private component capability; current source, privacy, session and physical guards still apply.
                    native.ready().get()
                    val runtime = StandaloneRuntime(owner = owner, siteKey = siteKey, configurationGate = gate, nativeReplay = native)
                    runtimeRef.set(runtime)
                    val flags = AndroidFeatureFlagClient(
                        owner, StandaloneRuntime.defaultVersions(), flagTransport,
                        object : FlagClock {
                            override fun wallNowEpochMillis() = AndroidV2ConfigClock.wallNowEpochMillis()
                            override fun monotonicNowNanos() = AndroidV2ConfigClock.monotonicNowNanos()
                        },
                        FlagOpaqueIdSource { UUID.randomUUID().toString() },
                        FlagOpaqueIdSource { UUID.randomUUID().toString() },
                        configurationGate = gate,
                        collectionAllowed = { !facade.isOptedOut() },
                    )
                    if (closing.get()) { flags.close(); runtime.close(); error("Standalone stack is closed") }
                    StandaloneStack(runtime, owner, flags)
                } catch (error: Throwable) {
                    closing.set(true)
                    lifecycle.close()
                    driver.close()
                    gate.close()
                    flagTransport.close()
                    notifications.shutdownNow()
                    val runtime = runtimeRef.getAndSet(null)
                    if (runtime != null) runtime.closeAndWait()
                    else {
                        val originalNative = native
                        if (originalNative != null) originalNative.closeAndWait().whenComplete { _, _ -> runCatching { owner.closeAsync() } }
                        else runCatching { owner.closeAsync() }
                    }
                    throw error
                }
            },
            deliverCallback = { callback -> mainThread.post(callback) },
            configurationGate = gate,
            onOpened = {
                if (performanceOptions.enabled && !closing.get()) {
                    val monitor = dev.elu.analytics.internal.performance.AndroidPerformanceMonitor(
                        performanceOptions, mainThread, facade::performanceContext, facade::capturePerformance)
                    performanceRef.set(monitor)
                    if (closing.get()) performanceRef.getAndSet(null)?.close()
                }
                lifecycle.ready()
            },
            onCloseRequested = {
                closing.set(true)
                performanceRef.getAndSet(null)?.close()
                runtimeRef.get()?.withdrawNativeReplay(restrictive = true)
                driver.close()
                gate.close()
                lifecycle.close()
                flagTransport.close()
                notifications.shutdownNow()
            },
        )
        // The manifest initializer normally installs before the first Activity. If customers
        // remove it, installing here can observe future starts/resumes but cannot invent past ones.
        application?.let(AndroidProcessLifecycle::install)
        lifecycle = StandaloneLifecycleBinding(AndroidProcessLifecycle.observed, object : RuntimeLifecycleSink {
            override fun applicationForegrounded(occurredAt: String, fromBackground: Boolean) {
                facade.nativeReplayLifecycleChanged(true)
                performanceRef.get()?.foreground(true)
                driver.onForeground()
                runtimeRef.get()?.markForegrounded()
                facade.capture(StandaloneRuntime.APPLICATION_OPENED_EVENT,
                    mapOf(StandaloneRuntime.FROM_BACKGROUND_PROPERTY to fromBackground),
                    Date(java.time.Instant.parse(occurredAt).toEpochMilli()))
            }
            override fun applicationBackgrounded(occurredAt: String) {
                // Revoke synchronously before any queued storage or customer callback can run.
                facade.nativeReplayLifecycleChanged(false)
                performanceRef.get()?.foreground(false)
                val runtime = runtimeRef.get()
                if (runtime == null) driver.onBackground()
                else runtime.applicationBackgrounded(driver, occurredAt)
            }
            override fun screenViewed(name: String, occurredAt: String) { facade.screen(name, null) }
        })
        return facade
    }
}
