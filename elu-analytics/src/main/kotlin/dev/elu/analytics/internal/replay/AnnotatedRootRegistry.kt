package dev.elu.analytics.internal.replay

import android.os.Looper
import android.view.View
import android.view.Window
import dev.elu.analytics.EluReplayGeometryReader
import dev.elu.analytics.EluReplayPrivateRegion
import dev.elu.analytics.EluReplayRegionGeometry
import dev.elu.analytics.R
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Node removal never mutates the separately declared privacy obligations. */
internal class RequiredPrivacyIntents {
    private var required: List<EluReplayPrivateRegion> = emptyList()
    var revision: Long = 0; private set
    var valid: Boolean = false; private set
    fun update(values: List<EluReplayPrivateRegion>) {
        val copy = values.take(65)
        val unique = Collections.newSetFromMap(IdentityHashMap<EluReplayPrivateRegion, Boolean>())
        val okay = values.size <= 64 && copy.all { unique.add(it) }
        if (valid == okay && required.size == copy.size && required.indices.all { required[it] === copy[it] }) return
        check(revision != Long.MAX_VALUE)
        revision++
        valid = okay
        required = copy
    }
    fun snapshot(): List<EluReplayPrivateRegion> { check(valid) { "invalid-required-intent" }; return required.toList() }
}

/** No global retention list. The exact real host tag and caller scope own this registration. */
internal class AnnotatedRootRegistry(view: View) {
    val intents = RequiredPrivacyIntents()
    private val entries = mutableListOf<WeakReference<AnnotatedGeometryBinding>>()
    private val host = WeakReference(view)
    private val closed = AtomicBoolean()
    private val conflicted = AtomicBoolean()
    private val attached = AtomicBoolean()
    private val revision = AtomicLong()
    private val policyRevision = AtomicLong()
    private var overflowed = false
    private var originalSource: AnnotatedRasterSourceIdentity? = null
    private var originalRoot: WeakReference<AnnotatedGeometryBinding>? = null
    private var originalCoordinates: WeakReference<Any>? = null
    private var originalParent: WeakReference<Any>? = null
    private var originalParentWasNull = true
    private var originalWindow: WeakReference<Window>? = null
    private var originalDecor: WeakReference<View>? = null
    private var originalWindowToken: WeakReference<Any>? = null

