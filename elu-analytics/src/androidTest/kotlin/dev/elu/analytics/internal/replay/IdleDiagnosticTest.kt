package dev.elu.analytics.internal.replay

import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.fail
import org.junit.Test

/** TEMPORARY diagnostic (revert before merge): does the fixture window ever let the main queue idle? */
class IdleDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Same idle signal as Instrumentation.waitForIdleSync, but bounded. Returns elapsed ms or -1. */
    private fun awaitIdle(millis: Long, onIdle: () -> Unit = {}): Long {
        val latch = CountDownLatch(1)
        val start = SystemClock.uptimeMillis()
        Looper.getMainLooper().queue.addIdleHandler { onIdle(); latch.countDown(); false }
        Handler(Looper.getMainLooper()).post { }
        return if (latch.await(millis, TimeUnit.MILLISECONDS)) SystemClock.uptimeMillis() - start else -1
    }

    private fun iteration(index: Int, withProgress: Boolean, hideBeforeIdle: Boolean = false): String {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context, NativeWindowCompatibilityTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val draws = AtomicInteger()
        val drawsAtIdle = AtomicInteger(-1)
        val runningAtIdle = AtomicReference<String>("n/a")
        var progress: ProgressBar? = null
        var focusAtSet = false
        instrumentation.runOnMainSync {
            val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val controls = arrayListOf<View>(TextView(activity).apply { text = "L" }, Button(activity).apply { text = "B" },
                EditText(activity).apply { setText("I") }, CheckBox(activity).apply { text = "C" },
                Switch(activity).apply { text = "S" }, SeekBar(activity))
            if (withProgress) controls += ProgressBar(activity).also { progress = it }
            for (view in controls) root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80))
            activity.window.decorView.viewTreeObserver.addOnDrawListener(ViewTreeObserver.OnDrawListener { draws.incrementAndGet() })
            focusAtSet = activity.window.decorView.hasWindowFocus()
            activity.setContentView(root)
            if (hideBeforeIdle) progress?.visibility = View.GONE
        }
        val idleMs = awaitIdle(3000) {
            drawsAtIdle.set(draws.get())
            runningAtIdle.set(((progress?.indeterminateDrawable as? Animatable)?.isRunning).toString())
        }
        val before = draws.get()
        Thread.sleep(1000)
        val perSecond = draws.get() - before
        var running = "n/a"; var drawable = "n/a"; var focus = false; var laidOut = false
        instrumentation.runOnMainSync {
            running = ((progress?.indeterminateDrawable as? Animatable)?.isRunning).toString()
            drawable = progress?.indeterminateDrawable?.javaClass?.simpleName ?: "none"
            focus = activity.window.decorView.hasWindowFocus()
            laidOut = activity.window.decorView.width > 0
        }
        // Does hiding the spinner let the queue idle?
        var idleAfterHide = -2L
        if (withProgress && !hideBeforeIdle) {
            instrumentation.runOnMainSync { progress?.visibility = View.GONE }
            idleAfterHide = awaitIdle(3000)
        }
        instrumentation.runOnMainSync { activity.finish() }
        val idleAfterFinish = awaitIdle(3000)
        val line = "i=$index api=${Build.VERSION.SDK_INT} progress=$withProgress hideFirst=$hideBeforeIdle drawable=$drawable " +
            "idleMs=$idleMs drawsAtIdle=${drawsAtIdle.get()} runningAtIdle=${runningAtIdle.get()} drawsPerSec=$perSecond " +
            "runningAfter=$running focusAtSet=$focusAtSet focus=$focus laidOut=$laidOut idleAfterHide=$idleAfterHide " +
            "idleAfterFinish=$idleAfterFinish"
        Log.i("EluIdleDiag", line)
        return line
    }

    @Test fun mainQueueIdleWithFixtureWindow() {
        val scale = Settings.Global.getFloat(instrumentation.targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, -1f)
        val lines = ArrayList<String>()
        lines += "animator_duration_scale=$scale"
        for (i in 0 until 12) lines += iteration(i, withProgress = true)
        for (i in 12 until 15) lines += iteration(i, withProgress = false)
        for (i in 15 until 18) lines += iteration(i, withProgress = true, hideBeforeIdle = true)
        lines.forEach { Log.i("EluIdleDiag", it) }
        fail("DIAGNOSTIC\n" + lines.joinToString("\n"))
    }
}
