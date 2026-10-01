package dev.elu.analytics

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EluConfigHostPolicyTest {
    @Test
    fun `default host is approved for release applications`() {
        assertEquals("https://elu.dev", EluConfigHostPolicy.resolve(EluOptions().configHost, debuggable = false))
    }

    @Test
    fun `elu subdomains over https are approved and normalized`() {
        assertEquals("https://config.elu.dev", EluConfigHostPolicy.resolve("https://config.elu.dev/", debuggable = false))
        assertEquals("https://config.elu.dev", EluConfigHostPolicy.resolve(" HTTPS://Config.ELU.dev ", debuggable = false))
    }

    @Test
    fun `plain http to an elu host is refused`() {
        assertNull(EluConfigHostPolicy.resolve("http://elu.dev", debuggable = false))
        assertNull(EluConfigHostPolicy.resolve("http://elu.dev", debuggable = true))
    }

    @Test
    fun `third-party origins are refused in every build`() {
        for (candidate in
            listOf(
                "https://example.com",
                "https://elu.dev.example.com",
                "https://notelu.dev",
                "https://elu.dev@example.com",
                "https://example.com/elu.dev",
                "https://elu.dev:8443",
                "https://elu.dev?redirect=1",
                "https://elu.dev#fragment",
                "https://elu.dev/v1",
                "ftp://elu.dev",
                "elu.dev",
                "",
                "not a url",
            )) {
            assertNull(candidate, EluConfigHostPolicy.resolve(candidate, debuggable = false))
            assertNull(candidate, EluConfigHostPolicy.resolve(candidate, debuggable = true))
        }
    }

    @Test
    fun `loopback origins are approved only for debuggable applications`() {
        val loopbacks =
            mapOf(
                "http://localhost:8080" to "http://localhost:8080",
                "http://127.0.0.1:3000/" to "http://127.0.0.1:3000",
                "http://[::1]:3000" to "http://[::1]:3000",
                "http://10.0.2.2:8787" to "http://10.0.2.2:8787",
                "https://localhost" to "https://localhost",
            )
        for ((candidate, expected) in loopbacks) {
            assertEquals(candidate, expected, EluConfigHostPolicy.resolve(candidate, debuggable = true))
            assertNull(candidate, EluConfigHostPolicy.resolve(candidate, debuggable = false))
        }
    }

    @Test
    fun `loopback origins never carry credentials paths or queries`() {
        assertNull(EluConfigHostPolicy.resolve("http://user:pw@localhost:8080", debuggable = true))
        assertNull(EluConfigHostPolicy.resolve("http://localhost:8080/config", debuggable = true))
        assertNull(EluConfigHostPolicy.resolve("http://localhost:8080?x=1", debuggable = true))
    }

    private val cell = "https://analytics.example.com"

    @Test
    fun `declared regular prefix is canonical and cannot widen cloud or loopback config rules`() {
        val base = "$cell/team-a/elu_v2~beta"
        assertEquals(base, EluConfigHostPolicy.resolve(" HTTPS://Analytics.Example.com/team-a/elu_v2~beta/ ", false, base))
        assertEquals(base, EluConfigHostPolicy.selfHostedOrigin("$base/"))
        assertEquals("$cell/%CE%B1", EluConfigHostPolicy.selfHostedOrigin("$cell/%CE%B1/"))
        assertNull(EluConfigHostPolicy.resolve(base, false))
        assertNull(EluConfigHostPolicy.resolve("$cell/team-b/elu_v2~beta", false, base))
        assertNull(EluConfigHostPolicy.resolve(cell, false, base))
        assertNull(EluConfigHostPolicy.resolve("https://elu.dev/team-a", false, base))
        assertNull(EluConfigHostPolicy.resolve("http://localhost:8787/team-a", true, base))
        assertEquals("https://elu.dev", EluConfigHostPolicy.resolve("https://elu.dev", false, base))
        assertEquals("https://elu.dev/team-a", EluConfigHostPolicy.resolve("https://elu.dev/team-a/", false, "https://elu.dev/team-a"))
        assertNull(EluConfigHostPolicy.selfHostedOrigin("https://analytics.example.com:/team-a"))
    }

    @Test
    fun `irregular or rewritten prefix never creates local authority even with approved config host`() {
        for (suffix in listOf("//", "/a//b", "/a//", "/./a", "/a/../b", "/a/.", "/a/..", "/%2e/a", "/a/.%2E/b",
            "/%2E%2e/b", "/a%2fb", "/a%5Cb", "/a%252fb", "/a%00b", "/a%1fb", "/a%20b", "/a%7Fb", "/a\\b", "/a b",
            "/a\tb", "/a\nb", "/ümlaut", "/a@b", "/a?x=1", "/a#fragment")) {
            val base = cell + suffix
            assertNull(base, EluConfigHostPolicy.selfHostedOrigin(base))
            assertNull(base, EluConfigHostPolicy.resolve("https://elu.dev", false, base))
            assertNull(base, EluConfigHostPolicy.resolve("http://localhost:8787", true, base))
        }
    }

    @Test
    fun `a self-hosted config host is approved when it is exactly the declared api host`() {
        for (debuggable in listOf(false, true)) {
            assertEquals(cell, EluConfigHostPolicy.resolve(cell, debuggable, apiHost = cell))
            // Case and a bare trailing slash are normalization, not a different origin.
            assertEquals(cell, EluConfigHostPolicy.resolve(" HTTPS://Analytics.Example.com/ ", debuggable, apiHost = "$cell/"))
        }
        assertEquals(cell, EluOptions(cell, cell).let { EluConfigHostPolicy.resolve(it.configHost, false, it.apiHost) })
    }

    @Test
    fun `a self-hosted config host is refused without a declared api host`() {
        assertNull(EluConfigHostPolicy.resolve(cell, debuggable = false))
        assertNull(EluConfigHostPolicy.resolve(cell, debuggable = true))
        assertNull(EluOptions(cell).apiHost)
        assertNull(EluOptions().apiHost)
    }

    @Test
    fun `a self-hosted config host is refused when it differs from the api host in any way`() {
        val attempts =
            listOf(
                // plain http, on either side
                "http://analytics.example.com" to cell,
                cell to "http://analytics.example.com",
                "http://analytics.example.com" to "http://analytics.example.com",
                // a different or explicit port, even the https default
                "https://analytics.example.com:8443" to cell,
                "https://analytics.example.com:8443" to "https://analytics.example.com:8443",
                "https://analytics.example.com:443" to "https://analytics.example.com:443",
                // a subdomain or parent of the declared host
                "https://evil.analytics.example.com" to cell,
                "https://example.com" to cell,
                // userinfo that makes the real host another
                "https://analytics.example.com@evil.example" to cell,
                "https://user:pw@analytics.example.com" to "https://user:pw@analytics.example.com",
                // a trailing-dot host
                "https://analytics.example.com." to cell,
                "https://analytics.example.com." to "https://analytics.example.com.",
                // paths, queries and fragments
                "https://analytics.example.com/v1" to cell,
                "https://analytics.example.com?x=1" to cell,
                "https://analytics.example.com#f" to cell,
                // loopback names stay under the debug-only loopback rule
                "https://localhost" to "https://localhost",
                "https://127.0.0.1" to "https://127.0.0.1",
                "https://10.0.2.2" to "https://10.0.2.2",
                // a different host altogether, and junk
                "https://other.example" to cell,
                "analytics.example.com" to "analytics.example.com",
                "" to "",
                cell to "",
                cell to "not a url",
            )
        for ((configHost, apiHost) in attempts) {
            assertNull("$configHost vs $apiHost", EluConfigHostPolicy.resolve(configHost, false, apiHost))
        }
    }

    @Test
    fun `declaring an api host leaves the elu and loopback rules unchanged`() {
        assertEquals("https://elu.dev", EluConfigHostPolicy.resolve("https://elu.dev", false, apiHost = cell))
        assertNull(EluConfigHostPolicy.resolve("http://localhost:8080", false, apiHost = "http://localhost:8080"))
        assertEquals("http://localhost:8080", EluConfigHostPolicy.resolve("http://localhost:8080", true, apiHost = cell))
    }
    @Test
    fun `diagnostics compose with declared origins without changing existing option defaults`() {
        val diagnostics = EluDiagnosticsOptions(enabled = true, launchTimings = true)
        val performance = EluPerformanceOptions(enabled = true)
        val combined = EluOptions(diagnostics, performance, cell, cell)
        assertTrue(combined.diagnostics === diagnostics)
        assertTrue(combined.performance === performance)
        assertEquals(cell, combined.configHost)
        assertEquals(cell, combined.apiHost)
        assertEquals(cell, EluConfigHostPolicy.resolve(combined.configHost, false, combined.apiHost))
        val concise = EluOptions(diagnostics = diagnostics, configHost = cell, apiHost = cell)
        assertFalse(concise.performance.enabled)
        assertTrue(concise.diagnostics === diagnostics)
        assertNull(EluOptions(diagnostics).apiHost)
        assertFalse(EluOptions(cell, cell).diagnostics.enabled)
        assertTrue(EluOptions(performance, cell, cell).performance === performance)
    }

}
