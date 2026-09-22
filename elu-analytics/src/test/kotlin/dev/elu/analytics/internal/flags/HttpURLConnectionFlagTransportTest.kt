package dev.elu.analytics.internal.flags

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.ArrayDeque
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpURLConnectionFlagTransportTest {
    @Test
    fun `transport posts immutable producer bytes to its bound endpoint with bearer and identity encoding`() {
        val worker = Worker()
        val response = "{\"schemaVersion\":1}".toByteArray()
        val connection = Connection(200, response)
        val transport = transport(worker, connection)
        val body = resource("contracts/v1/fixtures/flags-request.json")
        val original = body.copyOf()
        val future = transport.send(FlagTransportRequest(ENDPOINT, body)).toFuture()
        body.fill(0)
        assertEquals(1, worker.tasks.size)
        worker.runNext()
        assertArrayEquals(original, connection.output.toByteArray())
        assertArrayEquals(response, future.get(1, TimeUnit.SECONDS))
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer $KEY", connection.getRequestProperty("Authorization"))
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.disconnected)
        assertTrue(connection.streamClosed)
        transport.close()
    }

    @Test
    fun `constructor and request binding reject foreign roles credentials and endpoint substitution`() {
        listOf("http://ingest.elu.dev/v1/flags", "https://evil.test/v1/flags", "https://ingest.elu.dev/v1/events",
            "https://ingest.elu.dev:444/v1/flags", "https://user@ingest.elu.dev/v1/flags", "https://ingest.elu.dev/v1/flags#x",
            "https://ingest.elu.dev/v1/flags?site_key=x", "https://ingest.elu.dev/v1/flags?%73ite_key=x").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { transport(Worker(), Connection(200), URI(url)) }
        }
        listOf("", "provider_token", "$KEY\n", "$KEY/other").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { HttpURLConnectionFlagTransport(key, ENDPOINT) }
        }
        val worker = Worker()
        val connection = Connection(200)
        val transport = transport(worker, connection)
        assertFailure(transport.send(FlagTransportRequest(URI("https://ingest.elu.dev/v1/flags?route=other"), "{}".toByteArray())).toFuture())
        assertTrue(worker.tasks.isEmpty())
        assertEquals(0, connection.bodyReads)
        transport.close()
        transport(Worker(), Connection(200), URI("https://ingest.elu.dev:443/v1/flags?route=eu")).close()
    }

    @Test
    fun `invalid UTF8 empty and oversized requests fail before worker or network admission`() {
        val worker = Worker()
        val transport = transport(worker, Connection(200))
        listOf(ByteArray(0), byteArrayOf(0xc3.toByte(), 0x28), ByteArray(FLAG_MAX_WIRE_BYTES + 1)).forEach { bytes ->
            assertFailure(transport.send(FlagTransportRequest(ENDPOINT, bytes)).toFuture())
        }
        assertTrue(worker.tasks.isEmpty())
        transport.close()
    }

    @Test
    fun `non200 replies including redirects and auth refusal never consume an error body`() {
        listOf(301, 302, 304, 307, 401, 403, 429, 503).forEach { status ->
            val worker = Worker()
            val connection = Connection(status, failBody = true)
            val transport = transport(worker, connection)
            val result = transport.send(request()).toFuture()
            worker.runNext()
            assertFailure(result)
            assertEquals(0, connection.bodyReads)
            assertTrue(connection.disconnected)
            transport.close()
        }
    }

    @Test
    fun `streamed declared and encoded responses are bounded with stream cleanup`() {
        listOf(Connection(200, ByteArray(6)), Connection(200, headers = mapOf("Content-Length" to "6")),
            Connection(200, headers = mapOf("Content-Length" to "-1")),
            Connection(200, headers = mapOf("Content-Length" to "invalid")),
            Connection(200, headers = mapOf("Content-Encoding" to "gzip"))).forEach { connection ->
            val worker = Worker()
            val transport = transport(worker, connection, maximumBytes = 5)
            val result = transport.send(request()).toFuture()
            worker.runNext()
            assertFailure(result)
            assertTrue(connection.disconnected)
            transport.close()
        }
    }

    @Test
    fun `response bytes remain for the existing strict owner codec to validate`() {
        val worker = Worker()
        val invalid = byteArrayOf(0xc3.toByte(), 0x28)
        val transport = transport(worker, Connection(200, invalid))
        val result = transport.send(request()).toFuture()
        worker.runNext()
        assertArrayEquals(invalid, result.get(1, TimeUnit.SECONDS))
        assertThrows(FlagProtocolException::class.java) { FlagCodec.decodeResponse(result.get()) }
        transport.close()
    }

    @Test
    fun `one physical request bounds admission until a canceled queued request settles`() {
        val worker = Worker()
        val connection = Connection(200)
        val transport = transport(worker, connection)
        val first = transport.send(request()).toFuture()
        assertFailure(transport.send(request()).toFuture())
        assertTrue(first.cancel(false))
        assertFailure(transport.send(request()).toFuture())
        assertEquals(1, worker.tasks.size)
        worker.runNext()
        assertEquals(0, connection.output.size())
        val next = transport.send(request()).toFuture()
        worker.runNext()
        next.get(1, TimeUnit.SECONDS)
        transport.close()
    }

    @Test
    fun `completion listener can admit the next request after physical cleanup`() {
        val worker = Worker()
        val transport = transport(worker, Connection(200))
        val first = transport.send(request()).toFuture()
        val next = first.thenCompose { transport.send(request()) }.toFuture()
        worker.runNext()
        assertEquals(1, worker.tasks.size)
        worker.runNext()
        next.get(1, TimeUnit.SECONDS)
        transport.close()
    }

    @Test
    fun `close invalidates queued work and refuses future requests`() {
        val worker = Worker()
        val connection = Connection(200)
        val transport = transport(worker, connection)
        val first = transport.send(request()).toFuture()
        transport.close()
        assertTrue(first.isCancelled)
        worker.runNext()
        assertEquals(0, connection.output.size())
        assertFailure(transport.send(request()).toFuture())
    }

    @Test
    fun `cancellation during read disconnects and cannot publish bytes`() {
        val worker = Worker()
        lateinit var future: SdkFuture<ByteArray>
        val connection = Connection(200, "abc".toByteArray(), onRead = { future.cancel(false) })
        val transport = transport(worker, connection)
        future = transport.send(request()).toFuture()
        worker.runNext()
        assertTrue(future.isCancelled)
        assertTrue(connection.disconnected)
        assertTrue(connection.streamClosed)
        transport.close()
    }

    @Test
    fun `slow drip cannot reset total request deadline`() {
        val worker = Worker()
        var elapsed = 0L
        var canceled = false
        val connection = Connection(200, "abcdef".toByteArray(), onRead = { elapsed += 6_000_000L })
        val transport = HttpURLConnectionFlagTransport(KEY, ENDPOINT, readTimeoutMillis = 10, requestTimeoutMillis = 10,
            elapsedRealtimeNanos = { elapsed }, scheduleDeadline = { _, _ -> AutoCloseable { canceled = true } },
            executor = worker, connectionFactory = { connection })
        val future = transport.send(request()).toFuture()
        worker.runNext()
        assertTrue(assertFailure(future) is SocketTimeoutException)
        assertEquals(4, connection.readTimeout)
        assertTrue(connection.disconnected)
        assertTrue(canceled)
        transport.close()
    }

    @Test
    fun `independent deadline aborts without another clock tick`() {
        val worker = Worker()
        var expire: (() -> Unit)? = null
        val connection = Connection(200, "abc".toByteArray(), onRead = { checkNotNull(expire).invoke() })
        val transport = HttpURLConnectionFlagTransport(KEY, ENDPOINT, elapsedRealtimeNanos = { 1L },
            scheduleDeadline = { _, action -> expire = action; AutoCloseable {} }, executor = worker, connectionFactory = { connection })
        val future = transport.send(request()).toFuture()
        worker.runNext()
        assertTrue(assertFailure(future) is SocketTimeoutException)
        assertTrue(connection.disconnected)
        transport.close()
    }

    @Test
    fun `executor refusal releases admission for a later accepted call`() {
        val worker = Worker()
        val transport = transport(worker, Connection(200))
        worker.rejected = true
        assertFailure(transport.send(request()).toFuture())
        worker.rejected = false
        val next = transport.send(request()).toFuture()
        worker.runNext()
        next.get(1, TimeUnit.SECONDS)
        transport.close()
    }

    private fun transport(worker: Worker, connection: Connection, endpoint: URI = ENDPOINT, maximumBytes: Int = FLAG_MAX_WIRE_BYTES) =
        HttpURLConnectionFlagTransport(KEY, endpoint, maximumResponseBytes = maximumBytes, elapsedRealtimeNanos = System::nanoTime,
            executor = worker, connectionFactory = { connection })
    private fun request() = FlagTransportRequest(ENDPOINT, "{}".toByteArray())
    private fun assertFailure(result: SdkFuture<ByteArray>): Throwable =
        checkNotNull(assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }.cause)
    private fun resource(path: String): ByteArray = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)).use { it.readBytes() }

    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        var rejected = false
        override fun execute(command: Runnable) { check(!rejected); tasks.add(command) }
        fun runNext() { tasks.removeFirst().run() }
    }

    private class Connection(
        private val status: Int,
        private val bytes: ByteArray = ByteArray(0),
        private val headers: Map<String, String> = emptyMap(),
        private val failBody: Boolean = false,
        private val onRead: (() -> Unit)? = null,
    ) : HttpURLConnection(URL("https://ingest.elu.dev/v1/flags")) {
        val output = ByteArrayOutputStream()
        var disconnected = false
        var streamClosed = false
        var bodyReads = 0
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun getOutputStream(): ByteArrayOutputStream = output
        override fun getInputStream(): InputStream {
            bodyReads++
            if (failBody) throw IOException("unreadable body")
            return object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead?.invoke()
                    return super.read(buffer, offset, if (onRead == null) length else minOf(1, length))
                }
                override fun close() { streamClosed = true; super.close() }
            }
        }
        override fun getErrorStream(): InputStream = error("Error body must not be read")
    }
    private companion object {
        val KEY = "elu_pk_live_${"A".repeat(26)}"
        val ENDPOINT = URI("https://ingest.elu.dev/v1/flags")
    }
}
