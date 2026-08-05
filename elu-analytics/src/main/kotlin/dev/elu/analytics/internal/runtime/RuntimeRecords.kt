package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.SessionState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val RUNTIME_RECORD_SCHEMA_VERSION: Int = 1
internal const val RUNTIME_CONTRACT_VERSION: String = "1.0.0"

internal enum class RuntimeRecordKind(val wireValue: String) {
    EVENT("event"),
    MUTATION("mutation"),
    ;

    companion object {
        fun fromWireValue(value: String): RuntimeRecordKind =
            values().firstOrNull { it.wireValue == value }
                ?: throw RuntimeRecordCorruptionException("Unsupported queued record kind: $value")
    }
}

internal enum class RuntimeEventKind(val wireValue: String) {
    CAPTURE("capture"),
    SCREEN("screen"),
    EXCEPTION("exception"),
    DIAGNOSTIC("diagnostic"),
    ;

    companion object {
        fun fromWireValue(value: String): RuntimeEventKind =
            values().firstOrNull { it.wireValue == value }
                ?: throw RuntimeRecordCorruptionException("Unsupported event kind: $value")
    }
}

internal enum class RuntimePlatform(val wireValue: String) {
    BROWSER("browser"),
    ANDROID("android"),
    IOS("ios"),
    ;

    companion object {
        fun fromWireValue(value: String): RuntimePlatform =
            values().firstOrNull { it.wireValue == value }
                ?: throw RuntimeRecordCorruptionException("Unsupported runtime platform: $value")
    }
}

internal data class RuntimeVersionComponent(
    val name: String,
    val version: String,
)

internal data class RuntimeVersions(
    val schemaVersion: Int = RUNTIME_RECORD_SCHEMA_VERSION,
    val contractVersion: String = RUNTIME_CONTRACT_VERSION,
    val platform: RuntimePlatform,
    val runtime: RuntimeVersionComponent,
    val facade: RuntimeVersionComponent,
    val build: String? = null,
)

internal data class RuntimeEventIdentity(
    val anonymousId: String,
    val userId: String?,
    val revision: Long,
)

internal data class RuntimeEventRecord(
    val schemaVersion: Int = RUNTIME_RECORD_SCHEMA_VERSION,
    val eventId: String,
    val streamId: String,
    val sequence: Long,
    val contextRevision: Long,
    val kind: RuntimeEventKind,
    val name: String,
    val occurredAt: String,
    val identity: RuntimeEventIdentity,
    val sessionId: String,
    val properties: Map<String, Any?>,
    val groups: Map<String, String>,
    val versions: RuntimeVersions,
)

internal data class RuntimeMutationSubject(
    val anonymousId: String,
    val userId: String?,
    val identityRevision: Long,
)

internal sealed interface RuntimeMutationChange {
    val type: String

    data class Identify(
        val userId: String,
        val set: Map<String, Any?>,
        val setOnce: Map<String, Any?>,
    ) : RuntimeMutationChange {
        override val type: String = "identify"
    }

    data class LinkAlias(
        val aliasId: String,
        val canonicalId: String,
    ) : RuntimeMutationChange {
        override val type: String = "linkAlias"
    }

    data class SetPersonProperties(
        val set: Map<String, Any?>,
        val setOnce: Map<String, Any?>,
        val unset: List<String>,
    ) : RuntimeMutationChange {
        override val type: String = "setPersonProperties"
    }

    data class AssociateGroup(
        val groupType: String,
        val groupKey: String,
    ) : RuntimeMutationChange {
        override val type: String = "associateGroup"
    }

    data class SetGroupProperties(
        val groupType: String,
        val groupKey: String,
        val set: Map<String, Any?>,
        val setOnce: Map<String, Any?>,
        val unset: List<String>,
    ) : RuntimeMutationChange {
        override val type: String = "setGroupProperties"
    }
}

internal data class RuntimeMutationRecord(
    val mutationId: String,
    val sequence: Long,
    val contextRevision: Long,
    val occurredAt: String,
    val subject: RuntimeMutationSubject,
    val change: RuntimeMutationChange,
)

/**
 * The canonical mutation schema is an envelope. A durable queue row carries exactly one
 * mutation so its primary-key sequence and acknowledgement identity remain unambiguous.
 */
