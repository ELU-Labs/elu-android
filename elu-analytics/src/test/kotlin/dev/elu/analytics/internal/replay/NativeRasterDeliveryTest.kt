package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.runtime.*
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Original source/owner and transaction model. Opaque seeded payloads are not capture proof. */
class NativeRasterDeliveryTest {
    @Test fun `default selection retains raster without granting delivery`() = Rig(support = ReplayDeliverySupport.WIREFRAME_ONLY).use { h ->
        val row = h.seed(); assertNull(h.queue.claim()); assertNull(h.queue.nextWakeDelayMillis())
        assertArrayEquals(row.copyBytes(), h.rows().single().request.copyBytes())
    }

    @Test fun `exact original schema3 ACK deletes only its immutable head before suffix`() = Rig().use { h ->
        val first = h.seed(); val second = h.seed(1)
        val claim = checkNotNull(h.queue.claim())
        assertEquals(ReplayDeliveryFormat.RASTER, claim.format)
        assertEquals("https://ingest.elu.dev/v3/replay", claim.authorization.endpoint.toString())
        assertArrayEquals(first.copyBytes(), claim.copyBody())
        assertEquals(ReplayDeliveryCommit.COMMITTED, h.queue.commit(claim, claim.classify(ack(first), h.rig.clock.wall, 1)))
        assertArrayEquals(second.copyBytes(), checkNotNull(h.queue.claim()).copyBody())
    }

