package dev.elu.analytics

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.internal.runtime.AndroidDefaultProcess
import dev.elu.analytics.internal.runtime.AndroidProcessLifecycle
import dev.elu.analytics.internal.runtime.AndroidRuntimeQueue
import dev.elu.analytics.internal.runtime.ProcessRole
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiProcessSetupInstrumentationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test
    fun setupInASecondaryProcessIsIgnoredWhileTheDefaultProcessOwnsTheRuntime() {
        val siteKey = "elu_pk_multi_process_${UUID.randomUUID()}"
        val database = AndroidRuntimeQueue.databaseFileFor(context, siteKey)
        val namespace = checkNotNull(database.parentFile)
        try {
            // The instrumentation runs in the default process, where the lifecycle provider installed.
            assertEquals(ProcessRole.DEFAULT, AndroidDefaultProcess.role(context))
            assertTrue(AndroidProcessLifecycle.isInstalled)

            SecondaryProcessProbe.bind(context).use { probe ->
                val result = probe.setup(siteKey)
                val pid = result.getInt(SecondaryProcessSetupService.PID)
                assertNotEquals(Process.myPid(), pid)
                assertEquals(context.packageName + SecondaryProcessSetupService.PROCESS_SUFFIX, processName(pid))
                assertEquals(ProcessRole.SECONDARY.name, result.getString(SecondaryProcessSetupService.ROLE))
                assertTrue("setup must leave the SDK idle", result.getBoolean(SecondaryProcessSetupService.SDK_IDLE))
                assertFalse("lifecycle must not be observed",
                    result.getBoolean(SecondaryProcessSetupService.LIFECYCLE_INSTALLED))
                assertFalse("runtime storage must not be created",
                    result.getBoolean(SecondaryProcessSetupService.RUNTIME_STORAGE_CREATED))
                assertFalse(namespace.exists())

                // The secondary process is still alive. The default process must own the exact
                // installation namespace that setup would use, rather than finding it locked.
                val owner = AndroidRuntimeQueue.open(context, siteKey, RuntimeQueueLimits(100, 1_000_000))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                try {
                    val snapshot = owner.snapshot().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    assertTrue(snapshot.state.identity.anonymousId.isNotBlank())
                    assertTrue(database.exists())
                } finally {
                    owner.closeAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        } finally {
            namespace.deleteRecursively()
        }
    }

    private fun processName(pid: Int): String? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }

    private class SecondaryProcessProbe private constructor(
        private val context: Context,
        private val connection: ServiceConnection,
        private val service: Messenger,
    ) : AutoCloseable {
        fun setup(siteKey: String): Bundle {
            val replies = LinkedBlockingQueue<Bundle>()
            val thread = HandlerThread("elu-secondary-process-reply").apply { start() }
            try {
                val replyTo = Messenger(Handler(thread.looper) { message -> replies.add(Bundle(message.data)); true })
                service.send(Message.obtain(null, SecondaryProcessSetupService.SETUP).apply {
                    data = Bundle().apply { putString(SecondaryProcessSetupService.SITE_KEY, siteKey) }
                    this.replyTo = replyTo
                })
                return checkNotNull(replies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "secondary process did not reply" }
            } finally {
                thread.quitSafely()
            }
        }

        override fun close() {
            context.unbindService(connection)
        }

        companion object {
            fun bind(context: Context): SecondaryProcessProbe {
                val connected = CountDownLatch(1)
                var binder: IBinder? = null
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        binder = service
                        connected.countDown()
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                val intent = Intent(context, SecondaryProcessSetupService::class.java)
                assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
                try {
                    assertTrue("secondary process did not start", connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    return SecondaryProcessProbe(context, connection, Messenger(checkNotNull(binder)))
                } catch (error: Throwable) {
                    context.unbindService(connection)
                    throw error
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
