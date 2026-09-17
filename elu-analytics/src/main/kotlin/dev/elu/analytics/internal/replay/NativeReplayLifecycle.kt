package dev.elu.analytics.internal.replay

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.atomic.AtomicBoolean

/** A failed acquisition retains any cleanup whose completion could not be proven. */
private class NativeReplayWatchAcquisitionFailure(val unsettledCleanup: AutoCloseable?, cause: Throwable) :
    RuntimeException("Native watcher acquisition failed", cause)

/** Main-thread observations only. Tests substitute this platform seam, never a physical use. */
internal interface NativeReplaySelectionAccess {
    fun onMain(action: () -> Unit)
    /** Called only on main; the returned native root never leaves that callback. */
    fun currentRoot(activity: Any, current: () -> Boolean): Any? = null
    fun observe(activity: Any, root: Any, current: () -> Boolean): NativeReplayRootFacts?
    fun watch(root: Any, withdrawn: () -> Unit): AutoCloseable
}

internal data class NativeReplayRootFacts(
    val window: Any, val token: Any, val width: Int, val height: Int, val density: Float,
    val apiLevel: Int,
)

/** Weak native facts are independent of process screen/foreground event semantics. */
internal class NativeReplayLifecycle(private val access: NativeReplaySelectionAccess = AndroidNativeReplaySelectionAccess) {
    private val monitor = Any()
    private val resumed = WeakHashMap<Any, Boolean>()
    private var generation: Any = Any()
    private val listeners = LinkedHashMap<Any, () -> Unit>()
    // Unpublished failed acquisitions have no caller to retain their physical cleanup.
    // Never revive a lifecycle after such cleanup becomes uncertain.
    private val unsettledSelections = ArrayList<NativeReplaySelection>()

    fun resumed(activity: Any) {
        val callbacks = synchronized(monitor) {
            resumed[activity] = true; generation = Any(); listeners.values.toList()
        }
        callbacks.forEach { runCatching { it() } }
    }
    fun withdrawing(activity: Any) {
        val callbacks = synchronized(monitor) {
            if (resumed.remove(activity) == null) emptyList()
            else { generation = Any(); listeners.values.toList() }
        }
        callbacks.forEach { runCatching { it() } }
    }

    /** Notifications are hints, never permission; original selection withdrawal is already visible. */
    fun observeChanges(changed: () -> Unit): AutoCloseable {
        val token = Any(); val active = AtomicBoolean(true)
        synchronized(monitor) { listeners[token] = { if (active.get()) changed() } }
        return AutoCloseable { active.set(false); synchronized(monitor) { listeners.remove(token) }; Unit }
    }

    private fun current(activity: Any, token: Any): Boolean = synchronized(monitor) {
        unsettledSelections.isEmpty() && generation === token && resumed.size == 1 && resumed.containsKey(activity)
    }

    /** Discover only the sole actually resumed Activity's existing content root on main. */
    fun selectCurrent(reusing: NativeReplaySelection? = null): SdkFuture<NativeReplaySelection?> {
        val result = object : SdkFuture<NativeReplaySelection?>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        val original = synchronized(monitor) {
            if (unsettledSelections.isNotEmpty()) null else resumed.keys.singleOrNull()?.let { WeakReference(it) to generation }
        } ?: return result.also { it.complete(null) }
        try {
            access.onMain {
                try {
                    val activity = original.first.get()
                    if (activity == null || !current(activity, original.second)) { result.complete(null); return@onMain }
                    val root = access.currentRoot(activity) { current(activity, original.second) }
                    if (root == null || !current(activity, original.second)) { reusing?.withdraw(); result.complete(null); return@onMain }
                    if (reusing != null) {
                        // Never install a replacement watcher before the caller joins old cleanup.
                        if (!reusing.matchesRoot(activity, root)) { result.complete(null); return@onMain }
                        reusing.validateCurrent().whenComplete { valid, error ->
                            try {
                                val originalActivity = original.first.get()
                                val same = error == null && valid == true && originalActivity != null &&
                                    current(originalActivity, original.second) &&
                                    reusing.matchesRoot(originalActivity, access.currentRoot(originalActivity) {
                                        current(originalActivity, original.second)
                                    }) && current(originalActivity, original.second)
                                if (!same) reusing.withdraw()
                                if (error != null) result.completeExceptionally(error)
                                else result.complete(reusing.takeIf { same && reusing.isCurrent() })
                            } catch (failure: Throwable) { reusing.withdraw(); result.completeExceptionally(failure) }
                        }
                    } else selectOriginal(original.first, WeakReference(root), original.second, result, discovered = true)

                } catch (error: Throwable) { result.completeExceptionally(error) }
            }
        } catch (error: Throwable) { result.completeExceptionally(error) }
        return result
    }

