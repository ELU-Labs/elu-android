package dev.elu.analytics.internal.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityStateCoreTest {
    @Test
    fun `fresh state is durable schema v1 with separate stream metadata`() {
        val store = MemoryStore()
        val core = newCore(store)
        val state = core.snapshot()

        assertEquals(1, state.identity.schemaVersion)
        assertEquals(0, state.identity.revision)
        assertEquals(0, state.identity.contextRevision)
        assertTrue(state.identity.anonymousId.startsWith("anon_"))
        assertNull(state.identity.userId)
        assertFalse(state.identity.optedOut)
        assertNull(state.identity.session)
        assertEquals(0, state.stream.nextSequence)
        assertTrue(state.stream.streamId.startsWith("stream_"))

        val persisted = JSONObject(String(store.bytes!!, Charsets.UTF_8))
        val identity = persisted.getJSONObject("identity")
        assertFalse(identity.has("streamId"))
        assertFalse(identity.has("nextSequence"))
        assertEquals(state, CoreStateCodec.decode(store.bytes!!))
    }

    @Test
    fun `identify preserves anonymous id and always advances context but only changes identity revision once`() {
        val core = newCore()
        val anonymousId = core.snapshot().identity.anonymousId

        val first = core.identify("user-123")
        val repeated = core.identify("user-123")

        assertEquals(anonymousId, first.anonymousId)
        assertEquals(anonymousId, repeated.anonymousId)
        assertEquals(1, first.revision)
        assertEquals(1, repeated.revision)
        assertEquals(1, first.contextRevision)
        assertEquals(2, repeated.contextRevision)
    }

    @Test
    fun `identity revisions and stream metadata survive a new core instance`() {
        val store = MemoryStore()
        val first = newCore(store)
        first.identify("user-123")
        val beforeRestart = first.snapshot().let { state ->
            state.copy(stream = state.stream.copy(nextSequence = 7))
        }
        store.write(CoreStateCodec.encode(beforeRestart))

        val restored = newCore(store)

        assertEquals(beforeRestart, restored.snapshot())
    }

    @Test
    fun `reset clears customer and flag context but preserves opt state and ordering stream`() {
        val store = MemoryStore()
        val initial = newCore(store).snapshot()
        store.write(CoreStateCodec.encode(initial.copy(stream = initial.stream.copy(nextSequence = 2))))
        val core = newCore(store)
        core.setOptedOut(true)
        core.identify("user-123")
        core.group("organization", "org-123")
        core.registerSuperProperties(mapOf("plan" to "growth"))
        core.setPersonPropertiesForFlags(mapOf("role" to "owner"))
        core.setGroupPropertiesForFlags("organization", mapOf("tier" to "partner"))
        core.setSession(validSession())
        val before = core.snapshot()

        val reset = core.reset()
        val after = core.snapshot()

        assertNotEquals(before.identity.anonymousId, reset.anonymousId)
        assertNull(reset.userId)
        assertTrue(reset.groups.isEmpty())
        assertTrue(reset.superProperties.isEmpty())
        assertNull(reset.session)
        assertTrue(reset.optedOut)
        assertEquals(before.identity.revision + 1, reset.revision)
        assertEquals(before.identity.contextRevision + 1, reset.contextRevision)
        assertTrue(after.flagContext.personProperties.isEmpty())
        assertTrue(after.flagContext.groupProperties.isEmpty())
        assertEquals(before.stream.streamId, after.stream.streamId)
        assertEquals(before.stream.nextSequence, after.stream.nextSequence)
    }

    @Test
    fun `group and property operations advance context and resetGroups clears group flag context`() {
        val core = newCore()

        core.group("organization", "org-1")
        core.registerSuperProperties(mapOf("plan" to "growth"))
        core.unregisterSuperProperty("missing")
        core.setPersonPropertiesForFlags(mapOf("role" to "owner"))
        core.setGroupPropertiesForFlags("organization", mapOf("tier" to "partner"))
        val resetGroups = core.resetGroups()

        assertEquals(6, resetGroups.contextRevision)
        assertTrue(resetGroups.groups.isEmpty())
        assertTrue(core.snapshot().flagContext.groupProperties.isEmpty())
        assertEquals(mapOf("role" to "owner"), core.snapshot().flagContext.personProperties)
    }

    @Test
    fun `first group association drops properties set before that group was associated`() {
        val core = newCore()
        core.setGroupPropertiesForFlags("organization", mapOf("stale" to true))

        core.group("organization", "org-1")

        assertFalse(core.snapshot().flagContext.groupProperties.containsKey("organization"))
        assertEquals(2, core.snapshot().identity.contextRevision)
    }

    @Test
    fun `identity alias group and property mutations do not create or rotate a session`() {
        val core = newCore()
        val session = validSession()
        core.setSession(session)
        val revision = core.snapshot().identity.revision

        core.identify("user-123")
        core.alias("legacy-user")
        core.group("organization", "org-1")
        core.registerSuperProperties(mapOf("plan" to "growth"))
        core.setPersonPropertiesForFlags(mapOf("role" to "owner"))

        assertEquals(session, core.snapshot().identity.session)
        assertEquals(revision + 1, core.snapshot().identity.revision)
    }

    @Test
    fun `concurrent mutations remain complete and durably ordered`() {
        val workers = 8
        val operationsPerWorker = 100
        val total = workers * operationsPerWorker
        val store = MemoryStore()
        val core = newCore(store)
        val pool = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)

        repeat(workers) { worker ->
            pool.execute {
                start.await()
                repeat(operationsPerWorker) { offset ->
                    core.registerSuperProperties(mapOf("$worker-$offset" to offset))
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(total.toLong(), core.snapshot().identity.contextRevision)
        assertEquals(total, core.snapshot().identity.superProperties.size)
        val durableRevisions =
            store.persistedWrites().map { bytes ->
                CoreStateCodec.decode(bytes).identity.contextRevision
            }
        assertEquals((0..total).map(Int::toLong), durableRevisions)
    }

    @Test
    fun `corrupt state rotates identity fail closed and keeps independently valid stream metadata`() {
        val originalStore = MemoryStore()
        newCore(originalStore)
        val valid = JSONObject(String(originalStore.bytes ?: error("missing bytes"), Charsets.UTF_8))
        val streamId = valid.getJSONObject("stream").getString("streamId")
        valid.getJSONObject("stream").put("nextSequence", 4)
        valid.getJSONObject("identity").remove("anonymousId")
        val store = MemoryStore(valid.toString().toByteArray())

        val recovered = newCore(store).snapshot()

        assertTrue(recovered.identity.optedOut)
        assertEquals(streamId, recovered.stream.streamId)
        assertEquals(4, recovered.stream.nextSequence)
        assertTrue(recovered.identity.anonymousId.startsWith("anon_"))
        assertEquals(recovered, CoreStateCodec.decode(store.bytes!!))
    }

    @Test
    fun `unparseable state recovers fail closed instead of assuming opt in`() {
        val store = MemoryStore("{not-json".toByteArray())

        val recovered = newCore(store).snapshot()

        assertTrue(recovered.identity.optedOut)
        assertEquals(0, recovered.stream.nextSequence)
        assertEquals(recovered, CoreStateCodec.decode(store.bytes!!))
    }

    @Test
    fun `malformed UTF-8 state recovers fail closed`() {
        val store = MemoryStore(byteArrayOf(0xc3.toByte(), 0x28))

        val recovered = newCore(store).snapshot()

        assertTrue(recovered.identity.optedOut)
        assertEquals(recovered, CoreStateCodec.decode(store.bytes!!))
    }

    @Test
    fun `unsupported schema is rejected without overwriting persisted bytes`() {
        val bytes =
            """{"schemaVersion":2,"identity":{},"stream":{},"flagContext":{}}"""
                .toByteArray()
        val store = MemoryStore(bytes.copyOf())

        assertThrows(UnsupportedCoreSchemaException::class.java) { newCore(store) }
        assertTrue(bytes.contentEquals(store.bytes))
    }

    @Test
    fun `failed persistence does not expose an uncommitted mutation`() {
        val store = MemoryStore()
        val core = newCore(store)
        val before = core.snapshot()
        store.failWrites = true

        assertThrows(java.io.IOException::class.java) { core.identify("user-123") }
        assertEquals(before, core.snapshot())
    }

    private fun newCore(store: MemoryStore = MemoryStore()): IdentityStateCore =
        IdentityStateCore(
            store = store,
            identifiers = CountingIdentifiers(),
            timestamps = CoreTimestampProvider { "2026-08-04T00:00:00.000Z" },
        )

    private fun validSession(): SessionState =
        SessionState(
            id = "session-1",
            startedAt = "2026-08-04T00:00:00.000Z",
            lastActivityAt = "2026-08-04T00:01:00.000Z",
            timeoutSeconds = 1_800,
            lifecycle = SessionLifecycle.ACTIVE,
            backgroundedAt = null,
        )

    private class CountingIdentifiers : CoreIdentifierGenerator {
        private val counter = AtomicLong()

        override fun next(prefix: String): String = "$prefix${counter.incrementAndGet()}"
    }

    private class MemoryStore(initial: ByteArray? = null) : CoreStateStore {
        @Volatile var bytes: ByteArray? = initial?.copyOf()
        @Volatile var failWrites: Boolean = false
        private val writes = mutableListOf<ByteArray>()

        @Synchronized
        override fun read(): ByteArray? = bytes?.copyOf()

        @Synchronized
        override fun write(bytes: ByteArray) {
            if (failWrites) throw java.io.IOException("injected failure")
            this.bytes = bytes.copyOf()
            writes += bytes.copyOf()
        }

        @Synchronized
        fun persistedWrites(): List<ByteArray> = writes.map { it.copyOf() }
    }
}