    @Test fun `sealed raster delivery does not reactivate background session or charge recording budget`() = Rig().use { h ->
        val original = h.seed()
        h.rig.owner.markBackgrounded("2026-08-05T00:01:00Z").get()
        val before = h.rig.owner.snapshot().get()
        val accounting = checkNotNull(h.rig.backing.replayRows[NativeReplayAccounting.KEY]).payload.copyOf()
        val claim = checkNotNull(h.queue.claim())
        assertArrayEquals(original.copyBytes(), claim.copyBody())
        assertEquals(ReplayDeliveryCommit.COMMITTED, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
        val after = h.rig.owner.snapshot().get()
        assertEquals(before.state.identity, after.state.identity); assertEquals(before.queuedCount, after.queuedCount)
        assertArrayEquals(accounting, checkNotNull(h.rig.backing.replayRows[NativeReplayAccounting.KEY]).payload)
    }

    @Test fun `raw source token replacement cannot adopt claimed or enrolled IO`() = Rig().use { h ->
        val original = h.seed(); val claim = checkNotNull(h.queue.claim())
        h.rig.body += " "; h.rig.refresh(); h.rig.publish()
        assertFalse(checkNotNull(claim.originalSource).isCurrent())
        var started = false
        assertNull(h.queue.dispatch(claim, ReplayDeliveryTransport { _, _ -> started = true; Op() }))
        assertFalse(started)
        assertEquals(ReplayDeliveryCommit.STALE, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
        assertArrayEquals(original.copyBytes(), h.rows().single().request.copyBytes())
        h.queue.abandon(claim); h.advance(30_000)
        val replacement = checkNotNull(h.queue.claim())
        assertNotSame(claim.originalSource, replacement.originalSource)
        assertArrayEquals(claim.copyBody(), replacement.copyBody())
    }

    @Test fun `identity context change denies original IO and ACK under another identity`() = Rig().use { h ->
        h.seed(); val claim = checkNotNull(h.queue.claim())
        h.rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("plan" to "changed"), "2026-08-05T00:01:00Z")).get()
        assertNull(h.queue.dispatch(claim, ReplayDeliveryTransport { _, _ -> error("must not start") }))
        assertEquals(ReplayDeliveryCommit.STALE, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
        assertEquals(1, h.rows().size)
    }

    @Test fun `identity reset or logout cannot deliver old pixels under a renewed source`() {
        for (identified in listOf(false, true)) Rig().use { h ->
            if (identified) h.rig.owner.appendMutations(listOf(RuntimeRecordDraft.Mutation("2026-08-05T00:01:00Z",
                RuntimeMutationChange.Identify("customer-before-logout", emptyMap(), emptyMap()), StandaloneRuntime.defaultVersions()))).get()
            val identity = h.rig.owner.snapshot().get().state.identity
            val original = h.seed(identity = identity); val claim = checkNotNull(h.queue.claim())
            h.rig.owner.applyLocal(RuntimeLocalStateChange.ResetIdentity("2026-08-05T00:01:00Z")).get()
            h.rig.body += " "; h.rig.refresh(); h.rig.publish()
            assertNotEquals(identity.revision, h.rig.owner.snapshot().get().state.identity.revision)
            assertNull(h.queue.dispatch(claim, ReplayDeliveryTransport { _, _ -> error("old identity must not dispatch") }))
            assertEquals(ReplayDeliveryCommit.STALE, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
            h.queue.abandon(claim); h.advance(30_000)
            assertNull(h.queue.claim()); assertNull(h.queue.nextWakeDelayMillis())
            assertArrayEquals(original.copyBytes(), h.rows().single().request.copyBytes())
        }
    }

    @Test fun `raster429 and legacy cooldowns isolate endpoints while sharing one occupied claim`() = Rig().use { h ->
        val raster = h.seed(); val old = h.seedLegacy()
        val first = checkNotNull(h.queue.claim()); assertEquals(ReplayDeliveryFormat.RASTER, first.format)
        assertNull(h.queue.claim())
        val error = JSONObject().put("schemaVersion", 1).put("requestId", raster.requestId).put("status", 429)
            .put("code", "request-refused").put("disposition", "retryable").put("message", "later")
        val response = ReplayTransportResponse(429, error.toString().toByteArray(), "10")
        val retry = first.classify(response, h.rig.clock.wall, 1)
        assertEquals(ReplayDeliveryOutcome.Retry(10_000, true), retry)
        h.queue.commit(first, retry)
        val legacy = checkNotNull(h.queue.claim()); assertArrayEquals(old.copyBytes(), legacy.copyBody())
        assertEquals(ReplayDeliveryFormat.WIREFRAME, legacy.format)
        h.queue.commit(legacy, ReplayDeliveryOutcome.Retry(20_000, true))
        assertNull(h.queue.claim()); assertEquals(10_000L, h.queue.nextWakeDelayMillis())
        h.advance(10_000)
        val next = checkNotNull(h.queue.claim()); assertArrayEquals(raster.copyBytes(), next.copyBody())
        h.queue.commit(next, ReplayDeliveryOutcome.Accepted)
        assertNull(h.queue.claim()); assertEquals(10_000L, h.queue.nextWakeDelayMillis())
        h.advance(10_000); assertArrayEquals(old.copyBytes(), checkNotNull(h.queue.claim()).copyBody())
    }

    @Test fun `explicit optout defers whole epoch deletion until original physical cleanup`() = Rig().use { h ->
        h.seed(); h.seed(1); val claim = checkNotNull(h.queue.claim()); val op = Op()
        val physical = checkNotNull(h.queue.dispatch(claim, ReplayDeliveryTransport { _, _ -> op }))
        h.rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, "2026-08-05T00:01:00Z")).get()
        assertTrue(op.canceled); assertEquals(2, h.rows().size)
        assertEquals(ReplayDeliveryCommit.STALE, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
        op.result.complete(refusal(claim.rasterRow.request, ReplayBlockKind.RASTER_REQUEST))
        physical.settlement.get(3, TimeUnit.SECONDS)
        assertTrue(h.rows().isEmpty())
    }

    @Test fun `divergent ACK reconciliation quarantines original owner rather than deleting suffix`(): Unit = Rig().use { h ->
        h.seed(); h.seed(1); val claim = checkNotNull(h.queue.claim())
        h.rig.expectQuarantinedClose = true
        h.rig.backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE
        assertThrows(RuntimeQueueCorruptionException::class.java) { h.queue.commit(claim, ReplayDeliveryOutcome.Accepted) }
        assertThrows(Exception::class.java) { h.queue.claim() }
    }

    @Test fun `missing raster branch retains exact body but current closed child is required`() = Rig().use { h ->
        val original = h.seed()
        h.rig.body = JSONObject(h.rig.body).apply {
            remove("raster"); getJSONObject("configV2").put("issuedAt", "2026-08-05T00:00:30Z")
        }.toString(); h.rig.refresh(); h.rig.publish()
        assertNull(h.queue.claim()); assertArrayEquals(original.copyBytes(), h.rows().single().request.copyBytes())
    }

    @Test fun `valid409 and413 retain and permanently block whole epoch append and reopen`() {
        for (kind in listOf(ReplayBlockKind.RASTER_REQUEST, ReplayBlockKind.RASTER_CHUNK,
            ReplayBlockKind.RASTER_SEQUENCE, ReplayBlockKind.RASTER_TOO_LARGE)) {
            val backing = FakeRuntimeQueueBacking(); val name = "raster-delivery-" + UUID.randomUUID()
            val originals: List<ByteArray>
            Rig(backing, name).use { h ->
                h.seed(); h.seed(1); originals = h.rows().map { it.request.copyBytes() }
                val claim = checkNotNull(h.queue.claim())
                val classified = claim.classify(refusal(claim.rasterRow.request, kind), h.rig.clock.wall, 1)
                assertEquals(ReplayDeliveryOutcome.Blocked(kind), classified)
                assertEquals(ReplayDeliveryCommit.COMMITTED, h.queue.commit(claim, classified))
                assertEquals(ReplayDeliveryCommit.COMMITTED, h.queue.commit(claim, classified))
                assertNull(h.queue.claim()); assertTrue(h.seedResult(2) is ReplayAppendResult.Rejected)
            }
            Rig(backing, name).use { reopened ->
                assertNull(reopened.queue.claim()); assertEquals(2, reopened.rows().size)
                originals.zip(reopened.rows()).forEach { (bytes, row) -> assertArrayEquals(bytes, row.request.copyBytes()) }
            }
        }
    }

    @Test fun `malformed conflict is protocol refusal rather than exact identity conflict`() = Rig().use { h ->
        h.seed(); val claim = checkNotNull(h.queue.claim())
        val outcome = claim.classify(ReplayTransportResponse(409, "{}".toByteArray()), h.rig.clock.wall, 1)
        assertEquals(ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL), outcome)
        h.queue.commit(claim, outcome); assertNull(h.queue.claim()); assertEquals(1, h.rows().size)
    }

