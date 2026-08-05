package dev.elu.analytics.internal.config

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashSet
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal class V1MalformedConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal class V1UnsupportedConfigSchemaException(val foundVersion: Long) :
    IllegalArgumentException("Unsupported ELU SDK config schema version: $foundVersion")

/** Strict, closed-shape codecs for the frozen v1 config and effective privacy schemas. */
internal object V1ConfigJson {
    private const val MAX_CONFIG_BYTES = 65_536
    private const val MAX_PRIVACY_BYTES = 32_768
    private const val MAX_JSON_NESTING = 64

    private val configRequired = setOf("schemaVersion", "revision", "issuedAt", "expiresAt", "status")
    private val configOptional =
        setOf("site", "endpoints", "privacy", "features", "capabilities", "session", "limits", "reason")
    private val siteRequired = setOf("id")
    private val endpointsRequired = setOf("events", "flags")
    private val endpointsOptional = setOf("replay", "assets")
    private val featuresRequired = setOf("capture", "replay", "flags", "assets")
    private val capabilitiesRequired = setOf("replay")
    private val replayCapabilitiesRequired = setOf("acceptedCodecs", "acceptedCompressions")
    private val sessionRequired = setOf("idleTimeoutSeconds", "maximumDurationSeconds")
    private val limitsRequired = setOf("eventBatchCount", "eventBatchBytes", "replayChunkBytes", "queueBytes")

    private val policyRequired = setOf("schemaVersion", "revision", "capture", "replay", "masking", "regionPolicy")
    private val capturePolicyRequired = setOf("enabled")
    private val capturePolicyOptional = setOf("reason")
    private val replayPolicyRequired =
        setOf("enabled", "sampleRate", "minimumDurationSeconds", "maximumDurationSeconds")
    private val replayPolicyOptional = setOf("reason")
    private val maskingRequired = setOf("text", "inputs", "images", "secureInputsMasked")
    private val maskingOptional = setOf("platformRules")
    private val platformRuleRequired = setOf("platform", "action", "targetDialect", "target")
    private val regionRequired = setOf("mode")
    private val regionOptional = setOf("evaluator")

    private val privacyRequired =
        setOf(
            "schemaVersion",
            "policyRevision",
            "contextRevision",
            "effectivePolicyHash",
            "onDeviceDecision",
            "captureAllowed",
            "replayAllowed",
            "replaySampled",
            "identityOptedOut",
            "maskingValidated",
            "replaySessionEligible",
            "replayBudgetRemainingSeconds",
            "replayTransport",
            "effectiveMasking",
        )
    private val decisionRequired = setOf("decision", "source")
    private val decisionOptional = setOf("reason", "evaluatedAt")
    private val replayTransportRequired = setOf("codec", "compression", "advertised")
    private val effectiveMaskingRequired =
        setOf("text", "inputs", "images", "secureInputsMasked", "platformFallbackApplied")

    fun parseConfigBoundary(body: String?): V1ParsedConfigBoundary =
        parseConfigBoundary(parseRoot(body, MAX_CONFIG_BYTES, "config"))

    fun parseConfig(body: String?): V1ParsedConfig {
        val root = parseRoot(body, MAX_CONFIG_BYTES, "config")
        val boundary = parseConfigBoundary(root)
        val revision = boundary.revision
        val issuedAt = requiredString(root, "issuedAt", 1, Int.MAX_VALUE, "config")
        val issuedAtInstant = boundary.issuedAtInstant
        val expiresAt = requiredString(root, "expiresAt", 1, Int.MAX_VALUE, "config")
        val expiresAtInstant = boundary.expiresAtInstant
        val status =
            checkNotNull(enumValue<V1ConfigStatus>(requiredString(root, "status", 1, Int.MAX_VALUE, "config")))

        val siteId = optionalObject(root, "site")?.let(::parseSite)
        val endpoints = optionalObject(root, "endpoints")?.let(::parseEndpoints)
        val privacy = optionalObject(root, "privacy")?.let(::parsePrivacyPolicy)
        val features = optionalObject(root, "features")?.let(::parseFeatures)
        val replayCapabilities = optionalObject(root, "capabilities")?.let(::parseCapabilities)
        val session = optionalObject(root, "session")?.let(::parseSession)
        val limits = optionalObject(root, "limits")?.let(::parseLimits)
        val reason = optionalString(root, "reason", 0, 256, "config")

        if (status == V1ConfigStatus.ENABLED) {
            if (
                siteId == null || endpoints == null || privacy == null || features == null ||
                replayCapabilities == null || session == null || limits == null
            ) {
                malformed("enabled config is missing an authorization field")
            }
            if (features.replay && (endpoints.replay == null || replayCapabilities.acceptedCodecs.isEmpty() || replayCapabilities.acceptedCompressions.isEmpty())) {
                malformed("replay-enabled config must advertise an endpoint, codec, and compression")
            }
            if (features.assets && endpoints.assets == null) {
                malformed("assets-enabled config must advertise an assets endpoint")
            }
        } else {
            if (reason == null) malformed("inactive config must include reason")
            if (siteId != null || endpoints != null) malformed("inactive config must not include site or endpoints")
        }

        return V1ParsedConfig(
            revision = revision,
            issuedAt = issuedAt,
            issuedAtInstant = issuedAtInstant,
            expiresAt = expiresAt,
            expiresAtInstant = expiresAtInstant,
            expiresAtEpochMillis = expiresAtInstant.toEpochMillisFloor(),
            status = status,
            siteId = siteId,
            endpoints = endpoints,
            privacy = privacy,
            features = features,
            replayCapabilities = replayCapabilities,
            session = session,
            limits = limits,
            serialized = boundary.serialized,
        )
    }

