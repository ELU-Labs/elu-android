package dev.elu.analytics.internal.replay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Real laid-out framework text with the stock theme, never a synthetic layout or altered window. */
class NativeReadableTextTest {
    @Test fun stockThemeTextIsReadableWhileInvisibleInputsAndBlockedTextAreExcluded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context, NativeWindowCompatibilityTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var root: LinearLayout
        lateinit var ordinary: TextView
        lateinit var transparent: TextView
        instrumentation.runOnMainSync {
            root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            ordinary = TextView(activity).apply { text = "Readable ordinary text" }
            transparent = TextView(activity).apply { text = "HIDDEN_TRANSPARENT_CANARY"; setTextColor(Color.TRANSPARENT) }
            val faint = TextView(activity).apply { text = "HIDDEN_FAINT_CANARY"; setTextColor(0x01000000) }
            val input = EditText(activity).apply { setText("PRIVATE_INPUT_CANARY") }
            val blocked = LinearLayout(activity).apply {
                addView(TextView(activity).apply { text = "BLOCKED_SUBTREE_CANARY" })
            }
            dev.elu.analytics.Elu.blockView(blocked)
            for (view in listOf(ordinary, transparent, faint, input, blocked))
                root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 100))
            activity.setContentView(root)
        }
        try {
            instrumentation.waitForIdleSync()
            var ready = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!ready && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync { ready = root.isAttachedToWindow && root.hasWindowFocus() && ordinary.layout != null }
                if (!ready) Thread.sleep(20)
            }
            assertTrue("Fixture must actually render in focused window", ready)
            // A laid-out focused window can still be behind the launch surface for a frame.
            // Require actual rendered glyph pixels in the label rectangle before recording proof.
            val labelBounds = IntArray(4)
            instrumentation.runOnMainSync {
                ordinary.getLocationOnScreen(labelBounds)
                labelBounds[2] = ordinary.width; labelBounds[3] = ordinary.height
            }
            var rendered: Bitmap? = null
            val renderDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (rendered == null && System.nanoTime() < renderDeadline) {
                val candidate = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                var dark = 0
                for (y in labelBounds[1].coerceAtLeast(0) until (labelBounds[1] + labelBounds[3]).coerceAtMost(candidate.height))
                    for (x in labelBounds[0].coerceAtLeast(0) until (labelBounds[0] + labelBounds[2]).coerceAtMost(candidate.width)) {
                        val pixel = candidate.getPixel(x, y)
                        if (Color.red(pixel) < 180 && Color.green(pixel) < 180 && Color.blue(pixel) < 180) dark++
                    }
                if (dark >= 100) rendered = candidate else { candidate.recycle(); Thread.sleep(100) }
            }
            val screenshot = checkNotNull(rendered) { "Ordinary label must be readable in actual rendered frame" }
            File(instrumentation.targetContext.filesDir, "native-readable-text.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
            var snapshot: NativeMaskedSnapshot? = null
            var error: Throwable? = null
            instrumentation.runOnMainSync {
                try { snapshot = AndroidViewReplayCollector(maskingProfile = NativeMaskingProfile.sensitiveMask())
                    .collect(root, 0, 1000, NativeCollectionFence(), { true }, false) }
                catch (caught: Throwable) { error = caught }
            }
            error?.let { throw it }
            val readable = checkNotNull(snapshot).nodes.mapNotNull { (it.kind as? NativeMaskedKind.ReadableText)?.text?.value }
            assertEquals(listOf("Readable ordinary text"), readable)
            assertTrue(checkNotNull(snapshot).nodes.any { it.kind is NativeMaskedKind.Input })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
