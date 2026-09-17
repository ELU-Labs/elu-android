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
class NativeReplayDeadlineRetryTest {
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
        @Volatile var onPause: (() -> Boolean)? = null
        override fun createCollector(): NativeReplayCaptureCollector {
            check(Thread.currentThread() === access.main); factories.incrementAndGet()
            val identity = UUID.randomUUID()
            return NativeReplayCaptureCollector { root, ordinal, timestamp, _, current, unresolved ->
                check(Thread.currentThread() === access.main); check(root === access.root); check(current()); check(!unresolved)
                collections.incrementAndGet(); onCollect?.invoke()
                if (!current()) throw NativeCollectionException(NativeCollectionFailure.WITHDRAWN)
                firstFrame.countDown()
                NativeMaskedSnapshot(ordinal, timestamp, NativeViewport(100, 200), listOf(
                    NativeMaskedNode(identity, NativeMaskedKind.Rectangle, NativeRect(0.0, 0.0, 10.0, 10.0), NativeRect(0.0, 0.0, 10.0, 10.0))))
            }
        }
        override fun awaitNext(withdrawn: CountDownLatch): Boolean {
            onPause?.let { return it() }
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
    private fun composition(rig: Rig, lifecycle: NativeReplayLifecycle, platform: NativeReplayCapturePlatform,
        transport: ReplayDeliveryTransport = Transport(), allowed: () -> Boolean = { true },
        capabilities: NativeReplayCapabilities = proof(), worker: ExecutorService = Executors.newSingleThreadExecutor()) =
        NativeReplayComposition(rig.owner, lifecycle, capabilities, StandaloneRuntime.defaultVersions(), { false }, allowed,
            platform, transport, worker)


    /** Manual scheduler advances only scheduled opportunities; it creates no thread. */
    private class Timer(private val captureWorker: () -> Thread?) : AbstractExecutorService(), ScheduledExecutorService {
        @Volatile var tick = 0L
        @Volatile private var stopped = false
        val tasks = CopyOnWriteArrayList<Pending<*>>()
        inner class Pending<T>(val original: Callable<T>, val due: Long, val delay: Long) :
            FutureTask<T>(original), ScheduledFuture<T> {
            val scheduledOn = Thread.currentThread()
            override fun getDelay(unit: TimeUnit) = unit.convert(due - tick, TimeUnit.MILLISECONDS)
            override fun compareTo(other: Delayed) = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
            fun forceOriginal() { original.call() }
        }
        override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
            if (stopped) throw RejectedExecutionException()
            val millis = unit.toMillis(delay)
            return Pending(callable, tick + millis, millis).also { tasks += it }
        }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            schedule(Callable { command.run(); Unit }, delay, unit)
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> = error("No repeating task is allowed")
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> = error("No repeating task is allowed")
        override fun execute(command: Runnable) = error("No unscheduled work is allowed")
        fun pending() = tasks.filter { !it.isDone }
        fun retryTasks() = tasks.filter { it.scheduledOn === captureWorker() }
        fun retryPending() = retryTasks().filter { !it.isDone }
        fun advance(millis: Long) { require(millis >= 0); tick += millis; pending().filter { it.due <= tick }.forEach { it.run() } }
        override fun shutdown() { stopped = true; tasks.forEach { it.cancel(false) } }
        override fun shutdownNow(): MutableList<Runnable> { shutdown(); return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && pending().isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private inner class RetryFixture(diagnostics: Boolean = true) : AutoCloseable {
        val rig = Rig()
        val access = MainAccess()
        val workerThread = AtomicReference<Thread>()
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "fixture-retry-composition").also { workerThread.set(it) } }
        val timer = Timer { workerThread.get() }
        val lifecycle = NativeReplayLifecycle(access)
        val platform = Platform(rig, access, single = true)
        val wire = Transport(hold = true)
        val accepted = AtomicBoolean(true)
        val trace = CopyOnWriteArrayList<JSONObject>()
        val owner: NativeReplayComposition
        var quarantineExpected = false
        var acceptedFailure: Throwable? = null
        init {
            rig.minimum(); rig.activate(); lifecycle.resumed(access.activity)
            owner = NativeReplayComposition(rig.owner, lifecycle, proof(), StandaloneRuntime.defaultVersions(),
                { false }, { true }, platform, wire, worker, timer,
                nativeStartObserver = if (diagnostics) BoundedNativeStartObserver.create { trace += JSONObject(String(it, Charsets.US_ASCII)) } else BoundedNativeStartObserver.NONE)
            owner.ready().get(3, TimeUnit.SECONDS)
        }
        fun start() { assertEquals(NativeReplayCompositionEvaluation.ACTIVE,
            owner.reevaluate(originalAcceptance = { acceptedFailure?.let { throw it }; accepted.get() }).get(3, TimeUnit.SECONDS)) }
        fun timeout() { rig.clock.nanos += 50_000_001L }
        fun barrier() { worker.submit { }.get(3, TimeUnit.SECONDS) }
        fun pending(delay: Long): Timer.Pending<*> {
            awaitCondition("settled deadline must schedule exactly one delayed retry") { timer.retryPending().size == 1 }
            barrier(); assertEquals(1, timer.retryPending().size)
            return timer.retryPending().single().also { assertEquals(delay, it.delay) }
        }
        override fun close() {
            wire.result.complete(ReplayTransportResponse(403, byteArrayOf()))
            try {
                if (quarantineExpected) failure { owner.closeAndWait().get(3, TimeUnit.SECONDS) }
                else owner.closeAndWait().get(3, TimeUnit.SECONDS)
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); assertTrue(timer.isTerminated)
            } finally { timer.shutdownNow(); rig.close(); access.close() }
        }
    }

    @Test fun `two timed out captures recover on cadence without another public call`() = RetryFixture().use { f ->
        val originalIdentity = f.rig.owner.snapshot().get().state.identity
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.platform.onCollect = {
            if (f.platform.collections.get() == 1) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            if (f.platform.collections.get() <= 2) f.timeout()
        }
        f.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        // This original field and completion API exist on both selected predecessor and successor.
        val originalCapture = NativeReplayComposition::class.java.getDeclaredField("capture")
            .also { it.isAccessible = true }.get(f.owner) as NativeReplayCaptureOwner
        release.countDown()
        val outcome = originalCapture.finished().get(3, TimeUnit.SECONDS)
        assertTrue("original capture must physically settle", outcome.name in setOf("SETTLED", "SETTLED_PASS_DEADLINE"))
        awaitCondition("original settled watcher must retire before causal timer check") { f.access.closes.get() == 1 }
        val failure = f.trace.filter { it.getString("phase") == "CAPTURE_FAILED" }.single()
        assertEquals("COLLECTOR", failure.getString("stage")); assertEquals(0, failure.getInt("frames"))
        assertEquals("PASS_DEADLINE", failure.getString("failure"))
        assertNull(f.rig.state().session?.activeEpoch); assertTrue(f.rig.rows().isEmpty())
        f.pending(1_000)
        assertEquals(1, f.platform.collections.get()); assertEquals(1, f.access.closes.get())
        assertNull(f.rig.state().session?.activeEpoch); assertTrue(f.rig.rows().isEmpty())
        f.timer.advance(999); f.barrier(); assertEquals(1, f.platform.collections.get())
        f.timer.advance(1); f.pending(2_000)
        assertEquals(2, f.platform.collections.get()); assertEquals(2, f.access.closes.get())
        f.timer.advance(2_000)
        awaitCondition("third current same-identity capture must commit") { f.rig.rows().size == 1 }
        f.barrier(); assertEquals(3, f.platform.factories.get()); assertTrue(f.timer.retryPending().isEmpty())
        assertEquals(originalIdentity, f.rig.owner.snapshot().get().state.identity)
        assertTrue(f.wire.entered.await(3, TimeUnit.SECONDS)); assertEquals(1, f.wire.requests.size)
    }

    @Test fun `diagnostics disabled quiet session recovers after two settled pass deadlines`() = RetryFixture(diagnostics = false).use { f ->
        val identity = f.rig.owner.snapshot().get().state.identity
        f.platform.onCollect = { if (f.platform.collections.get() <= 2) f.timeout() }
        f.start(); f.pending(1_000)
        assertEquals(1, f.platform.collections.get()); assertEquals(1, f.access.closes.get())
        assertTrue(f.rig.rows().isEmpty()); assertNull(f.rig.state().session?.activeEpoch)
        f.timer.advance(1_000); f.pending(2_000)
        assertEquals(2, f.platform.collections.get()); assertEquals(2, f.access.closes.get())
        assertTrue(f.rig.rows().isEmpty()); assertNull(f.rig.state().session?.activeEpoch)
        f.timer.advance(2_000)
        awaitCondition("untraced third capture commits under unchanged identity") { f.rig.rows().size == 1 }
        f.barrier(); assertEquals(3, f.platform.factories.get()); assertTrue(f.timer.retryPending().isEmpty())
        assertEquals(identity, f.rig.owner.snapshot().get().state.identity)
        assertTrue(f.wire.entered.await(3, TimeUnit.SECONDS)); assertEquals(1, f.wire.requests.size)
        assertTrue(f.trace.isEmpty())
    }

    @Test fun `backoff caps at thirty seconds and repeated evaluations never create a second timer`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start()
        for ((index, delay) in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L).withIndex()) {
            f.pending(delay)
            repeat(3) { f.owner.reevaluate().get(3, TimeUnit.SECONDS) }
            assertEquals(1, f.timer.retryPending().size); assertEquals(index + 1, f.platform.collections.get())
            if (index < 6) { f.timer.advance(delay - 1); f.barrier(); assertEquals(index + 1, f.platform.collections.get()); f.timer.advance(1) }
        }
        assertTrue(f.rig.rows().isEmpty())
    }

    @Test fun `withdrawal cancels pending timer and rejects its physically late callback`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); val original = f.pending(1_000)
        f.owner.withdrawAll(); assertTrue(original.isCancelled); original.forceOriginal(); f.barrier()
        assertTrue(f.timer.retryPending().isEmpty()); assertEquals(1, f.platform.factories.get()); assertTrue(f.rig.rows().isEmpty())
    }

    @Test fun `close cancels pending timer and late callback cannot restart executor`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); val original = f.pending(1_000)
        f.owner.closeAndWait().get(3, TimeUnit.SECONDS); assertTrue(original.isCancelled); original.forceOriginal()
        assertTrue(f.worker.awaitTermination(3, TimeUnit.SECONDS)); assertEquals(1, f.platform.factories.get())
    }

    @Test fun `forced same key geometry refusal invalidates old timer and late old completion`() = RetryFixture().use { f ->
        val collecting = CountDownLatch(1); val release = CountDownLatch(1)
        f.platform.onCollect = {
            if (f.platform.collections.get() == 1) { collecting.countDown(); check(release.await(3, TimeUnit.SECONDS)); f.timeout() }
            else throw NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
        }
        f.start(); assertTrue(collecting.await(3, TimeUnit.SECONDS))
        fun field(name: String): Any = NativeReplayComposition::class.java.getDeclaredField(name)
            .also { it.isAccessible = true }.get(f.owner)
        val oldOwner = field("capture"); val oldKey = field("lastAttempt")
        val oldAttempt = field("captureAttempt"); val oldIntent = field("intent")
        release.countDown(); val oldTimer = f.pending(1_000)
        f.owner.reevaluate(force = true).get(3, TimeUnit.SECONDS)
        awaitCondition("replacement geometry refusal retired") { f.access.closes.get() == 2 }; f.barrier()
        assertTrue(oldTimer.isCancelled); assertEquals(2, f.platform.factories.get())
        // Deliberately deliver the original completion after the replacement is fully retired.
        NativeReplayComposition::class.java.declaredMethods.single { it.name == "retrySettledDeadline" }
            .also { it.isAccessible = true }.invoke(f.owner, oldOwner, oldKey, oldAttempt, oldIntent, { true })
        oldTimer.forceOriginal(); f.barrier()
        assertEquals(1, f.timer.retryTasks().size); assertTrue(f.timer.retryPending().isEmpty())
        assertEquals(2, f.platform.factories.get()); assertTrue(f.rig.rows().isEmpty())
    }

    @Test fun `forced same key deadline replaces timer and stale wake cannot cancel the replacement`() = RetryFixture().use { f ->
        f.platform.onCollect = { if (f.platform.collections.get() <= 2) f.timeout() }
        f.start(); val first = f.pending(1_000)
        f.owner.reevaluate(force = true).get(3, TimeUnit.SECONDS)
        val replacement = f.pending(2_000)
        assertTrue(first.isCancelled); assertNotSame(first, replacement); assertEquals(2, f.timer.retryTasks().size)
        first.forceOriginal(); f.barrier(); assertSame(replacement, f.timer.retryPending().single())
        f.timer.advance(2_000)
        awaitCondition("replacement timer commits one new capture") { f.rig.rows().size == 1 }; f.barrier()
        assertEquals(3, f.platform.factories.get()); assertTrue(f.timer.retryPending().isEmpty())
    }

    @Test fun `source expiry before retry denies new collector and transport`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); f.pending(1_000)
        f.rig.advance(600); f.timer.advance(1_000); f.barrier(); f.barrier()
        assertEquals(1, f.platform.factories.get()); assertTrue(f.timer.retryPending().isEmpty()); assertTrue(f.wire.requests.isEmpty())
    }

    @Test fun `source rollback before retry remains denied`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); f.pending(1_000)
        f.rig.clock.nanos = 0L; f.rig.clock.wall -= 1
        f.timer.advance(1_000); f.barrier(); f.barrier()
        assertEquals(1, f.platform.factories.get()); assertTrue(f.rig.rows().isEmpty()); assertTrue(f.timer.retryPending().isEmpty())
    }

    @Test fun `consent change before retry cannot reuse original scheduling identity`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); f.pending(1_000)
        f.rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, f.rig.now())).get()
        f.timer.advance(1_000); f.barrier(); f.barrier()
        assertEquals(1, f.platform.factories.get()); assertTrue(f.rig.rows().isEmpty()); assertTrue(f.timer.retryPending().isEmpty())
    }

    @Test fun `reset before retry cannot relabel the original attempt`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); f.pending(1_000)
        f.rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(f.rig.now())).get()
        f.timer.advance(1_000); f.barrier(); assertEquals(1, f.platform.factories.get())
        assertTrue(f.rig.rows().isEmpty()); assertTrue(f.timer.retryPending().isEmpty())
    }

    @Test fun `original acceptance loss rejects a due retry`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); f.pending(1_000); f.accepted.set(false)
        f.timer.advance(1_000); f.barrier(); assertEquals(1, f.platform.factories.get()); assertTrue(f.timer.retryPending().isEmpty())
    }

    @Test fun `unsupported geometry remains a same-identity refusal without timer`() = RetryFixture().use { f ->
        f.platform.onCollect = { throw NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY) }
        f.start(); awaitCondition("original watcher retired") { f.access.closes.get() == 1 }; f.barrier()
        f.owner.reevaluate().get(3, TimeUnit.SECONDS)
        assertEquals(1, f.platform.factories.get()); assertTrue(f.timer.retryTasks().isEmpty()); assertTrue(f.rig.rows().isEmpty())
    }

    @Test fun `permission withdrawal during collector is never classified as elapsed retry`() = RetryFixture().use { f ->
        f.platform.onCollect = { f.rig.source.withdraw() }
        f.start(); awaitCondition("withdrawn watcher retired") { f.access.closes.get() == 1 }; f.barrier()
        assertEquals(1, f.platform.factories.get()); assertTrue(f.timer.retryTasks().isEmpty()); assertTrue(f.rig.rows().isEmpty())
    }

    @Test fun `partial deadline retains original composition watcher enrollment and sealed delivery`() = RetryFixture().use { f ->
        val reachedRetry = CountDownLatch(1); val releaseRetry = CountDownLatch(1)
        val prefix = AtomicReference<ByteArray>()
        f.platform.onCollect = { if (f.platform.collections.get() == 2) {
            prefix.set(f.rig.rows().single().prepared.copyBytes()); f.timeout()
        } }
        f.platform.onPause = {
            if (f.platform.collections.get() == 2) {
                reachedRetry.countDown(); check(releaseRetry.await(3, TimeUnit.SECONDS))
            }
            if (f.platform.collections.get() >= 3) false else { f.rig.clock.wall += 10_000; f.rig.clock.nanos += 10_000_000_000; true }
        }
        f.start()
        try {
            assertTrue(reachedRetry.await(3, TimeUnit.SECONDS)); f.barrier()
            assertEquals(1, f.access.watches.get()); assertEquals(0, f.access.closes.get())
            assertNull(f.rig.owner.enrollNativeReplayCapture().get()); assertTrue(f.timer.retryTasks().isEmpty())
            assertEquals(1, f.platform.factories.get())
        } finally { releaseRetry.countDown() }
        awaitCondition("original capture settles after successful retry") { f.access.closes.get() == 1 }; f.barrier()
        val rows = f.rig.rows(); assertEquals(3, f.platform.collections.get()); assertEquals(2, rows.size)
        assertArrayEquals(prefix.get(), rows[0].prepared.copyBytes())
        assertEquals(rows[0].prepared.replayId, rows[1].prepared.replayId)
        assertEquals(listOf(0L, 1L), rows.map { it.prepared.sequence })
        assertTrue(f.timer.retryTasks().isEmpty()); assertEquals(1, f.platform.factories.get())
    }

    @Test fun `watcher cleanup uncertainty forbids timer and replacement`() = RetryFixture().use { f ->
        f.quarantineExpected = true
        f.access.onClose = { throw IllegalStateException("Original watcher cleanup unknown") }
        f.platform.onCollect = f::timeout; f.start()
        awaitCondition("original watcher cleanup attempted") { f.access.closes.get() == 1 }; f.barrier()
        assertEquals(1, f.platform.factories.get()); assertTrue(f.timer.retryTasks().isEmpty()); assertTrue(f.rig.rows().isEmpty())
    }

    @Test fun `retry snapshot failure is retained and permanently quarantines further evaluation`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); f.pending(1_000)
        f.rig.owner.closeAsync().get(3, TimeUnit.SECONDS)
        f.quarantineExpected = true
        f.timer.advance(1_000); f.barrier()
        assertEquals(NativeReplayCompositionEvaluation.QUARANTINED, f.owner.reevaluate(true).get(3, TimeUnit.SECONDS))
        assertEquals(1, f.platform.factories.get()); assertTrue(f.timer.retryPending().isEmpty())
        val error = try { f.owner.closeAndWait().get(3, TimeUnit.SECONDS); error("Expected retained snapshot failure") }
            catch (error: ExecutionException) { error.cause }
        assertNotNull(error); assertFalse(error!!.message == "Native replay cleanup is quarantined")
    }

    @Test fun `retry acceptance exception preserves original error through cleanup and close`() = RetryFixture().use { f ->
        f.platform.onCollect = f::timeout; f.start(); f.pending(1_000)
        val original = IllegalStateException("Original retry acceptance failure")
        f.acceptedFailure = original; f.quarantineExpected = true
        f.timer.advance(1_000); f.barrier()
        assertEquals(NativeReplayCompositionEvaluation.QUARANTINED, f.owner.reevaluate(true).get(3, TimeUnit.SECONDS))
        assertEquals(1, f.platform.factories.get()); assertTrue(f.timer.retryPending().isEmpty())
        try { f.owner.closeAndWait().get(3, TimeUnit.SECONDS); fail("Expected original error") }
        catch (error: ExecutionException) { assertSame(original, error.cause) }
    }

    @Test fun `exact fifty milliseconds still permits the complete original frame`() = RetryFixture().use { f ->
        f.platform.onCollect = { f.rig.clock.nanos += 50_000_000L }; f.start()
        awaitCondition("exact-bound frame committed") { f.rig.rows().size == 1 }; f.barrier()
        assertEquals(1, f.platform.collections.get()); assertTrue(f.timer.retryTasks().isEmpty())
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