    private fun parseConfigBoundary(root: JSONObject): V1ParsedConfigBoundary {
        expectFields(root, configRequired, configOptional, "config")
        readSchemaVersion(root, "config")
        val revision = requiredString(root, "revision", 1, 128, "config")
        val issuedAt = requiredString(root, "issuedAt", 1, Int.MAX_VALUE, "config")
        val issuedAtInstant = parseRfc3339(issuedAt, "config.issuedAt")
        val expiresAt = requiredString(root, "expiresAt", 1, Int.MAX_VALUE, "config")
        val expiresAtInstant = parseRfc3339(expiresAt, "config.expiresAt")
        if (issuedAtInstant >= expiresAtInstant) malformed("config.issuedAt must be before expiresAt")
        if (enumValue<V1ConfigStatus>(requiredString(root, "status", 1, Int.MAX_VALUE, "config")) == null) {
            malformed("config.status is unsupported")
        }
        return V1ParsedConfigBoundary(
            revision = revision,
            issuedAtInstant = issuedAtInstant,
            expiresAtInstant = expiresAtInstant,
            serialized = root.toString(),
        )
    }

    fun parseEffectivePrivacy(body: String?): V1EffectivePrivacyState {
        val root = parseRoot(body, MAX_PRIVACY_BYTES, "effective privacy state")
        expectFields(root, privacyRequired, emptySet(), "effective privacy state")
        val schemaVersion = readSchemaVersion(root, "effective privacy state")
        val policyRevision = requiredString(root, "policyRevision", 1, 128, "effective privacy state")
        val contextRevision = requiredLong(root, "contextRevision", 0, Long.MAX_VALUE, "effective privacy state")
        val effectivePolicyHash =
            requiredString(root, "effectivePolicyHash", 71, 71, "effective privacy state")
        if (!POLICY_HASH.matches(effectivePolicyHash)) malformed("effective privacy state hash is malformed")
        val decision = parseDecision(requiredObject(root, "onDeviceDecision", "effective privacy state"))
        val captureAllowed = requiredBoolean(root, "captureAllowed", "effective privacy state")
        val replayAllowed = requiredBoolean(root, "replayAllowed", "effective privacy state")
        val replaySampled = requiredBoolean(root, "replaySampled", "effective privacy state")
        val identityOptedOut = requiredBoolean(root, "identityOptedOut", "effective privacy state")
        val maskingValidated = requiredBoolean(root, "maskingValidated", "effective privacy state")
        val replaySessionEligible = requiredBoolean(root, "replaySessionEligible", "effective privacy state")
        val replayBudgetRemainingSeconds =
            requiredInt(root, "replayBudgetRemainingSeconds", 0, 86_400, "effective privacy state")
        val replayTransport =
            if (root.isNull("replayTransport")) {
                null
            } else {
                parseReplayTransport(requiredObject(root, "replayTransport", "effective privacy state"))
            }
        val effectiveMasking =
            parseEffectiveMasking(requiredObject(root, "effectiveMasking", "effective privacy state"))

        return V1EffectivePrivacyState(
            schemaVersion = schemaVersion,
            policyRevision = policyRevision,
            contextRevision = contextRevision,
            effectivePolicyHash = effectivePolicyHash,
            onDeviceDecision = decision,
            captureAllowed = captureAllowed,
            replayAllowed = replayAllowed,
            replaySampled = replaySampled,
            identityOptedOut = identityOptedOut,
            maskingValidated = maskingValidated,
            replaySessionEligible = replaySessionEligible,
            replayBudgetRemainingSeconds = replayBudgetRemainingSeconds,
            replayTransport = replayTransport,
            effectiveMasking = effectiveMasking,
        )
    }

