package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.concurrent.SdkFuture

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeReplayCaptureQueueTest {
    private class Session(val rig: Rig) : AutoCloseable {
        val facts = NativeReplayLifecycle(TestSelectionAccess())
        val activity = Any(); val root = Any()
        val selection = run { facts.resumed(activity); checkNotNull(facts.select(activity, root).get()) }
        val authority = NativeReplayAuthority(rig.owner, NativeReplayCapabilities(setOf(
            V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)), setOf(ReplayFixtures.GENERATION)), { false })
        val prepared = checkNotNull(authority.prepare(selection).get())
        val enrollment = checkNotNull(rig.owner.enrollNativeReplayCapture().get())
        val use = checkNotNull(enrollment.takePhysicalUse())
        var permit: NativeReplayPermit? = null
        fun begin(): NativeReplayPermit = checkNotNull(authority.start(prepared, use).get()).also { permit = it }
        fun admission() = checkNotNull(authority.captureAdmission(checkNotNull(permit), use).get())
        fun request(admission: NativeReplayCaptureAdmission) = NativeReplaySealer(checkNotNull(permit).replayId,
            admission.identity, admission.authorization, admission.privacy, admission.profile, StandaloneRuntime.defaultVersions())
            .seal(listOf(NativeMaskedSnapshot(0, rig.clock.wall, NativeViewport(320, 480), emptyList())))
        override fun close() {
            use.settle()
            runCatching { authority.stop().get(1, TimeUnit.SECONDS) }
            runCatching { rig.owner.finishNativeReplayCapture(enrollment).get(1, TimeUnit.SECONDS) }
            authority.close(); selection.close()
        }
    }
    @Test fun `sealed native policy survives fresh retirement while original source and current privacy still govern IO`() {
        for (mode in listOf("retired", "unsampled", "exhausted", "background", "interrupted", "reset", "withdrawn", "expired", "empty-proof")) Rig().use { rig ->
            rig.activate()
            val request = Session(rig).use { session ->
                session.begin(); val admission = session.admission(); val bytes = session.request(admission)
                assertTrue(rig.owner.appendNativeReplay(bytes, admission, session.use).get() is NativeReplayAppendOutcome.Committed)
                bytes
            }
            when (mode) {
                "unsampled" -> rig.renew { it.getJSONObject("privacy").getJSONObject("replay").put("sampleRate", 0) }
                "exhausted" -> rig.renew { it.getJSONObject("privacy").getJSONObject("replay").put("maximumDurationSeconds", 0) }
                "background" -> rig.owner.markBackgrounded(rig.now()).get()
                "interrupted" -> { rig.reopen(); rig.publish(); assertTrue(rig.observe().session.interrupted) }
                "reset" -> { rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity(rig.now())).get(); rig.publish(); assertNull(rig.owner.snapshot().get().state.identity.session) }
                "withdrawn" -> rig.driver.onBackground()
                "expired" -> { rig.clock.wall += 600_000; rig.clock.nanos += 600_000_000_000L }
            }
            val proof = if (mode == "empty-proof") NativeReplayCapabilities() else NativeReplayCapabilities(
                setOf(V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)), setOf(ReplayFixtures.GENERATION))
            val delivery = rig.owner.openReplayDeliveryQueue(PrivacyStateProjector.nativeSealedDeliveryPolicy(proof) { false }).get()
            val claim = delivery.claim()
            if (mode in listOf("withdrawn", "expired", "empty-proof")) {
                assertNull(mode, claim)
            } else {
                assertNotNull(mode, claim)
                var io: (() -> Boolean)? = null
                val physical = object : ReplayTransportOperation {
                    override val settlement = dev.elu.analytics.internal.concurrent.SdkFuture<ReplayTransportResponse>()
                    override fun cancel() = Unit
                }
                val operation = checkNotNull(delivery.dispatch(checkNotNull(claim), ReplayDeliveryTransport { exact, guard ->
                    assertArrayEquals(mode, request.copyBytes(), exact.row.prepared.copyBytes()); io = guard; physical
                }))
                try { assertTrue(mode, checkNotNull(io).invoke()) }
                finally { physical.settlement.complete(ReplayTransportResponse(403, byteArrayOf())) }
                operation.settlement.get(3, TimeUnit.SECONDS)
                delivery.commit(claim, ReplayDeliveryOutcome.Blocked(ReplayBlockKind.FORBIDDEN))
            }
            assertArrayEquals(mode, request.copyBytes(), rig.owner.storedPreparedReplayForTesting().get().single().prepared.copyBytes())
        }
    }

    @Test fun `native close receipt waits original physical and durable stop and cannot be canceled`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val permit = session.begin()
            val closing = session.authority.closeAndWait()
            assertSame(closing, session.authority.closeAndWait()); assertFalse(closing.cancel(true))
            assertFalse(permit.isCurrent())
            assertEquals(NativeReplayAuthorityStop.PHYSICAL_WORK_PENDING, session.authority.stop().get())
            assertFalse(closing.isDone); assertNotNull(rig.state().session!!.activeEpoch)
            session.use.settle()
            assertFalse("Physical completion alone is not durable accounting", closing.isDone)
            assertEquals(NativeReplayAuthorityStop.SETTLED, session.authority.stop().get())
            assertEquals(NativeReplayAuthorityStop.SETTLED, closing.get(3, TimeUnit.SECONDS))
            assertNull(rig.state().session!!.activeEpoch)
            assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(session.enrollment).get())
        }
    }

    @Test fun `quarantined original physical work keeps authority close pending until that work finishes`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); session.enrollment.quarantine()
            val closing = session.authority.closeAndWait()
            assertEquals(NativeReplayAuthorityStop.PHYSICAL_WORK_PENDING, session.authority.stop().get())
            assertFalse(closing.isDone)
            session.use.settle()
            assertEquals(NativeReplayAuthorityStop.UNRESOLVED, session.authority.stop().get())
            assertEquals(NativeReplayAuthorityStop.UNRESOLVED, closing.get(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `native close preserves unresolved accounting disposition and quarantine`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); session.use.settle()
            rig.onNativeRead = { throw java.io.IOException("held original accounting failure") }
            assertEquals(NativeReplayAuthorityStop.UNRESOLVED, session.authority.closeAndWait().get(3, TimeUnit.SECONDS))
            assertTrue(session.enrollment.isQuarantined())
            assertEquals(NativeReplayCaptureFinish.ACCOUNTING_PENDING, rig.owner.finishNativeReplayCapture(session.enrollment).get())
            failure { rig.openSame().get() }
        }
    }

    @Test fun `queue enrollment is exclusive one shot and no-start release is authoritative`() = Rig().use { rig ->
        rig.activate()
        val e = checkNotNull(rig.owner.enrollNativeReplayCapture().get())
        assertNull(rig.owner.enrollNativeReplayCapture().get())
        assertEquals(NativeReplayCaptureFinish.PHYSICAL_WORK_PENDING, rig.owner.finishNativeReplayCapture(e).get())
        e.cancelUnused(); assertNull(e.takePhysicalUse())
        assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(e).get())
        assertEquals(NativeReplayCaptureFinish.STALE, rig.owner.finishNativeReplayCapture(e).get())
        assertEquals(0L, rig.state().nextReplayOrdinal)
        val next = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); next.cancelUnused()
        assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(next).get())
    }
    @Test fun `duplicate physical object cannot settle or reuse original enrollment`() = Rig().use { rig ->
        rig.activate(); val e = checkNotNull(rig.owner.enrollNativeReplayCapture().get())
        val use = checkNotNull(e.takePhysicalUse()); assertNull(e.takePhysicalUse())
        val forged = NativeReplayCapturePhysicalUse.issue(e)
        assertFalse(forged.isCurrent()); forged.settle()
        assertTrue(use.isCurrent()); assertEquals(NativeReplayCaptureFinish.PHYSICAL_WORK_PENDING, rig.owner.finishNativeReplayCapture(e).get())
        e.cancelUnused(); assertTrue(use.isCurrent())
        use.settle(); assertFalse(use.isCurrent()); assertNull(e.takePhysicalUse())
        assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(e).get())
    }
    @Test fun `legacy start cannot enter a reserved physical enrollment`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            assertNull(rig.owner.beginNativeReplayAuthority(session.prepared.projection).get())
            assertEquals(0L, rig.state().nextReplayOrdinal)
            session.begin(); assertEquals(1L, rig.state().nextReplayOrdinal)
        }
    }
    @Test fun `physical stop defers original receipt until finish then exact stop`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val permit = session.begin(); val epoch = rig.state().session!!.activeEpoch
            assertEquals(NativeReplayAuthorityStop.PHYSICAL_WORK_PENDING, session.authority.stop().get())
            assertFalse(permit.isCurrent()); assertEquals(epoch, rig.state().session!!.activeEpoch)
            assertFalse(rig.owner.stopNativeReplayAccounting(permit.started.receipt).get())
            session.use.settle()
            assertEquals(NativeReplayCaptureFinish.ACCOUNTING_PENDING, rig.owner.finishNativeReplayCapture(session.enrollment).get())
            assertEquals(NativeReplayAuthorityStop.SETTLED, session.authority.stop().get())
            assertNull(rig.state().session!!.activeEpoch)
            assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(session.enrollment).get())
        }
    }
    @Test fun `logical close holds installation through physical and durable settlement`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val closing = rig.owner.closeAsync()
            assertFalse(session.use.isCurrent()); assertFalse(closing.isDone); failure { rig.openSame().get() }
            session.use.settle(); assertFalse(closing.isDone)
            assertEquals(NativeReplayAuthorityStop.SETTLED, session.authority.stop().get()); assertFalse(closing.isDone)
            assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(session.enrollment).get())
            closing.get(2, TimeUnit.SECONDS)
            val reopened = rig.openSame().get(); reopened.closeAsync().get(); Unit
        }
    }
    @Test fun `actual native sealer bytes require opaque admission and preserve exact stored body`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val admission = session.admission(); val request = session.request(admission)
            assertEquals(ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY), rig.owner.appendPreparedReplay(request,
                ReplayMaskingProfile.parse(admission.profile.canonicalBytes), admission.privacy.toString(Charsets.UTF_8)).get())
            assertTrue(rig.owner.storedPreparedReplayForTesting().get().isEmpty())
            val committed = rig.owner.appendNativeReplay(request, admission, session.use).get()
            assertTrue(committed is NativeReplayAppendOutcome.Committed)
            val row = rig.owner.storedPreparedReplayForTesting().get().single()
            assertArrayEquals(request.copyBytes(), row.prepared.copyBytes())
            assertEquals(admission.identity.session!!.id, row.prepared.sessionId)
            assertEquals(request.captureProtocolGeneration, row.captureProtocolGeneration)
        }
    }
    @Test fun `known commit then withdrawal keeps sealed bytes and distinct completion`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val admission = session.admission(); val request = session.request(admission)
            rig.afterReplayCommit = { session.authority.withdraw() }
            assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.CommittedThenWithdrawn)
            assertArrayEquals(request.copyBytes(), rig.owner.storedPreparedReplayForTesting().get().single().prepared.copyBytes())
            assertFalse(admission.isCurrent())
            assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.Rejected)
        }
    }
    @Test fun `withdrawal during row write rolls back exact bytes and keeps original accounting`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val admission = session.admission(); val request = session.request(admission)
            rig.onReplayWrite = { session.authority.withdraw() }
            assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.Rejected)
            assertTrue(rig.owner.storedPreparedReplayForTesting().get().isEmpty())
            assertNotNull(rig.state().session!!.activeEpoch)
        }
    }
    @Test fun `actual source context and physical settlement deny retained append`() {
        for (mode in 0..2) Rig().use { rig ->
            rig.activate(); Session(rig).use { session ->
                session.begin(); val admission = session.admission(); val request = session.request(admission)
                when (mode) { 0 -> rig.driver.onBackground(); 1 -> rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("plan" to "changed"), rig.now())).get(); 2 -> session.use.settle() }
                assertFalse(admission.isCurrent())
                if (mode == 2) failure { rig.owner.appendNativeReplay(request, admission, session.use).get() }
                else assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.Rejected)
                assertTrue(rig.owner.storedPreparedReplayForTesting().get().isEmpty())
            }
        }
    }
    @Test fun `native start unknown commit retains one candidate and noncancelable exact outcome`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) Rig().use { rig ->
            rig.activate(); Session(rig).use { session ->
                rig.onWrite = { rig.backing.ambiguousNextCommit = outcome }
                val start = session.authority.start(session.prepared, session.use); assertFalse(start.cancel(true))
                val permit = checkNotNull(start.get()); session.permit = permit
                assertEquals(1L, rig.state().nextReplayOrdinal); assertEquals(permit.started.receipt.epoch, rig.state().session!!.activeEpoch)
                assertTrue(permit.isCurrent())
            }
        }
    }
    @Test fun `unrelated event poison cannot release active physical accounting owner`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val record = (rig.owner.capture(rig.event()).get() as RuntimeCaptureResult.Accepted).record
            rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE
            failure { rig.owner.acknowledge(RuntimeAcknowledgement(record.streamId, listOf(RuntimeRecordReference(record.sequence, record.kind, record.recordId)))).get() }
            session.use.settle(); assertEquals(NativeReplayAuthorityStop.UNRESOLVED, session.authority.stop().get())
            assertEquals(NativeReplayCaptureFinish.ACCOUNTING_PENDING, rig.owner.finishNativeReplayCapture(session.enrollment).get())
            failure { rig.owner.closeAsync().get(3, TimeUnit.SECONDS) }; failure { rig.openSame().get() }
            assertTrue(rig.executor().isShutdown)
            assertNotNull(rig.state().session!!.activeEpoch)
        }
    }
    @Test fun `unknown append quarantines original prepared request without releasing occupancy`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val admission = session.admission(); val request = session.request(admission)
            rig.onReplayWrite = { rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE }
            failure { rig.owner.appendNativeReplay(request, admission, session.use).get() }
            assertFalse(session.use.isCurrent()); session.use.settle()
            assertEquals(NativeReplayCaptureFinish.ACCOUNTING_PENDING, rig.owner.finishNativeReplayCapture(session.enrollment).get())
            failure { rig.openSame().get() }
        }
    }
    @Test fun `canonical native projection is installed admitted and sealed without changing full hash`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val projection = session.prepared.projection
            val originalSource = checkNotNull(projection.input.observation.source.body)
            val canonical = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(projection.privacy.body))
            assertArrayEquals(canonical, projection.privacy.body.toByteArray(Charsets.UTF_8))
            val full = V1ConfigJson.parseEffectivePrivacy(projection.privacy.body)
            val recoded = PrivacyStateProjector.encode(full)
            assertEquals(full, V1ConfigJson.parseEffectivePrivacy(recoded))
            assertEquals(full.effectivePolicyHash, projection.input.observation.capture.decisionHash)
            session.begin(); val admission = session.admission(); val request = session.request(admission)
            assertArrayEquals(canonical, admission.privacy)
            assertEquals(full, admission.authorization.effectivePrivacy)
            assertEquals(full.effectivePolicyHash, request.effectivePolicyHash)
            assertEquals(originalSource, admission.source.body)
            assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.Committed)
        }
    }
    @Test fun `native guard is suspended through exact recovery and retains original clock floor`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            rig.ownerNanos = rig.clock.nanos + 10; assertTrue(session.prepared.isCurrent())
            var connectionChecked = false; var fingerprintChecked = false
            rig.onConnection = {
                connectionChecked = true; assertFalse(session.prepared.isCurrent())
                rig.onNativeRead = { fingerprintChecked = true; assertFalse(session.prepared.isCurrent()) }
            }
            rig.onWrite = { rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT }
            val permit = session.begin()
            assertTrue(connectionChecked); assertTrue(fingerprintChecked); assertTrue(permit.isCurrent())
            rig.ownerNanos = rig.clock.nanos + 9; assertFalse(permit.isCurrent())
            session.use.settle(); assertEquals(NativeReplayAuthorityStop.SETTLED, session.authority.stop().get())
            assertTrue(rig.state().session!!.clockDenied)
            rig.ownerNanos = rig.clock.nanos + 11; assertFalse(session.prepared.isCurrent()); assertFalse(permit.isCurrent())
        }
    }
    @Test fun `recovery never revives source close or queued context withdrawal and never redraws`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) for (mode in 0..2) Rig().use { rig ->
            rig.activate(); Session(rig).use { session ->
                var pending: java.util.concurrent.Future<*>? = null
                rig.onConnection = {
                    assertFalse(session.prepared.isCurrent())
                    when (mode) {
                        0 -> rig.driver.onBackground()
                        1 -> pending = rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("changed" to true), rig.now()))
                        2 -> pending = rig.owner.closeAsync()
                    }
                }
                rig.onWrite = { rig.backing.ambiguousNextCommit = outcome }
                val result = runCatching { session.authority.start(session.prepared, session.use).get() }
                assertNull(result.getOrNull()); assertFalse(session.prepared.isCurrent())
                if (mode == 1) pending!!.get(2, TimeUnit.SECONDS)
                assertEquals("Outcome=$outcome, withdrawal=$mode", if (outcome == FakeAmbiguousOutcome.COMMIT) 1L else 0L, rig.state().nextReplayOrdinal)
                session.use.settle(); assertEquals(NativeReplayAuthorityStop.SETTLED, session.authority.stop().get())
                assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(session.enrollment).get())
                if (mode == 2) pending!!.get(2, TimeUnit.SECONDS)
                assertNull(rig.state().session!!.activeEpoch)
            }
        }
    }
    @Test fun `capture and HTTP physical occupancy settle independently before close releases owner`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val admission = session.admission(); val request = session.request(admission)
            assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.Committed)
            val policy = ReplayDeliveryPolicy(ReplayDeliveryPrivacy { _, _, _ -> admission.privacy.toString(Charsets.UTF_8) },
                ReplayMaskingRetention { profile, required -> NativeMaskingProfile.retention(profile.copyBytes(), required.privacy?.masking,
                    V1PrivacyPlatform.ANDROID) == NativeMaskingRetention.COMPATIBLE })
            val delivery = rig.owner.openReplayDeliveryQueue(policy).get(); val claim = checkNotNull(delivery.claim())
            var io: (() -> Boolean)? = null; var cancelled = false
            val transport = object : ReplayTransportOperation {
                override val settlement = dev.elu.analytics.internal.concurrent.SdkFuture<ReplayTransportResponse>()
                override fun cancel() { cancelled = true }
            }
            val operation = checkNotNull(delivery.dispatch(claim, ReplayDeliveryTransport { _, authorize -> io = authorize; transport }))
            assertTrue(checkNotNull(io).invoke()); assertTrue(session.use.isCurrent())
            val closing = rig.owner.closeAsync(); assertTrue(cancelled); assertFalse(closing.isDone)
            session.use.settle(); assertEquals(NativeReplayAuthorityStop.SETTLED, session.authority.stop().get())
            assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(session.enrollment).get())
            assertFalse(closing.isDone); failure { rig.openSame().get() }
            transport.settlement.complete(ReplayTransportResponse(403, ByteArray(0)))
            operation.settlement.get(2, TimeUnit.SECONDS); closing.get(2, TimeUnit.SECONDS)
            val reopened = rig.openSame().get(); assertArrayEquals(request.copyBytes(), reopened.storedPreparedReplayForTesting().get().single().prepared.copyBytes())
            reopened.closeAsync().get(); Unit
        }
    }
    @Test fun `queued duplicate finish cannot quarantine settled resources or a replacement enrollment`() = Rig().use { rig ->
        rig.activate(); val original = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); original.cancelUnused()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        rig.onNativeRead = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val first = rig.owner.finishNativeReplayCapture(original)
        check(entered.await(3, TimeUnit.SECONDS))
        val duplicate = rig.owner.finishNativeReplayCapture(original)
        release.countDown()
        assertEquals(NativeReplayCaptureFinish.SETTLED, first.get(3, TimeUnit.SECONDS))
        assertEquals(NativeReplayCaptureFinish.STALE, duplicate.get(3, TimeUnit.SECONDS))
        assertFalse(original.isQuarantined())
        val replacement = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); replacement.cancelUnused()
        assertEquals(NativeReplayCaptureFinish.STALE, rig.owner.finishNativeReplayCapture(original).get())
        assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(replacement).get())
    }
    @Test fun `uncertain stop retains resource-only quarantine and cannot release installation`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val admission = session.admission(); val prepared = session.request(admission)
            session.use.settle(); rig.onWrite = { rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE }
            assertEquals(NativeReplayAuthorityStop.UNRESOLVED, session.authority.stop().get())
            session.enrollment.quarantine(retaining = prepared)
            assertEquals(NativeReplayCaptureFinish.ACCOUNTING_PENDING, rig.owner.finishNativeReplayCapture(session.enrollment).get())
            val resourcesField = NativeReplayCaptureEnrollment::class.java.getDeclaredField("resources").also { it.isAccessible = true }
            val resources = resourcesField.get(session.enrollment)
            val preparedField = NativeReplayCaptureResources::class.java.getDeclaredField("prepared").also { it.isAccessible = true }
            assertSame(prepared, preparedField.get(resources))
            assertTrue(NativeReplayCaptureResources::class.java.declaredFields.none {
                RuntimeQueueOwner::class.java.isAssignableFrom(it.type) || java.util.concurrent.Executor::class.java.isAssignableFrom(it.type) ||
                    it.type.name.contains("Activity") || it.type.name.startsWith("kotlin.jvm.functions.")
            })
            failure { rig.owner.closeAsync().get(3, TimeUnit.SECONDS) }; failure { rig.openSame().get() }
            assertTrue(rig.executor().isShutdown)
        }
    }
    @Test fun `HTTP refusal persistence poison synchronously revokes retained capture permit`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            val permit = session.begin(); val admission = session.admission(); val request = session.request(admission)
            assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.Committed)
            val delivery = rig.owner.openReplayDeliveryQueue(ReplayDeliveryPolicy(
                ReplayDeliveryPrivacy { _, _, _ -> admission.privacy.toString(Charsets.UTF_8) },
                ReplayMaskingRetention { profile, required -> NativeMaskingProfile.retention(profile.copyBytes(), required.privacy?.masking,
                    V1PrivacyPlatform.ANDROID) == NativeMaskingRetention.COMPATIBLE })).get()
            val claim = checkNotNull(delivery.claim())
            val transport = object : ReplayTransportOperation {
                override val settlement = dev.elu.analytics.internal.concurrent.SdkFuture<ReplayTransportResponse>()
                override fun cancel() = Unit
            }
            val operation = checkNotNull(delivery.dispatch(claim, ReplayDeliveryTransport { _, _ -> transport }))
            rig.backing.failNextKnownCommit = java.io.IOException("Original refusal persistence failed")
            transport.settlement.complete(ReplayTransportResponse(403, ByteArray(0)))
            failure { rig.owner.snapshot().get() } // FIFO barrier after the actual receipt-failure command.
            assertFalse(permit.isCurrent()); assertFalse(admission.isCurrent()); assertFalse(session.use.isCurrent())
            assertFalse(operation.settlement.isDone)
            assertEquals(NativeReplayCaptureFinish.ACCOUNTING_PENDING, rig.owner.finishNativeReplayCapture(session.enrollment).get())
            assertFalse(rig.owner.closeAsync().isDone); failure { rig.openSame().get() }
        }
    }
    @Test fun `quarantine before final release retains exact enrollment slot`() = Rig().use { rig ->
        rig.activate(); val enrollment = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); enrollment.cancelUnused()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        rig.onNativeRead = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val finish = rig.owner.finishNativeReplayCapture(enrollment)
        check(entered.await(3, TimeUnit.SECONDS))
        enrollment.quarantine(); release.countDown()
        runCatching { finish.get(3, TimeUnit.SECONDS) }
        assertTrue(enrollment.isQuarantined())
        assertNull("Quarantine must keep the original owner slot", rig.owner.enrollNativeReplayCapture().get())
        assertEquals(NativeReplayCaptureFinish.ACCOUNTING_PENDING, rig.owner.finishNativeReplayCapture(enrollment).get())
    }
    @Test fun `late quarantine after committed release is harmless during settlement callback close`() = Rig().use { rig ->
        rig.activate(); val enrollment = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); enrollment.cancelUnused()
        val closing = dev.elu.analytics.internal.concurrent.SdkFuture<java.util.concurrent.Future<Unit>>()
        enrollment.settlement.whenComplete { _, error ->
            if (error != null) closing.completeExceptionally(error)
            else { enrollment.quarantine(); closing.complete(rig.owner.closeAsync()) }
        }
        assertEquals(NativeReplayCaptureFinish.SETTLED, rig.owner.finishNativeReplayCapture(enrollment).get(3, TimeUnit.SECONDS))
        closing.get(3, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS)
        assertFalse("Released resources cannot become quarantined", enrollment.isQuarantined())
        val reopened = rig.openSame().get(); reopened.closeAsync().get(); Unit
    }
    @Test fun `generic event reconciliation never revives retained guard before exact records proof`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            assertTrue(session.prepared.isCurrent())
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            rig.onConnection = {
                assertFalse(session.prepared.isCurrent())
                val original = checkNotNull(rig.backing.records.lastEntry()).value
                val record = RuntimeRecordCodec.decodeEvent(original.internalPayload)
                val changed = RuntimeRecordCodec.encodeEvent(record.copy(name = "modified"))
                assertEquals(original.internalPayload.size, changed.size)
                rig.backing.records[original.sequence] = original.copy(internalPayload = changed)
                rig.onRecordRead = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            }
            rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.COMMIT
            val capture = rig.owner.capture(rig.event())
            check(entered.await(3, TimeUnit.SECONDS))
            try { assertFalse("Exact recordsMatch is still unresolved", session.prepared.isCurrent()) }
            finally { release.countDown() }
            failure { capture.get(3, TimeUnit.SECONDS) }
            assertFalse(session.prepared.isCurrent())
            val stored = rig.owner.peek(1, MAX_RUNTIME_QUEUE_BYTES).get().single() as RuntimeQueuedRecord.Event
            assertEquals("modified", stored.record.name)
        }
    }
    @Test fun `terminal native quarantine closes worker exceptionally only after physical finish`() = Rig().use { rig ->
        rig.activate(); val enrollment = checkNotNull(rig.owner.enrollNativeReplayCapture().get())
        val use = checkNotNull(enrollment.takePhysicalUse())
        enrollment.quarantine()
        val closing = rig.owner.closeAsync()
        assertFalse(closing.isDone); assertFalse(enrollment.settlement.isDone)
        assertFalse(rig.executor().isShutdown)
        val closes = rig.databaseCloses
        use.settle()
        failure { closing.get(3, TimeUnit.SECONDS) }
        assertTrue(enrollment.settlement.isCompletedExceptionally)
        assertTrue(rig.executor().isShutdown)
        assertTrue(rig.executor().awaitTermination(3, TimeUnit.SECONDS))
        assertEquals(closes, rig.databaseCloses)
        failure { rig.openSame().get() }
    }
    @Test fun `quarantined native close waits original HTTP refusal before exceptional worker shutdown`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val admission = session.admission(); val request = session.request(admission)
            assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.Committed)
            val delivery = rig.owner.openReplayDeliveryQueue(ReplayDeliveryPolicy(
                ReplayDeliveryPrivacy { _, _, _ -> admission.privacy.toString(Charsets.UTF_8) },
                ReplayMaskingRetention { profile, required -> NativeMaskingProfile.retention(profile.copyBytes(), required.privacy?.masking,
                    V1PrivacyPlatform.ANDROID) == NativeMaskingRetention.COMPATIBLE })).get()
            val claim = checkNotNull(delivery.claim())
            val transport = object : ReplayTransportOperation {
                override val settlement = dev.elu.analytics.internal.concurrent.SdkFuture<ReplayTransportResponse>()
                override fun cancel() = Unit
            }
            val operation = checkNotNull(delivery.dispatch(claim, ReplayDeliveryTransport { _, _ -> transport }))
            session.enrollment.quarantine(retaining = request)
            val closing = rig.owner.closeAsync(); session.use.settle()
            assertTrue(session.enrollment.settlement.isCompletedExceptionally)
            assertFalse(closing.isDone); assertFalse(rig.executor().isShutdown)
            val closes = rig.databaseCloses
            transport.settlement.complete(ReplayTransportResponse(403, ByteArray(0)))
            assertEquals(403, operation.settlement.get(3, TimeUnit.SECONDS).status)
            failure { closing.get(3, TimeUnit.SECONDS) }
            assertTrue(rig.executor().awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(closes, rig.databaseCloses)
            val blocked = rig.backing.replayRows.values.filter { it.key.startsWith("delivery/") }
            assertEquals(1, blocked.size)
            val metadata = ReplayDeliveryMetadata.decode(blocked.single())
            assertEquals(ReplayDeliveryState.BLOCKED, metadata.state)
            assertEquals(ReplayBlockKind.FORBIDDEN.name, metadata.blockKind)
            failure { rig.openSame().get() }
        }
    }
    @Test fun `HTTP poison notifies native terminal quarantine outside lifecycle lock and retains HTTP barrier`() = Rig().use { rig ->
        rig.activate(); Session(rig).use { session ->
            session.begin(); val admission = session.admission(); val request = session.request(admission)
            assertTrue(rig.owner.appendNativeReplay(request, admission, session.use).get() is NativeReplayAppendOutcome.Committed)
            val delivery = rig.owner.openReplayDeliveryQueue(ReplayDeliveryPolicy(
                ReplayDeliveryPrivacy { _, _, _ -> admission.privacy.toString(Charsets.UTF_8) },
                ReplayMaskingRetention { profile, required -> NativeMaskingProfile.retention(profile.copyBytes(), required.privacy?.masking,
                    V1PrivacyPlatform.ANDROID) == NativeMaskingRetention.COMPATIBLE })).get()
            val claim = checkNotNull(delivery.claim())
            val transport = object : ReplayTransportOperation {
                override val settlement = dev.elu.analytics.internal.concurrent.SdkFuture<ReplayTransportResponse>()
                override fun cancel() = Unit
            }
            val operation = checkNotNull(delivery.dispatch(claim, ReplayDeliveryTransport { _, _ -> transport }))
            val callback = dev.elu.analytics.internal.concurrent.SdkFuture<Boolean>()
            val lock = checkNotNull(RuntimeQueueOwner::class.java.getDeclaredField("lifecycleLock").also { it.isAccessible = true }.get(rig.owner))
            session.enrollment.settlement.whenComplete { _, error -> callback.complete(error != null && !Thread.holdsLock(lock)) }
            session.use.settle(); rig.backing.failNextKnownCommit = java.io.IOException("Refusal persistence is unresolved")
            transport.settlement.complete(ReplayTransportResponse(403, ByteArray(0)))
            assertTrue(callback.get(3, TimeUnit.SECONDS))
            assertFalse(operation.settlement.isDone)
            val closing = rig.owner.closeAsync()
            assertFalse(closing.isDone); assertFalse(rig.executor().isShutdown)
            failure { rig.openSame().get() }
        }
    }
    @Test fun `resource detach and late quarantine arbitrate before callback or close`() = Rig().use { rig ->
        rig.activate(); val enrollment = checkNotNull(rig.owner.enrollNativeReplayCapture().get()); enrollment.cancelUnused()
        val resourceLock = checkNotNull(NativeReplayCaptureEnrollment::class.java.getDeclaredField("resources")
            .also { it.isAccessible = true }.get(enrollment))
        val entered = CountDownLatch(1)
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        rig.onNativeRead = { worker.set(Thread.currentThread()); entered.countDown() }
        lateinit var finish: java.util.concurrent.Future<NativeReplayCaptureFinish>
        val late = Thread { enrollment.quarantine() }
        synchronized(resourceLock) {
            finish = rig.owner.finishNativeReplayCapture(enrollment)
            check(entered.await(3, TimeUnit.SECONDS))
            // Force the actual queue release to pause while detaching only local resource refs.
            awaitBlocked(checkNotNull(worker.get()))
            late.start(); awaitBlocked(late)
        }
        late.join(3_000); assertFalse(late.isAlive)
        assertEquals(NativeReplayCaptureFinish.SETTLED, finish.get(3, TimeUnit.SECONDS))
        assertFalse(enrollment.isQuarantined())
        assertFalse(enrollment.settlement.isCompletedExceptionally)
        assertNull(NativeReplayCaptureResources::class.java.getDeclaredField("database")
            .also { it.isAccessible = true }.get(resourceLock))
        rig.owner.closeAsync().get(3, TimeUnit.SECONDS)
        val reopened = rig.openSame().get(); reopened.closeAsync().get(); Unit
    }
    private val nativePair = V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)

    @Test fun `borrowed cleanup uncertainty keeps exact installation resources and terminates closing worker`() = Rig().use { rig ->
        rig.activate(); val before = rig.row().payload.copyOf(); val executor = rig.executor()
        rig.owner.retainNativeReplayCleanupFailure().get(3, TimeUnit.SECONDS)
        assertArrayEquals(before, rig.row().payload)
        assertNull(rig.owner.enrollNativeReplayCapture().get(3, TimeUnit.SECONDS))
        // The one-way native restriction does not replace event authorization or mutate schema.
        assertTrue(rig.owner.capture(rig.event()).get(3, TimeUnit.SECONDS) is RuntimeCaptureResult.Accepted)
        assertArrayEquals(before, rig.row().payload)
        failure { rig.owner.closeAsync().get(3, TimeUnit.SECONDS) }
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        assertEquals(0, rig.databaseCloses)
        failure { rig.openSame().get(3, TimeUnit.SECONDS) }
        assertArrayEquals(before, rig.row().payload)
    }
    private class Rig : AutoCloseable {
        val clock = Clock(); val worker = Worker(); val gate = V2ConfigAuthorityGate()
        var body = ReplayFixtures.resource("contracts/v2/fixtures/config-enabled.json")
        val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, Scheduler(), worker)
        val backing = FakeRuntimeQueueBacking()
        val ownership = "native-accounting-" + UUID.randomUUID()
        @Volatile var ownerNanos: Long? = null
        @Volatile var onConnection: (() -> Unit)? = null
        @Volatile var onOwnerClock: (() -> Unit)? = null
        @Volatile var onReplayWrite: (() -> Unit)? = null
        @Volatile var afterReplayCommit: (() -> Unit)? = null
        @Volatile var onWrite: (() -> Unit)? = null
        @Volatile var afterWriteCommit: (() -> Unit)? = null
        @Volatile var onNativeRead: (() -> Unit)? = null
        @Volatile var onRecordRead: (() -> Unit)? = null
        @Volatile var databaseCloses = 0
        var owner = openSame().get().also { it.bindConfigurationGate(gate).get() }
        fun openSame() = RuntimeQueueOwner.open(ownership, RuntimeQueueLimits(100, MAX_RUNTIME_QUEUE_BYTES),
            readbackProvenReplayTransports = setOf(V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP)),
            supportedReplayProtocolGenerations = setOf(ReplayFixtures.GENERATION),
            databaseFactory = {
                onConnection?.also { onConnection = null }?.invoke()
                val db = backing.connection()
                object : RuntimeQueueDatabase by db {
                    override fun close() { databaseCloses += 1; db.close() }
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
                        var wrote = false; var replayWrote = false
                        val result = db.transaction { tx -> block(object : RuntimeQueueTransaction by tx {
                            override fun readRecord(sequence: Long): RuntimeStoredRecord? {
                                onRecordRead?.also { onRecordRead = null }?.invoke()
                                return tx.readRecord(sequence)
                            }
                            override fun readReplayRow(key: String): RuntimeReplayStoredRow? {
                                if (key == NativeReplayAccounting.KEY) onNativeRead?.also { onNativeRead = null }?.invoke()
                                return tx.readReplayRow(key)
                            }
                            override fun putReplayRow(row: RuntimeReplayStoredRow) {
                                tx.putReplayRow(row)
                                if (row.key.startsWith("chunk/")) { replayWrote = true; onReplayWrite?.also { onReplayWrite = null }?.invoke() }
                                if (row.key == NativeReplayAccounting.KEY) { wrote = true; onWrite?.also { onWrite = null }?.invoke() }
                            }
                        }) }
                        if (replayWrote) afterReplayCommit?.also { afterReplayCommit = null }?.invoke()
                        if (wrote) afterWriteCommit?.also { afterWriteCommit = null }?.invoke()
                        return result
                    }
                }
            }, legacyStateLoader = { initial() }, trustedSiteKey = KEY,
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis(): Long {
                    onOwnerClock?.also { onOwnerClock = null }?.invoke()
                    return clock.wallNowEpochMillis()
                }
                override fun elapsedRealtimeNanos() = ownerNanos ?: clock.monotonicNowNanos()
            })
        fun executor(): java.util.concurrent.ExecutorService = RuntimeQueueOwner::class.java.getDeclaredField("executor")
            .also { it.isAccessible = true }.get(owner) as java.util.concurrent.ExecutorService
        fun configure(change: (JSONObject) -> Unit) { val json = JSONObject(body); change(json); body = json.toString() }
        fun activate() {
            configure { it.getJSONObject("capabilities").getJSONObject("replay").getJSONArray("transports").put(
                JSONObject().put("codec", "elu-native-wireframe-v1").put("compression", "gzip")) }
            configure { it.getJSONObject("privacy").getJSONObject("replay").let { policy ->
                if (policy.getDouble("sampleRate") != 0.0) policy.put("sampleRate", 1.0)
            } }
            owner.ensurePreparedReplayStorage().get(); owner.ensureNativeReplayAccounting().get()
            driver.start(); worker.runNext(); publish()
        }
        fun privacy(): String {
            val config = V1ConfigJson.parseConfig(body)
            return PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(config.privacy),
                checkNotNull(config.features), checkNotNull(config.replayCapabilities), owner.snapshot().get().state.identity,
                false, now(), PrivacyReplayInput(false, false, false, 0, null))))
        }
        fun publish() { assertTrue(owner.submitCaptureAuthority(body, privacy()).get().toString(), owner.captureAuthorityForTesting().get() is RuntimeCaptureAuthorityState.Authorized) }
        fun renew(change: (JSONObject) -> Unit) {
            configure { it.put("issuedAt", "2026-08-05T00:01:05.000Z").put("revision", "renewed"); change(it) }
            driver.onBackground(); driver.onForeground(); worker.runNext(); publish()
        }
        fun observe() = checkNotNull(owner.observeNativeReplaySession().get())
        fun row() = checkNotNull(backing.replayRows[NativeReplayAccounting.KEY])
        fun state() = NativeReplayAccounting.read(row())
        fun replace(state: NativeReplaySessionState) { backing.replayRows[NativeReplayAccounting.KEY] = NativeReplayAccounting.row(state) }
        fun reopen() { owner.closeAsync().get(100, TimeUnit.MILLISECONDS); owner = openSame().get().also { it.bindConfigurationGate(gate).get() } }
        fun now() = RuntimeWallTimestamps.rfc3339(clock.wall)
        fun event() = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "activity", now(), emptyMap(), StandaloneRuntime.defaultVersions())
        override fun close() { runCatching { owner.closeAsync().get(100, TimeUnit.MILLISECONDS) }; driver.close() }
    }
    private class Clock : V2ConfigClock {
        var wall = Instant.parse("2026-08-05T00:01:06Z").toEpochMilli(); var nanos = 1L
        @Volatile var onRead: (() -> Unit)? = null
        var throwOnRead = false
        override fun wallNowEpochMillis(): Long { check(!throwOnRead); onRead?.also { onRead = null }?.invoke(); return wall }
        override fun monotonicNowNanos(): Long { check(!throwOnRead); return nanos }
    }
    private class Worker : V2ConfigLifecycleWorker {
        val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.add(task) }
        override fun interruptCurrent() = Unit
        override fun close() = Unit
        fun runNext() = tasks.removeFirst().invoke()
    }
    private class Scheduler : V2ConfigLifecycleScheduler {
        override fun schedule(delayNanos: Long, task: () -> Unit) = V2ConfigLifecycleTask { }
        override fun close() = Unit
    }
    private companion object {
        const val KEY = "elu_pk_live_AAAAAAAAAAAAAAAAAAAAAAAAAA"
        fun awaitBlocked(thread: Thread) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("Expected the exact owned thread to block at its held monitor", Thread.State.BLOCKED, thread.state)
        }
        fun failure(operation: () -> Unit) {
            try { operation(); fail("Expected bounded failure") } catch (error: java.util.concurrent.ExecutionException) { }
        }
        fun initial(): PersistedCoreState {
            val chunk = ReplayFixtures.request().getJSONObject("chunk"); val identity = chunk.getJSONObject("identity")
            return PersistedCoreState(identity = IdentityState(revision = identity.getLong("revision"), contextRevision = chunk.getLong("contextRevision"),
                anonymousId = identity.getString("anonymousId"), userId = identity.getString("userId"), groups = emptyMap(), superProperties = emptyMap(),
                session = SessionState(chunk.getString("sessionId"), "2026-08-05T00:01:00.000Z", "2026-08-05T00:01:05.000Z", 1800,
                    lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null), optedOut = false, updatedAt = "2026-08-05T00:01:05.000Z"),
                stream = StreamState(streamId = "stream_native", nextSequence = 0), flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
        }
    }
}
