package dev.elu.analytics.internal.config

/** A source-token handoff, not a second configuration cache or clock authority. */
internal class V2ConfigAuthorityGate {
    @Volatile private var current: V2ConfigLifecycleUpdate? = null
    @Volatile private var closed = false

    /** Called synchronously from the lifecycle notification, before downstream work is queued. */
    fun update(token: V2ConfigLifecycleUpdate) {
        if (!closed) current = token
    }

    fun snapshot(): V2ConfigAuthorityWitness? {
        if (closed) return null
        val token = current ?: return null
        var witness: V2ConfigAuthorityWitness? = null
        token.consume { body ->
            if (!closed && current === token) witness = V2ConfigAuthorityWitness(this, token, body)
        }
        return witness
    }

    fun snapshotFor(body: String?): V2ConfigAuthorityWitness? = snapshot()?.takeIf { it.body == body }

    fun hasDocument(): Boolean = snapshot()?.body != null

    fun close() { closed = true; current = null }

    internal fun matchesApplicationBackgrounded(withdrawal: V2ConfigLifecycleUpdate): Boolean =
        !closed && current === withdrawal

    internal fun consume(witness: V2ConfigAuthorityWitness, action: () -> Unit): Boolean {
        if (closed || current !== witness.token) return false
        var consumed = false
        witness.token.consume { body ->
            if (!closed && current === witness.token && body == witness.body) {
                action()
                consumed = true
            }
        }
        return consumed
    }
}

/** Retain across asynchronous work, then consume at the final synchronous publication. */
internal class V2ConfigAuthorityWitness internal constructor(
    private val gate: V2ConfigAuthorityGate,
    internal val token: V2ConfigLifecycleUpdate,
    val body: String?,
) {
    /** Classification comes from the current opaque source update, never from null alone. */
    val isApplicationSuspended: Boolean
        get() = body == null && token.kind == V2ConfigLifecycleUpdateKind.APPLICATION_SUSPENSION

    /** No blocking work, SQLite access, or network I/O may run inside this action. */
    fun consume(action: () -> Unit): Boolean = gate.consume(this, action)

    fun isCurrent(): Boolean = consume { }

    internal fun matchesApplicationBackgrounded(original: V2ConfigLifecycleUpdate, withdrawal: V2ConfigLifecycleUpdate): Boolean =
        token === original && body != null && gate.matchesApplicationBackgrounded(withdrawal)
}
