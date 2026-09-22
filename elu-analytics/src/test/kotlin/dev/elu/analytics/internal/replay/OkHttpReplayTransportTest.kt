package dev.elu.analytics.internal.replay

import java.io.IOException
import java.net.URI
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class OkHttpReplayTransportTest {
    @Test fun `posts original bytes using isolated client and one shot authority before execute`() {
        val worker = Worker(); val order = mutableListOf<String>(); val calls = mutableListOf<FakeCall>()
        val claim = claim(); val original = claim.row.prepared.copyBytes()
        val adapter = adapter(worker) { client, request ->
            order += "factory"
            assertSame(CookieJar.NO_COOKIES, client.cookieJar)
            assertSame(Authenticator.NONE, client.authenticator)
            assertSame(Authenticator.NONE, client.proxyAuthenticator)
            assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
            assertNull(client.cache); assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
            assertEquals(20_000, client.callTimeoutMillis); assertEquals(10_000, client.readTimeoutMillis)
            assertTrue(client.interceptors.isEmpty()); assertTrue(client.networkInterceptors.isEmpty())
            assertEquals("Bearer $KEY", request.header("Authorization")); assertNull(request.header("Cookie"))
            assertNull(request.header("Proxy-Authorization")); assertEquals("identity", request.header("Accept-Encoding"))
            assertEquals("POST", request.method); assertEquals(ENDPOINT.toString(), request.url.toString())
            assertEquals("application/json", request.body?.contentType().toString()); assertTrue(checkNotNull(request.body).isOneShot())
            FakeCall(client, request, onExecute = { order += "execute" }).also { calls += it }
        }
        val operation = adapter.start(claim) { order += "authorize"; true }
        assertTrue(order.isEmpty()); claim.row.prepared.copyBytes().fill(0)
        worker.runNext(); operation.settlement.get(1, TimeUnit.SECONDS)
        assertEquals(listOf("factory", "authorize", "execute"), order)
        assertArrayEquals(original, calls.single().written.readByteArray())
        assertTrue(calls.single().body.closed); assertTrue(adapter.isIdle()); adapter.close()
    }

    @Test fun `constructor and immutable claim binding reject foreign endpoint key and reserved query`() {
        for (endpoint in listOf("http://ingest.elu.dev/v2/replay", "https://evil.test/v2/replay", "https://ingest.elu.dev/v1/replay",
            "https://ingest.elu.dev/v1/flags", "https://user@ingest.elu.dev/v2/replay", "https://ingest.elu.dev:444/v2/replay",
            "https://ingest.elu.dev/v2/replay#x", "https://ingest.elu.dev/v2/replay?%73ite_key=x")) {
            assertThrows(IllegalArgumentException::class.java) { OkHttpReplayTransport(KEY, URI(endpoint)) }
        }
        for (key in listOf("", "provider_token", "$KEY\n")) assertThrows(IllegalArgumentException::class.java) { OkHttpReplayTransport(key, ENDPOINT) }
        val worker = Worker(); val adapter = adapter(worker)
        assertThrows(IllegalArgumentException::class.java) { adapter.start(claim(key = "elu_pk_test_${"B".repeat(26)}")) { true } }
        assertThrows(IllegalArgumentException::class.java) { adapter.start(claim(endpoint = URI("https://ingest.elu.dev/v2/replay?other=1"))) { true } }
        assertTrue(worker.tasks.isEmpty()); adapter.close()
        val routed = URI("https://ingest.elu.dev:443/v2/replay?route=eu")
        OkHttpReplayTransport(KEY, routed).close()
    }

    @Test fun `false or throwing authority never executes a call`() {
        for (throws in listOf(false, true)) {
            val worker = Worker(); lateinit var call: FakeCall
            val adapter = adapter(worker) { client, request -> FakeCall(client, request).also { call = it } }
            val operation = adapter.start(claim()) { if (throws) throw IOException("withdrawn") else false }
            worker.runNext(); failure(operation)
            assertFalse(call.executed); assertTrue(call.canceled); adapter.close()
        }
    }

    @Test fun `cancellation during final authority prevents I O without consuming guard twice`() {
        val worker = Worker(); lateinit var operation: ReplayTransportOperation; lateinit var call: FakeCall
        val adapter = adapter(worker) { client, request -> FakeCall(client, request).also { call = it } }
        var checks = 0
        operation = adapter.start(claim()) { checks++; operation.cancel(); true }
        worker.runNext(); failure(operation)
        assertEquals(1, checks); assertFalse(call.executed); adapter.close()
    }

    @Test fun `queued cancel retains slot until worker cleanup and settlement cannot be canceled`() {
        val worker = Worker(); var factories = 0
        val adapter = adapter(worker) { client, request -> factories++; FakeCall(client, request) }
        val operation = adapter.start(claim()) { fail("canceled guard"); true }
        assertFalse(operation.settlement.cancel(false)); operation.cancel()
        assertFalse(operation.settlement.isDone); assertFalse(adapter.isIdle())
        assertThrows(IllegalStateException::class.java) { adapter.start(claim()) { true } }
        worker.runNext(); failure(operation); assertEquals(0, factories); assertTrue(adapter.isIdle())
        val next = adapter.start(claim()) { true }; worker.runNext(); next.settlement.get(1, TimeUnit.SECONDS); adapter.close()
    }

    @Test fun `close keeps queued cleanup runnable and refuses new enrollment`() {
        val worker = Worker(); val adapter = adapter(worker)
        val operation = adapter.start(claim()) { fail("closed guard"); true }; adapter.close()
        assertFalse(operation.settlement.isDone); worker.runNext(); failure(operation)
        assertTrue(adapter.isIdle()); assertThrows(IllegalStateException::class.java) { adapter.start(claim()) { true } }
    }

    @Test fun `401 and403 ignore oversized malformed encoded bodies and preserve status after cancel`() {
        for (status in listOf(401, 403)) {
            val worker = Worker(); val body = Body(ByteArray(70_000), onRead = { fail("refusal body read") })
            lateinit var operation: ReplayTransportOperation
            val adapter = adapter(worker) { client, request -> FakeCall(client, request, status, body,
                mapOf("Content-Length" to "invalid", "Content-Encoding" to "gzip"), onHeaders = { operation.cancel() }) }
            operation = adapter.start(claim()) { true }; worker.runNext()
            val result = operation.settlement.get(1, TimeUnit.SECONDS)
            assertEquals(status, result.status); assertTrue(result.copyBody().isEmpty()); assertTrue(body.closed); adapter.close()
        }
    }

    @Test fun `observable refusal headers survive execute throwing before it returns a response`() {
        for (status in listOf(401, 403)) {
            val worker = Worker()
            val adapter = adapter(worker) { client, request -> FakeCall(client, request, status, throwAfterHeaders = true) }
            val operation = adapter.start(claim()) { true }; worker.runNext()
            assertEquals(status, operation.settlement.get(1, TimeUnit.SECONDS).status); adapter.close()
        }
    }

    @Test fun `declared and streamed response bounds return original status for strict protocol rejection`() {
        for ((headers, bytes) in listOf(mapOf("Content-Length" to "6") to byteArrayOf(), mapOf("Content-Length" to "-1") to byteArrayOf(),
            mapOf("Content-Encoding" to "gzip") to byteArrayOf(), emptyMap<String,String>() to ByteArray(6))) {
            val worker = Worker()
            val adapter = OkHttpReplayTransport(KEY, ENDPOINT, maximumResponseBytes = 5, elapsedRealtimeNanos = { 0L },
                scheduleDeadline = { _, _ -> AutoCloseable {} }, executor = worker,
                callFactory = { client, request -> FakeCall(client, request, body = Body(bytes), headers = headers) })
            val operation = adapter.start(claim()) { true }; worker.runNext()
            val response = operation.settlement.get(1, TimeUnit.SECONDS)
            assertEquals(200, response.status); assertTrue(response.copyBody().isEmpty()); adapter.close()
        }
    }

    @Test fun `valid operational response preserves exact bytes and bounded retry header without retrying`() {
        val worker = Worker(); val bytes = "{\"status\":503}".toByteArray(); var executes = 0
        val adapter = adapter(worker) { client, request -> FakeCall(client, request, 503, Body(bytes), mapOf("Retry-After" to "12"), { executes++ }) }
        val operation = adapter.start(claim()) { true }; worker.runNext(); val response = operation.settlement.get(1, TimeUnit.SECONDS)
        assertEquals(503, response.status); assertEquals("12", response.retryAfter); assertArrayEquals(bytes, response.copyBody()); assertEquals(1, executes); adapter.close()
    }

    @Test fun `slow drip consumes overall monotonic deadline`() {
        val worker = Worker(); var now = 0L; lateinit var call: FakeCall
        val adapter = OkHttpReplayTransport(KEY, ENDPOINT, requestTimeoutMillis = 10, elapsedRealtimeNanos = { now },
            scheduleDeadline = { _, _ -> AutoCloseable {} }, executor = worker, callFactory = { client, request ->
                FakeCall(client, request, body = Body(ByteArray(4), onRead = { now += 6_000_000 })).also { call = it }
            })
        val operation = adapter.start(claim()) { true }; worker.runNext()
        assertTrue(failure(operation) is java.net.SocketTimeoutException); assertTrue(call.canceled); assertTrue(call.body.closed); adapter.close()
    }

    @Test fun `independent deadline cancellation retains refusal fact`() {
        for (status in listOf(200, 401)) {
            val worker = Worker(); lateinit var expire: () -> Unit
            val adapter = OkHttpReplayTransport(KEY, ENDPOINT, elapsedRealtimeNanos = { 0L },
                scheduleDeadline = { _, action -> expire = action; AutoCloseable {} }, executor = worker,
                callFactory = { client, request -> FakeCall(client, request, status, onHeaders = { expire() }) })
            val operation = adapter.start(claim()) { true }; worker.runNext()
            if (status == 401) assertEquals(401, operation.settlement.get(1, TimeUnit.SECONDS).status)
            else assertTrue(failure(operation) is java.net.SocketTimeoutException)
            adapter.close()
        }
    }

    @Test fun `cancel and close never settle while physical response cleanup is held`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val body = Body(byteArrayOf(), onClose = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) })
        val adapter = adapter(Executors.newSingleThreadExecutor()) { client, request -> FakeCall(client, request, 403, body) }
        val operation = adapter.start(claim()) { true }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); operation.cancel(); adapter.close()
            assertFalse(operation.settlement.isDone); assertFalse(adapter.isIdle())
        } finally { release.countDown() }
        assertEquals(403, operation.settlement.get(3, TimeUnit.SECONDS).status); assertTrue(adapter.isIdle())
    }

    @Test fun `cleanup fault attempts later disposers but quarantines physical occupancy`() {
        val worker = Worker(); val body = Body(byteArrayOf()); lateinit var call: FakeCall
        val adapter = OkHttpReplayTransport(KEY, ENDPOINT, elapsedRealtimeNanos = { 0L },
            scheduleDeadline = { _, _ -> AutoCloseable { throw IOException("timer cleanup") } }, executor = worker,
            callFactory = { client, request -> FakeCall(client, request, 401, body).also { call = it } })
        val operation = adapter.start(claim()) { true }; worker.runNext()
        assertTrue(body.closed); assertTrue(call.canceled); assertFalse(operation.settlement.isDone); assertFalse(adapter.isIdle())
        assertThrows(IllegalStateException::class.java) { adapter.start(claim()) { true } }; adapter.close()
    }

    @Test fun `response source close error is observable and cannot release the physical slot`() {
        val worker = Worker(); var timerClosed = false; lateinit var call: FakeCall
        val body = Body(byteArrayOf(), onClose = { throw IOException("source close failed") })
        val adapter = OkHttpReplayTransport(KEY, ENDPOINT, elapsedRealtimeNanos = { 0L },
            scheduleDeadline = { _, _ -> AutoCloseable { timerClosed = true } }, executor = worker,
            callFactory = { client, request -> FakeCall(client, request, 403, body).also { call = it } })
        val operation = adapter.start(claim()) { true }; worker.runNext()
        assertTrue(timerClosed); assertTrue(call.canceled)
        assertFalse(operation.settlement.isDone); assertFalse(adapter.isIdle()); adapter.close()
    }

    @Test fun `ordinary response canceled during read never publishes a stale body`() {
        val worker = Worker(); lateinit var operation: ReplayTransportOperation
        val body = Body(byteArrayOf(1, 2), onRead = { operation.cancel() })
        val adapter = adapter(worker) { client, request -> FakeCall(client, request, body = body) }
        operation = adapter.start(claim()) { true }; worker.runNext()
        failure(operation); assertTrue(body.closed); assertTrue(adapter.isIdle()); adapter.close()
    }

    @Test fun `executor refusal has no physical work and permits a later enrollment`() {
        val worker = Worker(); worker.reject = true; val adapter = adapter(worker)
        failure(adapter.start(claim()) { true }); assertTrue(adapter.isIdle())
        worker.reject = false; val operation = adapter.start(claim()) { true }; worker.runNext()
        operation.settlement.get(1, TimeUnit.SECONDS); adapter.close()
    }

    @Test fun `completion callback can enroll next request only after cleanup`() {
        val worker = Worker(); val adapter = adapter(worker)
        val first = adapter.start(claim()) { true }
        val next = first.settlement.thenCompose { adapter.start(claim()) { true }.settlement }
        worker.runNext(); assertEquals(1, worker.tasks.size); worker.runNext(); next.get(1, TimeUnit.SECONDS); adapter.close()
    }

    private fun adapter(worker: Executor, factory: (OkHttpClient, Request) -> Call = { client, request -> FakeCall(client, request) }) =
        OkHttpReplayTransport(KEY, ENDPOINT, elapsedRealtimeNanos = { 0L }, scheduleDeadline = { _, _ -> AutoCloseable {} }, executor = worker, callFactory = factory)
    private fun claim(key: String = KEY, endpoint: URI = ENDPOINT): ReplayDeliveryClaim {
        val prepared = PreparedReplayRequest.parse(ReplayFixtures.bytes(), ReplayFixtures.GENERATION)
        return ReplayDeliveryClaim(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            ReplayStoredChunk(0, "site", ReplayFixtures.GENERATION, prepared, ReplayFixtures.profile()),
            ReplayDeliveryAuthorization(endpoint, key, "site", prepared.transport, ReplayFixtures.GENERATION, "credential", "scope"), 1)
    }
    private fun failure(operation: ReplayTransportOperation): Throwable =
        checkNotNull(assertThrows(ExecutionException::class.java) { operation.settlement.get(1, TimeUnit.SECONDS) }.cause)
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>(); var reject = false
        override fun execute(command: Runnable) { check(!reject); tasks.add(command) }
        fun runNext() = tasks.removeFirst().run()
    }
    private class Body(bytes: ByteArray, private val onRead: (() -> Unit)? = null, private val onClose: (() -> Unit)? = null) : ResponseBody() {
        val data = Buffer().write(bytes); var closed = false
        private val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long { onRead?.invoke(); return data.read(sink, if (onRead == null) byteCount else minOf(1L, byteCount)) }
            override fun timeout() = Timeout.NONE
            override fun close() { onClose?.invoke(); closed = true }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = input
    }
    private class FakeCall(
        private val client: OkHttpClient, private val wire: Request, private val status: Int = 200,
        val body: Body = Body("{}".toByteArray()), private val headers: Map<String,String> = emptyMap(),
        private val onExecute: (() -> Unit)? = null, private val onHeaders: (() -> Unit)? = null,
        private val throwAfterHeaders: Boolean = false,
    ) : Call {
        val written = Buffer(); var executed = false; @Volatile var canceled = false
        override fun request() = wire
        override fun execute(): Response {
            executed = true; onExecute?.invoke(); checkNotNull(wire.body).writeTo(written)
            val response = Response.Builder().request(wire).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body)
                .apply { headers.forEach { (name,value) -> header(name,value) } }.build()
            client.eventListenerFactory.create(this).responseHeadersEnd(this, response); onHeaders?.invoke()
            if (throwAfterHeaders) { body.close(); throw IOException("response discarded after headers") }
            return response
        }
        override fun enqueue(responseCallback: Callback) = error("No asynchronous dispatcher")
        override fun cancel() { canceled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = error("No retries")
    }
    companion object {
        private val KEY = "elu_pk_live_${"A".repeat(26)}"
        private val ENDPOINT = URI("https://ingest.elu.dev/v2/replay")
    }
}
