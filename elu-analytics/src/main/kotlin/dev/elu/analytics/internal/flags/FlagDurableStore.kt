package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.V1FlagAuthorizationSnapshot
import dev.elu.analytics.internal.config.V1FlagConfigBoundary
import dev.elu.analytics.internal.config.V1FlagConfigWitness
import dev.elu.analytics.internal.config.V1FlagProjectionRejection
import dev.elu.analytics.internal.config.V1PreparedFlagConfiguration
import dev.elu.analytics.internal.config.V1PreparedFlagDecision
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.runtime.RuntimeFlagStoredRow
import dev.elu.analytics.internal.runtime.RUNTIME_FLAG_AUTHORITY_KEY
import dev.elu.analytics.internal.runtime.RUNTIME_FLAG_CACHE_METADATA_KEY
import dev.elu.analytics.internal.runtime.RuntimeQueueTransaction
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections

internal sealed interface FlagConfigStoreResult {
    data class Allowed(val barrierGeneration: Long) : FlagConfigStoreResult

    data class Restricted(
        val reason: V1FlagProjectionRejection,
        val barrierGeneration: Long,
    ) : FlagConfigStoreResult

    data object Terminal : FlagConfigStoreResult
}

internal sealed interface FlagCommitStoreResult {
    data object Updated : FlagCommitStoreResult

    data object Stale : FlagCommitStoreResult

    data class Restricted(val reason: V1FlagProjectionRejection) : FlagCommitStoreResult

    data object Terminal : FlagCommitStoreResult
}

internal sealed interface FlagLeaseExpiryStoreResult {
    data object Expired : FlagLeaseExpiryStoreResult

    data class Restricted(val reason: V1FlagProjectionRejection) : FlagLeaseExpiryStoreResult

    data object Stale : FlagLeaseExpiryStoreResult

    data object Terminal : FlagLeaseExpiryStoreResult
}

internal sealed interface FlagCacheExpiryStoreResult {
    data object Expired : FlagCacheExpiryStoreResult

    data class Restricted(val reason: V1FlagProjectionRejection) : FlagCacheExpiryStoreResult

    data object Stale : FlagCacheExpiryStoreResult

    data object Terminal : FlagCacheExpiryStoreResult
}

/** SQLite transaction algorithms for the non-resettable authority and resettable request/cache. */
internal object FlagDurableStore {
    private const val AUTHORITY_KEY = RUNTIME_FLAG_AUTHORITY_KEY
    private const val CACHE_METADATA_KEY = RUNTIME_FLAG_CACHE_METADATA_KEY
    private const val BODY_PREFIX = "cache-body:"
    internal const val MAX_CHUNK_BYTES = 1_048_576
    private const val MAX_CHUNKS = FLAG_MAX_CACHE_BYTES / MAX_CHUNK_BYTES
    /**
     * A future envelope may place its discriminator beyond the v1 body ceiling. Scan one complete
     * physical row beyond that ceiling without ever joining the rows; an envelope still
     * unclassified at the bound is preserved conservatively.
     */
    private const val MAX_GROUP_SCHEMA_PROBE_BYTES: Long = 5_242_880L

    fun uninitializedAuthorityRow(
        trustedSiteKey: String,
        siteNamespaceDigest: String,
    ): RuntimeFlagStoredRow =
        RuntimeFlagStoredRow(
            AUTHORITY_KEY,
            FLAG_STORAGE_SCHEMA_VERSION.toLong(),
            AuthorityCodec.encode(AuthorityLedger.uninitialized(trustedSiteKey, siteNamespaceDigest)),
        )

    fun snapshotWitness(
        state: PersistedCoreState,
        versions: RuntimeVersions,
    ): FlagEvaluationWitness = witnessFrom(state, versions)

    /**
     * Persists an owner-observed monotonic config-lease expiry before the owner reports it.
     * The expected closed authorization prevents an old owner from expiring a newer config.
     */
    fun expireAuthorizationLease(
        transaction: RuntimeQueueTransaction,
        authorization: V1FlagAuthorizationSnapshot,
        wallNowEpochMillis: Long,
    ): FlagLeaseExpiryStoreResult {
        val ledger = readAuthority(transaction) ?: return FlagLeaseExpiryStoreResult.Terminal
        if (!ledger.initialized || ledger.terminal) return FlagLeaseExpiryStoreResult.Terminal
        if (hasFutureCacheStorage(transaction)) return FlagLeaseExpiryStoreResult.Terminal
        if (!wallNowEpochMillis.isSafePersistedInteger()) {
            return FlagLeaseExpiryStoreResult.Restricted(V1FlagProjectionRejection.STORAGE)
        }
        val floor = ledger.lastObservedWallEpochMillis ?: return FlagLeaseExpiryStoreResult.Terminal
        if (wallNowEpochMillis < floor) {
            return FlagLeaseExpiryStoreResult.Restricted(V1FlagProjectionRejection.STORAGE)
        }
        var current = ledger.copy(lastObservedWallEpochMillis = wallNowEpochMillis)
        if (
            current.barrierGeneration != authorization.barrierGeneration ||
            current.allowedAuthorization != authorization.witness
        ) {
            transaction.putFlagRow(authorityRow(current))
            return FlagLeaseExpiryStoreResult.Stale
        }
        current = incrementBarrierOrTerminal(current)
        if (!current.terminal) {
            current = current.copy(
                allowedAuthorization = null,
                restriction = V1FlagProjectionRejection.EXPIRED,
            )
        }
        transaction.putFlagRow(authorityRow(current))
        clearKnownCurrentCache(transaction)
        return if (current.terminal) FlagLeaseExpiryStoreResult.Terminal else FlagLeaseExpiryStoreResult.Expired
    }

    /** Persists a fixed monotonic response-cache deadline using the exact committed cache token. */
    fun expireCacheLease(
        transaction: RuntimeQueueTransaction,
        authorization: V1FlagAuthorizationSnapshot,
        state: PersistedCoreState,
        versions: RuntimeVersions,
        token: FlagCacheLeaseToken,
        wallNowEpochMillis: Long,
    ): FlagCacheExpiryStoreResult {
        if (hasFutureCacheStorage(transaction)) return FlagCacheExpiryStoreResult.Terminal
        when (val authority = validateAuthorityForUse(transaction, authorization, wallNowEpochMillis)) {
            is AuthorityUse.Restricted -> return FlagCacheExpiryStoreResult.Restricted(authority.reason)
            AuthorityUse.Terminal -> return FlagCacheExpiryStoreResult.Terminal
            is AuthorityUse.Allowed -> Unit
        }
        val witness: FlagEvaluationWitness
        val witnessHash: String
        try {
            witness = witnessFrom(state, versions)
            witnessHash = FlagCodec.witnessHash(witness)
        } catch (_: FlagProtocolException) {
            return FlagCacheExpiryStoreResult.Stale
        }
        if (
            token.barrierGeneration != authorization.barrierGeneration ||
            token.witnessHash != witnessHash
        ) {
            return FlagCacheExpiryStoreResult.Stale
        }
        val metadataRead = readMetadata(transaction)
        val metadata = (metadataRead as? MetadataRead.Current)?.metadata
            ?: return if (metadataRead is MetadataRead.Future) {
                FlagCacheExpiryStoreResult.Terminal
            } else {
                FlagCacheExpiryStoreResult.Stale
            }
        val pointer = metadata.cache ?: return FlagCacheExpiryStoreResult.Stale
        if (!metadata.matches(token) || !pointer.matches(token)) return FlagCacheExpiryStoreResult.Stale
        return when (val cache = readCache(transaction, pointer)) {
            CacheRead.Future -> FlagCacheExpiryStoreResult.Terminal
            CacheRead.Corrupt -> {
                clearKnownCurrentCache(transaction)
                FlagCacheExpiryStoreResult.Stale
            }
            is CacheRead.Current -> {
                val envelope = cache.envelope
                if (
                    envelope.authorization != authorization.witness ||
                    envelope.witness != witness ||
                    envelope.response.flagsRevision != token.flagsRevision ||
                    envelope.response.expiresAt != token.responseExpiresAt
                ) {
                    return FlagCacheExpiryStoreResult.Stale
                }
                expireCache(transaction, metadata)
                FlagCacheExpiryStoreResult.Expired
            }
        }
    }

