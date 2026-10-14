package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.diagnostics.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RuntimeExceptionIntakeTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    @After fun close() { owners.forEach { runCatching { it.closeAsync().await() } } }
    private class Clock : RuntimeCaptureClock {
        @Volatile var wall = Instant.parse(NOW).toEpochMilli()
        @Volatile var nanos = 1_000_000_000L
        override fun wallNowEpochMillis() = wall
        override fun elapsedRealtimeNanos() = nanos
        fun advance(ms: Long) { wall += ms; nanos += ms * 1_000_000 }
    }
    private class Spool : NativeExceptionSpool {
        @Volatile var report: RuntimeExceptionReport? = null
        var clearFailure: Throwable? = null
        var clears = 0
        var beforePublish: () -> Unit = {}
        val writerThreads = java.util.Collections.synchronizedSet(mutableSetOf<Thread>())
        override fun read() = report
        override fun publish(report: RuntimeExceptionReport, mayPublish: () -> Boolean): Boolean {
            writerThreads += Thread.currentThread()
            beforePublish()
            if (!mayPublish()) return false
            check(this.report == null); this.report = RuntimeExceptionReport.decode(report.encode()); return true
        }
        override fun clear() { clearFailure?.let { throw it }; clears++; report = null }
    }
    private inner class Rig(val backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(), val spool: Spool = Spool(),
        val clock: Clock = Clock(), val key: String = "exception-${UUID.randomUUID()}", memory: Boolean = false,
        leaseClosed: () -> Unit = {}, val factoryCount: () -> Unit = {}, val capacity: Int = 1000,
    ) {
        val policy = NativeExceptionPolicyLease("b".repeat(64))
        val owner = RuntimeQueueOwner.open(key, RuntimeQueueLimits(capacity, 1_000_000), { backing.connection() }, { state() },
            leaseFactory = { RuntimeOwnershipLease(leaseClosed) }, trustedSiteKey = "elu_pk_test_diagnostics", captureClock = clock,
            personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY, memoryOnly = memory,
            exceptionSpoolFactory = { factoryCount(); spool }).await().also { owners += it }
        fun activate(body: String = resource("config-enabled.json")) = owner.submitCaptureAuthority(body, privacy(owner.snapshot().await().state.identity.contextRevision)).await()
        fun user() = owner.capture(RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "user", now(), emptyMap(), versions())).await()
        fun now() = RuntimeWallTimestamps.rfc3339(clock.wall)
        fun prepare() = owner.prepareExceptionIntake(policy, versions()).await()
        fun crash(): NativeExceptionIntake {
            val intake = checkNotNull(prepare())
            intake.offer(NativeExceptionObservation.from(IllegalStateException("PRIVATE")))
            intake.reportSettlement.get(3, TimeUnit.SECONDS)
            assertTrue(intake.published)
            return intake
        }
        fun snapshotDeath(): Pair<FakeRuntimeQueueBacking, Spool> {
            val copy = FakeRuntimeQueueBacking().apply {
                core = backing.core!!.copy(stateJson = backing.core!!.stateJson.copyOf())
                databaseSchemaVersion = backing.databaseSchemaVersion; records.putAll(backing.records)
                captureRateState = backing.captureRateState
            }
            return copy to Spool().also { it.report = spool.report?.let { value -> RuntimeExceptionReport.decode(value.encode()) } }
        }
        fun restart(copy: Pair<FakeRuntimeQueueBacking, Spool>): Rig {
            owner.closeAsync().await()
            return Rig(copy.first, copy.second, clock, key)
        }
    }
    @Test fun `memory absent authority and revoked policy create no spool or writer`() {
        var created = 0
        val memory = Rig(memory = true, factoryCount = { created++ }); memory.activate()
        assertNull(memory.prepare()); assertNull(memory.backing.core!!.exceptions)
        val absent = Rig(factoryCount = { created++ }); assertNull(absent.prepare())
        absent.activate(); absent.policy.revoke(); assertNull(absent.prepare())
        assertEquals(0, created)
    }
    @Test fun `actual writer report imports once with event marker and current passive receipt session`() {
        val original = Rig(); original.activate(); original.user(); original.crash()
        val copy = original.snapshotDeath(); val restarted = original.restart(copy)
        restarted.activate(); val before = restarted.owner.snapshot().await().state.identity
        restarted.clock.advance(100)
        assertNotNull(restarted.prepare())
        val rows = restarted.owner.peek(10, 100_000).await()
        assertEquals(2, rows.size)
        val event = (rows.last() as RuntimeQueuedRecord.Event).record
        assertEquals(ExceptionSerializer.EVENT_NAME, event.name); assertEquals(before, restarted.owner.snapshot().await().state.identity)
        assertEquals(emptyMap<String, String>(), event.groups)
        assertFalse(event.properties.containsKey("private")); assertFalse(event.properties.toString().contains("PRIVATE"))
        assertEquals(before.anonymousId, event.properties["\$device_id"])
        assertNull(restarted.spool.report)
    }
    @Test fun `event commit before file deletion survives process death without duplicate after ACK`() {
        val original = Rig(); original.activate(); original.user(); original.crash()
        val restarted = original.restart(original.snapshotDeath()); restarted.activate()
        restarted.spool.clearFailure = java.io.IOException("delete failed after import")
        assertThrows(ExecutionException::class.java) { restarted.prepare() }
        assertNotNull(restarted.backing.core!!.exceptions!!.consumedDigest)
        assertEquals(2, restarted.owner.snapshot().await().queuedCount)
        val copy = restarted.snapshotDeath()
        // Model an ACK before abrupt death too: the marker must outlive queue record retirement.
        val events = restarted.owner.peek(10, 100_000).await()
        restarted.owner.acknowledge(RuntimeAcknowledgement(events.first().streamId, events.map { RuntimeRecordReference(it.sequence, it.kind, it.recordId) })).await()
        copy.first.records.clear()
        copy.first.core = copy.first.core!!.copy(queueCount = 0, queueBytes = 0)
        restarted.spool.clearFailure = null
        val final = restarted.restart(copy); final.activate(); assertNotNull(final.prepare())
        assertEquals(0, final.owner.snapshot().await().queuedCount)
    }
    @Test fun `unknown commit reconciles both complete outcomes with one imported event`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) {
            val original = Rig(); original.activate(); original.user(); original.crash()
            val restarted = original.restart(original.snapshotDeath()); restarted.activate()
            restarted.backing.ambiguousNextCommit = outcome
            assertNotNull(restarted.prepare())
            assertEquals(2, restarted.owner.snapshot().await().queuedCount)
            assertEquals(1, restarted.owner.peek(10, 100_000).await().filterIsInstance<RuntimeQueuedRecord.Event>()
                .count { it.record.name == ExceptionSerializer.EVENT_NAME })
        }
    }
    @Test fun `quota rejection retains the original report without consuming its reservation`() {
        val original = Rig(); original.activate(); original.user(); original.crash()
        val copy = original.snapshotDeath(); original.owner.closeAsync().await()
        val full = Rig(copy.first, copy.second, original.clock, original.key, capacity = 1); full.activate()
        assertNull(full.prepare()); assertNotNull(full.spool.report)
        assertNull(full.backing.core!!.exceptions!!.consumedDigest); assertEquals(1, full.owner.snapshot().await().queuedCount)
    }

    @Test fun `unresolved import commit keeps the original lease rather than losing its dedupe evidence`() {
        val original = Rig(); original.activate(); original.user(); original.crash()
        val copy = original.snapshotDeath(); original.owner.closeAsync().await()
        var releases = 0
        val next = Rig(copy.first, copy.second, original.clock, original.key, leaseClosed = { releases++ }); next.activate()
        next.backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE
        assertThrows(ExecutionException::class.java) { next.prepare() }
        assertThrows(ExecutionException::class.java) { next.owner.closeAsync().await() }
        assertEquals(0, releases); assertNotNull(next.spool.report)
        assertThrows(ExecutionException::class.java) { Rig(key = original.key) }
    }

    @Test fun `same consumed reservation with changed report bytes refuses rather than duplicating`() {
        val original = Rig(); original.activate(); original.user(); original.crash()
        val next = original.restart(original.snapshotDeath()); next.activate()
        next.spool.clearFailure = java.io.IOException("retain committed report")
        assertThrows(ExecutionException::class.java) { next.prepare() }
        val copy = next.snapshotDeath(); copy.second.report = copy.second.report!!.copy(type = "DifferentType")
        next.spool.clearFailure = null
        val changed = next.restart(copy); changed.activate()
        assertThrows(ExecutionException::class.java) { changed.prepare() }
        assertEquals(2L, changed.backing.core!!.queueCount)
    }

    @Test fun `report cannot create a receipt session or revive identity consent or policy coverage`() {
        val original = Rig(); original.activate(); original.crash()
        val restarted = original.restart(original.snapshotDeath()); restarted.activate()
        assertNull(restarted.prepare()); assertEquals(0, restarted.owner.snapshot().await().queuedCount)
        restarted.user(); assertNotNull(restarted.prepare()); assertEquals(2, restarted.owner.snapshot().await().queuedCount)
        for (change in listOf<(Rig) -> Unit>(
            { it.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(it.now())).await() },
            { it.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, it.now())).await()
              it.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, it.now())).await() })) {
            val first = Rig(); first.activate(); first.user(); first.crash(); val next = first.restart(first.snapshotDeath())
            change(next); next.activate(); next.user(); val count = next.owner.snapshot().await().queuedCount
            assertNotNull(next.prepare()); assertEquals(count, next.owner.snapshot().await().queuedCount)
            assertNull(next.spool.report)
        }
    }
    @Test fun `receipt wall rollback cannot import an observation from the future`() {
        val original = Rig(); original.activate(); original.user()
        val intake = checkNotNull(original.prepare()); original.clock.advance(100)
        intake.offer(NativeExceptionObservation.from(Error())); intake.reportSettlement.get(3, TimeUnit.SECONDS)
        val copy = original.snapshotDeath(); val next = original.restart(copy)
        next.clock.wall -= 50; next.activate()
        assertNull(next.prepare()); assertEquals(1, next.owner.snapshot().await().queuedCount)
        assertNull(next.backing.core!!.exceptions!!.consumedDigest); assertNotNull(next.spool.report)
    }

    @Test fun `death between durable reservation and file publication does not synthesize a report`() {
        val first = Rig(); first.activate(); first.user(); checkNotNull(first.prepare())
        assertNotNull(first.backing.core!!.exceptions!!.reservation); assertNull(first.spool.report)
        val second = first.restart(first.snapshotDeath()); second.activate(); assertNotNull(second.prepare())
        assertEquals(1, second.owner.snapshot().await().queuedCount)
    }
    @Test fun `original lease stays held across blocked publication and close observation timeout`() {
        var releases = 0
        val rig = Rig(leaseClosed = { releases++ }); rig.activate(); rig.user()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        rig.spool.beforePublish = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val intake = checkNotNull(rig.prepare())
        try {
            intake.offer(NativeExceptionObservation.from(Error()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val closing = rig.owner.closeAsync()
            assertFalse(closing.isDone); assertEquals(0, releases)
            assertThrows(java.util.concurrent.TimeoutException::class.java) { closing.get(10, TimeUnit.MILLISECONDS) }
            assertThrows(ExecutionException::class.java) { Rig(key = rig.key) }
            release.countDown(); closing.await()
            assertEquals(1, releases); assertNull(rig.spool.report)
        } finally { release.countDown() }
    }
    @Test fun `fresh reservation rearms only the same original settled writer after withdrawal`() {
        val rig = Rig(); rig.activate(); rig.user()
        val first = checkNotNull(rig.prepare()); val firstId = rig.backing.core!!.exceptions!!.reservation!!.id
        rig.activate() // ordinary authority renewal withdraws, but does not kill the writer.
        assertTrue(first.reportSettlement.isDone); assertFalse(first.settlement.isDone)
        rig.clock.advance(100)
        val second = checkNotNull(rig.prepare())
        assertSame(first, second); assertNotEquals(firstId, rig.backing.core!!.exceptions!!.reservation!!.id)
        second.offer(NativeExceptionObservation.from(Error()))
        second.reportSettlement.get(3, TimeUnit.SECONDS)
        assertTrue(second.published)
        assertEquals(rig.backing.core!!.exceptions!!.reservation, rig.spool.report!!.reservation)
    }

    @Test fun `late old write cannot publish or rearm while original physical work is outstanding`() {
        val rig = Rig(); rig.activate(); rig.user()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        rig.spool.beforePublish = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val first = checkNotNull(rig.prepare()); val oldId = rig.backing.core!!.exceptions!!.reservation!!.id
        try {
            first.offer(NativeExceptionObservation.from(Error()))
            assertTrue(entered.await(2, TimeUnit.SECONDS)); rig.activate()
            assertNull(rig.prepare()); assertEquals(oldId, rig.backing.core!!.exceptions!!.reservation!!.id)
        } finally { release.countDown() }
        first.reportSettlement.get(3, TimeUnit.SECONDS); assertNull(rig.spool.report)
        rig.spool.beforePublish = {}
        assertSame(first, rig.prepare()); assertNotEquals(oldId, rig.backing.core!!.exceptions!!.reservation!!.id)
        first.offer(NativeExceptionObservation.from(Error()))
        first.reportSettlement.get(3, TimeUnit.SECONDS)
        assertEquals(1, rig.spool.writerThreads.size); assertTrue(first.published)
    }

    @Test fun `failed preparation remains owned and closes through the existing barrier`() {
        var releases = 0
        val rig = Rig(leaseClosed = { releases++ }); rig.activate(); rig.spool.clearFailure = java.io.IOException("startup slot I/O")
        assertThrows(ExecutionException::class.java) { rig.prepare() }
        assertEquals(0, releases)
        assertThrows(ExecutionException::class.java) { Rig(key = rig.key) }
        rig.spool.clearFailure = null; rig.owner.closeAsync().await(); assertEquals(1, releases)
    }
    @Test fun `original source gates fresh offer and held publication without owner lane renewal`() {
        for (replacement in listOf(false, true)) for (held in listOf(false, true)) {
            val rig = Rig(); val gate = V2ConfigAuthorityGate()
            val body = JSONObject(javaClass.classLoader!!.getResource("contracts/v2/fixtures/config-enabled.json")!!.readText())
                .put("issuedAt", "2026-08-04T00:00:00.000Z").put("expiresAt", "2026-08-04T00:05:00.000Z").toString()
            val tasks = ArrayDeque<() -> Unit>()
            val clock = object : V2ConfigClock {
                override fun wallNowEpochMillis() = rig.clock.wall
                override fun monotonicNowNanos() = rig.clock.nanos
            }
            val source = V2ConfigSource("https://elu.dev", "elu_pk_test_AAAAAAAAAAAAAAAAAAAAAAAAAA",
                V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock)
            val driver = V2ConfigLifecycleDriver(source, gate::update, clock,
                object : V2ConfigLifecycleScheduler {
                    override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
                    override fun close() = Unit
                }, object : V2ConfigLifecycleWorker {
                    override fun execute(task: () -> Unit) { tasks.addLast(task) }
                    override fun interruptCurrent() = Unit
                    override fun close() { tasks.clear() }
                })
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                driver.start(); tasks.removeFirst().invoke()
                val original = checkNotNull(gate.snapshot()); assertEquals(body, original.body)
                rig.owner.bindConfigurationGate(gate).await()
                assertTrue(rig.activate(body) is RuntimeCaptureAuthorityUpdateResult.Activated)
                rig.user(); val intake = checkNotNull(rig.prepare())
                if (held) {
                    rig.spool.beforePublish = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                    intake.offer(NativeExceptionObservation.from(Error()))
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                }
                // Do not call activate/prepare on the original owner after this source change.
                driver.onBackground()
                if (replacement) {
                    driver.onForeground(); tasks.removeFirst().invoke()
                    val renewed = checkNotNull(gate.snapshot())
                    assertEquals(original.body, renewed.body); assertNotSame(original.token, renewed.token)
                    assertTrue(renewed.isCurrent())
                }
                assertFalse(original.isCurrent()); assertTrue(rig.policy.isCurrent())
                if (!held) intake.offer(NativeExceptionObservation.from(Error()))
                release.countDown()
                if (held) intake.reportSettlement.get(3, TimeUnit.SECONDS)
                else assertEquals(0, rig.spool.writerThreads.size)
                assertFalse(intake.published); assertNull(rig.spool.report)
                rig.owner.closeAsync().await()
            } finally { release.countDown(); driver.close(); gate.close() }
        }
    }
    @Test fun `completed writer signal cannot close or quarantine owner while its original thread is alive`() {
        for (nativeQuarantine in listOf(false, true)) {
            var releases = 0
            val rig = Rig(leaseClosed = { releases++ }); rig.activate(); rig.user()
            if (nativeQuarantine) {
                rig.owner.ensurePreparedReplayStorage().await(); rig.owner.ensureNativeReplayAccounting().await()
                val enrollment = checkNotNull(rig.owner.enrollNativeReplayCapture().await())
                enrollment.cancelUnused(); enrollment.quarantine()
                assertTrue(enrollment.settlement.isDone)
            }
            val publishEntered = CountDownLatch(1); val publishRelease = CountDownLatch(1)
            val callbackEntered = CountDownLatch(1); val callbackRelease = CountDownLatch(1)
            val writer = java.util.concurrent.atomic.AtomicReference<Thread>()
            rig.spool.beforePublish = { publishEntered.countDown(); check(publishRelease.await(3, TimeUnit.SECONDS)) }
            val intake = checkNotNull(rig.prepare())
            // SdkFuture notifies in reverse registration order: queue barrier runs first,
            // then this original writer is held inside the earlier synchronous listener.
            intake.settlement.whenComplete { _, _ ->
                writer.set(Thread.currentThread()); callbackEntered.countDown(); check(callbackRelease.await(3, TimeUnit.SECONDS))
            }
            try {
                intake.offer(NativeExceptionObservation.from(Error()))
                assertTrue(publishEntered.await(2, TimeUnit.SECONDS))
                val clears = rig.spool.clears
                val closing = rig.owner.closeAsync()
                publishRelease.countDown(); assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
                assertTrue(intake.settlement.isDone); assertTrue(writer.get().isAlive)
                assertThrows(java.util.concurrent.TimeoutException::class.java) { closing.get(100, TimeUnit.MILLISECONDS) }
                assertEquals(0, releases); assertEquals(clears, rig.spool.clears)
                assertThrows(ExecutionException::class.java) { Rig(key = rig.key) }
                callbackRelease.countDown()
                if (nativeQuarantine) {
                    assertThrows(ExecutionException::class.java) { closing.await() }
                    assertEquals(0, releases)
                } else { closing.await(); assertEquals(1, releases) }
                assertFalse(writer.get().isAlive)
            } finally { publishRelease.countDown(); callbackRelease.countDown() }
        }
    }
    private fun state() = PersistedCoreState(identity = IdentityState(revision = 2, contextRevision = 5,
        anonymousId = "anon_diag", userId = "user_diag", groups = mapOf("secret" to "group"),
        superProperties = mapOf("private" to "value"), session = null, optedOut = false, updatedAt = "2026-08-04T00:00:00.000Z"),
        stream = StreamState(streamId = "stream_diag", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
    private fun privacy(context: Long): String {
        val json = JSONObject(resource("privacy-allowed.json")).put("contextRevision", context)
        val parsed = V1StrictCanonicalJson.parse(json.toString()) as V1StrictCanonicalJson.Value.ObjectValue
        json.put("effectivePolicyHash", V1StrictCanonicalJson.sha256(V1StrictCanonicalJson.Value.ObjectValue(
            parsed.members.filterNot { it.first == "effectivePolicyHash" })))
        return json.toString()
    }
    private fun resource(name: String) = javaClass.classLoader!!.getResource("contracts/v1/fixtures/$name")!!.readText()
    private fun versions() = RuntimeVersions(platform = RuntimePlatform.ANDROID,
        runtime = RuntimeVersionComponent("elu-android", "0.2.0"), facade = RuntimeVersionComponent("Elu", "0.2.0"))
    private fun <T> Future<T>.await(): T = get(5, TimeUnit.SECONDS)
    private companion object { const val NOW = "2026-08-04T00:01:00.000Z" }
}
