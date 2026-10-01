package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.EluRateLimitingOptions
import dev.elu.analytics.EluEvent
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RuntimeEventFilterQueueTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val clock = object : RuntimeCaptureClock {
        var wall = Instant.parse(NOW).toEpochMilli()
        override fun wallNowEpochMillis() = wall
        var expireNext = false
        override fun elapsedRealtimeNanos(): Long = if (expireNext) { expireNext = false; 1_000_000_000_000L } else 1000L
    }
    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)
    @After fun close() { owners.asReversed().forEach { runCatching { it.closeAsync().await() } } }
    private fun fresh() = PersistedCoreState(
        identity = IdentityState(revision = 2, contextRevision = 5, anonymousId = "anon_capture", userId = "user_capture",
            groups = emptyMap(), superProperties = mapOf("super" to "original", "secret" to "private"), session = null, optedOut = false, updatedAt = ISSUED),
        stream = StreamState(streamId = "stream_capture", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
    private fun open(backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(), burst: Double = 2.0,
        count: Int = 100, filter: RuntimeEventFilter = RuntimeEventFilter(), wrap: (RuntimeQueueDatabase) -> RuntimeQueueDatabase = { it }): RuntimeQueueOwner =
        RuntimeQueueOwner.open("rate-${UUID.randomUUID()}", RuntimeQueueLimits(count, 1_000_000),
            { wrap(backing.connection()) }, ::fresh, trustedSiteKey = "elu_pk_test_capture", captureClock = clock,
            personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY, rateLimiting = EluRateLimitingOptions(1.0, burst), eventFilter = filter)
            .await().also { owners += it }
    private fun command(name: String = "ordinary") = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, name, NOW,
        emptyMap(), StandaloneRuntime.defaultVersions())
    private fun authorize(owner: RuntimeQueueOwner) {
        val revision = owner.snapshot().await().state.identity.contextRevision
        val json = JSONObject(resource("privacy-allowed.json")).put("contextRevision", revision)
        val parsed = V1StrictCanonicalJson.parse(json.toString()) as V1StrictCanonicalJson.Value.ObjectValue
        json.put("effectivePolicyHash", V1StrictCanonicalJson.sha256(V1StrictCanonicalJson.Value.ObjectValue(
            parsed.members.filterNot { it.first == "effectivePolicyHash" })))
        assertTrue(owner.submitCaptureAuthority(resource("config-enabled.json"), json.toString()).await() is RuntimeCaptureAuthorityUpdateResult.Activated)
    }
    private fun resource(name: String) = checkNotNull(javaClass.classLoader?.getResource("contracts/v1/fixtures/$name")).readText()
    private fun reject(result: RuntimeCaptureResult, reason: RuntimeCaptureRejection) = assertEquals(reason, (result as RuntimeCaptureResult.Rejected).reason)
    private fun events(owner: RuntimeQueueOwner) = owner.peek(100, Long.MAX_VALUE).await().filterIsInstance<RuntimeQueuedRecord.Event>()

    @Test fun `hook observes final merge outside transaction once across rollback and stamps owned metadata`() {
        val backing = FakeRuntimeQueueBacking(); var inTransaction = false; var calls = 0; var fail = true
        val owner = open(backing, filter = RuntimeEventFilter(listOf("secret"), EluEvent.Filter {
            assertFalse(inTransaction)
            calls++
            assertEquals("event override", it.properties["super"])
            assertFalse(it.properties.containsKey("secret"))
            it.properties.remove("super")
            it.properties["safe"] = "retained"
            it.properties["\$device_id"] = "forged"
            it.event = "filtered"
            it
        }), wrap = { db -> object : RuntimeQueueDatabase by db {
            override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
                check(!inTransaction); inTransaction = true
                try { return db.transaction { tx -> block(object : RuntimeQueueTransaction by tx {
                    override fun insertRecord(record: RuntimeStoredRecord) {
                        if (fail) { fail = false; throw ProvenNotCommittedRuntimeTransactionException("rollback", IOException()) }
                        tx.insertRecord(record)
                    }
                }) } } finally { inTransaction = false }
            }
        } })
        authorize(owner)
        val result = owner.capture(command().copy(properties = mapOf("super" to "event override"))).await()
        assertTrue(result is RuntimeCaptureResult.Accepted); assertEquals(1, calls)
        val record = events(owner).single().record
        assertEquals("filtered", record.name); assertFalse(record.properties.containsKey("super"))
        assertFalse(record.properties.containsKey("secret")); assertEquals("retained", record.properties["safe"])
        assertEquals("anon_capture", record.properties["\$device_id"])
        assertEquals(1.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
    }

    @Test fun `dropped hook consumes no event session sequence or exposure and preserves rate debit`() {
        val backing = FakeRuntimeQueueBacking(); var calls = 0
        val owner = open(backing, filter = RuntimeEventFilter(callback = EluEvent.Filter { calls++; null }))
        authorize(owner); val before = owner.snapshot().await()
        reject(owner.capture(command()).await(), RuntimeCaptureRejection.FILTER_DROPPED)
        val after = owner.snapshot().await()
        assertEquals(before.state, after.state); assertEquals(before.exposures, after.exposures)
        assertTrue(events(owner).isEmpty()); assertEquals(1, calls)
        assertEquals(1.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
    }

    @Test fun `callback source withdrawal and later original intent fail before first durable write`() {
        var current = true; var calls = 0
        val owner = open(filter = RuntimeEventFilter(callback = EluEvent.Filter { calls++; current = false; it },
            admission = { { current } }))
        authorize(owner); val before = owner.snapshot().await()
        val attempt = RuntimeCaptureRateAttempt()
        reject(owner.capture(command(), attempt).await(), RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED)
        authorize(owner)
        reject(owner.capture(command(), attempt).await(), RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED)
        assertEquals(1, calls); assertEquals(before.state, owner.snapshot().await().state)
        assertTrue(events(owner).isEmpty())
    }

    @Test fun `original restriction is checked after append and rolled back when revoked inside transaction`() {
        var current = true; var writes = 0
        val owner = open(filter = RuntimeEventFilter(callback = EluEvent.Filter { it }, admission = { { current } }),
            wrap = { db -> object : RuntimeQueueDatabase by db {
                override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = db.transaction { tx ->
                    block(object : RuntimeQueueTransaction by tx {
                        override fun insertRecord(record: RuntimeStoredRecord) {
                            tx.insertRecord(record); writes++; current = false
                        }
                    })
                }
            } })
        authorize(owner); val before = owner.snapshot().await()
        reject(owner.capture(command()).await(), RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED)
        assertEquals(1, writes); assertTrue(events(owner).isEmpty())
        assertEquals(before.state, owner.snapshot().await().state)
    }

    @Test fun `committed unknown acknowledgement keeps one filtered event and one callback`() {
        val backing = FakeRuntimeQueueBacking(); var calls = 0
        val owner = open(backing, filter = RuntimeEventFilter(callback = EluEvent.Filter {
            calls++; it.event = "one"; backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT; it
        }))
        authorize(owner)
        // The callback arms the fault after the separate rate debit and read-only preflight.
        assertTrue(owner.capture(command()).await() is RuntimeCaptureResult.Accepted)
        assertEquals(1, calls); assertEquals("one", events(owner).single().record.name)
    }

    @Test fun `automatic network path is filtered but cannot rename provenance or introduce person writes`() {
        for (mode in 0..2) {
            var calls = 0
            val owner = open(filter = RuntimeEventFilter(callback = EluEvent.Filter {
                calls++; it.properties.remove("url")
                if (mode == 1) it.event = "ordinary"
                if (mode == 2) it.set = mutableMapOf("person" to "unsupported")
                it
            }))
            authorize(owner)
            val original = owner.snapshot().await().state.identity
            val command = command("\$network_request").copy(properties = mapOf("url" to "secret"),
                networkExpectation = RuntimeNetworkExpectation(original.revision, original.contextRevision, null, null) { true })
            val result = owner.capture(command).await(); assertEquals(1, calls)
            when (mode) {
                0 -> { assertTrue(result is RuntimeCaptureResult.Accepted); assertFalse(events(owner).single().record.properties.containsKey("url")) }
                1 -> reject(result, RuntimeCaptureRejection.FILTER_INVALID)
                else -> reject(result, RuntimeCaptureRejection.FILTER_PERSON_UNSUPPORTED)
            }
        }
    }

    companion object {
        const val NOW = "2026-08-04T00:01:00.000Z"
        const val ISSUED = "2026-08-04T00:00:00.000Z"
    }
}