    fun applyConfiguration(
        transaction: RuntimeQueueTransaction,
        prepared: V1PreparedFlagConfiguration,
        wallNowEpochMillis: Long,
    ): FlagConfigStoreResult {
        val ledger = readAuthority(transaction) ?: return FlagConfigStoreResult.Terminal
        if (ledger.terminal) return FlagConfigStoreResult.Terminal
        if (hasFutureCacheStorage(transaction)) return FlagConfigStoreResult.Terminal
        if (!wallNowEpochMillis.isSafePersistedInteger()) {
            return FlagConfigStoreResult.Restricted(V1FlagProjectionRejection.STORAGE, ledger.barrierGeneration)
        }
        if (ledger.lastObservedWallEpochMillis != null && wallNowEpochMillis < ledger.lastObservedWallEpochMillis) {
            return FlagConfigStoreResult.Restricted(V1FlagProjectionRejection.STORAGE, ledger.barrierGeneration)
        }
        val ownership = prepared.ownership
        if (
            ownership == null ||
            ownership.trustedSiteKey != ledger.trustedSiteKey ||
            ownership.siteNamespaceDigest != ledger.siteNamespaceDigest ||
            (ledger.siteId != null && ownership.siteId != null && ledger.siteId != ownership.siteId)
        ) {
            val next = incrementBarrierOrTerminal(ledger.copy(lastObservedWallEpochMillis = wallNowEpochMillis))
            val restricted =
                if (next.terminal) {
                    next
                } else {
                    next.copy(
                        initialized = true,
                        allowedAuthorization = null,
                        restriction = V1FlagProjectionRejection.UNAUTHORIZED,
                    )
                }
            transaction.putFlagRow(authorityRow(restricted))
            clearKnownCurrentCache(transaction)
            return if (restricted.terminal) {
                FlagConfigStoreResult.Terminal
            } else {
                FlagConfigStoreResult.Restricted(
                    V1FlagProjectionRejection.UNAUTHORIZED,
                    restricted.barrierGeneration,
                )
            }
        }
        var wallAdvanced = ledger.copy(lastObservedWallEpochMillis = wallNowEpochMillis)
        if (wallAdvanced.siteId == null && ownership.siteId != null) {
            wallAdvanced = wallAdvanced.copy(siteId = ownership.siteId)
        }
        val decision = prepared.decision
        if (decision is V1PreparedFlagDecision.Allowed) {
            if (
                decision.witness.trustedSiteKey != wallAdvanced.trustedSiteKey ||
                decision.witness.siteNamespaceDigest != wallAdvanced.siteNamespaceDigest ||
                decision.witness.siteId != wallAdvanced.siteId
            ) {
                val next = incrementBarrierOrTerminal(wallAdvanced)
                val restricted =
                    if (next.terminal) next else next.copy(
                        initialized = true,
                        allowedAuthorization = null,
                        restriction = V1FlagProjectionRejection.UNAUTHORIZED,
                    )
                transaction.putFlagRow(authorityRow(restricted))
                clearKnownCurrentCache(transaction)
                return if (restricted.terminal) {
                    FlagConfigStoreResult.Terminal
                } else {
                    FlagConfigStoreResult.Restricted(
                        V1FlagProjectionRejection.UNAUTHORIZED,
                        restricted.barrierGeneration,
                    )
                }
            }
        }
        if (
            decision is V1PreparedFlagDecision.Restricted &&
            decision.reason == V1FlagProjectionRejection.TERMINAL
        ) {
            val next = incrementBarrierOrTerminal(wallAdvanced)
            val terminal =
                next.copy(
                    initialized = true,
                    terminal = true,
                    allowedAuthorization = null,
                    restriction = V1FlagProjectionRejection.TERMINAL,
                )
            transaction.putFlagRow(authorityRow(terminal))
            clearKnownCurrentCache(transaction)
            return FlagConfigStoreResult.Terminal
        }
        val boundary = decision.boundary
        val transition = decideTransition(wallAdvanced, decision, boundary, wallNowEpochMillis)
        return when (transition) {
            is Transition.Preserve -> {
                transaction.putFlagRow(authorityRow(transition.ledger))
                when {
                    transition.reason != null ->
                        FlagConfigStoreResult.Restricted(transition.reason, transition.ledger.barrierGeneration)
                    transition.ledger.allowedAuthorization != null ->
                        FlagConfigStoreResult.Allowed(transition.ledger.barrierGeneration)
                    else ->
                        FlagConfigStoreResult.Restricted(
                            transition.ledger.restriction ?: V1FlagProjectionRejection.INACTIVE,
                            transition.ledger.barrierGeneration,
                        )
                }
            }
            is Transition.Replace -> {
                val next = incrementBarrierOrTerminal(transition.ledger)
                if (next.terminal) {
                    transaction.putFlagRow(authorityRow(next))
                    clearKnownCurrentCache(transaction)
                    FlagConfigStoreResult.Terminal
                } else {
                    val replaced = transition.apply(next)
                    transaction.putFlagRow(authorityRow(replaced))
                    clearKnownCurrentCache(transaction)
                    replaced.allowedAuthorization?.let { FlagConfigStoreResult.Allowed(replaced.barrierGeneration) }
                        ?: FlagConfigStoreResult.Restricted(
                            replaced.restriction ?: V1FlagProjectionRejection.INACTIVE,
                            replaced.barrierGeneration,
                        )
                }
            }
        }
    }

    fun begin(
        transaction: RuntimeQueueTransaction,
        authorization: V1FlagAuthorizationSnapshot?,
        state: PersistedCoreState,
        versions: RuntimeVersions,
        requestId: String,
        replacementStoreEpoch: String,
        wallNowEpochMillis: Long,
    ): FlagBeginResult {
        validateOpaqueId(requestId, "requestId")
        validateOpaqueId(replacementStoreEpoch, "storeEpoch")
        if (hasFutureCacheStorage(transaction)) return FlagBeginResult.Terminal
        authorization ?: return FlagBeginResult.Restricted(FlagRestrictionReason.MISSING, null)
        val authority = validateAuthorityForUse(transaction, authorization, wallNowEpochMillis)
        when (authority) {
            is AuthorityUse.Restricted -> return FlagBeginResult.Restricted(authority.reason.toPublicRestriction(), authority.barrierGeneration)
            AuthorityUse.Terminal -> return FlagBeginResult.Terminal
            is AuthorityUse.Allowed -> Unit
        }
        val witness: FlagEvaluationWitness
        val witnessHash: String
        val request: FlagRequest
        try {
            witness = witnessFrom(state, versions)
            witnessHash = FlagCodec.witnessHash(witness)
            request = FlagCodec.encodeRequest(requestId, witness)
        } catch (_: FlagProtocolException) {
            return FlagBeginResult.Restricted(FlagRestrictionReason.MALFORMED, authorization.barrierGeneration)
        }
        val currentMetadata = readMetadata(transaction)
        if (currentMetadata is MetadataRead.Future) return FlagBeginResult.Terminal

        var metadata = (currentMetadata as? MetadataRead.Current)?.metadata
        if (metadata != null && metadata.cache != null) {
            when (val cache = readCache(transaction, metadata.cache)) {
                is CacheRead.Current -> {
                    if (
                        cache.envelope.authorization != authorization.witness ||
                        cache.envelope.witness != witness ||
                        metadata.cache.barrierGeneration != authorization.barrierGeneration ||
                        metadata.cache.witnessHash != witnessHash
                    ) {
                        clearKnownCurrentCache(transaction)
                        metadata = null
                    } else {
                        val now = FlagExactInstant.fromEpochMillis(wallNowEpochMillis)
                        if (now >= cache.envelope.response.expiresAt) {
                            metadata = expireCache(transaction, metadata)
                        }
                    }
                }
                CacheRead.Corrupt -> {
                    clearKnownCurrentCache(transaction)
                    metadata = null
                }
                CacheRead.Future -> return FlagBeginResult.Terminal
            }
        } else if (currentMetadata is MetadataRead.Corrupt) {
            clearKnownCurrentCache(transaction)
            metadata = null
        }

        val next =
            allocateRequest(
                metadata,
                replacementStoreEpoch,
                requestId,
                authorization.barrierGeneration,
                witnessHash,
            )
        transaction.putFlagRow(metadataRow(next))
        return FlagBeginResult.Begun(
            FlagBegunRequest(
                token =
                    FlagRequestToken(
                        next.storeEpoch,
                        next.requestGeneration,
                        authorization.barrierGeneration,
                        requestId,
                        witnessHash,
                    ),
                authorization = authorization,
                witness = witness,
                request = request,
            ),
        )
    }