    fun select(activity: Any, root: Any): SdkFuture<NativeReplaySelection?> {
        val result = SdkFuture<NativeReplaySelection?>()
        val original = synchronized(monitor) {
            if (unsettledSelections.isNotEmpty() || resumed.size != 1 || !resumed.containsKey(activity)) null else generation
        } ?: return result.also { it.complete(null) }
        selectOriginal(WeakReference(activity), WeakReference(root), original, result)
        return result
    }

    private fun selectOriginal(weakActivity: WeakReference<Any>, weakRoot: WeakReference<Any>,
        original: Any, result: SdkFuture<NativeReplaySelection?>, discovered: Boolean = false) {
        try {
            access.onMain {
                var acquired: NativeReplaySelection? = null
                try {
                    val selectedActivity = weakActivity.get()
                    val selectedRoot = weakRoot.get()
                    if (selectedActivity == null || selectedRoot == null || !current(selectedActivity, original)) {
                        result.complete(null); return@onMain
                    }
                    fun matchesDiscovery(): Boolean = current(selectedActivity, original) &&
                        (!discovered || access.currentRoot(selectedActivity) { current(selectedActivity, original) } === selectedRoot) &&
                        current(selectedActivity, original)
                    if (!matchesDiscovery()) { result.complete(null); return@onMain }
                    val facts = access.observe(selectedActivity, selectedRoot, ::matchesDiscovery)
                    if (facts == null || facts.apiLevel < 29 || !current(selectedActivity, original)) { result.complete(null); return@onMain }
                    val selection = NativeReplaySelection.issue(access, selectedActivity, selectedRoot, facts, discovered) {
                        weakActivity.get()?.let { current(it, original) } == true
                    }
                    acquired = selection
                    selection.installWatch()
                    if (selection.isCurrent() && matchesDiscovery()) {
                        if (!result.complete(selection)) discardUnpublished(selection, result)
                    } else discardUnpublished(selection, result)
                } catch (error: Throwable) {
                    val selection = acquired
                    if (selection == null) result.completeExceptionally(error)
                    else discardUnpublished(selection, result, error)
                }
            }
        } catch (error: Throwable) { result.completeExceptionally(error) }
    }

    private fun discardUnpublished(selection: NativeReplaySelection,
        result: SdkFuture<NativeReplaySelection?>, error: Throwable? = null) {
        // Reserve the original disposal before it can post main work or invoke dependents.
        // New selections cannot overlap either pending or uncertain unpublished cleanup.
        synchronized(monitor) { unsettledSelections.add(selection); generation = Any() }
        selection.closeAndWait().whenComplete { _, cleanupError ->
            if (cleanupError == null) synchronized(monitor) { unsettledSelections.remove(selection) }
            if (error != null && cleanupError != null && error !== cleanupError) error.addSuppressed(cleanupError)
            val failure = error ?: cleanupError
            if (failure == null) result.complete(null) else result.completeExceptionally(failure)
        }
    }
}

