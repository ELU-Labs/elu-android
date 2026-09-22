package dev.elu.analytics.internal.facade

import dev.elu.analytics.EluFeatureFlagResult
import java.util.Date

/**
 * The runtime-neutral target of every public facade method.
 *
 * `Elu` resolves exactly one implementation at setup and keeps it for the life of the process, so
 * a call reaches one runtime and never both. Method names, argument order, and return types mirror
 * the public surface one-for-one; every method must be safe on any thread and must not block the
 * caller.
 */
internal interface EluFacadeSink {
    fun capture(
        event: String,
        properties: Map<String, Any>?,
        timestamp: Date,
    )

    fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
    )

    fun identify(distinctId: String, userProperties: Map<String, Any>?, userPropertiesOnce: Map<String, Any>?)

    fun screen(
        name: String,
        properties: Map<String, Any>?,
    )

    fun alias(alias: String)

    fun reset()

    fun optOut()

    fun optIn(captureEventName: String?, properties: Map<String, Any>?)

    fun viewPrivacyChanged() = Unit

    fun isOptedOut(): Boolean

    fun captureException(
        error: Throwable,
        properties: Map<String, Any>?,
    )

    fun register(properties: Map<String, Any>)

    fun registerOnce(properties: Map<String, Any>, defaultValue: Any?)

    fun unregister(key: String)

    fun setPersonProperties(properties: Map<String, Any>)

    fun setPersonProperties(properties: Map<String, Any>, propertiesOnce: Map<String, Any>)

    fun getGroups(): Map<String, String>

    fun resetGroups()

    fun group(
        type: String,
        key: String,
        properties: Map<String, Any>?,
    )

    fun distinctId(): String?

    fun getFeatureFlag(key: String): Any?

    fun getFeatureFlagResult(key: String): EluFeatureFlagResult?

    fun getFeatureFlagPayload(key: String): Any?

    fun isFeatureEnabled(key: String): Boolean

    fun reloadFeatureFlags(completion: (() -> Unit)?)

    fun onFeatureFlagsLoaded(callback: () -> Unit)

    fun setPersonPropertiesForFlags(properties: Map<String, Any>)

    fun resetPersonPropertiesForFlags()

    fun resetGroupPropertiesForFlags(type: String?)

    fun setGroupPropertiesForFlags(
        type: String,
        properties: Map<String, Any>,
    )

    fun flush()
}
