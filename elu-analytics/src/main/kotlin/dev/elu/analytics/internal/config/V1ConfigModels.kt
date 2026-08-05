package dev.elu.analytics.internal.config

import java.net.URI

/** The frozen ELU SDK semantic/configuration schema major. */
internal const val V1_CONFIG_SCHEMA_VERSION: Int = 1

/** Why a config document exposed no network authority to the caller. */
internal enum class V1ConfigRejection {
    MALFORMED,
    UNSUPPORTED_SCHEMA,
    EXPIRED,
    INACTIVE,
    UNAUTHORIZED,
    STALE,
    CONFLICT,
}

internal sealed class V1ConfigUpdateResult {
    data class Enabled(
        val revision: String,
        /** POSIX millisecond metadata; exact leap-second order remains authoritative internally. */
        val expiresAtEpochMillis: Long,
    ) : V1ConfigUpdateResult()

    data class Inactive(
        val status: V1ConfigStatus,
        val revision: String,
    ) : V1ConfigUpdateResult()

    data class Rejected(val reason: V1ConfigRejection) : V1ConfigUpdateResult()
}

internal sealed class V1ConfigResolution {
    data class Authorized(val config: V1AuthorizedConfig) : V1ConfigResolution()

    data class Rejected(val reason: V1ConfigRejection) : V1ConfigResolution()
}

internal enum class V1EndpointRole {
    EVENTS,
    REPLAY,
    FLAGS,
    ASSETS,
}

/**
 * Endpoints that passed their role-specific config, feature, privacy, and ELU ownership gates.
 * A null role is deliberately unauthorized; callers must never fall back to another host.
 */
internal data class V1ResolvedEndpointSet(
    val events: URI?,
    val replay: URI?,
    val flags: URI?,
    val assets: URI?,
) {
    operator fun get(role: V1EndpointRole): URI? =
        when (role) {
            V1EndpointRole.EVENTS -> events
            V1EndpointRole.REPLAY -> replay
            V1EndpointRole.FLAGS -> flags
            V1EndpointRole.ASSETS -> assets
        }
}

internal data class V1AuthorizedConfig(
    val schemaVersion: Int = V1_CONFIG_SCHEMA_VERSION,
    val revision: String,
    val issuedAt: String,
    val expiresAt: String,
    val siteId: String,
    val endpoints: V1ResolvedEndpointSet,
    val privacy: V1ServerPrivacyPolicy,
    val effectivePrivacy: V1EffectivePrivacyState?,
    val captureAuthorization: V1ChannelAuthorization,
    val replayAuthorization: V1ChannelAuthorization,
    val features: V1Features,
    val replayCapabilities: V1ReplayCapabilities,
    /** Present only when replay was authorized against a locally readback-proven exact pair. */
    val negotiatedReplayTransport: V1ReplayTransport?,
    val session: V1SessionConfiguration,
    val limits: V1ConfigLimits,
)

internal enum class V1ChannelAuthorizationStatus {
    AUTHORIZED,
    RESTRICTED,
    INVALID,
}

internal enum class V1ChannelAuthorizationReason {
    AUTHORIZED,
    PRIVACY_STATE_MISSING,
    PRIVACY_STATE_MALFORMED,
    PRIVACY_SCHEMA_UNSUPPORTED,
    IDENTITY_SCHEMA_UNSUPPORTED,
    POLICY_REVISION_MISMATCH,
    CONTEXT_REVISION_MISMATCH,
    IDENTITY_OPT_STATE_MISMATCH,
    POLICY_HASH_MISMATCH,
    REGION_POLICY_CONFLICT,
    DERIVED_CLAIM_MISMATCH,
    FEATURE_DISABLED,
    POLICY_DISABLED,
    DECISION_NOT_ALLOWED,
    IDENTITY_OPTED_OUT,
    CAPTURE_RESTRICTED,
    REPLAY_NOT_SAMPLED,
    MASKING_NOT_VALIDATED,
    MASKING_POLICY_VIOLATION,
    SESSION_INELIGIBLE,
    BUDGET_EXHAUSTED,
    TRANSPORT_MISSING,
    TRANSPORT_NOT_ADVERTISED,
    PLATFORM_FALLBACK_REQUIRED,
    LOCAL_TRANSPORT_UNPROVEN,
}

