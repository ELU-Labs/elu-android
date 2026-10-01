package dev.elu.analytics.internal.diagnostics

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.internal.runtime.*
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AndroidExceptionSpoolTest {
    private val roots = mutableListOf<File>()
    @After fun cleanup() { roots.forEach { it.deleteRecursively() } }
    private fun file(): File {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "exception-spool-${UUID.randomUUID()}")
        assertTrue(root.mkdirs()); roots += root
        return File(root, "queue.sqlite")
    }
    private fun report() = RuntimeExceptionReport(RuntimeExceptionReservation(UUID.randomUUID().toString(), "a".repeat(64),
        "stream", "anonymous", 1, 2, "b".repeat(64), 1000, 2000), 1001, "java.lang.IllegalStateException", false)
    @Test fun exactDurableSlotReopensAndNeverOverwrites() {
        val file = file(); val spool = AndroidExceptionSpool(file); val value = report()
        assertTrue(spool.publish(value) { true }); assertEquals(value, AndroidExceptionSpool(file).read())
        assertThrows(IOException::class.java) { spool.publish(report()) { true } }
        val directory = File(file.parentFile, "exceptions-v1")
        assertEquals(448, Os.lstat(directory.path).st_mode and 511)
        assertEquals(384, Os.lstat(File(directory, "report").path).st_mode and 511)
        spool.clear(); assertNull(AndroidExceptionSpool(file).read()); assertTrue(directory.list()!!.isEmpty())
    }
    @Test fun interruptedBeforeRenameIsNeverImportedAndInterruptionAfterRenameHasExactBytes() {
        for (afterRename in listOf(false, true)) {
            val file = file(); val value = report()
            val spool = AndroidExceptionSpool(file, object : AndroidExceptionSpool.Faults {
                override fun afterFileSync() { if (!afterRename) throw IOException("before rename") }
                override fun afterRename() { if (afterRename) throw IOException("after rename") }
            })
            assertThrows(IOException::class.java) { spool.publish(value) { true } }
            val reopened = AndroidExceptionSpool(file)
            assertEquals(if (afterRename) value else null, reopened.read())
            reopened.clear(); assertNull(reopened.read())
        }
    }
    @Test fun finalPermissionCheckAndDeletionInterruptionDoNotInventSuccess() {
        val file = file(); val value = report(); var checks = 0
        val spool = AndroidExceptionSpool(file)
        assertFalse(spool.publish(value) { checks++; false }); assertEquals(1, checks); assertNull(spool.read())
        spool.clear(); assertTrue(spool.publish(value) { true })
        val interrupted = AndroidExceptionSpool(file, object : AndroidExceptionSpool.Faults {
            override fun afterRemoval() { throw IOException("before directory fsync") }
        })
        assertThrows(IOException::class.java) { interrupted.clear() }
        spool.clear(); assertNull(spool.read())
    }
    @Test fun spoolOnlyPresenceIsUnknownAnalyticsForMemoryConsentWithoutReadingItsContents() {
        val file = file(); val consent = AndroidExplicitConsentStore(file)
        assertFalse(consent.priorAnalyticsPresent())
        AndroidExceptionSpool(file)
        assertFalse(file.exists()); assertTrue(consent.priorAnalyticsPresent())
        File(file.parentFile, "exceptions-v1/unreadable").writeBytes(ByteArray(1))
        assertTrue(consent.priorAnalyticsPresent())
    }
    @Test fun unknownSymlinkOversizeAndForeignModesRefuseWithoutRemovingPrivateFiles() {
        for (kind in listOf("unknown", "symlink", "oversize", "mode")) {
            val file = file(); val spool = AndroidExceptionSpool(file)
            val directory = File(file.parentFile, "exceptions-v1")
            val original = File(file.parentFile, "original").apply { writeText("PRIVATE") }
            when (kind) {
                "unknown" -> File(directory, "unowned").writeText("PRIVATE")
                "symlink" -> Os.symlink(original.path, File(directory, "report").path)
                "oversize" -> { File(directory, "report").writeBytes(ByteArray(4097)); Os.chmod(File(directory, "report").path, 384) }
                "mode" -> { File(directory, "report").writeText("{}"); Os.chmod(File(directory, "report").path, 420) }
            }
            assertThrows(IOException::class.java) { spool.read() }
            assertThrows(IOException::class.java) { spool.clear() }
            assertEquals("PRIVATE", original.readText()); assertFalse(directory.list()!!.isEmpty())
        }
    }
}
