package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ExactTimestamp
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import org.junit.Assert.*
import org.junit.Test

internal class NativeReplaySessionStateTest {
    private val key = NativeReplaySessionState.Key("site-a", "session-a", "2026-09-09T00:00:00Z")
    private val epoch = "00000000-0000-4000-8000-000000000001"
    private val otherEpoch = "00000000-0000-4000-8000-000000000002"
    private val start = "2026-09-09T00:00:01Z"
    private fun observed(rate: Double = 1.0, cap: Int = 60) = NativeReplaySessionState("a".repeat(64), "stream-a")
        .observe(key, rate, cap, start, null, null)
    private fun active(cap: Int = 60) = observed(cap = cap).begin(epoch, start, 1.0)
    private fun canonical(text: String) = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(text))
    private fun denyDecode(text: String) { assertThrows(IllegalArgumentException::class.java) { NativeReplaySessionState.decode(canonical(text)) } }
    private fun elapsed(a: String, b: String) = NativeReplaySessionState.elapsedCeilMicroseconds(
        V1ConfigJson.parseExactTimestamp(a), V1ConfigJson.parseExactTimestamp(b))

    @Test fun closedCanonicalMetadataRoundTripsWithExplicitNulls() {
        for (state in listOf(NativeReplaySessionState("a".repeat(64), "stream-a"), observed(), active())) {
            val encoded = state.encoded(); assertEquals(state, NativeReplaySessionState.decode(encoded))
            assertArrayEquals(encoded, NativeReplaySessionState.decode(encoded).encoded())
        }
        assertTrue(String(observed().encoded()).contains("\"firstStartAt\":null"))
        assertTrue(String(observed().encoded()).contains("\"activeEpoch\":null"))
    }

    @Test fun observationDoesNotStartOrAllocate() {
        val value = observed(); assertNull(value.session!!.firstStartAt); assertNull(value.session.activeEpoch)
        assertEquals(0L, value.nextReplayOrdinal); assertEquals(60_000_000L, value.session.remainingMicroseconds)
        assertEquals(60, value.session.remainingWholeSeconds)
    }

    @Test fun originalFalseCannotBecomeTrueAfterPolicyIncreaseOrReopen() {
        val value = NativeReplaySessionState.decode(observed(0.0).encoded()).observe(key, 1.0, 60, start, null, null)
        assertFalse(value.session!!.originalSelected); assertFalse(value.session.selected(1.0))
        assertEquals(value, value.begin(epoch, start, 1.0))
    }

    @Test fun currentRateCanRestrictOriginalTrue() {
        val value = observed(); assertTrue(value.session!!.selected(1.0)); assertFalse(value.session.selected(0.0))
        for (bad in listOf(-1.0, 2.0, Double.NaN, Double.POSITIVE_INFINITY)) assertFalse(value.session.selected(bad))
    }

    @Test fun independentlyComputedCanonicalSamplingAndIdentifierVectors() {
        // Fixed vectors calculated from the documented canonical arrays with Python hashlib/json.
        val hash = "sha256:2765bf0203123aeeef14337ee3acc9f54f8ef1cf19879e8568b2dfb74dd2823e"
        assertEquals(hash, NativeReplaySessionState.samplingHash(key))
        assertFalse(NativeReplaySessionState.selected(hash, 0.15389627265091899))
        assertTrue(NativeReplaySessionState.selected(hash, Math.nextUp(0.15389627265091899)))
        assertEquals("replay_4109d4499a1d41536c422859aea4a7748490a7cbd8b3c2df9cecf247d40e192b",
            observed().allocateReplayId().replayId)
    }

    @Test fun zeroCapRemainsZeroAcrossLaterIncrease() {
        val value = observed().observe(key, 1.0, 0, start, null, null).observe(key, 1.0, 60, start, null, null)
        assertEquals(0, value.session!!.maximumDurationSeconds); assertEquals(0L, value.session.remainingMicroseconds)
        assertEquals(value, value.begin(epoch, start, 1.0))
    }

    @Test fun smallestCapAndOriginalStartSurviveStopRestart() {
        val begun = active().observe(key, 1.0, 20, "2026-09-09T00:00:02Z", epoch, 1_000_000)
        val stopped = begun.stop(key, start, epoch, "2026-09-09T00:00:03Z", 2_000_000).state
        val restarted = stopped.observe(key, 1.0, 60, "2026-09-09T00:00:04Z", null, null)
            .begin(otherEpoch, "2026-09-09T00:00:04Z", 1.0)
        assertEquals(start, restarted.session!!.firstStartAt); assertEquals(20, restarted.session.maximumDurationSeconds)
        assertEquals(3_000_000L, restarted.session.elapsedFloorMicroseconds)
        assertEquals(otherEpoch, restarted.session.activeEpoch)
    }

    @Test fun aWallLeadIsNotAddedToLaterOriginalContinuousElapsed() {
        var value = active().observe(key, 1.0, 60, "2026-09-09T00:00:01.000250Z", epoch, 0)
        for ((ticks, expected) in listOf(100L to 250L, 200L to 250L, 300L to 300L, 400L to 400L)) {
            value = value.observe(key, 1.0, 60, "2026-09-09T00:00:01.000250Z", epoch, ticks)
            assertEquals(expected, value.session!!.elapsedFloorMicroseconds)
        }
    }

    @Test fun repeatedSubMicrosecondRefreshesRoundOriginalIntervalOnce() {
        var value = active()
        for (step in 1..25) {
            val nanos = step * 100
            val time = "2026-09-09T00:00:01." + nanos.toString().padStart(9, '0') + "Z"
            value = value.observe(key, 1.0, 60, time, epoch, null)
        }
        assertEquals(3L, value.session!!.elapsedFloorMicroseconds)
        assertEquals(1L, elapsed(start, "2026-09-09T00:00:01.0000000001Z"))
    }

    @Test fun exactFractionBorrowAndDayCrossing() {
        assertEquals(1L, elapsed("2026-09-09T00:00:01.0000009Z", "2026-09-09T00:00:01.0000010Z"))
        assertEquals(1L, elapsed("2026-09-09T23:59:59.9999999Z", "2026-09-10T00:00:00Z"))
        assertEquals(1_000_000L, elapsed("2026-09-09T00:00:01.0000009Z", "2026-09-09T00:00:02.0000008Z"))
        assertEquals(0L, elapsed(start, "2026-09-09T00:00:01.000000000Z"))
    }

    @Test fun rollbackAndLeapSecondArithmeticDeniedAndLongIntervalsSaturate() {
        assertNull(elapsed(start, key.sessionStartedAt))
        assertNull(NativeReplaySessionState.elapsedCeilMicroseconds(
            V1ExactTimestamp.fromEpochSecondAndFraction(1, "", true), V1ExactTimestamp.fromEpochSecondAndFraction(2, "")))
        assertEquals(NativeReplaySessionState.MAXIMUM_MICROSECONDS, elapsed(start, "2027-09-09T00:00:01Z"))
    }

    @Test fun exhaustedBudgetDoesNotRestart() {
        val value = active(1).stop(key, start, epoch, "2026-09-09T00:00:02Z", 1_000_000).state
        assertEquals(value, value.begin(otherEpoch, "2026-09-09T00:00:02Z", 1.0))
        assertEquals(0L, value.session!!.remainingMicroseconds)
    }

    @Test fun wallRollbackLatchesForActualSessionEvenAfterRecovery() {
        val value = active().observe(key, 1.0, 60, "2026-09-09T00:00:02Z", epoch, 1_000_000)
            .observe(key, 1.0, 60, start, epoch, 1_000_000)
            .observe(key, 1.0, 60, "2026-09-09T00:00:03Z", epoch, 2_000_000)
        assertTrue(value.session!!.clockDenied)
        assertTrue(NativeReplaySessionState.decode(value.encoded()).session!!.clockDenied)
    }

    @Test fun foreignActiveEpochInterruptsSessionButOwnedEpochDoesNot() {
        val value = NativeReplaySessionState.decode(active().encoded())
        assertFalse(value.observe(key, 1.0, 60, start, epoch, 0).session!!.interrupted)
        for (foreign in listOf(null, otherEpoch)) {
            val interrupted = value.observe(key, 1.0, 60, start, foreign, 0)
            assertTrue(interrupted.session!!.interrupted)
            assertTrue(interrupted.observe(key, 1.0, 60, start, epoch, 0).session!!.interrupted)
        }
    }

    @Test fun newActualSessionGetsNewAccountingButRetainsInstallationOrdinal() {
        val old = active().allocateReplayId().state.denyClock(key)
        val next = old.observe(key.copy(sessionId = "new-session"), 1.0, 120, start, null, null)
        assertFalse(next.session!!.clockDenied); assertFalse(next.session.interrupted)
        assertNull(next.session.firstStartAt); assertEquals(120, next.session.maximumDurationSeconds)
        assertEquals(1L, next.nextReplayOrdinal)
    }

    @Test fun exactStopReceiptCannotRestrictAnotherSessionSiteStartOrEpoch() {
        val value = active()
        for (foreign in listOf(key.copy(siteId = "other-site"), key.copy(sessionId = "other-session"),
            key.copy(sessionStartedAt = "2026-09-09T00:00:00.000Z"))) {
            val stop = value.stop(foreign, start, epoch, "malformed", -1)
            assertFalse(stop.matched); assertEquals(value, stop.state)
        }
        for ((first, token) in listOf(start to otherEpoch, "2026-09-09T00:00:01.0Z" to epoch)) {
            assertFalse(value.stop(key, first, token, null, null).matched)
        }
    }

    @Test fun matchingStopWithInvalidClocksDeniesAndClearsEpoch() {
        for ((wall, ticks) in listOf(null to null, "malformed" to 0L, start to -1L,
            key.sessionStartedAt to 0L, start to NativeReplaySessionState.MAXIMUM_MICROSECONDS + 1)) {
            val result = active().stop(key, start, epoch, wall, ticks)
            assertTrue(result.matched); assertTrue(result.state.session!!.clockDenied); assertNull(result.state.session.activeEpoch)
        }
    }

    @Test fun stopIsIdempotentlyUnmatchedAfterOriginalEpochClears() {
        val first = active().stop(key, start, epoch, start, 0)
        assertTrue(first.matched)
        val again = first.state.stop(key, start, epoch, null, null)
        assertFalse(again.matched); assertEquals(first.state, again.state)
    }

    @Test fun preparedOnlyClockDenialAppliesToExactSiteSessionStart() {
        val value = observed()
        assertEquals(value, value.denyClock(key.copy(siteId = "other")))
        val denied = value.denyClock(key); assertTrue(denied.session!!.clockDenied)
        assertEquals(denied, denied.begin(epoch, start, 1.0))
    }

    @Test fun replayIdsDoNotRepeatAfterReopenAndExhaustionIsChecked() {
        val first = observed().allocateReplayId()
        val second = NativeReplaySessionState.decode(first.state.encoded()).allocateReplayId()
        assertNotEquals(first.replayId, second.replayId); assertEquals(2L, second.state.nextReplayOrdinal)
        assertNotEquals(first.replayId, observed().copy(streamId = "stream-b").allocateReplayId().replayId)
        assertThrows(IllegalArgumentException::class.java) {
            observed().copy(nextReplayOrdinal = MAX_REPLAY_SAFE_INTEGER).allocateReplayId()
        }
    }

    @Test fun nonCanonicalWhitespaceUnknownMissingDuplicateAndFutureFieldsRejected() {
        val text = String(observed().encoded())
        assertThrows(IllegalArgumentException::class.java) { NativeReplaySessionState.decode((" " + text).toByteArray()) }
        denyDecode(text.dropLast(1) + ",\"future\":true}")
        denyDecode(text.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        denyDecode(text.replace("\"firstStartAt\":null,", ""))
        denyDecode(text.replace("\"activeEpoch\":null", "\"activeEpoch\":null,\"future\":0"))
        assertThrows(IllegalArgumentException::class.java) {
            NativeReplaySessionState.decode(text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").toByteArray())
        }
    }

    @Test fun alteredSamplingMalformedCountersAndWrongTypesRejected() {
        val text = String(observed().encoded())
        for ((a, b) in listOf("\"originalSelected\":true" to "\"originalSelected\":false",
            "\"maximumDurationSeconds\":60" to "\"maximumDurationSeconds\":86401",
            "\"elapsedFloorMicroseconds\":0" to "\"elapsedFloorMicroseconds\":1",
            "\"nextReplayOrdinal\":0" to "\"nextReplayOrdinal\":9007199254740992",
            "\"nextReplayOrdinal\":0" to "\"nextReplayOrdinal\":0.5",
            "\"clockDenied\":false" to "\"clockDenied\":0")) denyDecode(text.replace(a, b))
    }

    @Test fun invalidEpochCannotBeginOrDecode() {
        assertEquals(observed(), observed().begin("1-1-1-1-1", start, 1.0))
        denyDecode(String(active().encoded()).replace(epoch, "1-1-1-1-1"))
    }

    @Test fun boundsAndUnpairedUnicodeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { NativeReplaySessionState("A".repeat(64), "stream").encoded() }
        assertThrows(IllegalArgumentException::class.java) { NativeReplaySessionState("a".repeat(64), "").encoded() }
        assertThrows(IllegalArgumentException::class.java) { NativeReplaySessionState("a".repeat(64), "\uD800").encoded() }
        assertThrows(IllegalArgumentException::class.java) { NativeReplaySessionState("a".repeat(64), "\uDC00").encoded() }
        assertThrows(IllegalArgumentException::class.java) { NativeReplaySessionState("a".repeat(64), "\uD800x").encoded() }
        assertThrows(IllegalArgumentException::class.java) { NativeReplaySessionState.decode(ByteArray(16_385)) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            NativeReplaySessionState.decode(byteArrayOf(0xFF.toByte()))
        }
    }

    @Test fun validAstralScalarsAndCanonicallyDistinctIdentifiersRemainDistinct() {
        val first = NativeReplaySessionState("a".repeat(64), "\uD83D\uDE80").validate()
        assertEquals(first, NativeReplaySessionState.decode(first.encoded()))
        assertNotEquals(NativeReplaySessionState.samplingHash(key.copy(siteId = "é")),
            NativeReplaySessionState.samplingHash(key.copy(siteId = "e\u0301")))
    }

    @Test fun encodedInputAndOutputCannotMutateDecodedState() {
        val value = active(); val input = value.encoded(); val decoded = NativeReplaySessionState.decode(input)
        input.fill(0); decoded.encoded().fill(0); assertEquals(value, decoded)
    }

    @Test fun invalidObservationDoesNotMutateExistingState() {
        val value = observed(); val before = value.encoded()
        for (rate in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -0.01, 1.01)) {
            assertThrows(IllegalArgumentException::class.java) { value.observe(key, rate, 60, start, null, null) }
        }
        assertThrows(IllegalArgumentException::class.java) { value.observe(key, 1.0, 60, start, null, -1) }
        assertArrayEquals(before, value.encoded())
    }
}
