package dev.elu.fixture.smoke

import android.app.Activity
import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import dev.elu.analytics.Elu
import dev.elu.analytics.EluEvent
import dev.elu.analytics.EluOptions
import java.io.File

/**
 * Device smoke for an optimized (R8) consumer build of the staged SDK artifact.
 *
 * Setup follows the README: once, from Application.onCreate. The reserved,
 * unresolvable self-hosted origin keeps the smoke offline, so no remote
 * configuration arrives: the SDK must open its local store, accept every public
 * call without throwing, keep flags unavailable and keep replay off.
 */
class SmokeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val options = EluOptions(SMOKE_ORIGIN, SMOKE_ORIGIN)
        options.propertyDenylist = listOf("smoke_private")
        options.beforeSend = EluEvent.Filter { event -> event }
        Elu.setup(this, "consumer-smoke-site-key", options)
    }
}

class SmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = applicationContext
        Thread({
            try {
                Log.i(TAG, "ELU_CONSUMER_SMOKE PASS ${ConsumerSmoke.run(context)}")
            } catch (error: Throwable) {
                Log.e(TAG, "ELU_CONSUMER_SMOKE FAIL", error)
            }
        }, "elu-consumer-smoke").start()
    }
}

internal object ConsumerSmoke {
    fun run(context: Context): String {
        val tables = awaitStore(context)
        Elu.register(mapOf("fixture" to "consumer-smoke"))
        Elu.capture("consumer_smoke", mapOf("api" to Build.VERSION.SDK_INT, "smoke_private" to "dropped"))
        Elu.screen("ConsumerSmokeScreen")
        Elu.identify("consumer-smoke-user", mapOf("plan" to "fixture"))
        Elu.group("company", "consumer-smoke-company")
        Elu.captureException(IllegalStateException("consumer smoke"))
        check(Elu.getFeatureFlag("consumer-smoke-flag") == null) { "no configuration means no flag value" }
        check(!Elu.isFeatureEnabled("consumer-smoke-flag")) { "no configuration means flags stay disabled" }
        Elu.startSessionRecording()
        // Replay needs API 29+ and server authorization; neither path may start it here.
        check(!Elu.sessionRecordingStarted()) { "replay must remain off" }
        Elu.stopSessionRecording()
        Elu.optOut()
        await("opt-out") { Elu.isOptedOut().takeIf { it } }
        Elu.optIn(null)
        await("opt-in") { (!Elu.isOptedOut()).takeIf { it } }
        val extra = SmokeExtras.run()
        Elu.flush()
        Elu.reset()
        return "api=${Build.VERSION.SDK_INT} storeTables=$tables replay=${Elu.sessionRecordingStarted()} $extra"
    }

    /** The owned runtime opens its SQLite store at setup, before any configuration arrives. */
    private fun awaitStore(context: Context): Int = await("runtime store") {
        File(context.noBackupFilesDir, "elu-analytics/runtime").walkTopDown()
            .firstOrNull { it.isFile && it.name == "queue-v1.sqlite" }
            ?.let { file ->
                runCatching {
                    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                        database.rawQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                            if (cursor.moveToFirst()) cursor.getInt(0) else 0
                        }
                    }
                }.getOrNull()?.takeIf { it > 0 }
            }
    }

    private fun <T : Any> await(stage: String, read: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (true) {
            read()?.let { return it }
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting for $stage" }
            Thread.sleep(100)
        }
    }
}

internal const val TAG = "EluConsumerSmoke"
private const val SMOKE_ORIGIN = "https://consumer-smoke.invalid"
