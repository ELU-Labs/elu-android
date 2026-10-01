package dev.elu.analytics.internal.config

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeV3ConfigParserTest {
    @Test
    fun `public issuer golden preserves complete original base policy`() {
        val parsed = parse(envelope())
        val raster = requireNotNull(parsed.raster)
        assertEquals(GOLDEN, raster.effectivePolicyHash)
        assertEquals("privacy-golden-1", raster.revision)
        assertEquals(URI("https://ingest.elu.dev/v3/replay"), raster.endpoint)
        assertEquals(5_242_880, raster.maximumRequestBytes)
        assertEquals(0.25, requireNotNull(parsed.base.privacy).replay.sampleRate, 0.0)
        assertEquals(5, parsed.base.privacy?.replay?.minimumDurationSeconds)
        assertEquals(V1RegionPolicyMode.BLOCK_EU_ON_DEVICE, parsed.base.privacy?.region?.mode)
        assertEquals(1_800, parsed.base.session?.idleTimeoutSeconds)
        assertEquals(30_000, parsed.base.capturePerformance?.sampleIntervalMillis)
        assertEquals("2026-09-04T00:05:00.000Z", parsed.base.issuedAt)
    }

    @Test
    fun `original wrapper and escaped embedded bytes remain exact independent owned copies`() {
        val originalBase = BASE.replace("0.25", "2.5e-1").replace("site_golden", "site_\\u0067olden")
        val wire = "  { \"raster\": ${envelope().getJSONObject("raster")}, \"config\\u00562\": $originalBase, \"schemaVersion\": 3.0 }\n"
        val bytes = wire.toByteArray()
        val parsed = NativeV3ConfigParser.parse(bytes)
        assertArrayEquals(bytes, parsed.data)
        assertArrayEquals(originalBase.toByteArray(), parsed.configV2Data)
        assertFalse(parsed.configV2Data.contentEquals(parsed.baseCanonicalData))
        assertEquals(parse(envelope()).semanticHash, parsed.semanticHash)
        val legacy = V1ConfigJson.parseConfig(originalBase)
        assertEquals(legacy.configSemanticHash, parsed.baseSemanticHash)
        assertEquals(legacy.policySourceHash, parsed.basePrivacyHash)
        assertArrayEquals(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(originalBase)), parsed.baseCanonicalData)
        bytes.fill(0); parsed.data.fill(0); parsed.configV2Data.fill(0); parsed.canonicalData.fill(0)
        assertArrayEquals(wire.toByteArray(), parsed.data)
        assertArrayEquals(originalBase.toByteArray(), parsed.configV2Data)
    }

    @Test
    fun `legacy parser refuses v3 and extracted v2 retains original validation`() {
        val input = envelope().toString()
        assertThrows(IllegalArgumentException::class.java) { V1ConfigJson.parseConfig(input) }
        val parsed = NativeV3ConfigParser.parse(input.toByteArray())
        val legacy = V1ConfigJson.parseConfig(String(parsed.configV2Data, Charsets.UTF_8))
        // V1ExactTimestamp has reference equality. Compare its complete exact value first,
        // then normalize only those references to compare every other parsed base field.
        for ((original, reparsed) in listOf(parsed.base.issuedAtInstant to legacy.issuedAtInstant,
                parsed.base.expiresAtInstant to legacy.expiresAtInstant)) {
            assertEquals(original.epochWholeSecond, reparsed.epochWholeSecond)
            assertEquals(original.fractionalDigits, reparsed.fractionalDigits)
            assertEquals(original.isLeapSecond, reparsed.isLeapSecond)
            assertEquals(0, original.compareTo(reparsed))
        }
        assertEquals(parsed.base.copy(issuedAtInstant = legacy.issuedAtInstant,
            expiresAtInstant = legacy.expiresAtInstant), legacy)
        assertArrayEquals(parsed.baseCanonicalData,
            V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(legacy.serialized)))
        val versionOne = envelope().apply { getJSONObject("configV2").put("schemaVersion", 1) }
        rejected(versionOne)
    }

    @Test
    fun `base only browser and terminal wrappers have no raster policy`() {
        val browser = envelope().apply {
            remove("raster")
            getJSONObject("configV2").getJSONObject("capabilities").getJSONObject("replay")
                .put("replayProtocolGeneration", "replay-v2-generation-1")
                .put("transports", JSONArray().put(JSONObject().put("codec", "elu-browser-dom-v1").put("compression", "gzip")))
        }
        assertNull(parse(browser).raster)
        browser.put("raster", envelope().getJSONObject("raster"))
        rejected(browser, NativeV3ConfigParser.Rejection.INCOMPATIBLE_BASE)
        for (status in listOf("disabled", "revoked")) {
            val terminal = JSONObject().put("schemaVersion", 3).put("configV2", JSONObject()
                .put("schemaVersion", 2).put("revision", "closed").put("issuedAt", "2026-09-04T00:05:00Z")
                .put("expiresAt", "2026-09-04T00:15:00Z").put("status", status).put("reason", "closed"))
            assertNull(parse(terminal).raster)
            terminal.put("raster", envelope().getJSONObject("raster"))
            rejected(terminal, NativeV3ConfigParser.Rejection.INCOMPATIBLE_BASE)
        }
    }

    @Test
    fun `exact v1 and v2 pairs work but crossed mixed and unknown pairs refuse`() {
        for (version in listOf(1, 2)) {
            val value = envelope()
            val replay = value.getJSONObject("configV2").getJSONObject("capabilities").getJSONObject("replay")
            replay.put("replayProtocolGeneration", "protocol-generation-v$version")
            replay.getJSONArray("transports").getJSONObject(0).put("codec", "elu-native-wireframe-v$version")
            assertEquals(GOLDEN, parse(value).raster?.effectivePolicyHash)
            replay.put("replayProtocolGeneration", "protocol-generation-v${3-version}")
            rejected(value, NativeV3ConfigParser.Rejection.INCOMPATIBLE_BASE)
        }
        for ((codec, compression, generation) in listOf(
            Triple("elu-unknown-v1", "gzip", "protocol-generation-v2"),
            Triple("elu-native-wireframe-v2", "none", "protocol-generation-v2"),
            Triple("elu-native-wireframe-v2", "gzip", "unknown"),
        )) {
            val value = envelope(); val replay = value.getJSONObject("configV2").getJSONObject("capabilities").getJSONObject("replay")
            replay.put("replayProtocolGeneration", generation)
            replay.put("transports", JSONArray().put(JSONObject().put("codec", codec).put("compression", compression)))
            rejected(value, NativeV3ConfigParser.Rejection.INCOMPATIBLE_BASE)
        }
        for ((codec, compression) in listOf("unknown" to "gzip", "elu-native-wireframe-v2" to "unknown")) {
            val malformed = envelope()
            malformed.getJSONObject("configV2").getJSONObject("capabilities").getJSONObject("replay")
                .put("transports", JSONArray().put(JSONObject().put("codec", codec).put("compression", compression)))
            rejected(malformed, NativeV3ConfigParser.Rejection.MALFORMED)
        }
        val mixed = envelope()
        mixed.getJSONObject("configV2").getJSONObject("capabilities").getJSONObject("replay")
            .getJSONArray("transports").put(JSONObject().put("codec", "elu-native-wireframe-v1").put("compression", "gzip"))
        rejected(mixed, NativeV3ConfigParser.Rejection.INCOMPATIBLE_BASE)
    }

    @Test
    fun `incompatible stricter base cannot be authorized by rehashing`() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("features").put("capture", false) },
            { it.getJSONObject("features").put("replay", false) },
            { it.getJSONObject("privacy").getJSONObject("capture").put("enabled", false) },
            { it.getJSONObject("privacy").getJSONObject("replay").put("enabled", false) },
            { it.getJSONObject("privacy").getJSONObject("regionPolicy").put("mode", "block").remove("evaluator") },
            { it.getJSONObject("privacy").getJSONObject("masking").put("text", "all") },
            { it.getJSONObject("privacy").getJSONObject("masking").put("images", "block") },
            { it.getJSONObject("privacy").getJSONObject("masking").getJSONArray("platformRules").getJSONObject(0)
                .put("platform", "android").put("targetDialect", "android-view-id-v1") },
            { it.getJSONObject("privacy").getJSONObject("masking").getJSONArray("platformRules").getJSONObject(0)
                .put("platform", "ios").put("targetDialect", "ios-accessibility-id-v1") },
        )
        for (change in changes) {
            val value = envelope(); change(value.getJSONObject("configV2")); refreshHash(value); rejected(value)
        }
    }

    @Test
    fun `whole original policy and audience mutations require independent new golden`() {
        val value = envelope(); value.getJSONObject("configV2").put("replayAudience", "new-devices")
        rejected(value, NativeV3ConfigParser.Rejection.MISMATCHED_RASTER)
        refreshHash(value)
        assertEquals(V1ReplayAudience.NEW_DEVICES, parse(value).base.replayAudience)
        assertNotEquals(GOLDEN, parse(value).raster?.effectivePolicyHash)
        value.getJSONObject("configV2").getJSONObject("privacy").getJSONObject("replay").put("sampleRate", 0.1)
        rejected(value, NativeV3ConfigParser.Rejection.MISMATCHED_RASTER)
        refreshHash(value)
        assertEquals("sha256:bc2da02d73f818a5c392e345d416bf343de4f252dd087c56fc531aaa543daaee", parse(value).raster?.effectivePolicyHash)
    }

    @Test
    fun `effective request bound never widens original session limit`() {
        for (limit in listOf(1_024, 5_242_880, 10_485_760)) {
            val value = envelope(); value.getJSONObject("configV2").getJSONObject("limits").put("replayChunkBytes", limit)
            assertEquals(minOf(limit, 5_242_880), parse(value).raster?.maximumRequestBytes)
            assertEquals(limit, parse(value).base.limits?.replayChunkBytes)
        }
    }

    @Test
    fun `each raster member and nested member is required closed and exact`() {
        val paths = listOf(emptyList(), listOf("privacy"), listOf("limits"))
        for (path in paths) {
            val target = path.fold(envelope().getJSONObject("raster")) { obj, key -> obj.getJSONObject(key) }
            for (key in target.keys().asSequence().toList()) {
                val missing = envelope(); path.fold(missing.getJSONObject("raster")) { obj, name -> obj.getJSONObject(name) }.remove(key)
                rejected(missing)
                val nullValue = envelope(); path.fold(nullValue.getJSONObject("raster")) { obj, name -> obj.getJSONObject(name) }.put(key, JSONObject.NULL)
                rejected(nullValue)
            }
            val unknown = envelope(); path.fold(unknown.getJSONObject("raster")) { obj, name -> obj.getJSONObject(name) }.put("unknown", false)
            rejected(unknown)
        }
        for (platforms in listOf(JSONArray("[\"ios\",\"android\"]"), JSONArray("[\"android\"]"), JSONArray("[\"android\",\"ios\",\"browser\"]"))) {
            rejected(envelope().apply { getJSONObject("raster").put("platforms", platforms) })
        }
        for ((field, value) in listOf("codec" to "elu-native-wireframe-v2", "compression" to "none",
            "replayProtocolGeneration" to "protocol-generation-v2", "replayContractVersion" to "2.0.0")) {
            rejected(envelope().apply { getJSONObject("raster").put(field, value) })
        }
        rejected(envelope().apply { getJSONObject("raster").getJSONObject("privacy").put("automaticInputDiscovery", true) })
        rejected(envelope().apply { getJSONObject("raster").getJSONObject("limits").put("framesPerChunk", 2) })
    }

    @Test
    fun `all original endpoints and child endpoint retain local origin boundaries`() {
        for (role in listOf("events", "flags", "replay", "assets")) {
            val value = envelope(); value.getJSONObject("configV2").getJSONObject("endpoints").put(role, "https://untrusted.invalid/v1/events")
            rejected(value)
        }
        for (bad in listOf("http://ingest.elu.dev/v3/replay", "https://ingest.elu.dev:443/v3/replay",
            "https://ingest.elu.dev/v3/replay?site_key=secret", "https://ingest.elu.dev/v3/replay#fragment",
            "https://35-224-68-29.sslip.io/v3/replay")) {
            rejected(envelope().apply { getJSONObject("raster").put("endpoint", bad) })
        }
        val local = envelope(); val endpoints = local.getJSONObject("configV2").getJSONObject("endpoints")
        for (role in endpoints.keys().asSequence().toList()) {
            val old = URI(endpoints.getString(role)); endpoints.put(role, "https://35-224-68-29.sslip.io${old.rawPath}")
        }
        local.getJSONObject("raster").put("endpoint", "https://35-224-68-29.sslip.io/v3/replay")
        rejected(local)
        val policy = LocalEndpointPolicy.fromApiHost("https://35-224-68-29.sslip.io")
        assertEquals(URI("https://35-224-68-29.sslip.io/v3/replay"), NativeV3ConfigParser.parse(local.toString().toByteArray(), policy).raster?.endpoint)
        for (role in listOf("events", "flags", "replay", "assets")) {
            val bad = envelope(); val es = bad.getJSONObject("configV2").getJSONObject("endpoints")
            es.put(role, es.getString(role) + "?%73ite_key=secret"); rejected(bad)
        }
    }

    @Test
    fun `outer unknown duplicate null and schema mutations never degrade to base only`() {
        rejected(envelope().put("unknown", false))
        rejected(envelope().put("raster", JSONObject.NULL))
        rejected(envelope().put("configV2", JSONObject.NULL))
        for (version in listOf<Any>(2, 4, "3", true, 3.5)) rejected(envelope().put("schemaVersion", version))
        for (name in listOf("schemaVersion", "configV2")) rejected(envelope().apply { remove(name) })
        val valid = envelope().toString()
        for (suffix in listOf(",\"schemaVersion\":3", ",\"config\\u00562\":{}", ",\"raster\":null")) {
            rejectedBytes((valid.dropLast(1) + suffix + "}").toByteArray())
        }
        val duplicatedNested = valid.replace("\"sampleRate\":0.25", "\"sampleRate\":0.25,\"sample\\u0052ate\":0.1")
        assertNotEquals(valid, duplicatedNested); rejectedBytes(duplicatedNested.toByteArray())
    }

    @Test
    fun `invalid utf8 BOM surrogate nesting trailing data and complete byte ceiling reject`() {
        val bytes = envelope().toString().toByteArray()
        rejectedBytes(byteArrayOf(0xc0.toByte(), 0xaf.toByte()) + bytes)
        rejectedBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes)
        rejectedBytes((envelope().toString().replace("site_golden", "\\ud800")).toByteArray())
        rejectedBytes(("[".repeat(65) + "0" + "]".repeat(65)).toByteArray())
        rejectedBytes(bytes + "false".toByteArray())
        val exact = bytes + ByteArray(NativeV3ConfigParser.MAXIMUM_BYTES - bytes.size) { 0x20 }
        assertEquals(GOLDEN, NativeV3ConfigParser.parse(exact).raster?.effectivePolicyHash)
        assertEquals(NativeV3ConfigParser.Rejection.TOO_LARGE, rejectedBytes(exact + byteArrayOf(0x20)).reason)
    }

    @Test
    fun `semantic parser does not replace original expiry with present authority`() {
        val parsed = parse(envelope())
        // Deliberately historical. Freshness is an original-source responsibility, never minted here.
        assertEquals("2026-09-04T00:15:00.000Z", parsed.base.expiresAt)
        assertTrue(parsed.raster != null)
        val invalid = envelope(); invalid.getJSONObject("configV2").put("expiresAt", "2026-09-04T00:04:00Z")
        rejected(invalid)
    }

    private fun parse(value: JSONObject) = NativeV3ConfigParser.parse(value.toString().toByteArray())
    private fun rejected(value: JSONObject, expected: NativeV3ConfigParser.Rejection? = null) {
        val error = rejectedBytes(value.toString().toByteArray())
        if (expected != null) assertEquals(expected, error.reason)
    }
    private fun rejectedBytes(value: ByteArray): NativeV3ConfigParser.ParseException =
        assertThrows(NativeV3ConfigParser.ParseException::class.java) { NativeV3ConfigParser.parse(value) }
    private fun envelope(): JSONObject = JSONObject().put("schemaVersion", 3).put("configV2", JSONObject(BASE))
        .put("raster", JSONObject().put("schemaVersion", 1).put("endpoint", "https://ingest.elu.dev/v3/replay")
            .put("replayContractVersion", "3.0.0").put("replaySchemaVersion", 3).put("ackSchemaVersion", 3)
            .put("replayProtocolGeneration", "native-raster-generation-v1").put("codec", "elu-native-raster-v1")
            .put("compression", "gzip").put("platforms", JSONArray().put("android").put("ios"))
            .put("privacy", JSONObject().put("schemaVersion", 1).put("revision", "privacy-golden-1")
                .put("effectivePolicyHash", GOLDEN).put("maskingProfileHash", NativeV3ConfigParser.PROFILE_HASH)
                .put("declaredRegionsAllowed", true).put("inputCoverage", "declared-regions")
                .put("automaticInputDiscovery", false).put("unknownContentClassification", false)
                .put("redactionBoundary", "before-encoding").put("requiredBindingBehavior", "deny-incomplete-or-stale"))
            .put("limits", limits()))
    private fun limits() = JSONObject().put("requestBytes", 5_242_880).put("decodedPayloadBytes", 2_800_000)
        .put("pngBytes", 2_097_152).put("imageEdgePixels", 2_048).put("imagePixels", 1_048_576)
        .put("viewportEdge", 16_384).put("minimumFrameIntervalSeconds", 1).put("framesPerChunk", 1)
    private fun refreshHash(value: JSONObject) {
        val base = value.getJSONObject("configV2"); val privacy = base.getJSONObject("privacy")
        val material = JSONObject().put("schemaVersion", 1).put("policyRevision", privacy.getString("revision"))
            .put("basePolicyRevision", privacy.getString("revision")).put("basePrivacy", privacy)
            .put("replayAudience", base.optString("replayAudience", "all-devices"))
            .put("declaredRegionsAllowed", true).put("inputCoverage", "declared-regions")
            .put("automaticInputDiscovery", false).put("unknownContentClassification", false)
            .put("redactionBoundary", "before-encoding").put("requiredBindingBehavior", "deny-incomplete-or-stale")
            .put("maskingProfileHash", NativeV3ConfigParser.PROFILE_HASH).put("limits", limits())
        val bytes = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(material.toString()))
        value.getJSONObject("raster").getJSONObject("privacy").put("effectivePolicyHash",
            V1StrictCanonicalJson.sha256("elu-native-raster-effective-policy-v1\u0000".toByteArray() + bytes))
    }
    companion object {
        private const val GOLDEN = "sha256:b6913d8dc296baf84dce6c84e9ce598000c1698cca3407475588f46573d846c7"
        private val BASE = """
{
  "schemaVersion": 2,
  "revision": "config-v2-enabled-golden-1",
  "issuedAt": "2026-09-04T00:05:00.000Z",
  "expiresAt": "2026-09-04T00:15:00.000Z",
  "status": "enabled",
  "site": {
    "id": "site_golden"
  },
  "endpoints": {
    "events": "https://ingest.elu.dev/v1/events",
    "replay": "https://ingest.elu.dev/v2/replay",
    "flags": "https://ingest.elu.dev/v1/flags",
    "assets": "https://assets.elu.dev/sdk/"
  },
  "privacy": {
    "schemaVersion": 1,
    "revision": "privacy-golden-1",
    "capture": {
      "enabled": true
    },
    "replay": {
      "enabled": true,
      "sampleRate": 0.25,
      "minimumDurationSeconds": 5,
      "maximumDurationSeconds": 3600
    },
    "masking": {
      "text": "sensitive",
      "inputs": "all",
      "images": "allow",
      "secureInputsMasked": true,
      "platformRules": [
        {
          "platform": "browser",
          "action": "mask",
          "targetDialect": "elu-css-selector-v1",
          "target": ".private"
        }
      ]
    },
    "regionPolicy": {
      "mode": "block-eu-on-device",
      "evaluator": "elu-eu-timezone-v1"
    }
  },
  "features": {
    "capture": true,
    "replay": true,
    "flags": true,
    "assets": true
  },
  "capabilities": {
    "events": {
      "contractVersion": "1.0.0",
      "schemaVersion": 1
    },
    "mutations": {
      "contractVersion": "1.0.0",
      "schemaVersion": 1
    },
    "flags": {
      "contractVersion": "1.0.0",
      "schemaVersion": 1
    },
    "replay": {
      "replayContractVersion": "2.0.0",
      "replaySchemaVersion": 2,
      "replayProtocolGeneration": "protocol-generation-v2",
      "transports": [
        {
          "codec": "elu-native-wireframe-v2",
          "compression": "gzip"
        }
      ]
    }
  },
  "session": {
    "idleTimeoutSeconds": 1800,
    "maximumDurationSeconds": 86400
  },
  "limits": {
    "eventBatchCount": 100,
    "eventBatchBytes": 1048576,
    "replayChunkBytes": 5242880,
    "queueBytes": 16777216
  },
  "capturePerformance": {
    "memory": true,
    "long_tasks": false,
    "sample_interval_ms": 30000
  }
}
        """.trimIndent()
    }
}
