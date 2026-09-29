package dev.elu.analytics.internal.replay

import android.os.Looper
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NativeTouchAction { DOWN, MOVE, UP, CANCEL, UNSUPPORTED }

/** Detached event facts. Historical positions are privacy checks, never reconstructed samples. */
internal data class NativeTouchInput(
    val action: NativeTouchAction,
    val pointerId: Int,
    val downTime: Long,
    val locations: List<NativeTouchLocation>,
    val directSingleFinger: Boolean,
)

/** Descriptive mailbox row, not an encoder ID or a durable/authority proof. */
internal data class NativeTouchObservation(
    val projection: NativeTouchProjection,
    val interaction: NativeReplayInteraction,
)

/** Monotonic elapsed SDK-work ceiling, excluding time inside the original app callback.
 * Limits are provisional safety ceilings, not measured customer-performance qualification.
 * Public framework getters are non-preemptible; an overrun is charged and stops further work.
 */
internal class NativeTouchWorkBudget(
    private val callbackLimit: Long = 2_000_000,
    private val windowLimit: Long = 20_000_000,
    private val windowNanos: Long = 1_000_000_000,
) {
    private data class Charge(val endedAt: Long, val nanos: Long)
    private val charges = ArrayDeque<Charge>()
    private var windowSpent = 0L
    private var callbackSpent = 0L
    private var lastClock: Long? = null
    private var segment: Long? = null
    private var active = false
    private var clockFailed = false
    var refused = false
        private set
    var totalCharged = 0L
        private set
    init { require(callbackLimit > 0 && windowLimit >= callbackLimit && windowNanos > 0) }

    fun begin(now: Long): Boolean {
        check(!active)
        refused = false
        if (!sample(now) || windowSpent >= windowLimit || charges.size >= 128) { refused = true; return false }
        active = true; callbackSpent = 0; segment = now
        return true
    }
    fun within(now: Long): Boolean {
        val start = segment
        if (!active || start == null || refused || !sample(now)) { refused = true; return false }
        val spent = now - start
        val allowed = callbackSpent <= callbackLimit && spent <= callbackLimit - callbackSpent &&
            windowSpent <= windowLimit && spent <= windowLimit - windowSpent && charges.size < 128
        if (!allowed) refused = true
        return allowed
    }
    fun pause(now: Long): Boolean {
        val start = segment ?: return false
        val allowed = within(now)
        segment = null
        if (!clockFailed && now >= start) {
            val spent = now - start
            callbackSpent = saturatingAdd(callbackSpent, spent)
            totalCharged = saturatingAdd(totalCharged, spent)
            if (spent > 0) { charges.addLast(Charge(now, spent)); windowSpent = saturatingAdd(windowSpent, spent) }
        }
        return allowed
    }
    fun resume(now: Long): Boolean {
        if (!active || segment != null || !sample(now) || refused || windowSpent >= windowLimit || charges.size >= 128) {
            refused = true; return false
        }
        segment = now; return true
    }
    fun clockFailure() { clockFailed = true; refused = true; segment = null; active = false }
    fun finish(now: Long) {
        if (segment != null) pause(now)
        active = false
    }
    private fun sample(now: Long): Boolean {
        if (clockFailed || now < 0 || lastClock?.let { now < it } == true) { clockFailed = true; return false }
        lastClock = now
        while (charges.isNotEmpty() && now - charges.first.endedAt >= windowNanos) {
            windowSpent -= charges.removeFirst().nanos
        }
        return true
    }
    companion object {
        fun saturatingAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
    }
}

internal data class NativeTouchWorkObservation(
    val callbacks: Long, val cheapActionNanos: Long, val coreHandlingNanos: Long, val sdkObservationNanos: Long,
    val fullEventsRead: Long, val budgetRefusals: Long,
)

/** Original main-only component handle. Values are descriptive; the capture owner supplies commit ordering. */
internal interface NativeReplayCaptureTouch {
    fun install()
    fun arm(value: NativeTouchProjection): Boolean
    fun handoff(value: NativeTouchProjection, continuous: Long): List<NativeTouchObservation>
    fun drain(): List<NativeTouchObservation>
    fun active(): Boolean
    fun stopAndDrain(): List<NativeTouchObservation>
    fun withdrawIntake()
    fun closeAndWait(): SdkFuture<Unit>
}

