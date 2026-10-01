package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.EluRateLimitingOptions
import dev.elu.analytics.EluEvent
import dev.elu.analytics.internal.config.*
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
        count: Int = 100, filter: RuntimeEventFilter = RuntimeEventFilter(),
        profiles: EluPersonProfilesMode = EluPersonProfilesMode.IDENTIFIED_ONLY,
        wrap: (RuntimeQueueDatabase) -> RuntimeQueueDatabase = { it }): RuntimeQueueOwner =
        RuntimeQueueOwner.open("rate-${UUID.randomUUID()}", RuntimeQueueLimits(count, 1_000_000),
            { wrap(backing.connection()) }, ::fresh, trustedSiteKey = "elu_pk_test_capture", captureClock = clock,
            personProfiles = profiles, rateLimiting = EluRateLimitingOptions(1.0, burst), eventFilter = filter)
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

    @Test fun `automatic network path is filtered preserves provenance and continues original person output`() {
        for (mode in 0..2) {
            var calls = 0
            val owner = open(filter = RuntimeEventFilter(callback = EluEvent.Filter {
                calls++; it.properties.remove("url")
                if (mode == 1) it.event = "ordinary"
                if (mode == 2) it.set = mutableMapOf("person" to "filtered")
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
                else -> {
                    assertTrue(result is RuntimeCaptureResult.Accepted)
                    assertFalse((result as RuntimeCaptureResult.Accepted).personMutationRejected)
                    val rows = owner.peek(100, Long.MAX_VALUE).await()
                    assertEquals(2, rows.size); assertTrue(rows[0] is RuntimeQueuedRecord.Event)
                    val mutation = (rows[1] as RuntimeQueuedRecord.Mutation).envelope.mutation
                    assertEquals(mapOf("person" to "filtered"), (mutation.change as RuntimeMutationChange.SetPersonProperties).set)
                    assertEquals(1L, mutation.sequence)
                }
            }
        }
    }

    @Test fun `automatic accepted event survives refused person capacity chronology and postwrite restriction`() {
        for (mode in 0..2) {
            var current = true; var calls = 0
            val owner = open(count = if (mode == 0) 1 else 100,
                filter = RuntimeEventFilter(callback = EluEvent.Filter {
                    calls++; it.set = mutableMapOf("tier" to "paid")
                    if (mode == 1) it.timestamp = java.util.Date(clock.wall + 1_000)
                    it
                }, admission = { { current } }), wrap = { db -> object : RuntimeQueueDatabase by db {
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = db.transaction { tx ->
                        block(object : RuntimeQueueTransaction by tx {
                            override fun insertRecord(record: RuntimeStoredRecord) {
                                tx.insertRecord(record)
                                if (mode == 2 && record.sequence == 1L) current = false
                            }
                        })
                    }
                } })
            authorize(owner)
            val accepted = owner.capture(command()).await() as RuntimeCaptureResult.Accepted
            assertTrue(accepted.personMutationRejected); assertEquals(1, calls)
            val rows = owner.peek(100, Long.MAX_VALUE).await()
            assertEquals(listOf(accepted.record), rows)
            assertEquals(1L, owner.snapshot().await().state.stream.nextSequence)
            assertTrue(owner.snapshot().await().state.flagContext.personProperties.isEmpty())
        }
    }

    @Test fun `never profile refuses person continuation without dropping the accepted event`() {
        val owner = open(profiles = EluPersonProfilesMode.NEVER,
            filter = RuntimeEventFilter(callback = EluEvent.Filter { it.set = mutableMapOf("person" to true); it }))
        authorize(owner)
        assertTrue(owner.capture(command()).await() is RuntimeCaptureResult.Accepted)
        assertEquals(1, owner.peek(100, Long.MAX_VALUE).await().size)
        assertTrue(owner.snapshot().await().state.flagContext.personProperties.isEmpty())
    }

    @Test fun `recursive warning owns its accepted person continuation while outer rate limited capture stays rejected`() {
        val names = mutableListOf<String>()
        val owner = open(burst = 1.0, filter = RuntimeEventFilter(callback = EluEvent.Filter {
            names += it.event; if (it.event == "\$\$client_ingestion_warning") it.setOnce = mutableMapOf("warning" to true); it
        }))
        authorize(owner)
        assertTrue(owner.capture(command()).await() is RuntimeCaptureResult.Accepted)
        reject(owner.capture(command()).await(), RuntimeCaptureRejection.RATE_LIMITED)
        assertEquals(listOf("ordinary", "\$\$client_ingestion_warning"), names)
        val rows = owner.peek(100, Long.MAX_VALUE).await()
        assertEquals(3, rows.size); assertTrue(rows[0] is RuntimeQueuedRecord.Event); assertTrue(rows[1] is RuntimeQueuedRecord.Event)
        assertEquals(mapOf("warning" to true), ((rows[2] as RuntimeQueuedRecord.Mutation).envelope.mutation.change
            as RuntimeMutationChange.SetPersonProperties).setOnce)
    }

    @Test fun `mutation callback runs once outside transaction and original postwrite admission rolls back properties`() {
        var inside = false; var current = true; var calls = 0
        val owner = open(filter = RuntimeEventFilter(callback = EluEvent.Filter {
            assertFalse(inside); calls++; it.properties["\$set"] = mutableMapOf("safe" to true); it
        }), wrap = { db -> object : RuntimeQueueDatabase by db {
            override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
                inside = true
                try { return db.transaction { tx -> block(object : RuntimeQueueTransaction by tx {
                    override fun insertRecord(record: RuntimeStoredRecord) { tx.insertRecord(record); current = false }
                }) } } finally { inside = false }
            }
        } })
        authorize(owner); val before = owner.snapshot().await()
        val result = owner.appendMutations(listOf(RuntimeRecordDraft.Mutation(NOW,
            RuntimeMutationChange.SetPersonProperties(mapOf("private" to true), emptyMap(), emptyList()),
            StandaloneRuntime.defaultVersions())), { current }, true).await()
        assertEquals(RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE, (result as RuntimeAppendResult.Rejected).reason)
        assertEquals(1, calls); assertEquals(before.state, owner.snapshot().await().state)
        assertTrue(owner.peek(100, Long.MAX_VALUE).await().isEmpty())
    }

    @Test fun `committed ambiguous person mutation preserves one original event and one transformed mutation`() {
        val backing = FakeRuntimeQueueBacking(); var calls = 0; var armed = false
        val owner = open(backing, filter = RuntimeEventFilter(callback = EluEvent.Filter {
            calls++; it.set = mutableMapOf("person" to true); it
        }), wrap = { db -> object : RuntimeQueueDatabase by db {
            override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = db.transaction { tx ->
                block(object : RuntimeQueueTransaction by tx {
                    override fun insertRecord(record: RuntimeStoredRecord) {
                        tx.insertRecord(record)
                        if (record.sequence == 1L && !armed) { armed = true; backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT }
                    }
                })
            }
        } })
        authorize(owner)
        val result = owner.capture(command()).await() as RuntimeCaptureResult.Accepted
        assertFalse(result.personMutationRejected); assertEquals(1, calls)
        assertEquals(listOf(0L, 1L), owner.peek(100, Long.MAX_VALUE).await().map { it.sequence })
    }

    @Test fun `fixed original background handoff filters and continues person output after foreground witness closes`() {
        val key = "elu_pk_live_" + "A".repeat(26)
        val config = checkNotNull(javaClass.classLoader?.getResource("contracts/v2/fixtures/config-enabled.json")).readText()
        val wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
        val sourceClock = object : V2ConfigClock {
            override fun wallNowEpochMillis() = wall
            override fun monotonicNowNanos() = 100L
        }
        val gate = V2ConfigAuthorityGate()
        val tasks = ArrayDeque<() -> Unit>()
        val worker = object : V2ConfigLifecycleWorker {
            override fun execute(task: () -> Unit) { tasks.add(task) }
            override fun interruptCurrent() = Unit
            override fun close() = Unit
        }
        val source = V2ConfigSource("https://elu.dev", key, V2ConfigTransport { V2ConfigHttpResponse(200, config) }, sourceClock)
        val driver = V2ConfigLifecycleDriver(source, gate::update, sourceClock,
            object : V2ConfigLifecycleScheduler {
                override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
                override fun close() = Unit
            }, worker)
        lateinit var facade: dev.elu.analytics.internal.facade.StandaloneFacade
        val names = mutableListOf<String>()
        val filter = RuntimeEventFilter(callback = EluEvent.Filter {
            names += it.event
            if (it.event == StandaloneRuntime.APPLICATION_BACKGROUNDED_EVENT) it.set = mutableMapOf("handoff" to true)
            it
        })
        val backing = FakeRuntimeQueueBacking()
        val owner = RuntimeQueueOwner.open("filter-background-${UUID.randomUUID()}", RuntimeQueueLimits(100, 1_000_000),
            { backing.connection() }, { fresh().let { it.copy(identity = it.identity.copy(updatedAt = "2026-08-05T00:00:00Z")) } },
            trustedSiteKey = key, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = wall
                override fun elapsedRealtimeNanos() = 100L
            }, eventFilter = filter.boundTo { facade.eventFilterAdmission() }).await()
        owners += owner; owner.bindConfigurationGate(gate).await()
        val runtime = StandaloneRuntime(owner, key, wallClock = { wall }, configurationGate = gate,
            transportFactory = { dev.elu.analytics.internal.runtime.delivery.BatchHTTPTransport {
                dev.elu.analytics.internal.runtime.delivery.BatchHTTPResponse(503, ByteArray(0))
            } }, deviceInEuTimezone = { false })
        facade = dev.elu.analytics.internal.facade.StandaloneFacade(
            open = { dev.elu.analytics.internal.facade.StandaloneStack(runtime, owner, null) },
            deliverCallback = { it.run() }, wallClock = { wall }, configurationGate = gate,
            personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY, eventFilter = filter)
        try {
            facade.start(); driver.start(); tasks.removeFirst().invoke(); facade.configurationChanged(); facade.settled().await()
            assertTrue(facade.state() is dev.elu.analytics.internal.facade.EluFacadeState.Enabled)
            val original = checkNotNull(gate.snapshot())
            checkNotNull(runtime.applicationBackgrounded(driver, Instant.ofEpochMilli(wall).toString())).await()
            assertFalse(original.isCurrent())
            val rows = owner.peek(100, Long.MAX_VALUE).await()
            assertEquals(listOf(StandaloneRuntime.APPLICATION_BACKGROUNDED_EVENT), names)
            assertEquals(2, rows.size)
            assertEquals(mapOf("handoff" to true), ((rows[1] as RuntimeQueuedRecord.Mutation).envelope.mutation.change
                as RuntimeMutationChange.SetPersonProperties).set)
            assertEquals(SessionLifecycle.BACKGROUND, owner.snapshot().await().state.identity.session?.lifecycle)
        } finally { facade.close(); driver.close(); gate.close() }
    }

    companion object {
        const val NOW = "2026-08-04T00:01:00.000Z"
        const val ISSUED = "2026-08-04T00:00:00.000Z"
    }
}
