package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.config.V1ExactTimestamp
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal interface RuntimeCaptureClock {
    fun wallNowEpochMillis(): Long

    /** A sleep-inclusive, monotonic nanosecond counter in production. */
    fun elapsedRealtimeNanos(): Long
}

internal object JvmRuntimeCaptureClock : RuntimeCaptureClock {
    override fun wallNowEpochMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeNanos(): Long = System.nanoTime()
}

internal sealed interface RuntimeCaptureAuthorityState {
    data object Absent : RuntimeCaptureAuthorityState

    /** A newer accepted config has revoked execution while its activation is still fallible. */
    data class Pending(
        val trustedConfigBoundary: Pair<V1ExactTimestamp, String>?,
    ) : RuntimeCaptureAuthorityState

    data class Authorized(
        val ownerEpoch: Long,
        val configIssuedAt: V1ExactTimestamp,
        val configExpiresAt: V1ExactTimestamp,
        val configSemanticHash: String,
        val policySourceHash: String,
        val decisionHash: String,
        val ownerNamespaceHash: String,
        val configSiteId: String,
        val streamId: String,
        val identityRevision: Long,
        val contextRevision: Long,
        val identityOptedOut: Boolean,
        val monotonicStartedAt: Long,
        val monotonicBudget: Long,
        val idleTimeoutSeconds: Int,
        val maximumDurationSeconds: Int,
    ) : RuntimeCaptureAuthorityState

    data class Terminal(
        val ownerEpoch: Long,
        val trustedConfigBoundary: Pair<V1ExactTimestamp, String>?,
        val policySourceHash: String?,
        val contextRevision: Long?,
        val reason: RuntimeCaptureAuthorityTerminalReason,
    ) : RuntimeCaptureAuthorityState
}

internal enum class RuntimeCaptureAuthorityTerminalReason {
    DISABLED,
    REVOKED,
    PRIVACY_BLOCKED,
    MALFORMED,
    CONFLICT,
    EXPIRED,
    SITE_CHANGED,
    STALE,
}

internal sealed interface RuntimeCaptureAuthorityUpdateResult {
    data class Activated(val authority: RuntimeCaptureAuthorityState.Authorized) : RuntimeCaptureAuthorityUpdateResult

    data class Terminated(val authority: RuntimeCaptureAuthorityState.Terminal) : RuntimeCaptureAuthorityUpdateResult
}

internal data class RuntimeCaptureCommand(
    val kind: RuntimeEventKind,
    val name: String,
    val occurredAt: String,
    val properties: Map<String, Any?>,
    val versions: RuntimeVersions,
)

internal enum class RuntimeCaptureRejection {
    AUTHORITY_ABSENT,
    AUTHORITY_PENDING,
    AUTHORITY_TERMINAL,
    AUTHORITY_EXPIRED,
    AUTHORITY_WITNESS_CHANGED,
    OPTED_OUT,
    EVENT_INVALID,
    QUEUE_LIMIT,
}

internal sealed interface RuntimeCaptureResult {
    data class Accepted(
        val record: RuntimeQueuedRecord.Event,
        val snapshot: RuntimeQueueSnapshot,
    ) : RuntimeCaptureResult

    data class Rejected(
        val reason: RuntimeCaptureRejection,
        val snapshot: RuntimeQueueSnapshot,
    ) : RuntimeCaptureResult
}

internal object RuntimeSiteNamespace {
    fun digest(exactConstructorSiteKey: String): String {
        require(exactConstructorSiteKey.isNotEmpty()) { "constructor site key must not be empty" }
        var index = 0
        while (index < exactConstructorSiteKey.length) {
            val character = exactConstructorSiteKey[index]
            when {
                Character.isHighSurrogate(character) -> {
                    require(
                        index + 1 < exactConstructorSiteKey.length &&
                            Character.isLowSurrogate(exactConstructorSiteKey[index + 1]),
                    ) { "constructor site key contains an unpaired Unicode surrogate" }
                    index += 2
                }
                Character.isLowSurrogate(character) ->
                    throw IllegalArgumentException("constructor site key contains an unpaired Unicode surrogate")
                else -> index += 1
            }
        }
        val bytes = exactConstructorSiteKey.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    fun directory(exactConstructorSiteKey: String): String = "site-${digest(exactConstructorSiteKey)}"
}
