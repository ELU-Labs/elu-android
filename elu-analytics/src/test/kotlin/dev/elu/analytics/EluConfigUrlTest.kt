package dev.elu.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class EluConfigUrlTest {
    @Test
    fun `site key is encoded as exactly one path segment`() {
        val url = EluConfigUrl.build("https://config.elu.dev/", "site /?#%+ü")

        assertEquals(
            "https://config.elu.dev/v1/site%20%2F%3F%23%25%2B%C3%BC/config",
            url.toExternalForm(),
        )
    }

    @Test
    fun `unreserved site key characters remain readable`() {
        val url = EluConfigUrl.build("https://config.elu.dev", "abc-XYZ_123.~")

        assertEquals("https://config.elu.dev/v1/abc-XYZ_123.~/config", url.toExternalForm())
    }
}
