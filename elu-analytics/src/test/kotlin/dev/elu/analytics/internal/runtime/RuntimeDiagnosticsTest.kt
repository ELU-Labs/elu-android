package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.diagnostics.NativeStartupMeasurement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticsTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    @After fun close() { owners.forEach { runCatching { it.closeAsync().await() } } }
    private class Clock : RuntimeCaptureClock, RuntimeDiagnosticsClock {
        var wall = Instant.parse(NOW).toEpochMilli()
        var nanos = 1_000_000_000L
        var boot = 7L
        override fun wallNowEpochMillis() = wall
        override fun elapsedRealtimeNanos() = nanos
        override fun read() = RuntimeDiagnosticsClockReading(boot, wall, nanos, nanos)
        fun advance(ms: Long) { wall += ms; nanos += ms * 1_000_000 }
    }
    private inner class Rig(val backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(), val clock: Clock = Clock(),
        val ownershipKey: String = "diagnostics-${UUID.randomUUID()}",
        wrap: (RuntimeQueueDatabase) -> RuntimeQueueDatabase = { it },
        leaseClosed: () -> Unit = {},
    ) {
        val owner = RuntimeQueueOwner.open(ownershipKey, RuntimeQueueLimits(1000, 1_000_000),
            { wrap(backing.connection()) }, { state() }, leaseFactory = { RuntimeOwnershipLease(leaseClosed) },
            trustedSiteKey = "elu_pk_test_diagnostics", captureClock = clock).await().also { owners += it }
        fun configure(enabled: Boolean = true, launches: Boolean = true) =
            owner.configureDiagnostics(RuntimeDiagnosticsConfiguration(enabled, launches), clock).await()
        fun activate(longTasks: Boolean = true, config: String = config(longTasks)) =
            owner.submitCaptureAuthority(config, privacy(owner.snapshot().await().state.identity.contextRevision)).await()
        fun user() = owner.capture(RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "user", now(), emptyMap(), versions())).await()
        fun now() = RuntimeWallTimestamps.rfc3339(clock.wall)
        fun measurement(): NativeStartupMeasurement {
            val epoch = checkNotNull(owner.diagnosticsEpoch())
            clock.advance(100)
            val start = clock.nanos
            clock.advance(100)
            return NativeStartupMeasurement(epoch, start, clock.nanos, 6, 1)
        }
        fun capture(measurement: NativeStartupMeasurement, current: () -> Boolean = { true }): RuntimeCaptureResult {
            val identity = owner.snapshot().await().state.identity
            return owner.capture(RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "\$native_launch", now(), measurement.properties(), versions(),
                RuntimeCaptureExpectation(identity.revision, identity.contextRevision, identity.session?.id ?: "missing", current),
                startupMeasurement = measurement)).await()
        }
    }

    @Test fun `opt-in coverage needs authority and startup never creates a receipt session`() {
        val rig = Rig(); rig.configure(); assertNull(rig.owner.diagnosticsEpoch())
        rig.activate(); assertNotNull(rig.owner.diagnosticsEpoch())
        val measurement = rig.measurement()
        assertTrue(rig.capture(measurement) is RuntimeCaptureResult.Rejected)
        assertEquals(0, rig.owner.snapshot().await().queuedCount)
        assertNull(rig.backing.core!!.diagnostics.lastLaunchUptimeNanos)
        rig.user(); assertTrue(rig.capture(measurement) is RuntimeCaptureResult.Accepted)
    }

    @Test fun `passive receipt excludes inherited customer context and retains idle deadline`() {
        val rig = Rig(); rig.configure(); rig.activate(config = config(true, idle = 60)); rig.user()
        val before = rig.owner.snapshot().await().state.identity
        val measurement = rig.measurement(); rig.clock.advance(58_800)
        val accepted = rig.capture(measurement) as RuntimeCaptureResult.Accepted
        assertEquals(before, accepted.snapshot.state.identity)
        assertTrue(accepted.record.record.groups.isEmpty())
        assertEquals(JsonValues.objectValue(measurement.properties(), "measurement"), accepted.record.record.properties)
        rig.clock.advance(2000)
        val next = rig.user() as RuntimeCaptureResult.Accepted
        assertNotEquals(before.session!!.id, next.record.record.sessionId)
    }

    @Test fun `event and dedupe reconcile both ambiguous outcomes atomically`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) {
            val rig = Rig(); rig.configure(); rig.activate(); rig.user()
            val measurement = rig.measurement()
            rig.backing.ambiguousNextCommit = outcome
            assertTrue(rig.capture(measurement) is RuntimeCaptureResult.Accepted)
            assertEquals(measurement.launchUptimeNanos, rig.backing.core!!.diagnostics.lastLaunchUptimeNanos)
            assertTrue(rig.capture(measurement) is RuntimeCaptureResult.Rejected)
            assertEquals(2, rig.owner.snapshot().await().queuedCount)
        }
    }

    @Test fun `final withdrawal rolls back both event and marker`() {
        val rig = Rig(); rig.configure(); rig.activate(); rig.user()
        val measurement = rig.measurement(); var checks = 0
        assertTrue(rig.capture(measurement) { ++checks < 3 } is RuntimeCaptureResult.Rejected)
        assertEquals(1, rig.owner.snapshot().await().queuedCount)
        assertNull(rig.backing.core!!.diagnostics.lastLaunchUptimeNanos)
        assertTrue(rig.capture(measurement) is RuntimeCaptureResult.Accepted)
    }

    @Test fun `explicit consent options reset and close end old coverage`() {
        val rig = Rig(); rig.configure(); rig.activate(); rig.user()
        val first = rig.measurement()
        rig.owner.withdrawDiagnosticsCoverage().await(); assertNull(rig.owner.diagnosticsEpoch())
        rig.activate(); assertNotEquals(first.epoch, rig.owner.diagnosticsEpoch())
        assertTrue(rig.capture(first) is RuntimeCaptureResult.Rejected)
        val second = rig.measurement()
        rig.configure(launches = false); assertNull(rig.owner.diagnosticsEpoch())
        rig.configure(); rig.activate(); assertNotEquals(second.epoch, rig.owner.diagnosticsEpoch())
        rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).await()
        assertNull(rig.owner.diagnosticsEpoch())
        rig.activate(); assertNotNull(rig.owner.diagnosticsEpoch())
        rig.owner.closeAsync().await(); assertNull(rig.backing.core!!.diagnostics.epoch)
    }

    @Test fun `denial clock rollback and changed boot cannot import old startup facts`() {
        for (change in listOf<(Rig) -> Unit>(
            { it.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, it.now())).await() },
            { it.clock.boot++ }, { it.clock.wall -= 10_000 }, { it.configure(enabled = false) })) {
            val rig = Rig(); rig.configure(); rig.activate(); rig.user(); val measurement = rig.measurement()
            change(rig); assertTrue(rig.capture(measurement) is RuntimeCaptureResult.Rejected)
            assertNull(rig.backing.core!!.diagnostics.lastLaunchUptimeNanos)
        }
    }

    @Test fun `current performance long-tasks permission is mandatory but ordinary expiry preserves coverage`() {
        val rig = Rig(); rig.configure(); rig.activate(longTasks = false); rig.user()
        val measurement = rig.measurement()
        assertTrue(rig.capture(measurement) is RuntimeCaptureResult.Rejected)
        val epoch = rig.owner.diagnosticsEpoch()
        rig.clock.advance(300_000); rig.activate(longTasks = false)
        assertEquals(epoch, rig.owner.diagnosticsEpoch())
    }

    @Test fun `process-death snapshot preserves coverage and watermark without adopting closed owner`() {
        val rig = Rig(); rig.configure(); rig.activate(); rig.user(); val measurement = rig.measurement()
        assertTrue(rig.capture(measurement) is RuntimeCaptureResult.Accepted)
        // A detached copy models bytes retained on abrupt process death; explicit close differs.
        val copy = FakeRuntimeQueueBacking().apply {
            core = rig.backing.core!!.copy(stateJson = rig.backing.core!!.stateJson.copyOf())
            databaseSchemaVersion = rig.backing.databaseSchemaVersion
            records.putAll(rig.backing.records)
        }
        val restarted = Rig(copy, rig.clock); restarted.configure()
        assertEquals(measurement.epoch, restarted.owner.diagnosticsEpoch())
        restarted.activate(); assertTrue(restarted.capture(measurement) is RuntimeCaptureResult.Rejected)
        assertEquals(2, restarted.owner.snapshot().await().queuedCount)
    }

    @Test fun `shutdown retries only proven rollback and releases only after durable closure`() {
        for (failure in listOf("known", "ambiguous-rollback", "ambiguous-commit")) {
            val rig = Rig(); rig.configure(); rig.activate()
            when (failure) {
                "known" -> rig.backing.failNextKnownCommit = ProvenNotCommittedRuntimeTransactionException("injected rollback")
                "ambiguous-rollback" -> rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.ROLLBACK
                else -> rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
            }
            val before = rig.backing.mutatedTransactionAttempts
            rig.owner.closeAsync().await()
            assertEquals(if (failure == "ambiguous-commit") 1 else 2, rig.backing.mutatedTransactionAttempts - before)
            assertNull(rig.backing.core!!.diagnostics.epoch)
            assertNull(rig.owner.diagnosticsEpoch())
            val reopened = Rig(rig.backing, rig.clock, rig.ownershipKey)
            assertNull(reopened.owner.diagnosticsEpoch())
        }
    }

    @Test fun `persistent closure failure denies projection and retains original ownership and lease`() {
        var fail = false; var attempts = 0; var leasesClosed = 0; var databasesClosed = 0
        val rig = Rig(wrap = { database -> object : RuntimeQueueDatabase by database {
            override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = database.transaction { tx ->
                block(object : RuntimeQueueTransaction by tx {
                    override fun updateCore(core: RuntimeStoredCore) {
                        if (fail && core.diagnostics == RuntimeDiagnosticsState()) {
                            attempts++
                            throw ProvenNotCommittedRuntimeTransactionException("storage remains unavailable")
                        }
                        tx.updateCore(core)
                    }
                })
            }
            override fun close() { databasesClosed++; database.close() }
        } }, leaseClosed = { leasesClosed++ })
        rig.configure(); rig.activate(); val epoch = rig.owner.diagnosticsEpoch()
        fail = true
        assertThrows(java.util.concurrent.ExecutionException::class.java) { rig.owner.closeAsync().await() }
        assertEquals(2, attempts)
        assertEquals(epoch, rig.backing.core!!.diagnostics.epoch)
        assertNull(rig.owner.diagnosticsEpoch())
        assertEquals(0, databasesClosed); assertEquals(0, leasesClosed)
        val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { Rig(rig.backing, rig.clock, rig.ownershipKey) }
        assertTrue(failure.cause is RuntimeQueueOwnershipException)
    }

    @Test fun `ambiguous first epoch grant with failed readback cannot release unknown coverage`() {
        val backing = FakeRuntimeQueueBacking(); var arm = false; var leaseClosed = 0
        val rig = Rig(backing, wrap = { database -> object : RuntimeQueueDatabase by database {
            override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = try {
                database.transaction(block)
            } catch (error: AmbiguousRuntimeCommitException) {
                if (arm) { arm = false; backing.failNextCoreRead = IllegalStateException("readback unavailable") }
                throw error
            }
        } }, leaseClosed = { leaseClosed++ })
        rig.configure(); arm = true; backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
        assertThrows(java.util.concurrent.ExecutionException::class.java) { rig.activate() }
        assertNotNull(backing.core!!.diagnostics.epoch)
        assertNull(rig.owner.diagnosticsEpoch())
        assertThrows(java.util.concurrent.ExecutionException::class.java) { rig.owner.closeAsync().await() }
        assertEquals(0, leaseClosed)
        val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { Rig(backing, rig.clock, rig.ownershipKey) }
        assertTrue(failure.cause is RuntimeQueueOwnershipException)
    }

    @Test fun `failed consent withdrawal stays denied through unrelated publication until durable retry`() {
        val rig = Rig(); rig.configure(); rig.activate(); rig.user()
        rig.backing.failNextKnownCommit = IllegalStateException("unclassified storage failure")
        assertThrows(java.util.concurrent.ExecutionException::class.java) { rig.owner.withdrawDiagnosticsCoverage().await() }
        assertNull(rig.owner.diagnosticsEpoch())
        rig.user(); rig.activate()
        assertNull(rig.owner.diagnosticsEpoch())
        rig.owner.withdrawDiagnosticsCoverage().await()
        rig.activate(); assertNotNull(rig.owner.diagnosticsEpoch())
    }

    @Test fun `closed migration preserves each owned table combination and subsequent lazy upgrades`() {
        for (version in 1..6) {
            val rig = Rig()
            if (version in listOf(2, 4, 6)) rig.owner.ensureFeatureFlagRuntime().await()
            if (version >= 3) rig.owner.ensurePreparedReplayStorage().await()
            if (version >= 5) rig.owner.ensureNativeReplayAccounting().await()
            rig.owner.closeAsync().await()
            rig.backing.databaseSchemaVersion = version
            val reopened = Rig(rig.backing); reopened.configure()
            assertEquals(version + 24, rig.backing.databaseSchemaVersion)
            assertEquals(RuntimeReplayAudienceState.Unknown, rig.backing.core!!.replayAudience)
            assertNull(reopened.owner.diagnosticsEpoch())
            reopened.owner.ensureFeatureFlagRuntime().await()
            reopened.owner.ensurePreparedReplayStorage().await()
            reopened.owner.ensureNativeReplayAccounting().await()
            assertEquals(30, rig.backing.databaseSchemaVersion)
        }
        for (unsupported in (13L..24L) + listOf(0L, 49L))
            assertThrows(UnsupportedRuntimeStorageSchemaException::class.java) { runtimeBaseDatabaseVersion(unsupported) }
    }

    private fun state() = PersistedCoreState(identity = IdentityState(revision = 2, contextRevision = 5,
        anonymousId = "anon_diag", userId = "user_diag", groups = mapOf("secret" to "group"),
        superProperties = mapOf("private" to "value"), session = null, optedOut = false, updatedAt = "2026-08-04T00:00:00.000Z"),
        stream = StreamState(streamId = "stream_diag", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
    private fun config(longTasks: Boolean, idle: Int = 1800) = JSONObject(resource("config-enabled.json")).apply {
        put("capturePerformance", JSONObject().put("memory", false).put("long_tasks", longTasks).put("sample_interval_ms", 5000))
        getJSONObject("session").put("idleTimeoutSeconds", idle)
    }.toString()
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
