package dev.elu.analytics.internal.runtime

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
import dev.elu.analytics.internal.runtime.delivery.BatchRetryScheduler
import dev.elu.analytics.internal.runtime.delivery.BatchScheduledTask
import dev.elu.analytics.internal.runtime.delivery.BatchWallInstant
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StandaloneRuntimeTest {
    private val runtimes = mutableListOf<StandaloneRuntime>()
    private val schedulers = mutableListOf<ScheduledExecutorService>()
    private val keyCounter = AtomicInteger()

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        runtimes.asReversed().forEach { runtime -> runCatching { runtime.close() } }
        schedulers.forEach { scheduler -> scheduler.shutdownNow() }
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test
    fun `enabled config activates capture and a flush posts one authorized batch that drains`() {
        val transport = ScriptedTransport()
        val scheduler = FakeScheduler()
        val harness = runtime(transport, scheduler)

        val activated = harness.runtime.applyConfiguration(config()).await()
        assertTrue(activated is RuntimeCaptureAuthorityUpdateResult.Activated)
        assertTrue(harness.runtime.hasDeliveryAuthorization())

        val accepted = harness.runtime.capture("checkout", mapOf("amount" to 42)).await() as RuntimeCaptureResult.Accepted
        assertEquals(RuntimeEventKind.CAPTURE, accepted.record.record.kind)
        assertEquals(NOW, accepted.record.record.occurredAt)
        assertEquals(42, accepted.record.record.properties["amount"])
        assertEquals("elu-android", accepted.record.record.versions.runtime.name)
        assertEquals(1, scheduler.entries.size)
        assertEquals(StandaloneRuntime.DEFAULT_FLUSH_DELAY_MILLIS, scheduler.entries.single().delayMillis)

        val pass = harness.runtime.flush().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.DRAINED, pass.stop)
        assertEquals(1, pass.networkRequests)
        assertEquals(1, pass.resolvedRecords)
        val request = transport.requests.single()
        assertEquals("https://ingest.elu.dev/v1/events", request.endpoint.toString())
        assertEquals("Bearer $SITE_KEY", request.authorizationHeader())
        val body = JSONObject(request.bodyBytes().decodeToString())
        assertEquals("stream_runtime", body.getString("streamId"))
        assertEquals("checkout", body.getJSONArray("records").getJSONObject(0).getJSONObject("event").getString("name"))
        assertEquals(0, harness.owner.snapshot().await().queuedCount)

        // The armed flush timer fires one more pass against the now-empty queue without a request.
        scheduler.runNext()
        awaitCondition { harness.runtime.flush().get(5, TimeUnit.SECONDS).stop == BatchDeliveryStop.IDLE }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `disabled malformed and EU-blocked configs terminate capture and send nothing`() {
        val transport = ScriptedTransport()
        val harness = runtime(transport, FakeScheduler())

        val disabled = harness.runtime.applyConfiguration(config("config-disabled.json")).await()
            as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.DISABLED, disabled.authority.reason)
        assertFalse(harness.runtime.hasDeliveryAuthorization())
        val rejected = harness.runtime.capture("blocked").await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_TERMINAL, rejected.reason)
        assertEquals(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE, harness.runtime.flush().get(5, TimeUnit.SECONDS).stop)

        val malformed = harness.runtime.applyConfiguration("{").await() as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.MALFORMED, malformed.authority.reason)
        val missing = harness.runtime.applyConfiguration(null).await() as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.MALFORMED, missing.authority.reason)

        val euHarness = runtime(transport, FakeScheduler(), deviceInEu = true)
        val blocked = euHarness.runtime.applyConfiguration(config()).await() as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED, blocked.authority.reason)
        assertFalse(euHarness.runtime.hasDeliveryAuthorization())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a newer config replaces the delivery authorization and a terminal update retires it`() {
        val transport = ScriptedTransport()
        val harness = runtime(transport, FakeScheduler())
        harness.runtime.applyConfiguration(config()).await()

        val newer =
            JSONObject(config()).apply {
                put("revision", "config-2026-08-04-2")
                put("issuedAt", "2026-08-04T00:00:30.000Z")
                put("expiresAt", "2026-08-04T00:05:30.000Z")
                getJSONObject("limits").put("eventBatchCount", 1)
            }.toString()
        assertTrue(harness.runtime.applyConfiguration(newer).await() is RuntimeCaptureAuthorityUpdateResult.Activated)
        harness.runtime.capture("one").await() as RuntimeCaptureResult.Accepted
        harness.runtime.capture("two").await() as RuntimeCaptureResult.Accepted
        val pass = harness.runtime.flush().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.DRAINED, pass.stop)
        assertEquals(2, pass.networkRequests)

        val revoked =
            JSONObject(config("config-disabled.json")).apply {
                put("revision", "config-revoked")
                put("issuedAt", "2026-08-04T00:00:45.000Z")
                put("expiresAt", "2026-08-04T00:05:45.000Z")
                put("status", "revoked")
            }.toString()
        val terminated = harness.runtime.applyConfiguration(revoked).await() as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.REVOKED, terminated.authority.reason)
        assertFalse(harness.runtime.hasDeliveryAuthorization())
    }

    @Test
    fun `exception and screen captures carry their kinds and derived properties`() {
        val harness = runtime(ScriptedTransport(), FakeScheduler())
        harness.runtime.applyConfiguration(config()).await()

        val exception =
            harness.runtime.captureException(IllegalStateException("boom"), mapOf("screen" to "Checkout")).await()
                as RuntimeCaptureResult.Accepted
        assertEquals(RuntimeEventKind.EXCEPTION, exception.record.record.kind)
        assertEquals(ExceptionSerializer.EVENT_NAME, exception.record.record.name)
        assertEquals("IllegalStateException", exception.record.record.properties[ExceptionSerializer.TYPE_PROPERTY])
        assertEquals("boom", exception.record.record.properties[ExceptionSerializer.MESSAGE_PROPERTY])
        assertEquals("Checkout", exception.record.record.properties["screen"])
        assertTrue(exception.record.record.properties[ExceptionSerializer.LIST_PROPERTY] is List<*>)

        val screen = harness.runtime.screen("Checkout", mapOf("tab" to "cart")).await() as RuntimeCaptureResult.Accepted
        assertEquals(RuntimeEventKind.SCREEN, screen.record.record.kind)
        assertEquals("Checkout", screen.record.record.name)
        assertEquals("Checkout", screen.record.record.properties[StandaloneRuntime.SCREEN_NAME_PROPERTY])
        assertEquals("cart", screen.record.record.properties["tab"])
        assertEquals(exception.record.record.sessionId, screen.record.record.sessionId)
    }

    @Test
    fun `lifecycle sink emits ordered application events backgrounds the session and schedules delivery`() {
        val transport = ScriptedTransport()
        val harness = runtime(transport, FakeScheduler())
        harness.runtime.applyConfiguration(config()).await()
        val sink = harness.runtime.lifecycleSink()

        sink.applicationForegrounded(NOW, fromBackground = false)
        sink.screenViewed("HomeActivity", NOW)
        sink.applicationBackgrounded(NOW)

        awaitCondition { harness.owner.snapshot().await().queuedCount == 0 }
        // The foreground trigger may deliver the first event before the rest are queued.
        val records = JSONArray()
        transport.requests.forEach { request ->
            val batch = JSONObject(request.bodyBytes().decodeToString()).getJSONArray("records")
            for (index in 0 until batch.length()) records.put(batch.getJSONObject(index))
        }
        assertEquals(3, records.length())
        assertEquals(StandaloneRuntime.APPLICATION_OPENED_EVENT, records.getJSONObject(0).getJSONObject("event").getString("name"))
        assertEquals(false, records.getJSONObject(0).getJSONObject("event").getJSONObject("properties").getBoolean("from_background"))
        assertEquals("screen", records.getJSONObject(1).getJSONObject("event").getString("kind"))
        assertEquals("HomeActivity", records.getJSONObject(1).getJSONObject("event").getJSONObject("properties").getString("\$screen_name"))
        assertEquals(StandaloneRuntime.APPLICATION_BACKGROUNDED_EVENT, records.getJSONObject(2).getJSONObject("event").getString("name"))
        val session = harness.owner.snapshot().await().state.identity.session
        assertEquals(SessionLifecycle.BACKGROUND, session?.lifecycle)
        assertEquals(NOW, session?.backgroundedAt)
        assertEquals(1, records.toSessionIds().distinct().size)
    }

    @Test
    fun `retryable failures schedule a jittered monotonic backoff and the retry drains`() {
        val transport = ScriptedTransport()
        val scheduler = FakeScheduler()
        val harness = runtime(transport, scheduler)
        harness.runtime.applyConfiguration(config()).await()
        harness.runtime.capture("retry-me").await() as RuntimeCaptureResult.Accepted
        scheduler.entries.clear()
        transport.nextResponses += { request -> transportError(503, request) }

        val first = harness.runtime.flush().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, first.stop)
        assertEquals(1, harness.owner.snapshot().await().queuedCount)
        assertEquals(listOf(500L), scheduler.entries.map { it.delayMillis })

        harness.clock.monotonicNanos = 500_000_000L
        scheduler.runNext()
        awaitCondition { harness.owner.snapshot().await().queuedCount == 0 }
        assertEquals(2, transport.requests.size)
        assertEquals(
            JSONObject(transport.requests[0].bodyBytes().decodeToString()).getString("requestId"),
            JSONObject(transport.requests[1].bodyBytes().decodeToString()).getString("requestId"),
        )
    }

    @Test
    fun `background marks the session then triggers one bounded pass and close is idempotent`() {
        val transport = ScriptedTransport()
        val harness = runtime(transport, FakeScheduler())
        harness.runtime.applyConfiguration(config()).await()
        harness.runtime.capture("before-background").await() as RuntimeCaptureResult.Accepted

        val backgrounded = harness.runtime.markBackgrounded(LATER).await() as RuntimeAppendResult.Accepted
        assertEquals(SessionLifecycle.BACKGROUND, backgrounded.snapshot.state.identity.session?.lifecycle)
        awaitCondition { harness.owner.snapshot().await().queuedCount == 0 }
        assertTrue(harness.runtime.markForegrounded())

        harness.runtime.close()
        harness.runtime.close()
        assertFalse(harness.runtime.hasDeliveryAuthorization())
        assertThrows(IllegalStateException::class.java) { harness.runtime.applyConfiguration(config()) }
        runtimes.remove(harness.runtime)
    }

    private fun runtime(
        transport: BatchHTTPTransport,
        scheduler: BatchRetryScheduler,
        deviceInEu: Boolean = false,
    ): Harness {
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = "standalone-runtime-${keyCounter.incrementAndGet()}",
                limits = RuntimeQueueLimits(10_000, 16_777_216),
                databaseFactory = FakeRuntimeQueueBacking()::connection,
                legacyStateLoader = ::state,
                trustedSiteKey = SITE_KEY,
                captureClock = FixedCaptureClock(NOW_MS),
            ).await()
        val executor = Executors.newSingleThreadScheduledExecutor()
        schedulers += executor
        val clock = FixedDeliveryClock(NOW_MS, NOW)
        val runtime =
            StandaloneRuntime(
                owner = owner,
                siteKey = SITE_KEY,
                wallClock = { NOW_MS },
                deliveryClock = clock,
                scheduler = executor,
                retryScheduler = scheduler,
                jitter = BatchJitterSource { 0.0 },
                transportFactory = { transport },
                deviceInEuTimezone = { deviceInEu },
            )
        runtimes += runtime
        return Harness(owner, runtime, clock)
    }

    private fun state(): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 1,
                    contextRevision = 3,
                    anonymousId = "anon_runtime",
                    userId = "user_runtime",
                    groups = mapOf("organization" to "org_runtime"),
                    superProperties = mapOf("plan" to "free"),
                    session = null,
                    optedOut = false,
                    updatedAt = ISSUED,
                ),
            stream = StreamState(streamId = "stream_runtime", nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )

    private fun config(name: String = "config-enabled.json"): String =
        checkNotNull(javaClass.classLoader?.getResource("contracts/v1/fixtures/$name")).readText()

    private fun awaitCondition(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition was not satisfied", condition())
    }

    private fun JSONArray.toSessionIds(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getJSONObject("event").getString("sessionId") }

    private fun <T> Future<T>.await(): T =
        try {
            get(10, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }

    private class Harness(
        val owner: RuntimeQueueOwner,
        val runtime: StandaloneRuntime,
        val clock: FixedDeliveryClock,
    )

    /** Accepts every batch unless a scripted response is queued for the next request. */
    private class ScriptedTransport : BatchHTTPTransport {
        val requests = mutableListOf<BatchHTTPRequest>()
        val nextResponses = ArrayDeque<(BatchHTTPRequest) -> BatchHTTPResponse>()

        @Synchronized
        override fun execute(request: BatchHTTPRequest): BatchHTTPResponse {
            requests += request
            val scripted = nextResponses.removeFirstOrNull()
            return scripted?.invoke(request) ?: acceptAll(request)
        }
    }

    private class FakeScheduler : BatchRetryScheduler {
        data class Entry(
            val delayMillis: Long,
            val task: Runnable,
            var cancelled: Boolean = false,
        )

        val entries = mutableListOf<Entry>()

        @Synchronized
        override fun schedule(
            delayMillis: Long,
            task: Runnable,
        ): BatchScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return BatchScheduledTask { entry.cancelled = true }
        }

        fun runNext() {
            val entry = synchronized(this) { entries.removeAt(0) }
            if (!entry.cancelled) entry.task.run()
        }
    }

    private class FixedCaptureClock(private val wallEpochMillis: Long) : RuntimeCaptureClock {
        override fun wallNowEpochMillis(): Long = wallEpochMillis

        override fun elapsedRealtimeNanos(): Long = 1_000L
    }

    private class FixedDeliveryClock(
        private val epochMillis: Long,
        private val rfc3339: String,
    ) : BatchDeliveryClock {
        @Volatile var monotonicNanos: Long = 0L

        override fun wallNow(): BatchWallInstant = BatchWallInstant(epochMillis, rfc3339)

        override fun monotonicNowNanos(): Long = monotonicNanos
    }

    private companion object {
        const val SITE_KEY = "elu_pk_test_runtime"
        const val ISSUED = "2026-08-04T00:00:00.000Z"
        const val NOW = "2026-08-04T00:01:00.000Z"
        const val LATER = "2026-08-04T00:02:00.000Z"
        val NOW_MS: Long = Instant.parse(NOW).toEpochMilli()

        fun acceptAll(request: BatchHTTPRequest): BatchHTTPResponse {
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

        fun transportError(
            status: Int,
            request: BatchHTTPRequest,
        ): BatchHTTPResponse {
            val requestId = JSONObject(request.bodyBytes().decodeToString()).getString("requestId")
            val body =
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("status", status)
                    .put("code", "request-failed")
                    .put("disposition", "retryable")
                    .put("message", "Request could not be processed.")
                    .put("requestId", requestId)
            return BatchHTTPResponse(status, body.toString().encodeToByteArray())
        }
    }
}
