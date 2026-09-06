package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.config.V1EffectiveMasking
import dev.elu.analytics.internal.config.V1EffectivePrivacyState
import dev.elu.analytics.internal.config.V1Features
import dev.elu.analytics.internal.config.V1ImageMasking
import dev.elu.analytics.internal.config.V1MaskingPolicy
import dev.elu.analytics.internal.config.V1OnDeviceDecision
import dev.elu.analytics.internal.config.V1OnDeviceDecisionSource
import dev.elu.analytics.internal.config.V1OnDeviceDecisionValue
import dev.elu.analytics.internal.config.V1PrivacyPlatform
import dev.elu.analytics.internal.config.V1RegionPolicyMode
import dev.elu.analytics.internal.config.V1ReplayCapabilities
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.config.V1ReplayTransportSelection
import dev.elu.analytics.internal.config.V1ServerPrivacyPolicy
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.config.V1TextMasking
import dev.elu.analytics.internal.config.V1_CONFIG_SCHEMA_VERSION
import dev.elu.analytics.internal.core.IdentityState
import org.json.JSONObject

/** Locally proven replay facts. Without a recorder every value stays at its restrictive default. */
internal data class PrivacyReplayInput(
    val sampled: Boolean,
    val maskingValidated: Boolean,
    val sessionEligible: Boolean,
    val budgetRemainingSeconds: Int,
    val transport: V1ReplayTransport?,
) {
    init {
        require(budgetRemainingSeconds in 0..MAX_REPLAY_BUDGET_SECONDS) {
            "budgetRemainingSeconds must be in 0..$MAX_REPLAY_BUDGET_SECONDS"
        }
    }

    companion object {
        const val MAX_REPLAY_BUDGET_SECONDS: Int = 86_400

        val UNAVAILABLE: PrivacyReplayInput =
            PrivacyReplayInput(
                sampled = false,
                maskingValidated = false,
                sessionEligible = false,
                budgetRemainingSeconds = 0,
                transport = null,
            )
    }
}

internal data class PrivacyProjectionInput(
    val policy: V1ServerPrivacyPolicy,
    val features: V1Features,
    val replayCapabilities: V1ReplayCapabilities,
    /** The transaction-current identity whose context revision and opt state the decision binds. */
    val identity: IdentityState,
    val deviceInEuTimezone: Boolean,
    /** RFC 3339 instant at which the on-device decision was evaluated. */
    val evaluatedAt: String,
    val replay: PrivacyReplayInput = PrivacyReplayInput.UNAVAILABLE,
    /** Android masking rule dialects this runtime can apply natively. None are interpreted today. */
    val recognizedMaskingDialects: Set<String> = emptySet(),
)

/**
 * Produces the effective on-device privacy state for one identity witness. The output is the
 * only privacy input the queue owner accepts; the config manager re-derives every claim from
 * the server policy, so a projection that disagrees with the policy fails authorization closed.
 */
internal object PrivacyStateProjector {
    const val EU_TIMEZONE_EVALUATOR: String = "elu-eu-timezone-v1"

    fun project(input: PrivacyProjectionInput): V1EffectivePrivacyState {
        val policy = input.policy
        val identity = input.identity
        val decision = regionDecision(input)
        val captureAllowed =
            input.features.capture &&
                policy.capture.enabled &&
                decision.decision == V1OnDeviceDecisionValue.ALLOW &&
                !identity.optedOut

        val fallbackRequired = platformFallbackRequired(policy.masking, input.recognizedMaskingDialects)
        val effectiveMasking = effectiveMasking(policy.masking, fallbackRequired)

        val replay = input.replay
        val advertisedTransport =
            replay.transport?.takeIf { transport -> transport in input.replayCapabilities.advertisedTransports }
        val replayAllowed =
            captureAllowed &&
                input.features.replay &&
                policy.replay.enabled &&
                replay.sampled &&
                replay.maskingValidated &&
                replay.sessionEligible &&
                replay.budgetRemainingSeconds > 0 &&
                advertisedTransport != null

        val unhashed =
            V1EffectivePrivacyState(
                schemaVersion = V1_CONFIG_SCHEMA_VERSION,
                policyRevision = policy.revision,
                contextRevision = identity.contextRevision,
                effectivePolicyHash = PLACEHOLDER_HASH,
                onDeviceDecision = decision,
                captureAllowed = captureAllowed,
                replayAllowed = replayAllowed,
                replaySampled = replay.sampled,
                identityOptedOut = identity.optedOut,
                maskingValidated = replay.maskingValidated,
                replaySessionEligible = replay.sessionEligible,
                replayBudgetRemainingSeconds = replay.budgetRemainingSeconds,
                replayTransport =
                    advertisedTransport?.let { transport ->
                        V1ReplayTransportSelection(transport.codec, transport.compression, advertised = true)
                    },
                effectiveMasking = effectiveMasking,
            )
        return unhashed.copy(effectivePolicyHash = hash(unhashed))
    }

    /** The exact JSON document the queue owner validates as an effective privacy candidate. */
    fun encode(state: V1EffectivePrivacyState): String = toJson(state, includeHash = true).toString()

