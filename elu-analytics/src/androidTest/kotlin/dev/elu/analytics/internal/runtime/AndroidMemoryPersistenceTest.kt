package dev.elu.analytics.internal.runtime

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.EluPersistenceMode
import dev.elu.analytics.internal.core.AndroidCoreStateStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AndroidMemoryPersistenceTest {
    private val directories = mutableListOf<File>()
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val key = "elu_pk_test_${"M".repeat(26)}"

    @After fun close() {
        owners.forEach { runCatching { it.closeAsync().await() } }
        RuntimeQueueOwner.clearOwnershipForTesting()
        directories.forEach { it.deleteRecursively() }
    }

    @Test fun productionMemoryUsesRealSQLiteWithoutAnalyticsFilesAndDropsAllStateOnClose() {
        val context = context()
        val owner = open(context, EluPersistenceMode.MEMORY)
        val path = AndroidRuntimeQueue.databaseFileFor(context, key)
        val first = owner.snapshot().await()
        val at = RuntimeWallTimestamps.rfc3339(System.currentTimeMillis())
        owner.applyLocal(RuntimeLocalStateChange.RegisterSuperProperties(mapOf("memory_private" to "never-on-disk"), at)).await()
        owner.appendMutations(listOf(RuntimeRecordDraft.Mutation(at,
            RuntimeMutationChange.Identify("memory-only-person", emptyMap(), emptyMap()), versions()))).await()
        owner.ensureFeatureFlagRuntime().await()
        owner.ensurePreparedReplayStorage().await()
        assertEquals(1, owner.snapshot().await().queuedCount)
        assertEquals(setOf(path.name + ".lock"), path.parentFile!!.list()!!.toSet())
        owner.closeAsync().await(); owners.remove(owner)
        val next = open(context, EluPersistenceMode.MEMORY).snapshot().await()
        assertNotEquals(first.state.identity.anonymousId, next.state.identity.anonymousId)
        assertNotEquals(first.person!!.deviceId, next.person!!.deviceId)
        assertNotEquals(first.state.stream.streamId, next.state.stream.streamId)
        assertEquals(0, next.queuedCount)
        assertNull(next.state.identity.userId)
        assertTrue(next.state.identity.superProperties.isEmpty())
        assertTrue(next.exposures!!.digests.isEmpty())
        assertEquals(setOf(path.name + ".lock"), path.parentFile!!.list()!!.toSet())
    }

    @Test fun corruptOldDatabaseFamilyIsNotReadModifiedOrImportedByMemoryMode() {
        val context = context()
        val file = AndroidRuntimeQueue.databaseFileFor(context, key)
        file.parentFile!!.mkdirs()
        val originals = listOf("", "-wal", "-shm", "-journal").associate { suffix ->
            File(file.path + suffix).apply { writeText("unreadable-owned-$suffix") } to "unreadable-owned-$suffix".toByteArray()
        }
        val owner = open(context, EluPersistenceMode.MEMORY)
        assertTrue(owner.snapshot().await().state.identity.optedOut)
        assertEquals(RuntimeExplicitConsent.PENDING_DENIAL, AndroidExplicitConsentStore(file).read())
        originals.forEach { (path, bytes) -> assertArrayEquals(bytes, path.readBytes()) }
        owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, RuntimeWallTimestamps.rfc3339(System.currentTimeMillis()))).await()
        assertFalse(owner.snapshot().await().state.identity.optedOut)
        originals.forEach { (path, bytes) -> assertArrayEquals(bytes, path.readBytes()) }
    }

    @Test fun legacyAggregatePresenceIsUnknownEvenWhenItContainsAnOptOutField() {
        val context = context()
        val legacy = AndroidCoreStateStore.fileFor(context, key)
        legacy.parentFile!!.mkdirs()
        val bytes = "{\"optedOut\":true,\"legacyPrivateId\":\"do-not-read\"}".toByteArray()
        legacy.writeBytes(bytes)
        val owner = open(context, EluPersistenceMode.MEMORY)
        assertTrue(owner.snapshot().await().state.identity.optedOut)
        val file = AndroidRuntimeQueue.databaseFileFor(context, key)
        assertEquals(RuntimeExplicitConsent.PENDING_DENIAL, AndroidExplicitConsentStore(file).read())
        assertArrayEquals(bytes, legacy.readBytes())
    }

    @Test fun bothModesUseOneOriginalFileAndProcessLease() {
        val context = context()
        val first = open(context, EluPersistenceMode.MEMORY)
        assertThrows(Exception::class.java) { open(context, EluPersistenceMode.PERSISTENT) }
        first.closeAsync().await(); owners.remove(first)
        val persistent = open(context, EluPersistenceMode.PERSISTENT)
        assertThrows(Exception::class.java) { open(context, EluPersistenceMode.MEMORY) }
        assertTrue(AndroidRuntimeQueue.databaseFileFor(context, key).exists())
        persistent.closeAsync().await(); owners.remove(persistent)
    }

    @Test fun explicitConsentSurvivesMemoryRestartButAnalyticsIdentityDoesNot() {
        val context = context()
        val first = open(context, EluPersistenceMode.MEMORY)
        val id = first.snapshot().await().state.identity.anonymousId
        first.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, RuntimeWallTimestamps.rfc3339(System.currentTimeMillis()))).await()
        first.closeAsync().await(); owners.remove(first)
        val denied = open(context, EluPersistenceMode.MEMORY)
        assertTrue(denied.snapshot().await().state.identity.optedOut)
        assertNotEquals(id, denied.snapshot().await().state.identity.anonymousId)
        denied.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, RuntimeWallTimestamps.rfc3339(System.currentTimeMillis()))).await()
        denied.closeAsync().await(); owners.remove(denied)
        val allowed = open(context, EluPersistenceMode.MEMORY)
        assertFalse(allowed.snapshot().await().state.identity.optedOut)
        val path = AndroidRuntimeQueue.databaseFileFor(context, key)
        assertEquals(setOf(path.name + ".lock", "explicit-consent-v1.json"), path.parentFile!!.list()!!.toSet())
        val bytes = File(path.parentFile, "explicit-consent-v1.json").readBytes()
        assertEquals(RuntimeExplicitConsent(false, true, false), RuntimeExplicitConsent.decode(bytes))
        assertTrue(bytes.size <= 128)
    }

    @Test fun actualMemoryRollbackAndAmbiguousCommitUseOneConfiguredOriginalConnection() {
        for (ambiguous in listOf(false, true)) {
            val file = AndroidRuntimeQueue.databaseFileFor(context(), key)
            val armed = AtomicBoolean(false)
            val configurations = AtomicInteger()
            val owner = AndroidRuntimeQueue.openMemoryForTesting(file, RuntimeQueueLimits(100, 1_000_000),
                object : AndroidRuntimeDatabaseFaults {
                    override fun connectionConfigured(settings: AndroidRuntimeConnectionSettings) {
                        assertEquals("memory", settings.journalMode); configurations.incrementAndGet()
                    }
                    override fun beforeCommit() {
                        if (!ambiguous && armed.compareAndSet(true, false)) throw IOException("known rollback")
                    }
                    override fun afterCommit() {
                        if (ambiguous && armed.compareAndSet(true, false)) throw IOException("lost commit acknowledgement")
                    }
                }).await().also { owners += it }
            val before = owner.snapshot().await()
            armed.set(true)
            val append = owner.appendMutations(listOf(RuntimeRecordDraft.Mutation(
                RuntimeWallTimestamps.rfc3339(System.currentTimeMillis()),
                RuntimeMutationChange.Identify("memory-transaction", emptyMap(), emptyMap()), versions())))
            if (ambiguous) {
                assertTrue(append.await() is RuntimeAppendResult.Accepted)
                assertEquals(1, owner.snapshot().await().queuedCount)
                assertEquals("memory-transaction", owner.snapshot().await().state.identity.userId)
            } else {
                assertThrows(Exception::class.java) { append.await() }
                assertEquals(before, owner.snapshot().await())
            }
            assertEquals(1, configurations.get())
            assertEquals(setOf(file.name + ".lock"), file.parentFile!!.list()!!.toSet())
        }
    }

    @Test fun interruptedPendingRewriteKeepsOriginalInodeAndCannotReviveOlderGrant() {
        val file = AndroidRuntimeQueue.databaseFileFor(context(), key)
        file.parentFile!!.mkdirs()
        val store = AndroidExplicitConsentStore(file)
        store.write(RuntimeExplicitConsent(false, true, true))
        val pending = File(file.parentFile, "explicit-consent-v1.tmp")
        pending.writeBytes(RuntimeExplicitConsent.PENDING_DENIAL.encode()); Os.chmod(pending.path, 384)
        val inode = Os.lstat(pending.path).st_ino
        val failing = AndroidExplicitConsentStore(file, faults = object : AndroidExplicitConsentStore.Faults {
            override fun afterTruncate() { throw IOException("injected interruption") }
        })
        assertThrows(IOException::class.java) { failing.write(RuntimeExplicitConsent(false, false, false)) }
        assertEquals(inode, Os.lstat(pending.path).st_ino)
        assertEquals(0L, pending.length())
        assertEquals(RuntimeExplicitConsent.PENDING_DENIAL, store.read())
        store.write(RuntimeExplicitConsent(true, true, false))
        assertFalse(pending.exists())
        assertEquals(RuntimeExplicitConsent(true, true, false), store.read())
    }

    @Test fun consentStoreRejectsMalformedOversizedAndSymlinkRecordsWithoutDeletingThem() {
        val file = AndroidRuntimeQueue.databaseFileFor(context(), key)
        file.parentFile!!.mkdirs()
        val record = File(file.parentFile, "explicit-consent-v1.json")
        val store = AndroidExplicitConsentStore(file)
        for (bytes in listOf("{}".toByteArray(), ByteArray(129) { 65 })) {
            record.writeBytes(bytes); Os.chmod(record.path, 384)
            assertThrows(Exception::class.java) { store.read() }
            assertArrayEquals(bytes, record.readBytes())
        }
        assertTrue(record.delete())
        val target = File(file.parentFile, "private-target").apply { writeText("untouched") }
        Os.symlink(target.path, record.path)
        assertThrows(Exception::class.java) { store.read() }
        assertEquals(target.path, Os.readlink(record.path))
        assertEquals("untouched", target.readText())
    }

    private fun context(): Context {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(original.cacheDir, "memory-mode-${UUID.randomUUID()}").apply { mkdirs() }
        directories += directory
        return object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    private fun open(context: Context, persistence: EluPersistenceMode): RuntimeQueueOwner =
        AndroidRuntimeQueue.open(context, key, RuntimeQueueLimits(100, 1_000_000), null,
            persistence = persistence).await().also { owners += it }

    private fun versions() = RuntimeVersions(platform = RuntimePlatform.ANDROID,
        runtime = RuntimeVersionComponent("elu-android", "0.2.0"), facade = RuntimeVersionComponent("Elu", "0.2.0"))
    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)
}
