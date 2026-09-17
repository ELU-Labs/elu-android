package dev.elu.analytics.internal.config

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConfigTransportTest {
    @Test
    fun `debug loopback eligibility reaches both endpoint validation and original config transport`() {
        listOf("http://127.0.0.1:8787", "http://localhost:8787", "http://10.0.2.2:8787", "https://[::1]:8787").forEach { host ->
            val endpoint = V2ConfigEndpoint.build(host, KEY, debuggable = true)
            assertEquals("$host/sdk/v2/$KEY/config", endpoint.toString())
            V2ConfigEndpoint.requireApproved(endpoint, debuggable = true)
            val connection = FakeConnection(200, "{}".toByteArray())
            var opened = false
            val transport = HttpURLConnectionV2ConfigTransport(
                elapsedRealtimeNanos = System::nanoTime,
                connectionFactory = { actual -> assertEquals(endpoint, actual); opened = true; connection },
                debuggable = true,
            )
            assertEquals("{}", transport.fetch(endpoint).body)
            assertTrue(opened)
            assertTrue(connection.disconnected)
            assertFalse(connection.instanceFollowRedirects)
        }
    }

    @Test
    fun `release default still refuses loopback and debug never grants a foreign or malformed origin`() {
        val loopback = URI("http://127.0.0.1:8787/sdk/v2/$KEY/config")
        var opened = false
        val transport = HttpURLConnectionV2ConfigTransport(
            elapsedRealtimeNanos = System::nanoTime,
            connectionFactory = { opened = true; FakeConnection(200) },
        )
        assertThrows(IllegalArgumentException::class.java) { transport.fetch(loopback) }
        assertThrows(IllegalArgumentException::class.java) { V2ConfigEndpoint.build("http://127.0.0.1:8787", KEY) }
        listOf("http://elu.dev", "https://evil.test", "http://localhost.evil.test:8787", "http://user@localhost:8787", "http://localhost:8787/path")
            .forEach { host -> assertThrows(host, IllegalArgumentException::class.java) { V2ConfigEndpoint.build(host, KEY, debuggable = true) } }
        val debugTransport = HttpURLConnectionV2ConfigTransport(
            elapsedRealtimeNanos = System::nanoTime,
            connectionFactory = { opened = true; FakeConnection(200) },
            debuggable = true,
        )
        listOf("http://localhost:8787/sdk/v2/$KEY/config?site_key=other", "http://localhost:8787/sdk/v2/invalid/config", "http://localhost:8787/sdk/v1/$KEY/config")
            .forEach { endpoint -> assertThrows(endpoint, IllegalArgumentException::class.java) { debugTransport.fetch(URI(endpoint)) } }
        assertFalse(opened)
    }

    @Test
    fun `only canonical ELU credentials and HTTPS config origins can enter the transport`() {
        assertEquals("https://eu.elu.dev/sdk/v2/$KEY/config", V2ConfigEndpoint.build("https://eu.elu.dev/", KEY).toString())
        listOf("", "provider_token", "elu_pk_live_short", "$KEY/other", "$KEY?key=x", "$KEY#x", "$KEY\n", " $KEY", "..")
            .forEach { key -> assertThrows(key, IllegalArgumentException::class.java) { V2ConfigEndpoint.build("https://elu.dev", key) } }
        listOf("http://elu.dev", "https://elu.dev.evil.test", "https://evil.test", "https://elu.dev:443", "https://u@elu.dev", "https://elu.dev/path", "https://elu.dev?x=1", "https://localhost")
            .forEach { host -> assertThrows(host, IllegalArgumentException::class.java) { V2ConfigEndpoint.build(host, KEY) } }
        var opened = false
        val transport = HttpURLConnectionV2ConfigTransport(elapsedRealtimeNanos = System::nanoTime, connectionFactory = { opened = true; FakeConnection(200) })
        listOf("https://evil.test/sdk/v2/$KEY/config", "https://elu.dev/sdk/v1/$KEY/config", "https://elu.dev/sdk/v2/$KEY/config?x=1")
            .forEach { endpoint -> assertThrows(IllegalArgumentException::class.java) { transport.fetch(URI(endpoint)) } }
        assertFalse(opened)
    }

    @Test
    fun `GET sends bounded identity JSON preferences with redirects and caching disabled`() {
        val connection = FakeConnection(200, "{\"enabled\":true}".toByteArray())
        val result = HttpURLConnectionV2ConfigTransport(elapsedRealtimeNanos = System::nanoTime, connectionFactory = { connection }).fetch(endpoint())
        assertEquals(200, result.status)
        assertEquals("{\"enabled\":true}", result.body)
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertEquals("no-store", connection.getRequestProperty("Cache-Control"))
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertFalse(connection.doOutput)
        assertTrue(connection.streamClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `redirects refusals cache replies and server errors never read their bodies`() {
        listOf(301, 302, 304, 307, 401, 403, 404, 429, 503).forEach { status ->
            val connection = FakeConnection(status, failBody = true)
            val result = HttpURLConnectionV2ConfigTransport(elapsedRealtimeNanos = System::nanoTime, connectionFactory = { connection }).fetch(endpoint())
            assertEquals(status, result.status)
            assertNull(result.body)
            assertEquals(0, connection.bodyReads)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun `declared streamed compressed and invalid UTF8 bodies fail with cleanup`() {
        listOf(
            FakeConnection(200, headers = mapOf("Content-Length" to "6")),
            FakeConnection(200, ByteArray(6)),
            FakeConnection(200, headers = mapOf("Content-Length" to "-1")),
            FakeConnection(200, headers = mapOf("Content-Length" to "invalid")),
            FakeConnection(200, headers = mapOf("Content-Encoding" to "gzip")),
            FakeConnection(200, byteArrayOf(0xc3.toByte(), 0x28)),
        ).forEach { connection ->
            assertThrows(IOException::class.java) {
                HttpURLConnectionV2ConfigTransport(elapsedRealtimeNanos = System::nanoTime, maximumResponseBytes = 5, connectionFactory = { connection }).fetch(endpoint())
            }
            assertTrue(connection.disconnected)
        }
        val connection = FakeConnection(200, ByteArray(5) { 'x'.code.toByte() })
        assertEquals("xxxxx", HttpURLConnectionV2ConfigTransport(elapsedRealtimeNanos = System::nanoTime, maximumResponseBytes = 5, connectionFactory = { connection }).fetch(endpoint()).body)
    }

    @Test
    fun `read failures always disconnect`() {
        val connection = FakeConnection(200, failBody = true)
        assertThrows(IOException::class.java) {
            HttpURLConnectionV2ConfigTransport(elapsedRealtimeNanos = System::nanoTime, connectionFactory = { connection }).fetch(endpoint())
        }
        assertTrue(connection.disconnected)
    }

    @Test
    fun `slow drip cannot reset overall elapsed deadline and scheduled abort is canceled`() {
        var elapsed = 0L
        var canceled = false
        val connection = FakeConnection(200, "abcdef".toByteArray(), onRead = { elapsed += 6_000_000L })
        val transport = HttpURLConnectionV2ConfigTransport(
            requestTimeoutMillis = 10,
            readTimeoutMillis = 10,
            elapsedRealtimeNanos = { elapsed },
            scheduleDeadline = { _, _ -> AutoCloseable { canceled = true } },
            connectionFactory = { connection },
        )
        assertThrows(SocketTimeoutException::class.java) { transport.fetch(endpoint()) }
        assertEquals(4, connection.readTimeout)
        assertTrue(connection.streamClosed)
        assertTrue(connection.disconnected)
        assertTrue(canceled)
    }

    @Test
    fun `independent deadline disconnects transport even without another elapsed clock sample`() {
        var expire: (() -> Unit)? = null
        var canceled = false
        val connection = FakeConnection(200, "x".toByteArray(), onRead = { checkNotNull(expire).invoke() })
        val transport = HttpURLConnectionV2ConfigTransport(
            elapsedRealtimeNanos = { 1L },
            scheduleDeadline = { _, action -> expire = action; AutoCloseable { canceled = true } },
            connectionFactory = { connection },
        )
        assertThrows(SocketTimeoutException::class.java) { transport.fetch(endpoint()) }
        assertTrue(connection.disconnected)
        assertTrue(canceled)
    }

    private fun endpoint(): URI = V2ConfigEndpoint.build("https://elu.dev", KEY)

    private class FakeConnection(
        private val status: Int,
        private val bytes: ByteArray = ByteArray(0),
        private val headers: Map<String, String> = emptyMap(),
        private val failBody: Boolean = false,
        private val onRead: (() -> Unit)? = null,
    ) : HttpURLConnection(URL("https://elu.dev")) {
        var disconnected = false
        var streamClosed = false
        var bodyReads = 0

        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun getInputStream(): InputStream {
            bodyReads++
            if (failBody) throw IOException("Unusable body")
            return object : ByteArrayInputStream(bytes) {
                override fun close() { streamClosed = true; super.close() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead?.invoke()
                    return super.read(buffer, offset, if (onRead == null) length else minOf(length, 1))
                }
            }
        }
        override fun getErrorStream(): InputStream = error("Error bodies must not be read")
    }

    private companion object {
        val KEY = "elu_pk_live_${"A".repeat(26)}"
    }
}
