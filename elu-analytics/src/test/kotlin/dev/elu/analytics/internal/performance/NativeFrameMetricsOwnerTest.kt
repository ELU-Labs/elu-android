package dev.elu.analytics.internal.performance

import dev.elu.analytics.internal.config.V1CapturePerformance
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class NativeFrameMetricsOwnerTest {
    private class Rig {
        val lifecycle = NativePerformanceActivityLifecycle()
        val frames = NativeFrameMetrics()
        var context: NativePerformanceContext? = NativePerformanceContext(Any(), 1, 1, "a", 0, Any(), V1CapturePerformance(false, true, 5_000))
        val access = Access()
        val owner = NativeFrameMetricsOwner(lifecycle, { context }, access, frames)
        val activity = Any()
        fun resume() { lifecycle.resumed(activity); access.runMain() }
    }
    private class Access : NativeFrameMetricsAccess {
        val main = ArrayDeque<() -> Unit>()
        val watchers = ArrayList<(NativeFrameMeasurement) -> Unit>()
        var closed = 0
        var failWatch = false
        var duringWatch: (() -> Unit)? = null
        override fun onMain(action: () -> Unit) { main.addLast(action) }
        override fun nanoTime() = 100L
        override fun watch(activity: Any, current: () -> Boolean, emit: (NativeFrameMeasurement) -> Unit): AutoCloseable {
            val handle = AutoCloseable { closed++ }
            if (failWatch) throw NativeFrameWatchAcquisitionFailure(handle, IllegalStateException("registration failed"))
            watchers += emit; duringWatch?.invoke(); return handle
        }
        fun runMain() { while (main.isNotEmpty()) main.removeFirst()() }
        fun emit(index: Int = watchers.lastIndex, started: Long = 110) =
            watchers[index](NativeFrameMeasurement(started, 200, 10, false, null, 0, null))
    }

    @Test fun `late setup attaches existing sole activity and repeated refresh never adds overlapping listener`() {
        val rig = Rig(); rig.lifecycle.resumed(rig.activity)
        repeat(1_000) { rig.owner.refresh() }
        assertEquals(1, rig.access.main.size)
        rig.access.runMain(); rig.owner.refresh(); rig.access.runMain()
        assertEquals(1, rig.access.watchers.size)
        rig.access.emit(); assertEquals(1L, rig.frames.drain(checkNotNull(rig.context))["\$frame_count"])
        val close = rig.owner.closeAndWait(); rig.access.runMain(); close.get(1, TimeUnit.SECONDS)
    }

    @Test fun `context change abandons old listener and old queued frames cannot enter new grant`() {
        val rig = Rig(); rig.resume()
        val previous = checkNotNull(rig.context)
        rig.context = previous.copy(source = Any())
        rig.access.emit(); assertTrue(rig.frames.drain(previous).isEmpty())
        rig.owner.refresh(); rig.access.runMain(); assertEquals(1, rig.access.closed)
        rig.access.emit(index = 0); rig.access.emit(started = 99)
        assertTrue(rig.frames.drain(checkNotNull(rig.context)).isEmpty())
        rig.access.emit(); assertEquals(1L, rig.frames.drain(checkNotNull(rig.context))["\$frame_count"])
        rig.owner.close(); rig.access.runMain()
    }

    @Test fun `multiple resumed activities fail closed and late withdrawal during registration removes original`() {
        val rig = Rig(); rig.resume()
        val other = Any(); rig.lifecycle.resumed(other); rig.access.runMain()
        assertEquals(1, rig.access.closed)
        rig.access.emit(); assertTrue(rig.frames.drain(checkNotNull(rig.context)).isEmpty())
        rig.lifecycle.withdrawing(other)
        rig.access.duringWatch = { rig.lifecycle.withdrawing(rig.activity) }
        rig.access.runMain()
        assertEquals(2, rig.access.closed)
        rig.owner.close(); rig.access.runMain()
    }

    @Test fun `distinct activities remain distinct even when customer equality matches`() {
        class EqualActivity {
            override fun equals(other: Any?) = other is EqualActivity
            override fun hashCode() = 1
        }
        val rig = Rig(); val first = EqualActivity(); val second = EqualActivity()
        rig.lifecycle.resumed(first); rig.access.runMain()
        val original = checkNotNull(rig.lifecycle.current())
        rig.lifecycle.resumed(second); rig.access.runMain()
        assertNull(rig.lifecycle.current()); assertFalse(rig.lifecycle.isCurrent(original))
        assertEquals(1, rig.access.closed)
        rig.access.emit(); assertTrue(rig.frames.drain(checkNotNull(rig.context)).isEmpty())
        rig.lifecycle.withdrawing(second); rig.access.runMain()
        assertSame(first, checkNotNull(rig.lifecycle.current()).activity.get())
        rig.owner.close(); rig.access.runMain()
    }

    @Test fun `close waits for physical removal and queued callbacks cannot collect while main is held`() {
        val rig = Rig(); rig.resume()
        val close = rig.owner.closeAndWait()
        assertFalse(close.isDone)
        rig.access.emit(); assertTrue(rig.frames.drain(checkNotNull(rig.context)).isEmpty())
        rig.access.runMain(); close.get(1, TimeUnit.SECONDS)
        assertEquals(1, rig.access.closed)
        assertSame(close, rig.owner.closeAndWait())
        rig.owner.refresh(); assertTrue(rig.access.main.isEmpty())
    }

    @Test fun `partial registration cleanup is retained and failure cannot be called a successful close`() {
        val rig = Rig(); rig.access.failWatch = true; rig.resume()
        assertEquals(1, rig.access.closed)
        assertThrows(ExecutionException::class.java) { rig.owner.closeAndWait().get(1, TimeUnit.SECONDS) }
        rig.owner.refresh(); assertTrue(rig.access.main.isEmpty())
    }
}
