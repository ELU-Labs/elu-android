package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeReplaySessionQueueTest {
    @Test fun `explicit native migration preserves flags in either order and event counters`() {
        for (flagsFirst in listOf(false, true)) Rig().use { rig ->
            if (flagsFirst) rig.owner.ensureFeatureFlagRuntime().get()
            rig.owner.ensurePreparedReplayStorage().get()
            rig.owner.ensureNativeReplayAccounting().get()
            assertEquals(if (flagsFirst) 6 else 5, rig.backing.databaseSchemaVersion)
            val native = rig.row().payload.copyOf()
            rig.owner.ensureFeatureFlagRuntime().get()
            assertEquals(6, rig.backing.databaseSchemaVersion)
            assertArrayEquals(native, rig.row().payload)
            assertTrue(rig.backing.flagRows.containsKey(RUNTIME_FLAG_AUTHORITY_KEY))
            rig.owner.ensureNativeReplayAccounting().get()
            assertArrayEquals(native, rig.row().payload)
            assertEquals(0L, rig.owner.snapshot().get().state.stream.nextSequence)
            assertEquals(0, rig.owner.snapshot().get().queuedCount)
        }
    }
    @Test fun `native storage requires replay and does not silently initialize it`() = Rig().use { rig ->
        failure { rig.owner.ensureNativeReplayAccounting().get() }
        assertEquals(1, rig.backing.databaseSchemaVersion)
        assertTrue(rig.backing.replayRows.isEmpty())
    }
    @Test fun `missing native metadata future schema and unexpected metadata never reseed`() {
        for (mode in 0..3) Rig().use { rig ->
            rig.activate(); val original = rig.row()
            when (mode) {
                0 -> rig.backing.replayRows.remove(NativeReplayAccounting.KEY)
                1 -> rig.backing.replayRows[NativeReplayAccounting.KEY] = original.copy(storageSchemaVersion = 2)
                2 -> rig.backing.replayRows[NativeReplayAccounting.KEY] = original.copy(payload = "{}".toByteArray())
                3 -> rig.backing.replayRows["native_unrecognized"] = original.copy(key = "native_unrecognized")
            }
            val before = rig.backing.replayRows.mapValues { it.value.payload.toList() }
            failure { rig.reopen() }
            assertEquals(before, rig.backing.replayRows.mapValues { it.value.payload.toList() })
        }
    }
    @Test fun `native row in older schema fails closed`() = Rig().use { rig ->
        rig.activate(); rig.backing.databaseSchemaVersion = 3
        failure { rig.reopen() }
        assertTrue(rig.backing.replayRows.containsKey(NativeReplayAccounting.KEY))
    }
    @Test fun `migration ambiguous commit and rollback retain single canonical initial row`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) Rig().use { rig ->
            rig.owner.ensurePreparedReplayStorage().get()
            rig.backing.ambiguousNextCommit = outcome
            rig.owner.ensureNativeReplayAccounting().get()
            assertEquals(5, rig.backing.databaseSchemaVersion)
            assertEquals(0L, rig.state().nextReplayOrdinal)
            assertNull(rig.state().session)
        }
    }
    @Test fun `no source cannot establish sample or start`() = Rig().use { rig ->
        rig.owner.ensurePreparedReplayStorage().get(); rig.owner.ensureNativeReplayAccounting().get()
        assertNull(rig.owner.observeNativeReplaySession().get()); assertNull(rig.state().session)
    }
    @Test fun `false draw and zero duration remain restrictive when policy rises`() {
        for (zeroSample in listOf(true, false)) Rig().use { rig ->
            rig.configure { it.getJSONObject("privacy").getJSONObject("replay").put(if (zeroSample) "sampleRate" else "maximumDurationSeconds", 0) }
            rig.activate(); val initial = rig.observe()
            assertNull(rig.owner.beginNativeReplayAccounting(initial).get())
            rig.renew { it.getJSONObject("privacy").getJSONObject("replay").put("sampleRate", 1).put("maximumDurationSeconds", 120) }
            val later = rig.observe()
            assertNull(rig.owner.beginNativeReplayAccounting(later).get())
            assertEquals(initial.session.originalSelected, later.session.originalSelected)
            assertEquals(minOf(initial.session.maximumDurationSeconds, 120), later.session.maximumDurationSeconds)
            assertEquals(0L, rig.state().nextReplayOrdinal)
        }
    }
    @Test fun `observation allocates nothing and begin stop preserves first window and new replay id`() = Rig().use { rig ->
        rig.activate(); val observation = rig.observe()
        assertEquals(0L, rig.state().nextReplayOrdinal); assertNull(observation.session.firstStartAt)
        val first = checkNotNull(rig.owner.beginNativeReplayAccounting(observation).get())
        rig.clock.nanos += 1_000_000_001L
        assertTrue(rig.owner.stopNativeReplayAccounting(first).get())
        assertEquals(1_000_001L, rig.state().session!!.elapsedFloorMicroseconds)
        val second = checkNotNull(rig.owner.beginNativeReplayAccounting(rig.observe()).get())
        assertNotEquals(first.replayId, second.replayId)
        assertEquals(first.firstStartAt, second.firstStartAt)
        assertEquals(2L, rig.state().nextReplayOrdinal)
    }
    @Test fun `ordinary append and exact acknowledgement do not invalidate original observation`() = Rig().use { rig ->
        rig.activate(); val observed = rig.observe()
        val record = (rig.owner.capture(rig.event()).get() as RuntimeCaptureResult.Accepted).record
        rig.owner.acknowledge(RuntimeAcknowledgement(record.streamId, listOf(RuntimeRecordReference(record.sequence, record.kind, record.recordId)))).get()
        assertNotNull(rig.owner.beginNativeReplayAccounting(observed).get())
    }
    @Test fun `original observation cannot adopt renewed source or changed context session identity`() {
        for (mode in 0..3) Rig().use { rig ->
            rig.activate(); val observed = rig.observe()
            when (mode) {
                0 -> rig.renew { }
                1 -> rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("plan" to "changed"), rig.now())).get()
                2 -> rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get()
                3 -> rig.owner.markBackgrounded(rig.now()).get()
            }
            assertNull(rig.owner.beginNativeReplayAccounting(observed).get())
            assertEquals(0L, rig.state().nextReplayOrdinal)
        }
    }
    @Test fun `same owner unknown commit keeps original epoch and never marks interrupted`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) Rig().use { rig ->
            rig.activate(); val observation = rig.observe(); rig.backing.ambiguousNextCommit = outcome
            val receipt = checkNotNull(rig.owner.beginNativeReplayAccounting(observation).get())
            assertEquals(receipt.epoch, rig.state().session!!.activeEpoch)
            assertFalse(rig.state().session!!.interrupted)
            assertEquals(1L, rig.state().nextReplayOrdinal)
            assertTrue(rig.owner.stopNativeReplayAccounting(receipt).get())
        }
    }
    @Test fun `clean close cannot prove first start monotonic continuity across new owner`() = Rig().use { rig ->
        rig.activate(); val first = checkNotNull(rig.owner.beginNativeReplayAccounting(rig.observe()).get())
        assertTrue(rig.owner.stopNativeReplayAccounting(first).get())
        rig.reopen(); rig.publish()
        val observation = rig.observe()
        assertTrue(observation.session.interrupted)
        assertNull(rig.owner.beginNativeReplayAccounting(observation).get())
        assertEquals(1L, rig.state().nextReplayOrdinal)
    }
    @Test fun `unstarted session survives clean reopen without interruption`() = Rig().use { rig ->
        rig.activate(); rig.observe(); rig.reopen(); rig.publish()
        assertNotNull(rig.owner.beginNativeReplayAccounting(rig.observe()).get())
    }
    @Test fun `wall lead is not rebased into later monotonic catchup`() = Rig().use { rig ->
        rig.activate(); val start = checkNotNull(rig.owner.beginNativeReplayAccounting(rig.observe()).get())
        rig.clock.wall += 1_000; rig.observe()
        assertEquals(1_000_000L, rig.state().session!!.elapsedFloorMicroseconds)
        rig.clock.nanos += 1_000_000_000L; rig.observe()
        assertEquals(1_000_000L, rig.state().session!!.elapsedFloorMicroseconds)
        rig.clock.nanos += 1; rig.observe()
        assertEquals(1_000_001L, rig.state().session!!.elapsedFloorMicroseconds)
        assertTrue(rig.owner.stopNativeReplayAccounting(start).get())
    }
    @Test fun `original nanosecond anchor rounds one interval rather than each observation`() = Rig().use { rig ->
        rig.activate(); rig.owner.beginNativeReplayAccounting(rig.observe()).get()
        repeat(100) { rig.clock.nanos += 1; rig.observe() }
        assertEquals(1L, rig.state().session!!.elapsedFloorMicroseconds)
        rig.clock.nanos += 900; rig.observe(); assertEquals(1L, rig.state().session!!.elapsedFloorMicroseconds)
        rig.clock.nanos += 1; rig.observe(); assertEquals(2L, rig.state().session!!.elapsedFloorMicroseconds)
    }
    @Test fun `withdrawal after committed begin settles original receipt before returning denial`() = Rig().use { rig ->
        rig.activate(); val observation = rig.observe()
        rig.afterWriteCommit = { rig.driver.onBackground() }
        assertNull(rig.owner.beginNativeReplayAccounting(observation).get())
        assertEquals(1L, rig.state().nextReplayOrdinal)
        assertNull(rig.state().session!!.activeEpoch)
    }
    @Test fun `withdrawal while writing rolls back without allocating an epoch`() = Rig().use { rig ->
        rig.activate(); val observation = rig.observe()
        rig.onWrite = { rig.driver.onBackground() }
        failure { rig.owner.beginNativeReplayAccounting(observation).get() }
        assertEquals(0L, rig.state().nextReplayOrdinal); assertNull(rig.state().session!!.activeEpoch)
    }
    @Test fun `caller cancellation cannot interrupt or lose accounting handoff`() = Rig().use { rig ->
        rig.activate(); val observation = rig.observe()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        rig.onWrite = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val future = rig.owner.beginNativeReplayAccounting(observation)
        check(entered.await(3, TimeUnit.SECONDS))
        try { assertFalse(future.cancel(true)) } finally { release.countDown() }
        val receipt = checkNotNull(future.get(3, TimeUnit.SECONDS))
        assertTrue(rig.owner.stopNativeReplayAccounting(receipt).get())
    }
    @Test fun `native close cannot cancel original settlement or publish start after close intent`() = Rig().use { rig ->
        rig.activate(); val observation = rig.observe()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        rig.onWrite = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val started = rig.owner.beginNativeReplayAccounting(observation)
        check(entered.await(3, TimeUnit.SECONDS))
        val closing = rig.owner.closeAsync()
        try { assertFalse(closing.cancel(true)) } finally { release.countDown() }
        try { assertNull(started.get(3, TimeUnit.SECONDS)) } catch (_: java.util.concurrent.ExecutionException) { }
        closing.get(3, TimeUnit.SECONDS)
        val reopened = rig.openSame().get(3, TimeUnit.SECONDS)
        reopened.closeAsync().get(3, TimeUnit.SECONDS)
        assertNull(rig.state().session!!.activeEpoch)
    }
    @Test fun `unrelated acknowledgement poison retains outstanding native epoch on close`() = Rig().use { rig ->
        rig.activate(); rig.owner.beginNativeReplayAccounting(rig.observe()).get()
        val record = (rig.owner.capture(rig.event()).get() as RuntimeCaptureResult.Accepted).record
        rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE
        failure { rig.owner.acknowledge(RuntimeAcknowledgement(record.streamId,
            listOf(RuntimeRecordReference(record.sequence, record.kind, record.recordId)))).get() }
        failure { rig.owner.closeAsync().get() }
        failure { rig.openSame().get() }
        assertNotNull(rig.state().session!!.activeEpoch)
    }
    @Test fun `close intent at final source consumption cannot publish native observation or receipt`() {
        for (begin in listOf(false, true)) Rig().use { rig ->
            rig.activate(); val observation = if (begin) rig.observe() else null
            var closing: java.util.concurrent.Future<Unit>? = null
            rig.afterWriteCommit = { rig.clock.onRead = { closing = rig.owner.closeAsync() } }
            if (begin) assertNull(rig.owner.beginNativeReplayAccounting(checkNotNull(observation)).get())
            else assertNull(rig.owner.observeNativeReplaySession().get())
            checkNotNull(closing).get(3, TimeUnit.SECONDS)
            val reopened = rig.openSame().get(3, TimeUnit.SECONDS); reopened.closeAsync().get(3, TimeUnit.SECONDS)
            assertNull(rig.state().session!!.activeEpoch)
        }
    }
    @Test fun `foreign stop receipt observes no clocks and cannot alter matching ledger`() {
        Rig().use { first -> Rig().use { second ->
            first.activate(); second.activate()
            val receipt = checkNotNull(first.owner.beginNativeReplayAccounting(first.observe()).get())
            second.clock.throwOnRead = true
            assertFalse(second.owner.stopNativeReplayAccounting(receipt).get())
            second.clock.throwOnRead = false
            assertNull(second.state().session)
        } }
    }
    @Test fun `unresolved exact stop read failure retains installation ownership after close`() = Rig().use { rig ->
        rig.activate(); rig.owner.beginNativeReplayAccounting(rig.observe()).get()
        rig.onNativeRead = { throw java.io.IOException("test initial metadata read failure") }
        failure { rig.owner.closeAsync().get() }
        failure { rig.openSame().get() }
        assertNotNull(rig.state().session!!.activeEpoch)
    }
    @Test fun `ordinal exhaustion never wraps or resets history`() = Rig().use { rig ->
        rig.activate(); rig.observe()
        rig.replace(rig.state().copy(nextReplayOrdinal = MAX_REPLAY_SAFE_INTEGER))
        failure { rig.owner.beginNativeReplayAccounting(rig.observe()).get() }
        assertEquals(MAX_REPLAY_SAFE_INTEGER, rig.state().nextReplayOrdinal)
        assertNull(rig.state().session!!.firstStartAt)
    }

    @Test fun `current lower cap is retained while later higher cap cannot refill`() = Rig().use { rig ->
        rig.activate(); val first = checkNotNull(rig.owner.beginNativeReplayAccounting(rig.observe()).get())
        rig.clock.nanos += 2_000_000_000L
        assertTrue(rig.owner.stopNativeReplayAccounting(first).get())
        rig.renew { it.getJSONObject("privacy").getJSONObject("replay").put("maximumDurationSeconds", 1) }
        val lower = rig.observe()
        assertEquals(0L, lower.session.remainingMicroseconds)
        assertNull(rig.owner.beginNativeReplayAccounting(lower).get())
    }
    @Test fun `expired source cannot establish another observation on stalled wall`() = Rig().use { rig ->
        rig.activate(); rig.observe(); val before = rig.row().payload.copyOf()
        rig.clock.nanos += 600_000_000_000L
        assertNull(rig.owner.observeNativeReplaySession().get())
        assertArrayEquals(before, rig.row().payload)
    }
    @Test fun `monotonic rollback denial is sticky only in its original durable session`() = Rig().use { rig ->
        rig.activate(); val receipt = checkNotNull(rig.owner.beginNativeReplayAccounting(rig.observe()).get())
        rig.clock.nanos += 1_000_000L; rig.observe(); rig.ownerNanos = rig.clock.nanos - 1
        rig.observe(); assertTrue(rig.state().session!!.clockDenied)
        assertTrue(rig.owner.stopNativeReplayAccounting(receipt).get())
        rig.ownerNanos = null; rig.clock.nanos += 1_000_000L
        assertNull(rig.owner.beginNativeReplayAccounting(rig.observe()).get())
        rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get(); rig.publish()
        assertTrue(rig.owner.capture(rig.event()).get() is RuntimeCaptureResult.Accepted)
        val fresh = rig.observe()
        assertNotEquals(receipt.key, fresh.session.key); assertFalse(fresh.session.clockDenied)
        assertNotNull(rig.owner.beginNativeReplayAccounting(fresh).get())
    }
    @Test fun `retained replay id collision rejects without consuming ordinal`() = Rig().use { rig ->
        rig.activate(); val observation = rig.observe(); val predicted = rig.state().allocateReplayId().replayId
        val request = PreparedReplayRequest.parse(ReplayFixtures.bytes { it.put("replayId", predicted) }, ReplayFixtures.GENERATION)
        val config = V1ConfigJson.parseConfig(rig.body)
        rig.backing.connection().use { db -> db.transaction { tx ->
            assertTrue(ReplayQueueStore.reconcile(tx, config, RuntimeSiteNamespace.digest(KEY), rig.clock.wall, false,
                setOf(request.transport), setOf(ReplayFixtures.GENERATION), ReplayMaskingRetention { _, _ -> true }))
            assertTrue(ReplayQueueStore.append(tx, request, ReplayFixtures.profile(), RuntimeSiteNamespace.digest(KEY),
                checkNotNull(config.siteId), ReplayFixtures.GENERATION, rig.clock.wall, MAX_RUNTIME_QUEUE_RECORDS,
                MAX_RUNTIME_QUEUE_BYTES, MAX_REPLAY_REQUEST_BYTES) { rig.clock.wall } is ReplayAppendResult.Stored)
        } }
        failure { rig.owner.beginNativeReplayAccounting(observation).get() }
        assertEquals(0L, rig.state().nextReplayOrdinal)
        assertNull(rig.state().session!!.firstStartAt)
    }
    @Test fun `unknown stop commit reconciles before releasing exact epoch`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) Rig().use { rig ->
            rig.activate(); val receipt = checkNotNull(rig.owner.beginNativeReplayAccounting(rig.observe()).get())
            rig.clock.nanos += 1_001; rig.backing.ambiguousNextCommit = outcome
            assertTrue(rig.owner.stopNativeReplayAccounting(receipt).get())
            assertNull(rig.state().session!!.activeEpoch)
            assertEquals(2L, rig.state().session!!.elapsedFloorMicroseconds)
            val before = rig.row().payload.copyOf(); rig.clock.throwOnRead = true
            assertFalse(rig.owner.stopNativeReplayAccounting(receipt).get()); rig.clock.throwOnRead = false
            assertArrayEquals(before, rig.row().payload)
        }
    }

    private class Rig : AutoCloseable {
        val clock = Clock(); val worker = Worker(); val gate = V2ConfigAuthorityGate()
        var body = ReplayFixtures.resource("contracts/v2/fixtures/config-enabled.json")
        val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, Scheduler(), worker)
        val backing = FakeRuntimeQueueBacking()
        val ownership = "native-accounting-" + UUID.randomUUID()
        @Volatile var ownerNanos: Long? = null
        @Volatile var onWrite: (() -> Unit)? = null
        @Volatile var afterWriteCommit: (() -> Unit)? = null
        @Volatile var onNativeRead: (() -> Unit)? = null
        var owner = openSame().get().also { it.bindConfigurationGate(gate).get() }
        fun openSame() = RuntimeQueueOwner.open(ownership, RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
            databaseFactory = {
                val db = backing.connection()
                object : RuntimeQueueDatabase by db {
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
                        var wrote = false
                        val result = db.transaction { tx -> block(object : RuntimeQueueTransaction by tx {
                            override fun readReplayRow(key: String): RuntimeReplayStoredRow? {
                                if (key == NativeReplayAccounting.KEY) onNativeRead?.also { onNativeRead = null }?.invoke()
                                return tx.readReplayRow(key)
                            }
                            override fun putReplayRow(row: RuntimeReplayStoredRow) {
                                tx.putReplayRow(row)
                                if (row.key == NativeReplayAccounting.KEY) { wrote = true; onWrite?.also { onWrite = null }?.invoke() }
                            }
                        }) }
                        if (wrote) afterWriteCommit?.also { afterWriteCommit = null }?.invoke()
                        return result
                    }
                }
            }, legacyStateLoader = { initial() }, trustedSiteKey = KEY,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = clock.wallNowEpochMillis()
                override fun elapsedRealtimeNanos() = ownerNanos ?: clock.monotonicNowNanos()
            })
        fun configure(change: (JSONObject) -> Unit) { val json = JSONObject(body); change(json); body = json.toString() }
        fun activate() {
            configure { it.getJSONObject("privacy").getJSONObject("replay").let { policy ->
                if (policy.getDouble("sampleRate") != 0.0) policy.put("sampleRate", 1.0)
            } }
            owner.ensurePreparedReplayStorage().get(); owner.ensureNativeReplayAccounting().get()
            driver.start(); worker.runNext(); publish()
        }
        fun privacy(): String {
            val config = V1ConfigJson.parseConfig(body)
            return PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(config.privacy),
                checkNotNull(config.features), checkNotNull(config.replayCapabilities), owner.snapshot().get().state.identity,
                false, now(), PrivacyReplayInput(false, false, false, 0, null))))
        }
        fun publish() { assertTrue(owner.submitCaptureAuthority(body, privacy()).get().toString(), owner.captureAuthorityForTesting().get() is RuntimeCaptureAuthorityState.Authorized) }
        fun renew(change: (JSONObject) -> Unit) {
            configure { it.put("issuedAt", "2026-08-05T00:01:05.000Z").put("revision", "renewed"); change(it) }
            driver.onBackground(); driver.onForeground(); worker.runNext(); publish()
        }
        fun observe() = checkNotNull(owner.observeNativeReplaySession().get())
        fun row() = checkNotNull(backing.replayRows[NativeReplayAccounting.KEY])
        fun state() = NativeReplayAccounting.read(row())
        fun replace(state: NativeReplaySessionState) { backing.replayRows[NativeReplayAccounting.KEY] = NativeReplayAccounting.row(state) }
        fun reopen() { owner.closeAsync().get(); owner = openSame().get().also { it.bindConfigurationGate(gate).get() } }
        fun now() = RuntimeWallTimestamps.rfc3339(clock.wall)
        fun event() = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "activity", now(), emptyMap(), StandaloneRuntime.defaultVersions())
        override fun close() { runCatching { owner.closeAsync().get() }; driver.close() }
    }
    private class Clock : V2ConfigClock {
        var wall = Instant.parse("2026-08-05T00:01:06Z").toEpochMilli(); var nanos = 1L
        @Volatile var onRead: (() -> Unit)? = null
        var throwOnRead = false
        override fun wallNowEpochMillis(): Long { check(!throwOnRead); onRead?.also { onRead = null }?.invoke(); return wall }
        override fun monotonicNowNanos(): Long { check(!throwOnRead); return nanos }
    }
    private class Worker : V2ConfigLifecycleWorker {
        val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.add(task) }
        override fun interruptCurrent() = Unit
        override fun close() = Unit
        fun runNext() = tasks.removeFirst().invoke()
    }
    private class Scheduler : V2ConfigLifecycleScheduler {
        override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
        override fun close() = Unit
    }
    private companion object {
        const val KEY = "elu_pk_live_AAAAAAAAAAAAAAAAAAAAAAAAAA"
        fun failure(operation: () -> Unit) {
            try { operation(); fail("Expected bounded failure") } catch (error: java.util.concurrent.ExecutionException) { }
        }
        fun initial(): PersistedCoreState {
            val chunk = ReplayFixtures.request().getJSONObject("chunk"); val identity = chunk.getJSONObject("identity")
            return PersistedCoreState(identity = IdentityState(revision = identity.getLong("revision"), contextRevision = chunk.getLong("contextRevision"),
                anonymousId = identity.getString("anonymousId"), userId = identity.getString("userId"), groups = emptyMap(), superProperties = emptyMap(),
                session = SessionState(chunk.getString("sessionId"), "2026-08-05T00:01:00.000Z", "2026-08-05T00:01:05.000Z", 1800,
                    lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null), optedOut = false, updatedAt = "2026-08-05T00:01:05.000Z"),
                stream = StreamState(streamId = "stream_native", nextSequence = 0), flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
        }
    }
}
