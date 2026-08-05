package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.SessionState
import dev.elu.analytics.internal.core.UuidCoreIdentifierGenerator
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

internal data class RuntimeQueueLimits(
    val maximumCount: Int,
    val maximumBytes: Long,
) {
    init {
        require(maximumCount in 1..MAX_RUNTIME_QUEUE_RECORDS) {
            "maximumCount must be in 1..$MAX_RUNTIME_QUEUE_RECORDS"
        }
        require(maximumBytes in 1..MAX_RUNTIME_QUEUE_BYTES) {
            "maximumBytes must be in 1..$MAX_RUNTIME_QUEUE_BYTES"
        }
    }
}

internal data class RuntimeQueueSnapshot(
    val state: PersistedCoreState,
    val queuedCount: Int,
    val queuedBytes: Long,
    /** The first queued sequence, or nextSequence when the queue is empty. */
    val headSequence: Long,
)

internal enum class RuntimeAppendRejection {
    COUNT_LIMIT,
    BYTE_LIMIT,
    RECORD_TOO_LARGE,
}

internal sealed interface RuntimeAppendResult {
    data class Accepted(
        val records: List<RuntimeQueuedRecord>,
        val snapshot: RuntimeQueueSnapshot,
    ) : RuntimeAppendResult

    data class Rejected(
        val reason: RuntimeAppendRejection,
        val snapshot: RuntimeQueueSnapshot,
    ) : RuntimeAppendResult
}

internal sealed interface RuntimeAcknowledgementResult {
    val snapshot: RuntimeQueueSnapshot

    data class Deleted(
        val count: Int,
        override val snapshot: RuntimeQueueSnapshot,
    ) : RuntimeAcknowledgementResult

    data class AlreadyApplied(override val snapshot: RuntimeQueueSnapshot) : RuntimeAcknowledgementResult

    data class Empty(override val snapshot: RuntimeQueueSnapshot) : RuntimeAcknowledgementResult
}

internal class RuntimeAcknowledgementMismatchException(message: String) :
    IllegalArgumentException(message)

internal class RuntimeQueueHeadTooLargeException(
    val headBytes: Int,
    val maximumBytes: Long,
) : IllegalStateException(
        "Queued head requires $headBytes bytes but the bounded peek permits $maximumBytes bytes",
    )

/**
 * Installation-scoped serialized owner for state and ordered records.
 *
 * Every operation, including initialization and reopen, runs on [executor]. No public SDK facade
 * references this type.
 */