    fun commit(
        transaction: RuntimeQueueTransaction,
        begun: FlagBegunRequest,
        currentAuthorization: V1FlagAuthorizationSnapshot,
        state: PersistedCoreState,
        versions: RuntimeVersions,
        response: FlagResponse,
        wallNowEpochMillis: Long,
    ): FlagCommitStoreResult {
        if (hasFutureCacheStorage(transaction)) return FlagCommitStoreResult.Terminal
        when (val authority = validateAuthorityForUse(transaction, currentAuthorization, wallNowEpochMillis)) {
            is AuthorityUse.Restricted -> return FlagCommitStoreResult.Restricted(authority.reason)
            AuthorityUse.Terminal -> return FlagCommitStoreResult.Terminal
            is AuthorityUse.Allowed -> Unit
        }
        if (currentAuthorization != begun.authorization) return FlagCommitStoreResult.Stale
        val witness = witnessFrom(state, versions)
        if (witness != begun.witness || FlagCodec.witnessHash(witness) != begun.token.witnessHash) {
            conditionalDelete(transaction, begun.token)
            return FlagCommitStoreResult.Stale
        }
        if (
            response.requestId != begun.token.activeRequestId ||
            response.contextRevision != witness.contextRevision ||
            response.identityRevision != witness.identityRevision
        ) {
            return FlagCommitStoreResult.Stale
        }
        val now = FlagExactInstant.fromEpochMillis(wallNowEpochMillis)
        if (now >= response.expiresAt || now >= currentAuthorization.witness.configExpiresAt) {
            return FlagCommitStoreResult.Stale
        }
        val metadata = (readMetadata(transaction) as? MetadataRead.Current)?.metadata
            ?: return FlagCommitStoreResult.Stale
        if (!metadata.matches(begun.token)) return FlagCommitStoreResult.Stale

        val body =
            try {
                FlagCodec.encodeCache(FlagCacheEnvelope(currentAuthorization.witness, witness, response))
            } catch (_: FlagProtocolException) {
                return FlagCommitStoreResult.Stale
            }
        val recordId = cacheRecordId(begun.token, body)
        val chunks = body.asListOfChunks(MAX_CHUNK_BYTES)
        if (chunks.isEmpty() || chunks.size > MAX_CHUNKS) return FlagCommitStoreResult.Stale
        clearBody(transaction, metadata.cache)
        chunks.forEachIndexed { index, bytes ->
            transaction.putFlagRow(
                RuntimeFlagStoredRow(bodyKey(recordId, index), FLAG_STORAGE_SCHEMA_VERSION.toLong(), bytes),
            )
        }
        val pointer =
            CachePointer(
                storeEpoch = begun.token.storeEpoch,
                requestGeneration = begun.token.requestGeneration,
                recordId = recordId,
                bodyLength = body.size,
                bodySha256 = FlagJson.sha256(body),
                chunkCount = chunks.size,
                barrierGeneration = begun.token.barrierGeneration,
                witnessHash = begun.token.witnessHash,
            )
        transaction.putFlagRow(metadataRow(metadata.copy(activeRequest = null, cache = pointer)))
        return FlagCommitStoreResult.Updated
    }

    /**
     * Revalidates the exact durable authority, core witness, and active request immediately before
     * the owner permits transport dispatch. Begin alone is not send authority across an async gap.
     */
    fun authorizeSend(
        transaction: RuntimeQueueTransaction,
        begun: FlagBegunRequest,
        currentAuthorization: V1FlagAuthorizationSnapshot,
        state: PersistedCoreState,
        versions: RuntimeVersions,
        wallNowEpochMillis: Long,
    ): FlagPreSendResult {
        if (hasFutureCacheStorage(transaction)) return FlagPreSendResult.Terminal
        when (val authority = validateAuthorityForUse(transaction, currentAuthorization, wallNowEpochMillis)) {
            is AuthorityUse.Restricted ->
                return FlagPreSendResult.Restricted(authority.reason.toPublicRestriction())
            AuthorityUse.Terminal -> return FlagPreSendResult.Terminal
            is AuthorityUse.Allowed -> Unit
        }
        if (currentAuthorization != begun.authorization) return FlagPreSendResult.Stale
        val witness =
            try {
                witnessFrom(state, versions)
            } catch (_: FlagProtocolException) {
                return FlagPreSendResult.Stale
            }
        if (witness != begun.witness || FlagCodec.witnessHash(witness) != begun.token.witnessHash) {
            conditionalDelete(transaction, begun.token)
            return FlagPreSendResult.Stale
        }
        val metadata = (readMetadata(transaction) as? MetadataRead.Current)?.metadata
            ?: return FlagPreSendResult.Stale
        return if (metadata.matches(begun.token)) FlagPreSendResult.Current else FlagPreSendResult.Stale
    }

    /**
     * The required post-commit CAS. It is deliberately separate from [commit] so transport decode
     * and every clock sample stay outside SQLite transactions.
     */
    fun finalizeCommit(
        transaction: RuntimeQueueTransaction,
        begun: FlagBegunRequest,
        currentAuthorization: V1FlagAuthorizationSnapshot,
        state: PersistedCoreState,
        versions: RuntimeVersions,
        response: FlagResponse,
        wallNowEpochMillis: Long,
    ): FlagFinalizeStoreResult {
        if (hasFutureCacheStorage(transaction)) return FlagFinalizeStoreResult.Terminal
        when (val authority = validateAuthorityForUse(transaction, currentAuthorization, wallNowEpochMillis)) {
            is AuthorityUse.Restricted -> return FlagFinalizeStoreResult.Restricted(authority.reason.toPublicRestriction())
            AuthorityUse.Terminal -> return FlagFinalizeStoreResult.Terminal
            is AuthorityUse.Allowed -> Unit
        }
        if (currentAuthorization != begun.authorization) return FlagFinalizeStoreResult.Stale
        val witness =
            try {
                witnessFrom(state, versions)
            } catch (_: FlagProtocolException) {
                conditionalDeleteCommitted(transaction, begun)
                return FlagFinalizeStoreResult.Stale
            }
        if (witness != begun.witness || FlagCodec.witnessHash(witness) != begun.token.witnessHash) {
            conditionalDeleteCommitted(transaction, begun)
            return FlagFinalizeStoreResult.Stale
        }
        val metadata = (readMetadata(transaction) as? MetadataRead.Current)?.metadata
            ?: return FlagFinalizeStoreResult.Stale
        val pointer = metadata.cache
            ?: return FlagFinalizeStoreResult.Stale
        if (!metadata.matchesCommitted(begun.token) || !pointer.matches(begun.token)) {
            return FlagFinalizeStoreResult.Stale
        }
        return when (val cache = readCache(transaction, pointer)) {
            CacheRead.Future -> FlagFinalizeStoreResult.Terminal
            CacheRead.Corrupt -> {
                conditionalDeleteCommitted(transaction, begun)
                FlagFinalizeStoreResult.Stale
            }
            is CacheRead.Current -> {
                val envelope = cache.envelope
                if (
                    envelope.authorization != currentAuthorization.witness ||
                    envelope.witness != witness ||
                    envelope.response != response ||
                    envelope.response.requestId != begun.token.activeRequestId
                ) {
                    conditionalDeleteCommitted(transaction, begun)
                    return FlagFinalizeStoreResult.Stale
                }
                if (FlagExactInstant.fromEpochMillis(wallNowEpochMillis) >= envelope.response.expiresAt) {
                    expireCache(transaction, metadata)
                    return FlagFinalizeStoreResult.Stale
                }
                FlagFinalizeStoreResult.Current(cacheLeaseToken(metadata, pointer, envelope))
            }
        }
    }

    fun read(
        transaction: RuntimeQueueTransaction,
        authorization: V1FlagAuthorizationSnapshot,
        state: PersistedCoreState,
        versions: RuntimeVersions,
        key: String,
        wallNowEpochMillis: Long,
    ): FlagReadResult {
        if (key.isEmpty() || key.codePointCount(0, key.length) > FLAG_MAX_KEY_SCALARS) return FlagReadResult.Missing
        try {
            FlagJson.fromPlatform(key)
        } catch (_: FlagProtocolException) {
            return FlagReadResult.Missing
        }
        if (hasFutureCacheStorage(transaction)) return FlagReadResult.Terminal
        when (val authority = validateAuthorityForUse(transaction, authorization, wallNowEpochMillis)) {
            is AuthorityUse.Restricted -> return FlagReadResult.Restricted(authority.reason.toPublicRestriction())
            AuthorityUse.Terminal -> return FlagReadResult.Terminal
            is AuthorityUse.Allowed -> Unit
        }
        val metadataRead = readMetadata(transaction)
        if (metadataRead is MetadataRead.Future) return FlagReadResult.Terminal
        val metadata = (metadataRead as? MetadataRead.Current)?.metadata ?: return FlagReadResult.Missing
        val pointer = metadata.cache ?: return FlagReadResult.Missing
        return when (val cache = readCache(transaction, pointer)) {
            CacheRead.Future -> FlagReadResult.Terminal
            CacheRead.Corrupt -> {
                clearKnownCurrentCache(transaction)
                FlagReadResult.Missing
            }
            is CacheRead.Current -> {
                val witness = witnessFrom(state, versions)
                val envelope = cache.envelope
                if (
                    envelope.authorization != authorization.witness ||
                    envelope.witness != witness ||
                    pointer.barrierGeneration != authorization.barrierGeneration ||
                    pointer.witnessHash != FlagCodec.witnessHash(witness)
                ) {
                    clearKnownCurrentCache(transaction)
                    return FlagReadResult.Missing
                }
                val now = FlagExactInstant.fromEpochMillis(wallNowEpochMillis)
                if (now >= envelope.response.expiresAt) {
                    expireCache(transaction, metadata)
                    return FlagReadResult.Missing
                }
                val value =
                    envelope.response.flags.member(key)
                        ?: return FlagReadResult.CacheMiss(
                            envelope.response.expiresAt,
                            cacheLeaseToken(metadata, pointer, envelope),
                        )
                FlagReadResult.Found(
                    value.deepCopy(),
                    envelope.response.payloads.member(key)?.deepCopy(),
                    envelope.response.flagsRevision,
                    envelope.response.expiresAt,
                    cacheLeaseToken(metadata, pointer, envelope),
                )
            }
        }
    }

