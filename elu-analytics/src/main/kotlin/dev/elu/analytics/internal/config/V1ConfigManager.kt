package dev.elu.analytics.internal.config

import dev.elu.analytics.internal.core.IdentityState
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TreeMap

/**
 * Serialized authorization boundary for frozen ELU SDK config v1.
 *
 * It retains one active enabled config and a monotonic issuance boundary. It does not perform
 * network fetching, persistence, public-facade calls, provider calls, replay recording, or queue
 * operations. Injected replay pairs must already have passed local engine readback; the default is
 * deliberately empty.
 */
internal class V1ConfigManager(
    readbackProvenReplayTransports: Set<V1ReplayTransport> = emptySet(),
) {
    private val readbackProvenReplayTransports: Set<V1ReplayTransport> =
        Collections.unmodifiableSet(LinkedHashSet(readbackProvenReplayTransports))

    private var activeConfig: InstalledConfig? = null
    private var newestBoundary: V1ParsedConfigBoundary? = null
    private var newestOutcome: V1ConfigUpdateResult? = null
    private var newestBoundaryPoisoned: Boolean = false

    /** Installs a document only if it is newer than the retained issuance boundary. */
    @Synchronized
    fun install(
        configBody: String?,
        nowEpochMillis: Long,
    ): V1ConfigUpdateResult {
        val parsed: V1ParsedConfig
        try {
            parsed = V1ConfigJson.parseConfig(configBody)
        } catch (_: V1UnsupportedConfigSchemaException) {
            return installInvalidDocument(configBody, nowEpochMillis, V1ConfigRejection.UNSUPPORTED_SCHEMA)
        } catch (_: V1MalformedConfigException) {
            return installInvalidDocument(configBody, nowEpochMillis, V1ConfigRejection.MALFORMED)
        }

        return installAtBoundary(parsed.toBoundary(), parsed, nowEpochMillis, null)
    }

    /** Resolves authority from the one active config without mutating its issuance boundary. */
    @Synchronized
    fun authorize(
        effectivePrivacyBody: String?,
        identity: IdentityState,
        nowEpochMillis: Long,
    ): V1ConfigResolution {
        val installed = activeConfig ?: return rejected(unavailableReason())
        if (installed.config.expiresAtInstant <= V1ExactTimestamp.fromEpochMillis(nowEpochMillis)) {
            activeConfig = null
            newestOutcome = V1ConfigUpdateResult.Rejected(V1ConfigRejection.EXPIRED)
            return rejected(V1ConfigRejection.EXPIRED)
        }

        val config = installed.config
        val policy = checkNotNull(config.privacy)
        val features = checkNotNull(config.features)
        val replayCapabilities = checkNotNull(config.replayCapabilities)
        var effectivePrivacy: V1EffectivePrivacyState? = null
        val privacyDecision =
            if (identity.schemaVersion != V1_CONFIG_SCHEMA_VERSION) {
                invalidPrivacy(V1ChannelAuthorizationReason.IDENTITY_SCHEMA_UNSUPPORTED)
            } else if (effectivePrivacyBody == null) {
                invalidPrivacy(V1ChannelAuthorizationReason.PRIVACY_STATE_MISSING)
            } else {
                try {
                    effectivePrivacy = V1ConfigJson.parseEffectivePrivacy(effectivePrivacyBody)
                    privacyDecision(policy, features, replayCapabilities, checkNotNull(effectivePrivacy), identity)
                } catch (_: V1UnsupportedConfigSchemaException) {
                    invalidPrivacy(V1ChannelAuthorizationReason.PRIVACY_SCHEMA_UNSUPPORTED)
                } catch (_: V1MalformedConfigException) {
                    invalidPrivacy(V1ChannelAuthorizationReason.PRIVACY_STATE_MALFORMED)
                }
            }

        val endpoints =
            V1ResolvedEndpointSet(
                events = installed.endpoints.events.takeIf { privacyDecision.capture.status == V1ChannelAuthorizationStatus.AUTHORIZED },
                replay = installed.endpoints.replay.takeIf { privacyDecision.replay.status == V1ChannelAuthorizationStatus.AUTHORIZED },
                flags = installed.endpoints.flags.takeIf { features.flags },
                // Native SDKs validate but never acquire browser asset authority.
                assets = null,
            )

        return V1ConfigResolution.Authorized(
            V1AuthorizedConfig(
                revision = config.revision,
                issuedAt = config.issuedAt,
                expiresAt = config.expiresAt,
                issuedAtInstant = config.issuedAtInstant,
                expiresAtInstant = config.expiresAtInstant,
                configSemanticHash = config.configSemanticHash,
                policySourceHash = checkNotNull(config.policySourceHash),
                siteId = checkNotNull(config.siteId),
                endpoints = endpoints,
                privacy = policy,
                effectivePrivacy = effectivePrivacy,
                captureAuthorization = privacyDecision.capture,
                replayAuthorization = privacyDecision.replay,
                features = features,
                replayCapabilities = replayCapabilities,
                negotiatedReplayTransport = privacyDecision.negotiatedReplayTransport,
                session = checkNotNull(config.session),
                limits = checkNotNull(config.limits),
            ),
        )
    }

    /** Explicit lifecycle reset; ordinary failed/stale updates retain the anti-rollback boundary. */
    @Synchronized
    fun clear() {
        activeConfig = null
        newestBoundary = null
        newestOutcome = null
        newestBoundaryPoisoned = false
    }

    private fun installInvalidDocument(
        configBody: String?,
        nowEpochMillis: Long,
        rejection: V1ConfigRejection,
    ): V1ConfigUpdateResult {
        val boundary =
            try {
                V1ConfigJson.parseConfigBoundary(configBody)
            } catch (_: V1UnsupportedConfigSchemaException) {
                invalidateCurrentBoundary(rejection)
                return updateRejected(rejection)
            } catch (_: V1MalformedConfigException) {
                invalidateCurrentBoundary(rejection)
                return updateRejected(rejection)
            }
        return installAtBoundary(boundary, null, nowEpochMillis, rejection)
    }

    private fun installAtBoundary(
        boundary: V1ParsedConfigBoundary,
        parsed: V1ParsedConfig?,
        nowEpochMillis: Long,
        parseRejection: V1ConfigRejection?,
    ): V1ConfigUpdateResult {
        val retained = newestBoundary
        if (retained != null) {
            val order = boundary.issuedAtInstant.compareTo(retained.issuedAtInstant)
            if (order < 0) return updateRejected(V1ConfigRejection.STALE)
            if (order == 0) {
                if (boundary.configSemanticHash != retained.configSemanticHash) {
                    activeConfig = null
                    newestBoundaryPoisoned = true
                    newestOutcome = updateRejected(V1ConfigRejection.CONFLICT)
                    return checkNotNull(newestOutcome)
                }
                if (newestBoundaryPoisoned) return updateRejected(V1ConfigRejection.CONFLICT)
                val active = activeConfig
                if (active != null && active.config.expiresAtInstant <= V1ExactTimestamp.fromEpochMillis(nowEpochMillis)) {
                    activeConfig = null
                    newestOutcome = updateRejected(V1ConfigRejection.EXPIRED)
                }
                return checkNotNull(newestOutcome)
            }
        }

        newestBoundary = boundary
        newestBoundaryPoisoned = false
        activeConfig = null

        if (parseRejection != null || parsed == null) {
            return remember(updateRejected(parseRejection ?: V1ConfigRejection.MALFORMED))
        }
        if (parsed.expiresAtInstant <= V1ExactTimestamp.fromEpochMillis(nowEpochMillis)) {
            return remember(updateRejected(V1ConfigRejection.EXPIRED))
        }
        if (parsed.status != V1ConfigStatus.ENABLED) {
            return remember(V1ConfigUpdateResult.Inactive(parsed.status, parsed.revision))
        }

        val endpoints =
            try {
                validateEndpointSet(checkNotNull(parsed.endpoints))
            } catch (_: V1EndpointAuthorizationException) {
                return remember(updateRejected(V1ConfigRejection.UNAUTHORIZED))
            }
        activeConfig = InstalledConfig(parsed, endpoints)
        return remember(V1ConfigUpdateResult.Enabled(parsed.revision, parsed.expiresAtEpochMillis))
    }

    private fun invalidateCurrentBoundary(rejection: V1ConfigRejection) {
        activeConfig = null
        if (newestBoundary != null) {
            newestBoundaryPoisoned = true
            newestOutcome = updateRejected(rejection)
        }
    }

    private fun remember(result: V1ConfigUpdateResult): V1ConfigUpdateResult {
        newestOutcome = result
        return result
    }

    private fun unavailableReason(): V1ConfigRejection =
        when (val outcome = newestOutcome) {
            is V1ConfigUpdateResult.Rejected -> outcome.reason
            else -> V1ConfigRejection.INACTIVE
        }

    private fun privacyDecision(
        policy: V1ServerPrivacyPolicy,
        features: V1Features,
        replayCapabilities: V1ReplayCapabilities,
        effective: V1EffectivePrivacyState,
        identity: IdentityState,
    ): PrivacyDecision {
        if (effective.policyRevision != policy.revision) {
            return invalidPrivacy(V1ChannelAuthorizationReason.POLICY_REVISION_MISMATCH)
        }
        if (effective.contextRevision != identity.contextRevision) {
            return invalidPrivacy(V1ChannelAuthorizationReason.CONTEXT_REVISION_MISMATCH)
        }
        if (effective.identityOptedOut != identity.optedOut) {
            return invalidPrivacy(V1ChannelAuthorizationReason.IDENTITY_OPT_STATE_MISMATCH)
        }
        if (!V1PrivacyStateHash.matches(effective)) {
            return invalidPrivacy(V1ChannelAuthorizationReason.POLICY_HASH_MISMATCH)
        }
        val regionConflict =
            policy.region.mode == V1RegionPolicyMode.BLOCK &&
                effective.onDeviceDecision.decision == V1OnDeviceDecisionValue.ALLOW

        val selectedTransport = effective.replayTransport
        val selectedPair = selectedTransport?.let { V1ReplayTransport(it.codec, it.compression) }
        val transportAdvertised =
            selectedTransport != null &&
                selectedTransport.advertised &&
                selectedTransport.codec in replayCapabilities.acceptedCodecs &&
                selectedTransport.compression in replayCapabilities.acceptedCompressions

        val expectedCaptureAllowed =
            features.capture &&
                policy.capture.enabled &&
                effective.onDeviceDecision.decision == V1OnDeviceDecisionValue.ALLOW &&
                !identity.optedOut
        val captureRestrictionReason =
            when {
                !features.capture -> V1ChannelAuthorizationReason.FEATURE_DISABLED
                !policy.capture.enabled -> V1ChannelAuthorizationReason.POLICY_DISABLED
                identity.optedOut -> V1ChannelAuthorizationReason.IDENTITY_OPTED_OUT
                effective.onDeviceDecision.decision != V1OnDeviceDecisionValue.ALLOW ->
                    V1ChannelAuthorizationReason.DECISION_NOT_ALLOWED
                else -> V1ChannelAuthorizationReason.AUTHORIZED
            }
        val capture =
            when {
                regionConflict -> invalid(V1ChannelAuthorizationReason.REGION_POLICY_CONFLICT)
                effective.captureAllowed != expectedCaptureAllowed ->
                    invalid(V1ChannelAuthorizationReason.DERIVED_CLAIM_MISMATCH)
                expectedCaptureAllowed -> authorized()
                else -> restricted(captureRestrictionReason)
            }

        val expectedReplayAllowed =
            expectedCaptureAllowed &&
                features.replay &&
                policy.replay.enabled &&
                effective.replaySampled &&
                effective.maskingValidated &&
                effective.replaySessionEligible &&
                effective.replayBudgetRemainingSeconds > 0 &&
                transportAdvertised
        val replay =
            when {
                !maskingIsEqualOrStricter(policy.masking, effective.effectiveMasking) ->
                    invalid(V1ChannelAuthorizationReason.MASKING_POLICY_VIOLATION)
                selectedTransport != null && !transportAdvertised ->
                    invalid(V1ChannelAuthorizationReason.TRANSPORT_NOT_ADVERTISED)
                effective.replayAllowed != expectedReplayAllowed ->
                    invalid(V1ChannelAuthorizationReason.DERIVED_CLAIM_MISMATCH)
                !expectedCaptureAllowed -> restricted(captureRestrictionReason)
                capture.status != V1ChannelAuthorizationStatus.AUTHORIZED ->
                    restricted(V1ChannelAuthorizationReason.CAPTURE_RESTRICTED)
                !features.replay -> restricted(V1ChannelAuthorizationReason.FEATURE_DISABLED)
                !policy.replay.enabled -> restricted(V1ChannelAuthorizationReason.POLICY_DISABLED)
                !effective.replaySampled -> restricted(V1ChannelAuthorizationReason.REPLAY_NOT_SAMPLED)
                !effective.maskingValidated -> restricted(V1ChannelAuthorizationReason.MASKING_NOT_VALIDATED)
                !effective.replaySessionEligible -> restricted(V1ChannelAuthorizationReason.SESSION_INELIGIBLE)
                effective.replayBudgetRemainingSeconds <= 0 -> restricted(V1ChannelAuthorizationReason.BUDGET_EXHAUSTED)
                selectedPair == null -> restricted(V1ChannelAuthorizationReason.TRANSPORT_MISSING)
                androidPlatformFallbackRequired(policy.masking) && !effective.effectiveMasking.platformFallbackApplied ->
                    restricted(V1ChannelAuthorizationReason.PLATFORM_FALLBACK_REQUIRED)
                selectedPair !in readbackProvenReplayTransports ->
                    restricted(V1ChannelAuthorizationReason.LOCAL_TRANSPORT_UNPROVEN)
                else -> authorized()
            }
        return PrivacyDecision(
            capture = capture,
            replay = replay,
            negotiatedReplayTransport = selectedPair.takeIf { replay.status == V1ChannelAuthorizationStatus.AUTHORIZED },
        )
    }

    private fun invalidPrivacy(reason: V1ChannelAuthorizationReason): PrivacyDecision {
        val invalid = invalid(reason)
        return PrivacyDecision(invalid, invalid, null)
    }

    private fun authorized(): V1ChannelAuthorization =
        V1ChannelAuthorization(V1ChannelAuthorizationStatus.AUTHORIZED, V1ChannelAuthorizationReason.AUTHORIZED)

    private fun restricted(reason: V1ChannelAuthorizationReason): V1ChannelAuthorization =
        V1ChannelAuthorization(V1ChannelAuthorizationStatus.RESTRICTED, reason)

    private fun invalid(reason: V1ChannelAuthorizationReason): V1ChannelAuthorization =
        V1ChannelAuthorization(V1ChannelAuthorizationStatus.INVALID, reason)

    private fun androidPlatformFallbackRequired(masking: V1MaskingPolicy): Boolean {
        val androidRules = masking.platformRules.filter { it.platform == V1PrivacyPlatform.ANDROID }
        // This config manager registers no Android target-dialect interpreter. Therefore no rule is
        // applicable; absent rules and every opaque dialect both require native fallback.
        val applicableRules = androidRules.filter { it.targetDialect in RECOGNIZED_ANDROID_MASKING_DIALECTS }
        val hasUnrecognizedRule = androidRules.any { it.targetDialect !in RECOGNIZED_ANDROID_MASKING_DIALECTS }
        return applicableRules.isEmpty() || hasUnrecognizedRule
    }

    private fun maskingIsEqualOrStricter(
        policy: V1MaskingPolicy,
        effective: V1EffectiveMasking,
    ): Boolean {
        if (!effective.secureInputsMasked) return false
        if (policy.text == V1TextMasking.ALL && effective.text != V1TextMasking.ALL) return false
        if (policy.inputs == V1TextMasking.ALL && effective.inputs != V1TextMasking.ALL) return false
        if (policy.images == V1ImageMasking.BLOCK && effective.images != V1ImageMasking.BLOCK) return false
        return true
    }

    private fun validateEndpointSet(configured: V1ConfiguredEndpointSet): ParsedEndpointSet =
        ParsedEndpointSet(
            events = validateEndpoint(V1EndpointRole.EVENTS, configured.events),
            replay = configured.replay?.let { validateEndpoint(V1EndpointRole.REPLAY, it) },
            flags = validateEndpoint(V1EndpointRole.FLAGS, configured.flags),
            assets = configured.assets?.let { validateEndpoint(V1EndpointRole.ASSETS, it) },
        )

    private fun validateEndpoint(
        role: V1EndpointRole,
        raw: String,
    ): URI {
        if (raw.any { it.code !in 0x21..0x7e }) unauthorized("$role endpoint must be an ASCII URI")
        if (!raw.startsWith("https://")) unauthorized("$role endpoint must use HTTPS")
        val uri =
            try {
                URI(raw)
            } catch (error: URISyntaxException) {
                unauthorized("$role endpoint must be an absolute URI", error)
            }
        if (!uri.isAbsolute || uri.scheme != "https" || uri.host == null) unauthorized("$role endpoint is not HTTPS")
        if (uri.rawUserInfo != null) unauthorized("$role endpoint must not contain user information")
        if (uri.rawFragment != null) unauthorized("$role endpoint must not contain a fragment")
        if (uri.port != -1 && uri.port != 443) unauthorized("$role endpoint uses an untrusted port")

        val authority = ENDPOINT_AUTHORITIES.getValue(role)
        if (uri.host.lowercase(Locale.US) != authority.host || uri.rawPath != authority.path) {
            unauthorized("$role endpoint is outside its ELU role allowlist")
        }
        if (containsReservedSiteKey(uri.rawQuery)) unauthorized("$role endpoint contains reserved authorization state")
        return uri
    }

    private fun containsReservedSiteKey(rawQuery: String?): Boolean {
        if (rawQuery == null) return false
        return rawQuery.split('&').any { pair ->
            val rawName = pair.substringBefore('=')
            val decodedName =
                try {
                    URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
                } catch (_: IllegalArgumentException) {
                    unauthorized("endpoint query is malformed")
                }
            decodedName == SITE_KEY_QUERY_PARAMETER
        }
    }

    private fun V1ParsedConfig.toBoundary(): V1ParsedConfigBoundary =
        V1ParsedConfigBoundary(
            revision = revision,
            issuedAtInstant = issuedAtInstant,
            expiresAtInstant = expiresAtInstant,
            serialized = serialized,
            configSemanticHash = configSemanticHash,
        )

    private fun rejected(reason: V1ConfigRejection): V1ConfigResolution = V1ConfigResolution.Rejected(reason)

    private fun updateRejected(reason: V1ConfigRejection): V1ConfigUpdateResult =
        V1ConfigUpdateResult.Rejected(reason)

    private fun unauthorized(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw V1EndpointAuthorizationException(message, cause)

    private data class EndpointAuthority(
        val host: String,
        val path: String,
    )

    private data class ParsedEndpointSet(
        val events: URI,
        val replay: URI?,
        val flags: URI,
        @Suppress("unused") val assets: URI?,
    )

    private data class InstalledConfig(
        val config: V1ParsedConfig,
        val endpoints: ParsedEndpointSet,
    )

    private data class PrivacyDecision(
        val capture: V1ChannelAuthorization,
        val replay: V1ChannelAuthorization,
        val negotiatedReplayTransport: V1ReplayTransport?,
    )

    private companion object {
        val ENDPOINT_AUTHORITIES =
            mapOf(
                V1EndpointRole.EVENTS to EndpointAuthority("ingest.elu.dev", "/v1/events"),
                V1EndpointRole.REPLAY to EndpointAuthority("ingest.elu.dev", "/v1/replay"),
                V1EndpointRole.FLAGS to EndpointAuthority("ingest.elu.dev", "/v1/flags"),
                V1EndpointRole.ASSETS to EndpointAuthority("assets.elu.dev", "/sdk/"),
            )
        val RECOGNIZED_ANDROID_MASKING_DIALECTS: Set<String> = emptySet()
        const val SITE_KEY_QUERY_PARAMETER = "site_key"
    }
}

private class V1EndpointAuthorizationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** RFC 8785 encoding for the closed effective-privacy shape, with the hash member omitted. */
private object V1PrivacyStateHash {
    fun matches(state: V1EffectivePrivacyState): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(state))
        val expected = "sha256:" + digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return constantTimeEquals(expected, state.effectivePolicyHash)
    }

    private fun canonicalBytes(state: V1EffectivePrivacyState): ByteArray =
        canonicalize(toHashableValue(state)).toByteArray(StandardCharsets.UTF_8)

    private fun toHashableValue(state: V1EffectivePrivacyState): Map<String, Any?> =
        mapOf(
            "schemaVersion" to state.schemaVersion,
            "policyRevision" to state.policyRevision,
            "contextRevision" to state.contextRevision,
            "onDeviceDecision" to
                mapOf(
                    "decision" to state.onDeviceDecision.decision.wireValue,
                    "source" to state.onDeviceDecision.source.wireValue,
                ).toMutableMap().apply {
                    state.onDeviceDecision.reason?.let { put("reason", it) }
                    state.onDeviceDecision.evaluatedAt?.let { put("evaluatedAt", it) }
                },
            "captureAllowed" to state.captureAllowed,
            "replayAllowed" to state.replayAllowed,
            "replaySampled" to state.replaySampled,
            "identityOptedOut" to state.identityOptedOut,
            "maskingValidated" to state.maskingValidated,
            "replaySessionEligible" to state.replaySessionEligible,
            "replayBudgetRemainingSeconds" to state.replayBudgetRemainingSeconds,
            "replayTransport" to
                state.replayTransport?.let {
                    mapOf(
                        "codec" to it.codec,
                        "compression" to it.compression.wireValue,
                        "advertised" to it.advertised,
                    )
                },
            "effectiveMasking" to
                mapOf(
                    "text" to state.effectiveMasking.text.wireValue,
                    "inputs" to state.effectiveMasking.inputs.wireValue,
                    "images" to state.effectiveMasking.images.wireValue,
                    "secureInputsMasked" to state.effectiveMasking.secureInputsMasked,
                    "platformFallbackApplied" to state.effectiveMasking.platformFallbackApplied,
                ),
        )

    private fun canonicalize(value: Any?): String =
        when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Int -> value.toString()
            is Long -> value.toString()
            is String -> quote(value)
            is Map<*, *> -> {
                val sorted = TreeMap<String, Any?>()
                value.forEach { (key, child) -> sorted[key as String] = child }
                sorted.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, child) ->
                    quote(key) + ":" + canonicalize(child)
                }
            }
            else -> throw IllegalStateException("Unsupported canonical privacy value")
        }

    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\u000c' -> append("\\f")
                    '\r' -> append("\\r")
                    else -> {
                        if (character.code < 0x20) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
            }
            append('"')
        }

    private fun constantTimeEquals(
        left: String,
        right: String,
    ): Boolean {
        if (left.length != right.length) return false
        var difference = 0
        left.indices.forEach { index -> difference = difference or (left[index].code xor right[index].code) }
        return difference == 0
    }
}
