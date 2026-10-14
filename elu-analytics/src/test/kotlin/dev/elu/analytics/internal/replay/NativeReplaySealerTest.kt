package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeReplaySealerTest {
    @Test fun `canonical request retains original identity privacy generation and version wrapper`() {
        val binding = fixture()
        val request = binding.sealer().seal(listOf(frame(0)))
        assertEquals(0L, request.sequence)
        assertEquals("session-sealer", request.sessionId)
        assertEquals("anon-sealer", request.anonymousId)
        assertEquals("user-sealer", request.userId)
        assertEquals(3L, request.identityRevision)
        assertEquals(7L, request.contextRevision)
        assertEquals(binding.config.replayCapabilities.replayProtocolGeneration, request.captureProtocolGeneration)
        assertEquals(binding.config.effectivePrivacy!!.effectivePolicyHash, request.effectivePolicyHash)
        assertEquals(NativeMaskingProfile.blanketMask().hash, request.maskingProfileHash)
        assertTrue(request.platformFallbackApplied)
        assertEquals(PAIR, request.transport)
        assertEquals("android", request.platform)
        assertArrayEquals(request.copyBytes(), V1StrictCanonicalJson.canonicalBytes(ReplayJson.parse(request.copyBytes())))
        assertEquals("2026-08-05T00:01:00.123Z", request.startedAt)
        assertEquals(request.startedAt, request.endedAt)
        val versions = chunk(request).getJSONObject("versions")
        assertEquals(2, versions.getInt("schemaVersion")); assertEquals("2.0.0", versions.getString("contractVersion"))
        assertEquals("fixture-build", versions.getString("build"))
        assertEquals(1, binding.versions.schemaVersion); assertEquals("1.0.0", binding.versions.contractVersion)
        export(request, "first-request.json")
    }
    @Test fun `identical input is deterministic and suffix keeps stream node and ordinal history`() {
        val left = fixture().sealer(); val right = fixture().sealer()
        val first = left.seal(listOf(frame(0)))
        assertArrayEquals(first.copyBytes(), right.seal(listOf(frame(0))).copyBytes())
        val suffix = left.seal(listOf(frame(1, nodes = 1), frame(2)))
        assertArrayEquals(suffix.copyBytes(), right.seal(listOf(frame(1, nodes = 1), frame(2))).copyBytes())
        assertEquals(1L, suffix.sequence); assertEquals(first.replayId, suffix.replayId)
        assertNotEquals(first.chunkId, suffix.chunkId); assertNotEquals(first.requestId, suffix.requestId)
        val events = JSONArray(decoded(suffix).toString(Charsets.UTF_8))
        assertEquals(10_000_001, events.getJSONObject(0).getJSONObject("data").getJSONArray("adds").getJSONObject(0).getJSONObject("wireframe").getInt("id"))
        export(suffix, "suffix-request.json")
    }
    @Test fun `gzip is bounded deterministic header with exact decoder output and no raw marker`() {
        val request = fixture().sealer().seal(listOf(frame(0, nodes = 3)))
        val gzip = ReplayBase64.decode(chunk(request).getString("payload"))
        assertArrayEquals(byteArrayOf(31, -117, 8, 0, 0, 0, 0, 0, 0, 3), gzip.copyOfRange(0, 10))
        assertArrayEquals(NativeWireframeEncoder().encode(listOf(frame(0, nodes = 3))).bytes, decoded(request))
        assertTrue(decoded(request).toString(Charsets.UTF_8).contains("[masked]"))
        assertFalse(decoded(request).toString(Charsets.UTF_8).contains("user-sealer"))
        val copy = request.copyBytes(); copy.fill(0)
        assertNotEquals(0.toByte(), request.copyBytes()[0])
    }
    @Test fun `invalid frame and timestamp leave original history available`() {
        val sealer = fixture().sealer()
        rejects { sealer.seal(listOf(frame(1))) }
        rejects { sealer.seal(listOf(frame(0, timestamp = 253_402_300_800_000L))) }
        assertEquals(0L, sealer.seal(listOf(frame(0))).sequence)
        rejects { sealer.seal(listOf(frame(1, timestamp = 1))) }
        assertEquals(1L, sealer.seal(listOf(frame(1))).sequence)
    }
    @Test fun `compression envelope bound failure after encode preserves original sequence and IDs`() {
        val clean = fixture().sealer()
        val expected = clean.seal(listOf(frame(0)))
        val bounded = fixture(limit = expected.byteCount).sealer()
        rejectsLimit { bounded.seal(listOf(frame(0, nodes = 120))) }
        assertArrayEquals(expected.copyBytes(), bounded.seal(listOf(frame(0))).copyBytes())
        val tooSmall = fixture(limit = expected.byteCount - 1).sealer()
        rejectsLimit { tooSmall.seal(listOf(frame(0))) }
    }
    @Test fun `failed suffix does not mutate committed prefix history or retire existing node IDs`() {
        val clean = fixture().sealer()
        val prefix = clean.seal(listOf(frame(0, nodes = 1)))
        val suffix = clean.seal(listOf(frame(1, nodes = 1)))
        val bounded = fixture(limit = maxOf(prefix.byteCount, suffix.byteCount)).sealer()
        assertArrayEquals(prefix.copyBytes(), bounded.seal(listOf(frame(0, nodes = 1))).copyBytes())
        rejectsLimit { bounded.seal(listOf(frame(1, nodes = 120))) }
        assertArrayEquals(suffix.copyBytes(), bounded.seal(listOf(frame(1, nodes = 1))).copyBytes())
        assertArrayEquals(prefix.copyBytes(), PreparedReplayRequest.parse(prefix.copyBytes(), prefix.captureProtocolGeneration).copyBytes())
    }
    @Test fun `minimum envelope budget repeatedly refuses same initial ordinal`() {
        val sealer = fixture(limit = 1024).sealer()
        repeat(2) { rejectsLimit { sealer.seal(listOf(frame(0))) } }
    }
    @Test fun `full canonical privacy and matching original context are required`() {
        val binding = fixture()
        rejects { binding.sealer(privacy = byteArrayOf(32) + binding.privacy) }
        val changed = JSONObject(binding.privacy.toString(Charsets.UTF_8)).put("replayBudgetRemainingSeconds", 59)
        rejects { binding.sealer(privacy = canonical(changed)) }
        rejects { binding.sealer(identity = binding.identity.copy(contextRevision = 8)) }
        rejects { binding.sealer(identity = binding.identity.copy(session = null)) }
        rejects { binding.sealer(identity = binding.identity.copy(optedOut = true)) }
        rejects { binding.sealer(privacy = byteArrayOf(0xc3.toByte(), 0x28)) }
    }
    @Test fun `missing proof unadvertised pair and restrictive privacy never become descriptive binding`() {
        for (mode in listOf("no-proof", "no-advertisement", "no-capture", "no-sample", "no-budget", "opt-out")) {
            val binding = fixture(mode = mode)
            rejects { binding.sealer() }
        }
    }
    @Test fun `unresolved block rule and invalid versions refuse without construction`() {
        val binding = fixture()
        val blocked = binding.config.privacy.masking.copy(platformRules = listOf(V1PlatformMaskingRule(
            V1PrivacyPlatform.ANDROID, V1PlatformRuleAction.BLOCK, "unrecognized", "private")))
        rejects { binding.copy(config = binding.config.copy(privacy = binding.config.privacy.copy(masking = blocked))).sealer() }
        rejects { binding.sealer(versions = binding.versions.copy(platform = RuntimePlatform.IOS)) }
        rejects { binding.sealer(versions = binding.versions.copy(schemaVersion = 2)) }
        rejects { binding.sealer(versions = binding.versions.copy(runtime = RuntimeVersionComponent("provider", "1"))) }
        rejects { binding.sealer(versions = binding.versions.copy(build = "")) }
    }
    @Test fun `exact positive integer milliseconds cover representable date limits`() {
        for (value in listOf(1L, 999L, 1000L, 1001L, 253_402_300_799_999L)) {
            val request = fixture().sealer().seal(listOf(frame(0, timestamp = value)))
            assertEquals(value, Instant.parse(request.startedAt).toEpochMilli())
            assertTrue(request.startedAt.endsWith(".%03dZ".format(java.util.Locale.ROOT, value % 1000)))
            export(request, "timestamp-$value.json")
        }
        for (value in listOf(0L, -1L, 253_402_300_800_000L)) rejects { fixture().sealer().seal(listOf(frame(0, timestamp = value))) }
    }
    @Test fun `Unicode exact safe integers and distinct replay scope retain original bindings`() {
        val binding = fixture(userId = "customer_e\u0301/東京/🚀", context = MAX_REPLAY_SAFE_INTEGER)
        val first = binding.sealer().seal(listOf(frame(0)))
        val second = binding.sealer(replayId = "replay-other").seal(listOf(frame(0)))
        assertEquals(binding.identity.userId, first.userId); assertEquals(MAX_REPLAY_SAFE_INTEGER, first.contextRevision)
        assertEquals(first.effectivePolicyHash, second.effectivePolicyHash)
        assertNotEquals(first.requestId, second.requestId); assertNotEquals(first.chunkId, second.chunkId)
        export(first, "unicode-safe-identity.json")
    }
    @Test fun `unpaired surrogate identifiers and unsafe identity revision are rejected exactly`() {
        val binding = fixture()
        for (bad in listOf("\uD800", "\uDC00", "x\uD800y")) {
            rejects { binding.sealer(replayId = bad) }
            rejects { binding.sealer(identity = binding.identity.copy(userId = bad)) }
            rejects { binding.sealer(identity = binding.identity.copy(anonymousId = bad)) }
            rejects { binding.sealer(identity = binding.identity.copy(session = binding.identity.session!!.copy(id = bad))) }
            rejects { binding.sealer(versions = binding.versions.copy(build = bad)) }
            rejects { binding.copy(config = binding.config.copy(replayCapabilities = binding.config.replayCapabilities.copy(replayProtocolGeneration = bad))).sealer() }
        }
        rejects { binding.sealer(identity = binding.identity.copy(revision = MAX_REPLAY_SAFE_INTEGER + 1)) }
    }
    @Test fun `mutating original byte buffer and caller collections cannot rebind retained sealer`() {
        val binding = fixture(); val sealer = binding.sealer()
        binding.privacy.fill(0)
        val mutable = mutableListOf(frame(0))
        val first = sealer.seal(mutable); mutable.clear()
        assertEquals("user-sealer", first.userId)
        assertEquals(1L, sealer.seal(listOf(frame(1))).sequence)
    }
    @Test fun `different context produces different original privacy request identity`() {
        val first = fixture().sealer().seal(listOf(frame(0)))
        val next = fixture(context = 8).sealer().seal(listOf(frame(0)))
        assertNotEquals(first.contextRevision, next.contextRevision)
        assertNotEquals(first.effectivePolicyHash, next.effectivePolicyHash)
        assertNotEquals(first.requestId, next.requestId)
    }
    @Test fun `v2 sealer preserves earliest movement span exact wrapper and independent sequence domain`() {
        val binding = fixture(protocol = NativeReplayProtocol.V2, profile = NativeMaskingProfile.sensitiveMask())
        val sealer = binding.sealer(); val twin = binding.sealer()
        val time = Instant.parse(NOW).toEpochMilli()
        val id = UUID(0, 1)
        val bounds = NativeRect(10.0, 10.0, 80.0, 80.0)
        val initial = NativeReplayV2Geometry(NativeMaskedSnapshot(0, time, NativeViewport(320, 480),
            listOf(NativeMaskedNode(id, NativeMaskedKind.Rectangle, bounds, bounds))))
        val first = sealer.sealV2(listOf(initial))
        assertArrayEquals(first.copyBytes(), twin.sealV2(listOf(initial)).copyBytes())
        assertEquals(NativeReplayProtocol.V2.transport, first.transport)
        assertEquals(NativeReplayProtocol.V2.generation, first.captureProtocolGeneration)
        assertEquals(NativeReplayProtocol.V2.codec, JSONArray(decoded(first).toString(Charsets.UTF_8))
            .getJSONObject(0).getJSONObject("data").getString("codec"))
        val start = NativeReplayInteraction.Start(NativeReplayTouchPoint(id, 20, 20, time))
        assertArrayEquals(sealer.sealV2(listOf(start)).copyBytes(), twin.sealV2(listOf(start)).copyBytes())
        val moves = NativeReplayInteraction.Moves(listOf(
            NativeReplayTouchPoint(id, 21, 21, time + 100), NativeReplayTouchPoint(id, 22, 22, time + 200)))
        val movement = sealer.sealV2(listOf(moves))
        assertArrayEquals(movement.copyBytes(), twin.sealV2(listOf(moves)).copyBytes())
        assertEquals(2L, movement.sequence)
        assertEquals(time + 100, Instant.parse(movement.startedAt).toEpochMilli())
        assertEquals(time + 200, Instant.parse(movement.endedAt).toEpochMilli())
        assertArrayEquals(movement.copyBytes(), PreparedReplayRequest.parse(movement.copyBytes(), NativeReplayProtocol.V2.generation).copyBytes())
        val v1 = fixture().sealer().seal(listOf(frame(0, timestamp = time)))
        assertNotEquals(v1.chunkId, first.chunkId)
    }

    @Test fun `v2 failed envelope does not consume encoder history and formats cannot cross sealer entrypoints`() {
        val clean = fixture(protocol = NativeReplayProtocol.V2).sealer()
        val initial = NativeReplayV2Geometry(frame(0))
        val expected = clean.sealV2(listOf(initial))
        val bounded = fixture(protocol = NativeReplayProtocol.V2, limit = expected.byteCount).sealer()
        rejectsLimit { bounded.sealV2(listOf(NativeReplayV2Geometry(frame(0, nodes = 120)))) }
        assertArrayEquals(expected.copyBytes(), bounded.sealV2(listOf(initial)).copyBytes())
        rejects { fixture().sealer().sealV2(listOf(initial)) }
        rejects { fixture(protocol = NativeReplayProtocol.V2).sealer().seal(listOf(frame(0))) }
        for (protocol in NativeReplayProtocol.values()) {
            val original = fixture(protocol = protocol)
            for (generation in NativeReplayProtocol.values().map { it.generation } + "unknown") {
                if (generation == protocol.generation) continue
                rejects { original.copy(config = original.config.copy(replayCapabilities =
                    original.config.replayCapabilities.copy(replayProtocolGeneration = generation))).sealer() }
            }
        }
    }

    private data class Binding(val identity: IdentityState, val config: V1AuthorizedConfig, val privacy: ByteArray, val versions: RuntimeVersions, val profile: NativeMaskingProfile) {
        fun sealer(identity: IdentityState = this.identity, privacy: ByteArray = this.privacy, versions: RuntimeVersions = this.versions,
                   replayId: String = "replay-sealer") = NativeReplaySealer(replayId, identity, config, privacy, profile, versions)
    }
    private fun fixture(limit: Int = MAX_REPLAY_REQUEST_BYTES, mode: String = "allowed", userId: String = "user-sealer", context: Long = 7,
                        protocol: NativeReplayProtocol = NativeReplayProtocol.V1,
                        profile: NativeMaskingProfile = NativeMaskingProfile.blanketMask()): Binding {
        // Pure value fixture only: synthetic native pair proof, without runtime or codec activation.
        val document = System.getenv("ELU_SEALER_CONFIG")?.let { File(it).readText() }
            ?: checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/v2/fixtures/config-enabled.json"))
                .bufferedReader().use { it.readText() }
        val root = JSONObject(document)
        val pair = protocol.transport
        root.getJSONObject("capabilities").getJSONObject("replay").put("replayProtocolGeneration", protocol.generation)
        root.getJSONObject("capabilities").getJSONObject("replay").put("transports", JSONArray().put(JSONObject()
            .put("codec", if (mode == "no-advertisement") "elu-browser-dom-v1" else pair.codec).put("compression", "gzip")))
        root.getJSONObject("limits").put("replayChunkBytes", limit)
        if (mode == "no-capture") root.getJSONObject("features").put("capture", false)
        val parsed = V1ConfigJson.parseConfig(root.toString())
        val identity = IdentityState(revision = 3, contextRevision = context, anonymousId = "anon-sealer", userId = userId,
            groups = emptyMap(), superProperties = emptyMap(), session = SessionState("session-sealer", NOW, NOW, 1800,
                lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null), optedOut = mode == "opt-out", updatedAt = NOW)
        val effective = PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(parsed.privacy), checkNotNull(parsed.features),
            checkNotNull(parsed.replayCapabilities), identity, false, NOW,
            PrivacyReplayInput(mode != "no-sample", true, true, if (mode == "no-budget") 0 else 60, pair)))
        val privacyJson = JSONObject(PrivacyStateProjector.encode(effective))
        privacyJson.getJSONObject("effectiveMasking").put("text", if (profile === NativeMaskingProfile.sensitiveMask()) "sensitive" else "all")
        privacyJson.remove("effectivePolicyHash")
        privacyJson.put("effectivePolicyHash", ReplayJson.digest(canonical(privacyJson)))
        val privacy = canonical(privacyJson)
        val manager = V1ConfigManager(if (mode == "no-proof") emptySet() else setOf(pair),
            replayTransportGenerations = NativeReplayProtocol.generationBindings())
        check(manager.install(root.toString(), Instant.parse(NOW).toEpochMilli()) is V1ConfigUpdateResult.Enabled)
        val authorized = manager.authorize(privacy.toString(Charsets.UTF_8), identity, Instant.parse(NOW).toEpochMilli()) as V1ConfigResolution.Authorized
        return Binding(identity, authorized.config, privacy, RuntimeVersions(platform = RuntimePlatform.ANDROID,
            runtime = RuntimeVersionComponent("elu-android", "1.0.0"), facade = RuntimeVersionComponent("EluAnalytics", "1.0.0"), build = "fixture-build"), profile)
    }
    private fun frame(ordinal: Long, timestamp: Long = Instant.parse("2026-08-05T00:01:00.123Z").toEpochMilli() + ordinal, nodes: Int = 0): NativeMaskedSnapshot {
        val items = (0 until nodes).map { index ->
            val bounds = NativeRect((index % 30).toDouble(), (index / 30).toDouble(), 5.0, 5.0)
            NativeMaskedNode(UUID(0, index + 1L), NativeMaskedKind.Text, bounds, bounds)
        }
        return NativeMaskedSnapshot(ordinal, timestamp, NativeViewport(320, 480), items)
    }
    private fun canonical(json: JSONObject) = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(json.toString()))
    private fun chunk(request: PreparedReplayRequest) = JSONObject(request.copyBytes().toString(Charsets.UTF_8)).getJSONObject("chunk")
    private fun decoded(request: PreparedReplayRequest) = GZIPInputStream(ByteArrayInputStream(ReplayBase64.decode(chunk(request).getString("payload")))).use { it.readBytes() }
    private fun rejects(block: () -> Unit) { try { block() } catch (_: Exception) { return }; fail("Expected refusal") }
    private fun rejectsLimit(block: () -> Unit) {
        try { block() } catch (error: NativeReplaySealingException) { assertEquals(NativeReplaySealingFailure.REQUEST_LIMIT, error.failure); return }
        fail("Expected request bound refusal")
    }
    private fun export(request: PreparedReplayRequest, name: String) {
        System.getenv("ELU_SEALER_EXPORT")?.let { path -> File(path).mkdirs(); File(path, name).writeBytes(request.copyBytes()) }
    }
    companion object {
        private const val NOW = "2026-08-05T00:01:00.000Z"
        private val PAIR = V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)
    }
}
