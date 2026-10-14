package dev.elu.analytics.internal.runtime

import android.os.Process
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/** Called only while the original runtime process/file lease is held. Never opens original SHM. */
internal object AndroidRuntimeDatabasePreflight {
    private val suffixes = listOf("", "-wal", "-journal")
    private val scratchNames = setOf("database.sqlite", "database.sqlite-wal", "database.sqlite-shm", "database.sqlite-journal")

    internal interface Faults {
        fun copied(bytes: Long) = Unit
        fun prepared(snapshot: File) = Unit
        data object None : Faults
    }

    fun <T> withSnapshot(database: File, faults: Faults = Faults.None, inspect: (File) -> T): T {
        val parent = database.parentFile ?: throw IOException("Runtime database parent is missing")
        val scratch = File(parent, database.name + ".preflight-v1")
        // Only this exact reserved directory belongs to this store. Unknown entries refuse cleanup.
        stat(scratch)?.let { cleanup(scratch, it) }
        val originals = suffixes.associateWith { suffix ->
            val path = File(database.path + suffix)
            stat(path)?.also { requireRegular(it) }
        }
        if (originals.getValue("") == null) throw IOException("Runtime database disappeared before preflight")
        val bytes = originals.values.filterNotNull().fold(0L) { total, item -> Math.addExact(total, item.st_size) }
        // No new store-size ceiling. This is the copy's exact minimum, not a promise that SQLite
        // sidecars or unrelated writes cannot exhaust free space afterward. All such failures refuse.
        if (StatFs(parent.path).availableBytes < bytes) throw IOException("Insufficient disk for runtime preflight snapshot")
        Os.mkdir(scratch.path, 448) //0700
        val directory = checkNotNull(stat(scratch))
        requireDirectory(directory)
        var failure: Throwable? = null
        try {
            var copied = 0L
            for ((suffix, original) in originals) if (original != null) {
                requireSame(scratch, directory)
                val source = File(database.path + suffix)
                val destination = File(scratch, "database.sqlite" + suffix)
                val inputFd = Os.open(source.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
                withDescriptor(inputFd) {
                    requireIdentity(original, Os.fstat(inputFd))
                    val outputFd = Os.open(destination.path,
                        OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384) //0600
                    withDescriptor(outputFd) {
                        val buffer = ByteArray(65_536)
                        var remaining = original.st_size
                        while (remaining > 0) {
                            if (Thread.currentThread().isInterrupted) throw IOException("Runtime preflight interrupted")
                            val count = Os.read(inputFd, buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count <= 0) throw IOException("Runtime preflight source shortened")
                            var written = 0
                            while (written < count) {
                                if (Thread.currentThread().isInterrupted) throw IOException("Runtime preflight interrupted")
                                val step = Os.write(outputFd, buffer, written, count - written)
                                if (step <= 0) throw IOException("Runtime preflight copy made no progress")
                                written += step
                            }
                            remaining -= count
                            copied = Math.addExact(copied, count.toLong())
                            faults.copied(copied)
                        }
                        if (Os.read(inputFd, buffer, 0, 1) != 0) throw IOException("Runtime preflight source grew")
                    }
                    requireIdentity(original, Os.fstat(inputFd))
                }
            }
            recheck(database, originals)
            requireSame(scratch, directory)
            val snapshot = File(scratch, "database.sqlite")
            faults.prepared(snapshot)
            val result = inspect(snapshot)
            recheck(database, originals)
            return result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { cleanup(scratch, directory) }
            catch (cleanupFailure: Throwable) {
                if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private inline fun <T> withDescriptor(descriptor: FileDescriptor, block: () -> T): T {
        var failure: Throwable? = null
        try { return block() }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { Os.close(descriptor) }
            catch (closeFailure: Throwable) {
                if (failure != null) failure.addSuppressed(closeFailure) else throw closeFailure
            }
        }
    }

    private fun recheck(database: File, originals: Map<String, StructStat?>) {
        for ((suffix, expected) in originals) {
            val actual = stat(File(database.path + suffix))
            if (expected == null) {
                if (actual != null) throw IOException("Runtime preflight source family changed")
            } else {
                if (actual == null) throw IOException("Runtime preflight source disappeared")
                requireIdentity(expected, actual)
            }
        }
    }

    private fun cleanup(directory: File, original: StructStat) {
        requireDirectory(original)
        requireSame(directory, original)
        val names = directory.list() ?: throw IOException("Cannot inspect runtime preflight directory")
        if (names.any { it !in scratchNames }) throw IOException("Unexpected runtime preflight entry")
        // Preflight the entire bounded set before removing any member; never recurse/follow links.
        val entries = names.associateWith { name ->
            checkNotNull(stat(File(directory, name))).also { requireRegular(it) }
        }
        for ((name, expected) in entries) {
            requireSame(directory, original)
            val path = File(directory, name)
            requireIdentity(expected, stat(path) ?: throw IOException("Runtime preflight entry disappeared"))
            Os.remove(path.path)
        }
        requireSame(directory, original)
        Os.remove(directory.path)
    }

    private fun stat(path: File): StructStat? = try { Os.lstat(path.path) }
    catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw error }

    private fun requireRegular(stat: StructStat) {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L || stat.st_uid != Process.myUid() || stat.st_size < 0)
            throw IOException("Runtime preflight requires an owned single-link regular file")
    }

    private fun requireDirectory(stat: StructStat) {
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() || (stat.st_mode and 511) != 448)
            throw IOException("Runtime preflight requires its private owned directory")
    }

    private fun requireSame(path: File, expected: StructStat) {
        val actual = stat(path) ?: throw IOException("Runtime preflight directory disappeared")
        requireDirectory(actual)
        if (actual.st_dev != expected.st_dev || actual.st_ino != expected.st_ino)
            throw IOException("Runtime preflight directory changed")
    }

    private fun requireIdentity(expected: StructStat, actual: StructStat) {
        requireRegular(actual)
        if (actual.st_dev != expected.st_dev || actual.st_ino != expected.st_ino || actual.st_size != expected.st_size ||
            actual.st_mtime != expected.st_mtime || actual.st_ctime != expected.st_ctime)
            throw IOException("Runtime preflight source identity changed")
    }
}
