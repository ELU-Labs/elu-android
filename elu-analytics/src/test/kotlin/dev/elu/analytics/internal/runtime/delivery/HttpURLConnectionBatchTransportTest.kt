package dev.elu.analytics.internal.runtime.delivery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpURLConnectionBatchTransportTest {
    @Test
    fun `transport posts bounded identity JSON with bearer authorization and no redirects`() {
        val connection = FakeConnection(200, "{\"ok\":true}".encodeToByteArray())
        val transport = HttpURLConnectionBatchTransport(connectionFactory = { connection })
        val body = "{\"schemaVersion\":1}".encodeToByteArray()

        val response =
            transport.execute(
                BatchHTTPRequest(
                    URI("https://ingest.elu.dev/v1/events"),
                    "request_test",
                    "Bearer elu_pk_test",
                    body,
                ),
            )

        assertEquals(200, response.status)
        assertArrayEquals("{\"ok\":true}".encodeToByteArray(), response.bodyBytes())
        assertArrayEquals(body, connection.output.toByteArray())
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer elu_pk_test", connection.getRequestProperty("Authorization"))
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `transport rejects declared or streamed oversized and encoded responses`() {
        val declared = FakeConnection(200, ByteArray(0), mapOf("Content-Length" to "6"))
        assertThrows(BatchResponseTooLargeException::class.java) {
            HttpURLConnectionBatchTransport(maximumResponseBytes = 5, connectionFactory = { declared })
                .execute(request())
        }
        assertTrue(declared.disconnected)

        val streamed = FakeConnection(200, ByteArray(6))
        assertThrows(BatchResponseTooLargeException::class.java) {
            HttpURLConnectionBatchTransport(maximumResponseBytes = 5, connectionFactory = { streamed })
                .execute(request())
        }
        assertTrue(streamed.disconnected)

        val compressed = FakeConnection(200, ByteArray(0), mapOf("Content-Encoding" to "gzip"))
        assertThrows(IllegalStateException::class.java) {
            HttpURLConnectionBatchTransport(connectionFactory = { compressed }).execute(request())
        }
        assertTrue(compressed.disconnected)
    }

    private fun request(): BatchHTTPRequest =
        BatchHTTPRequest(
            URI("https://ingest.elu.dev/v1/events"),
            "request_test",
            "Bearer elu_pk_test",
            "{}".encodeToByteArray(),
        )

    private class FakeConnection(
        private val responseStatus: Int,
        private val responseBody: ByteArray,
        private val responseHeaders: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(URL("https://ingest.elu.dev/v1/events")) {
        val output = ByteArrayOutputStream()
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): ByteArrayOutputStream = output

        override fun getResponseCode(): Int = responseStatus

        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBody)

        override fun getErrorStream(): InputStream? =
            if (responseStatus >= 400) ByteArrayInputStream(responseBody) else null

        override fun getHeaderField(name: String?): String? = responseHeaders[name]
    }
}
