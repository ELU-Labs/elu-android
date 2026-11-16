package dev.elu.analytics.internal.runtime.delivery

import dev.elu.analytics.EluPersistenceMode
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

        assertEquals("stream_a", streams.rotate(SOURCE, T0))
        assertEquals("stream_a", streams.wireStreamId(SOURCE))
        assertEquals(DeliveryStreamState(SOURCE, "stream_a", T0), store.read())
        // A state written for another record stream (a reset installation) is not used.
        assertEquals("stream_other", streams.wireStreamId("stream_other"))
        assertEquals("stream_other", DeliveryStreams(store).wireStreamId("stream_other"))
        assertEquals("stream_a", DeliveryStreams(store).wireStreamId(SOURCE))
    }

    @Test
    fun `a replacement never reuses the record stream or the current one`() {
        val streams = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids(SOURCE, "stream_a", "stream_a", "", "stream_b"))
        assertEquals("stream_a", streams.rotate(SOURCE, T0))
        assertEquals("stream_b", streams.rotate(SOURCE, T0))

        val stuck = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = { SOURCE })
        assertThrows(IllegalStateException::class.java) { stuck.rotate(SOURCE, T0) }
        assertEquals(SOURCE, stuck.wireStreamId(SOURCE))

        // Generated IDs are the same length as the installation's own.
        val generatedSource = "stream_" + "0".repeat(32)
        val generated = DeliveryStreams(MemoryDeliveryStreamStore())
        val first = generated.rotate(generatedSource, T0)
        val second = generated.rotate(generatedSource, T0)
        assertTrue(first, Regex("^stream_[0-9a-f]{32}$").matches(first))
        assertTrue(second, Regex("^stream_[0-9a-f]{32}$").matches(second))
        assertFalse(first == second)
    }

    @Test
    fun `an aged head moves off the record stream once and stays on a stream moved to within the window`() {
        val week = DeliveryStreams.AGED_REUSE_MILLIS
        val store = MemoryDeliveryStreamStore()
        val streams = DeliveryStreams(store, newStreamId = ids("stream_a", "stream_b", "stream_c", "stream_d"))
        assertTrue(streams.rotateForAge(SOURCE, T0))
        assertEquals(DeliveryStreamState(SOURCE, "stream_a", T0), store.read())
        // The aged records behind it stay on the new stream.
        assertFalse(streams.rotateForAge(SOURCE, T0 + 1))
        assertFalse(streams.rotateForAge(SOURCE, T0 + week - 1))
        assertEquals("stream_a", streams.wireStreamId(SOURCE))
        // After the window the stream may hold swept rows, so the move is made again.
        assertTrue(streams.rotateForAge(SOURCE, T0 + week))
        assertEquals(DeliveryStreamState(SOURCE, "stream_b", T0 + week), store.read())
        // A move for another reason is just as new: an aged head right behind it stays there.
        assertEquals("stream_c", streams.rotate(SOURCE, T0 + 2 * week))
        assertFalse(streams.rotateForAge(SOURCE, T0 + 2 * week + 1))
        assertEquals("stream_c", streams.wireStreamId(SOURCE))
        assertTrue(streams.rotateForAge(SOURCE, T0 + 3 * week))
        assertEquals("stream_d", streams.wireStreamId(SOURCE))
    }

    @Test
    fun `a stuck-head move also keeps an aged head on the new stream`() {
        val streams = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = ids("stream_a"), rotateAfterMillis = 0, rotateAfterAnswers = 1)
        assertTrue(streams.streamGapAnswered(SOURCE, SOURCE, 7, RECORD_7, T0))
        assertFalse(streams.rotateForAge(SOURCE, T0 + 1))
        assertEquals("stream_a", streams.wireStreamId(SOURCE))
    }

    @Test
    fun `a new process keeps an aged head on the stream an earlier one moved it to`() {
        val file = File(directory, FileDeliveryStreamStore.FILE_NAME)
        val first = DeliveryStreams(FileDeliveryStreamStore(file), newStreamId = ids("stream_a"))
        assertTrue(first.rotateForAge(SOURCE, T0))

        // The acknowledgement was lost and the process ended: the resend goes to the same stream,
        // where the server answers it from what it stored.
        val second = DeliveryStreams(FileDeliveryStreamStore(file), newStreamId = ids("stream_b"))
        assertFalse(second.rotateForAge(SOURCE, T0 + 60_000L))
        assertEquals("stream_a", second.wireStreamId(SOURCE))
        assertEquals(DeliveryStreamState(SOURCE, "stream_a", T0), FileDeliveryStreamStore(file).read())
    }

    @Test
    fun `answers and progress on a moved stream keep the time of the move`() {
        val store = MemoryDeliveryStreamStore()
        val streams = DeliveryStreams(store, newStreamId = ids("stream_a"), rotateAfterMillis = MINUTES_30, rotateAfterAnswers = 5)
        streams.rotate(SOURCE, T0)
        assertFalse(streams.streamGapAnswered(SOURCE, "stream_a", 7, RECORD_7, T0 + 1))
        assertEquals(DeliveryStreamState(SOURCE, "stream_a", T0, DeliveryStreamGap(7, RECORD_7, T0 + 1, 1)), store.read())
        streams.resolvedThrough(SOURCE, 7)
        assertEquals(DeliveryStreamState(SOURCE, "stream_a", T0), store.read())
        assertFalse(streams.rotateForAge(SOURCE, T0 + 2))
    }

    @Test
    fun `the age limit keeps twelve hours inside the server's retention and the reuse window a day`() {
        val hour = 60L * 60L
        assertEquals(691_200, DeliveryStreams.IDEMPOTENCY_RETENTION_SECONDS)
        assertEquals(12 * hour, DeliveryStreams.IDEMPOTENCY_RETENTION_SECONDS - DeliveryStreams.RESEND_AGE_LIMIT_SECONDS.toLong())
        assertEquals(24 * hour * 1_000L, DeliveryStreams.IDEMPOTENCY_RETENTION_SECONDS * 1_000L - DeliveryStreams.AGED_REUSE_MILLIS)
        // Past the server's maximum record age, so a moved head is stored as record-too-old.
        assertTrue(DeliveryStreams.RESEND_AGE_LIMIT_SECONDS > 604_800)
    }

    @Test
    fun `a persistent queue keeps its delivery stream in a file next to it and a memory queue in memory`() {
        val source = "stream_" + "0".repeat(32)
        val queueDirectory = File(directory, "persistent").also { assertTrue(it.mkdir()) }
        val queueFile = File(queueDirectory, "queue-v1.sqlite")
        val moved = DeliveryStreams.forQueue(EluPersistenceMode.PERSISTENT, queueFile).rotate(source, T0)
        assertEquals(listOf(FileDeliveryStreamStore.FILE_NAME), queueDirectory.list()?.toList())
        // A new process finds it.
        assertEquals(moved, DeliveryStreams.forQueue(EluPersistenceMode.PERSISTENT, queueFile).wireStreamId(source))

        val memoryDirectory = File(directory, "memory").also { assertTrue(it.mkdir()) }
        val memoryFile = File(memoryDirectory, "queue-v1.sqlite")
        val memory = DeliveryStreams.forQueue(EluPersistenceMode.MEMORY, memoryFile)
        val memoryMoved = memory.rotate(source, T0)
        assertEquals(memoryMoved, memory.wireStreamId(source))
        assertEquals(emptyList<String>(), memoryDirectory.list()?.toList())
        assertEquals(source, DeliveryStreams.forQueue(EluPersistenceMode.MEMORY, memoryFile).wireStreamId(source))
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
        val replacement = streams.rotate(SOURCE, T0)
        assertEquals(SOURCE.length, replacement.length)
        assertEquals("stream_" + "a".repeat(SOURCE.length - 7), replacement)

        // A candidate that is the record stream's own ID once cut is not a new stream.
        val same = DeliveryStreams(MemoryDeliveryStreamStore(), newStreamId = { SOURCE + "x" })
        assertThrows(IllegalStateException::class.java) { same.rotate(SOURCE, T0) }
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
        assertEquals(DeliveryStreamState(SOURCE, "stream_a", T0 + 2 * MINUTES_30 + 3), store.read())
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
        assertEquals("stream_a", streams.rotate(SOURCE, T0))
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

        val rotated = DeliveryStreamState(SOURCE, "stream_a", T0)
        store.write(rotated)
        assertEquals(rotated, store.read())
        assertArrayEquals(
            "{\"schemaVersion\":1,\"sourceStreamId\":\"$SOURCE\",\"wireStreamId\":\"stream_a\",\"movedAt\":$T0,\"gap\":null}".toByteArray(),
            file.readBytes(),
        )

        // Not moved yet: only the stuck head is kept.
        val unmoved = DeliveryStreamState(SOURCE, SOURCE, gap = DeliveryStreamGap(7, RECORD_7, T0, 1))
        store.write(unmoved)
        assertEquals(unmoved, store.read())
        assertArrayEquals(
            (
                "{\"schemaVersion\":1,\"sourceStreamId\":\"$SOURCE\",\"wireStreamId\":\"$SOURCE\",\"movedAt\":null,\"gap\":" +
                    "{\"sequence\":7,\"recordId\":\"$RECORD_7\",\"firstAnsweredAt\":$T0,\"answers\":1}}"
            ).toByteArray(),
            file.readBytes(),
        )

        val waiting = DeliveryStreamState(SOURCE, "stream_a", Long.MAX_VALUE, DeliveryStreamGap(Long.MAX_VALUE, RECORD_7, T0, Int.MAX_VALUE))
        store.write(waiting)
        assertEquals(waiting, store.read())
        assertEquals(waiting, FileDeliveryStreamStore(file).read())
        assertArrayEquals(
            (
                "{\"schemaVersion\":1,\"sourceStreamId\":\"$SOURCE\",\"wireStreamId\":\"stream_a\",\"movedAt\":${Long.MAX_VALUE},\"gap\":" +
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
        val valid = "{\"schemaVersion\":1,\"sourceStreamId\":\"$SOURCE\",\"wireStreamId\":\"stream_a\",\"movedAt\":$T0,\"gap\":$gap}"
        file.writeText(valid)
        assertEquals(DeliveryStreamState(SOURCE, "stream_a", T0, DeliveryStreamGap(7, RECORD_7, T0, 3)), store.read())

        listOf(
            "",
            "not json",
            "[]",
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.replace("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"extra\":true,"),
            valid.replace(",\"gap\":$gap", ""),
            valid.replace(",\"movedAt\":$T0", ""),
            // A moved stream has the time of the move, and the record stream itself has none.
            valid.replace("\"movedAt\":$T0", "\"movedAt\":null"),
            valid.replace("\"wireStreamId\":\"stream_a\"", "\"wireStreamId\":\"$SOURCE\""),
            valid.replace("\"movedAt\":$T0", "\"movedAt\":-1"),
            valid.replace("\"movedAt\":$T0", "\"movedAt\":\"$T0\""),
            valid.replace("\"movedAt\":$T0", "\"movedAt\":$T0.0"),
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
        assertThrows(IOException::class.java) { store.write(DeliveryStreamState(SOURCE, "stream_a", T0)) }
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