    @Test fun `credential refusal needs newer source and new epoch without reviving old bytes`() = Rig().use { h ->
        h.seed(); val refused = checkNotNull(h.queue.claim())
        h.queue.commit(refused, ReplayDeliveryOutcome.Blocked(ReplayBlockKind.UNAUTHORIZED))
        h.seed(0, "new-epoch"); assertNull(h.queue.claim())
        h.rig.body = JSONObject(h.rig.body).apply { getJSONObject("configV2").put("issuedAt", "2026-08-05T00:00:30Z") }.toString()
        h.rig.refresh(); h.rig.publish()
        val next = checkNotNull(h.queue.claim()); assertEquals("new-epoch", next.replayId)
        assertEquals(2, h.rows().size)
    }

    @Test fun `lost ACK reopen delays then retries same bytes and never skips suffix`() {
        val backing = FakeRuntimeQueueBacking(); val name = "raster-lost-" + UUID.randomUUID(); val original: ByteArray
        Rig(backing, name).use { h -> original = h.seed().copyBytes(); h.seed(1); assertNotNull(h.queue.claim()) }
        Rig(backing, name).use { h ->
            assertNull(h.queue.claim()); assertEquals(30_000L, h.queue.nextWakeDelayMillis())
            h.advance(29_999); assertNull(h.queue.claim()); h.advance(1)
            val retry = checkNotNull(h.queue.claim()); assertArrayEquals(original, retry.copyBody()); assertEquals(2, retry.attemptCount)
        }
    }

