package dev.elu.analytics.internal.compat

import dev.elu.analytics.EluVersion
import dev.elu.analytics.internal.core.CORE_SCHEMA_VERSION
import dev.elu.analytics.internal.core.CoreEpochClock
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.CoreStateCorruptionException
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.INITIAL_SEQUENCE
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.JsonValues
import dev.elu.analytics.internal.core.MigrationState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.core.SystemCoreEpochClock
import dev.elu.analytics.internal.core.UuidCoreIdentifierGenerator
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeQueueOwnershipException
import dev.elu.analytics.internal.runtime.RuntimeWallTimestamps
import dev.elu.analytics.internal.runtime.UnsupportedRuntimeStorageSchemaException
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/** What the coordinator concluded about the prior state before deciding how to proceed. */
internal enum class LegacySourceCondition {
    /** The runtime already held committed state, so the prior state was never opened. */
    NOT_READ,
    ABSENT,
    SUPPORTED,
    UNSUPPORTED,
    FUTURE,
    CORRUPT,
    OVERSIZED,
    PERMISSION_DENIED,
    PARTIAL,
}

internal enum class LegacyRecoveryAction {
    /** Prior identity, context, and opt state become the runtime's first committed state. */
    IMPORT,

    /** A new anonymous identity is committed; the prior state is left untouched. */
    FRESH,

    /** A new anonymous identity is committed, then the prior state is moved aside byte for byte. */
    QUARANTINE_THEN_FRESH,
}

/** The fixed mapping from a prior-state condition to the action the coordinator takes. */
internal object LegacyRecoveryTable {
    fun actionFor(condition: LegacySourceCondition): LegacyRecoveryAction =
        when (condition) {
            LegacySourceCondition.SUPPORTED -> LegacyRecoveryAction.IMPORT
            LegacySourceCondition.ABSENT,
            LegacySourceCondition.NOT_READ,
            LegacySourceCondition.PERMISSION_DENIED,
            -> LegacyRecoveryAction.FRESH
            LegacySourceCondition.UNSUPPORTED,
            LegacySourceCondition.FUTURE,
            LegacySourceCondition.CORRUPT,
            LegacySourceCondition.OVERSIZED,
            LegacySourceCondition.PARTIAL,
            -> LegacyRecoveryAction.QUARANTINE_THEN_FRESH
        }

    /**
     * A fresh identity starts opted out unless the prior privacy choice was readable or no
     * prior state existed at all. Unreadable prior state may have carried an opt-out.
     */
    fun freshOptedOut(
        condition: LegacySourceCondition,
        readableOptedOut: Boolean?,
    ): Boolean = readableOptedOut ?: (condition != LegacySourceCondition.ABSENT)
}

internal enum class MigrationOutcome {
    IMPORTED,
    FRESH_START,

    /** The runtime already carried a migration witness. */
    ALREADY_COMPLETE,

    /** The runtime already carried state that never went through a migration. */
    NOT_REQUIRED,

    /** The runtime storage was written by a newer schema; nothing was read or written. */
    REFUSED_STORAGE_SCHEMA,

    /** Another owner holds the runtime storage; nothing was read or written. */
    REFUSED_OWNER_UNAVAILABLE,

    /** The committed state read back from storage did not match what was written. */
    READBACK_MISMATCH,
    FAILED,
}

/** Non-sensitive record of one migration attempt: enums, schema versions, and timing only. */
internal data class MigrationReport(
    val outcome: MigrationOutcome,
    val sourceCondition: LegacySourceCondition,
    val recoveryAction: LegacyRecoveryAction?,
    val sourceSchema: String?,
    val targetSchemaVersion: Int,
    val sdkVersion: String,
    val elapsedMillis: Long,
    /** Optional context keys that were unreadable and therefore not imported. */
    val droppedContext: Set<LegacyStateKey>,
    val quarantine: LegacyQuarantineOutcome?,
    val legacyQueue: LegacyQueueSummary?,
)

internal data class MigrationResult(
    /** The opened runtime owner, or null when capture must stay closed. */
    val owner: RuntimeQueueOwner?,
    val report: MigrationReport,
)

internal sealed interface LegacyQueuePolicy {
    /** Leaves every prior queued record where it is. */
    data object Retain : LegacyQueuePolicy

    /** Discards records that occurred before [cutoffEpochMillis]; undated records are kept. */
    data class Expire(
        val cutoffEpochMillis: Long,
        val maxRecords: Int = DEFAULT_MAX_DISPOSED_RECORDS,
    ) : LegacyQueuePolicy

