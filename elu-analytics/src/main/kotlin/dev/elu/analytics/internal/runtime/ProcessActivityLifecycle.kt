package dev.elu.analytics.internal.runtime

import java.util.WeakHashMap

/** Current observed lifecycle facts only; no identity, events, storage or network. */
internal data class ProcessActivityState(
    val foreground: Boolean,
    val screenName: String?,
    val occurredAt: String,
    val fromBackground: Boolean,
)

/** Starts before Activities through the manifest initializer; subscriptions may arrive much later. */
internal class ProcessActivityLifecycle(
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val started = WeakHashMap<Any, String>()
    private val listeners = LinkedHashMap<Any, (ProcessActivityState?) -> Unit>()
    private var current: ProcessActivityState? = null
    private val tracker = ActivityLifecycleTracker(object : RuntimeLifecycleSink {
        override fun applicationForegrounded(occurredAt: String, fromBackground: Boolean) {
            publish(ProcessActivityState(true, null, occurredAt, fromBackground))
        }
        override fun applicationBackgrounded(occurredAt: String) {
            publish(ProcessActivityState(false, null, occurredAt, false))
        }
        override fun screenViewed(name: String, occurredAt: String) {
            current?.takeIf { it.foreground }?.let { publish(it.copy(screenName = name, occurredAt = occurredAt)) }
        }
    }, wallClock)

    @Synchronized fun activityStarted(activity: Any, screenName: String) {
        if (started.containsKey(activity)) return
        started[activity] = screenName
        tracker.activityStarted(screenName)
        // A different last Activity may have backgrounded during recreation. Its replacement's
        // direct start is current proof even if the tracker's recreation fold suppresses a signal.
        if (current?.foreground == false) {
            publish(ProcessActivityState(true, screenName, RuntimeWallTimestamps.rfc3339(wallClock()), true))
        }
    }

    @Synchronized fun activityResumed(activity: Any, screenName: String) {
        activityStarted(activity, screenName)
        current?.takeIf { it.foreground && it.screenName != screenName }?.let {
            publish(it.copy(screenName = screenName, occurredAt = RuntimeWallTimestamps.rfc3339(wallClock())))
        }
    }

    @Synchronized fun activityStopped(activity: Any, changingConfigurations: Boolean) {
        val stoppedName = started.remove(activity) ?: return
        tracker.activityStopped(changingConfigurations)
        // A different Activity may remain started. Keep foreground proof, but never replay the
        // screen of one that stopped while waiting for the remaining Activity's resume callback.
        current?.takeIf { it.foreground && started.isNotEmpty() && it.screenName == stoppedName &&
            !started.containsValue(stoppedName) }?.let { publish(it.copy(screenName = null)) }
    }

    @Synchronized fun subscribe(listener: (ProcessActivityState?) -> Unit): AutoCloseable {
        val key = Any()
        listeners[key] = listener
        // Publication and replay share the same lock: an older initial state cannot overtake stop.
        try { listener(current) } catch (error: Throwable) { listeners.remove(key); throw error }
        return AutoCloseable { synchronized(this) { listeners.remove(key); Unit } }
    }

    @Synchronized internal fun subscriberCount(): Int = listeners.size

    private fun publish(state: ProcessActivityState) {
        current = state
        // Listeners only publish lifecycle intent; they must never wait for storage or network.
        listeners.toList().forEach { (key, listener) ->
            if (listeners.containsKey(key)) listener(state)
        }
    }
}

/** Holds only the latest observed intent while the standalone owner opens asynchronously. */
internal class StandaloneLifecycleBinding(
    lifecycle: ProcessActivityLifecycle,
    private val sink: RuntimeLifecycleSink,
) : AutoCloseable {
    private val lock = Any()
    private var latest: ProcessActivityState? = null
    private var ready = false
    private var closed = false
    private var foreground = false
    private var screen: String? = null
    private val subscription = lifecycle.subscribe { state -> synchronized(lock) {
        latest = state
        if (ready && !closed) applyCurrent()
    } }

    fun ready() = synchronized(lock) {
        if (closed || ready) return@synchronized
        ready = true
        applyCurrent()
    }

    override fun close() {
        synchronized(lock) { closed = true; latest = null; screen = null }
        // Do not hold the binding lock while acquiring the process publication lock.
        subscription.close()
    }

    private fun applyCurrent() {
        val state = latest ?: return
        if (state.foreground != foreground) {
            foreground = state.foreground
            if (foreground) sink.applicationForegrounded(state.occurredAt, state.fromBackground)
            else { screen = null; sink.applicationBackgrounded(state.occurredAt) }
        }
        if (foreground && state.screenName != null && state.screenName != screen) {
            screen = state.screenName
            sink.screenViewed(state.screenName, state.occurredAt)
        }
    }
}
