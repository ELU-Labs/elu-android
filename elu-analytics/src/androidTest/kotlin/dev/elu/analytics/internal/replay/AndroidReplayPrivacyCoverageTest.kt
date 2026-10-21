package dev.elu.analytics.internal.replay

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.platform.ComposeView
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.Elu
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Privacy cases the iOS UIKit replay suite covers, on real framework Views through the Views collector
 * and the v1 encoder bytes. Masked text is asserted as its `*` shape.
 */
class AndroidReplayPrivacyCoverageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val sensitive = NativeMaskingProfile.sensitiveMask()
    private val opened = ArrayList<Activity>()

    @Before fun requireWireframeReplay() {
        // Visible skip: the Views wireframe path never runs below API 29.
        assumeTrue("Views wireframe replay requires API 29+; this device runs API ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 29)
    }

    @After fun finishActivities() {
        if (opened.isEmpty()) return
        instrumentation.runOnMainSync { opened.forEach { it.finish() } }
        waitForMainIdle("after finishing the privacy fixture Activities")
    }

    private fun <T : Activity> open(type: Class<T>): T {
        val activity = type.cast(instrumentation.startActivitySync(Intent(instrumentation.context, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))!!
        opened += activity
        waitForMainIdle("after launching ${type.simpleName}")
        return activity
    }

    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun await(message: String, condition: () -> Boolean) {
        waitForMainIdle("before waiting for: $message")
        var ready = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready && System.nanoTime() < deadline) {
            main { ready = condition() }
            if (!ready) Thread.sleep(20)
        }
        assertTrue(message, ready)
    }

    private fun settled(view: View) = view.isAttachedToWindow && view.hasWindowFocus() && view.isLaidOut &&
        !view.isLayoutRequested && view.width > 0 && view.height > 0

    /** Hand-laid-out root in the private rectangular test window, collected in the same main pass. */
    private fun fixture(test: (Activity, FrameLayout) -> Unit) {
        val activity = open(NativeCollectorTestActivity::class.java)
        main {
            // This private fixture selects a rectangular window. Production never changes host clipping.
            activity.window.decorView.clipToOutline = false
            val root = FrameLayout(activity)
            activity.setContentView(root)
            // Fill the content frame exactly, so no fixture view is clipped by the window.
            val frame = activity.findViewById<View>(android.R.id.content)
            assertTrue(frame.width > 0 && frame.height > 0)
            root.layout(0, 0, frame.width, frame.height)
            test(activity, root)
        }
    }

    private fun place(parent: ViewGroup, view: View, x: Int, y: Int, width: Int, height: Int) {
        parent.addView(view, ViewGroup.LayoutParams(width, height))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(x, y, x + width, y + height)
    }

    private fun View.dp(value: Int) = ceil(value * resources.displayMetrics.density).toInt()

    private fun collect(root: View) = AndroidViewReplayCollector(maskingProfile = sensitive)
        .collect(root, 0, 1_788_883_200_000L, NativeCollectionFence(), { true }, false)

    private fun encoded(frame: NativeMaskedSnapshot) =
        String(NativeWireframeEncoder(maskingProfile = sensitive).encode(listOf(frame)).bytes, Charsets.UTF_8)

    /** The node whose bounds are exactly this view's layout rectangle relative to the collected root. */
    private fun node(frame: NativeMaskedSnapshot, root: View, view: View): NativeMaskedNode {
        val density = root.resources.displayMetrics.density.toDouble()
        val origin = IntArray(2).also { root.getLocationInWindow(it) }
        val own = IntArray(2).also { view.getLocationInWindow(it) }
        val x = (own[0] - origin[0]) / density; val y = (own[1] - origin[1]) / density
        return frame.nodes.singleOrNull {
            abs(it.bounds.x - x) < 1e-6 && abs(it.bounds.y - y) < 1e-6 &&
                abs(it.bounds.width - view.width / density) < 1e-6 && abs(it.bounds.height - view.height / density) < 1e-6
        } ?: throw AssertionError("No single node for ${view.javaClass.simpleName} in ${frame.nodes.map { it.kind to it.bounds }}")
    }

    private fun readable(frame: NativeMaskedSnapshot, root: View, view: View): String? =
        (node(frame, root, view).kind as? NativeMaskedKind.ReadableText)?.text?.value

    /** Reading this row's text, or visiting it at all, means a list's rows were inspected. */
    private class TrapRow(context: Context, label: String) : TextView(context) {
        var armed = false
        init { text = label }
        override fun getText(): CharSequence { check(!armed) { "row text read" }; return super.getText() }
        override fun getContentDescription(): CharSequence? { check(!armed) { "row description read" }; return super.getContentDescription() }
        override fun getLocationInWindow(outLocation: IntArray) { check(!armed) { "row visited" }; super.getLocationInWindow(outLocation) }
    }

    /** Every content getter a WebView exposes fails if replay reads it. */
    private class TrapWebView(context: Context) : WebView(context) {
        var armed = false
        override fun getUrl(): String? { check(!armed) { "WebView URL read" }; return super.getUrl() }
        override fun getOriginalUrl(): String? { check(!armed) { "WebView original URL read" }; return super.getOriginalUrl() }
        override fun getTitle(): String? { check(!armed) { "WebView title read" }; return super.getTitle() }
        override fun getProgress(): Int { check(!armed) { "WebView progress read" }; return super.getProgress() }
        override fun getFavicon(): Bitmap? { check(!armed) { "WebView icon read" }; return super.getFavicon() }
        override fun getContentDescription(): CharSequence? { check(!armed) { "WebView description read" }; return super.getContentDescription() }
    }

    @Test fun recyclingListViewsArePlaceholdersWithoutVisitingRows() = fixture { activity, root ->
        val rows = ArrayList<TrapRow>()
        fun adapter(prefix: String) = object : BaseAdapter() {
            override fun getCount() = 20
            override fun getItem(position: Int): Any = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (convertView as? TrapRow ?: TrapRow(activity, "").also { rows += it }).apply { text = "${prefix}_$position" }
        }
        val list = ListView(activity).apply { adapter = adapter("PRIVATE_LIST_ROW") }
        place(root, list, 20, 20, 240, 160)
        val grid = GridView(activity).apply { numColumns = 2; adapter = adapter("PRIVATE_GRID_CELL") }
        place(root, grid, 20, 200, 240, 160)
        assertTrue("ListView must lay out real rows", list.childCount > 0)
        assertTrue("GridView must lay out real cells", grid.childCount > 0)
        rows.forEach { it.armed = true }
        val frame = try { collect(root) } finally { rows.forEach { it.armed = false } }
        assertEquals("Root, list and grid only: rows are never visited", 3, frame.nodes.size)
        assertEquals(NativeMaskedKind.Placeholder, node(frame, root, list).kind)
        assertEquals(NativeMaskedKind.Placeholder, node(frame, root, grid).kind)
        val bytes = encoded(frame)
        assertTrue(bytes.contains(NativeWireframeEncoder.PLACEHOLDER)); assertFalse(bytes.contains("PRIVATE_"))
    }

    @Test fun ellipsizedTruncatedAndClippedTextNeverExposesTheHiddenSuffix() = fixture { activity, root ->
        val suffix = "PRIVATE_HIDDEN_SUFFIX"
        val long = "Visible start of a much longer sentence that keeps going until $suffix"
        val ellipsized = TextView(activity).apply { text = long; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        // Each view is tall enough for one line, so only the hidden remainder can mask it.
        place(root, ellipsized, root.dp(8), root.dp(8), root.dp(160), root.dp(40))
        val limited = TextView(activity).apply { text = long; maxLines = 1 }
        place(root, limited, root.dp(8), root.dp(56), root.dp(160), root.dp(40))
        val short = TextView(activity).apply { text = long }
        place(root, short, root.dp(8), root.dp(104), root.dp(160), root.dp(40))
        val scrolling = TextView(activity).apply { text = long; setHorizontallyScrolling(true) }
        place(root, scrolling, root.dp(8), root.dp(152), root.dp(160), root.dp(40))
        val fits = TextView(activity).apply { text = "Visible start" }
        place(root, fits, root.dp(8), root.dp(200), root.dp(160), root.dp(40))
        val frame = collect(root)
        // Truncated or clipped text is masked in full: the hidden part's length shows, its content does not.
        for ((name, view) in listOf("maxLines" to limited, "overflowing" to short, "scrolling" to scrolling))
            assertEquals(name, NativeMaskedKind.MaskedText(NativeMaskText.maskText(long)), node(frame, root, view).kind)
        val shape = (node(frame, root, ellipsized).kind as NativeMaskedKind.MaskedText).text
        assertTrue(shape, NativeMaskText.isMaskShaped(shape)); assertEquals(shape, long.length, shape.length)
        // The same view size keeps text that fits readable, so the masking above is the hidden remainder.
        assertEquals("Visible start", readable(frame, root, fits))
        val bytes = encoded(frame)
        assertTrue(bytes.contains(NativeMaskText.maskText(long))); assertFalse(bytes.contains("[masked]"))
        assertFalse(bytes.contains(suffix)); assertFalse(bytes.contains("keeps going"))
    }

    @Test fun buttonTitlesMultilineTextAndLargeFontScaleFollowTheVisibilityRules() = fixture { activity, root ->
        val margin = root.dp(8)
        val width = root.width - 2 * margin
        assertTrue("Fixture window is too narrow: ${root.width}", width >= root.dp(280))
        val plainButton = Button(activity).apply { text = "Continue"; isAllCaps = false }
        place(root, plainButton, margin, root.dp(8), width, root.dp(56))
        val capsButton = Button(activity).apply { text = "Continue" }
        place(root, capsButton, margin, root.dp(72), width, root.dp(56))
        val multiline = TextView(activity).apply { text = "First line\nSecond line" }
        place(root, multiline, margin, root.dp(136), width, root.dp(56))
        val wrapped = TextView(activity).apply {
            text = "Short words wrap here"
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE; hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
        place(root, wrapped, margin, root.dp(200), ceil(wrapped.paint.measureText("Short words wrap ")).toInt() + root.dp(4), root.dp(56))
        val large = ContextThemeWrapper(activity, android.R.style.Theme_Material_Light_NoActionBar).apply {
            applyOverrideConfiguration(Configuration(activity.resources.configuration).apply { fontScale = 2f })
        }
        val roomy = TextView(large).apply { text = "Large type"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        place(root, roomy, margin, root.dp(264), width, root.dp(72))
        val cramped = TextView(large).apply { text = "Large type"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        place(root, cramped, margin, root.dp(344), root.dp(40), root.dp(12))
        val normalSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, activity.resources.displayMetrics)
        assertTrue("Font scale 2 must enlarge the text: ${roomy.textSize} vs $normalSize", roomy.textSize >= normalSize * 1.5f)
        assertTrue("Fixture must wrap", wrapped.layout.lineCount > 1)

        val frame = collect(root)
        assertEquals("Continue", readable(frame, root, plainButton))
        // The theme's all-caps transformation changes the displayed string, so the title stays masked.
        assertEquals(NativeMaskedKind.MaskedText("********"), node(frame, root, capsButton).kind)
        assertEquals("First line\nSecond line", readable(frame, root, multiline))
        assertEquals("Short words wrap here", readable(frame, root, wrapped))
        assertEquals("Large type", readable(frame, root, roomy))
        assertEquals("Large type that does not fit stays masked", NativeMaskedKind.MaskedText("***** ****"),
            node(frame, root, cramped).kind)
        val bytes = encoded(frame)
        assertTrue(bytes.contains("Continue")); assertTrue(bytes.contains("Short words wrap here"))
        assertTrue(bytes.contains("\"text\":\"***** ****\"")); assertFalse(bytes.contains("CONTINUE"))
    }

    @Test fun laterMaskCannotWeakenAnEarlierBlock() = fixture { activity, root ->
        val blockedFirst = FrameLayout(activity); place(root, blockedFirst, 20, 20, 200, 80)
        val first = TrapRow(activity, "PRIVATE_BLOCKED_THEN_MASKED"); place(blockedFirst, first, 0, 0, 200, 40)
        val maskedFirst = FrameLayout(activity); place(root, maskedFirst, 20, 120, 200, 80)
        val second = TrapRow(activity, "PRIVATE_MASKED_THEN_BLOCKED"); place(maskedFirst, second, 0, 0, 200, 40)
        Elu.blockView(blockedFirst); Elu.maskView(blockedFirst)
        Elu.maskView(maskedFirst); Elu.blockView(maskedFirst)
        first.armed = true; second.armed = true
        val frame = try { collect(root) } finally { first.armed = false; second.armed = false }
        assertEquals("Blocked subtrees are never visited", 3, frame.nodes.size)
        assertEquals(NativeMaskedKind.Placeholder, node(frame, root, blockedFirst).kind)
        assertEquals(NativeMaskedKind.Placeholder, node(frame, root, maskedFirst).kind)
        val bytes = encoded(frame)
        assertTrue(bytes.contains(NativeWireframeEncoder.PLACEHOLDER)); assertFalse(bytes.contains("PRIVATE_"))
    }

    @Test fun webViewGettersAreNeverReadAndItsBytesEncodeThePlaceholder() {
        // Every CI emulator image ships a WebView provider: a missing one fails instead of skipping.
        assertNotNull("WebView provider", WebView.getCurrentWebViewPackage())
        fixture { activity, root ->
            val web = TrapWebView(activity)
            try {
                place(root, web, 20, 20, 240, 160)
                web.loadDataWithBaseURL("https://example.invalid/PRIVATE_WEB_URL", "<title>PRIVATE_WEB_TITLE</title><p>PRIVATE_WEB_TEXT</p>",
                    "text/html", "utf-8", null)
                web.contentDescription = "PRIVATE_WEB_DESCRIPTION"
                val child = TrapRow(activity, "PRIVATE_WEB_CHILD"); place(web, child, 0, 0, 120, 40)
                web.armed = true; child.armed = true
                val frame = try { collect(root) } finally { web.armed = false; child.armed = false }
                assertEquals(2, frame.nodes.size)
                assertEquals(NativeMaskedKind.Placeholder, node(frame, root, web).kind)
                val bytes = encoded(frame)
                assertTrue(bytes.contains(NativeWireframeEncoder.PLACEHOLDER)); assertFalse(bytes.contains("PRIVATE_"))
            } finally { root.removeView(web); web.destroy() }
        }
    }

    @Test fun realComposeViewIsAnOpaquePlaceholder() {
        val activity = open(NativeAppCompatTestActivity::class.java)
        lateinit var column: LinearLayout
        lateinit var compose: ComposeView
        main {
            column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            compose = ComposeView(activity).apply { setContent { BasicText("PRIVATE_COMPOSE_TEXT") } }
            column.addView(compose, LinearLayout.LayoutParams(240, 120))
            activity.setContentView(column)
        }
        await("ComposeView composed and laid out") { settled(column) && settled(compose) && compose.childCount > 0 }
        main {
            val content = activity.findViewById<View>(android.R.id.content)
            val frame = collect(content)
            assertEquals("Content root, column and ComposeView only", 3, frame.nodes.size)
            assertEquals(NativeMaskedKind.Placeholder, node(frame, content, compose).kind)
            val bytes = encoded(frame)
            assertTrue(bytes.contains(NativeWireframeEncoder.PLACEHOLDER)); assertFalse(bytes.contains("PRIVATE_"))
        }
    }

    @Test fun realDialogTakesFocusSoItsActivityIsNotCapturedAndTheDialogNeverEntersTheFrame() {
        val activity = open(NativeWindowCompatibilityTestActivity::class.java)
        lateinit var column: LinearLayout
        main {
            column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(activity).apply { text = "Account overview" }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80))
            activity.setContentView(column)
        }
        await("activity focused") { settled(column) }
        val lifecycle = NativeReplayLifecycle()
        lifecycle.resumed(activity)
        assertEquals(NativeReplayRootReadiness.AVAILABLE, lifecycle.observeRootReadiness { true }.get(5, TimeUnit.SECONDS))
        val selection = checkNotNull(lifecycle.selectCurrent().get(5, TimeUnit.SECONDS))
        var dialog: Dialog? = null
        lateinit var dialogText: TextView
        try {
            assertTrue(selection.isCurrent())
            main {
                dialogText = TextView(activity).apply { text = "PRIVATE_DIALOG_TEXT" }
                dialog = Dialog(activity).apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    setContentView(dialogText)
                    show()
                }
            }
            await("dialog takes focus from the activity") { settled(dialogText) && !column.hasWindowFocus() }
            assertFalse("Focus loss withdraws the activity selection", selection.isCurrent())
            assertEquals(NativeReplayRootReadiness.WAITING, lifecycle.observeRootReadiness { true }.get(5, TimeUnit.SECONDS))
            main {
                assertNotSame("The Dialog is its own window", activity.window.decorView, dialogText.rootView)
                val bytes = encoded(collect(activity.findViewById(android.R.id.content)))
                assertTrue(bytes.contains("Account overview")); assertFalse(bytes.contains("PRIVATE_"))
            }
            main { dialog?.dismiss() }
            await("activity regains focus") { settled(column) }
            assertEquals(NativeReplayRootReadiness.AVAILABLE, lifecycle.observeRootReadiness { true }.get(5, TimeUnit.SECONDS))
        } finally {
            selection.closeAndWait().get(5, TimeUnit.SECONDS)
            main { dialog?.takeIf { it.isShowing }?.dismiss() }
        }
    }

    @Test fun realPopupWindowIsASeparateWindowThatNeverEntersTheActivityFrame() {
        val activity = open(NativeWindowCompatibilityTestActivity::class.java)
        lateinit var column: LinearLayout
        main {
            column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(activity).apply { text = "Account overview" }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80))
            activity.setContentView(column)
        }
        await("activity focused") { settled(column) }
        lateinit var popup: PopupWindow
        lateinit var popupText: TextView
        main {
            popupText = TextView(activity).apply { text = "PRIVATE_POPUP_TEXT" }
            popup = PopupWindow(popupText, 200, 80, false)
            popup.showAtLocation(column, Gravity.TOP or Gravity.START, 0, 0)
        }
        try {
            await("popup shown") { popupText.isAttachedToWindow && popupText.isLaidOut && popupText.width > 0 }
            main {
                assertTrue("A non-focusable popup leaves the activity focused", column.hasWindowFocus())
                assertNotSame("The popup is its own window", activity.window.decorView, popupText.rootView)
                val bytes = encoded(collect(activity.findViewById(android.R.id.content)))
                assertTrue(bytes.contains("Account overview")); assertFalse(bytes.contains("PRIVATE_"))
            }
        } finally { main { popup.dismiss() } }
    }

    @Test fun surfaceAndTextureViewsArePlaceholders() = fixture { activity, root ->
        val surface = SurfaceView(activity); place(root, surface, 20, 20, 160, 100)
        val texture = TextureView(activity); place(root, texture, 20, 140, 160, 100)
        val frame = collect(root)
        assertEquals(3, frame.nodes.size)
        assertEquals(NativeMaskedKind.Placeholder, node(frame, root, surface).kind)
        assertEquals(NativeMaskedKind.Placeholder, node(frame, root, texture).kind)
        assertTrue(encoded(frame).contains(NativeWireframeEncoder.PLACEHOLDER))
    }
}
