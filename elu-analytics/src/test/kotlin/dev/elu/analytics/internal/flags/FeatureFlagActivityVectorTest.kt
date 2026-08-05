package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.V1FlagAuthorizationResolution
import dev.elu.analytics.internal.config.V1ConfigManager
import dev.elu.analytics.internal.config.V1FlagProjectionRejection
import dev.elu.analytics.internal.config.V1PreparedFlagDecision
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.runtime.FakeRuntimeQueueBacking
import dev.elu.analytics.internal.runtime.RUNTIME_FLAG_AUTHORITY_KEY
import dev.elu.analytics.internal.runtime.RUNTIME_FLAG_CACHE_METADATA_KEY
import dev.elu.analytics.internal.runtime.RuntimeAppendResult
import dev.elu.analytics.internal.runtime.RuntimeFlagStoredRow
import dev.elu.analytics.internal.runtime.RuntimeMutationChange
import dev.elu.analytics.internal.runtime.RuntimePlatform
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeRecordDraft
import dev.elu.analytics.internal.runtime.RuntimeVersionComponent
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FeatureFlagActivityVectorTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val ownerIds = AtomicInteger()
    private val vector by lazy { jsonResource(VECTOR_PATH) }

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        owners.asReversed().forEach { owner -> runCatching { owner.closeAsync().await() } }
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test
    fun `shared canonical and fatal byte cases execute exactly`() {
        assertEquals(1, vector.getInt("schemaVersion"))
        assertEquals("elu-feature-flag-activity-v1", vector.getString("vectorId"))
        val canonicalCases = vector.getJSONObject("canonicalization").getJSONArray("cases")
        repeat(canonicalCases.length()) { index ->
            val case = canonicalCases.getJSONObject(index)
            val raw = case.getString("raw").toByteArray(StandardCharsets.UTF_8)
            if (case.optString("expect") == "reject") {
                assertProtocolRejected(case.getString("id")) { FlagJson.parse(raw) }
            } else {
                val expected = Base64.getDecoder().decode(case.getString("expectedCanonicalBase64"))
                assertArrayEquals(case.getString("id"), expected, FlagJson.canonicalBytes(FlagJson.parse(raw)))
            }
        }
        val invalidUtf8 = vector.getJSONArray("invalidUtf8Cases")
        repeat(invalidUtf8.length()) { index ->
            val case = invalidUtf8.getJSONObject(index)
            assertProtocolRejected(case.getString("id")) {
                FlagJson.parse(Base64.getDecoder().decode(case.getString("rawBase64")))
            }
        }
    }

    @Test
    fun `shared request oracle includes the complete durable witness`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        owner.ensureFeatureFlagRuntime().await()
        val allowed = owner.applyFeatureFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:00.000Z")).await()
        assertTrue(allowed is V1FlagAuthorizationResolution.Allowed)

        val begun =
            owner.beginFeatureFlagReload(
                browserVersions(),
                "flags_request_1",
                "store_epoch_1",
                millis("2026-08-04T00:01:01.000Z"),
            ).await() as FlagBeginResult.Begun
        val oracle = vector.getJSONObject("requestOracle")

        assertArrayEquals(Base64.getDecoder().decode(oracle.getString("canonicalBase64")), begun.request.request.canonicalBytes)
        assertEquals(oracle.getString("canonicalSha256"), FlagJson.sha256(begun.request.request.canonicalBytes))
        assertEquals(1L, begun.request.token.requestGeneration)
        assertEquals(1L, begun.request.token.barrierGeneration)
    }

    @Test
    fun `shared operational scenarios are executed from every vector step and expectation`() {
        val scenarios = vector.getJSONArray("scenarios").objects()
        val ids = scenarios.map { it.getString("id") }.toSet()
        assertEquals(
            setOf(
                "install-and-read-complete-snapshot",
                "empty-snapshot-replaces",
                "context-change-drops-response",
                "older-completion-cannot-overwrite-newer-begin",
                "cache-expiry-retains-config-barrier",
                "config-expiry-is-durable-restriction",
                "newer-revoke-rejects-old-completion",
                "cache-corruption-rotates-only-request-epoch",
                "authority-corruption-is-terminal",
                "future-schema-is-preserved",
            ),
            ids,
        )

        scenarios.forEach(::executeScenario)
    }

    @Test
    fun `common AST budgets reject one over every local ceiling`() {
        assertProtocolRejected("wire bytes") { FlagJson.parse(ByteArray(FLAG_MAX_WIRE_BYTES + 1) { ' '.code.toByte() }) }
        assertProtocolRejected("collection entries") {
            FlagJson.fromPlatform(List(FLAG_MAX_COLLECTION_ENTRIES + 1) { null })
        }
        assertProtocolRejected("nodes") {
            FlagJson.fromPlatform(List(FLAG_MAX_COLLECTION_ENTRIES) { listOf(null, null, null) })
        }
        assertProtocolRejected("key scalars") {
            FlagJson.fromPlatform(mapOf("k".repeat(FLAG_MAX_KEY_SCALARS + 1) to true))
        }
        assertProtocolRejected("string scalars") {
            FlagJson.fromPlatform("x".repeat(FLAG_MAX_STRING_SCALARS + 1))
        }
        var nested: Any? = null
        repeat(FLAG_MAX_DEPTH + 1) { nested = listOf(nested) }
        assertProtocolRejected("depth") { FlagJson.fromPlatform(nested) }
    }

    @Test
    fun `normalized unsafe integer spellings are rejected on wire and platform`() {
        assertProtocolRejected("unsafe exponent") {
            FlagJson.parse("9.007199254740992e15".toByteArray(StandardCharsets.UTF_8))
        }
        assertProtocolRejected("unsafe decimal integer") {
            FlagJson.parse("9007199254740992.0".toByteArray(StandardCharsets.UTF_8))
        }
        assertProtocolRejected("unsafe platform double") {
            FlagJson.fromPlatform(9.007199254740992E15)
        }
        assertProtocolRejected("unsafe platform decimal") {
            FlagJson.fromPlatform(BigDecimal("9007199254740992.0"))
        }
        assertEquals(
            "9007199254740991",
            FlagJson.canonicalString(
                FlagJson.parse("9.007199254740991e15".toByteArray(StandardCharsets.UTF_8)),
            ),
        )
    }

    @Test
    fun `serialized injected client coalesces one same-witness transport and final-CASes`() {
        val owner = open(FakeRuntimeQueueBacking())
        val clock = MutableFlagClock(millis("2026-08-04T00:01:00.000Z"), 1_000_000_000L)
        val sent = AtomicInteger()
        val request = AtomicReference<FlagTransportRequest>()
        val entered = CountDownLatch(1)
        val response = CompletableFuture<ByteArray>()
        val client =
            AndroidFeatureFlagClient(
                owner,
                browserVersions(),
                FlagTransport { outbound ->
                    request.set(outbound)
                    sent.incrementAndGet()
                    entered.countDown()
                    response
                },
                clock,
                FlagOpaqueIdSource { "flags_request_1" },
                FlagOpaqueIdSource { "store_epoch_1" },
            )
        try {
            assertTrue(client.applyConfiguration(configAllowed()).get(10, TimeUnit.SECONDS) is V1FlagAuthorizationResolution.Allowed)
            clock.set(millis("2026-08-04T00:01:01.000Z"), 2_000_000_000L)
            val first = client.reload()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second = client.reload()
            clock.set(millis("2026-08-04T00:01:02.000Z"), 3_000_000_000L)
            response.complete(responseMixed())

            assertTrue(first.get(10, TimeUnit.SECONDS) is FlagReloadResult.Updated)
            assertTrue(second.get(10, TimeUnit.SECONDS) is FlagReloadResult.Updated)
            assertEquals(1, sent.get())
            assertEquals("https://ingest.elu.dev/v1/flags", request.get().endpoint.toString())
            assertEquals(vector.getJSONObject("requestOracle").getString("canonicalSha256"), FlagJson.sha256(request.get().canonicalBody))
        } finally {
            client.close()
        }
    }

    @Test
    fun `configuration queued behind close is stale and cannot mutate durable authority`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        val clock = MutableFlagClock(millis("2026-08-04T00:01:00.000Z"), 1_000_000_000L)
        val client =
            AndroidFeatureFlagClient(
                owner,
                browserVersions(),
                FlagTransport { CompletableFuture() },
                clock,
                FlagOpaqueIdSource { "unused_closed_request" },
                FlagOpaqueIdSource { "unused_closed_epoch" },
            )
        try {
            assertTrue(
                client.applyConfiguration(configAllowed()).get(10, TimeUnit.SECONDS) is
                    V1FlagAuthorizationResolution.Allowed,
            )
            val rowsBefore =
                backing.flagRows.mapValues { (_, row) ->
                    row.copy(payload = row.payload.copyOf())
                }
            val mutationBefore = backing.committedMutationGeneration
            val lane =
                AndroidFeatureFlagClient::class.java
                    .getDeclaredField("lane")
                    .apply { isAccessible = true }
                    .get(client) as ExecutorService
            val blockerEntered = CountDownLatch(1)
            val releaseBlocker = CountDownLatch(1)
            lane.execute {
                blockerEntered.countDown()
                assertTrue(releaseBlocker.await(10, TimeUnit.SECONDS))
            }
            assertTrue(blockerEntered.await(10, TimeUnit.SECONDS))

            // Both close calls and the candidate are accepted while the lane is blocked. FIFO
            // ordering makes the first close latch `closed` before the queued candidate runs.
            client.close()
            client.close()
            val afterClose = client.applyConfiguration(configNewerRevoked())
            releaseBlocker.countDown()

            assertEquals(
                V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.STALE),
                afterClose.get(10, TimeUnit.SECONDS),
            )
            assertEquals(mutationBefore, backing.committedMutationGeneration)
            assertEquals(rowsBefore.keys, backing.flagRows.keys)
            rowsBefore.forEach { (key, expected) ->
                val actual = backing.flagRows.getValue(key)
                assertEquals(expected.storageSchemaVersion, actual.storageSchemaVersion)
                assertArrayEquals(expected.payload, actual.payload)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `client config lease equality commits durable expiry before returning a miss`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        val clock = MutableFlagClock(millis("2026-08-04T00:01:00.000Z"), 1_000_000_000L)
        val transportEntered = CountDownLatch(1)
        val transportResponse = CompletableFuture<ByteArray>()
        val client =
            AndroidFeatureFlagClient(
                owner,
                browserVersions(),
                FlagTransport {
                    transportEntered.countDown()
                    transportResponse
                },
                clock,
                FlagOpaqueIdSource { "flags_request_expiry" },
                FlagOpaqueIdSource { "store_epoch_expiry" },
            )
        try {
            assertTrue(client.applyConfiguration(configAllowed()).get(10, TimeUnit.SECONDS) is V1FlagAuthorizationResolution.Allowed)
            clock.set(millis("2026-08-04T00:01:01.000Z"), 2_000_000_000L)
            val reload = client.reload()
            assertTrue(transportEntered.await(10, TimeUnit.SECONDS))
            assertNotNull(backing.flagRows[RUNTIME_FLAG_CACHE_METADATA_KEY])

            // The fixed deadline is apply monotonic time + the exact remaining config lifetime.
            clock.set(millis("2026-08-04T00:05:00.000Z"), 241_000_000_000L)
            assertTrue(client.read("variant").get(10, TimeUnit.SECONDS) is FlagReadResult.Missing)

            val authority =
                JSONObject(
                    String(
                        backing.flagRows.getValue(RUNTIME_FLAG_AUTHORITY_KEY).payload,
                        StandardCharsets.UTF_8,
                    ),
                )
            assertEquals(2L, authority.getLong("barrierGeneration"))
            assertTrue(authority.isNull("allowedAuthorization"))
            assertEquals("EXPIRED", authority.getString("restriction"))
            assertEquals(millis("2026-08-04T00:05:00.000Z"), authority.getLong("lastObservedWall"))
            assertFalse(backing.flagRows.containsKey(RUNTIME_FLAG_CACHE_METADATA_KEY))

            assertTrue(reload.get(10, TimeUnit.SECONDS) is FlagReloadResult.Stale)
        } finally {
            client.close()
        }
    }

    @Test
    fun `client cache lease equality advances request generation even when wall lags`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        val clock = MutableFlagClock(millis("2026-08-04T00:01:00.000Z"), 1_000_000_000L)
        val entered = CountDownLatch(1)
        val transportResponse = CompletableFuture<ByteArray>()
        val client =
            AndroidFeatureFlagClient(
                owner,
                browserVersions(),
                FlagTransport {
                    entered.countDown()
                    transportResponse
                },
                clock,
                FlagOpaqueIdSource { "flags_request_1" },
                FlagOpaqueIdSource { "store_epoch_1" },
            )
        try {
            assertTrue(client.applyConfiguration(configAllowed()).get(10, TimeUnit.SECONDS) is V1FlagAuthorizationResolution.Allowed)
            clock.set(millis("2026-08-04T00:01:01.000Z"), 2_000_000_000L)
            val reload = client.reload()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            clock.set(millis("2026-08-04T00:01:02.000Z"), 3_000_000_000L)
            transportResponse.complete(responseMixed())
            assertTrue(reload.get(10, TimeUnit.SECONDS) is FlagReloadResult.Updated)

            // The response deadline is 178 seconds after commit. Wall deliberately advances only
            // one second; the monotonic ceiling is still authoritative and must be persisted.
            clock.set(millis("2026-08-04T00:01:03.000Z"), 181_000_000_000L)
            assertTrue(client.read("variant").get(10, TimeUnit.SECONDS) is FlagReadResult.Missing)

            val authority =
                JSONObject(String(backing.flagRows.getValue(RUNTIME_FLAG_AUTHORITY_KEY).payload, StandardCharsets.UTF_8))
            assertEquals(1L, authority.getLong("barrierGeneration"))
            assertFalse(authority.isNull("allowedAuthorization"))
            assertEquals(millis("2026-08-04T00:01:03.000Z"), authority.getLong("lastObservedWall"))
            val metadata =
                JSONObject(String(backing.flagRows.getValue(RUNTIME_FLAG_CACHE_METADATA_KEY).payload, StandardCharsets.UTF_8))
            assertEquals(2L, metadata.getLong("requestGeneration"))
            assertTrue(metadata.isNull("activeRequest"))
            assertTrue(metadata.isNull("cache"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `barrier safe integer ceiling latches terminal without wrapping`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = configuredOwner(backing)
        val authorityRow = backing.flagRows.getValue(RUNTIME_FLAG_AUTHORITY_KEY)
        val authority = JSONObject(String(authorityRow.payload, StandardCharsets.UTF_8))
        authority.put("barrierGeneration", FLAG_MAX_SAFE_INTEGER)
        backing.flagRows[RUNTIME_FLAG_AUTHORITY_KEY] =
            authorityRow.copy(payload = authority.toString().toByteArray(StandardCharsets.UTF_8))

        val result = owner.applyFeatureFlagConfiguration(configNewerRevoked(), millis("2026-08-04T00:02:00.000Z")).await()
        assertEquals(
            V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.TERMINAL),
            result,
        )
        val terminal =
            JSONObject(
                String(
                    backing.flagRows.getValue(RUNTIME_FLAG_AUTHORITY_KEY).payload,
                    StandardCharsets.UTF_8,
                ),
            )
        assertEquals(FLAG_MAX_SAFE_INTEGER, terminal.getLong("barrierGeneration"))
        assertTrue(terminal.getBoolean("terminal"))
        assertEquals("TERMINAL", terminal.getString("restriction"))
        assertTrue(terminal.isNull("allowedAuthorization"))
    }

    @Test
    fun `exact persisted site ownership rejects a colliding namespace owner`() {
        val backing = FakeRuntimeQueueBacking()
        val ownerA = open(backing, "elu_pk_test_flags")
        ownerA.ensureFeatureFlagRuntime().await()
        assertTrue(
            ownerA.applyFeatureFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:00.000Z")).await() is
                V1FlagAuthorizationResolution.Allowed,
        )

        val ownerB = open(backing, "elu_pk_other_flags")
        ownerB.ensureFeatureFlagRuntime().await()
        val rejected =
            ownerB.applyFeatureFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:01.000Z")).await()
                as V1FlagAuthorizationResolution.Restricted
        assertEquals(V1FlagProjectionRejection.UNAUTHORIZED, rejected.reason)
        val authority = authorityJson(backing)
        assertEquals("elu_pk_test_flags", authority.getString("trustedSiteKey"))
        assertEquals(
            vector.getJSONObject("constants").getString("ownerNamespaceDigest"),
            authority.getString("siteNamespaceDigest"),
        )
        assertEquals("site_demo", authority.getString("siteId"))
    }

    @Test
    fun `flags projection accepts a strict document with unrelated channel fields absent`() {
        val owner = open(FakeRuntimeQueueBacking())
        owner.ensureFeatureFlagRuntime().await()
        val config =
            JSONObject(configAllowed()).also { root ->
                root.put("endpoints", JSONObject().put("flags", "https://ingest.elu.dev/v1/flags"))
                root.put("features", JSONObject().put("flags", true))
                root.remove("privacy")
                root.remove("capabilities")
                root.remove("session")
                root.remove("limits")
            }.toString()
        assertTrue(
            owner.applyFeatureFlagConfiguration(config, millis("2026-08-04T00:01:00.000Z")).await() is
                V1FlagAuthorizationResolution.Allowed,
        )
    }

    @Test
    fun `flags projection strictly validates inactive shape and never versions malformed candidates`() {
        fun assertUnversionedMalformed(label: String, candidate: JSONObject) {
            val backing = FakeRuntimeQueueBacking()
            val owner = configuredOwner(backing)
            val retainedBoundary = authorityJson(backing).getJSONObject("newestBoundary").toString()
            val resolution =
                owner.applyFeatureFlagConfiguration(
                    candidate.toString(),
                    millis("2026-08-04T00:03:00.000Z"),
                ).await()
            assertEquals(
                "$label resolution",
                V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.MALFORMED),
                resolution,
            )
            assertEquals(
                "$label high-water",
                retainedBoundary,
                authorityJson(backing).getJSONObject("newestBoundary").toString(),
            )
        }

        fun inactive(status: String): JSONObject =
            JSONObject(resourceText("contracts/v1/fixtures/config-disabled.json"))
                .put("status", status)
                .put("revision", "config-$status-newer")
                .put("issuedAt", "2026-08-04T00:02:00.000Z")
                .put("expiresAt", "2026-08-04T00:10:00.000Z")

        val enabled = JSONObject(configAllowed())
        listOf("disabled", "revoked").forEach { status ->
            assertUnversionedMalformed(
                "$status missing reason",
                inactive(status).also { it.remove("reason") },
            )
            listOf("site", "endpoints").forEach { forbiddenField ->
                assertUnversionedMalformed(
                    "$status retains $forbiddenField",
                    inactive(status).put(
                        forbiddenField,
                        JSONObject(enabled.getJSONObject(forbiddenField).toString()),
                    ),
                )
            }
        }

        listOf("enabled", "disabled", "revoked").forEach { status ->
            val base = if (status == "enabled") JSONObject(configAllowed()) else inactive(status)
            assertUnversionedMalformed(
                "$status reason type",
                JSONObject(base.toString()).put("reason", 7),
            )
            assertUnversionedMalformed(
                "$status reason length",
                JSONObject(base.toString()).put("reason", "x".repeat(257)),
            )
        }
    }

    @Test
    fun `future inner metadata and orphan body envelopes are byte preserved before v1 budgets`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = configuredOwner(backing)
        var deep = "true"
        repeat(FLAG_MAX_DEPTH + 50) { deep = "[$deep]" }
        val futureMetadata = "{\"schemaVersion\":2,\"deep\":$deep}".toByteArray(StandardCharsets.UTF_8)
        backing.flagRows[RUNTIME_FLAG_CACHE_METADATA_KEY] =
            RuntimeFlagStoredRow(RUNTIME_FLAG_CACHE_METADATA_KEY, 1, futureMetadata.copyOf())
        assertTrue(
            owner.beginFeatureFlagReload(
                browserVersions(),
                "future_metadata",
                "future_epoch",
                millis("2026-08-04T00:01:01.000Z"),
            ).await() is FlagBeginResult.Terminal,
        )
        assertArrayEquals(futureMetadata, backing.flagRows.getValue(RUNTIME_FLAG_CACHE_METADATA_KEY).payload)

        backing.flagRows.remove(RUNTIME_FLAG_CACHE_METADATA_KEY)
        val futureBody = "{\"schemaVersion\":2,\"deep\":$deep}".toByteArray(StandardCharsets.UTF_8)
        backing.flagRows["cache-body:future:0000"] = RuntimeFlagStoredRow("cache-body:future:0000", 1, futureBody.copyOf())
        assertTrue(
            owner.beginFeatureFlagReload(
                browserVersions(),
                "future_body",
                "future_epoch",
                millis("2026-08-04T00:01:02.000Z"),
            ).await() is FlagBeginResult.Terminal,
        )
        assertArrayEquals(futureBody, backing.flagRows.getValue("cache-body:future:0000").payload)
    }

    @Test
    fun `future cache schema after the first body chunk is preserved on config replacement`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = seededOwner(backing)
        val metadataRow = backing.flagRows.getValue(RUNTIME_FLAG_CACHE_METADATA_KEY)
        val metadata = JSONObject(String(metadataRow.payload, StandardCharsets.UTF_8))
        val pointer = metadata.getJSONObject("cache")
        val recordId = pointer.getString("recordId")
        val futureBody =
            ("{\"future\":\"" + "x".repeat(FlagDurableStore.MAX_CHUNK_BYTES + 128) + "\",\"schemaVersion\":2}")
                .toByteArray(StandardCharsets.UTF_8)
        assertTrue(
            String(futureBody, StandardCharsets.UTF_8).indexOf("\"schemaVersion\"") >
                FlagDurableStore.MAX_CHUNK_BYTES,
        )
        backing.flagRows.keys.filter { it.startsWith("cache-body:") }.toList().forEach(backing.flagRows::remove)
        val chunks =
            futureBody.indices
                .step(FlagDurableStore.MAX_CHUNK_BYTES)
                .map { offset ->
                    futureBody.copyOfRange(
                        offset,
                        minOf(futureBody.size, offset + FlagDurableStore.MAX_CHUNK_BYTES),
                    )
                }
        chunks.forEachIndexed { index, bytes ->
            val key = "cache-body:$recordId:${index.toString().padStart(4, '0')}"
            backing.flagRows[key] = RuntimeFlagStoredRow(key, 1, bytes.copyOf())
        }
        pointer
            .put("bodyLength", futureBody.size)
            .put("bodySha256", FlagJson.sha256(futureBody))
            .put("chunkCount", chunks.size)
        val futureMetadata = metadata.toString().toByteArray(StandardCharsets.UTF_8)
        backing.flagRows[metadataRow.key] = metadataRow.copy(payload = futureMetadata.copyOf())

        val newerConfig =
            JSONObject(configAllowed())
                .put("revision", "config-future-cache-newer")
                .put("issuedAt", "2026-08-04T00:02:00.000Z")
                .put("expiresAt", "2026-08-04T00:10:00.000Z")
                .toString()
        assertEquals(
            V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.TERMINAL),
            owner.applyFeatureFlagConfiguration(newerConfig, millis("2026-08-04T00:03:00.000Z")).await(),
        )
        assertArrayEquals(futureMetadata, backing.flagRows.getValue(metadataRow.key).payload)
        chunks.forEachIndexed { index, bytes ->
            val key = "cache-body:$recordId:${index.toString().padStart(4, '0')}"
            assertArrayEquals(bytes, backing.flagRows.getValue(key).payload)
        }
    }

    @Test
    fun `grouped future body survives missing or corrupt metadata beyond the v1 ceiling`() {
        listOf(false, true).forEach { corruptMetadata ->
            val backing = FakeRuntimeQueueBacking()
            val owner = configuredOwner(backing)
            val recordId = if (corruptMetadata) "future-corrupt-metadata" else "future-missing-metadata"
            val chunks = futureBodyChunksCrossingV1Ceiling()
            val bodyRows =
                chunks.mapIndexed { index, bytes ->
                    val key = "cache-body:$recordId:${index.toString().padStart(4, '0')}"
                    RuntimeFlagStoredRow(key, 1, bytes.copyOf())
                }
            bodyRows.forEach { row -> backing.flagRows[row.key] = row.copy(payload = row.payload.copyOf()) }

            val metadataSnapshot =
                if (corruptMetadata) {
                    RuntimeFlagStoredRow(
                        RUNTIME_FLAG_CACHE_METADATA_KEY,
                        1,
                        "{\"schemaVersion\":1,\"storeEpoch\":".toByteArray(StandardCharsets.UTF_8),
                    ).also { row -> backing.flagRows[row.key] = row.copy(payload = row.payload.copyOf()) }
                } else {
                    null
                }
            val mutationBefore = backing.committedMutationGeneration
            val newerConfig =
                JSONObject(configAllowed())
                    .put("revision", "config-grouped-future-${if (corruptMetadata) "corrupt" else "missing"}")
                    .put("issuedAt", "2026-08-04T00:02:00.000Z")
                    .put("expiresAt", "2026-08-04T00:10:00.000Z")
                    .toString()

            assertEquals(
                V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.TERMINAL),
                owner.applyFeatureFlagConfiguration(newerConfig, millis("2026-08-04T00:03:00.000Z")).await(),
            )
            assertEquals(mutationBefore, backing.committedMutationGeneration)
            metadataSnapshot?.let { expected ->
                val actual = backing.flagRows.getValue(expected.key)
                assertEquals(expected.storageSchemaVersion, actual.storageSchemaVersion)
                assertArrayEquals(expected.payload, actual.payload)
            }
            bodyRows.forEach { expected ->
                val actual = backing.flagRows.getValue(expected.key)
                assertEquals(expected.storageSchemaVersion, actual.storageSchemaVersion)
                assertArrayEquals(expected.payload, actual.payload)
            }
        }
    }

    @Test
    fun `authority codec rejects terminal reason in a nonterminal union`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = configuredOwner(backing)
        val row = backing.flagRows.getValue(RUNTIME_FLAG_AUTHORITY_KEY)
        val incoherent = JSONObject(String(row.payload, StandardCharsets.UTF_8))
            .put("terminal", false)
            .put("allowedAuthorization", JSONObject.NULL)
            .put("restriction", "TERMINAL")
        backing.flagRows[row.key] = row.copy(payload = incoherent.toString().toByteArray(StandardCharsets.UTF_8))
        val mutationBefore = backing.committedMutationGeneration
        assertTrue(
            owner.beginFeatureFlagReload(
                browserVersions(),
                "terminal_union",
                "terminal_epoch",
                millis("2026-08-04T00:01:01.000Z"),
            ).await() is FlagBeginResult.Terminal,
        )
        assertEquals(mutationBefore, backing.committedMutationGeneration)
    }

    @Test
    fun `durable wall rollback permanently poisons the owner lifetime`() {
        val owner = configuredOwner()
        val rollback =
            owner.applyFeatureFlagConfiguration(configAllowed(), millis("2026-08-04T00:00:59.000Z")).await()
                as V1FlagAuthorizationResolution.Restricted
        assertEquals(V1FlagProjectionRejection.STORAGE, rollback.reason)
        val later =
            owner.applyFeatureFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:01.000Z")).await()
                as V1FlagAuthorizationResolution.Restricted
        assertEquals(V1FlagProjectionRejection.STORAGE, later.reason)
    }

    @Test
    fun `pre-send authorization observes a core mutation and suppresses transport`() {
        val owner = open(FakeRuntimeQueueBacking())
        val sent = AtomicInteger()
        val calls = AtomicInteger()
        val clock =
            object : FlagClock {
                override fun wallNowEpochMillis(): Long {
                    if (calls.incrementAndGet() == 4) {
                        owner.appendMutations(
                            listOf(
                                RuntimeRecordDraft.Mutation(
                                    "2026-08-04T00:01:01.000Z",
                                    RuntimeMutationChange.SetPersonProperties(
                                        mapOf("plan" to "enterprise"),
                                        emptyMap(),
                                        emptyList(),
                                    ),
                                    browserVersions(),
                                ),
                            ),
                        ).await()
                    }
                    return millis("2026-08-04T00:01:00.000Z")
                }

                override fun monotonicNowNanos(): Long = calls.get().toLong() * 1_000_000_000L
            }
        val client =
            AndroidFeatureFlagClient(
                owner,
                browserVersions(),
                FlagTransport {
                    sent.incrementAndGet()
                    CompletableFuture.completedFuture(responseMixed())
                },
                clock,
                FlagOpaqueIdSource { "pre_send_request" },
                FlagOpaqueIdSource { "pre_send_epoch" },
            )
        try {
            assertTrue(client.applyConfiguration(configAllowed()).await() is V1FlagAuthorizationResolution.Allowed)
            assertTrue(client.reload().await() is FlagReloadResult.Stale)
            assertEquals(0, sent.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun `newer witness logically cancels callers while noncooperative transport retains its slot`() {
        val owner = open(FakeRuntimeQueueBacking())
        val entered = CountDownLatch(1)
        val sent = AtomicInteger()
        val never = CompletableFuture<ByteArray>()
        val clock = MutableFlagClock(millis("2026-08-04T00:01:00.000Z"), 1_000_000_000L)
        val client =
            AndroidFeatureFlagClient(
                owner,
                browserVersions(),
                FlagTransport {
                    sent.incrementAndGet()
                    entered.countDown()
                    never
                },
                clock,
                FlagOpaqueIdSource { "noncooperative_request" },
                FlagOpaqueIdSource { "noncooperative_epoch" },
            )
        try {
            assertTrue(client.applyConfiguration(configAllowed()).await() is V1FlagAuthorizationResolution.Allowed)
            val first = client.reload()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            owner.appendMutations(
                listOf(
                    RuntimeRecordDraft.Mutation(
                        "2026-08-04T00:01:01.000Z",
                        RuntimeMutationChange.SetPersonProperties(mapOf("role" to "admin"), emptyMap(), emptyList()),
                        browserVersions(),
                    ),
                ),
            ).await()
            clock.set(millis("2026-08-04T00:01:01.000Z"), 2_000_000_000L)
            val second = client.reload()
            assertTrue(first.get(10, TimeUnit.SECONDS) is FlagReloadResult.Stale)
            assertFalse(second.isDone)
            assertEquals(1, sent.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun `identical config bytes cannot recompute the fixed monotonic lease`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing)
        val clock = MutableFlagClock(millis("2026-08-04T00:01:00.000Z"), 1_000_000_000L)
        val client =
            AndroidFeatureFlagClient(
                owner,
                browserVersions(),
                FlagTransport { CompletableFuture() },
                clock,
                FlagOpaqueIdSource { "unused_request" },
                FlagOpaqueIdSource { "unused_epoch" },
            )
        try {
            val first = client.applyConfiguration(configAllowed()).await() as V1FlagAuthorizationResolution.Allowed
            clock.set(millis("2026-08-04T00:01:30.000Z"), 200_000_000_000L)
            val second = client.applyConfiguration(configAllowed()).await() as V1FlagAuthorizationResolution.Allowed
            assertEquals(first.authorization, second.authorization)
            clock.set(millis("2026-08-04T00:01:31.000Z"), 241_000_000_000L)
            assertTrue(client.read("missing").await() is FlagReadResult.Missing)
            assertEquals("EXPIRED", authorityJson(backing).getString("restriction"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `a valid cache miss installs but never extends the immutable cache lease`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = seededOwner(backing)
        val clock = MutableFlagClock(millis("2026-08-04T00:01:03.000Z"), 1_000_000_000L)
        val client =
            AndroidFeatureFlagClient(
                owner,
                browserVersions(),
                FlagTransport { CompletableFuture() },
                clock,
                FlagOpaqueIdSource { "unused_request" },
                FlagOpaqueIdSource { "unused_epoch" },
            )
        try {
            assertTrue(client.applyConfiguration(configAllowed()).await() is V1FlagAuthorizationResolution.Allowed)
            assertTrue(client.read("missing").await() is FlagReadResult.Missing)
            clock.set(millis("2026-08-04T00:01:30.000Z"), 100_000_000_000L)
            assertTrue(client.read("missing").await() is FlagReadResult.Missing)
            clock.set(millis("2026-08-04T00:01:31.000Z"), 178_000_000_000L)
            assertTrue(client.read("missing").await() is FlagReadResult.Missing)
            val metadata = metadataJson(backing)
            assertEquals(2L, metadata.getLong("requestGeneration"))
            assertTrue(metadata.isNull("activeRequest"))
            assertTrue(metadata.isNull("cache"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `cache lease expires its immutable origin while a newer request is active`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = seededOwner(backing)
        val found = owner.readFlag("variant") as FlagReadResult.Found
        owner.begin("newer_request", "unused_epoch", "2026-08-04T00:01:04.000Z")
        val authorization = checkNotNull(owner.snapshotFeatureFlagReload(browserVersions()).await()).authorization
        assertTrue(
            owner.expireFeatureFlagCache(
                authorization,
                browserVersions(),
                found.cacheLeaseToken,
                millis("2026-08-04T00:01:05.000Z"),
            ).await() is FlagCacheExpiryStoreResult.Expired,
        )
        val metadata = metadataJson(backing)
        assertEquals(3L, metadata.getLong("requestGeneration"))
        assertTrue(metadata.isNull("activeRequest"))
        assertTrue(metadata.isNull("cache"))
    }

    @Test
    fun `pre-send full token CAS rejects active request barrier tampering`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = configuredOwner(backing)
        val begun = owner.begin("full_token", "full_token_epoch", "2026-08-04T00:01:01.000Z")
        val row = backing.flagRows.getValue(RUNTIME_FLAG_CACHE_METADATA_KEY)
        val metadata = JSONObject(String(row.payload, StandardCharsets.UTF_8))
        metadata.getJSONObject("activeRequest").put("barrierGeneration", 2)
        backing.flagRows[row.key] = row.copy(payload = metadata.toString().toByteArray(StandardCharsets.UTF_8))
        assertTrue(
            owner.authorizeFeatureFlagSend(
                begun,
                browserVersions(),
                millis("2026-08-04T00:01:02.000Z"),
            ).await() is FlagPreSendResult.Stale,
        )
    }

    @Test
    fun `owner-local preparation exhaustion reserves max and durably terminals the store`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = configuredOwner(backing)
        val managerField = RuntimeQueueOwner::class.java.getDeclaredField("configManager").apply { isAccessible = true }
        val manager = managerField.get(owner) as V1ConfigManager
        val generationField = V1ConfigManager::class.java.getDeclaredField("flagPreparationGeneration").apply { isAccessible = true }
        generationField.setLong(manager, FLAG_MAX_SAFE_INTEGER - 2)

        val formerlyValid = manager.prepareFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:01.000Z"))
        assertEquals(FLAG_MAX_SAFE_INTEGER - 1, formerlyValid.preparationGeneration)
        assertTrue(formerlyValid.decision is V1PreparedFlagDecision.Allowed)

        val exhausted = manager.prepareFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:02.000Z"))
        assertEquals(FLAG_MAX_SAFE_INTEGER, exhausted.preparationGeneration)
        assertTrue(
            exhausted.decision is V1PreparedFlagDecision.Restricted &&
                exhausted.decision.reason == V1FlagProjectionRejection.TERMINAL,
        )
        assertEquals(
            V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.TERMINAL),
            manager.commitFlagConfiguration(formerlyValid, 1),
        )

        val persisted =
            owner.applyFeatureFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:03.000Z")).await()
        assertEquals(
            V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.TERMINAL),
            persisted,
        )
        val terminal = authorityJson(backing)
        assertTrue(terminal.getBoolean("terminal"))
        assertEquals("TERMINAL", terminal.getString("restriction"))
        assertTrue(terminal.isNull("allowedAuthorization"))

        owner.closeAsync().await()
        val reopened = open(backing)
        reopened.ensureFeatureFlagRuntime().await()
        assertEquals(
            V1FlagAuthorizationResolution.Restricted(V1FlagProjectionRejection.TERMINAL),
            reopened.applyFeatureFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:04.000Z")).await(),
        )
        assertTrue(authorityJson(backing).getBoolean("terminal"))
    }

    /** Interprets the shared activity language; no scenario input or expectation is advisory. */
    private fun executeScenario(scenario: JSONObject) {
        assertObjectKeys(scenario, setOf("id", "steps"), emptySet(), "scenario")
        val backing = FakeRuntimeQueueBacking()
        val state = ScenarioState(backing, open(backing))
        state.owner.ensureFeatureFlagRuntime().await()
        executeScenarioSteps(scenario.getString("id"), scenario.getJSONArray("steps"), state)
    }

    private fun executeScenarioSteps(
        scenarioId: String,
        steps: JSONArray,
        state: ScenarioState,
    ) {
        repeat(steps.length()) { index ->
            val step = steps.getJSONObject(index)
            val path = "$scenarioId.steps[$index]"
            when (val type = step.getString("type")) {
                "seedScenario" -> {
                    assertObjectKeys(step, setOf("type", "scenario"), emptySet(), path)
                    val seedId = step.getString("scenario")
                    val seed =
                        vector.getJSONArray("scenarios").objects().singleOrNull { it.getString("id") == seedId }
                            ?: fail("$path references unknown scenario $seedId").let { error("unreachable") }
                    executeScenarioSteps(seedId, seed.getJSONArray("steps"), state)
                }
                "applyConfig" -> executeApplyConfig(step, path, state)
                "beginReload" -> executeBeginReload(step, path, state)
                "completeReload" -> executeCompleteReload(step, path, state)
                "readAll" -> executeReadAll(step, path, state)
                "setPersonProperties" -> executeSetPersonProperties(step, path, state)
                "injectCacheCorruption" -> executeCacheCorruption(step, path, state)
                "injectAuthorityCorruption" -> executeAuthorityCorruption(step, path, state)
                "injectFutureCacheRecord" -> executeFutureRecord(step, path, state)
                else -> fail("$path has unsupported step type $type")
            }
        }
    }

    private fun executeApplyConfig(step: JSONObject, path: String, state: ScenarioState) {
        assertObjectKeys(step, setOf("type", "config", "wallNow", "expect"), emptySet(), path)
        val expect = step.getJSONObject("expect")
        assertObjectKeys(
            expect,
            setOf("authorization"),
            setOf("barrierGeneration", "lastObservedWall", "reason"),
            "$path.expect",
        )
        val result =
            state.owner.applyFeatureFlagConfiguration(
                documentVariant(step.getString("config")),
                millis(step.getString("wallNow")),
            ).await()
        when (expect.getString("authorization")) {
            "allowed" -> assertTrue("$path expected allowed", result is V1FlagAuthorizationResolution.Allowed)
            "restricted" -> assertTrue("$path expected restricted", result is V1FlagAuthorizationResolution.Restricted)
            else -> fail("$path has unsupported authorization expectation")
        }
        if (expect.has("barrierGeneration")) {
            val actual =
                when (result) {
                    is V1FlagAuthorizationResolution.Allowed -> result.authorization.barrierGeneration
                    is V1FlagAuthorizationResolution.Restricted -> authorityJson(state.backing).getLong("barrierGeneration")
                }
            assertEquals("$path barrier", expect.getLong("barrierGeneration"), actual)
        }
        if (expect.has("lastObservedWall")) {
            assertEquals(
                "$path wall floor",
                millis(expect.getString("lastObservedWall")),
                authorityJson(state.backing).getLong("lastObservedWall"),
            )
        }
        if (expect.has("reason")) {
            val restricted = result as V1FlagAuthorizationResolution.Restricted
            val expected =
                when (expect.getString("reason")) {
                    "wall-rollback" -> V1FlagProjectionRejection.STORAGE
                    else -> fail("$path has unsupported restriction reason").let { V1FlagProjectionRejection.STORAGE }
                }
            assertEquals("$path reason", expected, restricted.reason)
        }
    }

    private fun executeBeginReload(step: JSONObject, path: String, state: ScenarioState) {
        assertObjectKeys(
            step,
            setOf("type", "requestId", "wallNow", "expect"),
            setOf("storeEpoch", "realm"),
            path,
        )
        val expect = step.getJSONObject("expect")
        assertObjectKeys(
            expect,
            emptySet(),
            setOf(
                "result",
                "requestGeneration",
                "barrierGeneration",
                "requestCanonicalSha256",
                "restriction",
                "storeEpoch",
                "authorityPreserved",
                "storageMutation",
                "futureRecordPreservedByteForByte",
                "bodyMaterialized",
            ),
            "$path.expect",
        )
        val authorityBefore = state.backing.flagRows[RUNTIME_FLAG_AUTHORITY_KEY]?.payload?.copyOf()
        val mutationBefore = state.backing.committedMutationGeneration
        val result =
            state.owner.beginFeatureFlagReload(
                browserVersions(),
                step.getString("requestId"),
                step.optString("storeEpoch", vector.getJSONObject("constants").getString("initialStoreEpoch")),
                millis(step.getString("wallNow")),
            ).await()
        val expectedResult = expect.optString("result", "begun")
        when (expectedResult) {
            "begun" -> {
                val begun = (result as FlagBeginResult.Begun).request
                state.begun[step.optString("realm", step.getString("requestId"))] = begun
                expect.optLongOrNull("requestGeneration")?.let { assertEquals("$path request generation", it, begun.token.requestGeneration) }
                expect.optLongOrNull("barrierGeneration")?.let { assertEquals("$path barrier", it, begun.token.barrierGeneration) }
                expect.optStringOrNull("requestCanonicalSha256")?.let {
                    assertEquals("$path request hash", it, FlagJson.sha256(begun.request.canonicalBytes))
                }
                expect.optStringOrNull("storeEpoch")?.let { assertEquals("$path store epoch", it, begun.token.storeEpoch) }
            }
            "restricted" -> {
                val restricted = result as FlagBeginResult.Restricted
                expect.optLongOrNull("barrierGeneration")?.let { assertEquals("$path barrier", it, restricted.barrierGeneration) }
                val expectedRestriction =
                    when (expect.getString("restriction")) {
                        "config-expired" -> FlagRestrictionReason.CONFIG_EXPIRED
                        else -> fail("$path has unsupported begin restriction").let { FlagRestrictionReason.MALFORMED }
                    }
                assertEquals("$path restriction", expectedRestriction, restricted.reason)
            }
            "terminal" -> assertTrue("$path expected terminal, got $result", result is FlagBeginResult.Terminal)
            else -> fail("$path has unsupported begin result $expectedResult")
        }
        if (expect.optBoolean("authorityPreserved", false)) {
            assertEquals(
                "$path authority apart from its advancing wall floor",
                authorityWithoutWall(checkNotNull(authorityBefore)),
                authorityWithoutWall(state.backing.flagRows.getValue(RUNTIME_FLAG_AUTHORITY_KEY).payload),
            )
        }
        if (expect.has("storageMutation") && !expect.getBoolean("storageMutation")) {
            assertEquals("$path storage mutation", mutationBefore, state.backing.committedMutationGeneration)
        }
        if (expect.optBoolean("futureRecordPreservedByteForByte", false)) {
            val future = checkNotNull(state.futureRecord)
            val current = state.backing.flagRows.getValue(future.key)
            assertEquals(future.storageSchemaVersion, current.storageSchemaVersion)
            assertArrayEquals(future.payload, current.payload)
        }
        if (expect.has("bodyMaterialized") && !expect.getBoolean("bodyMaterialized")) {
            assertFalse(state.backing.flagRows.keys.any { it.startsWith("cache-body:") })
        }
    }

    private fun executeCompleteReload(step: JSONObject, path: String, state: ScenarioState) {
        assertObjectKeys(
            step,
            setOf("type", "requestId", "response", "wallNow", "expect"),
            setOf("realm"),
            path,
        )
        val expect = step.getJSONObject("expect")
        assertObjectKeys(
            expect,
            setOf("result"),
            setOf("flagsRevision", "requestGeneration", "flagCount", "payloadCount", "cache"),
            "$path.expect",
        )
        val begun = state.begun.getValue(step.optString("realm", step.getString("requestId")))
        val responseBytes = documentVariant(step.getString("response")).toByteArray(StandardCharsets.UTF_8)
        val response = FlagCodec.decodeResponse(responseBytes)
        val result = complete(state.owner, begun, responseBytes, step.getString("wallNow"))
        when (expect.getString("result")) {
            "updated" -> assertTrue("$path expected updated", result is FlagReloadResult.Updated)
            "stale" -> assertTrue("$path expected stale", result is FlagReloadResult.Stale)
            else -> fail("$path has unsupported completion result")
        }
        expect.optStringOrNull("flagsRevision")?.let {
            assertEquals("$path flags revision", it, (result as FlagReloadResult.Updated).flagsRevision)
        }
        expect.optLongOrNull("requestGeneration")?.let {
            assertEquals("$path request generation", it, (result as FlagReloadResult.Updated).requestGeneration)
        }
        expect.optLongOrNull("flagCount")?.let { assertEquals("$path flag count", it, response.flags.members.size.toLong()) }
        expect.optLongOrNull("payloadCount")?.let { assertEquals("$path payload count", it, response.payloads.members.size.toLong()) }
        if (expect.has("cache") && expect.isNull("cache")) {
            assertFalse("$path cache metadata", state.backing.flagRows.containsKey(RUNTIME_FLAG_CACHE_METADATA_KEY))
        }
    }

    private fun executeReadAll(step: JSONObject, path: String, state: ScenarioState) {
        assertObjectKeys(step, setOf("type", "wallNow", "expect"), emptySet(), path)
        val expect = step.getJSONObject("expect")
        val wall = step.getString("wallNow")
        if (expect.has("status")) {
            assertObjectKeys(
                expect,
                setOf("status", "barrierGeneration", "requestGeneration", "activeRequestId"),
                emptySet(),
                "$path.expect",
            )
            assertEquals("miss", expect.getString("status"))
            assertTrue(state.owner.readFlag("variant", wall).isMissing())
            assertEquals(expect.getLong("barrierGeneration"), authorityJson(state.backing).getLong("barrierGeneration"))
            val metadata = metadataJson(state.backing)
            assertEquals(expect.getLong("requestGeneration"), metadata.getLong("requestGeneration"))
            assertTrue(metadata.isNull("activeRequest"))
            assertTrue(expect.isNull("activeRequestId"))
            return
        }
        val keys = expect.keys().asSequence().toSet()
        keys.forEach { key ->
            if (key == "orphanPayloadExposed") return@forEach
            val expected = expect.getJSONObject(key)
            assertObjectKeys(expected, setOf("status"), setOf("value", "payload"), "$path.expect.$key")
            val actual = state.owner.readFlag(key, wall)
            when (expected.getString("status")) {
                "missing" -> assertTrue("$path $key expected missing", actual.isMissing())
                "found" -> {
                    val found = actual as FlagReadResult.Found
                    assertEquals("$path $key value", expectedFlagValue(expected.get("value")), found.value)
                    if (expected.has("payload")) {
                        assertEquals("$path $key payload", expectedFlagValue(expected.get("payload")), found.payload)
                    }
                }
                else -> fail("$path has unsupported read status")
            }
        }
        if (expect.has("orphanPayloadExposed")) {
            assertFalse(expect.getBoolean("orphanPayloadExposed"))
            assertTrue(state.owner.readFlag("orphan", wall).isMissing())
        }
    }

    private fun executeSetPersonProperties(step: JSONObject, path: String, state: ScenarioState) {
        assertObjectKeys(step, setOf("type", "set", "wallNow", "expect"), emptySet(), path)
        val expect = step.getJSONObject("expect")
        assertObjectKeys(expect, setOf("contextRevision"), emptySet(), "$path.expect")
        val setObject = step.getJSONObject("set")
        val set =
            setObject.keys().asSequence().associateWith { key ->
                setObject.get(key).let { value -> if (value === JSONObject.NULL) null else value }
            }
        val result =
            state.owner.appendMutations(
                listOf(
                    RuntimeRecordDraft.Mutation(
                        step.getString("wallNow"),
                        RuntimeMutationChange.SetPersonProperties(set, emptyMap(), emptyList()),
                        browserVersions(),
                    ),
                ),
            ).await() as RuntimeAppendResult.Accepted
        assertEquals(expect.getLong("contextRevision"), result.snapshot.state.identity.contextRevision)
    }

    private fun executeCacheCorruption(step: JSONObject, path: String, state: ScenarioState) {
        assertObjectKeys(step, setOf("type", "kind"), emptySet(), path)
        assertEquals("digest-mismatch", step.getString("kind"))
        val metadata = state.backing.flagRows.getValue(RUNTIME_FLAG_CACHE_METADATA_KEY)
        val json = JSONObject(String(metadata.payload, StandardCharsets.UTF_8))
        json.getJSONObject("cache").put("bodySha256", "sha256:" + "0".repeat(64))
        state.backing.flagRows[metadata.key] = metadata.copy(payload = json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun executeAuthorityCorruption(step: JSONObject, path: String, state: ScenarioState) {
        assertObjectKeys(step, setOf("type", "kind"), emptySet(), path)
        assertEquals("missing-after-initialized", step.getString("kind"))
        state.backing.flagRows.remove(RUNTIME_FLAG_AUTHORITY_KEY)
    }

    private fun executeFutureRecord(step: JSONObject, path: String, state: ScenarioState) {
        assertObjectKeys(step, setOf("type", "storageSchemaVersion", "declaredBodyBytes"), emptySet(), path)
        val payload =
            JSONObject()
                .put("declaredBodyBytes", step.getLong("declaredBodyBytes"))
                .put("future", true)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        val row = RuntimeFlagStoredRow(RUNTIME_FLAG_CACHE_METADATA_KEY, step.getLong("storageSchemaVersion"), payload)
        state.backing.flagRows[row.key] = row.copy(payload = payload.copyOf())
        state.futureRecord = row.copy(payload = payload.copyOf())
    }

    private fun documentVariant(name: String): String {
        val variants = vector.getJSONObject("documentVariants")
        val variant = variants.getJSONObject(name)
        assertObjectKeys(
            variant,
            setOf("baseFixtureId"),
            setOf("orderedReplacements", "serialization"),
            "documentVariants.$name",
        )
        val catalogName = variant.getString("baseFixtureId")
        val catalog = vector.getJSONObject("fixtureCatalog").getJSONObject(catalogName)
        assertObjectKeys(catalog, setOf("kind", "fixtureId"), emptySet(), "fixtureCatalog.$catalogName")
        var root = JSONObject(resourceText("contracts/v1/fixtures/${catalog.getString("fixtureId")}.json"))
        variant.optJSONArray("orderedReplacements")?.let { replacements ->
            repeat(replacements.length()) { index ->
                val replacement = replacements.getJSONObject(index)
                assertObjectKeys(replacement, setOf("pointer", "value"), emptySet(), "documentVariants.$name[$index]")
                val member = replacement.getString("pointer").removePrefix("/")
                check('/' !in member) { "The frozen vector currently permits top-level replacements only" }
                root.put(member, replacement.get("value"))
            }
        }
        variant.optStringOrNull("serialization")?.let { serialization ->
            assertEquals("reverse-object-member-order-and-add-json-whitespace", serialization)
            root = reverseObject(root)
            return root.toString(2)
        }
        return root.toString()
    }

    private fun reverseObject(source: JSONObject): JSONObject {
        val result = JSONObject()
        source.keys().asSequence().toList().asReversed().forEach { key ->
            val value = source.get(key)
            result.put(
                key,
                when (value) {
                    is JSONObject -> reverseObject(value)
                    is JSONArray -> reverseArray(value)
                    else -> value
                },
            )
        }
        return result
    }

    private fun reverseArray(source: JSONArray): JSONArray =
        JSONArray().also { result ->
            repeat(source.length()) { index ->
                val value = source.get(index)
                result.put(
                    when (value) {
                        is JSONObject -> reverseObject(value)
                        is JSONArray -> reverseArray(value)
                        else -> value
                    },
                )
            }
        }

    private fun expectedFlagValue(value: Any): FlagJsonValue = FlagJson.fromPlatform(jsonPlatform(value))

    private fun jsonPlatform(value: Any?): Any? =
        when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.keys().asSequence().associateWith { key -> jsonPlatform(value.get(key)) }
            is JSONArray -> List(value.length()) { index -> jsonPlatform(value.get(index)) }
            else -> value
        }

    private fun authorityJson(backing: FakeRuntimeQueueBacking): JSONObject =
        JSONObject(String(backing.flagRows.getValue(RUNTIME_FLAG_AUTHORITY_KEY).payload, StandardCharsets.UTF_8))

    private fun authorityWithoutWall(bytes: ByteArray): String {
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        json.remove("lastObservedWall")
        return FlagJson.canonicalString(FlagJson.parse(json.toString().toByteArray(StandardCharsets.UTF_8)))
    }

    private fun metadataJson(backing: FakeRuntimeQueueBacking): JSONObject =
        JSONObject(String(backing.flagRows.getValue(RUNTIME_FLAG_CACHE_METADATA_KEY).payload, StandardCharsets.UTF_8))

    private fun assertObjectKeys(
        value: JSONObject,
        required: Set<String>,
        optional: Set<String>,
        path: String,
    ) {
        val actual = value.keys().asSequence().toSet()
        assertEquals("$path missing fields", emptySet<String>(), required - actual)
        assertEquals("$path unknown fields", emptySet<String>(), actual - required - optional)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun FlagReadResult.isMissing(): Boolean =
        this is FlagReadResult.Missing || this is FlagReadResult.CacheMiss

    private data class ScenarioState(
        val backing: FakeRuntimeQueueBacking,
        val owner: RuntimeQueueOwner,
        val begun: MutableMap<String, FlagBegunRequest> = mutableMapOf(),
        var futureRecord: RuntimeFlagStoredRow? = null,
    )

    private fun futureBodyChunksCrossingV1Ceiling(): List<ByteArray> {
        val chunkBytes = FlagDurableStore.MAX_CHUNK_BYTES
        val fill = 'x'.code.toByte()
        val opening = "{\"padding\":\"".toByteArray(StandardCharsets.UTF_8)
        val keyPrefix = "\",\"schemaVer".toByteArray(StandardCharsets.UTF_8)
        val keyRemainder = "sion\":2}".toByteArray(StandardCharsets.UTF_8)
        val first = ByteArray(chunkBytes) { fill }.also { opening.copyInto(it) }
        val boundary =
            ByteArray(chunkBytes) { fill }.also { bytes ->
                keyPrefix.copyInto(bytes, destinationOffset = bytes.size - keyPrefix.size)
            }
        return listOf(
            first,
            ByteArray(chunkBytes) { fill },
            ByteArray(chunkBytes) { fill },
            boundary,
            keyRemainder,
        ).also { chunks ->
            check(chunks.sumOf { it.size } > FLAG_MAX_CACHE_BYTES)
        }
    }

    private fun seededOwner(backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking()): RuntimeQueueOwner {
        val owner = configuredOwner(backing)
        val begun = owner.begin("flags_request_1", "store_epoch_1", "2026-08-04T00:01:01.000Z")
        val completed = complete(owner, begun, responseMixed(), "2026-08-04T00:01:02.000Z")
        assertTrue("Expected seeded reload to update, got $completed", completed is FlagReloadResult.Updated)
        return owner
    }

    private fun configuredOwner(backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking()): RuntimeQueueOwner {
        val owner = open(backing)
        owner.ensureFeatureFlagRuntime().await()
        val resolution = owner.applyFeatureFlagConfiguration(configAllowed(), millis("2026-08-04T00:01:00.000Z")).await()
        assertTrue(resolution is V1FlagAuthorizationResolution.Allowed)
        return owner
    }

    private fun RuntimeQueueOwner.begin(requestId: String, storeEpoch: String, wall: String): FlagBegunRequest =
        (beginFeatureFlagReload(browserVersions(), requestId, storeEpoch, millis(wall)).await() as FlagBeginResult.Begun).request

    private fun complete(
        owner: RuntimeQueueOwner,
        begun: FlagBegunRequest,
        responseBytes: ByteArray,
        wall: String,
    ): FlagReloadResult {
        val response = FlagCodec.decodeResponse(responseBytes)
        val committed = owner.commitFeatureFlagReload(begun, browserVersions(), response, millis(wall)).await()
        if (committed !is FlagReloadResult.Updated) return committed
        return owner.finalizeFeatureFlagReload(begun, browserVersions(), response, millis(wall)).await()
    }

    private fun RuntimeQueueOwner.readFlag(
        key: String,
        wall: String = "2026-08-04T00:01:03.000Z",
    ): FlagReadResult = readFeatureFlag(browserVersions(), key, millis(wall)).await()

    private fun open(
        backing: FakeRuntimeQueueBacking,
        trustedSiteKey: String = "elu_pk_test_flags",
    ): RuntimeQueueOwner {
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = "flag-vector-${ownerIds.incrementAndGet()}",
                limits = RuntimeQueueLimits(10_000, 16_777_216),
                databaseFactory = backing::connection,
                legacyStateLoader = ::initialState,
                trustedSiteKey = trustedSiteKey,
            ).await()
        owners += owner
        return owner
    }

    private fun initialState(): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 1,
                    contextRevision = 7,
                    anonymousId = "anon_flags_1",
                    userId = "user_123",
                    groups = mapOf("organization" to "org_456"),
                    superProperties = emptyMap(),
                    session = null,
                    optedOut = false,
                    updatedAt = "2026-08-04T00:00:00.000Z",
                ),
            stream = StreamState(streamId = "stream_flags", nextSequence = 0),
            flagContext =
                FlagContextState(
                    personProperties = mapOf("plan" to "growth", "role" to "owner"),
                    groupProperties = mapOf("organization" to mapOf("tier" to "design-partner")),
                ),
        )

    private fun browserVersions(): RuntimeVersions =
        RuntimeVersions(
            platform = RuntimePlatform.BROWSER,
            runtime = RuntimeVersionComponent("elu-js", "1.409.5-elu.1"),
            facade = RuntimeVersionComponent("window.elu", "1.0.0"),
            build = "4670b69e98ed590c31cf42fc840a25bdadf45ae7",
        )

    private fun configAllowed(): String = resourceText("contracts/v1/fixtures/config-enabled.json")

    private fun configNewerRevoked(): String =
        JSONObject(resourceText("contracts/v1/fixtures/config-disabled.json"))
            .put("issuedAt", "2026-08-04T00:01:30.000Z")
            .put("expiresAt", "2026-08-04T00:06:30.000Z")
            .put("revision", "config-revoked-2")
            .put("status", "revoked")
            .toString()

    private fun responseMixed(): ByteArray =
        JSONObject(resourceText("contracts/v1/fixtures/flags-response.json"))
            .put("requestId", "flags_request_1")
            .put("flagsRevision", "flags-mixed-1")
            .put("expiresAt", "2026-08-04T00:04:00.000Z")
            .put(
                "flags",
                JSONObject()
                    .put("bool-false", false)
                    .put("empty-string", "")
                    .put("null-value", JSONObject.NULL)
                    .put("number-zero", -0.0)
                    .put("variant", "variant-a"),
            )
            .put(
                "payloads",
                JSONObject()
                    .put("orphan", JSONObject().put("ignored", true))
                    .put("variant", JSONObject().put("buttonColor", "violet")),
            )
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    private fun assertProtocolRejected(label: String, block: () -> Unit) {
        try {
            block()
            fail("Expected protocol rejection for $label")
        } catch (_: FlagProtocolException) {
            // Expected.
        }
    }

    private fun millis(source: String): Long = Instant.parse(source).toEpochMilli()

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing test resource $path" }
            .use { String(it.readBytes(), StandardCharsets.UTF_8) }

    private fun jsonResource(path: String): JSONObject = JSONObject(resourceText(path))

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { index -> getJSONObject(index) }

    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)

    private class MutableFlagClock(
        @Volatile private var wall: Long,
        @Volatile private var monotonic: Long,
    ) : FlagClock {
        override fun wallNowEpochMillis(): Long = wall

        override fun monotonicNowNanos(): Long = monotonic

        fun set(wall: Long, monotonic: Long) {
            this.wall = wall
            this.monotonic = monotonic
        }
    }

    private companion object {
        const val VECTOR_PATH = "contracts/v1/test-vectors/feature-flag-activity.json"
    }
}
