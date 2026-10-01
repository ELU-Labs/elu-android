package dev.elu.analytics.internal.facade

import dev.elu.analytics.EluFeatureFlagSnapshot
import dev.elu.analytics.EluFeatureFlagSubscription
import dev.elu.analytics.EluCaptureOptions
import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.internal.runtime.RuntimeStartupObserver
import dev.elu.analytics.internal.runtime.RuntimeStartupPhase
import dev.elu.analytics.EluFeatureFlagOptions
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.flags.AndroidFeatureFlagClient
import dev.elu.analytics.internal.flags.FlagClock
import dev.elu.analytics.internal.flags.FlagOpaqueIdSource
import dev.elu.analytics.internal.flags.FlagTransport
import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
import dev.elu.analytics.internal.runtime.RuntimeMutationChange
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
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.Future
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `recording controls are instance local and independent of identity consent or configuration`() {
        val h = harness(autoStart = false)
        val before = h.owner.snapshot().get()
        assertTrue(h.facade.nativeReplayRecordingAllowed()); assertFalse(h.facade.sessionRecordingStarted())
        h.facade.stopSessionRecording(); h.facade.stopSessionRecording()
        assertFalse(h.facade.nativeReplayRecordingAllowed())
        h.facade.start(); h.facade.applyConfiguration(config()); h.settle()
        h.facade.identify("recording-user", null); h.settle()
        h.facade.reset(); h.settle()
        h.facade.optOut(); h.settle(); h.facade.optIn(null, null); h.settle()
        h.facade.applyConfiguration(config()); h.settle()
        assertFalse(h.facade.nativeReplayRecordingAllowed()); assertFalse(h.facade.sessionRecordingStarted())
        val stopped = h.owner.snapshot().get()
        h.facade.startSessionRecording(); h.settle()
        assertTrue(h.facade.nativeReplayRecordingAllowed())
        assertFalse("No collector in this production-runtime harness", h.facade.sessionRecordingStarted())
        assertEquals(stopped.state, h.owner.snapshot().get().state)
        assertNull(before.state.identity.session)
        h.facade.closeAndWait().get(3, TimeUnit.SECONDS)
        h.facade.startSessionRecording(); assertFalse(h.facade.nativeReplayRecordingAllowed())
    }

    @Test
    fun `pending recording stop does not create session or change consent`() {
        val h = harness(autoStart = false); val before = h.owner.snapshot().get().state
        h.facade.stopSessionRecording(); h.facade.start(); h.facade.settled().get()
        assertEquals(before, h.owner.snapshot().get().state)
        assertFalse(h.facade.nativeReplayRecordingAllowed()); assertFalse(h.facade.sessionRecordingStarted())
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
        assertNull(harness.facade.getFeatureFlagSnapshot())
        harness.settle()

        val identity = harness.owner.snapshot().get().state.identity
        assertNull(identity.userId)
        assertTrue(identity.anonymousId != anonymousBefore)
        assertEquals(identity.anonymousId, harness.facade.distinctId())
        assertEquals("variant-a", harness.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
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
    fun `a flag read reports once per visitor and typed value across identify reload and reset`() {
        val harness = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        harness.facade.applyConfiguration(config())
        harness.settle()

        // A complete admitted publication makes the first evaluated key immediately available.
        assertEquals("variant-a", harness.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))

        assertEquals("variant-a", harness.facade.getFeatureFlag("variant"))
        assertEquals(mapOf("buttonColor" to "violet"), harness.facade.getFeatureFlagPayload("variant"))
        assertTrue(harness.facade.isFeatureEnabled("variant"))
        assertFalse(harness.facade.isFeatureEnabled("bool-false"))
        harness.settle()

        val exposures = harness.exposures()
        assertEquals(2, exposures.size)
        val exposure = exposures.single { it["\$feature_flag"] == "variant" }
        assertEquals(false, exposures.single { it["\$feature_flag"] == "bool-false" }["\$feature_flag_response"])
        assertEquals("variant", exposure["\$feature_flag"])
        assertEquals("variant-a", exposure["\$feature_flag_response"])
        assertTrue((exposure["\$feature_flag_request_id"] as String).startsWith("flags_request_"))
        assertEquals(NOW_MS, exposure["\$feature_flag_evaluated_at"])
        assertEquals(false, exposure["\$used_bootstrap_value"])
        assertTrue(exposure.containsKey("\$feature_flag_bootstrapped_response"))
        assertNull(exposure["\$feature_flag_bootstrapped_response"])
        assertNull(exposure["\$feature_flag_bootstrapped_payload"])

        harness.facade.getFeatureFlag("variant")
        harness.settle()
        assertEquals(2, harness.exposures().size)

        harness.facade.identify("user_2", null)
        harness.settle()
        harness.facade.getFeatureFlag("variant")
        harness.settle()
        assertEquals(2, harness.exposures().size)
        harness.facade.reloadFeatureFlags {}; harness.settle()
        harness.facade.getFeatureFlag("variant"); harness.settle()
        assertEquals(2, harness.exposures().size)
        harness.facade.reset(); harness.settle()
        harness.facade.getFeatureFlag("variant"); harness.settle()
        harness.facade.getFeatureFlag("variant"); harness.settle()
        assertEquals(3, harness.exposures().size)
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

    @Test
    fun `held lane preserves API entry chronology for identity and context changes`() {
        val cases = listOf<Pair<String, (StandaloneFacade) -> Unit>>(
            "identify" to { it.identify("user_entry", mapOf("tier" to "test")) },
            "alias" to { it.alias("alias_entry") },
            "reset" to { it.reset() },
            "register" to { it.register(mapOf("entry" to "yes")) },
            "unregister" to { it.register(mapOf("entry" to "yes")); it.unregister("entry") },
            "person" to { it.setPersonProperties(mapOf("entry" to "yes")) },
            "group" to { it.group("company", "entry", mapOf("tier" to "test")) },
            "flag-person" to { it.setPersonPropertiesForFlags(mapOf("entry" to "yes")) },
            "flag-group" to { it.setGroupPropertiesForFlags("company", mapOf("entry" to "yes")) },
        )
        val failures = mutableListOf<String>()
        for ((name, operation) in cases) {
            val clock = AtomicLong(NOW_MS)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val harness = harness(wall = clock::get, beforeOpen = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            })
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                harness.facade.applyConfiguration(config())
                operation(harness.facade)
                clock.addAndGet(5)
                val callerDate = Date(clock.get())
                harness.facade.capture("after-$name", null, callerDate)
                callerDate.time = NOW_MS + 50_000 // Input was already snapshotted.
                clock.set(NOW_MS + 100)
                release.countDown(); harness.settle()
                val records = harness.records()
                val events = records.filterIsInstance<RuntimeQueuedRecord.Event>()
                if (events.singleOrNull()?.record?.name != "after-$name") {
                    failures += "$name: ${harness.queued()} drops=${harness.diagnostics().dropped}"
                } else {
                    assertEquals("2026-08-04T00:01:00.005Z", events.single().record.occurredAt)
                    for (mutation in records.filterIsInstance<RuntimeQueuedRecord.Mutation>()) {
                        assertEquals("2026-08-04T00:01:00.000Z", mutation.envelope.mutation.occurredAt)
                    }
                }
            } finally { release.countDown(); harness.facade.close() }
        }
        assertEquals("No later lane timestamp may make the following caller capture appear stale", emptyList<String>(), failures)
    }

    @Test
    fun `an explicitly stale event timestamp is still denied after an API entry mutation`() {
        val clock = AtomicLong(NOW_MS)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val harness = harness(wall = clock::get, beforeOpen = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            harness.facade.applyConfiguration(config())
            harness.facade.identify("entry-user", null)
            harness.facade.capture("explicitly-stale", null, Date(NOW_MS - 1))
            clock.set(NOW_MS + 100); release.countDown(); harness.settle()
            assertEquals(listOf("mutation:identify"), harness.queued())
        } finally { release.countDown() }
    }

    @Test
    fun `register once preserves values and only replaces absent or explicit defaults`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())
        harness.facade.register(mapOf("plan" to "paid", "placeholder" to "None", "zero" to 0))
        harness.facade.registerOnce(mapOf("plan" to "free", "placeholder" to "ready", "new" to true), "None")
        harness.facade.registerOnce(mapOf("zero" to 9), 0.0)
        harness.facade.registerOnce(mapOf("new" to false), "None")
        harness.settle()
        assertEquals(mapOf("plan" to "paid", "placeholder" to "ready", "zero" to 9, "new" to true),
            harness.owner.snapshot().get().state.identity.superProperties)
    }

    @Test
    fun `pre-start denial commits before lifecycle starts and persists across reset and reopen`() {
        val backing = FakeRuntimeQueueBacking()
        val observed = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val h = harness(backing = backing, autoStart = false, onOpened = { observed.set(it) })
        val handoff = EluConsentHandoff()
        handoff.optOut()
        assertTrue(handoff.isOptedOut())
        handoff.install(h.facade, h.facade::start)
        h.facade.applyConfiguration(config())
        h.facade.capture("forbidden-startup", null, Date(NOW_MS))
        h.facade.reset()
        h.settle()
        assertEquals(true, observed.get())
        assertTrue(h.owner.snapshot().get().state.identity.optedOut)
        assertTrue(h.queued().isEmpty())
        assertTrue(h.transport.requests.isEmpty())
        h.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        val reopened = harness(backing = backing)
        reopened.facade.applyConfiguration(config()); reopened.settle()
        assertTrue(reopened.facade.isOptedOut())
        assertTrue(reopened.owner.snapshot().get().state.identity.optedOut)
    }

    @Test
    fun `latest pre-start choice wins and an invalid opt in cannot erase denial`() {
        for (denyLast in listOf(true, false)) {
            val observed = java.util.concurrent.atomic.AtomicReference<Boolean>()
            val h = harness(autoStart = false, onOpened = { observed.set(it) })
            val handoff = EluConsentHandoff()
            handoff.optOut()
            handoff.optIn("accepted", mapOf("source" to "settings"))
            if (denyLast) { handoff.optOut(); handoff.optIn("", null) }
            assertEquals(denyLast, handoff.isOptedOut())
            handoff.install(h.facade, h.facade::start)
            h.facade.applyConfiguration(config()); h.settle()
            assertEquals(denyLast, observed.get())
            assertEquals(denyLast, h.owner.snapshot().get().state.identity.optedOut)
            // A pre-config opt-in attempt is not replayed when config later arrives.
            assertFalse(h.queued().contains("event:accepted"))
        }
    }

    @Test
    fun `pre-start opt in durably clears existing denial before lifecycle starts`() {
        val backing = FakeRuntimeQueueBacking()
        val initial = harness(backing = backing)
        initial.facade.optOut(); initial.settle(); initial.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        val observed = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val h = harness(backing = backing, autoStart = false, onOpened = { observed.set(it) })
        h.facade.optIn(null, null)
        h.settle() // Consent task can run before start; the pending intent must survive it.
        h.facade.start(); h.settle()
        assertEquals(false, observed.get())
        assertFalse(h.facade.isOptedOut())
        h.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        val reopened = harness(backing = backing); reopened.settle()
        assertFalse(reopened.owner.snapshot().get().state.identity.optedOut)
    }

    @Test
    fun `denial received during open is committed before lifecycle startup`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val observed = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val h = harness(beforeOpen = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) },
            onOpened = { observed.set(it) })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.facade.optIn(null, null); h.facade.optOut()
            assertTrue(h.facade.isOptedOut())
        } finally { release.countDown() }
        h.settle()
        assertEquals(true, observed.get())
        assertTrue(h.owner.snapshot().get().state.identity.optedOut)
    }

    @Test
    fun `later opt in cannot backfill activity submitted while opening under denial`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val h = harness(beforeOpen = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.facade.optOut()
            h.facade.capture("private-during-denial", null, Date(NOW_MS))
            h.facade.screen("private-screen", null)
            h.facade.captureException(IllegalStateException("private-error"), null)
            h.facade.optIn(null, null)
            h.facade.applyConfiguration(config())
        } finally { release.countDown() }
        h.settle()
        assertFalse(h.facade.isOptedOut())
        assertTrue(h.queued().isEmpty())
        assertEquals(3, h.diagnostics().dropped[EluFacadeDropReason.OPTED_OUT])
        h.facade.capture("permitted-after-grant", null, Date(NOW_MS)); h.settle()
        assertEquals(listOf("event:permitted-after-grant"), h.queued())
    }

    @Test
    fun `close before startup never opens or emits pending consent event`() {
        val opened = java.util.concurrent.atomic.AtomicBoolean()
        val h = harness(autoStart = false, onOpened = { opened.set(true) })
        h.facade.optIn("never", null)
        h.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        h.facade.start()
        assertFalse(opened.get())
        assertTrue(h.queued().isEmpty())
    }

    @Test
    fun `opt out persists without configuration and reset preserves consent`() {
        val harness = harness()
        harness.facade.capture("before-consent", null, Date(NOW_MS))
        harness.facade.optOut()
        assertTrue(harness.facade.isOptedOut())
        harness.settle()
        assertTrue(harness.owner.snapshot().get().state.identity.optedOut)
        harness.facade.applyConfiguration(config())
        harness.facade.reset()
        harness.facade.capture("after-reset", null, Date(NOW_MS))
        harness.settle()
        assertTrue(harness.owner.snapshot().get().state.identity.optedOut)
        assertTrue(harness.facade.isOptedOut())
        assertNull(harness.facade.distinctId())
        assertTrue(harness.queued().isEmpty())
        assertTrue(harness.transport.requests.isEmpty())
    }

    @Test
    fun `changed denial persists even when standalone diagnostics metadata withdrawal is unavailable`() {
        val backing = FakeRuntimeQueueBacking(); var rejectedMetadataWrites = 0
        val h = harness(autoStart = false, backing = backing, databaseDecorator = { database ->
            object : dev.elu.analytics.internal.runtime.RuntimeQueueDatabase by database {
                override fun <T> transaction(block: (dev.elu.analytics.internal.runtime.RuntimeQueueTransaction) -> T): T =
                    database.transaction { tx ->
                        block(object : dev.elu.analytics.internal.runtime.RuntimeQueueTransaction by tx {
                            override fun updateCore(core: dev.elu.analytics.internal.runtime.RuntimeStoredCore) {
                                if (backing.core?.diagnostics?.epoch != null &&
                                    core.diagnostics == dev.elu.analytics.internal.runtime.RuntimeDiagnosticsState() &&
                                    !dev.elu.analytics.internal.core.CoreStateCodec.decode(core.stateJson).identity.optedOut) {
                                    rejectedMetadataWrites++
                                    throw IllegalStateException("metadata-only withdrawal unavailable")
                                }
                                tx.updateCore(core)
                            }
                        })
                    }
            }
        })
        h.owner.configureDiagnostics(dev.elu.analytics.internal.runtime.RuntimeDiagnosticsConfiguration(true, true),
            dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClock {
                dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClockReading(1, NOW_MS, 1_000, 1_000)
            }).get(5, TimeUnit.SECONDS)
        h.facade.start(); h.facade.applyConfiguration(config()); h.settle()
        assertTrue(h.owner.diagnosticsEpoch() != null)
        h.facade.optOut(); assertTrue(h.facade.isOptedOut()); h.settle()
        assertTrue(h.owner.snapshot().get().state.identity.optedOut)
        assertNull(backing.core!!.diagnostics.epoch)
        assertEquals(0, rejectedMetadataWrites)
    }

    @Test
    fun `same-choice grant still closes previous diagnostic interval`() {
        val h = harness(autoStart = false)
        h.owner.configureDiagnostics(dev.elu.analytics.internal.runtime.RuntimeDiagnosticsConfiguration(true, true),
            dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClock {
                dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClockReading(1, NOW_MS, 1_000, 1_000)
            }).get(5, TimeUnit.SECONDS)
        h.facade.start(); h.facade.applyConfiguration(config()); h.settle()
        val original = checkNotNull(h.owner.diagnosticsEpoch())
        h.facade.optIn(null, null); h.settle()
        val current = checkNotNull(h.owner.diagnosticsEpoch())
        assertTrue(original.id != current.id)
        assertFalse(h.facade.isOptedOut())
    }

    @Test
    fun `opt in restores consent after durable storage and captures its event`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())
        harness.facade.optOut()
        harness.settle()
        harness.facade.optIn("\$opt_in", mapOf("source" to "settings"))
        harness.settle()
        assertFalse(harness.facade.isOptedOut())
        assertFalse(harness.owner.snapshot().get().state.identity.optedOut)
        assertEquals(listOf("event:\$opt_in"), harness.queued())
        harness.facade.capture("allowed", null, Date(NOW_MS))
        harness.settle()
        assertTrue(harness.queued().contains("event:allowed"))
    }

    @Test
    fun `newer opt out prevents queued opt in from reopening collection`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val harness = harness(beforeOpen = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            harness.facade.applyConfiguration(config())
            harness.facade.optIn("\$opt_in", null)
            harness.facade.optOut()
            harness.facade.capture("forbidden", null, Date(NOW_MS))
            release.countDown()
            harness.settle()
            assertTrue(harness.facade.isOptedOut())
            assertTrue(harness.owner.snapshot().get().state.identity.optedOut)
            assertTrue(harness.queued().isEmpty())
            assertTrue(harness.transport.requests.isEmpty())
        } finally { release.countDown() }
    }

    @Test
    fun `concurrent consent cannot invert durable enqueue order and survives reopen`() {
        for (lastOptedOut in listOf(true, false)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val target = java.util.concurrent.atomic.AtomicReference<Thread>()
            val delegate = java.util.concurrent.Executors.newSingleThreadExecutor()
            val lane = object : java.util.concurrent.ExecutorService by delegate {
                override fun execute(command: Runnable) {
                    if (Thread.currentThread() === target.get() && entered.count != 0L) {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    }
                    delegate.execute(command)
                }
            }
            val backing = FakeRuntimeQueueBacking()
            val harness = harness(backing = backing, facadeLane = lane)
            harness.facade.applyConfiguration(config()); harness.settle()
            if (lastOptedOut) { harness.facade.optOut(); harness.settle() }
            val first = Thread {
                if (lastOptedOut) harness.facade.optIn(null, null) else harness.facade.optOut()
            }
            target.set(first)
            val secondDone = CountDownLatch(1)
            val second = Thread {
                try { if (lastOptedOut) harness.facade.optOut() else harness.facade.optIn(null, null) }
                finally { secondDone.countDown() }
            }
            try {
                first.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); second.start()
                // Acceptance and queue insertion form one boundary: a later call cannot overtake it.
                assertFalse(secondDone.await(100, TimeUnit.MILLISECONDS))
            } finally { release.countDown() }
            first.join(5_000); second.join(5_000)
            assertFalse(first.isAlive); assertFalse(second.isAlive)
            harness.settle()
            assertEquals(lastOptedOut, harness.owner.snapshot().get().state.identity.optedOut)
            assertEquals(lastOptedOut, harness.facade.isOptedOut())
            harness.facade.closeAndWait().get(5, TimeUnit.SECONDS)
            val reopened = harness(backing = backing)
            reopened.facade.applyConfiguration(config()); reopened.settle()
            assertEquals(lastOptedOut, reopened.owner.snapshot().get().state.identity.optedOut)
            assertEquals(lastOptedOut, reopened.facade.isOptedOut())
        }
    }

    @Test
    fun `typed flag result preserves variant and payload and disappears on identity change`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())
        harness.settle()
        val result = harness.facade.getFeatureFlagResult("variant")!!
        assertEquals("variant", result.key)
        assertTrue(result.enabled)
        assertEquals("variant-a", result.variant)
        harness.facade.identify("next-account", null)
        assertNull(harness.facade.getFeatureFlagResult("variant"))
        harness.settle()
    }

    @Test
    fun `set once and reset context APIs preserve and isolate durable evaluation state`() {
        val harness = harness()
        harness.facade.applyConfiguration(config())
        harness.facade.identify("account-a", mapOf("plan" to "paid"), mapOf("source" to "first"))
        harness.facade.setPersonProperties(mapOf("plan" to "enterprise"), mapOf("source" to "second", "region" to "west"))
        harness.facade.group("company", "company-a", mapOf("tier" to "paid"))
        harness.settle()
        assertEquals(mapOf("plan" to "enterprise", "source" to "first", "region" to "west"),
            harness.owner.snapshot().get().state.flagContext.personProperties)
        assertEquals(mapOf("company" to "company-a"), harness.facade.getGroups())
        harness.facade.resetGroupPropertiesForFlags("company")
        harness.facade.resetPersonPropertiesForFlags()
        harness.settle()
        assertTrue(harness.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
        assertTrue(harness.owner.snapshot().get().state.flagContext.groupProperties.isEmpty())
        assertEquals(mapOf("company" to "company-a"), harness.facade.getGroups())
        harness.facade.resetGroups()
        harness.settle()
        assertTrue(harness.facade.getGroups().isEmpty())
    }

    @Test fun `performance requires current foreground session and drops stale consent identity and config aggregates`() {
        val wall = AtomicLong(NOW_MS)
        val h = harness(wall = wall::get)
        val document = JSONObject(config()).put("capturePerformance", JSONObject().put("memory", true).put("long_tasks", true).put("sample_interval_ms", 5000)).toString()
        h.facade.applyConfiguration(document)
        h.facade.nativeReplayLifecycleChanged(true)
        h.facade.capture("activity", null, Date(NOW_MS))
        h.settle()
        val original = checkNotNull(h.facade.performanceContext())
        wall.addAndGet(5_000)
        h.facade.capturePerformance(original, mapOf("\$memory_process_pss_bytes" to 42_000L))
        h.settle()
        assertEquals(1, h.records().filterIsInstance<RuntimeQueuedRecord.Event>().count { it.record.name == "\$performance_sample" })
        assertEquals(Instant.ofEpochMilli(NOW_MS).toString(), Instant.parse(h.owner.snapshot().get().state.identity.session!!.lastActivityAt).toString())
        h.facade.optOut()
        assertNull(h.facade.performanceContext())
        h.facade.capturePerformance(original, emptyMap()); h.settle()
        h.facade.optIn(null, null); h.settle()
        h.facade.capture("new activity", null, Date(wall.get())); h.settle()
        h.facade.capturePerformance(original, emptyMap()); h.settle()
        h.facade.identify("other", null); h.settle()
        h.facade.capturePerformance(original, emptyMap()); h.settle()
        val latest = checkNotNull(h.facade.performanceContext())
        h.facade.nativeReplayLifecycleChanged(false)
        h.facade.capturePerformance(latest, emptyMap()); h.settle()
        assertNull(h.facade.performanceContext())
        assertEquals(1, h.records().filterIsInstance<RuntimeQueuedRecord.Event>().count { it.record.name == "\$performance_sample" })
    }

    @Test fun `network capture can start first session and settles only once`() {
        val h = harness()
        assertNull(h.facade.beginNetworkObservation("customer.example"))
        val document = JSONObject(config()).also { it.getJSONObject("features").put("replay", false) }.toString()
        h.facade.applyConfiguration(document); h.settle()
        assertNull(h.owner.snapshot().get().state.identity.session)
        val observation = checkNotNull(h.facade.beginNetworkObservation("customer.example"))
        observation.complete(mapOf("\$network_status_code" to 200)); observation.complete(emptyMap()); h.settle()
        assertEquals(listOf("event:\$network_request"), h.queued())
        assertTrue(h.owner.snapshot().get().state.identity.session != null)
    }

    @Test fun `network requests cannot cross accepted consent identity reset config or lifecycle changes`() {
        for (change in listOf<(StandaloneFacade) -> Unit>(
            { it.optOut(); it.optIn(null, null) }, { it.identify("other-user", null) },
            { it.reset() }, { it.applyConfiguration(config()) },
            { it.nativeReplayLifecycleChanged(false); it.nativeReplayLifecycleChanged(true) },
        )) {
            val h = harness(); h.facade.applyConfiguration(config()); h.settle()
            val observation = checkNotNull(h.facade.beginNetworkObservation("customer.example"))
            change(h.facade)
            observation.complete(mapOf("\$network_status_code" to 200)); h.settle()
            assertTrue(h.records().filterIsInstance<RuntimeQueuedRecord.Event>().none { it.record.name == "\$network_request" })
        }
    }

    @Test fun `prefixed local API declaration still excludes all customer traffic to the SDK host`() {
        val policy = dev.elu.analytics.internal.config.LocalEndpointPolicy.fromApiHost("https://analytics.example.com/team-a/elu/")
        val h = harness(networkApiHost = policy.apiHost); h.facade.applyConfiguration(config()); h.settle()
        // Interceptor admission and completion pass only the host, so a prefix cannot
        // narrow the exclusion to SDK paths or admit sibling-prefix requests.
        assertEquals("analytics.example.com", policy.apiHost)
        assertNull(h.facade.beginNetworkObservation(java.net.URI("https://analytics.example.com/unrelated").host))
        val original = checkNotNull(h.facade.beginNetworkObservation("customer.example"))
        original.complete(emptyMap(), "analytics.example.com"); h.settle()
        assertTrue(h.records().filterIsInstance<RuntimeQueuedRecord.Event>().none { it.record.name == "\$network_request" })
    }

    @Test fun `network process cap is shared by observations and not renewed by reset or consent`() {
        val h = harness(networkConfigHost = "localhost", networkApiHost = "analytics.example.com"); h.facade.applyConfiguration(config()); h.settle()
        assertNull(h.facade.beginNetworkObservation("elu.dev"))
        assertNull(h.facade.beginNetworkObservation("ingest.elu.dev"))
        assertNull(h.facade.beginNetworkObservation("localhost"))
        assertNull(h.facade.beginNetworkObservation("analytics.example.com"))
        repeat(200) { checkNotNull(h.facade.beginNetworkObservation("customer.example")) }
        assertNull(h.facade.beginNetworkObservation("customer.example"))
        h.facade.reset(); h.facade.optOut(); h.facade.optIn(null, null); h.settle()
        assertNull(h.facade.beginNetworkObservation("customer.example"))
    }

    @Test fun `network cannot attach completion to a session begun by different activity`() {
        val h = harness(); h.facade.applyConfiguration(config()); h.settle()
        val original = checkNotNull(h.facade.beginNetworkObservation("customer.example"))
        h.facade.capture("other activity", null, Date(NOW_MS)); h.settle()
        original.complete(emptyMap()); h.settle()
        assertEquals(listOf("event:other activity"), h.queued())
        val current = checkNotNull(h.facade.beginNetworkObservation("customer.example"))
        current.complete(mapOf("\$network_status_code" to 200)); h.settle()
        assertEquals(listOf("event:other activity", "event:\$network_request"), h.queued())
    }

    @Test fun `close waits for original platform listener cleanup and propagates failure`() {
        for (failed in listOf(false, true)) {
            val cleanup = SdkFuture<Unit>()
            val h = harness(onCloseSettled = { cleanup })
            h.settle()
            val closed = h.facade.closeAndWait()
            assertFalse(closed.isDone)
            if (failed) cleanup.completeExceptionally(IllegalStateException("listener removal failed"))
            else cleanup.complete(Unit)
            if (failed) assertThrows(java.util.concurrent.ExecutionException::class.java) { closed.get(5, TimeUnit.SECONDS) }
            else closed.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun `quiet reads leave exposure ledger available to the next reporting read`() {
        val h = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY); h.facade.applyConfiguration(config()); h.settle()
        val quiet = EluFeatureFlagOptions(sendEvent = false)
        assertEquals("variant-a", h.facade.getFeatureFlag("variant", quiet))
        assertEquals("variant-a", h.facade.getFeatureFlag("variant", quiet))
        val result = checkNotNull(h.facade.getFeatureFlagResult("variant", quiet))
        assertEquals("variant-a", result.variant)
        assertEquals(mapOf("buttonColor" to "violet"), result.payload)
        assertEquals(true, h.facade.isFeatureEnabled("variant", quiet, null)); h.settle()
        assertTrue(h.exposures().isEmpty())
        assertTrue(h.owner.snapshot().get().exposures!!.digests.isEmpty())
        assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions())); h.settle()
        assertEquals(1, h.exposures().size)
        assertEquals(1, h.owner.snapshot().get().exposures!!.digests.size)
        h.facade.getFeatureFlagResult("variant", EluFeatureFlagOptions()); h.settle()
        assertEquals(1, h.exposures().size)
    }

    @Test fun `optional enabled fallback distinguishes unavailable from evaluated false`() {
        val h = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        val quiet = EluFeatureFlagOptions(sendEvent = false)
        assertFalse(h.facade.isFeatureEnabled("bool-false"))
        assertNull(h.facade.isFeatureEnabled("bool-false", quiet, null))
        assertEquals(true, h.facade.isFeatureEnabled("bool-false", quiet, true))
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.getFeatureFlag("bool-false", quiet); h.settle()
        assertEquals(false, h.facade.getFeatureFlag("bool-false", quiet))
        assertEquals(false, h.facade.isFeatureEnabled("bool-false", quiet, true))
        assertFalse(checkNotNull(h.facade.getFeatureFlagResult("bool-false", quiet)).enabled)
        h.facade.getFeatureFlag("not-in-evaluation", quiet); h.settle()
        assertNull(h.facade.isFeatureEnabled("not-in-evaluation", quiet, null))
        assertEquals(true, h.facade.isFeatureEnabled("not-in-evaluation", quiet, true))
        h.settle(); assertTrue(h.exposures().isEmpty())
        assertTrue(h.owner.snapshot().get().exposures!!.digests.isEmpty())
    }

    @Test fun `fresh rejects reopened cache without fetching or consuming a report`() {
        val backing = FakeRuntimeQueueBacking()
        val quiet = EluFeatureFlagOptions(sendEvent = false)
        val fresh = EluFeatureFlagOptions(fresh = true)
        val first = harness(backing = backing, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        first.facade.applyConfiguration(config()); first.settle()
        first.facade.getFeatureFlag("variant", quiet); first.settle()
        assertEquals("variant-a", first.facade.getFeatureFlag("variant", quiet))
        first.settle(); first.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        val transport = RespondingFlagTransport().apply { failing = true }
        val reopened = harness(backing = backing, suppliedFlagTransport = transport, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        reopened.facade.applyConfiguration(config()); reopened.settle()
        reopened.facade.getFeatureFlag("variant", quiet); reopened.settle()
        assertEquals("variant-a", reopened.facade.getFeatureFlag("variant", quiet))
        val requests = transport.requests.size
        assertNull(reopened.facade.getFeatureFlag("variant", fresh))
        assertNull(reopened.facade.getFeatureFlagResult("variant", fresh))
        assertEquals(false, reopened.facade.isFeatureEnabled("variant", fresh, false))
        reopened.settle()
        assertEquals(requests, transport.requests.size)
        assertTrue(reopened.exposures().isEmpty())
        assertTrue(reopened.owner.snapshot().get().exposures!!.digests.isEmpty())
        transport.failing = false
        reopened.facade.reloadFeatureFlags {}; reopened.settle()
        assertEquals("variant-a", reopened.facade.getFeatureFlag("variant", fresh)); reopened.settle()
        assertEquals(1, reopened.exposures().size)
        assertEquals(false, reopened.exposures().single()["\$used_bootstrap_value"])
    }

    @Test fun `fresh does not extend original cache expiry or initiate network work`() {
        val advance = AtomicLong(0)
        val clock = object : FlagClock {
            override fun wallNowEpochMillis() = NOW_MS + advance.get()
            override fun monotonicNowNanos() = 1_000_000_000L + advance.get() * 1_000_000L
        }
        val h = harness(flagClock = clock, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        val freshQuiet = EluFeatureFlagOptions(sendEvent = false, fresh = true)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.getFeatureFlag("variant", freshQuiet); h.settle()
        assertEquals("variant-a", h.facade.getFeatureFlag("variant", freshQuiet)); h.settle()
        val requests = h.flagTransport.requests.size
        advance.set(180_001L) // The actual response expires at 00:04; NOW is 00:01.
        assertNull(h.facade.getFeatureFlag("variant", freshQuiet))
        assertNull(h.facade.getFeatureFlagResult("variant", freshQuiet))
        assertEquals(true, h.facade.isFeatureEnabled("variant", freshQuiet, true))
        h.settle()
        assertEquals(requests, h.flagTransport.requests.size)
        assertTrue(h.exposures().isEmpty())
        assertTrue(h.owner.snapshot().get().exposures!!.digests.isEmpty())
    }

    @Test fun `fresh exposure queued before identity context reset or consent change cannot commit`() {
        val changes: List<(StandaloneFacade) -> Unit> = listOf(
            { it.identify("new-reader", null) },
            { it.setPersonPropertiesForFlags(mapOf("plan" to "changed")) },
            { it.reset() },
            { it.optOut() },
        )
        for (change in changes) {
            val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
            val h = harness(facadeLane = lane, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            h.facade.applyConfiguration(config()); h.settle()
            h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)); h.settle()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            lane.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(fresh = true)))
                change(h.facade)
                assertNull(h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(fresh = true)))
            } finally { release.countDown() }
            h.settle()
            assertTrue(h.exposures().isEmpty())
            assertTrue(h.owner.snapshot().get().exposures!!.digests.isEmpty())
        }
    }

    @Test fun `accepted exposure survives reopen and cached missing flag keeps original evaluation metadata`() {
        val backing = FakeRuntimeQueueBacking()
        fun selected(transport: RespondingFlagTransport = RespondingFlagTransport()) = harness(backing = backing,
            suppliedFlagTransport = transport, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        val first = selected(); first.facade.applyConfiguration(config()); first.settle()
        first.facade.getFeatureFlag("variant"); first.settle()
        first.facade.getFeatureFlag("variant"); first.settle()
        assertEquals(1, first.exposures().size)
        val originalRequest = first.exposures().first()["\$feature_flag_request_id"]
        first.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        val transport = RespondingFlagTransport().apply { failing = true }
        val reopened = selected(transport); reopened.facade.applyConfiguration(config()); reopened.settle()
        reopened.facade.getFeatureFlag("variant"); reopened.settle()
        assertEquals("variant-a", reopened.facade.getFeatureFlag("variant")); reopened.settle()
        assertEquals(1, reopened.exposures().size)
        reopened.facade.getFeatureFlag("not-in-evaluation"); reopened.settle()
        assertNull(reopened.facade.getFeatureFlag("not-in-evaluation")); reopened.settle()
        val missing = reopened.exposures().last()
        assertEquals(2, reopened.exposures().size)
        assertEquals("flag_missing", missing["\$feature_flag_error"])
        assertFalse(missing.containsKey("\$feature_flag_response"))
        assertEquals(true, missing["\$used_bootstrap_value"])
        assertEquals(NOW_MS, missing["\$feature_flag_evaluated_at"])
        assertEquals(originalRequest, missing["\$feature_flag_request_id"])
        transport.failing = false
        reopened.facade.reloadFeatureFlags {}; reopened.settle()
        reopened.facade.getFeatureFlag("bool-false"); reopened.settle()
        reopened.facade.getFeatureFlag("bool-false"); reopened.settle()
        assertEquals(false, reopened.exposures().last()["\$feature_flag_response"])
        assertEquals(false, reopened.exposures().last()["\$used_bootstrap_value"])
    }

    @Test fun `exposure rejection and rollback do not consume durable visitor report`() {
        for (ambiguous in listOf(false, true)) {
            val backing = FakeRuntimeQueueBacking()
            val h = harness(backing = backing, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
            h.facade.applyConfiguration(config()); h.settle()
            h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)); h.settle()
            val attempts = backing.attemptedRecordAppends.size
            if (ambiguous) backing.ambiguousNextCommit = dev.elu.analytics.internal.runtime.FakeAmbiguousOutcome.ROLLBACK
            else backing.failNextKnownCommit = java.io.IOException("known rollback")
            h.facade.getFeatureFlag("variant"); h.settle()
            if (ambiguous) {
                // Exact reconciliation proves the first attempt rolled back and retries once.
                // If the first attempt had consumed the marker, this retry could not report.
                val appended = backing.attemptedRecordAppends.drop(attempts)
                // Session publication also refreshes flag authority. Count only the two
                // record-appending transactions, not that separate metadata-only write.
                assertEquals(2, appended.size)
                assertEquals(listOf(StandaloneFacade.FEATURE_FLAG_CALLED_EVENT, StandaloneFacade.FEATURE_FLAG_CALLED_EVENT),
                    appended.map { RuntimeRecordCodec.decodeEvent(it.single().internalPayload).name })
                assertEquals(appended.first().single().recordId, appended.last().single().recordId)
                assertNull(backing.ambiguousNextCommit)
                assertEquals(1, h.exposures().size)
            } else {
                assertTrue(h.exposures().isEmpty())
                assertTrue(h.owner.snapshot().get().exposures!!.digests.isEmpty())
                h.facade.getFeatureFlag("variant"); h.settle()
            }
            assertEquals(1, h.exposures().size)
            assertEquals(1, h.owner.snapshot().get().exposures!!.digests.size)
        }
        val small = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY,
            limits = RuntimeQueueLimits(10, 1))
        small.facade.applyConfiguration(config()); small.settle()
        small.facade.getFeatureFlag("variant"); small.settle()
        small.facade.getFeatureFlag("variant"); small.settle()
        assertTrue(small.owner.snapshot().get().exposures!!.digests.isEmpty())
    }

    @Test fun `ambiguous committed exposure is reconciled and duplicate does not allocate again`() {
        val backing = FakeRuntimeQueueBacking()
        val h = harness(backing = backing, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)); h.settle()
        backing.ambiguousNextCommit = dev.elu.analytics.internal.runtime.FakeAmbiguousOutcome.COMMIT
        h.facade.getFeatureFlag("variant"); h.settle()
        val before = h.owner.snapshot().get()
        repeat(3) { h.facade.getFeatureFlag("variant") }; h.settle()
        assertEquals(1, h.exposures().size)
        assertEquals(before, h.owner.snapshot().get())
    }

    @Test fun `flag failures use bounded backoff and stale retry cannot run after optout or close`() {
        val scheduler = ManualFlagRetryScheduler()
        val transport = RespondingFlagTransport().apply { failing = true }
        val h = harness(suppliedFlagTransport = transport, retryScheduler = scheduler,
            personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        for (seconds in listOf(5L, 10L, 20L, 40L, 80L, 160L)) {
            assertEquals(seconds * 1_000_000_000L, scheduler.entries.last().delay)
            scheduler.entries.last().task(); h.settle()
        }
        assertEquals(7, transport.requests.size)
        assertEquals(6, scheduler.entries.size)
        transport.failing = false
        h.facade.reloadFeatureFlags {}; h.settle()
        transport.failing = true
        h.facade.reloadFeatureFlags {}; h.settle()
        assertEquals(5_000_000_000L, scheduler.entries.last().delay)
        val stale = scheduler.entries.last().task
        h.facade.optOut(); h.settle()
        val before = transport.requests.size
        stale(); h.settle()
        assertEquals(before, transport.requests.size)
        h.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        stale()
        assertTrue(scheduler.closed)
        assertEquals(before, transport.requests.size)
    }

    @Test fun `boolean and same-spelled string variants receive different durable reports`() {
        val transport = RespondingFlagTransport().apply { variant = false }
        val h = harness(suppliedFlagTransport = transport, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.getFeatureFlag("variant"); h.settle()
        assertEquals(false, h.facade.getFeatureFlag("variant")); h.settle()
        transport.variant = "false"
        h.facade.reloadFeatureFlags {}; h.settle()
        assertEquals("false", h.facade.getFeatureFlag("variant")); h.settle()
        assertEquals(listOf(false, "false"), h.exposures().map { it["\$feature_flag_response"] })
        assertEquals(2, h.owner.snapshot().get().exposures!!.digests.size)
    }

    @Test fun `acknowledgement and optout optin retain accepted report without advancing session on duplicate`() {
        val h = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.getFeatureFlag("variant"); h.settle()
        h.facade.getFeatureFlag("variant"); h.settle()
        val ledger = h.owner.snapshot().get().exposures
        val record = h.records().single()
        h.owner.acknowledge(dev.elu.analytics.internal.runtime.RuntimeAcknowledgement("stream_facade", listOf(
            dev.elu.analytics.internal.runtime.RuntimeRecordReference(record.sequence, record.kind, record.recordId)))).get(5, TimeUnit.SECONDS)
        h.facade.optOut(); h.settle(); h.facade.optIn(null, null); h.settle()
        val before = h.owner.snapshot().get()
        h.facade.getFeatureFlag("variant"); h.settle()
        h.facade.getFeatureFlag("variant"); h.settle()
        assertTrue(h.exposures().isEmpty())
        assertEquals(ledger, h.owner.snapshot().get().exposures)
        assertEquals(before.state.identity.session, h.owner.snapshot().get().state.identity.session)
    }

    @Test fun `consent revocation after exposure write rolls back both event and ledger`() {
        var afterInsert: () -> Unit = {}
        val h = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY,
            databaseDecorator = { database -> object : dev.elu.analytics.internal.runtime.RuntimeQueueDatabase by database {
                override fun <T> transaction(block: (dev.elu.analytics.internal.runtime.RuntimeQueueTransaction) -> T): T =
                    database.transaction { transaction -> block(object : dev.elu.analytics.internal.runtime.RuntimeQueueTransaction by transaction {
                        override fun insertRecord(record: dev.elu.analytics.internal.runtime.RuntimeStoredRecord) {
                            transaction.insertRecord(record); afterInsert()
                        }
                    }) }
            } })
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)); h.settle()
        afterInsert = { h.facade.optOut() }
        h.facade.getFeatureFlag("variant"); h.settle()
        assertTrue(h.exposures().isEmpty())
        assertTrue(h.owner.snapshot().get().exposures!!.digests.isEmpty())
        assertTrue(h.facade.isOptedOut())
    }

    @Test fun `saturated exposure ledger retains old reports while ordinary capture and getters remain usable`() {
        val backing = FakeRuntimeQueueBacking()
        val initial = harness(backing = backing, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        initial.settle(); initial.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        var ledger = backing.core!!.exposures!!
        repeat(dev.elu.analytics.internal.runtime.MAX_RUNTIME_FLAG_EXPOSURES) {
            ledger = ledger.adding(dev.elu.analytics.internal.runtime.RuntimeFlagExposureState.digest("old-$it", true))!!
        }
        backing.core = backing.core!!.copy(exposures = ledger)
        val h = harness(backing = backing, personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.getFeatureFlag("variant"); h.settle()
        assertEquals("variant-a", h.facade.getFeatureFlag("variant")); h.settle()
        assertTrue(h.exposures().isEmpty())
        assertEquals(ledger, h.owner.snapshot().get().exposures)
        h.facade.capture("ordinary", null, Date(NOW_MS)); h.settle()
        assertEquals(listOf("event:ordinary"), h.queued())
    }

    private class ManualFlagRetryScheduler : dev.elu.analytics.internal.config.V2ConfigLifecycleScheduler {
        data class Entry(val delay: Long, val task: () -> Unit, var canceled: Boolean = false)
        val entries = mutableListOf<Entry>()
        var closed = false
        override fun schedule(delayNanos: Long, task: () -> Unit): dev.elu.analytics.internal.config.V2ConfigLifecycleTask {
            check(!closed)
            val entry = Entry(delayNanos, task); entries += entry
            return dev.elu.analytics.internal.config.V2ConfigLifecycleTask { entry.canceled = true }
        }
        override fun close() { closed = true; entries.forEach { it.canceled = true } }
    }

    // ---- harness -------------------------------------------------------------

    @Test
    fun `selected profile mode reaches runtime events and reset through real facade stack`() {
        val harness = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
        harness.facade.applyConfiguration(config()); harness.settle()
        val originalDevice = harness.owner.snapshot().get().person!!.deviceId
        harness.facade.setPersonPropertiesForFlags(mapOf("plan" to "flags-only")); harness.settle()
        harness.facade.capture("anonymous", mapOf("\$device_id" to "spoof", "\$process_person_profile" to true, "\$epp" to true), Date(NOW_MS))
        harness.settle()
        val anonymous = (harness.records().last() as RuntimeQueuedRecord.Event).record
        assertEquals(originalDevice, anonymous.properties["\$device_id"])
        assertEquals(false, anonymous.properties["\$process_person_profile"])
        assertFalse(anonymous.properties.containsKey("\$epp"))
        harness.facade.identify("customer", null); harness.settle()
        harness.facade.screen("Home", null)
        harness.facade.captureException(IllegalStateException("handled"), null)
        harness.settle()
        harness.records().filterIsInstance<RuntimeQueuedRecord.Event>().drop(1).forEach {
            assertEquals(originalDevice, it.record.properties["\$device_id"])
            assertEquals(true, it.record.properties["\$is_identified"])
            assertEquals(true, it.record.properties["\$process_person_profile"])
        }
        harness.facade.reset(); harness.settle()
        assertEquals(originalDevice, harness.owner.snapshot().get().person!!.deviceId)
        assertFalse(harness.owner.snapshot().get().person!!.processingEnabled)
        harness.facade.reset(true); harness.settle()
        val rotated = harness.owner.snapshot().get()
        assertEquals(rotated.state.identity.anonymousId, rotated.person!!.deviceId)
        assertTrue(originalDevice != rotated.person.deviceId)
    }

    @Test
    fun `never profile ignores person APIs before optimistic identity projection and buffering`() {
        val harness = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.NEVER)
        harness.facade.applyConfiguration(config()); harness.settle()
        val before = harness.owner.snapshot().get()
        val original = harness.facade.distinctId()
        // A separate pending facade is deterministic: no actor task can run before explicit start.
        val pending = harness(autoStart = false, personProfiles = dev.elu.analytics.EluPersonProfilesMode.NEVER)
        val pendingIdentity = pending.facade.distinctId()
        pending.facade.identify("forbidden", mapOf("plan" to "paid"))
        pending.facade.alias("forbidden-alias")
        pending.facade.setPersonProperties(mapOf("role" to "admin"))
        assertEquals(pendingIdentity, pending.facade.distinctId())
        pending.facade.start(); pending.facade.applyConfiguration(config()); pending.settle()
        assertTrue(pending.records().isEmpty())
        assertEquals(0, pending.diagnostics().buffered)
        harness.facade.identify("forbidden", null)
        assertEquals(original, harness.facade.distinctId())
        harness.settle()
        assertEquals(before.state.identity, harness.owner.snapshot().get().state.identity)
        harness.facade.capture("still-anonymous", null, Date(NOW_MS)); harness.settle()
        assertEquals(false, (harness.records().last() as RuntimeQueuedRecord.Event).record.properties["\$process_person_profile"])
    }


    @Test fun `public facade invalid names spend selected budget and exceptions share it`() {
        val backing = FakeRuntimeQueueBacking()
        val harness = harness(backing=backing, personProfiles=dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY,
            rateLimiting=dev.elu.analytics.EluRateLimitingOptions(1.0, 2.0))
        harness.facade.applyConfiguration(config()); harness.settle()
        harness.facade.capture("", null, Date(NOW_MS))
        harness.facade.screen("", null)
        harness.settle()
        assertEquals(0.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
        assertEquals(2, harness.diagnostics().dropped[EluFacadeDropReason.INVALID_INPUT])
        assertTrue(harness.records().isEmpty())
        assertNull(harness.owner.snapshot().get().state.identity.session)
        harness.facade.captureException(IllegalStateException("not serialized into warning"), null)
        harness.settle()
        assertEquals(listOf("event:\$\$client_ingestion_warning"), harness.queued())
        assertEquals(1, harness.diagnostics().dropped[EluFacadeDropReason.RATE_LIMITED])
        harness.facade.identify("identity-exempt", null); harness.settle()
        assertTrue(harness.queued().contains("mutation:identify"))
        assertEquals(0.0, backing.captureRateState!!.bucket!!.tokens, 0.0)
    }

    @Test fun `capture options detach nested inputs and timestamp before pending admission`() {
        val h = harness(autoStart = false, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
        val date = Date(NOW_MS - 5_000)
        val nested = mutableListOf<Any>("original")
        val properties = mutableMapOf<String, Any>("items" to nested)
        val set = mutableMapOf<String, Any>("tier" to mutableMapOf("name" to "paid"))
        val once = mutableMapOf<String, Any>("origin" to "capture")
        h.facade.capture("checkout", properties, EluCaptureOptions(date, set, once))
        date.time = NOW_MS + 90_000; nested[0] = "changed"; set.clear(); once.clear()
        h.facade.start(); h.facade.applyConfiguration(config()); h.settle()
        assertEquals(listOf("event:checkout", "mutation:setPersonProperties"), h.queued())
        val event = (h.records()[0] as RuntimeQueuedRecord.Event).record
        val mutation = (h.records()[1] as RuntimeQueuedRecord.Mutation).envelope.mutation
        assertEquals("2026-08-04T00:00:55.000Z", event.occurredAt)
        assertEquals(listOf("original"), event.properties["items"])
        assertFalse(event.properties.containsKey("\$set"))
        assertEquals("2026-08-04T00:01:00.000Z", mutation.occurredAt)
        assertEquals(event.identity.anonymousId, mutation.subject.anonymousId)
        assertEquals(event.identity.revision, mutation.subject.identityRevision)
        assertEquals(event.sequence + 1, mutation.sequence)
        assertEquals(mapOf("tier" to mapOf("name" to "paid"), "origin" to "capture"),
            h.owner.snapshot().get().state.flagContext.personProperties)
    }

    @Test fun `accepted capture set and setOnce stay ordered and do not request another flag reload`() {
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.setPersonProperties(mapOf("origin" to "existing")); h.settle()
        val reloads = h.flagTransport.requests.size
        val options = EluCaptureOptions(Date(NOW_MS), mapOf("tier" to "paid"),
            mapOf("origin" to "ignored", "first" to "value"))
        h.facade.capture("first", null, options); h.facade.capture("second", null, options); h.settle()
        assertEquals(listOf("mutation:setPersonProperties", "event:first", "mutation:setPersonProperties", "event:second"), h.queued())
        assertEquals(mapOf("origin" to "existing", "tier" to "paid", "first" to "value"),
            h.owner.snapshot().get().state.flagContext.personProperties)
        assertEquals(reloads, h.flagTransport.requests.size)
        assertNull(h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false))); h.settle()
        assertEquals(reloads, h.flagTransport.requests.size)
    }

    @Test fun `capture person intent does not erase another pending operation reload in either order`() {
        for (captureFirst in listOf(true, false)) {
            val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
            val h = harness(facadeLane = lane, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
            h.facade.applyConfiguration(config()); h.settle()
            assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            lane.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val capture = { h.facade.capture("checkout", null, EluCaptureOptions(set = mapOf("tier" to "paid"))) }
                val context = { h.facade.setPersonPropertiesForFlags(mapOf("context" to "later")) }
                if (captureFirst) { capture(); context() } else { context(); capture() }
                assertNull(h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
            } finally { release.countDown() }
            h.settle()
            assertEquals(mapOf("tier" to "paid", "context" to "later"), h.owner.snapshot().get().state.flagContext.personProperties)
            assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
            assertTrue(h.exposures().isEmpty())
        }
    }

    @Test fun `explicit reload queued before a quiet associated capture remains requested`() {
        val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
        val h = harness(facadeLane = lane, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        val reloads = h.flagTransport.requests.size
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        lane.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.facade.reloadFeatureFlags(null)
            h.facade.capture("quiet", null, EluCaptureOptions(set = mapOf("tier" to "paid")))
        } finally { release.countDown() }
        h.settle()
        assertTrue(h.flagTransport.requests.size > reloads)
        assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
        assertEquals(mapOf("tier" to "paid"), h.owner.snapshot().get().state.flagContext.personProperties)
    }

    @Test fun `rejected associated capture settles its intent and permits an explicit flag reload`() {
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        val reloads = h.flagTransport.requests.size
        h.facade.capture("", null, EluCaptureOptions(set = mapOf("must-not-apply" to true))); h.settle()
        assertTrue(h.records().isEmpty())
        assertTrue(h.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
        assertEquals(reloads, h.flagTransport.requests.size)
        var completed = false
        h.facade.reloadFeatureFlags { completed = true }; h.settle()
        assertTrue(completed)
        assertTrue(h.flagTransport.requests.size > reloads)
        assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
    }

    @Test fun `invalid denied limited or full capture never appends accompanying person properties`() {
        for (case in listOf("invalid", "json", "person-json", "disabled", "opted-out", "rate", "queue")) {
            val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
                rateLimiting = if (case == "rate") dev.elu.analytics.EluRateLimitingOptions(1.0, 1.0) else null,
                limits = if (case == "queue") RuntimeQueueLimits(10, 1) else RuntimeQueueLimits(10_000, 16_777_216))
            h.facade.applyConfiguration(if (case == "disabled") config("config-disabled.json") else config()); h.settle()
            if (case == "opted-out") { h.facade.optOut(); h.settle() }
            if (case == "rate") { h.facade.capture("spend", null, Date(NOW_MS)); h.settle() }
            val options = EluCaptureOptions(Date(NOW_MS), mapOf("tier" to if (case == "person-json") Any() else "forbidden"))
            h.facade.capture(if (case == "invalid") "" else "denied", if (case == "json") mapOf("bad" to Any()) else null, options)
            h.settle()
            assertTrue(case, h.records().filterIsInstance<RuntimeQueuedRecord.Mutation>().isEmpty())
            assertTrue(case, h.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
            assertFalse(case, h.records().filterIsInstance<RuntimeQueuedRecord.Event>().any { it.record.name == "denied" })
        }
    }

    @Test fun `capture options never mode retains the event without person mutation or option payload`() {
        val h = harness(personProfiles = EluPersonProfilesMode.NEVER)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.capture("anonymous", mapOf("amount" to 42), EluCaptureOptions(set = mapOf("private" to "person")))
        h.settle()
        val record = (h.records().single() as RuntimeQueuedRecord.Event).record
        assertEquals(false, record.properties["\$process_person_profile"])
        assertFalse(record.properties.containsKey("private"))
        assertFalse(record.properties.containsKey("\$set"))
        assertTrue(h.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
    }

    @Test fun `ambiguous capture rollback retries event once and appends one associated mutation`() {
        val backing = FakeRuntimeQueueBacking()
        val h = harness(backing = backing, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        val attempts = backing.attemptedRecordAppends.size
        backing.ambiguousNextCommit = dev.elu.analytics.internal.runtime.FakeAmbiguousOutcome.ROLLBACK
        h.facade.capture("retry", null, EluCaptureOptions(set = mapOf("tier" to "paid"))); h.settle()
        assertEquals(listOf("event:retry", "mutation:setPersonProperties"), h.queued())
        val appended = backing.attemptedRecordAppends.drop(attempts)
        assertEquals(3, appended.size)
        assertEquals(appended[0].single().recordId, appended[1].single().recordId)
        assertTrue(appended[1].single().sequence < appended[2].single().sequence)
        assertEquals(mapOf("tier" to "paid"), h.owner.snapshot().get().state.flagContext.personProperties)
    }

    @Test fun `accepted capture cannot move person fields across later synchronous identity or consent intent`() {
        for (action in listOf("reset", "identify", "opt-out")) {
            var onAccepted: () -> Unit = {}
            val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
                startupObserver = RuntimeStartupObserver { observation ->
                    if (observation.phase == RuntimeStartupPhase.CAPTURE_FIRST && observation.captureAccepted == true) onAccepted()
                })
            h.facade.applyConfiguration(config()); h.settle()
            val original = h.owner.snapshot().get().state.identity
            var observed = false
            onAccepted = {
                observed = true
                when (action) {
                    "reset" -> h.facade.reset()
                    "identify" -> h.facade.identify("next-user", null)
                    else -> h.facade.optOut()
                }
            }
            h.facade.capture("original", null, EluCaptureOptions(set = mapOf("must-not-cross" to true)))
            h.settle()
            assertTrue(action, observed)
            assertTrue(action, h.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
            assertFalse(action, h.records().filterIsInstance<RuntimeQueuedRecord.Mutation>().any {
                it.envelope.mutation.change is dev.elu.analytics.internal.runtime.RuntimeMutationChange.SetPersonProperties
            })
            val event = h.records().filterIsInstance<RuntimeQueuedRecord.Event>().single { it.record.name == "original" }.record
            assertEquals(original.anonymousId, event.identity.anonymousId)
            assertEquals(original.userId, event.identity.userId)
        }
    }

    @Test fun `failure after durable event keeps an event-only prefix and does not consume person dedupe`() {
        for (reopen in listOf(false, true)) {
            val backing = FakeRuntimeQueueBacking()
            var onAccepted: () -> Unit = {}
            val h = harness(backing = backing, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
                startupObserver = RuntimeStartupObserver { observation ->
                    if (observation.phase == RuntimeStartupPhase.CAPTURE_FIRST && observation.captureAccepted == true) onAccepted()
                })
            h.facade.applyConfiguration(config()); h.settle()
            onAccepted = { backing.failNextKnownCommit = java.io.IOException("person write unavailable") }
            val options = EluCaptureOptions(set = mapOf("tier" to "paid"))
            h.facade.capture("first", null, options); h.settle()
            assertEquals(listOf("event:first"), h.queued())
            assertTrue(h.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
            onAccepted = {}
            val current = if (reopen) {
                h.facade.closeAndWait().get(5, TimeUnit.SECONDS)
                harness(backing = backing, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY).also {
                    it.facade.applyConfiguration(config()); it.settle()
                    assertEquals(listOf("event:first"), it.queued())
                    assertTrue(it.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
                }
            } else h
            current.facade.capture("second", null, options); current.settle()
            assertEquals(listOf("event:first", "event:second", "mutation:setPersonProperties"), current.queued())
            assertEquals(mapOf("tier" to "paid"), current.owner.snapshot().get().state.flagContext.personProperties)
        }
    }

    @Test fun `absent person options differ from an explicit empty person intent`() {
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.capture("plain", null, EluCaptureOptions()); h.settle()
        assertEquals(listOf("event:plain"), h.queued())
        h.facade.capture("person", null, EluCaptureOptions(set = emptyMap())); h.settle()
        assertEquals(listOf("event:plain", "event:person", "mutation:setPersonProperties"), h.queued())
        h.facade.capture("following", null, EluCaptureOptions()); h.settle()
        assertEquals(true, (h.records().last() as RuntimeQueuedRecord.Event).record.properties["\$process_person_profile"])
    }

    @Test fun `future event time cannot silently backdate its later person mutation`() {
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.capture("future", null, EluCaptureOptions(Date(NOW_MS + 1_000), set = mapOf("tier" to "paid")))
        h.settle()
        assertEquals(listOf("event:future"), h.queued())
        assertEquals("2026-08-04T00:01:01.000Z", (h.records().single() as RuntimeQueuedRecord.Event).record.occurredAt)
        assertTrue(h.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
        assertEquals(1, h.diagnostics().dropped[EluFacadeDropReason.INVALID_INPUT])
    }

    @Test fun `complete snapshot is immediate typed detached and does not expose or fetch`() {
        val transport = RespondingFlagTransport().apply {
            transform = { response ->
                response.getJSONObject("flags").put("number", 2.5).put("null", JSONObject.NULL)
                    .put("é", "composed").put("e\u0301", "decomposed")
                response.getJSONObject("payloads").put("null", JSONObject.NULL).put("payload-only", JSONObject().put("nested", true))
            }
        }
        val h = harness(suppliedFlagTransport = transport, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
        h.facade.applyConfiguration(config()); h.settle()
        val before = h.owner.snapshot().get()
        val requestCount = transport.requests.size
        val snapshot = checkNotNull(h.facade.getFeatureFlagSnapshot())
        assertEquals(EluFeatureFlagSnapshot.Source.REMOTE, snapshot.source); assertTrue(snapshot.isAvailable)
        assertNull(snapshot.error)
        assertEquals(6, snapshot.entries.size)
        assertEquals(EluFeatureFlagSnapshot.Value.BooleanValue(false), snapshot.getEntry("bool-false")!!.value)
        assertEquals(EluFeatureFlagSnapshot.Value.NumberValue(2.5), snapshot.getEntry("number")!!.value)
        assertEquals(EluFeatureFlagSnapshot.Value.NullValue, snapshot.getEntry("null")!!.value)
        assertEquals(EluFeatureFlagSnapshot.Value.StringValue("composed"), snapshot.getEntry("é")!!.value)
        assertEquals(EluFeatureFlagSnapshot.Value.StringValue("decomposed"), snapshot.getEntry("e\u0301")!!.value)
        assertNull(snapshot.getEntry("payload-only"))
        assertTrue(JSONObject(String(snapshot.payloadsJSON, Charsets.UTF_8)).getJSONObject("payload-only").getBoolean("nested"))
        assertNull(snapshot.getEntry("number")!!.payloadJSON)
        assertEquals("null", String(snapshot.getEntry("null")!!.payloadJSON!!, Charsets.UTF_8))
        val bytes = snapshot.flagsJSON; bytes.fill(0)
        val payload = snapshot.getEntry("variant")!!.payloadJSON!!; payload.fill(0)
        val allPayloads = snapshot.payloadsJSON; allPayloads.fill(0)
        snapshot.evaluatedAt!!.time = 0; snapshot.expiresAt!!.time = 0
        assertEquals(NOW_MS, snapshot.evaluatedAt!!.time)
        assertEquals(NOW_MS + 180_000L, snapshot.expiresAt!!.time)
        assertTrue(JSONObject(String(snapshot.flagsJSON, Charsets.UTF_8)).has("number"))
        assertEquals("violet", JSONObject(String(snapshot.getEntry("variant")!!.payloadJSON!!, Charsets.UTF_8)).getString("buttonColor"))
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.entries as MutableList<*>).clear() }
        assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
        assertEquals(true, h.facade.getFeatureFlag("number", EluFeatureFlagOptions(sendEvent = false)))
        assertEquals(false, h.facade.getFeatureFlag("null", EluFeatureFlagOptions(sendEvent = false)))
        h.settle()
        assertEquals(requestCount, transport.requests.size)
        assertEquals(before, h.owner.snapshot().get()); assertTrue(h.exposures().isEmpty())
    }

    @Test fun `empty evaluated snapshot differs from unavailable and errors belong to original load`() {
        val transport = RespondingFlagTransport().apply { failing = true }
        val h = harness(suppliedFlagTransport = transport)
        h.facade.applyConfiguration(config()); h.settle()
        val unavailable = checkNotNull(h.facade.getFeatureFlagSnapshot())
        assertFalse(unavailable.isAvailable); assertEquals(EluFeatureFlagSnapshot.Source.UNAVAILABLE, unavailable.source)
        assertEquals(EluFeatureFlagSnapshot.LoadError.TRANSPORT, unavailable.error)
        assertNull(unavailable.requestId); assertTrue(unavailable.entries.isEmpty())
        transport.failing = false
        transport.transform = { it.put("flags", JSONObject()).put("payloads", JSONObject()) }
        h.facade.reloadFeatureFlags(null); h.settle()
        val empty = checkNotNull(h.facade.getFeatureFlagSnapshot())
        assertTrue(empty.isAvailable); assertTrue(empty.entries.isEmpty()); assertNull(empty.error)
        assertTrue(empty.requestId!!.startsWith("flags_request_"))
        transport.invalidBytes = true
        h.facade.reloadFeatureFlags(null); h.settle()
        val failed = checkNotNull(h.facade.getFeatureFlagSnapshot())
        assertEquals(empty.requestId, failed.requestId)
        assertEquals(EluFeatureFlagSnapshot.Source.REMOTE, failed.source)
        assertEquals(EluFeatureFlagSnapshot.LoadError.INVALID_RESPONSE, failed.error)
        assertNull(empty.error) // Existing detached values cannot acquire later mutable error state.
        transport.invalidBytes = false
        h.facade.reloadFeatureFlags(null); h.settle()
        assertNull(checkNotNull(h.facade.getFeatureFlagSnapshot()).error)
    }

    @Test fun `cache snapshot retains original source and consumes original expiry without fetch`() {
        val backing = FakeRuntimeQueueBacking()
        val first = harness(backing = backing)
        first.facade.applyConfiguration(config()); first.settle()
        val original = checkNotNull(first.facade.getFeatureFlagSnapshot())
        first.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        val advance = AtomicLong(0)
        val clock = object : FlagClock {
            override fun wallNowEpochMillis() = NOW_MS + advance.get()
            override fun monotonicNowNanos() = 1_000_000_000L + advance.get() * 1_000_000L
        }
        val transport = RespondingFlagTransport().apply { failing = true }
        val h = harness(backing = backing, suppliedFlagTransport = transport, flagClock = clock)
        h.facade.applyConfiguration(config()); h.settle()
        val cached = checkNotNull(h.facade.getFeatureFlagSnapshot())
        assertEquals(original.requestId, cached.requestId); assertEquals(EluFeatureFlagSnapshot.Source.CACHE, cached.source)
        assertEquals(EluFeatureFlagSnapshot.LoadError.TRANSPORT, cached.error)
        val requests = transport.requests.size
        advance.set(180_000)
        assertNull(h.facade.getFeatureFlagSnapshot())
        assertEquals(requests, transport.requests.size)
        assertEquals(2, cached.entries.size) // Retained bytes are a value, never live permission.
    }

    @Test fun `subscriptions cancel queued callbacks preserve order and isolate client exceptions`() {
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val h = harness(callbackDelivery = { queued.add(it) })
        val received = mutableListOf<String>()
        val cancelled = h.facade.subscribeToFeatureFlags { received += "cancelled" }
        h.facade.subscribeToFeatureFlags { received += "first"; error("customer callback") }
        h.facade.subscribeToFeatureFlags { received += "second" }
        h.facade.applyConfiguration(config()); h.settle()
        cancelled.cancel(); cancelled.close()
        while (true) (queued.poll() ?: break).run()
        assertEquals(listOf("first", "second"), received)
        assertEquals(1, h.diagnostics().listenerErrors)
        lateinit var late: EluFeatureFlagSubscription
        late = h.facade.subscribeToFeatureFlags { received += "late"; late.cancel() }
        h.settle(); while (true) (queued.poll() ?: break).run()
        assertEquals(listOf("first", "second", "late"), received)
        h.facade.reloadFeatureFlags(null); h.settle()
        while (true) (queued.poll() ?: break).run()
        assertEquals(listOf("first", "second", "late", "first", "second"), received)
        h.facade.closeAndWait().get(5, TimeUnit.SECONDS)
        late.close(); cancelled.close()
    }

    @Test fun `cancel before registration prevents callback without preventing other listeners`() {
        val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
        val h = harness(facadeLane = lane)
        h.facade.applyConfiguration(config()); h.settle()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        lane.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val token = h.facade.subscribeToFeatureFlags { fail("cancelled before registration") }
            token.close(); token.cancel()
            h.facade.subscribeToFeatureFlags { callbacks += "live" }
        } finally { release.countDown() }
        h.settle(); assertEquals(listOf("live"), callbacks)
    }

    @Test fun `queued snapshot is fenced by original identity consent expiry and close`() {
        for (change in listOf("identity", "context", "optout", "expiry", "close")) {
            val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            val advance = AtomicLong(0)
            val clock = object : FlagClock {
                override fun wallNowEpochMillis() = NOW_MS + advance.get()
                override fun monotonicNowNanos() = 1_000_000_000L + advance.get() * 1_000_000L
            }
            val h = harness(callbackDelivery = { queued.add(it) }, flagClock = clock)
            h.facade.applyConfiguration(config()); h.settle()
            var delivered = 0
            h.facade.subscribeToFeatureFlags { delivered++ }; h.settle()
            val old = checkNotNull(queued.poll())
            when (change) {
                "identity" -> h.facade.identify("replacement", null)
                "context" -> h.facade.setPersonPropertiesForFlags(mapOf("plan" to "replacement"))
                "optout" -> h.facade.optOut()
                "expiry" -> advance.set(180_000)
                "close" -> h.facade.closeAndWait().get(5, TimeUnit.SECONDS)
            }
            old.run(); assertEquals(change, 0, delivered)
            if (change != "close") h.settle()
        }
    }

    @Test fun `coalesced reload callbacks finish from one original request with its current snapshot`() {
        val transport = RespondingFlagTransport()
        val h = harness(suppliedFlagTransport = transport)
        h.facade.applyConfiguration(config()); h.settle()
        val count = transport.requests.size
        transport.hold = true; transport.requestObserved = CountDownLatch(1)
        val completed = mutableListOf<String>()
        h.facade.reloadFeatureFlags { completed += "first:" + h.facade.getFeatureFlagSnapshot()!!.requestId }
        assertTrue(transport.requestObserved.await(5, TimeUnit.SECONDS))
        h.facade.reloadFeatureFlags { completed += "second:" + h.facade.getFeatureFlagSnapshot()!!.requestId }
        h.diagnostics() // Original facade queue has admitted the second caller into the same request.
        assertTrue(completed.isEmpty()); assertEquals(count + 1, transport.requests.size)
        transport.releaseHeld(); h.settle()
        val current = checkNotNull(h.facade.getFeatureFlagSnapshot()).requestId
        assertEquals(listOf("first:$current", "second:$current"), completed)
    }

    @Test fun `reload completion waits through quiet capture intent and cannot cross a later identity intent`() {
        for (change in listOf("quiet", "reset", "identify")) {
            val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
            val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            val transport = RespondingFlagTransport()
            val h = harness(facadeLane = lane, callbackDelivery = { queued.add(it) }, suppliedFlagTransport = transport,
                personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY)
            h.facade.applyConfiguration(config()); h.settle()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            lane.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            var completed = 0
            transport.hold = true; transport.requestObserved = CountDownLatch(1)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                h.facade.reloadFeatureFlags { completed++ }
                when (change) {
                    "reset" -> h.facade.reset()
                    "identify" -> h.facade.identify("later-identity", null)
                    else -> h.facade.capture("quiet", null, EluCaptureOptions(set = mapOf("plan" to "paid")))
                }
            } finally { release.countDown() }
            assertTrue(transport.requestObserved.await(5, TimeUnit.SECONDS))
            h.diagnostics()
            assertTrue("No premature completion from a deferred command", queued.isEmpty())
            transport.releaseHeld(); h.settle()
            while (true) (queued.poll() ?: break).run()
            assertEquals(change, if (change == "quiet") 1 else 0, completed)
            assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
        }
    }

    @Test fun `hook transforms accepted manual person maps in order and does not manufacture empty mutations`() {
        val filter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
            if (it.event == "person") {
                it.event = "accepted-person"; it.set = mutableMapOf("tier" to "hook")
                it.setOnce = mutableMapOf("origin" to "hook")
            } else { it.set = null; it.setOnce = null }
            it
        })
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY, eventFilter = filter)
        h.facade.applyConfiguration(config()); h.settle()
        val loads = h.flagTransport.requests.size
        h.facade.capture("person", null, EluCaptureOptions(set = mapOf("tier" to "caller")))
        h.settle()
        assertEquals(listOf("event:accepted-person", "mutation:setPersonProperties"), h.queued())
        val event = (h.records()[0] as RuntimeQueuedRecord.Event).record
        val person = (h.records()[1] as RuntimeQueuedRecord.Mutation).envelope.mutation
        assertEquals(event.sequence + 1, person.sequence)
        assertEquals(event.identity.revision, person.subject.identityRevision)
        assertEquals(mapOf("tier" to "hook", "origin" to "hook"), h.owner.snapshot().get().state.flagContext.personProperties)
        assertEquals(loads, h.flagTransport.requests.size)
        h.facade.capture("removed", null, EluCaptureOptions(set = mapOf("should" to "not persist")))
        h.facade.capture("ordinary", null, Date(NOW_MS)); h.settle()
        assertEquals(listOf("event:accepted-person", "mutation:setPersonProperties", "event:removed", "event:ordinary"), h.queued())
        assertFalse(h.owner.snapshot().get().state.flagContext.personProperties.containsKey("should"))
    }

    @Test fun `quiet hook reread preserves exact cached load metadata and original retry without notifying`() {
        for (cached in listOf(false, true)) {
            val backing = FakeRuntimeQueueBacking()
            if (cached) {
                val first = harness(backing = backing)
                first.facade.applyConfiguration(config()); first.settle()
                first.facade.closeAndWait().get(5, TimeUnit.SECONDS)
            }
            val scheduler = ManualFlagRetryScheduler()
            val transport = RespondingFlagTransport().apply { failing = cached }
            val h = harness(backing = backing, suppliedFlagTransport = transport, retryScheduler = scheduler,
                eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                    if (cached) null else it
                }))
            h.facade.applyConfiguration(config()); h.settle()
            if (!cached) {
                transport.invalidBytes = true
                h.facade.reloadFeatureFlags(null); h.settle()
            }
            val before = checkNotNull(h.facade.getFeatureFlagSnapshot())
            val retry = scheduler.entries.single()
            assertEquals(if (cached) EluFeatureFlagSnapshot.Source.CACHE else EluFeatureFlagSnapshot.Source.REMOTE, before.source)
            assertEquals(if (cached) EluFeatureFlagSnapshot.LoadError.TRANSPORT else EluFeatureFlagSnapshot.LoadError.INVALID_RESPONSE, before.error)
            var callbacks = 0
            h.facade.subscribeToFeatureFlags { callbacks++ }; h.settle()
            val loads = transport.requests.size
            val ledger = h.owner.snapshot().get().exposures
            h.facade.capture("quiet-metadata", null, Date(NOW_MS)); h.settle()
            val after = checkNotNull(h.facade.getFeatureFlagSnapshot())
            assertNotSame(before, after) // A fresh getter guard, never a revived old publication.
            assertEquals(before.requestId, after.requestId); assertEquals(before.source, after.source)
            assertEquals(before.error, after.error); assertArrayEquals(before.flagsJSON, after.flagsJSON)
            assertArrayEquals(before.payloadsJSON, after.payloadsJSON)
            assertEquals(before.evaluatedAt, after.evaluatedAt); assertEquals(before.expiresAt, after.expiresAt)
            assertEquals(1, callbacks); assertEquals(loads, transport.requests.size)
            assertEquals(ledger, h.owner.snapshot().get().exposures)
            assertSame(retry, scheduler.entries.single()); assertFalse(retry.canceled)
            assertEquals(5_000_000_000L, retry.delay)
            retry.task(); h.settle()
            assertEquals(loads + 1, transport.requests.size)
            assertEquals(10_000_000_000L, scheduler.entries.last().delay)
        }
    }

    @Test fun `original inflight reload survives quiet pass and drop before or after its actual outcome`() {
        for (invalid in listOf(false, true)) for (duringQuiet in listOf(false, true)) {
            val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
            val transport = RespondingFlagTransport()
            val scheduler = ManualFlagRetryScheduler()
            val h = harness(facadeLane = lane, suppliedFlagTransport = transport, retryScheduler = scheduler,
                eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(
                    callback = dev.elu.analytics.EluEvent.Filter { if (invalid) null else it }))
            h.facade.applyConfiguration(config()); h.settle()
            val original = checkNotNull(h.facade.getFeatureFlagSnapshot())
            var notifications = 0
            h.facade.subscribeToFeatureFlags { notifications++ }; h.settle()
            val completed = mutableListOf<EluFeatureFlagSnapshot>()
            val loads = transport.requests.size
            transport.invalidBytes = invalid; transport.hold = true; transport.requestObserved = CountDownLatch(1)
            h.facade.reloadFeatureFlags { completed += checkNotNull(h.facade.getFeatureFlagSnapshot()) }
            assertTrue(transport.requestObserved.await(5, TimeUnit.SECONDS))
            if (duringQuiet) {
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                lane.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    transport.releaseHeld()
                    // Join the original client's actual finalization before the facade can
                    // consume it. The quiet caller then withholds reads synchronously.
                    h.flagClient.readSnapshot().get(5, TimeUnit.SECONDS)
                    h.facade.capture("quiet-inflight", null, Date(NOW_MS))
                    assertNull(h.facade.getFeatureFlagSnapshot()); assertTrue(completed.isEmpty())
                } finally { release.countDown() }
            } else {
                h.facade.capture("quiet-inflight", null, Date(NOW_MS))
                assertTrue(h.diagnostics().flagReloadInFlight)
                assertNull(h.facade.getFeatureFlagSnapshot()); assertTrue(completed.isEmpty())
                assertEquals(loads + 1, transport.requests.size)
                transport.releaseHeld()
            }
            h.settle()
            val actual = checkNotNull(h.facade.getFeatureFlagSnapshot())
            assertEquals(1, completed.size); assertSame(actual, completed.single())
            assertEquals(EluFeatureFlagSnapshot.Source.REMOTE, actual.source)
            assertEquals(if (invalid) EluFeatureFlagSnapshot.LoadError.INVALID_RESPONSE else null, actual.error)
            if (invalid) assertEquals(original.requestId, actual.requestId)
            else assertFalse(original.requestId == actual.requestId)
            assertEquals(loads + 1, transport.requests.size); assertEquals(2, notifications)
            assertTrue(h.exposures().isEmpty())
            assertEquals(if (invalid) emptyList<String>() else listOf("event:quiet-inflight"), h.queued())
            assertEquals(if (invalid) listOf(5_000_000_000L) else emptyList<Long>(), scheduler.entries.map { it.delay })
        }
    }

    @Test fun `original inflight outcome cannot cross identity consent or source replacement`() {
        for (change in listOf("identity", "consent", "source")) {
            val gate = dev.elu.analytics.internal.config.V2ConfigAuthorityGate()
            val body = config()
            gate.update(dev.elu.analytics.internal.config.V2ConfigLifecycleUpdate(1) { consumer -> consumer(body); true })
            val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
            val transport = RespondingFlagTransport()
            val h = harness(configurationGate = gate, facadeLane = lane, suppliedFlagTransport = transport,
                eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(
                    callback = dev.elu.analytics.EluEvent.Filter { null }))
            h.facade.configurationChanged(); h.settle()
            transport.hold = true; transport.requestObserved = CountDownLatch(1)
            var completed = 0
            h.facade.reloadFeatureFlags { completed++ }
            assertTrue(transport.requestObserved.await(5, TimeUnit.SECONDS))
            val rejectedRequest = transport.requests.last().getString("requestId")
            val requestCount = transport.requests.size
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            lane.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                transport.releaseHeld(); h.flagClient.readSnapshot().get(5, TimeUnit.SECONDS)
                h.facade.capture("quiet-before-restriction", null, Date(NOW_MS))
                when (change) {
                    "identity" -> h.facade.reset()
                    "consent" -> h.facade.optOut()
                    else -> {
                        gate.update(dev.elu.analytics.internal.config.V2ConfigLifecycleUpdate(2) { consumer -> consumer(body); true })
                        h.facade.configurationChanged()
                    }
                }
                assertNull(h.facade.getFeatureFlagSnapshot())
            } finally { release.countDown() }
            h.settle()
            assertEquals(change, 0, completed)
            if (change == "source") {
                // The response was accepted by the client before the same document received a
                // new gate token. Its cache remains valid; the original REMOTE completion does not.
                val cached = checkNotNull(h.facade.getFeatureFlagSnapshot())
                assertEquals(rejectedRequest, cached.requestId)
                assertEquals(EluFeatureFlagSnapshot.Source.CACHE, cached.source)
                assertNull(cached.error)
                assertEquals("variant-a", h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
                assertNull(h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false, fresh = true)))
                assertEquals(requestCount, transport.requests.size)
            } else {
                assertFalse(change, rejectedRequest == h.facade.getFeatureFlagSnapshot()?.requestId)
            }
            assertTrue(h.queued().isEmpty())
        }
    }

    @Test fun `actual completion survives listener quiet capture while preserving original callback order`() {
        for (queued in listOf(false, true)) {
            val callbacks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            val h = harness(callbackDelivery = { if (queued) callbacks.add(it) else it.run() },
                eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(
                    callback = dev.elu.analytics.EluEvent.Filter { null }))
            h.facade.applyConfiguration(config()); h.settle()
            val order = mutableListOf<String>()
            var notifications = 0
            h.facade.subscribeToFeatureFlags {
                notifications++; order += "listener-$notifications"
                if (notifications == 2) h.facade.capture("listener-before-completion", null, Date(NOW_MS))
            }; h.settle()
            while (true) (callbacks.poll() ?: break).run()
            h.facade.reloadFeatureFlags { order += "completion" }; h.settle()
            // In queued mode the original listener runs before the original completion task.
            // Its quiet capture then settles on the same facade lane before completion resumes.
            while (true) (callbacks.poll() ?: break).run()
            h.settle()
            while (true) (callbacks.poll() ?: break).run()
            assertEquals(listOf("listener-1", "listener-2", "completion"), order)
            assertEquals(2, h.flagTransport.requests.size); assertTrue(h.queued().isEmpty())
            assertNotNull(h.facade.getFeatureFlagSnapshot())
        }
    }

    @Test fun `quiet call during original full read retains the physical result until its own settlement`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val holdRead = java.util.concurrent.atomic.AtomicBoolean(false)
        val h = harness(eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(
            callback = dev.elu.analytics.EluEvent.Filter { null }), flagClientDecorator = { original ->
                object : FacadeFlagClient by original {
                    override fun readSnapshot(): SdkFuture<dev.elu.analytics.internal.flags.FlagSnapshotReadResult> {
                        val actual = original.readSnapshot()
                        if (!holdRead.compareAndSet(true, false)) return actual
                        val joined = SdkFuture<dev.elu.analytics.internal.flags.FlagSnapshotReadResult>()
                        actual.whenComplete { value, error ->
                            entered.countDown()
                            try {
                                check(release.await(5, TimeUnit.SECONDS))
                                if (error != null) joined.completeExceptionally(error) else joined.complete(checkNotNull(value))
                            } catch (failure: Throwable) { joined.completeExceptionally(failure) }
                        }
                        return joined
                    }
                }
            })
        h.facade.applyConfiguration(config()); h.settle()
        var completed = 0; var notifications = 0
        h.facade.subscribeToFeatureFlags { notifications++ }; h.settle()
        holdRead.set(true)
        try {
            h.facade.reloadFeatureFlags { completed++ }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.facade.capture("quiet-during-read", null, Date(NOW_MS))
            assertNull(h.facade.getFeatureFlagSnapshot()); assertEquals(0, completed)
        } finally { release.countDown() }
        h.settle()
        assertEquals(1, completed); assertEquals(2, notifications)
        assertEquals(2, h.flagTransport.requests.size); assertTrue(h.queued().isEmpty())
        assertEquals(EluFeatureFlagSnapshot.Source.REMOTE, checkNotNull(h.facade.getFeatureFlagSnapshot()).source)
    }

    @Test fun `initial unavailable load keeps its actual error and completion through listener quiet capture`() {
        for (queued in listOf(false, true)) for (transportFailure in listOf(false, true)) {
            val callbacks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            val transport = RespondingFlagTransport().apply { hold = true; invalidBytes = !transportFailure }
            val scheduler = ManualFlagRetryScheduler()
            val h = harness(suppliedFlagTransport = transport, retryScheduler = scheduler,
                callbackDelivery = { if (queued) callbacks.add(it) else it.run() },
                eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(
                    callback = dev.elu.analytics.EluEvent.Filter { null }))
            val observed = mutableListOf<EluFeatureFlagSnapshot>()
            val order = mutableListOf<String>()
            h.facade.subscribeToFeatureFlags {
                observed += it; order += "listener"
                if (observed.size == 1) h.facade.capture("quiet-unavailable", null, Date(NOW_MS))
            }
            h.facade.applyConfiguration(config())
            assertTrue(transport.requestObserved.await(5, TimeUnit.SECONDS))
            assertNull(h.facade.getFeatureFlagSnapshot())
            h.facade.reloadFeatureFlags {
                observed += checkNotNull(h.facade.getFeatureFlagSnapshot()); order += "completion"
            }
            h.diagnostics() // The explicit caller joined the same original held physical load.
            transport.releaseHeld(if (transportFailure) java.io.IOException("offline") else null)
            h.settle()
            while (true) (callbacks.poll() ?: break).run()
            h.settle()
            while (true) (callbacks.poll() ?: break).run()
            val current = checkNotNull(h.facade.getFeatureFlagSnapshot())
            assertEquals(listOf("listener", "completion"), order)
            assertEquals(2, observed.size)
            (observed + current).forEach {
                assertEquals(EluFeatureFlagSnapshot.Source.UNAVAILABLE, it.source)
                assertEquals(if (transportFailure) EluFeatureFlagSnapshot.LoadError.TRANSPORT else
                    EluFeatureFlagSnapshot.LoadError.INVALID_RESPONSE, it.error)
                assertFalse(it.isAvailable); assertTrue(it.entries.isEmpty()); assertNull(it.requestId)
                assertNull(it.evaluatedAt); assertNull(it.expiresAt)
            }
            assertNotSame(observed.first(), current)
            assertNull(h.facade.getFeatureFlag("variant", EluFeatureFlagOptions(sendEvent = false)))
            assertEquals(1, transport.requests.size); assertTrue(h.queued().isEmpty())
            assertEquals(listOf(5_000_000_000L), scheduler.entries.map { it.delay })
            assertFalse(scheduler.entries.single().canceled)
            h.facade.optOut(); h.settle()
            assertNull(h.facade.getFeatureFlagSnapshot()); assertTrue(scheduler.entries.single().canceled)
        }
    }

    @Test fun `quiet session renewal restores only the same original gate token and complete cache lease`() {
        val gate = dev.elu.analytics.internal.config.V2ConfigAuthorityGate()
        val body = config()
        val originalToken = dev.elu.analytics.internal.config.V2ConfigLifecycleUpdate(1) { consumer ->
            consumer(body); true
        }
        gate.update(originalToken)
        val renewals = AtomicInteger()
        val scheduler = ManualFlagRetryScheduler()
        val transport = RespondingFlagTransport()
        val h = harness(configurationGate = gate, suppliedFlagTransport = transport, retryScheduler = scheduler,
            startupObserver = RuntimeStartupObserver {
                if (it.phase == RuntimeStartupPhase.AUTHORITY_BEGIN) renewals.incrementAndGet()
            }, eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(
                callback = dev.elu.analytics.EluEvent.Filter { it }))
        h.facade.configurationChanged(); h.settle()
        assertNull(h.owner.snapshot().get().state.identity.session)
        transport.invalidBytes = true; h.facade.reloadFeatureFlags(null); h.settle()
        val before = checkNotNull(h.facade.getFeatureFlagSnapshot())
        val original = checkNotNull(gate.snapshot()); val timer = scheduler.entries.single()
        val beforeRenewals = renewals.get(); val loads = transport.requests.size
        h.facade.capture("first-session", null, Date(NOW_MS)); h.settle()
        assertNotNull(h.owner.snapshot().get().state.identity.session)
        assertTrue("Accepted session creation renewed the original authority", renewals.get() > beforeRenewals)
        val current = checkNotNull(gate.snapshot())
        assertNotSame(original, current); assertSame(original.token, current.token)
        assertTrue(original.isCurrent()); assertTrue(current.isCurrent())
        val after = checkNotNull(h.facade.getFeatureFlagSnapshot())
        assertEquals(before.requestId, after.requestId); assertEquals(before.source, after.source)
        assertEquals(EluFeatureFlagSnapshot.Source.REMOTE, after.source)
        assertEquals(EluFeatureFlagSnapshot.LoadError.INVALID_RESPONSE, after.error)
        assertEquals(before.expiresAt, after.expiresAt); assertEquals(loads, transport.requests.size)
        assertSame(timer, scheduler.entries.single()); assertFalse(timer.canceled)
        // Equal document text under a new original lifecycle token does not revive the old read.
        gate.update(dev.elu.analytics.internal.config.V2ConfigLifecycleUpdate(2) { consumer -> consumer(body); true })
        assertFalse(original.isCurrent()); assertNull(h.facade.getFeatureFlagSnapshot())
        h.facade.configurationChanged(); h.settle()
        assertTrue(timer.canceled)
        val replacementLoads = transport.requests.size
        timer.task(); h.settle()
        assertEquals(replacementLoads, transport.requests.size)
    }

    @Test fun `original due flag retry waits for quiet work without resetting its backoff`() {
        val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
        val scheduler = ManualFlagRetryScheduler()
        val transport = RespondingFlagTransport()
        val observedLoads = AtomicInteger(-1)
        val h = harness(facadeLane = lane, suppliedFlagTransport = transport, retryScheduler = scheduler,
            eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                observedLoads.set(transport.requests.size); null
            }))
        h.facade.applyConfiguration(config()); h.settle()
        transport.failing = true; h.facade.reloadFeatureFlags(null); h.settle()
        val loads = transport.requests.size; val retry = scheduler.entries.single()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        lane.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            retry.task() // Queue the actual original deadline before the quiet operation.
            h.facade.capture("quiet-due", null, Date(NOW_MS))
            assertNull(h.facade.getFeatureFlagSnapshot())
            assertEquals(loads, transport.requests.size)
        } finally { release.countDown() }
        h.settle()
        assertEquals(loads, observedLoads.get())
        assertEquals(loads + 1, transport.requests.size)
        assertEquals(listOf(5_000_000_000L, 10_000_000_000L), scheduler.entries.map { it.delay })
        assertTrue(h.queued().isEmpty())
        val next = scheduler.entries.last()
        h.facade.optOut(); h.settle()
        assertTrue(next.canceled)
        val stoppedLoads = transport.requests.size
        retry.task(); next.task(); h.settle()
        assertEquals(stoppedLoads, transport.requests.size)
    }

    @Test fun `quiet listener capture does not recursively announce the same flag load`() {
        val h = harness(eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(
            callback = dev.elu.analytics.EluEvent.Filter { null }))
        var callbacks = 0
        h.facade.subscribeToFeatureFlags {
            callbacks++
            if (callbacks <= 3) h.facade.capture("listener-capture", null, Date(NOW_MS))
        }
        h.facade.applyConfiguration(config()); h.settle()
        assertEquals(1, callbacks); assertEquals(1, h.flagTransport.requests.size)
        assertNotNull(h.facade.getFeatureFlagSnapshot()); assertTrue(h.queued().isEmpty())
        h.facade.reloadFeatureFlags(null); h.settle()
        assertEquals(2, callbacks); assertEquals(2, h.flagTransport.requests.size)
    }

    @Test fun `later quiet capture does not withdraw an earlier running hook or its accepted person continuation`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                calls += it.event
                if (it.event == "first") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                it.set = mutableMapOf("last" to it.event)
                it.setOnce = mutableMapOf("first" to it.event)
                it
            }))
        h.facade.applyConfiguration(config()); h.settle()
        val loads = h.flagTransport.requests.size
        h.facade.capture("first", null, EluCaptureOptions(set = mapOf("caller" to "first")))
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.facade.capture("second", null, EluCaptureOptions(set = mapOf("caller" to "second")))
            assertNull("Pending quiet work still withholds flag reads", h.facade.getFeatureFlagSnapshot())
        } finally { release.countDown() }
        h.settle()
        assertEquals(listOf("first", "second"), calls)
        assertEquals(listOf("event:first", "mutation:setPersonProperties", "event:second", "mutation:setPersonProperties"), h.queued())
        assertEquals(mapOf("last" to "second", "first" to "first"), h.owner.snapshot().get().state.flagContext.personProperties)
        assertEquals(loads, h.flagTransport.requests.size)
    }

    @Test fun `dropped event and reentrant consent or identity intent cannot append transformed person data`() {
        for (change in listOf("drop", "reset", "optout")) {
            lateinit var target: StandaloneFacade
            val filter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                it.set = mutableMapOf("private" to "rejected")
                when (change) {
                    "drop" -> null
                    "reset" -> { target.reset(); it }
                    else -> { target.optOut(); it }
                }
            })
            val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY, eventFilter = filter)
            target = h.facade; target.applyConfiguration(config()); h.settle()
            target.capture("blocked", null, EluCaptureOptions(set = mapOf("original" to true))); h.settle()
            assertFalse(change, h.records().any { it is RuntimeQueuedRecord.Event })
            assertFalse(change, h.queued().contains("mutation:setPersonProperties"))
            assertTrue(change, h.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
            if (change == "drop") assertNotNull(h.facade.getFeatureFlagSnapshot()) // Original quiet intent settled.
        }
    }

    @Test fun `dropped exposure leaves original marker available and retry does not expose stale identity`() {
        var allow = false
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                if (it.event == "\$feature_flag_called" && !allow) null else it
            }))
        h.facade.applyConfiguration(config()); h.settle()
        val before = h.owner.snapshot().get().exposures
        assertEquals("variant-a", h.facade.getFeatureFlag("variant")); h.settle()
        assertTrue(h.exposures().isEmpty()); assertEquals(before, h.owner.snapshot().get().exposures)
        allow = true
        assertEquals("variant-a", h.facade.getFeatureFlag("variant")); h.settle()
        assertEquals(1, h.exposures().size)
        h.facade.getFeatureFlag("variant"); h.settle(); assertEquals(1, h.exposures().size)
    }

    @Test fun `screen and handled exception share original filter and default callback adds no person mutation`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(listOf("private"), dev.elu.analytics.EluEvent.Filter {
                seen += it.event; assertFalse(it.properties.containsKey("private")); it
            }))
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.screen("Home", mapOf("private" to "hidden"))
        h.facade.captureException(IllegalStateException("example"), mapOf("private" to "hidden")); h.settle()
        assertEquals(listOf("Home", "\$exception"), seen)
        assertEquals(2, h.records().size); assertTrue(h.records().all { it is RuntimeQueuedRecord.Event })
        val screen = (h.records().first() as RuntimeQueuedRecord.Event).record
        assertEquals(RuntimeEventKind.SCREEN, screen.kind); assertEquals("Home", screen.name)
        assertEquals("Home", screen.properties["\$screen_name"])
        val wire = JSONObject(String(dev.elu.analytics.internal.runtime.RuntimeRecordCodec.encodeEvent(screen), StandardCharsets.UTF_8))
        assertEquals("screen", wire.getString("kind")); assertEquals("Home", wire.getString("name"))
        assertEquals("Home", wire.getJSONObject("properties").getString("\$screen_name"))
    }

    @Test fun `screen and handled exception continue person output once on original ordered owner`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                seen += it.event; it.set = mutableMapOf("last" to it.event); it.setOnce = mutableMapOf("first" to it.event); it
            }))
        h.facade.applyConfiguration(config()); h.settle()
        val requests = h.flagTransport.requests.size
        h.facade.screen("Home", null)
        assertNull(h.facade.getFeatureFlagSnapshot())
        h.facade.captureException(IllegalStateException("handled"), null); h.settle()
        assertEquals(listOf("Home", "\$exception"), seen)
        assertEquals(listOf("event:Home", "mutation:setPersonProperties", "event:\$exception", "mutation:setPersonProperties"), h.queued())
        assertEquals(mapOf("first" to "Home", "last" to "\$exception"), h.owner.snapshot().get().state.flagContext.personProperties)
        assertEquals(requests, h.flagTransport.requests.size)
        assertNull(h.facade.getFeatureFlagSnapshot())
        h.facade.reloadFeatureFlags(null); h.settle()
        assertEquals(requests + 1, h.flagTransport.requests.size)
        assertNotNull(h.facade.getFeatureFlagSnapshot())
    }

    @Test fun `identify same identity alias and group use one filtered projection without changing targets`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                seen += it.event
                when (it.event) {
                    "\$identify" -> { it.set = mutableMapOf("tier" to "filtered"); it.setOnce = null }
                    "\$set" -> { it.properties["\$set"] = mutableMapOf("person" to "filtered"); it.properties.remove("\$set_once") }
                    "\$groupidentify" -> it.properties.remove("\$group_set")
                }
                it.properties["distinct_id"] = "forged"; it.properties["alias"] = "forged"
                it.properties["\$group_key"] = "forged"; it.timestamp = Date(0); it
            }))
        h.facade.applyConfiguration(config()); h.settle()
        h.facade.identify("customer", mapOf("private" to true), mapOf("private-once" to true)); h.settle()
        h.facade.identify("customer", mapOf("private" to true)); h.settle()
        h.facade.alias("original-alias"); h.settle()
        h.facade.group("company", "original-group", mapOf("private" to true)); h.settle()
        assertEquals(listOf("\$identify", "\$set", "\$create_alias", "\$groupidentify"), seen)
        val changes = h.records().filterIsInstance<RuntimeQueuedRecord.Mutation>().map { it.envelope.mutation }
        assertEquals("customer", (changes[0].change as RuntimeMutationChange.Identify).userId)
        assertEquals("original-alias", (changes[2].change as RuntimeMutationChange.LinkAlias).aliasId)
        assertEquals(RuntimeMutationChange.AssociateGroup("company", "original-group"), changes[3].change)
        assertTrue(changes.all { it.occurredAt == NOW })
        assertEquals(mapOf("tier" to "filtered", "person" to "filtered"), h.owner.snapshot().get().state.flagContext.personProperties)
        assertTrue(h.owner.snapshot().get().state.flagContext.groupProperties.isEmpty())
    }

    @Test fun `capture associated mutation rechecks later original intent after its insert without losing accepted event`() {
        for (action in listOf("reset", "optout")) {
            lateinit var target: StandaloneFacade; var invoked = false
            val h = harness(personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
                eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                    it.set = mutableMapOf("private" to true); it
                }), databaseDecorator = { db -> object : dev.elu.analytics.internal.runtime.RuntimeQueueDatabase by db {
                    override fun <T> transaction(block: (dev.elu.analytics.internal.runtime.RuntimeQueueTransaction) -> T): T = db.transaction { tx ->
                        block(object : dev.elu.analytics.internal.runtime.RuntimeQueueTransaction by tx {
                            override fun insertRecord(record: dev.elu.analytics.internal.runtime.RuntimeStoredRecord) {
                                tx.insertRecord(record)
                                if (record.sequence == 1L && !invoked) {
                                    invoked = true
                                    if (action == "reset") target.reset() else target.optOut()
                                }
                            }
                        })
                    }
                } })
            target = h.facade; target.applyConfiguration(config()); h.settle()
            target.capture("accepted", null, EluCaptureOptions(set = mapOf("caller" to true))); h.settle()
            assertTrue(action, invoked)
            assertEquals(action, listOf("event:accepted"), h.queued())
            assertTrue(action, h.owner.snapshot().get().state.flagContext.personProperties.isEmpty())
        }
    }

    @Test fun `public flags only context filters under original gate while explicit flags APIs remain unfiltered`() {
        val gate = dev.elu.analytics.internal.config.V2ConfigAuthorityGate()
        val body = JSONObject(config()).also { it.getJSONObject("features").put("capture", false) }.toString()
        gate.update(dev.elu.analytics.internal.config.V2ConfigLifecycleUpdate(1) { consumer -> consumer(body); true })
        var calls = 0; var drop = false
        val h = harness(configurationGate = gate, personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            eventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(callback = dev.elu.analytics.EluEvent.Filter {
                calls++
                if (drop) null else {
                    if (it.event == "\$set") it.properties["\$set"] = mutableMapOf("filtered" to true)
                    if (it.event == "\$groupidentify") it.properties.remove("\$group_set")
                    it
                }
            }))
        h.facade.configurationChanged(); h.settle()
        h.facade.setPersonProperties(mapOf("private" to true)); h.settle()
        assertEquals(mapOf("filtered" to true), h.owner.snapshot().get().state.flagContext.personProperties)
        h.facade.group("company", "retained-target", mapOf("private" to true)); h.settle()
        assertEquals(mapOf("company" to "retained-target"), h.owner.snapshot().get().state.identity.groups)
        assertTrue(h.owner.snapshot().get().state.flagContext.groupProperties.isEmpty())
        val prior = h.owner.snapshot().get().state
        drop = true; h.facade.setPersonProperties(mapOf("rejected" to true)); h.settle()
        assertEquals(prior, h.owner.snapshot().get().state)
        h.facade.setPersonPropertiesForFlags(mapOf("explicit" to true)); h.settle()
        assertEquals(3, calls)
        assertEquals(mapOf("filtered" to true, "explicit" to true), h.owner.snapshot().get().state.flagContext.personProperties)
        assertTrue(h.records().isEmpty())
    }

    private fun harness(
        bufferLimit: Int = StandaloneFacade.PRE_INIT_BUFFER_LIMIT,
        deviceInEu: Boolean = false,
        wall: () -> Long = { NOW_MS },
        beforeOpen: () -> Unit = {},
        autoStart: Boolean = true,
        onOpened: (Boolean) -> Unit = {},
        backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(),
        databaseDecorator: (dev.elu.analytics.internal.runtime.RuntimeQueueDatabase) -> dev.elu.analytics.internal.runtime.RuntimeQueueDatabase = { it },
        facadeLane: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor(),
        networkConfigHost: String? = null,
        networkApiHost: String? = null,
        onCloseSettled: () -> SdkFuture<Unit> = { SdkFuture.completedFuture(Unit) },
        personProfiles: dev.elu.analytics.EluPersonProfilesMode? = null,
        suppliedFlagTransport: RespondingFlagTransport = RespondingFlagTransport(),
        flagClock: FlagClock = FixedFlagClock,
        retryScheduler: dev.elu.analytics.internal.config.V2ConfigLifecycleScheduler = ManualFlagRetryScheduler(),
        limits: RuntimeQueueLimits = RuntimeQueueLimits(10_000, 16_777_216),
        rateLimiting: dev.elu.analytics.EluRateLimitingOptions? = null,
        startupObserver: RuntimeStartupObserver = RuntimeStartupObserver.NONE,
        callbackDelivery: (Runnable) -> Unit = { it.run() },
        eventFilter: dev.elu.analytics.internal.runtime.RuntimeEventFilter = dev.elu.analytics.internal.runtime.RuntimeEventFilter(),
        configurationGate: dev.elu.analytics.internal.config.V2ConfigAuthorityGate? = null,
        flagClientDecorator: (FacadeFlagClient) -> FacadeFlagClient = { it },
    ): Harness {
        lateinit var facade: StandaloneFacade
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = "facade-${keyCounter.incrementAndGet()}",
                limits = limits,
                databaseFactory = { databaseDecorator(backing.connection()) },
                legacyStateLoader = ::initialState,
                personProfiles = personProfiles,
                rateLimiting = rateLimiting,
                eventFilter = eventFilter.boundTo { facade.eventFilterAdmission() },
                trustedSiteKey = SITE_KEY,
                captureClock = object : RuntimeCaptureClock {
                    override fun wallNowEpochMillis() = wall()
                    override fun elapsedRealtimeNanos() = 1_000L + (wall() - NOW_MS) * 1_000_000L
                },
            ).get(10, TimeUnit.SECONDS)
        owners += owner
        if (configurationGate != null) owner.bindConfigurationGate(configurationGate).get()
        val transport = RecordingBatchTransport()
        val runtime =
            StandaloneRuntime(
                owner = owner,
                siteKey = SITE_KEY,
                wallClock = wall,
                transportFactory = { transport },
                deviceInEuTimezone = { deviceInEu },
                configurationGate = configurationGate,
            )
        val flagTransport = suppliedFlagTransport
        val flags =
            AndroidFeatureFlagClient(
                owner,
                StandaloneRuntime.defaultVersions(),
                flagTransport,
                flagClock,
                FlagOpaqueIdSource { "flags_request_${flagTransport.requestIds.incrementAndGet()}" },
                FlagOpaqueIdSource { "store_epoch_1" },
                configurationGate = configurationGate,
            )
        facade =
            StandaloneFacade(
                open = { beforeOpen(); StandaloneStack(runtime, owner, flagClientDecorator(flags)) },
                deliverCallback = callbackDelivery,
                wallClock = wall,
                configurationGate = configurationGate,
                bufferLimit = bufferLimit,
                onOpened = { onOpened(owner.snapshot().get().state.identity.optedOut) },
                lane = facadeLane,
                flagRetryScheduler = retryScheduler,
                flagRetryJitter = { 0.0 },
                networkConfigHost = networkConfigHost,
                networkApiHost = networkApiHost,
                onCloseSettled = onCloseSettled,
                personProfiles = personProfiles ?: dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY,
                eventFilter = eventFilter,
                startupObserver = startupObserver,
            )
        facades += facade
        if (autoStart) facade.start()
        return Harness(owner, facade, transport, flagTransport, flags)
    }

    private class Harness(
        val owner: RuntimeQueueOwner,
        val facade: StandaloneFacade,
        val transport: RecordingBatchTransport,
        val flagTransport: RespondingFlagTransport,
        val flagClient: AndroidFeatureFlagClient,
    ) {
        fun settle() {
            // A flag reload leaves the lane and comes back as another lane task, so drain until
            // two consecutive drains find no reload in flight.
            repeat(400) {
                if (!diagnostics().flagReloadInFlight && !diagnostics().flagReloadInFlight) return
                Thread.sleep(5)
            }
            fail("the facade did not settle")
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
        @Volatile var failing = false
        @Volatile var variant: Any = "variant-a"
        @Volatile var transform: ((JSONObject) -> Unit)? = null
        @Volatile var invalidBytes = false
        @Volatile var hold = false
        @Volatile var requestObserved = CountDownLatch(1)
        private val held = mutableListOf<Pair<SdkFuture<ByteArray>, ByteArray>>()
        @Synchronized fun releaseHeld(failure: Throwable? = null) {
            val pending = held.toList(); held.clear(); hold = false
            pending.forEach { (future, bytes) ->
                if (failure == null) future.complete(bytes) else future.completeExceptionally(failure)
            }
        }

        @Synchronized
        override fun send(request: dev.elu.analytics.internal.flags.FlagTransportRequest): SdkFuture<ByteArray> {
            val body = JSONObject(String(request.canonicalBody, StandardCharsets.UTF_8))
            requests += body
            requestObserved.countDown()
            if (failing) return SdkFuture<ByteArray>().also { it.completeExceptionally(java.io.IOException("offline")) }
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
                            .put("variant", variant)
                            .put("bool-false", false),
                    )
                    .put("payloads", JSONObject().put("variant", JSONObject().put("buttonColor", "violet")))
            transform?.invoke(response)
            val bytes = if (invalidBytes) byteArrayOf(0xc3.toByte(), 0x28) else response.toString().toByteArray(StandardCharsets.UTF_8)
            return if (hold) SdkFuture<ByteArray>().also { held += it to bytes } else SdkFuture.completedFuture(bytes)
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