    private fun regionDecision(input: PrivacyProjectionInput): V1OnDeviceDecision {
        val region = input.policy.region
        return when (region.mode) {
            V1RegionPolicyMode.ALLOW ->
                V1OnDeviceDecision(
                    decision = V1OnDeviceDecisionValue.ALLOW,
                    source = V1OnDeviceDecisionSource.NOT_EVALUATED,
                    reason = null,
                    evaluatedAt = input.evaluatedAt,
                )
            V1RegionPolicyMode.BLOCK ->
                V1OnDeviceDecision(
                    decision = V1OnDeviceDecisionValue.BLOCK,
                    source = V1OnDeviceDecisionSource.REMOTE_KILL_SWITCH,
                    reason = "region-policy-block",
                    evaluatedAt = input.evaluatedAt,
                )
            V1RegionPolicyMode.BLOCK_EU_ON_DEVICE ->
                when {
                    region.evaluator != EU_TIMEZONE_EVALUATOR ->
                        V1OnDeviceDecision(
                            decision = V1OnDeviceDecisionValue.UNKNOWN,
                            source = V1OnDeviceDecisionSource.DEVICE_REGION,
                            reason = "unsupported-region-evaluator",
                            evaluatedAt = input.evaluatedAt,
                        )
                    input.deviceInEuTimezone ->
                        V1OnDeviceDecision(
                            decision = V1OnDeviceDecisionValue.BLOCK,
                            source = V1OnDeviceDecisionSource.DEVICE_REGION,
                            reason = "regional-policy",
                            evaluatedAt = input.evaluatedAt,
                        )
                    else ->
                        V1OnDeviceDecision(
                            decision = V1OnDeviceDecisionValue.ALLOW,
                            source = V1OnDeviceDecisionSource.DEVICE_REGION,
                            reason = null,
                            evaluatedAt = input.evaluatedAt,
                        )
                }
        }
    }

    /**
     * Mirrors the config manager's fallback rule: without at least one applicable Android rule,
     * or with any rule in an unrecognized dialect, the platform must apply its strictest posture.
     */
    private fun platformFallbackRequired(
        masking: V1MaskingPolicy,
        recognizedDialects: Set<String>,
    ): Boolean {
        val androidRules = masking.platformRules.filter { rule -> rule.platform == V1PrivacyPlatform.ANDROID }
        val applicable = androidRules.filter { rule -> rule.targetDialect in recognizedDialects }
        val unrecognized = androidRules.any { rule -> rule.targetDialect !in recognizedDialects }
        return applicable.isEmpty() || unrecognized
    }

    /**
     * Screenshot capture masks every text view through one control, so an input rule of `all`
     * also masks static text. The result is always equal to or stricter than the server policy.
     */
    private fun effectiveMasking(
        masking: V1MaskingPolicy,
        fallbackRequired: Boolean,
    ): V1EffectiveMasking {
        if (fallbackRequired) {
            return V1EffectiveMasking(
                text = V1TextMasking.ALL,
                inputs = V1TextMasking.ALL,
                images = V1ImageMasking.BLOCK,
                secureInputsMasked = true,
                platformFallbackApplied = true,
            )
        }
        val text =
            if (masking.text == V1TextMasking.ALL || masking.inputs == V1TextMasking.ALL) {
                V1TextMasking.ALL
            } else {
                V1TextMasking.SENSITIVE
            }
        return V1EffectiveMasking(
            text = text,
            inputs = V1TextMasking.ALL,
            images = masking.images,
            secureInputsMasked = true,
            platformFallbackApplied = false,
        )
    }

    private fun hash(state: V1EffectivePrivacyState): String {
        val document = toJson(state, includeHash = false).toString()
        return V1StrictCanonicalJson.sha256(V1StrictCanonicalJson.parse(document))
    }

    private fun toJson(
        state: V1EffectivePrivacyState,
        includeHash: Boolean,
    ): JSONObject {
        val decision =
            JSONObject()
                .put("decision", state.onDeviceDecision.decision.wireValue)
                .put("source", state.onDeviceDecision.source.wireValue)
        state.onDeviceDecision.reason?.let { decision.put("reason", it) }
        state.onDeviceDecision.evaluatedAt?.let { decision.put("evaluatedAt", it) }
        val transport =
            state.replayTransport?.let { selection ->
                JSONObject()
                    .put("codec", selection.codec)
                    .put("compression", selection.compression.wireValue)
                    .put("advertised", selection.advertised)
            } ?: JSONObject.NULL
        val masking =
            JSONObject()
                .put("text", state.effectiveMasking.text.wireValue)
                .put("inputs", state.effectiveMasking.inputs.wireValue)
                .put("images", state.effectiveMasking.images.wireValue)
                .put("secureInputsMasked", state.effectiveMasking.secureInputsMasked)
                .put("platformFallbackApplied", state.effectiveMasking.platformFallbackApplied)
        val root =
            JSONObject()
                .put("schemaVersion", state.schemaVersion)
                .put("policyRevision", state.policyRevision)
                .put("contextRevision", state.contextRevision)
                .put("onDeviceDecision", decision)
                .put("captureAllowed", state.captureAllowed)
                .put("replayAllowed", state.replayAllowed)
                .put("replaySampled", state.replaySampled)
                .put("identityOptedOut", state.identityOptedOut)
                .put("maskingValidated", state.maskingValidated)
                .put("replaySessionEligible", state.replaySessionEligible)
                .put("replayBudgetRemainingSeconds", state.replayBudgetRemainingSeconds)
                .put("replayTransport", transport)
                .put("effectiveMasking", masking)
        if (includeHash) root.put("effectivePolicyHash", state.effectivePolicyHash)
        return root
    }

    private const val PLACEHOLDER_HASH = "sha256:" + "0000000000000000000000000000000000000000000000000000000000000000"
}
