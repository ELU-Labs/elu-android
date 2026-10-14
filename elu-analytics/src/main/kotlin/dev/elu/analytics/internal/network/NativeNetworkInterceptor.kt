package dev.elu.analytics.internal.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

/** One original facade admission; settlement is at most once even if a caller repeats it. */
internal class NativeNetworkObservation(
    private val acceptsFinalHost: (String) -> Boolean = { true },
    private val publish: (Map<String, Any>) -> Unit,
) {
    private val completed = AtomicBoolean(false)
    fun complete(properties: Map<String, Any>, finalHost: String? = null) {
        if (completed.compareAndSet(false, true) && (finalHost == null || acceptsFinalHost(finalHost))) publish(properties)
    }
}

/** Only closed numeric/Boolean/method fields survive the interception boundary. */
internal class NativeNetworkInterceptor(
    private val begin: (String) -> NativeNetworkObservation?,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val observation = if (isOwnedSdkHost(request.url.host)) null else runCatching { begin(request.url.host) }.getOrNull()
        val started = observation?.let { runCatching(monotonicNanos).getOrNull() }
        fun report(status: Int, failed: Boolean, finalHost: String?) {
            if (observation == null || started == null || finalHost?.let(::isOwnedSdkHost) == true) return
            val finished = monotonicNanos()
            if (finished < started) return
            val elapsed = (finished.toDouble() - started.toDouble()) / 1_000_000.0
            if (!elapsed.isFinite() || elapsed < 0) return
            observation.complete(mapOf(
                "\$network_method" to method(request.method),
                "\$network_status_code" to status.coerceIn(0, 999),
                "\$network_response_time_ms" to floor(elapsed.coerceAtMost(86_400_000.0) * 10 + 0.5) / 10,
                "\$network_initiator" to "okhttp",
                "\$network_failed" to failed,
            ), finalHost)
        }
        return try {
            val response = chain.proceed(request)
            runCatching { report(response.code, false, response.request.url.host) }
            response
        } catch (failure: Throwable) {
            runCatching { report(0, true, null) }
            throw failure
        }
    }

    private fun method(value: String): String = when (value) {
        "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH" -> value
        else -> "UNKNOWN"
    }

    internal companion object {
        fun isOwnedSdkHost(host: String): Boolean = host == "elu.dev" || host.endsWith(".elu.dev")
    }
}

/** Original identity/session/source snapshot; equality never grants authority by itself. */
internal data class NativeNetworkContext(
    val source: Any,
    val identityRevision: Long,
    val contextRevision: Long,
    val sessionId: String?,
    val sessionStartedAt: String?,
    val flagIntentRevision: Long,
    val consentIntentRevision: Long,
    val lifecycleEpoch: Any,
)