    private fun decideTransition(
        ledger: AuthorityLedger,
        decision: V1PreparedFlagDecision,
        boundary: V1FlagConfigBoundary?,
        wallNowEpochMillis: Long,
    ): Transition {
        if (boundary == null) {
            val reason = (decision as? V1PreparedFlagDecision.Restricted)?.reason ?: V1FlagProjectionRejection.MALFORMED
            return Transition.Replace(ledger) { current ->
                current.copy(initialized = true, allowedAuthorization = null, restriction = reason)
            }
        }
        val retained = ledger.newestBoundary
        if (retained != null) {
            val order = boundary.issuedAt.compareTo(retained.issuedAt)
            if (order < 0) return Transition.Preserve(ledger, V1FlagProjectionRejection.STALE)
            if (order == 0) {
                if (boundary.semanticHash != retained.semanticHash) {
                    return Transition.Replace(ledger) { current ->
                        current.copy(initialized = true, allowedAuthorization = null, restriction = V1FlagProjectionRejection.CONFLICT)
                    }
                }
                if (ledger.allowedAuthorization == null) return Transition.Preserve(ledger)
                val allowed = decision as? V1PreparedFlagDecision.Allowed
                    ?: return Transition.Replace(ledger) { current ->
                        current.copy(initialized = true, allowedAuthorization = null, restriction = restrictionOf(decision))
                    }
                if (allowed.witness != ledger.allowedAuthorization) {
                    return Transition.Replace(ledger) { current ->
                        current.copy(initialized = true, allowedAuthorization = null, restriction = V1FlagProjectionRejection.CONFLICT)
                    }
                }
                val now = FlagExactInstant.fromEpochMillis(wallNowEpochMillis)
                if (now >= allowed.witness.configExpiresAt) {
                    return Transition.Replace(ledger) { current ->
                        current.copy(initialized = true, allowedAuthorization = null, restriction = V1FlagProjectionRejection.EXPIRED)
                    }
                }
                return Transition.Preserve(ledger.copy(allowedAuthorization = allowed.witness, restriction = null))
            }
        }
        return Transition.Replace(ledger) { current ->
            when (decision) {
                is V1PreparedFlagDecision.Allowed -> {
                    val now = FlagExactInstant.fromEpochMillis(wallNowEpochMillis)
                    if (now >= decision.witness.configExpiresAt) {
                        current.copy(
                            initialized = true,
                            newestBoundary = boundary,
                            allowedAuthorization = null,
                            restriction = V1FlagProjectionRejection.EXPIRED,
                        )
                    } else {
                        current.copy(
                            initialized = true,
                            newestBoundary = boundary,
                            allowedAuthorization = decision.witness,
                            restriction = null,
                        )
                    }
                }
                is V1PreparedFlagDecision.Restricted ->
                    current.copy(
                        initialized = true,
                        newestBoundary = boundary,
                        allowedAuthorization = null,
                        restriction = decision.reason,
                    )
            }
        }
    }

    private fun restrictionOf(decision: V1PreparedFlagDecision): V1FlagProjectionRejection =
        (decision as? V1PreparedFlagDecision.Restricted)?.reason ?: V1FlagProjectionRejection.INACTIVE

    private fun incrementBarrierOrTerminal(ledger: AuthorityLedger): AuthorityLedger =
        if (ledger.barrierGeneration >= FLAG_MAX_SAFE_INTEGER) {
            ledger.copy(terminal = true, allowedAuthorization = null, restriction = V1FlagProjectionRejection.TERMINAL)
        } else {
            ledger.copy(barrierGeneration = ledger.barrierGeneration + 1)
        }

    private fun validateAuthorityForUse(
        transaction: RuntimeQueueTransaction,
        authorization: V1FlagAuthorizationSnapshot,
        wallNowEpochMillis: Long,
    ): AuthorityUse {
        val ledger = readAuthority(transaction) ?: return AuthorityUse.Terminal
        if (!ledger.initialized || ledger.terminal) return AuthorityUse.Terminal
        if (!wallNowEpochMillis.isSafePersistedInteger()) {
            return AuthorityUse.Restricted(V1FlagProjectionRejection.STORAGE, ledger.barrierGeneration)
        }
        val floor = ledger.lastObservedWallEpochMillis ?: return AuthorityUse.Terminal
        if (wallNowEpochMillis < floor) return AuthorityUse.Restricted(V1FlagProjectionRejection.STORAGE, ledger.barrierGeneration)
        var current = ledger.copy(lastObservedWallEpochMillis = wallNowEpochMillis)
        if (
            current.barrierGeneration != authorization.barrierGeneration ||
            current.allowedAuthorization != authorization.witness
        ) {
            transaction.putFlagRow(authorityRow(current))
            return AuthorityUse.Restricted(current.restriction ?: V1FlagProjectionRejection.STALE, current.barrierGeneration)
        }
        if (FlagExactInstant.fromEpochMillis(wallNowEpochMillis) >= authorization.witness.configExpiresAt) {
            current = incrementBarrierOrTerminal(current)
            if (!current.terminal) {
                current = current.copy(allowedAuthorization = null, restriction = V1FlagProjectionRejection.EXPIRED)
            }
            transaction.putFlagRow(authorityRow(current))
            clearKnownCurrentCache(transaction)
            return if (current.terminal) AuthorityUse.Terminal else AuthorityUse.Restricted(V1FlagProjectionRejection.EXPIRED, current.barrierGeneration)
        }
        transaction.putFlagRow(authorityRow(current))
        return AuthorityUse.Allowed(current)
    }

    private fun readAuthority(transaction: RuntimeQueueTransaction): AuthorityLedger? {
        val row = transaction.readFlagRow(AUTHORITY_KEY) ?: return null
        if (row.storageSchemaVersion != FLAG_STORAGE_SCHEMA_VERSION.toLong()) return null
        if (
            FlagJson.probeStoredSchema(row.payload, FLAG_MAX_WIRE_BYTES) !=
            FlagJson.StoredSchemaDisposition.CURRENT
        ) {
            return null
        }
        return try {
            AuthorityCodec.decode(row.payload)
        } catch (_: FlagProtocolException) {
            null
        }
    }

    private fun readMetadata(transaction: RuntimeQueueTransaction): MetadataRead {
        val row = transaction.readFlagRow(CACHE_METADATA_KEY) ?: return MetadataRead.Missing
        if (row.storageSchemaVersion > FLAG_STORAGE_SCHEMA_VERSION) return MetadataRead.Future
        if (row.storageSchemaVersion != FLAG_STORAGE_SCHEMA_VERSION.toLong()) return MetadataRead.Corrupt
        when (FlagJson.probeStoredSchema(row.payload, FLAG_MAX_WIRE_BYTES)) {
            FlagJson.StoredSchemaDisposition.FUTURE -> return MetadataRead.Future
            FlagJson.StoredSchemaDisposition.CORRUPT -> return MetadataRead.Corrupt
            FlagJson.StoredSchemaDisposition.CURRENT -> Unit
        }
        return try {
            MetadataRead.Current(CacheMetadataCodec.decode(row.payload))
        } catch (_: FlagProtocolException) {
            MetadataRead.Corrupt
        }
    }

    private fun readCache(
        transaction: RuntimeQueueTransaction,
        pointer: CachePointer,
    ): CacheRead {
        if (pointer.bodyLength !in 1..FLAG_MAX_CACHE_BYTES || pointer.chunkCount !in 1..MAX_CHUNKS) return CacheRead.Corrupt
        val output = ByteArrayOutputStream(minOf(pointer.bodyLength, FLAG_MAX_CACHE_BYTES))
        repeat(pointer.chunkCount) { index ->
            val row = transaction.readFlagRow(bodyKey(pointer.recordId, index)) ?: return CacheRead.Corrupt
            if (row.storageSchemaVersion > FLAG_STORAGE_SCHEMA_VERSION) return CacheRead.Future
            if (row.storageSchemaVersion != FLAG_STORAGE_SCHEMA_VERSION.toLong()) return CacheRead.Corrupt
            if (row.payload.isEmpty() || row.payload.size > MAX_CHUNK_BYTES) return CacheRead.Corrupt
            if (output.size() + row.payload.size > FLAG_MAX_CACHE_BYTES) return CacheRead.Corrupt
            output.write(row.payload)
        }
        val body = output.toByteArray()
        if (body.size != pointer.bodyLength || FlagJson.sha256(body) != pointer.bodySha256) return CacheRead.Corrupt
        when (FlagJson.probeStoredSchema(body, FLAG_MAX_CACHE_BYTES)) {
            FlagJson.StoredSchemaDisposition.FUTURE -> return CacheRead.Future
            FlagJson.StoredSchemaDisposition.CORRUPT -> return CacheRead.Corrupt
            FlagJson.StoredSchemaDisposition.CURRENT -> Unit
        }
        return try {
            CacheRead.Current(FlagCodec.decodeCache(body))
        } catch (_: FlagProtocolException) {
            CacheRead.Corrupt
        }
    }

    private fun allocateRequest(
        metadata: CacheMetadata?,
        replacementStoreEpoch: String,
        requestId: String,
        barrierGeneration: Long,
        witnessHash: String,
    ): CacheMetadata =
        when {
            metadata == null ->
                CacheMetadata(
                    replacementStoreEpoch,
                    1,
                    ActiveRequest(requestId, barrierGeneration, witnessHash),
                    null,
                )
            metadata.requestGeneration >= FLAG_MAX_SAFE_INTEGER ->
                CacheMetadata(
                    replacementStoreEpoch,
                    1,
                    ActiveRequest(requestId, barrierGeneration, witnessHash),
                    metadata.cache,
                )
            else ->
                metadata.copy(
                    requestGeneration = metadata.requestGeneration + 1,
                    activeRequest = ActiveRequest(requestId, barrierGeneration, witnessHash),
                )
        }

