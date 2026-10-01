package dev.elu.analytics

import java.util.Date

/** A detached event presented to [EluOptions.beforeSend]. Return null to discard it. */
public class EluEvent @JvmOverloads constructor(
    public var event: String,
    public var properties: MutableMap<String, Any?>,
    public var timestamp: Date,
    public var set: MutableMap<String, Any?>? = null,
    public var setOnce: MutableMap<String, Any?>? = null,
) {
    /** Runs synchronously on the capture worker, outside storage transactions and SDK locks.
     * Keep this callback short; do not wait for SDK operations or perform UI/network work.
     * Returned values are copied and validated before storage. Throwing discards the event.
     */
    public fun interface Filter {
        public fun filter(event: EluEvent): EluEvent?
    }
}