/** Main-serialized detached state. The original capture owner must supply known committed projection arming. */
internal class NativeTouchObservationCore(private val maximumLogicalEvents: Int = 64) {
    private data class Active(val pointerId: Int, val downTime: Long, val identity: UUID)
    private var projection: NativeTouchProjection? = null
    private var active: Active? = null
    private var contact = false
    private var withdrawn = false
    private var rows = ArrayList<NativeTouchObservation>()
    private var pending = ArrayList<NativeReplayTouchPoint>()
    private var lastWall: Long? = null
    private var lastContinuous: Long? = null
    private var lastMoveWall: Long? = null
    private var lastMoveContinuous: Long? = null
    private var logicalCount = 0

    init { require(maximumLogicalEvents in 4..64) }

    /** No precommit proof is minted here; no arm during a contact or over an undrained boundary. */
    fun arm(value: NativeTouchProjection): Boolean {
        if (withdrawn || contact || active != null || rows.isNotEmpty() || pending.isNotEmpty()) return false
        projection = value
        return true
    }

    fun active(): Boolean = !withdrawn && active != null
    fun pressure(): Boolean = logicalCount >= maximumLogicalEvents - 2 || pending.size >= 10
    fun samples(): List<NativeReplayTouchPoint> = rows.flatMap { row -> when (val event = row.interaction) {
        is NativeReplayInteraction.Start -> listOf(event.point)
        is NativeReplayInteraction.End -> listOf(event.point)
        is NativeReplayInteraction.Moves -> event.points
        is NativeReplayInteraction.Cancel -> emptyList()
    } } + pending

    /** Old rows precede the new geometry; no start permission is created for an unarmed contact. */
    fun handoff(value: NativeTouchProjection, continuous: Long): List<NativeTouchObservation> {
        if (withdrawn || projection == null) return emptyList()
        val previous = checkNotNull(projection)
        check(value.generation > previous.generation && value.snapshot.ordinal > previous.snapshot.ordinal &&
            value.snapshot.viewport == previous.snapshot.viewport)
        check(lastWall?.let { value.snapshot.timestamp >= it } != false &&
            lastContinuous?.let { continuous >= it } != false)
        val target = active?.identity
        if (target != null && value.snapshot.nodes.none {
            it.identity == target && (it.kind === NativeMaskedKind.Rectangle ||
                it.kind is NativeMaskedKind.ReadableText && it.kind.text.value != NativeWireframeV2Encoder.MASK) &&
                it.geometry == NativeGeometryKind.VISIBLE_CLIP && it.clip.width > 0 && it.clip.height > 0
        }) cancel(value.snapshot.timestamp)
        val old = drain()
        projection = value
        lastWall = value.snapshot.timestamp; lastContinuous = continuous
        return old
    }
    fun stop(wall: Long, continuous: Long): List<NativeTouchObservation> {
        check(wall in 1..253_402_300_799_999L && continuous >= 0 &&
            lastWall?.let { wall >= it } != false && lastContinuous?.let { continuous >= it } != false)
        cancel(wall)
        return drain()
    }

    fun needsProjection(action: NativeTouchAction): Boolean = !withdrawn && projection != null &&
        (if (!contact) action == NativeTouchAction.DOWN else active != null && action != NativeTouchAction.CANCEL && action != NativeTouchAction.UNSUPPORTED)

