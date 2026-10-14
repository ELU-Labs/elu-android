package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.flags.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeFlagExposureStateTest {
    private fun state() = PersistedCoreState(
        identity = IdentityState(revision = 0, contextRevision = 0, anonymousId = "anon_exposure", userId = null, groups = emptyMap(), superProperties = emptyMap(), session = null, optedOut = false, updatedAt = "2026-08-04T00:01:00Z"),
        stream = StreamState(streamId = "stream_exposure", nextSequence = 0), flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
    private val at = FlagExactInstant.parse("2026-08-04T00:01:00.123456Z")
    private val expiry = FlagExactInstant.parse("2026-08-04T00:04:00Z")
    private val token get() = FlagCacheLeaseToken("store_epoch", 1, "record", 1, "witness", "revision", expiry)
    private val metadata get() = FlagEvaluationMetadata("request_original", at, "logical")

    @Test fun `typed digests distinguish boolean string and missing without delimiter ambiguity`() {
        val values = listOf<Any?>(false, "false", true, "true", null, "null", "undefined")
        assertEquals(values.size, values.map { RuntimeFlagExposureState.digest("flag", it) }.toSet().size)
        assertNotEquals(RuntimeFlagExposureState.digest("a\u0000b", "c"), RuntimeFlagExposureState.digest("a", "b\u0000c"))
    }

    @Test fun `canonical visitor ledger is bounded and retains every earlier entry at saturation`() {
        var ledger = RuntimeFlagExposureState.initial(state())
        repeat(MAX_RUNTIME_FLAG_EXPOSURES) { ledger = ledger.adding(RuntimeFlagExposureState.digest("key-$it", true))!! }
        assertTrue(ledger.encode().size <= MAX_RUNTIME_EXPOSURE_STATE_BYTES)
        assertEquals(ledger, RuntimeFlagExposureState.decode(ledger.encode()))
        assertNull(ledger.adding(RuntimeFlagExposureState.digest("new", true)))
        assertSame(ledger, ledger.adding(RuntimeFlagExposureState.digest("key-0", true)))
        assertEquals(MAX_RUNTIME_FLAG_EXPOSURES, ledger.digests.size)
    }

    @Test fun `unknown duplicate unordered and malformed metadata refuse rather than reset`() {
        val ledger = RuntimeFlagExposureState.initial(state()).adding(RuntimeFlagExposureState.digest("a", true))!!
        val valid = JSONObject(String(ledger.encode()))
        val duplicates = JSONObject(valid.toString()).apply { getJSONArray("digests").put(getJSONArray("digests").getString(0)) }
        for (invalid in listOf("{}", valid.toString() + " ", JSONObject(valid.toString()).put("unknown", true).toString(),
            duplicates.toString(), JSONObject(valid.toString()).put("streamId", "").toString(),
            JSONObject(valid.toString()).put("digests", org.json.JSONArray().put("not-a-digest")).toString())) {
            assertThrows(RuntimeQueueCorruptionException::class.java) { RuntimeFlagExposureState.decode(invalid.toByteArray()) }
        }
    }

    @Test fun `logical evaluation fingerprint excludes request timing but includes revision flags and payload`() {
        val response = FlagResponse("first", 1, 1, "revision", at, expiry,
            FlagJsonValue.ObjectValue(listOf(FlagJsonValue.ObjectValue.Member("flag", FlagJsonValue.BooleanValue(false)))),
            FlagJsonValue.ObjectValue(emptyList()))
        val later = response.copy(requestId = "second", evaluatedAt = expiry, expiresAt = FlagExactInstant.parse("2026-08-04T00:05:00Z"))
        assertEquals(response.evaluationMetadata().logicalDigest, later.evaluationMetadata().logicalDigest)
        assertNotEquals(response.evaluationMetadata().requestId, later.evaluationMetadata().requestId)
        assertNotEquals(response.evaluationMetadata().logicalDigest, response.copy(flagsRevision = "new").evaluationMetadata().logicalDigest)
        assertNotEquals(response.evaluationMetadata().logicalDigest, response.copy(payloads = response.flags).evaluationMetadata().logicalDigest)
        assertNotEquals(response.evaluationMetadata().logicalDigest,
            response.copy(flags = FlagJsonValue.ObjectValue(listOf(FlagJsonValue.ObjectValue.Member("flag", FlagJsonValue.StringValue("false"))))).evaluationMetadata().logicalDigest)
    }

    @Test fun `only actual current cache result can carry exposure metadata including valid missing key`() {
        assertNull(RuntimeFlagExposureCapture.from("flag", FlagReadResult.Missing, true) { true })
        val without = FlagReadResult.Found(FlagJsonValue.BooleanValue(false), null, "revision", expiry, token)
        assertNull(RuntimeFlagExposureCapture.from("flag", without, false) { true })
        val found = RuntimeFlagExposureCapture.from("flag", without.copy(metadata = metadata), false) { true }!!
        assertEquals(false, found.properties()["\$feature_flag_response"])
        assertEquals(at.toEpochMillisFloor(), found.properties()["\$feature_flag_evaluated_at"])
        assertEquals("request_original", found.properties()["\$feature_flag_request_id"])
        assertEquals(false, found.properties()["\$used_bootstrap_value"])
        val missing = RuntimeFlagExposureCapture.from("flag", FlagReadResult.CacheMiss(expiry, token, metadata), true) { true }!!
        assertEquals("flag_missing", missing.properties()["\$feature_flag_error"])
        assertFalse(missing.properties().containsKey("\$feature_flag_response"))
        assertEquals(true, missing.properties()["\$used_bootstrap_value"])
        assertTrue(missing.properties().containsKey("\$feature_flag_bootstrapped_payload"))
        assertNull(missing.properties()["\$feature_flag_bootstrapped_payload"])
        assertNotEquals(found.digest, missing.digest)
    }
}