    @Test fun `known committed and rolled back ACK ambiguity preserve exact deletion`() {
        for (outcome in listOf(FakeAmbiguousOutcome.COMMIT, FakeAmbiguousOutcome.ROLLBACK)) Rig().use { h ->
            h.seed(); val second = h.seed(1); val claim = checkNotNull(h.queue.claim())
            h.rig.backing.ambiguousNextCommit = outcome
            assertEquals(ReplayDeliveryCommit.COMMITTED, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
            assertArrayEquals(second.copyBytes(), h.rows().single().request.copyBytes())
        }
    }

    @Test fun `actual owner IO guard is once only and late physical refusal survives source loss`() = Rig().use { h ->
        h.seed(); val claim = checkNotNull(h.queue.claim()); val op = Op(); var guard: (() -> Boolean)? = null
        val original = checkNotNull(h.queue.dispatch(claim, ReplayDeliveryTransport { same, authorize ->
            assertSame(claim, same); guard = authorize; op
        }))
        assertTrue(checkNotNull(guard).invoke()); assertFalse(checkNotNull(guard).invoke())
        h.rig.driver.onBackground()
        op.result.complete(refusal(claim.rasterRow.request, ReplayBlockKind.RASTER_SEQUENCE))
        original.settlement.get(3, TimeUnit.SECONDS)
        assertEquals(ReplayDeliveryState.BLOCKED, h.metadata(claim.ordinal).state)
        assertEquals(ReplayBlockKind.RASTER_SEQUENCE.name, h.metadata(claim.ordinal).blockKind)
        assertEquals(1, h.rows().size)
    }

    @Test fun `TTL removes whole epoch and waits for original physical response cleanup`() = Rig().use { h ->
        h.seed(); h.seed(1, timestamp = "2026-08-05T00:01:01.000Z")
        val claim = checkNotNull(h.queue.claim()); val op = Op()
        val physical = checkNotNull(h.queue.dispatch(claim, ReplayDeliveryTransport { _, _ -> op }))
        h.advance(REPLAY_RETENTION_SECONDS * 1000)
        assertEquals(0, h.rig.owner.expirePreparedReplay().get())
        assertEquals(2, h.rows().size); assertTrue(op.canceled)
        op.result.completeExceptionally(IllegalStateException("closed original connection"))
        runCatching { physical.settlement.get(3, TimeUnit.SECONDS) }
        assertTrue(h.rows().isEmpty())
    }

    @Test fun `original coordinator close joins held raster refusal and never starts its suffix`() = Rig().use { h ->
        h.seed(); h.seed(1)
        val entered = java.util.concurrent.CountDownLatch(1); val op = Op()
        val observed = java.util.concurrent.atomic.AtomicReference<ReplayDeliveryClaim>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val coordinator = ReplayDeliveryCoordinator(h.queue, ReplayDeliveryTransport { claim, _ ->
            assertTrue(observed.compareAndSet(null, claim)); entered.countDown(); op
        }, executor, ReplayDeliveryScheduler { _, _ -> ReplayDeliveryScheduledTask {} },
            { h.rig.clock.wall }, { h.rig.clock.nanos / 1_000_000 }, { 0.5 })
        try {
            val pass = coordinator.flush(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            val closing = coordinator.closeAndWait(); assertFalse(closing.isDone); assertFalse(pass.isDone)
            op.result.complete(refusal(checkNotNull(observed.get()).rasterRow.request, ReplayBlockKind.RASTER_TOO_LARGE))
            assertEquals(ReplayDeliveryPass(1, 1), pass.get(3, TimeUnit.SECONDS))
            closing.get(3, TimeUnit.SECONDS); assertTrue(executor.isShutdown)
            assertEquals(2, h.rows().size); assertNull(h.queue.claim())
            assertEquals(ReplayBlockKind.RASTER_TOO_LARGE.name, h.metadata(checkNotNull(observed.get()).ordinal).blockKind)
        } finally {
            op.result.completeExceptionally(IllegalStateException("test cleanup"))
            coordinator.closeAndWait().get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun `durable restriction survives optin and process image reopen with or without an original attempt`() {
        for (dispatched in listOf(false, true)) Rig().use { h ->
            h.seed(); h.seed(1)
            val enrollment = if (dispatched) null else checkNotNull(h.rig.owner.enrollNativeReplayCapture().get())
            val use = enrollment?.let { checkNotNull(it.takePhysicalUse()) }
            val claim = if (dispatched) checkNotNull(h.queue.claim()) else null
            val op = if (dispatched) Op() else null
            val physical = claim?.let { checkNotNull(h.queue.dispatch(it, ReplayDeliveryTransport { _, _ -> checkNotNull(op) })) }
            try {
                val beforeSource = claim?.authorization?.sourceIssuedAt ?: h.rig.ledger().issuedAt
                h.rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, "2026-08-05T00:01:00Z")).get()
                h.rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, "2026-08-05T00:01:00Z")).get()
                assertNull(h.queue.claim()); assertNull(h.queue.nextWakeDelayMillis())
                val restriction = h.metadata(0)
                assertEquals(ReplayBlockKind.RASTER_RETIRE.name, restriction.blockKind)
                assertEquals(0, restriction.attempts); assertEquals("", restriction.owner); assertEquals("", restriction.nonce)
                assertEquals("", restriction.credentialWitness); assertEquals("", restriction.scopeWitness)
                assertEquals(beforeSource, restriction.sourceIssuedAt)
                assertEquals(restriction, ReplayDeliveryMetadata.decode(restriction.row()))
                if (claim != null) {
                    assertEquals(ReplayDeliveryCommit.STALE, h.queue.commit(claim, ReplayDeliveryOutcome.Accepted))
                    assertEquals(ReplayDeliveryCommit.STALE, h.queue.commit(claim, ReplayDeliveryOutcome.Blocked(ReplayBlockKind.RASTER_SEQUENCE)))
                    assertEquals(restriction, h.metadata(0))
                }
                // A copied committed database image models process death, never adopts physical work.
                val restored = FakeRuntimeQueueBacking().apply {
                    core = h.rig.backing.core; databaseSchemaVersion = h.rig.backing.databaseSchemaVersion
                    replayRows.putAll(h.rig.backing.replayRows); records.putAll(h.rig.backing.records)
                    flagRows.putAll(h.rig.backing.flagRows); captureRateState = h.rig.backing.captureRateState
                }
                Rig(restored).use { restarted ->
                    assertNull(restarted.queue.claim()); assertTrue(restarted.rows().isEmpty())
                }
            } finally {
                op?.result?.completeExceptionally(IllegalStateException("original IO settled"))
                physical?.let { runCatching { it.settlement.get(3, TimeUnit.SECONDS) } }
                use?.settle()
                enrollment?.let { assertEquals(NativeReplayCaptureFinish.SETTLED, h.rig.owner.finishNativeReplayCapture(it).get()) }
            }
            assertTrue(h.rows().isEmpty())
        }
    }

