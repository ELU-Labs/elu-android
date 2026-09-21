package dev.elu.analytics.internal.config

import dev.elu.analytics.internal.concurrent.SdkFuture

import dev.elu.analytics.internal.flags.AndroidFeatureFlagClient
import dev.elu.analytics.internal.flags.FlagClock
import dev.elu.analytics.internal.flags.FlagOpaqueIdSource
import dev.elu.analytics.internal.flags.FlagTransport
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.facade.EluFacadeState
import dev.elu.analytics.internal.facade.StandaloneFacade
import dev.elu.analytics.internal.facade.StandaloneStack
import dev.elu.analytics.internal.runtime.FakeRuntimeQueueBacking
import dev.elu.analytics.internal.runtime.RuntimeAppendResult
import dev.elu.analytics.internal.runtime.RuntimeAppendRejection
import dev.elu.analytics.internal.runtime.RuntimeRecordDraft
import dev.elu.analytics.internal.runtime.RuntimeMutationChange
import dev.elu.analytics.internal.runtime.RuntimeLocalStateChange
import dev.elu.analytics.internal.runtime.RuntimeEventSessionUpdate
import dev.elu.analytics.internal.runtime.RuntimeEventKind
import dev.elu.analytics.internal.runtime.RuntimeCaptureAuthorityUpdateResult
import dev.elu.analytics.internal.runtime.RuntimeCaptureClock
import dev.elu.analytics.internal.runtime.RuntimeCaptureResult
import dev.elu.analytics.internal.runtime.RuntimeQueueDatabase
import dev.elu.analytics.internal.runtime.RuntimeQueueLimits
import dev.elu.analytics.internal.runtime.RuntimeQueueOwner
import dev.elu.analytics.internal.runtime.RuntimeQueuedRecord
import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
import dev.elu.analytics.internal.runtime.RuntimeQueueTransaction
import dev.elu.analytics.internal.runtime.RuntimeStoredCore
import dev.elu.analytics.internal.runtime.StandaloneRuntime
import dev.elu.analytics.internal.runtime.delivery.BatchDeliveryClock
import dev.elu.analytics.internal.runtime.delivery.BatchWallInstant
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPResponse
import dev.elu.analytics.internal.runtime.delivery.BatchHTTPTransport
import java.time.Instant
import java.util.ArrayDeque
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V2ConfigCompositionTest {
    @Test fun `raw configuration cannot activate a composed owner without a source token`() = Rig().use { rig ->
        rig.facade.applyConfiguration(rig.body)
        rig.settle()
        assertFalse(rig.facade.state() is EluFacadeState.Enabled)
        assertTrue(rig.runtime.capture("blocked").get() is RuntimeCaptureResult.Rejected)
        assertEquals(0, rig.owner.snapshot().get().queuedCount)
    }

    @Test fun `validated source activates and synchronous background gate defeats delayed facade withdrawal`() = Rig().use { rig ->
        rig.enable()
        rig.facade.capture("before", null, Date(rig.clock.wall))
        rig.settle()
        assertEquals(1, rig.owner.snapshot().get().queuedCount)
        rig.driver.onBackground() // listener updates the gate, deliberately does not drain the facade
        assertTrue(rig.facade.state() is EluFacadeState.Disabled)
        assertNull(rig.facade.distinctId())
        assertTrue(rig.runtime.capture("after").get() is RuntimeCaptureResult.Rejected)
        rig.facade.capture("also-after", null, Date(rig.clock.wall))
        rig.settle()
        assertEquals(1, rig.owner.snapshot().get().queuedCount)
        rig.runtime.flush().get(2, TimeUnit.SECONDS)
        assertEquals(0, rig.networkCalls)
    }

    @Test fun `withdrawal during SQLite activation prevents final authority publication`() = Rig().use { rig ->
        rig.driver.start(); rig.worker.runNext()
        rig.onRead = { rig.driver.onBackground() }
        assertTrue(rig.runtime.applyConfiguration(rig.body).get() is RuntimeCaptureAuthorityUpdateResult.Terminated)
        assertTrue(rig.runtime.capture("blocked").get() is RuntimeCaptureResult.Rejected)
        assertEquals(0, rig.owner.snapshot().get().queuedCount)
    }

    @Test fun `withdrawal after SQLite admission prevents first capture write`() = Rig().use { rig ->
        rig.enable()
        rig.onRead = { rig.driver.onBackground() }
        assertTrue(rig.runtime.capture("blocked").get() is RuntimeCaptureResult.Rejected)
        assertEquals(0, rig.owner.snapshot().get().queuedCount)
    }

    @Test fun `source retained deadline wins when authority is installed late and wall time stalls`() = Rig().use { rig ->
        rig.driver.start(); rig.worker.runNext()
        rig.clock.nanos += 120_000_000_000L
        rig.facade.configurationChanged(); rig.settle()
        assertTrue(rig.facade.state() is EluFacadeState.Enabled)
        rig.clock.nanos += 120_000_000_000L
        assertTrue(rig.runtime.capture("expired").get() is RuntimeCaptureResult.Rejected)
        assertEquals(0, rig.owner.snapshot().get().queuedCount)
    }

    @Test fun `old token cannot revive after same-document foreground restoration`() = Rig().use { rig ->
        rig.enable()
        val old = checkNotNull(rig.gate.snapshot())
        rig.driver.onBackground()
        rig.driver.onForeground(); rig.worker.runNext()
        assertFalse(old.consume { fail("old source generation executed") })
        assertTrue(checkNotNull(rig.gate.snapshot()).isCurrent())
        rig.facade.configurationChanged(); rig.settle()
        assertTrue(rig.runtime.capture("restored").get() is RuntimeCaptureResult.Accepted)
    }

    @Test fun `fully applied background suspension resumes same issuance and drains fixed event`() =
        Rig(withFlags = true, acknowledgeEvents = true).use { rig ->
            rig.enable(); rig.settleFlags()
            rig.facade.capture("before", null, Date(rig.clock.wall)); rig.settle()
            rig.runtime.flush().get(2, TimeUnit.SECONDS)
            // trigger() may return COALESCED while the original admitted pass is still running.
            val initialDrainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (rig.owner.snapshot().get().queuedCount != 0 && System.nanoTime() < initialDrainDeadline) Thread.sleep(1)
            assertEquals(0, rig.owner.snapshot().get().queuedCount)
            val original = checkNotNull(rig.gate.snapshot())
            val issuance = rig.body
            rig.clock.wall += 1_000; rig.clock.nanos += 1_000_000_000
            val backgroundAt = Instant.ofEpochMilli(rig.clock.wall).toString()

            // Match AndroidStandaloneStack: withdraw the gate and enqueue the fixed event,
            // then completely process the asynchronous null notification before foreground.
            rig.facade.nativeReplayLifecycleChanged(false)
            checkNotNull(rig.runtime.applicationBackgrounded(rig.driver, backgroundAt)).get(2, TimeUnit.SECONDS)
            rig.runtime.configurationChanged(); rig.facade.configurationChanged(); rig.settle()
            assertNull(rig.gate.snapshot()?.body)
            assertFalse(original.isCurrent())
            assertTrue(rig.facade.state() is EluFacadeState.Disabled)
            val pending = rig.owner.peek(10, 100_000).get().single() as RuntimeQueuedRecord.Event
            assertEquals(StandaloneRuntime.APPLICATION_BACKGROUNDED_EVENT, pending.record.name)
            assertEquals(backgroundAt, pending.record.occurredAt)
            assertEquals(1L, pending.sequence)
            assertEquals(1, rig.sentEvents.size)

            // The real foreground callback runs before the held HTTP refresh returns.
            rig.facade.nativeReplayLifecycleChanged(true)
            rig.driver.onForeground(); rig.runtime.markForegrounded()
            rig.facade.capture(StandaloneRuntime.APPLICATION_OPENED_EVENT,
                mapOf(StandaloneRuntime.FROM_BACKGROUND_PROPERTY to true), Date(rig.clock.wall))
            rig.settle()
            assertEquals(listOf(pending), rig.owner.peek(10, 100_000).get())
            assertEquals(1, rig.sentEvents.size)
            rig.worker.runNext()
            assertEquals(issuance, rig.gate.snapshot()?.body)
            rig.runtime.configurationChanged(); rig.facade.configurationChanged(); rig.settle()

            assertTrue("same valid issuance must resume after transient background suspension",
                rig.facade.state() is EluFacadeState.Enabled)
            // No new capture, explicit flush, or scheduled timer after installation.
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (rig.owner.snapshot().get().queuedCount != 0 && System.nanoTime() < drainDeadline) Thread.sleep(1)
            assertEquals(0, rig.owner.snapshot().get().queuedCount)
            assertEquals(2L, rig.owner.snapshot().get().state.stream.nextSequence)
            val delivered = rig.sentEvents.last()
            assertEquals(2, rig.sentEvents.size)
            assertEquals(pending.recordId, delivered.getString("eventId"))
            assertEquals(pending.sequence, delivered.getLong("sequence"))
            assertEquals(pending.record.name, delivered.getString("name"))
            assertEquals(pending.record.occurredAt, delivered.getString("occurredAt"))
            assertEquals(pending.record.sessionId, delivered.getString("sessionId"))
            assertEquals(pending.record, RuntimeRecordCodec.decodeEvent(delivered.toString().encodeToByteArray()))
            rig.settleFlags()
            rig.facade.getFeatureFlag("variant"); rig.settle()
            assertEquals("a", rig.facade.getFeatureFlag("variant"))
        }

    @Test fun `invalid restricted expired and opted-out resume stay closed after suspension`() {
        for (mode in listOf("malformed", "conflict", "disabled", "revoked", "expired", "opted-out")) {
            Rig(withFlags = true).use { rig ->
                rig.enable(); rig.settleFlags()
                val original = rig.body
                rig.driver.onBackground(); rig.runtime.configurationChanged()
                rig.facade.configurationChanged(); rig.settle()
                assertTrue(checkNotNull(rig.gate.snapshot()).isApplicationSuspended)
                assertNull(rig.facade.getFeatureFlag("variant"))
                assertTrue(rig.runtime.capture("during-suspension").get() is RuntimeCaptureResult.Rejected)
                when (mode) {
                    "malformed" -> rig.body = "{"
                    "conflict" -> rig.body = JSONObject(original).put("revision", "equal-conflict").toString()
                    "disabled", "revoked" -> rig.body = JSONObject().put("schemaVersion", 2)
                        .put("revision", mode).put("status", mode).put("reason", "test")
                        .put("issuedAt", "2026-08-05T00:00:30.000Z")
                        .put("expiresAt", "2026-08-05T00:05:00.000Z").toString()
                    "expired" -> rig.clock.nanos += 240_000_000_000L
                    "opted-out" -> rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true,
                        "2026-08-05T00:01:00.000Z")).get()
                }
                rig.driver.onForeground(); rig.worker.runNext()
                rig.runtime.configurationChanged(); rig.facade.configurationChanged(); rig.settle()
                assertFalse(mode, rig.facade.state() is EluFacadeState.Enabled)
                assertTrue(mode, rig.runtime.capture("must-stay-blocked").get() is RuntimeCaptureResult.Rejected)
                assertNull(mode, rig.facade.getFeatureFlag("variant"))
                assertEquals(mode, 0, rig.owner.snapshot().get().queuedCount)
                assertEquals(mode, 0, rig.networkCalls)
                if (mode == "malformed" || mode == "conflict") {
                    // Returning the exact former issuance cannot undo a real poisoned boundary.
                    rig.body = original; rig.driver.refresh(); rig.worker.runNext()
                    rig.runtime.configurationChanged(); rig.facade.configurationChanged(); rig.settle()
                    assertFalse(mode, rig.facade.state() is EluFacadeState.Enabled)
                    assertTrue(rig.runtime.capture("equal-poisoned").get() is RuntimeCaptureResult.Rejected)
                    assertNull(rig.facade.getFeatureFlag("variant"))
                }
            }
        }
    }

    @Test fun `explicit invalid document still poisons equal issuance on injected facade`() = Rig(bindGate = false).use { rig ->
        rig.facade.applyConfiguration(rig.body); rig.settle()
        assertTrue(rig.facade.state() is EluFacadeState.Enabled)
        rig.facade.applyConfiguration(null); rig.settle()
        rig.facade.applyConfiguration(rig.body); rig.settle()
        assertFalse(rig.facade.state() is EluFacadeState.Enabled)
        assertTrue(rig.runtime.capture("equal-poisoned").get() is RuntimeCaptureResult.Rejected)
    }

    @Test fun `close invalidates gate before queued facade cleanup`() = Rig().use { rig ->
        rig.enable()
        rig.facade.close()
        assertNull(rig.gate.snapshot())
        assertFalse(rig.facade.state() is EluFacadeState.Enabled)
    }

    @Test fun `explicit null also withdraws the original injected facade path`() = Rig(bindGate = false).use { rig ->
        rig.facade.applyConfiguration(rig.body); rig.settle()
        assertTrue(rig.facade.state() is EluFacadeState.Enabled)
        rig.facade.applyConfiguration(null); rig.settle()
        assertTrue(rig.facade.state() is EluFacadeState.Disabled)
        assertTrue(rig.runtime.capture("withdrawn").get() is RuntimeCaptureResult.Rejected)
    }

    @Test fun `flags remain usable while valid configuration disables capture`() = Rig(withFlags = true).use { rig ->
        rig.body = JSONObject(rig.body).also { it.getJSONObject("features").put("capture", false) }.toString()
        rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
        assertTrue(rig.facade.state() is EluFacadeState.Disabled)
        assertTrue(rig.runtime.capture("blocked").get() is RuntimeCaptureResult.Rejected)
        rig.facade.getFeatureFlag("variant"); rig.settle()
        assertEquals("a", rig.facade.getFeatureFlag("variant"))
        rig.driver.onBackground()
        assertNull(rig.facade.getFeatureFlag("variant"))
    }

    @Test fun `capture-off flags-on setters update local context and reload without collection or backfill`() =
        Rig(withFlags = true).use { rig ->
            rig.body = JSONObject(rig.body).also { it.getJSONObject("features").put("capture", false) }.toString()
            rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
            val original = rig.owner.snapshot().get().state.identity
            rig.facade.setPersonProperties(mapOf("role" to "member"))
            rig.facade.group("company", "team-a", mapOf("tier" to "trial"))
            rig.facade.setPersonPropertiesForFlags(mapOf("role" to "owner"))
            rig.facade.setGroupPropertiesForFlags("company", mapOf("tier" to "paid"))
            rig.settle(); rig.settleFlags()
            val current = rig.owner.snapshot().get()
            assertEquals(0, current.queuedCount)
            assertEquals(original.anonymousId, current.state.identity.anonymousId)
            assertEquals(original.userId, current.state.identity.userId)
            assertEquals(original.revision, current.state.identity.revision)
            assertEquals("team-a", current.state.identity.groups["company"])
            assertEquals("owner", current.state.flagContext.personProperties["role"])
            assertEquals("paid", current.state.flagContext.groupProperties["company"]?.get("tier"))
            val sent = checkNotNull(rig.lastFlagRequest)
            assertEquals("owner", sent.getJSONObject("personProperties").getString("role"))
            assertEquals("team-a", sent.getJSONObject("groups").getString("company"))
            assertEquals("paid", sent.getJSONObject("groupProperties").getJSONObject("company").getString("tier"))
            rig.body = JSONObject(rig.body).put("revision", "capture-restored").put("issuedAt", "2026-08-05T00:00:30.000Z")
                .also { it.getJSONObject("features").put("capture", true) }.toString()
            rig.driver.refresh(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
            assertTrue(rig.facade.state() is EluFacadeState.Enabled)
            assertEquals(0, rig.owner.snapshot().get().queuedCount)
        }

    @Test fun `capture-off setters deny disabled flags and expired source`() {
        listOf(false, true).forEach { expire -> Rig(withFlags = true).use { rig ->
            rig.body = JSONObject(rig.body).also {
                it.getJSONObject("features").put("capture", false).put("flags", expire)
            }.toString()
            rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
            val before = rig.owner.snapshot().get()
            if (expire) rig.clock.nanos += 240_000_000_000L
            rig.facade.setPersonProperties(mapOf("role" to "denied"))
            rig.facade.group("company", "denied", mapOf("tier" to "denied"))
            rig.facade.setPersonPropertiesForFlags(mapOf("role" to "denied"))
            rig.facade.setGroupPropertiesForFlags("company", mapOf("tier" to "denied"))
            rig.settle()
            assertEquals(before, rig.owner.snapshot().get())
        } }
    }

    @Test fun `flags-off denies overrides without disabling ordinary capture-on person mutation`() = Rig(withFlags = true).use { rig ->
        rig.body = JSONObject(rig.body).also { it.getJSONObject("features").put("flags", false) }.toString()
        rig.enable()
        val before = rig.owner.snapshot().get()
        rig.facade.setPersonPropertiesForFlags(mapOf("role" to "denied"))
        rig.facade.setGroupPropertiesForFlags("company", mapOf("tier" to "denied"))
        rig.settle()
        assertEquals(before, rig.owner.snapshot().get())
        rig.facade.setPersonProperties(mapOf("role" to "captured")); rig.settle()
        val after = rig.owner.snapshot().get()
        assertEquals(1, after.queuedCount)
        assertEquals("captured", after.state.flagContext.personProperties["role"])
    }

    @Test fun `flag context source withdrawal during storage rolls back local change`() = Rig(withFlags = true).use { rig ->
        rig.body = JSONObject(rig.body).also { it.getJSONObject("features").put("capture", false) }.toString()
        rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
        val before = rig.owner.snapshot().get()
        rig.onWrite = { rig.driver.onBackground() }
        val result = rig.owner.applyFlagContext(RuntimeLocalStateChange.SetFlagPersonProperties(
            mapOf("role" to "denied"), "2026-08-05T00:01:00.000Z")).get()
        assertTrue(result is RuntimeAppendResult.Rejected && result.reason == RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE)
        assertEquals(before, rig.owner.snapshot().get())
    }

    @Test fun `flag context wall rollback remains terminal after owner clock recovers`() = Rig(withFlags = true).use { rig ->
        rig.body = JSONObject(rig.body).also { it.getJSONObject("features").put("capture", false) }.toString()
        rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
        val before = rig.owner.snapshot().get()
        // Keep the independent source lease valid while exercising the durable owner's floor.
        rig.ownerWall = rig.clock.wall - 1
        val change = RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("role" to "denied"),
            "2026-08-05T00:01:00.000Z")
        assertTrue(rig.owner.applyFlagContext(change).get() is RuntimeAppendResult.Rejected)
        rig.ownerWall = rig.clock.wall
        assertTrue(rig.owner.applyFlagContext(change).get() is RuntimeAppendResult.Rejected)
        assertEquals(before, rig.owner.snapshot().get())
    }

    @Test fun `flag context changes deny current opted-out identity`() = Rig(withFlags = true).use { rig ->
        rig.body = JSONObject(rig.body).also { it.getJSONObject("features").put("capture", false) }.toString()
        rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
        rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, "2026-08-05T00:01:00.000Z")).get()
        val before = rig.owner.snapshot().get()
        val result = rig.owner.applyFlagContext(RuntimeLocalStateChange.SetFlagGroup(
            "company", "denied", null, "2026-08-05T00:01:00.000Z")).get()
        assertTrue(result is RuntimeAppendResult.Rejected)
        assertEquals(before, rig.owner.snapshot().get())
    }

    @Test fun `withdrawal before queued customer callback suppresses stale flags-loaded notification`() =
        Rig(withFlags = true, queueCallbacks = true).use { rig ->
            var callbacks = 0
            rig.facade.onFeatureFlagsLoaded { callbacks++ }
            rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
            assertFalse(rig.callbacks.isEmpty())
            rig.driver.onBackground()
            rig.callbacks.forEach { it.run() }
            assertEquals(0, callbacks)
        }

    @Test fun `queued callback from prior identity is discarded after same-config reauthorization`() =
        Rig(withFlags = true, queueCallbacks = true).use { rig ->
            var callbacks = 0
            rig.facade.onFeatureFlagsLoaded { callbacks++ }
            rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
            val oldCallbacks = rig.callbacks.toList()
            assertFalse(oldCallbacks.isEmpty())
            rig.facade.identify("user-new", null); rig.settleFlags()
            oldCallbacks.forEach { it.run() }
            assertEquals(0, callbacks)
            rig.callbacks.drop(oldCallbacks.size).forEach { it.run() }
            assertTrue(callbacks > 0)
        }

    @Test fun `public context intent closes getter and queued callback before owner work begins`() {
        val changes: List<Pair<String, (StandaloneFacade) -> Unit>> = listOf(
            "person flags" to { it.setPersonPropertiesForFlags(mapOf("role" to "new")) },
            "group flags" to { it.setGroupPropertiesForFlags("company", mapOf("tier" to "new")) },
            "person" to { it.setPersonProperties(mapOf("role" to "new")) },
            "group" to { it.group("company", "new", null) },
            "identify" to { it.identify("new-user", null) },
            "reset" to { it.reset() },
            "super properties" to { it.register(mapOf("screen" to "new")) },
            "unregister" to { it.unregister("screen") },
        )
        changes.forEach { (name, change) ->
            Rig(withFlags = true, queueCallbacks = true).use { rig ->
                var callbacks = 0
                rig.facade.onFeatureFlagsLoaded { callbacks++ }
                rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
                rig.facade.getFeatureFlag("variant"); rig.settle()
                assertEquals(name, "a", rig.facade.getFeatureFlag("variant")); rig.settle()
                val oldCallbacks = rig.callbacks.toList()
                assertFalse(name, oldCallbacks.isEmpty())
                val entered = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                rig.onRead = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
                val held = rig.runtime.capture("held-owner")
                check(entered.await(2, TimeUnit.SECONDS))
                try {
                    change(rig.facade)
                    assertNull(name, rig.facade.getFeatureFlag("variant"))
                    assertNull(name, rig.facade.getFeatureFlagPayload("variant"))
                    assertFalse(name, rig.facade.isFeatureEnabled("variant"))
                    oldCallbacks.forEach { it.run() }
                    assertEquals(name, 0, callbacks)
                } finally { release.countDown() }
                held.get(2, TimeUnit.SECONDS)
                rig.settle(); rig.settleFlags()
                oldCallbacks.forEach { it.run() }
                assertEquals(name, 0, callbacks)
            }
        }
    }

    @Test fun `context intent settles before following explicit reload completion`() {
        val changes: List<(StandaloneFacade) -> Unit> = listOf(
            { it.identify("next-user", null) },
            { it.setPersonPropertiesForFlags(mapOf("role" to "next")) },
        )
        changes.forEach { change -> Rig(withFlags = true).use { rig ->
            rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val completed = java.util.concurrent.CountDownLatch(1)
            var callbacks = 0
            rig.onRead = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            val held = rig.runtime.capture("held-owner")
            check(entered.await(2, TimeUnit.SECONDS))
            try {
                change(rig.facade)
                rig.facade.reloadFeatureFlags { callbacks++; completed.countDown() }
            } finally { release.countDown() }
            held.get(2, TimeUnit.SECONDS)
            check(completed.await(2, TimeUnit.SECONDS))
            rig.settleFlags()
            assertEquals(1, callbacks)
        } }
    }

    @Test fun `failure after stack publication closes flags and owner resources`() = Rig().use { rig ->
        val failed = java.util.concurrent.CountDownLatch(1)
        val flagsClosed = java.util.concurrent.CountDownLatch(1)
        var flagCloses = 0
        val facade = StandaloneFacade(open = {
            StandaloneStack(rig.runtime, rig.owner, object : dev.elu.analytics.internal.facade.FacadeFlagClient {
                override fun applyConfiguration(configBody: String?) = error("unused")
                override fun reload() = error("unused")
                override fun read(key: String) = error("unused")
                override fun isCacheLeaseCurrent(token: dev.elu.analytics.internal.flags.FlagCacheLeaseToken) = false
                override fun close() { flagCloses++; flagsClosed.countDown() }
            })
        }, deliverCallback = { it.run() }, onOpened = { error("subscription unavailable") },
            onCloseRequested = { failed.countDown() })
        facade.start(); check(failed.await(2, TimeUnit.SECONDS))
        // Both closures must come from startup cleanup, without the test closing either resource.
        check(flagsClosed.await(2, TimeUnit.SECONDS))
        check(rig.databaseClosed.await(2, TimeUnit.SECONDS))
        assertEquals(1, flagCloses)
        assertTrue(runCatching { rig.owner.snapshot().get(2, TimeUnit.SECONDS) }.isFailure)
        facade.close()
    }

    @Test fun `open failure closes lifecycle subscription before disabling facade`() {
        var closes = 0
        val failed = java.util.concurrent.CountDownLatch(1)
        val facade = StandaloneFacade(open = { error("unavailable storage") }, deliverCallback = { it.run() },
            onCloseRequested = { closes++; failed.countDown() })
        facade.start(); check(failed.await(2, TimeUnit.SECONDS))
        assertEquals(1, closes)
        assertTrue(facade.state() is EluFacadeState.Disabled)
        facade.close()
        assertEquals(1, closes)
    }

    @Test fun `source renewal discards old facade flag values and reloads observed keys`() = Rig(withFlags = true).use { rig ->
        rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
        rig.facade.getFeatureFlag("variant"); rig.settle()
        assertEquals("a", rig.facade.getFeatureFlag("variant"))
        rig.flagVariant = "b"
        rig.body = JSONObject(rig.body).put("revision", "renewed").put("issuedAt", "2026-08-05T00:01:00.000Z")
            .put("expiresAt", "2026-08-05T00:06:00.000Z").toString()
        rig.driver.refresh(); rig.worker.runNext()
        assertNull(rig.facade.getFeatureFlag("variant"))
        rig.facade.configurationChanged(); rig.settleFlags()
        assertEquals("b", rig.facade.getFeatureFlag("variant"))
    }

    @Test fun `transaction withdrawal rejects queued mutation but permits local reset continuity`() = Rig().use { rig ->
        rig.enable()
        val before = rig.owner.snapshot().get().state.identity
        rig.onRead = { rig.driver.onBackground() }
        val mutation = RuntimeRecordDraft.Mutation("2026-08-05T00:01:00.000Z",
            RuntimeMutationChange.Identify("blocked-user", emptyMap(), emptyMap()), StandaloneRuntime.defaultVersions())
        val result = rig.owner.appendMutations(listOf(mutation)).get()
        assertTrue(result is RuntimeAppendResult.Rejected && result.reason == RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE)
        assertEquals(before, rig.owner.snapshot().get().state.identity)
        assertEquals(0, rig.owner.snapshot().get().queuedCount)
        assertTrue(rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity("2026-08-05T00:01:00.000Z")).get() is RuntimeAppendResult.Accepted)
        assertTrue(rig.owner.snapshot().get().state.identity.revision > before.revision)
    }

    @Test fun `transaction withdrawal rejects low-level queued event append`() = Rig().use { rig ->
        rig.enable()
        assertTrue(rig.runtime.capture("establish-session").get() is RuntimeCaptureResult.Accepted)
        val session = checkNotNull(rig.owner.snapshot().get().state.identity.session)
        rig.onRead = { rig.driver.onBackground() }
        val draft = RuntimeRecordDraft.Event(RuntimeEventKind.CAPTURE, "blocked-event", "2026-08-05T00:01:00.000Z",
            session.id, emptyMap(), StandaloneRuntime.defaultVersions())
        val result = rig.owner.appendEvents(RuntimeEventSessionUpdate.Preserve, listOf(draft)).get()
        assertTrue(result is RuntimeAppendResult.Rejected && result.reason == RuntimeAppendRejection.AUTHORIZATION_UNAVAILABLE)
        assertEquals(1, rig.owner.snapshot().get().queuedCount)
    }

    @Test fun `queued delivery remains lawful across local context and session changes but not opt-out`() = Rig().use { rig ->
        rig.enable()
        assertTrue(rig.runtime.capture("queued-before-context-change").get() is RuntimeCaptureResult.Accepted)
        rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity("2026-08-05T00:01:00.000Z")).get()
        assertTrue(rig.runtime.capture("fresh-needs-reauthorization").get() is RuntimeCaptureResult.Rejected)
        assertTrue(rig.owner.authorizeCurrentDelivery(rig.body) { false }.get())
        rig.runtime.flush().get(2, TimeUnit.SECONDS)
        // A coalesced trigger does not await the pass already admitted by configuration.
        assertTrue(rig.firstNetworkCall.await(2, TimeUnit.SECONDS))
        assertEquals(1, rig.networkCalls)
        rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, "2026-08-05T00:01:00.000Z")).get()
        assertFalse(rig.owner.authorizeCurrentDelivery(rig.body) { false }.get())
    }

    @Test fun `delivery final return rejects withdrawal during current privacy storage read`() = Rig().use { rig ->
        rig.enable()
        rig.onRead = { rig.driver.onBackground() }
        assertFalse(rig.owner.authorizeCurrentDelivery(rig.body) { false }.get())
    }

    @Test fun `expired cache projection returns defaults and cannot enqueue stale exposure`() = Rig(withFlags = true).use { rig ->
        rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
        rig.facade.getFeatureFlagPayload("variant"); rig.settle()
        assertEquals("a", rig.facade.getFeatureFlag("variant"))
        rig.settle(); rig.settle() // drain the exposure operation enqueued by the getter's lane task
        val before = rig.owner.snapshot().get().queuedCount
        rig.clock.nanos += 60_000_000_000L // wall stalls; config has another three minutes
        assertTrue(checkNotNull(rig.gate.snapshot()).isCurrent())
        assertNull(rig.facade.getFeatureFlag("variant"))
        assertFalse(rig.facade.isFeatureEnabled("variant"))
        rig.settle()
        assertEquals(before, rig.owner.snapshot().get().queuedCount)
    }

    @Test fun `queued flags-loaded callback expires with cache while source stays current`() =
        Rig(withFlags = true, queueCallbacks = true).use { rig ->
            var callbacks = 0
            rig.facade.onFeatureFlagsLoaded { callbacks++ }
            rig.driver.start(); rig.worker.runNext(); rig.facade.configurationChanged(); rig.settleFlags()
            assertFalse(rig.callbacks.isEmpty())
            rig.clock.wall += 60_000L
            assertTrue(checkNotNull(rig.gate.snapshot()).isCurrent())
            rig.callbacks.forEach { it.run() }
            assertEquals(0, callbacks)
        }

    private class Rig(bindGate: Boolean = true, withFlags: Boolean = false, queueCallbacks: Boolean = false, acknowledgeEvents: Boolean = false) : AutoCloseable {
        val clock = Clock()
        val worker = Worker()
        val gate = V2ConfigAuthorityGate()
        var body = resource("contracts/v2/fixtures/config-enabled.json")
        val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, Scheduler(), worker)
        val backing = FakeRuntimeQueueBacking()
        val databaseClosed = java.util.concurrent.CountDownLatch(1)
        @Volatile var onRead: (() -> Unit)? = null
        @Volatile var onWrite: (() -> Unit)? = null
        @Volatile var ownerWall: Long? = null
        @Volatile var lastFlagRequest: JSONObject? = null
        @Volatile var networkCalls = 0
        val firstNetworkCall = java.util.concurrent.CountDownLatch(1)
        val sentEvents = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        @Volatile var flagVariant = "a"
        val callbacks = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
        val owner = RuntimeQueueOwner.open(
            "config-composition-" + UUID.randomUUID(), RuntimeQueueLimits(100, 1_000_000),
            databaseFactory = {
                val original = backing.connection()
                object : RuntimeQueueDatabase by original {
                    override fun close() { original.close(); databaseClosed.countDown() }
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T = original.transaction { transaction ->
                        block(object : RuntimeQueueTransaction by transaction {
                            override fun updateCore(core: RuntimeStoredCore) {
                                transaction.updateCore(core)
                                onWrite?.also { onWrite = null }?.invoke()
                            }
                            override fun readCore(): RuntimeStoredCore? {
                                val result = transaction.readCore()
                                onRead?.also { onRead = null }?.invoke()
                                return result
                            }
                        })
                    }
                }
            },
            legacyStateLoader = { initialState() }, trustedSiteKey = KEY,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = ownerWall ?: clock.wall
                override fun elapsedRealtimeNanos() = clock.nanos
            },
        ).get(2, TimeUnit.SECONDS).also { if (bindGate) it.bindConfigurationGate(gate).get(2, TimeUnit.SECONDS) }
        val runtime = StandaloneRuntime(owner, KEY, wallClock = { clock.wall },
            deliveryClock = object : BatchDeliveryClock {
                override fun wallNow() = BatchWallInstant(clock.wall, Instant.ofEpochMilli(clock.wall).toString())
                override fun monotonicNowNanos() = clock.nanos
            },
            transportFactory = { BatchHTTPTransport { request ->
                networkCalls++
                firstNetworkCall.countDown()
                if (!acknowledgeEvents) BatchHTTPResponse(503, ByteArray(0)) else {
                    val body = JSONObject(request.bodyBytes().decodeToString())
                    val records = body.getJSONArray("records")
                    val outcomes = JSONArray()
                    var last: Long? = null
                    for (index in 0 until records.length()) {
                        val record = records.getJSONObject(index)
                        assertEquals("event", record.getString("kind"))
                        val event = record.getJSONObject("event")
                        sentEvents.add(event)
                        last = event.getLong("sequence")
                        outcomes.put(JSONObject().put("sequence", last).put("recordId", event.getString("eventId"))
                            .put("kind", "event").put("result", "accepted"))
                    }
                    val ack = JSONObject().put("schemaVersion", 1).put("requestId", body.getString("requestId"))
                        .put("streamId", body.getString("streamId")).put("resolvedThroughSequence", last ?: JSONObject.NULL)
                        .put("retryFromSequence", JSONObject.NULL).put("outcomes", outcomes)
                    BatchHTTPResponse(200, ack.toString().encodeToByteArray())
                }
            } },
            deviceInEuTimezone = { false }, configurationGate = if (bindGate) gate else null)
        val flags = if (withFlags) AndroidFeatureFlagClient(owner, StandaloneRuntime.defaultVersions(),
            FlagTransport { request ->
                val json = JSONObject(String(request.canonicalBody))
                lastFlagRequest = json
                val response = JSONObject().put("schemaVersion", 1).put("requestId", json.getString("requestId"))
                    .put("contextRevision", json.getLong("contextRevision"))
                    .put("identityRevision", json.getJSONObject("identity").getLong("revision"))
                    .put("flagsRevision", "flags-1").put("evaluatedAt", "2026-08-05T00:01:00.000Z")
                    .put("expiresAt", "2026-08-05T00:02:00.000Z")
                    .put("flags", JSONObject().put("variant", flagVariant)).put("payloads", JSONObject())
                dev.elu.analytics.internal.concurrent.SdkFuture.completedFuture(response.toString().toByteArray())
            }, object : FlagClock {
                override fun wallNowEpochMillis() = clock.wall
                override fun monotonicNowNanos() = clock.nanos
            }, FlagOpaqueIdSource { UUID.randomUUID().toString() }, FlagOpaqueIdSource { UUID.randomUUID().toString() },
            configurationGate = if (bindGate) gate else null) else null
        val facade = StandaloneFacade(
            open = { StandaloneStack(runtime, owner, flags) }, deliverCallback = { if (queueCallbacks) callbacks.add(it) else it.run() }, wallClock = { clock.wall },
            configurationGate = if (bindGate) gate else null, onCloseRequested = { driver.close(); gate.close() },
        ).also { it.start() }
        fun enable() { driver.start(); worker.runNext(); facade.configurationChanged(); settle(); assertTrue(facade.state() is EluFacadeState.Enabled) }
        fun settle() { facade.settled().get(2, TimeUnit.SECONDS) }
        fun settleFlags() {
            repeat(300) {
                if (!facade.diagnostics().get(2, TimeUnit.SECONDS).flagReloadInFlight &&
                    !facade.diagnostics().get(2, TimeUnit.SECONDS).flagReloadInFlight) return
                Thread.sleep(1)
            }
            fail("Flag callback did not settle")
        }
        override fun close() { facade.close(); runCatching { owner.closeAsync().get(2, TimeUnit.SECONDS) }; driver.close() }
    }

    private class Clock : V2ConfigClock {
        @Volatile var wall = Instant.parse("2026-08-05T00:01:00Z").toEpochMilli()
        @Volatile var nanos = 100L
        override fun wallNowEpochMillis() = wall
        override fun monotonicNowNanos() = nanos
    }
    private class Worker : V2ConfigLifecycleWorker {
        val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.add(task) }
        override fun interruptCurrent() = Unit
        override fun close() = Unit
        fun runNext() { tasks.removeFirst().invoke() }
    }
    private class Scheduler : V2ConfigLifecycleScheduler {
        override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
        override fun close() = Unit
    }
    private companion object {
        val KEY = "elu_pk_live_" + "A".repeat(26)
        fun resource(path: String) = checkNotNull(V2ConfigCompositionTest::class.java.classLoader?.getResourceAsStream(path)).bufferedReader().use { it.readText() }
        fun initialState() = PersistedCoreState(
            identity = IdentityState(revision = 1, contextRevision = 1, anonymousId = "anon_composition", userId = null,
                groups = emptyMap(), superProperties = emptyMap(), session = null, optedOut = false,
                updatedAt = "2026-08-05T00:00:00.000Z"),
            stream = StreamState(streamId = "stream_composition", nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )
    }
}
