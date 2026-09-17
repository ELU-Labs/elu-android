package dev.elu.analytics.internal.config

import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConfigSourceTest {
    @Test
    fun `debug config source accepts approved loopback but retains original data endpoint restrictions`() {
        val key = "elu_pk_test_${"A".repeat(26)}"
        var body = config().toString()
        val source = V2ConfigSource(
            "http://127.0.0.1:8787", key,
            transport = V2ConfigTransport { endpoint ->
                assertEquals("http://127.0.0.1:8787/sdk/v2/$key/config", endpoint.toString())
                ok(body)
            },
            clock = FakeClock(),
            debuggable = true,
        )
        try {
            assertEquals(V2ConfigSourceResult.Document(body, V1ConfigStatus.ENABLED), source.refresh())
            body = config().put("issuedAt", "2026-08-05T00:00:01Z")
                .apply { getJSONObject("endpoints").put("events", "http://127.0.0.1:8787/v1/events") }.toString()
            assertTrue(source.refresh() is V2ConfigSourceResult.Unavailable)
            assertNull(source.currentDocument())
        } finally { source.close() }
    }

    @Test
    fun `validated v2 body is data and remains byte exact through its live lease`() {
        val body = config().toString(2)
        val source = source { ok(body) }
        assertEquals(V2ConfigSourceResult.Document(body, V1ConfigStatus.ENABLED), source.refresh())
        assertEquals(body, source.currentDocument())
    }

    @Test
    fun `valid inactive documents remain available so consumers can install their exact boundary`() {
        listOf("disabled", "revoked").forEach { status ->
            val body = inactive(status).toString()
            val source = source { ok(body) }
            assertEquals(V2ConfigSourceResult.Document(body, V1ConfigStatus.entries.single { it.wireValue == status }), source.refresh())
            assertEquals(body, source.currentDocument())
        }
    }

    @Test
    fun `fetch failures withdraw without making the previous document stale while revalidating`() {
        var fail = false
        val body = config().toString()
        val source = source { if (fail) throw IOException("offline") else ok(body) }
        assertTrue(source.refresh() is V2ConfigSourceResult.Document)
        fail = true
        assertEquals(V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.TRANSPORT), source.refresh())
        assertNull(source.currentDocument())
        fail = false
        assertEquals(V2ConfigSourceResult.Document(body, V1ConfigStatus.ENABLED), source.refresh())
    }

    @Test
    fun `every non200 response withdraws a prior config even with a valid response body`() {
        listOf(301, 304, 401, 403, 404, 429, 503).forEach { status ->
            var response = ok(config().toString())
            val source = source { response }
            source.refresh()
            response = V2ConfigHttpResponse(status, config().toString())
            assertEquals(V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.HTTP), source.refresh())
            assertNull(source.currentDocument())
        }
    }

    @Test
    fun `v1 unknown schema malformed unsafe endpoint and oversized documents fail closed`() {
        val foreign = config().apply { getJSONObject("endpoints").put("events", "https://evil.test/v1/events") }
        listOf(
            resource("contracts/v1/fixtures/config-enabled.json"),
            config().put("schemaVersion", 3).toString(),
            "{broken",
            foreign.toString(),
            " ".repeat(V2_CONFIG_MAXIMUM_RESPONSE_BYTES + 1),
        ).forEach { body ->
            var response = ok(config().toString())
            val source = source { response }
            source.refresh()
            response = ok(body)
            assertTrue(body.take(32), source.refresh() is V2ConfigSourceResult.Unavailable)
            assertNull(source.currentDocument())
        }
    }

    @Test
    fun `validity rejects future issuance expiry and more than exactly ten minutes`() {
        val future = config().put("issuedAt", "2026-08-05T00:01:00.0000000001Z")
        val tooLong = config().put("expiresAt", "2026-08-05T00:10:00.0000000001Z")
        val expired = config().put("expiresAt", "2026-08-05T00:01:00.000Z")
        listOf(future, tooLong, expired).forEach { body ->
            val source = source { ok(body.toString()) }
            assertTrue(source.refresh() is V2ConfigSourceResult.Unavailable)
            assertNull(source.currentDocument())
        }
        val exact = config().put("expiresAt", "2026-08-05T00:10:00.000Z")
        assertTrue(source { ok(exact.toString()) }.refresh() is V2ConfigSourceResult.Document)
    }

    @Test
    fun `wall and monotonic expiry independently withdraw at the boundary`() {
        listOf(false, true).forEach { monotonicOnly ->
            val clock = FakeClock()
            val source = source(clock) { ok(config().toString()) }
            source.refresh()
            if (monotonicOnly) clock.nanos += 240_000_000_000L else clock.wall += 240_000L
            assertNull(source.currentDocument())
            assertTrue(source.refresh() is V2ConfigSourceResult.Unavailable)
        }
    }

    @Test
    fun `same document never extends its monotonic deadline after failure or expiry`() {
        val clock = FakeClock()
        var offline = false
        val source = source(clock) { if (offline) throw IOException("offline") else ok(config().toString()) }
        source.refresh()
        clock.nanos += 120_000_000_000L
        source.refresh()
        offline = true
        source.refresh()
        assertNull(source.currentDocument())
        offline = false
        clock.nanos += 119_999_999_999L
        assertTrue(source.refresh() is V2ConfigSourceResult.Document)
        clock.nanos++
        assertNull(source.currentDocument())
        assertEquals(
            V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.CONFIG, V1ConfigRejection.EXPIRED),
            source.refresh(),
        )
    }

    @Test
    fun `response delay cannot extend first lease when wall time stalls`() {
        val clock = FakeClock()
        val source = source(clock) {
            clock.nanos += 240_000_000_000L
            ok(config().toString())
        }
        assertEquals(
            V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.CONFIG, V1ConfigRejection.EXPIRED),
            source.refresh(),
        )
        assertNull(source.currentDocument())
    }

    @Test
    fun `foreign endpoint is rejected on first install before any consumer can receive it`() {
        val body = config().apply { getJSONObject("endpoints").put("events", "https://evil.test/v1/events") }.toString()
        assertEquals(
            V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.CONFIG, V1ConfigRejection.UNAUTHORIZED),
            source { ok(body) }.refresh(),
        )
    }

    @Test
    fun `clock rollback fails closed and cannot be restored on the same source`() {
        listOf(false, true).forEach { monotonic ->
            val clock = FakeClock()
            val source = source(clock) { ok(config().toString()) }
            source.refresh()
            if (monotonic) clock.nanos-- else clock.wall--
            assertNull(source.currentDocument())
            clock.wall = NOW
            clock.nanos = 100L
            assertEquals(V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.CLOCK), source.refresh())
        }
    }

    @Test
    fun `newer revocation survives stale enabled document and failed request`() {
        val enabled = config().toString()
        val revoked = inactive("revoked").put("issuedAt", "2026-08-05T00:00:30.000Z").toString()
        var response = ok(enabled)
        val source = source { response }
        source.refresh()
        response = ok(revoked)
        assertEquals(V2ConfigSourceResult.Document(revoked, V1ConfigStatus.REVOKED), source.refresh())
        response = ok(enabled)
        assertEquals(V2ConfigSourceResult.Superseded, source.refresh())
        assertEquals(revoked, source.currentDocument())
        response = V2ConfigHttpResponse(503, null)
        source.refresh()
        response = ok(enabled)
        assertEquals(V2ConfigSourceResult.Superseded, source.refresh())
        assertNull(source.currentDocument())
    }

    @Test
    fun `conflicting same-time configuration poisons that boundary until a newer document`() {
        val first = config().toString()
        var response = ok(first)
        val source = source { response }
        source.refresh()
        response = ok(config().put("revision", "conflicting").toString())
        assertEquals(V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.CONFIG, V1ConfigRejection.CONFLICT), source.refresh())
        response = ok(first)
        assertTrue(source.refresh() is V2ConfigSourceResult.Unavailable)
        response = ok(config().put("issuedAt", "2026-08-05T00:00:30.000Z").toString())
        assertTrue(source.refresh() is V2ConfigSourceResult.Document)
    }

    @Test
    fun `later success or failure fences an earlier transport completion`() {
        listOf(false to false, true to false, false to true).forEach { (laterFailure, firstFailure) ->
            withBlockedFirst(laterFailure, firstFailure) { source, released, pending ->
                val later = source.refresh()
                if (laterFailure) assertTrue(later is V2ConfigSourceResult.Unavailable)
                else assertTrue(later is V2ConfigSourceResult.Document)
                released.countDown()
                assertEquals(V2ConfigSourceResult.Superseded, pending.get(2, TimeUnit.SECONDS))
                if (laterFailure) assertNull(source.currentDocument())
                else assertEquals("newer", JSONObject(source.currentDocument()!!).getString("revision"))
            }
        }
    }

    @Test
    fun `close fences an outstanding response and prevents any further network call`() {
        withBlockedFirst { source, released, pending ->
            source.close()
            released.countDown()
            assertEquals(V2ConfigSourceResult.Superseded, pending.get(2, TimeUnit.SECONDS))
            assertEquals(V2ConfigSourceResult.Unavailable(V2ConfigSourceFailure.CLOSED), source.refresh())
            assertNull(source.currentDocument())
        }
    }

    @Test
    fun `withdrawal preserves exact lease snapshot and cancels older in-flight decisions`() {
        val clock = FakeClock()
        val body = config().toString()
        val source = source(clock) { ok(body) }
        source.refresh()
        val original = checkNotNull(source.currentLeaseSnapshot())
        assertEquals(body, original.body)
        source.withdraw()
        assertNull(source.currentLeaseSnapshot())
        clock.nanos += 120_000_000_000L
        assertTrue(source.refresh() is V2ConfigSourceResult.Document)
        assertEquals(original.monotonicDeadlineNanos, source.currentLeaseSnapshot()?.monotonicDeadlineNanos)
        source.withdraw()
        clock.nanos += 120_000_000_000L
        assertTrue(source.refresh() is V2ConfigSourceResult.Unavailable)
        assertNull(source.currentLeaseSnapshot())
        withBlockedFirst { pendingSource, released, pending ->
            pendingSource.withdraw()
            released.countDown()
            assertEquals(V2ConfigSourceResult.Superseded, pending.get(2, TimeUnit.SECONDS))
            assertNull(pendingSource.currentLeaseSnapshot())
        }
    }

    private fun withBlockedFirst(
        laterFailure: Boolean = false,
        firstFailure: Boolean = false,
        run: (V2ConfigSource, CountDownLatch, java.util.concurrent.Future<V2ConfigSourceResult>) -> Unit,
    ) {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val calls = AtomicInteger()
        val source = source {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(released.await(2, TimeUnit.SECONDS))
                if (firstFailure) throw IOException("old request failed")
                ok(config().toString())
            } else if (laterFailure) throw IOException("offline")
            else ok(config().put("revision", "newer").put("issuedAt", "2026-08-05T00:00:30.000Z").toString())
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<V2ConfigSourceResult> { source.refresh() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            run(source, released, pending)
        } finally {
            released.countDown()
            executor.shutdownNow()
            source.close()
        }
    }

    private fun source(clock: FakeClock = FakeClock(), fetch: () -> V2ConfigHttpResponse): V2ConfigSource =
        V2ConfigSource("https://elu.dev", "elu_pk_live_${"A".repeat(26)}", V2ConfigTransport { fetch() }, clock)

    private fun ok(body: String): V2ConfigHttpResponse = V2ConfigHttpResponse(200, body)
    private fun config(): JSONObject = JSONObject(resource("contracts/v2/fixtures/config-enabled.json"))
    private fun inactive(status: String): JSONObject = JSONObject()
        .put("schemaVersion", 2).put("revision", status).put("status", status).put("reason", "test restriction")
        .put("issuedAt", "2026-08-05T00:00:00.000Z").put("expiresAt", "2026-08-05T00:05:00.000Z")
    private fun resource(name: String): String = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }

    private class FakeClock : V2ConfigClock {
        @Volatile var wall = NOW
        @Volatile var nanos = 100L
        override fun wallNowEpochMillis(): Long = wall
        override fun monotonicNowNanos(): Long = nanos
    }

    private companion object {
        val NOW = Instant.parse("2026-08-05T00:01:00.000Z").toEpochMilli()
    }
}
