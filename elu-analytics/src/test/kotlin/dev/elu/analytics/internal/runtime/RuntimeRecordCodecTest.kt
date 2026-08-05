package dev.elu.analytics.internal.runtime

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecordCodecTest {
    @Test
    fun `event codec is closed canonical and UTF-8 byte stable`() {
        val event = eventRecord()

        val encoded = RuntimeRecordCodec.encodeEvent(event)
        val decoded = RuntimeRecordCodec.decodeEvent(encoded)

        assertEquals(event, decoded)
        assertTrue(encoded.contentEquals(RuntimeRecordCodec.encodeEvent(decoded)))

        val unknown = JSONObject(String(encoded, StandardCharsets.UTF_8)).put("futureField", true)
        assertThrows(UnsupportedRuntimeRecordSchemaExtensionException::class.java) {
            RuntimeRecordCodec.decodeEvent(unknown.toString().toByteArray(StandardCharsets.UTF_8))
        }
        val unsupported = JSONObject(String(encoded, StandardCharsets.UTF_8)).put("schemaVersion", 2)
        assertThrows(UnsupportedRuntimeRecordSchemaException::class.java) {
            RuntimeRecordCodec.decodeEvent(unsupported.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `all mutation changes round trip as one row envelopes`() {
        val changes =
            listOf(
                RuntimeMutationChange.Identify("user_1", mapOf("role" to "owner"), mapOf("source" to "sdk")),
                RuntimeMutationChange.LinkAlias("legacy_1", "user_1"),
                RuntimeMutationChange.SetPersonProperties(
                    set = mapOf("plan" to "growth"),
                    setOnce = emptyMap(),
                    unset = listOf("old-plan"),
                ),
                RuntimeMutationChange.AssociateGroup("organization", "org_1"),
                RuntimeMutationChange.SetGroupProperties(
                    "organization",
                    "org_1",
                    set = mapOf("tier" to "design-partner"),
                    setOnce = emptyMap(),
                    unset = emptyList(),
                ),
            )

        changes.forEachIndexed { index, change ->
            val envelope = mutationEnvelope(index.toLong(), change)
            val encoded = RuntimeRecordCodec.encodeMutation(envelope)
            assertEquals(envelope, RuntimeRecordCodec.decodeMutation(encoded))
            assertTrue(encoded.contentEquals(RuntimeRecordCodec.encodeMutation(RuntimeRecordCodec.decodeMutation(encoded))))
        }
    }

    @Test
    fun `mutation codec rejects multi-record rows duplicate unsets and trailing input`() {
        val encoded = RuntimeRecordCodec.encodeMutation(
            mutationEnvelope(
                0,
                RuntimeMutationChange.SetPersonProperties(emptyMap(), emptyMap(), listOf("old")),
            ),
        )
        val root = JSONObject(String(encoded, StandardCharsets.UTF_8))
        root.getJSONArray("mutations").put(JSONObject(root.getJSONArray("mutations").getJSONObject(0).toString()).put("mutationId", "mutation_second").put("sequence", 1))
        assertThrows(RuntimeRecordCorruptionException::class.java) {
            RuntimeRecordCodec.decodeMutation(root.toString().toByteArray(StandardCharsets.UTF_8))
        }

        val duplicates = JSONObject(String(encoded, StandardCharsets.UTF_8))
        duplicates.getJSONArray("mutations").getJSONObject(0).getJSONObject("change")
            .put("unset", JSONArray().put("same").put("same"))
        assertThrows(RuntimeRecordCorruptionException::class.java) {
            RuntimeRecordCodec.decodeMutation(duplicates.toString().toByteArray(StandardCharsets.UTF_8))
        }

        assertThrows(RuntimeRecordCorruptionException::class.java) {
            RuntimeRecordCodec.decodeMutation(encoded + " true".toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `codec rejects malformed UTF-8 non-finite numbers and unpaired surrogates`() {
        assertThrows(RuntimeRecordCorruptionException::class.java) {
            RuntimeRecordCodec.decodeEvent(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertThrows(RuntimeRecordCorruptionException::class.java) {
            RuntimeRecordCodec.encodeEvent(eventRecord().copy(properties = mapOf("bad" to Double.NaN)))
        }
        assertThrows(RuntimeRecordCorruptionException::class.java) {
            RuntimeRecordCodec.encodeEvent(eventRecord().copy(name = "bad\ud800"))
        }
    }

    @Test
    fun `timestamp comparison normalizes offsets and preserves fractional precision`() {
        assertEquals(
            0,
            RuntimeRecordCodec.compareTimestamps(
                "2026-08-05T00:00:00.1Z",
                "2026-08-05T01:00:00.100+01:00",
            ),
        )
        assertTrue(
            RuntimeRecordCodec.compareTimestamps(
                "2026-08-05T00:00:00.1000001Z",
                "2026-08-04T19:00:00.1-05:00",
            ) > 0,
        )
    }

    @Test
    fun `raw storage codecs and V1BatchRecord wrappers are fixture exact`() {
        val batchFixture = fixture("batch-request.json")
        val batchRecords = batchFixture.getJSONArray("records")

        val expectedMutationWrapper = batchRecords.getJSONObject(0)
        val mutationsFixture = fixture("mutations.json")
        val fixtureMutation =
            mutationsFixture.getJSONArray("mutations")
                .getJSONObject(mutationsFixture.getJSONArray("mutations").length() - 1)
        assertEquals(fixtureMutation.toString(), expectedMutationWrapper.getJSONObject("mutation").toString())
        val mutationRaw =
            JSONObject()
                .put("schemaVersion", 1)
                .put("streamId", batchFixture.getString("streamId"))
                .put("versions", batchFixture.getJSONObject("versions"))
                .put("mutations", JSONArray().put(fixtureMutation))
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        val mutationEnvelope = RuntimeRecordCodec.decodeMutation(mutationRaw)
        assertArrayEquals(mutationRaw, RuntimeRecordCodec.encodeMutation(mutationEnvelope))
        assertArrayEquals(
            expectedMutationWrapper.toString().toByteArray(StandardCharsets.UTF_8),
            RuntimeRecordCodec.encodeBatchRecord(mutationEnvelope),
        )
        assertFalse(expectedMutationWrapper.has("versions"))
        assertEquals("4670b69e98ed590c31cf42fc840a25bdadf45ae7", mutationEnvelope.versions.build)
        assertNotEquals(mutationRaw.size, RuntimeRecordCodec.encodeBatchRecord(mutationEnvelope).size)

        val expectedEventWrapper = batchRecords.getJSONObject(1)
        val eventFixture = fixture("event.json")
        assertEquals(eventFixture.toString(), expectedEventWrapper.getJSONObject("event").toString())
        val eventRaw = eventFixture.toString().toByteArray(StandardCharsets.UTF_8)
        val event = RuntimeRecordCodec.decodeEvent(eventRaw)
        assertArrayEquals(eventRaw, RuntimeRecordCodec.encodeEvent(event))
        assertArrayEquals(
            expectedEventWrapper.toString().toByteArray(StandardCharsets.UTF_8),
            RuntimeRecordCodec.encodeBatchRecord(event),
        )
        assertNotEquals(eventRaw.size, RuntimeRecordCodec.encodeBatchRecord(event).size)
    }

    private fun fixture(name: String): JSONObject {
        val path = "contracts/v1/fixtures/$name"
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing $path" }.use { it.readBytes() }
        return JSONObject(String(bytes, StandardCharsets.UTF_8))
    }

    private fun eventRecord(): RuntimeEventRecord =
        RuntimeEventRecord(
            eventId = "event_1",
            streamId = "stream_1",
            sequence = 0,
            contextRevision = 2,
            kind = RuntimeEventKind.CAPTURE,
            name = "checkout_started",
            occurredAt = NOW,
            identity = RuntimeEventIdentity("anon_1", "user_1", 1),
            sessionId = "session_1",
            properties =
                mapOf(
                    "unicode" to "💡",
                    "nested" to mapOf("array" to listOf(1, true, null, 1.25)),
                ),
            groups = mapOf("organization" to "org_1"),
            versions = versions(),
        )

    private fun mutationEnvelope(
        sequence: Long,
        change: RuntimeMutationChange,
    ): RuntimeMutationEnvelope =
        RuntimeMutationEnvelope(
            streamId = "stream_1",
            versions = versions(),
            mutation =
                RuntimeMutationRecord(
                    mutationId = "mutation_$sequence",
                    sequence = sequence,
                    contextRevision = 2,
                    occurredAt = NOW,
                    subject = RuntimeMutationSubject("anon_1", "user_1", 1),
                    change = change,
                ),
        )

    private fun versions(): RuntimeVersions =
        RuntimeVersions(
            platform = RuntimePlatform.ANDROID,
            runtime = RuntimeVersionComponent("elu-android", "0.1.0"),
            facade = RuntimeVersionComponent("Elu", "0.1.0"),
            build = "test",
        )

    private companion object {
        const val NOW = "2026-08-05T00:00:00.000Z"
    }
}
