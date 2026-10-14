package dev.elu.analytics.internal.performance

import java.lang.ref.WeakReference

/** Weak lifecycle facts only. Registration does not collect frame metrics or start a worker. */
internal class NativePerformanceActivityLifecycle {
    private val lock = Any()
    private val resumed = ArrayList<WeakReference<Any>>()
    private var generation: Any = Any()
    private val observers = LinkedHashMap<Any, () -> Unit>()

    fun resumed(activity: Any) {
        val notify = synchronized(lock) {
            prune()
            if (resumed.none { it.get() === activity }) resumed += WeakReference(activity)
            generation = Any(); observers.values.toList()
        }
        notify.forEach { runCatching(it) }
    }

    fun withdrawing(activity: Any) {
        val notify = synchronized(lock) {
            prune()
            if (!resumed.removeAll { it.get() === activity }) emptyList()
            else { generation = Any(); observers.values.toList() }
        }
        notify.forEach { runCatching(it) }
    }

    fun observe(changed: () -> Unit): AutoCloseable {
        val key = Any()
        synchronized(lock) { observers[key] = changed }
        return AutoCloseable { synchronized(lock) { observers.remove(key) }; Unit }
    }

    fun current(): Selection? = synchronized(lock) {
        prune()
        resumed.singleOrNull()?.get()?.let { Selection(WeakReference(it), generation) }
    }

    fun isCurrent(selection: Selection): Boolean = synchronized(lock) {
        prune()
        selection.generation === generation && resumed.size == 1 &&
            selection.activity.get()?.let { original -> resumed.single().get() === original } == true
    }

    private fun prune() {
        if (resumed.removeAll { it.get() == null }) generation = Any()
    }

    internal class Selection(val activity: WeakReference<Any>, val generation: Any)
}
