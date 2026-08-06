package dev.elu.analytics.internal.config

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ReplayContractTest {
    @Test
    fun `manifest closure and every normative byte are checksum pinned`() {
        val manifest = json("contracts/v2/manifest.json")
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        assertEquals("2.0.0", manifest.getString("contractVersion"))
        assertEquals(2, manifest.getInt("schemaVersion"))
        assertEquals("config-and-replay", manifest.getString("contractScope"))
        assertEquals("specified-not-wired", manifest.getJSONObject("transport").getString("status"))
        assertEquals("unchanged", manifest.getJSONObject("transport").getString("runtimeBehavior"))
        val configurationEndpoint = manifest.getJSONObject("transport").getJSONObject("configurationEndpoint")
        assertEquals("GET", configurationEndpoint.getString("method"))
        assertEquals("/sdk/v2/{siteKey}/config", configurationEndpoint.getString("pathTemplate"))
        val configDelivery = configurationEndpoint.getJSONObject("delivery")
        assertEquals(
            activity.getJSONObject("transport").getJSONObject("configV2").getJSONObject("delivery").toString(),
            configDelivery.toString(),
        )
        assertEquals("fetch", configDelivery.getString("api"))
        assertEquals("cors", configDelivery.getString("mode"))
        assertEquals("omit", configDelivery.getString("credentials"))
        assertEquals("default", configDelivery.getString("cache"))
        assertEquals("not-required", configDelivery.getString("preflight"))
        val configCors = configurationEndpoint.getJSONObject("cors")
        assertEquals(
            activity.getJSONObject("transport").getJSONObject("configV2").getJSONObject("cors").toString(),
            configCors.toString(),
        )
        assertEquals("OPTIONS", configCors.getJSONObject("optionsRequest").getString("method"))
        assertFalse(configCors.getJSONObject("optionsRequest").getBoolean("authorizationRequired"))
        assertEquals("*", configCors.getJSONObject("optionsResponse").getString("accessControlAllowOrigin"))
        assertEquals(
            JSONArray(listOf("GET", "OPTIONS")).toString(),
            configCors.getJSONObject("optionsResponse").getJSONArray("accessControlAllowMethods").toString(),
        )
        assertEquals("omitted", configCors.getJSONObject("optionsResponse").getString("accessControlAllowCredentials"))
        assertEquals("*", configCors.getJSONObject("getResponse").getString("accessControlAllowOrigin"))
        assertEquals("omitted", configCors.getJSONObject("getResponse").getString("accessControlAllowCredentials"))
        assertFalse(configurationEndpoint.getJSONObject("transientFailures").getBoolean("staleConfigMayAuthorizeSend"))
        val replayEndpoint = manifest.getJSONObject("transport").getJSONObject("replayEndpoint")
        assertEquals("POST", replayEndpoint.getString("method"))
        assertEquals("/v2/replay", replayEndpoint.getString("path"))
        assertEquals("unsupported", replayEndpoint.getString("replayV1"))
        val authorization = replayEndpoint.getJSONObject("authorization")
        assertEquals("current-site-route", authorization.getString("serverAuthority"))
        assertTrue(authorization.getBoolean("serverRecordsProtocolGeneration"))
        assertFalse(authorization.getBoolean("serverRecordedGenerationProvesCaptureGeneration"))
        assertTrue(authorization.getBoolean("clientQueuePersistsProtocolGeneration"))
        assertTrue(authorization.getBoolean("generationChangePurgesBeforeSend"))
        assertFalse(authorization.getBoolean("requestIdentityCarriesConfigRevision"))
        assertFalse(authorization.getBoolean("requestIdentityCarriesProtocolGeneration"))
        assertFalse(authorization.getBoolean("captureAuditHashesAuthorizeCaller"))
        val delivery = replayEndpoint.getJSONObject("delivery")
        assertEquals(
            activity.getJSONObject("transport").getJSONObject("replayV2").getJSONObject("delivery").toString(),
            delivery.toString(),
        )
        assertEquals("fetch", delivery.getString("api"))
        assertEquals("cors", delivery.getString("mode"))
        assertEquals("omit", delivery.getString("credentials"))
        assertEquals("application/json", delivery.getString("contentType"))
        assertEquals("identity", delivery.getString("httpContentEncoding"))
        assertEquals("required", delivery.getString("preflight"))
        assertEquals("unsupported", delivery.getString("beacon"))
        val replayCors = replayEndpoint.getJSONObject("cors")
        assertEquals(
            activity.getJSONObject("transport").getJSONObject("replayV2").getJSONObject("cors").toString(),
            replayCors.toString(),
        )
        val preflightRequest = replayCors.getJSONObject("preflightRequest")
        assertEquals("OPTIONS", preflightRequest.getString("method"))
        assertFalse(preflightRequest.getBoolean("authorizationRequired"))
        assertEquals("POST", preflightRequest.getString("accessControlRequestMethod"))
        assertEquals(
            JSONArray(listOf("authorization", "content-type")).toString(),
            preflightRequest.getJSONArray("accessControlRequestHeaders").toString(),
        )
        val preflightResponse = replayCors.getJSONObject("preflightResponse")
        assertEquals("*", preflightResponse.getString("accessControlAllowOrigin"))
        assertEquals(
            JSONArray(listOf("POST", "OPTIONS")).toString(),
            preflightResponse.getJSONArray("accessControlAllowMethods").toString(),
        )
        assertEquals(
            JSONArray(listOf("Authorization", "Content-Type")).toString(),
            preflightResponse.getJSONArray("accessControlAllowHeaders").toString(),
        )
        assertFalse(preflightResponse.has("accessControlAllowCredentials"))
        val postResponse = replayCors.getJSONObject("postResponse")
        assertEquals("*", postResponse.getString("accessControlAllowOrigin"))
        assertEquals(JSONArray(listOf("Retry-After")).toString(), postResponse.getJSONArray("accessControlExposeHeaders").toString())
        assertFalse(postResponse.has("accessControlAllowCredentials"))
        val identityConflict = replayEndpoint.getJSONObject("responses").getJSONObject("identityConflict")
        assertEquals(409, identityConflict.getInt("httpStatus"))
        assertEquals("schemas/replay-error.schema.json", identityConflict.getString("schema"))
        assertEquals("permanent", identityConflict.getString("disposition"))
        assertEquals("none", identityConflict.getString("acknowledgement"))

        val routed =
            buildList {
                manifest.getJSONObject("schemas").keys().forEach { role ->
                    add(manifest.getJSONObject("schemas").getString(role))
                }
                manifest.getJSONArray("fixtures").strings().forEach(::add)
                manifest.getJSONObject("testVectors").keys().forEach { role ->
                    add(manifest.getJSONObject("testVectors").getString(role))
                }
                manifest.getJSONObject("gates").keys().forEach { role ->
                    add(manifest.getJSONObject("gates").getString(role))
                }
                manifest.getJSONObject("dependencies").keys().forEach { role ->
                    add(manifest.getJSONObject("dependencies").getJSONObject(role).getString("path"))
                }
                add(manifest.getString("checksums"))
            }
        routed.forEach { relative ->
            val normalized = Path.of("v2").resolve(relative).normalize().toString().replace('\\', '/')
            checkNotNull(javaClass.classLoader?.getResource("contracts/$normalized")) {
                "Manifest dependency is missing or has wrong case: contracts/$normalized"
            }
        }

        val checksums =
            resourceText("contracts/v2/SHA256SUMS")
                .lineSequence()
                .filter(String::isNotBlank)
                .associate { line ->
                    val match = CHECKSUM_LINE.matchEntire(line) ?: error("Invalid checksum line $line")
                    match.groupValues[2] to match.groupValues[1]
                }
        assertEquals(EXPECTED_NORMATIVE_FILES, checksums.keys)
        checksums.forEach { (relative, digest) ->
            assertEquals(relative, digest, sha256(resourceBytes("contracts/v2/$relative")))
        }

        val dependencies = manifest.getJSONObject("dependencies")
        dependencies.keys().forEach { role ->
            val dependency = dependencies.getJSONObject(role)
            val normalized = Path.of("v2").resolve(dependency.getString("path")).normalize().toString().replace('\\', '/')
            assertEquals(role, dependency.getString("sha256"), sha256(resourceBytes("contracts/$normalized")))
        }
        assertEquals("elu-canonical-json-v1", dependencies.getJSONObject("canonicalJsonV1").getString("algorithm"))
    }

    @Test
    fun `all fixtures validate and negative vectors fail closed`() {
        val validator = ResourceJsonSchemaValidator("contracts")
        val manifest = json("contracts/v2/manifest.json")
        assertEquals(FIXTURE_SCHEMAS.keys, manifest.getJSONArray("fixtures").strings().toSet())
        FIXTURE_SCHEMAS.forEach { (fixture, schema) ->
            val errors = validator.validate("v2/$schema", json("contracts/v2/$fixture"))
            assertTrue("$fixture failed $schema: ${errors.joinToString()}", errors.isEmpty())
        }

        val request = json("contracts/v2/fixtures/replay-request.json")
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        listOf("configRevision", "replayProtocolGeneration").forEach { field ->
            assertFalse(field, request.has(field))
            assertFalse(field, request.getJSONObject("chunk").has(field))
        }
        activity.getJSONArray("negativeRequests").objects().forEach { vector ->
            val candidate = mutate(request, vector)
            val errors = validator.validate("v2/schemas/replay-request.schema.json", candidate)
            if (vector.optString("validation") == "semantic") {
                assertTrue(vector.getString("id"), errors.isEmpty())
                assertFalse(vector.getString("id"), requestIdMatches(candidate))
            } else {
                assertFalse(vector.getString("id"), errors.isEmpty())
            }
        }
        val missingContext = JSONObject(request.toString())
        missingContext.getJSONObject("chunk").remove("contextRevision")
        assertFalse(
            validator.validate("v2/schemas/replay-request.schema.json", missingContext).isEmpty(),
        )
    }

    @Test
    fun `negotiation and canonical request identity match the shared vector`() {
        val config = json("contracts/v2/fixtures/config-enabled.json")
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        val negotiation = activity.getJSONObject("negotiation")
        assertEquals(2, config.getInt("schemaVersion"))
        assertEquals(2, negotiation.getInt("configSchemaVersion"))
        val capabilities = config.getJSONObject("capabilities")
        val channels = negotiation.getJSONObject("channels")
        listOf("events", "mutations", "flags").forEach { channel ->
            assertEquals(
                channels.getJSONObject(channel).getString("contractVersion"),
                capabilities.getJSONObject(channel).getString("contractVersion"),
            )
            assertEquals(
                channels.getJSONObject(channel).getInt("schemaVersion"),
                capabilities.getJSONObject(channel).getInt("schemaVersion"),
            )
        }
        val replayCapability = capabilities.getJSONObject("replay")
        val replayVector = channels.getJSONObject("replay")
        listOf("replayContractVersion", "replayProtocolGeneration").forEach { field ->
            assertEquals(replayVector.getString(field), replayCapability.getString(field))
        }
        assertEquals(replayVector.getInt("replaySchemaVersion"), replayCapability.getInt("replaySchemaVersion"))
        assertEquals(replayVector.getJSONArray("transports").toString(), replayCapability.getJSONArray("transports").toString())
        assertEquals("/v2/replay", replayVector.getString("endpointPath"))
        assertEquals("reject", negotiation.getString("replayV1"))

        val requestSource = resourceText("contracts/v2/fixtures/replay-request.json")
        val requestValue = V1StrictCanonicalJson.parse(requestSource) as V1StrictCanonicalJson.Value.ObjectValue
        val chunk = checkNotNull(requestValue.member("chunk"))
        val chunkBytes = V1StrictCanonicalJson.canonicalBytes(chunk)
        val requestBytes = V1StrictCanonicalJson.canonicalBytes(requestValue)
        val requestIdentity = activity.getJSONObject("requestIdentity")
        val lengthBytes = ByteBuffer.allocate(4).putInt(chunkBytes.size).array()
        val material =
            requestIdentity.getString("domain").toByteArray(StandardCharsets.UTF_8) +
                byteArrayOf(0) +
                lengthBytes +
                chunkBytes
        val computedRequestId = "request_" + sha256(material)

        assertEquals(requestIdentity.getInt("canonicalChunkLength"), chunkBytes.size)
        assertEquals(requestIdentity.getString("canonicalChunkLengthUint32beHex"), hex(lengthBytes))
        assertEquals(requestIdentity.getString("canonicalChunkBase64"), Base64.getEncoder().encodeToString(chunkBytes))
        assertEquals(requestIdentity.getString("canonicalChunkSha256").removePrefix("sha256:"), sha256(chunkBytes))
        assertEquals(requestIdentity.getInt("materialLength"), material.size)
        assertEquals(requestIdentity.getString("materialSha256").removePrefix("sha256:"), sha256(material))
        assertEquals(requestIdentity.getString("requestId"), computedRequestId)
        assertEquals(json("contracts/v2/fixtures/replay-request.json").getString("requestId"), computedRequestId)
        assertEquals(requestIdentity.getInt("canonicalRequestLength"), requestBytes.size)
        assertEquals(requestIdentity.getString("canonicalRequestBase64"), Base64.getEncoder().encodeToString(requestBytes))
        assertEquals(requestIdentity.getString("canonicalRequestSha256").removePrefix("sha256:"), sha256(requestBytes))
        assertTrue(String(requestBytes, StandardCharsets.UTF_8).startsWith("{\"chunk\":"))
    }

    @Test
    fun `strict raw JSON canonical Unicode and safe integer vectors are executable`() {
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        val rawStrictJson = activity.getJSONObject("rawStrictJson")
        V1StrictCanonicalJson.parse(rawStrictJson.getString("valid"))
        rawStrictJson.getJSONArray("duplicateKeyCases").objects().forEach { vector ->
            assertStrictJsonRejected(vector.getString("id"), vector.getString("raw"))
        }

        val canonicalDependency = json("contracts/v1/test-vectors/capture-admission-activity.json")
        assertEquals("elu-canonical-json-v1", canonicalDependency.getJSONObject("canonicalization").getString("algorithm"))
        canonicalDependency.getJSONObject("canonicalization").getJSONArray("cases").objects().forEach { vector ->
            val id = vector.getString("id")
            if (vector.optString("expect") == "reject") {
                assertStrictJsonRejected(id, vector.getString("raw"))
            } else {
                val canonical = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(vector.getString("raw")))
                assertEquals(id, vector.getString("expectedCanonicalBase64"), Base64.getEncoder().encodeToString(canonical))
                assertEquals(id, vector.getString("expectedSha256").removePrefix("sha256:"), sha256(canonical))
            }
        }

        val supplement = activity.getJSONObject("canonicalizationSupplement")
        assertEquals("elu-canonical-json-v1", supplement.getString("algorithm"))
        supplement.getJSONArray("cases").objects().forEach { vector ->
            val canonical = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(vector.getString("raw")))
            assertEquals(vector.getString("id"), vector.getString("expectedCanonicalBase64"), Base64.getEncoder().encodeToString(canonical))
            assertEquals(vector.getString("id"), vector.getString("expectedSha256").removePrefix("sha256:"), sha256(canonical))
        }

        activity.getJSONObject("safeIntegerParity").getJSONArray("cases").objects().forEach { vector ->
            val id = vector.getString("raw")
            val canonical = replaySafeIntegerCanonical(vector.getString("raw"))
            if (vector.getString("expect") == "accept") {
                assertEquals(id, vector.getString("canonical"), canonical)
            } else {
                assertEquals(id, null, canonical)
            }
        }
    }

    @Test
    fun `config cache vectors enforce bounded ttl and transient no store`() {
        val manifest = json("contracts/v2/manifest.json")
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        val endpoint = manifest.getJSONObject("transport").getJSONObject("configurationEndpoint")
        assertEquals(activity.getJSONObject("transport").getJSONObject("configV2").getJSONObject("delivery").toString(), endpoint.getJSONObject("delivery").toString())
        val successful = endpoint.getJSONObject("successfulResponses")
        assertEquals(300, successful.getInt("maximumTtlSeconds"))
        assertEquals(300, successful.getInt("maximumRevocationBudgetSeconds"))
        assertEquals("forbidden", successful.getString("staleWhileRevalidate"))
        assertTrue(successful.getBoolean("sdkRejectsAtOrAfterExpiresAt"))
        activity.getJSONObject("configCaching").getJSONArray("cases").objects().forEach { vector ->
            val ttl =
                maxOf(
                    0,
                    minOf(
                        successful.getInt("maximumTtlSeconds"),
                        vector.getInt("remainingIssuanceLifetimeSeconds"),
                        vector.getInt("revocationBudgetSeconds"),
                    ),
                )
            assertEquals(vector.toString(), vector.getInt("expectedTtlSeconds"), ttl)
            val header = successful.getString("cacheControlTemplate").replace("{ttl}", ttl.toString())
            assertTrue(header, "max-age=$ttl" in header)
            assertTrue(header, "s-maxage=$ttl" in header)
            assertTrue(header, "must-revalidate" in header)
            assertFalse(header, "stale-while-revalidate" in header)
        }
        val transient = endpoint.getJSONObject("transientFailures")
        assertEquals("no-store, max-age=0, s-maxage=0", transient.getString("httpResponseCacheControl"))
        assertFalse(transient.getBoolean("staleConfigMayAuthorizeSend"))
        assertEquals("preserve-queue-and-block-send", transient.getString("clientAction"))
    }

    @Test
    fun `payload admission executes every exact limit and permanent rejection vector`() {
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        val constants = activity.getJSONObject("constants")
        val admission = activity.getJSONObject("payloadAdmission")
        assertEquals("permanent", admission.getJSONObject("failure").getString("disposition"))
        assertEquals("none", admission.getJSONObject("failure").getString("acknowledgement"))
        val replayPayload = json("contracts/v2/fixtures/replay.json").getString("payload")
        val canonical = admitBrowserDomPayload(replayPayload, constants)
        assertEquals(activity.getJSONObject("codecPayload").getInt("decompressedLength"), canonical.decodedBytes)
        assertEquals(activity.getJSONObject("codecPayload").getInt("recordCount"), canonical.logicalRecords)
        admission.getJSONArray("exactLimitCases").objects().forEach { vector ->
            val encoded = gzipBase64(payloadConstruction(vector.getString("construction"), constants))
            val inspected = admitBrowserDomPayload(encoded, constants)
            if (vector.has("expectedDecodedBytes")) {
                assertEquals(vector.getString("id"), vector.getInt("expectedDecodedBytes"), inspected.decodedBytes)
            }
            assertEquals(vector.getString("id"), vector.getInt("expectedLogicalRecords"), inspected.logicalRecords)
            if (vector.has("expectedNestingDepth")) {
                assertEquals(vector.getString("id"), vector.getInt("expectedNestingDepth"), inspected.nestingDepth)
            }
        }

        admission.getJSONArray("negativeCases").objects().forEach { vector ->
            val encoded = encodedPayloadConstruction(vector.getString("construction"), constants, replayPayload)
            val error =
                try {
                    admitBrowserDomPayload(encoded, constants)
                    null
                } catch (failure: PayloadAdmissionFailure) {
                    failure.code
                }
            assertEquals(vector.getString("id"), vector.getString("expectedError"), error)
        }
    }

    @Test
    fun `queue accounting invalidation generation and scheduling vectors are executable`() {
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        val constants = activity.getJSONObject("constants")
        assertEquals(7 * 24 * 60 * 60, constants.getInt("maximumRecordAgeSeconds"))
        assertEquals(8 * 24 * 60 * 60, constants.getInt("idempotencyRetentionSeconds"))
        assertTrue(constants.getInt("idempotencyRetentionSeconds") > constants.getInt("maximumRecordAgeSeconds"))
        val accounting = activity.getJSONObject("aggregateQueueAccounting")
        assertEquals("eventMutationCount + replayCount", accounting.getJSONObject("formulas").getString("count"))
        assertEquals("eventMutationBytes + replayRequestBytes", accounting.getJSONObject("formulas").getString("bytes"))
        assertEquals("min(configQueueBytes, 268435456)", accounting.getJSONObject("formulas").getString("effectiveQueueByteLimit"))
        assertEquals("min(configReplayChunkBytes, 5242880)", accounting.getJSONObject("formulas").getString("effectiveReplayRequestLimit"))
        val configuredReplayChunkBytes =
            json("contracts/v2/fixtures/config-enabled.json")
                .getJSONObject("limits")
                .getLong("replayChunkBytes")
        val hardReplayRequestLimit = constants.getLong("maximumReplayRequestBytes")
        val admitsReplayRequest: (Long, Long) -> Boolean = { requestBytes, configLimit ->
            requestBytes <= minOf(configLimit, hardReplayRequestLimit)
        }
        val reducedConfigLimit = 1_024L
        assertTrue(admitsReplayRequest(reducedConfigLimit, reducedConfigLimit))
        assertFalse(admitsReplayRequest(reducedConfigLimit + 1, reducedConfigLimit))
        accounting.getJSONArray("cases").objects().forEach { vector ->
            val count = vector.getInt("eventMutationCount") + vector.getInt("replayCount")
            val bytes = vector.getLong("eventMutationBytes") + vector.getLong("replayRequestBytes")
            val byteLimit = minOf(vector.getLong("configQueueBytes"), constants.getLong("maximumAggregateQueueBytes"))
            val admit =
                count <= constants.getInt("maximumAggregateQueueRecords") &&
                    bytes <= byteLimit &&
                    admitsReplayRequest(vector.getLong("replayRequestBytes"), configuredReplayChunkBytes)
            assertEquals(vector.getString("id"), vector.getInt("expectedCount"), count)
            assertEquals(vector.getString("id"), vector.getLong("expectedBytes"), bytes)
            assertEquals(vector.getString("id"), vector.getBoolean("admit"), admit)
        }

        activity.getJSONObject("invalidation").getJSONArray("cases").objects().forEach { vector ->
            assertEquals(vector.getString("id"), vector.getString("action"), classifyInvalidation(vector.getString("id")))
        }

        val generation = activity.getJSONObject("generationQueue")
        assertEquals("replayProtocolGeneration", generation.getString("persistedField"))
        assertEquals("before-any-send-attempt", generation.getString("comparisonPoint"))
        assertTrue(generation.getBoolean("serverRecordsCurrentGeneration"))
        assertFalse(generation.getBoolean("serverRecordProvesClientCaptureGeneration"))
        val expectedGenerationActions =
            mapOf(
                "same-generation" to "preserve-and-send",
                "changed-generation-same-transport" to "purge-before-send",
                "unsupported-current-generation" to "purge-and-fail-closed",
            )
        generation.getJSONArray("cases").objects().forEach { vector ->
            assertEquals(
                vector.getString("id"),
                expectedGenerationActions.getValue(vector.getString("id")),
                classifyGeneration(vector),
            )
            assertEquals(vector.getString("id"), vector.getString("expectedAction"), classifyGeneration(vector))
            if (vector.getString("currentGeneration") != vector.getString("queuedGeneration")) {
                assertFalse(vector.getString("id"), vector.getString("expectedAction") == "preserve-and-send")
            }
        }

        val scheduling = activity.getJSONObject("scheduling")
        assertEquals("lowest-unresolved-sequence-per-replay-then-lowest-global-ordinal", scheduling.getString("rule"))
        scheduling.getJSONArray("cases").objects().forEach { vector ->
            val selected = checkNotNull(selectScheduledHead(scheduling.getJSONArray("rows"), vector.getLong("now")))
            assertEquals(vector.toString(), vector.getString("expectedReplayId"), selected.getString("replayId"))
            assertEquals(vector.toString(), vector.getLong("expectedSequence"), selected.getLong("sequence"))
        }
    }

    @Test
    fun `site scoped idempotency executes duplicates conflicts and other site acceptance`() {
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        val vector = activity.getJSONObject("idempotency")
        assertEquals("authorized-site-id", vector.getString("siteAuthority"))
        assertEquals(JSONArray(listOf("siteId", "requestId")).toString(), vector.getJSONArray("requestScope").toString())
        assertEquals(JSONArray(listOf("canonicalRequestSha256")).toString(), vector.getJSONArray("requestBinding").toString())
        assertEquals(JSONArray(listOf("siteId", "replayId", "chunkId")).toString(), vector.getJSONArray("semanticScope").toString())
        assertEquals(JSONArray(listOf("siteId", "replayId", "sequence")).toString(), vector.getJSONArray("orderingScope").toString())
        assertEquals("none", vector.getString("conflictAcknowledgement"))

        val initialObject = vector.getJSONObject("initial")
        assertEquals("fixtures/replay-ack.json", initialObject.getString("storedResponseFixture"))
        val initial = ReplayIdentity.from(initialObject)
        val storedAcknowledgement = json("contracts/v2/${initialObject.getString("storedResponseFixture")}")
        val request = json("contracts/v2/fixtures/replay-request.json")
        val conflictTemplate = json("contracts/v2/${vector.getString("conflictResponseFixture")}")
        vector.getJSONArray("cases").objects().forEach { case ->
            val candidate = initial.withOverrides(case.getJSONObject("overrides"))
            val resolution = resolveReplayIdentity(initial, candidate)
            assertEquals(case.getString("id"), case.getString("expected"), resolution.result)
            when (resolution.result) {
                "same-stored-effective-ack" -> {
                    assertEquals(vector.getString("exactDuplicateResponse"), resolution.result)
                    assertTrue(acknowledgementMatches(request, storedAcknowledgement))
                    assertEquals(request.getString("requestId"), storedAcknowledgement.getString("requestId"))
                }
                "permanent-conflict" -> {
                    assertEquals(case.getString("conflictScope"), resolution.conflictScope)
                    val conflict =
                        JSONObject(conflictTemplate.toString())
                            .put("requestId", candidate.requestId)
                            .put("conflictScope", resolution.conflictScope)
                    assertEquals(candidate.requestId, conflict.getString("requestId"))
                    assertEquals(409, conflict.getInt("status"))
                    assertEquals("replay-identity-conflict", conflict.getString("code"))
                    assertEquals("permanent", conflict.getString("disposition"))
                    listOf("result", "replayId", "chunkId", "sequence").forEach { field -> assertFalse(field, conflict.has(field)) }
                }
                "accepted-new-site-scope" -> assertFalse(case.getString("id"), candidate.siteId == initial.siteId)
                else -> error("Unexpected idempotency result ${resolution.result}")
            }
        }
    }

    @Test
    fun `masking receipt artifact catalog and acknowledgement binding are exact`() {
        val activity = json("contracts/v2/test-vectors/replay-activity.json")
        val replay = json("contracts/v2/fixtures/replay.json")
        val request = json("contracts/v2/fixtures/replay-request.json")
        val acknowledgement = json("contracts/v2/fixtures/replay-ack.json")
        assertEquals(replay.toString(), request.getJSONObject("chunk").toString())

        val masking = activity.getJSONObject("masking")
        val profileValue = V1StrictCanonicalJson.parse(masking.getJSONObject("profile").toString())
        val profileBytes = V1StrictCanonicalJson.canonicalBytes(profileValue)
        assertEquals(masking.getString("canonicalProfileBase64"), Base64.getEncoder().encodeToString(profileBytes))
        assertEquals(masking.getString("maskingProfileHash").removePrefix("sha256:"), sha256(profileBytes))
        assertEquals(masking.getString("maskingProfileHash"), replay.getJSONObject("privacy").getString("maskingProfileHash"))
        assertTrue(replay.getJSONObject("privacy").getBoolean("appliedBeforeSerialization"))
        assertTrue(replay.getJSONObject("privacy").getBoolean("secureInputsMasked"))
        assertEquals(
            JSONArray(
                listOf(
                    "mask-and-block",
                    "serialize-codec-payload",
                    "compress-once",
                    "base64",
                    "build-outer-envelope",
                    "persist-exact-request",
                    "transport",
                ),
            ).toString(),
            masking.getJSONArray("requiredOrder").toString(),
        )

        val codec = activity.getJSONObject("codecPayload")
        val compressed = Base64.getDecoder().decode(replay.getString("payload"))
        assertEquals(codec.getString("codec"), replay.getString("codec"))
        assertEquals(codec.getString("compression"), replay.getString("compression"))
        assertEquals(1, codec.getInt("compressionPasses"))
        assertEquals(codec.getInt("compressedLength"), compressed.size)
        assertEquals(codec.getString("compressedSha256").removePrefix("sha256:"), sha256(compressed))
        assertEquals(codec.getString("compressedBase64"), Base64.getEncoder().encodeToString(compressed))
        val decompressed = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        assertFalse(decompressed.size >= 2 && decompressed[0] == 0x1f.toByte() && decompressed[1] == 0x8b.toByte())
        assertEquals(codec.getInt("decompressedLength"), decompressed.size)
        assertEquals(codec.getString("decompressedSha256").removePrefix("sha256:"), sha256(decompressed))
        assertEquals(codec.getString("decompressedBase64"), Base64.getEncoder().encodeToString(decompressed))
        val decompressedValue = V1StrictCanonicalJson.parse(String(decompressed, StandardCharsets.UTF_8))
        assertTrue(decompressedValue is V1StrictCanonicalJson.Value.ArrayValue)
        assertTrue(V1StrictCanonicalJson.canonicalBytes(decompressedValue).contentEquals(decompressed))
        val records = JSONArray(String(decompressed, StandardCharsets.UTF_8))
        assertEquals(codec.getInt("recordCount"), records.length())
        assertEquals(
            codec.getJSONArray("orderedRecordTypes").toString(),
            JSONArray(List(records.length()) { index -> records.getJSONObject(index).getInt("type") }).toString(),
        )
        val maskedText =
            records.getJSONObject(1)
                .getJSONObject("data")
                .getJSONObject("node")
                .getJSONArray("childNodes")
                .getJSONObject(1)
                .getJSONArray("childNodes")
                .getJSONObject(1)
                .getJSONArray("childNodes")
                .getJSONObject(0)
                .getJSONArray("childNodes")
                .getJSONObject(0)
                .getString("textContent")
        assertEquals(codec.getString("maskedText"), maskedText)

        activity.getJSONObject("artifactCatalog").keys().forEach { relative ->
            assertEquals(
                relative,
                activity.getJSONObject("artifactCatalog").getString(relative).removePrefix("sha256:"),
                sha256(resourceBytes("contracts/v2/$relative")),
            )
        }
        val acknowledgementPolicy = activity.getJSONObject("acknowledgement")
        assertEquals(
            JSONArray(listOf("requestId", "replayId", "chunkId", "sequence", "result")).toString(),
            acknowledgementPolicy.getJSONArray("requiredBindings").toString(),
        )
        assertEquals("accepted", acknowledgementPolicy.getString("acceptedResult"))
        assertTrue(acknowledgementPolicy.getBoolean("deleteOnlyAfterCompleteValidation"))
        assertEquals("same-stored-effective-ack", acknowledgementPolicy.getString("duplicateBehavior"))
        assertTrue(acknowledgementMatches(request, acknowledgement))
        listOf(
            "requestId" to "request_${"0".repeat(64)}",
            "replayId" to "replay_other",
            "chunkId" to "chunk_other",
            "sequence" to 2,
            "result" to "retryable",
        ).forEach { (field, replacement) ->
            val candidate = JSONObject(acknowledgement.toString()).put(field, replacement)
            assertFalse(field, acknowledgementMatches(request, candidate))
        }
    }

    @Test
    fun `contract assets do not activate a production v2 transport`() {
        val sourceRoot = repositoryRoot().resolve("elu-analytics/src/main/kotlin")
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.forEach { path ->
                val source = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                assertFalse(path.toString(), source.contains("elu-http-v2"))
                assertFalse(path.toString(), source.contains("replayProtocolGeneration"))
                assertFalse(path.toString(), source.contains("/v2/replay"))
            }
        }
    }

    private fun requestIdMatches(request: JSONObject): Boolean {
        val parsed = V1StrictCanonicalJson.parse(request.toString()) as V1StrictCanonicalJson.Value.ObjectValue
        val chunk = checkNotNull(parsed.member("chunk"))
        val chunkBytes = V1StrictCanonicalJson.canonicalBytes(chunk)
        val length = ByteBuffer.allocate(4).putInt(chunkBytes.size).array()
        val material =
            "elu-sdk-replay-request-v2".toByteArray(StandardCharsets.UTF_8) +
                byteArrayOf(0) +
                length +
                chunkBytes
        return request.getString("requestId") == "request_" + sha256(material)
    }

    private fun acknowledgementMatches(request: JSONObject, acknowledgement: JSONObject): Boolean {
        val chunk = request.getJSONObject("chunk")
        return acknowledgement.optString("requestId") == request.getString("requestId") &&
            acknowledgement.optString("replayId") == chunk.getString("replayId") &&
            acknowledgement.optString("chunkId") == chunk.getString("chunkId") &&
            acknowledgement.optLong("sequence", -1) == chunk.getLong("sequence") &&
            acknowledgement.optString("result") == "accepted"
    }

    private fun assertStrictJsonRejected(id: String, raw: String) {
        val rejected =
            try {
                V1StrictCanonicalJson.parse(raw)
                false
            } catch (_: V1MalformedConfigException) {
                true
            }
        assertTrue(id, rejected)
    }

    private fun replaySafeIntegerCanonical(raw: String): String? {
        return try {
            val value = V1StrictCanonicalJson.parse(raw)
            if (value !is V1StrictCanonicalJson.Value.NumberValue) {
                null
            } else {
                val canonical = V1StrictCanonicalJson.canonicalize(value)
                val integer = canonical.toLongOrNull()
                canonical.takeIf { integer != null && integer in 0..MAXIMUM_SAFE_INTEGER }
            }
        } catch (_: V1MalformedConfigException) {
            null
        }
    }

    private fun admitBrowserDomPayload(
        encoded: String,
        constants: JSONObject,
    ): CodecInspection {
        val compressed = decodeCanonicalBase64(encoded)
        val decoded = inflateSingleGzip(compressed, constants.getInt("maximumReplayDecodedBytes"))
        if (decoded.size >= 2 && decoded[0] == GZIP_MAGIC_FIRST && decoded[1] == GZIP_MAGIC_SECOND) {
            throw PayloadAdmissionFailure("nested-compression")
        }
        return inspectCodecJson(decoded, constants)
    }

    private fun decodeCanonicalBase64(value: String): ByteArray {
        if (value.isEmpty() || INVALID_BASE64_CHARACTER.containsMatchIn(value)) {
            throw PayloadAdmissionFailure("invalid-base64")
        }
        if (!CANONICAL_BASE64.matches(value)) throw PayloadAdmissionFailure("noncanonical-base64")
        val decoded =
            try {
                Base64.getDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                throw PayloadAdmissionFailure("invalid-base64")
            }
        if (Base64.getEncoder().encodeToString(decoded) != value) {
            throw PayloadAdmissionFailure("noncanonical-base64")
        }
        return decoded
    }

    private fun inflateSingleGzip(
        payload: ByteArray,
        maximumDecodedBytes: Int,
    ): ByteArray {
        val headerLength = gzipHeaderLength(payload)
        val inflater = Inflater(true)
        try {
            inflater.setInput(payload, headerLength, payload.size - headerLength)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (!inflater.finished()) {
                val count =
                    try {
                        inflater.inflate(buffer)
                    } catch (_: DataFormatException) {
                        throw PayloadAdmissionFailure("invalid-gzip")
                    }
                if (count > 0) {
                    if (output.size() + count > maximumDecodedBytes) {
                        throw PayloadAdmissionFailure("decoded-byte-limit")
                    }
                    output.write(buffer, 0, count)
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw PayloadAdmissionFailure("invalid-gzip")
                } else {
                    throw PayloadAdmissionFailure("invalid-gzip")
                }
            }

            val trailerOffset = headerLength + inflater.bytesRead.toInt()
            if (trailerOffset + GZIP_TRAILER_BYTES > payload.size) throw PayloadAdmissionFailure("invalid-gzip")
            val decoded = output.toByteArray()
            val crc = CRC32().apply { update(decoded) }.value
            if (readUint32LittleEndian(payload, trailerOffset) != crc) throw PayloadAdmissionFailure("invalid-gzip")
            if (readUint32LittleEndian(payload, trailerOffset + 4) != (decoded.size.toLong() and UINT32_MASK)) {
                throw PayloadAdmissionFailure("invalid-gzip")
            }
            if (trailerOffset + GZIP_TRAILER_BYTES != payload.size) {
                throw PayloadAdmissionFailure("trailing-gzip-data")
            }
            return decoded
        } finally {
            inflater.end()
        }
    }

    private fun gzipHeaderLength(payload: ByteArray): Int {
        if (
            payload.size < GZIP_MINIMUM_BYTES ||
            payload[0] != GZIP_MAGIC_FIRST ||
            payload[1] != GZIP_MAGIC_SECOND ||
            payload[2] != GZIP_DEFLATE_METHOD
        ) {
            throw PayloadAdmissionFailure("invalid-gzip")
        }
        val flags = payload[3].toInt() and 0xff
        if (flags and GZIP_RESERVED_FLAGS != 0) throw PayloadAdmissionFailure("invalid-gzip")
        var offset = GZIP_FIXED_HEADER_BYTES
        if (flags and GZIP_FLAG_EXTRA != 0) {
            if (offset + 2 > payload.size) throw PayloadAdmissionFailure("invalid-gzip")
            val extraLength = (payload[offset].toInt() and 0xff) or ((payload[offset + 1].toInt() and 0xff) shl 8)
            offset += 2 + extraLength
            if (offset > payload.size) throw PayloadAdmissionFailure("invalid-gzip")
        }
        listOf(GZIP_FLAG_NAME, GZIP_FLAG_COMMENT).forEach { flag ->
            if (flags and flag != 0) {
                while (offset < payload.size && payload[offset] != 0.toByte()) offset += 1
                if (offset >= payload.size) throw PayloadAdmissionFailure("invalid-gzip")
                offset += 1
            }
        }
        if (flags and GZIP_FLAG_HEADER_CRC != 0) offset += 2
        if (offset + GZIP_TRAILER_BYTES > payload.size) throw PayloadAdmissionFailure("invalid-gzip")
        return offset
    }

    private fun readUint32LittleEndian(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun gzipBase64(decoded: ByteArray): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(decoded) }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun encodedPayloadConstruction(
        construction: String,
        constants: JSONObject,
        replayPayload: String,
    ): String {
        val validCompressed = decodeCanonicalBase64(replayPayload)
        return when (construction) {
            "invalid-gzip-bytes" -> Base64.getEncoder().encodeToString("not-gzip".toByteArray(StandardCharsets.UTF_8))
            "invalid-base64-alphabet" -> "***"
            "remove-required-padding" -> replayPayload.trimEnd('=')
            "gzip-of-gzip" -> gzipBase64(validCompressed)
            "concatenated-gzip-members" -> Base64.getEncoder().encodeToString(validCompressed + validCompressed)
            "gzip-plus-one-byte" -> Base64.getEncoder().encodeToString(validCompressed + byteArrayOf(0))
            else -> gzipBase64(payloadConstruction(construction, constants))
        }
    }

    private fun inspectCodecJson(
        decoded: ByteArray,
        constants: JSONObject,
    ): CodecInspection {
        val maximumBytes = constants.getInt("maximumReplayDecodedBytes")
        if (decoded.size > maximumBytes) throw PayloadAdmissionFailure("decoded-byte-limit")
        val source =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString()
            } catch (_: Exception) {
                throw PayloadAdmissionFailure("invalid-unicode")
            }
        val shape = scanTopLevelArray(source)
        if (shape.nestingDepth > constants.getInt("maximumJsonNestingDepth")) {
            throw PayloadAdmissionFailure("json-nesting-limit")
        }
        if (shape.logicalRecords > constants.getInt("maximumReplayLogicalRecords")) {
            throw PayloadAdmissionFailure("logical-record-limit")
        }
        if (decoded.size <= MAXIMUM_FULL_CANONICAL_INSPECTION_BYTES && shape.nestingDepth <= V1_CANONICAL_MAXIMUM_NESTING) {
            val value =
                try {
                    V1StrictCanonicalJson.parse(source)
                } catch (failure: V1MalformedConfigException) {
                    val code = if (failure.message?.contains("surrogate") == true) "invalid-unicode" else "invalid-codec-json"
                    throw PayloadAdmissionFailure(code)
                }
            if (value !is V1StrictCanonicalJson.Value.ArrayValue) throw PayloadAdmissionFailure("invalid-codec-shape")
            if (!V1StrictCanonicalJson.canonicalBytes(value).contentEquals(decoded)) {
                throw PayloadAdmissionFailure("noncanonical-codec-json")
            }
        }
        return CodecInspection(decoded.size, shape.logicalRecords, shape.nestingDepth)
    }

    private fun scanTopLevelArray(source: String): CodecInspection {
        val stack = mutableListOf<Char>()
        var inString = false
        var escaped = false
        var rootClosed = false
        var expectingRootValue = true
        var logicalRecords = 0
        var maximumDepth = 0
        source.forEach { character ->
            if (rootClosed) {
                if (!character.isWhitespace()) throw PayloadAdmissionFailure("invalid-codec-json")
                return@forEach
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                return@forEach
            }
            when (character) {
                ' ', '\t', '\r', '\n' -> Unit
                '"' -> {
                    if (stack.size == 1 && expectingRootValue) {
                        logicalRecords += 1
                        expectingRootValue = false
                    }
                    inString = true
                }
                '[', '{' -> {
                    if (stack.isEmpty() && character != '[') throw PayloadAdmissionFailure("invalid-codec-shape")
                    if (stack.size == 1 && expectingRootValue) {
                        logicalRecords += 1
                        expectingRootValue = false
                    }
                    stack += character
                    maximumDepth = maxOf(maximumDepth, stack.size)
                }
                ']', '}' -> {
                    if (stack.isEmpty()) throw PayloadAdmissionFailure("invalid-codec-json")
                    val opening = stack.removeAt(stack.lastIndex)
                    if ((opening == '[' && character != ']') || (opening == '{' && character != '}')) {
                        throw PayloadAdmissionFailure("invalid-codec-json")
                    }
                    if (stack.isEmpty()) {
                        if (logicalRecords > 0 && expectingRootValue) throw PayloadAdmissionFailure("invalid-codec-json")
                        rootClosed = true
                    }
                }
                ',' -> {
                    if (stack.size == 1) {
                        if (expectingRootValue) throw PayloadAdmissionFailure("invalid-codec-json")
                        expectingRootValue = true
                    }
                }
                else -> {
                    if (stack.size == 1 && expectingRootValue) {
                        logicalRecords += 1
                        expectingRootValue = false
                    }
                }
            }
        }
        if (!rootClosed || inString || stack.isNotEmpty()) throw PayloadAdmissionFailure("invalid-codec-json")
        return CodecInspection(source.toByteArray(StandardCharsets.UTF_8).size, logicalRecords, maximumDepth)
    }

    private fun payloadConstruction(
        construction: String,
        constants: JSONObject,
    ): ByteArray {
        val maximumBytes = constants.getInt("maximumReplayDecodedBytes")
        val maximumRecords = constants.getInt("maximumReplayLogicalRecords")
        val maximumDepth = constants.getInt("maximumJsonNestingDepth")
        return when (construction) {
            "canonical-array-bytes-16777216" -> "[\"${"a".repeat(maximumBytes - 4)}\"]".toByteArray(StandardCharsets.UTF_8)
            "logical-records-10000" -> numericArray(maximumRecords)
            "json-nesting-depth-256" -> ("[".repeat(maximumDepth) + "0" + "]".repeat(maximumDepth)).toByteArray(StandardCharsets.UTF_8)
            "decoded-bytes-16777217" -> ByteArray(maximumBytes + 1) { 'a'.code.toByte() }
            "logical-records-10001" -> numericArray(maximumRecords + 1)
            "json-nesting-depth-257" -> ("[".repeat(maximumDepth + 1) + "0" + "]".repeat(maximumDepth + 1)).toByteArray(StandardCharsets.UTF_8)
            "escaped-lone-high-surrogate" -> "[\"\\ud800\"]".toByteArray(StandardCharsets.UTF_8)
            "invalid-utf8-in-json-string" -> byteArrayOf(0x5b, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x5d)
            else -> error("Unknown decoded payload construction $construction")
        }
    }

    private fun numericArray(count: Int): ByteArray =
        buildString(count * 2 + 1) {
            append('[')
            repeat(count) { index ->
                if (index > 0) append(',')
                append('0')
            }
            append(']')
        }.toByteArray(StandardCharsets.UTF_8)

    private fun classifyInvalidation(id: String): String =
        when (id) {
            in PURGE_INVALIDATIONS -> "purge"
            in BLOCK_SEND_INVALIDATIONS -> "preserve-and-block-send"
            in PRESERVE_INVALIDATIONS -> "preserve"
            else -> error("Unknown invalidation vector $id")
        }

    private fun classifyGeneration(vector: JSONObject): String =
        when {
            !vector.getBoolean("currentGenerationSupported") -> "purge-and-fail-closed"
            vector.getString("currentGeneration") != vector.getString("queuedGeneration") -> "purge-before-send"
            else -> "preserve-and-send"
        }

    private fun selectScheduledHead(
        rows: JSONArray,
        now: Long,
    ): JSONObject? {
        val heads =
            rows.objects()
                .groupBy { it.getString("replayId") }
                .values
                .mapNotNull { replayRows ->
                    replayRows.minWithOrNull(
                        compareBy<JSONObject> { it.getLong("sequence") }
                            .thenBy { it.getLong("globalOrdinal") },
                    )
                }
        return heads
            .filter { it.getLong("eligibleAt") <= now }
            .minByOrNull { it.getLong("globalOrdinal") }
    }

    private fun resolveReplayIdentity(
        initial: ReplayIdentity,
        candidate: ReplayIdentity,
    ): ReplayIdentityResolution {
        val requestCollision = initial.siteId == candidate.siteId && initial.requestId == candidate.requestId
        if (requestCollision && initial.canonicalRequestSha256 != candidate.canonicalRequestSha256) {
            return ReplayIdentityResolution("permanent-conflict", "request")
        }
        val chunkCollision =
            initial.siteId == candidate.siteId &&
                initial.replayId == candidate.replayId &&
                initial.chunkId == candidate.chunkId
        if (
            chunkCollision &&
            (
                initial.requestId != candidate.requestId ||
                    initial.canonicalRequestSha256 != candidate.canonicalRequestSha256 ||
                    initial.sequence != candidate.sequence
            )
        ) {
            return ReplayIdentityResolution("permanent-conflict", "chunk")
        }
        val sequenceCollision =
            initial.siteId == candidate.siteId &&
                initial.replayId == candidate.replayId &&
                initial.sequence == candidate.sequence
        if (
            sequenceCollision &&
            (
                initial.requestId != candidate.requestId ||
                    initial.canonicalRequestSha256 != candidate.canonicalRequestSha256 ||
                    initial.chunkId != candidate.chunkId
            )
        ) {
            return ReplayIdentityResolution("permanent-conflict", "sequence")
        }
        return if (requestCollision && chunkCollision && sequenceCollision) {
            ReplayIdentityResolution("same-stored-effective-ack")
        } else {
            ReplayIdentityResolution("accepted-new-site-scope")
        }
    }

    private fun mutate(request: JSONObject, vector: JSONObject): JSONObject {
        val copy = JSONObject(request.toString())
        val path = vector.getJSONArray("path").strings()
        var owner = copy
        path.dropLast(1).forEach { segment -> owner = owner.getJSONObject(segment) }
        val key = path.last()
        if (vector.getString("operation") == "remove") owner.remove(key) else owner.put(key, vector.get("value"))
        return copy
    }

    private fun repositoryRoot(): Path {
        var path = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.isRegularFile(path.resolve("settings.gradle.kts"))) {
            path = checkNotNull(path.parent) { "Unable to locate repository root" }
        }
        return path
    }

    private fun json(path: String): JSONObject = JSONObject(resourceText(path))

    private fun resourceText(path: String): String = String(resourceBytes(path), StandardCharsets.UTF_8)

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing test resource $path" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        val CHECKSUM_LINE = Regex("^([a-f0-9]{64})  ([A-Za-z0-9./_-]+)$")
        val FIXTURE_SCHEMAS =
            linkedMapOf(
                "fixtures/config-enabled.json" to "schemas/config.schema.json",
                "fixtures/config-disabled.json" to "schemas/config.schema.json",
                "fixtures/replay.json" to "schemas/replay.schema.json",
                "fixtures/replay-request.json" to "schemas/replay-request.schema.json",
                "fixtures/replay-ack.json" to "schemas/replay-ack.schema.json",
                "fixtures/replay-error-identity-conflict.json" to "schemas/replay-error.schema.json",
            )
        val EXPECTED_NORMATIVE_FILES =
            linkedSetOf(
                "fixtures/config-disabled.json",
                "fixtures/config-enabled.json",
                "fixtures/replay-ack.json",
                "fixtures/replay-error-identity-conflict.json",
                "fixtures/replay-request.json",
                "fixtures/replay.json",
                "manifest.json",
                "readback-expectations.json",
                "README.md",
                "schemas/config.schema.json",
                "schemas/replay-ack.schema.json",
                "schemas/replay-error.schema.json",
                "schemas/replay-request.schema.json",
                "schemas/replay.schema.json",
                "schemas/version.schema.json",
                "test-vectors/replay-activity.json",
            )
        const val MAXIMUM_SAFE_INTEGER = 9_007_199_254_740_991L
        const val MAXIMUM_FULL_CANONICAL_INSPECTION_BYTES = 1_000_000
        const val V1_CANONICAL_MAXIMUM_NESTING = 64
        val INVALID_BASE64_CHARACTER = Regex("[^A-Za-z0-9+/=]")
        val CANONICAL_BASE64 = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
        val GZIP_MAGIC_FIRST = 0x1f.toByte()
        val GZIP_MAGIC_SECOND = 0x8b.toByte()
        val GZIP_DEFLATE_METHOD = 8.toByte()
        const val GZIP_MINIMUM_BYTES = 18
        const val GZIP_FIXED_HEADER_BYTES = 10
        const val GZIP_TRAILER_BYTES = 8
        const val GZIP_RESERVED_FLAGS = 0xe0
        const val GZIP_FLAG_HEADER_CRC = 0x02
        const val GZIP_FLAG_EXTRA = 0x04
        const val GZIP_FLAG_NAME = 0x08
        const val GZIP_FLAG_COMMENT = 0x10
        const val UINT32_MASK = 0xffff_ffffL
        val PURGE_INVALIDATIONS =
            setOf(
                "replay-disabled",
                "integration-revoked",
                "identity-opted-out",
                "region-block",
                "region-unknown",
                "masking-profile-stricter",
                "masking-profile-incomparable",
                "transport-pair-removed",
            )
        val BLOCK_SEND_INVALIDATIONS = setOf("config-expired", "config-fetch-failed", "wall-clock-untrusted")
        val PRESERVE_INVALIDATIONS =
            setOf(
                "masking-profile-equal",
                "masking-profile-looser",
                "context-revision-changed",
                "sampling-changed",
                "budget-decremented",
                "identity-reset-sealed-row",
            )
    }

    private data class CodecInspection(
        val decodedBytes: Int,
        val logicalRecords: Int,
        val nestingDepth: Int,
    )

    private data class ReplayIdentity(
        val siteId: String,
        val requestId: String,
        val canonicalRequestSha256: String,
        val replayId: String,
        val chunkId: String,
        val sequence: Long,
    ) {
        fun withOverrides(overrides: JSONObject): ReplayIdentity =
            copy(
                siteId = overrides.optString("siteId", siteId),
                requestId = overrides.optString("requestId", requestId),
                canonicalRequestSha256 = overrides.optString("canonicalRequestSha256", canonicalRequestSha256),
                replayId = overrides.optString("replayId", replayId),
                chunkId = overrides.optString("chunkId", chunkId),
                sequence = if (overrides.has("sequence")) overrides.getLong("sequence") else sequence,
            )

        companion object {
            fun from(value: JSONObject): ReplayIdentity =
                ReplayIdentity(
                    siteId = value.getString("siteId"),
                    requestId = value.getString("requestId"),
                    canonicalRequestSha256 = value.getString("canonicalRequestSha256"),
                    replayId = value.getString("replayId"),
                    chunkId = value.getString("chunkId"),
                    sequence = value.getLong("sequence"),
                )
        }
    }

    private data class ReplayIdentityResolution(
        val result: String,
        val conflictScope: String? = null,
    )

    private class PayloadAdmissionFailure(val code: String) : IllegalArgumentException(code)
}

private fun JSONArray.strings(): List<String> = List(length()) { index -> getString(index) }

private fun JSONArray.objects(): List<JSONObject> = List(length()) { index -> getJSONObject(index) }
