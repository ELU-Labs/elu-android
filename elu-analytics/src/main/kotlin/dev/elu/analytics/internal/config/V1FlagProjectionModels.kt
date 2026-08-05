package dev.elu.analytics.internal.config

import dev.elu.analytics.internal.flags.FLAG_MAX_SAFE_INTEGER
import dev.elu.analytics.internal.flags.FlagExactInstant
import java.net.URI

internal enum class V1FlagProjectionRejection {
    MISSING,
    MALFORMED,
    UNSUPPORTED_SCHEMA,
    EXPIRED,
    INACTIVE,
    REVOKED,
    FLAGS_DISABLED,
    UNAUTHORIZED,
    STALE,
    CONFLICT,
    STORAGE,
    TERMINAL,
}

internal data class V1FlagConfigBoundary(
    val revision: String,
    val issuedAt: FlagExactInstant,
    val semanticHash: String,
)

/** Strict flags-only projection. Unrelated channel documents are intentionally not decoded. */
internal data class V1ParsedFlagConfig(
    val revision: String,
    val issuedAt: String,
    val issuedAtInstant: V1ExactTimestamp,
    val expiresAt: String,
    val expiresAtInstant: V1ExactTimestamp,
    val status: V1ConfigStatus,
    val siteId: String?,
    val flagsEndpoint: String?,
    val flagsEnabled: Boolean?,
    val serialized: String,
    val configSemanticHash: String,
)

/** Exact installation ownership carried through every prepared transition, including restrictions. */
internal data class V1FlagSiteOwnership(
    val trustedSiteKey: String,
    val siteNamespaceDigest: String,
    /** Bound only after a config document has passed strict parsing. */
    val siteId: String?,
)

/** Complete owner-minted config witness. Hashes are never accepted in place of exact fields. */
internal data class V1FlagConfigWitness(
    val trustedSiteKey: String,
    val siteNamespaceDigest: String,
    val siteId: String,
    val endpoint: URI,
    val configRevision: String,
    val configIssuedAt: FlagExactInstant,
    val configSemanticHash: String,
    val configExpiresAt: FlagExactInstant,
)

internal sealed interface V1PreparedFlagDecision {
    val boundary: V1FlagConfigBoundary?

    data class Allowed(
        val witness: V1FlagConfigWitness,
        override val boundary: V1FlagConfigBoundary,
    ) : V1PreparedFlagDecision

    data class Restricted(
        val reason: V1FlagProjectionRejection,
        override val boundary: V1FlagConfigBoundary?,
    ) : V1PreparedFlagDecision
}

/** The constructor is module-internal; the manager also checks its private preparation generation. */
internal data class V1PreparedFlagConfiguration(
    val preparationGeneration: Long,
    val decision: V1PreparedFlagDecision,
    val ownership: V1FlagSiteOwnership?,
    /** Reused only when the durable store preserves the exact same authorization. */
    val priorAuthorization: V1FlagAuthorizationSnapshot?,
)

internal data class V1FlagAuthorizationSnapshot(
    val witness: V1FlagConfigWitness,
    val ownerGeneration: Long,
    val barrierGeneration: Long,
) {
    init {
        require(ownerGeneration in 1..FLAG_MAX_SAFE_INTEGER)
        require(barrierGeneration in 1..FLAG_MAX_SAFE_INTEGER)
    }
}

internal sealed interface V1FlagAuthorizationResolution {
    data class Allowed(val authorization: V1FlagAuthorizationSnapshot) : V1FlagAuthorizationResolution

    data class Restricted(val reason: V1FlagProjectionRejection) : V1FlagAuthorizationResolution
}
