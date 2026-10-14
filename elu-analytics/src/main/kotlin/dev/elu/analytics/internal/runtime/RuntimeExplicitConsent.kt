package dev.elu.analytics.internal.runtime

/** No analytics identifiers or chronology belong in this deliberately separate record. */
internal data class RuntimeExplicitConsent(
    val optedOut: Boolean,
    val settled: Boolean,
    val persistentReconciled: Boolean,
) {
    val deniesCollection: Boolean get() = optedOut || !settled

    fun encode(): ByteArray =
        "{\"schemaVersion\":1,\"optedOut\":$optedOut,\"settled\":$settled,\"persistentReconciled\":$persistentReconciled}"
            .toByteArray(Charsets.US_ASCII)

    companion object {
        const val MAX_BYTES = 128
        val PENDING_DENIAL = RuntimeExplicitConsent(true, false, false)

        fun decode(bytes: ByteArray): RuntimeExplicitConsent {
            require(bytes.size in 1..MAX_BYTES) { "Explicit consent size is invalid" }
            // A closed canonical encoding also refuses duplicates, coercions and unknown keys.
            for (denied in listOf(false, true)) for (settled in listOf(false, true)) for (reconciled in listOf(false, true)) {
                val candidate = RuntimeExplicitConsent(denied, settled, reconciled)
                if (candidate.encode().contentEquals(bytes)) return candidate
            }
            throw RuntimeQueueCorruptionException("Explicit consent record is malformed or unsupported")
        }
    }
}

/** All calls occur on the original storage lane after the shared namespace lease is acquired. */
internal interface RuntimeExplicitConsentStore {
    fun read(): RuntimeExplicitConsent?
    fun write(record: RuntimeExplicitConsent)
    /** Presence only. Memory startup must never open an old analytics store to infer a grant. */
    fun priorAnalyticsPresent(): Boolean
}
