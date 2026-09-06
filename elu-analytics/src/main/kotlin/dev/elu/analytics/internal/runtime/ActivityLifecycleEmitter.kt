package dev.elu.analytics.internal.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Counts started activities and turns the transitions into application and screen signals.
 *
 * Every entry point is serialized, so it may be driven from the main thread's lifecycle
 * callbacks or from a test without extra synchronization. A configuration change stops and
 * restarts the same logical screen; that pair is folded away rather than reported as a
 * background/foreground round trip and a second screen view.
 */
internal class ActivityLifecycleTracker(
    private val sink: RuntimeLifecycleSink,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private var startedActivities = 0
    private var everForegrounded = false
    private var recreatingActivities = 0

    @Synchronized
    fun activityStarted(screenName: String) {
        val occurredAt = RuntimeWallTimestamps.rfc3339(wallClock())
        startedActivities += 1
        if (recreatingActivities > 0) {
            recreatingActivities -= 1
            return
        }
        if (startedActivities == 1) {
            sink.applicationForegrounded(occurredAt, fromBackground = everForegrounded)
            everForegrounded = true
        }
        sink.screenViewed(screenName, occurredAt)
    }

    @Synchronized
    fun activityStopped(changingConfigurations: Boolean) {
        if (startedActivities == 0) return
        startedActivities -= 1
        if (changingConfigurations) {
            recreatingActivities += 1
            return
        }
        if (startedActivities == 0) {
            sink.applicationBackgrounded(RuntimeWallTimestamps.rfc3339(wallClock()))
        }
    }

    @Synchronized
    fun startedActivityCount(): Int = startedActivities
}

/**
 * Maps [Application.ActivityLifecycleCallbacks] onto an [ActivityLifecycleTracker]. The screen
 * name is the activity's class name, which needs no package-manager lookup and is stable across
 * launches. Nothing registers this adapter yet; [attach] exists for the future composition step.
 */
internal class ActivityLifecycleEmitter(
    private val tracker: ActivityLifecycleTracker,
) : Application.ActivityLifecycleCallbacks {
    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun detach(application: Application) {
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        tracker.activityStarted(screenNameOf(activity))
    }

    override fun onActivityStopped(activity: Activity) {
        tracker.activityStopped(activity.isChangingConfigurations)
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

    internal companion object {
        fun screenNameOf(activity: Activity): String {
            val type = activity.javaClass
            return type.simpleName.ifEmpty { type.name }
        }
    }
}
