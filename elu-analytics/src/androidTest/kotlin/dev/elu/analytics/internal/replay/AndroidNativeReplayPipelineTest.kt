package dev.elu.analytics.internal.replay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.Elu
import dev.elu.analytics.internal.concurrent.SdkFuture
import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.facade.AndroidStandaloneStack
import dev.elu.analytics.internal.runtime.*
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Real Views in an ordinary window through the production composition: Views collector, v1
 * encoder, sealer, gzip and the SQLite replay queue. The stored chunks are then decoded.
 * Counterpart of the iOS sensitive-capture storage test.
 */
class AndroidNativeReplayPipelineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val key = "elu_pk_live_AAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val origin = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
    private lateinit var activity: Activity
    private lateinit var content: View
    private lateinit var column: LinearLayout
    private lateinit var label: TextView
    private lateinit var typed: EditText
    private lateinit var password: EditText
    private lateinit var masked: FrameLayout
    private lateinit var blocked: FrameLayout
    private lateinit var image: ImageView
    private lateinit var web: WebView

    @Before fun openWindow() {
        // Visible skip: the Views wireframe path never runs below API 29.
        assumeTrue("Views wireframe replay requires API 29+; this device runs API ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= 29)
        activity = instrumentation.startActivitySync(Intent(instrumentation.context, NativeWindowCompatibilityTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        main {
            // Typing goes through an InputConnection; the soft keyboard must not resize the window.
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fun add(view: View, height: Int) = column.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height)))
            label = TextView(activity).apply { text = LABEL }; add(label, 48)
            typed = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT }; add(typed, 56)
            password = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }; add(password, 56)
            masked = FrameLayout(activity).apply {
                addView(TextView(activity).apply { text = MASKED_CANARY },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }; add(masked, 48)
            blocked = FrameLayout(activity).apply { addView(TextView(activity).apply { text = BLOCKED_CANARY }) }; add(blocked, 48)
            image = ImageView(activity).apply { setImageDrawable(ColorDrawable(Color.RED)); contentDescription = IMAGE_CANARY }
            add(image, 48)
            web = WebView(activity); add(web, 72)
            Elu.maskView(masked); Elu.blockView(blocked)
            activity.setContentView(column)
            content = activity.findViewById(android.R.id.content)
            web.loadDataWithBaseURL(null, "<p>$WEB_CANARY</p>", "text/html", "utf-8", null)
        }
        awaitSettled("ordinary window focused and laid out")
        main {
            // Real typing path: text arrives through the editor's InputConnection, not setText.
            assertTrue(checkNotNull(typed.onCreateInputConnection(EditorInfo())).commitText(TYPED_CANARY, 1))
            assertTrue(checkNotNull(password.onCreateInputConnection(EditorInfo())).commitText(PASSWORD_CANARY, 1))
            assertEquals(TYPED_CANARY, typed.text.toString()); assertEquals(PASSWORD_CANARY, password.text.toString())
        }
        awaitSettled("typed text laid out")
    }

    @After fun closeWindow() {
        if (!::activity.isInitialized) return
        main {
            if (::web.isInitialized) { column.removeView(web); web.destroy() }
            activity.finish()
        }
        waitForMainIdle("after finishing the pipeline fixture Activity")
    }

    private fun dp(value: Int) = kotlin.math.ceil(value * activity.resources.displayMetrics.density).toInt()

    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun awaitSettled(message: String) {
        waitForMainIdle("before waiting for: $message")
        var ready = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready && System.nanoTime() < deadline) {
            main { ready = column.isAttachedToWindow && column.hasWindowFocus() && column.isLaidOut &&
                !column.isLayoutRequested && column.width > 0 && label.layout != null }
            if (!ready) Thread.sleep(20)
        }
        assertTrue(message, ready)
    }

    private fun until(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) { assertTrue(message, System.nanoTime() < deadline); Thread.sleep(10) }
    }

    @Test fun realViewsReachStoredChunksWithReadableLabelsAndNoPrivateContent() = Rig().use { rig ->
        assertEquals(NativeReplayCompositionEvaluation.ACTIVE, rig.composition.reevaluate().get(5, TimeUnit.SECONDS))
        until("first chunk stored") { rig.rows().size == 1 }
        until("capture waits for the next tick") { rig.waits.get() >= 1 }

        lateinit var inserted: TextView
        main {
            label.text = UPDATED_LABEL
            inserted = TextView(activity).apply { text = INSERTED_LABEL }
            column.addView(inserted, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
            assertTrue(checkNotNull(typed.onCreateInputConnection(EditorInfo())).commitText(SECOND_TYPED_CANARY, 1))
        }
        awaitSettled("changed views laid out")
        main { assertEquals(INSERTED_LABEL, inserted.layout?.text?.toString()) }
        val waited = rig.waits.get()
        rig.next()
        until("second frame collected") { rig.waits.get() > waited }
        rig.composition.stopRecording().get(5, TimeUnit.SECONDS)
        until("stop seals the tail chunk") { rig.rows().size == 2 }

        val rows = rig.rows().sortedBy { it.prepared.sequence }
        assertEquals(2, rows.size); assertTrue(rows[0].prepared.sequence < rows[1].prepared.sequence)
        assertEquals(1, rows.map { it.prepared.replayId }.toSet().size)
        val decoded = rows.map { row ->
            val request = String(row.prepared.copyBytes(), Charsets.UTF_8)
            assertNoPrivateContent("stored request ${row.prepared.sequence}", request)
            val chunk = JSONObject(request).getJSONObject("chunk")
            assertEquals("elu-native-wireframe-v1", chunk.getString("codec"))
            assertEquals("gzip", chunk.getString("compression"))
            assertEquals("base64", chunk.getString("contentEncoding"))
            val gzip = Base64.getDecoder().decode(chunk.getString("payload"))
            assertEquals(0x1f, gzip[0].toInt() and 255); assertEquals(0x8b, gzip[1].toInt() and 255)
            String(GZIPInputStream(ByteArrayInputStream(gzip)).use { it.readBytes() }, Charsets.UTF_8)
        }
        decoded.forEachIndexed { index, events -> assertNoPrivateContent("decoded chunk $index", events) }
        for (file in rig.storeFiles()) assertNoPrivateContent("SQLite file ${file.name}", String(file.readBytes(), Charsets.ISO_8859_1))

        val first = wireframes(decoded[0])
        lateinit var expected: Map<String, DoubleArray>
        main { expected = mapOf("label" to rect(label), "typed" to rect(typed), "password" to rect(password),
            "blocked" to rect(blocked), "image" to rect(image), "web" to rect(web)) }
        fun at(name: String): JSONObject {
            val r = checkNotNull(expected[name])
            return first.singleOrNull { abs(it.getDouble("x") - r[0]) < 1e-6 && abs(it.getDouble("y") - r[1]) < 1e-6 &&
                abs(it.getDouble("width") - r[2]) < 1e-6 && abs(it.getDouble("height") - r[3]) < 1e-6 }
                ?: throw AssertionError("No single wireframe for $name in $first")
        }
        assertEquals("text", at("label").getString("type")); assertEquals(LABEL, at("label").getString("text"))
        // Input values carry one `*` per character, never the typed text.
        for ((name, canary) in listOf("typed" to TYPED_CANARY, "password" to PASSWORD_CANARY)) {
            assertEquals("input", at(name).getString("type")); assertEquals(stars(canary.length), at(name).getString("value"))
        }
        assertEquals("password", at("password").getString("inputType"))
        for (name in listOf("blocked", "image", "web")) {
            assertEquals(name, "placeholder", at(name).getString("type"))
            assertEquals(name, NativeWireframeEncoder.PLACEHOLDER, at(name).getString("label"))
        }
        assertTrue("maskView text encodes its star shape", first.any {
            it.optString("type") == "text" && it.optString("text") == stars(MASKED_CANARY.length)
        })

        val second = decoded[1]
        assertTrue("Changed label reaches the second chunk", second.contains(UPDATED_LABEL))
        assertTrue("Inserted label reaches the second chunk", second.contains(INSERTED_LABEL))
        assertFalse(second.contains(LABEL))
        assertTrue("Typing changes only the star count", second.contains("\"value\":\"${stars(TYPED_CANARY.length + SECOND_TYPED_CANARY.length)}\""))
        assertTrue(second.contains("\"text\":\"${stars(MASKED_CANARY.length)}\"")); assertTrue(second.contains(NativeWireframeEncoder.PLACEHOLDER))
        for (events in decoded) assertFalse("The earlier fixed word is gone", events.contains("[masked]"))
    }

    private fun stars(count: Int) = "*".repeat(count)

    /** Layout rectangle in the selected content root's logical coordinates, as the collector projects it. */
    private fun rect(view: View): DoubleArray {
        val density = content.resources.displayMetrics.density.toDouble()
        val root = IntArray(2).also { content.getLocationInWindow(it) }
        val own = IntArray(2).also { view.getLocationInWindow(it) }
        return doubleArrayOf((own[0] - root[0]) / density, (own[1] - root[1]) / density, view.width / density, view.height / density)
    }

    private fun wireframes(events: String): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        val array = JSONArray(events)
        for (index in 0 until array.length()) {
            val event = array.getJSONObject(index)
            val data = event.optJSONObject("data") ?: continue
            when (event.getInt("type")) {
                2 -> {
                    val children = data.getJSONArray("wireframes").getJSONObject(0).getJSONArray("childWireframes")
                    for (child in 0 until children.length()) result += children.getJSONObject(child)
                }
                3 -> {
                    val adds = data.getJSONArray("adds")
                    for (add in 0 until adds.length()) result += adds.getJSONObject(add).getJSONObject("wireframe")
                }
            }
        }
        return result
    }

    private fun assertNoPrivateContent(where: String, text: String) {
        for (canary in listOf(TYPED_CANARY, SECOND_TYPED_CANARY, PASSWORD_CANARY, MASKED_CANARY, BLOCKED_CANARY, IMAGE_CANARY, WEB_CANARY))
            assertFalse("$where contains $canary", text.contains(canary))
        assertFalse("$where contains a private marker", text.contains("PRIVATE_"))
    }

    private inner class Rig : AutoCloseable {
        val now = AtomicLong(origin)
        val waits = AtomicInteger()
        private val step = Semaphore(0)
        private val io = Executors.newSingleThreadExecutor()
        private val dir = File(instrumentation.targetContext.cacheDir, "replay-pipeline-" + UUID.randomUUID())
        private val configClock = object : V2ConfigClock {
            override fun wallNowEpochMillis() = now.get()
            override fun monotonicNowNanos() = 1_000_000_000L + (now.get() - origin) * 1_000_000L
        }
        private val gate = V2ConfigAuthorityGate()
        private val wire = config()
        private val source = V2ConfigSource("https://elu.dev", key, V2ConfigTransport { V2ConfigHttpResponse(200, wire) }, configClock)
        private val tasks = ArrayDeque<() -> Unit>()
        private val driver = V2ConfigLifecycleDriver(source, gate::update, configClock, object : V2ConfigLifecycleScheduler {
            override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
            override fun close() = Unit
        }, object : V2ConfigLifecycleWorker {
            override fun execute(task: () -> Unit) { tasks.add(task) }
            override fun interruptCurrent() = Unit
            override fun close() = Unit
        })
        private val protocols = AndroidStandaloneStack.installedNativeReplayProtocols
        val owner = RuntimeQueueOwner.open("replay-pipeline-" + UUID.randomUUID(), RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
            readbackProvenReplayTransports = protocols.map { it.transport }.toSet(),
            supportedReplayProtocolGenerations = protocols.map { it.generation }.toSet(),
            databaseFactory = { AndroidSQLiteRuntimeDatabase.open(File(dir, "queue.sqlite")) }, legacyStateLoader = { initial() },
            trustedSiteKey = key, captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = now.get()
                override fun elapsedRealtimeNanos() = configClock.monotonicNowNanos()
            }).get()
        private val lifecycle = NativeReplayLifecycle()
        /** Production platform and collector; only the wait between frames is driven by the test. */
        private val platform = object : NativeReplayCapturePlatform by AndroidNativeReplayCapturePlatform {
            override fun awaitNext(withdrawn: CountDownLatch): Boolean {
                waits.incrementAndGet()
                while (!withdrawn.await(10, TimeUnit.MILLISECONDS)) if (step.tryAcquire()) return true
                return false
            }
        }
        val composition: NativeReplayComposition
        init {
            owner.bindConfigurationGate(gate).get()
            owner.ensurePreparedReplayStorage().get(); owner.ensureNativeReplayAccounting().get()
            driver.start(); tasks.removeFirst().invoke()
            val base = checkNotNull(gate.snapshot()?.body); val parsed = V1ConfigJson.parseConfig(base)
            val privacy = PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(parsed.privacy),
                checkNotNull(parsed.features), checkNotNull(parsed.replayCapabilities), owner.snapshot().get().state.identity,
                false, RuntimeWallTimestamps.rfc3339(now.get()))))
            assertTrue(owner.submitCaptureAuthority(base, privacy).get() is RuntimeCaptureAuthorityUpdateResult.Activated)
            lifecycle.resumed(activity)
            composition = NativeReplayComposition(owner, lifecycle, NativeReplayCapabilities(protocols.map { it.transport }.toSet(),
                protocols.map { it.generation }.toSet()), StandaloneRuntime.defaultVersions(), { false }, { true }, platform,
                // Offline delivery: every attempt fails like a dropped connection, so chunks stay queued.
                ReplayDeliveryTransport { _, _ ->
                    val future = SdkFuture<ReplayTransportResponse>()
                    io.execute { future.completeExceptionally(java.io.IOException("offline test transport")) }
                    object : ReplayTransportOperation {
                        override val settlement = future
                        override fun cancel() = Unit
                    }
                })
            composition.ready().get(5, TimeUnit.SECONDS)
        }
        fun rows(): List<ReplayStoredChunk> = owner.storedPreparedReplayForTesting().get(5, TimeUnit.SECONDS)
        fun next() { now.addAndGet(1_000); step.release() }
        fun storeFiles(): List<File> = dir.walkTopDown().filter { it.isFile }.toList().also { assertTrue(it.isNotEmpty()) }
        override fun close() {
            try { composition.closeAndWait().get(5, TimeUnit.SECONDS) }
            finally {
                driver.close(); owner.closeAsync().get(5, TimeUnit.SECONDS)
                io.shutdown(); assertTrue(io.awaitTermination(5, TimeUnit.SECONDS)); dir.deleteRecursively()
            }
        }
    }

    private fun initial() = PersistedCoreState(identity = IdentityState(revision = 1, contextRevision = 1,
        anonymousId = "pipeline-anon", userId = null, groups = emptyMap(), superProperties = emptyMap(),
        session = SessionState("pipeline-session", "2026-08-05T00:01:00Z", "2026-08-05T00:01:00Z", 1800,
            lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null), optedOut = false, updatedAt = "2026-08-05T00:01:00Z"),
        stream = StreamState(streamId = "pipeline-stream", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))

    /** Enabled v2 configuration granting native wireframe v1 with sensitive text and no minimum duration. */
    private fun config(): String {
        val base = JSONObject(BASE)
        base.getJSONObject("privacy").getJSONObject("replay").put("sampleRate", 1).put("minimumDurationSeconds", 0)
        val replay = base.getJSONObject("capabilities").getJSONObject("replay")
        replay.put("replayProtocolGeneration", NativeReplayProtocol.V1.generation)
        replay.put("transports", JSONArray().put(JSONObject().put("codec", NativeReplayProtocol.V1.codec).put("compression", "gzip")))
        return base.toString()
    }

    private companion object {
        const val LABEL = "Readable account label"
        const val UPDATED_LABEL = "Updated readable label"
        const val INSERTED_LABEL = "Inserted readable label"
        const val TYPED_CANARY = "PRIVATE_TYPED_CANARY"
        const val SECOND_TYPED_CANARY = "PRIVATE_SECOND_TYPED_CANARY"
        const val PASSWORD_CANARY = "PRIVATE_PASSWORD_CANARY"
        const val MASKED_CANARY = "PRIVATE_MASKED_CANARY"
        const val BLOCKED_CANARY = "PRIVATE_BLOCKED_CANARY"
        const val IMAGE_CANARY = "PRIVATE_IMAGE_CANARY"
        const val WEB_CANARY = "PRIVATE_WEB_CANARY"
        val BASE = """{
    "schemaVersion": 2,
    "revision": "config-v2-2026-08-05-1",
    "issuedAt": "2026-08-05T00:00:00.000Z",
    "expiresAt": "2026-08-05T00:05:00.000Z",
    "status": "enabled",
    "site": {
        "id": "site_demo"
    },
    "endpoints": {
        "events": "https://ingest.elu.dev/v1/events",
        "replay": "https://ingest.elu.dev/v2/replay",
        "flags": "https://ingest.elu.dev/v1/flags",
        "assets": "https://assets.elu.dev/sdk/"
    },
    "privacy": {
        "schemaVersion": 1,
        "revision": "privacy-1",
        "capture": {
            "enabled": true
        },
        "replay": {
            "enabled": true,
            "sampleRate": 0.25,
            "minimumDurationSeconds": 2,
            "maximumDurationSeconds": 3600
        },
        "masking": {
            "text": "sensitive",
            "inputs": "all",
            "images": "block",
            "secureInputsMasked": true,
            "platformRules": [
                {
                    "platform": "browser",
                    "action": "mask",
                    "targetDialect": "elu-css-selector-v1",
                    "target": ".elu-mask"
                },
                {
                    "platform": "browser",
                    "action": "block",
                    "targetDialect": "elu-css-selector-v1",
                    "target": ".elu-block"
                }
            ]
        },
        "regionPolicy": {
            "mode": "block-eu-on-device",
            "evaluator": "elu-eu-timezone-v1"
        }
    },
    "features": {
        "capture": true,
        "replay": true,
        "flags": true,
        "assets": true
    },
    "capabilities": {
        "events": {
            "contractVersion": "1.0.0",
            "schemaVersion": 1
        },
        "mutations": {
            "contractVersion": "1.0.0",
            "schemaVersion": 1
        },
        "flags": {
            "contractVersion": "1.0.0",
            "schemaVersion": 1
        },
        "replay": {
            "replayContractVersion": "2.0.0",
            "replaySchemaVersion": 2,
            "replayProtocolGeneration": "replay-v2-generation-1",
            "transports": [
                {
                    "codec": "elu-browser-dom-v1",
                    "compression": "gzip"
                }
            ]
        }
    },
    "session": {
        "idleTimeoutSeconds": 1800,
        "maximumDurationSeconds": 86400
    },
    "limits": {
        "eventBatchCount": 100,
        "eventBatchBytes": 1048576,
        "replayChunkBytes": 5242880,
        "queueBytes": 16777216
    }
}"""
    }
}