internal class NativeReplaySelection private constructor(
    private val access: NativeReplaySelectionAccess,
    activity: Any, root: Any, facts: NativeReplayRootFacts,
    private val discovered: Boolean,
    private val originalCurrent: () -> Boolean,
) : AutoCloseable {
    private val activity = WeakReference(activity)
    private val root = WeakReference(root)
    private val window = WeakReference(facts.window)
    private val token = WeakReference(facts.token)
    private val width = facts.width; private val height = facts.height
    private val density = facts.density; private val api = facts.apiLevel
    private val withdrawn = AtomicBoolean(false)
    private var watcher: AutoCloseable? = null // touched only on main
    private val closeRequested = AtomicBoolean(false)
    private val closeResult = object : SdkFuture<Unit>() {
        override fun cancel(mayInterruptIfRunning: Boolean) = false
    }

    internal fun withdraw() { withdrawn.set(true) }
    internal fun matchesRoot(selectedActivity: Any, selectedRoot: Any?): Boolean {
        val same = activity.get() === selectedActivity && root.get() === selectedRoot && isCurrent()
        if (!same) withdrawn.set(true)
        return same
    }

    fun isCurrent(): Boolean = !withdrawn.get() && api >= 29 && activity.get() != null && root.get() != null &&
        window.get() != null && token.get() != null && originalCurrent() && !withdrawn.get()

    /** Invoked only inside a main callback, never by the worker-safe isCurrent getter. */
    private fun matchesDiscovery(selectedActivity: Any, selectedRoot: Any): Boolean =
        !discovered || (isCurrent() && access.currentRoot(selectedActivity, ::isCurrent) === selectedRoot && isCurrent())

    internal fun installWatch() {
        val selected = root.get() ?: return close()
        val weak = WeakReference(this)
        try { watcher = access.watch(selected) { weak.get()?.withdrawn?.set(true) } }
        catch (error: Throwable) {
            withdrawn.set(true); closeRequested.set(true)
            val acquisition = error as? NativeReplayWatchAcquisitionFailure
            watcher = acquisition?.unsettledCleanup
            val failure = acquisition?.cause ?: error
            if (acquisition != null && acquisition.unsettledCleanup == null) closeResult.complete(Unit)
            else closeResult.completeExceptionally(failure)
            throw failure
        }
    }

    fun validateCurrent(): SdkFuture<Boolean> {
        val result = SdkFuture<Boolean>()
        if (!isCurrent()) return result.also { it.complete(false) }
        try { access.onMain {
            try {
                val activity = activity.get(); val root = root.get()
                val facts = if (activity != null && root != null && isCurrent() && matchesDiscovery(activity, root))
                    access.observe(activity, root, ::isCurrent) else null
                val same = facts != null && facts.window === window.get() && facts.token === token.get() &&
                    facts.width == width && facts.height == height && facts.density == density && facts.apiLevel == api &&
                    matchesDiscovery(checkNotNull(activity), checkNotNull(root)) && isCurrent()
                if (!same) withdrawn.set(true)
                result.complete(same)
            } catch (error: Throwable) { withdrawn.set(true); result.completeExceptionally(error) }
        } } catch (error: Throwable) { withdrawn.set(true); result.completeExceptionally(error) }
        return result
    }

    /**
     * Consume only the original weak root on main and return a detached internal value.
     * Neither callback nor View observation runs under a lifecycle/selection lock.
     * The caller must retain this exact noncancelable completion until main work ends.
     * The existing watcher is borrowed; this method does not join watcher disposal.
     */
    fun <T : Any> consumeOriginalRoot(
        current: () -> Boolean,
        consume: (Any, () -> Boolean) -> T?,
    ): SdkFuture<T?> {
        val result = object : SdkFuture<T?>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
        try {
            access.onMain {
                try {
                    val selectedActivity = activity.get()
                    val selectedRoot = root.get()
                    fun allowed(): Boolean = isCurrent() && current() && isCurrent()
                    fun matches(): Boolean {
                        if (selectedActivity == null || selectedRoot == null || !allowed() || !matchesDiscovery(selectedActivity, selectedRoot)) return false
                        val facts = access.observe(selectedActivity, selectedRoot, ::allowed) ?: return false
                        return facts.window === window.get() && facts.token === token.get() &&
                            facts.width == width && facts.height == height && facts.density == density &&
                            facts.apiLevel == api && matchesDiscovery(selectedActivity, selectedRoot) && allowed()
                    }
                    if (!matches()) {
                        withdrawn.set(true)
                        result.complete(null)
                    } else {
                        val value = consume(checkNotNull(selectedRoot), ::allowed)
                        if (value == null || !matches()) {
                            withdrawn.set(true)
                            result.complete(null)
                        } else result.complete(value)
                    }
                } catch (error: Throwable) {
                    withdrawn.set(true)
                    result.completeExceptionally(error)
                }
            }
        } catch (error: Throwable) {
            withdrawn.set(true)
            result.completeExceptionally(error)
        }
        return result
    }

    override fun close() { closeAndWait() }

    /** The original main-thread watcher is retained until its actual disposal result is known. */
    fun closeAndWait(): SdkFuture<Unit> {
        withdrawn.set(true)
        if (!closeRequested.compareAndSet(false, true)) return closeResult
        try {
            access.onMain {
                try { watcher?.close(); watcher = null; closeResult.complete(Unit) }
                catch (error: Throwable) { closeResult.completeExceptionally(error) }
            }
        } catch (error: Throwable) { closeResult.completeExceptionally(error) }
        return closeResult
    }

    companion object {
        fun issue(access: NativeReplaySelectionAccess, activity: Any, root: Any, facts: NativeReplayRootFacts,
            discovered: Boolean = false, current: () -> Boolean) =
            NativeReplaySelection(access, activity, root, facts, discovered, current)
    }
}

