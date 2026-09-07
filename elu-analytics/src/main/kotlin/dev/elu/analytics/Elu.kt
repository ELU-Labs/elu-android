package dev.elu.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.posthog.PostHog
import com.posthog.PostHogOnFeatureFlags
import dev.elu.analytics.internal.facade.EluCoreLifecycleGate
import dev.elu.analytics.internal.facade.EluFacadeSink
import dev.elu.analytics.internal.facade.EmbeddedRuntimeSink
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
 * Methods carry no runtime detail: [setup] resolves one [EluFacadeSink] and every call goes to it.
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
                val core = EluCore(appContext, siteKey.trim(), configHost)
                sink = EmbeddedRuntimeSink(EluCoreLifecycleGate(core), EmbeddedRuntime)
                core.start()
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

/**
 * Every call the facade makes into the embedded analytics runtime, in facade terms. The interface
 * exists so the mapping can be exercised without that runtime, and so one file holds every symbol
 * the embedded dependency contributes to the facade path.
 */
internal interface EmbeddedRuntimeCalls {
    fun capture(
        event: String,
        properties: Map<String, Any>?,
        timestamp: Date,
    )

    fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
    )

    fun screen(
        name: String,
        properties: Map<String, Any>?,
    )

    fun alias(alias: String)

    fun reset()

    fun captureException(
        error: Throwable,
        properties: Map<String, Any>?,
    )

    fun register(properties: Map<String, Any>)

    fun unregister(key: String)

    fun setPersonProperties(properties: Map<String, Any>)

    fun group(
        type: String,
        key: String,
        properties: Map<String, Any>?,
    )

    fun distinctId(): String?

    fun getFeatureFlag(key: String): Any?

    fun getFeatureFlagPayload(key: String): Any?

    fun isFeatureEnabled(key: String): Boolean

    fun reloadFeatureFlags(completion: (() -> Unit)?)

    fun setPersonPropertiesForFlags(properties: Map<String, Any>)

    fun setGroupPropertiesForFlags(
        type: String,
        properties: Map<String, Any>,
    )

    fun flush()
}

/** The embedded runtime as a process-wide singleton, matching the one-instance-per-app web model. */
internal object EmbeddedRuntime : EmbeddedRuntimeCalls {
    override fun capture(
        event: String,
        properties: Map<String, Any>?,
        timestamp: Date,
    ) {
        PostHog.capture(event, properties = properties, timestamp = timestamp)
    }

    override fun identify(
        distinctId: String,
        userProperties: Map<String, Any>?,
    ) {
        PostHog.identify(distinctId, userProperties = userProperties)
    }

    override fun screen(
        name: String,
        properties: Map<String, Any>?,
    ) {
        PostHog.screen(name, properties)
    }

    override fun alias(alias: String) {
        PostHog.alias(alias)
    }

    override fun reset() {
        PostHog.reset()
    }

    override fun captureException(
        error: Throwable,
        properties: Map<String, Any>?,
    ) {
        PostHog.captureException(error, properties)
    }

    /** The native API is per-key. */
    override fun register(properties: Map<String, Any>) {
        properties.forEach { (key, value) -> PostHog.register(key, value) }
    }

    override fun unregister(key: String) {
        PostHog.unregister(key)
    }

    override fun setPersonProperties(properties: Map<String, Any>) {
        PostHog.setPersonProperties(userPropertiesToSet = properties)
    }

    override fun group(
        type: String,
        key: String,
        properties: Map<String, Any>?,
    ) {
        PostHog.group(type, key, properties)
    }

    override fun distinctId(): String? = PostHog.distinctId().ifBlank { null }

    override fun getFeatureFlag(key: String): Any? = PostHog.getFeatureFlag(key)

    // Web-parity method; native marks it deprecated in favor of
    // getFeatureFlagResult, which would send $feature_flag_called.
    @Suppress("DEPRECATION")
    override fun getFeatureFlagPayload(key: String): Any? = PostHog.getFeatureFlagPayload(key)

    override fun isFeatureEnabled(key: String): Boolean = PostHog.isFeatureEnabled(key)

    override fun reloadFeatureFlags(completion: (() -> Unit)?) {
        PostHog.reloadFeatureFlags(completion?.let { callback -> PostHogOnFeatureFlags { callback() } })
    }

    override fun setPersonPropertiesForFlags(properties: Map<String, Any>) {
        PostHog.setPersonPropertiesForFlags(properties)
    }

    override fun setGroupPropertiesForFlags(
        type: String,
        properties: Map<String, Any>,
    ) {
        PostHog.setGroupPropertiesForFlags(type, properties)
    }

    override fun flush() {
        PostHog.flush()
    }
}
