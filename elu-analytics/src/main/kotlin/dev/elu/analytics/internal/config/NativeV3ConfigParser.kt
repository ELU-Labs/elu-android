package dev.elu.analytics.internal.config

import dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value
import dev.elu.analytics.internal.replay.NativeReplayProtocol
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Closed semantic projection only: parsing cannot install, renew or authorize a source. */
internal object NativeV3ConfigParser {
    const val MAXIMUM_BYTES = 65_536
    const val PROFILE_HASH = "sha256:e374338e6100edcad1d11de079f6bdfc24df043107628b83a559bb3e87206422"
    private const val POLICY_DOMAIN = "elu-native-raster-effective-policy-v1\u0000"

    enum class Rejection { TOO_LARGE, MALFORMED, INCOMPATIBLE_BASE, MISMATCHED_RASTER }
    class ParseException(val reason: Rejection, cause: Exception? = null) :
        IllegalArgumentException("Native v3 configuration rejected: $reason", cause)

    /** Descriptive policy; it carries no capture permit, installed proof or currentness witness. */
    data class RasterPolicy(
        val endpoint: URI,
        val revision: String,
        val effectivePolicyHash: String,
        val maximumRequestBytes: Int,
    )

    class Parsed internal constructor(
        data: ByteArray,
        configV2Data: ByteArray,
        val base: V1ParsedConfig,
        canonicalData: ByteArray,
        baseCanonicalData: ByteArray,
        val raster: RasterPolicy?,
    ) {
        private val original = data.copyOf()
        private val originalBase = configV2Data.copyOf()
        private val canonical = canonicalData.copyOf()
        private val canonicalBase = baseCanonicalData.copyOf()
        val data: ByteArray get() = original.copyOf()
        val configV2Data: ByteArray get() = originalBase.copyOf()
        val canonicalData: ByteArray get() = canonical.copyOf()
        val baseCanonicalData: ByteArray get() = canonicalBase.copyOf()
        val semanticHash: String = V1StrictCanonicalJson.sha256(canonical)
        val baseSemanticHash: String = base.configSemanticHash
        val basePrivacyHash: String? = base.policySourceHash
    }

