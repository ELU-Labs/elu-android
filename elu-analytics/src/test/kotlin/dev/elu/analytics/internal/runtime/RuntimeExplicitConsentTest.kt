package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.internal.core.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RuntimeExplicitConsentTest {
    private val at = "2026-09-29T00:00:00Z"
    private val owners = mutableListOf<RuntimeQueueOwner>()

    @After fun close() {
        owners.forEach { runCatching { it.closeAsync().await() } }
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test fun `consent encoding is closed bounded and contains no analytics fields`() {
        for (denied in listOf(false, true)) for (settled in listOf(false, true)) for (reconciled in listOf(false, true)) {
            val value = RuntimeExplicitConsent(denied, settled, reconciled)
            assertTrue(value.encode().size <= 128)
            assertEquals(value, RuntimeExplicitConsent.decode(value.encode()))
            assertEquals(denied || !settled, value.deniesCollection)
        }
        for (invalid in listOf("{}", " ", "x".repeat(129),
            String(RuntimeExplicitConsent.PENDING_DENIAL.encode()) + " ",
            String(RuntimeExplicitConsent.PENDING_DENIAL.encode()).replace("true", "1"),
            String(RuntimeExplicitConsent.PENDING_DENIAL.encode()).replace("\"schemaVersion\":1", "\"schemaVersion\":2"))) {
            assertThrows(Exception::class.java) { RuntimeExplicitConsent.decode(invalid.toByteArray()) }
        }
    }

    @Test fun `fresh memory namespace keeps initial default without inventing explicit consent`() {
        val store = Store()
        val owner = open(store, memory = true)
        assertFalse(owner.snapshot().await().state.identity.optedOut)
        assertNull(store.record)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun `old analytics presence without explicit choice denies memory without importing identity`() {
        val store = Store(prior = true)
        val fresh = state()
        val owner = open(store, memory = true, initial = fresh)
        assertEquals(RuntimeExplicitConsent.PENDING_DENIAL, store.record)
        assertTrue(owner.snapshot().await().state.identity.optedOut)
        assertEquals(fresh.identity.anonymousId, owner.snapshot().await().state.identity.anonymousId)
        assertNull(owner.snapshot().await().state.identity.session)
    }

    @Test fun `settled explicit grant restores memory but pending grant never does`() {
        for (settled in listOf(false, true)) {
            val store = Store(record = RuntimeExplicitConsent(false, settled, true))
            val owner = open(store, memory = true)
            assertEquals(!settled, owner.snapshot().await().state.identity.optedOut)
            assertEquals(false, store.record!!.persistentReconciled)
        }
    }

    @Test fun `memory optout optin forces old persistent privacy barrier even when final bit matches`() {
        val old = state().let { it.copy(identity = it.identity.copy(session = SessionState(
            id = "old_session", startedAt = at, lastActivityAt = at, timeoutSeconds = 1800,
            lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null))) }
        val backing = FakeRuntimeQueueBacking()
        val store = Store()
        val key = UUID.randomUUID().toString()
        val persistent = open(store, memory = false, backing = backing, initial = old, key = key)
        val original = persistent.snapshot().await()
        persistent.closeAsync().await(); owners.remove(persistent)
        store.prior = true
        val memory = open(store, memory = true, key = key)
        memory.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, at)).await()
        memory.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, at)).await()
        assertEquals(RuntimeExplicitConsent(false, true, false), store.record)
        memory.closeAsync().await(); owners.remove(memory)
        val restored = open(store, memory = false, backing = backing, key = key)
        val after = restored.snapshot().await()
        assertFalse(after.state.identity.optedOut)
        assertNull(after.state.identity.session)
        assertEquals(original.state.identity.anonymousId, after.state.identity.anonymousId)
        assertEquals(original.person, after.person)
        assertTrue(after.state.identity.contextRevision > original.state.identity.contextRevision)
        assertEquals(RuntimeExplicitConsent(false, true, true), store.record)
    }

    @Test fun `repeated explicit choice writes pending then settled through real queue transaction`() {
        val store = Store()
        val owner = open(store, memory = false)
        val before = owner.snapshot().await()
        owner.applyConsent(RuntimeLocalStateChange.SetOptedOut(false, at)).await()
        assertEquals(listOf(RuntimeExplicitConsent(false, false, false), RuntimeExplicitConsent(false, true, true)), store.writes)
        assertEquals(before, owner.snapshot().await())
    }

    @Test fun `same choice on raw facade owner preserves identity without requiring a sidecar`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = RuntimeQueueOwner.open(UUID.randomUUID().toString(), RuntimeQueueLimits(100, 1_000_000),
            databaseFactory = backing::connection, legacyStateLoader = { state() }).await().also { owners += it }
        val before = owner.snapshot().await()
        owner.applyConsent(RuntimeLocalStateChange.SetOptedOut(false, at)).await()
        assertEquals(before, owner.snapshot().await())
    }

    @Test fun `stale reconciled grant cannot override a separately committed persistent denial`() {
        val backing = FakeRuntimeQueueBacking()
        val old = open(Store(), memory = false, backing = backing)
        old.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, at)).await()
        old.closeAsync().await(); owners.remove(old)
        val stale = Store(record = RuntimeExplicitConsent(false, true, true))
        val reopened = open(stale, memory = false, backing = backing)
        assertTrue(reopened.snapshot().await().state.identity.optedOut)
        assertEquals(RuntimeExplicitConsent(true, false, true), stale.record)
    }

    @Test fun `pending sidecar failure cannot prevent changed durable optout`() {
        val store = Store()
        val backing = FakeRuntimeQueueBacking()
        val key = UUID.randomUUID().toString()
        val owner = open(store, memory = false, backing = backing, key = key)
        store.failNextWrite = true
        assertThrows(Exception::class.java) { owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, at)).await() }
        assertTrue(CoreStateCodec.decode(backing.core!!.stateJson).identity.optedOut)
        assertThrows(Exception::class.java) { owner.closeAsync().await() }
        assertThrows(Exception::class.java) { open(store, memory = true, key = key) }
    }

    @Test fun `pending grant write failure cannot alter denied core or release original owner`() {
        val store = Store(record = RuntimeExplicitConsent(true, true, true))
        val backing = FakeRuntimeQueueBacking()
        val owner = open(store, memory = true, backing = backing)
        store.failNextWrite = true
        assertThrows(Exception::class.java) { owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, at)).await() }
        assertTrue(CoreStateCodec.decode(backing.core!!.stateJson).identity.optedOut)
        assertEquals(RuntimeExplicitConsent(true, true, false), store.record)
        assertThrows(Exception::class.java) { owner.closeAsync().await() }
    }

    @Test fun `memory ambiguous commit reconciles exact original connection without factory reopening`() {
        val store = Store()
        val backing = FakeRuntimeQueueBacking()
        val owner = open(store, memory = true, backing = backing)
        backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        owner.applyLocal(RuntimeLocalStateChange.RegisterSuperProperties(mapOf("kept" to true), at)).await()
        assertEquals(1, backing.connectionCalls)
        assertEquals(true, owner.snapshot().await().state.identity.superProperties["kept"])
        owner.closeAsync().await(); owners.remove(owner)
    }

    @Test fun `unsettled memory transaction retains original namespace and never creates replacement database`() {
        val store = Store()
        val backing = FakeRuntimeQueueBacking()
        val key = UUID.randomUUID().toString()
        val owner = open(store, memory = true, backing = backing, key = key, validateMemory = { error("transaction still active") })
        backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        assertThrows(Exception::class.java) { owner.applyLocal(RuntimeLocalStateChange.RegisterSuperProperties(mapOf("kept" to true), at)).await() }
        assertEquals(1, backing.connectionCalls)
        assertThrows(Exception::class.java) { owner.closeAsync().await() }
        assertThrows(Exception::class.java) { open(store, memory = true, key = key) }
        assertEquals(1, backing.connectionCalls)
    }

    private class Store(var prior: Boolean = false, var record: RuntimeExplicitConsent? = null) : RuntimeExplicitConsentStore {
        val writes = mutableListOf<RuntimeExplicitConsent>()
        var failNextWrite = false
        override fun read() = record
        override fun priorAnalyticsPresent() = prior
        override fun write(record: RuntimeExplicitConsent) {
            if (failNextWrite) { failNextWrite = false; throw IOException("injected consent write failure") }
            writes += record; this.record = record
        }
    }

    private fun open(store: Store, memory: Boolean, backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(),
        initial: PersistedCoreState = state(), key: String = UUID.randomUUID().toString(), validateMemory: () -> Unit = {}): RuntimeQueueOwner =
        RuntimeQueueOwner.open(key, RuntimeQueueLimits(100, 1_000_000), databaseFactory = {
            val original = backing.connection()
            object : RuntimeQueueDatabase by original { override fun validateMemoryReconciliation() = validateMemory() }
        }, legacyStateLoader = { initial }, memoryOnly = memory, explicitConsentStore = store,
            personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY).await().also { owners += it }

    private fun state() = PersistedCoreState(identity = IdentityState(revision = 0, contextRevision = 0,
        anonymousId = "anon_${UUID.randomUUID()}", userId = null, groups = emptyMap(), superProperties = emptyMap(),
        session = null, optedOut = false, updatedAt = at), stream = StreamState("stream_${UUID.randomUUID()}", 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))

    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)
}