    private fun expireCache(
        transaction: RuntimeQueueTransaction,
        metadata: CacheMetadata,
    ): CacheMetadata {
        clearBody(transaction, metadata.cache)
        val expired =
            if (metadata.requestGeneration >= FLAG_MAX_SAFE_INTEGER) {
                // No injected fresh epoch is available on a read. Preserve the authority and make
                // the resettable cache unavailable until begin supplies one.
                metadata.copy(activeRequest = null, cache = null)
            } else {
                metadata.copy(requestGeneration = metadata.requestGeneration + 1, activeRequest = null, cache = null)
            }
        transaction.putFlagRow(metadataRow(expired))
        return expired
    }

    private fun conditionalDelete(transaction: RuntimeQueueTransaction, token: FlagRequestToken) {
        val metadata = (readMetadata(transaction) as? MetadataRead.Current)?.metadata ?: return
        if (!metadata.matches(token)) return
        clearBody(transaction, metadata.cache)
        transaction.deleteFlagRow(CACHE_METADATA_KEY)
    }

    private fun conditionalDeleteCommitted(
        transaction: RuntimeQueueTransaction,
        begun: FlagBegunRequest,
    ) {
        val metadata = (readMetadata(transaction) as? MetadataRead.Current)?.metadata ?: return
        val pointer = metadata.cache ?: return
        if (!metadata.matchesCommitted(begun.token) || !pointer.matches(begun.token)) return
        when (val cache = readCache(transaction, pointer)) {
            CacheRead.Future -> return
            CacheRead.Corrupt -> Unit
            is CacheRead.Current -> {
                if (
                    cache.envelope.authorization != begun.authorization.witness ||
                    cache.envelope.witness != begun.witness ||
                    cache.envelope.response.requestId != begun.token.activeRequestId
                ) {
                    return
                }
            }
        }
        clearBody(transaction, pointer)
        transaction.deleteFlagRow(CACHE_METADATA_KEY)
    }

    private fun clearKnownCurrentCache(transaction: RuntimeQueueTransaction) {
        if (hasFutureCacheStorage(transaction)) return
        val metadata = readMetadata(transaction)
        if (metadata is MetadataRead.Future) return
        (metadata as? MetadataRead.Current)?.metadata?.cache?.let { clearBody(transaction, it) }
        if (metadata !is MetadataRead.Missing) transaction.deleteFlagRow(CACHE_METADATA_KEY)
        val orphanKeys = mutableListOf<String>()
        transaction.scanFlagRows(BODY_PREFIX) { row ->
            if (row.storageSchemaVersion == FLAG_STORAGE_SCHEMA_VERSION.toLong()) orphanKeys += row.key
        }
        orphanKeys.forEach(transaction::deleteFlagRow)
    }

    private fun hasFutureCacheStorage(transaction: RuntimeQueueTransaction): Boolean {
        when (readMetadata(transaction)) {
            MetadataRead.Future -> return true
            is MetadataRead.Current,
            MetadataRead.Corrupt,
            MetadataRead.Missing,
            -> Unit
        }
        val bodies = GroupedBodySchemaPreclassifier()
        transaction.scanFlagRows(BODY_PREFIX, bodies::accept)
        return bodies.mustPreserve()
    }

    /**
     * Physical cache rows are raw chunks, not independent JSON documents. Classification must
     * therefore happen over one contiguous record-id group. The probe retains constant memory and
     * treats gaps, truncated prefixes, and an exhausted scan budget as opaque so cleanup can never
     * erase bytes that may belong to a future envelope.
     */
    private class GroupedBodySchemaPreclassifier {
        private var recordId: String? = null
        private var expectedIndex: Long = 0
        private var probe: StreamingStoredSchemaProbe? = null
        private var preserve: Boolean = false

        fun accept(row: RuntimeFlagStoredRow) {
            if (preserve) return
            if (row.storageSchemaVersion > FLAG_STORAGE_SCHEMA_VERSION.toLong()) {
                preserve = true
                return
            }
            if (row.storageSchemaVersion != FLAG_STORAGE_SCHEMA_VERSION.toLong()) {
                preserve = true
                return
            }
            val address = bodyRowAddress(row.key)
            if (address == null) {
                preserve = true
                return
            }
            if (recordId != address.recordId) {
                finishGroup()
                if (preserve) return
                if (address.index != 0L) {
                    preserve = true
                    return
                }
                recordId = address.recordId
                expectedIndex = 0
                probe = StreamingStoredSchemaProbe()
            }
            if (address.index != expectedIndex) {
                preserve = true
                return
            }
            expectedIndex =
                if (expectedIndex == Long.MAX_VALUE) {
                    preserve = true
                    return
                } else {
                    expectedIndex + 1
                }
            probe?.feed(row.payload)
        }

        fun mustPreserve(): Boolean {
            finishGroup()
            return preserve
        }

        private fun finishGroup() {
            val disposition = probe?.finish() ?: return
            if (
                disposition == StreamingSchemaDisposition.FUTURE ||
                disposition == StreamingSchemaDisposition.OPAQUE
            ) {
                preserve = true
            }
            probe = null
            recordId = null
            expectedIndex = 0
        }

        private data class BodyRowAddress(val recordId: String, val index: Long)

        private fun bodyRowAddress(key: String): BodyRowAddress? {
            if (!key.startsWith(BODY_PREFIX)) return null
            val suffix = key.substring(BODY_PREFIX.length)
            val separator = suffix.lastIndexOf(':')
            if (separator <= 0 || separator == suffix.lastIndex) return null
            val indexToken = suffix.substring(separator + 1)
            if (indexToken.length < 4 || indexToken.any { it !in '0'..'9' }) return null
            val index = indexToken.toLongOrNull() ?: return null
            return BodyRowAddress(suffix.substring(0, separator), index)
        }
    }

    private enum class StreamingSchemaDisposition {
        PENDING,
        CURRENT,
        FUTURE,
        CORRUPT,
        OPAQUE,
    }

    /** Byte-stream top-level `schemaVersion` probe. No body-sized buffer or recursive stack. */
    private class StreamingStoredSchemaProbe {
        private var disposition: StreamingSchemaDisposition = StreamingSchemaDisposition.PENDING
        private var observedBytes: Long = 0
        private var rootStarted: Boolean = false
        private var depth: Int = 0
        private var expectingKey: Boolean = false
        private var inString: Boolean = false
        private var stringIsKey: Boolean = false
        private var escaped: Boolean = false
        private var unicodeDigitsRemaining: Int = 0
        private var unicodeValue: Int = 0
        private var keyMatchesSchema: Boolean = false
        private var keyIndex: Int = 0
        private var schemaPhase: SchemaPhase = SchemaPhase.NONE
        private var schemaDigits: Int = 0
        private var schemaCategory: Int = 0

        fun feed(bytes: ByteArray) {
            if (disposition != StreamingSchemaDisposition.PENDING) return
            var index = 0
            while (index < bytes.size && disposition == StreamingSchemaDisposition.PENDING) {
                if (observedBytes >= MAX_GROUP_SCHEMA_PROBE_BYTES) {
                    disposition = StreamingSchemaDisposition.OPAQUE
                    return
                }
                consume(bytes[index].toInt() and 0xff)
                observedBytes += 1
                index += 1
            }
        }

        fun finish(): StreamingSchemaDisposition =
            if (disposition == StreamingSchemaDisposition.PENDING) {
                StreamingSchemaDisposition.OPAQUE
            } else {
                disposition
            }

        private fun consume(byte: Int) {
            if (inString) {
                consumeStringByte(byte)
                return
            }
            when (schemaPhase) {
                SchemaPhase.EXPECT_COLON -> {
                    when {
                        isJsonWhitespace(byte) -> Unit
                        byte == ':'.code -> schemaPhase = SchemaPhase.EXPECT_VALUE
                        else -> corrupt()
                    }
                    return
                }
                SchemaPhase.EXPECT_VALUE -> {
                    when {
                        isJsonWhitespace(byte) -> Unit
                        byte in '0'.code..'9'.code -> {
                            schemaPhase = SchemaPhase.IN_INTEGER
                            consumeSchemaDigit(byte)
                        }
                        else -> corrupt()
                    }
                    return
                }
                SchemaPhase.IN_INTEGER -> {
                    when {
                        byte in '0'.code..'9'.code -> consumeSchemaDigit(byte)
                        isJsonWhitespace(byte) || byte == ','.code || byte == '}'.code ->
                            finishSchemaInteger()
                        else -> corrupt()
                    }
                    return
                }
                SchemaPhase.NONE -> Unit
            }

            if (!rootStarted) {
                if (isJsonWhitespace(byte)) return
                if (byte != '{'.code) {
                    corrupt()
                    return
                }
                rootStarted = true
                depth = 1
                expectingKey = true
                return
            }

            when (byte) {
                '"'.code -> {
                    inString = true
                    stringIsKey = depth == 1 && expectingKey
                    keyMatchesSchema = stringIsKey
                    keyIndex = 0
                }
                '{'.code,
                '['.code,
                -> depth += 1
                '}'.code,
                ']'.code,
                -> {
                    depth -= 1
                    if (depth <= 0) corrupt()
                }
                ','.code -> if (depth == 1) expectingKey = true
                else -> if (byte >= 0x80 && depth == 0) corrupt()
            }
        }

