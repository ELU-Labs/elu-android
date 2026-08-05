package dev.elu.analytics.internal.core

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Minimal durable boundary used by [IdentityStateCore]. */
internal interface CoreStateStore {
    @Throws(IOException::class)
    fun read(): ByteArray?

    /**
     * Pre-commit failures throw. A failure after the primary rename is returned
     * explicitly so the caller can advance its in-memory aggregate before
     * surfacing the durability uncertainty.
     */
    @Throws(IOException::class)
    fun write(bytes: ByteArray): CoreStateWriteOutcome
}

internal sealed interface CoreStateWriteOutcome {
    data object Durable : CoreStateWriteOutcome

    /** The primary is authoritative, but its directory entry could not be fsynced. */
    data class CommittedWithDurabilityFailure(val failure: IOException) : CoreStateWriteOutcome
}

internal const val MAX_PERSISTED_CORE_STATE_BYTES: Int = 1_048_576

/** Injectable because local JVM tests cannot call Android's directory fsync APIs. */
internal fun interface CoreDirectorySync {
    @Throws(IOException::class)
    fun sync(directory: File)
}

internal object AndroidCoreDirectorySync : CoreDirectorySync {
    override fun sync(directory: File) {
        val descriptor =
            try {
                Os.open(
                    directory.absolutePath,
                    OsConstants.O_RDONLY,
                    0,
                )
            } catch (error: ErrnoException) {
                throw IOException("Could not open the core state directory for fsync", error)
            }
        try {
            Os.fsync(descriptor)
        } catch (error: ErrnoException) {
            throw IOException("Could not fsync the core state directory", error)
        } finally {
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // fsync already established the durability boundary. A close
                // failure is not allowed to turn a committed write into a
                // reported rollback.
            }
        }
    }
}

internal interface CoreFileOperations {
    fun delete(file: File): Boolean

    fun rename(
        source: File,
        destination: File,
    ): Boolean
}

internal object SystemCoreFileOperations : CoreFileOperations {
    override fun delete(file: File): Boolean = file.delete()

    override fun rename(
        source: File,
        destination: File,
    ): Boolean = source.renameTo(destination)
}

/**
 * Android filesystem implementation using a same-directory write/fsync/rename commit.
 *
 * The previous complete value is retained as a backup until the new value is in place.
 * Staging-to-primary rename is the commit point: recovery restores a backup only when
 * no primary exists and never rolls a committed privacy state backward. The class
 * deliberately has no analytics-provider dependency.
 */
