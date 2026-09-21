package dev.elu.analytics.internal.compat

import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyAndroidPendingHistoryTest {
    private val oldLibrary = ANDROID_LEGACY_SOURCE_SCHEMA.substringAfter('/').removeSuffix("-3.58.0")
    private val id = "01980000-0000-7000-8000-000000000001"
    private val session = "01980000-0000-7000-8000-000000000002"
    private val occurredAt = "2026-08-04T00:01:00.000Z"

    private fun entry(identified: Boolean = false, library: String = oldLibrary): AndroidLegacyHistoryEntry {
        val properties = JSONObject().put("\$lib", library).put("\$lib_version", "3.58.0")
            .put("\$session_id", session).put("\$is_identified", identified).put("customer-value", "retained")
        if (identified) properties.put("\$anon_distinct_id", "anonymous-original")
        val body = JSONObject().put("uuid", id).put("event", if (identified) "\$identify" else "prior-event")
            .put("distinct_id", if (identified) "user-original" else "anonymous-original")
            .put("timestamp", occurredAt).put("properties", properties).toString().toByteArray()
        return AndroidLegacyHistoryEntry("$id.event", 1000, body)
    }

    @Test fun `migration metadata uses owned build while original identity time and customer data remain unchanged`() {
        val converted = LegacyAndroidPendingHistory.convert(listOf(entry()), "stream-import", "2026-08-04T00:02:00.000Z").single()
        val event = RuntimeRecordCodec.decodeEvent(converted.row.internalPayload)
        assertEquals("elu-android-0.1.0-migration", event.versions.build)
        assertEquals("anonymous-original", event.identity.anonymousId)
        assertNull(event.identity.userId)
        assertEquals(occurredAt, event.occurredAt)
        assertEquals(session, event.sessionId)
        assertEquals("retained", event.properties["customer-value"])
        // The transition reader preserves original event properties; these are not clean-release evidence.
        assertEquals(oldLibrary, event.properties["\$lib"])
        assertEquals(id, event.eventId)
    }

    @Test fun `identity mutation uses same owned migration build`() {
        val converted = LegacyAndroidPendingHistory.convert(listOf(entry(true)), "stream-import", "2026-08-04T00:02:00.000Z").single()
        val mutation = RuntimeRecordCodec.decodeMutation(converted.row.internalPayload)
        assertEquals("elu-android-0.1.0-migration", mutation.versions.build)
        assertEquals("anonymous-original", mutation.mutation.subject.anonymousId)
        assertEquals("user-original", mutation.mutation.subject.userId)
    }

    @Test fun `owned output naming does not relax exact inbound provenance`() {
        val failure = assertThrows(AndroidLegacyHistoryException::class.java) {
            LegacyAndroidPendingHistory.convert(listOf(entry(library = "unknown-sdk")), "stream-import", "2026-08-04T00:02:00.000Z")
        }
        assertEquals(AndroidLegacyHistoryRefusal.SOURCE_SHAPE, failure.reason)
    }
}
