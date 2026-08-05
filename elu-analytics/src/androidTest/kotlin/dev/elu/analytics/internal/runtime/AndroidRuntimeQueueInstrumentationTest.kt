package dev.elu.analytics.internal.runtime

import android.database.sqlite.SQLiteDatabase
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

        assertEquals(
            listOf(
                AndroidRuntimeConnectionSettings(
                    journalMode = "wal",
                    synchronous = 2L,
                    busyTimeoutMillis = 5_000L,
                ),
            ),
            faults.connectionSettings,
        )
        assertEquals(0, owner.snapshot().await().queuedCount)
        owner.closeAsync().await()
        owners.remove(owner)

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(1, sqlite.version)
            sqlite.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null,
            ).use { cursor ->
                val tables = mutableListOf<String>()
                while (cursor.moveToNext()) tables += cursor.getString(0)
                assertEquals(listOf("core_state", "queue_records"), tables)
            }
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
            executePragma(sqlite, "PRAGMA user_version = 2")
        }

        assertFutureCause(UnsupportedRuntimeStorageSchemaException::class.java) {
            AndroidRuntimeQueue.openForTesting(
                unsupportedFile,
                RuntimeQueueLimits(100, 1_000_000),
                ::freshState,
            ).await()
        }
        SQLiteDatabase.openDatabase(unsupportedFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(2L, pragmaLong(sqlite, "PRAGMA user_version"))
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

    private fun open(
        file: File,
        identifiers: CountingIdentifiers,
        faults: RecordingFaults,
        stateLoader: () -> PersistedCoreState,
        trustedSiteKey: String? = null,
        captureClock: RuntimeCaptureClock = JvmRuntimeCaptureClock,
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
