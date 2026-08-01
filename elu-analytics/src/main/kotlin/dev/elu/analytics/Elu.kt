package dev.elu.analytics

import android.content.Context
import android.util.Log
import com.posthog.PostHog
import com.posthog.PostHogOnFeatureFlags
import java.util.Date

/**
 * The ELU Analytics facade — the mobile analog of the web `window.elu`
 * allowlist. Customer code never touches `posthog.*`; a future upstream swap
 * changes nothing here.
 *
 * Every method is safe in every state: before [setup] (idle) and while
 * disabled they no-op; while pending (no usable config yet) event-class calls
 * are buffered in memory and getters return defaults; while running they
 * delegate. Never throws, never blocks the caller.
 */
public object Elu {
    private const val TAG = "EluAnalytics"

    // Never synchronize on `this`: the object is globally reachable, and
    // customer code locking on Elu could contend or deadlock with setup.
    private val setupLock = Any()

    @Volatile private var core: EluCore? = null

    /**
     * Initializes the SDK with the ELU site key. Call once from
     * `Application.onCreate` — an Application context is required for
     * foreground config refresh and lifecycle/screen autocapture.
     * Idempotent: a second call warns and is ignored.
     */
    @JvmStatic
    @JvmOverloads
    public fun setup(
        context: Context,
        siteKey: String,
        options: EluOptions = EluOptions(),
    ) {
        synchronized(setupLock) {
            if (core != null) {
                Log.w(TAG, "Elu.setup called more than once; ignoring.")
                return
            }
            if (siteKey.isBlank()) {
                Log.w(TAG, "Elu.setup called with a blank siteKey; ignoring.")
                return
            }
            try {
                val c = EluCore(context.applicationContext, siteKey.trim(), options)
                core = c
                c.start()
            } catch (t: Throwable) {
                Log.w(TAG, "Elu.setup failed: $t")
                core = null
            }
        }
    }

    // ---- events --------------------------------------------------------------

    @JvmStatic
    @JvmOverloads
    public fun capture(
        event: String,
        properties: Map<String, Any>? = null,
    ) {
        // Call-time timestamp so ops buffered while `pending` (first launch,
        // config not yet arrived) are not re-stamped seconds late at drain.
        val timestamp = Date()
        core?.dispatch { PostHog.capture(event, properties = properties, timestamp = timestamp) }
    }

    /** Identity is customer-supplied only — ELU never auto-identifies. */
    @JvmStatic
    @JvmOverloads
    public fun identify(
        distinctId: String,
        userProperties: Map<String, Any>? = null,
    ) {
        core?.dispatch { PostHog.identify(distinctId, userProperties = userProperties) }
    }

    /** Feeds `$screen`/`$screen_name` — call manually from Compose navigation. */
    @JvmStatic
    @JvmOverloads
    public fun screen(
        name: String,
        properties: Map<String, Any>? = null,
    ) {
        core?.dispatch { PostHog.screen(name, properties) }
    }

    @JvmStatic
    public fun alias(alias: String) {
        core?.dispatch { PostHog.alias(alias) }
    }

    @JvmStatic
    public fun reset() {
        val c = core ?: return
        c.dispatch {
            PostHog.reset()
            // Upstream reset() wipes registered super properties along with
            // identity (prefs clear; ELU keys are not on its except-list) —
            // re-register so post-logout events keep elu_facade_version.
            c.registerEluSuperProperties()
        }
    }

    @JvmStatic
    @JvmOverloads
    public fun captureException(
        error: Throwable,
        properties: Map<String, Any>? = null,
    ) {
        core?.dispatch { PostHog.captureException(error, properties) }
    }

    // ---- properties ----------------------------------------------------------

    /** Super properties: sent with every subsequent event. Native API is per-key. */
    @JvmStatic
    public fun register(properties: Map<String, Any>) {
        core?.dispatch { properties.forEach { (k, v) -> PostHog.register(k, v) } }
    }

    @JvmStatic
    public fun unregister(key: String) {
        core?.dispatch { PostHog.unregister(key) }
    }

    @JvmStatic
    public fun setPersonProperties(properties: Map<String, Any>) {
        core?.dispatch { PostHog.setPersonProperties(userPropertiesToSet = properties) }
    }

    @JvmStatic
    @JvmOverloads
    public fun group(
        type: String,
        key: String,
        properties: Map<String, Any>? = null,
    ) {
        core?.dispatch { PostHog.group(type, key, properties) }
    }

    @JvmStatic
    public fun distinctId(): String? {
        return core?.read(null) { PostHog.distinctId().ifBlank { null } }
    }

    // ---- feature flags -------------------------------------------------------

    @JvmStatic
    public fun getFeatureFlag(key: String): Any? {
        return core?.read(null) { PostHog.getFeatureFlag(key) }
    }

    // Web-parity method; native marks it deprecated in favor of
    // getFeatureFlagResult, which would send $feature_flag_called.
    @JvmStatic
    @Suppress("DEPRECATION")
    public fun getFeatureFlagPayload(key: String): Any? {
        return core?.read(null) { PostHog.getFeatureFlagPayload(key) }
    }

    @JvmStatic
    public fun isFeatureEnabled(key: String): Boolean {
        return core?.read(false) { PostHog.isFeatureEnabled(key) } ?: false
    }

    @JvmStatic
    @JvmOverloads
    public fun reloadFeatureFlags(completion: (() -> Unit)? = null) {
        core?.dispatchRunningOnly {
            PostHog.reloadFeatureFlags(completion?.let { cb -> PostHogOnFeatureFlags { cb() } })
        }
    }

    /**
     * Registers a callback fired every time feature flags finish loading; if
     * flags have already loaded this run, it also fires immediately.
     */
    @JvmStatic
    public fun onFeatureFlagsLoaded(callback: () -> Unit) {
        try {
            core?.addOnFeatureFlagsLoaded(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "onFeatureFlagsLoaded failed: $t")
        }
    }

    @JvmStatic
    public fun setPersonPropertiesForFlags(properties: Map<String, Any>) {
        core?.dispatch { PostHog.setPersonPropertiesForFlags(properties) }
    }

    @JvmStatic
    public fun setGroupPropertiesForFlags(
        type: String,
        properties: Map<String, Any>,
    ) {
        core?.dispatch { PostHog.setGroupPropertiesForFlags(type, properties) }
    }

    // ---- transport -----------------------------------------------------------

    @JvmStatic
    public fun flush() {
        core?.dispatchRunningOnly { PostHog.flush() }
    }
}
