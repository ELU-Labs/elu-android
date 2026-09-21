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

    // ---- harness -------------------------------------------------------------

    private fun harness(
        bufferLimit: Int = StandaloneFacade.PRE_INIT_BUFFER_LIMIT,
        deviceInEu: Boolean = false,
        wall: () -> Long = { NOW_MS },
        beforeOpen: () -> Unit = {},
        autoStart: Boolean = true,
        onOpened: (Boolean) -> Unit = {},
        backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(),
        facadeLane: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor(),
    ): Harness {
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = "facade-${keyCounter.incrementAndGet()}",
                limits = RuntimeQueueLimits(10_000, 16_777_216),
                databaseFactory = backing::connection,
                legacyStateLoader = ::initialState,
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
                open = { beforeOpen(); StandaloneStack(runtime, owner, flags) },
                deliverCallback = { callback -> callback.run() },
                wallClock = wall,
                bufferLimit = bufferLimit,
                onOpened = { onOpened(owner.snapshot().get().state.identity.optedOut) },
                lane = facadeLane,
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

        @Synchronized
        override fun send(request: dev.elu.analytics.internal.flags.FlagTransportRequest): SdkFuture<ByteArray> {
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