        private fun consumeStringByte(byte: Int) {
            if (unicodeDigitsRemaining > 0) {
                val digit = hexDigit(byte)
                if (digit < 0) {
                    corrupt()
                    return
                }
                unicodeValue = (unicodeValue shl 4) or digit
                unicodeDigitsRemaining -= 1
                if (unicodeDigitsRemaining == 0 && stringIsKey) compareKeyCodePoint(unicodeValue)
                return
            }
            if (escaped) {
                escaped = false
                when (byte) {
                    '"'.code,
                    '\\'.code,
                    '/'.code,
                    -> if (stringIsKey) compareKeyCodePoint(byte)
                    'b'.code -> if (stringIsKey) compareKeyCodePoint('\b'.code)
                    'f'.code -> if (stringIsKey) compareKeyCodePoint('\u000c'.code)
                    'n'.code -> if (stringIsKey) compareKeyCodePoint('\n'.code)
                    'r'.code -> if (stringIsKey) compareKeyCodePoint('\r'.code)
                    't'.code -> if (stringIsKey) compareKeyCodePoint('\t'.code)
                    'u'.code -> {
                        unicodeDigitsRemaining = 4
                        unicodeValue = 0
                    }
                    else -> corrupt()
                }
                return
            }
            when {
                byte == '\\'.code -> escaped = true
                byte == '"'.code -> {
                    inString = false
                    if (stringIsKey) {
                        expectingKey = false
                        if (keyMatchesSchema && keyIndex == SCHEMA_VERSION_KEY.length) {
                            schemaPhase = SchemaPhase.EXPECT_COLON
                        }
                    }
                    stringIsKey = false
                }
                byte < 0x20 -> corrupt()
                stringIsKey -> compareKeyCodePoint(byte)
            }
        }

        private fun compareKeyCodePoint(codePoint: Int) {
            if (!keyMatchesSchema) return
            if (
                keyIndex >= SCHEMA_VERSION_KEY.length ||
                codePoint != SCHEMA_VERSION_KEY[keyIndex].code
            ) {
                keyMatchesSchema = false
            } else {
                keyIndex += 1
            }
        }

        private fun consumeSchemaDigit(byte: Int) {
            val digit = byte - '0'.code
            if (schemaDigits == 0) {
                schemaCategory =
                    when (digit) {
                        0 -> 0
                        1 -> 1
                        else -> 2
                    }
            } else {
                if (schemaCategory == 0) {
                    corrupt()
                    return
                }
                schemaCategory = 2
            }
            schemaDigits += 1
        }

        private fun finishSchemaInteger() {
            disposition =
                when {
                    schemaDigits == 0 || schemaCategory == 0 -> StreamingSchemaDisposition.CORRUPT
                    schemaCategory == 1 -> StreamingSchemaDisposition.CURRENT
                    else -> StreamingSchemaDisposition.FUTURE
                }
        }

        private fun corrupt() {
            disposition = StreamingSchemaDisposition.CORRUPT
        }

        private fun isJsonWhitespace(byte: Int): Boolean =
            byte == ' '.code || byte == '\n'.code || byte == '\r'.code || byte == '\t'.code

        private fun hexDigit(byte: Int): Int =
            when (byte) {
                in '0'.code..'9'.code -> byte - '0'.code
                in 'a'.code..'f'.code -> byte - 'a'.code + 10
                in 'A'.code..'F'.code -> byte - 'A'.code + 10
                else -> -1
            }

        private enum class SchemaPhase {
            NONE,
            EXPECT_COLON,
            EXPECT_VALUE,
            IN_INTEGER,
        }

        private companion object {
            const val SCHEMA_VERSION_KEY: String = "schemaVersion"
        }
    }

    private fun clearBody(transaction: RuntimeQueueTransaction, pointer: CachePointer?) {
        if (pointer == null || pointer.chunkCount !in 1..MAX_CHUNKS) return
        repeat(pointer.chunkCount) { index ->
            val key = bodyKey(pointer.recordId, index)
            val row = transaction.readFlagRow(key)
            if (row?.storageSchemaVersion == FLAG_STORAGE_SCHEMA_VERSION.toLong()) transaction.deleteFlagRow(key)
        }
    }

    private fun witnessFrom(
        state: PersistedCoreState,
        versions: RuntimeVersions,
    ): FlagEvaluationWitness {
        val identity = state.identity
        val groups = FlagJson.fromPlatform(identity.groups) as FlagJsonValue.ObjectValue
        val associatedProperties = state.flagContext.groupProperties.filterKeys { it in identity.groups }
        return FlagEvaluationWitness(
            anonymousId = identity.anonymousId,
            userId = identity.userId,
            identityRevision = identity.revision,
            contextRevision = identity.contextRevision,
            optedOut = identity.optedOut,
            personProperties = FlagJson.fromPlatform(state.flagContext.personProperties) as FlagJsonValue.ObjectValue,
            groups = groups,
            groupProperties = FlagJson.fromPlatform(associatedProperties) as FlagJsonValue.ObjectValue,
            versions = versions.copy(runtime = versions.runtime.copy(), facade = versions.facade.copy()),
        )
    }

    private fun CacheMetadata.matches(token: FlagRequestToken): Boolean =
        storeEpoch == token.storeEpoch && requestGeneration == token.requestGeneration &&
            activeRequest == ActiveRequest(token.activeRequestId, token.barrierGeneration, token.witnessHash)

    private fun CacheMetadata.matchesCommitted(token: FlagRequestToken): Boolean =
        storeEpoch == token.storeEpoch && requestGeneration == token.requestGeneration && activeRequest == null

    private fun CacheMetadata.matches(token: FlagCacheLeaseToken): Boolean =
        cache?.matches(token) == true

    private fun CachePointer.matches(token: FlagRequestToken): Boolean =
        storeEpoch == token.storeEpoch && requestGeneration == token.requestGeneration &&
            barrierGeneration == token.barrierGeneration && witnessHash == token.witnessHash

    private fun CachePointer.matches(token: FlagCacheLeaseToken): Boolean =
        storeEpoch == token.storeEpoch && requestGeneration == token.requestGeneration &&
            recordId == token.recordId && barrierGeneration == token.barrierGeneration && witnessHash == token.witnessHash

    private fun cacheLeaseToken(
        metadata: CacheMetadata,
        pointer: CachePointer,
        envelope: FlagCacheEnvelope,
    ): FlagCacheLeaseToken =
        FlagCacheLeaseToken(
            storeEpoch = pointer.storeEpoch,
            requestGeneration = pointer.requestGeneration,
            recordId = pointer.recordId,
            barrierGeneration = pointer.barrierGeneration,
            witnessHash = pointer.witnessHash,
            flagsRevision = envelope.response.flagsRevision,
            responseExpiresAt = envelope.response.expiresAt,
        )

    private fun authorityRow(ledger: AuthorityLedger): RuntimeFlagStoredRow =
        RuntimeFlagStoredRow(AUTHORITY_KEY, FLAG_STORAGE_SCHEMA_VERSION.toLong(), AuthorityCodec.encode(ledger))

    private fun metadataRow(metadata: CacheMetadata): RuntimeFlagStoredRow =
        RuntimeFlagStoredRow(CACHE_METADATA_KEY, FLAG_STORAGE_SCHEMA_VERSION.toLong(), CacheMetadataCodec.encode(metadata))

    private fun bodyKey(recordId: String, index: Int): String = "$BODY_PREFIX$recordId:${index.toString().padStart(4, '0')}"

    private fun cacheRecordId(token: FlagRequestToken, body: ByteArray): String =
        FlagJson.sha256(
            (token.storeEpoch + ":" + token.requestGeneration + ":" + token.activeRequestId + ":" + FlagJson.sha256(body))
                .toByteArray(StandardCharsets.UTF_8),
        ).removePrefix("sha256:")

    private fun ByteArray.asListOfChunks(size: Int): List<ByteArray> {
        if (isEmpty()) return emptyList()
        val chunks = ArrayList<ByteArray>((this.size + size - 1) / size)
        var offset = 0
        while (offset < this.size) {
            val end = minOf(this.size, offset + size)
            chunks += copyOfRange(offset, end)
            offset = end
        }
        return Collections.unmodifiableList(chunks)
    }

    private fun validateOpaqueId(value: String, path: String) {
        if (value.isEmpty() || value.codePointCount(0, value.length) > 256) protocol("$path is outside its bounds")
        FlagJson.fromPlatform(value)
    }

    private fun Long.isSafePersistedInteger(): Boolean = this in -FLAG_MAX_SAFE_INTEGER..FLAG_MAX_SAFE_INTEGER

