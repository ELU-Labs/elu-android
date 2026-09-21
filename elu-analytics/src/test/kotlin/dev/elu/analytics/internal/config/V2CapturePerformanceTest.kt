package dev.elu.analytics.internal.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class V2CapturePerformanceTest {
    private fun fixture(): JSONObject {
        val resource = javaClass.classLoader!!.getResourceAsStream("contracts/v2/fixtures/config-enabled.json")
            ?: javaClass.classLoader!!.getResourceAsStream("contracts/v1/fixtures/config-enabled.json")
            ?: error("Config fixture unavailable")
        return resource.bufferedReader().use { JSONObject(it.readText()) }
    }

    @Test fun `optional performance control uses current boolean and interval contract`() {
        val baseline = fixture()
        assertNull(V1ConfigJson.parseConfig(baseline.toString()).capturePerformance)
        val control = JSONObject().put("memory", true).put("long_tasks", false).put("sample_interval_ms", 5000)
        val parsed = V1ConfigJson.parseConfig(baseline.put("capturePerformance", control).toString())
        assertEquals(V1CapturePerformance(true, false, 5000), parsed.capturePerformance)
        assertEquals(parsed.configSemanticHash, V1ConfigJson.parseFlagConfig(baseline.toString()).configSemanticHash)
    }

    @Test fun `malformed performance controls fail closed`() {
        val values = listOf(
            JSONObject().put("memory", "true").put("long_tasks", false).put("sample_interval_ms", 5000),
            JSONObject().put("memory", true).put("long_tasks", false).put("sample_interval_ms", 4999),
            JSONObject().put("memory", true).put("long_tasks", false).put("sample_interval_ms", 5000.5),
            JSONObject().put("memory", true).put("long_tasks", false).put("sample_interval_ms", 5000).put("extra", true),
        )
        values.forEach { control ->
            assertThrows(V1MalformedConfigException::class.java) {
                V1ConfigJson.parseConfig(fixture().put("capturePerformance", control).toString())
            }
        }
    }
}
