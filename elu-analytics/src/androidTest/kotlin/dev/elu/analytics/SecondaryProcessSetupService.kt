package dev.elu.analytics

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import dev.elu.analytics.internal.runtime.AndroidDefaultProcess
import dev.elu.analytics.internal.runtime.AndroidProcessLifecycle
import dev.elu.analytics.internal.runtime.AndroidRuntimeQueue
import dev.elu.analytics.internal.runtime.EluLifecycleInitializer

/**
 * Test-only component declared with its own `android:process`. It calls setup the way a
 * multi-process app's `Application.onCreate` would, then reports what the SDK did there.
 */
class SecondaryProcessSetupService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("elu-secondary-process-probe").apply { start() }
        messenger = Messenger(Handler(worker.looper) { message -> handle(message); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handle(message: Message) {
        if (message.what != SETUP) return
        val replyTo = message.replyTo ?: return
        val siteKey = message.data.getString(SITE_KEY) ?: return
        val application = application
        // Loopback config keeps a regression from reaching the network; setup itself is the subject.
        val options = EluOptions(configHost = LOOPBACK_CONFIG_HOST)
        Elu.setup(application, siteKey, options)
        Elu.setup(application, siteKey, options)
        Elu.capture("secondary_process_event")
        Elu.flush()
        // The manifest provider is never created here; drive its startup path and the
        // fallback install directly so both gates are exercised in a real secondary process.
        EluLifecycleInitializer().attachInfo(application, null)
        AndroidProcessLifecycle.install(application)
        // A setup that was not ignored opens its runtime asynchronously well within this window.
        SystemClock.sleep(SETTLE_MILLIS)
        val result = Bundle().apply {
            putInt(PID, Process.myPid())
            putString(ROLE, AndroidDefaultProcess.role(application).name)
            putBoolean(SDK_IDLE, Elu.distinctId() == null && !Elu.sessionRecordingStarted() &&
                Elu.getFeatureFlagSnapshot() == null)
            putBoolean(LIFECYCLE_INSTALLED, AndroidProcessLifecycle.isInstalled)
            putBoolean(RUNTIME_STORAGE_CREATED,
                AndroidRuntimeQueue.databaseFileFor(application, siteKey).parentFile?.exists() == true)
        }
        replyTo.send(Message.obtain(null, SETUP).apply { data = result })
    }

    companion object {
        const val SETUP = 1
        const val SITE_KEY = "siteKey"
        const val PID = "pid"
        const val ROLE = "role"
        const val SDK_IDLE = "sdkIdle"
        const val LIFECYCLE_INSTALLED = "lifecycleInstalled"
        const val RUNTIME_STORAGE_CREATED = "runtimeStorageCreated"
        const val PROCESS_SUFFIX = ":elu_secondary"
        private const val LOOPBACK_CONFIG_HOST = "http://127.0.0.1:9"
        private const val SETTLE_MILLIS = 1_500L
    }
}
