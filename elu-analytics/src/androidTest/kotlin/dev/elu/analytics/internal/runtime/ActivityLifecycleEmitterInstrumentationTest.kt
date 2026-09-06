package dev.elu.analytics.internal.runtime

import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLifecycleEmitterInstrumentationTest {
    class ProbeActivity : Activity()

    @Test
    fun mainThreadCallbacksMapActivitiesToScreenAndApplicationSignals() {
        val sink = RecordingSink()
        val emitter = ActivityLifecycleEmitter(ActivityLifecycleTracker(sink) { 1_785_801_660_000L })
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        var screenName: String? = null
        instrumentation.runOnMainSync {
            val activity = ProbeActivity()
            screenName = ActivityLifecycleEmitter.screenNameOf(activity)
            emitter.onActivityCreated(activity, null)
            emitter.onActivityStarted(activity)
            emitter.onActivityResumed(activity)
            emitter.onActivityPaused(activity)
            emitter.onActivityStopped(activity)
            emitter.onActivityDestroyed(activity)
        }

        assertEquals("ProbeActivity", screenName)
        assertEquals(
            listOf(
                "foreground:false@2026-08-04T00:01:00.000Z",
                "screen:ProbeActivity@2026-08-04T00:01:00.000Z",
                "background@2026-08-04T00:01:00.000Z",
            ),
            sink.events,
        )
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
}
