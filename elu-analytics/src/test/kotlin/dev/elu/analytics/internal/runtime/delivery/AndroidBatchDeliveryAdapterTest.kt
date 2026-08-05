package dev.elu.analytics.internal.runtime.delivery

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBatchDeliveryAdapterTest {
    @Test
    fun `lifecycle callbacks enqueue one non-blocking bounded trigger`() {
        val executor = ManualExecutor()
        val triggers = AtomicInteger()
        val adapter = AndroidBatchDeliveryAdapter(executor) { triggers.incrementAndGet() }

        assertTrue(adapter.onBackgrounded())
        assertFalse(adapter.onForegrounded())
        assertEquals(0, triggers.get())
        assertEquals(1, executor.tasks.size)

        executor.runNext()
        assertEquals(1, triggers.get())
        assertTrue(adapter.onForegrounded())
        executor.runNext()
        assertEquals(2, triggers.get())
    }

    @Test
    fun `rejection and trigger failure release the scheduling guard`() {
        val rejecting = AndroidBatchDeliveryAdapter(Executor { throw RejectedExecutionException("closed") }) {}
        assertFalse(rejecting.onBackgrounded())
        assertFalse(rejecting.onForegrounded())

        val executor = ManualExecutor()
        val adapter = AndroidBatchDeliveryAdapter(executor) { throw IllegalStateException("failed") }
        assertTrue(adapter.onBackgrounded())
        assertThrows(IllegalStateException::class.java) { executor.runNext() }
        assertTrue(adapter.onBackgrounded())
    }

    @Test
    fun `blocking queue adapter fails fast on the queue owner worker`() {
        requireOffRuntimeQueueOwnerThread(false)
        assertThrows(IllegalStateException::class.java) {
            requireOffRuntimeQueueOwnerThread(true)
        }
    }

    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
