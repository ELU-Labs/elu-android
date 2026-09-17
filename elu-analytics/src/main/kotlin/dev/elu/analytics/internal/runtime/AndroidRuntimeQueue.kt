package dev.elu.analytics.internal.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Xml
import dev.elu.analytics.internal.compat.BoundedAndroidLegacyStartupSource
import dev.elu.analytics.internal.compat.LegacyAndroidStartupMigration
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.core.AndroidCoreStateStore
import dev.elu.analytics.internal.core.CoreEpochClock
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateStore
import dev.elu.analytics.internal.core.CoreStateCorruptionException
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
        // Lazy source construction is essential: committed current SQLite wins without
        // reading or canonicalizing any old provider path on subsequent startup.
        val legacyStore by lazy(LazyThreadSafetyMode.NONE) {
            AndroidCoreStateStore.forProduction(
                AndroidCoreStateStore.fileFor(applicationContext, constructorSiteKey).canonicalFile,
            )
        }
        val startupMigration by lazy(LazyThreadSafetyMode.NONE) {
            val roots = BoundedAndroidLegacyStartupSource.contextDirectories(
                applicationContext.filesDir, applicationContext.cacheDir,
            )
            LegacyAndroidStartupMigration(
                source = BoundedAndroidLegacyStartupSource(
                    roots.data, roots.files, roots.cache,
                    constructorSiteKey, Xml::newPullParser, assertStartupCurrent,
                ),
                identifiers = identifiers,
                clock = SystemCoreEpochClock,
                assertCurrent = assertStartupCurrent,
            )
        }
        return RuntimeQueueOwner.open(
            ownershipKey = databaseFile.path,
            limits = limits,
            databaseFactory = { AndroidSQLiteRuntimeDatabase.open(databaseFile) },
            legacyStateLoader = {
                bootstrapBeforeQueue(legacyStore, { startupMigration.prepare() }, identifiers,
                    SystemCoreEpochClock, freshIdentityStartedAt)
            },
            identifiers = identifiers,
            leaseFactory = { AndroidFileOwnershipLease.acquire(File(databaseFile.path + ".lock")) },
            trustedSiteKey = constructorSiteKey,
            captureClock = AndroidRuntimeCaptureClock,
            readbackProvenReplayTransports = readbackProvenReplayTransports,
            supportedReplayProtocolGenerations = supportedReplayProtocolGenerations,
            startupMigrationCompleter = { pending -> startupMigration.complete(pending) },
            startupHistoryLoader = { completed -> startupMigration.recordsFor(completed) },
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

    /** Existing ELU aggregate precedes the bounded original-provider import. */
    internal fun bootstrapBeforeQueue(
        legacyStore: CoreStateStore,
        migrationLoader: () -> PersistedCoreState?,
        identifiers: CoreIdentifierGenerator,
        clock: CoreEpochClock,
        freshIdentityStartedAt: Long?,
    ): PersistedCoreState {
        // Read the previous ELU aggregate once. Its recovery remains the original core's
        // responsibility; the provider source cannot replace a present or malformed value.
        var readFailure: CoreStateCorruptionException? = null
        val original = try { legacyStore.read()?.copyOf() }
            catch (failure: CoreStateCorruptionException) { readFailure = failure; null }
        val captured = object : CoreStateStore {
            override fun read(): ByteArray? {
                readFailure?.let { throw it }
                return original?.copyOf()
            }
            override fun write(bytes: ByteArray): CoreStateWriteOutcome =
                error("The bootstrap read snapshot cannot be written")
        }
        if (original == null && readFailure == null) migrationLoader()?.let { return it }
        return bootstrapFromLegacy(captured, identifiers, clock, freshIdentityStartedAt)
    }

    internal fun bootstrapFromLegacy(
        legacyStore: CoreStateStore,
        identifiers: CoreIdentifierGenerator = UuidCoreIdentifierGenerator,
        clock: CoreEpochClock = SystemCoreEpochClock,
    ): PersistedCoreState = bootstrapFromLegacy(legacyStore, identifiers, clock, null)

    internal fun bootstrapFromLegacy(
        legacyStore: CoreStateStore,
        identifiers: CoreIdentifierGenerator,
        clock: CoreEpochClock,
        freshIdentityStartedAt: Long?,
    ): PersistedCoreState {
        val bootstrapStore = BootstrapCoreStateStore(legacyStore)
        val bootstrapClock = if (freshIdentityStartedAt == null) clock else CoreEpochClock {
            val current = clock.nowEpochMillis()
            if (bootstrapStore.legacyWasAbsent) {
                require(freshIdentityStartedAt <= current) { "Fresh identity setup clock moved backwards" }
                freshIdentityStartedAt
            } else current
        }
        return IdentityStateCore.forTesting(bootstrapStore, identifiers, bootstrapClock).snapshot()
    }

    internal fun databaseFileFor(
        context: Context,
        constructorSiteKey: String,
    ): File {
        val siteDirectory = RuntimeSiteNamespace.directory(constructorSiteKey)
        return File(File(File(context.noBackupFilesDir, "elu-analytics/runtime"), siteDirectory), "queue-v1.sqlite")
    }

    /**
     * Lets the existing core apply its bounded recovery rules while directing every recovery or
     * fresh-state write to memory. The legacy file is an import source, never a second authority.
     */
    private class BootstrapCoreStateStore(private val legacyStore: CoreStateStore) : CoreStateStore {
        private var memoryBytes: ByteArray? = null
        private var hasMemoryValue: Boolean = false
        var legacyWasAbsent: Boolean = false
            private set

        override fun read(): ByteArray? =
            if (hasMemoryValue) memoryBytes?.copyOf() else legacyStore.read()?.copyOf().also {
                // A thrown read or non-null malformed legacy value must keep recovery time live.
                legacyWasAbsent = it == null
            }

        override fun write(bytes: ByteArray): CoreStateWriteOutcome {
            memoryBytes = bytes.copyOf()
            hasMemoryValue = true
            return CoreStateWriteOutcome.Durable
        }
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
