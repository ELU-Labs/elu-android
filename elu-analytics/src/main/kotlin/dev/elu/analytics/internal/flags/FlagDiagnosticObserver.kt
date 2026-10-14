package dev.elu.analytics.internal.flags

/** Private diagnostic vocabulary. No field accepts payload, identity, endpoint or error text. */
internal enum class FlagDiagnosticPhase {
    RELOAD_REQUESTED, RELOAD_RESULT, BEGIN_RESULT, PRE_SEND_RESULT, WORKER_OWNER_RESULT, CLIENT_TRANSPORT_SEND,
    BOUND_SEND, BOUND_REFUSAL, BOUND_SOURCE_BEFORE, BOUND_OWNER_AUTHORIZATION, BOUND_SOURCE_AFTER,
    HTTP_ADMISSION, HTTP_BODY_REFUSED, HTTP_SLOT_REFUSED, HTTP_QUEUED, HTTP_WORKER,
    HTTP_CLOCK, HTTP_CONNECTION_FACTORY, HTTP_DEADLINE, HTTP_HEADERS,
    HTTP_AUTHORIZATION_BEFORE, HTTP_OUTPUT_OPEN, HTTP_AUTHORIZATION_AFTER,
    HTTP_WRITE, HTTP_RESPONSE_STATUS, HTTP_RESPONSE_BODY, HTTP_CLEANUP, HTTP_FAILURE,
    HTTP_CANCELED, HTTP_COMPLETED,
}

internal enum class FlagDiagnosticResult {
    UPDATED, STALE, RESTRICTED, TERMINAL, BEGUN, CURRENT, EXCEPTION, ABSENT,
    CLIENT_CLOSED, STORAGE_UNAVAILABLE, CLOCK_UNAVAILABLE, AUTHORIZATION_UNAVAILABLE,
    IDENTIFIER_UNAVAILABLE, TRANSPORT_FAILURE, PROTOCOL_FAILURE, OTHER_FAILED,
    BOUND_CLOSED, CONFIG_UNAVAILABLE, CONFIG_WITHDRAWN, CONFIG_INVALID, REQUEST_UNBOUND,
    CONFIG_CHANGED, PHYSICAL_SETTLING,
}

internal enum class FlagDiagnosticFailure {
    TLS_HANDSHAKE, TLS, SOCKET_TIMEOUT, IO, SECURITY, ARGUMENT, EXECUTOR_REJECTED, OTHER,
}

internal data class FlagDiagnosticRecord(
    val phase: FlagDiagnosticPhase,
    val result: FlagDiagnosticResult? = null,
    val failure: FlagDiagnosticFailure? = null,
    val restriction: FlagRestrictionReason? = null,
    val allowed: Boolean? = null,
)

internal fun interface FlagDiagnosticObserver {
    fun observe(record: FlagDiagnosticRecord)
    companion object { val NONE = FlagDiagnosticObserver { } }
}

/** Observation is never an authority check and cannot replace an original result or exception. */
internal fun observeFlag(observer: FlagDiagnosticObserver, record: () -> FlagDiagnosticRecord) {
    if (observer === FlagDiagnosticObserver.NONE) return
    try { observer.observe(record()) } catch (_: Throwable) { }
}

internal fun flagDiagnosticFailure(error: Throwable?): FlagDiagnosticFailure? = when (error) {
    null -> null
    is javax.net.ssl.SSLHandshakeException -> FlagDiagnosticFailure.TLS_HANDSHAKE
    is javax.net.ssl.SSLException -> FlagDiagnosticFailure.TLS
    is java.net.SocketTimeoutException -> FlagDiagnosticFailure.SOCKET_TIMEOUT
    is java.io.IOException -> FlagDiagnosticFailure.IO
    is SecurityException -> FlagDiagnosticFailure.SECURITY
    is IllegalArgumentException -> FlagDiagnosticFailure.ARGUMENT
    is java.util.concurrent.RejectedExecutionException -> FlagDiagnosticFailure.EXECUTOR_REJECTED
    else -> FlagDiagnosticFailure.OTHER
}

internal fun flagDiagnosticResult(result: FlagReloadResult?, error: Throwable?): FlagDiagnosticResult = when {
    error != null -> FlagDiagnosticResult.EXCEPTION
    result is FlagReloadResult.Updated -> FlagDiagnosticResult.UPDATED
    result === FlagReloadResult.Stale -> FlagDiagnosticResult.STALE
    result is FlagReloadResult.Restricted -> FlagDiagnosticResult.RESTRICTED
    result === FlagReloadResult.Terminal -> FlagDiagnosticResult.TERMINAL
    result is FlagReloadResult.Failed -> when (result.reason) {
        "client-closed" -> FlagDiagnosticResult.CLIENT_CLOSED
        "storage-unavailable" -> FlagDiagnosticResult.STORAGE_UNAVAILABLE
        "clock-unavailable" -> FlagDiagnosticResult.CLOCK_UNAVAILABLE
        "authorization-unavailable" -> FlagDiagnosticResult.AUTHORIZATION_UNAVAILABLE
        "identifier-unavailable" -> FlagDiagnosticResult.IDENTIFIER_UNAVAILABLE
        "transport-failure" -> FlagDiagnosticResult.TRANSPORT_FAILURE
        "protocol-failure" -> FlagDiagnosticResult.PROTOCOL_FAILURE
        else -> FlagDiagnosticResult.OTHER_FAILED
    }
    else -> FlagDiagnosticResult.ABSENT
}

internal fun flagDiagnosticBoundRefusal(reason: String): FlagDiagnosticResult = when (reason) {
    "Flag transport is closed" -> FlagDiagnosticResult.BOUND_CLOSED
    "Configuration is unavailable" -> FlagDiagnosticResult.CONFIG_UNAVAILABLE
    "Configuration is withdrawn" -> FlagDiagnosticResult.CONFIG_WITHDRAWN
    "Configuration is invalid" -> FlagDiagnosticResult.CONFIG_INVALID
    "Flag request is unbound" -> FlagDiagnosticResult.REQUEST_UNBOUND
    "Flag request configuration changed" -> FlagDiagnosticResult.CONFIG_CHANGED
    "A physical flag request is settling" -> FlagDiagnosticResult.PHYSICAL_SETTLING
    else -> FlagDiagnosticResult.OTHER_FAILED
}

/** Saturating, serialized sink: failed writes consume their slot; no worker or clock is added. */
internal class BoundedFlagDiagnosticObserver(private val write: (ByteArray) -> Unit) : FlagDiagnosticObserver {
    private var admitted = 0
    @Synchronized override fun observe(record: FlagDiagnosticRecord) {
        if (admitted >= MAXIMUM_RECORDS) return
        val sequence = ++admitted
        try {
            val raw = with(record) {
                ("{\"sequence\":$sequence,\"phase\":\"$phase\",\"result\":${json(result)}," +
                    "\"failure\":${json(failure)},\"restriction\":${json(restriction)},\"allowed\":$allowed}\n")
                    .toByteArray(Charsets.UTF_8)
            }
            check(raw.size in 1..MAXIMUM_RECORD_BYTES)
            write(raw)
        } catch (_: Throwable) { }
    }

    private fun json(value: Enum<*>?): String = value?.let { "\"${it.name}\"" } ?: "null"

    companion object {
        const val MAXIMUM_RECORDS = 256
        const val MAXIMUM_RECORD_BYTES = 512
        const val MAXIMUM_BYTES = MAXIMUM_RECORDS * MAXIMUM_RECORD_BYTES
    }
}
