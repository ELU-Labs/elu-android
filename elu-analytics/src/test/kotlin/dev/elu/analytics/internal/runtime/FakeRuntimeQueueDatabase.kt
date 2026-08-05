package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.CoreStateCodec
import java.util.Collections
import java.util.TreeMap

internal enum class FakeAmbiguousOutcome {
    COMMIT,
    ROLLBACK,
    DIVERGE,
}

internal class FakeRuntimeQueueBacking {
    var core: RuntimeStoredCore? = null
    val records: TreeMap<Long, RuntimeStoredRecord> = TreeMap()
    var databaseSchemaVersion: Int = RUNTIME_STORAGE_SCHEMA_VERSION
    val flagRows: TreeMap<String, RuntimeFlagStoredRow> = TreeMap()
    var failNextKnownCommit: Throwable? = null
    var failNextCoreRead: Throwable? = null
    var ambiguousNextCommit: FakeAmbiguousOutcome? = null
    var ambiguousNextReadOnlyTransaction: Boolean = false
    var scanCalls: Int = 0
    var connectionCalls: Int = 0
    var mutatedTransactionAttempts: Int = 0
    /** Logical storage generation: one step per durably committed mutated transaction. */
    var committedMutationGeneration: Long = 0L
    val transactionThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    fun connection(): RuntimeQueueDatabase {
        connectionCalls += 1
        return FakeRuntimeQueueDatabase(this)
    }
}

