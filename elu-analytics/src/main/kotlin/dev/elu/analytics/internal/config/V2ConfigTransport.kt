package dev.elu.analytics.internal.config

import dev.elu.analytics.EluConfigHostPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

internal const val V2_CONFIG_MAXIMUM_RESPONSE_BYTES: Int = 65_536

/** Only an issued, single-component ELU credential may enter the config path. */
internal object V2ConfigEndpoint {
    private val SITE_KEY = Regex("elu_pk_(live|test)_[A-Za-z0-9]{22,64}")

    fun build(configHost: String, siteKey: String, debuggable: Boolean = false): URI {
        require(SITE_KEY.matches(siteKey)) { "Invalid ELU site key" }
        val origin = requireNotNull(EluConfigHostPolicy.resolve(configHost, debuggable)) {
            "Configuration requires an approved application config origin"
        }
        return URI("$origin/sdk/v2/$siteKey/config")
    }

    fun requireApproved(endpoint: URI, debuggable: Boolean = false) {
        val components = endpoint.rawPath?.split('/') ?: emptyList()
        require(components.size == 5 && components[1] == "sdk" && components[2] == "v2" && components[4] == "config") {
            "Invalid v2 config endpoint"
        }
        val origin = "${endpoint.scheme}://${endpoint.rawAuthority}"
        require(endpoint == build(origin, components[3], debuggable)) { "Invalid v2 config endpoint" }
    }
}

internal data class V2ConfigHttpResponse(val status: Int, val body: String?)

internal fun interface V2ConfigTransport {
    fun fetch(endpoint: URI): V2ConfigHttpResponse
}

/**
 * Blocking native transport. The lifecycle owner must invoke it off the main thread.
 * No redirect, URL cache, provider fallback, or error response body is consumed.
 */
internal class HttpURLConnectionV2ConfigTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maximumResponseBytes: Int = V2_CONFIG_MAXIMUM_RESPONSE_BYTES,
    private val requestTimeoutMillis: Int = 20_000,
    private val elapsedRealtimeNanos: () -> Long = AndroidV2ConfigClock::monotonicNowNanos,
    private val scheduleDeadline: (Long, () -> Unit) -> AutoCloseable = { delay, expire ->
        val timer = Timer("elu-v2-config-deadline", true)
        timer.schedule(object : TimerTask() {
            override fun run() = expire()
        }, delay)
        AutoCloseable { timer.cancel() }
    },
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val debuggable: Boolean = false,
) : V2ConfigTransport {
    init {
        require(connectTimeoutMillis in 1..30_000)
        require(readTimeoutMillis in 1..30_000)
        require(requestTimeoutMillis in 1..30_000)
        require(maximumResponseBytes in 1..V2_CONFIG_MAXIMUM_RESPONSE_BYTES)
    }

    override fun fetch(endpoint: URI): V2ConfigHttpResponse {
        V2ConfigEndpoint.requireApproved(endpoint, debuggable)
        val started = elapsedRealtimeNanos()
        val connection = connectionFactory(endpoint)
        val timedOut = AtomicBoolean(false)
        var deadline: AutoCloseable? = null
        fun remainingMillis(): Int {
            val now = elapsedRealtimeNanos()
            val remaining = requestTimeoutMillis * 1_000_000L - (now - started)
            if (timedOut.get() || started < 0 || now < started || remaining <= 0) {
                throw SocketTimeoutException("Config request deadline elapsed")
            }
            return ((remaining + 999_999L) / 1_000_000L).toInt()
        }
        try {
            // The independent deadline also releases a read blocked inside the native connection.
            deadline = scheduleDeadline(remainingMillis().toLong()) {
                timedOut.set(true)
                try { connection.disconnect() } catch (_: Exception) { }
            }
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = minOf(connectTimeoutMillis, remainingMillis())
            connection.readTimeout = minOf(readTimeoutMillis, remainingMillis())
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            val status = connection.responseCode
            remainingMillis()
            if (status != HttpURLConnection.HTTP_OK) return V2ConfigHttpResponse(status, null)

            val encoding = connection.getHeaderField("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                throw IOException("Unsupported config response encoding")
            }
            connection.getHeaderField("Content-Length")?.let { declared ->
                val length = declared.toLongOrNull() ?: throw IOException("Invalid config response length")
                if (length < 0 || length > maximumResponseBytes) throw IOException("Config response exceeds byte limit")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(maximumResponseBytes, 8_192))
                val buffer = ByteArray(8_192)
                while (true) {
                    connection.readTimeout = minOf(readTimeoutMillis, remainingMillis())
                    val count = input.read(buffer)
                    remainingMillis()
                    if (count < 0) break
                    if (count == 0) throw IOException("Config response made no progress")
                    if (count > maximumResponseBytes - output.size()) throw IOException("Config response exceeds byte limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val body = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            remainingMillis()
            return V2ConfigHttpResponse(status, body)
        } finally {
            deadline?.close()
            connection.disconnect()
        }
    }
}
