package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import java.time.Instant
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual original source/gate/owner and SQLite-shaped transactions; no collector permission is fabricated. */
class NativeRasterQueueTest {
    @Test fun `source detected conflict is durable before acknowledgment and plain v2 restart cannot bypass it`() {
        val backing = FakeRuntimeQueueBacking(); val name = "raster-" + UUID.randomUUID()
        RasterQueueRig(backing, name).use { rig ->
            rig.activate(); assertTrue(rig.owner.ensureNativeRasterStorage().get())
            rig.body = JSONObject(rig.body).apply { remove("raster") }.toString()
            rig.refresh()
            assertNotNull(rig.gate.rasterDenial()); assertNull(rig.gate.snapshot()?.body)
            assertFalse(rig.owner.authorizeCurrentDelivery(null) { false }.get())
            assertNull(rig.gate.rasterDenial()); assertNull(rig.source.rasterConflictReceipt())
            assertTrue(rig.ledger().conflicted)
        }
        RasterQueueRig(backing, name, V2ConfigFormat.V2).use { rig ->
            rig.start()
            assertFalse(rig.owner.authorizeCurrentDelivery(rig.base()) { false }.get())
            assertTrue(rig.owner.applyFeatureFlagConfiguration(rig.base(), rig.clock.wall).get() is V1FlagAuthorizationResolution.Restricted)
            assertTrue(rig.ledger().conflicted)
            rig.body = JSONObject(rig.body).put("issuedAt", "2026-08-05T00:00:30Z").toString(); rig.refresh()
            rig.publish()
            assertTrue(rig.owner.authorizeCurrentDelivery(rig.base()) { false }.get())
            assertTrue("Newer plain v2 does not erase raster poison", rig.ledger().conflicted)
        }
        RasterQueueRig(backing, name).use { rig ->
            rig.start(); assertFalse(rig.owner.authorizeCurrentDelivery(rig.base()) { false }.get())
            assertTrue(rig.ledger().conflicted)
        }
    }