    @Test fun `retirement zero attempt shape cannot become legacy retry claim or a different row`() = Rig().use { h ->
        val row = h.seed()
        val restriction = ReplayDeliveryMetadata(0, row.digest, ReplayDeliveryState.BLOCKED, "", "", 0,
            blockKind = ReplayBlockKind.RASTER_RETIRE.name, protocolGeneration = NativeRasterSealer.GENERATION,
            raster = true, replayId = row.replayId, sourceIssuedAt = h.rig.ledger().issuedAt)
        assertEquals(restriction, ReplayDeliveryMetadata.decode(restriction.row()))
        for (bad in listOf(restriction.row().copy(storageSchemaVersion = 1),
            restriction.copy(state = ReplayDeliveryState.RETRY).row(), restriction.copy(state = ReplayDeliveryState.CLAIMED).row(),
            restriction.copy(attempts = 1).row(), restriction.copy(owner = UUID.randomUUID().toString()).row(),
            restriction.copy(credentialWitness = "sha256:" + "a".repeat(64)).row())) {
            assertThrows(Exception::class.java) { ReplayDeliveryMetadata.decode(bad) }
        }
        for (bad in listOf(restriction.copy(digest = "sha256:" + "f".repeat(64)), restriction.copy(replayId = "foreign-epoch"))) {
            h.rig.backing.connection().use { db ->
                assertThrows(RuntimeQueueCorruptionException::class.java) { db.transaction { tx ->
                    tx.putReplayRow(bad.row())
                    tx.putReplayRow(checkNotNull(ReplayQueueStore.state(tx)).copy(deliveryMetadataCount = 1).row())
                    ReplayQueueStore.validate(tx, RasterQueueRig.namespace)
                } }
            }
        }
    }