    /** Hands records to [sink] and discards only the ones it durably accepted. */
    data class Drain(
        val sink: LegacyRecordSink,
        val maxRecords: Int = DEFAULT_MAX_DISPOSED_RECORDS,
        val maxBytes: Long = DEFAULT_MAX_DISPOSED_BYTES,
        val pageSize: Int = DEFAULT_DISPOSAL_PAGE_SIZE,
    ) : LegacyQueuePolicy

    companion object {
        const val DEFAULT_MAX_DISPOSED_RECORDS: Int = 1_000
        const val DEFAULT_MAX_DISPOSED_BYTES: Long = 10_485_760L
        const val DEFAULT_DISPOSAL_PAGE_SIZE: Int = 100
    }
}

internal enum class LegacyQueueStop {
    RETAINED,
    EXHAUSTED,
    LIMIT_REACHED,

    /** A page produced no accepted or expired records, so later pages would repeat it. */
    NO_PROGRESS,
    SOURCE_FAILURE,
}

/** Evidence of one explicit disposal pass over the prior queue: counts only. */
internal data class LegacyQueueDisposition(
    val examined: Int,
    val accepted: Int,
    val discarded: Int,
    val stop: LegacyQueueStop,
)

/**
 * Moves one installation from a prior on-device state format to the runtime owner's
 * storage.
 *
 * The owner is opened through [ownerOpener]; the coordinator supplies the loader the owner
 * calls only when its storage holds no committed state. That loader reads the prior state
 * once, applies [LegacyRecoveryTable], and returns the complete first state (identity,
 * context, opt state, stream metadata, and the migration witness), which the owner commits
 * in one transaction. The coordinator then closes the owner and reopens it with a loader
 * that refuses to run, so the state handed to the caller is the one read back from storage.
 *
 * A failure before the commit leaves the prior state authoritative and the next run reads
 * it again. A failure after the commit leaves the runtime state authoritative: the next run
 * finds it and never opens the prior state, so identity is never rotated twice. Quarantine
 * happens only after a successful readback, and prior queued records are touched only by an
 * explicit [disposeLegacyQueue] call.
 *
 * This runs blocking storage work and must be called from a background thread. Nothing in
 * the public facade constructs it.
 */
