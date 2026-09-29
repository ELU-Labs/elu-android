package dev.elu.analytics.internal.config

import dev.elu.analytics.EluConfigHostPolicy
import dev.elu.analytics.internal.runtime.RuntimeSiteNamespace
import dev.elu.analytics.internal.runtime.delivery.V1BatchAuthorizationSnapshot
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class LocalEndpointPolicyTest {
    private val origin = "https://analytics.example.com"
    private val selected = LocalEndpointPolicy.fromApiHost(origin)

    @Test fun `local origin alone changes every role while cloud defaults remain closed`() {
        for ((role, path) in listOf(V1EndpointRole.EVENTS to "/v1/events", V1EndpointRole.FLAGS to "/v1/flags",
            V1EndpointRole.REPLAY to "/v2/replay", V1EndpointRole.ASSETS to "/sdk/")) {
            assertThrows(java.net.URISyntaxException::class.java) { URI("$origin$path?%=x") }
            selected.requireApproved(URI("$origin$path"), role)
            selected.requireApproved(URI("$origin:443$path?route=eu"), role)
            val cloud = if (role == V1EndpointRole.ASSETS) "assets.elu.dev" else "ingest.elu.dev"
            LocalEndpointPolicy.CLOUD.requireApproved(URI("https://$cloud$path"), role)
            assertThrows(IllegalArgumentException::class.java) { selected.requireApproved(URI("https://$cloud$path"), role) }
            assertThrows(IllegalArgumentException::class.java) { LocalEndpointPolicy.CLOUD.requireApproved(URI("$origin$path"), role) }
            for (bad in listOf("http://analytics.example.com$path", "https://child.analytics.example.com$path",
                "https://analytics.example.com.evil.test$path", "$origin.$path", "$origin:444$path",
                "https://user@analytics.example.com$path", "$origin$path#x", "$origin$path?%73ite_key=x",
                "$origin/not-the-role")) {
                assertThrows(bad, IllegalArgumentException::class.java) { selected.requireApproved(URI(bad), role) }
            }
        }
        selected.requireApproved(URI("$origin/v1/replay"), V1EndpointRole.REPLAY, 1)
        assertThrows(IllegalArgumentException::class.java) { selected.requireApproved(URI("$origin/v1/replay"), V1EndpointRole.REPLAY) }
    }

    @Test fun `invalid explicit API declaration cannot hide behind approved cloud or debug config origins`() {
        for (api in listOf("", "bad", "http://analytics.example.com", "$origin:443", "$origin/path",
            "$origin?x=1", "$origin#x", "https://user@analytics.example.com", "$origin.", "https://localhost")) {
            assertThrows(api, IllegalArgumentException::class.java) { LocalEndpointPolicy.fromApiHost(api) }
            assertNull(EluConfigHostPolicy.resolve("https://elu.dev", false, api))
            assertNull(EluConfigHostPolicy.resolve("http://localhost:8787", true, api))
        }
    }

    @Test fun `canonical custom origin preserves namespace while cloud other hosts and keys remain isolated`() {
        val key = "elu_pk_test_${"A".repeat(26)}"
        val cloud = RuntimeSiteNamespace.directory(key)
        assertEquals("site-${RuntimeSiteNamespace.digest(key)}", cloud)
        val a = RuntimeSiteNamespace.directory(key, selected)
        val same = RuntimeSiteNamespace.directory(key, LocalEndpointPolicy.fromApiHost(" HTTPS://Analytics.Example.Com/ "))
        val b = RuntimeSiteNamespace.directory(key, LocalEndpointPolicy.fromApiHost("https://other.example.com"))
        assertEquals(a, same)
        assertEquals(3, setOf(cloud, a, b).size)
        assertNotEquals(a, RuntimeSiteNamespace.directory(key + "B", selected))
        assertFalse(a.contains(key)); assertFalse(a.contains("example"))
    }

    @Test fun `batch authorization uses selected origin without granting a different role or config substitution`() {
        val endpoint = URI("$origin/v1/events?route=eu")
        assertEquals(endpoint, V1BatchAuthorizationSnapshot("key", endpoint,
            "2026-08-05T01:00:00Z", 100, 4096, selected).eventsEndpoint)
        for (bad in listOf("https://ingest.elu.dev/v1/events", "https://other.example.com/v1/events", "$origin/v1/flags")) {
            assertThrows(IllegalArgumentException::class.java) { V1BatchAuthorizationSnapshot("key", URI(bad),
                "2026-08-05T01:00:00Z", 100, 4096, selected) }
        }
        assertThrows(IllegalArgumentException::class.java) { V1BatchAuthorizationSnapshot("key", endpoint,
            "2026-08-05T01:00:00Z", 100, 4096) }
    }
}