private class FakeRuntimeQueueDatabase(
    private val backing: FakeRuntimeQueueBacking,
) : RuntimeQueueDatabase {
    private var closed = false

    override fun ensureFlagSchema(initialAuthority: RuntimeFlagStoredRow) =
        synchronized(backing) {
            check(!closed) { "Fake database is closed" }
            when (backing.databaseSchemaVersion) {
                RUNTIME_STORAGE_SCHEMA_VERSION -> {
                    check(initialAuthority.key == RUNTIME_FLAG_AUTHORITY_KEY)
                    backing.flagRows[initialAuthority.key] = initialAuthority.deepCopy()
                    backing.databaseSchemaVersion = RUNTIME_DATABASE_SCHEMA_VERSION_WITH_FLAGS
                    backing.advanceCommittedMutationGeneration()
                }
                RUNTIME_DATABASE_SCHEMA_VERSION_WITH_FLAGS -> Unit
                else -> throw UnsupportedRuntimeStorageSchemaException(backing.databaseSchemaVersion.toLong())
            }
        }

    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T =
        synchronized(backing) {
            check(!closed) { "Fake database is closed" }
            backing.transactionThreads += Thread.currentThread()
            val workingCore = backing.core?.copy(stateJson = backing.core!!.stateJson.copyOf())
            val originalRecords = TreeMap<Long, RuntimeStoredRecord>()
            backing.records.forEach { (sequence, row) -> originalRecords[sequence] = row.deepCopy() }
            val workingRecords = TreeMap<Long, RuntimeStoredRecord>()
            originalRecords.forEach { (sequence, row) -> workingRecords[sequence] = row.deepCopy() }
            val originalFlagRows = TreeMap<String, RuntimeFlagStoredRow>()
            backing.flagRows.forEach { (key, row) -> originalFlagRows[key] = row.deepCopy() }
            val workingFlagRows = TreeMap<String, RuntimeFlagStoredRow>()
            originalFlagRows.forEach { (key, row) -> workingFlagRows[key] = row.deepCopy() }
            val transaction = FakeTransaction(backing, workingCore, workingRecords, workingFlagRows)
            val result = block(transaction)
            if (!transaction.mutated) {
                if (backing.ambiguousNextReadOnlyTransaction) {
                    backing.ambiguousNextReadOnlyTransaction = false
                    throw AmbiguousRuntimeCommitException("Fake read-only transaction reported an ambiguous result")
                }
                return@synchronized result
            }
            backing.mutatedTransactionAttempts += 1

            backing.failNextKnownCommit?.let { failure ->
                backing.failNextKnownCommit = null
                throw failure
            }
            when (backing.ambiguousNextCommit.also { backing.ambiguousNextCommit = null }) {
                FakeAmbiguousOutcome.COMMIT -> {
                    backing.core = transaction.core?.copy(stateJson = transaction.core!!.stateJson.copyOf())
                    backing.records.clear()
                    transaction.records.forEach { (sequence, row) -> backing.records[sequence] = row.deepCopy() }
                    backing.flagRows.clear()
                    transaction.flagRows.forEach { (key, row) -> backing.flagRows[key] = row.deepCopy() }
                    backing.advanceCommittedMutationGeneration()
                    throw AmbiguousRuntimeCommitException("Fake committed with an ambiguous result")
                }
                FakeAmbiguousOutcome.ROLLBACK ->
                    throw AmbiguousRuntimeCommitException("Fake rolled back with an ambiguous result")
                FakeAmbiguousOutcome.DIVERGE -> {
                    val beforeCore = checkNotNull(workingCore)
                    val beforeState = CoreStateCodec.decode(beforeCore.stateJson)
                    val divergentState =
                        beforeState.copy(
                            identity =
                                beforeState.identity.copy(
                                    contextRevision = Math.addExact(beforeState.identity.contextRevision, 1L),
                                ),
                        )
                    backing.core = beforeCore.copy(stateJson = CoreStateCodec.encode(divergentState))
                    backing.records.clear()
                    originalRecords.forEach { (sequence, row) -> backing.records[sequence] = row.deepCopy() }
                    backing.flagRows.clear()
                    originalFlagRows.forEach { (key, row) -> backing.flagRows[key] = row.deepCopy() }
                    backing.advanceCommittedMutationGeneration()
                    throw AmbiguousRuntimeCommitException("Fake diverged at an ambiguous result")
                }
                null -> {
                    backing.core = transaction.core?.copy(stateJson = transaction.core!!.stateJson.copyOf())
                    backing.records.clear()
                    transaction.records.forEach { (sequence, row) -> backing.records[sequence] = row.deepCopy() }
                    backing.flagRows.clear()
                    transaction.flagRows.forEach { (key, row) -> backing.flagRows[key] = row.deepCopy() }
                    backing.advanceCommittedMutationGeneration()
                    result
                }
            }
        }

    override fun close() {
        closed = true
    }

    private class FakeTransaction(
        private val backing: FakeRuntimeQueueBacking,
        var core: RuntimeStoredCore?,
        val records: TreeMap<Long, RuntimeStoredRecord>,
        val flagRows: TreeMap<String, RuntimeFlagStoredRow>,
    ) : RuntimeQueueTransaction {
        var mutated: Boolean = false
            private set

        override fun readCore(): RuntimeStoredCore? {
            backing.failNextCoreRead?.let { failure ->
                backing.failNextCoreRead = null
                throw failure
            }
            return core?.copy(stateJson = core!!.stateJson.copyOf())
        }

        override fun insertCore(core: RuntimeStoredCore) {
            check(this.core == null) { "Duplicate fake core row" }
            this.core = core.copy(stateJson = core.stateJson.copyOf())
            mutated = true
        }

        override fun updateCore(core: RuntimeStoredCore) {
            check(this.core != null) { "Missing fake core row" }
            this.core = core.copy(stateJson = core.stateJson.copyOf())
            mutated = true
        }

        override fun readRecord(sequence: Long): RuntimeStoredRecord? = records[sequence]?.deepCopy()

        override fun scanRecords(visitor: (RuntimeStoredRecord) -> Unit) {
            backing.scanCalls += 1
            records.values.forEach { record -> visitor(record.deepCopy()) }
        }

        override fun insertRecord(record: RuntimeStoredRecord) {
            check(!records.containsKey(record.sequence)) { "Duplicate fake sequence" }
            check(records.values.none { it.recordId == record.recordId }) { "Duplicate fake record ID" }
            records[record.sequence] = record.deepCopy()
            mutated = true
        }

        override fun deleteRecord(sequence: Long): Boolean {
            val removed = records.remove(sequence) != null
            if (removed) mutated = true
            return removed
        }

        override fun readFlagRow(key: String): RuntimeFlagStoredRow? {
            requireFlagSchema()
            return flagRows[key]?.deepCopy()
        }

        override fun scanFlagRows(prefix: String, visitor: (RuntimeFlagStoredRow) -> Unit) {
            requireFlagSchema()
            flagRows.values.filter { it.key.startsWith(prefix) }.forEach { visitor(it.deepCopy()) }
        }

        override fun putFlagRow(row: RuntimeFlagStoredRow) {
            requireFlagSchema()
            flagRows[row.key] = row.deepCopy()
            mutated = true
        }

        override fun deleteFlagRow(key: String): Boolean {
            requireFlagSchema()
            val removed = flagRows.remove(key) != null
            if (removed) mutated = true
            return removed
        }

        override fun invalidateCurrentFlagCache() {
            // The persisted context revision is the invalidation witness. Schema-aware cleanup is
            // intentionally deferred to FlagDurableStore so future envelopes remain byte-exact.
        }

        private fun requireFlagSchema() {
            check(backing.databaseSchemaVersion == RUNTIME_DATABASE_SCHEMA_VERSION_WITH_FLAGS) {
                "Fake flag schema has not been initialized"
            }
        }
    }
}

private fun FakeRuntimeQueueBacking.advanceCommittedMutationGeneration() {
    committedMutationGeneration = Math.addExact(committedMutationGeneration, 1L)
}

private fun RuntimeStoredRecord.deepCopy(): RuntimeStoredRecord =
    copy(internalPayload = internalPayload.copyOf())

private fun RuntimeFlagStoredRow.deepCopy(): RuntimeFlagStoredRow = copy(payload = payload.copyOf())