    @Test fun `restriction survives known rollback and exact unknown committed outcome`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) RasterQueueRig().use { rig ->
            rig.activate(); assertTrue(rig.owner.ensureNativeRasterStorage().get())
            rig.body = JSONObject(rig.body).apply { remove("raster") }.toString(); rig.refresh()
            rig.backing.ambiguousNextCommit = outcome
            assertFalse(rig.owner.authorizeCurrentDelivery(null) { false }.get())
            assertTrue(rig.ledger().conflicted); assertNull(rig.gate.rasterDenial())
        }
    }

    @Test fun `unknown divergent conflict commit retains denial and original owner quarantine`() = RasterQueueRig().use { rig ->
        rig.activate(); assertTrue(rig.owner.ensureNativeRasterStorage().get())
        rig.body = JSONObject(rig.body).apply { remove("raster") }.toString(); rig.refresh()
        val receipt = checkNotNull(rig.gate.rasterDenial())
        rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE
        assertThrows(Exception::class.java) { rig.owner.authorizeCurrentDelivery(null) { false }.get() }
        assertSame(receipt, rig.gate.rasterDenial())
        assertThrows(Exception::class.java) { rig.owner.snapshot().get() }
        rig.expectQuarantinedClose = true
    }

    @Test fun `close commits source conflict before any raster storage and plain v2 reopen remains denied`() {
        val backing = FakeRuntimeQueueBacking(); val name = "raster-close-" + UUID.randomUUID()
        val rig = RasterQueueRig(backing, name)
        try {
            rig.start(); rig.publish()
            assertFalse(backing.connection().use { it.transaction { tx -> tx.replaySchemaPresent() } })
            rig.body = JSONObject(rig.body).apply { remove("raster") }.toString()
            // Original validation completed, but no lifecycle notification or queue task delivered it yet.
            assertTrue(rig.source.refresh() is V2ConfigSourceResult.Unavailable)
            rig.gate.close()
            assertNotNull(rig.gate.rasterDenial()); assertNull(rig.gate.snapshot())
        } finally { rig.close() }
        assertNull(rig.source.rasterConflictReceipt()); assertTrue(rig.ledger().conflicted)
        assertNull(NativeReplayAccounting.read(checkNotNull(backing.replayRows[NativeReplayAccounting.KEY])).session)
        assertEquals(0L, ReplayStoredState.decode(checkNotNull(backing.replayRows["state"])).count)
        RasterQueueRig(backing, name, V2ConfigFormat.V2).use { reopened ->
            reopened.start()
            assertFalse(reopened.owner.authorizeCurrentDelivery(reopened.base()) { false }.get())
            assertTrue(reopened.ledger().conflicted)
        }
    }

    @Test fun `denial only migration reconciles exact original committed and rolled back outcomes`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) RasterQueueRig().use { rig ->
            rig.start(); rig.publish()
            rig.body = JSONObject(rig.body).apply { remove("raster") }.toString(); rig.refresh()
            rig.backing.ambiguousNextCommit = outcome
            assertFalse(rig.owner.authorizeCurrentDelivery(null) { false }.get())
            assertTrue(rig.ledger().conflicted); assertNull(rig.source.rasterConflictReceipt())
            assertNull(NativeReplayAccounting.read(checkNotNull(rig.backing.replayRows[NativeReplayAccounting.KEY])).session)
        }
    }

    @Test fun `failed denial only migration makes original close fail and keeps original occupancy`() {
        val backing = FakeRuntimeQueueBacking(); val name = "raster-undurable-" + UUID.randomUUID()
        val rig = RasterQueueRig(backing, name)
        try {
            rig.start(); rig.publish()
            rig.body = JSONObject(rig.body).apply { remove("raster") }.toString(); rig.refresh()
            backing.failNextKnownCommit = ProvenNotCommittedRuntimeTransactionException("denial migration unavailable")
            rig.expectQuarantinedClose = true
        } finally { rig.close() }
        assertNotNull(rig.closeFailure); assertNotNull(rig.source.rasterConflictReceipt())
        assertThrows(Exception::class.java) {
            RuntimeQueueOwner.open(name, RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
                databaseFactory = { backing.connection() }, trustedSiteKey = RasterQueueRig.KEY,
                legacyStateLoader = { RasterQueueRig.initial() }).get()
        }
    }

    @Test fun `delayed denial cannot poison a newer durable base only boundary`() = RasterQueueRig().use { rig ->
        rig.activate()
        val newer = V1ConfigJson.parseConfig(JSONObject(rig.base()).put("issuedAt", "2026-08-05T00:00:30Z").toString())
        rig.backing.connection().use { db -> db.transaction { tx ->
            ReplayQueueStore.reconcile(tx, newer, RasterQueueRig.namespace, rig.clock.wall, false,
                setOf(NativeReplayProtocol.V2.transport), setOf(NativeReplayProtocol.V2.generation), ReplayMaskingRetention { _, _ -> true })
        } }
        rig.body = JSONObject(rig.body).apply { remove("raster") }.toString(); rig.refresh()
        assertFalse(rig.owner.authorizeCurrentDelivery(null) { false }.get())
        assertNull(rig.source.rasterConflictReceipt())
        val state = ReplayStoredState.decode(checkNotNull(rig.backing.replayRows["state"]))
        assertEquals(newer.issuedAt, state.issuedAt); assertNull(state.rasterSource); assertFalse(state.poisoned)
    }

    @Test fun `newer durable boundary is not poisoned by an older original pending denial`() = RasterQueueRig().use { rig ->
        rig.activate(); assertTrue(rig.owner.ensureNativeRasterStorage().get())
        val newer = NativeRasterSourceLedger("2026-08-05T00:00:30Z", "sha256:" + "f".repeat(64))
        rig.backing.connection().use { db -> db.transaction { tx -> tx.putReplayRow(checkNotNull(ReplayQueueStore.state(tx)).copy(rasterSource = newer).row()) } }
        rig.body = JSONObject(rig.body).apply { remove("raster") }.toString(); rig.refresh()
        assertFalse(rig.owner.authorizeCurrentDelivery(null) { false }.get())
        assertEquals(newer, rig.ledger()); assertNull(rig.gate.rasterDenial())
    }
}

