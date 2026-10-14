package dev.elu.analytics.internal.runtime

import android.database.sqlite.SQLiteDatabase
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRuntimeDatabasePreflightTest {
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "preflight-test-" + java.util.UUID.randomUUID()).apply { check(mkdir()) }
    private val database = File(root, "original.sqlite")
    private val scratch get() = File(root, "original.sqlite.preflight-v1")
    private val suffixes = listOf("", "-wal", "-shm", "-journal")
    @After fun close() { root.deleteRecursively() }
    private fun family() = suffixes.map { File(database.path + it) }.filter { it.exists() }.associate { it.name to it.readBytes() }
    private fun assertFamily(before: Map<String, ByteArray>) {
        val after = family(); assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after.getValue(name)) }
    }
    private fun restore(bytes: Map<String, ByteArray>) {
        for (suffix in suffixes) {
            val file = File(database.path + suffix)
            bytes[file.name]?.let { file.writeBytes(it) } ?: run { if (file.exists()) assertTrue(file.delete()) }
        }
    }
    private fun sqlite(file: File = database) = SQLiteDatabase.openDatabase(file.path, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    private fun pragma(db: SQLiteDatabase, sql: String) { db.rawQuery(sql, null).use { while (it.moveToNext()) Unit } }
    private fun value(db: SQLiteDatabase) = db.rawQuery("SELECT value FROM test WHERE id=1", null).use { check(it.moveToFirst()); it.getString(0) }

    @Test fun committedWalAndWalHeaderWithoutSidecarsStayByteExactDuringSnapshotRead() {
        for (retainWal in listOf(false, true)) {
            var retained: Map<String, ByteArray>? = null
            sqlite().use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS test(id INTEGER PRIMARY KEY, value TEXT)")
                db.execSQL("INSERT OR REPLACE INTO test VALUES(1,'before')")
                assertTrue(db.enableWriteAheadLogging()); pragma(db, "PRAGMA wal_autocheckpoint=0")
                db.execSQL("UPDATE test SET value='committed'")
                if (retainWal) retained = family().also { assertTrue(it.containsKey(database.name + "-wal")) }
            }
            retained?.let(::restore)
            val before = family()
            if (!retainWal) { assertFalse(before.containsKey(database.name + "-wal")); assertEquals(2, before.getValue(database.name)[18].toInt()) }
            AndroidRuntimeDatabasePreflight.withSnapshot(database) { snapshot ->
                assertFalse("Original SHM must never be copied", File(snapshot.path + "-shm").exists())
                sqlite(snapshot).use { assertEquals("committed", value(it)) }
            }
            assertFamily(before); assertFalse(scratch.exists())
            suffixes.forEach { File(database.path + it).delete() }
        }
    }

    @Test fun interruptedRollbackJournalRecoversOnlyInsidePrivateCopy() {
        lateinit var interrupted: Map<String, ByteArray>
        sqlite().use { db ->
            db.disableWriteAheadLogging(); pragma(db, "PRAGMA synchronous=FULL"); pragma(db, "PRAGMA cache_size=1")
            db.execSQL("CREATE TABLE test(id INTEGER PRIMARY KEY, value TEXT, padding BLOB)")
            repeat(32) { db.execSQL("INSERT INTO test VALUES(?, 'before', zeroblob(8192))", arrayOf(it + 1)) }
            db.beginTransaction()
            try {
                db.execSQL("UPDATE test SET value='uncommitted', padding=zeroblob(16384)")
                interrupted = family()
                val journal = interrupted.getValue(database.name + "-journal")
                assertTrue("Fixture must have a real hot journal header", journal.take(8).any { it.toInt() != 0 })
            } finally { db.endTransaction() }
        }
        restore(interrupted)
        AndroidRuntimeDatabasePreflight.withSnapshot(database) { snapshot -> sqlite(snapshot).use { assertEquals("before", value(it)) } }
        assertFamily(interrupted); assertFalse(scratch.exists())
        // A successful original writable open can subsequently perform the same legitimate recovery.
        sqlite().use { assertEquals("before", value(it)) }
    }

    @Test fun interruptedCopyAndRejectedInspectionLeaveOriginalFamilyAndNoScratch() {
        database.writeBytes(ByteArray(200_000) { (it % 251).toByte() })
        File(database.path + "-wal").writeBytes(ByteArray(123))
        File(database.path + "-shm").writeBytes(byteArrayOf(1, 2, 3))
        val before = family()
        assertThrows(IOException::class.java) {
            AndroidRuntimeDatabasePreflight.withSnapshot(database, object : AndroidRuntimeDatabasePreflight.Faults {
                override fun copied(bytes: Long) { throw IOException("Injected copy failure") }
            }) { error("Must not inspect partial copy") }
        }
        assertFamily(before); assertFalse(scratch.exists())
        var copied = 0L
        assertThrows(IllegalStateException::class.java) {
            AndroidRuntimeDatabasePreflight.withSnapshot(database, object : AndroidRuntimeDatabasePreflight.Faults {
                override fun copied(bytes: Long) { copied = bytes }
            }) { error("Injected invalid metadata") }
        }
        assertEquals(200_123L, copied) // Exact copy cost; SHM excluded.
        assertFamily(before); assertFalse(scratch.exists())
    }

    @Test fun abandonedKnownScratchIsBoundedAndUnknownOrSymlinkEntriesRefuseWithoutDeletion() {
        database.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(scratch.mkdir()); Os.chmod(scratch.path, 448)
        File(scratch, "database.sqlite").writeBytes(byteArrayOf(9))
        AndroidRuntimeDatabasePreflight.withSnapshot(database) { assertArrayEquals(database.readBytes(), it.readBytes()) }
        assertFalse(scratch.exists())
        for (kind in listOf("unknown", "symlink")) {
            assertTrue(scratch.mkdir()); Os.chmod(scratch.path, 448)
            val path = File(scratch, if (kind == "unknown") "unowned" else "database.sqlite")
            if (kind == "unknown") path.writeBytes(byteArrayOf(7))
            else Os.symlink(database.path, path.path)
            assertThrows(IOException::class.java) { AndroidRuntimeDatabasePreflight.withSnapshot(database) { error("Must refuse") } }
            assertTrue(path.exists()); assertArrayEquals(byteArrayOf(1, 2, 3), database.readBytes())
            Os.remove(path.path); Os.remove(scratch.path)
        }
    }

    @Test fun hardlinkScratchEitherMeetsOriginalOsDenialOrExercisesSdkRefusal() {
        database.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(scratch.mkdir()); Os.chmod(scratch.path, 448)
        val path = File(scratch, "database.sqlite")
        try {
            Os.link(database.path, path.path)
        } catch (error: android.system.ErrnoException) {
            // Android app filesystem/SELinux policy may refuse the fixture itself.
            // Only these explicit permission errors establish that separate outcome.
            if (error.errno != android.system.OsConstants.EACCES && error.errno != android.system.OsConstants.EPERM) throw error
            assertFalse(path.exists())
            assertEquals(1L, Os.lstat(database.path).st_nlink)
            assertArrayEquals(byteArrayOf(1, 2, 3), database.readBytes())
            assertTrue(checkNotNull(scratch.list()).isEmpty())
            android.util.Log.i("EluPreflightTest", "hardlink fixture: OS permission denial; SDK hardlink refusal NOT exercised")
            Os.remove(scratch.path)
            return
        }
        assertEquals(2L, Os.lstat(database.path).st_nlink)
        assertThrows(IOException::class.java) { AndroidRuntimeDatabasePreflight.withSnapshot(database) { error("Must refuse") } }
        assertEquals(Os.lstat(database.path).st_ino, Os.lstat(path.path).st_ino)
        assertArrayEquals(byteArrayOf(1, 2, 3), database.readBytes())
        android.util.Log.i("EluPreflightTest", "hardlink fixture: actual SDK refusal exercised")
        Os.remove(path.path); Os.remove(scratch.path)
    }

    @Test fun repeatedSuccessAndCopyFailureCloseBothOriginalDescriptors() {
        database.writeBytes(ByteArray(100_000))
        fun descriptors() = checkNotNull(File("/proc/self/fd").list()).size
        val before = descriptors()
        repeat(40) { index ->
            if (index % 2 == 0) AndroidRuntimeDatabasePreflight.withSnapshot(database) { assertEquals(100_000L, it.length()) }
            else assertThrows(IOException::class.java) {
                AndroidRuntimeDatabasePreflight.withSnapshot(database, object : AndroidRuntimeDatabasePreflight.Faults {
                    override fun copied(bytes: Long) { throw IOException("Interrupted copy") }
                }) { error("Not reached") }
            }
            assertFalse(scratch.exists())
        }
        // Allow unrelated framework descriptors; the former two-handles-per-copy leak exceeds this.
        assertTrue("Raw descriptors leaked", descriptors() <= before + 4)
    }

    @Test fun replacedSourceCannotBeValidatedAsOriginal() {
        database.writeBytes(ByteArray(100))
        assertThrows(IOException::class.java) {
            AndroidRuntimeDatabasePreflight.withSnapshot(database, object : AndroidRuntimeDatabasePreflight.Faults {
                override fun prepared(snapshot: File) {
                    val replacement = File(root, "replacement").apply { writeBytes(ByteArray(100) { 1 }) }
                    Os.rename(replacement.path, database.path)
                }
            }) { Unit }
        }
        assertFalse(scratch.exists())
    }
}
