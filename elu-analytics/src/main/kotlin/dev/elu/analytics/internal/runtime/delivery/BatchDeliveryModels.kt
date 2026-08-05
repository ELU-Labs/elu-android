package dev.elu.analytics.internal.runtime.delivery

import dev.elu.analytics.internal.runtime.MAX_RUNTIME_DELIVERY_BYTES
import dev.elu.analytics.internal.runtime.MAX_RUNTIME_DELIVERY_RECORDS
import dev.elu.analytics.internal.runtime.RuntimeAcknowledgement
import dev.elu.analytics.internal.runtime.RuntimeAcknowledgementResult
import dev.elu.analytics.internal.runtime.RuntimeQueuedRecord
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal const val MAX_BATCH_RESPONSE_BYTES: Int = 1_048_576

/** One immutable, already-authorized events endpoint decision. */
internal class V1BatchAuthorizationSnapshot(
    val siteKey: String,
    eventsEndpoint: URI,
    val expiresAt: String,
    val eventBatchCount: Int,
    val eventBatchBytes: Int,
) {
    val eventsEndpoint: URI = URI(eventsEndpoint.toASCIIString())
    val expiresAtEpochMillisFloor: Long

    init {
        require(
            siteKey.length in 1..512 &&
                siteKey.all { character ->
                    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "._~-"
                },
        ) {
            "siteKey must be a non-empty authorization-header-safe value"
        }
        requireAuthorizedEventsEndpoint(this.eventsEndpoint)
        RuntimeRecordCodec.compareTimestamps(expiresAt, expiresAt)
        expiresAtEpochMillisFloor = RuntimeRecordCodec.timestampToEpochMillisFloor(expiresAt)
        require(eventBatchCount in 1..MAX_RUNTIME_DELIVERY_RECORDS) {
            "eventBatchCount must be in 1..$MAX_RUNTIME_DELIVERY_RECORDS"
        }
        require(eventBatchBytes in 1_024..MAX_RUNTIME_DELIVERY_BYTES.toInt()) {
            "eventBatchBytes must be in 1024..$MAX_RUNTIME_DELIVERY_BYTES"
        }
    }

    private fun requireAuthorizedEventsEndpoint(endpoint: URI) {
        require(
            endpoint.isAbsolute &&
                endpoint.scheme == "https" &&
                endpoint.host?.lowercase(Locale.US) == EVENTS_HOST &&
                endpoint.rawPath == EVENTS_PATH &&
                (endpoint.port == -1 || endpoint.port == 443) &&
                endpoint.rawUserInfo == null &&
                endpoint.rawFragment == null,
        ) { "eventsEndpoint is outside the authorized ELU events endpoint" }
        val containsSiteKey =
            endpoint.rawQuery?.split('&')?.any { part ->
                val name = part.substringBefore('=')
                runCatching { URLDecoder.decode(name, StandardCharsets.UTF_8.name()) }.getOrNull() == "site_key"
            } == true
        require(!containsSiteKey) { "eventsEndpoint must not contain authorization state" }
    }

    private companion object {
        const val EVENTS_HOST = "ingest.elu.dev"
        const val EVENTS_PATH = "/v1/events"
    }
}

internal data class BatchWallInstant(
    val epochMillis: Long,
    val rfc3339: String,
) {
    init {
        require(RuntimeRecordCodec.timestampToEpochMillisFloor(rfc3339) == epochMillis) {
            "epochMillis must match the RFC 3339 instant at millisecond precision"
        }
    }
}

internal interface BatchDeliveryClock {
    fun wallNow(): BatchWallInstant

    fun monotonicNowNanos(): Long
}

internal object SystemBatchDeliveryClock : BatchDeliveryClock {
    override fun wallNow(): BatchWallInstant {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return BatchWallInstant(now, formatter.format(Date(now)))
    }

    override fun monotonicNowNanos(): Long = System.nanoTime()
}

internal fun interface BatchScheduledTask {
    fun cancel()
}

internal fun interface BatchRetryScheduler {
    fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): BatchScheduledTask
}

internal class ScheduledExecutorBatchRetryScheduler(
    private val executor: ScheduledExecutorService,
) : BatchRetryScheduler {
    override fun schedule(
        delayMillis: Long,
        task: Runnable,
    ): BatchScheduledTask {
        require(delayMillis >= 0) { "delayMillis must be non-negative" }
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return BatchScheduledTask { future.cancel(false) }
    }
}

internal fun interface BatchJitterSource {
    /** Returns a finite value in the half-open interval [0, 1). */
    fun nextUnitDouble(): Double
}

internal interface RuntimeDeliveryQueue {
    fun peek(
        maximumCount: Int,
        maximumBytes: Long,
    ): List<RuntimeQueuedRecord>

    fun acknowledge(acknowledgement: RuntimeAcknowledgement): DeliveryQueueAcknowledgement
}

internal sealed interface DeliveryQueueAcknowledgement {
    data class Deleted(val count: Int) : DeliveryQueueAcknowledgement

