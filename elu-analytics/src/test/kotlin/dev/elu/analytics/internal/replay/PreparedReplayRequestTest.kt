package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import java.nio.ByteBuffer
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreparedReplayRequestTest {
    @Test fun `frozen request domain and exact canonical bytes survive export mutation`() {
        val bytes = ReplayFixtures.bytes()
        val expected = bytes.copyOf()
        val parsed = PreparedReplayRequest.parse(bytes, ReplayFixtures.GENERATION)
        bytes.fill(0)
        parsed.copyBytes().fill(0)
        assertArrayEquals(expected, parsed.copyBytes())
        assertEquals("request_fc8f5188920b236355fba0142235aaffd9eb08073d6d9725bcea42fc85d0e20b", parsed.requestId)
    }
    @Test fun `malformed UTF8 BOM duplicate fields and noncanonical envelopes reject`() {
        val bytes = ReplayFixtures.bytes()
        val raw = String(bytes)
        listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes,
            (" " + raw).toByteArray(), raw.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2").toByteArray()).forEach(::reject)
    }
    @Test fun `closed envelope chunk privacy identity and versions reject extra fields`() {
        listOf("root", "chunk", "identity", "privacy", "versions", "runtime", "facade").forEach { location ->
            val json = ReplayFixtures.request()
            val target = when(location) {
                "root" -> json; "chunk" -> json.getJSONObject("chunk")
                "identity", "privacy", "versions" -> json.getJSONObject("chunk").getJSONObject(location)
                else -> json.getJSONObject("chunk").getJSONObject("versions").getJSONObject(location)
            }
            target.put("unexpected", true)
            reject(ReplayFixtures.canonical(json))
        }
    }
    @Test fun `bad request identity and non-safe numeric coordinates reject`() {
        val json = ReplayFixtures.request().put("requestId", "request_" + "0".repeat(64))
        reject(ReplayFixtures.canonical(json))
        listOf(-1, 1.5, 9_007_199_254_740_992L).forEach { n ->
            reject(ReplayFixtures.bytes { it.put("sequence", n) })
            reject(ReplayFixtures.bytes { it.getJSONObject("identity").put("revision", n) })
        }
    }
    @Test fun `invalid canonical base64 and false secure masking receipts reject`() {
        listOf("", "YQ", "YR==", "YQ===", "YQ==\n", "YQ-_", "====").forEach { payload -> reject(ReplayFixtures.bytes { it.put("payload", payload) }) }
        listOf("appliedBeforeSerialization", "secureInputsMasked").forEach { flag ->
            reject(ReplayFixtures.bytes { it.getJSONObject("privacy").put(flag, false) })
        }
    }
    @Test fun `inverted timestamps and illegal codec fields reject`() {
        reject(ReplayFixtures.bytes { it.put("endedAt", "2026-08-04T00:00:00Z") })
        listOf("provider-codec", "elu-", "elu-Browser").forEach { value -> reject(ReplayFixtures.bytes { it.put("codec", value) }) }
        reject(ReplayFixtures.bytes { it.put("compression", "br") })
    }
    @Test fun `request accepts opaque payload over one MiB but enforces full request limit`() {
        val bytes = ReplayFixtures.bytes { it.put("payload", Base64.getEncoder().encodeToString(ByteArray(1_100_000))) }
        assertTrue(bytes.size > 1_048_576)
        assertEquals(bytes.size, PreparedReplayRequest.parse(bytes, ReplayFixtures.GENERATION).byteCount)
        try { PreparedReplayRequest.parse(bytes, ReplayFixtures.GENERATION, bytes.size - 1); fail("limit") } catch (_: IllegalArgumentException) { }
        reject(ByteArray(MAX_REPLAY_REQUEST_BYTES + 1))
    }
    @Test fun `retention is anchored to exact started timestamp not enqueue time`() {
        val parsed = PreparedReplayRequest.parse(ReplayFixtures.bytes { it.put("startedAt", "2026-08-05T00:01:00.001Z") }, ReplayFixtures.GENERATION)
        val expires = java.time.Instant.parse("2026-08-12T00:01:00.001Z").toEpochMilli()
        assertFalse(parsed.expiredAt(expires - 1)); assertTrue(parsed.expiredAt(expires))
    }
    private fun reject(bytes: ByteArray) {
        try { PreparedReplayRequest.parse(bytes, ReplayFixtures.GENERATION); fail("Expected strict envelope rejection") } catch (_: Exception) { }
    }
}

internal object ReplayFixtures {
    const val GENERATION = "replay-v2-generation-1"
    fun resource(path: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)).bufferedReader().use { it.readText() }
    fun request() = JSONObject(resource("contracts/v2/fixtures/replay-request.json"))
    fun canonical(json: JSONObject) = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(json.toString()))
    fun bytes(change: (JSONObject) -> Unit = {}): ByteArray {
        val root = request(); val chunk = root.getJSONObject("chunk"); change(chunk)
        val bytes = canonical(chunk)
        val material = "elu-sdk-replay-request-v2".toByteArray() + byteArrayOf(0) + ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes
        root.put("requestId", "request_" + ReplayJson.digest(material).removePrefix("sha256:"))
        return canonical(root)
    }
    fun profile() = ReplayMaskingProfile.parse(canonical(JSONObject(resource("contracts/v2/test-vectors/replay-activity.json"))
        .getJSONObject("masking").getJSONObject("profile")))
}