    fun parse(data: ByteArray, endpointPolicy: LocalEndpointPolicy = LocalEndpointPolicy.CLOUD): Parsed {
        if (data.size > MAXIMUM_BYTES) reject(Rejection.TOO_LARGE)
        val original = data.copyOf()
        try {
            val source = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(original)).toString()
            val extracted = V1StrictCanonicalJson.parseRetainingRootProperty(source, "configV2")
            val root = extracted.value as? Value.ObjectValue ?: reject(Rejection.MALFORMED)
            if (root.members.any { it.first !in setOf("schemaVersion", "configV2", "raster") } ||
                root.member("schemaVersion")?.let(V1StrictCanonicalJson::canonicalize) != "3"
            ) reject(Rejection.MALFORMED)
            val baseSource = extracted.propertySource ?: reject(Rejection.MALFORMED)
            val base = V1ConfigJson.parseConfig(baseSource)
            if (base.schemaVersion != V2_CONFIG_SCHEMA_VERSION) reject(Rejection.INCOMPATIBLE_BASE)
            // Apply the same application-declared endpoint policy to ALL original base roles,
            // including base-only/browser documents. This does not install the base document.
            val endpoints = base.endpoints
            val events = endpoints?.let {
                fun trusted(raw: String, role: V1EndpointRole): URI = URI(raw).also { uri ->
                    endpointPolicy.requireApproved(uri, role, base.schemaVersion)
                }
                val eventEndpoint = trusted(it.events, V1EndpointRole.EVENTS)
                trusted(it.flags, V1EndpointRole.FLAGS)
                it.replay?.let { replay -> trusted(replay, V1EndpointRole.REPLAY) }
                it.assets?.let { assets -> trusted(assets, V1EndpointRole.ASSETS) }
                eventEndpoint
            }
            val baseValue = root.member("configV2") as? Value.ObjectValue ?: reject(Rejection.MALFORMED)
            val raster = root.member("raster")?.let { validateRaster(it, base, baseValue, events) }
            return Parsed(original, baseSource.toByteArray(Charsets.UTF_8), base,
                V1StrictCanonicalJson.canonicalBytes(root), V1StrictCanonicalJson.canonicalBytes(baseValue), raster)
        } catch (failure: ParseException) {
            throw failure
        } catch (failure: Exception) {
            throw ParseException(Rejection.MALFORMED, failure)
        }
    }

    private fun validateRaster(
        candidate: Value,
        base: V1ParsedConfig,
        baseValue: Value.ObjectValue,
        events: URI?,
    ): RasterPolicy {
        val privacy = base.privacy ?: reject(Rejection.INCOMPATIBLE_BASE)
        val replay = base.replayCapabilities ?: reject(Rejection.INCOMPATIBLE_BASE)
        val limits = base.limits ?: reject(Rejection.INCOMPATIBLE_BASE)
        if (base.status != V1ConfigStatus.ENABLED || base.features?.capture != true || base.features?.replay != true ||
            !privacy.capture.enabled || !privacy.replay.enabled || privacy.region.mode == V1RegionPolicyMode.BLOCK ||
            privacy.masking.text != V1TextMasking.SENSITIVE || privacy.masking.images != V1ImageMasking.ALLOW ||
            privacy.masking.platformRules.any { it.platform != V1PrivacyPlatform.BROWSER } ||
            replay.advertisedTransports.size != 1 ||
            NativeReplayProtocol.match(replay.advertisedTransports.singleOrNull(), replay.replayProtocolGeneration) == null
        ) reject(Rejection.INCOMPATIBLE_BASE)
        val originalEvents = events ?: reject(Rejection.INCOMPATIBLE_BASE)
        val origin = "${originalEvents.scheme}://${originalEvents.rawAuthority}"
        // Frozen issuer origins only. A broader application local allowlist does not extend this leaf.
        if (origin !in setOf("https://ingest.elu.dev", "https://35-224-68-29.sslip.io")) {
            reject(Rejection.INCOMPATIBLE_BASE)
        }
        val endpoint = URI("$origin/v3/replay")
        val basePrivacy = baseValue.member("privacy") ?: reject(Rejection.INCOMPATIBLE_BASE)
        val material = obj(
            "schemaVersion" to number(1), "policyRevision" to string(privacy.revision),
            "basePolicyRevision" to string(privacy.revision), "basePrivacy" to basePrivacy,
            "replayAudience" to string(if (base.replayAudience == V1ReplayAudience.NEW_DEVICES) "new-devices" else "all-devices"),
            "declaredRegionsAllowed" to Value.BooleanValue(true), "inputCoverage" to string("declared-regions"),
            "automaticInputDiscovery" to Value.BooleanValue(false), "unknownContentClassification" to Value.BooleanValue(false),
            "redactionBoundary" to string("before-encoding"), "requiredBindingBehavior" to string("deny-incomplete-or-stale"),
            "maskingProfileHash" to string(PROFILE_HASH), "limits" to rasterLimits(),
        )
        val hash = V1StrictCanonicalJson.sha256(POLICY_DOMAIN.toByteArray(Charsets.UTF_8) +
            V1StrictCanonicalJson.canonicalBytes(material))
        val expected = obj(
            "schemaVersion" to number(1), "endpoint" to string(endpoint.toString()),
            "replayContractVersion" to string("3.0.0"), "replaySchemaVersion" to number(3), "ackSchemaVersion" to number(3),
            "replayProtocolGeneration" to string("native-raster-generation-v1"),
            "codec" to string("elu-native-raster-v1"), "compression" to string("gzip"),
            "platforms" to Value.ArrayValue(listOf(string("android"), string("ios"))),
            "privacy" to obj(
                "schemaVersion" to number(1), "revision" to string(privacy.revision), "effectivePolicyHash" to string(hash),
                "maskingProfileHash" to string(PROFILE_HASH), "declaredRegionsAllowed" to Value.BooleanValue(true),
                "inputCoverage" to string("declared-regions"), "automaticInputDiscovery" to Value.BooleanValue(false),
                "unknownContentClassification" to Value.BooleanValue(false), "redactionBoundary" to string("before-encoding"),
                "requiredBindingBehavior" to string("deny-incomplete-or-stale"),
            ),
            "limits" to rasterLimits(),
        )
        // Complete canonical shape equality rejects missing, extra, null and crossed members recursively.
        if (V1StrictCanonicalJson.canonicalize(candidate) != V1StrictCanonicalJson.canonicalize(expected)) {
            reject(Rejection.MISMATCHED_RASTER)
        }
        return RasterPolicy(endpoint, privacy.revision, hash, minOf(limits.replayChunkBytes, 5_242_880))
    }

    private fun rasterLimits(): Value = obj(
        "requestBytes" to number(5_242_880), "decodedPayloadBytes" to number(2_800_000),
        "pngBytes" to number(2_097_152), "imageEdgePixels" to number(2_048), "imagePixels" to number(1_048_576),
        "viewportEdge" to number(16_384), "minimumFrameIntervalSeconds" to number(1), "framesPerChunk" to number(1),
    )
    private fun obj(vararg members: Pair<String, Value>): Value.ObjectValue = Value.ObjectValue(members.toList())
    private fun string(value: String): Value = Value.StringValue(value)
    private fun number(value: Int): Value = Value.NumberValue(value.toString())
    private fun reject(reason: Rejection): Nothing = throw ParseException(reason)
}
