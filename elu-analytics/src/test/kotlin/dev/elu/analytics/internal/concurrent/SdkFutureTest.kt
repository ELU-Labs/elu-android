package dev.elu.analytics.internal.concurrent

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SdkFutureTest {
    @Test
    fun `first result wins and completed null is a value`() {
        val future = SdkFuture<String?>()
        assertFalse(future.isDone)
        assertTrue(future.complete(null))
        assertTrue(future.isDone)
        assertFalse(future.complete("later"))
        assertFalse(future.completeExceptionally(IOException()))
        assertFalse(future.cancel(true))
        assertNull(future.get())
        assertNull(future.get(0, TimeUnit.NANOSECONDS))
        assertSame(future, future.toFuture())
    }

    @Test
    fun `failure preserves its original cause`() {
        val error = IOException("original")
        val future = SdkFuture<Int>()
        future.completeExceptionally(error)
        assertTrue(future.isCompletedExceptionally)
        assertFalse(future.isCancelled)
        assertSame(error, failure(future))
    }

    @Test
    fun `cancellation does not cancel producer or dependent operations`() {
        val future = SdkFuture<Int>()
        val dependent = future.whenComplete { _, _ -> }
        assertTrue(future.cancel(true))
        assertTrue(future.cancel(false))
        assertTrue(future.isCancelled)
        assertFalse(future.complete(1))
        try { future.get(); fail("cancelled get succeeded") } catch (_: CancellationException) { }
        assertFalse(dependent.isCancelled)
        assertTrue(failure(dependent) is CancellationException)
        val original = SdkFuture<Int>()
        val observer = original.whenComplete { _, _ -> }
        observer.cancel(true)
        assertFalse(original.isDone)
        original.complete(5)
        assertEquals(5, original.get())
    }

    @Test
    fun `an owner can retain original settlement despite cancellation`() {
        val original = object : SdkFuture<Int>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        assertFalse(original.cancel(true))
        assertFalse(original.isDone)
        original.complete(9)
        assertEquals(9, original.get())
    }

    @Test
    fun `listeners run outside locks and can reenter the original result`() {
        val original = SdkFuture<Int>()
        val observed = AtomicInteger()
        val dependent = original.whenComplete { value, error ->
            assertNull(error)
            assertEquals(7, value)
            val completed = CountDownLatch(1)
            val worker = Thread {
                original.whenComplete { nested, _ -> observed.addAndGet(checkNotNull(nested)) }
                completed.countDown()
            }
            worker.start()
            assertTrue("completion listener held result lock", completed.await(2, TimeUnit.SECONDS))
            worker.join(2000)
            assertFalse(worker.isAlive)
        }
        assertTrue(original.complete(7))
        assertEquals(7, dependent.get())
        assertEquals(7, observed.get())
    }

    @Test
    fun `throwing observer cannot erase original failure or starve other observers`() {
        val originalError = IOException("original")
        val observerError = IllegalStateException("observer")
        val original = SdkFuture<Int>()
        val count = AtomicInteger()
        val good = original.whenComplete { _, _ -> count.incrementAndGet() }
        val bad = original.whenComplete { _, _ -> throw observerError }
        original.completeExceptionally(originalError)
        assertEquals(1, count.get())
        assertSame(originalError, failure(original))
        assertSame(originalError, failure(good))
        assertSame(originalError, failure(bad))
        val successful = SdkFuture.completedFuture(1)
        assertSame(observerError, failure(successful.whenComplete { _, _ -> throw observerError }))
        assertEquals(1, successful.get())
    }

    @Test
    fun `joined failure waits for all original settlements`() {
        val first = SdkFuture<Int>()
        val second = SdkFuture<Int>()
        val error = IOException("first")
        val joined = SdkFuture.allOf(first, second)
        first.completeExceptionally(error)
        assertFalse(joined.isDone)
        second.complete(2)
        assertSame(error, failure(joined))
        assertEquals(Unit, SdkFuture.allOf().get())
        assertEquals(Unit, SdkFuture.allOf(second, second).get())
    }

    @Test
    fun `joined failure uses input order and cancellation cannot cancel inputs`() {
        val first = SdkFuture<Int>()
        val second = SdkFuture<Int>()
        val firstError = IOException("first")
        val secondError = IOException("second")
        val joined = SdkFuture.allOf(first, second)
        second.completeExceptionally(secondError)
        first.completeExceptionally(firstError)
        assertSame(firstError, failure(joined))
        val original = SdkFuture<Int>()
        val canceled = SdkFuture.allOf(original)
        canceled.cancel(true)
        assertFalse(original.isDone)
        original.complete(3)
        assertEquals(3, original.get())
    }

    @Test
    fun `compose preserves the original asynchronous result`() {
        val first = SdkFuture<Int>()
        val second = SdkFuture<String>()
        val next = first.thenCompose { value -> assertEquals(8, value); second }
        first.complete(8)
        assertFalse(next.isDone)
        second.complete("done")
        assertEquals("done", next.get())
        val error = IOException()
        val failed = SdkFuture.completedFuture(1).thenCompose<Int> { throw error }
        assertSame(error, failure(failed))
    }

    @Test
    fun `timed waits and interruption leave the original result pending`() {
        val future = SdkFuture<Int>()
        try { future.get(1, TimeUnit.MILLISECONDS); fail("pending result returned") } catch (_: TimeoutException) { }
        assertFalse(future.isDone)
        Thread.currentThread().interrupt()
        try {
            future.get()
            fail("interrupted wait returned")
        } catch (_: InterruptedException) {
            assertFalse(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        future.complete(1)
        Thread.currentThread().interrupt()
        try {
            assertEquals(1, future.get())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test
    fun `racing registration and competing completion settle each observer once`() {
        val workers = Executors.newFixedThreadPool(8)
        try {
            repeat(100) {
                val original = SdkFuture<Int>()
                val start = CountDownLatch(1)
                val calls = AtomicInteger()
                val successes = AtomicInteger()
                val jobs = (0 until 16).map { index ->
                    workers.submit {
                        start.await()
                        if (index % 2 == 0) original.whenComplete { value, failure ->
                            assertNull(failure)
                            assertNotNull(value)
                            calls.incrementAndGet()
                        }.get(2, TimeUnit.SECONDS)
                        else if (original.complete(index)) successes.incrementAndGet()
                    }
                }
                start.countDown()
                jobs.forEach { it.get(3, TimeUnit.SECONDS) }
                assertEquals(1, successes.get())
                assertEquals(8, calls.get())
            }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun failure(future: SdkFuture<*>): Throwable {
        try { future.get(2, TimeUnit.SECONDS); fail("failed result succeeded") }
        catch (error: ExecutionException) { return checkNotNull(error.cause) }
        throw AssertionError("unreachable")
    }
}
