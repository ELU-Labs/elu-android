package dev.elu.analytics.internal.config

import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.facade.EluFacadeDiagnostics
import dev.elu.analytics.internal.facade.EluFacadeDisabledReason
import dev.elu.analytics.internal.facade.EluFacadeDropReason
import dev.elu.analytics.internal.facade.EluFacadeState
import dev.elu.analytics.internal.facade.StandaloneFacade
import dev.elu.analytics.internal.facade.StandaloneStack
import dev.elu.analytics.internal.runtime.FakeRuntimeQueueBacking
import dev.elu.analytics.internal.runtime.RuntimeCaptureAuthorityUpdateResult
import dev.elu.analytics.internal.runtime.RuntimeCaptureClock
import dev.elu.analytics.internal.runtime.RuntimeEventRecord
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeQueuedRecord
import dev.elu.analytics.internal.runtime.RuntimeWallTimestamps
import dev.elu.analytics.internal.runtime.StandaloneRuntime
import dev.elu.analytics.internal.runtime.delivery.BatchDeliveryClock
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPResponse
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPTransport
import dev.elu.analytics.internal.runtime.delivery.BatchWallInstant
import java.time.Instant
import java.util.ArrayDeque
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The initial configuration decision through the AndroidStandaloneStack composition: the driver
 * listener updates the gate synchronously, then the runtime and facade are notified.
 */
class V2ConfigInitialDecisionTest {
    @Test fun `offline cold start keeps calls held and stores them in call order after recovery`() = Rig().use { rig ->
        rig.offline = true
        val firstCall = rig.clock.wall
        rig.facade.identify("user-1", null)
        rig.facade.register(mapOf("tier" to "gold"))
        rig.facade.capture("launch-0", null, Date(firstCall))
        rig.clock.advance(1_000)
        val secondCall = rig.clock.wall
        rig.facade.screen("Home", null)
        rig.facade.capture("launch-1", null, Date(secondCall))
        rig.settle()

        rig.start() // the first fetch throws, as in airplane mode
        assertEquals(EluFacadeState.Pending, rig.facade.state())
        assertEquals(5, rig.diagnostics().buffered)
        assertEquals(emptyMap<EluFacadeDropReason, Int>(), rig.diagnostics().dropped)
        assertNull(rig.facade.distinctId())
        assertTrue(rig.records().isEmpty())
        assertTrue(rig.logs.isEmpty())

        rig.offline = false
        rig.clock.advance(2_000)
        rig.retry()
        assertEquals(EluFacadeState.Enabled, rig.facade.state())
        assertEquals(listOf("mutation:identify", "event:launch-0", "event:Home", "event:launch-1"), rig.queued())
        val events = rig.events()
        assertEquals(listOf(RuntimeWallTimestamps.rfc3339(firstCall), RuntimeWallTimestamps.rfc3339(secondCall),
            RuntimeWallTimestamps.rfc3339(secondCall)), events.map { it.occurredAt })
        events.forEach { event ->
            assertEquals("user-1", event.identity.userId)
            assertEquals("anon_initial", event.identity.anonymousId)
            assertEquals("gold", event.properties["tier"])
        }
        assertEquals("user-1", rig.facade.distinctId())
        assertEquals(0, rig.diagnostics().buffered)
        assertEquals(emptyMap<EluFacadeDropReason, Int>(), rig.diagnostics().dropped)
        assertEquals(listOf("First configuration decision: 5 held calls; dropped: none"), rig.logs)
    }

    @Test fun `a 503 first fetch keeps calls held until a retry succeeds`() = Rig().use { rig ->
        rig.httpStatus = 503
        rig.facade.register(mapOf("leg" to "restart"))
        rig.facade.capture("after-restart", null, Date(rig.clock.wall))
        rig.settle()
        rig.start()
        assertEquals(EluFacadeState.Pending, rig.facade.state())
        assertEquals(2, rig.diagnostics().buffered)

        rig.httpStatus = 200
        rig.clock.advance(1_000)
        rig.retry()
        assertEquals(EluFacadeState.Enabled, rig.facade.state())
        assertEquals(listOf("event:after-restart"), rig.queued())
        assertEquals("restart", rig.events().single().properties["leg"])
    }