    private fun V1FlagProjectionRejection.toPublicRestriction(): FlagRestrictionReason =
        when (this) {
            V1FlagProjectionRejection.MISSING -> FlagRestrictionReason.MISSING
            V1FlagProjectionRejection.MALFORMED -> FlagRestrictionReason.MALFORMED
            V1FlagProjectionRejection.UNSUPPORTED_SCHEMA -> FlagRestrictionReason.UNSUPPORTED_SCHEMA
            V1FlagProjectionRejection.EXPIRED -> FlagRestrictionReason.CONFIG_EXPIRED
            V1FlagProjectionRejection.INACTIVE -> FlagRestrictionReason.DISABLED
            V1FlagProjectionRejection.REVOKED -> FlagRestrictionReason.REVOKED
            V1FlagProjectionRejection.FLAGS_DISABLED -> FlagRestrictionReason.FLAGS_DISABLED
            V1FlagProjectionRejection.UNAUTHORIZED -> FlagRestrictionReason.UNAUTHORIZED
            V1FlagProjectionRejection.STALE -> FlagRestrictionReason.STALE
            V1FlagProjectionRejection.CONFLICT -> FlagRestrictionReason.CONFLICT
            V1FlagProjectionRejection.STORAGE -> FlagRestrictionReason.WALL_ROLLBACK
            V1FlagProjectionRejection.TERMINAL -> FlagRestrictionReason.TERMINAL
        }

    private sealed interface Transition {
        data class Preserve(val ledger: AuthorityLedger, val reason: V1FlagProjectionRejection? = null) : Transition

        data class Replace(
            val ledger: AuthorityLedger,
            val apply: (AuthorityLedger) -> AuthorityLedger,
        ) : Transition
    }

    private sealed interface AuthorityUse {
        data class Allowed(val ledger: AuthorityLedger) : AuthorityUse

        data class Restricted(val reason: V1FlagProjectionRejection, val barrierGeneration: Long) : AuthorityUse

        data object Terminal : AuthorityUse
    }

    private sealed interface MetadataRead {
        data object Missing : MetadataRead

        data class Current(val metadata: CacheMetadata) : MetadataRead

        data object Corrupt : MetadataRead

        data object Future : MetadataRead
    }

    private sealed interface CacheRead {
        data class Current(val envelope: FlagCacheEnvelope) : CacheRead

        data object Corrupt : CacheRead

        data object Future : CacheRead
    }

    private data class AuthorityLedger(
        val trustedSiteKey: String,
        val siteNamespaceDigest: String,
        val siteId: String?,
        val initialized: Boolean,
        val terminal: Boolean,
        val barrierGeneration: Long,
        val newestBoundary: V1FlagConfigBoundary?,
        val allowedAuthorization: V1FlagConfigWitness?,
        val restriction: V1FlagProjectionRejection?,
        val lastObservedWallEpochMillis: Long?,
    ) {
        companion object {
            fun uninitialized(trustedSiteKey: String, siteNamespaceDigest: String): AuthorityLedger =
                AuthorityLedger(
                    trustedSiteKey,
                    siteNamespaceDigest,
                    null,
                    false,
                    false,
                    0,
                    null,
                    null,
                    null,
                    null,
                )
        }
    }

    private data class ActiveRequest(
        val id: String,
        val barrierGeneration: Long,
        val witnessHash: String,
    )

    private data class CachePointer(
        val storeEpoch: String,
        val requestGeneration: Long,
        val recordId: String,
        val bodyLength: Int,
        val bodySha256: String,
        val chunkCount: Int,
        val barrierGeneration: Long,
        val witnessHash: String,
    )

    private data class CacheMetadata(
        val storeEpoch: String,
        val requestGeneration: Long,
        val activeRequest: ActiveRequest?,
        val cache: CachePointer?,
    )

    private object AuthorityCodec {
        fun encode(ledger: AuthorityLedger): ByteArray =
            FlagJson.canonicalBytes(
                obj(
                    "schemaVersion" to number(1),
                    "trustedSiteKey" to string(ledger.trustedSiteKey),
                    "siteNamespaceDigest" to string(ledger.siteNamespaceDigest),
                    "siteId" to (ledger.siteId?.let(::string) ?: FlagJsonValue.NullValue),
                    "initialized" to FlagJsonValue.BooleanValue(ledger.initialized),
                    "terminal" to FlagJsonValue.BooleanValue(ledger.terminal),
                    "barrierGeneration" to number(ledger.barrierGeneration),
                    "newestBoundary" to (ledger.newestBoundary?.let(::encodeBoundary) ?: FlagJsonValue.NullValue),
                    "allowedAuthorization" to
                        (ledger.allowedAuthorization?.let(FlagCodec::encodeAuthorization) ?: FlagJsonValue.NullValue),
                    "restriction" to (ledger.restriction?.name?.let(::string) ?: FlagJsonValue.NullValue),
                    "lastObservedWall" to
                        (ledger.lastObservedWallEpochMillis?.let(::number) ?: FlagJsonValue.NullValue),
                ),
            )

        fun decode(bytes: ByteArray): AuthorityLedger {
            val root = FlagJson.requiredObject(FlagJson.parse(bytes), "authority")
            FlagJson.requireFields(
                root,
                setOf(
                    "schemaVersion",
                    "trustedSiteKey",
                    "siteNamespaceDigest",
                    "siteId",
                    "initialized",
                    "terminal",
                    "barrierGeneration",
                    "newestBoundary",
                    "allowedAuthorization",
                    "restriction",
                    "lastObservedWall",
                ),
                emptySet(),
                "authority",
            )
            if (FlagJson.exactLong(root.member("schemaVersion"), "authority.schemaVersion") != 1L) protocol("authority schema unsupported")
            val trustedSiteKey = FlagJson.requiredString(root.member("trustedSiteKey"), "authority.trustedSiteKey", 1, 512)
            val siteNamespaceDigest =
                FlagJson.requiredString(root.member("siteNamespaceDigest"), "authority.siteNamespaceDigest", 64, 64)
            if (!HEX_SHA256.matches(siteNamespaceDigest)) protocol("authority namespace digest is malformed")
            val siteId = nullableString(root.member("siteId"), "authority.siteId")
            val generation = FlagJson.exactLong(root.member("barrierGeneration"), "authority.barrierGeneration")
            if (generation !in 0..FLAG_MAX_SAFE_INTEGER) protocol("authority generation invalid")
            val boundary = nullableObject(root.member("newestBoundary"), "authority.newestBoundary")?.let(::decodeBoundary)
            val authorization = nullableObject(root.member("allowedAuthorization"), "authority.allowedAuthorization")
                ?.let(FlagCodec::decodeAuthorization)
            val restriction = nullableString(root.member("restriction"), "authority.restriction")?.let { source ->
                V1FlagProjectionRejection.values().firstOrNull { it.name == source }
                    ?: protocol("authority restriction unsupported")
            }
            val wall = nullableLong(root.member("lastObservedWall"), "authority.lastObservedWall")
            val initialized = FlagJson.requiredBoolean(root.member("initialized"), "authority.initialized")
            val terminal = FlagJson.requiredBoolean(root.member("terminal"), "authority.terminal")
            if (!initialized && (generation != 0L || boundary != null || authorization != null || restriction != null || wall != null || terminal || siteId != null)) {
                protocol("uninitialized authority contains state")
            }
            if (initialized && (generation == 0L || wall == null || !wall.isSafePersistedInteger())) {
                protocol("initialized authority is missing its durable generation or wall floor")
            }
            if (terminal != (restriction == V1FlagProjectionRejection.TERMINAL)) {
                protocol("terminal authority is inconsistent")
            }
            if (terminal && authorization != null) protocol("terminal authority contains authorization")
            if (authorization != null && (boundary == null || restriction != null || generation == 0L)) {
                protocol("allowed authority is inconsistent")
            }
            if (
                authorization != null &&
                (
                    authorization.trustedSiteKey != trustedSiteKey ||
                        authorization.siteNamespaceDigest != siteNamespaceDigest ||
                        authorization.siteId != siteId
                )
            ) {
                protocol("allowed authority does not match its persisted owner")
            }
            if (
                authorization != null &&
                (
                    boundary?.revision != authorization.configRevision ||
                        boundary.issuedAt != authorization.configIssuedAt ||
                        boundary.semanticHash != authorization.configSemanticHash
                )
            ) {
                protocol("allowed authority does not match its ordering boundary")
            }
            if (authorization == null && initialized && !terminal && restriction == null) {
                protocol("restrictive authority is missing its reason")
            }
            return AuthorityLedger(
                trustedSiteKey,
                siteNamespaceDigest,
                siteId,
                initialized,
                terminal,
                generation,
                boundary,
                authorization,
                restriction,
                wall,
            )
        }

        private fun encodeBoundary(boundary: V1FlagConfigBoundary): FlagJsonValue.ObjectValue =
            obj(
                "revision" to string(boundary.revision),
                "issuedAt" to encodeInstant(boundary.issuedAt),
                "semanticHash" to string(boundary.semanticHash),
            )

