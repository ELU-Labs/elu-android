package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.config.LocalEndpointPolicy
import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.EluPersistenceMode
import dev.elu.analytics.internal.core.AndroidCoreStateStore
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
        endpointPolicy: LocalEndpointPolicy = LocalEndpointPolicy.CLOUD,
        personProfiles: EluPersonProfilesMode = EluPersonProfilesMode.IDENTIFIED_ONLY,
        persistence: EluPersistenceMode = EluPersistenceMode.PERSISTENT,
        rateLimiting: dev.elu.analytics.EluRateLimitingOptions = dev.elu.analytics.EluRateLimitingOptions(),
    ): Future<RuntimeQueueOwner> {
        val applicationContext = context.applicationContext ?: context
        val databaseFile = databaseFileFor(applicationContext, constructorSiteKey, endpointPolicy).canonicalFile
        val identifiers = UuidCoreIdentifierGenerator
        return RuntimeQueueOwner.open(
            ownershipKey = databaseFile.path,
            limits = limits,
            databaseFactory = {
                if (persistence == EluPersistenceMode.MEMORY) AndroidSQLiteRuntimeDatabase.openMemory()
                else AndroidSQLiteRuntimeDatabase.open(databaseFile)
            },
            memoryOnly = persistence == EluPersistenceMode.MEMORY,
            explicitConsentStore = AndroidExplicitConsentStore(databaseFile,
                // The retired aggregate store is presence-only and belongs to the old cloud namespace.
                if (endpointPolicy == LocalEndpointPolicy.CLOUD) {
                    val legacy = AndroidCoreStateStore.fileFor(applicationContext, constructorSiteKey)
                    listOf(legacy, File(legacy.path + ".bak"), File(legacy.path + ".new"))
                } else emptyList()),
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
            endpointPolicy = endpointPolicy,
            personProfiles = personProfiles,
            rateLimiting = rateLimiting,
            // Only a factory is retained. No directory/writer/handler exists until the future
            // closed exception policy consumer explicitly prepares the original queue intake.
            exceptionSpoolFactory = if (persistence == EluPersistenceMode.PERSISTENT)
                ({ dev.elu.analytics.internal.diagnostics.AndroidExceptionSpool(databaseFile) }) else null,
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
        personProfiles: EluPersonProfilesMode? = null,
        rateLimiting: dev.elu.analytics.EluRateLimitingOptions? = null,
        exceptionSpoolFactory: (() -> dev.elu.analytics.internal.diagnostics.NativeExceptionSpool)? = null,
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
            personProfiles = personProfiles,
            rateLimiting = rateLimiting,
            exceptionSpoolFactory = exceptionSpoolFactory,
        )
    }

    /** Real SQLite fault coverage with the same namespace/file lease and consent store. */
    internal fun openMemoryForTesting(
        databaseFile: File,
        limits: RuntimeQueueLimits,
        faults: AndroidRuntimeDatabaseFaults = AndroidRuntimeDatabaseFaults.None,
    ): Future<RuntimeQueueOwner> {
        val canonical = databaseFile.canonicalFile
        return RuntimeQueueOwner.open(
            ownershipKey = canonical.path,
            limits = limits,
            databaseFactory = { AndroidSQLiteRuntimeDatabase.openMemory(faults) },
            legacyStateLoader = { freshState() },
            leaseFactory = { AndroidFileOwnershipLease.acquire(File(canonical.path + ".lock")) },
            personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            memoryOnly = true,
            explicitConsentStore = AndroidExplicitConsentStore(canonical),
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
        endpointPolicy: LocalEndpointPolicy = LocalEndpointPolicy.CLOUD,
    ): File {
        val siteDirectory = RuntimeSiteNamespace.directory(constructorSiteKey, endpointPolicy)
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
