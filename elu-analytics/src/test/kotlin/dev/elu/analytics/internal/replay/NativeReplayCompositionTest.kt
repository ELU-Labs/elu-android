package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.concurrent.SdkFuture

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual queue, source, authority, sealer and composition; synthetic platform/proof/HTTP, fake DB. */
class NativeReplayCompositionTest {
    private class MainAccess : NativeReplaySelectionAccess, AutoCloseable {
        @Volatile var main: Thread? = null
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "fixture-composition-main").also { main = it } }
        val window = Any(); val token = Any(); val root = Any(); val activity = Any()
        val rootReads = AtomicInteger(); val watches = AtomicInteger(); val closes = AtomicInteger()
        @Volatile var rootAvailable = true
        @Volatile var factsAvailable = true
        @Volatile var onObserve: (() -> Unit)? = null
        @Volatile var onClose: (() -> Unit)? = null
        override fun onMain(action: () -> Unit) { if (Thread.currentThread() === main) action() else executor.execute(action) }
        override fun currentRoot(activity: Any, current: () -> Boolean): Any? {
            check(Thread.currentThread() === main); rootReads.incrementAndGet()
            return root.takeIf { rootAvailable && activity === this.activity && current() }
        }
        override fun observe(activity: Any, root: Any, current: () -> Boolean): NativeReplayRootFacts? {
            check(Thread.currentThread() === main); onObserve?.also { onObserve = null }?.invoke()
            return if (factsAvailable && current()) NativeReplayRootFacts(window, token, 100, 200, 1f, 36) else null
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
    private fun NativeReplayComposition.diagnostic(): NativeReplayCompletedCaptureSnapshot? =
        NativeReplayComposition::class.java.getDeclaredField("latestCompletedCapture").also { it.isAccessible = true }
            .get(this) as NativeReplayCompletedCaptureSnapshot?
    private fun NativeReplayComposition.currentCapture(): NativeReplayCaptureOwner =
        checkNotNull(NativeReplayComposition::class.java.getDeclaredField("capture").also { it.isAccessible = true }
            .get(this) as NativeReplayCaptureOwner?)
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }
    private fun composition(rig: Rig, lifecycle: NativeReplayLifecycle, platform: NativeReplayCapturePlatform,
        transport: ReplayDeliveryTransport = Transport(), allowed: () -> Boolean = { true },
        capabilities: NativeReplayCapabilities = proof(), worker: ExecutorService = Executors.newSingleThreadExecutor()) =
        NativeReplayComposition(rig.owner, lifecycle, capabilities, StandaloneRuntime.defaultVersions(), { false }, allowed,
            platform, transport, worker)

    @Test fun `empty local proof never observes a native root platform or network`() = Rig().use { rig -> MainAccess().use { access ->
        val life = NativeReplayLifecycle(access); life.resumed(access.activity); val wire = Transport()
        val noPlatform = object : NativeReplayCapturePlatform {
            override val apiLevel: Int get() = error("Platform was inspected without proof")
            override fun createCollector(): NativeReplayCaptureCollector = error("Collector created")
            override fun awaitNext(withdrawn: CountDownLatch) = error("Capture started")
        }
        val owner = composition(rig, life, noPlatform, wire, capabilities = NativeReplayCapabilities())
        owner.ready().get(); assertEquals(NativeReplayCompositionEvaluation.INACTIVE, owner.reevaluate(true).get())
        owner.closeAndWait().get(3, TimeUnit.SECONDS)
        assertEquals(0, access.rootReads.get()); assertTrue(wire.requests.isEmpty())
    } }

    @Test fun `first real session starts capture and same session activity does not restart or rewatch`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get(); rig.publish()
        val life = NativeReplayLifecycle(access); life.resumed(access.activity); val platform = Platform(rig, access)
        val owner = composition(rig, life, platform); owner.ready().get()
        assertEquals(NativeReplayCompositionEvaluation.INACTIVE, owner.reevaluate().get(3, TimeUnit.SECONDS))
        assertEquals(0, platform.factories.get())
        assertTrue(rig.owner.capture(RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "first", rig.now(), emptyMap(), StandaloneRuntime.defaultVersions())).get() is RuntimeCaptureResult.Accepted)
        assertEquals(NativeReplayCompositionEvaluation.ACTIVE, owner.reevaluate().get(3, TimeUnit.SECONDS))
        assertTrue(platform.firstFrame.await(3, TimeUnit.SECONDS)); awaitCondition("first durable row") { rig.rows().isNotEmpty() }
        repeat(8) {
            assertTrue(rig.owner.capture(RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "activity", rig.now(), emptyMap(), StandaloneRuntime.defaultVersions())).get() is RuntimeCaptureResult.Accepted)
            assertEquals(NativeReplayCompositionEvaluation.ACTIVE, owner.reevaluate().get(3, TimeUnit.SECONDS))
        }
        assertEquals(1, platform.factories.get()); assertEquals(1, access.watches.get())
        owner.closeAndWait().get(3, TimeUnit.SECONDS); assertEquals(1, access.closes.get())
    } }

    @Test fun `same resumed identity retries root discovery after original unavailable observation`() =
        rootAvailabilityRecovery(afterReset = false)

    @Test fun `reset session retries native root facts after original unavailable observation`() =
        rootAvailabilityRecovery(afterReset = true)

    private fun rootAvailabilityRecovery(afterReset: Boolean) = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate()
        if (afterReset) {
            rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get()
            rig.publish()
            assertTrue(rig.owner.capture(rig.event()).get() is RuntimeCaptureResult.Accepted)
        }
        val originalIdentity = rig.owner.snapshot().get().state.identity
        if (afterReset) access.factsAvailable = false else access.rootAvailable = false
        val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access); val owner = composition(rig, life, platform)
        try {
            owner.ready().get(3, TimeUnit.SECONDS)
            assertEquals(NativeReplayCompositionEvaluation.INACTIVE, owner.reevaluate().get(3, TimeUnit.SECONDS))
            assertEquals(0, access.watches.get()); assertEquals(0, platform.collections.get())
            assertNull(rig.state().session?.activeEpoch)
            // Original platform facts become usable; no new Activity, identity, source or force token.
            access.rootAvailable = true; access.factsAvailable = true
            assertEquals(originalIdentity, rig.owner.snapshot().get().state.identity)
            assertEquals("A no-root attempt must not suppress a later original observation",
                NativeReplayCompositionEvaluation.ACTIVE, owner.reevaluate().get(3, TimeUnit.SECONDS))
            assertTrue(platform.firstFrame.await(3, TimeUnit.SECONDS))
            awaitCondition("same-session durable native frame") { rig.rows().isNotEmpty() }
            assertEquals(1, access.watches.get())
        } finally { owner.closeAndWait().get(3, TimeUnit.SECONDS) }
        assertEquals(access.watches.get(), access.closes.get())
    } }

    @Test fun `unknown collection failure is not retried by an ordinary same identity hint`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access).also { it.onCollect = { error("Permanent unknown fixture failure") } }
        val owner = composition(rig, life, platform)
        try {
            owner.ready().get(3, TimeUnit.SECONDS); owner.reevaluate().get(3, TimeUnit.SECONDS)
            awaitCondition("original failed collector and watcher settled") { access.closes.get() == 1 }
            assertNull(rig.state().session?.activeEpoch); assertTrue(rig.rows().isEmpty())
            assertEquals(NativeReplayCompositionEvaluation.INACTIVE, owner.reevaluate().get(3, TimeUnit.SECONDS))
            assertEquals(1, platform.collections.get()); assertEquals(1, access.watches.get())
            assertEquals(NativeReplayCompletedCaptureSnapshot(1, NativeCaptureStage.COLLECTOR, 0,
                NativeCaptureFailureKind.OTHER, NativeReplayCaptureOutcome.SETTLED), owner.diagnostic())
        } finally { owner.closeAndWait().get(3, TimeUnit.SECONDS) }
    } }

    @Test fun `retirement publishes once and a late old completion cannot overwrite its replacement`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val entered = CountDownLatch(1); val releaseCollection = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1); val releaseCallback = CountDownLatch(1)
        val platform = Platform(rig, access)
        platform.onCollect = {
            if (platform.collections.get() == 1) {
                entered.countDown(); check(releaseCollection.await(3, TimeUnit.SECONDS))
                throw NativeCollectionException(NativeCollectionFailure.INVALID_ROOT)
            }
            throw NativeCollectionException(NativeCollectionFailure.UNRESOLVED_BLOCK_RULE)
        }
        val owner = composition(rig, life, platform)
        try {
            owner.ready().get(3, TimeUnit.SECONDS); owner.reevaluate().get(3, TimeUnit.SECONDS)
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertNull(owner.diagnostic())
            val original = owner.currentCapture()
            assertNull(original.completedDiagnostic())
            // This newer SdkFuture dependent holds notification, not the original completion.
            val held = original.finished().whenComplete { _, _ ->
                callbackEntered.countDown(); check(releaseCallback.await(3, TimeUnit.SECONDS))
            }
            releaseCollection.countDown(); assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
            assertTrue(original.finished().isDone); assertFalse(held.isDone)
            assertEquals(NativeReplayCompositionEvaluation.ACTIVE, owner.reevaluate(true).get(3, TimeUnit.SECONDS))
            awaitCondition("replacement completion published") { owner.diagnostic()?.completionCount == 2 }
            val replacement = owner.diagnostic()
            assertEquals(NativeCaptureFailureKind.UNRESOLVED_BLOCK_RULE, replacement?.failure)
            releaseCallback.countDown(); held.get(3, TimeUnit.SECONDS)
            owner.reevaluate().get(3, TimeUnit.SECONDS)
            assertEquals(replacement, owner.diagnostic()); assertEquals(2, platform.collections.get())
        } finally {
            releaseCollection.countDown(); releaseCallback.countDown(); owner.closeAndWait().get(3, TimeUnit.SECONDS)
        }
        assertEquals(2, owner.diagnostic()?.completionCount); assertEquals(2, access.closes.get())
    } }

    @Test fun `completed capture counter saturates and the snapshot holds only detached closed values`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access, single = true); val owner = composition(rig, life, platform)
        try {
            owner.ready().get(3, TimeUnit.SECONDS)
            NativeReplayComposition::class.java.getDeclaredField("latestCompletedCapture").also { it.isAccessible = true }
                .set(owner, NativeReplayCompletedCaptureSnapshot(Int.MAX_VALUE, NativeCaptureStage.BEFORE_LOOP, 0,
                    NativeCaptureFailureKind.OTHER, NativeReplayCaptureOutcome.QUARANTINED))
            owner.reevaluate().get(3, TimeUnit.SECONDS)
            awaitCondition("saturated counter still publishes new completion") { owner.diagnostic()?.outcome == NativeReplayCaptureOutcome.SETTLED }
            assertEquals(Int.MAX_VALUE, owner.diagnostic()?.completionCount)
            assertNull(owner.diagnostic()?.failure); assertEquals(1, owner.diagnostic()?.frames)
        } finally { owner.closeAndWait().get(3, TimeUnit.SECONDS) }
        assertEquals(setOf("completionCount", "stage", "frames", "failure", "outcome"),
            NativeReplayCompletedCaptureSnapshot::class.java.declaredFields.map { it.name }.toSet())
    } }

    @Test fun `close holds physical main work then exact accounting and watcher before completion`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val platform = Platform(rig, access)
        platform.onCollect = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val worker = Executors.newSingleThreadExecutor(); val owner = composition(rig, life, platform, worker = worker)
        owner.ready().get(); owner.reevaluate().get(3, TimeUnit.SECONDS); assertTrue(entered.await(3, TimeUnit.SECONDS))
        val close = owner.closeAndWait(); assertSame(close, owner.closeAndWait()); assertFalse(close.cancel(true)); assertFalse(close.isDone)
        assertEquals(0, access.closes.get()); assertNull(rig.owner.enrollNativeReplayCapture().get(3, TimeUnit.SECONDS))
        release.countDown(); close.get(3, TimeUnit.SECONDS)
        assertEquals(1, access.closes.get()); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); assertTrue(rig.rows().isEmpty())
    } }

    @Test fun `pending public intent defeats held selection and cannot publish after consent mutation`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val allowed = AtomicBoolean(true)
        access.onObserve = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val platform = Platform(rig, access); val owner = composition(rig, life, platform, allowed = allowed::get)
        owner.ready().get(); val evaluating = owner.reevaluate(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        allowed.set(false); owner.withdrawFresh(); rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, rig.now())).get()
        release.countDown(); assertEquals(NativeReplayCompositionEvaluation.WITHDRAWN, evaluating.get(3, TimeUnit.SECONDS))
        assertEquals(0, platform.factories.get()); assertTrue(rig.rows().isEmpty())
        allowed.set(true); assertEquals(NativeReplayCompositionEvaluation.INACTIVE, owner.reevaluate(true).get(3, TimeUnit.SECONDS))
        owner.closeAndWait().get(3, TimeUnit.SECONDS); assertEquals(0, platform.collections.get())
    } }

    @Test fun `watcher cleanup uncertainty denies replacement retains installation and releases worker`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access); val worker = Executors.newSingleThreadExecutor()
        val owner = composition(rig, life, platform, worker = worker); owner.ready().get(); owner.reevaluate().get(3, TimeUnit.SECONDS)
        assertTrue(platform.firstFrame.await(3, TimeUnit.SECONDS)); awaitCondition("durable prefix") { rig.rows().isNotEmpty() }
        access.onClose = { throw IllegalStateException("Unproven original listener cleanup") }
        failure { owner.closeAndWait().get(3, TimeUnit.SECONDS) }
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); assertEquals(1, access.watches.get())
        val queueWorker = rig.executor(); failure { rig.owner.closeAsync().get(3, TimeUnit.SECONDS) }
        assertTrue(queueWorker.awaitTermination(3, TimeUnit.SECONDS)); assertEquals(0, rig.databaseCloses)
        failure { rig.openSame().get(3, TimeUnit.SECONDS) }
    } }

    @Test fun `original caller acceptance survives a held worker and cannot retire a newer interval`(): Unit = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access); val worker = Executors.newSingleThreadExecutor()
        val owner = composition(rig, life, platform, worker = worker); owner.ready().get()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val accepted = AtomicBoolean(true)
        worker.execute { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val older = owner.reevaluate(originalAcceptance = accepted::get)
        accepted.set(false); release.countDown()
        assertEquals(NativeReplayCompositionEvaluation.WITHDRAWN, older.get(3, TimeUnit.SECONDS))
        assertEquals(0, access.rootReads.get()); assertEquals(0, platform.factories.get())
        owner.reevaluate().get(3, TimeUnit.SECONDS); assertTrue(platform.firstFrame.await(3, TimeUnit.SECONDS))
        assertEquals(NativeReplayCompositionEvaluation.WITHDRAWN, owner.reevaluate(true, accepted::get).get(3, TimeUnit.SECONDS))
        assertEquals(0, access.closes.get()); assertEquals(1, platform.factories.get())
        owner.closeAndWait().get(3, TimeUnit.SECONDS)
    } }

    @Test fun `queued reevaluation cannot adopt an intent accepted after it was submitted`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access); val worker = Executors.newSingleThreadExecutor()
        val owner = composition(rig, life, platform, worker = worker); owner.ready().get()
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        worker.execute { held.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        assertTrue(held.await(3, TimeUnit.SECONDS))
        val prior = owner.reevaluate(); owner.withdrawFresh(); release.countDown()
        try {
            assertEquals(NativeReplayCompositionEvaluation.WITHDRAWN, prior.get(3, TimeUnit.SECONDS))
            assertEquals(0, access.rootReads.get()); assertEquals(0, platform.factories.get())
        } finally { release.countDown(); owner.closeAndWait().get(3, TimeUnit.SECONDS) }
    } }

    @Test fun `restrictive withdrawal defeats enrolled IO and late capture wake without renewing delivery`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access); val wire = Transport(hold = true)
        val release = CountDownLatch(1); wire.beforeIo = { check(release.await(3, TimeUnit.SECONDS)) }
        val owner = composition(rig, life, platform, wire); owner.ready().get(); owner.reevaluate().get(3, TimeUnit.SECONDS)
        assertTrue(wire.started.await(3, TimeUnit.SECONDS)); owner.withdrawAll(); release.countDown()
        assertTrue(wire.refused.await(3, TimeUnit.SECONDS))
        assertEquals(ReplayDeliveryPass(0, 0), owner.flushSealed().get(3, TimeUnit.SECONDS))
        owner.closeAndWait().get(3, TimeUnit.SECONDS)
        assertTrue(wire.requests.isEmpty()); assertEquals(1, platform.factories.get()); assertEquals(1, access.watches.get())
        assertEquals(1, rig.rows().size)
    } }

    @Test fun `fresh only withdrawal preserves original sealed IO and then joins physical close`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access); val wire = Transport(hold = true)
        val release = CountDownLatch(1); wire.beforeIo = { check(release.await(3, TimeUnit.SECONDS)) }
        val owner = composition(rig, life, platform, wire); owner.ready().get(); owner.reevaluate().get(3, TimeUnit.SECONDS)
        assertTrue(wire.started.await(3, TimeUnit.SECONDS)); owner.withdrawFresh(); release.countDown()
        assertTrue(wire.entered.await(3, TimeUnit.SECONDS)); val close = owner.closeAndWait(); assertFalse(close.isDone)
        wire.result.complete(ReplayTransportResponse(403, byteArrayOf())); close.get(3, TimeUnit.SECONDS)
        assertEquals(1, wire.requests.size); assertEquals(1, rig.rows().size)
    } }

    @Test fun `late physical refusal survives composition close while close waits its original settlement`() = Rig().use { rig -> MainAccess().use { access ->
        rig.minimum(); rig.activate(); val life = NativeReplayLifecycle(access); life.resumed(access.activity)
        val platform = Platform(rig, access); val wire = Transport(hold = true); val owner = composition(rig, life, platform, wire)
        owner.ready().get(); val active = owner.reevaluate().get(3, TimeUnit.SECONDS)
        if (!wire.entered.await(3, TimeUnit.SECONDS)) {
            val count = rig.rows().size
            val pass = runCatching { owner.flushSealed().get(3, TimeUnit.SECONDS).toString() }.fold({ it }, { it.toString() })
            owner.closeAndWait().get(3, TimeUnit.SECONDS)
            fail("No synthetic transport entry: evaluation=$active rows=$count collections=${platform.collections.get()} explicitPass=$pass")
        }
        val original = wire.requests.single().row.prepared.copyBytes(); val closing = owner.closeAndWait(); assertFalse(closing.isDone)
        wire.result.complete(ReplayTransportResponse(403, byteArrayOf())); closing.get(3, TimeUnit.SECONDS)
        assertArrayEquals(original, rig.rows().single().prepared.copyBytes())
        rig.reopen(); rig.publish()
        val next = rig.owner.openReplayDeliveryQueue(PrivacyStateProjector.nativeSealedDeliveryPolicy(proof()) { false }).get()
        assertNull(next.claim()); assertArrayEquals(original, rig.rows().single().prepared.copyBytes())
    } }

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
