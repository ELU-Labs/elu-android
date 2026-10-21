package dev.elu.analytics.internal.replay

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.text.Editable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A window the app marks FLAG_SECURE is never wireframed. Real Activity and Dialog windows; the flag
 * is set, cleared and set again on the same collector, so every pass must re-read it.
 */
class AndroidSecureWindowReplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: Activity
    private val sensitive = NativeMaskingProfile.sensitiveMask()

    @Before fun start() {
        // Visible skip: the Views wireframe path never runs below API 29.
        assumeTrue("Views wireframe replay requires API 29+; this device runs API ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 29)
        activity = instrumentation.startActivitySync(Intent(instrumentation.context, NativeCollectorTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForMainIdle("after launching NativeCollectorTestActivity")
        // This private fixture selects a rectangular window. Production never changes host clipping.
        instrumentation.runOnMainSync { activity.window.decorView.clipToOutline = false }
    }

    @After fun finish() {
        if (::activity.isInitialized) instrumentation.runOnMainSync {
            activity.window.clearFlags(SECURE)
            activity.finish()
        }
        waitForMainIdle("after finishing the secure-window fixture Activity")
    }

    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun add(parent: ViewGroup, view: View, x: Int = 20, y: Int = 20, width: Int = 80, height: Int = 40) {
        parent.addView(view, ViewGroup.LayoutParams(width, height))
        view.layout(x, y, x + width, y + height)
    }

    /** Reading this view's text, or visiting it at all, means the secure window was inspected. */
    private class TrapText(context: Context) : TextView(context) {
        var armed = false
        override fun getText(): CharSequence { check(!armed) { "secure window text read" }; return super.getText() }
        override fun getHint(): CharSequence? { check(!armed) { "secure window hint read" }; return super.getHint() }
        override fun getContentDescription(): CharSequence? { check(!armed) { "secure window description read" }; return super.getContentDescription() }
        override fun getLocationInWindow(outLocation: IntArray) { check(!armed) { "secure window label visited" }; super.getLocationInWindow(outLocation) }
    }

    private class TrapInput(context: Context) : EditText(context) {
        var armed = false
        var onInputType: (() -> Unit)? = null
        override fun getText(): Editable { check(!armed) { "secure window input read" }; return super.getText() }
        override fun getLocationInWindow(outLocation: IntArray) { check(!armed) { "secure window input visited" }; super.getLocationInWindow(outLocation) }
        override fun getInputType(): Int { onInputType?.invoke(); return super.getInputType() }
    }

    private fun assertSecurePlaceholder(frame: NativeMaskedSnapshot, root: View) {
        assertEquals("A secure window must be exactly one placeholder", 1, frame.nodes.size)
        val node = frame.nodes.single()
        assertEquals(NativeMaskedKind.Placeholder, node.kind)
        val density = root.resources.displayMetrics.density.toDouble()
        assertEquals(0.0, node.bounds.x, 0.0); assertEquals(0.0, node.bounds.y, 0.0)
        assertEquals(root.width / density, node.bounds.width, 1e-9)
        assertEquals(root.height / density, node.bounds.height, 1e-9)
        assertEquals(node.bounds, node.clip)
    }

    private fun assertSecureBytes(encoded: String, vararg hidden: String) {
        assertTrue(encoded.contains(NativeWireframeEncoder.PLACEHOLDER))
        assertFalse("A secure window has no masked text node", encoded.contains(NativeWireframeEncoder.MASK))
        for (value in hidden) assertFalse(value, encoded.contains(value))
    }

    @Test fun secureActivityWindowIsOneBlockedPlaceholderAndIsRecheckedOnEveryPass() = main {
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.layout(0, 0, 600, 600)
        val visible = Rect(); assertTrue(root.getGlobalVisibleRect(visible))
        val width = minOf(500, visible.width() - 40)
        val label = TextView(activity).apply { text = "Readable account summary" }
        root.addView(label, ViewGroup.LayoutParams(width, 80))
        label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        label.layout(20, 20, 20 + width, 100)
        val input = TrapInput(activity).apply { setText("PRIVATE_SECURE_INPUT") }
        add(root, input, y = 110)
        val trap = TrapText(activity).apply { text = "PRIVATE_SECURE_LABEL" }
        add(root, trap, y = 160)
        val collector = AndroidViewReplayCollector(maskingProfile = sensitive)
        val encoder = NativeWireframeEncoder(maskingProfile = sensitive)
        val fence = NativeCollectionFence()
        fun capture(ordinal: Long): Pair<NativeMaskedSnapshot, String> {
            val frame = collector.collect(root, ordinal, 1_788_883_200_000L + ordinal * 1_000, fence, { true }, false)
            return frame to String(encoder.encode(listOf(frame)).bytes, Charsets.UTF_8)
        }
        fun secureCapture(ordinal: Long): Pair<NativeMaskedSnapshot, String> {
            trap.armed = true; input.armed = true
            try { return capture(ordinal) } finally { trap.armed = false; input.armed = false }
        }

        activity.window.addFlags(SECURE)
        val (first, firstBytes) = secureCapture(0)
        assertSecurePlaceholder(first, root)
        assertSecureBytes(firstBytes, "Readable account summary", "PRIVATE_")

        activity.window.clearFlags(SECURE)
        val (open, openBytes) = capture(1)
        assertEquals(4, open.nodes.size)
        assertEquals("The window root keeps its identity across the flag change", first.nodes.single().identity, open.nodes.first().identity)
        assertEquals(NativeMaskedKind.Rectangle, open.nodes.first().kind)
        assertTrue("Cleared flag restores ordinary readable text: ${open.nodes.map { it.kind }}",
            open.nodes.any { (it.kind as? NativeMaskedKind.ReadableText)?.text?.value == "Readable account summary" })
        assertTrue(open.nodes.any { it.kind is NativeMaskedKind.Input })
        assertTrue(openBytes.contains("Readable account summary"))
        assertFalse(openBytes.contains("PRIVATE_"))

        activity.window.addFlags(SECURE)
        val (again, againBytes) = secureCapture(2)
        assertSecurePlaceholder(again, root)
        assertSecureBytes(againBytes, "Readable account summary", "PRIVATE_")

        // A View callback that marks the window secure during a pass discards that pass.
        activity.window.clearFlags(SECURE)
        input.onInputType = { input.onInputType = null; activity.window.addFlags(SECURE) }
        try {
            capture(3)
            fail("A pass during which the window became secure must not publish a frame")
        } catch (_: NativeCollectionException) {
        } finally { input.onInputType = null }
        assertTrue(activity.window.attributes.flags and SECURE != 0)
        val (late, lateBytes) = secureCapture(3)
        assertSecurePlaceholder(late, root)
        assertSecureBytes(lateBytes, "Readable account summary", "PRIVATE_")
    }

    @Test fun secureWindowPlacesNoTouchAndAnOpenProjectionCannotPlaceOneAfterTheFlagIsSet() = main {
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.layout(0, 0, 600, 600)
        val leaf = View(activity); add(root, leaf)
        val collector = AndroidViewReplayCollector(retainTouchWitnesses = true, maskingProfile = sensitive)
        val fence = NativeCollectionFence()
        val location = IntArray(2); leaf.getLocationInWindow(location)
        val point = listOf(NativeTouchLocation(location[0] + 10.0, location[1] + 10.0))
        val open = collector.collect(root, 0, 1000, fence, { true }, false)
        val openToken = checkNotNull(collector.touchProjection(open))
        assertNotNull("Fixture leaf must accept an ordinary touch",
            collector.projectTouchPoints(root, openToken, point, fence, { true }, false).single())

        activity.window.addFlags(SECURE)
        assertNull(collector.projectTouchPoints(root, openToken, point, fence, { true }, false).single())
        val secure = collector.collect(root, 1, 1200, fence, { true }, false)
        assertSecurePlaceholder(secure, root)
        val secureToken = checkNotNull(collector.touchProjection(secure))
        assertNull(collector.projectTouchPoints(root, secureToken, point, fence, { true }, false).single())

        // Clearing the flag does not reopen touches until new geometry is accepted.
        activity.window.clearFlags(SECURE)
        assertNull(collector.projectTouchPoints(root, secureToken, point, fence, { true }, false).single())
        val reopened = collector.collect(root, 2, 1400, fence, { true }, false)
        assertNotNull(collector.projectTouchPoints(root, checkNotNull(collector.touchProjection(reopened)),
            point, fence, { true }, false).single())
    }

    @Test fun secureDialogWindowIsOneBlockedPlaceholderAndIsRecheckedOnEveryPass() {
        lateinit var dialog: Dialog
        lateinit var content: FrameLayout
        lateinit var label: TrapText
        main {
            dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            content = FrameLayout(activity)
            label = TrapText(activity).apply { text = "Dialog confirmation code" }
            content.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setContentView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            checkNotNull(dialog.window).addFlags(SECURE)
            dialog.show()
        }
        try {
            waitForMainIdle("after showing the secure Dialog")
            var ready = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!ready && System.nanoTime() < deadline) {
                main { ready = content.isAttachedToWindow && content.hasWindowFocus() && content.isLaidOut &&
                    !content.isLayoutRequested && content.width > 0 && content.height > 0 }
                if (!ready) Thread.sleep(20)
            }
            assertTrue("The Dialog window must be shown, focused and laid out", ready)
            main {
                val window = checkNotNull(dialog.window)
                // Same private rectangular-window fixture as the Activity; the flag is the only variable.
                window.decorView.clipToOutline = false
                assertNotSame("The Dialog is its own window", activity.window.decorView, content.rootView)
                val collector = AndroidViewReplayCollector(maskingProfile = sensitive)
                val encoder = NativeWireframeEncoder(maskingProfile = sensitive)
                fun capture(ordinal: Long, armed: Boolean): Pair<NativeMaskedSnapshot, String> {
                    label.armed = armed
                    try {
                        val frame = collector.collect(content, ordinal, 1_788_883_200_000L + ordinal * 1_000,
                            NativeCollectionFence(), { true }, false)
                        return frame to String(encoder.encode(listOf(frame)).bytes, Charsets.UTF_8)
                    } finally { label.armed = false }
                }

                val (first, firstBytes) = capture(0, armed = true)
                assertSecurePlaceholder(first, content)
                assertSecureBytes(firstBytes, "Dialog confirmation code")

                window.clearFlags(SECURE)
                val (open, _) = capture(1, armed = false)
                assertEquals(NativeMaskedKind.Rectangle, open.nodes.first().kind)
                assertTrue("Cleared flag wireframes the Dialog again: ${open.nodes.map { it.kind }}",
                    open.nodes.drop(1).any { it.kind is NativeMaskedKind.ReadableText || it.kind == NativeMaskedKind.Text })

                window.addFlags(SECURE)
                val (again, againBytes) = capture(2, armed = true)
                assertSecurePlaceholder(again, content)
                assertSecureBytes(againBytes, "Dialog confirmation code")
            }
        } finally { main { dialog.dismiss() } }
    }

    private companion object {
        const val SECURE = WindowManager.LayoutParams.FLAG_SECURE
    }
}
