package dev.elu.analytics.internal.facade

import dev.elu.analytics.internal.concurrent.SdkFuture

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.replay.*
import dev.elu.analytics.internal.runtime.*
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPResponse
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPTransport
import java.time.Instant
import java.util.ArrayDeque
import java.util.Date
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual facade/runtime/native/source/queue composition; fake DB, platform and local proof only. */
class StandaloneNativeReplayTest {
    private class MainAccess : NativeReplaySelectionAccess, AutoCloseable {
        @Volatile var main: Thread? = null
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "fixture-composition-main").also { main = it } }
        val window = Any(); val token = Any(); val root = Any(); val activity = Any()
        val rootReads = AtomicInteger(); val watches = AtomicInteger(); val closes = AtomicInteger()
        @Volatile var onObserve: (() -> Unit)? = null
        @Volatile var onClose: (() -> Unit)? = null
        override fun onMain(action: () -> Unit) { if (Thread.currentThread() === main) action() else executor.execute(action) }
        override fun currentRoot(activity: Any, current: () -> Boolean): Any? {
            check(Thread.currentThread() === main); rootReads.incrementAndGet()
            return root.takeIf { activity === this.activity && current() }
        }
        override fun observe(activity: Any, root: Any, current: () -> Boolean): NativeReplayRootFacts? {
            check(Thread.currentThread() === main); onObserve?.also { onObserve = null }?.invoke()
            return if (current()) NativeReplayRootFacts(window, token, 100, 200, 1f, 36) else null
        }
        override fun watch(root: Any, withdrawn: () -> Unit): AutoCloseable {
            check(Thread.currentThread() === main); watches.incrementAndGet()
            return AutoCloseable { check(Thread.currentThread() === main); closes.incrementAndGet(); onClose?.invoke() }
        }
        override fun close() { executor.shutdown(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
    }
    private class Platform(val rig: Rig, val access: MainAccess, val single: Boolean = false) : NativeReplayCapturePlatform {
        override val apiLevel = 36
        val factories = AtomicInteger(); val collections = AtomicInteger(); val firstFrame = CountDownLatch(1)
        @Volatile var onCollect: (() -> Unit)? = null
        override fun createCollector(): NativeReplayCaptureCollector {
            check(Thread.currentThread() === access.main); factories.incrementAndGet()
            val identity = UUID.randomUUID()
            return NativeReplayCaptureCollector { root, ordinal, timestamp, _, current, unresolved ->
                check(Thread.currentThread() === access.main); check(root === access.root); check(current()); check(!unresolved)
                collections.incrementAndGet(); onCollect?.invoke(); check(current()); firstFrame.countDown()
                NativeMaskedSnapshot(ordinal, timestamp, NativeViewport(100, 200), listOf(
                    NativeMaskedNode(identity, NativeMaskedKind.Rectangle, NativeRect(0.0, 0.0, 10.0, 10.0), NativeRect(0.0, 0.0, 10.0, 10.0))))
            }
        }
        override fun awaitNext(withdrawn: CountDownLatch): Boolean {
            if (single) return false
            return !withdrawn.await(3, TimeUnit.SECONDS)
        }
    }
    private class Transport(val hold: Boolean = false) : ReplayDeliveryTransport {
        val entered = CountDownLatch(1); val requests = CopyOnWriteArrayList<ReplayDeliveryClaim>()
        val canceled = AtomicInteger(); val result = SdkFuture<ReplayTransportResponse>()
        val started = CountDownLatch(1); val refused = CountDownLatch(1)
        @Volatile var beforeIo: (() -> Unit)? = null
        override fun start(claim: ReplayDeliveryClaim, authorizeIo: () -> Boolean): ReplayTransportOperation {
            // The actual queue forbids I/O authorization on its enrollment lane.
            val operation = object : ReplayTransportOperation {
                override val settlement = result
                override fun cancel() { canceled.incrementAndGet() }
            }
            Thread({
                try {
                    started.countDown(); beforeIo?.invoke()
                    if (!authorizeIo()) { refused.countDown(); error("Original I/O authority denied") }
                    requests += claim; entered.countDown()
                    if (!hold) result.complete(ReplayTransportResponse(403, byteArrayOf()))
                } catch (error: Throwable) { result.completeExceptionally(error) }
            }, "fixture-replay-io").apply { isDaemon = true }.start()
            return operation
        }
    }
    private fun proof() = NativeReplayCapabilities(setOf(V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)), setOf(ReplayFixtures.GENERATION))
    private fun Rig.minimum() = configure { it.getJSONObject("privacy").getJSONObject("replay").put("minimumDurationSeconds", 0) }
    private fun Rig.rows() = owner.storedPreparedReplayForTesting().get(3, TimeUnit.SECONDS)
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }
    private inner class Harness(activateEarly: Boolean = true, resetEarly: Boolean = false, bufferLimit: Int = 100) : AutoCloseable {
        val rig = Rig(); val access = MainAccess()
        val lifecycle = NativeReplayLifecycle(access)
        val platform = Platform(rig, access)
        val wire = Transport()
        lateinit var facade: StandaloneFacade
        val native: NativeReplayComposition
        val runtime: StandaloneRuntime
        init {
            rig.minimum()
            if (activateEarly) rig.activate()
            if (resetEarly) { rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get(); rig.publish() }
            lifecycle.resumed(access.activity)
            native = NativeReplayComposition(rig.owner, lifecycle, proof(), StandaloneRuntime.defaultVersions(), { false },
                { ::facade.isInitialized && facade.nativeReplayIntakeAllowed() }, platform, wire)
            native.ready().get(3, TimeUnit.SECONDS)
            runtime = StandaloneRuntime(rig.owner, KEY, wallClock = { rig.clock.wall },
                transportFactory = { BatchHTTPTransport { BatchHTTPResponse(503, byteArrayOf()) } },
                deviceInEuTimezone = { false }, flushDelayMillis = 60_000, configurationGate = rig.gate, nativeReplay = native)
            facade = StandaloneFacade(open = { StandaloneStack(runtime, rig.owner, null) }, deliverCallback = { it.run() },
                wallClock = { rig.clock.wall }, bufferLimit = bufferLimit, configurationGate = rig.gate)
            facade.start()
        }
        fun settle() { facade.settled().get(3, TimeUnit.SECONDS) }
        fun activate() { rig.activate(); facade.configurationChanged(); settle() }
        fun holdLane(): CountDownLatch {
            val lane = StandaloneFacade::class.java.getDeclaredField("lane").also { it.isAccessible = true }.get(facade) as ExecutorService
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            lane.execute { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); return release
        }
        override fun close() {
            runCatching { facade.closeAndWait().get(4, TimeUnit.SECONDS) }
            access.close(); rig.close()
        }
    }

    @Test fun `public first activity creates actual native session and later activities reuse one owner`(): Unit = Harness(resetEarly = true).use { h ->
        h.settle(); assertEquals(0, h.platform.factories.get())
        h.facade.capture("first", null, Date(h.rig.clock.wall)); h.settle()
        assertTrue(h.platform.firstFrame.await(3, TimeUnit.SECONDS))
        awaitCondition("first sealed public row") { h.rig.rows().isNotEmpty() }
        repeat(12) { h.facade.capture("activity-$it", null, Date(h.rig.clock.wall)) }
        h.settle(); assertEquals(1, h.platform.factories.get()); assertEquals(1, h.access.watches.get())
        assertTrue(h.facade.nativeReplayIntakeAllowed())
    }

    @Test fun `valid context acceptance revokes a held main frame without blocking facade or granting replacement`(): Unit = Harness(activateEarly = false).use { h ->
        h.settle(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.platform.onCollect = { h.platform.onCollect = null; entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        h.activate(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        h.facade.identify("joined-owner", mapOf("tier" to "paid")); h.settle()
        assertEquals("joined-owner", h.facade.distinctId())
        assertEquals(1, h.platform.factories.get()); assertTrue(h.rig.rows().isEmpty())
        release.countDown(); awaitCondition("new identity capture after old cleanup") { h.platform.factories.get() == 2 }
        awaitCondition("sealed new identity") { h.rig.rows().isNotEmpty() }
        assertEquals("joined-owner", h.rig.rows().single().prepared.userId)
    }

    @Test fun `set and set once fence native intake from first public acceptance`(): Unit = Harness().use { h ->
        h.settle(); assertTrue(h.platform.firstFrame.await(3, TimeUnit.SECONDS))
        for (field in listOf("\$set", "\$set_once")) {
            val release = h.holdLane()
            h.facade.capture("context", mapOf(field to mapOf("plan" to field)), Date(h.rig.clock.wall))
            assertFalse(h.facade.nativeReplayIntakeAllowed())
            release.countDown(); h.settle()
            assertTrue(h.facade.nativeReplayIntakeAllowed())
        }
        assertTrue(h.facade.diagnostics().get().dropped.isEmpty())
    }

    @Test fun `new restrictive source epoch prevents held older context from republishing capture`(): Unit = Harness().use { h ->
        h.settle(); assertTrue(h.platform.firstFrame.await(3, TimeUnit.SECONDS))
        val release = h.holdLane()
        h.facade.identify("must-not-restore", null)
        h.rig.gate.close(); h.facade.configurationChanged()
        assertFalse(h.facade.nativeReplayIntakeAllowed())
        release.countDown(); h.settle()
        assertTrue(h.facade.state() is EluFacadeState.Disabled)
        assertEquals(1, h.platform.factories.get()); assertNull(h.facade.distinctId())
    }

    @Test fun `initial buffer drop and final accepted prefix settle native intents exactly once`(): Unit = Harness(activateEarly = false, bufferLimit = 1).use { h ->
        h.settle()
        h.facade.identify("prefix", null); h.facade.register(mapOf("dropped" to true)); h.settle()
        assertFalse(h.facade.nativeReplayIntakeAllowed())
        assertEquals(1, h.facade.diagnostics().get().dropped[EluFacadeDropReason.BUFFER_FULL])
        h.activate(); assertTrue(h.facade.nativeReplayIntakeAllowed())
        assertEquals("prefix", h.facade.distinctId())
        assertFalse(h.rig.owner.snapshot().get().state.identity.superProperties.containsKey("dropped"))
        assertTrue(h.platform.firstFrame.await(3, TimeUnit.SECONDS))
    }

    @Test fun `facade close joins original held physical interval before releasing installation`(): Unit = Harness(activateEarly = false).use { h ->
        h.settle(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.platform.onCollect = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        h.activate(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        val close = h.facade.closeAndWait(); assertSame(close, h.facade.closeAndWait())
        assertFalse(close.cancel(true)); assertFalse(close.isDone); assertFalse(h.facade.nativeReplayIntakeAllowed())
        failure { h.rig.openSame().get(3, TimeUnit.SECONDS) }
        release.countDown(); close.get(3, TimeUnit.SECONDS)
        assertEquals(1, h.access.closes.get()); assertEquals(1, h.rig.databaseCloses)
        h.rig.openSame().get(3, TimeUnit.SECONDS).closeAsync().get(3, TimeUnit.SECONDS)
    }

    @Test fun `lifecycle false true false cannot publish an older queued resume`(): Unit = Harness().use { h ->
        h.settle(); assertTrue(h.platform.firstFrame.await(3, TimeUnit.SECONDS))
        val release = h.holdLane()
        h.facade.nativeReplayLifecycleChanged(false)
        h.facade.nativeReplayLifecycleChanged(true)
        h.facade.nativeReplayLifecycleChanged(false)
        assertFalse(h.facade.nativeReplayIntakeAllowed())
        release.countDown(); h.settle()
        assertFalse(h.facade.nativeReplayIntakeAllowed()); assertEquals(1, h.platform.factories.get())
        h.facade.nativeReplayLifecycleChanged(true); h.settle()
        assertTrue(h.facade.nativeReplayIntakeAllowed())
    }

    private class Rig : AutoCloseable {
        val clock = Clock(); val worker = Worker(); val gate = V2ConfigAuthorityGate()
        var body = ReplayFixtures.resource("contracts/v2/fixtures/config-enabled.json")
        val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, Scheduler(), worker)
        val backing = FakeRuntimeQueueBacking()
        val ownership = "native-accounting-" + UUID.randomUUID()
        @Volatile var ownerNanos: Long? = null
        @Volatile var onConnection: (() -> Unit)? = null
        @Volatile var onOwnerClock: (() -> Unit)? = null
        @Volatile var onReplayWrite: (() -> Unit)? = null
        @Volatile var afterReplayCommit: (() -> Unit)? = null
        @Volatile var onWrite: (() -> Unit)? = null
        @Volatile var afterWriteCommit: (() -> Unit)? = null
        @Volatile var onNativeRead: (() -> Unit)? = null
        @Volatile var onRecordRead: (() -> Unit)? = null
        @Volatile var databaseCloses = 0
        var owner = openSame().get().also { it.bindConfigurationGate(gate).get() }
        fun openSame() = RuntimeQueueOwner.open(ownership, RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
            readbackProvenReplayTransports = setOf(V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)),
            supportedReplayProtocolGenerations = setOf(ReplayFixtures.GENERATION),
            databaseFactory = {
                onConnection?.also { onConnection = null }?.invoke()
                val db = backing.connection()
                object : RuntimeQueueDatabase by db {
                    override fun close() { databaseCloses += 1; db.close() }
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
                        var wrote = false; var replayWrote = false
                        val result = db.transaction { tx -> block(object : RuntimeQueueTransaction by tx {
                            override fun readRecord(sequence: Long): RuntimeStoredRecord? {
                                onRecordRead?.also { onRecordRead = null }?.invoke()
                                return tx.readRecord(sequence)
                            }
                            override fun readReplayRow(key: String): RuntimeReplayStoredRow? {
                                if (key == NativeReplayAccounting.KEY) onNativeRead?.also { onNativeRead = null }?.invoke()
                                return tx.readReplayRow(key)
                            }
                            override fun putReplayRow(row: RuntimeReplayStoredRow) {
                                tx.putReplayRow(row)
                                if (row.key.startsWith("chunk/")) { replayWrote = true; onReplayWrite?.also { onReplayWrite = null }?.invoke() }
                                if (row.key == NativeReplayAccounting.KEY) { wrote = true; onWrite?.also { onWrite = null }?.invoke() }
                            }
                        }) }
                        if (replayWrote) afterReplayCommit?.also { afterReplayCommit = null }?.invoke()
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
        fun executor(): java.util.concurrent.ExecutorService = RuntimeQueueOwner::class.java.getDeclaredField("executor")
            .also { it.isAccessible = true }.get(owner) as java.util.concurrent.ExecutorService
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
        fun reopen() { owner.closeAsync().get(100, TimeUnit.MILLISECONDS); owner = openSame().get().also { it.bindConfigurationGate(gate).get() } }
        fun now() = RuntimeWallTimestamps.rfc3339(clock.wall)
        fun advance(seconds: Long = 1) { clock.wall += seconds * 1000; clock.nanos += seconds * 1_000_000_000 }
        fun event() = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "activity", now(), emptyMap(), StandaloneRuntime.defaultVersions())
        override fun close() { runCatching { owner.closeAsync().get(100, TimeUnit.MILLISECONDS) }; driver.close() }
    }
    private class Clock : V2ConfigClock {
        @Volatile var wall = Instant.parse("2026-08-05T00:01:06Z").toEpochMilli(); @Volatile var nanos = 1L
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
        fun awaitBlocked(thread: Thread) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("Expected the exact owned thread to block at its held monitor", Thread.State.BLOCKED, thread.state)
        }
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
