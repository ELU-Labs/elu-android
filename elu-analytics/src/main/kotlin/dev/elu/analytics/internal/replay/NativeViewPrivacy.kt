package dev.elu.analytics.internal.replay

import android.view.View
import java.lang.ref.WeakReference

/** Weak identity annotations. Restriction changes invalidate any capture already in progress. */
internal object NativeViewPrivacy {
    private data class Entry(val view: WeakReference<View>, val restriction: NativeViewRestriction)
    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var revision = Any()
    private var overflow = false

    fun restrict(view: View, restriction: NativeViewRestriction) = synchronized(lock) {
        entries.removeAll { it.view.get() == null }
        val index = entries.indexOfFirst { it.view.get() === view }
        if (index >= 0 && (entries[index].restriction == restriction || entries[index].restriction == NativeViewRestriction.BLOCK)) return@synchronized
        revision = Any()
        if (index >= 0) entries[index] = Entry(WeakReference(view), restriction)
        else if (entries.size < 128) entries += Entry(WeakReference(view), restriction)
        else overflow = true // Never drop a requested privacy restriction.
    }

    class Snapshot internal constructor(internal val revision: Any, val annotations: List<NativeViewAnnotation>, val overflow: Boolean) {
        fun isCurrent(): Boolean = synchronized(lock) { revision === NativeViewPrivacy.revision }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        entries.removeAll { it.view.get() == null }
        Snapshot(revision, entries.mapNotNull { entry -> entry.view.get()?.let { NativeViewAnnotation(it, entry.restriction) } }, overflow)
    }
}
