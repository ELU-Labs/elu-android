package dev.elu.analytics.internal.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.SessionState
import dev.elu.analytics.internal.core.StreamState
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AndroidRuntimeQueueInstrumentationTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val testDirectories = mutableListOf<File>()

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        owners.asReversed().forEach { owner -> runCatching { owner.closeAsync().await() } }
        RuntimeQueueOwner.clearOwnershipForTesting()
        testDirectories.asReversed().forEach { directory -> directory.deleteRecursively() }
    }

    @Test
    fun constructorSiteKeySelectsExactHashedDirectoryWithoutNormalization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = AndroidRuntimeQueue.databaseFileFor(context, "elu_pk_test_capture")
        val other = AndroidRuntimeQueue.databaseFileFor(context, " elu_pk_test_capture ")

        assertEquals(
            "site-0d28cb28b0d301938550ddaf297a1c9b59a78c1d02534cf2be40aef423d6b943",
            first.parentFile?.name,
        )
        assertEquals("queue-v1.sqlite", first.name)
        assertNotEquals(first.parentFile?.name, other.parentFile?.name)
    }

    @Test
    fun selfHostedOriginsUseSeparateRealSQLiteFilesAndCanonicalOriginReopensOriginal() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(original.cacheDir, "origin-isolation-${UUID.randomUUID()}").apply { mkdirs() }
        testDirectories += root
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val key = "elu_pk_test_${"A".repeat(26)}"
        val cloud = dev.elu.analytics.internal.config.LocalEndpointPolicy.CLOUD
        val a = dev.elu.analytics.internal.config.LocalEndpointPolicy.fromApiHost("https://a.example.com")
        val b = dev.elu.analytics.internal.config.LocalEndpointPolicy.fromApiHost("https://b.example.com")
        fun openOrigin(policy: dev.elu.analytics.internal.config.LocalEndpointPolicy): RuntimeQueueOwner =
            AndroidRuntimeQueue.open(context, key, RuntimeQueueLimits(100, 1_000_000), null,
                endpointPolicy = policy).await().also { owners += it }
        val originalOwners = listOf(openOrigin(cloud), openOrigin(a), openOrigin(b))
        originalOwners.forEach { it.ensureFeatureFlagRuntime().await() }
        val originalStates = originalOwners.map { it.snapshot().await() }
        assertEquals(3, originalStates.map { it.state.identity.anonymousId }.toSet().size)
        val at = RuntimeWallTimestamps.rfc3339(System.currentTimeMillis())
        assertTrue(originalOwners[1].appendMutations(listOf(RuntimeRecordDraft.Mutation(at,
            RuntimeMutationChange.Identify("only-origin-a", emptyMap(), emptyMap()), versions()))).await() is RuntimeAppendResult.Accepted)
        assertEquals(1, originalOwners[1].snapshot().await().queuedCount)
        for (index in listOf(0, 2)) {
            assertEquals(originalStates[index].state.identity, originalOwners[index].snapshot().await().state.identity)
            assertEquals(0, originalOwners[index].snapshot().await().queuedCount)
        }
        val paths = listOf(cloud, a, b).map { AndroidRuntimeQueue.databaseFileFor(context, key, it).canonicalPath }
        assertEquals(3, paths.toSet().size)
        originalOwners[1].closeAsync().await(); owners.remove(originalOwners[1])
        val normalized = dev.elu.analytics.internal.config.LocalEndpointPolicy.fromApiHost(" HTTPS://A.EXAMPLE.COM/ ")
        val reopened = openOrigin(normalized)
        assertEquals(paths[1], AndroidRuntimeQueue.databaseFileFor(context, key, normalized).canonicalPath)
        assertEquals("only-origin-a", reopened.snapshot().await().state.identity.userId)
        assertEquals(originalStates[1].state.identity.anonymousId, reopened.snapshot().await().state.identity.anonymousId)
        assertEquals(1, reopened.snapshot().await().queuedCount)
    }

    @Test
    fun selfHostedPrefixesUseSeparateRealSQLiteFilesAndCanonicalBaseReopensOriginal() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(original.cacheDir, "origin-isolation-${UUID.randomUUID()}").apply { mkdirs() }
        testDirectories += root
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val key = "elu_pk_test_${"A".repeat(26)}"
        val cloud = dev.elu.analytics.internal.config.LocalEndpointPolicy.CLOUD
        val a = dev.elu.analytics.internal.config.LocalEndpointPolicy.fromApiHost("https://same.example.com/a")
        val b = dev.elu.analytics.internal.config.LocalEndpointPolicy.fromApiHost("https://same.example.com/b")
        fun openOrigin(policy: dev.elu.analytics.internal.config.LocalEndpointPolicy): RuntimeQueueOwner =
            AndroidRuntimeQueue.open(context, key, RuntimeQueueLimits(100, 1_000_000), null,
                endpointPolicy = policy).await().also { owners += it }
        val rootBase = dev.elu.analytics.internal.config.LocalEndpointPolicy.fromApiHost("https://same.example.com")
        val originalOwners = listOf(openOrigin(cloud), openOrigin(a), openOrigin(b), openOrigin(rootBase))
        originalOwners.forEach { it.ensureFeatureFlagRuntime().await() }
        val originalStates = originalOwners.map { it.snapshot().await() }
        assertEquals(4, originalStates.map { it.state.identity.anonymousId }.toSet().size)
        val at = RuntimeWallTimestamps.rfc3339(System.currentTimeMillis())
        assertTrue(originalOwners[1].appendMutations(listOf(RuntimeRecordDraft.Mutation(at,
            RuntimeMutationChange.Identify("only-origin-a", emptyMap(), emptyMap()), versions()))).await() is RuntimeAppendResult.Accepted)
        assertEquals(1, originalOwners[1].snapshot().await().queuedCount)
        for (index in listOf(0, 2, 3)) {
            assertEquals(originalStates[index].state.identity, originalOwners[index].snapshot().await().state.identity)
            assertEquals(0, originalOwners[index].snapshot().await().queuedCount)
        }
        val paths = listOf(cloud, a, b, rootBase).map { AndroidRuntimeQueue.databaseFileFor(context, key, it).canonicalPath }
        assertEquals(4, paths.toSet().size)
        originalOwners[1].closeAsync().await(); owners.remove(originalOwners[1])
        val normalized = dev.elu.analytics.internal.config.LocalEndpointPolicy.fromApiHost(" HTTPS://SAME.EXAMPLE.COM/a/ ")
        val reopened = openOrigin(normalized)
        assertEquals(paths[1], AndroidRuntimeQueue.databaseFileFor(context, key, normalized).canonicalPath)
        assertEquals("only-origin-a", reopened.snapshot().await().state.identity.userId)
        assertEquals(originalStates[1].state.identity.anonymousId, reopened.snapshot().await().state.identity.anonymousId)
        assertEquals(1, reopened.snapshot().await().queuedCount)
    }

    @Test
    fun cleanSetupAndReopenNeverAccessUnrelatedAppStorage() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(original.cacheDir, "clean-setup-${UUID.randomUUID()}").apply { mkdirs() }
        testDirectories += root
        val unrelated = File(root, "unrelated-application-data").apply { writeText("must remain byte-for-byte intact") }
        val before = unrelated.readBytes()
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "owned").apply { mkdirs() }
            override fun getFilesDir(): File = error("Clean setup must not open aggregate or preview files")
            override fun getCacheDir(): File = error("Clean setup must not open preview queues")
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                error("Clean setup must not open preview preferences")
        }
        val owner = AndroidRuntimeQueue.open(context, "elu_pk_clean_setup", RuntimeQueueLimits(100, 1_000_000)).await()
        owners += owner
        val first = owner.snapshot().await()
        assertEquals(null, first.state.identity.userId)
        assertEquals(0, first.queuedCount)
        owner.closeAsync().await()
        owners.remove(owner)
        val reopened = AndroidRuntimeQueue.open(context, "elu_pk_clean_setup", RuntimeQueueLimits(100, 1_000_000)).await()
        owners += reopened
        assertEquals(first.state, reopened.snapshot().await().state)
        assertArrayEquals(before, unrelated.readBytes())
    }

    @Test
    fun sqliteRollbackAmbiguousCommitReopenAndRestartPreserveOneStream() {
        val file = databaseFile()
        val faults = RecordingFaults()
        val identifiers = CountingIdentifiers()
        val loaderThreads = mutableListOf<Thread>()
        val owner =
            open(
                file = file,
                identifiers = identifiers,
                faults = faults,
                stateLoader = {
                    loaderThreads += Thread.currentThread()
                    freshState()
                },
            )

        faults.failBeforeCommit.set(true)
        assertFutureCause(IOException::class.java) {
            owner.appendMutations(
                listOf(mutation(RuntimeMutationChange.Identify("rolled-back", emptyMap(), emptyMap()))),
            ).await()
        }
        val rolledBack = owner.snapshot().await()
        assertEquals(null, rolledBack.state.identity.userId)
        assertEquals(0L, rolledBack.state.stream.nextSequence)
        assertEquals(0, rolledBack.queuedCount)

        val first = appendEvents(owner, event("first")) as RuntimeAppendResult.Accepted
        assertEquals(0L, first.records.single().sequence)

        faults.failAfterCommit.set(true)
        val second = appendEvents(owner, event("second")) as RuntimeAppendResult.Accepted
        assertEquals(1L, second.records.single().sequence)
        assertEquals(2L, second.snapshot.state.stream.nextSequence)
        assertEquals(2, second.snapshot.queuedCount)
        assertEquals(0, identifiers.generatedCalls)
        assertTrue(loaderThreads.all { it !== Looper.getMainLooper().thread })
        assertTrue(faults.callbackThreads.all { it !== Looper.getMainLooper().thread })

        val ids = owner.peek(10, Long.MAX_VALUE).await().map { it.recordId }
        owner.closeAsync().await()
        owners.remove(owner)

        val reopened =
            open(
                file = file,
                identifiers = identifiers,
                faults = faults,
                stateLoader = {
                    error("Legacy JSON must not be read after SQLite became authoritative")
                },
            )
        assertEquals(ids, reopened.peek(10, Long.MAX_VALUE).await().map { it.recordId })
        assertEquals(2L, reopened.snapshot().await().state.stream.nextSequence)
    }

    @Test
    fun captureAuthorityAtomicallyCommitsSessionAndEventAfterRealSQLiteRollbackThenReopens() {
        val file = databaseFile()
        val faults = RecordingFaults()
        val identifiers = CountingIdentifiers()
        val clock = FixedCaptureClock(Instant.parse("2026-08-05T00:01:00Z").toEpochMilli(), 1_000_000_000L)
        val owner =
            open(
                file = file,
                identifiers = identifiers,
                faults = faults,
                stateLoader = ::freshState,
                trustedSiteKey = "elu_pk_test_capture",
                captureClock = clock,
            )
        assertTrue(
            owner.submitCaptureAuthority(captureConfig(), capturePrivacy()).await() is
                RuntimeCaptureAuthorityUpdateResult.Activated,
        )

        val commitsBeforeCapture = faults.beforeCommitCalls.get()
        faults.failBeforeCommit.set(true)
        val accepted =
            owner.capture(
                RuntimeCaptureCommand(
                    kind = RuntimeEventKind.CAPTURE,
                    name = "instrumented-atomic-capture",
                    occurredAt = "2026-08-05T00:01:01.000Z",
                    properties = mapOf("source" to "instrumentation"),
                    versions = versions(),
                ),
            ).await() as RuntimeCaptureResult.Accepted
        assertEquals(commitsBeforeCapture + 2, faults.beforeCommitCalls.get())
        assertEquals(1, accepted.snapshot.queuedCount)
        assertEquals(1L, accepted.snapshot.state.stream.nextSequence)
        assertEquals(accepted.record.record.sessionId, accepted.snapshot.state.identity.session?.id)
        assertEquals("instrumentation", accepted.record.record.properties["source"])
        val recordId = accepted.record.record.eventId

        owner.closeAsync().await()
        owners.remove(owner)
        val reopened =
            open(
                file = file,
                identifiers = identifiers,
                faults = RecordingFaults(),
                stateLoader = { error("Legacy state must not be read after capture commit") },
                trustedSiteKey = "elu_pk_test_capture",
                captureClock = clock,
            )
        val persisted = reopened.peek(1, Long.MAX_VALUE).await().single() as RuntimeQueuedRecord.Event
        assertEquals(recordId, persisted.record.eventId)
        assertEquals(persisted.record.sessionId, reopened.snapshot().await().state.identity.session?.id)
        assertEquals(1, reopened.snapshot().await().queuedCount)
    }

    @Test
    fun freshDatabaseOpenConfiguresAndVerifiesSQLiteConnectionAndSchema() {
        val file = databaseFile()
        val faults = RecordingFaults()
        val owner = open(file, CountingIdentifiers(), faults, ::freshState)

        assertEquals(1, faults.connectionSettings.size)
        val settings = faults.connectionSettings.single()
        // Older Android releases use one connection with a durable rollback journal;
        // API 35+ configures every WAL connection before accepting database work.
        val durableJournalModes =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                setOf("wal")
            } else {
                setOf("delete", "truncate", "persist")
            }
        assertTrue("Unexpected journal mode: ${settings.journalMode}", settings.journalMode in durableJournalModes)
        assertEquals(2L, settings.synchronous)
        assertEquals(5_000L, settings.busyTimeoutMillis)
        assertEquals(0, owner.snapshot().await().queuedCount)
        owner.closeAsync().await()
        owners.remove(owner)

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(7, sqlite.version)
            sqlite.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null,
            ).use { cursor ->
                val tables = mutableListOf<String>()
                while (cursor.moveToNext()) tables += cursor.getString(0)
                assertEquals(listOf("core_state", "queue_records", "replay_audience"), tables)
            }
        }
    }

    @Test
    fun lazyFlagMigrationPreservesCoreAndQueueBytesAndKeepsRuntimeStateAtV1() {
        val file = databaseFile()
        val owner =
            open(
                file,
                CountingIdentifiers(),
                RecordingFaults(),
                ::freshState,
                trustedSiteKey = "elu_pk_test_capture",
            )
        appendEvents(owner, event("before-flag-migration"))
        owner.closeAsync().await()
        owners.remove(owner)

        val beforeCore: ByteArray
        val beforeQueue: List<ByteArray>
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(7L, pragmaLong(sqlite, "PRAGMA user_version"))
            beforeCore = singleBlob(sqlite, "SELECT state_json FROM core_state WHERE singleton_id = 1")
            beforeQueue = orderedBlobs(sqlite, "SELECT internal_payload FROM queue_records ORDER BY sequence")
            assertEquals(1, JSONObject(String(beforeCore, Charsets.UTF_8)).getInt("schemaVersion"))
        }

        val migrated =
            open(
                file,
                CountingIdentifiers(),
                RecordingFaults(),
                { error("Migration must retain the existing SQLite core") },
                trustedSiteKey = "elu_pk_test_capture",
            )
        migrated.ensureFeatureFlagRuntime().await()
        migrated.closeAsync().await()
        owners.remove(migrated)

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(8L, pragmaLong(sqlite, "PRAGMA user_version"))
            assertArrayEquals(beforeCore, singleBlob(sqlite, "SELECT state_json FROM core_state WHERE singleton_id = 1"))
            val afterQueue = orderedBlobs(sqlite, "SELECT internal_payload FROM queue_records ORDER BY sequence")
            assertEquals(beforeQueue.size, afterQueue.size)
            beforeQueue.zip(afterQueue).forEach { (before, after) -> assertArrayEquals(before, after) }
            sqlite.rawQuery(
                "SELECT storage_schema_version, length(payload) FROM flag_cache WHERE record_key = ?",
                arrayOf(RUNTIME_FLAG_AUTHORITY_KEY),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertTrue(cursor.getLong(1) > 0L)
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun ordinaryFlagEnabledReopenBytePreservesCurrentAndFutureFlagRows() {
        val file = databaseFile()
        val owner =
            open(
                file,
                CountingIdentifiers(),
                RecordingFaults(),
                ::freshState,
                trustedSiteKey = "elu_pk_test_capture",
            )
        owner.ensureFeatureFlagRuntime().await()
        owner.closeAsync().await()
        owners.remove(owner)

        val current = byteArrayOf(0, 1, 2, 3, 127, -1)
        val future = "{\"declaredBodyBytes\":4194305,\"future\":true}".toByteArray(Charsets.UTF_8)
        SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { sqlite ->
            sqlite.execSQL(
                "INSERT INTO flag_cache(record_key, storage_schema_version, payload) VALUES (?, ?, ?)",
                arrayOf<Any>("cache-body:preserved:0000", 1L, current),
            )
            sqlite.execSQL(
                "INSERT INTO flag_cache(record_key, storage_schema_version, payload) VALUES (?, ?, ?)",
                arrayOf<Any>(RUNTIME_FLAG_CACHE_METADATA_KEY, 2L, future),
            )
        }

        val reopened =
            open(
                file,
                CountingIdentifiers(),
                RecordingFaults(),
                { error("A valid v2 reopen must use the retained SQLite core") },
                trustedSiteKey = "elu_pk_test_capture",
            )
        assertEquals(0, reopened.snapshot().await().queuedCount)
        reopened.closeAsync().await()
        owners.remove(reopened)

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(8L, pragmaLong(sqlite, "PRAGMA user_version"))
            assertArrayEquals(
                current,
                keyedFlagPayload(sqlite, "cache-body:preserved:0000", expectedSchema = 1L),
            )
            assertArrayEquals(
                future,
                keyedFlagPayload(sqlite, RUNTIME_FLAG_CACHE_METADATA_KEY, expectedSchema = 2L),
            )
        }
    }

    @Test
    fun flagStorageAcceptsExactlyFourOneMiBChunksAndRejectsOneByteOverARow() {
        val file = databaseFile()
        val owner =
            open(
                file,
                CountingIdentifiers(),
                RecordingFaults(),
                ::freshState,
                trustedSiteKey = "elu_pk_test_capture",
            )
        owner.ensureFeatureFlagRuntime().await()
        owner.closeAsync().await()
        owners.remove(owner)

        val chunks = List(4) { index -> ByteArray(MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES) { index.toByte() } }
        val database = AndroidSQLiteRuntimeDatabase.open(file)
        try {
            database.transaction { transaction ->
                chunks.forEachIndexed { index, payload ->
                    transaction.putFlagRow(
                        RuntimeFlagStoredRow("cache-body:limit:${index.toString().padStart(4, '0')}", 1L, payload),
                    )
                }
            }
            val loaded = mutableListOf<ByteArray>()
            database.transaction { transaction ->
                transaction.scanFlagRows("cache-body:limit:") { row -> loaded += row.payload.copyOf() }
            }
            assertEquals(4, loaded.size)
            chunks.zip(loaded).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
            assertEquals(4_194_304L, loaded.sumOf { it.size.toLong() })

            try {
                database.transaction { transaction ->
                    transaction.putFlagRow(
                        RuntimeFlagStoredRow(
                            "cache-body:over:0000",
                            1L,
                            ByteArray(MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES + 1),
                        ),
                    )
                }
                fail("Expected a flag row one byte over the SQLite limit to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected before SQLite mutation.
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun sqliteMultiRecordAppendAndExactAcknowledgementAreAtomic() {
        val file = databaseFile()
        val faults = RecordingFaults()
        val owner = open(file, CountingIdentifiers(), faults, ::freshState)
        val drafts =
            listOf(
                mutation(RuntimeMutationChange.AssociateGroup("organization", "org_1")),
                mutation(
                    RuntimeMutationChange.SetGroupProperties(
                        "organization",
                        "org_1",
                        set = mapOf("tier" to "growth"),
                        setOnce = emptyMap(),
                        unset = emptyList(),
                    ),
                ),
            )

        faults.failBeforeCommit.set(true)
        assertFutureCause(IOException::class.java) { owner.appendMutations(drafts).await() }
        assertEquals(0, owner.snapshot().await().queuedCount)
        assertFalse(owner.snapshot().await().state.identity.groups.containsKey("organization"))

        owner.appendMutations(drafts).await()
        val queued = owner.peek(10, Long.MAX_VALUE).await()
        assertEquals(listOf(0L, 1L), queued.map { it.sequence })
        val references = queued.map { RuntimeRecordReference(it.sequence, it.kind, it.recordId) }
        val wrongKind = RuntimeRecordKind.EVENT
        val wrongReference =
            references.first().copy(
                kind = wrongKind,
                recordId = RuntimeRecordIdentity.recordId(STREAM_ID, references.first().sequence, wrongKind),
            )
        assertFutureCause(RuntimeAcknowledgementMismatchException::class.java) {
            owner.acknowledge(acknowledgement(listOf(wrongReference))).await()
        }
        assertEquals(2, owner.snapshot().await().queuedCount)

        faults.failAfterCommit.set(true)
        val acknowledged = owner.acknowledge(acknowledgement(references)).await() as RuntimeAcknowledgementResult.Deleted
        assertEquals(0, acknowledged.snapshot.queuedCount)
        assertEquals(2L, acknowledged.snapshot.headSequence)
    }

    @Test
    fun unsupportedAndMalformedSchemasRemainFailClosed() {
        val unsupportedFile = databaseFile()
        SQLiteDatabase.openOrCreateDatabase(unsupportedFile, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE preserved_marker (value TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO preserved_marker(value) VALUES ('keep')")
            executePragma(sqlite, "PRAGMA user_version = 13")
        }

        assertFutureCause(UnsupportedRuntimeStorageSchemaException::class.java) {
            AndroidRuntimeQueue.openForTesting(
                unsupportedFile,
                RuntimeQueueLimits(100, 1_000_000),
                ::freshState,
            ).await()
        }
        SQLiteDatabase.openDatabase(unsupportedFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(13L, pragmaLong(sqlite, "PRAGMA user_version"))
            sqlite.rawQuery("SELECT value FROM preserved_marker", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep", cursor.getString(0))
            }
        }

        val malformedFile = databaseFile()
        SQLiteDatabase.openOrCreateDatabase(malformedFile, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE core_state (singleton_id INTEGER PRIMARY KEY)")
            sqlite.execSQL("CREATE TABLE queue_records (sequence INTEGER PRIMARY KEY)")
            executePragma(sqlite, "PRAGMA user_version = 1")
        }
        assertFutureCause(RuntimeQueueCorruptionException::class.java) {
            AndroidRuntimeQueue.openForTesting(
                malformedFile,
                RuntimeQueueLimits(100, 1_000_000),
                ::freshState,
            ).await()
        }
        SQLiteDatabase.openDatabase(malformedFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(1L, pragmaLong(sqlite, "PRAGMA user_version"))
            sqlite.rawQuery("PRAGMA table_info(core_state)", null).use { cursor ->
                assertEquals(1, cursor.count)
            }
        }
    }

    @Test
    fun existingOwnedSchemaFamiliesUpgradeUnknownAudienceWithoutChangingCoreOrQueue() {
        for (version in 1..6) {
            val file = databaseFile()
            val original = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState,
                trustedSiteKey = "elu_pk_test_capture")
            appendEvents(original, event("historical-session"))
            if (version in listOf(2, 4, 6)) original.ensureFeatureFlagRuntime().await()
            if (version in 3..6) original.ensurePreparedReplayStorage().await()
            if (version in 5..6) original.ensureNativeReplayAccounting().await()
            original.closeAsync().await(); owners.remove(original)
            val beforeCore: ByteArray
            val beforeQueue: List<ByteArray>
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { sqlite ->
                // Reconstruct the exact original table family, retaining its canonical runtime rows.
                sqlite.execSQL("DROP TABLE replay_audience")
                executePragma(sqlite, "PRAGMA user_version = $version")
                beforeCore = singleBlob(sqlite, "SELECT state_json FROM core_state WHERE singleton_id = 1")
                beforeQueue = orderedBlobs(sqlite, "SELECT internal_payload FROM queue_records ORDER BY sequence")
            }
            val upgraded = open(file, CountingIdentifiers(), RecordingFaults(), { error("Owned SQLite must remain authoritative") },
                trustedSiteKey = "elu_pk_test_capture")
            assertEquals(1, upgraded.snapshot().await().queuedCount)
            upgraded.closeAsync().await(); owners.remove(upgraded)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                assertEquals((version + 6).toLong(), pragmaLong(sqlite, "PRAGMA user_version"))
                assertArrayEquals(beforeCore, singleBlob(sqlite, "SELECT state_json FROM core_state WHERE singleton_id = 1"))
                val afterQueue = orderedBlobs(sqlite, "SELECT internal_payload FROM queue_records ORDER BY sequence")
                assertEquals(beforeQueue.size, afterQueue.size)
                beforeQueue.zip(afterQueue).forEach { (before, after) -> assertArrayEquals(before, after) }
                sqlite.rawQuery("SELECT status, session_id, session_started_at FROM replay_audience", null).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals("unknown", cursor.getString(0))
                    assertTrue(cursor.isNull(1)); assertTrue(cursor.isNull(2)); assertFalse(cursor.moveToNext())
                }
            }
        }
    }

    @Test
    fun firstSessionMarkerRollsBackWithEventAndSurvivesAmbiguousCommitAndReset() {
        val file = databaseFile()
        val faults = RecordingFaults()
        val owner = open(file, CountingIdentifiers(), faults, ::freshState)
        faults.failBeforeCommit.set(true)
        assertFutureCause(IOException::class.java) { appendEvents(owner, event("failed-first-session")) }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT status FROM replay_audience", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("unseen", cursor.getString(0))
            }
        }
        faults.failAfterCommit.set(true)
        val accepted = appendEvents(owner, event("first-session")) as RuntimeAppendResult.Accepted
        val session = accepted.snapshot.state.identity.session!!
        owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW)).await()
        owner.closeAsync().await(); owners.remove(owner)
        val reopened = open(file, CountingIdentifiers(), faults, { error("Must reopen existing SQLite") })
        assertEquals(1, reopened.snapshot().await().queuedCount)
        reopened.closeAsync().await(); owners.remove(reopened)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT status, session_id, session_started_at FROM replay_audience", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("first-session", cursor.getString(0))
                assertEquals(session.id, cursor.getString(1)); assertEquals(session.startedAt, cursor.getString(2))
            }
        }
    }

    @Test
    fun diagnosticsSchemaPreservesAllOwnedFamiliesAndRollsBackFailedMigration() {
        for (base in 1..6) {
            val file = databaseFile()
            val faults = RecordingFaults()
            val owner = open(file, CountingIdentifiers(), faults, ::freshState, trustedSiteKey = "elu_pk_test_capture")
            appendEvents(owner, event("retained"))
            if (base in listOf(2, 4, 6)) owner.ensureFeatureFlagRuntime().await()
            if (base >= 3) owner.ensurePreparedReplayStorage().await()
            if (base >= 5) owner.ensureNativeReplayAccounting().await()
            val before = owner.peek(10, Long.MAX_VALUE).await()
            val diagnosticClock = RuntimeDiagnosticsClock { RuntimeDiagnosticsClockReading(1,
                Instant.parse(NOW).toEpochMilli(), 1_000_000_000, 1_000_000_000) }
            faults.failBeforeCommit.set(true)
            assertFutureCause(IOException::class.java) {
                owner.configureDiagnostics(RuntimeDiagnosticsConfiguration(true, true), diagnosticClock).await()
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                assertEquals((base + 6).toLong(), pragmaLong(sqlite, "PRAGMA user_version"))
                sqlite.rawQuery("SELECT name FROM sqlite_master WHERE name='native_diagnostics'", null).use {
                    assertFalse(it.moveToFirst())
                }
            }
            owner.configureDiagnostics(RuntimeDiagnosticsConfiguration(true, true), diagnosticClock).await()
            assertEquals(before, owner.peek(10, Long.MAX_VALUE).await())
            owner.closeAsync().await(); owners.remove(owner)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                assertEquals((base + 24).toLong(), pragmaLong(sqlite, "PRAGMA user_version"))
                assertEquals(RuntimeDiagnosticsState(), RuntimeDiagnosticsState.decode(
                    singleBlob(sqlite, "SELECT payload FROM native_diagnostics WHERE singleton_id=1")))
            }
            val reopened = open(file, CountingIdentifiers(), RecordingFaults(), { error("No import on upgrade") },
                trustedSiteKey = "elu_pk_test_capture")
            assertEquals(before, reopened.peek(10, Long.MAX_VALUE).await())
        }
    }

    @Test
    fun diagnosticEventAndDedupeSurviveSQLiteAmbiguityAndExplicitCloseEndsEpoch() {
        val file = databaseFile(); val faults = RecordingFaults()
        val wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
        val owner = open(file, CountingIdentifiers(), faults, ::freshState, trustedSiteKey = "elu_pk_test_capture",
            captureClock = FixedCaptureClock(wall, 1_000_000_000))
        var reading = RuntimeDiagnosticsClockReading(1, wall, 1_000_000_000, 1_000_000_000)
        owner.configureDiagnostics(RuntimeDiagnosticsConfiguration(true, true), RuntimeDiagnosticsClock { reading }).await()
        val config = JSONObject(captureConfig()).put("capturePerformance",
            JSONObject().put("memory", false).put("long_tasks", true).put("sample_interval_ms", 5000)).toString()
        owner.submitCaptureAuthority(config, capturePrivacy()).await()
        val user = owner.capture(RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "user",
            RuntimeWallTimestamps.rfc3339(wall), emptyMap(), versions())).await() as RuntimeCaptureResult.Accepted
        val epoch = checkNotNull(owner.diagnosticsEpoch())
        reading = reading.copy(uptimeNanos = 1_300_000_000, elapsedNanos = 1_300_000_000)
        val measurement = dev.elu.analytics.internal.diagnostics.NativeStartupMeasurement(epoch, 1_100_000_000, 1_200_000_000, 6, 1)
        val identity = user.snapshot.state.identity
        val command = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "\$native_launch",
            RuntimeWallTimestamps.rfc3339(wall), measurement.properties(), versions(),
            RuntimeCaptureExpectation(identity.revision, identity.contextRevision, identity.session!!.id) { true },
            startupMeasurement = measurement)
        faults.failAfterCommit.set(true)
        assertTrue(owner.capture(command).await() is RuntimeCaptureResult.Accepted)
        assertTrue(owner.capture(command).await() is RuntimeCaptureResult.Rejected)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            val state = RuntimeDiagnosticsState.decode(singleBlob(sqlite, "SELECT payload FROM native_diagnostics WHERE singleton_id=1"))
            assertEquals(measurement.launchUptimeNanos, state.lastLaunchUptimeNanos)
        }
        owner.closeAsync().await(); owners.remove(owner)
        val reopened = open(file, CountingIdentifiers(), RecordingFaults(), { error("No import on reopen") },
            trustedSiteKey = "elu_pk_test_capture", captureClock = FixedCaptureClock(wall, 1_000_000_000))
        assertEquals(null, reopened.diagnosticsEpoch())
        assertEquals(2, reopened.peek(10, Long.MAX_VALUE).await().size)
    }

    @Test
    fun duplicateOwnerIsRejectedUntilTheLeaseClosesAndOpenRunsOffMain() {
        val file = databaseFile()
        var loaderThread: Thread? = null
        val first =
            open(
                file = file,
                identifiers = CountingIdentifiers(),
                faults = RecordingFaults(),
                stateLoader = {
                    loaderThread = Thread.currentThread()
                    freshState()
                },
            )
        assertNotEquals(Looper.getMainLooper().thread, loaderThread)

        assertFutureCause(RuntimeQueueOwnershipException::class.java) {
            AndroidRuntimeQueue.openForTesting(
                file,
                RuntimeQueueLimits(100, 1_000_000),
                ::freshState,
            ).await()
        }

        first.closeAsync().await()
        owners.remove(first)
        val replacement = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState)
        assertEquals(0, replacement.snapshot().await().queuedCount)
    }

    @Test
    fun cursorWindowSafeRowReopensAndOversizedRowIsRejectedBeforeInsert() {
        val file = databaseFile()
        val owner = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState)
        val safePayload = "x".repeat(MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES - 4_096)
        val safeEvent = event("safe-large").copy(properties = mapOf("payload" to safePayload))

        val accepted = appendEvents(owner, safeEvent) as RuntimeAppendResult.Accepted
        assertEquals(1L, accepted.snapshot.state.stream.nextSequence)

        val oversized =
            event("oversized").copy(
                properties = mapOf("payload" to "x".repeat(MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES)),
            )
        val rejected = appendEvents(owner, oversized) as RuntimeAppendResult.Rejected
        assertEquals(RuntimeAppendRejection.RECORD_TOO_LARGE, rejected.reason)
        assertEquals(1L, rejected.snapshot.state.stream.nextSequence)

        owner.closeAsync().await()
        owners.remove(owner)
        val reopened =
            open(
                file = file,
                identifiers = CountingIdentifiers(),
                faults = RecordingFaults(),
                stateLoader = { error("legacy must not be read") },
            )
        val event = (reopened.peek(1, MAX_RUNTIME_DELIVERY_BYTES).await().single() as RuntimeQueuedRecord.Event).record
        assertEquals(safePayload.length, (event.properties.getValue("payload") as String).length)
    }

    @Test
    fun productionOpenerDefaultsToPersonMetadataAndReopensTheSameDevice() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val root = databaseFile().parentFile!!
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val key = "elu_pk_test_${"P".repeat(26)}"
        fun production() = AndroidRuntimeQueue.open(context, key, RuntimeQueueLimits(100, 1_000_000))
            .await().also { owners += it }
        val first = production()
        val before = first.snapshot().await()
        assertEquals(before.state.identity.anonymousId, before.person!!.deviceId)
        assertFalse(before.person.processingEnabled)
        val at = RuntimeWallTimestamps.rfc3339(System.currentTimeMillis())
        first.applyLocal(RuntimeLocalStateChange.ResetIdentity(at)).await()
        val reset = first.snapshot().await()
        assertEquals(before.person.deviceId, reset.person!!.deviceId)
        assertNotEquals(before.state.identity.anonymousId, reset.state.identity.anonymousId)
        first.closeAsync().await(); owners.remove(first)
        val reopened = production()
        assertEquals(reset, reopened.snapshot().await())
        reopened.closeAsync().await(); owners.remove(reopened)
        SQLiteDatabase.openDatabase(AndroidRuntimeQueue.databaseFileFor(context, key).path, null,
            SQLiteDatabase.OPEN_READONLY).use { sqlite -> assertEquals(31, sqlite.version) }
    }

    @Test
    fun personMetadataMigratesAllEighteenOwnedFamiliesWithoutRewritingQueuedBytes() {
        for (offset in listOf(0, 6, 24)) for (base in 1..6) {
            val file = databaseFile()
            val owner = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState, trustedSiteKey = "elu_pk_test_capture")
            appendEvents(owner, event("retained-before-person"))
            if (base in listOf(2, 4, 6)) owner.ensureFeatureFlagRuntime().await()
            if (base >= 3) owner.ensurePreparedReplayStorage().await()
            if (base >= 5) owner.ensureNativeReplayAccounting().await()
            if (offset == 24) owner.configureDiagnostics(RuntimeDiagnosticsConfiguration(true, false),
                RuntimeDiagnosticsClock { null }).await()
            val before = owner.peek(10, Long.MAX_VALUE).await()
            val identity = owner.snapshot().await().state.identity
            owner.closeAsync().await(); owners.remove(owner)
            if (offset == 0) SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                sqlite.execSQL("DROP TABLE replay_audience"); executePragma(sqlite, "PRAGMA user_version = $base")
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                assertEquals((base + offset).toLong(), pragmaLong(sqlite, "PRAGMA user_version"))
            }
            val migrated = open(file, CountingIdentifiers(), RecordingFaults(), { error("No legacy import") },
                trustedSiteKey = "elu_pk_test_capture", personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            assertEquals(before, migrated.peek(10, Long.MAX_VALUE).await())
            assertEquals(identity, migrated.snapshot().await().state.identity)
            assertEquals(identity.anonymousId, migrated.snapshot().await().person!!.deviceId)
            migrated.closeAsync().await(); owners.remove(migrated)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                assertEquals((base + 30).toLong(), pragmaLong(sqlite, "PRAGMA user_version"))
                assertEquals(RuntimePersonState(STREAM_ID, identity.anonymousId), RuntimePersonState.decode(
                    singleBlob(sqlite, "SELECT payload FROM person_state WHERE singleton_id=1")))
            }
            // Nullable raw conformance cannot read production metadata and emit old wire identity.
            assertFutureCause(RuntimeQueueCorruptionException::class.java) {
                open(file, CountingIdentifiers(), RecordingFaults(), { error("No import") })
            }
        }
    }

    @Test
    fun personMigrationRollbackAndAmbiguousCommitPreserveOriginalState() {
        val file = databaseFile()
        val original = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState)
        appendEvents(original, event("old-queue"))
        val before = original.snapshot().await()
        original.closeAsync().await(); owners.remove(original)
        val faults = RecordingFaults().apply { failBeforeCommit.set(true) }
        assertFutureCause(IOException::class.java) {
            open(file, CountingIdentifiers(), faults, { error("No import") },
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.ALWAYS)
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(7, sqlite.version)
            sqlite.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('person_state','native_diagnostics')", null).use {
                assertFalse(it.moveToFirst())
            }
        }
        faults.failAfterCommit.set(true)
        val selected = open(file, CountingIdentifiers(), faults, { error("No import") },
            personProfiles = dev.elu.analytics.EluPersonProfilesMode.ALWAYS)
        assertEquals(before.state, selected.snapshot().await().state)
        assertEquals(before.queuedBytes, selected.snapshot().await().queuedBytes)
        assertFalse(selected.snapshot().await().person!!.processingEnabled)
        faults.failBeforeCommit.set(true)
        assertFutureCause(IOException::class.java) { appendEvents(selected, event("rolled-back-sticky")) }
        assertFalse(selected.snapshot().await().person!!.processingEnabled)
        faults.failAfterCommit.set(true)
        appendEvents(selected, event("committed-sticky"))
        assertTrue(selected.snapshot().await().person!!.processingEnabled)
        faults.failAfterCommit.set(true)
        selected.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW, true)).await()
        val reset = selected.snapshot().await()
        assertEquals(reset.state.identity.anonymousId, reset.person!!.deviceId)
        assertFalse(reset.person.processingEnabled)
    }

    @Test
    fun personSchemaRefusesMissingCorruptForeignAndFutureMetadataWithoutRecovery() {
        for (damage in listOf("missing", "corrupt", "foreign", "table", "future")) {
            val file = databaseFile()
            val original = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState,
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            original.closeAsync().await(); owners.remove(original)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                when (damage) {
                    "missing" -> sqlite.execSQL("DELETE FROM person_state")
                    "corrupt" -> sqlite.execSQL("UPDATE person_state SET payload=?", arrayOf("{}".toByteArray()))
                    "foreign" -> sqlite.execSQL("UPDATE person_state SET payload=?", arrayOf(RuntimePersonState("foreign", "device").encode()))
                    "table" -> sqlite.execSQL("ALTER TABLE person_state ADD COLUMN unknown TEXT")
                    "future" -> executePragma(sqlite, "PRAGMA user_version=37")
                }
            }
            val originalBytes = file.readBytes()
            try {
                open(file, CountingIdentifiers(), RecordingFaults(), { error("Must not recover invalid metadata") },
                    personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
                fail("Must refuse $damage")
            } catch (expected: ExecutionException) {
                assertTrue(expected.cause is RuntimeQueueCorruptionException || expected.cause is UnsupportedRuntimeStorageSchemaException)
            }
            assertArrayEquals(originalBytes, file.readBytes())
        }
    }

    private fun open(
        file: File,
        identifiers: CountingIdentifiers,
        faults: RecordingFaults,
        stateLoader: () -> PersistedCoreState,
        trustedSiteKey: String? = null,
        captureClock: RuntimeCaptureClock = JvmRuntimeCaptureClock,
        personProfiles: dev.elu.analytics.EluPersonProfilesMode? = null,
    ): RuntimeQueueOwner {
        val owner =
            AndroidRuntimeQueue.openForTesting(
                databaseFile = file,
                limits = RuntimeQueueLimits(10_000, 16_777_216),
                legacyStateLoader = stateLoader,
                identifiers = identifiers,
                faults = faults,
                trustedSiteKey = trustedSiteKey,
                captureClock = captureClock,
                personProfiles = personProfiles,
            ).await()
        owners += owner
        return owner
    }

    private fun databaseFile(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "elu-runtime-tests/${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        testDirectories += directory
        return File(directory, "runtime.sqlite")
    }

    private fun singleBlob(
        sqlite: SQLiteDatabase,
        query: String,
    ): ByteArray =
        sqlite.rawQuery(query, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val value = cursor.getBlob(0).copyOf()
            assertFalse(cursor.moveToNext())
            value
        }

    private fun orderedBlobs(
        sqlite: SQLiteDatabase,
        query: String,
    ): List<ByteArray> =
        sqlite.rawQuery(query, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getBlob(0).copyOf())
            }
        }

    private fun keyedFlagPayload(
        sqlite: SQLiteDatabase,
        key: String,
        expectedSchema: Long,
    ): ByteArray =
        sqlite.rawQuery(
            "SELECT storage_schema_version, payload FROM flag_cache WHERE record_key = ?",
            arrayOf(key),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedSchema, cursor.getLong(0))
            val payload = cursor.getBlob(1).copyOf()
            assertFalse(cursor.moveToNext())
            payload
        }

    private fun appendEvents(
        owner: RuntimeQueueOwner,
        vararg events: RuntimeRecordDraft.Event,
    ): RuntimeAppendResult {
        val expectedCurrentSessionId = owner.snapshot().await().state.identity.session?.id
        return owner.appendEvents(
            RuntimeEventSessionUpdate.Replace(expectedCurrentSessionId, session()),
            events.toList(),
        ).await()
    }

    private fun acknowledgement(references: List<RuntimeRecordReference>): RuntimeAcknowledgement =
        RuntimeAcknowledgement(STREAM_ID, references)

    private fun event(name: String): RuntimeRecordDraft.Event =
        RuntimeRecordDraft.Event(
            RuntimeEventKind.CAPTURE,
            name,
            NOW,
            "session_test",
            mapOf("name" to name),
            versions(),
        )

    private fun mutation(change: RuntimeMutationChange): RuntimeRecordDraft.Mutation =
        RuntimeRecordDraft.Mutation(NOW, change, versions())

    private fun session(): SessionState =
        SessionState(
            id = "session_test",
            startedAt = NOW,
            lastActivityAt = NOW,
            timeoutSeconds = 1_800,
            lifecycle = SessionLifecycle.ACTIVE,
            backgroundedAt = null,
        )

    private fun versions(): RuntimeVersions =
        RuntimeVersions(
            platform = RuntimePlatform.ANDROID,
            runtime = RuntimeVersionComponent("elu-android", "0.1.0"),
            facade = RuntimeVersionComponent("Elu", "0.1.0"),
            build = "instrumentation",
        )

    private fun captureConfig(): String =
        JSONObject()
            .put("schemaVersion", 1)
            .put("revision", "instrumentation-config-1")
            .put("issuedAt", "2026-08-05T00:00:00.000Z")
            .put("expiresAt", "2026-08-05T00:05:00.000Z")
            .put("status", "enabled")
            .put("site", JSONObject().put("id", "site_instrumentation"))
            .put(
                "endpoints",
                JSONObject()
                    .put("events", "https://ingest.elu.dev/v1/events")
                    .put("flags", "https://ingest.elu.dev/v1/flags"),
            ).put(
                "privacy",
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("revision", "privacy-instrumentation-1")
                    .put("capture", JSONObject().put("enabled", true))
                    .put(
                        "replay",
                        JSONObject()
                            .put("enabled", false)
                            .put("sampleRate", 0)
                            .put("minimumDurationSeconds", 0)
                            .put("maximumDurationSeconds", 0),
                    ).put(
                        "masking",
                        JSONObject()
                            .put("text", "sensitive")
                            .put("inputs", "all")
                            .put("images", "block")
                            .put("secureInputsMasked", true),
                    ).put("regionPolicy", JSONObject().put("mode", "allow")),
            ).put(
                "features",
                JSONObject()
                    .put("capture", true)
                    .put("replay", false)
                    .put("flags", false)
                    .put("assets", false),
            ).put(
                "capabilities",
                JSONObject()
                    .put(
                        "replay",
                        JSONObject()
                            .put("acceptedCodecs", JSONArray())
                            .put("acceptedCompressions", JSONArray()),
                    ),
            ).put(
                "session",
                JSONObject()
                    .put("idleTimeoutSeconds", 1_800)
                    .put("maximumDurationSeconds", 86_400),
            ).put(
                "limits",
                JSONObject()
                    .put("eventBatchCount", 100)
                    .put("eventBatchBytes", 1_048_576)
                    .put("replayChunkBytes", 5_242_880)
                    .put("queueBytes", 16_777_216),
            ).toString()

    private fun capturePrivacy(): String {
        val json =
            JSONObject()
                .put("schemaVersion", 1)
                .put("policyRevision", "privacy-instrumentation-1")
                .put("contextRevision", 0)
                .put("effectivePolicyHash", "sha256:" + "0".repeat(64))
                .put(
                    "onDeviceDecision",
                    JSONObject()
                        .put("decision", "allow")
                        .put("source", "local-consent")
                        .put("evaluatedAt", "2026-08-05T00:00:00.000Z"),
                ).put("captureAllowed", true)
                .put("replayAllowed", false)
                .put("replaySampled", false)
                .put("identityOptedOut", false)
                .put("maskingValidated", true)
                .put("replaySessionEligible", false)
                .put("replayBudgetRemainingSeconds", 0)
                .put("replayTransport", JSONObject.NULL)
                .put(
                    "effectiveMasking",
                    JSONObject()
                        .put("text", "sensitive")
                        .put("inputs", "all")
                        .put("images", "block")
                        .put("secureInputsMasked", true)
                        .put("platformFallbackApplied", true),
                )
        val root = V1StrictCanonicalJson.parse(json.toString()) as V1StrictCanonicalJson.Value.ObjectValue
        val withoutHash =
            V1StrictCanonicalJson.Value.ObjectValue(
                root.members.filterNot { member -> member.first == "effectivePolicyHash" },
            )
        json.put("effectivePolicyHash", V1StrictCanonicalJson.sha256(withoutHash))
        return json.toString()
    }

    private fun freshState(): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 0,
                    contextRevision = 0,
                    anonymousId = "anon_android_test",
                    userId = null,
                    groups = emptyMap(),
                    superProperties = emptyMap(),
                    session = null,
                    optedOut = false,
                    updatedAt = NOW,
                ),
            stream = StreamState(streamId = STREAM_ID, nextSequence = 0),
            flagContext =
                FlagContextState(
                    personProperties = emptyMap(),
                    groupProperties = emptyMap(),
                ),
        )

    private fun pragmaLong(
        sqlite: SQLiteDatabase,
        pragma: String,
    ): Long = sqlite.rawQuery(pragma, null).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun executePragma(
        sqlite: SQLiteDatabase,
        statement: String,
    ) {
        sqlite.rawQuery(statement, null).use { cursor ->
            while (cursor.moveToNext()) {
                // Fully consume row-returning PRAGMA assignments on Android 15.
            }
        }
    }

    private fun <T> Future<T>.await(): T = get(20, TimeUnit.SECONDS)

    private fun assertFutureCause(
        expected: Class<out Throwable>,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected ${expected.simpleName}")
        } catch (error: ExecutionException) {
            assertTrue("Expected ${expected.name}, got ${error.cause}", expected.isInstance(error.cause))
        }
    }

    private class CountingIdentifiers : CoreIdentifierGenerator {
        private val next = AtomicInteger()
        val generatedCalls: Int
            get() = next.get()

        override fun next(prefix: String): String = prefix + next.getAndIncrement().toString().padStart(8, '0')
    }

    private class RecordingFaults : AndroidRuntimeDatabaseFaults {
        val failBeforeCommit = AtomicBoolean()
        val failAfterCommit = AtomicBoolean()
        val callbackThreads = mutableListOf<Thread>()
        val connectionSettings = mutableListOf<AndroidRuntimeConnectionSettings>()
        val beforeCommitCalls = AtomicInteger()

        override fun connectionConfigured(settings: AndroidRuntimeConnectionSettings) {
            callbackThreads += Thread.currentThread()
            connectionSettings += settings
        }

        override fun beforeCommit() {
            beforeCommitCalls.incrementAndGet()
            if (failBeforeCommit.compareAndSet(true, false)) {
                callbackThreads += Thread.currentThread()
                throw IOException("Injected pre-commit failure")
            }
        }

        override fun afterCommit() {
            if (failAfterCommit.compareAndSet(true, false)) {
                callbackThreads += Thread.currentThread()
                throw IOException("Injected ambiguous post-commit failure")
            }
        }
    }

    private class FixedCaptureClock(
        private val wallEpochMillis: Long,
        private val monotonicNanos: Long,
    ) : RuntimeCaptureClock {
        override fun wallNowEpochMillis(): Long = wallEpochMillis

        override fun elapsedRealtimeNanos(): Long = monotonicNanos
    }

    private companion object {
        const val NOW = "2026-08-05T00:00:00.000Z"
        const val STREAM_ID = "stream_android_test"
    }
}
