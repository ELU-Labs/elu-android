package dev.elu.analytics.internal.runtime

import android.content.Context
import android.os.SystemClock
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.core.CoreEpochClock
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateStore
import dev.elu.analytics.internal.core.CoreStateWriteOutcome
import dev.elu.analytics.internal.core.IdentityStateCore
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.SystemCoreEpochClock
import dev.elu.analytics.internal.core.UuidCoreIdentifierGenerator
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.Future

/** Android-only factory kept separate from the pure-JVM queue algorithm. */
internal object AndroidRuntimeQueue {
    @Throws(IOException::class)
    fun open(
        context: Context,
        constructorSiteKey: String,
        limits: RuntimeQueueLimits,
    ): Future<RuntimeQueueOwner> = open(context, constructorSiteKey, limits, null)

    /** The original overload retains its behavior; only the facade supplies a setup anchor. */
    internal fun open(
        context: Context,
        constructorSiteKey: String,
        limits: RuntimeQueueLimits,
        freshIdentityStartedAt: Long?,
        readbackProvenReplayTransports: Set<V1ReplayTransport> = emptySet(),
        supportedReplayProtocolGenerations: Set<String> = emptySet(),
        assertStartupCurrent: () -> Unit = {},
    ): Future<RuntimeQueueOwner> {
        val applicationContext = context.applicationContext ?: context
        val databaseFile = databaseFileFor(applicationContext, constructorSiteKey).canonicalFile
        val identifiers = UuidCoreIdentifierGenerator
        return RuntimeQueueOwner.open(
            ownershipKey = databaseFile.path,
            limits = limits,
            databaseFactory = { AndroidSQLiteRuntimeDatabase.open(databaseFile) },
            legacyStateLoader = {
                freshState(identifiers, SystemCoreEpochClock, freshIdentityStartedAt)
            },
            identifiers = identifiers,
            leaseFactory = { AndroidFileOwnershipLease.acquire(File(databaseFile.path + ".lock")) },
            trustedSiteKey = constructorSiteKey,
            captureClock = AndroidRuntimeCaptureClock,
            readbackProvenReplayTransports = readbackProvenReplayTransports,
            supportedReplayProtocolGenerations = supportedReplayProtocolGenerations,
            assertStartupCurrent = assertStartupCurrent,
        )
    }

    internal fun openForTesting(
        databaseFile: File,
        limits: RuntimeQueueLimits,
        legacyStateLoader: () -> PersistedCoreState,
        identifiers: CoreIdentifierGenerator = UuidCoreIdentifierGenerator,
        faults: AndroidRuntimeDatabaseFaults = AndroidRuntimeDatabaseFaults.None,
        trustedSiteKey: String? = null,
        captureClock: RuntimeCaptureClock = JvmRuntimeCaptureClock,
    ): Future<RuntimeQueueOwner> {
        val canonical = databaseFile.canonicalFile
        return RuntimeQueueOwner.open(
            ownershipKey = canonical.path,
            limits = limits,
            databaseFactory = { AndroidSQLiteRuntimeDatabase.open(canonical, faults) },
            legacyStateLoader = legacyStateLoader,
            identifiers = identifiers,
            leaseFactory = { AndroidFileOwnershipLease.acquire(File(canonical.path + ".lock")) },
            trustedSiteKey = trustedSiteKey,
            captureClock = captureClock,
        )
    }

    /** A new owned SQLite installation never opens pre-release aggregate or provider files. */
    internal fun freshState(
        identifiers: CoreIdentifierGenerator = UuidCoreIdentifierGenerator,
        clock: CoreEpochClock = SystemCoreEpochClock,
        freshIdentityStartedAt: Long? = null,
    ): PersistedCoreState {
        val bootstrapClock = CoreEpochClock {
            val current = clock.nowEpochMillis()
            require(freshIdentityStartedAt == null || freshIdentityStartedAt <= current) {
                "Fresh identity setup clock moved backwards"
            }
            freshIdentityStartedAt ?: current
        }
        val memoryStore = object : CoreStateStore {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray): CoreStateWriteOutcome = CoreStateWriteOutcome.Durable
        }
        return IdentityStateCore.forTesting(memoryStore, identifiers, bootstrapClock).snapshot()
    }

    internal fun databaseFileFor(
        context: Context,
        constructorSiteKey: String,
    ): File {
        val siteDirectory = RuntimeSiteNamespace.directory(constructorSiteKey)
        return File(File(File(context.noBackupFilesDir, "elu-analytics/runtime"), siteDirectory), "queue-v1.sqlite")
    }

}

private object AndroidRuntimeCaptureClock : RuntimeCaptureClock {
    override fun wallNowEpochMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

private class AndroidFileOwnershipLease private constructor(
    private val randomAccessFile: RandomAccessFile,
    private val lock: FileLock,
) : RuntimeOwnershipLease {
    override fun close() {
        try {
            lock.release()
        } finally {
            randomAccessFile.close()
        }
    }

    companion object {
        fun acquire(file: File): AndroidFileOwnershipLease {
            val parent = file.parentFile ?: throw IOException("Runtime lock must have a parent directory")
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Could not create runtime lock directory")
            }
            val randomAccessFile = RandomAccessFile(file, "rw")
            try {
                val lock =
                    try {
                        randomAccessFile.channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) {
                    throw RuntimeQueueOwnershipException(
                        "Another process already owns this runtime installation namespace",
                    )
                }
                return AndroidFileOwnershipLease(randomAccessFile, lock)
            } catch (error: Throwable) {
                randomAccessFile.close()
                throw error
            }
        }
    }
}
