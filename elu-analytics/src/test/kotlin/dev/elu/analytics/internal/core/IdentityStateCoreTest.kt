package dev.elu.analytics.internal.core

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityStateCoreTest {
    @After
    fun clearProductionSingletons() {
        IdentityStateCore.clearProductionSingletonsForTesting()
    }

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
        core.installSessionForTestingOrMigration(validSession())
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
        core.installSessionForTestingOrMigration(session)
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
    fun `eligible activity resumes before idle boundary and rotates exactly at it`() {
        val clock = MutableClock(FIXED_NOW_MILLIS)
        val core = newCore(clock = clock)
        val first = core.recordEligibleActivity()

        clock.nowEpochMillis = FIXED_NOW_MILLIS + DEFAULT_SESSION_TIMEOUT_SECONDS * 1_000L - 1L
        val resumed = core.recordEligibleActivity()

        assertEquals(first.id, resumed.id)
        assertEquals("2026-08-04T00:29:59.999Z", resumed.lastActivityAt)

        clock.nowEpochMillis += DEFAULT_SESSION_TIMEOUT_SECONDS * 1_000L
        val rotated = core.recordEligibleActivity()

        assertNotEquals(first.id, rotated.id)
        assertEquals(rotated.startedAt, rotated.lastActivityAt)
    }

    @Test
    fun `eligible activity applies a tightened timeout immediately`() {
        val clock = MutableClock(FIXED_NOW_MILLIS)
        val core = newCore(clock = clock)
        val first = core.recordEligibleActivity(DEFAULT_SESSION_TIMEOUT_SECONDS)

        clock.nowEpochMillis += MIN_SESSION_TIMEOUT_SECONDS * 1_000L
        val rotated = core.recordEligibleActivity(MIN_SESSION_TIMEOUT_SECONDS)

        assertNotEquals(first.id, rotated.id)
        assertEquals(MIN_SESSION_TIMEOUT_SECONDS, rotated.timeoutSeconds)
    }

    @Test
    fun `eligible activity does not revive an expired session when timeout is relaxed`() {
        val clock = MutableClock(FIXED_NOW_MILLIS)
        val core = newCore(clock = clock)
        val first = core.recordEligibleActivity(MIN_SESSION_TIMEOUT_SECONDS)

        clock.nowEpochMillis += MIN_SESSION_TIMEOUT_SECONDS * 1_000L
        val rotated = core.recordEligibleActivity(MAX_SESSION_TIMEOUT_SECONDS)

        assertNotEquals(first.id, rotated.id)
        assertEquals(MAX_SESSION_TIMEOUT_SECONDS, rotated.timeoutSeconds)
    }

    @Test
    fun `eligible activity rotates when the wall clock moves backward`() {
        val clock = MutableClock(FIXED_NOW_MILLIS)
        val core = newCore(clock = clock)
        val first = core.recordEligibleActivity()

        clock.nowEpochMillis -= 1L
        val rotated = core.recordEligibleActivity()

        assertNotEquals(first.id, rotated.id)
        assertEquals("2026-08-03T23:59:59.999Z", rotated.startedAt)
    }

    @Test
    fun `eligible activity clamps timeout and rotates at maximum duration boundary`() {
        val clock = MutableClock(FIXED_NOW_MILLIS)
        val core = newCore(clock = clock)
        assertEquals(MIN_SESSION_TIMEOUT_SECONDS, core.recordEligibleActivity(1).timeoutSeconds)

        val justBeforeMaximum = FIXED_NOW_MILLIS + SESSION_MAXIMUM_DURATION_SECONDS * 1_000L - 1L
        core.installSessionForTestingOrMigration(
            validSession(
                id = "session-before-maximum",
                startedAt = "2026-08-04T00:00:00.000Z",
                lastActivityAt = "2026-08-04T23:59:59.999Z",
                timeoutSeconds = MAX_SESSION_TIMEOUT_SECONDS,
            ),
        )
        clock.nowEpochMillis = justBeforeMaximum
        val resumed = core.recordEligibleActivity(Int.MAX_VALUE)
        assertEquals("session-before-maximum", resumed.id)
        assertEquals(MAX_SESSION_TIMEOUT_SECONDS, resumed.timeoutSeconds)

        clock.nowEpochMillis = FIXED_NOW_MILLIS + SESSION_MAXIMUM_DURATION_SECONDS * 1_000L
        val rotated = core.recordEligibleActivity()
        assertNotEquals(resumed.id, rotated.id)
    }

    @Test
    fun `background and foreground update lifecycle without ending the session`() {
        val clock = MutableClock(FIXED_NOW_MILLIS)
        val core = newCore(clock = clock)
        assertNull(core.setSessionLifecycle(SessionLifecycle.BACKGROUND))
        val active = core.recordEligibleActivity()

        clock.nowEpochMillis += 1_000L
        val background = checkNotNull(core.setSessionLifecycle(SessionLifecycle.BACKGROUND))
        assertEquals(active.id, background.id)
        assertEquals(SessionLifecycle.BACKGROUND, background.lifecycle)
        assertEquals("2026-08-04T00:00:01.000Z", background.backgroundedAt)

        clock.nowEpochMillis += 1_000L
        val foreground = checkNotNull(core.setSessionLifecycle(SessionLifecycle.ACTIVE))
        assertEquals(active.id, foreground.id)
        assertEquals(SessionLifecycle.ACTIVE, foreground.lifecycle)
        assertNull(foreground.backgroundedAt)
    }

    @Test
    fun `production singleton prevents stale aggregate writes for one canonical path`() {
        val store = MemoryStore()
        val first =
            IdentityStateCore.productionSingletonForTesting("/canonical/core-state.json") {
                newCore(store)
            }
        first.setOptedOut(true)
        val second =
            IdentityStateCore.productionSingletonForTesting("/canonical/core-state.json") {
                throw AssertionError("a second production core must not be constructed")
            }

        assertSame(first, second)
        second.identify("user-123")
        assertTrue(first.snapshot().identity.optedOut)
        assertTrue(CoreStateCodec.decode(store.bytes!!).identity.optedOut)
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
    fun `future nested schema with new fields is rejected without overwriting bytes`() {
        val originalStore = MemoryStore()
        newCore(originalStore)
        val root = JSONObject(String(originalStore.bytes!!, Charsets.UTF_8))
        root.getJSONObject("identity")
            .put("schemaVersion", 2)
            .put("futureIdentityField", true)
        val bytes = root.toString().toByteArray()
        val store = MemoryStore(bytes)

        assertThrows(UnsupportedCoreSchemaException::class.java) { newCore(store) }
        assertTrue(bytes.contentEquals(store.bytes))
    }

    @Test
    fun `unknown same-major extension is rejected without overwriting bytes`() {
        val originalStore = MemoryStore()
        newCore(originalStore)
        val root = JSONObject(String(originalStore.bytes!!, Charsets.UTF_8))
        root.getJSONObject("identity").put("futureConsentState", true)
        val bytes = root.toString().toByteArray()
        val store = MemoryStore(bytes)

        assertThrows(UnsupportedCoreSchemaExtensionException::class.java) { newCore(store) }
        assertTrue(bytes.contentEquals(store.bytes))
    }

    @Test
    fun `damaged aggregate marker repairs from independently valid children`() {
        val originalStore = MemoryStore()
        val expected = newCore(originalStore).identify("user-123")
        val root = JSONObject(String(originalStore.bytes!!, Charsets.UTF_8)).apply { remove("schemaVersion") }
        val store = MemoryStore(root.toString().toByteArray())

        val recovered = newCore(store).snapshot()

        assertEquals(expected, recovered.identity)
        assertEquals(1, JSONObject(String(store.bytes!!, Charsets.UTF_8)).getInt("schemaVersion"))
    }

    @Test
    fun `damaged aggregate marker with unsupported child preserves store bytes`() {
        val originalStore = MemoryStore()
        newCore(originalStore)
        val root =
            JSONObject(String(originalStore.bytes!!, Charsets.UTF_8)).apply {
                remove("schemaVersion")
                getJSONObject("stream").put("schemaVersion", 2)
            }
        val bytes = root.toString().toByteArray()
        val store = MemoryStore(bytes)

        assertThrows(UnsupportedCoreSchemaException::class.java) { newCore(store) }
        assertTrue(bytes.contentEquals(store.bytes))
    }

    @Test
    fun `damaged aggregate marker with child extension preserves store bytes`() {
        val originalStore = MemoryStore()
        newCore(originalStore)
        val root =
            JSONObject(String(originalStore.bytes!!, Charsets.UTF_8)).apply {
                put("schemaVersion", "one")
                getJSONObject("flagContext").put("futureFlagState", true)
            }
        val bytes = root.toString().toByteArray()
        val store = MemoryStore(bytes)

        assertThrows(UnsupportedCoreSchemaExtensionException::class.java) { newCore(store) }
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

    private fun newCore(
        store: MemoryStore = MemoryStore(),
        clock: CoreEpochClock = MutableClock(FIXED_NOW_MILLIS),
    ): IdentityStateCore =
        IdentityStateCore.forTesting(
            store = store,
            identifiers = CountingIdentifiers(),
            clock = clock,
        )

    private fun validSession(
        id: String = "session-1",
        startedAt: String = "2026-08-04T00:00:00.000Z",
        lastActivityAt: String = "2026-08-04T00:01:00.000Z",
        timeoutSeconds: Int = 1_800,
    ): SessionState =
        SessionState(
            id = id,
            startedAt = startedAt,
            lastActivityAt = lastActivityAt,
            timeoutSeconds = timeoutSeconds,
            lifecycle = SessionLifecycle.ACTIVE,
            backgroundedAt = null,
        )

    private class MutableClock(@Volatile var nowEpochMillis: Long) : CoreEpochClock {
        override fun nowEpochMillis(): Long = nowEpochMillis
    }

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
        override fun write(bytes: ByteArray): CoreStateWriteOutcome {
            if (failWrites) throw java.io.IOException("injected failure")
            this.bytes = bytes.copyOf()
            writes += bytes.copyOf()
            return CoreStateWriteOutcome.Durable
        }

        @Synchronized
        fun persistedWrites(): List<ByteArray> = writes.map { it.copyOf() }
    }

    private companion object {
        val FIXED_NOW_MILLIS: Long = Instant.parse("2026-08-04T00:00:00.000Z").toEpochMilli()
    }
}
