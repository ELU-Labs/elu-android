package dev.elu.analytics.internal.runtime

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.runtime.delivery.BatchDeliveryClock
import dev.elu.analytics.internal.runtime.delivery.BatchDeliveryStop
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPRequest
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPResponse
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPTransport
import dev.elu.analytics.internal.runtime.delivery.BatchJitterSource
import dev.elu.analytics.internal.runtime.delivery.BatchWallInstant
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StandaloneRuntimeInstrumentationTest {
    private val runtimes = mutableListOf<StandaloneRuntime>()
    private val testDirectories = mutableListOf<File>()

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        runtimes.asReversed().forEach { runtime -> runCatching { runtime.close() } }
        RuntimeQueueOwner.clearOwnershipForTesting()
        testDirectories.asReversed().forEach { directory -> directory.deleteRecursively() }
    }

    @Test
    fun sqliteBackedRuntimeCapturesBackgroundsAndDeliversOffTheMainThread() {
        val transport = RecordingTransport()
        val owner =
            AndroidRuntimeQueue.openForTesting(
                databaseFile = databaseFile(),
                limits = RuntimeQueueLimits(10_000, 16_777_216),
                legacyStateLoader = ::freshState,
                trustedSiteKey = SITE_KEY,
                captureClock = FixedCaptureClock(NOW_MS),
            ).await()
        val runtime =
            StandaloneRuntime(
                owner = owner,
                siteKey = SITE_KEY,
                wallClock = { NOW_MS },
                deliveryClock = FixedDeliveryClock(NOW_MS, NOW),
                scheduler = Executors.newSingleThreadScheduledExecutor(),
                jitter = BatchJitterSource { 0.0 },
                transportFactory = { transport },
                deviceInEuTimezone = { false },
            )
        runtimes += runtime

        val activated = runtime.applyConfiguration(captureConfig()).await()
        assertTrue(activated is RuntimeCaptureAuthorityUpdateResult.Activated)

        val sink = runtime.lifecycleSink()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sink.applicationForegrounded(NOW, fromBackground = false)
            sink.screenViewed("MainActivity", NOW)
            runtime.captureException(IllegalStateException("instrumented"), emptyMap(), NOW)
            sink.applicationBackgrounded(NOW)
        }

        awaitCondition { owner.snapshot().await().queuedCount == 0 }
        val snapshot = owner.snapshot().await()
        assertEquals(4L, snapshot.state.stream.nextSequence)
        assertEquals(SessionLifecycle.BACKGROUND, snapshot.state.identity.session?.lifecycle)
        // The foreground trigger may deliver the first event before the rest are queued.
        val records = JSONArray()
        transport.requests.forEach { request ->
            assertEquals("Bearer $SITE_KEY", request.authorizationHeader())
            val batch = JSONObject(request.bodyBytes().decodeToString()).getJSONArray("records")
            for (index in 0 until batch.length()) records.put(batch.getJSONObject(index))
        }
        assertEquals(
            listOf("capture", "screen", "exception", "capture"),
            (0 until records.length()).map { index -> records.getJSONObject(index).getJSONObject("event").getString("kind") },
        )
        assertTrue(transport.threads.none { thread -> thread === Looper.getMainLooper().thread })
        awaitCondition { runtime.flush().get(5, TimeUnit.SECONDS).stop == BatchDeliveryStop.IDLE }
    }

    private fun databaseFile(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "elu-standalone-runtime-tests/${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        testDirectories += directory
        return File(directory, "runtime.sqlite")
    }

    private fun awaitCondition(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition was not satisfied", condition())
    }

    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)

    private fun freshState(): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 0,
                    contextRevision = 0,
                    anonymousId = "anon_standalone_test",
                    userId = null,
                    groups = emptyMap(),
                    superProperties = emptyMap(),
                    session = null,
                    optedOut = false,
                    updatedAt = ISSUED,
                ),
            stream = StreamState(streamId = "stream_standalone_test", nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )

    private fun captureConfig(): String =
        JSONObject()
            .put("schemaVersion", 1)
            .put("revision", "standalone-config-1")
            .put("issuedAt", ISSUED)
            .put("expiresAt", "2026-08-04T00:05:00.000Z")
            .put("status", "enabled")
            .put("site", JSONObject().put("id", "site_standalone"))
            .put(
                "endpoints",
                JSONObject()
                    .put("events", "https://ingest.elu.dev/v1/events")
                    .put("flags", "https://ingest.elu.dev/v1/flags"),
            ).put(
                "privacy",
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("revision", "privacy-standalone-1")
                    .put("capture", JSONObject().put("enabled", true))
                    .put(
                        "replay",
                        JSONObject()
                            .put("enabled", false)
                            .put("sampleRate", 0)
                            .put("minimumDurationSeconds", 0)
                            .put("maximumDurationSeconds", 0),
                    ).put(
                        "masking",
                        JSONObject()
                            .put("text", "sensitive")
                            .put("inputs", "all")
                            .put("images", "block")
                            .put("secureInputsMasked", true),
                    ).put("regionPolicy", JSONObject().put("mode", "block-eu-on-device").put("evaluator", "elu-eu-timezone-v1")),
            ).put(
                "features",
                JSONObject()
                    .put("capture", true)
                    .put("replay", false)
                    .put("flags", false)
                    .put("assets", false),
            ).put(
                "capabilities",
                JSONObject().put(
                    "replay",
                    JSONObject()
                        .put("acceptedCodecs", JSONArray())
                        .put("acceptedCompressions", JSONArray()),
                ),
            ).put(
                "session",
                JSONObject()
                    .put("idleTimeoutSeconds", 1_800)
                    .put("maximumDurationSeconds", 86_400),
            ).put(
                "limits",
                JSONObject()
                    .put("eventBatchCount", 100)
                    .put("eventBatchBytes", 1_048_576)
                    .put("replayChunkBytes", 5_242_880)
                    .put("queueBytes", 16_777_216),
            ).toString()

    private class RecordingTransport : BatchHTTPTransport {
        val requests: MutableList<BatchHTTPRequest> = Collections.synchronizedList(mutableListOf())
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

        override fun execute(request: BatchHTTPRequest): BatchHTTPResponse {
            requests += request
            threads += Thread.currentThread()
            val body = JSONObject(request.bodyBytes().decodeToString())
            val records = body.getJSONArray("records")
            val outcomes = JSONArray()
            var last: Long? = null
            for (index in 0 until records.length()) {
                val record = records.getJSONObject(index)
                val isEvent = record.getString("kind") == "event"
                val payload = record.getJSONObject(if (isEvent) "event" else "mutation")
                last = payload.getLong("sequence")
                outcomes.put(
                    JSONObject()
                        .put("sequence", payload.getLong("sequence"))
                        .put("recordId", payload.getString(if (isEvent) "eventId" else "mutationId"))
                        .put("kind", record.getString("kind"))
                        .put("result", "accepted"),
                )
            }
            val acknowledgement =
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("requestId", body.getString("requestId"))
                    .put("streamId", body.getString("streamId"))
                    .put("resolvedThroughSequence", last ?: JSONObject.NULL)
                    .put("retryFromSequence", JSONObject.NULL)
                    .put("outcomes", outcomes)
            return BatchHTTPResponse(200, acknowledgement.toString().encodeToByteArray())
        }
    }

    private class FixedDeliveryClock(
        private val epochMillis: Long,
        private val rfc3339: String,
    ) : BatchDeliveryClock {
        override fun wallNow(): BatchWallInstant = BatchWallInstant(epochMillis, rfc3339)

        override fun monotonicNowNanos(): Long = 0L
    }

    private class FixedCaptureClock(private val wallEpochMillis: Long) : RuntimeCaptureClock {
        override fun wallNowEpochMillis(): Long = wallEpochMillis

        override fun elapsedRealtimeNanos(): Long = 1_000_000_000L
    }

    private companion object {
        const val SITE_KEY = "elu_pk_test_standalone"
        const val ISSUED = "2026-08-04T00:00:00.000Z"
        const val NOW = "2026-08-04T00:01:00.000Z"
        val NOW_MS: Long = Instant.parse(NOW).toEpochMilli()
    }
}
