package dev.elu.analytics.internal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessActivityLifecycleTest {
    @Test fun `setup after onStart replays current foreground without another Activity start`() {
        val process = ProcessActivityLifecycle { 1000L }
        val activity = Any()
        process.activityStarted(activity, "Home")
        val sink = Sink()
        StandaloneLifecycleBinding(process, sink).use { binding ->
            assertTrue(sink.events.isEmpty())
            binding.ready()
            assertEquals(listOf("foreground:false", "screen:Home"), sink.events)
        }
    }

    @Test fun `foreground arriving during async open is retained until ready`() {
        val process = ProcessActivityLifecycle { 1000L }
        val sink = Sink()
        val activity = Any()
        StandaloneLifecycleBinding(process, sink).use { binding ->
            process.activityStarted(activity, "Home")
            assertTrue(sink.events.isEmpty())
            binding.ready(); binding.ready()
            assertEquals(listOf("foreground:false", "screen:Home"), sink.events)
        }
    }

    @Test fun `background before async open completes defeats old foreground intent`() {
        val process = ProcessActivityLifecycle { 1000L }
        val sink = Sink()
        val activity = Any()
        StandaloneLifecycleBinding(process, sink).use { binding ->
            process.activityStarted(activity, "Home")
            process.activityStopped(activity, false)
            binding.ready()
            assertTrue(sink.events.isEmpty())
            process.activityStarted(activity, "Home")
            assertEquals(listOf("foreground:true", "screen:Home"), sink.events)
        }
    }

    @Test fun `only the latest foreground and screen survive async open`() {
        val process = ProcessActivityLifecycle { 1000L }
        val sink = Sink()
        val first = Any(); val second = Any()
        StandaloneLifecycleBinding(process, sink).use { binding ->
            process.activityStarted(first, "First"); process.activityStopped(first, false)
            process.activityStarted(second, "Second")
            binding.ready()
            assertEquals(listOf("foreground:true", "screen:Second"), sink.events)
        }
    }

    @Test fun `closed binding releases subscriber and cannot replay pending foreground`() {
        val process = ProcessActivityLifecycle { 1000L }
        val sink = Sink()
        val activity = Any()
        val binding = StandaloneLifecycleBinding(process, sink)
        process.activityStarted(activity, "Home")
        binding.close(); binding.close(); binding.ready()
        process.activityStopped(activity, false)
        assertEquals(0, process.subscriberCount())
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `multiple Activities and duplicate resume proof do not double count`() {
        val process = ProcessActivityLifecycle { 1000L }
        val sink = Sink()
        val first = Any(); val second = Any()
        StandaloneLifecycleBinding(process, sink).use { binding ->
            binding.ready()
            process.activityStarted(first, "First"); process.activityResumed(first, "First")
            process.activityStarted(second, "Second")
            process.activityStopped(Any(), false)
            process.activityStopped(first, false)
            assertEquals(listOf("foreground:false", "screen:First", "screen:Second"), sink.events)
            process.activityStopped(second, false)
            assertEquals("background", sink.events.last())
        }
    }

    @Test fun `late setup never reports the stopped screen when another Activity remains started`() {
        val process = ProcessActivityLifecycle { 1000L }
        val first = Any(); val second = Any(); val sink = Sink()
        process.activityStarted(first, "First"); process.activityStarted(second, "Second")
        process.activityStopped(second, false)
        StandaloneLifecycleBinding(process, sink).use { binding ->
            binding.ready()
            assertEquals(listOf("foreground:false"), sink.events)
            process.activityResumed(first, "First")
            assertEquals(listOf("foreground:false", "screen:First"), sink.events)
        }
    }

    @Test fun `configuration recreation preserves foreground and avoids duplicate screen`() {
        val process = ProcessActivityLifecycle { 1000L }
        val sink = Sink()
        val original = Any(); val replacement = Any()
        StandaloneLifecycleBinding(process, sink).use { binding ->
            binding.ready()
            process.activityStarted(original, "Home"); process.activityStopped(original, true)
            process.activityStarted(replacement, "Home")
            assertEquals(listOf("foreground:false", "screen:Home"), sink.events)
            process.activityStopped(replacement, false)
            assertEquals("background", sink.events.last())
        }
    }

    @Test fun `replacement restores foreground after another Activity stops during recreation`() {
        val process = ProcessActivityLifecycle { 1000L }
        val sink = Sink()
        val first = Any(); val second = Any(); val replacement = Any()
        StandaloneLifecycleBinding(process, sink).use { binding ->
            binding.ready()
            process.activityStarted(first, "First"); process.activityStarted(second, "Second")
            process.activityStopped(first, true); process.activityStopped(second, false)
            assertEquals("background", sink.events.last())
            process.activityStarted(replacement, "First"); process.activityResumed(replacement, "First")
            assertEquals(listOf("foreground:false", "screen:First", "screen:Second", "background",
                "foreground:true", "screen:First"), sink.events)
        }
    }

    @Test fun `missing initializer provides no foreground proof until observed lifecycle`() {
        val process = ProcessActivityLifecycle { 1000L }
        val sink = Sink()
        StandaloneLifecycleBinding(process, sink).use { binding ->
            binding.ready()
            assertTrue(sink.events.isEmpty())
        }
    }

    @Test fun `closing one stack does not remove another subscriber`() {
        val process = ProcessActivityLifecycle { 1000L }
        val first = Sink(); val second = Sink(); val activity = Any()
        val closed = StandaloneLifecycleBinding(process, first)
        StandaloneLifecycleBinding(process, second).use { live ->
            closed.ready(); live.ready(); closed.close()
            process.activityStarted(activity, "Home")
            assertTrue(first.events.isEmpty())
            assertEquals(listOf("foreground:false", "screen:Home"), second.events)
            assertEquals(1, process.subscriberCount())
        }
        assertEquals(0, process.subscriberCount())
    }

    private class Sink : RuntimeLifecycleSink {
        val events = mutableListOf<String>()
        override fun applicationForegrounded(occurredAt: String, fromBackground: Boolean) { events += "foreground:$fromBackground" }
        override fun applicationBackgrounded(occurredAt: String) { events += "background" }
        override fun screenViewed(name: String, occurredAt: String) { events += "screen:$name" }
    }
}
