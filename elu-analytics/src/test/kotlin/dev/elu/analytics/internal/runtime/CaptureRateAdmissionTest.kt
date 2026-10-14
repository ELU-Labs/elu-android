package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.EluRateLimitingOptions
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

class CaptureRateAdmissionTest {
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
            groups = emptyMap(), superProperties = emptyMap(), session = null, optedOut = false, updatedAt = ISSUED),
        stream = StreamState(streamId = "stream_capture", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
    private fun open(backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(), burst: Double = 2.0,
        count: Int = 100, wrap: (RuntimeQueueDatabase) -> RuntimeQueueDatabase = { it }): RuntimeQueueOwner =
        RuntimeQueueOwner.open("rate-${UUID.randomUUID()}", RuntimeQueueLimits(count, 1_000_000),
            { wrap(backing.connection()) }, ::fresh, trustedSiteKey = "elu_pk_test_capture", captureClock = clock,
            personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY, rateLimiting = EluRateLimitingOptions(1.0, burst))
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

    @Test fun `source refusal is free but invalid canonical name consumes and warning uses ordinary stamps`() {
        val backing = FakeRuntimeQueueBacking(); val owner = open(backing, burst = 1.0)
        reject(owner.capture(command()).await(), RuntimeCaptureRejection.AUTHORITY_ABSENT)
        assertEquals(1.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
        authorize(owner)
        reject(owner.capture(command("")).await(), RuntimeCaptureRejection.EVENT_INVALID)
        assertEquals(RuntimeReplayAudienceState.Unseen, backing.core!!.replayAudience)
        assertNull(owner.snapshot().await().state.identity.session)
        reject(owner.capture(command()).await(), RuntimeCaptureRejection.RATE_LIMITED)
        val warning = events(owner).single().record
        assertEquals("\$\$client_ingestion_warning", warning.name)
        assertEquals("Analytics SDK client rate limited. Config is set to 1 events per second and 1 events burst limit.",
            warning.properties["\$\$client_ingestion_warning_message"])
        assertEquals("anon_capture", warning.properties["\$device_id"])
        reject(owner.capture(command("\$\$client_ingestion_warning")).await(), RuntimeCaptureRejection.RATE_LIMITED)
        assertEquals(1, events(owner).size)
    }

    @Test fun `event quota and rollback never refund independent debit`() {
        val backing = FakeRuntimeQueueBacking(); val owner = open(backing, count = 1)
        authorize(owner)
        assertTrue(owner.capture(command()).await() is RuntimeCaptureResult.Accepted)
        reject(owner.capture(command("quota")).await(), RuntimeCaptureRejection.QUEUE_LIMIT)
        assertEquals(0.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
        reject(owner.capture(command()).await(), RuntimeCaptureRejection.RATE_LIMITED)
        assertEquals(1, events(owner).size) // Warning is quota-governed too.
        val failed = FakeRuntimeQueueBacking(); var fail = true
        val other = open(failed, burst = 1.0, wrap = { db -> object : RuntimeQueueDatabase by db {
            override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = db.transaction { tx ->
                block(object : RuntimeQueueTransaction by tx {
                    override fun insertRecord(record: RuntimeStoredRecord) {
                        if (fail) { fail = false; throw IOException("event failed before commit") }
                        tx.insertRecord(record)
                    }
                })
            }
        } })
        authorize(other)
        assertThrows(java.util.concurrent.ExecutionException::class.java) { other.capture(command()).await() }
        assertEquals(0.0, failed.captureRateState!!.bucket!!.tokens, 0.0)
        assertEquals(RuntimeReplayAudienceState.Unseen, failed.core!!.replayAudience)
    }

    @Test fun `proved event retry and authority renewal reuse one original debit`() {
        val backing = FakeRuntimeQueueBacking(); var fail = true
        val owner = open(backing, wrap = { db -> object : RuntimeQueueDatabase by db {
            override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = db.transaction { tx ->
                block(object : RuntimeQueueTransaction by tx {
                    override fun insertRecord(record: RuntimeStoredRecord) {
                        if (fail) { fail = false; throw ProvenNotCommittedRuntimeTransactionException("rolled back", IOException()) }
                        tx.insertRecord(record)
                    }
                })
            }
        } })
        val attempt = RuntimeCaptureRateAttempt(); val command = command()
        reject(owner.capture(command, attempt).await(), RuntimeCaptureRejection.AUTHORITY_ABSENT)
        authorize(owner)
        assertTrue(owner.capture(command, attempt).await() is RuntimeCaptureResult.Accepted)
        assertEquals(1.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
        assertEquals(1, events(owner).size)
        assertFalse(fail)
    }

    @Test fun `identity consent and reopen preserve empty budget and do not warn again`() {
        val backing = FakeRuntimeQueueBacking(); val owner = open(backing, burst = 1.0)
        authorize(owner); owner.capture(command()).await()
        owner.applyConsent(RuntimeLocalStateChange.SetOptedOut(true, NOW)).await()
        owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(NOW, true)).await()
        reject(owner.capture(command()).await(), RuntimeCaptureRejection.OPTED_OUT)
        owner.applyConsent(RuntimeLocalStateChange.SetOptedOut(false, NOW)).await()
        owner.closeAsync().await()
        val reopened = open(backing, burst = 1.0); authorize(reopened)
        val before = events(reopened).size
        reject(reopened.capture(command()).await(), RuntimeCaptureRejection.RATE_LIMITED)
        assertEquals(before, events(reopened).size)
        assertEquals(0.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
    }

    @Test fun `optional known IO uses held but a later durable read wins`() {
        val backing = FakeRuntimeQueueBacking(); val owner = open(backing, burst = 1.0); authorize(owner)
        val unavailable = RuntimeCaptureRateStorageUnavailable(IOException("optional"))
        backing.failNextRateRead = unavailable; backing.failNextRateWrite = unavailable
        assertTrue(owner.capture(command("held")).await() is RuntimeCaptureResult.Accepted)
        assertEquals(1.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
        assertTrue(owner.capture(command("durable-wins")).await() is RuntimeCaptureResult.Accepted)
        assertEquals(0.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
    }

    @Test fun `structural missing row and unknown debit commit stop original owner`() {
        for (ambiguous in listOf(false, true)) {
            val backing = FakeRuntimeQueueBacking(); val owner = open(backing); authorize(owner)
            if (ambiguous) backing.ambiguousNextRateWrite = true else backing.captureRateState = null
            assertThrows(java.util.concurrent.ExecutionException::class.java) { owner.capture(command()).await() }
            assertThrows(java.util.concurrent.ExecutionException::class.java) { owner.capture(command()).await() }
            assertTrue(backing.records.isEmpty())
            assertEquals(1, backing.connectionCalls)
        }
    }

    @Test fun `passive warning does not prolong session and first network warning cannot create one`() {
        for (performance in listOf(false, true)) {
            val owner = open(burst = 1.0); authorize(owner)
            val first = owner.capture(command()).await() as RuntimeCaptureResult.Accepted
            val identity = first.snapshot.state.identity
            val passive = if (performance) command("\$performance_sample").copy(
                expectation = RuntimeCaptureExpectation(identity.revision, identity.contextRevision, identity.session!!.id) { true })
            else command("\$network_request").copy(networkExpectation = RuntimeNetworkExpectation(identity.revision,
                identity.contextRevision, identity.session!!.id, identity.session!!.startedAt) { true })
            reject(owner.capture(passive).await(), RuntimeCaptureRejection.RATE_LIMITED)
            assertEquals(identity.session, owner.snapshot().await().state.identity.session)
            assertEquals(2, events(owner).size)
        }
        val owner = open(burst = 1.0); authorize(owner)
        owner.capture(command("")).await()
        val identity = owner.snapshot().await().state.identity
        reject(owner.capture(command("\$network_request").copy(networkExpectation = RuntimeNetworkExpectation(
            identity.revision, identity.contextRevision, null, null) { true })).await(), RuntimeCaptureRejection.RATE_LIMITED)
        assertNull(owner.snapshot().await().state.identity.session)
        assertTrue(events(owner).isEmpty())
    }


    @Test fun `a late authority refusal after debit renews without charging the same call twice`() {
        var arm = false
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing, wrap = { db -> object : RuntimeQueueDatabase by db {
            override fun <T> captureRateTransaction(block: (RuntimeQueueTransaction) -> T): T {
                var wrote = false
                val result = db.captureRateTransaction { tx -> block(object : RuntimeQueueTransaction by tx {
                    override fun writeCaptureRateState(state: RuntimeCaptureRateState) { tx.writeCaptureRateState(state); wrote = true }
                }) }
                if (wrote && arm) { arm=false; clock.expireNext=true }
                return result
            }
        } })
        authorize(owner); arm=true
        val attempt = RuntimeCaptureRateAttempt(); val original = command()
        reject(owner.capture(original, attempt).await(), RuntimeCaptureRejection.AUTHORITY_EXPIRED)
        assertEquals(1.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
        authorize(owner)
        assertTrue(owner.capture(original, attempt).await() is RuntimeCaptureResult.Accepted)
        assertEquals(1.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
        assertEquals(1, events(owner).size)
    }

    @Test fun `duplicate or withdrawn exposure is rejected before budget or warning`() {
        for (full in listOf(false, true)) {
        val backing = FakeRuntimeQueueBacking()
        if (full) {
            val seed = open(backing, burst=1.0); seed.closeAsync().await()
            val digests = (0 until MAX_RUNTIME_FLAG_EXPOSURES).map { "%064x".format(it) }
            val ledger = JSONObject().put("streamId", "stream_capture").put("anonymousId", "anon_capture")
                .put("digests", org.json.JSONArray(digests)).toString().toByteArray()
            backing.core = backing.core!!.copy(exposures = RuntimeFlagExposureState.decode(ledger))
        }
        val owner = open(backing, burst=1.0); authorize(owner)
        val client = dev.elu.analytics.internal.flags.AndroidFeatureFlagClient(owner, StandaloneRuntime.defaultVersions(),
            dev.elu.analytics.internal.flags.FlagTransport { request ->
                val body = JSONObject(String(request.canonicalBody, Charsets.UTF_8))
                val result = JSONObject().put("schemaVersion", 1).put("requestId", body.getString("requestId"))
                    .put("contextRevision", body.getLong("contextRevision"))
                    .put("identityRevision", body.getJSONObject("identity").getLong("revision"))
                    .put("flagsRevision", "rate-flags").put("evaluatedAt", NOW).put("expiresAt", "2026-08-04T00:04:00.000Z")
                    .put("flags", JSONObject().put("variant", false)).put("payloads", JSONObject())
                dev.elu.analytics.internal.concurrent.SdkFuture.completedFuture(result.toString().toByteArray())
            }, object : dev.elu.analytics.internal.flags.FlagClock {
                override fun wallNowEpochMillis() = clock.wall
                override fun monotonicNowNanos() = 1000L
            }, dev.elu.analytics.internal.flags.FlagOpaqueIdSource { UUID.randomUUID().toString() },
            dev.elu.analytics.internal.flags.FlagOpaqueIdSource { UUID.randomUUID().toString() })
        client.use {
            client.applyConfiguration(resource("config-enabled.json")).await()
            assertTrue(client.reload().await() is dev.elu.analytics.internal.flags.FlagReloadResult.Updated)
            val read = client.read("variant").await() as dev.elu.analytics.internal.flags.FlagReadResult.Found
            val stale = checkNotNull(RuntimeFlagExposureCapture.from("variant", read, false) { false })
            reject(owner.capture(command("\$feature_flag_called").copy(properties=stale.properties(), flagExposure=stale)).await(),
                RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED)
            assertEquals(1.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
            val report = checkNotNull(RuntimeFlagExposureCapture.from("variant", read, false) { client.isCacheLeaseCurrent(read.cacheLeaseToken) })
            val original = command("\$feature_flag_called").copy(properties=report.properties(), flagExposure=report)
            if (full) {
                // Full ledger is a post-debit quota refusal, unlike a duplicate report.
                reject(owner.capture(original).await(), RuntimeCaptureRejection.QUEUE_LIMIT)
                assertTrue(events(owner).isEmpty())
            } else {
                assertTrue(owner.capture(original).await() is RuntimeCaptureResult.Accepted)
                reject(owner.capture(original).await(), RuntimeCaptureRejection.EXPOSURE_ALREADY_REPORTED)
                assertEquals(1, events(owner).size)
            }
            assertEquals(0.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
        }
        }
    }

    companion object {
        const val NOW = "2026-08-04T00:01:00.000Z"
        const val ISSUED = "2026-08-04T00:00:00.000Z"
    }
}
