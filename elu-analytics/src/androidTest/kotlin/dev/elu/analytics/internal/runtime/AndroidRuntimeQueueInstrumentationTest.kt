package dev.elu.analytics.internal.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.CoreStateCodec
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
import org.junit.Assert.assertThrows
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
            SQLiteDatabase.OPEN_READONLY).use { sqlite -> assertEquals(43, sqlite.version) }
    }

    @Test
    fun exposureMetadataMigratesAllTwentyFourOwnedFamiliesWithoutRewritingQueuedBytes() {
        for (offset in listOf(0, 6, 24, 30)) for (base in 1..6) {
            val file = databaseFile()
            val owner = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState, trustedSiteKey = "elu_pk_test_capture",
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY.takeIf { offset == 30 })
            appendEvents(owner, event("retained-before-person"))
            if (base in listOf(2, 4, 6)) owner.ensureFeatureFlagRuntime().await()
            if (base >= 3) owner.ensurePreparedReplayStorage().await()
            if (base >= 5) owner.ensureNativeReplayAccounting().await()
            if (offset == 24) owner.configureDiagnostics(RuntimeDiagnosticsConfiguration(true, false),
                RuntimeDiagnosticsClock { null }).await()
            val before = owner.peek(10, Long.MAX_VALUE).await()
            val identity = owner.snapshot().await().state.identity
            owner.closeAsync().await(); owners.remove(owner)
            if (offset == 30) SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { sqlite ->
                sqlite.execSQL("DROP TABLE flag_exposure_state"); executePragma(sqlite, "PRAGMA user_version = ${base + 30}")
            }
            if (offset == 0) SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { sqlite ->
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
                assertEquals((base + 36).toLong(), pragmaLong(sqlite, "PRAGMA user_version"))
                assertEquals(RuntimePersonState(STREAM_ID, identity.anonymousId), RuntimePersonState.decode(
                    singleBlob(sqlite, "SELECT payload FROM person_state WHERE singleton_id=1")))
                val exposures = RuntimeFlagExposureState.decode(singleBlob(sqlite, "SELECT payload FROM flag_exposure_state WHERE singleton_id=1"))
                assertEquals(STREAM_ID, exposures.streamId)
                assertEquals(identity.anonymousId, exposures.anonymousId)
                assertTrue(exposures.digests.isEmpty())
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
        for (wal in listOf(false, true)) for (damage in listOf("missing", "corrupt", "foreign", "table", "future", "exposure-missing", "exposure-corrupt", "exposure-foreign", "exposure-table")) {
            val file = databaseFile()
            val familySuffixes = listOf("", "-wal", "-shm", "-journal")
            fun databaseFamily(): Map<String, ByteArray> = familySuffixes
                .map { suffix -> File(file.path + suffix) }.filter { it.exists() }
                .associate { it.name to it.readBytes() }
            val original = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState,
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            original.closeAsync().await(); owners.remove(original)
            var retainedWal: Map<String, ByteArray>? = null
            SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { sqlite ->
                // Deliberately retain a rollback-journal file. Refusal must happen before the
                // production open could rewrite header byte18 to WAL, including on API35+.
                if (wal) {
                    assertTrue(sqlite.enableWriteAheadLogging())
                    executePragma(sqlite, "PRAGMA wal_autocheckpoint = 0")
                } else sqlite.disableWriteAheadLogging()
                when (damage) {
                    "missing" -> sqlite.execSQL("DELETE FROM person_state")
                    "corrupt" -> sqlite.execSQL("UPDATE person_state SET payload=?", arrayOf("{}".toByteArray()))
                    "foreign" -> sqlite.execSQL("UPDATE person_state SET payload=?", arrayOf(RuntimePersonState("foreign", "device").encode()))
                    "table" -> sqlite.execSQL("ALTER TABLE person_state ADD COLUMN unknown TEXT")
                    "future" -> executePragma(sqlite, "PRAGMA user_version=55")
                    "exposure-missing" -> sqlite.execSQL("DELETE FROM flag_exposure_state")
                    "exposure-corrupt" -> sqlite.execSQL("UPDATE flag_exposure_state SET payload=?", arrayOf("{}".toByteArray()))
                    "exposure-foreign" -> sqlite.execSQL("UPDATE flag_exposure_state SET payload=?", arrayOf(RuntimeFlagExposureState.initial(
                        freshState().copy(identity = freshState().identity.copy(anonymousId = "foreign"))).encode()))
                    "exposure-table" -> sqlite.execSQL("ALTER TABLE flag_exposure_state ADD COLUMN unknown TEXT")
                }
                if (wal) {
                    retainedWal = databaseFamily()
                    assertTrue(retainedWal!!.containsKey(file.name + "-wal"))
                    assertTrue(retainedWal!!.containsKey(file.name + "-shm"))
                }
            }
            // Retain actual uncheckpointed SQLite bytes, as a process interruption would;
            // closing the fixture connection normally checkpoints/removes these sidecars.
            retainedWal?.let { retained ->
                for (suffix in familySuffixes) {
                    val member = File(file.path + suffix)
                    val bytes = retained[member.name]
                    if (bytes == null) { if (member.exists()) assertTrue(member.delete()) }
                    else member.writeBytes(bytes)
                }
            }
            val originalBytes = databaseFamily()
            val faults = RecordingFaults()
            try {
                open(file, CountingIdentifiers(), faults, { error("Must not recover invalid metadata") },
                    personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
                fail("Must refuse $damage")
            } catch (expected: ExecutionException) {
                assertTrue(expected.cause is RuntimeQueueCorruptionException || expected.cause is UnsupportedRuntimeStorageSchemaException)
            }
            assertTrue("$damage WAL=$wal reached writable connection configuration", faults.connectionSettings.isEmpty())
            val after = databaseFamily()
            assertEquals(originalBytes.keys, after.keys)
            for ((name, bytes) in originalBytes) assertArrayEquals("$damage WAL=$wal changed $name", bytes, after.getValue(name))
        }
    }

    @Test
    fun exposureEventAndVisitorLedgerShareRealSQLiteCommitReconciliationAndReopen() {
        for (ambiguous in listOf(false, true)) {
            val file = databaseFile(); val faults = RecordingFaults(); val identifiers = CountingIdentifiers()
            val wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
            val clock = FixedCaptureClock(wall, 1_000_000_000L)
            val config = JSONObject(captureConfig()).apply { getJSONObject("features").put("flags", true) }.toString()
            val owner = open(file, identifiers, faults, ::freshState, "elu_pk_test_capture", clock,
                dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            fun client(current: RuntimeQueueOwner) = dev.elu.analytics.internal.flags.AndroidFeatureFlagClient(
                current, versions(), dev.elu.analytics.internal.flags.FlagTransport { request ->
                    val body = JSONObject(String(request.canonicalBody, Charsets.UTF_8))
                    val response = JSONObject().put("schemaVersion", 1).put("requestId", body.getString("requestId"))
                        .put("contextRevision", body.getLong("contextRevision"))
                        .put("identityRevision", body.getJSONObject("identity").getLong("revision"))
                        .put("flagsRevision", "native-flags").put("evaluatedAt", "2026-08-05T00:01:00.000Z")
                        .put("expiresAt", "2026-08-05T00:04:00.000Z")
                        .put("flags", JSONObject().put("variant", false)).put("payloads", JSONObject())
                    dev.elu.analytics.internal.concurrent.SdkFuture.completedFuture(response.toString().toByteArray())
                }, object : dev.elu.analytics.internal.flags.FlagClock {
                    override fun wallNowEpochMillis() = wall
                    override fun monotonicNowNanos() = 1_000_000_000L
                }, dev.elu.analytics.internal.flags.FlagOpaqueIdSource { UUID.randomUUID().toString() },
                dev.elu.analytics.internal.flags.FlagOpaqueIdSource { UUID.randomUUID().toString() })
            fun exposure(client: dev.elu.analytics.internal.flags.AndroidFeatureFlagClient): RuntimeCaptureCommand {
                val read = client.read("variant").await() as dev.elu.analytics.internal.flags.FlagReadResult.Found
                val report = checkNotNull(RuntimeFlagExposureCapture.from("variant", read, false) {
                    client.isCacheLeaseCurrent(read.cacheLeaseToken)
                })
                return RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "\$feature_flag_called",
                    "2026-08-05T00:01:00.000Z", report.properties(), versions(), flagExposure = report)
            }
            var acceptedId: String? = null
            client(owner).use { client ->
                assertTrue(owner.submitCaptureAuthority(config, capturePrivacy()).await() is RuntimeCaptureAuthorityUpdateResult.Activated)
                client.applyConfiguration(config).await()
                assertTrue(client.reload().await() is dev.elu.analytics.internal.flags.FlagReloadResult.Updated)
                val command = exposure(client)
                val commits = faults.beforeCommitCalls.get()
                if (ambiguous) faults.failAfterCommit.set(true) else faults.failBeforeCommit.set(true)
                val result = owner.capture(command).await() as RuntimeCaptureResult.Accepted
                assertEquals(commits + if (ambiguous) 1 else 2, faults.beforeCommitCalls.get())
                assertEquals(1, result.snapshot.queuedCount)
                assertEquals(1, result.snapshot.exposures!!.digests.size)
                acceptedId = result.record.record.eventId
                val duplicate = owner.capture(command).await() as RuntimeCaptureResult.Rejected
                assertEquals(RuntimeCaptureRejection.EXPOSURE_ALREADY_REPORTED, duplicate.reason)
            }
            owner.closeAsync().await(); owners.remove(owner)
            val reopened = open(file, identifiers, RecordingFaults(), { error("Must use owned SQLite") },
                "elu_pk_test_capture", clock, dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            assertEquals(1, reopened.snapshot().await().exposures!!.digests.size)
            assertEquals(acceptedId, reopened.peek(10, Long.MAX_VALUE).await().single().recordId)
            client(reopened).use { client ->
                reopened.submitCaptureAuthority(config, capturePrivacy()).await()
                client.applyConfiguration(config).await()
                val duplicate = reopened.capture(exposure(client)).await() as RuntimeCaptureResult.Rejected
                assertEquals(RuntimeCaptureRejection.EXPOSURE_ALREADY_REPORTED, duplicate.reason)
                assertEquals(1, reopened.snapshot().await().queuedCount)
            }
        }
    }


    @Test fun captureRateMetadataMigratesAllThirtyOwnedFamiliesWithoutChangingRecordsOrCore() {
        for (offset in listOf(0, 6, 24, 30, 36)) for (base in 1..6) {
            val file = databaseFile()
            val original = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState,
                trustedSiteKey = "elu_pk_test_capture",
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY.takeIf { offset >= 30 })
            appendEvents(original, event("retained-before-rate"))
            if (base in listOf(2, 4, 6)) original.ensureFeatureFlagRuntime().await()
            if (base >= 3) original.ensurePreparedReplayStorage().await()
            if (base >= 5) original.ensureNativeReplayAccounting().await()
            if (offset == 24) original.configureDiagnostics(RuntimeDiagnosticsConfiguration(true, false), RuntimeDiagnosticsClock { null }).await()
            val records = original.peek(10, Long.MAX_VALUE).await()
            val state = original.snapshot().await().state
            original.closeAsync().await(); owners.remove(original)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                if (offset == 0) db.execSQL("DROP TABLE replay_audience")
                if (offset == 30) db.execSQL("DROP TABLE flag_exposure_state")
                executePragma(db, "PRAGMA user_version=${base + offset}")
            }
            val migrated = open(file, CountingIdentifiers(), RecordingFaults(), { error("No legacy import") },
                trustedSiteKey = "elu_pk_test_capture",
                captureClock = FixedCaptureClock(Instant.parse(NOW).toEpochMilli(), 1000),
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY,
                rateLimiting = dev.elu.analytics.EluRateLimitingOptions(1.0, 2.0))
            assertEquals(state, migrated.snapshot().await().state)
            assertEquals(records, migrated.peek(10, Long.MAX_VALUE).await())
            migrated.closeAsync().await(); owners.remove(migrated)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                assertEquals(base + 42, db.version)
                val bucket = RuntimeCaptureRateState.decode(STREAM_ID, singleBlob(db, "SELECT payload FROM capture_rate_limit WHERE singleton_id=1"))
                assertEquals(2.0, bucket.bucket!!.tokens, 0.0)
            }
        }
    }

    @Test fun captureRateRowRefusalsPreserveTheEntireOriginalDatabaseFamily() {
        for (wal in listOf(false, true)) for (damage in listOf("missing", "corrupt", "foreign", "table", "future")) {
            val file = databaseFile(); val suffixes = listOf("", "-wal", "-shm", "-journal")
            fun family() = suffixes.map { File(file.path + it) }.filter { it.exists() }.associate { it.name to it.readBytes() }
            val original = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState,
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY, rateLimiting = dev.elu.analytics.EluRateLimitingOptions())
            original.closeAsync().await(); owners.remove(original)
            var retained: Map<String, ByteArray>? = null
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                if (wal) { assertTrue(db.enableWriteAheadLogging()); executePragma(db, "PRAGMA wal_autocheckpoint=0") }
                else db.disableWriteAheadLogging()
                when (damage) {
                    "missing" -> db.execSQL("DELETE FROM capture_rate_limit")
                    "corrupt" -> db.execSQL("UPDATE capture_rate_limit SET payload=?", arrayOf("{}".toByteArray()))
                    "foreign" -> db.execSQL("UPDATE capture_rate_limit SET stream_id='foreign'")
                    "table" -> db.execSQL("ALTER TABLE capture_rate_limit ADD COLUMN unknown TEXT")
                    "future" -> executePragma(db, "PRAGMA user_version=55")
                }
                if (wal) retained = family().also { assertTrue(it.containsKey(file.name + "-wal")); assertTrue(it.containsKey(file.name + "-shm")) }
            }
            retained?.let { rows -> suffixes.forEach { suffix ->
                val member = File(file.path + suffix); val bytes = rows[member.name]
                if (bytes == null) { if (member.exists()) assertTrue(member.delete()) } else member.writeBytes(bytes)
            } }
            val before = family(); val faults = RecordingFaults()
            try {
                // Even a caller omitting limiter selection must validate a present new row.
                open(file, CountingIdentifiers(), faults, { error("No recovery") },
                    personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
                fail("Expected $damage refusal")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is RuntimeQueueCorruptionException || error.cause is UnsupportedRuntimeStorageSchemaException)
            }
            assertTrue(faults.connectionSettings.isEmpty())
            val after = family(); assertEquals(before.keys, after.keys)
            before.forEach { (name, bytes) -> assertArrayEquals("$damage WAL=$wal $name", bytes, after.getValue(name)) }
        }
    }

    @Test fun captureRateDebitPersistsBeforeInvalidEventAndEmptyReopenDoesNotWarn() {
        val file = databaseFile(); val wall = Instant.parse("2026-08-05T00:01:00.000Z").toEpochMilli()
        fun selected() = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState, "elu_pk_test_capture",
            FixedCaptureClock(wall, 1000), dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY,
            dev.elu.analytics.EluRateLimitingOptions(1.0, 1.0))
        val owner = selected(); owner.submitCaptureAuthority(captureConfig(), capturePrivacy()).await()
        val invalid = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "", RuntimeWallTimestamps.rfc3339(wall), emptyMap(), versions())
        assertEquals(RuntimeCaptureRejection.EVENT_INVALID, (owner.capture(invalid).await() as RuntimeCaptureResult.Rejected).reason)
        assertEquals(0, owner.snapshot().await().queuedCount)
        owner.closeAsync().await(); owners.remove(owner)
        val reopened = selected(); reopened.submitCaptureAuthority(captureConfig(), capturePrivacy()).await()
        assertEquals(RuntimeCaptureRejection.RATE_LIMITED, (reopened.capture(invalid.copy(name="after-reopen")).await() as RuntimeCaptureResult.Rejected).reason)
        assertEquals(0, reopened.snapshot().await().queuedCount)
        assertEquals(null, reopened.snapshot().await().state.identity.session)
    }

    @Test fun captureRateOptionalRollbackUsesHeldButUnknownCommitNeverReopensMemory() {
        for (memory in listOf(false, true)) {
            val file = databaseFile(); val wall = Instant.parse("2026-08-05T00:01:00.000Z").toEpochMilli()
            var rejectRead = false; var rejectWrite = false; var unknownWrite = false; var connections = 0
            val faults = object : AndroidRuntimeDatabaseFaults {
                override fun beforeCaptureRateRead() { if (rejectRead) { rejectRead=false; throw IOException("optional read") } }
                override fun beforeCommit() { if (rejectWrite) { rejectWrite=false; throw IOException("optional rolled-back write") } }
                override fun afterCommit() { if (unknownWrite) { unknownWrite=false; throw IOException("unknown commit") } }
            }
            val owner = RuntimeQueueOwner.open(file.path, RuntimeQueueLimits(100, 1_000_000), {
                connections++
                if (memory) AndroidSQLiteRuntimeDatabase.openMemory(faults) else AndroidSQLiteRuntimeDatabase.open(file, faults)
            }, ::freshState, trustedSiteKey="elu_pk_test_capture", captureClock=FixedCaptureClock(wall, 1000),
                personProfiles=dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY, memoryOnly=memory,
                rateLimiting=dev.elu.analytics.EluRateLimitingOptions(1.0, 1.0)).await().also { owners += it }
            owner.submitCaptureAuthority(captureConfig(), capturePrivacy()).await()
            val command = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "ordinary", RuntimeWallTimestamps.rfc3339(wall), emptyMap(), versions())
            rejectRead=true; rejectWrite=true
            assertTrue(owner.capture(command).await() is RuntimeCaptureResult.Accepted)
            // Readable original storage wins over the held empty bucket after failed write.
            assertTrue(owner.capture(command.copy(name="durable-wins")).await() is RuntimeCaptureResult.Accepted)
            unknownWrite=true
            assertFutureCause(AmbiguousRuntimeCommitException::class.java) { owner.capture(command).await() }
            assertFutureCause(IllegalStateException::class.java) { owner.capture(command).await() }
            assertEquals(1, connections)
            if (memory) {
                assertFalse(file.exists())
                // The original ambiguous memory connection remains quarantined until process exit.
                testDirectories.remove(file.parentFile)
            }
        }
    }


    @Test fun captureRateMemoryBucketDisappearsWithoutChangingDormantPersistentBudget() {
        val file = databaseFile(); val wall = Instant.parse("2026-08-05T00:01:00.000Z").toEpochMilli()
        val options = dev.elu.analytics.EluRateLimitingOptions(1.0, 1.0)
        fun selected(memory: Boolean) = RuntimeQueueOwner.open(file.path, RuntimeQueueLimits(100, 1_000_000), {
            if (memory) AndroidSQLiteRuntimeDatabase.openMemory() else AndroidSQLiteRuntimeDatabase.open(file)
        }, ::freshState, trustedSiteKey="elu_pk_test_capture", captureClock=FixedCaptureClock(wall, 1000),
            personProfiles=dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY, memoryOnly=memory, rateLimiting=options)
            .await().also { owners += it }
        val invalid = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "", RuntimeWallTimestamps.rfc3339(wall), emptyMap(), versions())
        val persistent = selected(false); persistent.submitCaptureAuthority(captureConfig(), capturePrivacy()).await()
        assertEquals(RuntimeCaptureRejection.EVENT_INVALID, (persistent.capture(invalid).await() as RuntimeCaptureResult.Rejected).reason)
        persistent.closeAsync().await(); owners.remove(persistent)
        val bytes = file.readBytes()
        repeat(2) {
            val memory = selected(true); memory.submitCaptureAuthority(captureConfig(), capturePrivacy()).await()
            // A new original memory connection starts full each time, not at the dormant empty balance.
            assertEquals(RuntimeCaptureRejection.EVENT_INVALID, (memory.capture(invalid).await() as RuntimeCaptureResult.Rejected).reason)
            memory.closeAsync().await(); owners.remove(memory)
            assertArrayEquals(bytes, file.readBytes())
        }
        val reopened = selected(false); reopened.submitCaptureAuthority(captureConfig(), capturePrivacy()).await()
        assertEquals(RuntimeCaptureRejection.RATE_LIMITED, (reopened.capture(invalid.copy(name="later")).await() as RuntimeCaptureResult.Rejected).reason)
        assertEquals(0, reopened.snapshot().await().queuedCount)
    }

    @Test fun exceptionWriterAndSqlImportSurviveRetainedProcessDeathFamilyAndAmbiguousCommit() {
        for (uncertain in listOf(false, true)) {
            val file = databaseFile(); val clock = FixedCaptureClock(Instant.parse(NOW).toEpochMilli(), 1000)
            fun selected(faults: RecordingFaults = RecordingFaults()): RuntimeQueueOwner = AndroidRuntimeQueue.openForTesting(
                file, RuntimeQueueLimits(1000, 1_000_000), ::freshState, trustedSiteKey = "elu_pk_test_capture",
                captureClock = clock, faults = faults, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY,
                exceptionSpoolFactory = { dev.elu.analytics.internal.diagnostics.AndroidExceptionSpool(file) })
                .await().also { owners += it }
            val original = selected()
            appendEvents(original, event("before-exception"))
            original.submitCaptureAuthority(captureConfig(), capturePrivacy()).await()
            val policy = dev.elu.analytics.internal.diagnostics.NativeExceptionPolicyLease("b".repeat(64))
            val intake = checkNotNull(original.prepareExceptionIntake(policy, versions()).await())
            intake.offer(dev.elu.analytics.internal.diagnostics.NativeExceptionObservation.from(IllegalStateException("PRIVATE")))
            intake.reportSettlement.get(5, TimeUnit.SECONDS); assertTrue(intake.published)
            // Stable committed DB/WAL + the original writer's synced report are retained before
            // graceful cleanup, then restored after lease release to model abrupt process death.
            val suffixes = listOf("", "-wal", "-shm", "-journal")
            val retained = suffixes.filter { it != "-shm" }.map { File(file.path + it) }
                .filter { it.exists() }.associate { it.name to it.readBytes() }
            val reportFile = File(file.parentFile, "exceptions-v1/report")
            val report = reportFile.readBytes()
            original.closeAsync().await(); owners.remove(original)
            for (suffix in suffixes) {
                val member = File(file.path + suffix); val bytes = retained[member.name]
                if (bytes == null) { if (member.exists()) assertTrue(member.delete()) } else member.writeBytes(bytes)
            }
            reportFile.writeBytes(report); android.system.Os.chmod(reportFile.path, 384)
            val faults = RecordingFaults(); val reopened = selected(faults)
            reopened.submitCaptureAuthority(captureConfig(), capturePrivacy()).await()
            val before = reopened.snapshot().await().state.identity
            if (uncertain) faults.failAfterCommit.set(true)
            checkNotNull(reopened.prepareExceptionIntake(dev.elu.analytics.internal.diagnostics.NativeExceptionPolicyLease("b".repeat(64)), versions()).await())
            val events = reopened.peek(10, Long.MAX_VALUE).await().filterIsInstance<RuntimeQueuedRecord.Event>()
            assertEquals(2, events.size); assertEquals(1, events.count { it.record.name == ExceptionSerializer.EVENT_NAME })
            val imported = events.last().record
            assertEquals(before, reopened.snapshot().await().state.identity)
            assertTrue(imported.groups.isEmpty()); assertFalse(imported.properties.toString().contains("PRIVATE"))
            assertFalse(reportFile.exists())
            reopened.closeAsync().await(); owners.remove(reopened)
            val final = selected(); assertEquals(events, final.peek(10, Long.MAX_VALUE).await())
        }
    }

    @Test fun exceptionMetadataMigratesAllThirtySixOwnedFamiliesWithoutChangingRecordsOrCore() {
        for (offset in listOf(0, 6, 24, 30, 36, 42)) for (base in 1..6) {
            val file = databaseFile()
            val original = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState,
                trustedSiteKey = "elu_pk_test_capture",
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY.takeIf { offset >= 30 },
                rateLimiting = dev.elu.analytics.EluRateLimitingOptions().takeIf { offset >= 42 })
            appendEvents(original, event("retained-before-exception"))
            if (base in listOf(2, 4, 6)) original.ensureFeatureFlagRuntime().await()
            if (base >= 3) original.ensurePreparedReplayStorage().await()
            if (base >= 5) original.ensureNativeReplayAccounting().await()
            if (offset == 24) original.configureDiagnostics(RuntimeDiagnosticsConfiguration(true, false), RuntimeDiagnosticsClock { null }).await()
            val records = original.peek(10, Long.MAX_VALUE).await(); val state = original.snapshot().await().state
            original.closeAsync().await(); owners.remove(original)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                if (offset == 0) db.execSQL("DROP TABLE replay_audience")
                if (offset == 30) db.execSQL("DROP TABLE flag_exposure_state")
                executePragma(db, "PRAGMA user_version=${base + offset}")
            }
            AndroidSQLiteRuntimeDatabase.open(file).use { database ->
                database.ensureExceptionSchema()
                database.transaction { tx ->
                    assertEquals(RuntimeExceptionState(STREAM_ID), tx.readCore()!!.exceptions)
                    assertArrayEquals(CoreStateCodec.encode(state), tx.readCore()!!.stateJson)
                }
            }
            val migrated = open(file, CountingIdentifiers(), RecordingFaults(), { error("No legacy import") },
                trustedSiteKey = "elu_pk_test_capture", personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            assertEquals(state, migrated.snapshot().await().state)
            assertEquals(records, migrated.peek(10, Long.MAX_VALUE).await())
            migrated.closeAsync().await(); owners.remove(migrated)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                assertEquals(base + 48, db.version)
            }
        }
    }

    @Test fun exceptionMetadataRefusesCorruptForeignMissingAndFutureFamiliesWithoutOriginalWrites() {
        for (wal in listOf(false, true)) for (damage in listOf("missing", "corrupt", "foreign", "table", "future")) {
            val file = databaseFile(); val suffixes = listOf("", "-wal", "-shm", "-journal")
            fun family() = suffixes.map { File(file.path + it) }.filter { it.exists() }.associate { it.name to it.readBytes() }
            val owner = open(file, CountingIdentifiers(), RecordingFaults(), ::freshState,
                personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            owner.closeAsync().await(); owners.remove(owner)
            AndroidSQLiteRuntimeDatabase.open(file).use { it.ensureExceptionSchema() }
            var retained: Map<String, ByteArray>? = null
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                if (wal) { assertTrue(db.enableWriteAheadLogging()); executePragma(db, "PRAGMA wal_autocheckpoint=0") }
                else db.disableWriteAheadLogging()
                when (damage) {
                    "missing" -> db.execSQL("DELETE FROM exception_state")
                    "corrupt" -> db.execSQL("UPDATE exception_state SET payload=?", arrayOf("{}".toByteArray()))
                    "foreign" -> db.execSQL("UPDATE exception_state SET payload=?", arrayOf(RuntimeExceptionState("foreign").encode()))
                    "table" -> db.execSQL("ALTER TABLE exception_state ADD COLUMN unknown TEXT")
                    "future" -> executePragma(db, "PRAGMA user_version=55")
                }
                if (wal) retained = family().also { assertTrue(it.containsKey(file.name + "-wal")); assertTrue(it.containsKey(file.name + "-shm")) }
            }
            retained?.let { rows -> suffixes.forEach { suffix ->
                val member = File(file.path + suffix); val bytes = rows[member.name]
                if (bytes == null) { if (member.exists()) assertTrue(member.delete()) } else member.writeBytes(bytes)
            } }
            val before = family(); val faults = RecordingFaults()
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                open(file, CountingIdentifiers(), faults, { error("No recovery") },
                    personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            }
            val after = family(); assertEquals(before.keys, after.keys)
            before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after.getValue(name)) }
            assertTrue(faults.connectionSettings.isEmpty())
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
        rateLimiting: dev.elu.analytics.EluRateLimitingOptions? = null,
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
                rateLimiting = rateLimiting,
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