    fun observe(input: NativeTouchInput, proof: List<NativeProjectedTouch?>?, wall: Long, continuous: Long) {
        if (withdrawn) return
        if (wall !in 1..253_402_300_799_999L || continuous < 0 ||
            lastWall?.let { wall < it } == true || lastContinuous?.let { continuous < it } == true) {
            withdraw(); return
        }
        lastWall = wall; lastContinuous = continuous
        val selected = projection
        val points = proof?.takeIf { input.locations.size in 1..33 && it.size == input.locations.size }
        val candidate = points?.lastOrNull()
        val lawful = selected != null && points != null && candidate != null && input.directSingleFinger &&
            points.all { it != null && it.projection === selected && it.identity == candidate.identity }
        if (input.action == NativeTouchAction.DOWN) {
            // A second DOWN is not an opportunity to adopt an already-running gesture.
            if (contact) { cancel(wall); return }
            contact = true
            if (!lawful || rows.isNotEmpty() && logicalCount >= maximumLogicalEvents - 1) return
            val point = checkNotNull(candidate).point(wall)
            active = Active(input.pointerId, input.downTime, point.identity)
            append(NativeReplayInteraction.Start(point))
            return
        }
        val original = active
        val terminal = input.action == NativeTouchAction.UP || input.action == NativeTouchAction.CANCEL
        if (original == null) {
            if (terminal) contact = false
            else contact = true // Installing/arming after an unseen DOWN cannot adopt a MOVE.
            return
        }
        if (!lawful || input.pointerId != original.pointerId || input.downTime != original.downTime ||
            candidate?.identity != original.identity || input.action == NativeTouchAction.UNSUPPORTED ||
            input.action == NativeTouchAction.CANCEL) {
            cancel(wall)
            if (terminal) contact = false
            return
        }
        if (input.action == NativeTouchAction.UP) {
            flushMoves()
            append(NativeReplayInteraction.End(checkNotNull(candidate).point(wall)), terminal = true)
            active = null; contact = false
            return
        }
        if (input.action != NativeTouchAction.MOVE) { cancel(wall); return }
        // Privacy was checked above even when either cadence refuses the coordinate.
        if (lastMoveWall?.let { wall - it < 100 } == true ||
            lastMoveContinuous?.let { continuous - it < 100_000_000L } == true) return
        if (logicalCount >= maximumLogicalEvents - 1) { cancel(wall); return }
        if (pending.size == 10 || pending.firstOrNull()?.let { wall - it.timestamp > 900 } == true) flushMoves()
        pending.add(checkNotNull(candidate).point(wall)); logicalCount += 1
        lastMoveWall = wall; lastMoveContinuous = continuous
    }

    /** Call before any geometry/root boundary; the owner must serialize these rows before that boundary. */
    fun drain(): List<NativeTouchObservation> {
        if (withdrawn) return emptyList()
        flushMoves()
        return Collections.unmodifiableList(ArrayList(rows)).also { rows.clear(); logicalCount = 0 }
    }

    /** No pending coordinates survive loss of original observer ownership/current authority. */
    fun withdraw() {
        withdrawn = true; projection = null; active = null; rows.clear(); pending.clear(); logicalCount = 0
    }

    private fun NativeProjectedTouch.point(wall: Long) = NativeReplayTouchPoint(identity, x, y, wall)
    private fun append(event: NativeReplayInteraction, terminal: Boolean = false) {
        if (logicalCount >= maximumLogicalEvents - if (terminal) 0 else 1) { withdraw(); return }
        rows.add(NativeTouchObservation(checkNotNull(projection), event)); logicalCount += 1
    }
    private fun flushMoves() {
        if (pending.isEmpty()) return
        rows.add(NativeTouchObservation(checkNotNull(projection), NativeReplayInteraction.Moves(pending)))
        pending = ArrayList()
    }
    private fun cancel(wall: Long) {
        if (active != null) {
            flushMoves(); append(NativeReplayInteraction.Cancel(wall), terminal = true); active = null
        }
        // Suppress through lift. A later lawful position never repairs this gesture.
    }
}

/**
 * Uninstalled-by-production main-thread observer. All callbacks except touch are exact delegation.
 * This is visual primary-pointer observation, never a claim about a child click/interception recipient.
 * It does not install listeners, consume events, copy MotionEvent, or schedule per-event work.
 */
