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
    private val isCurrent: () -> Boolean,
    private val unresolvedBlockRules: () -> Boolean,
    private val annotations: () -> List<NativeViewAnnotation> = { emptyList() },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val continuousClock: () -> Long = System::nanoTime,
) {
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
    private val settled = object : SdkFuture<Unit>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }

    init {
        requireMain()
        check(Build.VERSION.SDK_INT >= 29) { "touch projection requires public transition observation" }
        original = checkNotNull(window.callback)
        wrapper = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = dispatch(event)
        }
        window.callback = wrapper
        if (window.callback !== wrapper) withdraw()
    }

    /** Descriptive selection only; a future original owner must call only after known initial durable commit. */
    fun arm(value: NativeTouchProjection): Boolean {
        requireMain()
        if (!current() || depth != 0 || collector.touchProjection(value.snapshot) !== value) return false
        return core.arm(value).also { if (it) projection = value }
    }

    fun drain(): List<NativeTouchObservation> {
        requireMain()
        if (!current() || depth != 0) return emptyList()
        return core.drain()
    }

    fun workObservation(): NativeTouchWorkObservation {
        requireMain()
        return NativeTouchWorkObservation(callbacks, cheapActionNanos, coreHandlingNanos, budget.totalCharged, fullEventsRead, budgetRefusals)
    }

    /** Completion joins any original callback on the current stack before ownership-sensitive restoration. */
    fun closeAndWait(): SdkFuture<Unit> {
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
                    if (core.needsProjection(action)) {
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
                    val now = continuousClock(); val wall = wallClock(); val facts = input
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
        core.observe(input, proof, wall, continuous)
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
        if (withdrawn || closing) return false
        val valid = try { !offMainWithdrawal.get() && window.callback === wrapper && fence.isCurrent() && isCurrent() &&
            fence.isCurrent() && !offMainWithdrawal.get() }
            catch (_: Throwable) { false }
        if (!valid) withdraw()
        return valid
    }
    private fun withdraw() { withdrawn = true; projection = null; core.withdraw() }
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
