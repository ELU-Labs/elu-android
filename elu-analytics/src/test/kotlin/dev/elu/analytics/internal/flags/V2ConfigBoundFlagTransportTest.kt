package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.V1FlagAuthorizationResolution
import dev.elu.analytics.internal.config.V2ConfigAuthorityGate
import dev.elu.analytics.internal.config.V2ConfigLifecycleUpdate
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.runtime.FakeRuntimeQueueBacking
import dev.elu.analytics.internal.runtime.RuntimeCaptureClock
import dev.elu.analytics.internal.runtime.RuntimeFlagStoredRow
import dev.elu.analytics.internal.runtime.RuntimeLocalStateChange
import dev.elu.analytics.internal.runtime.RuntimeQueueDatabase
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeQueueTransaction
import dev.elu.analytics.internal.runtime.StandaloneRuntime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConfigBoundFlagTransportTest {
    @Test fun `unbound requests cannot use a live configuration token`() = Rig().use { rig ->
        val result = rig.transport.send(FlagTransportRequest(ENDPOINT, "{}".toByteArray())).toFuture()
        assertTrue(result.isCompletedExceptionally)
        assertEquals(0, rig.connections.size)
    }

    @Test fun `frozen flag client request reaches HTTP and owner validates the returned witness`() = Rig().use { rig ->
        val result = rig.reloadQueued()
        rig.worker.runNext()
        assertTrue(result.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertTrue(rig.connections.single().output.size() > 0)
        val read = rig.client.read("variant").get(2, TimeUnit.SECONDS)
        assertTrue(read is FlagReadResult.Found)
    }

    @Test fun `identity change after send queue admission prevents HTTP body write`() = Rig().use { rig ->
        val result = rig.reloadQueued()
        rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("tier" to "new"), NOW)).get(2, TimeUnit.SECONDS)
        rig.worker.runNext()
        assertFalse(result.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertEquals(0, rig.connections.single().output.size())
    }

    @Test fun `source withdrawal after queue admission prevents request and cache publication`() = Rig().use { rig ->
        val result = rig.reloadQueued()
        rig.publish(null)
        rig.worker.runNext()
        assertFalse(result.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertEquals(0, rig.connections.single().output.size())
        assertEquals(FlagReadResult.Missing, rig.client.read("variant").get(2, TimeUnit.SECONDS))
    }

    @Test fun `rotation waits for the canceled physical slot before constructing another delegate`() = Rig().use { rig ->
        val first = rig.reloadQueued()
        rig.body = JSONObject(rig.body).put("revision", "renewed").put("issuedAt", NOW)
            .put("expiresAt", "2026-08-05T00:06:00.000Z").toString()
        rig.publish(rig.body)
        rig.transport.retireSuperseded()
        first.get(2, TimeUnit.SECONDS)
        rig.apply()
        assertFalse(rig.client.reload().get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertEquals(1, rig.connections.size)
        rig.worker.runNext() // canceled physical request has now settled
        assertEquals(0, rig.connections.first().output.size())
        val second = rig.reloadQueued()
        assertEquals(2, rig.connections.size)
        rig.worker.runNext()
        assertTrue(second.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
    }

    @Test fun `withdrawal during response completion cannot commit or expose cache`() = Rig().use { rig ->
        val result = rig.reloadQueued()
        rig.connections.single().onResponse = { rig.publish(null) }
        rig.worker.runNext()
        assertFalse(result.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertEquals(FlagReadResult.Missing, rig.client.read("variant").get(2, TimeUnit.SECONDS))
    }

    @Test fun `transport close cancels queued work and cannot open another socket`() = Rig().use { rig ->
        val result = rig.reloadQueued()
        rig.transport.close()
        rig.worker.runNext()
        assertFalse(result.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertEquals(0, rig.connections.single().output.size())
        assertFalse(rig.client.reload().get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertEquals(1, rig.connections.size)
    }

    @Test fun `withdrawal after durable cache commit cannot return Updated`() = Rig().use { rig ->
        val result = rig.reloadQueued()
        rig.afterFlagWrite = { rig.publish(null) }
        rig.worker.runNext()
        assertFalse(result.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertTrue(rig.connections.single().output.size() > 0)
        assertTrue(rig.backing.flagRows.keys.any { it.startsWith("cache-body:") })
        assertEquals(FlagReadResult.Missing, rig.client.read("variant").get(2, TimeUnit.SECONDS))
    }

    @Test fun `withdrawal after cached row read cannot return its value`() = Rig().use { rig ->
        val result = rig.reloadQueued(); rig.worker.runNext()
        assertTrue(result.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        rig.afterFlagTransaction = { rig.publish(null) }
        assertEquals(FlagReadResult.Missing, rig.client.read("variant").get(2, TimeUnit.SECONDS))
    }

    @Test fun `cache expiry during storage read cannot return a value or extend original deadline`() = Rig().use { rig ->
        val result = rig.reloadQueued(); rig.worker.runNext()
        val updated = result.get(2, TimeUnit.SECONDS) as FlagReloadResult.Updated
        assertTrue(rig.client.isCacheLeaseCurrent(checkNotNull(updated.cacheLeaseToken)))
        rig.afterFlagTransaction = { rig.nanos += 60_000_000_000L }
        assertEquals(FlagReadResult.Missing, rig.client.read("variant").get(2, TimeUnit.SECONDS))
        assertFalse(rig.client.isCacheLeaseCurrent(checkNotNull(updated.cacheLeaseToken)))
    }

    @Test fun `wall cache expiry during durable finalization cannot return Updated`() = Rig().use { rig ->
        val result = rig.reloadQueued()
        rig.afterFlagWrite = { rig.wall += 60_000L }
        rig.worker.runNext()
        assertFalse(result.get(2, TimeUnit.SECONDS) is FlagReloadResult.Updated)
        assertEquals(FlagReadResult.Missing, rig.client.read("variant").get(2, TimeUnit.SECONDS))
    }

    @Test fun `synchronous projection clock observations cannot regress without a client-lane read`() = Rig().use { rig ->
        val result = rig.reloadQueued(); rig.worker.runNext()
        val token = checkNotNull((result.get(2, TimeUnit.SECONDS) as FlagReloadResult.Updated).cacheLeaseToken)
        rig.nanos += 1_000L
        assertTrue(rig.client.isCacheLeaseCurrent(token))
        rig.nanos -= 1L
        assertFalse(rig.client.isCacheLeaseCurrent(token))
        rig.nanos += 2L
        assertFalse(rig.client.isCacheLeaseCurrent(token))
        assertEquals(FlagReadResult.Missing, rig.client.read("variant").get(2, TimeUnit.SECONDS))
    }

    private class Rig : AutoCloseable {
        @Volatile var wall = NOW_EPOCH
        @Volatile var nanos = 100L
        val gate = V2ConfigAuthorityGate()
        var generation = 0L
        var body = resource("contracts/v2/fixtures/config-enabled.json")
        val worker = Worker()
        val connections = mutableListOf<Connection>()
        val transport = V2ConfigBoundFlagTransport(KEY, gate) { key, endpoint ->
            val connection = Connection().also { connections.add(it) }
            HttpURLConnectionFlagTransport(key, endpoint, elapsedRealtimeNanos = { 100L },
                scheduleDeadline = { _, _ -> AutoCloseable { } }, executor = worker,
                connectionFactory = { connection })
        }
        val backing = FakeRuntimeQueueBacking()
        @Volatile var afterFlagTransaction: (() -> Unit)? = null
        @Volatile var afterFlagWrite: (() -> Unit)? = null
        val owner = RuntimeQueueOwner.open(
            "flag-composition-" + UUID.randomUUID(), RuntimeQueueLimits(100, 1_000_000),
            databaseFactory = {
                val original = backing.connection()
                object : RuntimeQueueDatabase by original {
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
                        var touchedFlags = false
                        var wroteFlags = false
                        val result = original.transaction { transaction ->
                            block(object : RuntimeQueueTransaction by transaction {
                                override fun readFlagRow(key: String): RuntimeFlagStoredRow? {
                                    touchedFlags = true
                                    return transaction.readFlagRow(key)
                                }
                                override fun putFlagRow(row: RuntimeFlagStoredRow) {
                                    if (row.key.startsWith("cache-body:")) wroteFlags = true
                                    touchedFlags = true
                                    transaction.putFlagRow(row)
                                }
                            })
                        }
                        if (wroteFlags) afterFlagWrite?.also { afterFlagWrite = null }?.invoke()
                        if (touchedFlags) afterFlagTransaction?.also { afterFlagTransaction = null }?.invoke()
                        return result
                    }
                }
            }, legacyStateLoader = { initialState() }, trustedSiteKey = KEY,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = wall
                override fun elapsedRealtimeNanos() = 100L
            },
        ).get(2, TimeUnit.SECONDS).also { it.bindConfigurationGate(gate).get(2, TimeUnit.SECONDS) }
        val client = AndroidFeatureFlagClient(owner, StandaloneRuntime.defaultVersions(), transport,
            object : FlagClock {
                override fun wallNowEpochMillis() = wall
                override fun monotonicNowNanos() = nanos
            }, FlagOpaqueIdSource { UUID.randomUUID().toString() }, FlagOpaqueIdSource { UUID.randomUUID().toString() }, configurationGate = gate)
        init { publish(body); apply() }
        fun publish(document: String?) {
            val expected = ++generation
            gate.update(V2ConfigLifecycleUpdate(expected) { consumer ->
                if (generation != expected) false else { consumer(document); true }
            })
        }
        fun apply() {
            assertTrue(client.applyConfiguration(body).get(2, TimeUnit.SECONDS) is V1FlagAuthorizationResolution.Allowed)
        }
        fun reloadQueued(): SdkFuture<FlagReloadResult> {
            val result = client.reload()
            worker.awaitQueued()
            return result
        }
        override fun close() {
            gate.close(); transport.close(); client.close()
            runCatching { owner.closeAsync().get(2, TimeUnit.SECONDS) }
        }
    }

    private class Worker : Executor {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun awaitQueued() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (tasks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
            assertFalse("HTTP worker was never queued", tasks.isEmpty())
        }
        fun runNext() { checkNotNull(tasks.poll()).run() }
    }
    private class Connection : HttpURLConnection(URL("https://ingest.elu.dev/v1/flags")) {
        val output = ByteArrayOutputStream()
        var onResponse: (() -> Unit)? = null
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = Unit
        override fun getOutputStream() = output
        override fun getResponseCode() = 200
        override fun getInputStream(): ByteArrayInputStream {
            val request = JSONObject(output.toString(Charsets.UTF_8.name()))
            val response = JSONObject().put("schemaVersion", 1).put("requestId", request.getString("requestId"))
                .put("contextRevision", request.getLong("contextRevision"))
                .put("identityRevision", request.getJSONObject("identity").getLong("revision"))
                .put("flagsRevision", "flags-1").put("evaluatedAt", NOW)
                .put("expiresAt", "2026-08-05T00:02:00.000Z")
                .put("flags", JSONObject().put("variant", "a")).put("payloads", JSONObject())
            onResponse?.invoke()
            return ByteArrayInputStream(response.toString().toByteArray())
        }
    }
    private companion object {
        val KEY = "elu_pk_live_" + "A".repeat(26)
        val ENDPOINT = URI("https://ingest.elu.dev/v1/flags")
        const val NOW = "2026-08-05T00:01:00.000Z"
        val NOW_EPOCH = Instant.parse(NOW).toEpochMilli()
        fun resource(path: String) = checkNotNull(V2ConfigBoundFlagTransportTest::class.java.classLoader?.getResourceAsStream(path)).bufferedReader().use { it.readText() }
        fun initialState() = PersistedCoreState(
            identity = IdentityState(revision = 1, contextRevision = 1, anonymousId = "anon_composition", userId = null,
                groups = emptyMap(), superProperties = emptyMap(), session = null, optedOut = false,
                updatedAt = "2026-08-05T00:00:00.000Z"),
            stream = StreamState(streamId = "stream_composition", nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )
    }
}