internal class AndroidReplayTouchObserver(
    private val window: Window,
    root: View,
    private val collector: AndroidViewReplayCollector,
    private val fence: NativeCollectionFence,
    @Volatile private var isCurrent: () -> Boolean,
    @Volatile private var unresolvedBlockRules: () -> Boolean,
    @Volatile private var annotations: () -> List<NativeViewAnnotation> = { emptyList() },
    @Volatile private var wallClock: () -> Long = System::currentTimeMillis,
    @Volatile private var continuousClock: () -> Long = System::nanoTime,
    @Volatile private var freshIntakeAllowed: () -> Boolean = { true },
    @Volatile private var wake: () -> Unit = {},
) : NativeReplayCaptureTouch {
    private val offMainWithdrawal = AtomicBoolean(false)
    private val originalRoot = WeakReference(root)
    private val original: Window.Callback
    private val wrapper: Window.Callback
    private val core = NativeTouchObservationCore()
    private val budget = NativeTouchWorkBudget()
    private var callbacks = 0L
    private var cheapActionNanos = 0L
    private var coreHandlingNanos = 0L
    private var fullEventsRead = 0L
    private var budgetRefusals = 0L
    private var projection: NativeTouchProjection? = null
    private var depth = 0
    private var withdrawn = false
    private var closing = false
    private var installed = false
    private val settled = object : SdkFuture<Unit>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }

    init {
        requireMain()
        check(Build.VERSION.SDK_INT >= 29) { "touch projection requires public transition observation" }
        original = checkNotNull(window.callback)
        wrapper = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = dispatch(event)
        }
    }

    /** Construction is side-effect free. The owner retains this handle BEFORE this call. */
    override fun install() {
        requireMain(); check(!installed && !closing && !withdrawn)
        check(window.callback === original) { "Original callback changed before installation" }
        installed = true // A throwing setter can have applied; cleanup retains the exact wrapper.
        window.callback = wrapper
        check(window.callback === wrapper) { "Original touch observer was displaced during installation" }
    }

    /** Descriptive selection only; a future original owner must call only after known initial durable commit. */
    override fun arm(value: NativeTouchProjection): Boolean {
        requireMain()
        if (!current() || depth != 0 || collector.touchProjection(value.snapshot) !== value) return false
        return core.arm(value).also { if (it) projection = value }
    }

    override fun drain(): List<NativeTouchObservation> {
        requireMain()
        check(current() && depth == 0)
        validatePendingSamples()
        return core.drain()
    }

    override fun active(): Boolean { requireMain(); return current() && core.active() }

    override fun handoff(value: NativeTouchProjection, continuous: Long): List<NativeTouchObservation> {
        requireMain(); check(current() && depth == 0 && collector.touchProjection(value.snapshot) === value)
        val old = core.handoff(value, continuous)
        // Unarmed initial/minimum frames remain descriptive until arm() after known commit.
        if (projection != null) projection = value
        return old
    }

    override fun stopAndDrain(): List<NativeTouchObservation> {
        requireMain(); check(current() && depth == 0)
        validatePendingSamples()
        return core.stop(wallClock(), continuousClock())
    }

    /** Queued coordinates still require the current hierarchy before leaving the main mailbox. */
    private fun validatePendingSamples() {
        val selected = projection
        val samples = core.samples()
        try {
            if (samples.isNotEmpty()) {
                check(selected != null && budget.begin(continuousClock()))
                val root = checkNotNull(originalRoot.get())
                val origin = IntArray(2)
                eventFact { root.getLocationInWindow(origin) }
                val density = eventFact { root.resources.displayMetrics.density }.toDouble()
                check(density.isFinite() && density > 0)
                for (batch in samples.chunked(33)) {
                    val facts = NativeTouchInput(NativeTouchAction.MOVE, 0, 0,
                        batch.map { NativeTouchLocation(it.x * density + origin[0], it.y * density + origin[1]) }, true)
                    val proven = project(facts, selected)
                    check(proven != null && proven.size == batch.size && proven.zip(batch).all { (a, b) ->
                        a != null && a.projection === selected && a.identity == b.identity && a.x == b.x && a.y == b.y
                    })
                }
                check(budget.pause(continuousClock()))
            }
            check(current())
        } catch (error: Throwable) { withdraw(); throw error }
        finally { budget.finish(continuousClock()) }
    }

    fun workObservation(): NativeTouchWorkObservation {
        requireMain()
        return NativeTouchWorkObservation(callbacks, cheapActionNanos, coreHandlingNanos, budget.totalCharged, fullEventsRead, budgetRefusals)
    }

    /** Completion joins any original callback on the current stack before ownership-sensitive restoration. */
    override fun closeAndWait(): SdkFuture<Unit> {
        requireMain(); closing = true; withdraw()
        if (depth == 0) settle()
        return settled
    }

    private fun dispatch(event: MotionEvent): Boolean {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            offMainWithdrawal.set(true)
            return original.dispatchTouchEvent(event)
        }
        depth += 1
        var completed = false
        var input: NativeTouchInput? = null
        var before: List<NativeProjectedTouch?>? = null
        var working = false
        val selected = projection
        callbacks = NativeTouchWorkBudget.saturatingAdd(callbacks, 1)
        try {
            if (depth != 1) withdraw()
            if (current()) {
                try {
                    val started = continuousClock()
                    // On exhausted/suppressed callbacks, read only the action needed to observe lift.
                    val action = action(event.actionMasked)
                    val sampled = continuousClock()
                    if (sampled < started) throw IllegalStateException("touch action clock reversed")
                    cheapActionNanos = NativeTouchWorkBudget.saturatingAdd(cheapActionNanos, sampled - started)
                    input = NativeTouchInput(action, -1, 0, emptyList(), false)
                    if (freshIntakeAllowed() && core.needsProjection(action)) {
                        working = budget.begin(sampled)
                        if (working) {
                            fullEventsRead = NativeTouchWorkBudget.saturatingAdd(fullEventsRead, 1)
                            input = detach(event, action)
                            before = project(input, selected)
                            if (!budget.pause(continuousClock())) before = null
                        } else budgetRefusals = NativeTouchWorkBudget.saturatingAdd(budgetRefusals, 1)
                    }
                } catch (error: Throwable) {
                    // Only the guarded SDK-budget rejection may become a cancel.
                    // A previous callback's refused bit must not hide a clock/getter fault.
                    if (!working || error !is NativeCollectionException || !budget.refused) withdraw()
                    before = null
                    if (working) runCatching { budget.pause(continuousClock()) }.onFailure { budget.clockFailure(); withdraw() }
                }
            }
            // Preserve exact original return/throw and event once; original app time is excluded.
            val result = original.dispatchTouchEvent(event)
            completed = true
            if (current()) {
                try {
                    var after: List<NativeProjectedTouch?>? = null
                    if (working && !budget.refused && budget.resume(continuousClock())) {
                        after = project(input, selected)
                        if (!budget.pause(continuousClock())) after = null
                    }
                    if (working && budget.refused) budgetRefusals = NativeTouchWorkBudget.saturatingAdd(budgetRefusals, 1)
                    val now = continuousClock(); val wall = wallClock(); val facts = input.takeIf { freshIntakeAllowed() }
                    val identical = before != null && after != null && before == after && !budget.refused
                    if (facts != null && current() && projection === selected) observeCore(facts, if (identical) after else null, wall, now)
                } catch (error: Throwable) {
                    if (working && error is NativeCollectionException && budget.refused) {
                        budgetRefusals = NativeTouchWorkBudget.saturatingAdd(budgetRefusals, 1)
                        runCatching { input?.let { observeCore(it, null, wallClock(), continuousClock()) } }.onFailure { withdraw() }
                    } else withdraw()
                }
            }
            return result
        } finally {
            if (!completed) withdraw()
            if (working) runCatching { budget.finish(continuousClock()) }.onFailure { budget.clockFailure(); withdraw() }
            depth -= 1
            if (depth == 0 && closing) settle()
        }
    }

    /** Fixed-size detached bookkeeping remains available for cancellation/lift after budget exhaustion. */
    private fun observeCore(input: NativeTouchInput, proof: List<NativeProjectedTouch?>?, wall: Long, continuous: Long) {
        val started = continuousClock()
        val wasActive = core.active()
        core.observe(input, proof, wall, continuous)
        if (wasActive != core.active() || core.pressure()) wake()
        val ended = continuousClock()
        if (ended < started) throw IllegalStateException("touch terminal clock reversed")
        coreHandlingNanos = NativeTouchWorkBudget.saturatingAdd(coreHandlingNanos, ended - started)
    }

    private fun project(input: NativeTouchInput?, selected: NativeTouchProjection?): List<NativeProjectedTouch?>? {
        if (input == null || selected == null || input.locations.isEmpty() || !input.directSingleFinger) return null
        val root = originalRoot.get() ?: return null
        fun withinPass(): Boolean = budget.within(continuousClock()) && current() && projection === selected
        if (!withinPass()) return null
        return collector.projectTouchPoints(root, selected, input.locations, fence, ::withinPass,
            unresolvedBlockRules(), annotations()).takeIf { withinPass() }
    }

    private fun current(): Boolean {
        if (!installed || withdrawn || closing) return false
        val valid = try { !offMainWithdrawal.get() && window.callback === wrapper && fence.isCurrent() && isCurrent() &&
            fence.isCurrent() && !offMainWithdrawal.get() }
            catch (_: Throwable) { false }
        if (!valid) withdraw()
        return valid
    }
    /** Off-main-safe denial/reference release only. No View, MotionEvent or core mutation here. */
    override fun withdrawIntake() {
        offMainWithdrawal.set(true)
        // Failed restoration can retain this exact UI handle in the existing resource quarantine.
        // It must not retain a capture run, authority, queue, wake task or customer annotation closure.
        isCurrent = { false }; unresolvedBlockRules = { true }; annotations = { emptyList() }
        freshIntakeAllowed = { false }; wake = {}; wallClock = System::currentTimeMillis
        continuousClock = System::nanoTime
    }
    private fun withdraw() {
        withdrawn = true; projection = null; core.withdraw(); withdrawIntake()
    }
    private fun settle() {
        try {
            if (window.callback === wrapper) window.callback = original
            // A displaced callback belongs to the app; never overwrite or adopt it.
            if (window.callback === wrapper) throw IllegalStateException("original touch observer remains installed")
            settled.complete(Unit)
        } catch (error: Throwable) { settled.completeExceptionally(error) }
    }
    private fun requireMain() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "touch observation requires main thread" }
    }
    private fun action(value: Int): NativeTouchAction = when (value) {
        MotionEvent.ACTION_DOWN -> NativeTouchAction.DOWN
        MotionEvent.ACTION_MOVE -> NativeTouchAction.MOVE
        MotionEvent.ACTION_UP -> NativeTouchAction.UP
        MotionEvent.ACTION_CANCEL -> NativeTouchAction.CANCEL
        else -> NativeTouchAction.UNSUPPORTED
    }
    private fun <T> eventFact(getter: () -> T): T {
        if (!budget.within(continuousClock()) || !current()) throw NativeCollectionException(NativeCollectionFailure.WITHDRAWN)
        val value = getter()
        if (!budget.within(continuousClock()) || !current()) throw NativeCollectionException(NativeCollectionFailure.WITHDRAWN)
        return value
    }
    private fun detach(event: MotionEvent, action: NativeTouchAction): NativeTouchInput {
        val history = eventFact { event.historySize }
        // 0x2 is FLAG_WINDOW_IS_PARTIALLY_OBSCURED, public from API29 (collector's existing floor).
        val direct = eventFact { event.pointerCount } == 1 && eventFact { event.source } == InputDevice.SOURCE_TOUCHSCREEN &&
            eventFact { event.getToolType(0) } == MotionEvent.TOOL_TYPE_FINGER && eventFact { event.flags } and 3 == 0 &&
            history in 0..32 && (action == NativeTouchAction.MOVE || history == 0)
        val points = ArrayList<NativeTouchLocation>()
        if (direct) {
            for (index in 0 until history) points.add(NativeTouchLocation(
                eventFact { event.getHistoricalX(0, index) }.toDouble(), eventFact { event.getHistoricalY(0, index) }.toDouble()))
            points.add(NativeTouchLocation(eventFact { event.getX(0) }.toDouble(), eventFact { event.getY(0) }.toDouble()))
        }
        return NativeTouchInput(action, if (direct) eventFact { event.getPointerId(0) } else -1,
            eventFact { event.downTime }, Collections.unmodifiableList(points), direct)
    }
}
