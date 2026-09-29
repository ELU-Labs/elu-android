package dev.elu.analytics.internal.diagnostics

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import dev.elu.analytics.internal.runtime.MAX_RUNTIME_EXCEPTION_BYTES
import dev.elu.analytics.internal.runtime.RuntimeExceptionReport
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/** One bounded file slot below the original leased runtime namespace. Never used in memory mode. */
internal class AndroidExceptionSpool(databaseFile: File, private val faults: Faults = Faults.None) : NativeExceptionSpool {
    private val directory = File(checkNotNull(databaseFile.parentFile), "exceptions-v1")
    private val temporary = File(directory, "pending")
    private val report = File(directory, "report")
    private val original: StructStat
    internal interface Faults {
        fun afterFileSync() = Unit
        fun afterRename() = Unit
        fun afterRemoval() = Unit
        data object None : Faults
    }
    init {
        if (stat(directory) == null) {
            Os.mkdir(directory.path, 448)
            syncDirectory(checkNotNull(directory.parentFile), privateMode = false)
        }
        original = checkNotNull(stat(directory)); requireDirectory(original, true)
        inspect()
    }
    override fun read(): RuntimeExceptionReport? {
        inspect()
        // A temporary file is never an acknowledged report. Retain it until controlled clear.
        val expected = stat(report) ?: return null
        val bytes = readExact(report, expected)
        inspect()
        return RuntimeExceptionReport.decode(bytes)
    }
    override fun publish(report: RuntimeExceptionReport, mayPublish: () -> Boolean): Boolean {
        val bytes = report.encode()
        inspect()
        if (stat(this.report) != null || stat(temporary) != null) throw IOException("Exception slot is occupied")
        val fd = Os.open(temporary.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
        val written = withDescriptor(fd) {
            requireRegular(Os.fstat(fd))
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(fd, bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Exception write made no progress")
                offset += count
            }
            Os.fsync(fd)
            Os.fstat(fd).also { requireRegular(it) }
        }
        syncDirectory(directory)
        val expected = checkNotNull(stat(temporary))
        requireIdentity(written, expected)
        if (!readExact(temporary, expected).contentEquals(bytes)) throw IOException("Exception write changed")
        faults.afterFileSync()
        inspect()
        if (!mayPublish()) return false
        // Only the one original writer can publish while the original queue lease is held.
        requireIdentity(expected, checkNotNull(stat(temporary)))
        if (stat(this.report) != null) throw IOException("Exception publication would overwrite")
        Os.rename(temporary.path, this.report.path)
        faults.afterRename()
        syncDirectory(directory)
        val published = checkNotNull(stat(this.report))
        requireRegular(published)
        if (published.st_dev != expected.st_dev || published.st_ino != expected.st_ino ||
            !readExact(this.report, published).contentEquals(bytes)) throw IOException("Published exception changed")
        return true
    }
    override fun clear() {
        val files = inspect()
        for ((path, expected) in files) {
            requireOriginalDirectory()
            requireIdentity(expected, stat(path) ?: throw IOException("Exception entry disappeared"))
            Os.remove(path.path)
        }
        faults.afterRemoval()
        syncDirectory(directory)
        if (inspect().isNotEmpty()) throw IOException("Exception slot did not settle")
    }
    private fun inspect(): Map<File, StructStat> {
        requireOriginalDirectory()
        val names = directory.list() ?: throw IOException("Cannot inspect exception slot")
        if (names.size > 2 || names.any { it != "pending" && it != "report" }) throw IOException("Foreign exception entry")
        return names.associate { name ->
            val path = File(directory, name)
            path to checkNotNull(stat(path)).also { requireRegular(it) }
        }.also { requireOriginalDirectory() }
    }
    private fun readExact(path: File, expected: StructStat): ByteArray {
        requireRegular(expected)
        if (expected.st_size !in 1..MAX_RUNTIME_EXCEPTION_BYTES.toLong()) throw IOException("Invalid exception file length")
        val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        return withDescriptor(fd) {
            requireIdentity(expected, Os.fstat(fd))
            val bytes = ByteArray(expected.st_size.toInt()); var offset = 0
            while (offset < bytes.size) {
                val count = Os.read(fd, bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Exception file shortened")
                offset += count
            }
            if (Os.read(fd, ByteArray(1), 0, 1) != 0) throw IOException("Exception file grew")
            requireIdentity(expected, Os.fstat(fd)); requireIdentity(expected, checkNotNull(stat(path)))
            bytes
        }
    }
    private fun requireOriginalDirectory() {
        val current = stat(directory) ?: throw IOException("Exception directory disappeared")
        requireDirectory(current, true)
        if (current.st_dev != original.st_dev || current.st_ino != original.st_ino) throw IOException("Exception directory changed")
    }
    private fun syncDirectory(path: File, privateMode: Boolean = true) {
        val expected = checkNotNull(stat(path)); requireDirectory(expected, privateMode)
        val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        withDescriptor(fd) {
            val actual = Os.fstat(fd); requireDirectory(actual, privateMode)
            if (expected.st_dev != actual.st_dev || expected.st_ino != actual.st_ino) throw IOException("Exception directory changed")
            Os.fsync(fd)
        }
    }
    private fun requireDirectory(value: StructStat, privateMode: Boolean) {
        if (!OsConstants.S_ISDIR(value.st_mode) || value.st_uid != Process.myUid() ||
            (privateMode && (value.st_mode and 511) != 448)) throw IOException("Exception directory is not owned and private")
    }
    private fun requireRegular(value: StructStat) {
        if (!OsConstants.S_ISREG(value.st_mode) || value.st_uid != Process.myUid() || value.st_nlink != 1L ||
            (value.st_mode and 511) != 384 || value.st_size !in 0..MAX_RUNTIME_EXCEPTION_BYTES.toLong())
            throw IOException("Exception file is not a private bounded single-link file")
    }
    private fun requireIdentity(expected: StructStat, actual: StructStat) {
        requireRegular(actual)
        if (expected.st_dev != actual.st_dev || expected.st_ino != actual.st_ino || expected.st_size != actual.st_size ||
            expected.st_mtime != actual.st_mtime || expected.st_ctime != actual.st_ctime) throw IOException("Exception file changed")
    }
    private fun stat(file: File): StructStat? = try { Os.lstat(file.path) }
    catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw error }
    private inline fun <T> withDescriptor(fd: FileDescriptor, body: () -> T): T {
        var failure: Throwable? = null
        try { return body() } catch (error: Throwable) { failure = error; throw error }
        finally { try { Os.close(fd) } catch (error: Throwable) { if (failure != null) failure.addSuppressed(error) else throw error } }
    }
}
