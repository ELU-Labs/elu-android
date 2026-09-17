package dev.elu.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import dev.elu.analytics.internal.facade.AndroidStandaloneStack
import dev.elu.analytics.internal.facade.EluFacadeSink
import java.util.Date

/**
 * The ELU Analytics facade — the mobile analog of the web `window.elu`
 * allowlist. Customer code interacts only with this stable public surface.
 *
 * Every method is safe in every state: before [setup] (idle) and while
 * disabled they no-op; while pending (no usable config yet) event-class calls
 * are buffered in memory and getters return defaults; while running they
 * delegate. Never throws, never blocks the caller.
 *
 * [setup] creates one ELU-owned runtime. Calls share its serialized identity, durable storage,
 * configuration, feature flags and delivery lifecycle.
 */
public object Elu {
    private const val TAG = "EluAnalytics"

    // Never synchronize on `this`: the object is globally reachable, and
    // customer code locking on Elu could contend or deadlock with setup.
    private val setupLock = Any()

    @Volatile private var sink: EluFacadeSink? = null

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
            if (sink != null) {
                Log.w(TAG, "Elu.setup called more than once; ignoring.")
                return
            }
            if (siteKey.isBlank()) {
                Log.w(TAG, "Elu.setup called with a blank siteKey; ignoring.")
                return
            }
            try {
                val appContext = context.applicationContext
                val debuggable =
                    (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val configHost = EluConfigHostPolicy.resolve(options.configHost, debuggable)
                if (configHost == null) {
                    Log.w(
                        TAG,
                        "Elu.setup called with a configHost that is not an approved ELU origin " +
                            "(or a loopback origin in a debuggable app); ignoring.",
                    )
                    return
                }
                val key = siteKey.trim()
                val facade = AndroidStandaloneStack.facade(appContext, key, configHost)
                // Publish before starting so calls made during startup are held rather than lost.
                sink = facade
                facade.start()
            } catch (t: Throwable) {
                Log.w(TAG, "Elu.setup failed: $t")
                sink = null
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
        sink?.capture(event, properties, Date())
    }

    /** Identity is customer-supplied only — ELU never auto-identifies. */
    @JvmStatic
    @JvmOverloads
    public fun identify(
        distinctId: String,
        userProperties: Map<String, Any>? = null,
    ) {
        sink?.identify(distinctId, userProperties)
    }

    /** Feeds `$screen`/`$screen_name` — call manually from Compose navigation. */
    @JvmStatic
    @JvmOverloads
    public fun screen(
        name: String,
        properties: Map<String, Any>? = null,
    ) {
        sink?.screen(name, properties)
    }

    @JvmStatic
    public fun alias(alias: String) {
        sink?.alias(alias)
    }

    @JvmStatic
    public fun reset() {
        sink?.reset()
    }

    @JvmStatic
    @JvmOverloads
    public fun captureException(
        error: Throwable,
        properties: Map<String, Any>? = null,
    ) {
        sink?.captureException(error, properties)
    }

    // ---- properties ----------------------------------------------------------

    /** Super properties: sent with every subsequent event. */
    @JvmStatic
    public fun register(properties: Map<String, Any>) {
        sink?.register(properties)
    }

    @JvmStatic
    public fun unregister(key: String) {
        sink?.unregister(key)
    }

    @JvmStatic
    public fun setPersonProperties(properties: Map<String, Any>) {
        sink?.setPersonProperties(properties)
    }

    @JvmStatic
    @JvmOverloads
    public fun group(
        type: String,
        key: String,
        properties: Map<String, Any>? = null,
    ) {
        sink?.group(type, key, properties)
    }

    @JvmStatic
    public fun distinctId(): String? {
        return sink?.distinctId()
    }

    // ---- feature flags -------------------------------------------------------

    @JvmStatic
    public fun getFeatureFlag(key: String): Any? {
        return sink?.getFeatureFlag(key)
    }

    @JvmStatic
    public fun getFeatureFlagPayload(key: String): Any? {
        return sink?.getFeatureFlagPayload(key)
    }

    @JvmStatic
    public fun isFeatureEnabled(key: String): Boolean {
        return sink?.isFeatureEnabled(key) ?: false
    }

    @JvmStatic
    @JvmOverloads
    public fun reloadFeatureFlags(completion: (() -> Unit)? = null) {
        sink?.reloadFeatureFlags(completion)
    }

    /**
     * Registers a callback fired every time feature flags finish loading; if
     * flags have already loaded this run, it also fires immediately.
     */
    @JvmStatic
    public fun onFeatureFlagsLoaded(callback: () -> Unit) {
        try {
            sink?.onFeatureFlagsLoaded(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "onFeatureFlagsLoaded failed: $t")
        }
    }

    @JvmStatic
    public fun setPersonPropertiesForFlags(properties: Map<String, Any>) {
        sink?.setPersonPropertiesForFlags(properties)
    }

    @JvmStatic
    public fun setGroupPropertiesForFlags(
        type: String,
        properties: Map<String, Any>,
    ) {
        sink?.setGroupPropertiesForFlags(type, properties)
    }

    // ---- transport -----------------------------------------------------------

    @JvmStatic
    public fun flush() {
        sink?.flush()
    }
}