    fun attach(): Boolean {
        main()
        if (closed.get() || conflicted.get()) return false
        val view = host.get() ?: return false
        val previous = view.getTag(R.id.elu_annotated_replay_root)
        if (previous != null && previous !== this) {
            (previous as? AnnotatedRootRegistry)?.conflict()
            conflict()
            return false
        }
        if (attached.get()) {
            if (previous !== this) { conflict(); return false }
            return true
        }
        // The caller already retains its scope before this first external mutation.
        attached.set(true)
        try { view.setTag(R.id.elu_annotated_replay_root, this); changed() }
        catch (error: Throwable) { conflict(); throw error }
        return true
    }
    private fun conflict() { conflicted.set(true); sourceBindingChanged(); changed() }
    fun declare(required: List<EluReplayPrivateRegion>) {
        main(); if (closed.get()) return
        val before = intents.revision
        intents.update(required)
        if (before != intents.revision) { policyRevision.incrementAndGet(); changed() }
    }
    fun bind(intent: EluReplayPrivateRegion?, reader: EluReplayGeometryReader): AnnotatedGeometryBinding {
        main()
        if (intent == null) sourceBindingChanged()
        val entry = AnnotatedGeometryBinding(this, intent, reader)
        entries.removeAll { it.get() == null || it.get()?.isClosed == true }
        if (!closed.get()) {
            if (entries.size == 65) overflowed = true else entries += WeakReference(entry)
        }
        changed()
        return entry
    }
    internal fun changed() { main(); check(revision.get() != Long.MAX_VALUE); revision.incrementAndGet() }
    internal fun sourceBindingChanged() {
        main(); originalSource?.withdraw(); originalSource = null
        originalRoot = null; originalCoordinates = null; originalParent = null
        originalWindow = null; originalDecor = null; originalWindowToken = null
    }
    /** Main-only provenance of an already validated plan, never capture permission. */
    fun sourceIdentity(root: AnnotatedGeometryBinding, geometry: EluReplayRegionGeometry,
        window: Window, decor: View, token: Any): AnnotatedRasterSourceIdentity {
        main(); check(live() && root.owner === this && root.intent == null && !root.isClosed)
        val parentMatches = if (geometry.parentIdentity == null) originalParentWasNull
            else !originalParentWasNull && originalParent?.get() === geometry.parentIdentity
        originalSource?.let {
            if (originalRoot?.get() === root && originalCoordinates?.get() === geometry.coordinateIdentity &&
                parentMatches && originalWindow?.get() === window && originalDecor?.get() === decor &&
                originalWindowToken?.get() === token) return it
        }
        sourceBindingChanged()
        originalRoot = WeakReference(root); originalCoordinates = WeakReference(geometry.coordinateIdentity)
        originalParentWasNull = geometry.parentIdentity == null
        originalParent = geometry.parentIdentity?.let { WeakReference(it) }
        originalWindow = WeakReference(window); originalDecor = WeakReference(decor); originalWindowToken = WeakReference(token)
        return AnnotatedRasterSourceIdentity().also { originalSource = it }
    }
    fun version(): Long = revision.get()
    fun policyVersion(): Long = policyRevision.get()
    private fun live(): Boolean = attached.get() && !closed.get() && !conflicted.get()
    fun policyCurrent(version: Long): Boolean = live() && policyRevision.get() == version
    fun current(version: Long): Boolean = live() && revision.get() == version
    fun host(): View {
        main(); check(live()) { "inactive-root" }
        val view = checkNotNull(host.get()) { "missing-host" }
        check(view.getTag(R.id.elu_annotated_replay_root) === this) { "displaced-root" }
        return view
    }
    fun bindings(): List<AnnotatedGeometryBinding> {
        main(); check(live() && !overflowed) { "binding-limit" }
        return entries.mapNotNull { it.get() }.filter { !it.isClosed }.also { check(it.size <= 65) }
    }
    fun close() {
        main()
        if (!closed.compareAndSet(false, true)) return
        sourceBindingChanged()
        changed(); entries.clear()
        try {
            host.get()?.let { if (it.getTag(R.id.elu_annotated_replay_root) === this) it.setTag(R.id.elu_annotated_replay_root, null) }
        } finally { attached.set(false); host.clear() }
    }
    companion object {
        fun main() { check(Looper.myLooper() === Looper.getMainLooper()) { "annotated-main-only" } }
        /** Original capture-owner lookup only; this function installs nothing and creates no permit. */
        fun fromHost(view: View): AnnotatedRootRegistry? {
            main()
            return (view.getTag(R.id.elu_annotated_replay_root) as? AnnotatedRootRegistry)?.takeIf {
                it.live() && it.host.get() === view
            }
        }
    }
}

internal class AnnotatedGeometryBinding(
    val owner: AnnotatedRootRegistry,
    val intent: EluReplayPrivateRegion?,
    private val reader: EluReplayGeometryReader,
) {
    var generation: Long = 0; private set
    var isClosed: Boolean = false; private set
    fun invalidate() {
        AnnotatedRootRegistry.main(); if (isClosed) return
        check(generation != Long.MAX_VALUE); generation++; owner.changed()
    }
    fun read(): EluReplayRegionGeometry? { AnnotatedRootRegistry.main(); return if (isClosed) null else reader.read() }
    fun close() {
        AnnotatedRootRegistry.main(); if (isClosed) return
        if (intent == null) owner.sourceBindingChanged()
        invalidate(); isClosed = true
    }
}

/** Opaque, collector-owned source token. No View or permission is carried to the worker. */
internal class AnnotatedRasterSourceIdentity internal constructor() {
    private val live = AtomicBoolean(true)
    fun isCurrent(): Boolean = live.get()
    internal fun withdraw() { live.set(false) }
}
