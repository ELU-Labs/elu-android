package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.replay.NativeReplayAccounting
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
    val replayRows: TreeMap<String, RuntimeReplayStoredRow> = TreeMap()
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
                RUNTIME_STORAGE_SCHEMA_VERSION, RUNTIME_DATABASE_SCHEMA_VERSION_WITH_REPLAY, RUNTIME_DATABASE_SCHEMA_VERSION_WITH_NATIVE_REPLAY -> {
                    check(initialAuthority.key == RUNTIME_FLAG_AUTHORITY_KEY)
                    backing.flagRows[initialAuthority.key] = initialAuthority.deepCopy()
                    backing.databaseSchemaVersion = when (backing.databaseSchemaVersion) { 1 -> 2; 5 -> 6; else -> 4 }
                    backing.advanceCommittedMutationGeneration()
                }
                RUNTIME_DATABASE_SCHEMA_VERSION_WITH_FLAGS, RUNTIME_DATABASE_SCHEMA_VERSION_WITH_FLAGS_AND_REPLAY, RUNTIME_DATABASE_SCHEMA_VERSION_WITH_FLAGS_AND_NATIVE_REPLAY -> Unit
                else -> throw UnsupportedRuntimeStorageSchemaException(backing.databaseSchemaVersion.toLong())
            }
        }

    override fun ensureReplaySchema(initialState: RuntimeReplayStoredRow) = synchronized(backing) {
        check(!closed)
        when (backing.databaseSchemaVersion) {
            1, 2 -> {
                check(initialState.key == "state")
                backing.replayRows[initialState.key] = initialState.deepCopy()
                backing.databaseSchemaVersion = if (backing.databaseSchemaVersion == 1) 3 else 4
                backing.advanceCommittedMutationGeneration()
            }
            3, 4, 5, 6 -> Unit
            else -> throw UnsupportedRuntimeStorageSchemaException(backing.databaseSchemaVersion.toLong())
        }
    }

    override fun ensureNativeReplaySchema(initialAuthority: RuntimeReplayStoredRow) {
        val initial = NativeReplayAccounting.read(initialAuthority)
        transaction { tx ->
            check(tx.replaySchemaPresent())
            if (tx.nativeReplaySchemaPresent()) {
                val current = NativeReplayAccounting.read(checkNotNull(tx.readReplayRow(NativeReplayAccounting.KEY)))
                check(current.namespaceHash == initial.namespaceHash && current.streamId == initial.streamId)
            } else {
                check(tx.readReplayRow(NativeReplayAccounting.KEY) == null)
                check(CoreStateCodec.decode(checkNotNull(tx.readCore()).stateJson).stream.streamId == initial.streamId)
                tx.putReplayRow(initialAuthority)
                (tx as FakeTransaction).schemaVersion = if (tx.schemaVersion == 3) 5 else 6
            }
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
            val originalReplayRows = TreeMap<String, RuntimeReplayStoredRow>()
            backing.replayRows.forEach { (key, row) -> originalReplayRows[key] = row.deepCopy() }
            val workingReplayRows = TreeMap<String, RuntimeReplayStoredRow>()
            originalReplayRows.forEach { (key, row) -> workingReplayRows[key] = row.deepCopy() }
            val transaction = FakeTransaction(backing, workingCore, workingRecords, workingFlagRows, workingReplayRows)
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
                    backing.databaseSchemaVersion = transaction.schemaVersion
                    backing.core = transaction.core?.copy(stateJson = transaction.core!!.stateJson.copyOf())
                    backing.records.clear()
                    transaction.records.forEach { (sequence, row) -> backing.records[sequence] = row.deepCopy() }
                    backing.flagRows.clear()
                    transaction.flagRows.forEach { (key, row) -> backing.flagRows[key] = row.deepCopy() }
                    backing.replayRows.clear()
                    transaction.replayRows.forEach { (key, row) -> backing.replayRows[key] = row.deepCopy() }
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
                    backing.replayRows.clear()
                    originalReplayRows.forEach { (key, row) -> backing.replayRows[key] = row.deepCopy() }
                    backing.advanceCommittedMutationGeneration()
                    throw AmbiguousRuntimeCommitException("Fake diverged at an ambiguous result")
                }
                null -> {
                    backing.databaseSchemaVersion = transaction.schemaVersion
                    backing.core = transaction.core?.copy(stateJson = transaction.core!!.stateJson.copyOf())
                    backing.records.clear()
                    transaction.records.forEach { (sequence, row) -> backing.records[sequence] = row.deepCopy() }
                    backing.flagRows.clear()
                    transaction.flagRows.forEach { (key, row) -> backing.flagRows[key] = row.deepCopy() }
                    backing.replayRows.clear()
                    transaction.replayRows.forEach { (key, row) -> backing.replayRows[key] = row.deepCopy() }
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
        val replayRows: TreeMap<String, RuntimeReplayStoredRow>,
    ) : RuntimeQueueTransaction {
        var schemaVersion: Int = backing.databaseSchemaVersion
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

        override fun replaySchemaPresent(): Boolean = schemaVersion in setOf(3, 4, 5, 6)
        override fun nativeReplaySchemaPresent(): Boolean = schemaVersion in setOf(5, 6)
        private fun requireReplaySchema() { check(replaySchemaPresent()) }

        override fun readReplayRow(key: String): RuntimeReplayStoredRow? {
            requireReplaySchema()
            return replayRows[key]?.deepCopy()
        }

        override fun scanReplayRows(prefix: String, visitor: (RuntimeReplayStoredRow) -> Unit) {
            requireReplaySchema()
            replayRows.values.filter { it.key.startsWith(prefix) }.forEach { visitor(it.deepCopy()) }
        }

        override fun putReplayRow(row: RuntimeReplayStoredRow) {
            requireReplaySchema()
            replayRows[row.key] = row.deepCopy()
            mutated = true
        }

        override fun deleteReplayRow(key: String): Boolean {
            requireReplaySchema()
            val removed = replayRows.remove(key) != null
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
            check(schemaVersion in setOf(2, 4, 6)) {
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

private fun RuntimeReplayStoredRow.deepCopy(): RuntimeReplayStoredRow = copy(payload = payload.copyOf())