internal data class V1ChannelAuthorization(
    val status: V1ChannelAuthorizationStatus,
    val reason: V1ChannelAuthorizationReason,
)

internal data class V1Features(
    val capture: Boolean,
    val replay: Boolean,
    val flags: Boolean,
    val assets: Boolean,
)

internal enum class V1ConfigStatus(val wireValue: String) {
    ENABLED("enabled"),
    DISABLED("disabled"),
    REVOKED("revoked"),
    ;
}

internal data class V1ConfiguredEndpointSet(
    val events: String,
    val replay: String?,
    val flags: String,
    val assets: String?,
)

internal data class V1ParsedConfig(
    val revision: String,
    val issuedAt: String,
    val issuedAtInstant: V1ExactTimestamp,
    val expiresAt: String,
    val expiresAtInstant: V1ExactTimestamp,
    val expiresAtEpochMillis: Long,
    val status: V1ConfigStatus,
    val siteId: String?,
    val endpoints: V1ConfiguredEndpointSet?,
    val privacy: V1ServerPrivacyPolicy?,
    val features: V1Features?,
    val replayCapabilities: V1ReplayCapabilities?,
    val session: V1SessionConfiguration?,
    val limits: V1ConfigLimits?,
    val serialized: String,
)

/** Trusted ordering envelope retained even when the document body fails a nested policy check. */
internal data class V1ParsedConfigBoundary(
    val revision: String,
    val issuedAtInstant: V1ExactTimestamp,
    val expiresAtInstant: V1ExactTimestamp,
    val serialized: String,
)

