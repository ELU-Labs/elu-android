package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual owner/authority/sealer/queue; synthetic platform facts and masked collection, fake DB. */
class NativeReplayCaptureOwnerTest {
    private class MainAccess : NativeReplaySelectionAccess, AutoCloseable {
        @Volatile var main: Thread? = null
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "fixture-native-main").also { main = it } }
        val window = Any(); val token = Any()
        @Volatile var valid = true
        override fun onMain(action: () -> Unit) { if (Thread.currentThread() === main) action() else executor.execute(action) }
        override fun observe(activity: Any, root: Any, current: () -> Boolean): NativeReplayRootFacts? {
            check(Thread.currentThread() === main)
            return if (valid && current()) NativeReplayRootFacts(window, token, 100, 200, 1f, 36) else null
        }
        override fun watch(root: Any, withdrawn: () -> Unit) = AutoCloseable { }
        override fun close() { executor.shutdown(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
    }
    private class Session(val rig: Rig, proof: NativeReplayCapabilities? = null) : AutoCloseable {
        val access = MainAccess(); val lifecycle = NativeReplayLifecycle(access)
        val activity = Any(); val root = Any()
        val selection = run { lifecycle.resumed(activity); checkNotNull(lifecycle.select(activity, root).get()) }
        val authority = NativeReplayAuthority(rig.owner, proof ?: NativeReplayCapabilities(setOf(
            V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)), setOf(ReplayFixtures.GENERATION)), { false })
        fun prepare() = authority.prepare(selection).get(3, TimeUnit.SECONDS)
        fun start(prepared: NativeReplayPreparedAuthority, platform: Platform, trace: NativeStartTrace = NativeStartTrace.NONE): NativeReplayCaptureOwner =
            checkNotNull(NativeReplayCaptureOwner.start(rig.owner, authority, prepared, StandaloneRuntime.defaultVersions(), platform, trace))
        override fun close() { authority.close(); selection.close(); access.close() }
    }
    private class Platform(val rig: Rig, val access: MainAccess, val maximumSamples: Int = 1) : NativeReplayCapturePlatform {
        override var apiLevel = 36
        var privacyRevision = Any()
        override fun privacyWitness(): () -> Boolean = privacyRevision.let { original -> { privacyRevision === original } }
        val collections = AtomicInteger(); val factories = AtomicInteger(); val profileFactories = AtomicInteger(); val pauses = AtomicInteger()
        val timestamps = CopyOnWriteArrayList<Long>(); val ordinals = CopyOnWriteArrayList<Long>()
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        var onCollect: ((() -> Boolean) -> Unit)? = null
        var onPause: ((CountDownLatch) -> Boolean)? = null
        var onFactory: (() -> Unit)? = null
        var transformFrame: ((NativeMaskedSnapshot) -> NativeMaskedSnapshot)? = null
        val identity = UUID.randomUUID()
        override fun createCollector(): NativeReplayCaptureCollector {
            check(Thread.currentThread() === access.main); factories.incrementAndGet(); onFactory?.invoke()
            return NativeReplayCaptureCollector { _, ordinal, timestamp, _, current, unresolved ->
                check(Thread.currentThread() === access.main); check(!unresolved); check(current())
                collections.incrementAndGet(); timestamps += timestamp; ordinals += ordinal
                onCollect?.invoke(current); check(current())
                val frame = NativeMaskedSnapshot(ordinal, timestamp, NativeViewport(100, 200), listOf(
                    NativeMaskedNode(identity, NativeMaskedKind.Rectangle, NativeRect(0.0, 0.0, 10.0, 10.0), NativeRect(0.0, 0.0, 10.0, 10.0))))
                transformFrame?.invoke(frame) ?: frame
            }
        }
        override fun createCollector(profile: NativeCapturePassProfile): NativeReplayCaptureCollector {
            profileFactories.incrementAndGet()
            return createCollector()
        }
        override fun awaitNext(withdrawn: CountDownLatch): Boolean {
            check(Thread.currentThread() !== access.main); worker.set(Thread.currentThread()); pauses.incrementAndGet()
            onPause?.let { return it(withdrawn) }
            if (collections.get() >= maximumSamples) return false
            rig.advance(); return true
        }
    }
    private fun Rig.minimum(seconds: Int) = configure { it.getJSONObject("privacy").getJSONObject("replay").put("minimumDurationSeconds", seconds) }
    private fun Rig.rows(): List<ReplayStoredChunk> = owner.storedPreparedReplayForTesting().get()

    @Test fun `stronger local privacy discards buffered readable frames before seal`(): Unit = Rig().use { rig ->
        rig.minimum(2)
        rig.configure { it.getJSONObject("privacy").getJSONObject("masking").put("text", "sensitive")
            .put("platformRules", org.json.JSONArray()) }
        rig.activate(); Session(rig).use { session ->
            val prepared = checkNotNull(session.prepare()); val platform = Platform(rig, session.access, maximumSamples = 3)
            platform.transformFrame = { frame -> NativeMaskedSnapshot(frame.ordinal, frame.timestamp, frame.viewport, frame.nodes.map {
                it.copy(kind = NativeMaskedKind.ReadableText(NativeReplayText.read("Previously readable")))
            }) }
            platform.onPause = { platform.privacyRevision = Any(); rig.advance(); true }
            val owner = session.start(prepared, platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(1, platform.collections.get()); assertTrue(rig.rows().isEmpty())
        }
    }

    @Test fun `zero minimum actual sealer and queue preserve original timestamp and immutable body`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val prepared = checkNotNull(session.prepare()); val clock = rig.clock.wall; val platform = Platform(rig, session.access)
            val owner = session.start(prepared, platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            val row = rig.rows().single(); val body = row.prepared.copyBytes(); val json = JSONObject(String(body))
            assertEquals(clock, platform.timestamps.single()); assertEquals(listOf(0L), platform.ordinals)
            assertEquals(RuntimeWallTimestamps.rfc3339(clock), json.getJSONObject("chunk").getString("startedAt"))
            assertEquals(0L, json.getJSONObject("chunk").getLong("sequence")); assertNull(rig.state().session?.activeEpoch)
            assertEquals(1, platform.factories.get()); assertEquals(0, platform.profileFactories.get()); assertFalse(owner.finished().cancel(true))
            assertEquals(NativeReplayCaptureCompletion(NativeCaptureStage.WAIT_NEXT, 1, null,
                NativeReplayCaptureOutcome.SETTLED), owner.completedDiagnostic())
            val replacement = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); replacement.cancelUnused()
            assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(replacement).get())
            assertArrayEquals(body, rig.rows().single().prepared.copyBytes())
        }
    }
    @Test fun `explicit internal trace selects profile collector while default capture has no profile`() = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val records = CopyOnWriteArrayList<ByteArray>()
            val trace = BoundedNativeStartObserver.create { records += it }.begin()
            val platform = Platform(rig, session.access)
            val owner = session.start(checkNotNull(session.prepare()), platform, trace)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(1, platform.profileFactories.get()); assertEquals(1, platform.collections.get())
            assertEquals(1, rig.rows().size)
            assertTrue(records.any { JSONObject(String(it, Charsets.US_ASCII)).getString("phase") == "CAPTURE_LOOP_READY" })
        }
    }
    @Test fun `positive minimum holds initial latest and subsequent prefixes keep exact cadence`(): Unit = Rig().use { rig ->
        rig.minimum(3); rig.activate(); Session(rig).use { session ->
            val initial = rig.clock.wall; val platform = Platform(rig, session.access, 14)
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(5, TimeUnit.SECONDS))
            val rows = rig.rows(); assertEquals(2, rows.size)
            assertEquals(listOf(0L, 1L, 1L, 1L) + (2L..11L).toList(), platform.ordinals.toList())
            assertEquals(listOf(0L, 1L), rows.map { it.prepared.sequence })
            val first = JSONObject(String(rows[0].prepared.copyBytes())).getJSONObject("chunk")
            assertEquals(RuntimeWallTimestamps.rfc3339(initial), first.getString("startedAt"))
            assertEquals(RuntimeWallTimestamps.rfc3339(initial + 3000), first.getString("endedAt"))
            assertEquals(1, platform.factories.get()); assertEquals(14, platform.pauses.get())
        }
    }
    @Test fun `API23 to28 denies before thread enrollment and persistable guard`(): Unit = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val prepared = checkNotNull(session.prepare()); val row = rig.row().payload.copyOf()
            rig.clock.throwOnRead = true
            for (api in 23..28) {
                val platform = Platform(rig, session.access).also { it.apiLevel = api }
                assertNull(NativeReplayCaptureOwner.start(rig.owner, session.authority, prepared, StandaloneRuntime.defaultVersions(), platform))
                assertEquals(0, platform.factories.get())
            }
            rig.clock.throwOnRead = false; assertArrayEquals(row, rig.row().payload)
            val e = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); e.cancelUnused(); rig.owner.finishNativeReplayCapture(e).get(); Unit
        }
    }
    @Test fun `empty or mismatched local proof cannot prepare a physical owner`(): Unit = Rig().use { rig ->
        rig.activate()
        for (caps in listOf(NativeReplayCapabilities(), NativeReplayCapabilities(setOf(
            V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)), setOf("wrong")))) {
            Session(rig, caps).use { assertNull(it.prepare()) }
        }
        assertEquals(0L, rig.state().nextReplayOrdinal); assertTrue(rig.rows().isEmpty())
    }
    @Test fun `mixed queue authority is denied before enrollment`(): Unit = Rig().use { first -> Rig().use { second ->
        first.activate(); second.activate(); Session(first).use { session ->
            val prepared = checkNotNull(session.prepare()); val platform = Platform(second, session.access)
            assertNull(NativeReplayCaptureOwner.start(second.owner, session.authority, prepared, StandaloneRuntime.defaultVersions(), platform))
            assertEquals(0, platform.collections.get()); assertEquals(0L, second.state().nextReplayOrdinal)
        }
    } }
    @Test fun `occupied enrollment never consumes prepared clock or stops original authority`(): Unit = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val prepared = checkNotNull(session.prepare()); val original = checkNotNull(rig.owner.enrollNativeReplayCapture().get())
            val use = checkNotNull(original.takePhysicalUse()); val platform = Platform(rig, session.access)
            rig.ownerNanos = -1 // A guard would persist denial; occupied enrollment does not consume it.
            val owner = session.start(prepared, platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertTrue(use.isCurrent()); assertFalse(rig.state().session!!.clockDenied); assertEquals(0, platform.factories.get())
            rig.ownerNanos = null; use.settle(); assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(original).get())
        }
    }
    @Test fun `persistable prepared denial after enrollment is settled even without a submitted start`(): Unit = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val prepared = checkNotNull(session.prepare()); rig.ownerNanos = -1
            val platform = Platform(rig, session.access); val owner = session.start(prepared, platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertTrue(rig.state().session!!.clockDenied); assertEquals(0, platform.collections.get()); rig.ownerNanos = null
            val e = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); e.cancelUnused(); rig.owner.finishNativeReplayCapture(e).get(); Unit
        }
    }
    @Test fun `start associates original use before source observation can close queue`(): Unit = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val prepared = checkNotNull(session.prepare()); val e = checkNotNull(rig.owner.enrollNativeReplayCapture().get())
            val use = checkNotNull(e.takePhysicalUse()); var closing: Future<Unit>? = null
            rig.onOwnerClock = { closing = rig.owner.closeAsync() }
            assertNull(session.authority.start(prepared, use).get(3, TimeUnit.SECONDS))
            assertNotNull(closing); assertFalse(closing!!.isDone); use.settle()
            assertEquals(NativeReplayAuthorityStop.SETTLED, session.authority.stop().get(3, TimeUnit.SECONDS))
            assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(e).get(3, TimeUnit.SECONDS))
            closing!!.get(3, TimeUnit.SECONDS)
        }
    }
    @Test fun `held main work prevents stop settlement and replacement until it actually returns`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val platform = Platform(rig, session.access)
            platform.onCollect = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            val owner = session.start(checkNotNull(session.prepare()), platform); check(entered.await(3, TimeUnit.SECONDS))
            assertNull(owner.completedDiagnostic())
            val stopped = owner.stop(); assertFalse(stopped.cancel(true)); assertFalse(stopped.isDone)
            assertNull(owner.completedDiagnostic())
            assertNull(rig.owner.enrollNativeReplayCapture().get()); assertTrue(rig.rows().isEmpty())
            release.countDown(); assertEquals(NativeReplayCaptureOutcome.SETTLED, stopped.get(3, TimeUnit.SECONDS))
            assertTrue(rig.rows().isEmpty()); assertNull(rig.state().session!!.activeEpoch)
            assertNotNull(owner.completedDiagnostic())
        }
    }
    @Test fun `completed diagnostic retains only actual closed collection failure taxonomy`() {
        val cases = NativeCollectionFailure.values().map { failure ->
            Triple(NativeCollectionException(failure), NativeCaptureFailureKind.valueOf(failure.name), 0L)
        } + listOf(
            Triple(NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY, NativeAncestorFailure.FRAMEWORK_ACTION_BAR),
                NativeCaptureFailureKind.GEOMETRY_ACTION_BAR_ANCESTOR, 0L),
            Triple(NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY, NativeAncestorFailure.OTHER),
                NativeCaptureFailureKind.GEOMETRY_UNKNOWN_ANCESTOR, 0L),
            Triple(IllegalStateException("Private fixture text must never enter the record"), NativeCaptureFailureKind.OTHER, 0L),
            Triple(NativeCollectionException(NativeCollectionFailure.WITHDRAWN), NativeCaptureFailureKind.PASS_DEADLINE, 50_000_001L),
            Triple(NativeCollectionException(NativeCollectionFailure.WITHDRAWN), NativeCaptureFailureKind.PASS_CLOCK_REVERSED, -1L),
        )
        for ((failure, expected, nanos) in cases) Rig().use { rig ->
            rig.minimum(0); rig.activate(); Session(rig).use { session ->
                val platform = Platform(rig, session.access)
                platform.onCollect = { current ->
                    if (nanos < 0L) {
                        // The first sample belongs to rootCurrent's original
                        // authority guard. Reverse only the following pass sample.
                        var reads = 0
                        rig.onOwnerNanos = { if (++reads == 2) rig.clock.nanos + nanos else rig.clock.nanos }
                    } else if (nanos > 0L) rig.ownerNanos = rig.clock.nanos + nanos
                    if (nanos != 0L) assertFalse(current())
                    throw failure
                }
                val owner = session.start(checkNotNull(session.prepare()), platform)
                val outcome = owner.finished().get(3, TimeUnit.SECONDS)
                assertEquals(if (expected == NativeCaptureFailureKind.PASS_DEADLINE)
                    NativeReplayCaptureOutcome.SETTLED_PASS_DEADLINE else NativeReplayCaptureOutcome.SETTLED, outcome)
                assertEquals(NativeReplayCaptureCompletion(NativeCaptureStage.COLLECTOR, 0, expected, outcome), owner.completedDiagnostic())
                assertTrue(rig.rows().isEmpty()); assertNull(rig.state().session!!.activeEpoch)
                assertEquals(0, platform.profileFactories.get())
            }
        }
        assertEquals(setOf("stage", "frames", "failure", "outcome"), NativeReplayCaptureCompletion::class.java.declaredFields.map { it.name }.toSet())
    }
    @Test fun `held append Future preserves physical occupancy and exact committed prefix after stop`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            rig.onReplayWrite = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            val platform = Platform(rig, session.access, 3); val owner = session.start(checkNotNull(session.prepare()), platform)
            check(entered.await(3, TimeUnit.SECONDS)); val stopped = owner.stop(); assertFalse(stopped.isDone)
            release.countDown(); assertEquals(NativeReplayCaptureOutcome.SETTLED, stopped.get(3, TimeUnit.SECONDS))
            assertEquals(1, rig.rows().size); assertEquals(1, platform.collections.get()); assertNull(rig.state().session!!.activeEpoch)
        }
    }
    @Test fun `source withdrawal inside collection discards frame without inventing prefix`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access)
            platform.onCollect = { current -> rig.driver.onBackground(); assertFalse(current()) }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS)); assertTrue(rig.rows().isEmpty())
        }
    }
    @Test fun `committed then withdrawn keeps original bytes and emits no suffix`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access, 3)
            rig.afterReplayCommit = { rig.driver.onBackground() }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(1, rig.rows().size); assertEquals(1, platform.collections.get())
        }
    }
    @Test fun `withdrawal below positive minimum never seals initial only`(): Unit = Rig().use { rig ->
        rig.minimum(5); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access, 3); val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(listOf(0L, 1L, 1L), platform.ordinals.toList()); assertTrue(rig.rows().isEmpty())
        }
    }
    @Test fun `cooperative pass ceiling denies after fifty milliseconds without claiming preemption`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access)
            platform.onCollect = { current -> rig.clock.nanos += 50_000_001; assertFalse(current()) }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS)); assertTrue(rig.rows().isEmpty())
        }
    }
    @Test fun `queue close while collecting waits exact physical and accounting settlement`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val platform = Platform(rig, session.access)
            platform.onCollect = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            val owner = session.start(checkNotNull(session.prepare()), platform); check(entered.await(3, TimeUnit.SECONDS))
            val closed = rig.owner.closeAsync(); assertFalse(closed.isDone); assertFalse(owner.finished().isDone)
            release.countDown(); assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            closed.get(3, TimeUnit.SECONDS); assertTrue(rig.backing.replayRows.keys.none { it.startsWith("chunk/") })
        }
    }
    @Test fun `unknown committed append retains exact original request in quarantine without suffix`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access, 5)
            rig.onReplayWrite = {
                rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
                rig.onConnection = { throw java.io.IOException("Unresolved original append reopen") }
            }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.QUARANTINED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(NativeReplayCaptureOutcome.QUARANTINED, owner.completedDiagnostic()?.outcome)
            assertEquals(1, platform.collections.get()); assertEquals(0, platform.pauses.get())
            val e = RuntimeQueueOwner::class.java.getDeclaredField("nativeCaptureEnrollment").also { it.isAccessible = true }
                .get(rig.owner) as NativeReplayCaptureEnrollment
            assertTrue(e.physicalIsFinished()); assertTrue(e.isQuarantined())
            val resources = NativeReplayCaptureEnrollment::class.java.getDeclaredField("resources").also { it.isAccessible = true }.get(e)
            val retained = NativeReplayCaptureResources::class.java.getDeclaredField("prepared").also { it.isAccessible = true }
                .get(resources) as PreparedReplayRequest
            val db = rig.backing.connection()
            val stored = db.transaction { tx -> ReplayQueueStore.read(tx, ReplayQueueStore.headers(tx).single()) }
            db.close()
            assertArrayEquals(stored.prepared.copyBytes(), retained.copyBytes())
            assertEquals(stored.prepared.requestId, retained.requestId)
            failure { rig.openSame().get(3, TimeUnit.SECONDS) }
        }
    }
    @Test fun `resolved ambiguous commit keeps stable bytes and known prefix may continue`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access, 11)
            rig.onReplayWrite = { rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(5, TimeUnit.SECONDS))
            val rows = rig.rows(); assertEquals(listOf(0L, 1L), rows.map { it.prepared.sequence })
            assertEquals(2, rows.size); assertEquals(11, platform.collections.get())
            assertNull(rig.state().session!!.activeEpoch)
        }
    }
    @Test fun `sealer request ceiling failure stops before append without substituting a binding`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.configure { it.getJSONObject("limits").put("replayChunkBytes", 1024) }; rig.activate()
        Session(rig).use { session ->
            val platform = Platform(rig, session.access, 3)
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(1, platform.collections.get()); assertEquals(0, platform.pauses.get()); assertTrue(rig.rows().isEmpty())
            assertNull(rig.state().session!!.activeEpoch)
        }
    }
    @Test fun `budget expiry retires fresh loop while keeping sealed prefix and capture channel`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.configure { it.getJSONObject("privacy").getJSONObject("replay").put("maximumDurationSeconds", 5) }; rig.activate()
        Session(rig).use { session ->
            val platform = Platform(rig, session.access, 3)
            platform.onPause = { rig.advance(5); true }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(1, rig.rows().size); assertEquals(1, platform.collections.get())
            assertTrue(rig.owner.capture(rig.event()).get(3, TimeUnit.SECONDS) is RuntimeCaptureResult.Accepted)
        }
    }
    @Test fun `original source expiry stops fresh work and preserves prior sealed bytes`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access, 3)
            platform.onPause = { rig.advance(1000); true }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(1, rig.rows().size); assertEquals(1, platform.collections.get())
            assertNull(rig.state().session!!.activeEpoch)
        }
    }
    @Test fun `identity and opt-out during collection revoke original frame and cannot rebind`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access)
            platform.onCollect = { current ->
                rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get(3, TimeUnit.SECONDS)
                rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, rig.now())).get(3, TimeUnit.SECONDS)
                assertFalse(current())
            }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertTrue(rig.rows().isEmpty()); assertTrue(rig.owner.snapshot().get().state.identity.optedOut)
        }
    }
    @Test fun `worker interruption cannot abandon held original main operation`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val platform = Platform(rig, session.access, 3)
            platform.onCollect = { if (platform.collections.get() == 2) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
            val owner = session.start(checkNotNull(session.prepare()), platform); check(entered.await(3, TimeUnit.SECONDS))
            val worker = checkNotNull(platform.worker.get()); worker.interrupt()
            val stopped = owner.stop(); assertFalse(stopped.isDone); assertNull(rig.owner.enrollNativeReplayCapture().get())
            release.countDown(); assertEquals(NativeReplayCaptureOutcome.SETTLED, stopped.get(3, TimeUnit.SECONDS))
            worker.join(3000); assertFalse(worker.isAlive)
            assertEquals(1, rig.rows().size); assertEquals(2, platform.collections.get())
        }
    }
    @Test fun `same original queue clock object supplies capture samples`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val sourceClock = rig.owner.nativeReplayCaptureClock()
            assertSame(sourceClock, rig.owner.nativeReplayCaptureClock())
            val platform = Platform(rig, session.access)
            platform.onCollect = { assertEquals(sourceClock.wallNowEpochMillis(), platform.timestamps.last()) }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(RuntimeWallTimestamps.rfc3339(rig.clock.wall), rig.rows().single().prepared.startedAt)
        }
    }
    @Test fun `foreign prepared owner is rejected before its denied clock can be consumed`(): Unit = Rig().use { first -> Rig().use { second ->
        first.activate(); second.activate(); Session(first).use { a -> Session(second).use { b ->
            val foreign = checkNotNull(b.prepare()); val platform = Platform(first, a.access)
            second.ownerNanos = -1
            val unexpected = NativeReplayCaptureOwner.start(first.owner, a.authority, foreign, StandaloneRuntime.defaultVersions(), platform)
            unexpected?.finished()?.get(3, TimeUnit.SECONDS)
            second.ownerNanos = null; second.owner.flushNativeReplayClockDenial().get(3, TimeUnit.SECONDS)
            assertNull(unexpected); assertFalse(second.state().session!!.clockDenied)
            assertEquals(0, platform.factories.get()); assertEquals(0L, first.state().nextReplayOrdinal)
        } }
    } }
    @Test fun `stale prepared invocation cannot observe a new denial before rejecting start`(): Unit = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val stale = checkNotNull(session.prepare()); assertNotNull(session.prepare())
            rig.ownerNanos = -1; val platform = Platform(rig, session.access)
            val unexpected = NativeReplayCaptureOwner.start(rig.owner, session.authority, stale, StandaloneRuntime.defaultVersions(), platform)
            unexpected?.finished()?.get(3, TimeUnit.SECONDS)
            rig.ownerNanos = null; rig.owner.flushNativeReplayClockDenial().get(3, TimeUnit.SECONDS)
            assertNull(unexpected); assertFalse(rig.state().session!!.clockDenied); assertEquals(0, platform.collections.get())
        }
    }

    @Test fun `known append wake runs outside owner and main and cannot abort later capture`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val calls = AtomicInteger(); val observedFailure = AtomicReference<Throwable?>(); val platform = Platform(rig, session.access, 11)
            val owner = checkNotNull(NativeReplayCaptureOwner.start(rig.owner, session.authority, checkNotNull(session.prepare()),
                StandaloneRuntime.defaultVersions(), platform) {
                try {
                    assertNotSame(session.access.main, Thread.currentThread())
                    assertFalse(rig.owner.isCurrentThreadWorker())
                    assertEquals(calls.incrementAndGet(), rig.rows().size)
                } catch (error: Throwable) { observedFailure.compareAndSet(null, error) }
                throw IllegalStateException("Notification consumer failed")
            })
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(5, TimeUnit.SECONDS))
            observedFailure.get()?.let { throw it }
            assertEquals(2, calls.get()); assertEquals(2, rig.rows().size); assertEquals(1, platform.factories.get())
        }
    }
    @Test fun `known withdrawn commit still wakes sealed delivery`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val calls = AtomicInteger(); val platform = Platform(rig, session.access)
            rig.afterReplayCommit = { rig.driver.onBackground() }
            val owner = checkNotNull(NativeReplayCaptureOwner.start(rig.owner, session.authority, checkNotNull(session.prepare()),
                StandaloneRuntime.defaultVersions(), platform) { calls.incrementAndGet(); Unit })
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get()); assertEquals(1, rig.rows().size)
        }
    }
    @Test fun `unresolved append retains original request without emitting commit notification`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val calls = AtomicInteger(); val platform = Platform(rig, session.access, 3)
            rig.onReplayWrite = { rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
                rig.onConnection = { throw java.io.IOException("Unresolved original append") } }
            val owner = checkNotNull(NativeReplayCaptureOwner.start(rig.owner, session.authority, checkNotNull(session.prepare()),
                StandaloneRuntime.defaultVersions(), platform) { calls.incrementAndGet(); Unit })
            assertEquals(NativeReplayCaptureOutcome.QUARANTINED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(0, calls.get()); assertEquals(1, platform.collections.get())
        }
    }
    private fun expireCollectorPass(rig: Rig, current: () -> Boolean): Nothing {
        rig.clock.nanos += 50_000_001L
        assertFalse(current())
        throw NativeCollectionException(NativeCollectionFailure.WITHDRAWN)
    }
    private fun wireTimestamps(row: ReplayStoredChunk): Set<Long> {
        val payload = JSONObject(String(row.prepared.copyBytes(), Charsets.UTF_8)).getJSONObject("chunk").getString("payload")
        val decoded = GZIPInputStream(ByteArrayInputStream(ReplayBase64.decode(payload))).use { it.readBytes() }
        val events = JSONArray(String(decoded, Charsets.UTF_8))
        return (0 until events.length()).map { events.getJSONObject(it).getLong("timestamp") }.toSet()
    }

    @Test fun `partial deadline retains initial frame and original enrollment until prefix commit`(): Unit = Rig().use { rig ->
        rig.minimum(2); rig.activate(); Session(rig).use { session ->
            val initial = rig.clock.wall; val platform = Platform(rig, session.access, 3)
            val waiting = CountDownLatch(1); val release = CountDownLatch(1)
            platform.onCollect = { current -> if (platform.collections.get() == 2) expireCollectorPass(rig, current) }
            platform.onPause = { withdrawn ->
                if (platform.collections.get() == 2) {
                    waiting.countDown(); check(release.await(3, TimeUnit.SECONDS)); assertEquals(1L, withdrawn.count)
                }
                if (platform.collections.get() >= 3) false else { rig.advance(); true }
            }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            try {
                assertTrue(waiting.await(3, TimeUnit.SECONDS)); assertFalse(owner.finished().isDone)
                assertTrue(session.selection.isCurrent()); assertNotNull(rig.state().session!!.activeEpoch)
                assertEquals(1L, rig.state().nextReplayOrdinal); assertNull(rig.owner.enrollNativeReplayCapture().get())
                assertTrue(rig.rows().isEmpty())
            } finally { release.countDown() }
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            val row = rig.rows().single()
            assertEquals(listOf(0L, 1L, 1L), platform.ordinals.toList())
            assertEquals(setOf(initial, initial + 2000), wireTimestamps(row))
            assertEquals(2, owner.completedDiagnostic()!!.frames); assertNull(owner.completedDiagnostic()!!.failure)
            assertEquals(1, platform.factories.get()); assertEquals(1L, rig.state().nextReplayOrdinal)
            assertNull(rig.state().session!!.activeEpoch)
        }
    }

    @Test fun `partial deadline preserves committed bytes and continues exact uncommitted suffix`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val initial = rig.clock.wall; val platform = Platform(rig, session.access, 11)
            val prefix = AtomicReference<ByteArray>(); val request = AtomicReference<String>()
            platform.onCollect = { current -> if (platform.collections.get() == 2) {
                val original = rig.rows().single().prepared
                prefix.set(original.copyBytes()); request.set(original.requestId)
                expireCollectorPass(rig, current)
            } }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            val rows = rig.rows(); assertEquals(2, rows.size)
            assertArrayEquals(prefix.get(), rows[0].prepared.copyBytes()); assertEquals(request.get(), rows[0].prepared.requestId)
            assertEquals(listOf(0L, 1L), rows.map { it.prepared.sequence })
            assertEquals(rows[0].prepared.replayId, rows[1].prepared.replayId)
            assertEquals(setOf(initial), wireTimestamps(rows[0]))
            assertEquals((2L..10L).map { initial + it * 1000 }.toSet(), wireTimestamps(rows[1]))
            assertEquals(listOf(0L, 1L, 1L) + (2L..9L).toList(), platform.ordinals.toList())
            assertEquals(1, platform.factories.get()); assertEquals(1L, rig.state().nextReplayOrdinal)
        }
    }

    @Test fun `partial deadline backoff uses original ticks and caps at thirty seconds`(): Unit = Rig().use { rig ->
        rig.minimum(2); rig.activate(); Session(rig).use { session ->
            val initial = rig.clock.wall; val platform = Platform(rig, session.access, 9)
            val pausesAtCollection = CopyOnWriteArrayList<Int>()
            platform.onCollect = { current ->
                pausesAtCollection += platform.pauses.get()
                if (platform.collections.get() in 2..8) expireCollectorPass(rig, current)
            }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(listOf(1, 1, 2, 4, 8, 16, 30, 30), pausesAtCollection.zipWithNext { a, b -> b - a })
            assertEquals(setOf(initial, initial + 92_000), wireTimestamps(rig.rows().single()))
            assertEquals(2, owner.completedDiagnostic()!!.frames); assertEquals(1L, rig.state().nextReplayOrdinal)
        }
    }

    @Test fun `successful frame does not reset original partial deadline backoff`(): Unit = Rig().use { rig ->
        rig.minimum(2); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access, 5); val pauses = CopyOnWriteArrayList<Int>()
            platform.onCollect = { current ->
                pauses += platform.pauses.get()
                if (platform.collections.get() in listOf(2, 4)) expireCollectorPass(rig, current)
            }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(listOf(1, 1, 1, 2), pauses.zipWithNext { a, b -> b - a })
            assertEquals(1, platform.factories.get()); assertEquals(3, owner.completedDiagnostic()!!.frames)
        }
    }

    @Test fun `cancel during partial deadline wait joins original capture and keeps committed bytes`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access, 3); val waiting = CountDownLatch(1)
            platform.onCollect = { current -> if (platform.collections.get() == 2) expireCollectorPass(rig, current) }
            platform.onPause = { withdrawn -> if (platform.collections.get() == 2) {
                waiting.countDown(); !withdrawn.await(3, TimeUnit.SECONDS)
            } else { rig.advance(); true } }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertTrue(waiting.await(3, TimeUnit.SECONDS)); val original = rig.rows().single().prepared.copyBytes()
            assertNull(rig.owner.enrollNativeReplayCapture().get()); assertFalse(owner.finished().isDone)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.stop().get(3, TimeUnit.SECONDS))
            assertArrayEquals(original, rig.rows().single().prepared.copyBytes()); assertEquals(2, platform.collections.get())
            val replacement = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); replacement.cancelUnused()
            assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(replacement).get())
        }
    }

    @Test fun `concurrent deadline and original source session root or privacy withdrawal never retry`(): Unit = run {
        for (kind in 0..5) Rig().use { rig ->
            rig.minimum(0); rig.activate(); Session(rig).use { session ->
                val platform = Platform(rig, session.access, 3)
                platform.onCollect = { current -> if (platform.collections.get() == 2) {
                    rig.clock.nanos += 50_000_001L; assertFalse(current())
                    when (kind) {
                        0 -> rig.source.withdraw()
                        1 -> session.lifecycle.withdrawing(session.activity)
                        2 -> session.access.valid = false // Refused by original post-observation.
                        3 -> rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, rig.now())).get()
                        4 -> rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get()
                        else -> rig.renew { it.getJSONObject("privacy").getJSONObject("replay").put("enabled", false) }
                    }
                    throw NativeCollectionException(NativeCollectionFailure.WITHDRAWN)
                } }
                val owner = session.start(checkNotNull(session.prepare()), platform)
                assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
                assertEquals(2, platform.collections.get()); assertEquals(1, platform.pauses.get())
                assertEquals(1, platform.factories.get()); assertNull(rig.state().session?.activeEpoch)
            }
        }
    }

    @Test fun `partial deadline wait cannot renew source or native session lifetime`(): Unit = run {
        for (sessionBudget in listOf(false, true)) Rig().use { rig ->
            rig.minimum(0)
            if (sessionBudget) rig.configure { it.getJSONObject("privacy").getJSONObject("replay").put("maximumDurationSeconds", 5) }
            rig.activate(); Session(rig).use { session ->
                val platform = Platform(rig, session.access, 3)
                platform.onCollect = { current -> if (platform.collections.get() == 2) expireCollectorPass(rig, current) }
                platform.onPause = { rig.advance(if (platform.collections.get() == 2) { if (sessionBudget) 5 else 1000 } else 1); true }
                val owner = session.start(checkNotNull(session.prepare()), platform)
                assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
                assertEquals(2, platform.collections.get()); assertEquals(1, rig.rows().size)
                assertEquals(1, platform.factories.get()); assertNull(rig.state().session!!.activeEpoch)
            }
        }
    }

    @Test fun `deadline crossing after collector returns remains terminal and never retries`(): Unit = Rig().use { rig ->
        rig.minimum(0); rig.activate(); Session(rig).use { session ->
            val platform = Platform(rig, session.access, 3)
            platform.transformFrame = { frame ->
                if (platform.collections.get() == 2) rig.clock.nanos += 50_000_001L
                frame // Actual collector returned; its projection state may already have committed.
            }
            val owner = session.start(checkNotNull(session.prepare()), platform)
            assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
            assertEquals(2, platform.collections.get()); assertEquals(1, platform.pauses.get())
            assertEquals(1, rig.rows().size); assertEquals(1, owner.completedDiagnostic()!!.frames)
            assertEquals(NativeCaptureFailureKind.OTHER, owner.completedDiagnostic()!!.failure)
            assertNull(rig.state().session!!.activeEpoch)
        }
    }

    @Test fun `only known collector deadline can retry and returned frame still passes original validation`(): Unit = run {
        for (kind in 0..3) Rig().use { rig ->
            rig.minimum(2); rig.activate(); Session(rig).use { session ->
                val platform = Platform(rig, session.access, 3)
                platform.onCollect = { current -> if (platform.collections.get() == 2) {
                    if (kind != 2) { rig.clock.nanos += 50_000_001L; assertFalse(current()) }
                    when (kind) {
                        0 -> throw NativeCollectionException(NativeCollectionFailure.UNSUPPORTED_GEOMETRY)
                        1 -> throw IllegalStateException("Unknown collector error")
                        else -> throw NativeCollectionException(NativeCollectionFailure.WITHDRAWN)
                    }
                } }
                if (kind == 3) platform.transformFrame = { if (platform.collections.get() == 3) NativeMaskedSnapshot(99, it.timestamp, it.viewport, it.nodes) else it }
                val owner = session.start(checkNotNull(session.prepare()), platform)
                assertEquals(NativeReplayCaptureOutcome.SETTLED, owner.finished().get(3, TimeUnit.SECONDS))
                assertTrue(rig.rows().isEmpty()); assertEquals(if (kind == 3) 3 else 2, platform.collections.get())
                assertEquals(1, platform.factories.get()); assertNull(rig.state().session!!.activeEpoch)
            }
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
        @Volatile var onOwnerNanos: (() -> Long)? = null
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
                override fun elapsedRealtimeNanos() = onOwnerNanos?.invoke() ?: ownerNanos ?: clock.monotonicNowNanos()
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
