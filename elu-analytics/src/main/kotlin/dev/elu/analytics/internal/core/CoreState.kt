package dev.elu.analytics.internal.core

/** The frozen ELU SDK persistence schema major. */
internal const val CORE_SCHEMA_VERSION: Int = 1

/** The next record in a fresh installation is sequence zero. */
internal const val INITIAL_SEQUENCE: Long = 0L

internal const val DEFAULT_SESSION_TIMEOUT_SECONDS: Int = 1_800
internal const val MIN_SESSION_TIMEOUT_SECONDS: Int = 60
internal const val MAX_SESSION_TIMEOUT_SECONDS: Int = 36_000
internal const val SESSION_MAXIMUM_DURATION_SECONDS: Int = 86_400

/**
 * The provider-neutral identity record defined by contracts/v1/identity.schema.json.
 *
 * Stream ordering and flag-evaluation state intentionally live in separate records;
 * the identity schema is closed and may not acquire implementation-only fields.
 */
internal data class IdentityState(
    val schemaVersion: Int = CORE_SCHEMA_VERSION,
    val revision: Long,
    val contextRevision: Long,
    val anonymousId: String,
    val userId: String?,
    val groups: Map<String, String>,
    val superProperties: Map<String, Any?>,
    val session: SessionState?,
    val optedOut: Boolean,
    val updatedAt: String,
    val migration: MigrationState? = null,
) {
    val identityRevision: Long
        get() = revision
}

internal data class SessionState(
    val id: String,
    val startedAt: String,
    val lastActivityAt: String,
    val timeoutSeconds: Int,
    val maximumDurationSeconds: Int = SESSION_MAXIMUM_DURATION_SECONDS,
    val lifecycle: SessionLifecycle,
    val backgroundedAt: String?,
)

internal enum class SessionLifecycle(val wireValue: String) {
    ACTIVE("active"),
    BACKGROUND("background"),
    ;

    companion object {
        fun fromWireValue(value: String): SessionLifecycle =
            values().firstOrNull { it.wireValue == value }
                ?: throw CoreStateCorruptionException("Unsupported session lifecycle: $value")
    }
}

internal data class MigrationState(
    val sourceSchema: String,
    val completedAt: String,
)

/**
 * Installation-scoped ordering metadata, persisted separately from identity.
 * The queue layer must advance [nextSequence] in the same durable commit that
 * enqueues its record; this state-only slice deliberately exposes no allocator.
 */
internal data class StreamState(
    val schemaVersion: Int = CORE_SCHEMA_VERSION,
    val streamId: String,
    val nextSequence: Long,
)

/** Inputs that participate in feature-flag evaluation and are cleared by reset. */
internal data class FlagContextState(
    val schemaVersion: Int = CORE_SCHEMA_VERSION,
    val personProperties: Map<String, Any?>,
    val groupProperties: Map<String, Map<String, Any?>>,
)

/** A single durable commit unit containing three logically separate records. */
internal data class PersistedCoreState(
    val schemaVersion: Int = CORE_SCHEMA_VERSION,
    val identity: IdentityState,
    val stream: StreamState,
    val flagContext: FlagContextState,
)

internal data class AliasContext(
    val aliasId: String,
    val canonicalId: String,
    val contextRevision: Long,
)
