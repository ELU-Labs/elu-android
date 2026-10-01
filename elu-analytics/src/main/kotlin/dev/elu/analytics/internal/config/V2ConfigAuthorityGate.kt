package dev.elu.analytics.internal.config

/** A source-token handoff, not a second configuration cache or clock authority. */
internal class V2ConfigAuthorityGate {
    @Volatile private var current: V2ConfigLifecycleUpdate? = null
    @Volatile private var closed = false
    private val denialLock = Any()
    private var pendingDenial: V2RasterDenialWitness? = null
    @Volatile private var originalRasterConflicts: V2RasterConflictChannel? = null

    /** Called synchronously from the lifecycle notification, before downstream work is queued. */
    fun update(token: V2ConfigLifecycleUpdate) {
        token.rasterConflictChannel?.let { channel ->
            synchronized(denialLock) {
                check(originalRasterConflicts == null || originalRasterConflicts === channel)
                originalRasterConflicts = channel
            }
            if (closed) channel.closeSource()
            rasterDenial()
        }
        if (!closed) current = token
    }

    /** No document, clock grant, or capture permission can be obtained from this value. */
    internal fun rasterDenial(): V2RasterDenialWitness? {
        val channel = originalRasterConflicts
        val receipt = channel?.receipt() // Never enter the source lock while holding denialLock.
        return synchronized(denialLock) {
            val old = pendingDenial
            if (channel != null && receipt != null && (old == null ||
                V1ConfigJson.parseExactTimestamp(receipt.issuedAt) > V1ConfigJson.parseExactTimestamp(old.receipt.issuedAt)))
                pendingDenial = V2RasterDenialWitness(this, channel, receipt)
            pendingDenial
        }
    }
    internal fun ownsDenial(value: V2RasterDenialWitness): Boolean = synchronized(denialLock) {
        value.belongsTo(this) && pendingDenial === value
    }
    internal fun acknowledgeDenial(value: V2RasterDenialWitness): Boolean {
        val accepted = synchronized(denialLock) {
            if (!value.belongsTo(this) || pendingDenial !== value) false else { pendingDenial = null; true }
        }
        // Never enter the source lock while holding denialLock.
        if (accepted) value.originalChannel.acknowledge(value.receipt)
        return accepted
    }

    fun snapshot(): V2ConfigAuthorityWitness? {
        if (closed) return null
        val token = current ?: return null
        var witness: V2ConfigAuthorityWitness? = null
        token.consumeLease { body, lease ->
            if (!closed && current === token &&
                (lease == null || (lease.body == body && lease.validReceiptBinding()))
            ) witness = V2ConfigAuthorityWitness(this, token, body, lease?.nativeV3, lease?.receiptBody)
        }
        return witness
    }

    fun snapshotFor(body: String?): V2ConfigAuthorityWitness? = snapshot()?.takeIf { it.body == body }

    fun hasDocument(): Boolean = snapshot()?.body != null

    fun close() {
        closed = true; current = null
        originalRasterConflicts?.closeSource()
        rasterDenial()
    }

    internal fun matchesApplicationBackgrounded(withdrawal: V2ConfigLifecycleUpdate): Boolean =
        !closed && current === withdrawal

    internal fun consume(witness: V2ConfigAuthorityWitness, action: () -> Unit): Boolean {
        if (closed || current !== witness.token) return false
        var consumed = false
        witness.token.consumeLease { body, lease ->
            if (!closed && current === witness.token && body == witness.body &&
                lease?.receiptBody == witness.receiptBody && lease?.nativeV3 === witness.nativeV3
            ) {
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
    /** Descriptive original receipt; use only through this witness's final consume fence. */
    val nativeV3: NativeV3ConfigParser.Parsed?,
    internal val receiptBody: String?,
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

/** Original gate-bound restriction. It deliberately has no consume/lease/document API. */
internal class V2RasterDenialWitness internal constructor(
    private val gate: V2ConfigAuthorityGate,
    internal val originalChannel: V2RasterConflictChannel,
    val receipt: V2RasterConflictReceipt,
) {
    internal fun belongsTo(value: V2ConfigAuthorityGate) = gate === value
}