    private fun parseSite(json: JSONObject): String {
        expectFields(json, siteRequired, emptySet(), "config.site")
        return requiredString(json, "id", 1, 128, "config.site")
    }

    private fun parseEndpoints(json: JSONObject): V1ConfiguredEndpointSet {
        expectFields(json, endpointsRequired, endpointsOptional, "config.endpoints")
        return V1ConfiguredEndpointSet(
            events = requiredString(json, "events", 1, Int.MAX_VALUE, "config.endpoints"),
            replay = optionalString(json, "replay", 1, Int.MAX_VALUE, "config.endpoints"),
            flags = requiredString(json, "flags", 1, Int.MAX_VALUE, "config.endpoints"),
            assets = optionalString(json, "assets", 1, Int.MAX_VALUE, "config.endpoints"),
        )
    }

    private fun parseFeatures(json: JSONObject): V1Features {
        expectFields(json, featuresRequired, emptySet(), "config.features")
        return V1Features(
            capture = requiredBoolean(json, "capture", "config.features"),
            replay = requiredBoolean(json, "replay", "config.features"),
            flags = requiredBoolean(json, "flags", "config.features"),
            assets = requiredBoolean(json, "assets", "config.features"),
        )
    }

    private fun parseCapabilities(json: JSONObject): V1ReplayCapabilities {
        expectFields(json, capabilitiesRequired, emptySet(), "config.capabilities")
        val replay = requiredObject(json, "replay", "config.capabilities")
        expectFields(replay, replayCapabilitiesRequired, emptySet(), "config.capabilities.replay")
        val codecs =
            parseUniqueStringArray(
                requiredArray(replay, "acceptedCodecs", "config.capabilities.replay"),
                maximumSize = 32,
                path = "config.capabilities.replay.acceptedCodecs",
            ) { value -> REPLAY_CODEC.matches(value) }
        val compressions =
            parseUniqueStringArray(
                requiredArray(replay, "acceptedCompressions", "config.capabilities.replay"),
                maximumSize = 8,
                path = "config.capabilities.replay.acceptedCompressions",
            ) { value -> enumValue<V1ReplayCompression>(value) != null }
                .map { value -> checkNotNull(enumValue<V1ReplayCompression>(value)) }
        return V1ReplayCapabilities(immutableList(codecs), immutableList(compressions))
    }

    private fun parseSession(json: JSONObject): V1SessionConfiguration {
        expectFields(json, sessionRequired, emptySet(), "config.session")
        return V1SessionConfiguration(
            idleTimeoutSeconds = requiredInt(json, "idleTimeoutSeconds", 60, 36_000, "config.session"),
            maximumDurationSeconds = requiredInt(json, "maximumDurationSeconds", 86_400, 86_400, "config.session"),
        )
    }

    private fun parseLimits(json: JSONObject): V1ConfigLimits {
        expectFields(json, limitsRequired, emptySet(), "config.limits")
        return V1ConfigLimits(
            eventBatchCount = requiredInt(json, "eventBatchCount", 1, 1_000, "config.limits"),
            eventBatchBytes = requiredInt(json, "eventBatchBytes", 1_024, 10_485_760, "config.limits"),
            replayChunkBytes = requiredInt(json, "replayChunkBytes", 1_024, 52_428_800, "config.limits"),
            queueBytes = requiredInt(json, "queueBytes", 1_024, 268_435_456, "config.limits"),
        )
    }

    private fun parsePrivacyPolicy(json: JSONObject): V1ServerPrivacyPolicy {
        expectFields(json, policyRequired, emptySet(), "config.privacy")
        val schemaVersion = readSchemaVersion(json, "config.privacy")
        return V1ServerPrivacyPolicy(
            schemaVersion = schemaVersion,
            revision = requiredString(json, "revision", 1, 128, "config.privacy"),
            capture = parseCapturePolicy(requiredObject(json, "capture", "config.privacy")),
            replay = parseReplayPolicy(requiredObject(json, "replay", "config.privacy")),
            masking = parseMaskingPolicy(requiredObject(json, "masking", "config.privacy")),
            region = parseRegionPolicy(requiredObject(json, "regionPolicy", "config.privacy")),
        )
    }

    private fun parseCapturePolicy(json: JSONObject): V1CapturePolicy {
        expectFields(json, capturePolicyRequired, capturePolicyOptional, "config.privacy.capture")
        return V1CapturePolicy(
            enabled = requiredBoolean(json, "enabled", "config.privacy.capture"),
            reason = optionalString(json, "reason", 0, 256, "config.privacy.capture"),
        )
    }

