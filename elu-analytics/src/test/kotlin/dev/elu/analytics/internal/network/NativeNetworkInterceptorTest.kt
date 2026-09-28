package dev.elu.analytics.internal.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.*
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class NativeNetworkInterceptorTest {
    @Test fun returnsExactResponseAndNeverReadsHeadersOrBodyForItsFiveFieldSummary() {
        val values = mutableListOf<Map<String, Any>>()
        var clock = 1_000_000_000L
        val interceptor = NativeNetworkInterceptor({ NativeNetworkObservation { values += it } }, { clock.also { clock += 123_450_000 } })
        val request = Request.Builder().url("https://customer.example/private/user@example.test?token=SECRET#PRIVATE").header("Authorization", "SECRET").build()
        val chain = Chain(request)
        assertSame(chain.response, interceptor.intercept(chain))
        assertEquals(1, chain.calls)
        assertSame(request, chain.proceeded)
        assertEquals(mapOf("\$network_method" to "GET", "\$network_status_code" to 201,
            "\$network_response_time_ms" to 123.5, "\$network_initiator" to "okhttp", "\$network_failed" to false), values.single())
        assertFalse(values.toString().contains("SECRET")); assertFalse(values.toString().contains("customer"))
    }

    @Test fun originalFailureIsRethrownEvenWhenTelemetryThrows() {
        val original = IOException("PRIVATE_FAILURE_DETAIL")
        val values = mutableListOf<Map<String, Any>>()
        val interceptor = NativeNetworkInterceptor({ NativeNetworkObservation { values += it; error("telemetry failed") } }, { 1 })
        val chain = Chain(failure = original)
        try { interceptor.intercept(chain); fail("expected original failure") } catch (caught: IOException) { assertSame(original, caught) }
        assertEquals(1, chain.calls)
        assertEquals(0, values.single()["\$network_status_code"])
        assertEquals(true, values.single()["\$network_failed"])
        assertFalse(values.toString().contains("PRIVATE"))
    }

    @Test fun noAdmissionAndBrokenClockDoNotAffectApplicationRequest() {
        for (interceptor in listOf(NativeNetworkInterceptor({ null }),
            NativeNetworkInterceptor({ error("not ready") }),
            NativeNetworkInterceptor({ NativeNetworkObservation { fail("broken clock must not report") } }, { error("clock") }))) {
            val chain = Chain(); assertSame(chain.response, interceptor.intercept(chain)); assertEquals(1, chain.calls)
        }
    }

    @Test fun sdkRequestsAndRedirectsAreExcludedWithoutChangingProceed() {
        var started = 0; var sent = 0
        val interceptor = NativeNetworkInterceptor({ started++; NativeNetworkObservation { sent++ } }, { 1 })
        for (host in listOf("elu.dev", "ingest.elu.dev", "preview.elu.dev")) {
            val chain = Chain(Request.Builder().url("https://$host/v2/anything").build())
            assertSame(chain.response, interceptor.intercept(chain))
        }
        assertEquals(0, started)
        val chain = Chain(finalRequest = Request.Builder().url("https://ingest.elu.dev/v2/capture").build())
        assertSame(chain.response, interceptor.intercept(chain)); assertEquals(1, started); assertEquals(0, sent)
        val configRedirect = NativeNetworkInterceptor({ NativeNetworkObservation({ it != "localhost" }) { sent++ } }, { 1 })
        configRedirect.intercept(Chain(finalRequest = Request.Builder().url("http://localhost:8787/config").build()))
        assertEquals(0, sent)
    }

    @Test fun unknownMethodAndDurationAreBoundedAndSettlementIsOnce() {
        val values = mutableListOf<Map<String, Any>>()
        val observation = NativeNetworkObservation { values += it }
        var clock = 0L
        val interceptor = NativeNetworkInterceptor({ observation }, { clock.also { clock = Long.MAX_VALUE } })
        val request = Request.Builder().url("https://customer.example").method("PRIVATE_METHOD", null).build()
        interceptor.intercept(Chain(request))
        observation.complete(mapOf("repeat" to true))
        assertEquals(1, values.size)
        assertEquals("UNKNOWN", values.single()["\$network_method"])
        assertEquals(86_400_000.0, values.single()["\$network_response_time_ms"])
    }

    private class Chain(
        private val original: Request = Request.Builder().url("https://customer.example").build(),
        private val failure: Throwable? = null,
        finalRequest: Request = original,
    ) : Interceptor.Chain {
        var calls = 0
        var proceeded: Request? = null
        val response = Response.Builder().request(finalRequest).protocol(Protocol.HTTP_1_1).code(201).message("OK")
            .body(object : ResponseBody() {
                override fun contentType(): MediaType? = error("body metadata read")
                override fun contentLength(): Long = error("body length read")
                override fun source(): BufferedSource = error("body read/closed")
            }).build()
        override fun request() = original
        override fun proceed(request: Request): Response { calls++; proceeded = request; failure?.let { throw it }; return response }
        override fun connection(): Connection? = error("not needed")
        override fun call(): Call = error("not needed")
        override fun connectTimeoutMillis() = 0
        override fun readTimeoutMillis() = 0
        override fun writeTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = error("timeout modified")
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = error("timeout modified")
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = error("timeout modified")
    }
}
