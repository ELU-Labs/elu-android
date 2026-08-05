package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ExactTimestamp
import dev.elu.analytics.internal.config.V1FlagAuthorizationResolution
import dev.elu.analytics.internal.config.V1FlagAuthorizationSnapshot
import dev.elu.analytics.internal.config.V1FlagConfigWitness
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.util.Collections

internal const val FLAG_STORAGE_SCHEMA_VERSION: Int = 1
internal const val FLAG_CONTRACT_SCHEMA_VERSION: Int = 1

internal data class FlagExactInstant(
    val source: String,
    val epochWholeSecond: Long,
    val fractionalDigits: String,
    val isLeapSecond: Boolean,
) : Comparable<FlagExactInstant> {
    private val exact: V1ExactTimestamp
        get() = V1ExactTimestamp.fromEpochSecondAndFraction(epochWholeSecond, fractionalDigits, isLeapSecond)

    override fun compareTo(other: FlagExactInstant): Int = exact.compareTo(other.exact)

    fun toEpochMillisFloor(): Long = exact.toEpochMillisFloor()

    fun elapsedNanosecondsFloorSince(other: FlagExactInstant): Long? =
        exact.elapsedNanosecondsFloorSince(other.exact)

    companion object {
        fun parse(source: String): FlagExactInstant {
            val parsed = V1ConfigJson.parseExactTimestamp(source)
            return FlagExactInstant(
                source = source,
                epochWholeSecond = parsed.epochWholeSecond,
                fractionalDigits = parsed.fractionalDigits,
                isLeapSecond = parsed.isLeapSecond,
            )
        }

        fun fromEpochMillis(epochMillis: Long): FlagExactInstant {
            val parsed = V1ExactTimestamp.fromEpochMillis(epochMillis)
            return FlagExactInstant(
                source = epochMillis.toString(),
                epochWholeSecond = parsed.epochWholeSecond,
                fractionalDigits = parsed.fractionalDigits,
                isLeapSecond = false,
            )
        }
    }
}

internal typealias FlagAuthorizationWitness = V1FlagConfigWitness
internal typealias FlagAuthorizationSnapshot = V1FlagAuthorizationSnapshot
internal typealias FlagAuthorizationResolution = V1FlagAuthorizationResolution

internal enum class FlagRestrictionReason {
    MISSING,
    MALFORMED,
    UNSUPPORTED_SCHEMA,
    DISABLED,
    REVOKED,
    FLAGS_DISABLED,
    UNAUTHORIZED,
    STALE,
    CONFLICT,
    CONFIG_EXPIRED,
    WALL_ROLLBACK,
    STORAGE,
    TERMINAL,
}

/** Full persisted context witness. [optedOut] is never emitted on the wire. */
internal data class FlagEvaluationWitness(
    val anonymousId: String,
    val userId: String?,
    val identityRevision: Long,
    val contextRevision: Long,
    val optedOut: Boolean,
    val personProperties: FlagJsonValue.ObjectValue,
    val groups: FlagJsonValue.ObjectValue,
    val groupProperties: FlagJsonValue.ObjectValue,
    val versions: RuntimeVersions,
)

internal data class FlagRequest(
    val requestId: String,
    val witness: FlagEvaluationWitness,
    val canonicalBytes: ByteArray,
)

internal data class FlagResponse(
    val requestId: String,
    val contextRevision: Long,
    val identityRevision: Long,
    val flagsRevision: String,
    val evaluatedAt: FlagExactInstant,
    val expiresAt: FlagExactInstant,
    val flags: FlagJsonValue.ObjectValue,
    val payloads: FlagJsonValue.ObjectValue,
)

internal data class FlagRequestToken(
    val storeEpoch: String,
    val requestGeneration: Long,
    val barrierGeneration: Long,
    val activeRequestId: String,
    val witnessHash: String,
)