    private fun parseReplayPolicy(json: JSONObject): V1ReplayPolicy {
        expectFields(json, replayPolicyRequired, replayPolicyOptional, "config.privacy.replay")
        return V1ReplayPolicy(
            enabled = requiredBoolean(json, "enabled", "config.privacy.replay"),
            reason = optionalString(json, "reason", 0, 256, "config.privacy.replay"),
            sampleRate = requiredDouble(json, "sampleRate", 0.0, 1.0, "config.privacy.replay"),
            minimumDurationSeconds =
                requiredInt(json, "minimumDurationSeconds", 0, 3_600, "config.privacy.replay"),
            maximumDurationSeconds =
                requiredInt(json, "maximumDurationSeconds", 0, 86_400, "config.privacy.replay"),
        )
    }

    private fun parseMaskingPolicy(json: JSONObject): V1MaskingPolicy {
        expectFields(json, maskingRequired, maskingOptional, "config.privacy.masking")
        val text = enumValue<V1TextMasking>(requiredString(json, "text", 1, Int.MAX_VALUE, "config.privacy.masking"))
            ?: malformed("config.privacy.masking.text is unsupported")
        val inputs = enumValue<V1TextMasking>(requiredString(json, "inputs", 1, Int.MAX_VALUE, "config.privacy.masking"))
            ?: malformed("config.privacy.masking.inputs is unsupported")
        val images = enumValue<V1ImageMasking>(requiredString(json, "images", 1, Int.MAX_VALUE, "config.privacy.masking"))
            ?: malformed("config.privacy.masking.images is unsupported")
        val secureInputsMasked = requiredBoolean(json, "secureInputsMasked", "config.privacy.masking")
        if (!secureInputsMasked) malformed("config.privacy.masking.secureInputsMasked must be true")
        val rules =
            if (json.has("platformRules")) {
                val array = requiredArray(json, "platformRules", "config.privacy.masking")
                if (array.length() > 128) malformed("config.privacy.masking.platformRules exceeds 128 entries")
                List(array.length()) { index ->
                    parsePlatformRule(array.opt(index) as? JSONObject ?: malformed("platform rule must be an object"))
                }
            } else {
                emptyList()
            }
        return V1MaskingPolicy(text, inputs, images, true, immutableList(rules))
    }

    private fun parsePlatformRule(json: JSONObject): V1PlatformMaskingRule {
        expectFields(json, platformRuleRequired, emptySet(), "config.privacy.masking.platformRule")
        val platform = enumValue<V1PrivacyPlatform>(requiredString(json, "platform", 1, Int.MAX_VALUE, "platformRule"))
            ?: malformed("platformRule.platform is unsupported")
        val action = enumValue<V1PlatformRuleAction>(requiredString(json, "action", 1, Int.MAX_VALUE, "platformRule"))
            ?: malformed("platformRule.action is unsupported")
        val dialect = requiredString(json, "targetDialect", 1, 68, "platformRule")
        if (!REPLAY_CODEC.matches(dialect)) malformed("platformRule.targetDialect is malformed")
        return V1PlatformMaskingRule(
            platform = platform,
            action = action,
            targetDialect = dialect,
            target = requiredString(json, "target", 1, 512, "platformRule"),
        )
    }

    private fun parseRegionPolicy(json: JSONObject): V1RegionPolicy {
        expectFields(json, regionRequired, regionOptional, "config.privacy.regionPolicy")
        val mode = enumValue<V1RegionPolicyMode>(requiredString(json, "mode", 1, Int.MAX_VALUE, "regionPolicy"))
            ?: malformed("regionPolicy.mode is unsupported")
        val evaluator = optionalString(json, "evaluator", 1, Int.MAX_VALUE, "regionPolicy")
        if (evaluator != null && evaluator != EU_TIMEZONE_EVALUATOR) malformed("regionPolicy.evaluator is unsupported")
        if (mode == V1RegionPolicyMode.BLOCK_EU_ON_DEVICE && evaluator == null) {
            malformed("block-eu-on-device requires its evaluator")
        }
        return V1RegionPolicy(mode, evaluator)
    }

    private fun parseDecision(json: JSONObject): V1OnDeviceDecision {
        expectFields(json, decisionRequired, decisionOptional, "effective privacy state.onDeviceDecision")
        val decision = enumValue<V1OnDeviceDecisionValue>(requiredString(json, "decision", 1, Int.MAX_VALUE, "onDeviceDecision"))
            ?: malformed("onDeviceDecision.decision is unsupported")
        val source = enumValue<V1OnDeviceDecisionSource>(requiredString(json, "source", 1, Int.MAX_VALUE, "onDeviceDecision"))
            ?: malformed("onDeviceDecision.source is unsupported")
        val evaluatedAt = optionalString(json, "evaluatedAt", 1, Int.MAX_VALUE, "onDeviceDecision")
        evaluatedAt?.let { parseRfc3339(it, "onDeviceDecision.evaluatedAt") }
        return V1OnDeviceDecision(
            decision = decision,
            source = source,
            reason = optionalString(json, "reason", 0, 256, "onDeviceDecision"),
            evaluatedAt = evaluatedAt,
        )
    }

