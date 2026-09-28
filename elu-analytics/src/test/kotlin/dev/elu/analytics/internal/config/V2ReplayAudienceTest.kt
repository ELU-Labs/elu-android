package dev.elu.analytics.internal.config

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V2ReplayAudienceTest {
    private fun config() = JSONObject(checkNotNull(javaClass.classLoader!!.getResourceAsStream(
        "contracts/v2/fixtures/config-enabled.json")).bufferedReader().use { it.readText() })

    @Test fun `absent audience preserves all devices while new devices participates in the config hash`() {
        val original = config()
        val absent = V1ConfigJson.parseConfig(original.toString())
        assertNull(absent.replayAudience)
        val body = original.put("replayAudience", "new-devices").toString()
        val restricted = V1ConfigJson.parseConfig(body)
        assertEquals(V1ReplayAudience.NEW_DEVICES, restricted.replayAudience)
        assertNotEquals(absent.configSemanticHash, restricted.configSemanticHash)
        assertEquals(restricted.configSemanticHash, V1ConfigJson.parseFlagConfig(body).configSemanticHash)
        assertEquals(restricted.configSemanticHash, V1ConfigJson.parseConfigBoundary(body).configSemanticHash)
    }

    @Test fun `audience accepts only enabled v2 exact string and no null fallback`() {
        val invalid = listOf(JSONObject.NULL, true, 1, "all-devices", "NEW-DEVICES", "", JSONObject())
            .map { config().put("replayAudience", it) } + listOf(
                config().put("replayAudience", "new-devices").put("schemaVersion", 1),
                config().put("replayAudience", "new-devices").put("status", "disabled"),
                config().put("replayAudience", "new-devices").put("status", "revoked"),
            )
        invalid.forEach { body ->
            assertThrows(V1MalformedConfigException::class.java) { V1ConfigJson.parseConfig(body.toString()) }
            assertThrows(V1MalformedConfigException::class.java) { V1ConfigJson.parseFlagConfig(body.toString()) }
            assertThrows(V1MalformedConfigException::class.java) { V1ConfigJson.parseConfigBoundary(body.toString()) }
        }
    }
}