internal class MigrationCoordinator(
    private val source: LegacyStateSource,
    private val ownerOpener: (legacyStateLoader: () -> PersistedCoreState) -> Future<RuntimeQueueOwner>,
    private val identifiers: CoreIdentifierGenerator = UuidCoreIdentifierGenerator,
    private val clock: CoreEpochClock = SystemCoreEpochClock,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val sdkVersion: String = EluVersion.NAME,
) {
    private class Plan(
        val condition: LegacySourceCondition,
        val action: LegacyRecoveryAction,
        val state: PersistedCoreState,
        val droppedContext: Set<LegacyStateKey>,
    )

    private class OpenFailure(
        val outcome: MigrationOutcome,
        cause: Throwable,
    ) : RuntimeException(cause)

    fun run(): MigrationResult {
        val startedAt = monotonicNanos()
        var plan: Plan? = null
        val loader: () -> PersistedCoreState = {
            (plan ?: planFromSource().also { plan = it }).state
        }

        val opened =
            try {
                openOwner(loader)
            } catch (failure: OpenFailure) {
                return MigrationResult(null, report(failure.outcome, plan, startedAt, null))
            }

        val committedPlan = plan
        if (committedPlan == null) {
            val existing = snapshot(opened)
            val outcome =
                if (existing.identity.migration != null) {
                    MigrationOutcome.ALREADY_COMPLETE
                } else {
                    MigrationOutcome.NOT_REQUIRED
                }
            return MigrationResult(opened, report(outcome, null, startedAt, null))
        }

        // The owner committed the planned state. Hand out only what storage reads back.
        close(opened)
        val reopened =
            try {
                openOwner { throw IllegalStateException("Prior state must not be read after the migration commit") }
            } catch (failure: OpenFailure) {
                return MigrationResult(null, report(failure.outcome, committedPlan, startedAt, null))
            }
        val committed = snapshot(reopened)
        if (committed != canonical(committedPlan.state)) {
            close(reopened)
            return MigrationResult(null, report(MigrationOutcome.READBACK_MISMATCH, committedPlan, startedAt, null))
        }

        val quarantine =
            if (committedPlan.action == LegacyRecoveryAction.QUARANTINE_THEN_FRESH) quarantineSource() else null
        val outcome =
            if (committedPlan.action == LegacyRecoveryAction.IMPORT) {
                MigrationOutcome.IMPORTED
            } else {
                MigrationOutcome.FRESH_START
            }
        return MigrationResult(reopened, report(outcome, committedPlan, startedAt, quarantine))
    }

    /**
     * Disposes of prior queued records under one explicit policy and returns count-only
     * evidence. [run] never calls this.
     */
    fun disposeLegacyQueue(policy: LegacyQueuePolicy): LegacyQueueDisposition =
        when (policy) {
            LegacyQueuePolicy.Retain -> LegacyQueueDisposition(0, 0, 0, LegacyQueueStop.RETAINED)
            is LegacyQueuePolicy.Expire -> expire(policy)
            is LegacyQueuePolicy.Drain -> drain(policy)
        }

    private fun drain(policy: LegacyQueuePolicy.Drain): LegacyQueueDisposition {
        require(policy.pageSize in 1..policy.maxRecords) { "pageSize must be in 1..maxRecords" }
        var examined = 0
        var accepted = 0
        var discarded = 0
        var remainingBytes = policy.maxBytes
        while (examined < policy.maxRecords && remainingBytes > 0) {
            val pageSize = minOf(policy.pageSize, policy.maxRecords - examined)
            val page =
                try {
                    source.readQueuedRecords(pageSize, remainingBytes)
                } catch (_: Exception) {
                    return LegacyQueueDisposition(examined, accepted, discarded, LegacyQueueStop.SOURCE_FAILURE)
                }
            if (page.isEmpty()) return LegacyQueueDisposition(examined, accepted, discarded, LegacyQueueStop.EXHAUSTED)
            examined += page.size
            remainingBytes -= page.sumOf { record -> record.encodedBytes.toLong() }
            val acceptance = policy.sink.accept(Collections.unmodifiableList(page.toList()))
            val acceptedIds = page.map { record -> record.id }.filter { id -> id in acceptance.acceptedIds }
            accepted += acceptedIds.size
            if (acceptedIds.isNotEmpty()) {
                discarded +=
                    try {
                        source.discardQueuedRecords(acceptedIds)
                    } catch (_: Exception) {
                        return LegacyQueueDisposition(examined, accepted, discarded, LegacyQueueStop.SOURCE_FAILURE)
                    }
            }
            // Records the sink did not accept stay at the head of the queue; another page
            // would return them again.
            if (acceptedIds.size < page.size) {
                return LegacyQueueDisposition(examined, accepted, discarded, LegacyQueueStop.NO_PROGRESS)
            }
        }
        return LegacyQueueDisposition(examined, accepted, discarded, LegacyQueueStop.LIMIT_REACHED)
    }

    private fun expire(policy: LegacyQueuePolicy.Expire): LegacyQueueDisposition {
        var examined = 0
        var discarded = 0
        while (examined < policy.maxRecords) {
            val pageSize = minOf(LegacyQueuePolicy.DEFAULT_DISPOSAL_PAGE_SIZE, policy.maxRecords - examined)
            val page =
                try {
                    source.readQueuedRecords(pageSize, Long.MAX_VALUE)
                } catch (_: Exception) {
                    return LegacyQueueDisposition(examined, 0, discarded, LegacyQueueStop.SOURCE_FAILURE)
                }
            if (page.isEmpty()) return LegacyQueueDisposition(examined, 0, discarded, LegacyQueueStop.EXHAUSTED)
            examined += page.size
            val expired =
                page.filter { record ->
                    val occurredAt = record.occurredAtEpochMillis
                    occurredAt != null && occurredAt < policy.cutoffEpochMillis
                }.map { record -> record.id }
            if (expired.isEmpty()) return LegacyQueueDisposition(examined, 0, discarded, LegacyQueueStop.NO_PROGRESS)
            discarded +=
                try {
                    source.discardQueuedRecords(expired)
                } catch (_: Exception) {
                    return LegacyQueueDisposition(examined, 0, discarded, LegacyQueueStop.SOURCE_FAILURE)
                }
            if (expired.size < page.size) return LegacyQueueDisposition(examined, 0, discarded, LegacyQueueStop.NO_PROGRESS)
        }
        return LegacyQueueDisposition(examined, 0, discarded, LegacyQueueStop.LIMIT_REACHED)
    }

    private fun openOwner(loader: () -> PersistedCoreState): RuntimeQueueOwner =
        try {
            ownerOpener(loader).await()
        } catch (error: UnsupportedRuntimeStorageSchemaException) {
            throw OpenFailure(MigrationOutcome.REFUSED_STORAGE_SCHEMA, error)
        } catch (error: RuntimeQueueOwnershipException) {
            throw OpenFailure(MigrationOutcome.REFUSED_OWNER_UNAVAILABLE, error)
        } catch (error: Exception) {
            throw OpenFailure(MigrationOutcome.FAILED, error)
        }

    private fun snapshot(owner: RuntimeQueueOwner): PersistedCoreState = owner.snapshot().await().state

    private fun close(owner: RuntimeQueueOwner) {
        try {
            owner.closeAsync().await()
        } catch (_: Exception) {
            // The owner is unusable either way; the reopen below decides the outcome.
        }
    }

    private fun quarantineSource(): LegacyQuarantineOutcome =
        try {
            source.quarantine()
        } catch (_: Exception) {
            LegacyQuarantineOutcome.FAILED
        }

    private fun planFromSource(): Plan {
        val probe =
            try {
                source.probe()
            } catch (_: SecurityException) {
                LegacyProbe.PERMISSION_DENIED
            } catch (_: Exception) {
                LegacyProbe.CORRUPT
            }
        return when (probe) {
            LegacyProbe.PRESENT -> planFromValues()
            LegacyProbe.ABSENT -> fresh(LegacySourceCondition.ABSENT, readableOptedOut = null)
            LegacyProbe.UNSUPPORTED -> fresh(LegacySourceCondition.UNSUPPORTED, readableOptedOut = null)
            LegacyProbe.FUTURE -> fresh(LegacySourceCondition.FUTURE, readableOptedOut = null)
            LegacyProbe.PERMISSION_DENIED -> fresh(LegacySourceCondition.PERMISSION_DENIED, readableOptedOut = null)
            LegacyProbe.CORRUPT -> fresh(LegacySourceCondition.CORRUPT, readableOptedOut = null)
        }
    }

    private fun planFromValues(): Plan {
        val values = LinkedHashMap<LegacyStateKey, LegacyValue>()
        for (key in LegacyStateKey.values()) {
            values[key] = readBounded(key)
        }
        val readableOptedOut = (values[LegacyStateKey.OPTED_OUT] as? LegacyValue.Flag)?.value

        val anonymousId =
            when (val value = values.getValue(LegacyStateKey.ANONYMOUS_ID)) {
                is LegacyValue.Text ->
                    value.value.takeIf { candidate -> candidate.codePointCount(0, candidate.length) in 1..256 }
                        ?: return fresh(LegacySourceCondition.CORRUPT, readableOptedOut)
                LegacyValue.Absent -> return fresh(LegacySourceCondition.PARTIAL, readableOptedOut)
                is LegacyValue.Unreadable -> return fresh(value.problem.toCondition(), readableOptedOut)
                is LegacyValue.Flag, is LegacyValue.Document ->
                    return fresh(LegacySourceCondition.CORRUPT, readableOptedOut)
            }

        val dropped = LinkedHashSet<LegacyStateKey>()
        val userId =
            when (val value = values.getValue(LegacyStateKey.USER_ID)) {
                is LegacyValue.Text -> value.value.takeIf { candidate -> candidate.isNotEmpty() && candidate != anonymousId }
                LegacyValue.Absent -> null
                else -> {
                    dropped += LegacyStateKey.USER_ID
                    null
                }
            }
        if (values.getValue(LegacyStateKey.IDENTIFIED) is LegacyValue.Unreadable) dropped += LegacyStateKey.IDENTIFIED
        val optedOut =
            when (values.getValue(LegacyStateKey.OPTED_OUT)) {
                is LegacyValue.Flag -> checkNotNull(readableOptedOut)
                LegacyValue.Absent -> false
                else -> {
                    // An unreadable privacy choice is honored as an opt-out.
                    dropped += LegacyStateKey.OPTED_OUT
                    true
                }
            }

        val now = nowTimestamp()
        val superProperties = document(values, LegacyStateKey.SUPER_PROPERTIES, dropped)
        val groups = stringDocument(values, LegacyStateKey.GROUPS, dropped)
        val personProperties = document(values, LegacyStateKey.PERSON_PROPERTIES_FOR_FLAGS, dropped)
        val groupProperties = nestedDocument(values, LegacyStateKey.GROUP_PROPERTIES_FOR_FLAGS, dropped)

        fun state(
            superProperties: Map<String, Any?>,
            groups: Map<String, String>,
            personProperties: Map<String, Any?>,
            groupProperties: Map<String, Map<String, Any?>>,
        ): PersistedCoreState =
            PersistedCoreState(
                identity =
                    IdentityState(
                        revision = 0,
                        contextRevision = 0,
                        anonymousId = anonymousId,
                        userId = userId,
                        groups = groups,
                        superProperties = superProperties,
                        session = null,
                        optedOut = optedOut,
                        updatedAt = now,
                        migration = MigrationState(witnessSchema(IMPORTED_WITNESS), now),
                    ),
                stream = newStream(),
                flagContext = FlagContextState(personProperties = personProperties, groupProperties = groupProperties),
            )

        val complete = state(superProperties, groups, personProperties, groupProperties)
        val validated =
            try {
                canonical(complete)
            } catch (_: Exception) {
                // Some optional context did not satisfy the runtime schema. Import the identity
                // without it rather than losing the identity.
                dropped += CONTEXT_KEYS
                try {
                    canonical(state(emptyMap(), emptyMap(), emptyMap(), emptyMap()))
                } catch (_: Exception) {
                    return fresh(LegacySourceCondition.CORRUPT, readableOptedOut)
                }
            }
        return Plan(LegacySourceCondition.SUPPORTED, LegacyRecoveryAction.IMPORT, validated, dropped)
    }

    private fun readBounded(key: LegacyStateKey): LegacyValue {
        val value =
            try {
                source.read(key)
            } catch (_: SecurityException) {
                return LegacyValue.Unreadable(LegacyValueProblem.PERMISSION_DENIED)
            } catch (_: Exception) {
                return LegacyValue.Unreadable(LegacyValueProblem.CORRUPT)
            }
        val oversized =
            when (value) {
                is LegacyValue.Text -> value.value.toByteArray(Charsets.UTF_8).size > key.maxBytes
                is LegacyValue.Document -> value.value.size > MAX_DOCUMENT_ENTRIES || documentTooLarge(value.value, key.maxBytes)
                else -> false
            }
        return if (oversized) LegacyValue.Unreadable(LegacyValueProblem.OVERSIZED) else value
    }

    private fun documentTooLarge(
        document: Map<String, Any?>,
        maxBytes: Int,
    ): Boolean {
        var total = 0L
        fun measure(value: Any?) {
            when (value) {
                null -> total += 4
                is String -> total += value.toByteArray(Charsets.UTF_8).size + 2
                is Boolean, is Number -> total += 8
                is Map<*, *> -> value.forEach { (key, child) ->
                    measure(key)
                    measure(child)
                }
                is List<*> -> value.forEach(::measure)
                else -> total += maxBytes.toLong() + 1
            }
        }
        measure(document)
        return total > maxBytes
    }

    private fun document(
        values: Map<LegacyStateKey, LegacyValue>,
        key: LegacyStateKey,
        dropped: MutableSet<LegacyStateKey>,
    ): Map<String, Any?> =
        when (val value = values.getValue(key)) {
            is LegacyValue.Document ->
                try {
                    JsonValues.objectValue(value.value, key.name)
                } catch (_: Exception) {
                    dropped += key
                    emptyMap()
                }
            LegacyValue.Absent -> emptyMap()
            else -> {
                dropped += key
                emptyMap()
            }
        }

    private fun stringDocument(
        values: Map<LegacyStateKey, LegacyValue>,
        key: LegacyStateKey,
        dropped: MutableSet<LegacyStateKey>,
    ): Map<String, String> {
        val raw = document(values, key, dropped)
        if (raw.isEmpty()) return emptyMap()
        val strings = LinkedHashMap<String, String>()
        raw.forEach { (name, child) ->
            if (child !is String) {
                dropped += key
                return emptyMap()
            }
            strings[name] = child
        }
        return Collections.unmodifiableMap(strings)
    }

    private fun nestedDocument(
        values: Map<LegacyStateKey, LegacyValue>,
        key: LegacyStateKey,
        dropped: MutableSet<LegacyStateKey>,
    ): Map<String, Map<String, Any?>> {
        val raw = document(values, key, dropped)
        if (raw.isEmpty()) return emptyMap()
        val nested = LinkedHashMap<String, Map<String, Any?>>()
        raw.forEach { (name, child) ->
            if (child !is Map<*, *>) {
                dropped += key
                return emptyMap()
            }
            @Suppress("UNCHECKED_CAST")
            nested[name] = child as Map<String, Any?>
        }
        return Collections.unmodifiableMap(nested)
    }

    private fun fresh(
        condition: LegacySourceCondition,
        readableOptedOut: Boolean?,
    ): Plan {
        val now = nowTimestamp()
        val witness =
            if (condition == LegacySourceCondition.ABSENT) NO_SOURCE_WITNESS else witnessSchema(FRESH_WITNESS)
        val state =
            PersistedCoreState(
                identity =
                    IdentityState(
                        revision = 0,
                        contextRevision = 0,
                        anonymousId = nextIdentifier("anon_"),
                        userId = null,
                        groups = emptyMap(),
                        superProperties = emptyMap(),
                        session = null,
                        optedOut = LegacyRecoveryTable.freshOptedOut(condition, readableOptedOut),
                        updatedAt = now,
                        migration = MigrationState(witness, now),
                    ),
                stream = newStream(),
                flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
            )
        return Plan(condition, LegacyRecoveryTable.actionFor(condition), canonical(state), emptySet())
    }

    private fun witnessSchema(suffix: String): String {
        val schema = source.schemaId.ifEmpty { "unknown" }
        return (schema + suffix).take(MAX_WITNESS_LENGTH)
    }

    private fun newStream(): StreamState =
        StreamState(streamId = nextIdentifier("stream_"), nextSequence = INITIAL_SEQUENCE)

    private fun nextIdentifier(prefix: String): String {
        val candidate = identifiers.next(prefix)
        check(candidate.codePointCount(0, candidate.length) in 1..256) { "Generated identifier is invalid" }
        return candidate
    }

    private fun nowTimestamp(): String = RuntimeWallTimestamps.rfc3339(clock.nowEpochMillis())

    private fun report(
        outcome: MigrationOutcome,
        plan: Plan?,
        startedAt: Long,
        quarantine: LegacyQuarantineOutcome?,
    ): MigrationReport =
        MigrationReport(
            outcome = outcome,
            sourceCondition = plan?.condition ?: LegacySourceCondition.NOT_READ,
            recoveryAction = plan?.action,
            sourceSchema = plan?.let { source.schemaId },
            targetSchemaVersion = CORE_SCHEMA_VERSION,
            sdkVersion = sdkVersion,
            elapsedMillis = (monotonicNanos() - startedAt) / NANOS_PER_MILLI,
            droppedContext = plan?.droppedContext?.let { Collections.unmodifiableSet(LinkedHashSet(it)) } ?: emptySet(),
            quarantine = quarantine,
            legacyQueue = plan?.let { queueSummary() },
        )

    private fun queueSummary(): LegacyQueueSummary? =
        try {
            source.queueSummary()
        } catch (_: Exception) {
            null
        }

    private fun <T> Future<T>.await(): T =
        try {
            get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }

    private fun LegacyValueProblem.toCondition(): LegacySourceCondition =
        when (this) {
            LegacyValueProblem.CORRUPT, LegacyValueProblem.WRONG_TYPE -> LegacySourceCondition.CORRUPT
            LegacyValueProblem.OVERSIZED -> LegacySourceCondition.OVERSIZED
            LegacyValueProblem.PERMISSION_DENIED -> LegacySourceCondition.PERMISSION_DENIED
        }

    internal companion object {
        const val IMPORTED_WITNESS: String = ":imported"
        const val FRESH_WITNESS: String = ":fresh"
        const val NO_SOURCE_WITNESS: String = "none"
        private const val MAX_WITNESS_LENGTH = 128
        private const val MAX_DOCUMENT_ENTRIES = 256
        private const val NANOS_PER_MILLI = 1_000_000L
        private val CONTEXT_KEYS =
            setOf(
                LegacyStateKey.SUPER_PROPERTIES,
                LegacyStateKey.GROUPS,
                LegacyStateKey.PERSON_PROPERTIES_FOR_FLAGS,
                LegacyStateKey.GROUP_PROPERTIES_FOR_FLAGS,
            )

        /** Decode/encode round trip through the runtime schema; throws when the state is not representable. */
        @Throws(CoreStateCorruptionException::class)
        fun canonical(state: PersistedCoreState): PersistedCoreState = CoreStateCodec.decode(CoreStateCodec.encode(state))
    }
}
