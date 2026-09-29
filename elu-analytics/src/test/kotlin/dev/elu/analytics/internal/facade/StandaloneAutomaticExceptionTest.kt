package dev.elu.analytics.internal.facade

import dev.elu.analytics.EluDiagnosticsOptions
import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.EluRateLimitingOptions
import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.diagnostics.*
import dev.elu.analytics.internal.runtime.*
import dev.elu.analytics.internal.runtime.delivery.*
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual facade/runtime/source/gate/queue/writer; only host registry, filesystem and HTTP are doubles. */
class StandaloneAutomaticExceptionTest {
    private class Clock : V2ConfigClock, RuntimeCaptureClock {
        @Volatile var wall = Instant.parse("2026-08-04T00:01:00Z").toEpochMilli()
        @Volatile var nanos = 1_000_000_000L
        override fun wallNowEpochMillis() = wall
        override fun monotonicNowNanos() = nanos
        override fun elapsedRealtimeNanos() = nanos
    }
    private class Spool : NativeExceptionSpool {
        @Volatile var report: RuntimeExceptionReport? = null
        @Volatile var beforePublish: () -> Unit = {}
        val writes = AtomicInteger(); val clears = AtomicInteger()
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        override fun read() = report
        override fun publish(report: RuntimeExceptionReport, mayPublish: () -> Boolean): Boolean {
            threads += Thread.currentThread(); beforePublish()
            if (!mayPublish()) return false
            check(this.report == null); this.report = RuntimeExceptionReport.decode(report.encode()); writes.incrementAndGet(); return true
        }
        override fun clear() { clears.incrementAndGet(); report = null }
    }
    private class Registry : NativeUncaughtExceptionRegistry {
        val calls = CopyOnWriteArrayList<Pair<Thread, Throwable>>()
        val previous = Thread.UncaughtExceptionHandler { t, e -> calls += t to e }
        @Volatile var handler: Thread.UncaughtExceptionHandler = previous
        var beforeSet: () -> Unit = {}
        var refuseRestore = false
        val writes = AtomicInteger()
        override fun current() = handler
        override fun replace(handler: Thread.UncaughtExceptionHandler) {
            if (refuseRestore && handler === previous) error("restore rejected")
            if (handler !== previous) beforeSet(); writes.incrementAndGet(); this.handler = handler
        }
    }
    private inner class Harness(options: EluDiagnosticsOptions = EluDiagnosticsOptions(true, crashReports = true),
        memory: Boolean = false, val backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(),
        val spool: Spool = Spool(), val clock: Clock = Clock(), val key: String = "auto-${UUID.randomUUID()}",
        grant: Any? = JSONObject().put("suppressionRules", JSONArray()), val capacity: Int = 1000) : AutoCloseable {
        val gate = V2ConfigAuthorityGate(); val work = ArrayDeque<() -> Unit>()
        var body = JSONObject(javaClass.classLoader!!.getResource("contracts/v2/fixtures/config-enabled.json")!!.readText())
            .put("issuedAt", "2026-08-04T00:00:00.000Z").put("expiresAt", "2026-08-04T00:05:00.000Z")
            .also { if (grant != null) it.put("captureExceptions", grant) }.toString()
        val factoryCount = AtomicInteger(); val releases = AtomicInteger(); val registry = Registry()
        val owner = RuntimeQueueOwner.open(key, RuntimeQueueLimits(capacity, 1_000_000), { backing.connection() }, { state() },
            leaseFactory = { RuntimeOwnershipLease { releases.incrementAndGet() } }, trustedSiteKey = KEY,
            captureClock = clock, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY, memoryOnly = memory, rateLimiting = EluRateLimitingOptions(),
            exceptionSpoolFactory = { factoryCount.incrementAndGet(); spool }).await()
        lateinit var runtime: StandaloneRuntime
        lateinit var facade: StandaloneFacade
        val driver = V2ConfigLifecycleDriver(V2ConfigSource("https://elu.dev", KEY,
            V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock), { token ->
            gate.update(token)
            if (::runtime.isInitialized) runtime.withdrawAutomaticExceptions()
        }, clock, object : V2ConfigLifecycleScheduler {
            override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
            override fun close() = Unit
        }, object : V2ConfigLifecycleWorker {
            override fun execute(task: () -> Unit) { work.addLast(task) }
            override fun interruptCurrent() = Unit
            override fun close() { work.clear() }
        })
        init {
            owner.bindConfigurationGate(gate).await()
            driver.start(); work.removeFirst().invoke()
            runtime = StandaloneRuntime(owner, KEY, wallClock = { clock.wall },
                transportFactory = { BatchHTTPTransport { BatchHTTPResponse(503, byteArrayOf()) } },
                deviceInEuTimezone = { false }, configurationGate = gate, diagnosticsOptions = options,
                exceptionRegistry = registry, automaticExceptionAllowed = { ::facade.isInitialized && facade.automaticExceptionIntakeAllowed() },
                flushDelayMillis = 60_000)
            registry.beforeSet = { assertNotNull(owner.snapshot().await()); assertNotNull(backing.core!!.exceptions!!.reservation) }
            facade = StandaloneFacade({ StandaloneStack(runtime, owner, null) }, { it.run() },
                wallClock = { clock.wall }, configurationGate = gate)
            facade.start(); settle()
        }
        fun settle() {
            facade.settled().get(3, TimeUnit.SECONDS)
            val control = StandaloneRuntime::class.java.getDeclaredField("controlExecutor").also { it.isAccessible = true }.get(runtime) as ExecutorService
            repeat(3) { control.submit { }.get(3, TimeUnit.SECONDS); owner.snapshot().await() }
        }
        fun installed() { await("original installed handler") { registry.handler !== registry.previous } }
        fun intake() = RuntimeQueueOwner::class.java.getDeclaredField("exceptionIntake").also { it.isAccessible = true }
            .get(owner) as NativeExceptionIntake
        fun armed() { await("current original arm") { intake().allowsObservation() } }
        fun activity() { facade.capture("user", null, Date(clock.wall)); settle() }
        fun crash(error: Throwable = IllegalStateException("PRIVATE_MESSAGE")) {
            val thread = Thread.currentThread(); registry.handler.uncaughtException(thread, error)
            assertSame(thread, registry.calls.last().first); assertSame(error, registry.calls.last().second)
        }
        fun exceptions() = owner.peek(100, 100_000).await().filterIsInstance<RuntimeQueuedRecord.Event>()
            .filter { it.record.name == ExceptionSerializer.EVENT_NAME }
        fun replaceGrant(grant: Any?) {
            driver.onBackground(); runtime.configurationChanged()
            val json = JSONObject(body); if (grant == null) json.remove("captureExceptions") else json.put("captureExceptions", grant)
            body = json.toString(); driver.onForeground(); work.removeFirst().invoke()
            facade.configurationChanged(); settle()
        }
        fun holdFacade(): CountDownLatch {
            val lane = StandaloneFacade::class.java.getDeclaredField("lane").also { it.isAccessible = true }.get(facade) as ExecutorService
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            lane.execute { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); return release
        }
        override fun close() { driver.close(); gate.close(); facade.closeAndWait().get(5, TimeUnit.SECONDS) }
    }
    @Test fun `only opted in persistent exact grant installs after original reservation without startup OS access`() {
        val unsupported = JSONObject().put("suppressionRules", JSONArray().put(JSONObject().put("type", "AND").put("values", JSONArray())))
        for (grant in listOf(null, false, unsupported)) Harness(grant = grant).use { h ->
            h.activity(); assertSame(h.registry.previous, h.registry.handler); assertEquals(0, h.factoryCount.get())
            assertNull(h.backing.core!!.exceptions)
        }
        for (options in listOf(EluDiagnosticsOptions(), EluDiagnosticsOptions(true), EluDiagnosticsOptions(false, crashReports = true)))
            Harness(options).use { h -> h.activity(); assertSame(h.registry.previous, h.registry.handler); assertEquals(0, h.factoryCount.get()) }
        Harness(memory = true).use { h -> h.activity(); assertSame(h.registry.previous, h.registry.handler); assertEquals(0, h.factoryCount.get()) }
        Harness().use { h -> h.installed(); assertEquals(1, h.registry.writes.get()); assertEquals(1, h.factoryCount.get()) }
    }
    @Test fun `returning original handler imports type once and rearms same writer with private data absent`() {
        Harness().use { h ->
            h.activity(); h.installed(); val before = h.owner.snapshot().await().state.identity
            h.crash(); await("one passive imported report") { h.exceptions().size == 1 }
            val event = h.exceptions().single().record
            assertEquals(before, h.owner.snapshot().await().state.identity)
            assertEquals(emptyMap<String, String>(), event.groups)
            assertFalse(event.properties.toString().contains("PRIVATE")); assertFalse(event.properties.containsKey("private"))
            assertEquals("java.lang.IllegalStateException", event.properties["\$exception_type"])
            await("new committed reservation") { h.spool.report == null }
            h.settle(); h.armed(); h.crash(UnsupportedOperationException("PRIVATE_TWO"))
            await("second original report") { h.exceptions().size == 2 }
            assertEquals(2, h.registry.calls.size); assertEquals(1, h.registry.writes.get()); assertEquals(1, h.spool.threads.size)
        }
    }
    @Test fun `sessionless report waits for real first activity then imports without creating historical session`() {
        Harness().use { h ->
            h.installed(); h.crash(); h.settle()
            assertNull(h.owner.snapshot().await().state.identity.session); assertTrue(h.exceptions().isEmpty()); assertNotNull(h.spool.report)
            h.activity(); await("actual-session delayed report") { h.exceptions().size == 1 }
            assertEquals(h.owner.snapshot().await().state.identity.session!!.id, h.exceptions().single().record.sessionId)
        }
    }
    @Test fun `quota refused report stays reserved and retries only after real queue capacity is available`() {
        Harness(capacity = 1).use { h ->
            h.activity(); h.installed(); h.crash(); h.settle()
            assertTrue(h.exceptions().isEmpty()); val retained = checkNotNull(h.spool.report)
            assertNull(h.backing.core!!.exceptions!!.consumedDigest)
            val queued = h.owner.peek(10, 100_000).await()
            assertEquals(1, queued.size)
            h.owner.acknowledge(RuntimeAcknowledgement(queued.first().streamId,
                queued.map { RuntimeRecordReference(it.sequence, it.kind, it.recordId) })).await()
            h.runtime.flush().get(3, TimeUnit.SECONDS)
            await("retained report after capacity release") { h.exceptions().size == 1 }
            assertEquals(retained.type, h.exceptions().single().record.properties["\$exception_type"])
            h.settle(); assertNull(h.spool.report)
            assertEquals(1, h.spool.writes.get())
        }
    }
    @Test fun `accepted identity and consent changes immediately disarm while facade lane is held`() {
        for (change in listOf<(StandaloneFacade) -> Unit>({ it.identify("next", null) }, { it.reset() }, { it.optOut() })) {
            Harness().use { h ->
                h.activity(); h.installed(); val release = h.holdFacade()
                try { change(h.facade); h.crash(); assertEquals(0, h.spool.writes.get()) }
                finally { release.countDown() }
                h.settle(); assertTrue(h.exceptions().isEmpty())
            }
        }
    }
    @Test fun `equivalent source renewal reuses original handler and writer but old held publication is denied`() {
        Harness().use { h ->
            h.activity(); h.installed(); val wrapper = h.registry.handler
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            h.spool.beforePublish = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            val originalCompletion = h.intake().reportSettlement
            try {
                h.crash(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                h.replaceGrant(JSONObject().put("suppressionRules", JSONArray()))
                assertSame(wrapper, h.registry.handler)
            } finally { release.countDown() }
            originalCompletion.get(3, TimeUnit.SECONDS)
            assertNull(h.spool.report)
            h.spool.beforePublish = {}; h.settle(); h.armed()
            h.crash(); await("new original report only") { h.exceptions().size == 1 }
            assertEquals(1, h.spool.writes.get()); assertEquals(1, h.spool.threads.size); assertEquals(1, h.registry.writes.get())
        }
    }
    @Test fun `restrictive rule withdrawal clears sessionless report before regrant can import it`() {
        Harness().use { h ->
            h.installed(); h.crash(); h.settle(); assertNotNull(h.spool.report)
            h.replaceGrant(false); assertNull(h.spool.report); assertNull(h.backing.core!!.exceptions!!.reservation)
            h.replaceGrant(JSONObject().put("suppressionRules", JSONArray())); h.activity()
            assertTrue(h.exceptions().isEmpty()); assertEquals(1, h.registry.writes.get())
        }
    }
    @Test fun `background expiry and displaced handler never observe or overwrite later host registration`() {
        Harness().use { h ->
            h.activity(); h.installed(); val wrapper = h.registry.handler
            h.driver.onBackground(); h.crash(); assertEquals(0, h.spool.writes.get())
            h.driver.onForeground(); h.work.removeFirst().invoke(); h.facade.configurationChanged(); h.settle()
            h.clock.wall += 300_000; h.clock.nanos += 300_000_000_000L
            h.crash(); assertEquals(0, h.spool.writes.get())
            val foreign = Thread.UncaughtExceptionHandler { _, _ -> }
            h.registry.handler = foreign; wrapper.uncaughtException(Thread(), Error())
            assertSame(foreign, h.registry.handler); assertEquals(3, h.registry.calls.size)
        }
    }
    @Test fun `foreground renewal rearms after the actual facade transition settles without a new event`() {
        Harness().use { h ->
            h.activity(); h.installed(); val wrapper = h.registry.handler
            h.facade.nativeReplayLifecycleChanged(false); h.runtime.markBackgrounded().await(); h.settle()
            val background = h.owner.snapshot().await().state.identity
            val backgroundSession = checkNotNull(background.session)
            assertEquals(SessionLifecycle.BACKGROUND, backgroundSession.lifecycle)
            h.crash(); assertEquals(0, h.spool.writes.get())
            h.facade.nativeReplayLifecycleChanged(true); h.runtime.markForegrounded(); h.settle(); h.armed()
            assertSame(wrapper, h.registry.handler)
            assertEquals(background, h.owner.snapshot().await().state.identity)
            val publication = h.intake().reportSettlement
            h.crash(); publication.get(3, TimeUnit.SECONDS); h.settle()
            assertEquals(1, h.spool.writes.get()); assertNotNull(h.spool.report)
            assertTrue(h.exceptions().isEmpty())
            assertEquals(background, h.owner.snapshot().await().state.identity)
            // Foregrounding rearms collection, but only real activity makes a session
            // eligible for passive import; the exception must not invent that activity.
            h.activity(); await("foreground report after real activity") { h.exceptions().size == 1 }
            h.settle(); h.armed()
            assertEquals(1, h.exceptions().size); assertNull(h.spool.report)
            assertEquals(backgroundSession.id, h.exceptions().single().record.sessionId)
            assertEquals(1, h.spool.writes.get()); assertSame(wrapper, h.registry.handler)
        }
    }
    @Test fun `retained process report imports once on next original stack and consumed bytes cannot duplicate`() {
        val first = Harness(); first.installed(); first.crash(); first.settle()
        val saved = checkNotNull(first.spool.report)
        // Retain the committed storage image a process death would leave. Closing this test's
        // original owner still performs real cleanup; a fresh owner never adopts its handles.
        val persisted = synchronized(first.backing) { FakeRuntimeQueueBacking().also {
            it.core = first.backing.core; it.databaseSchemaVersion = first.backing.databaseSchemaVersion
            it.captureRateState = first.backing.captureRateState
            it.records.putAll(first.backing.records); it.replayRows.putAll(first.backing.replayRows)
            it.flagRows.putAll(first.backing.flagRows)
        } }
        val retainedSpool = Spool().also { it.report = RuntimeExceptionReport.decode(saved.encode()) }
        first.close()
        Harness(backing = persisted, spool = retainedSpool, clock = first.clock, key = first.key).use { second ->
            assertTrue(second.exceptions().isEmpty()); assertEquals(saved, second.spool.report)
            second.activity(); await("restart import") { second.exceptions().size == 1 }
            second.settle(); second.armed()
            assertNull(second.spool.report)
            second.replaceGrant(JSONObject().put("suppressionRules", JSONArray())); second.armed()
            assertEquals(1, second.exceptions().size)
        }
        assertEquals(1, first.releases.get())
    }
    @Test fun `restore failure leaves original wrapper inert and close still joins writer and queue`() {
        val h = Harness(); h.activity(); h.installed(); val wrapper = h.registry.handler
        val writer = NativeExceptionIntake::class.java.getDeclaredField("writer").also { it.isAccessible = true }.get(h.intake()) as Thread
        assertTrue(writer.isAlive)
        h.registry.refuseRestore = true
        assertThrows(ExecutionException::class.java) { h.facade.closeAndWait().get(5, TimeUnit.SECONDS) }
        assertEquals(1, h.releases.get())
        val calls = h.registry.calls.size; val writes = h.spool.writes.get()
        val original = Error("PRIVATE"); wrapper.uncaughtException(Thread.currentThread(), original)
        assertEquals(calls + 1, h.registry.calls.size); assertSame(original, h.registry.calls.last().second)
        assertEquals(writes, h.spool.writes.get()); assertSame(wrapper, h.registry.handler)
        assertFalse(writer.isAlive)
        h.driver.close(); h.gate.close()
    }
    private fun state() = PersistedCoreState(identity = IdentityState(revision = 2, contextRevision = 5,
        anonymousId = "anon_diag", userId = "user_diag", groups = mapOf("private" to "PRIVATE_GROUP"),
        superProperties = mapOf("private" to "PRIVATE_PROPERTY"), session = null, optedOut = false,
        updatedAt = "2026-08-04T00:00:00.000Z"), stream = StreamState(streamId = "stream_diag", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue(message, condition())
    }
    private fun <T> Future<T>.await(): T = get(5, TimeUnit.SECONDS)
    private companion object { const val KEY = "elu_pk_test_AAAAAAAAAAAAAAAAAAAAAAAAAA" }
}
