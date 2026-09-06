package dev.elu.analytics.internal.compat

/**
 * The closed set of values a migration source can be asked for.
 *
 * Every key names one logical value of a prior on-device state format, independent
 * of how that format stores it. [maxBytes] is the largest encoded value the
 * coordinator accepts for the key: a source must answer [LegacyValue.Unreadable] with
 * [LegacyValueProblem.OVERSIZED] instead of materializing anything larger, and the
 * coordinator re-checks the bound on what it receives.
 */
internal enum class LegacyStateKey(val maxBytes: Int) {
    /** The device-scoped anonymous identity. The only key an importable state must have. */
    ANONYMOUS_ID(1_024),

    /** The customer-supplied identity, when one was set. */
    USER_ID(2_048),

    /** Whether [USER_ID] is an identified user rather than an alias of the anonymous identity. */
    IDENTIFIED(16),

    /** The persisted privacy choice. */
    OPTED_OUT(16),

    /** Properties attached to every event. */
    SUPER_PROPERTIES(65_536),

    /** Group type to group key associations. */
    GROUPS(16_384),

    /** Person properties used for flag evaluation. */
    PERSON_PROPERTIES_FOR_FLAGS(65_536),

    /** Group properties used for flag evaluation, keyed by group type. */
    GROUP_PROPERTIES_FOR_FLAGS(65_536),
}

/** A value answered for one [LegacyStateKey]. */
internal sealed interface LegacyValue {
    data object Absent : LegacyValue

    data class Text(val value: String) : LegacyValue

    data class Flag(val value: Boolean) : LegacyValue

    /** A JSON object made of plain maps, lists, strings, booleans, numbers, and nulls. */
    data class Document(val value: Map<String, Any?>) : LegacyValue

    data class Unreadable(val problem: LegacyValueProblem) : LegacyValue
}

internal enum class LegacyValueProblem {
    CORRUPT,
    OVERSIZED,
    PERMISSION_DENIED,
    WRONG_TYPE,
}

/** What a source found before any value is read. */
internal enum class LegacyProbe {
    /** No prior state exists. */
    ABSENT,

    /** Prior state exists in a format this source reads. */
    PRESENT,

    /** Prior state exists in a format this source does not understand. */
    UNSUPPORTED,

    /** Prior state was written by a newer format than this source reads. */
    FUTURE,

    /** Prior state exists but cannot be opened. */
    PERMISSION_DENIED,

    /** Prior state exists but its container is damaged. */
    CORRUPT,
}

/** Size of the prior queue without any record content. */
internal data class LegacyQueueSummary(
    val recordCount: Int,
    val recordBytes: Long,
)

/** One prior queued record, already translated into neutral event terms by the source. */
internal data class LegacyQueuedRecord(
    val id: String,
    val occurredAtEpochMillis: Long?,
    val name: String,
    val properties: Map<String, Any?>,
    val encodedBytes: Int,
)

/** Ids the sink durably accepted from one page of drained records. */
internal data class LegacyRecordAcceptance(val acceptedIds: Set<String>)

/** Receives drained legacy records; it must answer only ids it has durably accepted. */
internal fun interface LegacyRecordSink {
    fun accept(records: List<LegacyQueuedRecord>): LegacyRecordAcceptance
}

internal enum class LegacyQuarantineOutcome {
    QUARANTINED,
    NOTHING_TO_QUARANTINE,
    FAILED,
}

/**
 * A bounded reader of one prior on-device state format.
 *
 * The coordinator asks for allowlisted keys only, each with an explicit size cap, and
 * reads each key at most once per migration attempt. A source must not scan
 * directories, use reflection, or hand out raw file contents; quarantine moves its own
 * files aside byte for byte and reports only an outcome. Implementations may throw
 * [SecurityException] for an unreadable store; any other exception is treated as a
 * damaged store.
 */
internal interface LegacyStateSource {
    /** Stable identifier of the format this source reads. Recorded in the migration witness. */
    val schemaId: String

    fun probe(): LegacyProbe

    fun read(key: LegacyStateKey): LegacyValue

    /** Record count and byte size of the prior queue, or null when the source has no queue. */
    fun queueSummary(): LegacyQueueSummary?

    /** Reads up to [maxCount] queued records totalling at most [maxBytes], oldest first. */
    fun readQueuedRecords(
        maxCount: Int,
        maxBytes: Long,
    ): List<LegacyQueuedRecord>

    /** Removes the named queued records. Returns how many were removed. */
    fun discardQueuedRecords(ids: Collection<String>): Int

    /** Moves every prior state file aside without altering its bytes. */
    fun quarantine(): LegacyQuarantineOutcome
}