    private fun parseReplayTransport(json: JSONObject): V1ReplayTransportSelection {
        expectFields(json, replayTransportRequired, emptySet(), "effective privacy state.replayTransport")
        val codec = requiredString(json, "codec", 1, 68, "replayTransport")
        if (!REPLAY_CODEC.matches(codec)) malformed("replayTransport.codec is malformed")
        val compression = enumValue<V1ReplayCompression>(requiredString(json, "compression", 1, Int.MAX_VALUE, "replayTransport"))
            ?: malformed("replayTransport.compression is unsupported")
        val advertised = requiredBoolean(json, "advertised", "replayTransport")
        if (!advertised) malformed("replayTransport.advertised must be true")
        return V1ReplayTransportSelection(codec, compression, true)
    }

    private fun parseEffectiveMasking(json: JSONObject): V1EffectiveMasking {
        expectFields(json, effectiveMaskingRequired, emptySet(), "effective privacy state.effectiveMasking")
        val text = enumValue<V1TextMasking>(requiredString(json, "text", 1, Int.MAX_VALUE, "effectiveMasking"))
            ?: malformed("effectiveMasking.text is unsupported")
        val inputs = enumValue<V1TextMasking>(requiredString(json, "inputs", 1, Int.MAX_VALUE, "effectiveMasking"))
            ?: malformed("effectiveMasking.inputs is unsupported")
        val images = enumValue<V1ImageMasking>(requiredString(json, "images", 1, Int.MAX_VALUE, "effectiveMasking"))
            ?: malformed("effectiveMasking.images is unsupported")
        val secure = requiredBoolean(json, "secureInputsMasked", "effectiveMasking")
        if (!secure) malformed("effectiveMasking.secureInputsMasked must be true")
        return V1EffectiveMasking(
            text = text,
            inputs = inputs,
            images = images,
            secureInputsMasked = true,
            platformFallbackApplied = requiredBoolean(json, "platformFallbackApplied", "effectiveMasking"),
        )
    }

    private fun parseRoot(
        body: String?,
        maximumBytes: Int,
        path: String,
    ): JSONObject {
        if (body.isNullOrEmpty()) malformed("$path body is empty")
        if (body.toByteArray(StandardCharsets.UTF_8).size > maximumBytes) malformed("$path body exceeds $maximumBytes bytes")
        StrictJsonSyntax(body, MAX_JSON_NESTING).validate()
        return try {
            val tokener = JSONTokener(body)
            val root = tokener.nextValue() as? JSONObject ?: malformed("$path body must contain one object")
            if (tokener.nextClean().code != 0) malformed("$path body contains trailing data")
            root
        } catch (error: V1MalformedConfigException) {
            throw error
        } catch (error: JSONException) {
            malformed("$path body is not valid JSON", error)
        }
    }

    private fun readSchemaVersion(
        json: JSONObject,
        path: String,
    ): Int {
        val version = requiredLong(json, "schemaVersion", Long.MIN_VALUE, Long.MAX_VALUE, path)
        if (version != V1_CONFIG_SCHEMA_VERSION.toLong()) throw V1UnsupportedConfigSchemaException(version)
        return V1_CONFIG_SCHEMA_VERSION
    }

    private fun expectFields(
        json: JSONObject,
        required: Set<String>,
        optional: Set<String>,
        path: String,
    ) {
        val actual = json.keys().asSequence().toSet()
        val unknown = actual - required - optional
        if (unknown.isNotEmpty()) malformed("$path contains unsupported fields: ${unknown.sorted().joinToString()}")
        val missing = required - actual
        if (missing.isNotEmpty()) malformed("$path is missing fields: ${missing.sorted().joinToString()}")
    }

    private fun requiredObject(
        json: JSONObject,
        key: String,
        path: String,
    ): JSONObject = json.opt(key) as? JSONObject ?: malformed("$path.$key must be an object")

    private fun optionalObject(
        json: JSONObject,
        key: String,
    ): JSONObject? {
        if (!json.has(key)) return null
        return json.opt(key) as? JSONObject ?: malformed("$key must be an object")
    }

    private fun requiredArray(
        json: JSONObject,
        key: String,
        path: String,
    ): JSONArray = json.opt(key) as? JSONArray ?: malformed("$path.$key must be an array")

