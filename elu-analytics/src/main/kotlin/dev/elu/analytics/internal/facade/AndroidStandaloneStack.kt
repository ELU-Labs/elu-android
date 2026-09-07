package dev.elu.analytics.internal.facade

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.content.Context
import dev.elu.analytics.internal.runtime.ActivityLifecycleEmitter
import dev.elu.analytics.internal.runtime.ActivityLifecycleTracker
import dev.elu.analytics.internal.runtime.AndroidRuntimeQueue
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.StandaloneRuntime

/**
 * Assembles the owned runtime for one site key: the durable queue owner, the standalone event
 * runtime over it, and the activity lifecycle emitter that turns foreground, background and screen
 * transitions into events.
 *
 * Two pieces are deliberately absent. The feature-flag client is not constructed, because it takes
 * a transport this package does not ship; flag reads report their documented defaults until it is.
 * And nothing here fetches a configuration document — it reaches the facade through
 * [StandaloneFacade.applyConfiguration], so until a source supplies one the facade holds calls and
 * captures nothing.
 *
 * The runtime keeps its own identity storage and never reads the embedded runtime's: with it
 * selected, a device starts a fresh ELU identity.
 */
internal object AndroidStandaloneStack {
    /** Matches the queue ceilings the runtime is exercised against on device. */
    private val LIMITS = RuntimeQueueLimits(maximumCount = 10_000, maximumBytes = 16_777_216)

    fun facade(
        appContext: Context,
        siteKey: String,
    ): StandaloneFacade {
        val mainThread = Handler(Looper.getMainLooper())
        return StandaloneFacade(
            open = { open(appContext, siteKey) },
            deliverCallback = { callback -> mainThread.post(callback) },
        )
    }

    /** Runs on the facade lane: opening the queue is blocking storage work. */
    private fun open(
        appContext: Context,
        siteKey: String,
    ): StandaloneStack {
        val owner = AndroidRuntimeQueue.open(appContext, siteKey, LIMITS).get()
        val runtime = StandaloneRuntime(owner = owner, siteKey = siteKey)
        (appContext as? Application)?.let { application ->
            ActivityLifecycleEmitter(ActivityLifecycleTracker(runtime.lifecycleSink())).attach(application)
        }
        return StandaloneStack(runtime = runtime, owner = owner, flags = null)
    }
}
