package dev.elu.analytics.internal.runtime.delivery

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeliveryStreamsTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("elu-delivery-streams").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `records go on their own stream until a replacement, which is kept per record stream`() {
        val store = MemoryDeliveryStreamStore()
        val streams = DeliveryStreams(store, newStreamId = ids("stream_a"))
        assertEquals(SOURCE, streams.wireStreamId(SOURCE))
        assertNull(store.read())

        assertEquals("stream_a", streams.rotate(SOURCE))
        assertEquals("stream_a", streams.wireStreamId(SOURCE))
        assertEquals(DeliveryStreamState(SOURCE, "stream_a"), store.read())
        // A state written for another record stream (a reset installation) is not used.
        assertEquals("stream_other", streams.wireStreamId("stream_other"))
        assertEquals("stream_other", DeliveryStreams(store).wireStreamId("stream_other"))
        assertEquals("stream_a", DeliveryStreams(store).wireStreamId(SOURCE))
    }

    @Test
    fun `a replacement never reuses the record stream or the current one`() {
        val streams = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids(SOURCE, "stream_a", "stream_a", "", "stream_b"))
        assertEquals("stream_a", streams.rotate(SOURCE))
        assertEquals("stream_b", streams.rotate(SOURCE))

        val stuck = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = { SOURCE })
        assertThrows(IllegalStateException::class.java) { stuck.rotate(SOURCE) }
        assertEquals(SOURCE, stuck.wireStreamId(SOURCE))

        // Generated IDs are the same length as the installation's own.
        val generatedSource = "stream_" + "0".repeat(32)
        val generated = DeliveryStreams(MemoryDeliveryStreamStore())
        val first = generated.rotate(generatedSource)
        val second = generated.rotate(generatedSource)
        assertTrue(first, Regex("^stream_[0-9a-f]{32}$").matches(first))
        assertTrue(second, Regex("^stream_[0-9a-f]{32}$").matches(second))
        assertFalse(first == second)
    }

    @Test
    fun `a past-retention head moves the stream once per window and again after it or after another move`() {
        val week = DeliveryStreams.AGED_REUSE_MILLIS
        val streams = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids("stream_a", "stream_b", "stream_c", "stream_d"))
        assertTrue(streams.rotateForAge(SOURCE, T0))
        assertEquals("stream_a", streams.wireStreamId(SOURCE))
        // The aged records behind it stay on the new stream.
        assertFalse(streams.rotateForAge(SOURCE, T0 + 1))
        assertFalse(streams.rotateForAge(SOURCE, T0 + week - 1))
        assertEquals("stream_a", streams.wireStreamId(SOURCE))
        // A move for another reason is a stream the marker does not know.
        assertEquals("stream_b", streams.rotate(SOURCE))
        assertTrue(streams.rotateForAge(SOURCE, T0 + 2))
        assertEquals("stream_c", streams.wireStreamId(SOURCE))
        // After the window, and when the clock went back, the move is made again.
        assertTrue(streams.rotateForAge(SOURCE, T0 + 2 + week))
        assertEquals("stream_d", streams.wireStreamId(SOURCE))
    }

    @Test
    fun `a clock that went back moves a past-retention head again`() {
        val streams = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids("stream_a", "stream_b"))
        assertTrue(streams.rotateForAge(SOURCE, T0))
        assertTrue(streams.rotateForAge(SOURCE, T0 - 1))
        assertEquals("stream_b", streams.wireStreamId(SOURCE))
    }

    @Test
    fun `a replacement is never longer than the record stream ID`() {
        val streams = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids("stream_" + "a".repeat(40)))
        val replacement = streams.rotate(SOURCE)
        assertEquals(SOURCE.length, replacement.length)
        assertEquals("stream_" + "a".repeat(SOURCE.length - 7), replacement)

        // A candidate that is the record stream's own ID once cut is not a new stream.
        val same = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = { SOURCE + "x" })
        assertThrows(IllegalStateException::class.java) { same.rotate(SOURCE) }
    }

    @Test
    fun `stream-gap answers move the stream only after both the answer count and the wait`() {
        val store = MemoryDeliveryStreamStore()
        val streams = DeliveryStreams(store, newStreamId = ids("stream_a"), rotateAfterMillis = MINUTES_30, rotateAfterAnswers = 5)

        // One answer, then one after a long pause: the wait is met but not the count.
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0))
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + 2 * MINUTES_30))
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + 2 * MINUTES_30 + 1))
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + 2 * MINUTES_30 + 2))
        assertEquals(DeliveryStreamGap(7, RECORD_7, T0, 4), store.read()?.gap)
        assertEquals(SOURCE, streams.wireStreamId(SOURCE))

        assertTrue(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + 2 * MINUTES_30 + 3))
        assertEquals(DeliveryStreamState(SOURCE, "stream_a"), store.read())
        assertEquals("stream_a", streams.wireStreamId(SOURCE))

        // Many answers in a short time meet the count but not the wait.
        val quick = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids("stream_b"), rotateAfterMillis = MINUTES_30, rotateAfterAnswers = 5)
        repeat(20) { index -> assertFalse(quick.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + index * 1_000L)) }
        assertTrue(quick.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + MINUTES_30))
    }

    @Test
    fun `defaults wait forty-five minutes and five answers`() {
        assertEquals(45L * 60L * 1_000L, DeliveryStreams.STREAM_GAP_ROTATE_AFTER_MILLIS)
        assertEquals(5, DeliveryStreams.STREAM_GAP_ROTATE_AFTER_ANSWERS)
        val streams = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids("stream_a"))
        repeat(4) { index -> assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + index * MINUTES_30)) }
        assertTrue(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + 4 * MINUTES_30))

        val waiting = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids("stream_b"))
        repeat(5) { index -> assertFalse(waiting.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + index)) }
        assertFalse(waiting.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + MINUTES_45 - 1))
        assertTrue(waiting.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 + MINUTES_45))
    }

    @Test
    fun `another head, another wire stream or progress starts the count again`() {
        val store = MemoryDeliveryStreamStore()
        val streams = DeliveryStreams(store, newStreamId = ids("stream_a"), rotateAfterMillis = 0, rotateAfterAnswers = 2)

        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0))
        // An answer for a request on a stream that is no longer current counts for nothing.
        assertFalse(streams.streamGapAnswered(SOURCE, "stream_old", 7, RECORD_7, T0 + 1))
        assertEquals(1, store.read()?.gap?.answers)
        // Another head replaces the count.
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 8, RECORD_8, T0 + 2))
        assertEquals(DeliveryStreamGap(8, RECORD_8, T0 + 2, 1), store.read()?.gap)
        // Progress below the head keeps it; progress through it clears it.
        streams.resolvedThrough(SOURCE, 7)
        assertEquals(8L, store.read()?.gap?.sequence)
        streams.resolvedThrough(SOURCE, 8)
        assertNull(store.read()?.gap)
        assertEquals(SOURCE, streams.wireStreamId(SOURCE))
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 9, "event_9", T0 + 3))
        assertTrue(streams.streamGapAnswered(SOURCE, SOURCE, 9, "event_9", T0 + 4))
        assertEquals("stream_a", streams.wireStreamId(SOURCE))
    }

    @Test
    fun `a wall clock that moves back measures the wait again from then`() {
        val store = MemoryDeliveryStreamStore()
        val streams = DeliveryStreams(store, newStreamId = ids("stream_a"), rotateAfterMillis = MINUTES_30, rotateAfterAnswers = 2)
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0))
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 - 60_000L))
        assertEquals(DeliveryStreamGap(7, RECORD_7, T0 - 60_000L, 2), store.read()?.gap)
        assertFalse(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 - 60_000L + MINUTES_30 - 1))
        assertTrue(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0 - 60_000L + MINUTES_30))
    }

    @Test
    fun `a failed write still moves this process, and a later process reads what was last stored`() {
        val failing =
            object : DeliveryStreamStore {
                var reads = 0

                override fun read(): DeliveryStreamState? {
                    reads += 1
                    return null
                }

                override fun write(state: DeliveryStreamState) {
                    throw IOException("disk full")
                }
            }
        val streams = DeliveryStreams(failing, newStreamId = ids("stream_a"), rotateAfterMillis = 0, rotateAfterAnswers = 1)
        assertEquals("stream_a", streams.rotate(SOURCE))
        assertEquals("stream_a", streams.wireStreamId(SOURCE))
        assertEquals(1, failing.reads)
        assertEquals(SOURCE, DeliveryStreams(failing).wireStreamId(SOURCE))

        val unreadable =
            object : DeliveryStreamStore {
                override fun read(): DeliveryStreamState? {
                    throw IOException("unreadable")
                }

                override fun write(state: DeliveryStreamState) = Unit
            }
        assertEquals(SOURCE, DeliveryStreams(unreadable).wireStreamId(SOURCE))
    }

    @Test
    fun `the state file round-trips its exact bytes and replaces them whole`() {
        val file = File(directory, FileDeliveryStreamStore.FILE_NAME)
        val store = FileDeliveryStreamStore(file)
        assertNull(store.read())

        val rotated = DeliveryStreamState(SOURCE, "stream_a")
        store.write(rotated)
        assertEquals(rotated, store.read())
        assertArrayEquals(
            "{\"schemaVersion\":1,\"sourceStreamId\":\"$SOURCE\",\"wireStreamId\":\"stream_a\",\"gap\":null}".toByteArray(),
            file.readBytes(),
        )

        val waiting = DeliveryStreamState(SOURCE, "stream_a", DeliveryStreamGap(Long.MAX_VALUE, RECORD_7, T0, Int.MAX_VALUE))
        store.write(waiting)
        assertEquals(waiting, store.read())
        assertEquals(waiting, FileDeliveryStreamStore(file).read())
        assertArrayEquals(
            (
                "{\"schemaVersion\":1,\"sourceStreamId\":\"$SOURCE\",\"wireStreamId\":\"stream_a\",\"gap\":" +
                    "{\"sequence\":${Long.MAX_VALUE},\"recordId\":\"$RECORD_7\",\"firstAnsweredAt\":$T0,\"answers\":${Int.MAX_VALUE}}}"
            ).toByteArray(),
            file.readBytes(),
        )
        assertEquals(listOf(FileDeliveryStreamStore.FILE_NAME), directory.list()?.toList())

        // A temporary file left by a process that died mid-write does not matter.
        File(file.path + ".tmp").writeText("partial")
        assertEquals(waiting, store.read())
        store.write(rotated)
        assertEquals(rotated, store.read())
        assertEquals(listOf(FileDeliveryStreamStore.FILE_NAME), directory.list()?.toList())
    }

    @Test
    fun `a state file that is not exactly what the SDK writes reads as no state`() {
        val file = File(directory, FileDeliveryStreamStore.FILE_NAME)
        val store = FileDeliveryStreamStore(file)
        val gap = "{\"sequence\":7,\"recordId\":\"$RECORD_7\",\"firstAnsweredAt\":$T0,\"answers\":3}"
        val valid = "{\"schemaVersion\":1,\"sourceStreamId\":\"$SOURCE\",\"wireStreamId\":\"stream_a\",\"gap\":$gap}"
        file.writeText(valid)
        assertEquals(DeliveryStreamState(SOURCE, "stream_a", DeliveryStreamGap(7, RECORD_7, T0, 3)), store.read())

        listOf(
            "",
            "not json",
            "[]",
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"extra\":true,"),
            valid.replace(",\"gap\":$gap", ""),
            valid.replace("\"answers\":3", "\"answers\":0"),
            valid.replace("\"answers\":3", "\"answers\":3.0"),
            valid.replace("\"answers\":3", "\"answers\":2147483648"),
            valid.replace("\"sequence\":7", "\"sequence\":-1"),
            valid.replace("\"sequence\":7", "\"sequence\":\"7\""),
            valid.replace("\"firstAnsweredAt\":$T0", "\"firstAnsweredAt\":-1"),
            valid.replace("\"wireStreamId\":\"stream_a\"", "\"wireStreamId\":\"\""),
            valid.replace("\"wireStreamId\":\"stream_a\"", "\"wireStreamId\":\"${"s".repeat(257)}\""),
            valid.replace("\"wireStreamId\":\"stream_a\"", "\"wireStreamId\":null"),
            valid.replace(",\"gap\":", ", \"gap\":"),
            valid.replace("\"recordId\":\"$RECORD_7\",", "\"recordId\":\"$RECORD_7\",\"extra\":1,"),
            "$valid\n",
            valid.replace("\"sourceStreamId\":\"$SOURCE\"", "\"sourceStreamId\":\"${"s".repeat(2_100)}\""),
        ).forEach { content ->
            file.writeText(content)
            assertNull(content, store.read())
        }
    }

    @Test
    fun `writing into a missing directory fails and leaves nothing behind`() {
        val missing = File(File(directory, "absent"), FileDeliveryStreamStore.FILE_NAME)
        val store = FileDeliveryStreamStore(missing)
        assertThrows(IOException::class.java) { store.write(DeliveryStreamState(SOURCE, "stream_a")) }
        assertNull(store.read())
        assertFalse(missing.parentFile!!.exists())
    }

    private fun ids(vararg values: String): () -> String {
        val remaining = ArrayDeque(values.toList())
        return { remaining.removeFirst() }
    }

    private companion object {
        const val SOURCE = "stream_source"
        const val RECORD_7 = "event_7"
        const val RECORD_8 = "event_8"
        const val T0 = 1_893_456_000_000L
        const val MINUTES_30 = 30L * 60L * 1_000L
        const val MINUTES_45 = 45L * 60L * 1_000L
    }
}
