package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.SessionState
import dev.elu.analytics.internal.core.StreamState
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RuntimeQueueOwnerTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val keyCounter = AtomicInteger()

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        owners.asReversed().forEach { owner -> runCatching { owner.closeAsync().await() } }
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test
    fun `fresh multi-record append commits state rows counters and sequence from zero`() {
        val backing = FakeRuntimeQueueBacking()
        val identifiers = CountingIdentifiers()
        val owner = open(backing, identifiers = identifiers)

        val result =
            owner.appendMutations(
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
                ),
            ).await() as RuntimeAppendResult.Accepted

        assertEquals(listOf(0L, 1L), result.records.map { it.sequence })
        assertEquals(2, result.snapshot.queuedCount)
        assertEquals(2L, result.snapshot.state.stream.nextSequence)
        assertEquals(0L, result.snapshot.headSequence)
        assertEquals("org_1", result.snapshot.state.identity.groups["organization"])
        val peeked = owner.peek(10, Long.MAX_VALUE).await()
        assertEquals(result.records.map { it.recordId }, peeked.map { it.recordId })
        assertTrue(backing.transactionThreads.all { it.name == "elu-runtime-storage" })

        owner.closeAsync().await()
        owners.remove(owner)
        val reopened =
            open(
                backing,
                identifiers = identifiers,
                legacyStateLoader = { error("legacy state must not be read after SQLite initialization") },
            )
        assertEquals(result.records.map { it.recordId }, reopened.peek(10, Long.MAX_VALUE).await().map { it.recordId })
        assertEquals(2L, reopened.snapshot().await().state.stream.nextSequence)
    }

    @Test
    fun `count rejection keeps the head state and next sequence unchanged`() {
        val backing = FakeRuntimeQueueBacking()
        val identifiers = CountingIdentifiers()
        val owner = open(backing, limits = RuntimeQueueLimits(1, 1_000_000), identifiers = identifiers)
        val first = appendEvents(owner, event("first")) as RuntimeAppendResult.Accepted
        val originalId = first.records.single().recordId

        val rejected =
            owner.appendMutations(
                listOf(
                    mutation(RuntimeMutationChange.Identify("must-not-commit", emptyMap(), emptyMap())),
                ),
            ).await() as RuntimeAppendResult.Rejected

        assertEquals(RuntimeAppendRejection.COUNT_LIMIT, rejected.reason)
        assertEquals(1L, rejected.snapshot.state.stream.nextSequence)
        assertEquals(null, rejected.snapshot.state.identity.userId)
        assertEquals(0L, rejected.snapshot.headSequence)
        assertEquals(originalId, owner.peek(10, Long.MAX_VALUE).await().single().recordId)
        assertEquals(0, identifiers.recordCalls)
    }

    @Test
    fun `exact byte ceiling succeeds and one byte below rejects without sequence use`() {
        val measurementBacking = FakeRuntimeQueueBacking()
        val measurement = open(measurementBacking, identifiers = CountingIdentifiers())
        val bytes =
            (appendEvents(measurement, event("unicode-💡")) as RuntimeAppendResult.Accepted)
                .snapshot.queuedBytes
        measurement.closeAsync().await()
        owners.remove(measurement)

        val exact = open(FakeRuntimeQueueBacking(), RuntimeQueueLimits(10, bytes), CountingIdentifiers())
        assertTrue(appendEvents(exact, event("unicode-💡")) is RuntimeAppendResult.Accepted)

        val below = open(FakeRuntimeQueueBacking(), RuntimeQueueLimits(10, bytes - 1L), CountingIdentifiers())
        val rejected = appendEvents(below, event("unicode-💡")) as RuntimeAppendResult.Rejected
        assertEquals(RuntimeAppendRejection.BYTE_LIMIT, rejected.reason)
        assertEquals(0, rejected.snapshot.queuedCount)
        assertEquals(0L, rejected.snapshot.state.stream.nextSequence)
    }

    @Test
    fun `queue bytes use canonical batch wrappers while internal payloads retain mutation versions`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        val eventResult = appendEvents(owner, event("event")) as RuntimeAppendResult.Accepted
        val mutationResult =
            owner.appendMutations(
                listOf(mutation(RuntimeMutationChange.SetPersonProperties(mapOf("plan" to "growth"), emptyMap(), emptyList()))),
            ).await() as RuntimeAppendResult.Accepted
        val records = eventResult.records + mutationResult.records

        assertEquals(records.sumOf { it.accountedBytes.toLong() }, mutationResult.snapshot.queuedBytes)
        records.forEach { record ->
            assertEquals(RuntimeRecordCodec.encodeBatchRecord(record).size, record.accountedBytes)
            assertNotEquals(backing.records.getValue(record.sequence).internalPayload.size, record.accountedBytes)
        }
        val queuedMutation = records.last() as RuntimeQueuedRecord.Mutation
        assertEquals(versions(), queuedMutation.envelope.versions)
    }

    @Test
    fun `known commit failure publishes nothing and consumes no sequence`() {
        val backing = FakeRuntimeQueueBacking()
        val identifiers = CountingIdentifiers()
        val owner = open(backing, identifiers = identifiers)
        backing.failNextKnownCommit = IOException("disk full")

        assertFutureCause(IOException::class.java) {
            owner.appendMutations(
                listOf(mutation(RuntimeMutationChange.Identify("not-durable", emptyMap(), emptyMap()))),
            ).await()
        }

        val snapshot = owner.snapshot().await()
        assertEquals(null, snapshot.state.identity.userId)
        assertEquals(0L, snapshot.state.stream.nextSequence)
        assertEquals(0, snapshot.queuedCount)
        val retry = appendEvents(owner, event("retry")) as RuntimeAppendResult.Accepted
        assertEquals(0L, retry.records.single().sequence)
    }

    @Test
    fun `ambiguous committed and rolled-back appends reopen and retain candidate IDs`() {
        val backing = FakeRuntimeQueueBacking()
        val identifiers = CountingIdentifiers()
        val owner = open(backing, identifiers = identifiers)

        backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        val committed = appendEvents(owner, event("committed")) as RuntimeAppendResult.Accepted
        assertEquals(0L, committed.records.single().sequence)

        backing.ambiguousNextCommit = FakeAmbiguousOutcome.ROLLBACK
        val retried = appendEvents(owner, event("retried")) as RuntimeAppendResult.Accepted
        assertEquals(1L, retried.records.single().sequence)
        assertEquals(0, identifiers.recordCalls)
        assertNotEquals(committed.records.single().recordId, retried.records.single().recordId)
        assertEquals(listOf(0L, 1L), owner.peek(10, Long.MAX_VALUE).await().map { it.sequence })
    }

    @Test
    fun `exact acknowledgement mismatch deletes nothing and stale prefix is idempotent`() {
        val owner = open(FakeRuntimeQueueBacking())
        appendEvents(owner, event("a"), event("b"), event("c"))
        val queued = owner.peek(10, Long.MAX_VALUE).await()
        val refs = queued.map { RuntimeRecordReference(it.sequence, it.kind, it.recordId) }

        val wrongKind = RuntimeRecordKind.MUTATION
        val wrongReference =
            refs.first().copy(
                kind = wrongKind,
                recordId = RuntimeRecordIdentity.recordId(STREAM_ID, refs.first().sequence, wrongKind),
            )
        assertFutureCause(RuntimeAcknowledgementMismatchException::class.java) {
            owner.acknowledge(acknowledgement(listOf(wrongReference))).await()
        }
        assertEquals(3, owner.snapshot().await().queuedCount)

        val deleted = owner.acknowledge(acknowledgement(refs.take(2))).await() as RuntimeAcknowledgementResult.Deleted
        assertEquals(2, deleted.count)
        assertEquals(2L, deleted.snapshot.headSequence)
        assertTrue(owner.acknowledge(acknowledgement(refs.take(2))).await() is RuntimeAcknowledgementResult.AlreadyApplied)

        assertFutureCause(RuntimeAcknowledgementMismatchException::class.java) {
            owner.acknowledge(acknowledgement(refs.drop(1))).await()
        }
        assertEquals(1, owner.snapshot().await().queuedCount)

        val final = owner.acknowledge(acknowledgement(refs.takeLast(1))).await() as RuntimeAcknowledgementResult.Deleted
        assertEquals(0, final.snapshot.queuedCount)
        assertEquals(3L, final.snapshot.headSequence)
        assertTrue(owner.peek(10, Long.MAX_VALUE).await().isEmpty())
    }

    @Test
    fun `ambiguous acknowledgement commit and rollback both reconcile exactly`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        appendEvents(owner, event("a"), event("b"))
        val references =
            owner.peek(10, Long.MAX_VALUE).await().map { record ->
                RuntimeRecordReference(record.sequence, record.kind, record.recordId)
            }

        backing.ambiguousNextCommit = FakeAmbiguousOutcome.ROLLBACK
        val first = owner.acknowledge(acknowledgement(references.take(1))).await() as RuntimeAcknowledgementResult.Deleted
        assertEquals(1, first.snapshot.queuedCount)
        assertEquals(1L, first.snapshot.headSequence)

        backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        val second = owner.acknowledge(acknowledgement(references.takeLast(1))).await() as RuntimeAcknowledgementResult.Deleted
        assertEquals(0, second.snapshot.queuedCount)
        assertEquals(2L, second.snapshot.headSequence)
    }

    @Test
    fun `ambiguous read-only and stale acknowledgements always reopen before retry`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        appendEvents(owner, event("a"))
        val queued = owner.peek(1, MAX_RUNTIME_DELIVERY_BYTES).await().single()
        val reference = RuntimeRecordReference(queued.sequence, queued.kind, queued.recordId)
        owner.acknowledge(acknowledgement(listOf(reference))).await()

        var connections = backing.connectionCalls
        backing.ambiguousNextReadOnlyTransaction = true
        assertTrue(
            owner.acknowledge(acknowledgement(emptyList())).await() is RuntimeAcknowledgementResult.Empty,
        )
        assertEquals(++connections, backing.connectionCalls)

        backing.ambiguousNextReadOnlyTransaction = true
        assertTrue(
            owner.acknowledge(acknowledgement(listOf(reference))).await() is
                RuntimeAcknowledgementResult.AlreadyApplied,
        )
        assertEquals(++connections, backing.connectionCalls)
    }

    @Test
    fun `limit shrink preserves live queue and rejects until acknowledgement frees capacity`() {
        val backing = FakeRuntimeQueueBacking()
        val original = open(backing, limits = RuntimeQueueLimits(2, 1_000_000))
        appendEvents(original, event("a"), event("b"))
        original.closeAsync().await()
        owners.remove(original)

        val shrunken = open(backing, limits = RuntimeQueueLimits(1, 1_000_000))
        val queued = shrunken.peek(10, Long.MAX_VALUE).await()
        assertEquals(2, queued.size)
        assertTrue(appendEvents(shrunken, event("rejected")) is RuntimeAppendResult.Rejected)

        val references = queued.map { RuntimeRecordReference(it.sequence, it.kind, it.recordId) }
        shrunken.acknowledge(acknowledgement(references)).await()
        val accepted = appendEvents(shrunken, event("accepted")) as RuntimeAppendResult.Accepted
        assertEquals(2L, accepted.records.single().sequence)
    }

    @Test
    fun `serialized concurrent submissions create one contiguous mixed stream`() {
        val owner = open(FakeRuntimeQueueBacking(), limits = RuntimeQueueLimits(100, 2_000_000))
        val futures =
            (0 until 50).map { index ->
                if (index % 2 == 0) {
                    owner.appendEvents(RuntimeEventSessionUpdate.Replace(session()), listOf(event("event-$index")))
                } else {
                    owner.appendMutations(
                        listOf(mutation(RuntimeMutationChange.LinkAlias("alias-$index", "anon_test"))),
                    )
                }
            }
        futures.forEach { assertTrue(it.await() is RuntimeAppendResult.Accepted) }

        val records = owner.peek(100, Long.MAX_VALUE).await()
        assertEquals((0L until 50L).toList(), records.map { it.sequence })
        assertEquals(50, records.map { it.recordId }.toSet().size)
        assertEquals(50L, owner.snapshot().await().state.stream.nextSequence)
    }

    @Test
    fun `atomic append rejects more than one hundred drafts before submission`() {
        val owner = open(FakeRuntimeQueueBacking())

        assertThrows(IllegalArgumentException::class.java) {
            owner.appendEvents(
                RuntimeEventSessionUpdate.Replace(session()),
                List(MAX_RUNTIME_APPEND_RECORDS + 1) { index -> event("event-$index") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            owner.appendMutations(
                List(MAX_RUNTIME_APPEND_RECORDS + 1) { index ->
                    mutation(RuntimeMutationChange.LinkAlias("alias-$index", "anon_test"))
                },
            )
        }
        val snapshot = owner.snapshot().await()
        assertEquals(0, snapshot.queuedCount)
        assertEquals(0L, snapshot.state.stream.nextSequence)
    }

    @Test
    fun `state-only transaction persists without advancing sequence`() {
        val owner = open(FakeRuntimeQueueBacking())
        val result =
            owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, LATER)).await() as RuntimeAppendResult.Accepted

        assertTrue(result.snapshot.state.identity.optedOut)
        assertEquals(1L, result.snapshot.state.identity.contextRevision)
        assertEquals(0L, result.snapshot.state.stream.nextSequence)
        assertEquals(0, result.snapshot.queuedCount)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `corrupt counters fail closed and release ownership`() {
        val backing = FakeRuntimeQueueBacking()
        val validState = freshState()
        backing.core =
            RuntimeStoredCore(
                dev.elu.analytics.internal.core.CoreStateCodec.encode(validState),
                queueCount = 1,
                queueBytes = 0,
            )

        assertFutureCause(RuntimeQueueCorruptionException::class.java) {
            RuntimeQueueOwner.open(
                ownershipKey = nextKey(),
                limits = RuntimeQueueLimits(10, 1_000_000),
                databaseFactory = backing::connection,
                legacyStateLoader = ::freshState,
            ).await()
        }
    }

    @Test
    fun `external state divergence poisons the owner after its transaction unwinds`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        val stored = checkNotNull(backing.core)
        backing.core = stored.copy(queueBytes = 1)

        assertFutureCause(RuntimeQueueCorruptionException::class.java) {
            appendEvents(owner, event("must-not-append"))
        }
        assertFutureCause(IllegalStateException::class.java) {
            owner.snapshot().await()
        }
    }

    @Test
    fun `event session and mutation contexts are derived from persisted transitions`() {
        val owner = open(FakeRuntimeQueueBacking())

        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendEvents(RuntimeEventSessionUpdate.Preserve, listOf(event("missing-session"))).await()
        }
        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendEvents(
                RuntimeEventSessionUpdate.Replace(session().copy(id = "session_other")),
                listOf(event("mismatch")),
            ).await()
        }

        val mutationResult =
            owner.appendMutations(
                listOf(
                    mutation(RuntimeMutationChange.AssociateGroup("organization", "org_1")),
                    mutation(
                        RuntimeMutationChange.SetGroupProperties(
                            "organization",
                            "org_1",
                            mapOf("tier" to "growth"),
                            emptyMap(),
                            emptyList(),
                        ),
                    ),
                ),
            ).await() as RuntimeAppendResult.Accepted
        val mutations = mutationResult.records.map { (it as RuntimeQueuedRecord.Mutation).envelope.mutation }
        assertEquals(listOf(1L, 2L), mutations.map { it.contextRevision })
        assertEquals(listOf(0L, 0L), mutations.map { it.subject.identityRevision })
        assertEquals(2L, mutationResult.snapshot.state.identity.contextRevision)

        val eventResult = appendEvents(owner, event("derived")) as RuntimeAppendResult.Accepted
        val record = (eventResult.records.single() as RuntimeQueuedRecord.Event).record
        assertEquals(session().id, record.sessionId)
        assertEquals(mutationResult.snapshot.state.identity.groups, record.groups)
        assertEquals(mutationResult.snapshot.state.identity.contextRevision, record.contextRevision)
        assertEquals(mutationResult.snapshot.state.identity.revision, record.identity.revision)
    }

    @Test
    fun `oversized Android SQLite row is permanently rejected before sequence allocation`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeQueueLimits(MAX_RUNTIME_QUEUE_RECORDS + 1, MAX_RUNTIME_QUEUE_BYTES)
        }
        val owner = open(FakeRuntimeQueueBacking(), limits = RuntimeQueueLimits(10, MAX_RUNTIME_QUEUE_BYTES))
        val oversized =
            event("oversized").copy(
                properties = mapOf("payload" to "x".repeat(MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES)),
            )

        val result = appendEvents(owner, oversized) as RuntimeAppendResult.Rejected

        assertEquals(RuntimeAppendRejection.RECORD_TOO_LARGE, result.reason)
        assertEquals(0L, result.snapshot.state.stream.nextSequence)
        assertEquals(0, result.snapshot.queuedCount)
    }

    @Test
    fun `peek reports an oversized head instead of returning an empty batch`() {
        val owner = open(FakeRuntimeQueueBacking())
        appendEvents(owner, event("head"))

        assertFutureCause(RuntimeQueueHeadTooLargeException::class.java) {
            owner.peek(1, 1).await()
        }
        assertEquals(1, owner.snapshot().await().queuedCount)
    }

    @Test
    fun `session mutation and local timestamps may not move persisted time backward`() {
        val owner = open(FakeRuntimeQueueBacking())
        appendEvents(owner, event("initial"))

        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendEvents(
                RuntimeEventSessionUpdate.Replace(session().copy(startedAt = LATER, lastActivityAt = LATER)),
                listOf(event("changed-start")),
            ).await()
        }
        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendEvents(
                RuntimeEventSessionUpdate.Replace(
                    session().copy(id = "session_replacement", startedAt = EARLIER, lastActivityAt = LATER),
                ),
                listOf(event("stale-replacement").copy(expectedSessionId = "session_replacement")),
            ).await()
        }
        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendMutations(listOf(mutationAt(EARLIER, RuntimeMutationChange.LinkAlias("alias", "anon_test")))).await()
        }
        assertFutureCause(IllegalArgumentException::class.java) {
            owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, EARLIER)).await()
        }

        val snapshot = owner.snapshot().await()
        assertEquals(NOW, snapshot.state.identity.updatedAt)
        assertEquals(1, snapshot.queuedCount)
        assertEquals(1L, snapshot.state.stream.nextSequence)
    }

    @Test
    fun `event drafts are ordered and never exceed their session activity timestamp`() {
        val owner = open(FakeRuntimeQueueBacking())

        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendEvents(
                RuntimeEventSessionUpdate.Replace(session()),
                listOf(eventAt("after-session", LATER)),
            ).await()
        }
        val accepted =
            owner.appendEvents(
                RuntimeEventSessionUpdate.Replace(session(lastActivityAt = LATER)),
                listOf(eventAt("first", NOW), eventAt("second", LATER)),
            ).await() as RuntimeAppendResult.Accepted
        assertEquals(listOf(NOW, LATER), accepted.records.map { (it as RuntimeQueuedRecord.Event).record.occurredAt })

        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendEvents(
                RuntimeEventSessionUpdate.Replace(session(lastActivityAt = LATER)),
                listOf(eventAt("later-first", LATER), eventAt("backward", NOW)),
            ).await()
        }
        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendEvents(
                RuntimeEventSessionUpdate.Preserve,
                listOf(eventAt("past-persisted-session", EVEN_LATER)),
            ).await()
        }
        assertEquals(2, owner.snapshot().await().queuedCount)
    }

    @Test
    fun `event causality lower bound survives across committed transactions`() {
        val owner = open(FakeRuntimeQueueBacking())
        val committed =
            owner.appendEvents(
                RuntimeEventSessionUpdate.Replace(session(lastActivityAt = LATER)),
                listOf(eventAt("later", LATER)),
            ).await() as RuntimeAppendResult.Accepted
        val before = committed.snapshot

        assertFutureCause(IllegalArgumentException::class.java) {
            owner.appendEvents(
                RuntimeEventSessionUpdate.Preserve,
                listOf(eventAt("backward", NOW)),
            ).await()
        }

        val after = owner.snapshot().await()
        assertEquals(before, after)
        assertEquals(1L, after.state.stream.nextSequence)
        assertEquals(1, after.queuedCount)
        assertEquals("later", (owner.peek(1, MAX_RUNTIME_DELIVERY_BYTES).await().single() as RuntimeQueuedRecord.Event).record.name)
    }

    @Test
    fun `ordinary operations use targeted reads and only reopen streams all rows`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        backing.scanCalls = 0

        appendEvents(owner, event("a"), event("b"))
        val queued = owner.peek(2, MAX_RUNTIME_DELIVERY_BYTES).await()
        owner.acknowledge(
            acknowledgement(queued.map { RuntimeRecordReference(it.sequence, it.kind, it.recordId) }),
        ).await()

        assertEquals(0, backing.scanCalls)
        owner.closeAsync().await()
        owners.remove(owner)
        open(backing, legacyStateLoader = { error("legacy must not be read") })
        assertEquals(1, backing.scanCalls)
    }

    @Test
    fun `stale acknowledgement must still carry deterministic stream proof`() {
        val owner = open(FakeRuntimeQueueBacking())
        appendEvents(owner, event("a"))
        val queued = owner.peek(1, MAX_RUNTIME_DELIVERY_BYTES).await().single()
        val reference = RuntimeRecordReference(queued.sequence, queued.kind, queued.recordId)
        owner.acknowledge(acknowledgement(listOf(reference))).await()

        assertThrows(IllegalArgumentException::class.java) {
            owner.acknowledge(
                acknowledgement(listOf(reference.copy(recordId = "event_arbitrary"))),
            )
        }
        val otherStreamReference =
            reference.copy(
                recordId = RuntimeRecordIdentity.recordId("stream_other", reference.sequence, reference.kind),
            )
        assertFutureCause(RuntimeAcknowledgementMismatchException::class.java) {
            owner.acknowledge(RuntimeAcknowledgement("stream_other", listOf(otherStreamReference))).await()
        }
    }

    private fun open(
        backing: FakeRuntimeQueueBacking,
        limits: RuntimeQueueLimits = RuntimeQueueLimits(10_000, 16_777_216),
        identifiers: CountingIdentifiers = CountingIdentifiers(),
        legacyStateLoader: () -> PersistedCoreState = ::freshState,
    ): RuntimeQueueOwner {
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = nextKey(),
                limits = limits,
                databaseFactory = backing::connection,
                legacyStateLoader = legacyStateLoader,
                identifiers = identifiers,
            ).await()
        owners += owner
        return owner
    }

    private fun nextKey(): String = "runtime-owner-${keyCounter.incrementAndGet()}"

    private fun appendEvents(
        owner: RuntimeQueueOwner,
        vararg events: RuntimeRecordDraft.Event,
    ): RuntimeAppendResult =
        owner.appendEvents(RuntimeEventSessionUpdate.Replace(session()), events.toList()).await()

    private fun acknowledgement(references: List<RuntimeRecordReference>): RuntimeAcknowledgement =
        RuntimeAcknowledgement(STREAM_ID, references)

    private fun event(name: String): RuntimeRecordDraft.Event =
        eventAt(name, NOW)

    private fun eventAt(
        name: String,
        occurredAt: String,
    ): RuntimeRecordDraft.Event =
        RuntimeRecordDraft.Event(
            kind = RuntimeEventKind.CAPTURE,
            name = name,
            occurredAt = occurredAt,
            expectedSessionId = "session_test",
            properties = mapOf("value" to name),
            versions = versions(),
        )

    private fun mutation(change: RuntimeMutationChange): RuntimeRecordDraft.Mutation =
        RuntimeRecordDraft.Mutation(NOW, change, versions())

    private fun mutationAt(
        occurredAt: String,
        change: RuntimeMutationChange,
    ): RuntimeRecordDraft.Mutation = RuntimeRecordDraft.Mutation(occurredAt, change, versions())

    private fun session(lastActivityAt: String = NOW): SessionState =
        SessionState(
            id = "session_test",
            startedAt = NOW,
            lastActivityAt = lastActivityAt,
            timeoutSeconds = 1_800,
            lifecycle = SessionLifecycle.ACTIVE,
            backgroundedAt = null,
        )

    private fun versions(): RuntimeVersions =
        RuntimeVersions(
            platform = RuntimePlatform.ANDROID,
            runtime = RuntimeVersionComponent("elu-android", "0.1.0"),
            facade = RuntimeVersionComponent("Elu", "0.1.0"),
            build = "test",
        )

    private fun freshState(): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 0,
                    contextRevision = 0,
                    anonymousId = "anon_test",
                    userId = null,
                    groups = emptyMap(),
                    superProperties = emptyMap(),
                    session = null,
                    optedOut = false,
                    updatedAt = NOW,
                ),
            stream = StreamState(streamId = STREAM_ID, nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )

    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)

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
        private var next = 0
        var recordCalls: Int = 0
            private set

        override fun next(prefix: String): String {
            if (prefix == "event_" || prefix == "mutation_") recordCalls += 1
            return prefix + (next++).toString().padStart(8, '0')
        }
    }

    private companion object {
        const val EARLIER = "2026-08-04T23:59:59.000Z"
        const val NOW = "2026-08-05T00:00:00.000Z"
        const val LATER = "2026-08-05T00:00:01.000Z"
        const val EVEN_LATER = "2026-08-05T00:00:02.000Z"
        const val STREAM_ID = "stream_test"
    }
}