    @Test fun `a disabled revoked or region-blocked first decision discards the held calls`() {
        for (mode in listOf("disabled", "revoked", "blocked")) {
            Rig(deviceInEu = mode == "blocked").use { rig ->
                if (mode != "blocked") rig.body = JSONObject().put("schemaVersion", 2).put("revision", mode)
                    .put("status", mode).put("reason", "test").put("issuedAt", "2026-08-05T00:00:30.000Z")
                    .put("expiresAt", "2026-08-05T00:05:00.000Z").toString()
                rig.facade.identify("user-1", null)
                rig.facade.capture("held", null, Date(rig.clock.wall))
                rig.settle()
                rig.start()
                val expected = if (mode == "blocked") EluFacadeDisabledReason.BLOCKED else EluFacadeDisabledReason.UNAUTHORIZED
                assertEquals(mode, EluFacadeState.Disabled(expected), rig.facade.state())
                assertTrue(mode, rig.records().isEmpty())
                assertEquals(mode, 0, rig.diagnostics().buffered)
                assertEquals(mode, mapOf(EluFacadeDropReason.UNAUTHORIZED to 2), rig.diagnostics().dropped)
                assertNull(mode, rig.owner.snapshot().get().state.identity.userId)
                assertEquals(mode, listOf("First configuration decision: 2 held calls; dropped: UNAUTHORIZED=2"), rig.logs)
            }
        }
    }

    @Test fun `opt-out while pending discards the calls held before it`() = Rig().use { rig ->
        rig.offline = true
        rig.facade.capture("before-opt-out", null, Date(rig.clock.wall))
        rig.facade.identify("user-1", null)
        rig.facade.optOut()
        rig.settle()
        assertEquals(0, rig.diagnostics().buffered)
        assertEquals(mapOf(EluFacadeDropReason.OPTED_OUT to 2), rig.diagnostics().dropped)
        assertEquals(listOf("Opt-out: 2 held calls; dropped: OPTED_OUT=2"), rig.logs)

        rig.start()
        assertEquals(EluFacadeState.Disabled(EluFacadeDisabledReason.OPTED_OUT), rig.facade.state())
        rig.offline = false
        rig.retry()
        assertEquals(EluFacadeState.Disabled(EluFacadeDisabledReason.OPTED_OUT), rig.facade.state())
        assertTrue(rig.records().isEmpty())
        assertNull(rig.owner.snapshot().get().state.identity.userId)
        assertEquals(1, rig.logs.size)
    }

    @Test fun `opt-out then opt-in while pending never releases calls made before the opt-out`() = Rig().use { rig ->
        rig.offline = true
        rig.facade.capture("before-opt-out", null, Date(rig.clock.wall))
        rig.facade.identify("user-1", null)
        rig.facade.register(mapOf("tier" to "gold"))
        rig.facade.reset()
        rig.facade.optOut()
        rig.facade.capture("during-opt-out", null, Date(rig.clock.wall))
        rig.facade.optIn(null, null)
        rig.settle() // opt-in lifts the denial once the lane has applied it
        rig.facade.capture("after-opt-in", null, Date(rig.clock.wall))
        rig.settle()
        rig.start()
        assertEquals(EluFacadeState.Pending, rig.facade.state())
        // The held reset collects nothing and still applies, as it does while opted out.
        assertEquals(2, rig.diagnostics().buffered)

        rig.offline = false
        rig.retry()
        assertEquals(EluFacadeState.Enabled, rig.facade.state())
        assertEquals(listOf("event:after-opt-in"), rig.queued())
        val event = rig.events().single()
        assertNull(event.identity.userId)
        assertNotEquals("anon_initial", event.identity.anonymousId)
        assertNull(event.properties["tier"])
        assertEquals(mapOf(EluFacadeDropReason.OPTED_OUT to 4), rig.diagnostics().dropped)
        assertEquals(listOf("Opt-out: 3 held calls; dropped: OPTED_OUT=3",
            "First configuration decision: 2 held calls; dropped: none"), rig.logs)
    }