    private fun requiredBoolean(
        json: JSONObject,
        key: String,
        path: String,
    ): Boolean = json.opt(key) as? Boolean ?: malformed("$path.$key must be a boolean")

    private fun requiredString(
        json: JSONObject,
        key: String,
        minimumLength: Int,
        maximumLength: Int,
        path: String,
    ): String {
        val value = json.opt(key) as? String ?: malformed("$path.$key must be a string")
        requireString(value, minimumLength, maximumLength, "$path.$key")
        return value
    }

    private fun optionalString(
        json: JSONObject,
        key: String,
        minimumLength: Int,
        maximumLength: Int,
        path: String,
    ): String? {
        if (!json.has(key)) return null
        return requiredString(json, key, minimumLength, maximumLength, path)
    }

    private fun requireString(
        value: String,
        minimumLength: Int,
        maximumLength: Int,
        path: String,
    ) {
        var index = 0
        var codePoints = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        malformed("$path contains an unpaired Unicode surrogate")
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> malformed("$path contains an unpaired Unicode surrogate")
                else -> index += 1
            }
            codePoints += 1
        }
        if (codePoints !in minimumLength..maximumLength) {
            malformed("$path length must be in $minimumLength..$maximumLength")
        }
    }

    private fun requiredLong(
        json: JSONObject,
        key: String,
        minimum: Long,
        maximum: Long,
        path: String,
    ): Long {
        val raw = json.opt(key)
        if (raw !is Number) malformed("$path.$key must be an integer")
        val value =
            try {
                BigDecimal(raw.toString()).longValueExact()
            } catch (error: ArithmeticException) {
                malformed("$path.$key must be an integer in the signed 64-bit range", error)
            } catch (error: NumberFormatException) {
                malformed("$path.$key must be a finite integer", error)
            }
        if (value !in minimum..maximum) malformed("$path.$key is outside the allowed range")
        return value
    }

    private fun requiredInt(
        json: JSONObject,
        key: String,
        minimum: Int,
        maximum: Int,
        path: String,
    ): Int = requiredLong(json, key, minimum.toLong(), maximum.toLong(), path).toInt()

    private fun requiredDouble(
        json: JSONObject,
        key: String,
        minimum: Double,
        maximum: Double,
        path: String,
    ): Double {
        val raw = json.opt(key)
        if (raw !is Number) malformed("$path.$key must be a number")
        val value = raw.toDouble()
        if (!value.isFinite()) malformed("$path.$key must be finite")
        if (raw is BigDecimal && BigDecimal.valueOf(value).compareTo(raw) != 0) {
            malformed("$path.$key cannot be represented exactly in Android's numeric domain")
        }
        if (value < minimum || value > maximum) malformed("$path.$key is outside the allowed range")
        return value
    }

    private fun parseUniqueStringArray(
        array: JSONArray,
        maximumSize: Int,
        path: String,
        accepts: (String) -> Boolean,
    ): List<String> {
        if (array.length() > maximumSize) malformed("$path exceeds $maximumSize entries")
        val values = ArrayList<String>(array.length())
        val unique = LinkedHashSet<String>()
        repeat(array.length()) { index ->
            val value = array.opt(index) as? String ?: malformed("$path[$index] must be a string")
            requireString(value, 1, Int.MAX_VALUE, "$path[$index]")
            if (!accepts(value)) malformed("$path[$index] is unsupported")
            if (!unique.add(value)) malformed("$path must contain unique values")
            values += value
        }
        return values
    }

    private fun parseRfc3339(
        value: String,
        path: String,
    ): V1ExactTimestamp {
        val match = RFC_3339.matchEntire(value) ?: malformed("$path must be an RFC 3339 timestamp")
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        val fractionalDigits = match.groupValues[7]
        if (day > daysInMonth(year, month)) malformed("$path contains an invalid calendar date")
        val leapSecond = second == 60
        val zone = match.groupValues[8]
        val offsetSeconds =
            if (zone.equals("Z", ignoreCase = true)) {
                0L
            } else {
                val sign = if (zone[0] == '+') 1L else -1L
                val offsetHours = zone.substring(1, 3).toInt()
                val offsetMinutes = zone.substring(4, 6).toInt()
                if (offsetHours > 23 || offsetMinutes > 59) malformed("$path contains an invalid UTC offset")
                sign * (offsetHours * 60L + offsetMinutes) * 60L
            }
        val localEpochSecond =
            try {
                val daySeconds = Math.multiplyExact(daysFromCivil(year, month, day), SECONDS_PER_DAY)
                Math.addExact(
                    daySeconds,
                    hour * 3_600L + minute * 60L + if (leapSecond) 59L else second.toLong(),
                )
            } catch (error: ArithmeticException) {
                malformed("$path is outside the supported timestamp range", error)
            }
        val utcEpochSecondBeforeLeap =
            try {
                Math.subtractExact(localEpochSecond, offsetSeconds)
            } catch (error: ArithmeticException) {
                malformed("$path is outside the supported timestamp range", error)
            }
        if (leapSecond) {
            val followingBoundary =
                try {
                    Math.addExact(utcEpochSecondBeforeLeap, 1L)
                } catch (error: ArithmeticException) {
                    malformed("$path is outside the supported timestamp range", error)
                }
            if (!isPermittedLeapSecondBoundary(followingBoundary, year)) {
                malformed("$path leap second does not precede a UTC January or July boundary")
            }
        }
        return try {
            V1ExactTimestamp.fromEpochSecondAndFraction(
                epochWholeSecond = utcEpochSecondBeforeLeap,
                fractionalDigits = fractionalDigits,
                isLeapSecond = leapSecond,
            )
        } catch (error: ArithmeticException) {
            malformed("$path is outside the supported timestamp range", error)
        }
    }

    /** Proleptic-Gregorian day number relative to 1970-01-01; year 0000 is intentional. */
    private fun daysFromCivil(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        val adjustedYear = year - if (month <= 2) 1 else 0
        val era = Math.floorDiv(adjustedYear, 400)
        val yearOfEra = adjustedYear - era * 400
        val shiftedMonth = month + if (month > 2) -3 else 9
        val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    private fun daysInMonth(
        year: Int,
        month: Int,
    ): Int =
        when (month) {
            2 -> if (isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

    private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private fun isPermittedLeapSecondBoundary(
        utcBoundaryEpochSecond: Long,
        sourceYear: Int,
    ): Boolean {
        if (Math.floorMod(utcBoundaryEpochSecond, SECONDS_PER_DAY) != 0L) return false
        val utcDay = Math.floorDiv(utcBoundaryEpochSecond, SECONDS_PER_DAY)
        return (sourceYear - 1..sourceYear + 1).any { candidateYear ->
            utcDay == daysFromCivil(candidateYear, 1, 1) || utcDay == daysFromCivil(candidateYear, 7, 1)
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(wireValue: String): T? =
        enumValues<T>().firstOrNull {
            when (it) {
                is V1ConfigStatus -> it.wireValue == wireValue
                is V1ReplayCompression -> it.wireValue == wireValue
                is V1TextMasking -> it.wireValue == wireValue
                is V1ImageMasking -> it.wireValue == wireValue
                is V1PrivacyPlatform -> it.wireValue == wireValue
                is V1PlatformRuleAction -> it.wireValue == wireValue
                is V1RegionPolicyMode -> it.wireValue == wireValue
                is V1OnDeviceDecisionValue -> it.wireValue == wireValue
                is V1OnDeviceDecisionSource -> it.wireValue == wireValue
                else -> false
            }
        }

    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private fun malformed(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw V1MalformedConfigException(message, cause)

    private const val EU_TIMEZONE_EVALUATOR = "elu-eu-timezone-v1"
    private const val SECONDS_PER_DAY = 86_400L
    private val REPLAY_CODEC = Regex("^elu-[a-z0-9][a-z0-9.-]{0,63}$")
    private val POLICY_HASH = Regex("^sha256:[a-f0-9]{64}$")
    private val RFC_3339 =
        Regex(
            "^(\\d{4})-(0[1-9]|1[0-2])-([0-2]\\d|3[01])[Tt]" +
                "([01]\\d|2[0-3]):([0-5]\\d):([0-5]\\d|60)(?:\\.(\\d+))?" +
                "([Zz]|[+-]\\d{2}:\\d{2})$",
        )
}

/** Android's platform JSONTokener is deliberately lenient; enforce RFC 8259 before using it. */
private class StrictJsonSyntax(
    private val source: String,
    private val maximumNesting: Int,
) {
    private var index: Int = 0

    fun validate() {
        skipWhitespace()
        parseValue(0)
        skipWhitespace()
        if (index != source.length) fail("JSON contains trailing data")
    }

    private fun parseValue(containerDepth: Int) {
        if (index >= source.length) fail("JSON value is missing")
        when (source[index]) {
            '{' -> parseObject(containerDepth + 1)
            '[' -> parseArray(containerDepth + 1)
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> fail("JSON contains a non-standard value")
        }
    }

    private fun parseObject(depth: Int) {
        requireDepth(depth)
        index += 1
        skipWhitespace()
        if (consume('}')) return
        val names = HashSet<String>()
        while (true) {
            if (index >= source.length || source[index] != '"') fail("JSON object name must use double quotes")
            val name = parseString()
            if (!names.add(name)) fail("JSON object contains duplicate member $name")
            skipWhitespace()
            requireCharacter(':')
            skipWhitespace()
            parseValue(depth)
            skipWhitespace()
            when {
                consume('}') -> return
                consume(',') -> {
                    skipWhitespace()
                    if (index < source.length && source[index] == '}') fail("JSON object contains a trailing comma")
                }
                else -> fail("JSON object members must be comma-separated")
            }
        }
    }

    private fun parseArray(depth: Int) {
        requireDepth(depth)
        index += 1
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue(depth)
            skipWhitespace()
            when {
                consume(']') -> return
                consume(',') -> {
                    skipWhitespace()
                    if (index < source.length && source[index] == ']') fail("JSON array contains a trailing comma")
                }
                else -> fail("JSON array values must be comma-separated")
            }
        }
    }

    private fun parseString(): String {
        requireCharacter('"')
        val decoded = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> {
                    validateUnicodeScalarSequence(decoded)
                    return decoded.toString()
                }
                character == '\\' -> decoded.append(parseEscape())
                character.code < 0x20 -> fail("JSON string contains an unescaped control character")
                else -> decoded.append(character)
            }
        }
        fail("JSON string is unterminated")
    }

    private fun parseEscape(): Char {
        if (index >= source.length) fail("JSON string escape is unterminated")
        return when (val escape = source[index++]) {
            '"', '\\', '/' -> escape
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > source.length) fail("JSON Unicode escape is truncated")
                val digits = source.substring(index, index + 4)
                if (digits.any { it.digitToIntOrNull(16) == null }) fail("JSON Unicode escape is malformed")
                index += 4
                digits.toInt(16).toChar()
            }
            else -> fail("JSON string contains unsupported escape \\$escape")
        }
    }

    private fun parseLiteral(literal: String) {
        if (!source.regionMatches(index, literal, 0, literal.length)) fail("JSON literal is malformed")
        index += literal.length
    }

    private fun parseNumber() {
        val start = index
        consume('-')
        if (index >= source.length) fail("JSON number is truncated")
        if (consume('0')) {
            if (index < source.length && source[index] in '0'..'9') fail("JSON number contains a leading zero")
        } else {
            if (source[index] !in '1'..'9') fail("JSON number integer part is malformed")
            while (index < source.length && source[index] in '0'..'9') index += 1
        }
        var usesFloatingPoint = false
        if (consume('.')) {
            usesFloatingPoint = true
            val fractionStart = index
            while (index < source.length && source[index] in '0'..'9') index += 1
            if (index == fractionStart) fail("JSON number fraction is missing digits")
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            usesFloatingPoint = true
            index += 1
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index += 1
            val exponentStart = index
            while (index < source.length && source[index] in '0'..'9') index += 1
            if (index == exponentStart) fail("JSON number exponent is missing digits")
        }
        if (usesFloatingPoint) validateStableFloatingPoint(source.substring(start, index))
    }

    private fun validateStableFloatingPoint(token: String) {
        val decimal =
            try {
                BigDecimal(token)
            } catch (error: NumberFormatException) {
                fail("JSON number is malformed", error)
            }
        val asDouble = token.toDoubleOrNull()
        if (asDouble == null || !asDouble.isFinite() || BigDecimal.valueOf(asDouble).compareTo(decimal) != 0) {
            fail("JSON number cannot be represented stably in Android's numeric domain")
        }
    }

    private fun validateUnicodeScalarSequence(value: CharSequence) {
        var characterIndex = 0
        while (characterIndex < value.length) {
            val character = value[characterIndex]
            when {
                Character.isHighSurrogate(character) -> {
                    if (characterIndex + 1 >= value.length || !Character.isLowSurrogate(value[characterIndex + 1])) {
                        fail("JSON string contains an unpaired Unicode surrogate")
                    }
                    characterIndex += 2
                }
                Character.isLowSurrogate(character) -> fail("JSON string contains an unpaired Unicode surrogate")
                else -> characterIndex += 1
            }
        }
    }

    private fun requireDepth(depth: Int) {
        if (depth > maximumNesting) fail("JSON exceeds the maximum nesting depth")
    }

    private fun requireCharacter(expected: Char) {
        if (!consume(expected)) fail("JSON is missing '$expected'")
    }

    private fun consume(expected: Char): Boolean {
        if (index >= source.length || source[index] != expected) return false
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) index += 1
    }

    private fun fail(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw V1MalformedConfigException(message, cause)

    private companion object {
        val JSON_WHITESPACE = setOf(' ', '\t', '\n', '\r')
    }
}
