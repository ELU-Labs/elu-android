package dev.elu.analytics.internal.runtime

import java.io.Closeable
import java.io.IOException

internal const val RUNTIME_STORAGE_SCHEMA_VERSION: Int = 1
internal const val MAX_RUNTIME_QUEUE_RECORDS: Int = 10_000
internal const val MAX_RUNTIME_QUEUE_BYTES: Long = 268_435_456L

/**
 * Android CursorWindow capacity is implementation-dependent and historically as small as 2 MiB.
 * Keeping one complete SQLite row at or below 1 MiB leaves room for metadata and makes every row
 * inserted by this SDK readable through the platform Cursor API. Larger contract-valid records
 * are rejected permanently before insertion.
 */
internal const val MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES: Int = 1_048_576
internal const val MAX_RUNTIME_APPEND_RECORDS: Int = 100
internal const val MAX_RUNTIME_DELIVERY_RECORDS: Int = 1_000
internal const val MAX_RUNTIME_DELIVERY_BYTES: Long = 10_485_760L

internal class RuntimeQueueCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class UnsupportedRuntimeStorageSchemaException(val foundVersion: Long) :
    IllegalStateException("Unsupported ELU runtime storage schema version: $foundVersion")

internal class RuntimeQueueOwnershipException(message: String) : IllegalStateException(message)

/** The database may have committed; the owner must discard memory and reopen before mutation. */
internal class AmbiguousRuntimeCommitException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** The storage transaction explicitly rolled back; capture may revalidate once before retrying. */
internal class ProvenNotCommittedRuntimeTransactionException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class RuntimeStoredCore(
    val stateJson: ByteArray,
    val queueCount: Long,
    val queueBytes: Long,
)

internal data class RuntimeStoredRecord(
    val sequence: Long,
    val streamId: String,
    val kind: RuntimeRecordKind,
    val recordId: String,
    /** Canonical internal event or one-mutation envelope, not the outbound batch wrapper. */
    val internalPayload: ByteArray,
    /** Exact UTF-8 bytes of the canonical `{kind,event|mutation}` V1BatchRecord. */
    val accountedBytes: Int,
)

/** Minimal transaction surface shared by the SQLite implementation and deterministic fake. */
internal interface RuntimeQueueTransaction {
    fun readCore(): RuntimeStoredCore?

    fun insertCore(core: RuntimeStoredCore)

    fun updateCore(core: RuntimeStoredCore)

    /** Exact primary-key lookup used by append, peek, and acknowledgement operations. */
    fun readRecord(sequence: Long): RuntimeStoredRecord?

    /** Startup/reopen integrity pass. Implementations must retain only one row at a time. */
    fun scanRecords(visitor: (RuntimeStoredRecord) -> Unit)

    fun insertRecord(record: RuntimeStoredRecord)

    fun deleteRecord(sequence: Long): Boolean
}

internal interface RuntimeQueueDatabase : Closeable {
    /**
     * Executes [block] in a full synchronous transaction. A known pre-commit failure rolls back;
     * a mutated transaction with an explicitly confirmed rollback throws
     * [ProvenNotCommittedRuntimeTransactionException], and an uncertain commit boundary throws
     * [AmbiguousRuntimeCommitException].
     */
    fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T
}

internal fun interface RuntimeOwnershipLease : Closeable {
    override fun close()
}
