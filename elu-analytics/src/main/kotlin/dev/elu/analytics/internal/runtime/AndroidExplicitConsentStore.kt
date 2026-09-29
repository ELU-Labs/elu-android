package dev.elu.analytics.internal.runtime

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/** Fixed, small consent-only sidecar under the existing runtime namespace lease. */
internal class AndroidExplicitConsentStore(
    private val database: File,
    private val legacyFiles: List<File> = emptyList(),
    private val faults: Faults = Faults.None,
) : RuntimeExplicitConsentStore {
    internal interface Faults {
        fun afterTruncate() = Unit
        data object None : Faults
    }
    private val directory = checkNotNull(database.parentFile)
    private val file = File(directory, "explicit-consent-v1.json")
    private val temporary = File(directory, "explicit-consent-v1.tmp")

    override fun priorAnalyticsPresent(): Boolean =
        listOf("", "-wal", "-shm", "-journal", ".preflight-v1").any { stat(File(database.path + it)) != null } ||
            legacyFiles.any { stat(it) != null }

    override fun read(): RuntimeExplicitConsent? = withDirectory { identity, _ ->
        val record = stat(file)?.let { RuntimeExplicitConsent.decode(readFile(file, it)) }
        val pending = stat(temporary)
        if (pending != null) requirePrivateFile(pending)
        requireDirectoryIdentity(identity)
        // A crash at any part of the atomic replacement leaves a denial, never a grant.
        if (pending != null) RuntimeExplicitConsent.PENDING_DENIAL else record
    }

    override fun write(record: RuntimeExplicitConsent) = withDirectory { identity, directoryFd ->
        val primary = stat(file)?.also { RuntimeExplicitConsent.decode(readFile(file, it)) }
        val priorTemporary = stat(temporary)?.also { requirePrivateFile(it) }
        val flags = OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW or
            if (priorTemporary == null) OsConstants.O_CREAT or OsConstants.O_EXCL else 0
        withDescriptor(Os.open(temporary.path, flags, 384)) { fd ->
            val original = Os.fstat(fd)
            requirePrivateFile(original)
            if (priorTemporary != null) requireFileIdentity(priorTemporary, original)
            requireDirectoryIdentity(identity)
            // Publish the pending filename before writing the replacement, including failures.
            Os.fsync(directoryFd)
            Os.ftruncate(fd, 0)
            faults.afterTruncate()
            val bytes = record.encode()
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(fd, bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Explicit consent write made no progress")
                offset += count
            }
            Os.fsync(fd)
            requireFileIdentity(Os.fstat(fd), checkNotNull(stat(temporary)))
            requireDirectoryIdentity(identity)
            val currentPrimary = stat(file)
            if (primary == null) {
                if (currentPrimary != null) throw IOException("Explicit consent primary appeared outside its owner")
            } else requireFileIdentity(primary, currentPrimary ?: throw IOException("Explicit consent primary disappeared"))
            Os.rename(temporary.path, file.path)
            Os.fsync(directoryFd)
            requireDirectoryIdentity(identity)
        }
        check(read() == record) { "Explicit consent replacement could not be verified" }
    }

    private fun readFile(path: File, original: StructStat): ByteArray {
        requirePrivateFile(original)
        return withDescriptor(Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)) { fd ->
            requireFileIdentity(original, Os.fstat(fd))
            val bytes = ByteArray(original.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.read(fd, bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Explicit consent file shortened")
                offset += count
            }
            if (Os.read(fd, ByteArray(1), 0, 1) != 0) throw IOException("Explicit consent file grew")
            requireFileIdentity(original, Os.fstat(fd))
            requireFileIdentity(original, stat(path) ?: throw IOException("Explicit consent disappeared"))
            bytes
        }
    }

    private fun <T> withDirectory(block: (StructStat, FileDescriptor) -> T): T {
        val original = stat(directory) ?: throw IOException("Explicit consent namespace is missing")
        requireDirectory(original)
        return withDescriptor(Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY or OsConstants.O_NOFOLLOW, 0)) { fd ->
            val actual = Os.fstat(fd)
            if (actual.st_dev != original.st_dev || actual.st_ino != original.st_ino) throw IOException("Explicit consent namespace changed")
            block(original, fd)
        }
    }

    private fun requireDirectoryIdentity(original: StructStat) {
        val actual = stat(directory) ?: throw IOException("Explicit consent namespace disappeared")
        requireDirectory(actual)
        if (actual.st_dev != original.st_dev || actual.st_ino != original.st_ino) throw IOException("Explicit consent namespace changed")
    }

    private fun requireDirectory(stat: StructStat) {
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid()) throw IOException("Explicit consent namespace is not owned")
    }

    private fun requirePrivateFile(stat: StructStat) {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L || stat.st_uid != Process.myUid() ||
            (stat.st_mode and 511) != 384 || stat.st_size !in 0L..RuntimeExplicitConsent.MAX_BYTES.toLong())
            throw IOException("Explicit consent requires a bounded private single-link file")
    }

    private fun requireFileIdentity(expected: StructStat, actual: StructStat) {
        requirePrivateFile(actual)
        if (actual.st_dev != expected.st_dev || actual.st_ino != expected.st_ino || actual.st_size != expected.st_size ||
            actual.st_mtime != expected.st_mtime || actual.st_ctime != expected.st_ctime)
            throw IOException("Explicit consent file changed")
    }

    private fun stat(path: File): StructStat? = try { Os.lstat(path.path) }
    catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw error }

    private inline fun <T> withDescriptor(fd: FileDescriptor, block: (FileDescriptor) -> T): T {
        var failure: Throwable? = null
        try { return block(fd) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { Os.close(fd) }
            catch (closeFailure: Throwable) { if (failure != null) failure.addSuppressed(closeFailure) else throw closeFailure }
        }
    }
}
