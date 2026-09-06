package dev.elu.analytics.internal.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLifecycleTrackerTest {
    @Test
    fun `first start foregrounds then views the screen and last stop backgrounds`() {
        val sink = RecordingSink()
        val clock = SteppingClock()
        val tracker = ActivityLifecycleTracker(sink, clock::next)

        tracker.activityStarted("HomeActivity")
        tracker.activityStarted("DetailActivity")
        tracker.activityStopped(changingConfigurations = false)
        tracker.activityStopped(changingConfigurations = false)
        tracker.activityStarted("HomeActivity")

        assertEquals(
            listOf(
                "foreground:false@2026-08-04T00:00:00.000Z",
                "screen:HomeActivity@2026-08-04T00:00:00.000Z",
                "screen:DetailActivity@2026-08-04T00:00:01.000Z",
                "background@2026-08-04T00:00:02.000Z",
                "foreground:true@2026-08-04T00:00:03.000Z",
                "screen:HomeActivity@2026-08-04T00:00:03.000Z",
            ),
            sink.events,
        )
        assertEquals(1, tracker.startedActivityCount())
    }

    @Test
    fun `a configuration change is not a background round trip or a second screen view`() {
        val sink = RecordingSink()
        val tracker = ActivityLifecycleTracker(sink, SteppingClock()::next)

        tracker.activityStarted("HomeActivity")
        tracker.activityStopped(changingConfigurations = true)
        assertEquals(0, tracker.startedActivityCount())
        tracker.activityStarted("HomeActivity")
        tracker.activityStopped(changingConfigurations = false)

        assertEquals(
            listOf(
                "foreground:false@2026-08-04T00:00:00.000Z",
                "screen:HomeActivity@2026-08-04T00:00:00.000Z",
                "background@2026-08-04T00:00:02.000Z",
            ),
            sink.events,
        )
    }

    @Test
    fun `unbalanced stops are ignored`() {
        val sink = RecordingSink()
        val tracker = ActivityLifecycleTracker(sink, SteppingClock()::next)

        tracker.activityStopped(changingConfigurations = false)
        tracker.activityStarted("HomeActivity")

        assertEquals(2, sink.events.size)
        assertEquals(1, tracker.startedActivityCount())
    }

    private class RecordingSink : RuntimeLifecycleSink {
        val events = mutableListOf<String>()

        override fun applicationForegrounded(
            occurredAt: String,
            fromBackground: Boolean,
        ) {
            events += "foreground:$fromBackground@$occurredAt"
        }

        override fun applicationBackgrounded(occurredAt: String) {
            events += "background@$occurredAt"
        }

        override fun screenViewed(
            name: String,
            occurredAt: String,
        ) {
            events += "screen:$name@$occurredAt"
        }
    }

    private class SteppingClock {
        private var current = 1_785_801_600_000L

        fun next(): Long = current.also { current += 1_000L }
    }
}
