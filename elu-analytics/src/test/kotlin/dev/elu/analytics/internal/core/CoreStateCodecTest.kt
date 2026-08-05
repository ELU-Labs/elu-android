package dev.elu.analytics.internal.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreStateCodecTest {
    @Test
    fun `canonical identity fixture round trips without changing its closed shape`() {
        val fixture = JSONObject(IDENTITY_FIXTURE)

        val decoded = CoreStateCodec.decodeIdentity(fixture)
        val encoded = CoreStateCodec.encodeIdentity(decoded)

        assertEquals(fixture.keys().asSequence().toSet(), encoded.keys().asSequence().toSet())
        assertEquals(
            fixture.getJSONObject("session").keys().asSequence().toSet(),
            encoded.getJSONObject("session").keys().asSequence().toSet(),
        )
        assertEquals(decoded, CoreStateCodec.decodeIdentity(encoded))
        assertFalse(encoded.has("streamId"))
        assertFalse(encoded.has("nextSequence"))
        assertFalse(encoded.has("identityRevision"))
    }

    @Test
    fun `identity decoder rejects unknown keys and unsupported schema versions`() {
        val withUnknown = JSONObject(IDENTITY_FIXTURE).put("streamId", "must-not-be-here")
        assertThrows(CoreStateCorruptionException::class.java) {
            CoreStateCodec.decodeIdentity(withUnknown)
        }

        val newer = JSONObject(IDENTITY_FIXTURE).put("schemaVersion", 2)
        assertThrows(UnsupportedCoreSchemaException::class.java) {
            CoreStateCodec.decodeIdentity(newer)
        }

        val nonCanonicalOffset =
            JSONObject(IDENTITY_FIXTURE).put("updatedAt", "2026-08-04T01:01:00.000+01:00")
        assertThrows(CoreStateCorruptionException::class.java) {
            CoreStateCodec.decodeIdentity(nonCanonicalOffset)
        }
    }

    @Test
    fun `customer JSON rejects non-finite and non-native values`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonValues.objectValue(mapOf("bad" to Double.NaN), "properties")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonValues.objectValue(mapOf("bad" to Any()), "properties")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonValues.objectValue(mapOf("nested" to listOf(Float.POSITIVE_INFINITY)), "properties")
        }
    }

    private companion object {
        val IDENTITY_FIXTURE =
            """
            {
              "schemaVersion": 1,
              "revision": 1,
              "contextRevision": 7,
              "anonymousId": "anon_018fcb7cff80721387a19b859e7c53a6",
              "userId": "user_123",
              "groups": { "organization": "org_456" },
              "superProperties": { "plan": "growth" },
              "session": {
                "id": "session_018fcb7cff80721387a19b859e7c53a6",
                "startedAt": "2026-08-04T00:00:00.000Z",
                "lastActivityAt": "2026-08-04T00:01:00.000Z",
                "timeoutSeconds": 1800,
                "maximumDurationSeconds": 86400,
                "lifecycle": "active",
                "backgroundedAt": null
              },
              "optedOut": false,
              "updatedAt": "2026-08-04T00:01:00.000Z"
            }
            """.trimIndent()
    }
}
