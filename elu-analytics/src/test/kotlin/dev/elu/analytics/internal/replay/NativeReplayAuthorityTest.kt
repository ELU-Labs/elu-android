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

class NativeReplayAuthorityTest {
    private fun prepared(rig: Rig): NativeReplayPreparedProjection {
        val input = checkNotNull(rig.owner.observeNativeReplayProjection().get())
        val proof = NativeReplayCapabilities(setOf(nativePair), setOf(checkNotNull(input.config.replayCapabilities?.replayProtocolGeneration)))
        val privacy = checkNotNull(PrivacyStateProjector.projectNative(input, proof, false))
        return checkNotNull(rig.owner.prepareNativeReplayProjection(input, privacy).get())
    }
    @Test fun `native handoff installs original full privacy and exact immutable identity`() = Rig().use { rig ->
        rig.activate()
        val prepared = prepared(rig)
        assertTrue(prepared.isCurrent())
        val full = V1ConfigJson.parseEffectivePrivacy(prepared.privacy.body)
        assertTrue(full.replayAllowed)
        assertEquals(full.effectivePolicyHash, prepared.input.observation.capture.decisionHash)
        assertEquals(prepared.input.observation.session.remainingWholeSeconds, full.replayBudgetRemainingSeconds)
        assertEquals(prepared.input.identity.contextRevision, full.contextRevision)
        val copy = prepared.input.identity
        assertNotSame(copy, prepared.input.identity)
        val begun = checkNotNull(rig.owner.beginNativeReplayAuthority(prepared).get())
        assertTrue(begun.guard.isCurrent())
        assertEquals(begun.receipt.epoch, rig.state().session!!.activeEpoch)
        rig.owner.stopNativeReplayAccounting(begun.receipt).get(); Unit
    }
    @Test fun `default pair-only wrong generation and unresolved block deny before start`() {
        Rig().use { rig ->
            rig.activate(); val input = checkNotNull(rig.owner.observeNativeReplayProjection().get())
            for (caps in listOf(NativeReplayCapabilities(), NativeReplayCapabilities(setOf(nativePair)),
                NativeReplayCapabilities(setOf(nativePair), setOf("unproven")))) {
                assertNull(PrivacyStateProjector.projectNative(input, caps, false))
            }
            assertEquals(0L, rig.state().nextReplayOrdinal)
        }
    }
    @Test fun `same session append and ACK preserve prepared original authority`() = Rig().use { rig ->
        rig.activate(); val prepared = prepared(rig)
        val record = (rig.owner.capture(rig.event()).get() as RuntimeCaptureResult.Accepted).record
        rig.owner.acknowledge(RuntimeAcknowledgement(record.streamId,
            listOf(RuntimeRecordReference(record.sequence, record.kind, record.recordId)))).get()
        assertTrue(prepared.isCurrent())
        assertNotNull(rig.owner.beginNativeReplayAuthority(prepared).get())
    }
    @Test fun `original projection cannot adopt source context session or close changes`() {
        for (mode in 0..3) Rig().use { rig ->
            rig.activate(); val input = checkNotNull(rig.owner.observeNativeReplayProjection().get())
            val proof = NativeReplayCapabilities(setOf(nativePair), setOf(checkNotNull(input.config.replayCapabilities?.replayProtocolGeneration)))
            val projection = checkNotNull(PrivacyStateProjector.projectNative(input, proof, false))
            when (mode) {
                0 -> rig.renew { }
                1 -> rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("plan" to "changed"), rig.now())).get()
                2 -> rig.owner.markBackgrounded(rig.now()).get()
                3 -> rig.owner.closeAsync().get()
            }
            assertFalse(input.isCurrent())
            if (mode != 3) assertNull(rig.owner.prepareNativeReplayProjection(input, projection).get())
            assertEquals(0L, rig.state().nextReplayOrdinal)
        }
    }
    @Test fun `retained guard rollback is persisted on close even before start`() = Rig().use { rig ->
        rig.activate(); val prepared = prepared(rig)
        rig.ownerNanos = rig.clock.nanos + 10
        assertTrue(prepared.isCurrent())
        rig.ownerNanos = rig.clock.nanos + 9
        assertFalse(prepared.isCurrent())
        rig.owner.closeAsync().get()
        assertTrue(rig.state().session!!.clockDenied)
        assertEquals(0L, rig.state().nextReplayOrdinal)
    }
    @Test fun `flushed exact clock denial cannot revive retained original projection`() = Rig().use { rig ->
        rig.activate(); val prepared = prepared(rig)
        rig.ownerNanos = rig.clock.nanos + 10
        assertTrue(prepared.isCurrent())
        rig.ownerNanos = rig.clock.nanos + 9
        assertFalse(prepared.isCurrent())
        rig.owner.flushNativeReplayClockDenial().get()
        assertTrue(rig.state().session!!.clockDenied)
        rig.ownerNanos = rig.clock.nanos + 11
        assertFalse("Durable denial must permanently withdraw the original retained projection", prepared.isCurrent())
        assertNull(rig.owner.beginNativeReplayAuthority(prepared).get())
        assertEquals(0L, rig.state().nextReplayOrdinal)
    }
    @Test fun `resolved old denial preserves independently current replacement scope`() = Rig().use { rig ->
        rig.activate(); val prepared = prepared(rig)
        var nanos = rig.clock.nanos
        val scope = NativeReplayScope(object : RuntimeCaptureClock {
            override fun wallNowEpochMillis() = rig.clock.wall
            override fun elapsedRealtimeNanos() = nanos
        })
        val input = prepared.input; val observation = input.observation
        scope.publish(input.identity, observation.capture)
        val original = checkNotNull(scope.issue(observation.source, observation.capture, observation.session))
        nanos += 10; assertTrue(original.isCurrent())
        nanos -= 1; assertFalse(original.isCurrent())
        val identity = input.identity.copy(session = checkNotNull(input.identity.session).copy(id = "replacement-session"))
        val replacementKey = observation.session.key.copy(sessionId = checkNotNull(identity.session).id)
        val replacementSession = checkNotNull(rig.state().observe(replacementKey, 1.0,
            observation.session.maximumDurationSeconds, rig.now(), null, null).session)
        scope.publish(identity, observation.capture)
        nanos += 2
        val replacement = checkNotNull(scope.issue(observation.source, observation.capture, replacementSession))
        assertTrue(replacement.isCurrent())
        scope.resolved(observation.session.key)
        assertTrue(replacement.isCurrent())
        assertFalse(original.isCurrent())
    }
    @Test fun `flushed session denial does not deny a new actual queue session`() = Rig().use { rig ->
        rig.activate(); val original = prepared(rig)
        rig.ownerNanos = rig.clock.nanos + 10; assertTrue(original.isCurrent())
        rig.ownerNanos = rig.clock.nanos + 9; assertFalse(original.isCurrent())
        rig.owner.flushNativeReplayClockDenial().get()
        rig.ownerNanos = rig.clock.nanos + 11
        rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get(); rig.publish()
        assertTrue(rig.owner.capture(rig.event()).get() is RuntimeCaptureResult.Accepted)
        val replacement = prepared(rig)
        assertNotEquals(original.input.observation.session.key, replacement.input.observation.session.key)
        assertFalse(rig.state().session!!.clockDenied)
        assertTrue(replacement.isCurrent())
        assertFalse(original.isCurrent())
    }
    @Test fun `guard denial initial read failure quarantines installation`() = Rig().use { rig ->
        rig.activate(); val prepared = prepared(rig)
        rig.ownerNanos = rig.clock.nanos + 10; assertTrue(prepared.isCurrent())
        rig.ownerNanos = rig.clock.nanos + 9; assertFalse(prepared.isCurrent())
        rig.onNativeRead = { throw java.io.IOException("initial native denial read") }
        failure { rig.owner.closeAsync().get() }
        failure { rig.openSame().get() }
    }
    @Test fun `original native budget expires on continuous clock while wall stalls`() = Rig().use { rig ->
        rig.configure { it.getJSONObject("privacy").getJSONObject("replay").put("maximumDurationSeconds", 1) }
        rig.activate(); val started = checkNotNull(rig.owner.beginNativeReplayAuthority(prepared(rig)).get())
        rig.clock.nanos += 999_999_000L; assertTrue(started.guard.isCurrent())
        rig.clock.nanos += 1_000L; assertFalse(started.guard.isCurrent())
        rig.owner.stopNativeReplayAccounting(started.receipt).get()
        assertEquals(1_000_000L, rig.state().session!!.elapsedFloorMicroseconds)
    }
    @Test fun `prepared guard cannot survive queued local intent before worker runs`() = Rig().use { rig ->
        rig.activate(); val prepared = prepared(rig)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        rig.onNativeRead = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val read = rig.owner.observeNativeReplaySession()
        check(entered.await(3, TimeUnit.SECONDS))
        val change = rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("x" to 1), rig.now()))
        try { assertFalse(prepared.isCurrent()) } finally { release.countDown() }
        read.get(); change.get(); Unit
    }
    @Test fun `permission authority checks selection and keeps exact start stop results despite cancellation`() = Rig().use { rig ->
        rig.activate()
        val platform = TestSelectionAccess(); val facts = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); facts.resumed(activity)
        val selection = checkNotNull(facts.select(activity, root).get())
        val capabilities = NativeReplayCapabilities(setOf(nativePair), setOf(ReplayFixtures.GENERATION))
        val authority = NativeReplayAuthority(rig.owner, capabilities, { false })
        try {
            val prepared = checkNotNull(authority.prepare(selection).get())
            val permit = checkNotNull(authority.start(prepared).get())
            assertTrue(permit.isCurrent())
            facts.withdrawing(activity)
            assertFalse(permit.isCurrent())
            val stop = authority.stop(); assertFalse(stop.cancel(true))
            assertEquals(NativeReplayAuthorityStop.SETTLED, stop.get())
            assertNull(rig.state().session!!.activeEpoch)
        } finally { authority.close(); selection.close() }
    }
    @Test fun `reentrant close during original owner clock check cannot return current`() = Rig().use { rig ->
        rig.activate(); val prepared = prepared(rig)
        var closed: java.util.concurrent.Future<Unit>? = null
        rig.onOwnerClock = { closed = rig.owner.closeAsync() }
        assertFalse(prepared.isCurrent())
        checkNotNull(closed).get(3, TimeUnit.SECONDS)
        assertFalse(prepared.isCurrent())
    }
    @Test fun `close joins held prepare without publishing a late original authority`() = Rig().use { rig ->
        rig.activate(); val platform = TestSelectionAccess(); val facts = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); facts.resumed(activity)
        val selection = checkNotNull(facts.select(activity, root).get())
        val entered = CountDownLatch(1); var pending: (() -> Unit)? = null
        platform.hold = { pending = it; entered.countDown() }
        val authority = NativeReplayAuthority(rig.owner, NativeReplayCapabilities(setOf(nativePair), setOf(ReplayFixtures.GENERATION)), { false })
        try {
            val prepare = authority.prepare(selection); assertTrue(entered.await(3, TimeUnit.SECONDS))
            val closing = authority.closeAndWait(); assertFalse(closing.isDone); assertFalse(closing.cancel(true))
            platform.hold = null; checkNotNull(pending).also { pending = null }.invoke()
            assertNull(prepare.get(3, TimeUnit.SECONDS)); assertEquals(NativeReplayAuthorityStop.SETTLED, closing.get(3, TimeUnit.SECONDS))
            assertSame(closing, authority.closeAndWait()); assertEquals(0L, rig.state().nextReplayOrdinal)
        } finally { platform.hold = null; pending?.invoke(); authority.close(); selection.close() }
    }

    private val nativePair = V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)
    private class Rig : AutoCloseable {
        val clock = Clock(); val worker = Worker(); val gate = V2ConfigAuthorityGate()
        var body = ReplayFixtures.resource("contracts/v2/fixtures/config-enabled.json")
        val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, Scheduler(), worker)
        val backing = FakeRuntimeQueueBacking()
        val ownership = "native-accounting-" + UUID.randomUUID()
        @Volatile var ownerNanos: Long? = null
        @Volatile var onOwnerClock: (() -> Unit)? = null
        @Volatile var onWrite: (() -> Unit)? = null
        @Volatile var afterWriteCommit: (() -> Unit)? = null
        @Volatile var onNativeRead: (() -> Unit)? = null
        var owner = openSame().get().also { it.bindConfigurationGate(gate).get() }
        fun openSame() = RuntimeQueueOwner.open(ownership, RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
            readbackProvenReplayTransports = setOf(V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)),
            supportedReplayProtocolGenerations = setOf(ReplayFixtures.GENERATION),
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
                override fun wallNowEpochMillis(): Long {
                    onOwnerClock?.also { onOwnerClock = null }?.invoke()
                    return clock.wallNowEpochMillis()
                }
                override fun elapsedRealtimeNanos() = ownerNanos ?: clock.monotonicNowNanos()
            })
        fun configure(change: (JSONObject) -> Unit) { val json = JSONObject(body); change(json); body = json.toString() }
        fun activate() {
            configure { it.getJSONObject("capabilities").getJSONObject("replay").getJSONArray("transports").put(
                JSONObject().put("codec", "elu-native-wireframe-v1").put("compression", "gzip")) }
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