internal class RasterQueueRig(val backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(),
    name: String = "raster-" + UUID.randomUUID(), format: V2ConfigFormat = V2ConfigFormat.NATIVE_V3) : AutoCloseable {
    val clock = RasterClock(); val gate = V2ConfigAuthorityGate(); private val worker = RasterWorker()
    var expectQuarantinedClose = false
    var closeFailure: Throwable? = null
        private set
    var body = if (format == V2ConfigFormat.NATIVE_V3) nativeV3SourceFixture().toString() else nativeV3SourceFixture().getJSONObject("configV2").toString()
    val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock, format = format)
    val driver = V2ConfigLifecycleDriver(source, gate::update, clock, object : V2ConfigLifecycleScheduler {
        override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
        override fun close() = Unit
    }, worker)
    val owner = RuntimeQueueOwner.open(name, RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
        databaseFactory = { backing.connection() }, trustedSiteKey = KEY, legacyStateLoader = { initial() },
        readbackProvenReplayTransports = setOf(NativeReplayProtocol.V2.transport),
        supportedReplayProtocolGenerations = setOf(NativeReplayProtocol.V2.generation),
        captureClock = object : RuntimeCaptureClock {
            override fun wallNowEpochMillis() = clock.wall
            override fun elapsedRealtimeNanos() = clock.nanos
        }).get().also { it.bindConfigurationGate(gate).get() }
    fun start() { driver.start(); worker.next() }
    fun refresh() { driver.refresh(); worker.next() }
    fun base() = checkNotNull(gate.snapshot()?.body)
    fun publish() {
        val body = base(); val parsed = V1ConfigJson.parseConfig(body)
        val privacy = PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(parsed.privacy),
            checkNotNull(parsed.features), checkNotNull(parsed.replayCapabilities), owner.snapshot().get().state.identity, false,
            RuntimeWallTimestamps.rfc3339(clock.wall))))
        assertTrue(owner.submitCaptureAuthority(body, privacy).get() is RuntimeCaptureAuthorityUpdateResult.Activated)
    }
    fun activate() { owner.ensurePreparedReplayStorage().get(); owner.ensureNativeReplayAccounting().get(); start(); publish() }
    fun ledger() = checkNotNull(ReplayStoredState.decode(checkNotNull(backing.replayRows["state"])).rasterSource)
    override fun close() {
        driver.close()
        val result = runCatching { owner.closeAsync().get(3, TimeUnit.SECONDS) }
        closeFailure = result.exceptionOrNull()
        if (!expectQuarantinedClose) result.getOrThrow()
    }
    companion object {
        const val KEY = "elu_pk_live_AAAAAAAAAAAAAAAAAAAAAAAAAA"
        val namespace = RuntimeSiteNamespace.digest(KEY)
        fun initial() = PersistedCoreState(identity = IdentityState(revision = 1, contextRevision = 1,
            anonymousId = "raster-anon", userId = null, groups = emptyMap(), superProperties = emptyMap(), optedOut = false,
            updatedAt = "2026-08-05T00:01:00Z", session = SessionState("raster-session", "2026-08-05T00:01:00Z",
                "2026-08-05T00:01:00Z", 1800, lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null)),
            stream = StreamState(streamId = "raster-stream", nextSequence = 0), flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
    }
}
internal class RasterClock : V2ConfigClock {
    var wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli(); var nanos = 1L
    override fun wallNowEpochMillis() = wall
    override fun monotonicNowNanos() = nanos
}
private class RasterWorker : V2ConfigLifecycleWorker {
    private val pending = ArrayDeque<() -> Unit>()
    override fun execute(task: () -> Unit) { pending.add(task) }
    override fun interruptCurrent() = Unit
    override fun close() = Unit
    fun next() = pending.removeFirst().invoke()
}
