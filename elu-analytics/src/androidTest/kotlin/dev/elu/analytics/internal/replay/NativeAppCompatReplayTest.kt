package dev.elu.analytics.internal.replay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.*
import androidx.core.text.PrecomputedTextCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.Elu
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Test host only. Both standard AppCompat decoration modes use the actual library implementation. */
class NativeAppCompatTestActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        if (intent.getBooleanExtra("actionBar", false)) setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        super.onCreate(state)
    }
}

class NativeAppCompatReplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun fixture(actionBar: Boolean = false, test: (NativeAppCompatTestActivity, LinearLayoutCompat) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context, NativeAppCompatTestActivity::class.java)
            .putExtra("actionBar", actionBar).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeAppCompatTestActivity
        lateinit var root: LinearLayoutCompat
        instrumentation.runOnMainSync {
            root = LinearLayoutCompat(activity).apply { orientation = LinearLayoutCompat.VERTICAL }
            activity.setContentView(root)
        }
        try {
            instrumentation.waitForIdleSync()
            var ready = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!ready && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync { ready = root.isAttachedToWindow && root.hasWindowFocus() && root.width > 0 }
                if (!ready) Thread.sleep(20)
            }
            assertTrue("AppCompat fixture must be laid out in the original focused window", ready)
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { test(activity, root) } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun add(root: ViewGroup, view: View, top: Int = 0, height: Int = 70) {
        val width = root.width.coerceAtLeast(1)
        root.addView(view, ViewGroup.LayoutParams(width, height))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, top, width, top + height)
    }

    private fun ordinary(context: Context, text: String) = AppCompatTextView(context).apply {
        setEmojiCompatEnabled(false); setTextColor(Color.BLACK); setText(text)
    }

    private fun capture(activity: NativeAppCompatTestActivity, collector: AndroidViewReplayCollector =
        AndroidViewReplayCollector(maskingProfile = NativeMaskingProfile.sensitiveMask())): NativeMaskedSnapshot =
        collector.collect(activity.findViewById(android.R.id.content), 0, 1000, NativeCollectionFence(), { true }, false)

    private fun readable(snapshot: NativeMaskedSnapshot): List<String> = snapshot.nodes.mapNotNull {
        (it.kind as? NativeMaskedKind.ReadableText)?.text?.value
    }

    @Test fun plainAppCompatLeavesAreReadableInStandardNoActionBarWindow() = fixture { activity, root ->
        val views = listOf(
            ordinary(activity, "Ordinary"),
            AppCompatButton(activity).apply { setEmojiCompatEnabled(false); isAllCaps = false; text = "Button" },
            AppCompatCheckBox(activity).apply { setEmojiCompatEnabled(false); text = "Checkbox" },
            AppCompatRadioButton(activity).apply { setEmojiCompatEnabled(false); text = "Radio" },
            AppCompatToggleButton(activity).apply { setEmojiCompatEnabled(false); isAllCaps = false; text = "Toggle" },
            AppCompatCheckedTextView(activity).apply { setEmojiCompatEnabled(false); text = "Checked" },
        )
        views.forEachIndexed { index, view -> add(root, view, index * 70) }
        assertEquals(listOf("Ordinary", "Button", "Checkbox", "Radio", "Toggle", "Checked"), readable(capture(activity)))
    }

    @Test fun actionBarContentUsesOriginalAncestryAndUncertainLayout() = fixture(true) { activity, root ->
        add(root, ordinary(activity, "Ordinary"))
        val captured = capture(activity)
        assertEquals(listOf("Ordinary"), readable(captured))
        assertTrue(captured.containsLayoutBounds)
        Elu.maskView(root.parent as View)
        assertTrue(readable(capture(activity)).isEmpty())
    }

    @Test fun unresolvedTextFutureIsNotConsumedAndUpdatedLayoutIsObserved() = fixture { activity, root ->
        val text = ordinary(activity, "Displayed"); add(root, text)
        val future = UnresolvedFuture()
        text.setTextFuture(future)
        try {
            assertEquals(listOf("Displayed"), readable(capture(activity)))
            assertEquals(0, future.getCalls)
        } finally { text.setTextFuture(null) }
        text.text = "Updated"
        text.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(70, View.MeasureSpec.EXACTLY))
        text.layout(0, 0, root.width, 70)
        assertEquals(listOf("Updated"), readable(capture(activity)))
    }

    @Test fun changedDisplayedLayoutDuringCollectionCannotPublishStaleText() = fixture { activity, root ->
        val text = ordinary(activity, "Before"); add(root, text)
        // Original content root, LinearLayoutCompat, label, then the final sibling.
        add(root, View(activity), 80)
        var allocations = 0
        val lateCollector = AndroidViewReplayCollector(maskingProfile = NativeMaskingProfile.sensitiveMask(), newProjection = {
            if (++allocations == 4) {
                text.text = "After"
                text.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(70, View.MeasureSpec.EXACTLY))
                text.layout(0, 0, root.width, 70)
            }
            UUID.randomUUID()
        })
        try { capture(activity, lateCollector); fail("Changed displayed text must discard the frame") }
        catch (error: NativeCollectionException) { assertEquals(NativeCollectionFailure.TREE_CHANGED, error.failure) }
    }

    @Test fun privateInputsSpansSubclassesAndInheritedRestrictionsRemainClosed() = fixture { activity, root ->
        val input = AppCompatEditText(activity).apply { setText("PRIVATE_INPUT") }; add(root, input)
        val span = ordinary(activity, "").apply {
            text = SpannableString("PRIVATE_SPAN").apply { setSpan(ForegroundColorSpan(Color.TRANSPARENT), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }; add(root, span, 70)
        val custom = TrappedText(activity)
        add(root, custom, 140)
        val blocked = FrameLayout(activity); add(root, blocked, 210)
        add(blocked, ordinary(activity, "PRIVATE_BLOCKED")); Elu.blockView(blocked)
        val masked = FrameLayout(activity); add(root, masked, 280)
        add(masked, ordinary(activity, "PRIVATE_MASKED")); Elu.maskView(masked)
        custom.armed = true
        try {
            val result = capture(activity)
            assertTrue(readable(result).isEmpty())
            assertTrue(result.nodes.any { it.kind is NativeMaskedKind.Input })
            assertFalse(String(NativeWireframeEncoder().encode(listOf(result)).bytes).contains("PRIVATE"))
        } finally { custom.armed = false }
    }

    private class TrappedText(context: Context) : AppCompatTextView(context) {
        var armed = false
        override fun getText(): CharSequence { check(!armed) { "custom text getter" }; return super.getText() }
    }

    private class UnresolvedFuture : Future<PrecomputedTextCompat> {
        var getCalls = 0
        override fun isDone() = false
        override fun isCancelled() = false
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun get(): PrecomputedTextCompat { getCalls++; error("capture waited on an unresolved text future") }
        override fun get(timeout: Long, unit: TimeUnit): PrecomputedTextCompat = get()
    }
}
