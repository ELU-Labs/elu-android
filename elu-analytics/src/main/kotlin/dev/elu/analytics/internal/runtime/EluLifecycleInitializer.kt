package dev.elu.analytics.internal.runtime

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** Framework-only bootstrap. It observes lifecycle facts and never starts an analytics runtime. */
internal class EluLifecycleInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let(AndroidProcessLifecycle::install)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}

internal object AndroidProcessLifecycle {
    val observed = ProcessActivityLifecycle()
    val nativeObserved = dev.elu.analytics.internal.replay.NativeReplayLifecycle()
    private var installed = false

    @Synchronized fun install(application: Application) {
        if (installed) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) =
                observed.activityStarted(activity, ActivityLifecycleEmitter.screenNameOf(activity))
            override fun onActivityResumed(activity: Activity) {
                // A resume is also direct proof of a started Activity if initialization was late.
                nativeObserved.resumed(activity)
                observed.activityResumed(activity, ActivityLifecycleEmitter.screenNameOf(activity))
            }
            override fun onActivityStopped(activity: Activity) {
                nativeObserved.withdrawing(activity)
                observed.activityStopped(activity, activity.isChangingConfigurations)
            }
            override fun onActivityDestroyed(activity: Activity) {
                nativeObserved.withdrawing(activity)
                observed.activityStopped(activity, false)
            }
            override fun onActivityPrePaused(activity: Activity) = nativeObserved.withdrawing(activity)
            override fun onActivityPreStopped(activity: Activity) = nativeObserved.withdrawing(activity)
            override fun onActivityPreDestroyed(activity: Activity) = nativeObserved.withdrawing(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityPaused(activity: Activity) = nativeObserved.withdrawing(activity)
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        installed = true
    }
}
