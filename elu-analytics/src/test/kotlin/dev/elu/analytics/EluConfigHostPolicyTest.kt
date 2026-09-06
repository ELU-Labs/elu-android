package dev.elu.analytics

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
}
