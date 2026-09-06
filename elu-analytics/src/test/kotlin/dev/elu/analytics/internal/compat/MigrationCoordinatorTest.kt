package dev.elu.analytics.internal.compat

import dev.elu.analytics.internal.core.CORE_SCHEMA_VERSION
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.runtime.FakeRuntimeQueueBacking
import dev.elu.analytics.internal.runtime.RuntimeQueueDatabase
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.UnsupportedRuntimeStorageSchemaException
import java.io.IOException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MigrationCoordinatorTest {
    private val backing = FakeRuntimeQueueBacking()
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val keyCounter = AtomicInteger()
    private val ownershipKey = "migration-owner-${OWNER_KEYS.incrementAndGet()}"

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        owners.asReversed().forEach { owner -> runCatching { owner.closeAsync().get(10, TimeUnit.SECONDS) } }
        owners.clear()
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test
    fun `a supported source imports identity context and opt state in one commit`() {
        val source = supportedSource()

        val result = run(source)

        val state = committed(result)
        assertEquals(MigrationOutcome.IMPORTED, result.report.outcome)
        assertEquals(LegacySourceCondition.SUPPORTED, result.report.sourceCondition)
        assertEquals(LegacyRecoveryAction.IMPORT, result.report.recoveryAction)
        assertEquals("anon-from-prior-state", state.identity.anonymousId)
        assertEquals("user-42", state.identity.userId)
        assertFalse(state.identity.optedOut)
        assertEquals(mapOf("plan" to "growth"), state.identity.superProperties)
        assertEquals(mapOf("organization" to "org-7"), state.identity.groups)
        assertEquals(mapOf("role" to "admin"), state.flagContext.personProperties)
        assertEquals(mapOf("organization" to mapOf<String, Any?>("tier" to "growth")), state.flagContext.groupProperties)
        assertEquals("fake-legacy-v1:imported", state.identity.migration?.sourceSchema)
        assertNull("an imported installation starts without a session", state.identity.session)
        assertEquals(0L, state.stream.nextSequence)
        assertEquals(1, backing.mutatedTransactionAttempts)
        assertEquals(emptySet<LegacyStateKey>(), result.report.droppedContext)
        assertNull(result.report.quarantine)
        assertEquals(CORE_SCHEMA_VERSION, result.report.targetSchemaVersion)
    }

    @Test
    fun `a re-run reuses the committed state and never opens the prior state again`() {
        val first = run(supportedSource())
        val firstState = committed(first)
        close(first)

        val source = supportedSource().apply { text(LegacyStateKey.ANONYMOUS_ID, "a-different-identity") }
        val second = run(source)

        assertEquals(MigrationOutcome.ALREADY_COMPLETE, second.report.outcome)
        assertEquals(LegacySourceCondition.NOT_READ, second.report.sourceCondition)
        assertNull(second.report.recoveryAction)
        assertEquals(0, source.probeCalls)
        assertEquals(0, source.readCalls)
        assertEquals(firstState.identity.anonymousId, committed(second).identity.anonymousId)
        assertEquals(1, backing.mutatedTransactionAttempts)
    }

    @Test
    fun `a failure at the commit leaves the prior state authoritative for the next run`() {
        backing.failNextKnownCommit = IOException("storage unavailable at commit")

        val failed = run(supportedSource())

        assertEquals(MigrationOutcome.FAILED, failed.report.outcome)
        assertNull(failed.owner)
        assertNull("nothing may be committed before the migration transaction succeeds", backing.core)

        RuntimeQueueOwner.clearOwnershipForTesting()
        val retried = run(supportedSource())
        assertEquals(MigrationOutcome.IMPORTED, retried.report.outcome)
        assertEquals("anon-from-prior-state", committed(retried).identity.anonymousId)
    }

    @Test
    fun `state already present without a witness is left alone`() {
        val existing = run(FakeLegacyStateSource().apply { probe = LegacyProbe.ABSENT })
        val anonymousId = committed(existing).identity.anonymousId
        close(existing)
        // Strip the witness the way state written before the bridge existed would look.
        rewriteCommitted { state -> state.copy(identity = state.identity.copy(migration = null)) }

        val source = supportedSource()
        val result = run(source)

        assertEquals(MigrationOutcome.NOT_REQUIRED, result.report.outcome)
        assertEquals(0, source.probeCalls)
        assertEquals(anonymousId, committed(result).identity.anonymousId)
    }

    @Test
    fun `state that does not read back the way it was written is refused`() {
        val source = supportedSource()
        val opens = AtomicInteger()
        val result =
            MigrationCoordinator(
                source = source,
                ownerOpener = { loader ->
                    // Between the commit and the readback, storage answers with different state.
                    if (opens.incrementAndGet() == 2) {
                        rewriteCommitted { state ->
                            state.copy(identity = state.identity.copy(anonymousId = "rewritten-by-storage"))
                        }
                    }
                    openOwner(loader)
                },
                identifiers = countingIdentifiers(),
            ).run()

        assertEquals(MigrationOutcome.READBACK_MISMATCH, result.report.outcome)
        assertNull("capture stays closed when the committed state cannot be trusted", result.owner)
    }

    @Test
    fun `a corrupt source is quarantined and the installation starts fresh and opted out`() {
        val source = supportedSource().apply { probe = LegacyProbe.CORRUPT }

        val result = run(source)

        assertEquals(MigrationOutcome.FRESH_START, result.report.outcome)
        assertEquals(LegacySourceCondition.CORRUPT, result.report.sourceCondition)
        assertEquals(LegacyRecoveryAction.QUARANTINE_THEN_FRESH, result.report.recoveryAction)
        assertEquals(LegacyQuarantineOutcome.QUARANTINED, result.report.quarantine)
        assertEquals(1, source.quarantineCalls)
        val state = committed(result)
        assertNotEquals("anon-from-prior-state", state.identity.anonymousId)
        assertTrue("an unreadable prior state may have carried an opt-out", state.identity.optedOut)
        assertEquals("fake-legacy-v1:fresh", state.identity.migration?.sourceSchema)
    }

    @Test
    fun `quarantine happens only after the committed state reads back`() {
        backing.failNextKnownCommit = IOException("storage unavailable at commit")
        val source = supportedSource().apply { probe = LegacyProbe.UNSUPPORTED }

        val result = run(source)

        assertEquals(MigrationOutcome.FAILED, result.report.outcome)
        assertEquals(0, source.quarantineCalls)
        assertNull(result.report.quarantine)
    }

    @Test
    fun `an oversized value is treated as unreadable prior state`() {
        val source = supportedSource().apply { text(LegacyStateKey.ANONYMOUS_ID, "x".repeat(2_048)) }

        val result = run(source)

        assertEquals(LegacySourceCondition.OVERSIZED, result.report.sourceCondition)
        assertEquals(LegacyRecoveryAction.QUARANTINE_THEN_FRESH, result.report.recoveryAction)
        assertEquals(1, source.quarantineCalls)
        assertNotEquals("anon-from-prior-state", committed(result).identity.anonymousId)
    }

    @Test
    fun `a partially written source keeps its identity out of the new state`() {
        val source = supportedSource().apply { values.remove(LegacyStateKey.ANONYMOUS_ID) }

        val result = run(source)

        assertEquals(LegacySourceCondition.PARTIAL, result.report.sourceCondition)
        assertEquals(LegacyRecoveryAction.QUARANTINE_THEN_FRESH, result.report.recoveryAction)
        val state = committed(result)
        assertNull("no user may be carried over from an incomplete prior state", state.identity.userId)
        assertFalse("the readable privacy choice still applies", state.identity.optedOut)
    }

    @Test
    fun `a permission-denied source is left in place and the installation starts opted out`() {
        val source = supportedSource().apply { probeFailure = SecurityException("no access") }

        val result = run(source)

        assertEquals(LegacySourceCondition.PERMISSION_DENIED, result.report.sourceCondition)
        assertEquals(LegacyRecoveryAction.FRESH, result.report.recoveryAction)
        assertEquals("an unreadable store is never moved aside", 0, source.quarantineCalls)
        assertTrue(committed(result).identity.optedOut)
    }

    @Test
    fun `a future source is quarantined rather than guessed at`() {
        val source = supportedSource().apply { probe = LegacyProbe.FUTURE }

        val result = run(source)

        assertEquals(LegacySourceCondition.FUTURE, result.report.sourceCondition)
        assertEquals(LegacyQuarantineOutcome.QUARANTINED, result.report.quarantine)
    }

    @Test
    fun `an absent source starts a fresh opted-in installation and touches nothing`() {
        val source = FakeLegacyStateSource().apply { probe = LegacyProbe.ABSENT }

        val result = run(source)

        assertEquals(MigrationOutcome.FRESH_START, result.report.outcome)
        assertEquals(LegacySourceCondition.ABSENT, result.report.sourceCondition)
        assertEquals(LegacyRecoveryAction.FRESH, result.report.recoveryAction)
        assertEquals(0, source.quarantineCalls)
        val state = committed(result)
        assertFalse(state.identity.optedOut)
        assertEquals("none", state.identity.migration?.sourceSchema)
    }

    @Test
    fun `unreadable context is dropped and reported while the identity still imports`() {
        val source =
            supportedSource().apply {
                values[LegacyStateKey.SUPER_PROPERTIES] = LegacyValue.Unreadable(LegacyValueProblem.CORRUPT)
                document(LegacyStateKey.GROUPS, mapOf("organization" to 7))
            }

        val result = run(source)

        assertEquals(MigrationOutcome.IMPORTED, result.report.outcome)
        assertEquals(
            setOf(LegacyStateKey.SUPER_PROPERTIES, LegacyStateKey.GROUPS),
            result.report.droppedContext,
        )
        val state = committed(result)
        assertEquals("anon-from-prior-state", state.identity.anonymousId)
        assertEquals(emptyMap<String, Any?>(), state.identity.superProperties)
        assertEquals(emptyMap<String, String>(), state.identity.groups)
    }

    @Test
    fun `an unreadable privacy choice is honoured as an opt-out`() {
        val source =
            supportedSource().apply {
                values[LegacyStateKey.OPTED_OUT] = LegacyValue.Unreadable(LegacyValueProblem.WRONG_TYPE)
            }

        val result = run(source)

        assertEquals(MigrationOutcome.IMPORTED, result.report.outcome)
        assertTrue(committed(result).identity.optedOut)
        assertTrue(LegacyStateKey.OPTED_OUT in result.report.droppedContext)
    }

    @Test
    fun `a newer storage schema refuses the migration without reading the prior state`() {
        val source = supportedSource()

        val result =
            MigrationCoordinator(
                source = source,
                ownerOpener = { loader -> openOwner(loader) { throw UnsupportedRuntimeStorageSchemaException(4_096) } },
                identifiers = countingIdentifiers(),
            ).run()

        assertEquals(MigrationOutcome.REFUSED_STORAGE_SCHEMA, result.report.outcome)
        assertNull(result.owner)
        assertEquals(0, source.probeCalls)
        assertEquals(0, source.readCalls)
        assertNull("nothing may be committed when the storage schema is refused", backing.core)
    }

    @Test
    fun `a busy installation namespace refuses the migration without reading the prior state`() {
        val held = run(supportedSource())
        val source = supportedSource()

        val result =
            MigrationCoordinator(
                source = source,
                ownerOpener = ::openOwner,
                identifiers = countingIdentifiers(),
            ).run()

        assertEquals(MigrationOutcome.REFUSED_OWNER_UNAVAILABLE, result.report.outcome)
        assertNull(result.owner)
        assertEquals(0, source.probeCalls)
        close(held)
    }

    @Test
    fun `the report carries outcome enums schema versions and timing only`() {
        val source = supportedSource().apply { queue += legacyRecord("r1", occurredAt = 10) }

        val report = run(source).report

        assertEquals("fake-legacy-v1", report.sourceSchema)
        assertEquals(CORE_SCHEMA_VERSION, report.targetSchemaVersion)
        assertEquals("0.1.0", report.sdkVersion)
        assertTrue(report.elapsedMillis >= 0)
        assertEquals(LegacyQueueSummary(1, 40), report.legacyQueue)
        val rendered = report.toString()
        listOf("anon-from-prior-state", "user-42", "org-7", "growth", "admin").forEach { secret ->
            assertFalse("a report must not carry $secret", rendered.contains(secret))
        }
    }

    @Test
    fun `the migration itself never touches the prior queue`() {
        val source = supportedSource().apply { queue += legacyRecord("r1", occurredAt = 10) }

        run(source)

        assertEquals(emptyList<Pair<Int, Long>>(), source.readPages)
        assertEquals(emptyList<String>(), source.discarded)
        assertEquals(1, source.queue.size)
    }

    @Test
    fun `retaining the prior queue disposes of nothing`() {
        val coordinator = coordinator(supportedSource().apply { queue += legacyRecord("r1", occurredAt = 10) })

        val disposition = coordinator.disposeLegacyQueue(LegacyQueuePolicy.Retain)

        assertEquals(LegacyQueueDisposition(0, 0, 0, LegacyQueueStop.RETAINED), disposition)
    }

    @Test
    fun `a drain discards only the records the sink durably accepted`() {
        val source =
            FakeLegacyStateSource().apply {
                queue += listOf(legacyRecord("r1"), legacyRecord("r2"), legacyRecord("r3"))
            }
        val seen = mutableListOf<String>()
        val sink =
            LegacyRecordSink { records ->
                seen += records.map { record -> record.id }
                LegacyRecordAcceptance(records.map { record -> record.id }.toSet())
            }

        val disposition = coordinator(source).disposeLegacyQueue(LegacyQueuePolicy.Drain(sink, pageSize = 2))

        assertEquals(listOf("r1", "r2", "r3"), seen)
        assertEquals(LegacyQueueDisposition(3, 3, 3, LegacyQueueStop.EXHAUSTED), disposition)
        assertTrue(source.queue.isEmpty())
    }

    @Test
    fun `a drain stops instead of dropping a record the sink did not accept`() {
        val source = FakeLegacyStateSource().apply { queue += listOf(legacyRecord("r1"), legacyRecord("r2")) }
        val sink = LegacyRecordSink { _ -> LegacyRecordAcceptance(setOf("r1")) }

        val disposition = coordinator(source).disposeLegacyQueue(LegacyQueuePolicy.Drain(sink, pageSize = 2))

        assertEquals(LegacyQueueDisposition(2, 1, 1, LegacyQueueStop.NO_PROGRESS), disposition)
        assertEquals(listOf("r2"), source.queue.map { record -> record.id })
    }

    @Test
    fun `a drain reports a source failure without claiming acceptance`() {
        val source = FakeLegacyStateSource().apply { queueFailure = IOException("prior queue unreadable") }

        val disposition =
            coordinator(source).disposeLegacyQueue(
                LegacyQueuePolicy.Drain({ _ -> LegacyRecordAcceptance(emptySet()) }),
            )

        assertEquals(LegacyQueueDisposition(0, 0, 0, LegacyQueueStop.SOURCE_FAILURE), disposition)
    }

    @Test
    fun `expiry discards only records older than the cutoff and keeps undated ones`() {
        val source =
            FakeLegacyStateSource().apply {
                queue +=
                    listOf(
                        legacyRecord("old", occurredAt = 100),
                        legacyRecord("undated", occurredAt = null),
                        legacyRecord("recent", occurredAt = 900),
                    )
            }

        val disposition =
            coordinator(source).disposeLegacyQueue(LegacyQueuePolicy.Expire(cutoffEpochMillis = 500))

        assertEquals(1, disposition.discarded)
        assertEquals(0, disposition.accepted)
        assertEquals(LegacyQueueStop.NO_PROGRESS, disposition.stop)
        assertEquals(listOf("undated", "recent"), source.queue.map { record -> record.id })
    }

    private fun supportedSource(): FakeLegacyStateSource =
        FakeLegacyStateSource()
            .text(LegacyStateKey.ANONYMOUS_ID, "anon-from-prior-state")
            .text(LegacyStateKey.USER_ID, "user-42")
            .flag(LegacyStateKey.IDENTIFIED, true)
            .flag(LegacyStateKey.OPTED_OUT, false)
            .document(LegacyStateKey.SUPER_PROPERTIES, mapOf("plan" to "growth"))
            .document(LegacyStateKey.GROUPS, mapOf("organization" to "org-7"))
            .document(LegacyStateKey.PERSON_PROPERTIES_FOR_FLAGS, mapOf("role" to "admin"))
            .document(LegacyStateKey.GROUP_PROPERTIES_FOR_FLAGS, mapOf("organization" to mapOf("tier" to "growth")))

    private fun legacyRecord(
        id: String,
        occurredAt: Long? = 1_000,
    ): LegacyQueuedRecord =
        LegacyQueuedRecord(
            id = id,
            occurredAtEpochMillis = occurredAt,
            name = "prior_event",
            properties = mapOf("index" to id),
            encodedBytes = 40,
        )

    private fun coordinator(source: FakeLegacyStateSource): MigrationCoordinator =
        MigrationCoordinator(source = source, ownerOpener = ::openOwner, identifiers = countingIdentifiers())

    private fun run(source: FakeLegacyStateSource): MigrationResult =
        coordinator(source).run().also { result -> result.owner?.let { owner -> owners += owner } }

    private fun committed(result: MigrationResult): PersistedCoreState =
        checkNotNull(result.owner) { "migration did not hand back an owner" }
            .snapshot()
            .get(10, TimeUnit.SECONDS)
            .state

    private fun close(result: MigrationResult) {
        result.owner?.let { owner ->
            owner.closeAsync().get(10, TimeUnit.SECONDS)
            owners.remove(owner)
        }
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    private fun openOwner(
        loader: () -> PersistedCoreState,
        databaseFactory: () -> RuntimeQueueDatabase = backing::connection,
    ): Future<RuntimeQueueOwner> =
        RuntimeQueueOwner.open(
            ownershipKey = ownershipKey,
            limits = RuntimeQueueLimits(10_000, 16_777_216),
            databaseFactory = databaseFactory,
            legacyStateLoader = loader,
            identifiers = countingIdentifiers(),
        )

    /** Rewrites the committed core the way a divergent or pre-bridge store would read back. */
    private fun rewriteCommitted(transform: (PersistedCoreState) -> PersistedCoreState) {
        val core = checkNotNull(backing.core) { "nothing is committed yet" }
        val rewritten = transform(CoreStateCodec.decode(core.stateJson))
        backing.core = core.copy(stateJson = CoreStateCodec.encode(rewritten))
    }

    private fun countingIdentifiers(): CoreIdentifierGenerator =
        CoreIdentifierGenerator { prefix -> prefix + keyCounter.incrementAndGet().toString().padStart(8, '0') }

    private companion object {
        val OWNER_KEYS = AtomicInteger()
    }
}
