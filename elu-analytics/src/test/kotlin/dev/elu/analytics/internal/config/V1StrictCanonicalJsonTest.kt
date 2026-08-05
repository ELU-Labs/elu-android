package dev.elu.analytics.internal.config

import dev.elu.analytics.internal.runtime.RuntimeSiteNamespace
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V1StrictCanonicalJsonTest {
    @Test
    fun `shared behavior vector uses logical fixtures and canonical cases execute byte exactly`() {
        val vector = JSONObject(resourceText("contracts/v1/test-vectors/capture-admission-activity.json"))
        assertEquals(1, vector.getInt("schemaVersion"))
        assertEquals("elu-capture-admission-activity-v1", vector.getString("vectorId"))

        val cases = vector.getJSONObject("canonicalization").getJSONArray("cases")
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val raw = case.getString("raw")
            if (case.optString("expect") == "reject") {
                assertThrows(case.getString("id"), V1MalformedConfigException::class.java) {
                    V1StrictCanonicalJson.parse(raw)
                }
            } else {
                assertGolden(
                    source = raw,
                    base64 = case.getString("expectedCanonicalBase64"),
                    hash = case.getString("expectedSha256"),
                )
            }
        }

        val catalog = vector.getJSONObject("fixtureCatalog")
        catalog.keys().forEach { logicalName ->
            val reference = catalog.getJSONObject(logicalName)
            val fixtureId = reference.getString("fixtureId")
            assertTrue("fixture IDs are logical, never relative paths", '/' !in fixtureId && '\\' !in fixtureId)
            // Resolution is an explicit allowlist. Vector-controlled paths are never opened.
            assertTrue(resolveLogicalFixture(reference.getString("kind"), fixtureId).isNotEmpty())
        }
    }

    @Test
    fun `canonical UTF-16 order escaping and no normalization match the shared goldens`() {
        assertGolden(
            source =
                """{"\uE000":"bmp","\uD83D\uDE00":"astral","z":"line\nquote\"slash\\","a":[3,true,null]}""",
            base64 = "eyJhIjpbMyx0cnVlLG51bGxdLCJ6IjoibGluZVxucXVvdGVcInNsYXNoXFwiLCLwn5iAIjoiYXN0cmFsIiwi7oCAIjoiYm1wIn0=",
            hash = "sha256:933b6c88095d83223339c4dd1cc4af77f5b8d07bf7f3a44535eb2595aaec5b16",
        )
        assertGolden(
            source = """{"é":"composed","e\u0301":"decomposed"}""",
            base64 = "eyJlzIEiOiJkZWNvbXBvc2VkIiwiw6kiOiJjb21wb3NlZCJ9",
            hash = "sha256:8117f7eb721338d1c046fc7a3905d1f77b7a920abc82436194992a55af52f571",
        )
    }

    @Test
    fun `canonical numeric domain matches the shared golden`() {
        assertGolden(
            source =
                "[-0,1.0,4.50,2e-3,0.0000001,1E30,333333333.33333329," +
                    "9223372036854775807,-9223372036854775808,1e3]",
            base64 =
                "WzAsMSw0LjUsMC4wMDIsMWUtNywxZSszMCwzMzMzMzMzMzMuMzMzMzMzMyw5MjIzMzcyMDM2ODU0Nzc1ODA3LC05MjIzMzcyMDM2ODU0Nzc1ODA4LDEwMDBd",
            hash = "sha256:3ec225841a1fa2d9ff724a6420d5dc96e18cab7d39010bc0d877b7ed037dda05",
        )
    }

    @Test
    fun `binary64 spelling follows ECMAScript independently of the JVM formatter`() {
        val value =
            V1StrictCanonicalJson.parse(
                "[1e23,5e-324,1.7976931348623157e308,333333333.33333329,1.0000000000000002]",
            )
        assertEquals(
            "[1e+23,5e-324,1.7976931348623157e+308,333333333.3333333,1.0000000000000002]",
            V1StrictCanonicalJson.canonicalize(value),
        )
    }

    @Test
    fun `numeric precision and scale are bounded before arbitrary precision expansion`() {
        assertThrows(V1MalformedConfigException::class.java) {
            V1StrictCanonicalJson.parse("1e100000000")
        }
        assertThrows(V1MalformedConfigException::class.java) {
            V1StrictCanonicalJson.parse("1." + "0".repeat(2_048) + "1")
        }
    }

    @Test
    fun `duplicate decoded keys and unpaired surrogates reject before platform decoding`() {
        assertThrows(V1MalformedConfigException::class.java) {
            V1StrictCanonicalJson.parse("""{"a":1,"\u0061":2}""")
        }
        assertThrows(V1MalformedConfigException::class.java) {
            V1StrictCanonicalJson.parse("""{"value":"\uD800"}""")
        }
        assertThrows(V1MalformedConfigException::class.java) {
            V1StrictCanonicalJson.parse("""{"value":"\uDC00"}""")
        }
    }

    @Test
    fun `config policy and effective decision projections match frozen fixtures`() {
        val config = V1ConfigJson.parseConfig(resourceText("contracts/v1/fixtures/config-enabled.json"))
        assertEquals(
            "sha256:69da989f31a6a3133dcebcdb64cd7665c666eb6af5a8aa766bc0036d8736ca4f",
            config.configSemanticHash,
        )
        assertEquals(
            "sha256:6b13dc5469370452e41767356bedd92bbbdf3acf8ff4024447d03b1f19ea72ce",
            config.policySourceHash,
        )

        assertDecisionProjection(
            "contracts/v1/fixtures/privacy-allowed.json",
            "sha256:852aa75195a8a72e48ded6f286ea8634d83e64c55277207756632f7a60883ed3",
        )
        assertDecisionProjection(
            "contracts/v1/fixtures/privacy-blocked.json",
            "sha256:e0c5e50f3127f85b1530be39fced4cc7abeae535d3d50ca48acb30cfcca685f6",
        )
    }

    @Test
    fun `trusted site namespace hashes exact UTF-8 without normalization`() {
        assertEquals(
            "0d28cb28b0d301938550ddaf297a1c9b59a78c1d02534cf2be40aef423d6b943",
            RuntimeSiteNamespace.digest("elu_pk_test_capture"),
        )
        assertEquals(
            "site-0d28cb28b0d301938550ddaf297a1c9b59a78c1d02534cf2be40aef423d6b943",
            RuntimeSiteNamespace.directory("elu_pk_test_capture"),
        )
    }

    private fun assertDecisionProjection(path: String, expected: String) {
        val body = resourceText(path)
        val parsed = V1ConfigJson.parseEffectivePrivacy(body)
        assertEquals(expected, parsed.effectivePolicyHash)
        val root = V1StrictCanonicalJson.parse(body) as V1StrictCanonicalJson.Value.ObjectValue
        val withoutHash =
            V1StrictCanonicalJson.Value.ObjectValue(
                root.members.filterNot { it.first == "effectivePolicyHash" },
            )
        assertEquals(expected, V1StrictCanonicalJson.sha256(withoutHash))
    }

    private fun assertGolden(source: String, base64: String, hash: String) {
        val value = V1StrictCanonicalJson.parse(source)
        val bytes = V1StrictCanonicalJson.canonicalBytes(value)
        assertEquals(
            String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8),
            String(bytes, StandardCharsets.UTF_8),
        )
        assertEquals(hash, V1StrictCanonicalJson.sha256(bytes))
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing test resource $path" }.readText()

    private fun resolveLogicalFixture(kind: String, fixtureId: String): String =
        when (kind to fixtureId) {
            "config" to "config-enabled" -> resourceText("contracts/v1/fixtures/config-enabled.json")
            "config" to "config-disabled" -> resourceText("contracts/v1/fixtures/config-disabled.json")
            "effectivePrivacy" to "privacy-allowed" -> resourceText("contracts/v1/fixtures/privacy-allowed.json")
            "effectivePrivacy" to "privacy-blocked" -> resourceText("contracts/v1/fixtures/privacy-blocked.json")
            else -> error("Unsupported logical fixture reference: $kind/$fixtureId")
        }

}
