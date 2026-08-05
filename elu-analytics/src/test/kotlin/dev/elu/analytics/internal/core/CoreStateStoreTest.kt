package dev.elu.analytics.internal.core

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoreStateStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `write commits bytes and read returns an isolated copy`() {
        val file = File(temporaryFolder.newFolder("state"), "core-v1.json")
        val store = AndroidCoreStateStore(file)
        val expected = "durable-state".toByteArray()

        store.write(expected)
        expected.fill(0)

        assertArrayEquals("durable-state".toByteArray(), store.read())
    }

    @Test
    fun `read restores backup and discards staging file after interrupted commit`() {
        val directory = temporaryFolder.newFolder("interrupted")
        val file = File(directory, "core-v1.json")
        val backup = File(directory, "core-v1.json.bak")
        val staging = File(directory, "core-v1.json.new")
        val store = AndroidCoreStateStore(file)
        val previous = "previous-complete-state".toByteArray()
        store.write(previous)

        assertTrue(file.renameTo(backup))
        staging.writeText("incomplete-next-state")

        assertArrayEquals(previous, store.read())
        assertTrue(file.exists())
        assertFalse(backup.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun `oversized file is bounded and core replaces it with fail-closed state`() {
        val file = File(temporaryFolder.newFolder("oversized"), "core-v1.json")
        RandomAccessFile(file, "rw").use { it.setLength(MAX_PERSISTED_CORE_STATE_BYTES.toLong() + 1) }
        val store = AndroidCoreStateStore(file)

        assertThrows(CoreStateCorruptionException::class.java) { store.read() }

        val core =
            IdentityStateCore(
                store,
                CoreIdentifierGenerator { prefix -> "${prefix}recovered" },
                CoreTimestampProvider { "2026-08-04T00:00:00.000Z" },
            )
        assertTrue(core.snapshot().identity.optedOut)
        assertTrue(file.length() < MAX_PERSISTED_CORE_STATE_BYTES)
    }
}