/** Exact committed cache identity used when an owner observes its fixed monotonic deadline. */
internal data class FlagCacheLeaseToken(
    val storeEpoch: String,
    val requestGeneration: Long,
    val recordId: String,
    val barrierGeneration: Long,
    val witnessHash: String,
    val flagsRevision: String,
    val responseExpiresAt: FlagExactInstant,
)

internal data class FlagBegunRequest(
    val token: FlagRequestToken,
    val authorization: FlagAuthorizationSnapshot,
    val witness: FlagEvaluationWitness,
    val request: FlagRequest,
)

internal sealed interface FlagBeginResult {
    data class Begun(val request: FlagBegunRequest) : FlagBeginResult

    data class Restricted(val reason: FlagRestrictionReason, val barrierGeneration: Long?) : FlagBeginResult

    data object Terminal : FlagBeginResult
}

/** Store-backed authorization immediately before transport dispatch. */
internal sealed interface FlagPreSendResult {
    data object Current : FlagPreSendResult

    data class Restricted(val reason: FlagRestrictionReason) : FlagPreSendResult

    data object Stale : FlagPreSendResult

    data object Terminal : FlagPreSendResult
}

internal sealed interface FlagReloadResult {
    data class Updated(
        val flagsRevision: String,
        val requestGeneration: Long,
        /** Present only after the separate final CAS. */
        val cacheLeaseToken: FlagCacheLeaseToken? = null,
    ) : FlagReloadResult

    data object Stale : FlagReloadResult

    data class Restricted(val reason: FlagRestrictionReason) : FlagReloadResult

    data class Failed(val reason: String) : FlagReloadResult

    data object Terminal : FlagReloadResult
}

internal sealed interface FlagFinalizeStoreResult {
    data class Current(val cacheLeaseToken: FlagCacheLeaseToken) : FlagFinalizeStoreResult

    data object Stale : FlagFinalizeStoreResult

    data class Restricted(val reason: FlagRestrictionReason) : FlagFinalizeStoreResult

    data object Terminal : FlagFinalizeStoreResult
}

internal sealed interface FlagReadResult {
    data object Missing : FlagReadResult

    data class Found(
        val value: FlagJsonValue,
        val payload: FlagJsonValue?,
        val flagsRevision: String,
        /** Internal lease input; it is not exposed through the public facade. */
        val responseExpiresAt: FlagExactInstant,
        val cacheLeaseToken: FlagCacheLeaseToken,
    ) : FlagReadResult

    /** Valid current cache whose flag map simply does not contain the requested key. */
    data class CacheMiss(
        val responseExpiresAt: FlagExactInstant,
        val cacheLeaseToken: FlagCacheLeaseToken,
    ) : FlagReadResult

    data class Restricted(val reason: FlagRestrictionReason) : FlagReadResult

    data object Terminal : FlagReadResult
}

internal data class FlagCacheEnvelope(
    val authorization: FlagAuthorizationWitness,
    val witness: FlagEvaluationWitness,
    val response: FlagResponse,
)

internal data class FlagReloadWitnessSnapshot(
    val authorization: FlagAuthorizationSnapshot,
    val witness: FlagEvaluationWitness,
)

internal fun FlagJsonValue.ObjectValue.immutableCopy(): FlagJsonValue.ObjectValue =
    FlagJsonValue.ObjectValue(
        Collections.unmodifiableList(
            members.map { member ->
                FlagJsonValue.ObjectValue.Member(member.key, member.value.deepCopy())
            },
        ),
    )

internal fun FlagJsonValue.deepCopy(): FlagJsonValue =
    when (this) {
        is FlagJsonValue.ObjectValue -> immutableCopy()
        is FlagJsonValue.ArrayValue -> FlagJsonValue.ArrayValue(Collections.unmodifiableList(values.map { it.deepCopy() }))
        is FlagJsonValue.StringValue -> copy()
        is FlagJsonValue.NumberValue -> copy()
        is FlagJsonValue.BooleanValue -> copy()
        FlagJsonValue.NullValue -> FlagJsonValue.NullValue
    }