internal class RuntimeQueueOwner private constructor(
    private val ownershipKey: String,
    private val limits: RuntimeQueueLimits,
    private val databaseFactory: () -> RuntimeQueueDatabase,
    private val legacyStateLoader: () -> PersistedCoreState,
    private val identifiers: CoreIdentifierGenerator,
    private val executor: ExecutorService,
    private val workerThread: Thread,
    private val leaseFactory: () -> RuntimeOwnershipLease,
) {
    private var database: RuntimeQueueDatabase? = null
    private var lease: RuntimeOwnershipLease? = null
    private var loaded: LoadedSnapshot? = null
    private var poison: Throwable? = null
    private val lifecycleLock = Any()
    private var acceptingTasks: Boolean = true

    /** Blocking adapters must never wait for a task while already running on this worker. */
    fun isCurrentThreadWorker(): Boolean = Thread.currentThread() === workerThread

    fun snapshot(): Future<RuntimeQueueSnapshot> =
        submit {
            assertUsable()
            requireLoaded().publicSnapshot
        }

    fun appendEvents(
        sessionUpdate: RuntimeEventSessionUpdate,
        drafts: List<RuntimeRecordDraft.Event>,
    ): Future<RuntimeAppendResult> {
        require(drafts.size in 1..MAX_RUNTIME_APPEND_RECORDS) {
            "Event append must contain 1..$MAX_RUNTIME_APPEND_RECORDS events"
        }
        return submit { appendOnWorker(AppendRequest.Events(sessionUpdate, drafts.toList())) }
    }

    fun appendMutations(drafts: List<RuntimeRecordDraft.Mutation>): Future<RuntimeAppendResult> {
        require(drafts.size in 1..MAX_RUNTIME_APPEND_RECORDS) {
            "Mutation append must contain 1..$MAX_RUNTIME_APPEND_RECORDS mutations"
        }
        return submit { appendOnWorker(AppendRequest.Mutations(drafts.toList())) }
    }

    fun applyLocal(change: RuntimeLocalStateChange): Future<RuntimeAppendResult> =
        submit { appendOnWorker(AppendRequest.Local(change)) }

    /** Bounded FIFO read. A first record larger than [maximumBytes] fails explicitly. */
    fun peek(
        maximumCount: Int,
        maximumBytes: Long,
    ): Future<List<RuntimeQueuedRecord>> {
        require(maximumCount > 0) { "maximumCount must be positive" }
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        return submit { peekOnWorker(maximumCount, maximumBytes) }
    }

    /** Deletes only an exact ordered prefix identified by sequence, kind, and immutable ID. */
    fun acknowledge(acknowledgement: RuntimeAcknowledgement): Future<RuntimeAcknowledgementResult> {
        val copy = acknowledgement.copy(references = acknowledgement.references.toList())
        validateAcknowledgement(copy)
        return submit { acknowledgeOnWorker(copy) }
    }

    fun closeAsync(): Future<Unit> {
        val future: Future<Unit>
        synchronized(lifecycleLock) {
            if (!acceptingTasks) throw IllegalStateException("Runtime queue owner is already closing")
            acceptingTasks = false
            future =
                executor.submit(
                    Callable {
                        assertWorkerThread()
                        try {
                            closeResources()
                        } finally {
                            synchronized(OWNERSHIP_KEYS) { OWNERSHIP_KEYS.remove(ownershipKey) }
                        }
                        Unit
                    },
                )
            executor.shutdown()
        }
        return future
    }

    private fun initialize() {
        assertWorkerThread()
        lease = leaseFactory()
        database = databaseFactory()
        val existing = database().transaction { transaction -> loadValidated(transaction, validatePayloads = true) }
        if (existing != null) {
            loaded = existing
            return
        }

        val imported = canonicalState(legacyStateLoader())
        validateStateInvariants(imported.first)
        val candidate =
            LoadedSnapshot(
                state = imported.first,
                stateJson = imported.second,
                queuedCount = 0,
                queuedBytes = 0,
                headSequence = imported.first.stream.nextSequence,
            )
        repeat(MAX_RECONCILIATION_ATTEMPTS) { attempt ->
            try {
                database().transaction { transaction ->
                    val raced = loadValidated(transaction, validatePayloads = true)
                    if (raced != null) {
                        loaded = raced
                    } else {
                        transaction.insertCore(candidate.storedCore())
                        loaded = candidate
                    }
                }
                return
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                val reopened = reopenValidated(ambiguous)
                if (reopened != null) {
                    loaded = reopened
                    return
                }
                if (attempt == MAX_RECONCILIATION_ATTEMPTS - 1) throw ambiguous
            }
        }
    }

    private fun appendOnWorker(request: AppendRequest): RuntimeAppendResult {
        assertUsable()
        if (request.recordCount > MAX_RUNTIME_QUEUE_RECORDS) {
            return RuntimeAppendResult.Rejected(RuntimeAppendRejection.COUNT_LIMIT, requireLoaded().publicSnapshot)
        }
        var prepared: PreparedAppend? = null
        var attempts = 0
        while (attempts < MAX_RECONCILIATION_ATTEMPTS) {
            try {
                val candidate = prepared
                if (candidate == null) {
                    val outcome =
                        database().transaction { transaction ->
                            val before = requireCurrent(transaction)
                            validateAppendBoundaries(transaction, before)
                            val created = prepareAppend(before, request)
                            if (created.rejection != null) {
                                AppendCommit(
                                    RuntimeAppendResult.Rejected(created.rejection, before.publicSnapshot),
                                    published = null,
                                )
                            } else {
                                prepared = created
                                commitPreparedAppend(transaction, created)
                                AppendCommit(
                                    RuntimeAppendResult.Accepted(created.publicRecords, created.after.publicSnapshot),
                                    published = created.after,
                                )
                            }
                        }
                    outcome.published?.let { loaded = it }
                    return outcome.result
                }
                database().transaction { transaction ->
                    val current = requireCurrent(transaction)
                    if (!viewsEqual(current, candidate.before)) {
                        corrupt("Runtime state changed before append reconciliation")
                    }
                    validateAppendBoundaries(transaction, current)
                    commitPreparedAppend(transaction, candidate)
                }
                loaded = candidate.after
                return RuntimeAppendResult.Accepted(candidate.publicRecords, candidate.after.publicSnapshot)
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                attempts += 1
                val candidate = prepared
                val reopened = reopenValidated(ambiguous)
                    ?: poisonAndThrow(RuntimeQueueCorruptionException("Runtime core disappeared after an ambiguous append", ambiguous))
                if (candidate != null && candidate.rejection == null) {
                    when {
                        viewsEqual(reopened, candidate.after) && recordsMatch(candidate.records) -> {
                            loaded = reopened
                            return RuntimeAppendResult.Accepted(candidate.publicRecords, reopened.publicSnapshot)
                        }
                        viewsEqual(reopened, candidate.before) -> {
                            if (attempts == MAX_RECONCILIATION_ATTEMPTS) throw ambiguous
                            prepared = candidate
                        }
                        else ->
                            poisonAndThrow(
                                RuntimeQueueCorruptionException(
                                    "Ambiguous append reopened to neither the before nor after state",
                                    ambiguous,
                                ),
                            )
                    }
                } else if (attempts == MAX_RECONCILIATION_ATTEMPTS) {
                    throw ambiguous
                }
            }
        }
        throw IllegalStateException("Append reconciliation attempts exhausted")
    }

    private fun prepareAppend(
        before: LoadedSnapshot,
        request: AppendRequest,
    ): PreparedAppend {
        val countAfter = before.queuedCount.toLong() + request.recordCount
        if (countAfter > limits.maximumCount) {
            return PreparedAppend.rejected(before, RuntimeAppendRejection.COUNT_LIMIT)
        }
        val nextSequence =
            try {
                Math.addExact(before.state.stream.nextSequence, request.recordCount.toLong())
            } catch (error: ArithmeticException) {
                throw IllegalStateException("Runtime sequence exhausted", error)
            }
        val records = ArrayList<RuntimeStoredRecord>(request.recordCount)
        var bytesAfter = before.queuedBytes
        var transitionedState = before.state

        fun addRecord(
            state: PersistedCoreState,
            sequence: Long,
            draft: RuntimeRecordDraft,
        ): PreparedAppend? {
            val record = encodeDraft(state, sequence, draft)
            if (record.internalPayload.size > MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES) {
                return PreparedAppend.rejected(before, RuntimeAppendRejection.RECORD_TOO_LARGE)
            }
            bytesAfter = Math.addExact(bytesAfter, record.accountedBytes.toLong())
            if (bytesAfter > limits.maximumBytes) {
                return PreparedAppend.rejected(before, RuntimeAppendRejection.BYTE_LIMIT)
            }
            records += record
            return null
        }

        when (request) {
            is AppendRequest.Events -> {
                transitionedState = applyEventSessionUpdate(before.state, request.sessionUpdate)
                val canonicalEventState = canonicalState(transitionedState).first
                validateStateInvariants(canonicalEventState)
                validateEventCausality(
                    lowerBound = before.state.identity.updatedAt,
                    state = canonicalEventState,
                    drafts = request.drafts,
                )
                request.drafts.forEachIndexed { index, draft ->
                    val sequence = Math.addExact(before.state.stream.nextSequence, index.toLong())
                    addRecord(canonicalEventState, sequence, draft)?.let { return it }
                }
                transitionedState = canonicalEventState
            }
            is AppendRequest.Mutations -> {
                request.drafts.forEachIndexed { index, draft ->
                    transitionedState = canonicalState(applyMutation(transitionedState, draft)).first
                    validateStateInvariants(transitionedState)
                    val sequence = Math.addExact(before.state.stream.nextSequence, index.toLong())
                    addRecord(transitionedState, sequence, draft)?.let { return it }
                }
            }
            is AppendRequest.Local -> {
                transitionedState = canonicalState(applyLocalChange(before.state, request.change)).first
                validateStateInvariants(transitionedState)
            }
        }

        val committedState =
            transitionedState.copy(
                stream = transitionedState.stream.copy(nextSequence = nextSequence),
            )
        val canonicalCommitted = canonicalState(committedState)
        val after =
            LoadedSnapshot(
                state = canonicalCommitted.first,
                stateJson = canonicalCommitted.second,
                queuedCount = countAfter.toInt(),
                queuedBytes = bytesAfter,
                headSequence = if (before.queuedCount == 0 && records.isNotEmpty()) records.first().sequence else before.headSequence,
            )
        return PreparedAppend(before, after, records, rejection = null)
    }

    private fun commitPreparedAppend(
        transaction: RuntimeQueueTransaction,
        candidate: PreparedAppend,
    ) {
        candidate.records.forEach { record ->
            if (transaction.readRecord(record.sequence) != null) {
                corrupt("Runtime append target sequence is already occupied")
            }
            transaction.insertRecord(record)
        }
        transaction.updateCore(candidate.after.storedCore())
    }

    private fun encodeDraft(
        state: PersistedCoreState,
        sequence: Long,
        draft: RuntimeRecordDraft,
    ): RuntimeStoredRecord {
        val identity = state.identity
        return when (draft) {
            is RuntimeRecordDraft.Event -> {
                val session = identity.session
                    ?: throw IllegalArgumentException("Events require a persisted post-transition session")
                if (draft.expectedSessionId != session.id) {
                    throw IllegalArgumentException(
                        "Event session expectation does not match the persisted post-transition session",
                    )
                }
                val event =
                    RuntimeEventRecord(
                        eventId = RuntimeRecordIdentity.recordId(state.stream.streamId, sequence, RuntimeRecordKind.EVENT),
                        streamId = state.stream.streamId,
                        sequence = sequence,
                        contextRevision = identity.contextRevision,
                        kind = draft.kind,
                        name = draft.name,
                        occurredAt = draft.occurredAt,
                        identity = RuntimeEventIdentity(identity.anonymousId, identity.userId, identity.revision),
                        sessionId = session.id,
                        properties = draft.properties,
                        groups = identity.groups,
                        versions = draft.versions,
                    )
                val payload = RuntimeRecordCodec.encodeEvent(event)
                val canonical = RuntimeRecordCodec.decodeEvent(payload)
                val accountedBytes = RuntimeRecordCodec.encodeBatchRecord(canonical).size
                RuntimeStoredRecord(
                    sequence,
                    state.stream.streamId,
                    RuntimeRecordKind.EVENT,
                    canonical.eventId,
                    payload,
                    accountedBytes,
                )
            }
            is RuntimeRecordDraft.Mutation -> {
                val envelope =
                    RuntimeMutationEnvelope(
                        streamId = state.stream.streamId,
                        versions = draft.versions,
                        mutation =
                            RuntimeMutationRecord(
                                mutationId =
                                    RuntimeRecordIdentity.recordId(
                                        state.stream.streamId,
                                        sequence,
                                        RuntimeRecordKind.MUTATION,
                                    ),
                                sequence = sequence,
                                contextRevision = identity.contextRevision,
                                occurredAt = draft.occurredAt,
                                subject =
                                    RuntimeMutationSubject(
                                        identity.anonymousId,
                                        identity.userId,
                                        identity.revision,
                                    ),
                                change = draft.change,
                            ),
                    )
                val payload = RuntimeRecordCodec.encodeMutation(envelope)
                val canonical = RuntimeRecordCodec.decodeMutation(payload)
                val accountedBytes = RuntimeRecordCodec.encodeBatchRecord(canonical).size
                RuntimeStoredRecord(
                    sequence,
                    state.stream.streamId,
                    RuntimeRecordKind.MUTATION,
                    canonical.mutation.mutationId,
                    payload,
                    accountedBytes,
                )
            }
        }
    }

    private fun peekOnWorker(
        maximumCount: Int,
        maximumBytes: Long,
    ): List<RuntimeQueuedRecord> {
        assertUsable()
        return database().transaction { transaction ->
            val current = requireCurrent(transaction)
            if (current.queuedCount == 0) return@transaction emptyList()
            val requested = minOf(maximumCount, current.queuedCount, MAX_RUNTIME_DELIVERY_RECORDS)
            val byteLimit = minOf(maximumBytes, MAX_RUNTIME_DELIVERY_BYTES)
            val out = ArrayList<RuntimeQueuedRecord>(requested)
            var expected = current.headSequence
            var bytes = 0L
            while (out.size < requested) {
                val row = transaction.readRecord(expected)
                    ?: corrupt("Queue prefix contains a sequence gap")
                val nextBytes = Math.addExact(bytes, row.accountedBytes.toLong())
                if (nextBytes > byteLimit) {
                    if (out.isEmpty()) {
                        throw RuntimeQueueHeadTooLargeException(row.accountedBytes, byteLimit)
                    }
                    break
                }
                out += validateStoredRecord(row, current.state.stream.streamId)
                bytes = nextBytes
                expected = Math.addExact(expected, 1L)
            }
            Collections.unmodifiableList(out)
        }
    }

    private fun acknowledgeOnWorker(acknowledgement: RuntimeAcknowledgement): RuntimeAcknowledgementResult {
        assertUsable()
        val references = acknowledgement.references
        var prepared: PreparedAcknowledgement? = null
        repeat(MAX_RECONCILIATION_ATTEMPTS) { attempt ->
            try {
                val outcome =
                    database().transaction { transaction ->
                        val before = requireCurrent(transaction)
                        if (acknowledgement.streamId != before.state.stream.streamId) {
                            throw RuntimeAcknowledgementMismatchException(
                                "Acknowledgement stream does not match this runtime namespace",
                            )
                        }
                        if (references.isEmpty()) {
                            return@transaction AcknowledgementCommit(
                                RuntimeAcknowledgementResult.Empty(before.publicSnapshot),
                                published = null,
                            )
                        }
                        val last = references.last().sequence
                        if (last < before.headSequence) {
                            return@transaction AcknowledgementCommit(
                                RuntimeAcknowledgementResult.AlreadyApplied(before.publicSnapshot),
                                published = null,
                            )
                        }
                        if (references.first().sequence < before.headSequence) {
                            throw RuntimeAcknowledgementMismatchException("Acknowledgement overlaps the current queue head")
                        }
                        if (references.first().sequence != before.headSequence) {
                            throw RuntimeAcknowledgementMismatchException("Acknowledgement does not begin at the queue head")
                        }
                        var removedBytes = 0L
                        references.forEach { reference ->
                            val row = transaction.readRecord(reference.sequence)
                                ?: throw RuntimeAcknowledgementMismatchException(
                                    "Acknowledgement extends beyond the queued prefix",
                                )
                            if (
                                row.sequence != reference.sequence || row.kind != reference.kind ||
                                row.recordId != reference.recordId
                            ) {
                                throw RuntimeAcknowledgementMismatchException(
                                    "Acknowledgement reference does not exactly match the queued prefix",
                                )
                            }
                            validateStoredRecord(row, before.state.stream.streamId)
                            removedBytes = Math.addExact(removedBytes, row.accountedBytes.toLong())
                            if (!transaction.deleteRecord(reference.sequence)) {
                                corrupt("Verified acknowledgement row disappeared during deletion")
                            }
                        }
                        val remainingCount = before.queuedCount - references.size
                        val remainingBytes = Math.subtractExact(before.queuedBytes, removedBytes)
                        val head = if (remainingCount == 0) before.state.stream.nextSequence else Math.addExact(last, 1L)
                        val after = before.copy(queuedCount = remainingCount, queuedBytes = remainingBytes, headSequence = head)
                        val candidate = PreparedAcknowledgement(before, after, references)
                        prepared = candidate
                        transaction.updateCore(after.storedCore())
                        AcknowledgementCommit(
                            RuntimeAcknowledgementResult.Deleted(references.size, after.publicSnapshot),
                            published = after,
                        )
                    }
                outcome.published?.let { loaded = it }
                return outcome.result
            } catch (ambiguous: AmbiguousRuntimeCommitException) {
                val candidate = prepared
                val reopened = reopenValidated(ambiguous)
                    ?: poisonAndThrow(RuntimeQueueCorruptionException("Runtime core disappeared after an ambiguous acknowledgement"))
                if (candidate == null) {
                    loaded = reopened
                    if (attempt == MAX_RECONCILIATION_ATTEMPTS - 1) throw ambiguous
                    return@repeat
                }
                when {
                    viewsEqual(reopened, candidate.after) -> {
                        loaded = reopened
                        return RuntimeAcknowledgementResult.Deleted(candidate.references.size, reopened.publicSnapshot)
                    }
                    viewsEqual(reopened, candidate.before) -> {
                        if (attempt == MAX_RECONCILIATION_ATTEMPTS - 1) throw ambiguous
                    }
                    else ->
                        poisonAndThrow(
                            RuntimeQueueCorruptionException(
                                "Ambiguous acknowledgement reopened to neither the before nor after state",
                                ambiguous,
                            ),
                        )
                }
            }
        }
        throw IllegalStateException("Acknowledgement reconciliation attempts exhausted")
    }

    private fun requireCurrent(transaction: RuntimeQueueTransaction): LoadedSnapshot {
        val disk = loadValidated(transaction, validatePayloads = false)
            ?: corrupt("Runtime core state is missing")
        val memory = requireLoaded()
        if (!viewsEqual(disk, memory)) corrupt("Runtime state changed outside its installation owner")
        return disk
    }

    private fun loadValidated(
        transaction: RuntimeQueueTransaction,
        validatePayloads: Boolean,
    ): LoadedSnapshot? {
        val core = transaction.readCore()
        if (core == null) {
            if (validatePayloads) {
                transaction.scanRecords { corrupt("Queue rows exist without core state") }
            }
            return null
        }
        if (core.queueCount !in 0..MAX_RUNTIME_QUEUE_RECORDS.toLong()) corrupt("Stored queue count is outside the supported range")
        if (core.queueBytes !in 0..MAX_RUNTIME_QUEUE_BYTES) corrupt("Stored queue bytes are outside the supported range")
        val state = CoreStateCodec.decode(core.stateJson)
        val canonicalState = CoreStateCodec.encode(state)
        if (!canonicalState.contentEquals(core.stateJson)) corrupt("Stored core state is not canonical")
        validateStateInvariants(state)
        val count = core.queueCount.toInt()
        if (core.queueCount > state.stream.nextSequence) {
            corrupt("Stored queue count exceeds the allocated sequence range")
        }
        val head = Math.subtractExact(state.stream.nextSequence, core.queueCount)
        val loaded = LoadedSnapshot(state, core.stateJson.copyOf(), count, core.queueBytes, head)
        if (validatePayloads) validateAllRecords(transaction, loaded)
        return loaded
    }

    private fun validateAllRecords(
        transaction: RuntimeQueueTransaction,
        snapshot: LoadedSnapshot,
    ) {
        var expected = snapshot.headSequence
        var observedCount = 0
        var observedBytes = 0L
        transaction.scanRecords { row ->
            if (observedCount == MAX_RUNTIME_QUEUE_RECORDS) corrupt("Queue exceeds the supported record count")
            if (row.sequence != expected) corrupt("Queue contains a sequence gap")
            validateStoredRecord(row, snapshot.state.stream.streamId)
            observedCount += 1
            observedBytes = Math.addExact(observedBytes, row.accountedBytes.toLong())
            if (observedBytes > MAX_RUNTIME_QUEUE_BYTES) corrupt("Queue exceeds the supported byte count")
            expected = Math.addExact(expected, 1L)
        }
        if (observedCount != snapshot.queuedCount || observedBytes != snapshot.queuedBytes) {
            corrupt("Stored queue counters do not match streamed queue rows")
        }
        if (expected != snapshot.state.stream.nextSequence) corrupt("Queue validation did not end at nextSequence")
    }

    private fun validateStoredRecord(
        row: RuntimeStoredRecord,
        expectedStreamId: String,
    ): RuntimeQueuedRecord {
        if (
            row.internalPayload.isEmpty() ||
            row.internalPayload.size > MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES ||
            row.accountedBytes <= 0
        ) {
            corrupt("Queue row byte accounting is invalid")
        }
        if (row.sequence < 0 || row.streamId != expectedStreamId) corrupt("Queue row stream metadata is invalid")
        val decoded = RuntimeRecordCodec.decodeQueued(row.kind, row.internalPayload, row.accountedBytes)
        if (
            decoded.sequence != row.sequence || decoded.recordId != row.recordId ||
            decoded.streamId != row.streamId || decoded.kind != row.kind
        ) {
            corrupt("Queue row metadata does not match its payload")
        }
        val expectedRecordId = RuntimeRecordIdentity.recordId(row.streamId, row.sequence, row.kind)
        if (row.recordId != expectedRecordId) corrupt("Queue row record identity is not stream/sequence derived")
        when (decoded) {
            is RuntimeQueuedRecord.Event -> {
                if (decoded.record.identity.revision > decoded.record.contextRevision) {
                    corrupt("Event identity revision exceeds its context revision")
                }
            }
            is RuntimeQueuedRecord.Mutation -> {
                if (decoded.envelope.mutation.subject.identityRevision > decoded.envelope.mutation.contextRevision) {
                    corrupt("Mutation identity revision exceeds its context revision")
                }
            }
        }
        val canonical =
            when (decoded) {
                is RuntimeQueuedRecord.Event -> RuntimeRecordCodec.encodeEvent(decoded.record)
                is RuntimeQueuedRecord.Mutation -> RuntimeRecordCodec.encodeMutation(decoded.envelope)
            }
        if (!canonical.contentEquals(row.internalPayload)) corrupt("Queue internal payload is not canonical")
        val accountedBytes = RuntimeRecordCodec.encodeBatchRecord(decoded).size
        if (accountedBytes != row.accountedBytes) {
            corrupt("Queue accounted bytes do not match the canonical V1BatchRecord")
        }
        return decoded
    }

    private fun recordsMatch(records: List<RuntimeStoredRecord>): Boolean {
        if (records.isEmpty()) return true
        return database().transaction { transaction ->
            records.all { expected ->
                transaction.readRecord(expected.sequence)?.let { stored ->
                    storedRecordsEqual(stored, expected)
                } == true
            }
        }
    }

    private fun validateAppendBoundaries(
        transaction: RuntimeQueueTransaction,
        snapshot: LoadedSnapshot,
    ) {
        val nextSequence = snapshot.state.stream.nextSequence
        if (transaction.readRecord(nextSequence) != null) {
            corrupt("Queue contains an unaccounted append-target row")
        }
        if (snapshot.queuedCount == 0) return
        val head = transaction.readRecord(snapshot.headSequence)
            ?: corrupt("Queue head is missing")
        validateStoredRecord(head, snapshot.state.stream.streamId)
        val tailSequence = Math.subtractExact(nextSequence, 1L)
        if (tailSequence != snapshot.headSequence) {
            val tail = transaction.readRecord(tailSequence)
                ?: corrupt("Queue tail is missing")
            validateStoredRecord(tail, snapshot.state.stream.streamId)
        }
    }

    private fun reopenValidated(cause: Throwable): LoadedSnapshot? {
        assertWorkerThread()
        try {
            database?.close()
        } catch (closeError: Throwable) {
            cause.addSuppressed(closeError)
        }
        database = null
        loaded = null
        return try {
            database = databaseFactory()
            database().transaction { transaction -> loadValidated(transaction, validatePayloads = true) }
                .also { reopened -> loaded = reopened }
        } catch (error: Throwable) {
            error.addSuppressed(cause)
            poisonAndThrow(error)
        }
    }

    private fun canonicalState(state: PersistedCoreState): Pair<PersistedCoreState, ByteArray> {
        val bytes = CoreStateCodec.encode(state)
        return CoreStateCodec.decode(bytes) to bytes
    }

    private fun applyEventSessionUpdate(
        state: PersistedCoreState,
        update: RuntimeEventSessionUpdate,
    ): PersistedCoreState =
        when (update) {
            RuntimeEventSessionUpdate.Preserve -> {
                val session = state.identity.session
                    ?: throw IllegalArgumentException("Events require a persisted session")
                validateEventSession(session, state.identity.updatedAt)
                state
            }
            is RuntimeEventSessionUpdate.Replace -> {
                validateSessionTransition(state, update)
                state.copy(
                    identity =
                        state.identity.copy(
                            session = update.session,
                            updatedAt = update.session.lastActivityAt,
                        ),
                )
            }
        }

    private fun applyMutation(
        state: PersistedCoreState,
        draft: RuntimeRecordDraft.Mutation,
    ): PersistedCoreState {
        val identity = state.identity
        requireTimestampNotBefore(draft.occurredAt, identity.updatedAt, "Mutation occurredAt")
        val nextContextRevision = increment(identity.contextRevision, "identity context revision")
        return when (val change = draft.change) {
            is RuntimeMutationChange.Identify -> {
                val identityChanged = identity.userId != change.userId
                state.copy(
                    identity =
                        identity.copy(
                            revision =
                                if (identityChanged) increment(identity.revision, "identity revision") else identity.revision,
                            contextRevision = nextContextRevision,
                            userId = change.userId,
                            updatedAt = draft.occurredAt,
                        ),
                    flagContext =
                        state.flagContext.copy(
                            personProperties =
                                applyProperties(
                                    state.flagContext.personProperties,
                                    change.set,
                                    change.setOnce,
                                    emptyList(),
                                ),
                        ),
                )
            }
            is RuntimeMutationChange.LinkAlias ->
                state.copy(
                    identity =
                        identity.copy(
                            contextRevision = nextContextRevision,
                            updatedAt = draft.occurredAt,
                        ),
                )
            is RuntimeMutationChange.SetPersonProperties ->
                state.copy(
                    identity =
                        identity.copy(
                            contextRevision = nextContextRevision,
                            updatedAt = draft.occurredAt,
                        ),
                    flagContext =
                        state.flagContext.copy(
                            personProperties =
                                applyProperties(
                                    state.flagContext.personProperties,
                                    change.set,
                                    change.setOnce,
                                    change.unset,
                                ),
                        ),
                )
            is RuntimeMutationChange.AssociateGroup -> {
                val previousKey = identity.groups[change.groupType]
                val groups = LinkedHashMap(identity.groups).apply { put(change.groupType, change.groupKey) }
                val groupProperties =
                    if (previousKey != null && previousKey != change.groupKey) {
                        LinkedHashMap(state.flagContext.groupProperties).apply { remove(change.groupType) }
                    } else {
                        state.flagContext.groupProperties
                    }
                state.copy(
                    identity =
                        identity.copy(
                            contextRevision = nextContextRevision,
                            groups = groups,
                            updatedAt = draft.occurredAt,
                        ),
                    flagContext = state.flagContext.copy(groupProperties = groupProperties),
                )
            }
            is RuntimeMutationChange.SetGroupProperties -> {
                require(identity.groups[change.groupType] == change.groupKey) {
                    "Group properties mutation must match the persisted group association"
                }
                val properties =
                    applyProperties(
                        state.flagContext.groupProperties[change.groupType].orEmpty(),
                        change.set,
                        change.setOnce,
                        change.unset,
                    )
                val groupProperties =
                    LinkedHashMap(state.flagContext.groupProperties).apply {
                        put(change.groupType, properties)
                    }
                state.copy(
                    identity =
                        identity.copy(
                            contextRevision = nextContextRevision,
                            updatedAt = draft.occurredAt,
                        ),
                    flagContext = state.flagContext.copy(groupProperties = groupProperties),
                )
            }
        }
    }

    private fun applyLocalChange(
        state: PersistedCoreState,
        change: RuntimeLocalStateChange,
    ): PersistedCoreState =
        when (change) {
            is RuntimeLocalStateChange.SetOptedOut ->
                state.copy(
                    identity =
                        state.identity.copy(
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            optedOut = change.optedOut,
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                )
            is RuntimeLocalStateChange.ResetGroups ->
                state.copy(
                    identity =
                        state.identity.copy(
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            groups = emptyMap(),
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                    flagContext = state.flagContext.copy(groupProperties = emptyMap()),
                )
            is RuntimeLocalStateChange.ResetIdentity ->
                state.copy(
                    identity =
                        state.identity.copy(
                            revision = increment(state.identity.revision, "identity revision"),
                            contextRevision = increment(state.identity.contextRevision, "identity context revision"),
                            anonymousId = nextAnonymousId(state.identity.anonymousId),
                            userId = null,
                            groups = emptyMap(),
                            superProperties = emptyMap(),
                            session = null,
                            updatedAt = checkedLocalTimestamp(state, change),
                        ),
                    flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
                )
        }

    private fun checkedLocalTimestamp(
        state: PersistedCoreState,
        change: RuntimeLocalStateChange,
    ): String {
        requireTimestampNotBefore(change.occurredAt, state.identity.updatedAt, "Local change occurredAt")
        return change.occurredAt
    }

    private fun validateSessionTransition(
        state: PersistedCoreState,
        update: RuntimeEventSessionUpdate.Replace,
    ) {
        val current = state.identity.session
        require(current?.id == update.expectedCurrentSessionId) {
            "Persisted current session no longer matches the replacement expectation"
        }
        val session = update.session
        validateEventSession(session, session.lastActivityAt)
        requireTimestampNotBefore(session.lastActivityAt, state.identity.updatedAt, "Session lastActivityAt")
        current?.let {
            validateStoredSession(current, state.identity.updatedAt)
            if (current.id == session.id) {
                require(session.startedAt == current.startedAt) {
                    "An existing session may not change its start timestamp"
                }
                requireTimestampNotBefore(
                    session.lastActivityAt,
                    current.lastActivityAt,
                    "Session lastActivityAt",
                )
                val effectiveTimeoutSeconds = minOf(current.timeoutSeconds, session.timeoutSeconds)
                require(
                    RuntimeRecordCodec.compareElapsedSeconds(
                        session.lastActivityAt,
                        current.lastActivityAt,
                        effectiveTimeoutSeconds,
                    ) < 0,
                ) { "An expired session may not be revived by replacement" }
            } else {
                val currentBoundary = current.backgroundedAt ?: current.lastActivityAt
                requireTimestampNotBefore(session.startedAt, currentBoundary, "Replacement session startedAt")
            }
        }
    }

    private fun validateEventSession(
        session: SessionState,
        identityUpdatedAt: String,
    ) {
        validateStoredSession(session, identityUpdatedAt)
        require(session.lifecycle == SessionLifecycle.ACTIVE) {
            "Event session replacement must be active"
        }
        require(session.backgroundedAt == null) {
            "An active event session may not retain a background timestamp"
        }
    }

    private fun validateStoredSession(
        session: SessionState,
        identityUpdatedAt: String,
    ) {
        requireTimestampNotBefore(session.lastActivityAt, session.startedAt, "Session lastActivityAt")
        requireTimestampNotBefore(identityUpdatedAt, session.lastActivityAt, "Identity updatedAt")
        require(
            RuntimeRecordCodec.compareElapsedSeconds(
                session.lastActivityAt,
                session.startedAt,
                session.maximumDurationSeconds,
            ) < 0,
        ) { "Session exceeds its maximum duration" }
        when (session.lifecycle) {
            SessionLifecycle.ACTIVE ->
                require(session.backgroundedAt == null) {
                    "An active session may not retain a background timestamp"
                }
            SessionLifecycle.BACKGROUND -> {
                val backgroundedAt =
                    requireNotNull(session.backgroundedAt) {
                        "A background session requires a background timestamp"
                    }
                requireTimestampNotBefore(backgroundedAt, session.lastActivityAt, "Session backgroundedAt")
                requireTimestampNotBefore(identityUpdatedAt, backgroundedAt, "Identity updatedAt")
            }
        }
    }

    private fun validateEventCausality(
        lowerBound: String,
        state: PersistedCoreState,
        drafts: List<RuntimeRecordDraft.Event>,
    ) {
        val session = state.identity.session
            ?: throw IllegalArgumentException("Events require a persisted post-transition session")
        var previousOccurredAt = lowerBound
        drafts.forEach { draft ->
            requireTimestampNotBefore(draft.occurredAt, previousOccurredAt, "Event occurredAt")
            requireTimestampNotBefore(
                session.lastActivityAt,
                draft.occurredAt,
                "Session lastActivityAt",
            )
            previousOccurredAt = draft.occurredAt
        }
    }

    private fun requireTimestampNotBefore(
        candidate: String,
        current: String,
        label: String,
    ) {
        require(RuntimeRecordCodec.compareTimestamps(candidate, current) >= 0) {
            "$label may not move persisted time backward"
        }
    }

    private fun applyProperties(
        current: Map<String, Any?>,
        set: Map<String, Any?>,
        setOnce: Map<String, Any?>,
        unset: List<String>,
    ): Map<String, Any?> =
        LinkedHashMap(current).apply {
            unset.forEach(::remove)
            setOnce.forEach { (key, value) -> if (!containsKey(key)) put(key, value) }
            putAll(set)
        }

    private fun nextAnonymousId(excluding: String): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = identifiers.next("anon_")
            val length = candidate.codePointCount(0, candidate.length)
            if (length in 1..256 && candidate != excluding) return candidate
        }
        throw IllegalStateException("Identifier generator could not rotate the anonymous ID")
    }

    private fun validateStateInvariants(state: PersistedCoreState) {
        if (state.identity.revision > state.identity.contextRevision) {
            corrupt("Persisted identity revision exceeds its context revision")
        }
    }

    private fun increment(
        value: Long,
        label: String,
    ): Long =
        try {
            Math.addExact(value, 1L)
        } catch (error: ArithmeticException) {
            throw IllegalStateException("$label exhausted", error)
        }

    private fun validateAcknowledgement(acknowledgement: RuntimeAcknowledgement) {
        val streamLength = acknowledgement.streamId.codePointCount(0, acknowledgement.streamId.length)
        require(streamLength in 1..256) { "Acknowledgement stream ID length must be in 1..256" }
        require(acknowledgement.references.size <= MAX_RUNTIME_DELIVERY_RECORDS) {
            "Acknowledgement exceeds the bounded delivery prefix"
        }
        validateReferences(acknowledgement.streamId, acknowledgement.references)
    }

    private fun validateReferences(
        streamId: String,
        references: List<RuntimeRecordReference>,
    ) {
        var previous: Long? = null
        references.forEach { reference ->
            require(reference.sequence >= 0) { "Acknowledgement sequence must be non-negative" }
            val length = reference.recordId.codePointCount(0, reference.recordId.length)
            require(length in 1..256) { "Acknowledgement record ID length must be in 1..256" }
            require(reference.recordId == RuntimeRecordIdentity.recordId(streamId, reference.sequence, reference.kind)) {
                "Acknowledgement record identity is not bound to its stream and sequence"
            }
            previous?.let { expected ->
                val next =
                    try {
                        Math.addExact(expected, 1L)
                    } catch (error: ArithmeticException) {
                        throw IllegalArgumentException("Acknowledgement sequence exhausted", error)
                    }
                require(reference.sequence == next) { "Acknowledgement references must be contiguous and ordered" }
            }
            previous = reference.sequence
        }
    }

    private fun viewsEqual(
        left: LoadedSnapshot,
        right: LoadedSnapshot,
    ): Boolean =
        left.queuedCount == right.queuedCount &&
            left.queuedBytes == right.queuedBytes &&
            left.headSequence == right.headSequence &&
            left.stateJson.contentEquals(right.stateJson)

    private fun storedRecordsEqual(
        left: RuntimeStoredRecord,
        right: RuntimeStoredRecord,
    ): Boolean =
        left.sequence == right.sequence && left.streamId == right.streamId && left.kind == right.kind &&
            left.recordId == right.recordId && left.accountedBytes == right.accountedBytes &&
            left.internalPayload.contentEquals(right.internalPayload)

    private fun assertUsable() {
        assertWorkerThread()
        poison?.let { throw IllegalStateException("Runtime queue owner is poisoned", it) }
        requireLoaded()
    }

    private fun assertWorkerThread() {
        check(Thread.currentThread() === workerThread) { "Runtime storage accessed outside its dedicated worker" }
    }

    private fun requireLoaded(): LoadedSnapshot = loaded ?: throw IllegalStateException("Runtime queue is not initialized")

    private fun database(): RuntimeQueueDatabase = database ?: throw IllegalStateException("Runtime database is not open")

    /** Mark unusable without closing SQLite while its transaction is still unwinding. */
    private fun corrupt(message: String): Nothing {
        val error = RuntimeQueueCorruptionException(message)
        poison = error
        throw error
    }

    private fun poisonAndThrow(error: Throwable): Nothing {
        poison = error
        try {
            database?.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        database = null
        loaded = null
        throw error
    }

    private fun closeResources() {
        try {
            database?.close()
        } finally {
            database = null
            loaded = null
            lease?.close()
            lease = null
        }
    }

    private fun <T> submit(block: () -> T): Future<T> =
        synchronized(lifecycleLock) {
            if (!acceptingTasks) throw IllegalStateException("Runtime queue owner is closing")
            try {
                executor.submit(Callable { assertWorkerThread(); block() })
            } catch (error: RejectedExecutionException) {
                throw IllegalStateException("Runtime queue worker is unavailable", error)
            }
        }

    private sealed interface AppendRequest {
        val recordCount: Int

        data class Events(
            val sessionUpdate: RuntimeEventSessionUpdate,
            val drafts: List<RuntimeRecordDraft.Event>,
        ) : AppendRequest {
            override val recordCount: Int = drafts.size
        }

        data class Mutations(val drafts: List<RuntimeRecordDraft.Mutation>) : AppendRequest {
            override val recordCount: Int = drafts.size
        }

        data class Local(val change: RuntimeLocalStateChange) : AppendRequest {
            override val recordCount: Int = 0
        }
    }

    private data class LoadedSnapshot(
        val state: PersistedCoreState,
        val stateJson: ByteArray,
        val queuedCount: Int,
        val queuedBytes: Long,
        val headSequence: Long,
    ) {
        val publicSnapshot: RuntimeQueueSnapshot
            get() = RuntimeQueueSnapshot(state, queuedCount, queuedBytes, headSequence)

        fun storedCore(): RuntimeStoredCore = RuntimeStoredCore(stateJson.copyOf(), queuedCount.toLong(), queuedBytes)
    }

    private data class PreparedAppend(
        val before: LoadedSnapshot,
        val after: LoadedSnapshot,
        val records: List<RuntimeStoredRecord>,
        val rejection: RuntimeAppendRejection?,
    ) {
        val publicRecords: List<RuntimeQueuedRecord>
            get() =
                Collections.unmodifiableList(
                    records.map { row ->
                        RuntimeRecordCodec.decodeQueued(row.kind, row.internalPayload, row.accountedBytes)
                    },
                )

        companion object {
            fun rejected(
                before: LoadedSnapshot,
                rejection: RuntimeAppendRejection,
            ): PreparedAppend = PreparedAppend(before, before, emptyList(), rejection)
        }
    }

    private data class PreparedAcknowledgement(
        val before: LoadedSnapshot,
        val after: LoadedSnapshot,
        val references: List<RuntimeRecordReference>,
    )

    private data class AppendCommit(
        val result: RuntimeAppendResult,
        val published: LoadedSnapshot?,
    )

    private data class AcknowledgementCommit(
        val result: RuntimeAcknowledgementResult,
        val published: LoadedSnapshot?,
    )

    internal companion object {
        private val OWNERSHIP_KEYS = mutableSetOf<String>()
        private const val MAX_RECONCILIATION_ATTEMPTS = 3
        private const val MAX_ID_GENERATION_ATTEMPTS = 8

        /** Asynchronously opens an internal runtime on its dedicated storage worker. */
        internal fun open(
            ownershipKey: String,
            limits: RuntimeQueueLimits,
            databaseFactory: () -> RuntimeQueueDatabase,
            legacyStateLoader: () -> PersistedCoreState,
            identifiers: CoreIdentifierGenerator = UuidCoreIdentifierGenerator,
            leaseFactory: () -> RuntimeOwnershipLease = { RuntimeOwnershipLease { } },
        ): Future<RuntimeQueueOwner> {
            require(ownershipKey.isNotEmpty()) { "ownershipKey must not be empty" }
            lateinit var worker: Thread
            val executor =
                Executors.newSingleThreadExecutor(
                    ThreadFactory { runnable ->
                        Thread(runnable, "elu-runtime-storage").apply {
                            isDaemon = true
                            worker = this
                        }
                    },
                )
            return executor.submit(
                Callable {
                    var claimedOwnership = false
                    var owner: RuntimeQueueOwner? = null
                    try {
                        synchronized(OWNERSHIP_KEYS) {
                            if (!OWNERSHIP_KEYS.add(ownershipKey)) {
                                throw RuntimeQueueOwnershipException(
                                    "A runtime queue owner already holds this installation namespace",
                                )
                            }
                            claimedOwnership = true
                        }
                        owner =
                            RuntimeQueueOwner(
                                ownershipKey,
                                limits,
                                databaseFactory,
                                legacyStateLoader,
                                identifiers,
                                executor,
                                worker,
                                leaseFactory,
                            )
                        owner.initialize()
                        owner
                    } catch (error: Throwable) {
                        try {
                            owner?.closeResources()
                        } catch (closeError: Throwable) {
                            error.addSuppressed(closeError)
                        } finally {
                            if (claimedOwnership) {
                                synchronized(OWNERSHIP_KEYS) { OWNERSHIP_KEYS.remove(ownershipKey) }
                            }
                            executor.shutdown()
                        }
                        throw error
                    }
                },
            )
        }

        internal fun clearOwnershipForTesting() {
            synchronized(OWNERSHIP_KEYS) { OWNERSHIP_KEYS.clear() }
        }
    }
}