/** Known rectangles or explicitly marked boot-window layout uncertainty; customer geometry is never modified. */
internal object AndroidNativeReplaySelectionAccess : NativeReplaySelectionAccess {
    override fun onMain(action: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) action()
        else check(Handler(Looper.getMainLooper()).post(action)) { "Native selection main queue is unavailable" }
    }
    override fun currentRoot(activity: Any, current: () -> Boolean): Any? {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Native root discovery requires main thread" }
        if (Build.VERSION.SDK_INT < 29 || !current()) return null
        val selected = activity as? Activity ?: return null
        fun <T> read(action: () -> T): T? {
            if (!current()) return null
            val value = action()
            return value.takeIf { current() }
        }
        if (read { selected.isFinishing } != false || read { selected.isDestroyed } != false) return null
        val window = read { selected.window } ?: return null
        // Never create a decor view or normalize a customer window in order to qualify it.
        val decor = read { window.peekDecorView() } ?: return null
        val root = read { decor.findViewById<View>(android.R.id.content) } ?: return null
        if (read { selected.window } !== window || read { window.peekDecorView() } !== decor ||
            read { root.rootView } !== decor || !current()) return null
        return root
    }
    override fun observe(activity: Any, root: Any, current: () -> Boolean): NativeReplayRootFacts? {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Native selection requires main thread" }
        if (Build.VERSION.SDK_INT < 29 || !current()) return null
        val selected = activity as? Activity ?: return null
        val view = root as? View ?: return null
        fun <T> read(action: () -> T): T? {
            if (!current()) return null
            val value = action()
            return value.takeIf { current() }
        }
        if (read { selected.isFinishing } != false || read { selected.isDestroyed } != false) return null
        val window = read { selected.window } ?: return null
        val decor = read { window.decorView } ?: return null
        if (read { view.rootView } !== decor ||
            read { view.isAttachedToWindow } != true || read { view.hasWindowFocus() } != true ||
            read { view.visibility } != View.VISIBLE || read { view.windowVisibility } != View.VISIBLE ||
            read { view.alpha } != 1f) return null
        try { observeNativeReplayOutline(decor, decor) { check(current()) { "Native selection withdrawn" } } }
        catch (_: Throwable) { return null }
        val token = read { view.windowToken } ?: return null
        val width = read { view.width } ?: return null; val height = read { view.height } ?: return null
        val density = read { view.resources.displayMetrics.density } ?: return null
        if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0 || !current()) return null
        if (read { selected.window } !== window || read { view.windowToken } !== token ||
            read { view.hasWindowFocus() } != true || read { view.isAttachedToWindow } != true) return null
        return NativeReplayRootFacts(window, token, width, height, density, Build.VERSION.SDK_INT)
    }
    override fun watch(root: Any, withdrawn: () -> Unit): AutoCloseable {
        check(Looper.myLooper() === Looper.getMainLooper())
        val view = root as View
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) withdrawn() }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { withdrawn() }
        }
        val observer = view.viewTreeObserver
        val weak = WeakReference(view)
        val originalObserver = WeakReference(observer)
        val cleanup = AutoCloseable {
            var failure: Throwable? = null
            fun attempt(block: () -> Unit) { try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } }
            val old = originalObserver.get()
            attempt { if (old?.isAlive == true) old.removeOnWindowFocusChangeListener(focus) }
            weak.get()?.let { original ->
                attempt { val active = original.viewTreeObserver
                    if (active !== old && active.isAlive) active.removeOnWindowFocusChangeListener(focus) }
                attempt { original.removeOnAttachStateChangeListener(attachment) }
            }
            failure?.let { throw it }
        }
        try {
            observer.addOnWindowFocusChangeListener(focus)
            view.addOnAttachStateChangeListener(attachment)
        } catch (error: Throwable) {
            try { cleanup.close() } catch (cleanupError: Throwable) {
                if (error !== cleanupError) error.addSuppressed(cleanupError)
                throw NativeReplayWatchAcquisitionFailure(cleanup, error)
            }
            throw NativeReplayWatchAcquisitionFailure(null, error)
        }
        return cleanup
    }
}