    @Test fun `the hold keeps the first 100 calls and drops the newest`() = Rig().use { rig ->
        rig.offline = true
        repeat(105) { index -> rig.facade.capture("event-$index", null, Date(rig.clock.wall)) }
        rig.settle()
        rig.start()
        assertEquals(StandaloneFacade.PRE_INIT_BUFFER_LIMIT, rig.diagnostics().buffered)
        assertEquals(mapOf(EluFacadeDropReason.BUFFER_FULL to 5), rig.diagnostics().dropped)

        rig.offline = false
        rig.retry()
        assertEquals((0 until 100).map { "event:event-$it" }, rig.queued())
        assertEquals(listOf("First configuration decision: 100 held calls; dropped: BUFFER_FULL=5"), rig.logs)
    }

    @Test fun `the same document after a failed foreground or renewal fetch re-enables capture`() {
        for (mode in listOf("foreground", "renewal")) {
            Rig().use { rig ->
                rig.start()
                assertEquals(mode, EluFacadeState.Enabled, rig.facade.state())
                rig.facade.capture("online", null, Date(rig.clock.wall)); rig.settle()
                if (mode == "foreground") {
                    rig.driver.onBackground(); rig.drainNotifications(); rig.settle()
                    rig.offline = true
                    rig.driver.onForeground(); rig.fetch()
                } else {
                    rig.offline = true
                    rig.driver.refresh(); rig.fetch()
                }
                assertEquals(mode, EluFacadeState.Disabled(EluFacadeDisabledReason.UNAUTHORIZED), rig.facade.state())

                rig.offline = false
                rig.driver.refresh(); rig.fetch()
                assertEquals(mode, EluFacadeState.Enabled, rig.facade.state())
                val sameDocument = rig.runtime.applyConfiguration(rig.gate.snapshot()?.body).get(10, TimeUnit.SECONDS)
                assertTrue(mode, sameDocument is RuntimeCaptureAuthorityUpdateResult.Activated)
                rig.facade.capture("after-recovery", null, Date(rig.clock.wall)); rig.settle()
                assertEquals(mode, listOf("event:online", "event:after-recovery"), rig.queued())
            }
        }
    }

    @Test fun `a held call admitted a minute later under a newer issuance keeps its call time`() = Rig().use { rig ->
        rig.offline = true
        val calledAt = rig.clock.wall
        rig.facade.capture("held", null, Date(calledAt))
        rig.settle()
        rig.start()
        rig.clock.advance(60_000)
        // The recovered document was issued after the call was made.
        rig.body = JSONObject(rig.body).put("revision", "issued-after-call")
            .put("issuedAt", "2026-08-05T00:01:30.000Z").put("expiresAt", "2026-08-05T00:06:30.000Z").toString()
        rig.offline = false
        rig.retry()
        assertEquals(EluFacadeState.Enabled, rig.facade.state())
        val event = rig.events().single()
        assertEquals("held", event.name)
        assertEquals(RuntimeWallTimestamps.rfc3339(calledAt), event.occurredAt)
        assertEquals(emptyMap<EluFacadeDropReason, Int>(), rig.diagnostics().dropped)
    }

