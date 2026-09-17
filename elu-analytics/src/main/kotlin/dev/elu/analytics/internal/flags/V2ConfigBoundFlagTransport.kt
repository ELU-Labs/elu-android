package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ConfigStatus
import dev.elu.analytics.internal.config.V2ConfigAuthorityGate
import dev.elu.analytics.internal.config.V2ConfigAuthorityWitness
import java.io.IOException
import java.net.URI
import dev.elu.analytics.internal.concurrent.SdkFuture
import dev.elu.analytics.internal.concurrent.SdkCompletionStage

/** Rotates endpoint bindings only after the previous physical HTTP slot has settled. */
internal class V2ConfigBoundFlagTransport(
    private val siteKey: String,
    private val gate: V2ConfigAuthorityGate,
    private val open: (String, URI) -> HttpURLConnectionFlagTransport,
    private val diagnostic: FlagDiagnosticObserver,
) : FlagTransport, AutoCloseable {
    // Preserve the original constructor and trailing-lambda call shape.
    constructor(siteKey: String, gate: V2ConfigAuthorityGate,
        open: (String, URI) -> HttpURLConnectionFlagTransport = { key, endpoint ->
            HttpURLConnectionFlagTransport(key, endpoint)
        },
    ) : this(siteKey, gate, open, FlagDiagnosticObserver.NONE)
    private val lock = Any()
    private var closed = false
    private var current: Binding? = null

    override fun send(request: FlagTransportRequest): SdkCompletionStage<ByteArray> = synchronized(lock) {
        observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.BOUND_SEND) }
        if (closed) return@synchronized failed("Flag transport is closed")
        val witness = gate.snapshot() ?: return@synchronized failed("Configuration is unavailable")
        val body = witness.body ?: return@synchronized failed("Configuration is withdrawn")
        val config = try { V1ConfigJson.parseConfig(body) } catch (_: Exception) {
            return@synchronized failed("Configuration is invalid")
        }
        val authorization = request.authorization?.witness ?: return@synchronized failed("Flag request is unbound")
        if (config.status != V1ConfigStatus.ENABLED || config.features?.flags != true ||
            config.configSemanticHash != authorization.configSemanticHash ||
            config.endpoints?.flags != request.endpoint.toString() || authorization.endpoint != request.endpoint
        ) return@synchronized failed("Flag request configuration changed")

        val previous = current
        if (previous != null && !previous.witness.isCurrent()) previous.pending?.cancel(false)
        if (previous != null && !previous.transport.isIdle()) return@synchronized failed("A physical flag request is settling")
        val binding = if (previous != null && previous.witness.token === witness.token) previous else {
            previous?.transport?.close()
            Binding(witness, open(siteKey, request.endpoint)).also { current = it }
        }
        // The worker calls this after queueing and again after connection establishment. The
        // owner check is outside token consumption: no lifecycle lock spans storage or network.
        val guarded = request.copy(authorizeSend = {
            witness.isCurrent().also { allowed -> observeFlag(diagnostic) {
                FlagDiagnosticRecord(FlagDiagnosticPhase.BOUND_SOURCE_BEFORE, allowed = allowed) } } &&
                request.authorizeSend().also { allowed -> observeFlag(diagnostic) {
                    FlagDiagnosticRecord(FlagDiagnosticPhase.BOUND_OWNER_AUTHORIZATION, allowed = allowed) } } &&
                witness.isCurrent().also { allowed -> observeFlag(diagnostic) {
                    FlagDiagnosticRecord(FlagDiagnosticPhase.BOUND_SOURCE_AFTER, allowed = allowed) } }
        })
        binding.transport.send(guarded).also { binding.pending = it.toFuture() }
    }

    /** Run outside the lifecycle notification lock; the gate has already withdrawn synchronously. */
    fun retireSuperseded() = synchronized(lock) {
        current?.takeIf { !it.witness.isCurrent() }?.let { binding ->
            binding.pending?.cancel(false)
            if (binding.transport.isIdle()) {
                binding.transport.close()
                current = null
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        current?.let { it.pending?.cancel(false); it.transport.close() }
        current = null
    }

    private class Binding(val witness: V2ConfigAuthorityWitness, val transport: HttpURLConnectionFlagTransport) {
        var pending: SdkFuture<ByteArray>? = null
    }

    private fun failed(reason: String): SdkFuture<ByteArray> =
        SdkFuture<ByteArray>().apply {
            observeFlag(diagnostic) { FlagDiagnosticRecord(FlagDiagnosticPhase.BOUND_REFUSAL,
                result = flagDiagnosticBoundRefusal(reason)) }
            completeExceptionally(IOException(reason))
        }
}
