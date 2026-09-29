package dev.elu.analytics.internal.facade

import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.flags.AndroidFeatureFlagClient
import dev.elu.analytics.internal.flags.FlagClock
import dev.elu.analytics.internal.flags.FlagOpaqueIdSource
import dev.elu.analytics.internal.flags.FlagTransport
import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `a flag read reports once per visitor and typed value across identify reload and reset`() {
        val harness = harness(personProfiles = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY)
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
        assertTrue((exposures.single()["\$feature_flag_request_id"] as String).startsWith("flags_request_"))
        assertEquals(NOW_MS, exposures.single()["\$feature_flag_evaluated_at"])
        assertEquals(false, exposures.single()["\$used_bootstrap_value"])
        assertTrue(exposures.single().containsKey("\$feature_flag_bootstrapped_response"))
        assertNull(exposures.single()["\$feature_flag_bootstrapped_response"])
        assertNull(exposures.single()["\$feature_flag_bootstrapped_payload"])

        harness.facade.getFeatureFlag("variant")
        harness.settle()
        assertEquals(1, harness.exposures().size)

        harness.facade.identify("user_2", null)
        harness.settle()
        harness.facade.getFeatureFlag("variant")
        harness.settle()
        assertEquals(1, harness.exposures().size)
        harness.facade.reloadFeatureFlags {}; harness.settle()
        harness.facade.getFeatureFlag("variant"); harness.settle()
        assertEquals(1, harness.exposures().size)
        harness.facade.reset(); harness.settle()
        harness.facade.getFeatureFlag("variant"); harness.settle()
        harness.facade.getFeatureFlag("variant"); harness.settle()
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
        assertNull(harness.facade.getFeatureFlagResult("variant"))
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
            h.facade.getFeatureFlag("variant"); h.settle()
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
        h.facade.getFeatureFlag("variant"); h.settle()
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
        h.facade.getFeatureFlag("variant"); h.settle()
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
        retryScheduler: dev.elu.analytics.internal.config.V2ConfigLifecycleScheduler = ManualFlagRetryScheduler(),
        limits: RuntimeQueueLimits = RuntimeQueueLimits(10_000, 16_777_216),
        rateLimiting: dev.elu.analytics.EluRateLimitingOptions? = null,
    ): Harness {
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = "facade-${keyCounter.incrementAndGet()}",
                limits = limits,
                databaseFactory = { databaseDecorator(backing.connection()) },
                legacyStateLoader = ::initialState,
                personProfiles = personProfiles,
                rateLimiting = rateLimiting,
                trustedSiteKey = SITE_KEY,
                captureClock = object : RuntimeCaptureClock {
                    override fun wallNowEpochMillis() = wall()
                    override fun elapsedRealtimeNanos() = 1_000L + (wall() - NOW_MS) * 1_000_000L
                },
            ).get(10, TimeUnit.SECONDS)
        owners += owner
        val transport = RecordingBatchTransport()
        val runtime =
            StandaloneRuntime(
                owner = owner,
                siteKey = SITE_KEY,
                wallClock = wall,
                transportFactory = { transport },
                deviceInEuTimezone = { deviceInEu },
            )
        val flagTransport = suppliedFlagTransport
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
                open = { beforeOpen(); StandaloneStack(runtime, owner, flags) },
                deliverCallback = { callback -> callback.run() },
                wallClock = wall,
                bufferLimit = bufferLimit,
                onOpened = { onOpened(owner.snapshot().get().state.identity.optedOut) },
                lane = facadeLane,
                flagRetryScheduler = retryScheduler,
                flagRetryJitter = { 0.0 },
                networkConfigHost = networkConfigHost,
                networkApiHost = networkApiHost,
                onCloseSettled = onCloseSettled,
                personProfiles = personProfiles ?: dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY,
            )
        facades += facade
        if (autoStart) facade.start()
        return Harness(owner, facade, transport, flagTransport)
    }

    private class Harness(
        val owner: RuntimeQueueOwner,
        val facade: StandaloneFacade,
        val transport: RecordingBatchTransport,
        val flagTransport: RespondingFlagTransport,
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

        @Synchronized
        override fun send(request: dev.elu.analytics.internal.flags.FlagTransportRequest): SdkFuture<ByteArray> {
            val body = JSONObject(String(request.canonicalBody, StandardCharsets.UTF_8))
            requests += body
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
            return SdkFuture.completedFuture(response.toString().toByteArray(StandardCharsets.UTF_8))
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
