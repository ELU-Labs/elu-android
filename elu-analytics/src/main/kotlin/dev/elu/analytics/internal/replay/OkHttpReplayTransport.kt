package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.AndroidV2ConfigClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Timer
import java.util.TimerTask
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** Private, credential-bound HTTP adapter. Only the queue's one-shot callback grants I/O. */
internal class OkHttpReplayTransport(
    private val siteKey: String,
    private val endpoint: URI,
    private val requestTimeoutMillis: Int = 20_000,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maximumResponseBytes: Int = REPLAY_MAX_RESPONSE_BYTES,
    private val elapsedRealtimeNanos: () -> Long = AndroidV2ConfigClock::monotonicNowNanos,
    private val scheduleDeadline: (Long, () -> Unit) -> AutoCloseable = { delay, action ->
        val timer = Timer("elu-replay-deadline", true)
        timer.schedule(object : TimerTask() { override fun run() = action() }, delay)
        AutoCloseable { timer.cancel() }
    },
    private val executor: Executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "elu-replay-http").apply { isDaemon = true }
    },
    private val callFactory: (OkHttpClient, Request) -> Call = { client, request -> client.newCall(request) },
) : ReplayDeliveryTransport, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Flight?>()

    init {
        require(Regex("elu_pk_(live|test)_[A-Za-z0-9]{22,64}").matches(siteKey)) { "Invalid ELU site key" }
        requireApprovedEndpoint(endpoint)
        require(requestTimeoutMillis in 1..30_000 && connectTimeoutMillis in 1..30_000 && readTimeoutMillis in 1..30_000)
        require(maximumResponseBytes in 1..REPLAY_MAX_RESPONSE_BYTES)
    }

    override fun start(claim: ReplayDeliveryClaim, authorizeIo: () -> Boolean): ReplayTransportOperation {
        check(!closed.get()) { "Replay transport is closed" }
        require(claim.authorization.endpoint == endpoint && claim.authorization.siteKey == siteKey) {
            "Replay request differs from the transport's endpoint or credential binding"
        }
        val body = claim.row.prepared.copyBytes()
        require(body.size in 1..MAX_REPLAY_REQUEST_BYTES)
        val flight = Flight(elapsedRealtimeNanos())
        check(active.compareAndSet(null, flight)) { "A replay request is already in flight" }
        try {
            executor.execute { execute(flight, body, authorizeIo) }
        } catch (error: Throwable) {
            active.compareAndSet(flight, null)
            flight.result.completeExceptionally(error)
        }
        return flight
    }

    internal fun isIdle(): Boolean = active.get() == null

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.get()?.cancel()
        // A queued canceled flight must still run its cleanup and settle; shutdownNow can discard it.
        (executor as? ExecutorService)?.shutdown()
    }

    private fun execute(flight: Flight, body: ByteArray, authorizeIo: () -> Boolean) {
        var client: OkHttpClient? = null
        var response: Response? = null
        var deadline: AutoCloseable? = null
        var result: ReplayTransportResponse? = null
        var failure: Throwable? = null
        var cleanupFailure: Throwable? = null
        val timedOut = AtomicBoolean(false)
        val refusal = AtomicInteger(0)
        fun remainingMillis(): Int {
            val now = elapsedRealtimeNanos()
            val left = requestTimeoutMillis * 1_000_000L - (now - flight.started)
            if (timedOut.get() || flight.started < 0 || now < flight.started || left <= 0) {
                throw java.net.SocketTimeoutException("Replay request deadline elapsed")
            }
            if (closed.get() || flight.canceled.get()) throw IOException("Replay request canceled")
            return ((left + 999_999L) / 1_000_000L).toInt()
        }
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) { if (cleanupFailure == null) cleanupFailure = error }
        }
        try {
            remainingMillis()
            deadline = scheduleDeadline(remainingMillis().toLong()) {
                if (!flight.finished.get()) {
                    timedOut.set(true)
                    flight.cancelCall()
                }
            }
            client = OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .cache(null)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .callTimeout(remainingMillis().toLong(), TimeUnit.MILLISECONDS)
                .connectTimeout(minOf(connectTimeoutMillis, remainingMillis()).toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(minOf(readTimeoutMillis, remainingMillis()).toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(minOf(readTimeoutMillis, remainingMillis()).toLong(), TimeUnit.MILLISECONDS)
                .eventListener(object : EventListener() {
                    override fun responseHeadersEnd(call: Call, response: Response) {
                        // A known refusal belongs to this exact physical attempt, even if execute
                        // subsequently throws because cancellation raced response delivery.
                        if (response.code == 401 || response.code == 403) refusal.compareAndSet(0, response.code)
                    }
                })
                .build()
            val request = Request.Builder().url(endpoint.toASCIIString())
                .header("Authorization", "Bearer $siteKey")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store")
                .post(object : RequestBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = body.size.toLong()
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) {
                        var offset = 0
                        while (offset < body.size) {
                            remainingMillis()
                            val count = minOf(8_192, body.size - offset)
                            sink.write(body, offset, count)
                            offset += count
                        }
                    }
                }).build()
            val call = callFactory(client, request)
            flight.call.set(call)
            remainingMillis()
            if (!authorizeIo()) throw IOException("Replay authorization changed before egress")
            remainingMillis()
            response = call.execute()
            if (response.code == 401 || response.code == 403) refusal.compareAndSet(0, response.code)
            if (refusal.get() == 0) {
                remainingMillis()
                result = readResponse(response, maximumResponseBytes) { remainingMillis() }
                remainingMillis()
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            // Every disposer is attempted. Cancel before closing an unread refusal body to prevent
            // response draining from issuing more reads. No failure can publish false settlement.
            cleanup { deadline?.close() }
            cleanup { flight.call.get()?.cancel() }
            // ResponseBody.close uses a quiet-close helper; consume the source disposer directly
            // so an IOException cannot falsely prove physical cleanup.
            cleanup { response?.body?.source()?.close() }
            cleanup { client?.connectionPool?.evictAll() }
            cleanup { client?.dispatcher?.executorService?.shutdown() }
            cleanup { check(client?.connectionPool?.connectionCount() in listOf(null, 0)) { "Replay connection cleanup is incomplete" } }
            flight.finished.set(true)
        }
        if (cleanupFailure != null) {
            // Quarantine this physical slot. The owner's installation lease must remain held.
            return
        }
        flight.call.set(null)
        active.compareAndSet(flight, null)
        val status = refusal.get()
        when {
            status != 0 -> flight.result.complete(ReplayTransportResponse(status, ByteArray(0)))
            failure != null -> flight.result.completeExceptionally(failure)
            result != null && !closed.get() && !flight.canceled.get() && !timedOut.get() -> flight.result.complete(result)
            else -> flight.result.completeExceptionally(IOException("Replay request canceled"))
        }
    }

    private fun readResponse(response: Response, maximum: Int, remaining: () -> Int): ReplayTransportResponse {
        val status = response.code
        val retry = response.headers.values("Retry-After")
        // The empty original-status body makes the existing strict classifier reject a malformed
        // envelope. Network read failures remain transport failures; they do not invent an ACK.
        if (response.headers.byteCount() > REPLAY_MAX_RESPONSE_BYTES || retry.size > 1 || retry.any { it.length > 256 })
            return ReplayTransportResponse(status, ByteArray(0))
        val encoding = response.headers.values("Content-Encoding")
        if (encoding.size > 1 || encoding.any { !it.equals("identity", ignoreCase = true) })
            return ReplayTransportResponse(status, ByteArray(0))
        val lengths = response.headers.values("Content-Length")
        if (lengths.size > 1 || lengths.any { it.toLongOrNull()?.let { size -> size < 0 || size > maximum } != false })
            return ReplayTransportResponse(status, ByteArray(0))
        val input = response.body?.source() ?: return ReplayTransportResponse(status, ByteArray(0), retry.singleOrNull())
        val output = ByteArrayOutputStream(minOf(maximum, 8_192))
        val buffer = ByteArray(8_192)
        while (true) {
            remaining()
            val count = input.read(buffer)
            remaining()
            if (count < 0) break
            if (count == 0) throw IOException("Replay response made no progress")
            if (count > maximum - output.size()) return ReplayTransportResponse(status, ByteArray(0))
            output.write(buffer, 0, count)
        }
        return ReplayTransportResponse(status, output.toByteArray(), retry.singleOrNull())
    }

    private class Flight(val started: Long) : ReplayTransportOperation {
        val result = object : SdkFuture<ReplayTransportResponse>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        }
        override val settlement: SdkFuture<ReplayTransportResponse> get() = result
        val canceled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val call = AtomicReference<Call?>()
        override fun cancel() { canceled.set(true); cancelCall() }
        fun cancelCall() { try { call.get()?.cancel() } catch (_: Exception) { } }
    }

    private companion object {
        fun requireApprovedEndpoint(uri: URI) {
            require(uri.toString().all { it.code in 0x21..0x7e } && uri.isAbsolute && uri.scheme == "https" &&
                uri.host?.lowercase(java.util.Locale.US) == "ingest.elu.dev" && uri.rawPath == "/v2/replay" &&
                uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port == 443)) { "Untrusted replay endpoint" }
            uri.rawQuery?.split('&')?.forEach { part ->
                require(URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name()) != "site_key") { "Reserved replay authorization query" }
            }
        }
    }
}
