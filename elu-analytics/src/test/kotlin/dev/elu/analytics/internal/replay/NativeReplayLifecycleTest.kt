package dev.elu.analytics.internal.replay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Explicit synthetic platform facts; this suite does not execute Android View methods. */
internal class TestSelectionAccess : NativeReplaySelectionAccess {
    var window = Any(); var token = Any()
    var height = 200
    var density = 1f
    var inMain = false
    var lastActivity: Any? = null
    var lastRoot: Any? = null
    var api = 36
    var width = 100
    var observed = 0
    var watcherClosed = 0
    var watchers = 0
    var discoveredRoot: Any? = null
    var rootReads = 0
    var onRoot: (() -> Unit)? = null
    var onCloseWatch: (() -> Unit)? = null
    var onWatch: (() -> Unit)? = null
    var callback: (() -> Unit)? = null
    var onObserve: (() -> Unit)? = null
    var factsAvailable = true
    var hold: ((() -> Unit) -> Unit)? = null
    override fun onMain(action: () -> Unit) {
        val queued = { val previous = inMain; inMain = true; try { action() } finally { inMain = previous } }
        val gate = hold; if (gate == null) queued() else gate(queued)
    }
    override fun currentRoot(activity: Any, current: () -> Boolean): Any? {
        check(inMain); rootReads++
        onRoot?.also { onRoot = null }?.invoke()
        return discoveredRoot.takeIf { current() }
    }
    override fun observe(activity: Any, root: Any, current: () -> Boolean): NativeReplayRootFacts? {
        check(inMain)
        lastActivity = activity; lastRoot = root
        observed++
        onObserve?.also { onObserve = null }?.invoke()
        return if (factsAvailable && current()) NativeReplayRootFacts(window, token, width, height, density, api) else null
    }
    override fun watch(root: Any, withdrawn: () -> Unit): AutoCloseable {
        callback = withdrawn; watchers++; onWatch?.invoke()
        return AutoCloseable { watcherClosed++; onCloseWatch?.invoke(); callback = null }
    }
}

