package dev.elu.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.posthog.PostHog
import com.posthog.PostHogOnFeatureFlags
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The CONTRACT.md §2 lifecycle engine: `pending` → `running` / `disabled`.
 * All state transitions happen on a single background executor; the facade
 * only reads the volatile [state] and either delegates, buffers, or no-ops.
 *
 * The analytics runtime is embedded as a process-wide singleton, matching the
 * one-instance-per-app web model.
 */
internal class EluCore(
    private val appContext: Context,
    private val siteKey: String,
    options: EluOptions,
) {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "elu-analytics").apply { isDaemon = true }
        }
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("dev.elu.analytics", Context.MODE_PRIVATE)
    private val configClient = EluConfigClient(appContext.filesDir, siteKey, options.configHost)
    private val buffer = EluEventBuffer()
    private val budget = EluReplayBudget(prefs, executor)
    private val flagListeners = CopyOnWriteArrayList<() -> Unit>()
    private val flagLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchAttempts = AtomicInteger(0)

    @Volatile private var state: EluStatePolicy.State = EluStatePolicy.State.PENDING

    @Volatile private var activeConfig: EluRemoteConfig? = null

    @Volatile private var runtimeInitialized = false

    @Volatile private var replayEnabledAtInit = false

    // The privacy config actually applied at runtime initialization (plus live
    // reductions) — mid-session tighten checks compare against this, not the
    // last fetch, so an ignored loosening can't make a benign restore read as
    // a tighten.
    @Volatile private var appliedPrivacy: EluPrivacyConfig? = null

    @Volatile private var flagsLoadedOnce = false

    @Volatile private var lastFetchOkAt = 0L

    // Executor-confined (bootstrap, retries, and onForeground all run there).
    private var fetchRetry: ScheduledFuture<*>? = null

    // Written once during bootstrap (executor), read-only afterwards.
    @Volatile private var isNewUser = false

    @Volatile private var deviceInEu = false

    fun start() {
        registerForegroundWatcher()
        executor.execute { runSafe { bootstrap() } }
    }

    // ---- facade entry points -------------------------------------------------

    /** Buffer-class ops: delegate when running, buffer when pending, drop when disabled. */
    fun dispatch(op: () -> Unit) {
        when (state) {
            EluStatePolicy.State.RUNNING -> runSafe(op)
            EluStatePolicy.State.PENDING -> {
                buffer.add(op)
                // Heal the pending→running race: an op enqueued after the drain
                // would otherwise sit forever.
                if (state == EluStatePolicy.State.RUNNING) executor.execute { flushBuffer() }
                // Heal the pending→disabled race the same way: an op landing
                // after the disable-time clear() must not survive to be sent
                // by a later re-activation (EU-block guarantee).
                if (state == EluStatePolicy.State.DISABLED) buffer.clear()
            }
            EluStatePolicy.State.DISABLED -> Unit
        }
    }

    /** Command ops that make no sense to replay later (flush, reloadFeatureFlags). */
    fun dispatchRunningOnly(op: () -> Unit) {
        if (state == EluStatePolicy.State.RUNNING) runSafe(op)
    }

    fun <T> read(
        default: T,
        op: () -> T,
    ): T {
        if (state != EluStatePolicy.State.RUNNING) return default
        return try {
            op()
        } catch (t: Throwable) {
            Log.w(TAG, "elu read failed: $t")
            default
        }
    }

    fun addOnFeatureFlagsLoaded(callback: () -> Unit) {
        // add-vs-fire under one lock: either the listener made this load's
        // snapshot (no immediate replay) or it missed it (replay once) — never
        // both. Later loads legitimately re-fire everyone (web parity).
        val loadedAlready =
            synchronized(flagLock) {
                flagListeners.add(callback)
                flagsLoadedOnce
            }
        if (loadedAlready) mainHandler.post { runSafe(callback) }
    }

    // ---- lifecycle -----------------------------------------------------------

    private fun bootstrap() {
        // First-launch marker BEFORE anything else — it is the replayNewUsersOnly
        // probe: marker-absent-at-setup ⇒ "new user" (device-scoped).
        val markerAbsent = !prefs.contains(KEY_FIRST_LAUNCH)
        if (markerAbsent) prefs.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
        isNewUser = markerAbsent

        deviceInEu = EluEuGuard.isEuTimezone()

        val cached = configClient.loadCached()
        if (cached != null) {
            activeConfig = cached
            if (cached.enabled && !(deviceInEu && cached.privacy.blockEu)) {
                initializeRuntime(cached)
            } else {
                // Keep fetching on the normal cadence so re-activation recovers.
                state = EluStatePolicy.State.DISABLED
                buffer.clear()
            }
        }
        // No cache (first launch ever): stay PENDING and buffer. While the EU
        // decision is unresolved nothing leaves the device — the runtime is not
        // initialized until a config (or cached default) says so.

        runFetch()
    }

    private fun runFetch() {
        fetchRetry = null
        val fresh = configClient.fetch()
        if (fresh != null) {
            fetchAttempts.set(0)
            lastFetchOkAt = System.currentTimeMillis()
            applyFreshConfig(fresh)
            return
        }
        // Exponential backoff, max 3 tries per foreground window: 0s, 2s, 8s.
        val attempt = fetchAttempts.incrementAndGet()
        if (attempt < MAX_FETCH_TRIES) {
            val delayMs = 2000L shl (2 * (attempt - 1)) // 2s, 8s
            fetchRetry = executor.schedule({ runSafe { runFetch() } }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun applyFreshConfig(fresh: EluRemoteConfig) {
        activeConfig = fresh
        val euBlocked = deviceInEu && fresh.privacy.blockEu
        when (EluStatePolicy.actionFor(state, fresh.enabled, euBlocked, runtimeInitialized)) {
            EluStatePolicy.Action.INITIALIZE -> {
                if (state == EluStatePolicy.State.DISABLED) {
                    // Re-activation before the runtime initialized — treat as
                    // config-arrival in pending. If the runtime was initialized
                    // (kill switch opted us out), loosening applies next launch,
                    // where initializeRuntime clears the persisted opt-out.
                    // Belt-and-braces: nothing buffered during DISABLED may
                    // ride the re-activation drain.
                    buffer.clear()
                }
                initializeRuntime(fresh)
            }
            EluStatePolicy.Action.DISABLE -> {
                state = EluStatePolicy.State.DISABLED
                buffer.clear()
            }
            EluStatePolicy.Action.APPLY_RUNNING -> applyMidSession(fresh, euBlocked)
            EluStatePolicy.Action.NO_OP -> Unit
        }
    }

    /** CONTRACT.md §2 table: tightening applies immediately, loosening next launch. */
    private fun applyMidSession(
        fresh: EluRemoteConfig,
        euBlocked: Boolean,
    ) {
        if (!fresh.enabled || euBlocked) {
            stopReplayForRun()
            try {
                PostHog.optOut()
            } catch (t: Throwable) {
                Log.w(TAG, "elu optOut failed: $t")
            }
            state = EluStatePolicy.State.DISABLED
            buffer.clear()
            return
        }

        val p = appliedPrivacy ?: EluPrivacyConfig.DEFAULTS
        val f = fresh.privacy

        val maskingTightened =
            (!p.maskTextInputs && f.maskTextInputs) ||
                (!p.maskAllText && f.maskAllText) ||
                (!p.maskImages && f.maskImages)
        if (maskingTightened) {
            // Cannot re-mask live frames; correct masking applies next launch.
            stopReplayForRun()
        }

        if (f.replayNewUsersOnly && !p.replayNewUsersOnly && !isNewUser) {
            stopReplayForRun()
        }

        // Budget: apply now only when REDUCED (0 = unlimited); increases are a
        // loosening and wait for the next launch.
        val reduced = f.replayMaxMinutes != 0 && (p.replayMaxMinutes == 0 || f.replayMaxMinutes < p.replayMaxMinutes)
        if (reduced && replayEnabledAtInit) {
            budget.maxMinutes = f.replayMaxMinutes
            budget.check()
            // The reduction is now in force — fold it into the applied snapshot
            // so a later fetch is compared against the live budget.
            appliedPrivacy = p.copy(replayMaxMinutes = f.replayMaxMinutes)
        }
    }

    private fun stopReplayForRun() {
        budget.veto()
        try {
            PostHog.stopSessionReplay()
        } catch (t: Throwable) {
            Log.w(TAG, "elu stopSessionReplay failed: $t")
        }
    }

    private fun initializeRuntime(cfg: EluRemoteConfig) {
        val token = cfg.publicToken ?: return
        val host = cfg.host ?: return
        val privacy = cfg.privacy

        // "New" is device-scoped and decided by THIS setup call's marker probe.
        val replayAllowed = !(privacy.replayNewUsersOnly && !isNewUser)

        val runtimeConfig =
            PostHogAndroidConfig(apiKey = token, host = host).apply {
                // The SDK — not the customer — owns this object (CONTRACT.md §4).
                captureApplicationLifecycleEvents = true
                captureScreenViews = true
                captureDeepLinks = true
                surveys = false
                capturePushNotificationSubscriptions = false
                capturePushNotificationOpened = false
                // Replay on/off + sampling stay governed server-side by the
                // managed project's remote settings; this flag only arms the SDK.
                sessionReplay = replayAllowed
                // LOAD-BEARING: ELU's render/analysis pipeline consumes the
                // screenshot wireframe format; view-hierarchy mode is NOT
                // supported downstream.
                sessionReplayConfig.screenshot = true
                // 3.58.0 has a single text knob; in screenshot mode it masks ALL
                // text views, not just inputs — see CONTRACT.md.
                sessionReplayConfig.maskAllTextInputs = privacy.maskTextInputs || privacy.maskAllText
                sessionReplayConfig.maskAllImages = privacy.maskImages
                // Runtime defaults captureLogcat to true, and logcat routinely
                // echoes the same user text maskAllText exists to hide — mirror
                // the web loader's enable_recording_console_log gate.
                sessionReplayConfig.captureLogcat = !privacy.maskAllText
                onFeatureFlags = PostHogOnFeatureFlags { onFlagsLoaded() }
            }

        PostHogAndroid.setup(appContext, runtimeConfig)
        runtimeInitialized = true
        replayEnabledAtInit = replayAllowed
        appliedPrivacy = privacy

        // A prior ELU kill-switch run (enabled false / EU tighten) called
        // optOut(), which persists and hydrates on every later launch
        // — without this, re-activation would leave the device dark forever.
        // This is the ELU kill-switch path CONTRACT.md §4 carves out.
        try {
            if (PostHog.isOptOut()) PostHog.optIn()
        } catch (t: Throwable) {
            Log.w(TAG, "elu optIn failed: $t")
        }

        registerEluSuperProperties()

        // Drain BEFORE flipping to RUNNING so inline delegation (which runs on
        // the caller thread once state is RUNNING) cannot overtake older
        // buffered ops; the queued tail flush catches ops buffered during this
        // drain. Residual: an op dispatched inline in the instant after the
        // flip can still precede the executor tail — accepted, ~single-op
        // window, and buffered captures carry their call-time timestamps.
        flushBuffer()
        state = EluStatePolicy.State.RUNNING
        executor.execute { flushBuffer() }

        budget.maxMinutes = privacy.replayMaxMinutes
        if (replayAllowed && privacy.replayMaxMinutes > 0) budget.check()
    }

    private fun flushBuffer() {
        buffer.drain().forEach { runSafe(it) }
    }

    /**
     * Runtime `reset()` clears registered super properties along with
     * identity, so both init and every reset path must (re-)register these —
     * the backend depends on `elu_facade_version` for the fleet version
     * histogram (CONTRACT.md §5).
     */
    internal fun registerEluSuperProperties() {
        PostHog.register("elu_sdk", "android")
        PostHog.register("elu_sdk_version", EluVersion.NAME)
        PostHog.register("elu_facade_version", EluVersion.FACADE_VERSION)
    }

    private fun onFlagsLoaded() {
        val listeners =
            synchronized(flagLock) {
                flagsLoadedOnce = true
                flagListeners.toList()
            }
        // The runtime invokes this on its flag-loading network thread; customer
        // callbacks commonly touch views, so deliver on main (iOS parity).
        mainHandler.post { listeners.forEach { runSafe(it) } }
    }

    // ---- foreground watching -------------------------------------------------

    // Application.ActivityLifecycleCallbacks instead of androidx
    // lifecycle-process: same 0→1 started-activities signal, no extra
    // dependency. Requires an Application context (documented in README).
    private fun registerForegroundWatcher() {
        val app = appContext as? Application
        if (app == null) {
            Log.w(TAG, "Elu.setup: context is not an Application; foreground config refresh disabled.")
            return
        }
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                // Main-thread confined (activity callbacks).
                private var startedCount = 0

                override fun onActivityStarted(activity: Activity) {
                    if (startedCount++ == 0) executor.execute { runSafe { onForeground() } }
                }

                override fun onActivityStopped(activity: Activity) {
                    if (startedCount > 0) startedCount--
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun onForeground() {
        // A backoff retry still pending keeps its own chain + attempt budget —
        // never stack a second chain on top of it (cold start with a failed
        // first fetch would otherwise double-fetch).
        if (fetchRetry == null && System.currentTimeMillis() - lastFetchOkAt > FETCH_TTL_MS) {
            fetchAttempts.set(0)
            runFetch()
        }
        // Session may have rotated (or resumed) while backgrounded.
        if (state == EluStatePolicy.State.RUNNING && replayEnabledAtInit) budget.check()
    }

    private fun runSafe(op: () -> Unit) {
        try {
            op()
        } catch (t: Throwable) {
            Log.w(TAG, "elu op failed: $t")
        }
    }

    private companion object {
        const val TAG = "EluAnalytics"
        const val KEY_FIRST_LAUNCH = "elu.firstLaunchAt"
        const val MAX_FETCH_TRIES = 3
        const val FETCH_TTL_MS = 15 * 60_000L
    }
}