    private class Rig(deviceInEu: Boolean = false) : AutoCloseable {
        val clock = Clock()
        val worker = Worker()
        val scheduler = Scheduler()
        val gate = V2ConfigAuthorityGate()
        @Volatile var offline = false
        @Volatile var httpStatus = 200
        @Volatile var body = resource("contracts/v2/fixtures/config-enabled.json")
        val logs = CopyOnWriteArrayList<String>()
        val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport {
            if (offline) throw java.net.UnknownHostException("offline") else V2ConfigHttpResponse(httpStatus, body)
        }, clock)
        private val notifications = ArrayDeque<() -> Unit>()
        val driver = V2ConfigLifecycleDriver(source, listener = { token ->
            gate.update(token)
            synchronized(notifications) { notifications.add { runtime.configurationChanged(); facade.configurationChanged() } }
        }, clock = clock, scheduler = scheduler, worker = worker)
        val owner = RuntimeQueueOwner.open(
            "initial-decision-" + UUID.randomUUID(), RuntimeQueueLimits(1_000, 10_000_000),
            databaseFactory = { FakeRuntimeQueueBacking().connection() },
            legacyStateLoader = { initialState() }, trustedSiteKey = KEY,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = clock.wall
                override fun elapsedRealtimeNanos() = clock.nanos
            },
        ).get(10, TimeUnit.SECONDS).also { it.bindConfigurationGate(gate).get(10, TimeUnit.SECONDS) }
        val runtime = StandaloneRuntime(owner, KEY, wallClock = { clock.wall },
            deliveryClock = object : BatchDeliveryClock {
                override fun wallNow() = BatchWallInstant(clock.wall, Instant.ofEpochMilli(clock.wall).toString())
                override fun monotonicNowNanos() = clock.nanos
            },
            transportFactory = { BatchHTTPTransport { BatchHTTPResponse(503, ByteArray(0)) } },
            deviceInEuTimezone = { deviceInEu }, configurationGate = gate)
        val facade: StandaloneFacade = StandaloneFacade(
            open = { StandaloneStack(runtime, owner, null) }, deliverCallback = { it.run() }, wallClock = { clock.wall },
            configurationGate = gate, onCloseRequested = { driver.close(); gate.close() }, log = { logs += it },
        ).also { it.start() }

        fun drainNotifications() {
            while (true) { val next = synchronized(notifications) { notifications.pollFirst() } ?: return; next() }
        }
        fun settle() { facade.settled().get(10, TimeUnit.SECONDS) }
        fun fetch() { worker.runNext(); drainNotifications(); settle() }
        fun start() { driver.start(); fetch() }
        /** Fires the backoff retry the failed fetch scheduled. */
        fun retry() { scheduler.fireAll(); fetch() }
        fun diagnostics(): EluFacadeDiagnostics = facade.diagnostics().get(10, TimeUnit.SECONDS)
        fun records(): List<RuntimeQueuedRecord> = owner.peek(1_000, Long.MAX_VALUE).get(10, TimeUnit.SECONDS)
        fun events(): List<RuntimeEventRecord> = records().filterIsInstance<RuntimeQueuedRecord.Event>().map { it.record }
        fun queued(): List<String> = records().map { record ->
            when (record) {
                is RuntimeQueuedRecord.Event -> "event:${record.record.name}"
                is RuntimeQueuedRecord.Mutation -> "mutation:${record.envelope.mutation.change.type}"
            }
        }
        override fun close() { facade.close(); runCatching { owner.closeAsync().get(2, TimeUnit.SECONDS) }; driver.close() }
    }

    private class Clock : V2ConfigClock {
        @Volatile var wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
        @Volatile var nanos = 100L
        override fun wallNowEpochMillis() = wall
        override fun monotonicNowNanos() = nanos
        fun advance(millis: Long) { wall += millis; nanos += millis * 1_000_000L }
    }
    private class Worker : V2ConfigLifecycleWorker {
        val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.add(task) }
        override fun interruptCurrent() = Unit
        override fun close() = Unit
        fun runNext() { tasks.removeFirst().invoke() }
    }
    private class Scheduler : V2ConfigLifecycleScheduler {
        val pending = ArrayDeque<() -> Unit>()
        override fun schedule(delayNanos: Long, task: () -> Unit): V2ConfigLifecycleTask {
            pending.add(task); return V2ConfigLifecycleTask { pending.remove(task) }
        }
        fun fireAll() { val due = pending.toList(); pending.clear(); due.forEach { it() } }
        override fun close() = Unit
    }
    private companion object {
        val KEY = "elu_pk_live_" + "A".repeat(26)
        fun resource(path: String) = checkNotNull(V2ConfigInitialDecisionTest::class.java.classLoader?.getResourceAsStream(path)).bufferedReader().use { it.readText() }
        fun initialState() = PersistedCoreState(
            identity = IdentityState(revision = 1, contextRevision = 1, anonymousId = "anon_initial", userId = null,
                groups = emptyMap(), superProperties = emptyMap(), session = null, optedOut = false,
                updatedAt = "2026-08-05T00:00:00.000Z"),
            stream = StreamState(streamId = "stream_initial", nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )
    }
}