class NativeReplayLifecycleTest {
    @Test fun `same resumed Activity can be freshly observed after unavailable root or focus facts`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); lifecycle.resumed(activity)
        assertNull(lifecycle.selectCurrent().get()); assertEquals(0, platform.watchers)
        platform.discoveredRoot = Any(); platform.factsAvailable = false
        assertNull(lifecycle.selectCurrent().get()); assertEquals(0, platform.watchers)
        platform.factsAvailable = true
        val selected = checkNotNull(lifecycle.selectCurrent().get())
        assertTrue(selected.isCurrent()); assertEquals(1, platform.watchers)
        selected.closeAndWait().get(3, TimeUnit.SECONDS)
        assertEquals(1, platform.watcherClosed)
    }

    @Test fun `unobserved and ambiguous Activities never select`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val first = Any(); val second = Any(); val root = Any()
        assertNull(lifecycle.select(first, root).get()); assertEquals(0, platform.observed)
        lifecycle.resumed(first); lifecycle.resumed(second)
        assertNull(lifecycle.select(first, root).get()); assertEquals(0, platform.observed)
        lifecycle.withdrawing(second)
        assertNotNull(lifecycle.select(first, root).get())
    }
    @Test fun `withdrawal retained selection is terminal even after same Activity resumes`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get())
        assertTrue(selection.isCurrent())
        lifecycle.withdrawing(activity); assertFalse(selection.isCurrent())
        lifecycle.resumed(activity); assertFalse(selection.isCurrent())
        assertNotNull(lifecycle.select(activity, root).get())
        selection.close()
    }
    @Test fun `API23 to28 selection never becomes current`() {
        for (api in 23..28) {
            val platform = TestSelectionAccess().also { it.api = api }; val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); lifecycle.resumed(activity)
            assertNull(lifecycle.select(activity, Any()).get())
        }
    }
    @Test fun `focus detach and close callbacks invalidate synchronously`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get())
        platform.callback!!.invoke(); assertFalse(selection.isCurrent())
        selection.close(); assertEquals(1, platform.watcherClosed)
    }
    @Test fun `held original selection cannot adopt resumed replacement`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        var pending: (() -> Unit)? = null; platform.hold = { pending = it }
        val future = lifecycle.select(activity, root)
        lifecycle.withdrawing(activity); lifecycle.resumed(activity)
        platform.hold = null; pending!!.invoke()
        assertNull(future.get()); assertEquals(0, platform.observed)
    }
    @Test fun `withdrawal inside final root observation cannot publish stale selection`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        platform.onObserve = { lifecycle.withdrawing(activity) }
        assertNull(lifecycle.select(activity, root).get())
    }
    @Test fun `geometry change terminates exact original selection`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get())
        platform.width++
        assertFalse(selection.validateCurrent().get()); assertFalse(selection.isCurrent())
    }
    @Test fun `cancelled selection result cleans installed listener without leaking capability`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        var pending: (() -> Unit)? = null; platform.hold = { pending = it }
        val future = lifecycle.select(activity, root); assertTrue(future.cancel(false))
        platform.hold = null; pending!!.invoke()
        assertEquals(1, platform.watcherClosed)
    }
    @Test fun `physical use has no nonsynthetic public constructor`() {
        val type = NativeReplayCapturePhysicalUse::class.java
        assertTrue(type.declaredConstructors.filterNot { it.isSynthetic }.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
    }
    private fun detached(ordinal: Long = 0) = NativeReplayCollectionAttempt.Captured(NativeMaskedSnapshot(ordinal, 1234, NativeViewport(100, 200), emptyList()), 1000)

    @Test fun `consume original root publishes only detached snapshot after two main observations`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get()); val initialObservations = platform.observed
        val snapshot = detached(); var calls = 0
        val result = selection.consumeOriginalRoot({ true }) { original, current ->
            assertTrue(platform.inMain); assertSame(root, original); assertTrue(current()); calls++; snapshot
        }.get()
        assertSame(snapshot, result); assertEquals(1, calls); assertEquals(initialObservations + 2, platform.observed)
        assertSame(activity, platform.lastActivity); assertSame(root, platform.lastRoot)
        assertEquals(0, platform.watcherClosed); assertTrue(selection.isCurrent())
        assertSame(snapshot, selection.consumeOriginalRoot({ true }) { _, _ -> snapshot }.get())
        selection.close(); assertEquals(1, platform.watcherClosed)
    }
    @Test fun `typed collection result retains exact original pre and post observations`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get())
        val initial = platform.observed; val deadline = NativeReplayCollectionAttempt.CollectorDeadline
        assertSame(deadline, selection.consumeOriginalRoot({ true }) { original, current ->
            assertSame(root, original); assertTrue(current()); deadline
        }.get())
        assertEquals(initial + 2, platform.observed); assertTrue(selection.isCurrent())
        assertNull(selection.consumeOriginalRoot({ true }) { _, _ -> null }.get())
        assertFalse(selection.isCurrent())
    }

    @Test fun `typed deadline cannot bypass root facts external authority or callback failures`() {
        for (kind in 0..8) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); val root = Any(); lifecycle.resumed(activity)
            val selection = checkNotNull(lifecycle.select(activity, root).get()); var allowed = true
            val error = IllegalStateException("Original callback failed"); val deadline = NativeReplayCollectionAttempt.CollectorDeadline
            val result = selection.consumeOriginalRoot({ allowed }) { _, _ ->
                when (kind) {
                    0 -> platform.window = Any(); 1 -> platform.token = Any(); 2 -> platform.width++
                    3 -> platform.height++; 4 -> platform.density = 2f; 5 -> platform.api = 28
                    6 -> platform.onObserve = { allowed = false }
                    7 -> lifecycle.withdrawing(activity)
                    else -> throw error
                }
                deadline
            }
            if (kind == 8) {
                try { result.get(); fail("Expected original exception") }
                catch (caught: java.util.concurrent.ExecutionException) { assertSame(error, caught.cause) }
            } else assertNull(result.get())
            assertFalse(selection.isCurrent()); allowed = true
            assertNull(selection.consumeOriginalRoot({ allowed }) { _, _ -> deadline }.get())
        }
    }

    @Test fun `every original fact mismatch before and after consume is terminal`() {
        for (after in listOf(false, true)) for (kind in 0..5) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); val root = Any(); lifecycle.resumed(activity)
            val selection = checkNotNull(lifecycle.select(activity, root).get())
            val originalWindow = platform.window; val originalToken = platform.token
            fun change() { when (kind) {
                0 -> platform.window = Any(); 1 -> platform.token = Any(); 2 -> platform.width++
                3 -> platform.height++; 4 -> platform.density = 2f; else -> platform.api = 28
            } }
            if (!after) change()
            var called = false
            assertNull(selection.consumeOriginalRoot({ true }) { original, _ ->
                called = true; assertSame(root, original); change(); detached()
            }.get())
            assertEquals(after, called); assertFalse(selection.isCurrent())
            platform.window = originalWindow; platform.token = originalToken; platform.width = 100
            platform.height = 200; platform.density = 1f; platform.api = 36
            assertNull(selection.consumeOriginalRoot({ true }) { _, _ -> fail("Revived"); detached() }.get())
        }
    }
    @Test fun `accepted external gate withdrawal before posted main work skips callback permanently`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get())
        var pending: (() -> Unit)? = null; platform.hold = { pending = it }; var active = true
        val result = selection.consumeOriginalRoot({ active }) { _, _ -> fail("Late callback"); detached() }
        assertFalse(result.isDone); assertFalse(result.cancel(true)); active = false
        platform.hold = null; pending!!.invoke()
        assertNull(result.get()); assertFalse(selection.isCurrent())
        active = true; assertFalse(selection.isCurrent())
    }
    @Test fun `external gate withdrawal inside callback discards otherwise healthy snapshot`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get()); var active = true
        assertNull(selection.consumeOriginalRoot({ active }) { _, current ->
            assertTrue(current()); active = false; assertFalse(current()); detached()
        }.get()); assertFalse(selection.isCurrent())
    }
    @Test fun `post observation withdrawal cannot escape final source gate check`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get()); var active = true
        assertNull(selection.consumeOriginalRoot({ active }) { _, _ ->
            platform.onObserve = { active = false }; detached()
        }.get()); assertFalse(selection.isCurrent())
    }
    @Test fun `callback and platform observations hold no lifecycle lock`() {
        for (inObservation in listOf(false, true)) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); val root = Any(); lifecycle.resumed(activity)
            val selection = checkNotNull(lifecycle.select(activity, root).get())
            val completed = CountDownLatch(1)
            fun withdrawFromAnotherThread() {
                val thread = Thread { lifecycle.withdrawing(activity); completed.countDown() }
                thread.start(); assertTrue("Lifecycle lock spanned callback", completed.await(2, TimeUnit.SECONDS)); thread.join(2000)
            }
            if (inObservation) platform.onObserve = { withdrawFromAnotherThread() }
            assertNull(selection.consumeOriginalRoot({ true }) { _, _ ->
                assertFalse(inObservation); withdrawFromAnotherThread(); detached()
            }.get()); assertFalse(selection.isCurrent())
        }
    }
    @Test fun `close during main callback cannot settle result until callback physically returns`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); val root = Any(); lifecycle.resumed(activity)
        val selection = checkNotNull(lifecycle.select(activity, root).get())
        var pending: (() -> Unit)? = null; platform.hold = { pending = it }
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val result = selection.consumeOriginalRoot({ true }) { _, _ ->
            entered.countDown(); check(released.await(3, TimeUnit.SECONDS)); detached()
        }
        platform.hold = null
        val main = Thread { pending!!.invoke() }; main.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        selection.close(); assertFalse(selection.isCurrent()); assertFalse(result.cancel(false)); assertFalse(result.isDone)
        released.countDown(); main.join(2000); assertFalse(main.isAlive); assertNull(result.get())
    }
    @Test fun `observation gate callback and dispatch exceptions all terminate selection`() {
        for (where in 0..3) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); val root = Any(); lifecycle.resumed(activity)
            val selection = checkNotNull(lifecycle.select(activity, root).get()); val error = IllegalStateException("fixture")
            if (where == 0) platform.onObserve = { throw error }
            if (where == 3) platform.hold = { throw error }
            val result = selection.consumeOriginalRoot({ if (where == 1) throw error else true }) { _, _ ->
                if (where == 2) throw error else detached()
            }
            try { result.get(); fail("Expected original exception") }
            catch (caught: java.util.concurrent.ExecutionException) { assertSame(error, caught.cause) }
            assertFalse(selection.isCurrent())
        }
    }
    @Test fun `empty collection and activity replacement do not rebind original selection`() {
        for (replace in listOf(false, true)) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); val root = Any(); lifecycle.resumed(activity)
            val selection = checkNotNull(lifecycle.select(activity, root).get())
            assertNull(selection.consumeOriginalRoot({ true }) { _, _ ->
                if (replace) { lifecycle.withdrawing(activity); lifecycle.resumed(Any()); detached() } else null
            }.get()); assertFalse(selection.isCurrent())
        }
    }

    @Test fun `late setup discovers the sole actual root and healthy reuse installs no new watcher`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        assertNull(lifecycle.selectCurrent().get()); assertEquals(0, platform.rootReads)
        val activity = Any(); val other = Any(); val root = Any(); platform.discoveredRoot = root
        lifecycle.resumed(activity); lifecycle.resumed(other)
        assertNull(lifecycle.selectCurrent().get()); assertEquals(0, platform.rootReads)
        lifecycle.withdrawing(other)
        val original = checkNotNull(lifecycle.selectCurrent().get()); assertSame(root, platform.lastRoot)
        repeat(5) { assertSame(original, lifecycle.selectCurrent(original).get()) }
        assertEquals(1, platform.watchers); assertEquals(0, platform.watcherClosed)
        original.closeAndWait().get(); assertEquals(1, platform.watcherClosed)
    }

    @Test fun `held current-root discovery cannot adopt a newer resumed generation`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
        var pending: (() -> Unit)? = null; platform.hold = { pending = it }
        val discovery = lifecycle.selectCurrent(); assertFalse(discovery.cancel(true))
        lifecycle.withdrawing(activity); lifecycle.resumed(activity)
        platform.hold = null; checkNotNull(pending).invoke()
        assertNull(discovery.get()); assertEquals(0, platform.rootReads); assertEquals(0, platform.watchers)
    }

    @Test fun `current root replacement or loss withdraws original without installing a replacement watcher`() {
        for (removed in listOf(false, true)) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
            val original = checkNotNull(lifecycle.selectCurrent().get())
            platform.discoveredRoot = if (removed) null else Any()
            assertNull(lifecycle.selectCurrent(original).get()); assertFalse(original.isCurrent()); assertEquals(1, platform.watchers)
            original.closeAndWait().get(); assertEquals(1, platform.watcherClosed)
            if (!removed) { val next = checkNotNull(lifecycle.selectCurrent().get()); assertNotSame(original, next); next.closeAndWait().get() }
        }
    }

    @Test fun `discovered root identity is checked during collection and after reuse observation`() {
        for (during in listOf("before-collect", "in-collect", "reuse")) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
            val original = checkNotNull(lifecycle.selectCurrent().get())
            if (during == "before-collect") platform.discoveredRoot = Any()
            if (during == "reuse") {
                platform.onObserve = { platform.discoveredRoot = Any() }
                assertNull(lifecycle.selectCurrent(original).get())
            } else {
                var called = false
                assertNull(original.consumeOriginalRoot({ true }) { _, _ ->
                    called = true; platform.discoveredRoot = Any(); detached()
                }.get())
                assertEquals(during == "in-collect", called)
            }
            assertFalse(original.isCurrent()); original.closeAndWait().get()
        }
    }

    @Test fun `root discovery callback cannot publish through concurrent lifecycle withdrawal`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
        platform.onRoot = {
            val completed = CountDownLatch(1); val thread = Thread { lifecycle.withdrawing(activity); completed.countDown() }
            thread.start(); assertTrue("Lifecycle lock spanned root lookup", completed.await(2, TimeUnit.SECONDS)); thread.join(2000)
        }
        assertNull(lifecycle.selectCurrent().get()); assertEquals(0, platform.watchers)
    }

    @Test fun `unsubscribed queued change notification cannot retain or reenter a closed subscriber`() {
        val lifecycle = NativeReplayLifecycle(TestSelectionAccess()); val activity = Any()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var secondCalls = 0
        val first = lifecycle.observeChanges { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val second = lifecycle.observeChanges { secondCalls++ }
        val dispatch = Thread { lifecycle.resumed(activity) }; dispatch.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        // Subscription mutation can complete while the first callback is held: no lifecycle lock is borrowed.
        second.close(); first.close(); release.countDown(); dispatch.join(2000)
        assertFalse(dispatch.isAlive); assertEquals(0, secondCalls)
        lifecycle.withdrawing(activity); assertEquals(0, secondCalls)
    }

    @Test fun `watcher close joins main disposal exactly once and exposes original cleanup failure`() {
        for (fails in listOf(false, true)) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
            val selection = checkNotNull(lifecycle.selectCurrent().get()); val error = IllegalStateException("watcher cleanup")
            if (fails) platform.onCloseWatch = { throw error }
            var pending: (() -> Unit)? = null; platform.hold = { pending = it }
            val closing = selection.closeAndWait(); assertSame(closing, selection.closeAndWait())
            assertFalse(closing.cancel(true)); assertFalse(closing.isDone); assertFalse(selection.isCurrent())
            platform.hold = null; checkNotNull(pending).invoke()
            if (fails) try { closing.get(); fail("Cleanup failure swallowed") }
            catch (caught: java.util.concurrent.ExecutionException) { assertSame(error, caught.cause) }
            else closing.get()
            selection.close(); assertEquals(1, platform.watcherClosed)
        }
    }

    @Test fun `post watch discovery exception waits for original listener disposal`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
        val failure = IllegalStateException("post acquisition read"); var cleanup: (() -> Unit)? = null
        platform.onWatch = {
            platform.onRoot = { throw failure }
            platform.hold = { cleanup = it }
        }
        val selecting = lifecycle.selectCurrent()
        assertFalse("Failed acquisition escaped before listener disposal", selecting.isDone)
        assertEquals(0, platform.watcherClosed)
        platform.hold = null; checkNotNull(cleanup).invoke()
        try { selecting.get(); fail("Lost original discovery error") }
        catch (caught: java.util.concurrent.ExecutionException) { assertSame(failure, caught.cause) }
        assertEquals(1, platform.watcherClosed)
        platform.onWatch = null
        checkNotNull(lifecycle.selectCurrent().get()).closeAndWait().get()
    }

    @Test fun `unpublished listener cleanup uncertainty permanently denies replacement discovery`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
        val failure = IllegalStateException("post acquisition read")
        val cleanup = IllegalStateException("unknown disposal")
        platform.onWatch = { platform.onRoot = { throw failure } }
        platform.onCloseWatch = { throw cleanup }
        try { lifecycle.selectCurrent().get(); fail("Cleanup uncertainty hidden") }
        catch (_: java.util.concurrent.ExecutionException) { }
        assertEquals(1, platform.watcherClosed)
        platform.onWatch = null; platform.onCloseWatch = null
        lifecycle.withdrawing(activity); lifecycle.resumed(activity)
        assertNull(lifecycle.selectCurrent().get()); assertNull(lifecycle.select(activity, Any()).get())
        assertEquals(1, platform.watchers)
    }

    @Test fun `unproven partial watcher acquisition cannot authorize another selection`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
        val failure = IllegalStateException("acquired then threw")
        platform.onWatch = { throw failure }
        try { lifecycle.selectCurrent().get(); fail("Unknown acquisition succeeded") }
        catch (caught: java.util.concurrent.ExecutionException) { assertSame(failure, caught.cause) }
        platform.onWatch = null; lifecycle.withdrawing(activity); lifecycle.resumed(activity)
        assertNull(lifecycle.selectCurrent().get()); assertEquals(1, platform.watchers)
        assertEquals(0, platform.watcherClosed)
    }

    @Test fun `validation dispatcher failure withdraws original selection and returns its error`() {
        val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
        val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
        val original = checkNotNull(lifecycle.selectCurrent().get()); val failure = IllegalStateException("main rejected")
        platform.hold = { throw failure }
        val validating = original.validateCurrent()
        try { validating.get(); fail("Lost dispatcher failure") }
        catch (caught: java.util.concurrent.ExecutionException) { assertSame(failure, caught.cause) }
        assertFalse(original.isCurrent())
        platform.hold = null; original.closeAndWait().get()
    }

    @Test fun `pending unpublished disposal excludes every new discovery until exact settlement`() {
        for (fails in listOf(false, true)) {
            val platform = TestSelectionAccess(); val lifecycle = NativeReplayLifecycle(platform)
            val activity = Any(); platform.discoveredRoot = Any(); lifecycle.resumed(activity)
            val failure = IllegalStateException("post acquisition read")
            val pending = ArrayList<() -> Unit>()
            platform.onWatch = {
                platform.onRoot = { throw failure }
                platform.hold = { pending.add(it) }
            }
            if (fails) platform.onCloseWatch = { throw IllegalStateException("disposal failed") }
            val original = lifecycle.selectCurrent(); assertFalse(original.isDone)
            assertEquals(1, pending.size)
            val reads = platform.rootReads
            val next = lifecycle.selectCurrent(); val explicit = lifecycle.select(activity, Any())
            assertTrue("Another current-root discovery was admitted during cleanup", next.isDone)
            assertTrue("Another explicit selection was admitted during cleanup", explicit.isDone)
            assertNull(next.get()); assertNull(explicit.get())
            assertEquals(1, pending.size); assertEquals(reads, platform.rootReads); assertEquals(1, platform.watchers)
            platform.hold = null; platform.onWatch = null; pending.single().invoke()
            try { original.get(); fail("Original failure was lost") } catch (_: java.util.concurrent.ExecutionException) { }
            assertEquals(1, platform.watcherClosed)
            lifecycle.withdrawing(activity); lifecycle.resumed(activity)
            if (fails) { assertNull(lifecycle.selectCurrent().get()); assertEquals(1, platform.watchers) }
            else { checkNotNull(lifecycle.selectCurrent().get()).closeAndWait().get(); assertEquals(2, platform.watchers) }
        }
    }

}
