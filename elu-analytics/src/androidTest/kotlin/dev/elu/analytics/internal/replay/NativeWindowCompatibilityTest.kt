package dev.elu.analytics.internal.replay

import android.app.Activity
import android.content.Intent
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Test-only host: ordinary framework window. No outline/elevation/layer/layout normalization. */
class NativeWindowCompatibilityTestActivity : Activity()

class NativeWindowCompatibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: Activity
    private lateinit var root: LinearLayout
    private val controls = ArrayList<View>()

    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    @Before fun openOrdinaryWindow() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.context, NativeWindowCompatibilityTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        main {
            root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            controls += TextView(activity).apply { text = "PRIVATE_WINDOW_LABEL" }
            controls += Button(activity).apply { text = "PRIVATE_WINDOW_BUTTON" }
            controls += EditText(activity).apply { setText("PRIVATE_WINDOW_INPUT") }
            controls += CheckBox(activity).apply { text = "PRIVATE_WINDOW_CHECK" }
            controls += Switch(activity).apply { text = "PRIVATE_WINDOW_SWITCH" }
            controls += SeekBar(activity)
            controls += ProgressBar(activity)
            for (view in controls) root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80))
            activity.setContentView(root)
        }
        instrumentation.waitForIdleSync()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var ready = false
        while (!ready && System.nanoTime() < deadline) {
            main { ready = root.isAttachedToWindow && root.hasWindowFocus() && root.width > 0 && root.height > 0 }
            if (!ready) Thread.sleep(20)
        }
        assertTrue("Actual default Activity must reach focused, attached and laid-out state", ready)
    }

    @After fun finishWindow() {
        if (::activity.isInitialized) main { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    /** Geometry-only fixture diagnostics; sampling provider output is not cached render-outline proof. */
    private fun facts(view: View): JSONObject {
        val provider = view.outlineProvider
        val label = when (provider) {
            null -> "null"
            ViewOutlineProvider.BOUNDS -> "BOUNDS"
            ViewOutlineProvider.PADDED_BOUNDS -> "PADDED_BOUNDS"
            ViewOutlineProvider.BACKGROUND -> "BACKGROUND"
            else -> provider.javaClass.name
        }
        val result = JSONObject().put("class", view.javaClass.name).put("width", view.width).put("height", view.height)
            .put("backgroundClass", view.background?.javaClass?.name ?: "null")
            .put("clipToOutline", view.clipToOutline).put("outlineProvider", label).put("alpha", view.alpha.toDouble())
            .put("elevation", view.elevation.toDouble()).put("translationZ", view.translationZ.toDouble())
            .put("layerType", view.layerType).put("matrixIdentity", view.matrix.isIdentity)
            .put("animationPresent", view.animation != null).put("hardwareAccelerated", view.isHardwareAccelerated)
        if (Build.VERSION.SDK_INT >= 29) result.put("transitionAlpha", view.transitionAlpha.toDouble())
        if (provider != null) {
            val outline = Outline()
            try {
                provider.getOutline(view, outline)
                val rect = Rect()
                result.put("diagnosticOutlineEmpty", outline.isEmpty).put("diagnosticOutlineCanClip", outline.canClip())
                if (Build.VERSION.SDK_INT >= 24) {
                    result.put("diagnosticOutlineHasRect", outline.getRect(rect)).put("diagnosticOutlineRadius", outline.radius.toDouble().takeIf { it.isFinite() } ?: JSONObject.NULL)
                    result.put("diagnosticOutlineRect", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                }
            } catch (error: Throwable) { result.put("diagnosticOutlineError", error.javaClass.name) }
        }
        return result
    }

    private fun export(name: String, result: JSONObject) {
        val directory = File(activity.filesDir, "window-compatibility").also { check(it.mkdirs() || it.isDirectory) }
        File(directory, name).writeText(result.toString())
    }

    @Test fun defaultWindowSelectionUsesActualUnmodifiedWindow(): Unit {
        val lifecycle = NativeReplayLifecycle()
        lifecycle.resumed(activity)
        val selection = lifecycle.select(activity, root).get(3, TimeUnit.SECONDS)
        main {
            val ancestors = JSONArray()
            var view: View? = root
            while (view != null) { ancestors.put(facts(view)); view = view.parent as? View }
            export("selection.json", JSONObject().put("api", Build.VERSION.SDK_INT).put("selected", selection != null)
                .put("ancestors", ancestors).put("windowWasNormalized", false))
        }
        try {
            if (Build.VERSION.SDK_INT < 29) assertNull("Legacy API selection remains denied", selection)
            else assertNotNull("Ordinary default window should qualify when its clipping is representable", selection)
        } finally { selection?.close() }
    }

    @Test fun ordinaryControlsPreserveMaskedGeometryWithoutChangingTheirRendering(): Unit = main {
        val data = JSONObject().put("api", Build.VERSION.SDK_INT).put("windowWasNormalized", false)
            .put("controls", JSONArray(controls.map { facts(it) }))
        try {
            val snapshot = AndroidViewReplayCollector().collect(root, 0, 1_788_883_200_000L,
                NativeCollectionFence(), { true }, false)
            val encodedBytes = NativeWireframeEncoder().encode(listOf(snapshot)).bytes
            val encoded = String(encodedBytes, Charsets.UTF_8)
            data.put("collected", true).put("nodeCount", snapshot.nodes.size)
            data.put("containsLayoutBounds", snapshot.containsLayoutBounds)
            data.put("encoded", JSONArray(encoded))
            data.put("encoderBytesBase64", Base64.encodeToString(encodedBytes, Base64.NO_WRAP))
            data.put("encoderByteLength", encodedBytes.size)
            data.put("encoderBytesSha256", MessageDigest.getInstance("SHA-256").digest(encodedBytes)
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
            data.put("privateMarkerPresent", encoded.contains("PRIVATE_WINDOW"))
            export("controls.json", data)
            assertEquals(8, snapshot.nodes.size)
            assertTrue("Actual default-window uncertainty must be explicit", snapshot.containsLayoutBounds)
            assertTrue(snapshot.nodes.all { it.geometry == NativeGeometryKind.LAYOUT_BOUNDS })
            val wireRoot = JSONArray(encoded).getJSONObject(1).getJSONObject("data").getJSONArray("wireframes").getJSONObject(0)
            assertFalse(wireRoot.has("geometry"))
            val leaves = wireRoot.getJSONArray("childWireframes")
            assertEquals(8, leaves.length())
            for (index in 0 until leaves.length()) assertEquals("layout-bounds", leaves.getJSONObject(index).getString("geometry"))
            assertFalse(encoded.contains("PRIVATE_WINDOW"))
        } catch (error: Throwable) {
            data.put("collected", false).put("failure", (error as? NativeCollectionException)?.failure?.name ?: error.javaClass.name)
            export("controls.json", data)
            throw error
        }
    }
}
