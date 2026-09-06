package dev.elu.analytics.internal.compat

/**
 * A legacy state source driven entirely from the test: every answer, every fault,
 * and the quarantine outcome are set up before the coordinator runs, and the source
 * records what was asked of it so a test can assert the prior state was never opened.
 */
internal class FakeLegacyStateSource(
    override val schemaId: String = "fake-legacy-v1",
) : LegacyStateSource {
    var probe: LegacyProbe = LegacyProbe.PRESENT
    var probeFailure: Throwable? = null
    var readFailure: Throwable? = null
    var quarantineOutcome: LegacyQuarantineOutcome = LegacyQuarantineOutcome.QUARANTINED
    var quarantineFailure: Throwable? = null
    var queueFailure: Throwable? = null
    var discardFailure: Throwable? = null

    val values: MutableMap<LegacyStateKey, LegacyValue> = LinkedHashMap()
    val queue: MutableList<LegacyQueuedRecord> = mutableListOf()

    var probeCalls: Int = 0
        private set
    var readCalls: Int = 0
        private set
    var quarantineCalls: Int = 0
        private set
    val readPages: MutableList<Pair<Int, Long>> = mutableListOf()
    val discarded: MutableList<String> = mutableListOf()

    fun text(
        key: LegacyStateKey,
        value: String,
    ) = apply { values[key] = LegacyValue.Text(value) }

    fun flag(
        key: LegacyStateKey,
        value: Boolean,
    ) = apply { values[key] = LegacyValue.Flag(value) }

    fun document(
        key: LegacyStateKey,
        value: Map<String, Any?>,
    ) = apply { values[key] = LegacyValue.Document(value) }

    override fun probe(): LegacyProbe {
        probeCalls += 1
        probeFailure?.let { throw it }
        return probe
    }

    override fun read(key: LegacyStateKey): LegacyValue {
        readCalls += 1
        readFailure?.let { throw it }
        return values[key] ?: LegacyValue.Absent
    }

    override fun queueSummary(): LegacyQueueSummary? {
        queueFailure?.let { throw it }
        if (queue.isEmpty()) return LegacyQueueSummary(0, 0)
        return LegacyQueueSummary(queue.size, queue.sumOf { record -> record.encodedBytes.toLong() })
    }

    override fun readQueuedRecords(
        maxCount: Int,
        maxBytes: Long,
    ): List<LegacyQueuedRecord> {
        readPages += maxCount to maxBytes
        queueFailure?.let { throw it }
        val page = mutableListOf<LegacyQueuedRecord>()
        var bytes = 0L
        for (record in queue) {
            if (page.size >= maxCount) break
            if (page.isNotEmpty() && bytes + record.encodedBytes > maxBytes) break
            page += record
            bytes += record.encodedBytes
        }
        return page
    }

    override fun discardQueuedRecords(ids: Collection<String>): Int {
        discardFailure?.let { throw it }
        discarded += ids
        val before = queue.size
        queue.removeAll { record -> record.id in ids }
        return before - queue.size
    }

    override fun quarantine(): LegacyQuarantineOutcome {
        quarantineCalls += 1
        quarantineFailure?.let { throw it }
        return quarantineOutcome
    }
}