internal data class RuntimeMutationEnvelope(
    val schemaVersion: Int = RUNTIME_RECORD_SCHEMA_VERSION,
    val streamId: String,
    val versions: RuntimeVersions,
    val mutation: RuntimeMutationRecord,
)

internal sealed interface RuntimeRecordDraft {
    val occurredAt: String
    val versions: RuntimeVersions

    data class Event(
        val kind: RuntimeEventKind,
        val name: String,
        override val occurredAt: String,
        /** Must name the post-transition persisted session; it is never copied into the record. */
        val expectedSessionId: String,
        val properties: Map<String, Any?>,
        override val versions: RuntimeVersions,
    ) : RuntimeRecordDraft

    data class Mutation(
        override val occurredAt: String,
        val change: RuntimeMutationChange,
        override val versions: RuntimeVersions,
    ) : RuntimeRecordDraft
}

internal sealed interface RuntimeEventSessionUpdate {
    data object Preserve : RuntimeEventSessionUpdate

    /**
     * Replaces the session only when the persisted current session still has
     * [expectedCurrentSessionId]. A null expectation matches only no session.
     */
    data class Replace(
        val expectedCurrentSessionId: String?,
        val session: SessionState,
    ) : RuntimeEventSessionUpdate
}

internal sealed interface RuntimeLocalStateChange {
    val occurredAt: String

    data class SetOptedOut(
        val optedOut: Boolean,
        override val occurredAt: String,
    ) : RuntimeLocalStateChange

    data class ResetGroups(override val occurredAt: String) : RuntimeLocalStateChange

    data class ResetIdentity(override val occurredAt: String) : RuntimeLocalStateChange

    data class RegisterSuperProperties(
        val properties: Map<String, Any?>,
        override val occurredAt: String,
    ) : RuntimeLocalStateChange

    data class UnregisterSuperProperties(
        val keys: List<String>,
        override val occurredAt: String,
    ) : RuntimeLocalStateChange

    data class MarkBackgrounded(override val occurredAt: String) : RuntimeLocalStateChange
}

internal sealed interface RuntimeQueuedRecord {
    val sequence: Long
    val kind: RuntimeRecordKind
    val recordId: String
    val streamId: String
    /** Exact canonical V1BatchRecord bytes used by queue and delivery limits. */
    val accountedBytes: Int

    data class Event(
        val record: RuntimeEventRecord,
        override val accountedBytes: Int,
    ) : RuntimeQueuedRecord {
        override val sequence: Long = record.sequence
        override val kind: RuntimeRecordKind = RuntimeRecordKind.EVENT
        override val recordId: String = record.eventId
        override val streamId: String = record.streamId
    }

    data class Mutation(
        val envelope: RuntimeMutationEnvelope,
        override val accountedBytes: Int,
    ) : RuntimeQueuedRecord {
        override val sequence: Long = envelope.mutation.sequence
        override val kind: RuntimeRecordKind = RuntimeRecordKind.MUTATION
        override val recordId: String = envelope.mutation.mutationId
        override val streamId: String = envelope.streamId
    }
}

internal data class RuntimeRecordReference(
    val sequence: Long,
    val kind: RuntimeRecordKind,
    val recordId: String,
)

internal data class RuntimeAcknowledgement(
    val streamId: String,
    val references: List<RuntimeRecordReference>,
)

/** Stable acknowledgement identity that cannot be reused after deletion. */
internal object RuntimeRecordIdentity {
    fun recordId(
        streamId: String,
        sequence: Long,
        kind: RuntimeRecordKind,
    ): String {
        require(streamId.isNotEmpty()) { "streamId must not be empty" }
        require(sequence >= 0) { "sequence must be non-negative" }
        val digest =
            MessageDigest.getInstance("SHA-256").digest(
                "${kind.wireValue}\u0000$streamId\u0000$sequence".toByteArray(StandardCharsets.UTF_8),
            )
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX[value ushr 4]
            hex[index * 2 + 1] = HEX[value and 0x0f]
        }
        val prefix = if (kind == RuntimeRecordKind.EVENT) "event_" else "mutation_"
        return prefix + String(hex)
    }

    private const val HEX = "0123456789abcdef"
}