    data object AlreadyApplied : DeliveryQueueAcknowledgement

    data object Empty : DeliveryQueueAcknowledgement
}

/** Synchronous adapter used only from the delivery coordinator's worker. */
internal class RuntimeQueueOwnerDeliveryQueue(
    private val owner: RuntimeQueueOwner,
) : RuntimeDeliveryQueue {
    override fun peek(
        maximumCount: Int,
        maximumBytes: Long,
    ): List<RuntimeQueuedRecord> {
        requireOffRuntimeQueueOwnerThread(owner.isCurrentThreadWorker())
        return await(owner.peek(maximumCount, maximumBytes))
    }

    override fun acknowledge(acknowledgement: RuntimeAcknowledgement): DeliveryQueueAcknowledgement {
        requireOffRuntimeQueueOwnerThread(owner.isCurrentThreadWorker())
        return when (val result = await(owner.acknowledge(acknowledgement))) {
            is RuntimeAcknowledgementResult.Deleted -> DeliveryQueueAcknowledgement.Deleted(result.count)
            is RuntimeAcknowledgementResult.AlreadyApplied -> DeliveryQueueAcknowledgement.AlreadyApplied
            is RuntimeAcknowledgementResult.Empty -> DeliveryQueueAcknowledgement.Empty
        }
    }

    private fun <T> await(future: java.util.concurrent.Future<T>): T =
        try {
            future.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
}

internal fun requireOffRuntimeQueueOwnerThread(isQueueOwnerThread: Boolean) {
    check(!isQueueOwnerThread) {
        "Batch delivery must use an executor distinct from the runtime queue owner worker"
    }
}

internal class BatchHTTPRequest(
    val endpoint: URI,
    val requestId: String,
    authorizationHeader: String,
    body: ByteArray,
) {
    private val authorizationHeader = authorizationHeader
    private val body = body.copyOf()

    fun authorizationHeader(): String = authorizationHeader

    fun bodyBytes(): ByteArray = body.copyOf()
}

internal class BatchHTTPResponse(
    val status: Int,
    body: ByteArray,
    val retryAfter: String? = null,
) {
    private val body = body.copyOf()

    init {
        require(status in 100..599) { "status must be a valid HTTP status" }
        require(retryAfter == null || retryAfter.length <= 256) { "Retry-After exceeds the supported limit" }
    }

    fun bodyBytes(): ByteArray = body.copyOf()
}

internal fun interface BatchHTTPTransport {
    @Throws(IOException::class)
    fun execute(request: BatchHTTPRequest): BatchHTTPResponse
}

internal class BatchResponseTooLargeException(
    val maximumBytes: Int,
) : IllegalStateException("Batch response exceeds $maximumBytes bytes")

/** Bounded synchronous HTTP boundary. It is intentionally not instantiated by production code. */
internal class HttpURLConnectionBatchTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maximumResponseBytes: Int = MAX_BATCH_RESPONSE_BYTES,
    private val connectionFactory: (URI) -> HttpURLConnection = { endpoint ->
        endpoint.toURL().openConnection() as HttpURLConnection
    },
) : BatchHTTPTransport {
    init {
        require(connectTimeoutMillis in 1..30_000)
        require(readTimeoutMillis in 1..30_000)
        require(maximumResponseBytes in 1..MAX_BATCH_RESPONSE_BYTES)
    }

    override fun execute(request: BatchHTTPRequest): BatchHTTPResponse {
        val connection = connectionFactory(request.endpoint)
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.doOutput = true
        connection.useCaches = false
        connection.setRequestProperty("Authorization", request.authorizationHeader())
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        val body = request.bodyBytes()
        connection.setFixedLengthStreamingMode(body.size)
        try {
            connection.outputStream.use { output -> output.write(body) }
            val status = connection.responseCode
            val contentEncoding = connection.getHeaderField("Content-Encoding")
            if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
                throw IllegalStateException("Unsupported batch response content encoding")
            }
            connection.getHeaderField("Content-Length")?.toLongOrNull()?.let { length ->
                if (length > maximumResponseBytes) throw BatchResponseTooLargeException(maximumResponseBytes)
            }
            val stream =
                if (status >= 400) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }
            val responseBody = stream?.use { readBounded(it.readBytesChunked(), maximumResponseBytes) } ?: ByteArray(0)
            return BatchHTTPResponse(status, responseBody, connection.getHeaderField("Retry-After"))
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesChunked(): Sequence<ByteArray> = sequence {
        val buffer = ByteArray(8_192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read > 0) yield(buffer.copyOf(read))
        }
    }

    private fun readBounded(
        chunks: Sequence<ByteArray>,
        maximumBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8_192))
        var total = 0
        chunks.forEach { chunk ->
            total = Math.addExact(total, chunk.size)
            if (total > maximumBytes) throw BatchResponseTooLargeException(maximumBytes)
            output.write(chunk)
        }
        return output.toByteArray()
    }
}
