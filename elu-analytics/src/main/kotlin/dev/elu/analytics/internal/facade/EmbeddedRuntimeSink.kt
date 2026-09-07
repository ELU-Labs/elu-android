package dev.elu.analytics.internal.facade

import dev.elu.analytics.EluCore
import dev.elu.analytics.EmbeddedRuntimeCalls
import java.util.Date

/**
 * The lifecycle engine the embedded-runtime lane dispatches through: buffer while pending,
 * delegate while running, no-op while disabled. `EluCore` supplies the production implementation;
 * the narrow interface keeps the method mapping testable without an Android context.
 */
internal interface EluLifecycleGate {
    /** Buffer-class ops: delegate when running, buffer when pending, drop when disabled. */
    fun dispatch(op: () -> Unit)

    /** Command ops that make no sense to replay later. */
    fun dispatchRunningOnly(op: () -> Unit)

    fun <T> read(
        default: T,
        op: () -> T,
    ): T

    fun addOnFeatureFlagsLoaded(callback: () -> Unit)

    /** Re-registers the ELU super properties a runtime reset wipes along with identity. */
    fun registerEluSuperProperties()
}

internal class EluCoreLifecycleGate(private val core: EluCore) : EluLifecycleGate {
    override fun dispatch(op: () -> Unit) = core.dispatch(op)

    override fun dispatchRunningOnly(op: () -> Unit) = core.dispatchRunningOnly(op)

    override fun <T> read(
        default: T,
        op: () -> T,
    ): T = core.read(default, op)

    override fun addOnFeatureFlagsLoaded(callback: () -> Unit) = core.addOnFeatureFlagsLoaded(callback)

    override fun registerEluSuperProperties() = core.registerEluSuperProperties()
}

/**
 * Maps the public surface onto the embedded analytics runtime. This is the published behavior: the
 * lifecycle gate decides whether a call is delegated, buffered, or dropped, and every delegated
 * call reaches [EmbeddedRuntimeCalls] exactly as the 0.1.0 facade did.
 */
internal class EmbeddedRuntimeSink(
    private val gate: EluLifecycleGate,
    private val runtime: EmbeddedRuntimeCalls,
) : EluFacadeSink {
    override fun capture(
        event: String,
        properties: Map<String, Any>?,
        timestamp: Date,
    ) {
        gate.dispatch { runtime.capture(event, properties, timestamp) }
    }

    override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
    ) {
        gate.dispatch { runtime.identify(distinctId, userProperties) }
    }

    override fun screen(
        name: String,
        properties: Map<String, Any>?,
    ) {
        gate.dispatch { runtime.screen(name, properties) }
    }

    override fun alias(alias: String) {
        gate.dispatch { runtime.alias(alias) }
    }

    override fun reset() {
        gate.dispatch {
            runtime.reset()
            // Runtime reset() wipes registered super properties along with identity (prefs clear;
            // ELU keys are not on its except-list) — re-register so post-logout events keep
            // elu_facade_version.
            gate.registerEluSuperProperties()
        }
    }

    override fun captureException(
        error: Throwable,
        properties: Map<String, Any>?,
    ) {
        gate.dispatch { runtime.captureException(error, properties) }
    }

    override fun register(properties: Map<String, Any>) {
        gate.dispatch { runtime.register(properties) }
    }

    override fun unregister(key: String) {
        gate.dispatch { runtime.unregister(key) }
    }

    override fun setPersonProperties(properties: Map<String, Any>) {
        gate.dispatch { runtime.setPersonProperties(properties) }
    }

    override fun group(
        type: String,
        key: String,
        properties: Map<String, Any>?,
    ) {
        gate.dispatch { runtime.group(type, key, properties) }
    }

    override fun distinctId(): String? = gate.read(null) { runtime.distinctId() }

    override fun getFeatureFlag(key: String): Any? = gate.read(null) { runtime.getFeatureFlag(key) }

    override fun getFeatureFlagPayload(key: String): Any? = gate.read(null) { runtime.getFeatureFlagPayload(key) }

    override fun isFeatureEnabled(key: String): Boolean = gate.read(false) { runtime.isFeatureEnabled(key) }

    override fun reloadFeatureFlags(completion: (() -> Unit)?) {
        gate.dispatchRunningOnly { runtime.reloadFeatureFlags(completion) }
    }

    override fun onFeatureFlagsLoaded(callback: () -> Unit) {
        gate.addOnFeatureFlagsLoaded(callback)
    }

    override fun setPersonPropertiesForFlags(properties: Map<String, Any>) {
        gate.dispatch { runtime.setPersonPropertiesForFlags(properties) }
    }

    override fun setGroupPropertiesForFlags(
        type: String,
        properties: Map<String, Any>,
    ) {
        gate.dispatch { runtime.setGroupPropertiesForFlags(type, properties) }
    }

    override fun flush() {
        gate.dispatchRunningOnly { runtime.flush() }
    }
}
