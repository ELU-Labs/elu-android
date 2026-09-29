package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.internal.core.*
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Production metadata selection; frozen raw wire vectors intentionally remain a separate seam. */
class RuntimePersonStateTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private var nextId = 0
    private val identifiers = CoreIdentifierGenerator { prefix -> prefix + (++nextId) }
    private fun <T> Future<T>.await(): T = get(5, TimeUnit.SECONDS)
    @After fun close() { owners.asReversed().forEach { runCatching { it.closeAsync().await() } } }

    private fun open(backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(),
        mode: EluPersonProfilesMode? = EluPersonProfilesMode.IDENTIFIED_ONLY,
        limits: RuntimeQueueLimits = RuntimeQueueLimits(100, 1_000_000),
        idGenerator: CoreIdentifierGenerator = identifiers,
    ): RuntimeQueueOwner = RuntimeQueueOwner.open("person-${java.util.UUID.randomUUID()}", limits,
        backing::connection, { fresh() }, identifiers = idGenerator, personProfiles = mode).await().also { owners += it }

    private fun fresh() = PersistedCoreState(
        identity = IdentityState(revision = 0, contextRevision = 0, anonymousId = "anon_original", userId = null,
            groups = emptyMap(), superProperties = emptyMap(), session = null, optedOut = false, updatedAt = NOW),
        stream = StreamState(streamId = "stream_person", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
    private fun event(owner: RuntimeQueueOwner, name: String = "ordinary", diagnostic: Boolean = false): RuntimeAppendResult =
        owner.appendEvents(RuntimeEventSessionUpdate.Replace(owner.snapshot().await().state.identity.session?.id,
            SessionState("session_person", NOW, NOW, 1800, lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null)), listOf(
            RuntimeRecordDraft.Event(RuntimeEventKind.CAPTURE, name, NOW, "session_person",
                mapOf("\$device_id" to "forged", "\$is_identified" to "forged", "\$process_person_profile" to "forged", "\$epp" to true),
                StandaloneRuntime.defaultVersions(), nativeDiagnostic = diagnostic))).await()
    private fun properties(result: RuntimeAppendResult) = ((result as RuntimeAppendResult.Accepted).records.single() as RuntimeQueuedRecord.Event).record.properties
    private fun mutate(owner: RuntimeQueueOwner, change: RuntimeMutationChange) = owner.appendMutations(listOf(
        RuntimeRecordDraft.Mutation(NOW, change, StandaloneRuntime.defaultVersions()))).await()
    private fun person(owner: RuntimeQueueOwner) = checkNotNull(owner.snapshot().await().person)

    @Test fun `default mode stamps owned identity and never trusts customer identity properties`() {
        val owner = open()
        val props = properties(event(owner))
        assertEquals("anon_original", props["\$device_id"])
        assertEquals(false, props["\$is_identified"])
        assertEquals(false, props["\$process_person_profile"])
        assertFalse(props.containsKey("\$epp"))
        assertFalse(person(owner).processingEnabled)
    }

    @Test fun `person mutations promote but flags-only context does not`() {
        for (change in listOf(
            RuntimeMutationChange.Identify("user", emptyMap(), emptyMap()),
            RuntimeMutationChange.LinkAlias("alias", "anon_original"),
            RuntimeMutationChange.SetPersonProperties(mapOf("plan" to "paid"), emptyMap(), emptyList()),
        )) {
            val owner = open()
            owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("plan" to "flags-only"), NOW)).await()
            assertFalse(person(owner).processingEnabled)
            assertEquals(false, properties(event(owner))["\$process_person_profile"])
            assertTrue(mutate(owner, change) is RuntimeAppendResult.Accepted)
            assertTrue(person(owner).processingEnabled)
            assertEquals(true, properties(event(owner))["\$process_person_profile"])
        }
    }

    @Test fun `group processing becomes sticky only when an event commits and survives reopen`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        mutate(owner, RuntimeMutationChange.AssociateGroup("company", "one"))
        assertFalse(person(owner).processingEnabled)
        owner.applyLocal(RuntimeLocalStateChange.ResetGroups(NOW)).await()
        assertEquals(false, properties(event(owner))["\$process_person_profile"])
        mutate(owner, RuntimeMutationChange.AssociateGroup("company", "two"))
        assertEquals(true, properties(event(owner))["\$process_person_profile"])
        owner.applyLocal(RuntimeLocalStateChange.ResetGroups(NOW)).await()
        owner.closeAsync().await()
        val reopened = open(backing)
        assertTrue(person(reopened).processingEnabled)
        assertEquals(true, properties(event(reopened))["\$process_person_profile"])
    }

    @Test fun `always event sticks across mode reopen and stamps every automatic category`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing, EluPersonProfilesMode.ALWAYS)
        for (name in listOf("\$network_request", "\$performance", "\$native_launch", "\$feature_flag_called", "\$exception")) {
            val props = properties(event(owner, name, diagnostic = name == "\$native_launch"))
            assertEquals(true, props["\$process_person_profile"])
            assertEquals(false, props["\$is_identified"])
            assertEquals("anon_original", props["\$device_id"])
        }
        owner.closeAsync().await()
        assertEquals(true, properties(event(open(backing)))["\$process_person_profile"])
    }

    @Test fun `never rejects whole person mutation batch without identity or flag changes`() {
        val owner = open(mode = EluPersonProfilesMode.NEVER)
        val before = owner.snapshot().await()
        for (change in listOf(RuntimeMutationChange.Identify("user", emptyMap(), emptyMap()),
            RuntimeMutationChange.LinkAlias("alias", "anon_original"),
            RuntimeMutationChange.SetPersonProperties(mapOf("plan" to "paid"), emptyMap(), emptyList()))) {
            assertTrue(mutate(owner, change) is RuntimeAppendResult.Rejected)
            assertEquals(before, owner.snapshot().await())
        }
        val rejectedBatch = owner.appendMutations(listOf(
            RuntimeRecordDraft.Mutation(NOW, RuntimeMutationChange.AssociateGroup("company", "one"), StandaloneRuntime.defaultVersions()),
            RuntimeRecordDraft.Mutation(NOW, RuntimeMutationChange.Identify("user", emptyMap(), emptyMap()), StandaloneRuntime.defaultVersions()),
        )).await()
        assertTrue(rejectedBatch is RuntimeAppendResult.Rejected)
        assertEquals(before, owner.snapshot().await())
        mutate(owner, RuntimeMutationChange.AssociateGroup("company", "one"))
        assertEquals(false, properties(event(owner))["\$process_person_profile"])
        assertFalse(person(owner).processingEnabled)
    }

    @Test fun `reset keeps device by default and explicit reset rotates device with anonymous`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        mutate(owner, RuntimeMutationChange.Identify("user", emptyMap(), emptyMap()))
        assertEquals(true, properties(event(owner))["\$is_identified"])
        owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, NOW)).await()
        owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW)).await()
        val first = owner.snapshot().await()
        assertTrue(first.state.identity.optedOut)
        assertNull(first.state.identity.userId)
        assertNotEquals("anon_original", first.state.identity.anonymousId)
        assertEquals("anon_original", person(owner).deviceId)
        assertFalse(person(owner).processingEnabled)
        owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW, true)).await()
        val rotated = owner.snapshot().await()
        assertEquals(rotated.state.identity.anonymousId, person(owner).deviceId)
        assertNotEquals("anon_original", person(owner).deviceId)
        owner.closeAsync().await()
        assertEquals(rotated, open(backing).snapshot().await())
    }

    @Test fun `explicit device reset rejects both current anonymous and preserved old device collisions`() {
        val backing = FakeRuntimeQueueBacking()
        val candidates = java.util.ArrayDeque(listOf("anon_next", "anon_next", "anon_original", "anon_rotated"))
        val attempted = mutableListOf<String>()
        val owner = open(backing, idGenerator = CoreIdentifierGenerator { prefix ->
            check(prefix == "anon_")
            candidates.removeFirst().also { attempted += it }
        })
        owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW)).await()
        assertEquals("anon_next", owner.snapshot().await().state.identity.anonymousId)
        assertEquals("anon_original", person(owner).deviceId)

        owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW, true)).await()
        assertEquals(listOf("anon_next", "anon_next", "anon_original", "anon_rotated"), attempted)
        assertTrue(candidates.isEmpty())
        assertEquals("anon_rotated", owner.snapshot().await().state.identity.anonymousId)
        assertEquals("anon_rotated", person(owner).deviceId)
        owner.closeAsync().await()
        val reopened = open(backing)
        assertEquals("anon_rotated", reopened.snapshot().await().state.identity.anonymousId)
        assertEquals("anon_rotated", person(reopened).deviceId)
    }

    @Test fun `quota and known rollback cannot publish sticky or rotated device`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing, EluPersonProfilesMode.ALWAYS, RuntimeQueueLimits(100, 1))
        val before = owner.snapshot().await()
        assertTrue(event(owner) is RuntimeAppendResult.Rejected)
        assertEquals(before, owner.snapshot().await())
        backing.failNextKnownCommit = IOException("injected")
        assertThrows(ExecutionException::class.java) { owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW, true)).await() }
        assertEquals(before, owner.snapshot().await())
        val mutationBacking = FakeRuntimeQueueBacking()
        val mutationOwner = open(mutationBacking)
        val mutationBefore = mutationOwner.snapshot().await()
        mutationBacking.failNextKnownCommit = IOException("injected")
        assertThrows(ExecutionException::class.java) {
            mutate(mutationOwner, RuntimeMutationChange.SetPersonProperties(mapOf("x" to 1), emptyMap(), emptyList()))
        }
        assertEquals(mutationBefore, mutationOwner.snapshot().await())
    }

    @Test fun `ambiguous committed event and reset reconcile exact person metadata`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing, EluPersonProfilesMode.ALWAYS)
        backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        assertEquals(true, properties(event(owner))["\$process_person_profile"])
        assertEquals(1, owner.snapshot().await().queuedCount)
        backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW, true)).await()
        assertEquals(owner.snapshot().await().state.identity.anonymousId, person(owner).deviceId)
        assertFalse(person(owner).processingEnabled)
    }

    @Test fun `ack and consent preserve device and sticky metadata across reopen`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing, EluPersonProfilesMode.ALWAYS)
        val accepted = event(owner) as RuntimeAppendResult.Accepted
        val metadata = person(owner)
        val record = accepted.records.single()
        owner.acknowledge(RuntimeAcknowledgement("stream_person", listOf(
            RuntimeRecordReference(record.sequence, record.kind, record.recordId)))).await()
        owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, NOW)).await()
        owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, NOW)).await()
        assertEquals(metadata, person(owner))
        owner.closeAsync().await()
        assertEquals(metadata, person(open(backing)))
    }

    @Test fun `raw conformance cannot reopen selected production metadata and corrupt binding refuses`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing); owner.closeAsync().await()
        assertThrows(ExecutionException::class.java) { open(backing, mode = null) }
        val valid = open(backing)
        val metadata = person(valid)
        valid.closeAsync().await()
        backing.core = backing.core!!.copy(person = metadata.copy(streamId = "foreign"))
        // The deliberately corrupted stream must be rejected even with an explicit selection.
        assertThrows(ExecutionException::class.java) { open(backing) }
    }

    @Test fun `metadata codec rejects malformed coerced extended and oversized values`() {
        val bytes = RuntimePersonState("stream", "device", true).encode()
        assertEquals(RuntimePersonState("stream", "device", true), RuntimePersonState.decode(bytes))
        for (text in listOf(String(bytes).replace("true", "\"true\""),
            String(bytes).dropLast(1) + ",\"extra\":1}", "{}", "x".repeat(4097))) {
            assertThrows(RuntimeQueueCorruptionException::class.java) { RuntimePersonState.decode(text.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { RuntimePersonState("stream", "\uD800") }
    }

    companion object { private const val NOW = "2026-08-05T00:00:00.000Z" }
}
