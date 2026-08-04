package dev.elu.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EluRemoteConfigTest {
    @Test
    fun `parses supported enabled config and privacy`() {
        val config =
            EluRemoteConfig.parse(
                """
                {
                  "v": 1,
                  "enabled": true,
                  "publicToken": "elu-token",
                  "host": "https://ingest.elu.dev",
                  "privacy": {
                    "blockEu": false,
                    "maskTextInputs": false,
                    "maskAllText": true,
                    "maskImages": true,
                    "replayNewUsersOnly": true,
                    "replayMaxMinutes": 12
                  }
                }
                """.trimIndent(),
            )

        requireNotNull(config)
        assertEquals(1, config.schemaVersion)
        assertTrue(config.enabled)
        assertEquals("elu-token", config.publicToken)
        assertEquals("https://ingest.elu.dev", config.host)
        assertFalse(config.privacy.blockEu)
        assertFalse(config.privacy.maskTextInputs)
        assertTrue(config.privacy.maskAllText)
        assertTrue(config.privacy.maskImages)
        assertTrue(config.privacy.replayNewUsersOnly)
        assertEquals(12, config.privacy.replayMaxMinutes)
    }

    @Test
    fun `requires exactly supported integer schema version`() {
        assertNull(EluRemoteConfig.parse("""{"enabled":false}"""))
        assertNull(EluRemoteConfig.parse("""{"v":0,"enabled":false}"""))
        assertNull(EluRemoteConfig.parse("""{"v":2,"enabled":false}"""))
        assertNull(EluRemoteConfig.parse("""{"v":1.5,"enabled":false}"""))
        assertNull(EluRemoteConfig.parse("""{"v":"1","enabled":false}"""))

        val disabled = EluRemoteConfig.parse("""{"v":1,"enabled":false}""")
        requireNotNull(disabled)
        assertEquals(1, disabled.schemaVersion)
        assertFalse(disabled.enabled)
    }

    @Test
    fun `enabled config requires nonblank token and host`() {
        assertNull(EluRemoteConfig.parse("""{"v":1,"enabled":true}"""))
        assertNull(
            EluRemoteConfig.parse(
                """{"v":1,"enabled":true,"publicToken":" ","host":"https://ingest.elu.dev"}""",
            ),
        )
    }

    @Test
    fun `privacy defaults fail closed and invalid budget becomes unlimited`() {
        val defaults =
            EluRemoteConfig.parse(
                """{"v":1,"enabled":true,"publicToken":"token","host":"https://ingest.elu.dev"}""",
            )?.privacy
        assertEquals(EluPrivacyConfig.DEFAULTS, defaults)

        val invalidBudget =
            EluRemoteConfig.parse(
                """{"v":1,"enabled":true,"publicToken":"token","host":"https://ingest.elu.dev","privacy":{"replayMaxMinutes":61}}""",
            )?.privacy
        requireNotNull(invalidBudget)
        assertEquals(0, invalidBudget.replayMaxMinutes)
        assertTrue(invalidBudget.blockEu)
        assertTrue(invalidBudget.maskTextInputs)
    }
}
