package dev.elu.analytics

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EluEventBufferTest {
    @Test
    fun `drains FIFO and drops oldest at capacity`() {
        val calls = mutableListOf<Int>()
        val buffer = EluEventBuffer(capacity = 3)
        (1..4).forEach { value -> buffer.add { calls += value } }

        buffer.drain().forEach { it() }

        assertEquals(listOf(2, 3, 4), calls)
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `clear removes pending operations`() {
        val buffer = EluEventBuffer(capacity = 2)
        buffer.add { error("must not run") }
        buffer.clear()

        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `concurrent producers do not lose or duplicate operations`() {
        val producerCount = 8
        val operationsPerProducer = 250
        val total = producerCount * operationsPerProducer
        val buffer = EluEventBuffer(capacity = total)
        val pool = Executors.newFixedThreadPool(producerCount)
        val start = CountDownLatch(1)
        val observed = Collections.synchronizedSet(mutableSetOf<Int>())

        repeat(producerCount) { producer ->
            pool.execute {
                start.await()
                repeat(operationsPerProducer) { offset ->
                    val id = producer * operationsPerProducer + offset
                    buffer.add { observed += id }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        val drained = buffer.drain()
        assertEquals(total, drained.size)
        drained.forEach { it() }
        assertEquals((0 until total).toSet(), observed)
    }
}
