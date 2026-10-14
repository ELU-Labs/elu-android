package dev.elu.analytics.internal.config

import dev.elu.analytics.EluDiagnosticsOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V1CaptureExceptionsTest {
    private fun base() = JSONObject(javaClass.classLoader!!.getResource("contracts/v2/fixtures/config-enabled.json")!!.readText())
    @Test fun `old diagnostics constructors stay disabled and explicit JVM report option is independent of launch timings`() {
        for (options in listOf(EluDiagnosticsOptions(), EluDiagnosticsOptions(true), EluDiagnosticsOptions(true, true)))
            assertFalse(options.crashReports)
        val reports = EluDiagnosticsOptions(enabled = true, crashReports = true)
        assertTrue(reports.enabled); assertTrue(reports.crashReports); assertFalse(reports.launchTimings)
        assertNotNull(EluDiagnosticsOptions::class.java.getConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        assertNotNull(EluDiagnosticsOptions::class.java.getConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
    }
    @Test fun `only exact empty suppression grants automatic reporting and changes the canonical source hash`() {
        val absent = base().toString(); assertNull(V1ConfigJson.parseConfig(absent).captureExceptions)
        val denied = base().put("captureExceptions", false).toString()
        assertFalse(V1ConfigJson.parseConfig(denied).captureExceptions!!.allowsUncaughtReports)
        val allowed = base().put("captureExceptions", JSONObject().put("suppressionRules", JSONArray())).toString()
        assertTrue(V1ConfigJson.parseConfig(allowed).captureExceptions!!.allowsUncaughtReports)
        assertNotEquals(V1ConfigJson.parseConfig(absent).configSemanticHash, V1ConfigJson.parseConfig(allowed).configSemanticHash)
        assertNotNull(V1ConfigJson.parseFlagConfig(allowed))
        for (operator in listOf("exact", "is_not", "icontains", "not_icontains", "regex", "not_regex", "gt", "lt")) {
            val policy = JSONObject().put("suppressionRules", JSONArray().put(JSONObject().put("type", "AND")
                .put("values", JSONArray().put(JSONObject().put("key", "\$exception_values").put("operator", operator)
                    .put("value", JSONArray().put("PRIVATE"))))))
            assertFalse(V1ConfigJson.parseConfig(base().put("captureExceptions", policy).toString()).captureExceptions!!.allowsUncaughtReports)
        }
    }
    @Test fun `malformed unknown excessive and inactive policy fails common boundary including flags`() {
        val bad = listOf<Any>(true, JSONObject.NULL, "true", JSONObject(),
            JSONObject().put("suppressionRules", JSONArray()).put("other", false),
            JSONObject().put("suppressionRules", JSONArray().put(JSONObject().put("type", "X").put("values", JSONArray()))),
            JSONObject().put("suppressionRules", JSONArray().put(JSONObject().put("type", "OR").put("values", JSONArray()
                .put(JSONObject().put("key", "\$exception_types").put("operator", "unknown").put("value", "x"))))),
            JSONObject().put("suppressionRules", JSONArray().put(JSONObject().put("type", "AND").put("values", JSONArray()
                .put(JSONObject().put("key", "\$exception_types").put("operator", "exact").put("value", "x".repeat(1001)))))),
            JSONObject().put("suppressionRules", JSONArray(List(101) { JSONObject().put("type", "AND").put("values", JSONArray()) })))
        for (value in bad) {
            val body = base().put("captureExceptions", value).toString()
            assertThrows(V1MalformedConfigException::class.java) { V1ConfigJson.parseConfig(body) }
            assertThrows(V1MalformedConfigException::class.java) { V1ConfigJson.parseFlagConfig(body) }
        }
        for (body in listOf(base().put("schemaVersion", 1).put("captureExceptions", false),
            base().put("status", "disabled").put("captureExceptions", false)))
            assertThrows(V1MalformedConfigException::class.java) { V1ConfigJson.parseConfigBoundary(body.toString()) }
    }
}
