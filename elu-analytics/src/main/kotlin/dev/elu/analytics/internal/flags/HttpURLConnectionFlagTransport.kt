package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.AndroidV2ConfigClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Timer
import java.util.TimerTask
import dev.elu.analytics.internal.concurrent.SdkFuture
import dev.elu.analytics.internal.concurrent.SdkCompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Internal flags POST boundary, bound to one ELU credential and config endpoint.
 * The existing flag owner encodes the request and checks identity/context/authorization before
 * dispatch and again at response commit. This transport carries those bytes unchanged and never
 * grants authority, retries on its own, persists data, or supplies a provider fallback.
 */
internal class HttpURLConnectionFlagTransport(
    siteKey: String,
    private val endpoint: URI,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val requestTimeoutMillis: Int = 20_000,
    private val maximumResponseBytes: Int = FLAG_MAX_WIRE_BYTES,
    private val elapsedRealtimeNanos: () -> Long = AndroidV2ConfigClock::monotonicNowNanos,
    private val scheduleDeadline: (Long, () -> Unit) -> AutoCloseable = { delay, expire ->
        val timer = Timer("elu-flags-deadline", true)
        timer.schedule(object : TimerTask() { override fun run() = expire() }, delay)
        AutoCloseable { timer.cancel() }
    },
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "elu-flags-http").apply { isDaemon = true }
    },
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val diagnostic: FlagDiagnosticObserver = FlagDiagnosticObserver.NONE,
) : FlagTransport, AutoCloseable {
    private val authorization: String
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Flight?>()

    init {
        require(Regex("elu_pk_(live|test)_[A-Za-z0-9]{22,64}").matches(siteKey)) { "Invalid ELU site key" }
        requireApprovedEndpoint(endpoint)
        require(connectTimeoutMillis in 1..30_000 && readTimeoutMillis in 1..30_000 && requestTimeoutMillis in 1..30_000)
        require(maximumResponseBytes in 1..FLAG_MAX_WIRE_BYTES)
        authorization = "Bearer $siteKey"
    }

    override fun send(request: FlagTransportRequest): SdkCompletionStage<ByteArray> {
        val result = SdkFuture<ByteArray>()
        observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_ADMISSION) }
        if (closed.get()) return result.failed("Flag transport is closed")
        val body = try {
            require(request.endpoint == endpoint) { "Flag request endpoint differs from its config binding" }
            require(request.canonicalBody.isNotEmpty() && request.canonicalBody.size <= FLAG_MAX_WIRE_BYTES) { "Invalid flag request byte count" }
            request.canonicalBody.copyOf().also { bytes ->
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            }
        } catch (error: Exception) {
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_BODY_REFUSED, failure = flagDiagnosticFailure(error)) }
            result.completeExceptionally(error)
            return result
        }
        val flight = Flight(result)
        if (!active.compareAndSet(null, flight)) {
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_SLOT_REFUSED) }
            return result.failed("A flag request is already in flight")
        }
        result.whenComplete { _, _ -> if (result.isCancelled) flight.cancel() }
        if (closed.get()) {
            active.compareAndSet(flight, null)
            result.cancel(false)
            return result
        }
        try {
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_QUEUED) }
            executor.execute {
                observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_WORKER) }
                var response: ByteArray? = null
                var failure: Exception? = null
                try {
                    if (!flight.canceled.get() && !closed.get()) response = execute(body, flight, request.authorizeSend)
                } catch (error: Exception) {
                    observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_FAILURE, failure = flagDiagnosticFailure(error)) }
                    failure = error
                } finally {
                    // Release only after physical cleanup, but before invoking completion listeners.
                    active.compareAndSet(flight, null)
                }
                if (flight.canceled.get() || closed.get()) {
                    observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_CANCELED) }
                    result.cancel(false)
                }
                else if (failure != null) result.completeExceptionally(failure)
                else if (response != null) {
                    observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_COMPLETED) }
                    result.complete(response)
                }
                else result.cancel(false)
            }
        } catch (error: Exception) {
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_FAILURE, failure = flagDiagnosticFailure(error)) }
            active.compareAndSet(flight, null)
            result.completeExceptionally(error)
        }
        return result
    }

    internal fun isIdle(): Boolean = active.get() == null

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.get()?.let { it.result.cancel(false); it.cancel() }
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun execute(body: ByteArray, flight: Flight, authorizeSend: () -> Boolean): ByteArray {
        observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_CLOCK) }
        val started = elapsedRealtimeNanos()
        val timedOut = AtomicBoolean(false)
        observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_CONNECTION_FACTORY) }
        val connection = connectionFactory(endpoint)
        flight.connection.set(connection)
        var deadline: AutoCloseable? = null
        fun remainingMillis(): Int {
            val now = elapsedRealtimeNanos()
            val left = requestTimeoutMillis * 1_000_000L - (now - started)
            if (flight.canceled.get() || closed.get()) throw IOException("Flag request canceled")
            if (timedOut.get() || started < 0 || now < started || left <= 0) throw SocketTimeoutException("Flag request deadline elapsed")
            return ((left + 999_999L) / 1_000_000L).toInt()
        }
        try {
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_DEADLINE) }
            deadline = scheduleDeadline(remainingMillis().toLong()) {
                timedOut.set(true)
                try { connection.disconnect() } catch (_: Exception) { }
            }
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_HEADERS) }
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = minOf(connectTimeoutMillis, remainingMillis())
            connection.readTimeout = minOf(readTimeoutMillis, remainingMillis())
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setFixedLengthStreamingMode(body.size)
            remainingMillis()
            if (!authorizeSend().also { allowed -> observeFlag(diagnostic) {
                FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_AUTHORIZATION_BEFORE, allowed = allowed) } })
                throw IOException("Flag authorization changed before egress")
            remainingMillis()
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_OUTPUT_OPEN) }
            connection.outputStream.use { output ->
                if (!authorizeSend().also { allowed -> observeFlag(diagnostic) {
                    FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_AUTHORIZATION_AFTER, allowed = allowed) } })
                    throw IOException("Flag authorization changed while connecting")
                remainingMillis()
                observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_WRITE) }
                output.write(body)
            }
            remainingMillis()
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_RESPONSE_STATUS) }
            val status = connection.responseCode
            remainingMillis()
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Flag request refused with HTTP $status")
            val encoding = connection.getHeaderField("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw IOException("Unsupported flag response encoding")
            connection.getHeaderField("Content-Length")?.let { declared ->
                val length = declared.toLongOrNull() ?: throw IOException("Invalid flag response length")
                if (length < 0 || length > maximumResponseBytes) throw IOException("Flag response exceeds byte limit")
            }
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_RESPONSE_BODY) }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(maximumResponseBytes, 8_192))
                val buffer = ByteArray(8_192)
                while (true) {
                    connection.readTimeout = minOf(readTimeoutMillis, remainingMillis())
                    val count = input.read(buffer)
                    remainingMillis()
                    if (count < 0) break
                    if (count == 0) throw IOException("Flag response made no progress")
                    if (count > maximumResponseBytes - output.size()) throw IOException("Flag response exceeds byte limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            remainingMillis()
            return bytes
        } finally {
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.HTTP_CLEANUP) }
            deadline?.close()
            flight.connection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private class Flight(val result: SdkFuture<ByteArray>) {
        val canceled = AtomicBoolean(false)
        val connection = AtomicReference<HttpURLConnection?>()
        fun cancel() {
            canceled.set(true)
            try { connection.get()?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun SdkFuture<ByteArray>.failed(message: String): SdkFuture<ByteArray> =
        apply { completeExceptionally(IOException(message)) }

    private companion object {
        /** Mirrors the frozen config manager's flags role, including non-credential routing query. */
        fun requireApprovedEndpoint(uri: URI) {
            val raw = uri.toString()
            require(raw.all { it.code in 0x21..0x7e } && uri.isAbsolute && uri.scheme == "https" &&
                uri.host?.lowercase(java.util.Locale.US) == "ingest.elu.dev" && uri.rawPath == "/v1/flags" &&
                uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port == 443)
            ) { "Untrusted flag endpoint" }
            uri.rawQuery?.split('&')?.forEach { part ->
                require(URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name()) != "site_key") {
                    "Flag endpoint contains reserved authorization state"
                }
            }
        }
    }
}