    @Test fun `raster metadata cannot masquerade as legacy or lose epoch identity`(): Unit = Rig().use { h ->
        h.seed(); val claim = checkNotNull(h.queue.claim()); val metadata = h.metadata(claim.ordinal)
        val row = metadata.row(); assertEquals(2L, row.storageSchemaVersion)
        assertEquals(metadata, ReplayDeliveryMetadata.decode(row))
        assertThrows(Exception::class.java) { ReplayDeliveryMetadata.decode(row.copy(storageSchemaVersion = 1)) }
        val broken = JSONObject(row.payload.toString(Charsets.UTF_8)).apply { remove("replayId") }
        assertThrows(Exception::class.java) { ReplayDeliveryMetadata.decode(row.copy(payload = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(broken.toString())))) }
    }

    internal class Op : ReplayTransportOperation {
        val result = SdkFuture<ReplayTransportResponse>(); override val settlement get() = result
        var canceled = false; override fun cancel() { canceled = true }
    }
    private class Rig(backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(), name: String = "raster-delivery-" + UUID.randomUUID(),
        support: ReplayDeliverySupport = ReplayDeliverySupport.INCLUDING_RASTER) : AutoCloseable {
        val rig = RasterQueueRig(backing, name)
        val queue: ReplayDeliveryQueue
        init {
            rig.activate(); assertTrue(rig.owner.ensureNativeRasterStorage().get())
            queue = rig.owner.openReplayDeliveryQueue(ReplayDeliveryPolicy(ReplayDeliveryPrivacy { config, identity, now ->
                PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(config.privacy),
                    checkNotNull(config.features), checkNotNull(config.replayCapabilities), identity, false,
                    RuntimeWallTimestamps.rfc3339(now))))
            }, ReplayMaskingRetention { _, _ -> true }, support)).get()
        }
        fun advance(millis: Long) { rig.clock.wall += millis; rig.clock.nanos += millis * 1_000_000 }
        fun seed(sequence: Long = 0, epoch: String = "raster-epoch", timestamp: String = "2026-08-05T00:01:00.000Z",
            identity: dev.elu.analytics.internal.core.IdentityState? = null): NativeRasterStoredRequest {
            val request = request(sequence, epoch, timestamp, identity); assertTrue(append(request) is ReplayAppendResult.Stored); return request
        }
        fun seedResult(sequence: Long) = append(request(sequence, "raster-epoch", "2026-08-05T00:01:00.000Z"))
        private fun request(sequence: Long, epoch: String, timestamp: String, identity: dev.elu.analytics.internal.core.IdentityState? = null): NativeRasterStoredRequest {
            val policy = checkNotNull(rig.gate.snapshot()?.nativeV3?.raster)
            val json = JSONObject(NativeRasterStorageTest.storedBody(policy.effectivePolicyHash, sequence).toString(Charsets.UTF_8))
            json.getJSONObject("chunk").put("replayId", epoch).put("startedAt", timestamp).put("endedAt", timestamp)
                .getJSONObject("privacy").put("policyRevision", policy.revision)
            identity?.let {
                json.getJSONObject("chunk").put("contextRevision", it.contextRevision).getJSONObject("identity")
                    .put("anonymousId", it.anonymousId).put("userId", it.userId ?: JSONObject.NULL).put("revision", it.revision)
            }
            return NativeRasterStoredRequest.parse(resign(json))
        }
        private fun append(request: NativeRasterStoredRequest) = rig.backing.connection().use { db -> db.transaction { tx ->
            val wrapper = checkNotNull(rig.gate.snapshot()?.nativeV3)
            ReplayQueueStore.reconcile(tx, wrapper.base, RasterQueueRig.namespace, rig.clock.wall, false,
                setOf(NativeReplayProtocol.V2.transport), setOf(NativeReplayProtocol.V2.generation), ReplayMaskingRetention { _, _ -> true })
            ReplayQueueStore.appendRaster(tx, request, RasterQueueRig.namespace, checkNotNull(wrapper.base.siteId), wrapper,
                rig.clock.wall, 100, MAX_RUNTIME_QUEUE_BYTES, MAX_REPLAY_REQUEST_BYTES) { rig.clock.wall }
        } }
        fun seedLegacy(): PreparedReplayRequest {
            val request = PreparedReplayRequest.parse(ReplayFixtures.bytes {
                it.put("replayId", "legacy-epoch").put("sequence", 0).put("codec", NativeReplayProtocol.V2.transport.codec)
                    .put("startedAt", "2026-08-05T00:01:00.000Z").put("endedAt", "2026-08-05T00:01:00.000Z")
            }, NativeReplayProtocol.V2.generation)
            rig.backing.connection().use { db -> db.transaction { tx ->
                assertTrue(ReplayQueueStore.append(tx, request, ReplayFixtures.profile(), RasterQueueRig.namespace,
                    checkNotNull(rig.gate.snapshot()?.nativeV3?.base?.siteId), NativeReplayProtocol.V2.generation,
                    rig.clock.wall, 100, MAX_RUNTIME_QUEUE_BYTES, MAX_REPLAY_REQUEST_BYTES) { rig.clock.wall } is ReplayAppendResult.Stored)
            } }
            return request
        }
        fun rows() = rig.owner.storedNativeRasterForTesting().get()
        fun metadata(ordinal: Long) = rig.backing.connection().use { db -> db.transaction { checkNotNull(ReplayQueueStore.delivery(it, ordinal)) } }
        override fun close() = rig.close()
    }
    companion object {
        internal fun resign(json: JSONObject): ByteArray {
            val chunk = V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(json.getJSONObject("chunk").toString()))
            json.put("requestId", "request_" + ReplayJson.digest("elu-sdk-replay-request-v3".toByteArray() + byteArrayOf(0) +
                ByteBuffer.allocate(4).putInt(chunk.size).array() + chunk).removePrefix("sha256:"))
            return V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(json.toString()))
        }
        internal fun ack(request: NativeRasterStoredRequest) = ReplayTransportResponse(200, JSONObject().put("schemaVersion", 3)
            .put("requestId", request.requestId).put("replayId", request.replayId).put("chunkId", request.chunkId)
            .put("sequence", request.sequence).put("result", "accepted").toString().toByteArray())
        internal fun refusal(request: NativeRasterStoredRequest, kind: ReplayBlockKind): ReplayTransportResponse {
            if (kind == ReplayBlockKind.RASTER_TOO_LARGE) return ReplayTransportResponse(413, JSONObject().put("schemaVersion", 1)
                .put("requestId", request.requestId).put("status", 413).put("code", "request-refused")
                .put("disposition", "retry-after-reduction").put("message", "refused").toString().toByteArray())
            return ReplayTransportResponse(409, JSONObject().put("schemaVersion", 3).put("requestId", request.requestId)
                .put("status", 409).put("code", "replay-identity-conflict").put("disposition", "permanent")
                .put("conflictScope", kind.name.removePrefix("RASTER_").lowercase()).toString().toByteArray())
        }
    }
}
