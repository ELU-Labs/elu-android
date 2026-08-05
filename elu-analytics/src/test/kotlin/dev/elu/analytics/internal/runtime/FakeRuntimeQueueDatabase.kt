package dev.elu.analytics.internal.runtime

import java.util.Collections
import java.util.TreeMap

internal enum class FakeAmbiguousOutcome {
    COMMIT,
    ROLLBACK,
}

internal class FakeRuntimeQueueBacking {
    var core: RuntimeStoredCore? = null
    val records: TreeMap<Long, RuntimeStoredRecord> = TreeMap()
    var failNextKnownCommit: Throwable? = null
    var ambiguousNextCommit: FakeAmbiguousOutcome? = null
    var ambiguousNextReadOnlyTransaction: Boolean = false
    var scanCalls: Int = 0
    var connectionCalls: Int = 0
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

    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T =
        synchronized(backing) {
            check(!closed) { "Fake database is closed" }
            backing.transactionThreads += Thread.currentThread()
            val workingCore = backing.core?.copy(stateJson = backing.core!!.stateJson.copyOf())
            val workingRecords = TreeMap<Long, RuntimeStoredRecord>()
            backing.records.forEach { (sequence, row) -> workingRecords[sequence] = row.deepCopy() }
            val transaction = FakeTransaction(backing, workingCore, workingRecords)
            val result = block(transaction)
            if (!transaction.mutated) {
                if (backing.ambiguousNextReadOnlyTransaction) {
                    backing.ambiguousNextReadOnlyTransaction = false
                    throw AmbiguousRuntimeCommitException("Fake read-only transaction reported an ambiguous result")
                }
                return@synchronized result
            }

            backing.failNextKnownCommit?.let { failure ->
                backing.failNextKnownCommit = null
                throw failure
            }
            when (backing.ambiguousNextCommit.also { backing.ambiguousNextCommit = null }) {
                FakeAmbiguousOutcome.COMMIT -> {
                    backing.core = transaction.core?.copy(stateJson = transaction.core!!.stateJson.copyOf())
                    backing.records.clear()
                    transaction.records.forEach { (sequence, row) -> backing.records[sequence] = row.deepCopy() }
                    throw AmbiguousRuntimeCommitException("Fake committed with an ambiguous result")
                }
                FakeAmbiguousOutcome.ROLLBACK ->
                    throw AmbiguousRuntimeCommitException("Fake rolled back with an ambiguous result")
                null -> {
                    backing.core = transaction.core?.copy(stateJson = transaction.core!!.stateJson.copyOf())
                    backing.records.clear()
                    transaction.records.forEach { (sequence, row) -> backing.records[sequence] = row.deepCopy() }
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
    ) : RuntimeQueueTransaction {
        var mutated: Boolean = false
            private set

        override fun readCore(): RuntimeStoredCore? = core?.copy(stateJson = core!!.stateJson.copyOf())

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
    }
}

private fun RuntimeStoredRecord.deepCopy(): RuntimeStoredRecord =
    copy(internalPayload = internalPayload.copyOf())
