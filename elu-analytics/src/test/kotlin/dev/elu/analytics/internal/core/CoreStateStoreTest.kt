package dev.elu.analytics.internal.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        val store = AndroidCoreStateStore.forTesting(file)
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
        val store = AndroidCoreStateStore.forTesting(file)
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
    fun `both primary and backup keeps committed opted out primary`() {
        val directory = temporaryFolder.newFolder("committed-primary")
        val file = File(directory, "core-v1.json")
        val backup = File(directory, "core-v1.json.bak")
        val store = AndroidCoreStateStore.forTesting(file)
        val initial = newCore(store).snapshot()
        val optedOut =
            initial.copy(
                identity = initial.identity.copy(optedOut = true),
            )

        assertTrue(file.renameTo(backup))
        file.writeBytes(CoreStateCodec.encode(optedOut))

        val restored = newCore(store).snapshot()

        assertTrue(restored.identity.optedOut)
        assertFalse(backup.exists())
    }

    @Test
    fun `corrupt committed primary fails closed instead of restoring older opted in backup`() {
        val directory = temporaryFolder.newFolder("corrupt-committed-primary")
        val file = File(directory, "core-v1.json")
        val backup = File(directory, "core-v1.json.bak")
        val store = AndroidCoreStateStore.forTesting(file)
        newCore(store)

        assertTrue(file.renameTo(backup))
        file.writeText("{corrupt-primary")

        val recovered = newCore(store).snapshot()

        assertTrue(recovered.identity.optedOut)
        assertFalse(backup.exists())
        assertEquals(recovered, CoreStateCodec.decode(file.readBytes()))
    }

    @Test
    fun `backup cleanup failure still commits opted out primary`() {
        val directory = temporaryFolder.newFolder("backup-cleanup-failure")
        val file = File(directory, "core-v1.json")
        val operations = FailingBackupDeleteOperations()
        val store = AndroidCoreStateStore.forTesting(file, fileOperations = operations)
        val core = newCore(store)
        operations.failBackupDeletes = true

        core.setOptedOut(true)
        val afterRestart = newCore(store).snapshot()

        assertTrue(afterRestart.identity.optedOut)
        assertTrue(File(directory, "core-v1.json.bak").exists())
    }

    @Test
    fun `directory fsync failure never rolls an opted out commit back`() {
        val file = File(temporaryFolder.newFolder("fsync-failure"), "core-v1.json")
        val directorySync = FailingOnceDirectorySync()
        val store = AndroidCoreStateStore.forTesting(file, directorySync = directorySync)
        val core = newCore(store)
        directorySync.failNext = true

        val error = assertThrows(IOException::class.java) { core.setOptedOut(true) }

        assertTrue(error.message.orEmpty().contains("committed"))
        assertTrue(core.snapshot().identity.optedOut)
        assertTrue(CoreStateCodec.decode(file.readBytes()).identity.optedOut)
        assertTrue(File(file.parentFile, "${file.name}.bak").exists())

        // The same actor must not overwrite the committed opt-out from stale
        // memory, and restart recovery must continue to prefer the primary.
        core.identify("user-after-uncertain-fsync")
        assertTrue(core.snapshot().identity.optedOut)
        assertTrue(newCore(store).snapshot().identity.optedOut)
    }

    @Test
    fun `oversized file is bounded and core replaces it with fail-closed state`() {
        val file = File(temporaryFolder.newFolder("oversized"), "core-v1.json")
        RandomAccessFile(file, "rw").use { it.setLength(MAX_PERSISTED_CORE_STATE_BYTES.toLong() + 1) }
        val store = AndroidCoreStateStore.forTesting(file)

        assertThrows(CoreStateCorruptionException::class.java) { store.read() }

        val core =
            IdentityStateCore.forTesting(
                store,
                CoreIdentifierGenerator { prefix -> "${prefix}recovered" },
                CoreEpochClock { FIXED_NOW_MILLIS },
            )
        assertTrue(core.snapshot().identity.optedOut)
        assertTrue(file.length() < MAX_PERSISTED_CORE_STATE_BYTES)
    }

    private fun newCore(store: CoreStateStore): IdentityStateCore =
        IdentityStateCore.forTesting(
            store,
            CoreIdentifierGenerator { prefix -> "${prefix}stable" },
            CoreEpochClock { FIXED_NOW_MILLIS },
        )

    private class FailingBackupDeleteOperations : CoreFileOperations {
        var failBackupDeletes: Boolean = false

        override fun delete(file: File): Boolean =
            if (failBackupDeletes && file.name.endsWith(".bak")) false else file.delete()

        override fun rename(
            source: File,
            destination: File,
        ): Boolean = source.renameTo(destination)
    }

    private class FailingOnceDirectorySync : CoreDirectorySync {
        var failNext: Boolean = false

        override fun sync(directory: File) {
            if (failNext) {
                failNext = false
                throw IOException("injected directory fsync failure")
            }
        }
    }

    private companion object {
        val FIXED_NOW_MILLIS: Long = Instant.parse("2026-08-04T00:00:00.000Z").toEpochMilli()
    }
}
