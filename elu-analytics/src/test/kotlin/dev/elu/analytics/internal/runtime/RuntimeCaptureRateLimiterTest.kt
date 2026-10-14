package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluOptions
import dev.elu.analytics.EluPersistenceMode
import dev.elu.analytics.EluRateLimitingOptions
import org.junit.Assert.*
import org.junit.Test

class RuntimeCaptureRateLimiterTest {
    @Test fun `defaults normalization fractional settings and constructor selection`() {
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val options = EluRateLimitingOptions(invalid, invalid)
            assertEquals(10.0, options.eventsPerSecond, 0.0)
            assertEquals(100.0, options.eventsBurstLimit, 0.0)
        }
        assertEquals(0.5, EluRateLimitingOptions(0.5, 0.2).eventsBurstLimit, 0.0)
        assertEquals(Double.MAX_VALUE, EluRateLimitingOptions(Double.MAX_VALUE).eventsBurstLimit, 0.0)
        val rate = EluRateLimitingOptions(0.5, 2.5)
        val options = EluOptions(rate, persistence = EluPersistenceMode.MEMORY)
        assertSame(rate, options.rateLimiting)
        assertEquals(EluPersistenceMode.MEMORY, options.persistence)
        assertEquals(10.0, EluOptions().rateLimiting.eventsPerSecond, 0.0)
    }

    @Test fun `check-only does not debit and warning occurs only on transition`() {
        val limiter = RuntimeCaptureRateLimiter(EluRateLimitingOptions(1.0, 2.0))
        var bucket = limiter.check(null, 1000.0, true).bucket
        assertEquals(2.0, bucket.tokens, 0.0)
        repeat(2) { bucket = limiter.check(bucket, 1000.0).also { assertFalse(it.limited) }.bucket }
        assertTrue(limiter.check(bucket, 1000.0).warn)
        assertFalse(limiter.check(bucket, 1000.0).warn)
        bucket = limiter.check(bucket, 2000.0).also { assertFalse(it.limited) }.bucket
        assertTrue(limiter.check(bucket, 2000.0).warn)
        val reopened = RuntimeCaptureRateLimiter(EluRateLimitingOptions(1.0, 2.0))
        reopened.check(bucket, 2000.0, true)
        assertFalse(reopened.check(bucket, 2000.0).warn)
    }

    @Test fun `backwards wall creates debt and readable durable bucket wins over held`() {
        val limiter = RuntimeCaptureRateLimiter(EluRateLimitingOptions(1.0, 2.0))
        val debt = limiter.check(RuntimeCaptureRateBucket(0.0, 5000.0), 1000.0)
        assertEquals(-4.0, debt.bucket.tokens, 0.0)
        assertTrue(limiter.check(null, 2000.0).limited)
        // The browser rereads storage before held state even when the prior optional write failed.
        val fromDisk = limiter.check(RuntimeCaptureRateBucket(2.0, 2000.0), 2000.0)
        assertFalse(fromDisk.limited)
        assertEquals(1.0, fromDisk.bucket.tokens, 0.0)
    }

    @Test fun `extreme finite arithmetic stays bounded and invalid clock refuses`() {
        val limiter = RuntimeCaptureRateLimiter(EluRateLimitingOptions(Double.MAX_VALUE))
        assertEquals(-Double.MAX_VALUE, limiter.check(RuntimeCaptureRateBucket(0.0, Double.MAX_VALUE), -Double.MAX_VALUE).bucket.tokens, 0.0)
        assertFalse(limiter.check(RuntimeCaptureRateBucket(0.0, -Double.MAX_VALUE), Double.MAX_VALUE).limited)
        assertThrows(IllegalArgumentException::class.java) { limiter.check(null, Double.NaN) }
    }

    @Test fun `bucket bytes are closed canonical finite and stream validated`() {
        for (bucket in listOf(null, RuntimeCaptureRateBucket(-3.5, 12345.0))) {
            val row = RuntimeCaptureRateState("stream_rate", bucket)
            assertEquals(row, RuntimeCaptureRateState.decode(row.streamId, row.encodeBucket()))
        }
        for (bad in listOf("{}", "{\"tokens\":\"1\",\"last\":0}", "{\"tokens\":1,\"last\":0,\"extra\":1}", " null", "x".repeat(257))) {
            assertThrows(RuntimeQueueCorruptionException::class.java) { RuntimeCaptureRateState.decode("stream_rate", bad.toByteArray()) }
        }
    }

    @Test fun `attempt can renew exact command on same owner but no other call`() {
        val owner = Any(); val attempt = RuntimeCaptureRateAttempt()
        val command = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "one", "2026-08-04T00:01:00.000Z", emptyMap(), StandaloneRuntime.defaultVersions())
        assertTrue(attempt.claim(owner, command))
        assertFalse(attempt.claim(owner, command.copy()))
        assertThrows(IllegalStateException::class.java) { attempt.claim(Any(), command) }
        assertThrows(IllegalStateException::class.java) { attempt.claim(owner, command.copy(name = "two")) }
    }
}
