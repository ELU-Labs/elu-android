package dev.elu.analytics.internal.core

import android.content.Context
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

    /** Returns only after the complete replacement has been durably committed. */
    @Throws(IOException::class)
    fun write(bytes: ByteArray)
}

internal const val MAX_PERSISTED_CORE_STATE_BYTES: Int = 1_048_576

/**
 * Android filesystem implementation using a same-directory write/fsync/rename commit.
 *
 * The previous complete value is retained as a backup until the new value is in place.
 * On process restart, an interrupted commit rolls back to that backup and discards an
 * incomplete staging file. The class deliberately has no analytics-provider dependency.
 */
internal class AndroidCoreStateStore internal constructor(private val file: File) : CoreStateStore {
    constructor(
        context: Context,
        storageNamespace: String,
    ) : this(fileFor(context, storageNamespace))

    private val backupFile = File(file.parentFile, "${file.name}.bak")
    private val stagingFile = File(file.parentFile, "${file.name}.new")

    @Synchronized
    override fun read(): ByteArray? {
        recoverInterruptedCommit()
        if (!file.exists()) return null
        val declaredLength = file.length()
        if (declaredLength < 0 || declaredLength > MAX_PERSISTED_CORE_STATE_BYTES) {
            throw CoreStateCorruptionException("Core state exceeds the maximum persisted size")
        }
        return FileInputStream(file).use { input ->
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
    override fun write(bytes: ByteArray) {
        if (bytes.size > MAX_PERSISTED_CORE_STATE_BYTES) {
            throw IOException("Core state exceeds the maximum persisted size")
        }
        val parent = file.parentFile ?: throw IOException("Core state file must have a parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Could not create core state directory")
        }
        recoverInterruptedCommit()

        try {
            FileOutputStream(stagingFile).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }

            if (file.exists()) {
                if (backupFile.exists() && !backupFile.delete()) {
                    throw IOException("Could not clear stale core state backup")
                }
                if (!file.renameTo(backupFile)) {
                    throw IOException("Could not preserve the previous core state")
                }
            }

            if (!stagingFile.renameTo(file)) {
                restoreBackupAfterFailure()
                throw IOException("Could not atomically install the new core state")
            }

            if (backupFile.exists() && !backupFile.delete()) {
                // Leaving the backup makes the next read safely roll back. Report
                // failure so the in-memory state does not advance ahead of disk.
                throw IOException("Could not finalize the core state commit")
            }
        } catch (error: IOException) {
            if (!file.exists()) restoreBackupAfterFailure()
            throw error
        }
    }

    private fun recoverInterruptedCommit() {
        if (backupFile.exists()) {
            if (file.exists() && !file.delete()) {
                throw IOException("Could not discard an interrupted core state commit")
            }
            if (!backupFile.renameTo(file)) {
                throw IOException("Could not restore the previous core state")
            }
        }
        if (stagingFile.exists() && !stagingFile.delete()) {
            throw IOException("Could not discard an incomplete core state staging file")
        }
    }

    private fun restoreBackupAfterFailure() {
        if (!backupFile.exists()) return
        if (file.exists() && !file.delete()) return
        backupFile.renameTo(file)
    }

    private companion object {
        fun fileFor(
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
