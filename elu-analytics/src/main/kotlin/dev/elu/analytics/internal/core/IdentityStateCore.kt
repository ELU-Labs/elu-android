package dev.elu.analytics.internal.core

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal fun interface CoreIdentifierGenerator {
    fun next(prefix: String): String
}

internal fun interface CoreTimestampProvider {
    fun now(): String
}

internal object UuidCoreIdentifierGenerator : CoreIdentifierGenerator {
    override fun next(prefix: String): String =
        prefix + UUID.randomUUID().toString().replace("-", "")
}

internal object SystemCoreTimestampProvider : CoreTimestampProvider {
    override fun now(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}

/**
 * Serialized, durable identity/session mutations for the v1 ELU-owned core.
 *
 * No provider runtime is called here. Within one core instance, every mutation
 * writes the complete next aggregate before making it visible. The current
 * production facade is intentionally not wired to this class yet.
 */
internal class IdentityStateCore(
    private val store: CoreStateStore,
    private val identifiers: CoreIdentifierGenerator = UuidCoreIdentifierGenerator,
    private val timestamps: CoreTimestampProvider = SystemCoreTimestampProvider,
) {
    private var state: PersistedCoreState = loadOrCreate()

    @Synchronized
    fun snapshot(): PersistedCoreState = state

    /** Identify always advances context; identity revision advances only on an actual identity change. */
    @Synchronized
    fun identify(userId: String): IdentityState {
        requireIdentifier(userId, 512, "userId")
        val current = state.identity
        val identityChanged = current.userId != userId
        val nextIdentity =
            current.copy(
                revision =
                    if (identityChanged) increment(current.revision, "identity revision") else current.revision,
                contextRevision = increment(current.contextRevision, "context revision"),
                userId = userId,
                updatedAt = timestamps.now(),
            )
        commit(state.copy(identity = nextIdentity))
        return nextIdentity
    }

    /** Alias changes evaluation context but not anonymous or identified identity. */
    @Synchronized
    fun alias(
        aliasId: String,
        canonicalId: String? = null,
    ): AliasContext {
        requireIdentifier(aliasId, 512, "aliasId")
        canonicalId?.let { requireIdentifier(it, 512, "canonicalId") }
        val effectiveCanonicalId = canonicalId ?: state.identity.userId ?: state.identity.anonymousId
        val nextIdentity = advanceContext(state.identity)
        commit(state.copy(identity = nextIdentity))
        return AliasContext(aliasId, effectiveCanonicalId, nextIdentity.contextRevision)
    }

    /** Replaces one group association and invalidates properties tied to a replaced group. */
    @Synchronized
    fun group(
        type: String,
        key: String,
    ): IdentityState {
        requireIdentifier(type, 256, "group type")
        requireIdentifier(key, 512, "group key")
        val current = state.identity
        if (!current.groups.containsKey(type) && current.groups.size >= 64) {
            throw IllegalArgumentException("groups may contain at most 64 entries")
        }
        val groups = LinkedHashMap(current.groups).apply { put(type, key) }
        val groupProperties =
            if (current.groups[type] != key) {
                LinkedHashMap(state.flagContext.groupProperties).apply { remove(type) }
            } else {
                state.flagContext.groupProperties
            }
        val nextIdentity =
            advanceContext(current).copy(groups = immutableMap(groups))
        commit(
            state.copy(
                identity = nextIdentity,
                flagContext = state.flagContext.copy(groupProperties = immutableMap(groupProperties)),
            ),
        )
        return nextIdentity
    }

    @Synchronized
    fun resetGroups(): IdentityState {
        val nextIdentity = advanceContext(state.identity).copy(groups = emptyMap())
        commit(
            state.copy(
                identity = nextIdentity,
                flagContext = state.flagContext.copy(groupProperties = emptyMap()),
            ),
        )
        return nextIdentity
    }

    /** Merges customer super properties for later events. */
    @Synchronized
    fun registerSuperProperties(properties: Map<String, Any?>): IdentityState {
        val normalized = JsonValues.objectValue(properties, "superProperties")
        val merged = LinkedHashMap(state.identity.superProperties).apply { putAll(normalized) }
        val nextIdentity =
            advanceContext(state.identity).copy(superProperties = immutableMap(merged))
        commit(state.copy(identity = nextIdentity))
        return nextIdentity
    }

    @Synchronized
    fun unregisterSuperProperty(key: String): IdentityState {
        val properties = LinkedHashMap(state.identity.superProperties).apply { remove(key) }
        val nextIdentity =
            advanceContext(state.identity).copy(superProperties = immutableMap(properties))
        commit(state.copy(identity = nextIdentity))
        return nextIdentity
    }

    /** Merges effective person properties used by future flag evaluations. */
    @Synchronized
    fun setPersonPropertiesForFlags(properties: Map<String, Any?>): IdentityState {
        val normalized = JsonValues.objectValue(properties, "flag person properties")
        val merged = LinkedHashMap(state.flagContext.personProperties).apply { putAll(normalized) }
        val nextIdentity = advanceContext(state.identity)
        commit(
            state.copy(
                identity = nextIdentity,
                flagContext = state.flagContext.copy(personProperties = immutableMap(merged)),
            ),
        )
        return nextIdentity
    }

    /** Merges properties for the currently associated group type. */
    @Synchronized
    fun setGroupPropertiesForFlags(
        type: String,
        properties: Map<String, Any?>,
    ): IdentityState {
        requireIdentifier(type, 256, "group type")
        if (!state.flagContext.groupProperties.containsKey(type) && state.flagContext.groupProperties.size >= 64) {
            throw IllegalArgumentException("flag group properties may contain at most 64 entries")
        }
        val normalized = JsonValues.objectValue(properties, "flag group properties")
        val currentForType = state.flagContext.groupProperties[type].orEmpty()
        val mergedForType = LinkedHashMap(currentForType).apply { putAll(normalized) }
        val allGroups =
            LinkedHashMap(state.flagContext.groupProperties).apply {
                put(type, immutableMap(mergedForType))
            }
        val nextIdentity = advanceContext(state.identity)
        commit(
            state.copy(
                identity = nextIdentity,
                flagContext = state.flagContext.copy(groupProperties = immutableMap(allGroups)),
            ),
        )
        return nextIdentity
    }

    /** Session lifecycle logic can install or clear a schema-valid session without provider coupling. */
    @Synchronized
    fun setSession(session: SessionState?): IdentityState {
        val nextIdentity = state.identity.copy(session = session, updatedAt = timestamps.now())
        // Encoding performs the same closed-schema validation used for disk reads.
        CoreStateCodec.encodeIdentity(nextIdentity)
        commit(state.copy(identity = nextIdentity))
        return nextIdentity
    }

    @Synchronized
    fun setOptedOut(optedOut: Boolean): IdentityState {
        if (state.identity.optedOut == optedOut) return state.identity
        val nextIdentity = state.identity.copy(optedOut = optedOut, updatedAt = timestamps.now())
        commit(state.copy(identity = nextIdentity))
        return nextIdentity
    }

    /**
     * Logout/reset rotates anonymous identity and clears customer context while
     * preserving privacy choice and the installation's ordering stream.
     */
    @Synchronized
    fun reset(): IdentityState {
        val current = state.identity
        val nextIdentity =
            current.copy(
                revision = increment(current.revision, "identity revision"),
                contextRevision = increment(current.contextRevision, "context revision"),
                anonymousId = nextAnonymousId(excluding = current.anonymousId),
                userId = null,
                groups = emptyMap(),
                superProperties = emptyMap(),
                session = null,
                optedOut = current.optedOut,
                updatedAt = timestamps.now(),
            )
        commit(
            state.copy(
                identity = nextIdentity,
                flagContext = freshFlagContext(),
                // stream is intentionally unchanged, including nextSequence.
            ),
        )
        return nextIdentity
    }

    private fun loadOrCreate(): PersistedCoreState {
        val bytes =
            try {
                store.read()
            } catch (_: CoreStateCorruptionException) {
                return createAndPersistRecoveredState(RecoverableCoreRecords())
            }
        if (bytes == null) return createAndPersistFreshState()
        return try {
            CoreStateCodec.decode(bytes)
        } catch (unsupported: UnsupportedCoreSchemaException) {
            // A downgrade must not overwrite a valid state written by a newer major.
            throw unsupported
        } catch (_: CoreStateCorruptionException) {
            createAndPersistRecoveredState(CoreStateCodec.recoverableRecords(bytes))
        }
    }

    private fun createAndPersistFreshState(): PersistedCoreState {
        val fresh = newState(optedOut = false)
        store.write(CoreStateCodec.encode(fresh))
        return fresh
    }

    private fun createAndPersistRecoveredState(records: RecoverableCoreRecords): PersistedCoreState {
        val recoveredIdentity = records.identity
        val recovered =
            PersistedCoreState(
                identity = recoveredIdentity ?: newIdentity(optedOut = true),
                stream = records.stream ?: newStream(),
                // If identity could not be validated, no flag context may be
                // allowed to survive into the rotated fail-closed identity.
                flagContext =
                    if (recoveredIdentity == null) {
                        freshFlagContext()
                    } else {
                        records.flagContext ?: freshFlagContext()
                    },
            )
        store.write(CoreStateCodec.encode(recovered))
        return recovered
    }

    private fun newState(optedOut: Boolean): PersistedCoreState =
        PersistedCoreState(
            identity = newIdentity(optedOut),
            stream = newStream(),
            flagContext = freshFlagContext(),
        )

    private fun newIdentity(optedOut: Boolean): IdentityState {
        val now = timestamps.now()
        return IdentityState(
            revision = 0,
            contextRevision = 0,
            anonymousId = nextAnonymousId(),
            userId = null,
            groups = emptyMap(),
            superProperties = emptyMap(),
            session = null,
            optedOut = optedOut,
            updatedAt = now,
        )
    }

    private fun newStream(): StreamState =
        StreamState(
            streamId = nextStreamId(),
            nextSequence = INITIAL_SEQUENCE,
        )

    private fun commit(next: PersistedCoreState) {
        store.write(CoreStateCodec.encode(next))
        state = next
    }

    private fun advanceContext(identity: IdentityState): IdentityState =
        identity.copy(
            contextRevision = increment(identity.contextRevision, "context revision"),
            updatedAt = timestamps.now(),
        )

    private fun nextAnonymousId(excluding: String? = null): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = identifiers.next("anon_")
            requireGeneratedIdentifier(candidate, 256, "anonymous ID")
            if (candidate != excluding) return candidate
        }
        throw IllegalStateException("Identifier generator could not rotate the anonymous ID")
    }

    private fun nextStreamId(): String {
        val candidate = identifiers.next("stream_")
        requireGeneratedIdentifier(candidate, 256, "stream ID")
        return candidate
    }

    private fun requireIdentifier(
        value: String,
        maxLength: Int,
        label: String,
    ) {
        val length = value.codePointCount(0, value.length)
        require(length in 1..maxLength) { "$label length must be in 1..$maxLength" }
    }

    private fun requireGeneratedIdentifier(
        value: String,
        maxLength: Int,
        label: String,
    ) {
        try {
            requireIdentifier(value, maxLength, label)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Generated $label is invalid", error)
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

    private fun freshFlagContext(): FlagContextState =
        FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap())

    private fun <K, V> immutableMap(value: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(value))

    private companion object {
        const val MAX_ID_GENERATION_ATTEMPTS = 8
    }
}
