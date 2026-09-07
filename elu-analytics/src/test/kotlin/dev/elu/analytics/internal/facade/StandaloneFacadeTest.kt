package dev.elu.analytics.internal.facade

import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.flags.AndroidFeatureFlagClient
import dev.elu.analytics.internal.flags.FlagClock
import dev.elu.analytics.internal.flags.FlagOpaqueIdSource
import dev.elu.analytics.internal.flags.FlagTransport
import dev.elu.analytics.internal.runtime.FakeRuntimeQueueBacking
import dev.elu.analytics.internal.runtime.RuntimeCaptureClock
import dev.elu.analytics.internal.runtime.RuntimeEventKind
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeQueuedRecord
import dev.elu.analytics.internal.runtime.StandaloneRuntime
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPRequest
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPResponse
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPTransport
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StandaloneFacadeTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val facades = mutableListOf<StandaloneFacade>()
    private val keyCounter = AtomicInteger()
    private val callbacks = mutableListOf<String>()

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        facades.asReversed().forEach { facade -> runCatching { facade.close() } }
        owners.forEach { owner -> runCatching { owner.closeAsync().get(5, TimeUnit.SECONDS) } }
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test
    fun `every state-changing method reaches the standalone runtime`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())

        harness.facade.capture("checkout", mapOf("amount" to 42), Date(NOW_MS))
        harness.facade.screen("Home", mapOf("source" to "tab"))
        harness.facade.captureException(IllegalStateException("boom"), mapOf("handled" to true))
        harness.facade.identify("user_1", mapOf("plan" to "growth"))
        harness.facade.alias("alias_1")
        harness.facade.register(mapOf("plan" to "growth", "seat" to "owner"))
        harness.facade.unregister("seat")
        harness.facade.setPersonProperties(mapOf("role" to "admin"))
        harness.facade.group("organization", "org_1", mapOf("tier" to "design-partner"))
        harness.facade.setPersonPropertiesForFlags(mapOf("plan" to "growth"))
        harness.facade.setGroupPropertiesForFlags("organization", mapOf("tier" to "design-partner"))
        harness.settle()

        assertEquals(EluFacadeState.Enabled, harness.facade.state())
        assertEquals(
            listOf(
                "event:checkout",
                "event:Home",
                "event:\$exception",
                "mutation:identify",
                "mutation:linkAlias",
                "mutation:setPersonProperties",
                "mutation:associateGroup",
                "mutation:setGroupProperties",
            ),
            harness.queued(),
        )
        val state = harness.owner.snapshot().get().state
        assertEquals("user_1", state.identity.userId)
        assertEquals(mapOf("plan" to "growth"), state.identity.superProperties)
        assertEquals(mapOf("organization" to "org_1"), state.identity.groups)
        // Person properties reach the flag context through the mutation as well as the explicit
        // properties-for-flags call.
        assertEquals(mapOf("role" to "admin", "plan" to "growth"), state.flagContext.personProperties)
        assertEquals(
            mapOf("organization" to mapOf("tier" to "design-partner")),
            state.flagContext.groupProperties,
        )
        val screen = harness.records()[1] as RuntimeQueuedRecord.Event
        assertEquals(RuntimeEventKind.SCREEN, screen.record.kind)
        assertEquals("Home", screen.record.properties["\$screen_name"])
        assertEquals("user_1", harness.facade.distinctId())
        assertEquals(emptyMap<EluFacadeDropReason, Int>(), harness.diagnostics().dropped)
    }

    @Test
    fun `the capture keeps its call-time stamp and its explicit properties`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())
        harness.facade.capture("checkout", mapOf("amount" to 42), Date(NOW_MS - 5_000))
        harness.settle()

        val event = (harness.records().single() as RuntimeQueuedRecord.Event).record
        assertEquals("2026-08-04T00:00:55.000Z", event.occurredAt)
        assertEquals(42, event.properties["amount"])
        assertEquals("android", event.versions.platform.wireValue)
    }

    @Test
    fun `calls made before the first configuration decision replay in order`() {
        val harness = harness()

        harness.facade.capture("first", null, Date(NOW_MS))
        harness.facade.identify("user_1", null)
        harness.facade.capture("second", null, Date(NOW_MS))
        harness.settle()
        assertEquals(EluFacadeState.Pending, harness.facade.state())
        assertEquals(3, harness.diagnostics().buffered)
        assertTrue(harness.queued().isEmpty())

        harness.facade.applyConfiguration(config())
        harness.settle()

        assertEquals(
            listOf("event:first", "mutation:identify", "event:second"),
            harness.queued(),
        )
        assertEquals(0, harness.diagnostics().buffered)
    }

    @Test
    fun `the hold buffer drops the newest calls beyond its cap`() {
        val harness = harness(bufferLimit = 3)

        repeat(5) { index -> harness.facade.capture("event-$index", null, Date(NOW_MS)) }
        harness.facade.applyConfiguration(config())
        harness.settle()

        assertEquals(listOf("event:event-0", "event:event-1", "event:event-2"), harness.queued())
        assertEquals(2, harness.diagnostics().dropped[EluFacadeDropReason.BUFFER_FULL])
    }

    @Test
    fun `a disabled configuration discards activity calls and stores nothing`() {
        val harness = harness()
        harness.facade.applyConfiguration(config("config-disabled.json"))

        harness.facade.capture("checkout", null, Date(NOW_MS))
        harness.facade.identify("user_1", null)
        harness.settle()

        assertEquals(EluFacadeState.Disabled(EluFacadeDisabledReason.UNAUTHORIZED), harness.facade.state())
        assertTrue(harness.queued().isEmpty())
        assertEquals(2, harness.diagnostics().dropped[EluFacadeDropReason.UNAUTHORIZED])
        assertNull(harness.facade.distinctId())
        assertNull(harness.facade.getFeatureFlag("variant"))
        assertFalse(harness.facade.isFeatureEnabled("variant"))
    }

    @Test
    fun `an EU-blocked device is disabled for the region and not for authorization`() {
        val harness = harness(deviceInEu = true)
        harness.facade.applyConfiguration(config())

        harness.facade.capture("checkout", null, Date(NOW_MS))
        harness.settle()

        assertEquals(EluFacadeState.Disabled(EluFacadeDisabledReason.BLOCKED), harness.facade.state())
        assertTrue(harness.queued().isEmpty())
        assertEquals(1, harness.diagnostics().dropped[EluFacadeDropReason.BLOCKED])
        assertTrue(harness.transport.requests.isEmpty())
    }

    @Test
    fun `reserved properties never reach an event or the super properties`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())

        harness.facade.capture(
            "checkout",
            mapOf("amount" to 42, "distinct_id" to "spoofed", "\$session_id" to "spoofed", "\$elu_sdk" to "spoofed"),
            Date(NOW_MS),
        )
        harness.facade.register(mapOf("plan" to "growth", "\$user_id" to "spoofed"))
        harness.facade.unregister("\$user_id")
        harness.settle()

        val event = (harness.records().first() as RuntimeQueuedRecord.Event).record
        assertEquals(mapOf<String, Any?>("amount" to 42), event.properties)
        assertEquals(
            mapOf("plan" to "growth"),
            harness.owner.snapshot().get().state.identity.superProperties,
        )
        assertEquals(4, harness.diagnostics().dropped[EluFacadeDropReason.RESERVED_PROPERTY])
    }

    @Test
    fun `reset ends the identity and clears the loaded flags`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())
        harness.facade.identify("user_1", null)
        harness.settle()
        val anonymousBefore = harness.owner.snapshot().get().state.identity.anonymousId

        harness.facade.reset()
        harness.settle()

        val identity = harness.owner.snapshot().get().state.identity
        assertNull(identity.userId)
        assertTrue(identity.anonymousId != anonymousBefore)
        assertEquals(identity.anonymousId, harness.facade.distinctId())
        assertNull(harness.facade.getFeatureFlag("variant"))
    }

    @Test
    fun `flag listeners fire in registration order and a late listener fires once`() {
        val harness = harness()
        harness.facade.onFeatureFlagsLoaded { callbacks += "first" }
        harness.facade.onFeatureFlagsLoaded { callbacks += "second" }
        harness.facade.applyConfiguration(config())
        harness.settle()
        assertEquals(listOf("first", "second"), callbacks)

        harness.facade.onFeatureFlagsLoaded { callbacks += "late" }
        harness.settle()
        assertEquals(listOf("first", "second", "late"), callbacks)

        harness.facade.reloadFeatureFlags { callbacks += "completion" }
        harness.settle()
        assertEquals(
            listOf("first", "second", "late", "first", "second", "late", "completion"),
            callbacks,
        )
    }

    @Test
    fun `a flag read reports the stored value and exposes it once per identity`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())
        harness.settle()

        // The owned client resolves one key at a time, so the first read observes the key and the
        // value is reported from the next read onwards.
        assertNull(harness.facade.getFeatureFlag("variant"))
        harness.settle()

        assertEquals("variant-a", harness.facade.getFeatureFlag("variant"))
        assertEquals(mapOf("buttonColor" to "violet"), harness.facade.getFeatureFlagPayload("variant"))
        assertTrue(harness.facade.isFeatureEnabled("variant"))
        assertFalse(harness.facade.isFeatureEnabled("bool-false"))
        harness.settle()

        val exposures = harness.exposures()
        assertEquals(1, exposures.size)
        assertEquals("variant", exposures.single()["\$feature_flag"])
        assertEquals("variant-a", exposures.single()["\$feature_flag_response"])

        harness.facade.getFeatureFlag("variant")
        harness.settle()
        assertEquals(1, harness.exposures().size)

        harness.facade.identify("user_2", null)
        harness.settle()
        harness.facade.getFeatureFlag("variant")
        harness.settle()
        assertEquals(2, harness.exposures().size)
    }

    @Test
    fun `flag getters report defaults until a load completes`() {
        val harness = harness()

        assertNull(harness.facade.getFeatureFlag("variant"))
        assertNull(harness.facade.getFeatureFlagPayload("variant"))
        assertFalse(harness.facade.isFeatureEnabled("variant"))
        harness.settle()
        assertTrue(harness.queued().isEmpty())
        assertTrue(harness.flagTransport.requests.isEmpty())
    }

    @Test
    fun `a reload and a flush are commands that never replay from the hold buffer`() {
        val harness = harness()

        harness.facade.reloadFeatureFlags { callbacks += "completion" }
        harness.facade.flush()
        harness.settle()
        assertEquals(2, harness.diagnostics().dropped[EluFacadeDropReason.UNAUTHORIZED])
        assertTrue(callbacks.isEmpty())

        harness.facade.applyConfiguration(config())
        harness.settle()
        assertTrue(callbacks.isEmpty())
        assertEquals(0, harness.diagnostics().buffered)
    }

    // ---- harness -------------------------------------------------------------

    private fun harness(
        bufferLimit: Int = StandaloneFacade.PRE_INIT_BUFFER_LIMIT,
        deviceInEu: Boolean = false,
    ): Harness {
        val backing = FakeRuntimeQueueBacking()
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = "facade-${keyCounter.incrementAndGet()}",
                limits = RuntimeQueueLimits(10_000, 16_777_216),
                databaseFactory = backing::connection,
                legacyStateLoader = ::initialState,
                trustedSiteKey = SITE_KEY,
                captureClock = FixedCaptureClock,
            ).get(10, TimeUnit.SECONDS)
        owners += owner
        val transport = RecordingBatchTransport()
        val runtime =
            StandaloneRuntime(
                owner = owner,
                siteKey = SITE_KEY,
                wallClock = { NOW_MS },
                transportFactory = { transport },
                deviceInEuTimezone = { deviceInEu },
            )
        val flagTransport = RespondingFlagTransport()
        val flags =
            AndroidFeatureFlagClient(
                owner,
                StandaloneRuntime.defaultVersions(),
                flagTransport,
                FixedFlagClock,
                FlagOpaqueIdSource { "flags_request_${flagTransport.requestIds.incrementAndGet()}" },
                FlagOpaqueIdSource { "store_epoch_1" },
            )
        val facade =
            StandaloneFacade(
                open = { StandaloneStack(runtime, owner, flags) },
                deliverCallback = { callback -> callback.run() },
                wallClock = { NOW_MS },
                bufferLimit = bufferLimit,
            )
        facades += facade
        facade.start()
        return Harness(owner, facade, transport, flagTransport)
    }

    private class Harness(
        val owner: RuntimeQueueOwner,
        val facade: StandaloneFacade,
        val transport: RecordingBatchTransport,
        val flagTransport: RespondingFlagTransport,
    ) {
        fun settle() {
            // Flag reloads and identity writes queue further work on the same lane, so drain until
            // the lane comes back empty twice in a row.
            repeat(6) { facade.settled().get(10, TimeUnit.SECONDS) }
        }

        fun diagnostics(): EluFacadeDiagnostics = facade.diagnostics().get(10, TimeUnit.SECONDS)

        fun records(): List<RuntimeQueuedRecord> = owner.peek(1_000, Long.MAX_VALUE).get(10, TimeUnit.SECONDS)

        fun queued(): List<String> =
            records().map { record ->
                when (record) {
                    is RuntimeQueuedRecord.Event -> "event:${record.record.name}"
                    is RuntimeQueuedRecord.Mutation -> "mutation:${record.envelope.mutation.change.type}"
                }
            }

        /** The `$feature_flag_called` events the facade queued, oldest first. */
        fun exposures(): List<Map<String, Any?>> =
            records()
                .filterIsInstance<RuntimeQueuedRecord.Event>()
                .filter { record -> record.record.name == StandaloneFacade.FEATURE_FLAG_CALLED_EVENT }
                .map { record -> record.record.properties }
    }

    private class RecordingBatchTransport : BatchHTTPTransport {
        val requests = mutableListOf<BatchHTTPRequest>()

        @Synchronized
        override fun execute(request: BatchHTTPRequest): BatchHTTPResponse {
            requests += request
            return BatchHTTPResponse(503, ByteArray(0))
        }
    }

    /** Answers each flag request from the witness it carries, so no fixture revision can drift. */
    private class RespondingFlagTransport : FlagTransport {
        val requests = mutableListOf<JSONObject>()
        val requestIds = AtomicInteger()
        val revisions = AtomicInteger()

        @Synchronized
        override fun send(request: dev.elu.analytics.internal.flags.FlagTransportRequest): CompletableFuture<ByteArray> {
            val body = JSONObject(String(request.canonicalBody, StandardCharsets.UTF_8))
            requests += body
            val response =
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("requestId", body.getString("requestId"))
                    .put("contextRevision", body.getLong("contextRevision"))
                    .put("identityRevision", body.getJSONObject("identity").getLong("revision"))
                    .put("flagsRevision", "flags-${revisions.incrementAndGet()}")
                    .put("evaluatedAt", NOW)
                    .put("expiresAt", "2026-08-04T00:04:00.000Z")
                    .put(
                        "flags",
                        JSONObject()
                            .put("variant", "variant-a")
                            .put("bool-false", false),
                    )
                    .put("payloads", JSONObject().put("variant", JSONObject().put("buttonColor", "violet")))
            return CompletableFuture.completedFuture(response.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    private object FixedCaptureClock : RuntimeCaptureClock {
        override fun wallNowEpochMillis(): Long = NOW_MS

        override fun elapsedRealtimeNanos(): Long = 1_000L
    }

    private object FixedFlagClock : FlagClock {
        override fun wallNowEpochMillis(): Long = NOW_MS

        override fun monotonicNowNanos(): Long = 1_000_000_000L
    }

    private fun initialState(): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 1,
                    contextRevision = 1,
                    anonymousId = "anon_facade",
                    userId = null,
                    groups = emptyMap(),
                    superProperties = emptyMap(),
                    session = null,
                    optedOut = false,
                    updatedAt = ISSUED,
                ),
            stream = StreamState(streamId = "stream_facade", nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )

    private fun config(name: String = "config-enabled.json"): String =
        checkNotNull(javaClass.classLoader?.getResource("contracts/v1/fixtures/$name")).readText()

    private fun <T> Future<T>.get(): T = get(10, TimeUnit.SECONDS)

    private companion object {
        const val SITE_KEY = "elu_pk_test_facade"
        const val ISSUED = "2026-08-04T00:00:00.000Z"
        const val NOW = "2026-08-04T00:01:00.000Z"
        val NOW_MS: Long = Instant.parse(NOW).toEpochMilli()
    }
}
