package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import java.nio.ByteBuffer
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeRasterStorageTest {
    @Test fun `all forty two old families and exact fourteen lazy markers remain closed`() {
        val old = (1L..12L).toList() + (25L..54L).toList()
        assertEquals(42, old.size)
        val native = listOf(5L, 6L, 11L, 12L, 29L, 30L, 35L, 36L, 41L, 42L, 47L, 48L, 53L, 54L)
        old.forEach { assertEquals(it, runtimeNormalizedDatabaseVersion(it)) }
        native.forEach { before ->
            assertEquals(before, runtimeNormalizedDatabaseVersion(before + 128))
            assertEquals(runtimeBaseDatabaseVersion(before), runtimeBaseDatabaseVersion(before + 128))
            assertEquals(runtimeDatabaseFeatureOffset(before), runtimeDatabaseFeatureOffset(before + 128))
        }
        for (version in 0L..255L) if (version !in old && version !in native.map { it + 128 })
            assertThrows("version=$version", UnsupportedRuntimeStorageSchemaException::class.java) { runtimeNormalizedDatabaseVersion(version) }
    }

    @Test fun `outer parser is immutable exact schema domain platform and declared witness only`() {
        val body = storedBody(); val request = NativeRasterStoredRequest.parse(body)
        assertEquals(0L, request.sequence); assertEquals("raster-session", request.sessionId)
        body.fill(0); val copy = request.copyBytes(); copy.fill(0)
        assertNotEquals(0.toByte(), request.copyBytes()[0])
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("schemaVersion", 2) }, { it.getJSONObject("chunk").put("schemaVersion", 2) },
            { it.getJSONObject("chunk").put("codec", "elu-native-wireframe-v2") },
            { it.getJSONObject("chunk").getJSONObject("versions").put("platform", "ios") },
            { it.getJSONObject("chunk").getJSONObject("privacy").put("secureInputsMasked", true) },
            { it.getJSONObject("chunk").getJSONObject("privacy").put("automaticInputDiscovery", true) },
            { it.getJSONObject("chunk").put("payload", "AA") })) {
            val json = JSONObject(request.copyBytes().toString(Charsets.UTF_8)); change(json)
            assertThrows(IllegalArgumentException::class.java) { NativeRasterStoredRequest.parse(resigned(json)) }
        }
        assertThrows(IllegalArgumentException::class.java) { PreparedReplayRequest.parse(request.copyBytes(), NativeRasterSealer.GENERATION) }
        assertThrows(IllegalArgumentException::class.java) { NativeRasterStoredRequest.parse((" " + request.copyBytes().toString(Charsets.UTF_8)).toByteArray()) }
    }

    @Test fun `stored raster shares ordinals counts bytes and exact duplicate collision checks`() = RasterQueueRig().use { rig ->
        rig.activate(); assertTrue(rig.owner.ensureNativeRasterStorage().get())
        val wrapper = checkNotNull(rig.gate.snapshot()?.nativeV3)
        val request = NativeRasterStoredRequest.parse(storedBody(wrapper.raster!!.effectivePolicyHash))
        rig.backing.connection().use { db -> db.transaction { tx ->
            assertTrue(ReplayQueueStore.reconcile(tx, wrapper.base, RasterQueueRig.namespace, rig.clock.wall, false,
                setOf(NativeReplayProtocol.V2.transport), setOf(NativeReplayProtocol.V2.generation), ReplayMaskingRetention { _, _ -> true }))
            fun append(value: NativeRasterStoredRequest, count: Int = 100, bytes: Long = MAX_RUNTIME_QUEUE_BYTES) =
                ReplayQueueStore.appendRaster(tx, value, RasterQueueRig.namespace, checkNotNull(wrapper.base.siteId), wrapper,
                    rig.clock.wall, count, bytes, MAX_REPLAY_REQUEST_BYTES) { rig.clock.wall }
            assertEquals(ReplayAppendResult.Stored(0, false), append(request))
            assertEquals(ReplayAppendResult.Stored(0, true), append(request))
            val conflict = NativeRasterStoredRequest.parse(storedBody(wrapper.raster!!.effectivePolicyHash, payload = "AQ=="))
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.CONFLICT), append(conflict))
            val second = NativeRasterStoredRequest.parse(storedBody(wrapper.raster!!.effectivePolicyHash, sequence = 1))
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.COUNT_LIMIT), append(second, 1))
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.BYTE_LIMIT), append(second, bytes = request.byteCount.toLong()))
            ReplayQueueStore.validate(tx, RasterQueueRig.namespace)
            assertArrayEquals(request.copyBytes(), ReplayQueueStore.readRaster(tx, ReplayQueueStore.headers(tx).single()).request.copyBytes())
            val ledger = ReplayQueueStore.state(tx)!!.rasterSource
            ReplayQueueStore.purge(tx)
            assertEquals(ledger, ReplayQueueStore.state(tx)!!.rasterSource); assertEquals(0L, ReplayQueueStore.state(tx)!!.count)
        } }
    }

    @Test fun `missing child retains bodies but explicit privacy tightening deletes them without clearing ordering`() = RasterQueueRig().use { rig ->
        rig.activate(); assertTrue(rig.owner.ensureNativeRasterStorage().get())
        val wrapper = checkNotNull(rig.gate.snapshot()?.nativeV3)
        val request = NativeRasterStoredRequest.parse(storedBody(wrapper.raster!!.effectivePolicyHash))
        rig.backing.connection().use { db -> db.transaction { tx ->
            fun reconcile(config: V1ParsedConfig) = ReplayQueueStore.reconcile(tx, config, RasterQueueRig.namespace, rig.clock.wall,
                false, setOf(NativeReplayProtocol.V2.transport), setOf(NativeReplayProtocol.V2.generation), ReplayMaskingRetention { _, _ -> true })
            reconcile(wrapper.base)
            ReplayQueueStore.appendRaster(tx, request, RasterQueueRig.namespace, checkNotNull(wrapper.base.siteId), wrapper,
                rig.clock.wall, 100, MAX_RUNTIME_QUEUE_BYTES, MAX_REPLAY_REQUEST_BYTES) { rig.clock.wall }
            reconcile(wrapper.base); assertEquals(1L, ReplayQueueStore.state(tx)!!.count)
            val changed = JSONObject(wrapper.configV2Data.toString(Charsets.UTF_8)).put("issuedAt", "2026-08-05T00:00:30Z")
            changed.getJSONObject("privacy").getJSONObject("masking").put("images", "block")
            val ledger = ReplayQueueStore.state(tx)!!.rasterSource
            reconcile(V1ConfigJson.parseConfig(changed.toString()))
            assertEquals(0L, ReplayQueueStore.state(tx)!!.count); assertEquals(ledger, ReplayQueueStore.state(tx)!!.rasterSource)
        } }
    }

    @Test fun `mixed legacy and raster bodies share limits while legacy claims never select raster`() = RasterQueueRig().use { rig ->
        rig.activate(); assertTrue(rig.owner.ensureNativeRasterStorage().get())
        val wrapper = checkNotNull(rig.gate.snapshot()?.nativeV3)
        val raster = NativeRasterStoredRequest.parse(storedBody(wrapper.raster!!.effectivePolicyHash))
        fun legacy(replay: String) = PreparedReplayRequest.parse(ReplayFixtures.bytes {
            it.put("replayId", replay).put("sequence", 0).put("codec", NativeReplayProtocol.V2.transport.codec)
                .put("startedAt", "2026-08-05T00:01:00.000Z").put("endedAt", "2026-08-05T00:01:00.000Z")
        }, NativeReplayProtocol.V2.generation)
        val old = legacy("legacy-epoch")
        rig.backing.connection().use { db -> db.transaction { tx ->
            ReplayQueueStore.reconcile(tx, wrapper.base, RasterQueueRig.namespace, rig.clock.wall, false,
                setOf(NativeReplayProtocol.V2.transport), setOf(NativeReplayProtocol.V2.generation), ReplayMaskingRetention { _, _ -> true })
            assertEquals(ReplayAppendResult.Stored(0, false), ReplayQueueStore.appendRaster(tx, raster,
                RasterQueueRig.namespace, checkNotNull(wrapper.base.siteId), wrapper, rig.clock.wall,
                100, MAX_RUNTIME_QUEUE_BYTES, MAX_REPLAY_REQUEST_BYTES) { rig.clock.wall })
            fun append(value: PreparedReplayRequest, limit: Int) = ReplayQueueStore.append(tx, value, ReplayFixtures.profile(),
                RasterQueueRig.namespace, checkNotNull(wrapper.base.siteId), NativeReplayProtocol.V2.generation,
                rig.clock.wall, limit, MAX_RUNTIME_QUEUE_BYTES, MAX_REPLAY_REQUEST_BYTES) { rig.clock.wall }
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.COUNT_LIMIT), append(old, 1))
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.CONFLICT), append(legacy("raster-epoch"), 100))
            assertEquals(ReplayAppendResult.Stored(1, false), append(old, 100))
            ReplayQueueStore.validate(tx, RasterQueueRig.namespace)
            assertEquals(raster.byteCount.toLong() + old.byteCount, ReplayQueueStore.state(tx)!!.bytes)
            val authorization = ReplayDeliveryAuthorization(URI("https://ingest.elu.dev/v2/replay"), RasterQueueRig.KEY,
                checkNotNull(wrapper.base.siteId), NativeReplayProtocol.V2.transport, NativeReplayProtocol.V2.generation, "credential", "scope")
            val claim = checkNotNull(ReplayQueueStore.claim(tx, "original", authorization, rig.clock.wall, 1) { true })
            assertArrayEquals(old.copyBytes(), claim.row.prepared.copyBytes())
            assertEquals(ReplayDeliveryCommit.COMMITTED, ReplayQueueStore.commit(tx, claim, ReplayDeliveryOutcome.Accepted, 2))
            assertNull(ReplayQueueStore.claim(tx, "original", authorization, rig.clock.wall, 3) { true })
            assertNull(ReplayQueueStore.nextWakeDelay(tx, "original", authorization, 3))
            assertArrayEquals(raster.copyBytes(), ReplayQueueStore.readRaster(tx, ReplayQueueStore.headers(tx).single()).request.copyBytes())
            ReplayQueueStore.validate(tx, RasterQueueRig.namespace)
            Unit
        } }
    }

    companion object {
        private fun resigned(root: JSONObject): ByteArray {
            val bytes = canonical(root.getJSONObject("chunk"))
            root.put("requestId", "request_" + ReplayJson.digest("elu-sdk-replay-request-v3".toByteArray() + byteArrayOf(0) +
                ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes).removePrefix("sha256:"))
            return canonical(root)
        }
        private fun canonical(value: JSONObject) = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(value.toString()))
        // Deliberately opaque payload: restore validates outer integrity, never decodes or creates capture permission.
        fun storedBody(hash: String = "sha256:" + "a".repeat(64), sequence: Long = 0, payload: String = "AA=="): ByteArray {
            val chunk = JSONObject().put("schemaVersion", 3).put("replayId", "raster-epoch").put("sessionId", "raster-session")
                .put("chunkId", "chunk-$sequence").put("sequence", sequence).put("startedAt", "2026-08-05T00:01:00.000Z")
                .put("endedAt", "2026-08-05T00:01:00.000Z").put("codec", NativeRasterSealer.CODEC).put("compression", "gzip")
                .put("contentEncoding", "base64").put("payload", payload).put("contextRevision", 1)
                .put("identity", JSONObject().put("anonymousId", "raster-anon").put("userId", JSONObject.NULL).put("revision", 1))
                .put("versions", JSONObject().put("schemaVersion", 2).put("contractVersion", "2.0.0").put("platform", "android")
                    .put("runtime", JSONObject().put("name", "elu-android").put("version", "0.2.0"))
                    .put("facade", JSONObject().put("name", "EluAnalytics").put("version", "0.2.0")))
                .put("privacy", JSONObject().put("schemaVersion", 1).put("policyRevision", "policy-revision-1")
                    .put("effectivePolicyHash", hash).put("maskingProfileHash", NativeRasterSealer.PROFILE_HASH)
                    .put("inputCoverage", "declared-regions").put("automaticInputDiscovery", false)
                    .put("unknownContentClassification", false).put("appliedBeforeSerialization", true)
                    .put("requiredRegionsRedacted", true).put("platformFallbackApplied", false))
            val bytes = canonical(chunk)
            val id = "request_" + ReplayJson.digest("elu-sdk-replay-request-v3".toByteArray() + byteArrayOf(0) +
                ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes).removePrefix("sha256:")
            return canonical(JSONObject().put("schemaVersion", 3).put("requestId", id).put("chunk", chunk))
        }
    }
}