/** Exact RFC 3339 comparison value; fractions are never rounded to Android clock precision. */
internal class V1ExactTimestamp private constructor(
    val epochWholeSecond: Long,
    val fractionalDigits: String,
    private val isLeapSecond: Boolean,
) : Comparable<V1ExactTimestamp> {
    override fun compareTo(other: V1ExactTimestamp): Int {
        val seconds = epochWholeSecond.compareTo(other.epochWholeSecond)
        if (seconds != 0) return seconds
        val leap = isLeapSecond.compareTo(other.isLeapSecond)
        if (leap != 0) return leap
        val width = maxOf(fractionalDigits.length, other.fractionalDigits.length)
        repeat(width) { index ->
            val left = fractionalDigits.getOrElse(index) { '0' }
            val right = other.fractionalDigits.getOrElse(index) { '0' }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    fun toEpochMillisFloor(): Long {
        val milliseconds = fractionalDigits.take(3).padEnd(3, '0').ifEmpty { "0" }.toLong()
        val wholeSecond = if (isLeapSecond) Math.addExact(epochWholeSecond, 1L) else epochWholeSecond
        return Math.addExact(Math.multiplyExact(wholeSecond, 1_000L), milliseconds)
    }

    companion object {
        fun fromEpochSecondAndFraction(
            epochWholeSecond: Long,
            fractionalDigits: String,
            isLeapSecond: Boolean = false,
        ): V1ExactTimestamp {
            require(fractionalDigits.all { it in '0'..'9' }) { "Timestamp fraction must contain only digits" }
            return V1ExactTimestamp(epochWholeSecond, fractionalDigits.trimEnd('0'), isLeapSecond)
        }

        fun fromEpochMillis(epochMillis: Long): V1ExactTimestamp =
            fromEpochSecondAndFraction(
                epochWholeSecond = Math.floorDiv(epochMillis, 1_000L),
                fractionalDigits = Math.floorMod(epochMillis, 1_000L).toString().padStart(3, '0'),
            )
    }
}

internal enum class V1ReplayCompression(val wireValue: String) {
    NONE("none"),
    GZIP("gzip"),
    ;
}

internal data class V1ReplayCapabilities(
    val acceptedCodecs: List<String>,
    val acceptedCompressions: List<V1ReplayCompression>,
)

/** A codec/compression pair whose local decoder has passed engine readback. */
internal data class V1ReplayTransport(
    val codec: String,
    val compression: V1ReplayCompression,
)

internal data class V1SessionConfiguration(
    val idleTimeoutSeconds: Int,
    val maximumDurationSeconds: Int,
)

internal data class V1ConfigLimits(
    val eventBatchCount: Int,
    val eventBatchBytes: Int,
    val replayChunkBytes: Int,
    val queueBytes: Int,
)

internal data class V1ServerPrivacyPolicy(
    val schemaVersion: Int = V1_CONFIG_SCHEMA_VERSION,
    val revision: String,
    val capture: V1CapturePolicy,
    val replay: V1ReplayPolicy,
    val masking: V1MaskingPolicy,
    val region: V1RegionPolicy,
)

internal data class V1CapturePolicy(
    val enabled: Boolean,
    val reason: String?,
)

internal data class V1ReplayPolicy(
    val enabled: Boolean,
    val reason: String?,
    val sampleRate: Double,
    val minimumDurationSeconds: Int,
    val maximumDurationSeconds: Int,
)

internal enum class V1TextMasking(val wireValue: String) {
    ALL("all"),
    SENSITIVE("sensitive"),
    ;
}

internal enum class V1ImageMasking(val wireValue: String) {
    BLOCK("block"),
    ALLOW("allow"),
    ;
}

internal enum class V1PrivacyPlatform(val wireValue: String) {
    BROWSER("browser"),
    ANDROID("android"),
    IOS("ios"),
    ;
}

internal enum class V1PlatformRuleAction(val wireValue: String) {
    MASK("mask"),
    BLOCK("block"),
    ;
}

internal data class V1PlatformMaskingRule(
    val platform: V1PrivacyPlatform,
    val action: V1PlatformRuleAction,
    val targetDialect: String,
    val target: String,
)

internal data class V1MaskingPolicy(
    val text: V1TextMasking,
    val inputs: V1TextMasking,
    val images: V1ImageMasking,
    val secureInputsMasked: Boolean,
    val platformRules: List<V1PlatformMaskingRule>,
)

internal enum class V1RegionPolicyMode(val wireValue: String) {
    ALLOW("allow"),
    BLOCK("block"),
    BLOCK_EU_ON_DEVICE("block-eu-on-device"),
    ;
}

internal data class V1RegionPolicy(
    val mode: V1RegionPolicyMode,
    val evaluator: String?,
)

internal enum class V1OnDeviceDecisionValue(val wireValue: String) {
    ALLOW("allow"),
    BLOCK("block"),
    UNKNOWN("unknown"),
    ;
}

internal enum class V1OnDeviceDecisionSource(val wireValue: String) {
    NOT_EVALUATED("not-evaluated"),
    DEVICE_REGION("device-region"),
    LOCAL_CONSENT("local-consent"),
    REMOTE_KILL_SWITCH("remote-kill-switch"),
    ;
}

internal data class V1OnDeviceDecision(
    val decision: V1OnDeviceDecisionValue,
    val source: V1OnDeviceDecisionSource,
    val reason: String?,
    val evaluatedAt: String?,
)

internal data class V1ReplayTransportSelection(
    val codec: String,
    val compression: V1ReplayCompression,
    val advertised: Boolean,
)

internal data class V1EffectiveMasking(
    val text: V1TextMasking,
    val inputs: V1TextMasking,
    val images: V1ImageMasking,
    val secureInputsMasked: Boolean,
    val platformFallbackApplied: Boolean,
)

internal data class V1EffectivePrivacyState(
    val schemaVersion: Int = V1_CONFIG_SCHEMA_VERSION,
    val policyRevision: String,
    val contextRevision: Long,
    val effectivePolicyHash: String,
    val onDeviceDecision: V1OnDeviceDecision,
    val captureAllowed: Boolean,
    val replayAllowed: Boolean,
    val replaySampled: Boolean,
    val identityOptedOut: Boolean,
    val maskingValidated: Boolean,
    val replaySessionEligible: Boolean,
    val replayBudgetRemainingSeconds: Int,
    val replayTransport: V1ReplayTransportSelection?,
    val effectiveMasking: V1EffectiveMasking,
)