internal class AndroidCoreStateStore private constructor(
    private val file: File,
    private val directorySync: CoreDirectorySync,
    private val fileOperations: CoreFileOperations,
) : CoreStateStore {

    private val backupFile = File(file.parentFile, "${file.name}.bak")
    private val stagingFile = File(file.parentFile, "${file.name}.new")

    @Synchronized
    override fun read(): ByteArray? {
        recoverInterruptedCommit()
        if (!file.exists()) return null
        return readBoundedBytes(file)
    }

    private fun readBoundedBytes(candidate: File): ByteArray {
        val declaredLength = candidate.length()
        if (declaredLength < 0 || declaredLength > MAX_PERSISTED_CORE_STATE_BYTES) {
            throw CoreStateCorruptionException("Core state exceeds the maximum persisted size")
        }
        return FileInputStream(candidate).use { input ->
            val output = ByteArrayOutputStream(declaredLength.toInt().coerceAtLeast(32))
            val chunk = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_PERSISTED_CORE_STATE_BYTES) {
                    throw CoreStateCorruptionException("Core state exceeds the maximum persisted size")
                }
                output.write(chunk, 0, read)
            }
            output.toByteArray()
        }
    }

    @Synchronized
    override fun write(bytes: ByteArray): CoreStateWriteOutcome {
        if (bytes.size > MAX_PERSISTED_CORE_STATE_BYTES) {
            throw IOException("Core state exceeds the maximum persisted size")
        }
        val parent = file.parentFile ?: throw IOException("Core state file must have a parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Could not create core state directory")
        }
        recoverInterruptedCommit()

        FileOutputStream(stagingFile).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }

        val hadPrevious = file.exists()
        if (hadPrevious) {
            if (backupFile.exists()) {
                guardBackupBeforeMutation()
                if (!fileOperations.delete(backupFile)) {
                    throw IOException("Could not clear stale core state backup")
                }
            }
            if (!fileOperations.rename(file, backupFile)) {
                throw IOException("Could not preserve the previous core state")
            }
        }

        if (!fileOperations.rename(stagingFile, file)) {
            restoreBackupAfterFailure(parent, hadPrevious)
            throw IOException("Could not atomically install the new core state")
        }

        try {
            // The staging-to-primary rename is the commit point. Persist its
            // directory entry before exposing the new aggregate in memory.
            directorySync.sync(parent)
        } catch (error: IOException) {
            // The primary rename already committed the new privacy state. Never
            // roll it back to the older backup. The caller must install the new
            // aggregate in memory before surfacing this explicit uncertainty.
            return CoreStateWriteOutcome.CommittedWithDurabilityFailure(
                IOException(
                    "Core state was committed, but directory durability could not be confirmed",
                    error,
                ),
            )
        }

        // The new primary is committed. Backup cleanup is best effort and may
        // not turn success into a reported failure: recovery always prefers an
        // installed primary, so a stale backup can never relax privacy state.
        if (backupFile.exists() && fileOperations.delete(backupFile)) {
            try {
                directorySync.sync(parent)
            } catch (_: IOException) {
                // A crash may resurrect the backup entry. Recovery retains the
                // committed primary when both files are present.
            }
        }
        return CoreStateWriteOutcome.Durable
    }

    private fun recoverInterruptedCommit() {
        val parent = file.parentFile ?: throw IOException("Core state file must have a parent directory")
        if (backupFile.exists()) {
            guardBackupBeforeMutation()
        }
        if (file.exists()) {
            // Both files means staging was already renamed into place. That
            // rename is the commit point, so never replace the newer primary
            // with an older (possibly opted-in) backup. If payload validation
            // later rejects the primary, IdentityStateCore rotates fail closed
            // instead of relaxing privacy by guessing that the backup is safe.
            if (backupFile.exists() && fileOperations.delete(backupFile)) {
                try {
                    directorySync.sync(parent)
                } catch (_: IOException) {
                    // Safe to retry later; reads continue from the primary.
                }
            }
        } else if (backupFile.exists()) {
            if (!fileOperations.rename(backupFile, file)) {
                throw IOException("Could not restore the previous core state")
            }
            directorySync.sync(parent)
        }
        if (stagingFile.exists()) {
            // A staging file is never authoritative. Failure to clean it does
            // not prevent reading the committed primary and a later write will
            // truncate it before use.
            fileOperations.delete(stagingFile)
        }
    }

    /**
     * A backup is never loaded while a primary exists, but a downgraded SDK
     * must also never destroy bytes written by a newer schema. Corrupt and
     * ordinary v1 backups remain disposable after a complete bounded read.
     * Forward-schema signals and unreadable/oversized backups are propagated
     * before any destructive cleanup can occur.
     */
    private fun guardBackupBeforeMutation() {
        // If the backup cannot be read within this SDK's bound, preserve it:
        // a newer schema may legitimately use a larger cap. Only corruption
        // discovered after a complete bounded read is safe to ignore here.
        val bytes = readBoundedBytes(backupFile)
        try {
            CoreStateCodec.decode(bytes)
        } catch (_: CoreStateCorruptionException) {
            // A damaged envelope can still contain an independently parseable
            // future child. Recovery intentionally rethrows only the forward
            // schema exceptions and ignores ordinary corruption.
            CoreStateCodec.recoverableRecords(bytes)
        }
    }

    private fun restoreBackupAfterFailure(
        parent: File,
        hadPrevious: Boolean,
    ) {
        if (file.exists() && !fileOperations.delete(file)) {
            throw IOException("Could not remove the uncommitted core state")
        }
        if (hadPrevious) {
            if (!backupFile.exists() || !fileOperations.rename(backupFile, file)) {
                throw IOException("Could not restore the previous core state")
            }
        }
        directorySync.sync(parent)
    }

    internal companion object {
        internal fun forProduction(file: File): AndroidCoreStateStore =
            AndroidCoreStateStore(file, AndroidCoreDirectorySync, SystemCoreFileOperations)

        internal fun forTesting(
            file: File,
            directorySync: CoreDirectorySync = CoreDirectorySync { },
            fileOperations: CoreFileOperations = SystemCoreFileOperations,
        ): AndroidCoreStateStore = AndroidCoreStateStore(file, directorySync, fileOperations)

        internal fun fileFor(
            context: Context,
            storageNamespace: String,
        ): File {
            require(storageNamespace.isNotEmpty()) { "storageNamespace must not be empty" }
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(storageNamespace.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return File(File(context.noBackupFilesDir, "elu-analytics/core"), "$digest-v1.json")
        }
    }
}