        private fun decodeBoundary(root: FlagJsonValue.ObjectValue): V1FlagConfigBoundary {
            FlagJson.requireFields(root, setOf("revision", "issuedAt", "semanticHash"), emptySet(), "authority.boundary")
            val semanticHash =
                FlagJson.requiredString(root.member("semanticHash"), "authority.boundary.semanticHash", 71, 71)
            if (!PREFIXED_SHA256.matches(semanticHash)) protocol("authority boundary hash is malformed")
            return V1FlagConfigBoundary(
                FlagJson.requiredString(root.member("revision"), "authority.boundary.revision", 1, 128),
                decodeInstant(FlagJson.requiredObject(root.member("issuedAt"), "authority.boundary.issuedAt")),
                semanticHash,
            )
        }
    }

    private object CacheMetadataCodec {
        fun encode(metadata: CacheMetadata): ByteArray =
            FlagJson.canonicalBytes(
                obj(
                    "schemaVersion" to number(1),
                    "storeEpoch" to string(metadata.storeEpoch),
                    "requestGeneration" to number(metadata.requestGeneration),
                    "activeRequest" to (metadata.activeRequest?.let(::encodeActiveRequest) ?: FlagJsonValue.NullValue),
                    "cache" to (metadata.cache?.let(::encodePointer) ?: FlagJsonValue.NullValue),
                ),
            )

        fun decode(bytes: ByteArray): CacheMetadata {
            val root = FlagJson.requiredObject(FlagJson.parse(bytes), "cache metadata")
            FlagJson.requireFields(root, setOf("schemaVersion", "storeEpoch", "requestGeneration", "activeRequest", "cache"), emptySet(), "cache metadata")
            if (FlagJson.exactLong(root.member("schemaVersion"), "cache metadata.schemaVersion") != 1L) protocol("cache metadata schema unsupported")
            val generation = FlagJson.exactLong(root.member("requestGeneration"), "cache metadata.requestGeneration")
            if (generation !in 1..FLAG_MAX_SAFE_INTEGER) protocol("cache metadata generation invalid")
            return CacheMetadata(
                FlagJson.requiredString(root.member("storeEpoch"), "cache metadata.storeEpoch", 1, 256),
                generation,
                nullableObject(root.member("activeRequest"), "cache metadata.activeRequest")?.let(::decodeActiveRequest),
                nullableObject(root.member("cache"), "cache metadata.cache")?.let(::decodePointer),
            )
        }

        private fun encodeActiveRequest(active: ActiveRequest): FlagJsonValue.ObjectValue =
            obj(
                "id" to string(active.id),
                "barrierGeneration" to number(active.barrierGeneration),
                "witnessHash" to string(active.witnessHash),
            )

        private fun decodeActiveRequest(root: FlagJsonValue.ObjectValue): ActiveRequest {
            FlagJson.requireFields(
                root,
                setOf("id", "barrierGeneration", "witnessHash"),
                emptySet(),
                "cache metadata.activeRequest",
            )
            val barrier =
                FlagJson.exactLong(
                    root.member("barrierGeneration"),
                    "cache metadata.activeRequest.barrierGeneration",
                )
            if (barrier !in 1..FLAG_MAX_SAFE_INTEGER) protocol("active request barrier is invalid")
            val witnessHash =
                FlagJson.requiredString(
                    root.member("witnessHash"),
                    "cache metadata.activeRequest.witnessHash",
                    71,
                    71,
                )
            if (!PREFIXED_SHA256.matches(witnessHash)) protocol("active request witness hash is malformed")
            return ActiveRequest(
                FlagJson.requiredString(root.member("id"), "cache metadata.activeRequest.id", 1, 256),
                barrier,
                witnessHash,
            )
        }

        private fun encodePointer(pointer: CachePointer): FlagJsonValue.ObjectValue =
            obj(
                "storeEpoch" to string(pointer.storeEpoch),
                "requestGeneration" to number(pointer.requestGeneration),
                "recordId" to string(pointer.recordId),
                "bodyLength" to number(pointer.bodyLength.toLong()),
                "bodySha256" to string(pointer.bodySha256),
                "chunkCount" to number(pointer.chunkCount.toLong()),
                "barrierGeneration" to number(pointer.barrierGeneration),
                "witnessHash" to string(pointer.witnessHash),
            )

        private fun decodePointer(root: FlagJsonValue.ObjectValue): CachePointer {
            FlagJson.requireFields(
                root,
                setOf(
                    "storeEpoch",
                    "requestGeneration",
                    "recordId",
                    "bodyLength",
                    "bodySha256",
                    "chunkCount",
                    "barrierGeneration",
                    "witnessHash",
                ),
                emptySet(),
                "cache pointer",
            )
            val bodyLength = FlagJson.exactLong(root.member("bodyLength"), "cache pointer.bodyLength")
            val chunkCount = FlagJson.exactLong(root.member("chunkCount"), "cache pointer.chunkCount")
            val barrier = FlagJson.exactLong(root.member("barrierGeneration"), "cache pointer.barrierGeneration")
            val requestGeneration =
                FlagJson.exactLong(root.member("requestGeneration"), "cache pointer.requestGeneration")
            if (
                bodyLength !in 1..FLAG_MAX_CACHE_BYTES.toLong() ||
                chunkCount !in 1..MAX_CHUNKS.toLong() ||
                barrier !in 1..FLAG_MAX_SAFE_INTEGER ||
                requestGeneration !in 1..FLAG_MAX_SAFE_INTEGER
            ) {
                protocol("cache pointer bounds are invalid")
            }
            val bodySha256 = FlagJson.requiredString(root.member("bodySha256"), "cache pointer.bodySha256", 71, 71)
            val witnessHash = FlagJson.requiredString(root.member("witnessHash"), "cache pointer.witnessHash", 71, 71)
            if (!PREFIXED_SHA256.matches(bodySha256) || !PREFIXED_SHA256.matches(witnessHash)) {
                protocol("cache pointer hash is malformed")
            }
            return CachePointer(
                FlagJson.requiredString(root.member("storeEpoch"), "cache pointer.storeEpoch", 1, 256),
                requestGeneration,
                FlagJson.requiredString(root.member("recordId"), "cache pointer.recordId", 1, 256),
                bodyLength.toInt(),
                bodySha256,
                chunkCount.toInt(),
                barrier,
                witnessHash,
            )
        }
    }

    private fun encodeInstant(instant: FlagExactInstant): FlagJsonValue.ObjectValue =
        obj(
            "source" to string(instant.source),
            "epochWholeSecond" to number(instant.epochWholeSecond),
            "fractionalDigits" to string(instant.fractionalDigits),
            "leapSecond" to FlagJsonValue.BooleanValue(instant.isLeapSecond),
        )

    private fun decodeInstant(root: FlagJsonValue.ObjectValue): FlagExactInstant {
        FlagJson.requireFields(root, setOf("source", "epochWholeSecond", "fractionalDigits", "leapSecond"), emptySet(), "instant")
        val source = FlagJson.requiredString(root.member("source"), "instant.source", 1, 128)
        val parsed =
            try {
                FlagExactInstant.parse(source)
            } catch (error: IllegalArgumentException) {
                throw FlagProtocolException("Persisted flag instant is malformed", error)
            }
        val stored =
            FlagExactInstant(
                source,
                FlagJson.exactLong(root.member("epochWholeSecond"), "instant.epochWholeSecond"),
                FlagJson.requiredString(root.member("fractionalDigits"), "instant.fractionalDigits", 0, 128),
                FlagJson.requiredBoolean(root.member("leapSecond"), "instant.leapSecond"),
            )
        if (parsed != stored) protocol("instant source and exact fields disagree")
        return stored
    }

    private fun nullableObject(value: FlagJsonValue?, path: String): FlagJsonValue.ObjectValue? =
        when (value) {
            FlagJsonValue.NullValue -> null
            else -> FlagJson.requiredObject(value, path)
        }

    private fun nullableString(value: FlagJsonValue?, path: String): String? =
        when (value) {
            FlagJsonValue.NullValue -> null
            else -> FlagJson.requiredString(value, path, 1, 256)
        }

    private fun nullableLong(value: FlagJsonValue?, path: String): Long? =
        when (value) {
            FlagJsonValue.NullValue -> null
            else -> FlagJson.exactLong(value, path)
        }

    private fun obj(vararg members: Pair<String, FlagJsonValue>): FlagJsonValue.ObjectValue =
        FlagJsonValue.ObjectValue(
            Collections.unmodifiableList(members.map { (key, value) -> FlagJsonValue.ObjectValue.Member(key, value) }),
        )

    private fun string(value: String): FlagJsonValue.StringValue = FlagJsonValue.StringValue(value)

    private fun number(value: Long): FlagJsonValue.NumberValue {
        if (value !in -FLAG_MAX_SAFE_INTEGER..FLAG_MAX_SAFE_INTEGER) protocol("Persisted flag integer exceeds safe range")
        return FlagJsonValue.NumberValue(value.toString(), value.toDouble())
    }

    private fun protocol(message: String): Nothing = throw FlagProtocolException(message)

    private val PREFIXED_SHA256 = Regex("^sha256:[0-9a-f]{64}$")
    private val HEX_SHA256 = Regex("^[0-9a-f]{64}$")
}
