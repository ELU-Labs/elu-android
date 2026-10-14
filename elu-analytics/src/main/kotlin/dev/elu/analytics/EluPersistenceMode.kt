package dev.elu.analytics

/** Selects analytics storage. Explicit consent and namespace ownership remain durable in both modes. */
public enum class EluPersistenceMode {
    /** Retains identity, queued data, flags and replay in the owned SQLite store. */
    PERSISTENT,
    /** Keeps analytics in an actual memory database, discarded when this SDK owner closes. */
    MEMORY,
}
