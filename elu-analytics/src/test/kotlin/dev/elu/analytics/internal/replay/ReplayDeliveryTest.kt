package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.*
import dev.elu.analytics.internal.core.*
import dev.elu.analytics.internal.runtime.*
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import dev.elu.analytics.internal.concurrent.SdkFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplayDeliveryTest {
    @Test fun `sealed delivery ignores fresh sampling session and budget without changing original bytes`() = Rig().use { rig ->
        rig.activate(); rig.append()
        val original = rig.rows().single().prepared.copyBytes()
        rig.owner.markBackgrounded("2026-08-05T00:01:06Z").get()
        val claim = checkNotNull(rig.delivery().claim())
        assertArrayEquals(original, claim.row.prepared.copyBytes())
        assertEquals(ReplayFixtures.request().getJSONObject("chunk").getString("sessionId"), claim.row.prepared.sessionId)
        assertEquals(ReplayDeliveryCommit.COMMITTED, rig.delivery().commit(claim, ReplayDeliveryOutcome.Accepted))
        assertTrue(rig.rows().isEmpty())
    }
    @Test fun `exact acknowledgement binds every identity and forbids unknown or duplicate fields`() {
        val request = PreparedReplayRequest.parse(ReplayFixtures.bytes(), ReplayFixtures.GENERATION)
        val good = ack(request)
        assertEquals(ReplayDeliveryOutcome.Accepted, classify(good, request))
        listOf("requestId", "replayId", "chunkId", "sequence", "result").forEach { field ->
            val body = JSONObject(String(good)).put(field, if (field == "sequence") 999 else "wrong")
            assertEquals(ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL), classify(body.toString().toByteArray(), request))
        }
        assertEquals(ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL), classify(JSONObject(String(good)).put("extra", true).toString().toByteArray(), request))
        assertEquals(ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL), classify(String(good).replace("{", "{\"schemaVersion\":2,").toByteArray(), request))
    }
    @Test fun `401 and 403 ignore malformed body and remain blocked after source renewal and reopen`() {
        for (status in listOf(401, 403)) Rig().use { rig ->
            rig.activate(); rig.append(); val claim = checkNotNull(rig.delivery().claim())
            val outcome = ReplayResponseClassifier.classify(ReplayTransportResponse(status, byteArrayOf(0xff.toByte())), claim.row.prepared, rig.clock.wall, 1000)
            assertEquals(ReplayDeliveryCommit.COMMITTED, rig.delivery().commit(claim, outcome))
            rig.renew { }; rig.reopen()
            assertNull(rig.delivery().claim()); assertEquals(1, rig.rows().size)
        }
    }
    @Test fun `strict 413 resolves one row while malformed 413 and conflict preserve bytes`() {
        val request = PreparedReplayRequest.parse(ReplayFixtures.bytes(), ReplayFixtures.GENERATION)
        val body = error(413, request.requestId)
        assertEquals(ReplayDeliveryOutcome.RejectedTooLarge, ReplayResponseClassifier.classify(ReplayTransportResponse(413, body), request, 0, 1000))
        assertEquals(ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL), ReplayResponseClassifier.classify(ReplayTransportResponse(413, "{}".toByteArray()), request, 0, 1000))
        val conflict = JSONObject().put("schemaVersion",2).put("requestId",request.requestId).put("status",409)
            .put("code","replay-identity-conflict").put("disposition","permanent").put("conflictScope","sequence")
        assertEquals(ReplayDeliveryOutcome.Blocked(ReplayBlockKind.CONFLICT), ReplayResponseClassifier.classify(ReplayTransportResponse(409, conflict.toString().toByteArray()), request,0,1000))
    }
    @Test fun `429 requires retry header and 503 preserves exact bytes for retry`() = Rig().use { rig ->
        rig.activate(); rig.append(); val claim = checkNotNull(rig.delivery().claim())
        assertEquals(ReplayDeliveryOutcome.Blocked(ReplayBlockKind.PROTOCOL), ReplayResponseClassifier.classify(ReplayTransportResponse(429,error(429,claim.row.prepared.requestId)),claim.row.prepared,rig.clock.wall,1000))
        val retry = ReplayResponseClassifier.classify(ReplayTransportResponse(503,error(503,claim.row.prepared.requestId),"2"),claim.row.prepared,rig.clock.wall,1000)
        assertEquals(ReplayDeliveryOutcome.Retry(2000), retry)
        assertEquals(ReplayDeliveryCommit.COMMITTED,rig.delivery().commit(claim,retry)); assertNull(rig.delivery().claim())
        rig.clock.nanos += 2_000_000_000L
        val next = checkNotNull(rig.delivery().claim()); assertArrayEquals(claim.row.prepared.copyBytes(),next.row.prepared.copyBytes())
        assertNotEquals(claim.nonce,next.nonce); assertEquals(2,next.attemptCount)
        assertEquals(ReplayDeliveryCommit.STALE,rig.delivery().commit(claim,ReplayDeliveryOutcome.Accepted))
    }
    @Test fun `retry reopen rearms whole retained delay and unknown claims wait thirty seconds`() {
        for (retry in listOf(false,true)) Rig().use { rig ->
            rig.activate(); rig.append(); val claim=checkNotNull(rig.delivery().claim())
            if(retry) rig.delivery().commit(claim,ReplayDeliveryOutcome.Retry(2000))
            rig.reopen(); assertNull(rig.delivery().claim())
            rig.clock.nanos += (if(retry) 2000 else 30000)*1_000_000L
            assertArrayEquals(claim.row.prepared.copyBytes(),checkNotNull(rig.delivery().claim()).row.prepared.copyBytes())
        }
    }
    @Test fun `source withdrawal after claim forbids dispatch or ACK deletion but persists refusal`() = Rig().use { rig ->
        rig.activate();rig.append();val queue=rig.delivery();val claim=checkNotNull(queue.claim())
        rig.driver.onBackground()
        var started=false
        assertNull(queue.dispatch(claim,ReplayDeliveryTransport { _, _ -> started=true; Op() }))
        assertFalse(started);assertEquals(ReplayDeliveryCommit.STALE,queue.commit(claim,ReplayDeliveryOutcome.Accepted))
        assertEquals(ReplayDeliveryCommit.COMMITTED,queue.commit(claim,ReplayDeliveryOutcome.Blocked(ReplayBlockKind.FORBIDDEN)))
        assertEquals(1,rig.rows().size)
    }
    @Test fun `actual IO guard denies optout accepted after nonblocking enrollment`() = Rig().use { rig ->
        rig.activate();rig.append();val queue=rig.delivery();val claim=checkNotNull(queue.claim());val op=Op()
        var guard:(()->Boolean)?=null
        assertNotNull(queue.dispatch(claim,ReplayDeliveryTransport { _, authorize -> guard=authorize;op }))
        rig.owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true,"2026-08-05T00:01:06Z")).get()
        assertFalse(checkNotNull(guard).invoke());op.settlement.completeExceptionally(IllegalStateException("withdrawn")); Unit
    }
    @Test fun `owner close retains exclusive lease until physical settlement and denies final IO guard`() {
        val rig=Rig();rig.activate();rig.append();val queue=rig.delivery();val claim=checkNotNull(queue.claim());val op=Op()
        var guard:(()->Boolean)?=null
        queue.dispatch(claim,ReplayDeliveryTransport { _, authorize -> guard=authorize;op })
        val closing=rig.owner.closeAsync()
        assertFalse(closing.isDone);assertEquals(0,rig.leaseCloses);assertTrue(op.cancelled);assertFalse(checkNotNull(guard).invoke())
        op.settlement.completeExceptionally(IllegalStateException("settled"));closing.get(3,TimeUnit.SECONDS)
        assertEquals(1,rig.leaseCloses);rig.driver.close()
    }
    @Test fun `generation change and masking withdrawal purge before dispatch without resurrecting claimed rows`() {
        for(generation in listOf(false,true)) Rig().use { rig ->
            rig.activate();rig.append();val q=rig.delivery();val claim=checkNotNull(q.claim())
            if(generation) rig.renew { it.getJSONObject("capabilities").getJSONObject("replay").put("replayProtocolGeneration","replay-v2-generation-2") }
            else rig.profileCurrent=false
            assertNull(q.dispatch(claim,ReplayDeliveryTransport { _, _ -> fail("must not start");Op() }))
            assertTrue(rig.rows().isEmpty());assertEquals(ReplayDeliveryCommit.STALE,q.commit(claim,ReplayDeliveryOutcome.Accepted))
        }
    }
    @Test fun `ambiguous committed exact ACK and retry do not resolve another row`() = Rig().use { rig ->
        rig.activate();rig.append();val q=rig.delivery();val claim=checkNotNull(q.claim())
        rig.backing.ambiguousNextCommit=FakeAmbiguousOutcome.COMMIT
        assertEquals(ReplayDeliveryCommit.COMMITTED,q.commit(claim,ReplayDeliveryOutcome.Accepted));assertTrue(rig.rows().isEmpty())
        assertEquals(ReplayDeliveryCommit.STALE,q.commit(claim,ReplayDeliveryOutcome.Accepted))
    }
    @Test fun `coordinator coalesces holds physical slot on close and never blocks capture owner`() = Rig().use { rig ->
        rig.activate();rig.append();val q=rig.delivery();val op=Op();val enrolled=CountDownLatch(1)
        val co=ReplayDeliveryCoordinator(q,ReplayDeliveryTransport { _, _ -> enrolled.countDown();op },Executors.newSingleThreadExecutor(),
            ReplayDeliveryScheduler { _, _ -> ReplayDeliveryScheduledTask {} },{rig.clock.wall},{rig.clock.nanos/1_000_000},{0.5})
        val pass=co.flush();assertTrue(enrolled.await(3,TimeUnit.SECONDS));assertSame(pass,co.flush())
        rig.owner.applyLocal(RuntimeLocalStateChange.SetFlagPersonProperties(mapOf("plan" to "new"),"2026-08-05T00:01:06Z")).get(3,TimeUnit.SECONDS)
        co.close();assertTrue(op.cancelled);assertFalse(pass.isDone);assertSame(pass,co.flush())
        op.settlement.completeExceptionally(IllegalStateException("settled"));pass.get(3,TimeUnit.SECONDS)
        assertEquals(1,rig.rows().size)
    }

    @Test fun `single claim enrolls once and final IO permission is single-use`() = Rig().use { rig ->
        rig.activate(); rig.append(); val q=rig.delivery(); val claim=checkNotNull(q.claim()); val op=Op()
        var guard:(()->Boolean)?=null
        q.dispatch(claim,ReplayDeliveryTransport { _, authorize -> guard=authorize;op })
        assertTrue(checkNotNull(guard).invoke()); assertFalse(checkNotNull(guard).invoke())
        op.settlement.complete(ReplayTransportResponse(200,ack(claim.row.prepared)))
        assertFalse(checkNotNull(guard).invoke())
        assertNull(q.dispatch(claim,ReplayDeliveryTransport { _, _ -> fail("second enrollment");Op() }))
    }
    @Test fun `postcommit profile withdrawal denies claim and dispatch publication`() {
        for(dispatch in listOf(false,true)) Rig().use { rig ->
            rig.activate();rig.append();val q=rig.delivery()
            val claim=if(dispatch) checkNotNull(q.claim()) else null
            rig.afterTransaction={rig.profileCurrent=false}
            if(dispatch) assertNull(q.dispatch(checkNotNull(claim),ReplayDeliveryTransport { _, _ -> fail("late profile");Op() }))
            else assertNull(q.claim())
        }
    }
    @Test fun `postcommit wall rollback preserves bytes and persists replay clock denial across reopen`() = Rig().use { rig ->
        rig.activate();rig.append();val q=rig.delivery()
        rig.afterTransaction={rig.ownerWall=rig.clock.wall-1}
        assertNull(q.claim());assertEquals(1,rig.rows().size);assertTrue(rig.state().clockDenied)
        rig.ownerWall=rig.clock.wall;rig.reopen();assertNull(rig.delivery().claim());assertEquals(1,rig.rows().size)
    }
    @Test fun `heads use lowest unresolved sequence then global ordinal and blocked head cannot skip`() = Rig().use { rig ->
        rig.activate()
        rig.append(rig.request { it.put("chunkId","a2").put("sequence",2) })
        rig.append(rig.request { it.put("chunkId","a1").put("sequence",1) })
        rig.append(rig.request { it.put("replayId","replay-b").put("chunkId","b0").put("sequence",0) })
        val q=rig.delivery();val head=checkNotNull(q.claim());assertEquals(1L,head.row.ordinal)
        q.commit(head,ReplayDeliveryOutcome.Blocked(ReplayBlockKind.FORBIDDEN))
        val other=checkNotNull(q.claim());assertEquals(2L,other.row.ordinal)
        q.commit(other,ReplayDeliveryOutcome.Accepted);assertNull(q.claim());assertEquals(2,rig.rows().size)
    }
    @Test fun `endpoint cooldown spans other replay heads and restores its full delay on reopen`() = Rig().use { rig ->
        rig.activate();rig.append()
        rig.append(rig.request { it.put("replayId","replay-b").put("chunkId","b0").put("sequence",0) })
        val q=rig.delivery();val head=checkNotNull(q.claim());q.commit(head,ReplayDeliveryOutcome.Retry(2000,true))
        assertNull(q.claim());assertEquals(2000L,q.nextWakeDelayMillis())
        rig.reopen();val reopened=rig.delivery();assertNull(reopened.claim());assertEquals(2000L,reopened.nextWakeDelayMillis())
        rig.clock.nanos+=2_000_000_000L;assertNotNull(reopened.claim())
    }
    @Test fun `protocol and conflict blocks survive TTL and endpoint changes`() {
        for(kind in listOf(ReplayBlockKind.PROTOCOL,ReplayBlockKind.CONFLICT)) Rig().use { rig ->
            rig.activate();rig.append();val q=rig.delivery();val claim=checkNotNull(q.claim())
            q.commit(claim,ReplayDeliveryOutcome.Blocked(kind))
            rig.renew { it.getJSONObject("endpoints").put("replay","https://ingest.elu.dev/v2/replay?route=next") }
            assertNull(q.claim());assertEquals(1,rig.rows().size)
        }
    }
    @Test fun `unchanged identity sealed session can expire while source lapse preserves bytes`() = Rig().use { rig ->
        rig.activate();rig.append();rig.ownerWall=Instant.parse("2026-08-05T00:02:06Z").toEpochMilli()
        assertNotNull(rig.delivery().claim())
        rig.driver.onBackground();assertEquals(1,rig.rows().size)
    }
    @Test fun `tampered metadata and orphan delivery rows fail reopen without repair`() {
        for(orphan in listOf(false,true)) Rig().use { rig ->
            rig.activate();rig.append();rig.delivery().claim()
            val key=ReplayDeliveryMetadata.key(0);val row=checkNotNull(rig.backing.replayRows[key])
            if(orphan) rig.backing.replayRows[ReplayDeliveryMetadata.key(9)]=row.copy(key=ReplayDeliveryMetadata.key(9))
            else rig.backing.replayRows[key]=row.copy(payload=JSONObject(String(row.payload)).put("protocolGeneration","other").toString().toByteArray())
            try { rig.reopen();fail("corruption") } catch (_:java.util.concurrent.ExecutionException) { }
            assertTrue(rig.backing.replayRows.isNotEmpty())
        }
    }
    @Test fun `restored retry installs a wake without an existing live coordinator timer`() = Rig().use { rig ->
        rig.activate();rig.append();val q=rig.delivery();q.commit(checkNotNull(q.claim()),ReplayDeliveryOutcome.Retry(2000));rig.reopen()
        val tasks=java.util.concurrent.CopyOnWriteArrayList<Pair<Long,()->Unit>>()
        val co=ReplayDeliveryCoordinator(rig.delivery(),ReplayDeliveryTransport { _, _ -> fail("not due");Op() },Executors.newSingleThreadExecutor(),
            ReplayDeliveryScheduler { delay, task -> tasks.add(delay to task);ReplayDeliveryScheduledTask {} },{rig.clock.wall},{rig.clock.nanos/1_000_000},{0.5})
        assertEquals(0,co.flush().get(3,TimeUnit.SECONDS).attempted);assertEquals(2000L,tasks.single().first);co.close()
    }
    @Test fun `deadline cancellation retains physical occupancy and late ACK cannot delete`() = Rig().use { rig ->
        rig.activate();rig.append();val tasks=java.util.concurrent.CopyOnWriteArrayList<Pair<Long,()->Unit>>();val op=Op()
        val co=ReplayDeliveryCoordinator(rig.delivery(),ReplayDeliveryTransport { _, _ -> op },Executors.newSingleThreadExecutor(),
            ReplayDeliveryScheduler { delay, task -> tasks.add(delay to task);ReplayDeliveryScheduledTask {} },{rig.clock.wall},{rig.clock.nanos/1_000_000},{0.5})
        val pass=co.flush();val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(tasks.isEmpty() && System.nanoTime()<limit) Thread.yield()
        assertFalse(tasks.isEmpty());tasks.first().second();assertTrue(op.cancelled);assertFalse(pass.isDone)
        op.settlement.complete(ReplayTransportResponse(200,ack(rig.rows().single().prepared)))
        pass.get(3,TimeUnit.SECONDS);assertEquals(1,rig.rows().size);co.close()
    }

    @Test fun `long Retry After remains honored within the shared twenty four hour bound`() {
        val request=PreparedReplayRequest.parse(ReplayFixtures.bytes(),ReplayFixtures.GENERATION)
        assertEquals(ReplayDeliveryOutcome.Retry(3_600_000,true),ReplayResponseClassifier.classify(
            ReplayTransportResponse(429,error(429,request.requestId),"3600"),request,0,1000))
        assertEquals(ReplayDeliveryOutcome.Retry(REPLAY_MAX_RETRY_MILLIS),ReplayResponseClassifier.classify(
            ReplayTransportResponse(503,error(503,request.requestId),"999999999999999999999"),request,0,1000))
    }
    @Test fun `actual IO guard rechecks profile after its database commit`() = Rig().use { rig ->
        rig.activate();rig.append();val q=rig.delivery();val claim=checkNotNull(q.claim());val op=Op()
        var guard:(()->Boolean)?=null
        q.dispatch(claim,ReplayDeliveryTransport { _, authorize -> guard=authorize;op })
        rig.afterTransaction={rig.profileCurrent=false}
        try { assertFalse(checkNotNull(guard).invoke()) }
        finally { op.settlement.completeExceptionally(IllegalStateException("settled")) }
    }
    @Test fun `interrupted coordinator worker cancels but waits for physical settlement`() = Rig().use { rig ->
        rig.activate();rig.append();val op=Op();val enrolled=CountDownLatch(1)
        val thread=java.util.concurrent.atomic.AtomicReference<Thread>()
        val executor=Executors.newSingleThreadExecutor { task -> Thread(task).also { thread.set(it) } }
        val co=ReplayDeliveryCoordinator(rig.delivery(),ReplayDeliveryTransport { _, _ -> enrolled.countDown();op },executor,
            ReplayDeliveryScheduler { _, _ -> ReplayDeliveryScheduledTask {} },{rig.clock.wall},{rig.clock.nanos/1_000_000},{0.5})
        val pass=co.flush();assertTrue(enrolled.await(3,TimeUnit.SECONDS));thread.get().interrupt()
        val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(!op.cancelled && System.nanoTime()<limit) Thread.yield()
        assertTrue(op.cancelled);assertFalse(pass.isDone)
        op.settlement.completeExceptionally(IllegalStateException("settled"));pass.get(3,TimeUnit.SECONDS)
        assertEquals(1,rig.rows().size);co.close()
    }

    @Test fun `long endpoint cooldown controls wake even when another replay retry is already due`() = Rig().use { rig ->
        rig.activate();rig.append()
        rig.append(rig.request { it.put("replayId","replay-b").put("chunkId","b0").put("sequence",0) })
        val q=rig.delivery();q.commit(checkNotNull(q.claim()),ReplayDeliveryOutcome.Retry(1000))
        q.commit(checkNotNull(q.claim()),ReplayDeliveryOutcome.Retry(100000,true))
        rig.clock.nanos+=1_000_000_000L
        assertNull(q.claim());assertEquals(99000L,q.nextWakeDelayMillis())
    }
    @Test fun `canceled timer cannot wake after withdrawal or erase a replacement timer`() {
        val tasks=java.util.concurrent.CopyOnWriteArrayList<()->Unit>()
        val claims=java.util.concurrent.atomic.AtomicInteger()
        var delay=1000L
        val queue=object:ReplayDeliveryQueue {
            override fun claim():ReplayDeliveryClaim? { claims.incrementAndGet();return null }
            override fun nextWakeDelayMillis()=delay
            override fun dispatch(claim:ReplayDeliveryClaim,transport:ReplayDeliveryTransport):ReplayTransportOperation?=null
            override fun commit(claim:ReplayDeliveryClaim,outcome:ReplayDeliveryOutcome)=ReplayDeliveryCommit.STALE
            override fun abandon(claim:ReplayDeliveryClaim)=Unit
        }
        val co=ReplayDeliveryCoordinator(queue,ReplayDeliveryTransport { _, _ -> fail("no request");Op() },Executors.newSingleThreadExecutor(),
            ReplayDeliveryScheduler { _, task -> tasks.add(task);ReplayDeliveryScheduledTask {} },{0L},{0L},{0.5})
        co.flush().get(3,TimeUnit.SECONDS);val old=tasks.single();co.withdraw();old();assertEquals(1,claims.get())
        co.flush().get(3,TimeUnit.SECONDS);val replaced: () -> Unit = tasks[tasks.size-1];delay=500
        co.flush().get(3,TimeUnit.SECONDS);val current: () -> Unit = tasks[tasks.size-1];assertNotSame(replaced,current)
        val before=claims.get();replaced.invoke();old();assertEquals(before,claims.get())
        current.invoke();co.flush().get(3,TimeUnit.SECONDS);assertTrue(claims.get()>before);co.close()
    }

    @Test fun `late physical401 and403 remain permanent after withdrawal or deadline and reopen`() {
        for(status in listOf(401,403)) for(deadline in listOf(false,true)) Rig().use { rig ->
            rig.activate();rig.append();val op=Op();val tasks=java.util.concurrent.CopyOnWriteArrayList<()->Unit>()
            val co=ReplayDeliveryCoordinator(rig.delivery(),ReplayDeliveryTransport { _, _ -> op },Executors.newSingleThreadExecutor(),
                ReplayDeliveryScheduler { _, task -> tasks.add(task);ReplayDeliveryScheduledTask {} },{rig.clock.wall},{rig.clock.nanos/1_000_000},{0.5})
            val pass=co.flush();val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(tasks.isEmpty() && System.nanoTime()<limit) Thread.yield()
            assertFalse(tasks.isEmpty())
            if(deadline) tasks[0].invoke() else { rig.driver.onBackground();co.withdraw() }
            op.settlement.complete(ReplayTransportResponse(status,byteArrayOf(0xff.toByte())))
            pass.get(3,TimeUnit.SECONDS);co.close();rig.renew {};rig.reopen()
            assertNull(rig.delivery().claim());assertEquals(1,rig.rows().size)
            assertEquals(if(status==401) "UNAUTHORIZED" else "FORBIDDEN",ReplayDeliveryMetadata.decode(checkNotNull(rig.backing.replayRows[ReplayDeliveryMetadata.key(0)])).blockKind)
        }
    }
    @Test fun `owner close persists late401 and403 before releasing exclusive lease`() {
        for(status in listOf(401,403)) Rig().use { rig ->
            rig.activate();rig.append();val q=rig.delivery();val claim=checkNotNull(q.claim());val op=Op()
            q.dispatch(claim,ReplayDeliveryTransport { _, _ -> op })
            val closing=rig.owner.closeAsync();assertFalse(closing.isDone);assertEquals(0,rig.leaseCloses)
            rig.backing.ambiguousNextCommit=FakeAmbiguousOutcome.COMMIT
            op.settlement.complete(ReplayTransportResponse(status,byteArrayOf()))
            closing.get(3,TimeUnit.SECONDS);assertEquals(1,rig.leaseCloses)
            assertEquals(ReplayDeliveryState.BLOCKED,ReplayDeliveryMetadata.decode(checkNotNull(rig.backing.replayRows[ReplayDeliveryMetadata.key(0)])).state)
            rig.openAfterClose();assertNull(rig.delivery().claim());assertEquals(1,rig.rows().size)
        }
    }
    @Test fun `request budget schedules a bounded continuation for ready backlog`() = Rig().use { rig ->
        rig.activate();rig.append()
        rig.append(rig.request { it.put("chunkId","second").put("sequence",2) })
        val tasks=java.util.concurrent.CopyOnWriteArrayList<Pair<Long,()->Unit>>()
        val co=ReplayDeliveryCoordinator(rig.delivery(),ReplayDeliveryTransport { claim, _ -> Op().also { it.settlement.complete(ReplayTransportResponse(200,ack(claim.row.prepared))) } },
            Executors.newSingleThreadExecutor(),ReplayDeliveryScheduler { delay,task -> tasks.add(delay to task);ReplayDeliveryScheduledTask {} },
            {rig.clock.wall},{rig.clock.nanos/1_000_000},{0.5},maximumRequests=1)
        assertEquals(1,co.flush().get(3,TimeUnit.SECONDS).attempted);assertEquals(1,rig.rows().size)
        tasks.last { it.first==1L }.second();co.flush().get(3,TimeUnit.SECONDS)
        assertTrue(rig.rows().isEmpty());co.close()
    }

    @Test fun `interrupted dispatch handoff retains exact enrolled claim through late refusal`() {
        for (status in listOf(401, 403)) Rig().use { rig ->
            rig.activate(); rig.append()
            val queue = rig.delivery()
            val op = Op()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
            val executor = Executors.newSingleThreadExecutor { work -> Thread(work).also { worker.set(it) } }
            val coordinator = ReplayDeliveryCoordinator(queue, ReplayDeliveryTransport { _, _ ->
                // Test-only held enrollment proves the owner command has begun before its Future returns.
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                op
            }, executor, ReplayDeliveryScheduler { _, _ -> ReplayDeliveryScheduledTask {} },
                { rig.clock.wall }, { rig.clock.nanos / 1_000_000 }, { 0.5 })
            val pass = coordinator.flush()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                worker.get().interrupt()
                release.countDown()
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!op.cancelled && System.nanoTime() < limit) Thread.yield()
                assertTrue(op.cancelled)
                assertFalse(pass.isDone)
                assertNull(queue.claim())
                assertEquals(ReplayDeliveryState.ENROLLED,
                    ReplayDeliveryMetadata.decode(checkNotNull(rig.backing.replayRows[ReplayDeliveryMetadata.key(0)])).state)
                op.settlement.complete(ReplayTransportResponse(status, byteArrayOf()))
                pass.get(3, TimeUnit.SECONDS)
                rig.renew {}; rig.reopen()
                assertNull(rig.delivery().claim())
                assertEquals(if (status == 401) "UNAUTHORIZED" else "FORBIDDEN",
                    ReplayDeliveryMetadata.decode(checkNotNull(rig.backing.replayRows[ReplayDeliveryMetadata.key(0)])).blockKind)
                assertEquals(1, rig.rows().size)
            } finally {
                release.countDown()
                op.settlement.complete(ReplayTransportResponse(status, byteArrayOf()))
                coordinator.close()
            }
        }
    }

    @Test fun `legacy metadata count remains absent until first attempt then tracks exact creation and deletion`() = Rig().use { rig ->
        rig.activate(); rig.append()
        val legacy = checkNotNull(rig.backing.replayRows["state"])
        assertNull(rig.state().deliveryMetadataCount)
        assertArrayEquals(legacy.payload, ReplayStoredState.decode(legacy).row().payload)
        rig.reopen()
        val queue = rig.delivery()
        var claim = checkNotNull(queue.claim())
        assertEquals(1L, rig.state().deliveryMetadataCount)
        queue.commit(claim, ReplayDeliveryOutcome.Retry(1))
        rig.clock.nanos += 1_000_000
        claim = checkNotNull(queue.claim())
        assertEquals(1L, rig.state().deliveryMetadataCount)
        queue.commit(claim, ReplayDeliveryOutcome.Accepted)
        assertEquals(0L, rig.state().deliveryMetadataCount)
        rig.reopen(); assertTrue(rig.rows().isEmpty())
    }

    @Test fun `missing refusal metadata fails closed on reopen while preserving original chunk bytes`() {
        for (status in listOf(ReplayBlockKind.UNAUTHORIZED, ReplayBlockKind.FORBIDDEN)) Rig().use { rig ->
            rig.activate(); rig.append()
            val queue = rig.delivery()
            val claim = checkNotNull(queue.claim())
            queue.commit(claim, ReplayDeliveryOutcome.Blocked(status))
            assertEquals(1L, rig.state().deliveryMetadataCount)
            rig.backing.replayRows.remove(ReplayDeliveryMetadata.key(0))
            try { rig.reopen(); fail("missing refusal metadata") }
            catch (_: java.util.concurrent.ExecutionException) { }
            assertTrue(rig.backing.replayRows.containsKey("chunk/0000000000000000"))
            assertTrue(rig.backing.replayRows.keys.any { it.startsWith("body/") })
        }
    }

    @Test fun `old pass completion cannot settle close of a new pass started by its dependent`() = Rig().use { rig ->
        rig.activate(); rig.append(); rig.append(rig.request { it.put("chunkId", "second").put("sequence", 2) })
        val first = Op(); val second = Op()
        val enteredFirst = java.util.concurrent.CountDownLatch(1); val enteredSecond = java.util.concurrent.CountDownLatch(1)
        val starts = java.util.concurrent.atomic.AtomicInteger()
        // A bounded two-thread executor exposes callback overlap after pass1 releases running.
        // The coordinator still owns exactly one enrolled physical pass at a time.
        val executor = Executors.newFixedThreadPool(2)
        val co = ReplayDeliveryCoordinator(rig.delivery(), ReplayDeliveryTransport { _, _ ->
            if (starts.incrementAndGet() == 1) { enteredFirst.countDown(); first }
            else { enteredSecond.countDown(); second }
        }, executor, ReplayDeliveryScheduler { _, _ -> ReplayDeliveryScheduledTask {} },
            { rig.clock.wall }, { rig.clock.nanos / 1_000_000 }, { 0.5 }, maximumRequests = 1)
        val closeFromDependent = dev.elu.analytics.internal.concurrent.SdkFuture<dev.elu.analytics.internal.concurrent.SdkFuture<Unit>>()
        try {
            val pass = co.flush(); assertTrue(enteredFirst.await(3, TimeUnit.SECONDS))
            pass.whenComplete { _, _ ->
                try {
                    co.flush(); check(enteredSecond.await(3, TimeUnit.SECONDS))
                    closeFromDependent.complete(co.closeAndWait())
                } catch (error: Throwable) { closeFromDependent.completeExceptionally(error) }
            }
            val original = rig.rows().first().prepared
            first.settlement.complete(ReplayTransportResponse(200, ack(original)))
            val closing = closeFromDependent.get(3, TimeUnit.SECONDS)
            // Both pool workers were occupied; this task can run only after pass1 finally ends.
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(2, starts.get()); assertFalse("Pass1 completed pass2's close receipt", closing.isDone)
            second.settlement.complete(ReplayTransportResponse(403, byteArrayOf()))
            closing.get(3, TimeUnit.SECONDS)
            assertEquals("FORBIDDEN", ReplayDeliveryMetadata.decode(checkNotNull(rig.backing.replayRows[ReplayDeliveryMetadata.key(1)])).blockKind)
        } finally {
            first.settlement.complete(ReplayTransportResponse(403, byteArrayOf()))
            second.settlement.complete(ReplayTransportResponse(403, byteArrayOf()))
            co.closeAndWait().get(3, TimeUnit.SECONDS)
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `idle close blocks an already dispatched old timer and owns only its settled interval`() {
        val tasks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>(); val claims = java.util.concurrent.atomic.AtomicInteger()
        val queue = object : ReplayDeliveryQueue {
            override fun claim(): ReplayDeliveryClaim? { claims.incrementAndGet(); return null }
            override fun nextWakeDelayMillis(): Long? = 1
            override fun dispatch(claim: ReplayDeliveryClaim, transport: ReplayDeliveryTransport): ReplayTransportOperation? = null
            override fun commit(claim: ReplayDeliveryClaim, outcome: ReplayDeliveryOutcome) = ReplayDeliveryCommit.STALE
            override fun abandon(claim: ReplayDeliveryClaim) = Unit
        }
        val co = ReplayDeliveryCoordinator(queue, ReplayDeliveryTransport { _, _ -> error("No physical I/O") }, Executors.newSingleThreadExecutor(),
            ReplayDeliveryScheduler { _, task -> tasks.add(task); ReplayDeliveryScheduledTask {} }, { 0 }, { 0 })
        co.flush().get(3, TimeUnit.SECONDS); val closing = co.closeAndWait(); closing.get(3, TimeUnit.SECONDS)
        tasks.single().invoke(); assertEquals(0, co.flush().get().attempted)
        assertEquals(1, claims.get()); assertSame(closing, co.closeAndWait())
    }

    @Test fun `close joins a timer-origin pass and late physical refusal before completion`() {
        for (status in listOf(401, 403)) Rig().use { rig ->
            rig.activate(); rig.append()
            val queue = rig.delivery(); queue.commit(checkNotNull(queue.claim()), ReplayDeliveryOutcome.Retry(1000))
            val tasks = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, () -> Unit>>()
            val entered = java.util.concurrent.CountDownLatch(1); val op = Op()
            val executor = Executors.newSingleThreadExecutor()
            val co = ReplayDeliveryCoordinator(queue, ReplayDeliveryTransport { _, _ -> entered.countDown(); op }, executor,
                ReplayDeliveryScheduler { delay, task -> tasks.add(delay to task); ReplayDeliveryScheduledTask {} },
                { rig.clock.wall }, { rig.clock.nanos / 1_000_000 }, { 0.5 })
            try {
                assertEquals(0, co.flush().get(3, TimeUnit.SECONDS).attempted)
                rig.clock.nanos += 1_000_000_000; tasks.single().second.invoke()
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val closing = co.closeAndWait()
                assertSame(closing, co.closeAndWait()); assertFalse(closing.cancel(true)); assertFalse(closing.isDone)
                op.settlement.complete(ReplayTransportResponse(status, byteArrayOf()))
                closing.get(3, TimeUnit.SECONDS)
                assertTrue(executor.isShutdown)
                assertEquals(if (status == 401) "UNAUTHORIZED" else "FORBIDDEN",
                    ReplayDeliveryMetadata.decode(checkNotNull(rig.backing.replayRows[ReplayDeliveryMetadata.key(0)])).blockKind)
                assertEquals(0, co.flush().get().attempted)
            } finally { op.settlement.complete(ReplayTransportResponse(403, byteArrayOf())); co.close() }
        }
    }

    @Test fun `close exposes a held original pass failure and remains noncancelable`() {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val error = java.io.IOException("held queue failure")
        val queue = object : ReplayDeliveryQueue {
            override fun claim(): ReplayDeliveryClaim? { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); throw error }
            override fun nextWakeDelayMillis(): Long? = null
            override fun dispatch(claim: ReplayDeliveryClaim, transport: ReplayDeliveryTransport): ReplayTransportOperation? = null
            override fun commit(claim: ReplayDeliveryClaim, outcome: ReplayDeliveryOutcome) = ReplayDeliveryCommit.STALE
            override fun abandon(claim: ReplayDeliveryClaim) = Unit
        }
        val co = ReplayDeliveryCoordinator(queue, ReplayDeliveryTransport { _, _ -> error("No I/O") }, Executors.newSingleThreadExecutor(),
            ReplayDeliveryScheduler { _, _ -> ReplayDeliveryScheduledTask {} }, { 0 }, { 0 })
        val pass = co.flush(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        val closing = co.closeAndWait(); assertFalse(closing.isDone); assertFalse(closing.cancel(true)); release.countDown()
        for (future in listOf(pass, closing)) try { future.get(3, TimeUnit.SECONDS); fail("Failure was swallowed") }
        catch (caught: java.util.concurrent.ExecutionException) { assertSame(error, caught.cause) }
        assertSame(closing, co.closeAndWait())
    }

    private class Op : ReplayTransportOperation {
        override val settlement=SdkFuture<ReplayTransportResponse>()
        @Volatile var cancelled=false
        override fun cancel() { cancelled=true }
    }
    private fun ack(request: PreparedReplayRequest)=JSONObject().put("schemaVersion",2).put("requestId",request.requestId)
        .put("replayId",request.replayId).put("chunkId",request.chunkId).put("sequence",request.sequence).put("result","accepted").toString().toByteArray()
    private fun classify(body:ByteArray,request:PreparedReplayRequest)=ReplayResponseClassifier.classify(ReplayTransportResponse(200,body),request,0,1000)
    private fun error(status:Int,requestId:String)=JSONObject().put("schemaVersion",1).put("status",status).put("requestId",requestId)
        .put("code","request-refused").put("disposition",if(status==413) "retry-after-reduction" else "retryable").put("message","refused").toString().toByteArray()
    private class Rig(private val proven: Boolean = true, private val countLimit: Int = 100, private val allowProfile: Boolean = true, private val maximumExpiredSession: Boolean = false, private val supported: Set<String> = setOf(ReplayFixtures.GENERATION, "replay-v2-generation-2")) : AutoCloseable {
        val clock = Clock(); val worker = Worker(); val gate = V2ConfigAuthorityGate()
        var body = ReplayFixtures.resource("contracts/v2/fixtures/config-enabled.json")
        val source = V2ConfigSource("https://elu.dev", KEY, V2ConfigTransport { V2ConfigHttpResponse(200, body) }, clock)
        val driver = V2ConfigLifecycleDriver(source, gate::update, clock, Scheduler(), worker)
        val backing = FakeRuntimeQueueBacking()
        @Volatile var onReplayWrite: (() -> Unit)? = null
        @Volatile var onReplayStateRead: (() -> Unit)? = null
        @Volatile var profileCurrent = true
        @Volatile var profileChecks = 0
        @Volatile var afterTransaction: (() -> Unit)? = null
        @Volatile var ownerWall: Long? = null
        val namespace = RuntimeSiteNamespace.digest(KEY)
        var leaseCloses = 0
        var owner = open()
        var queue: ReplayDeliveryQueue? = null
        private fun open(): RuntimeQueueOwner = RuntimeQueueOwner.open("replay-" + UUID.randomUUID(), RuntimeQueueLimits(countLimit, MAX_RUNTIME_QUEUE_BYTES),
            databaseFactory = {
                val db = backing.connection()
                object : RuntimeQueueDatabase by db {
                    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
                        var wroteReplay = false
                        val result = db.transaction { tx -> block(object : RuntimeQueueTransaction by tx {
                            override fun readReplayRow(key: String): RuntimeReplayStoredRow? {
                                val result = tx.readReplayRow(key)
                                if (key == "state" && profileChecks > 0) onReplayStateRead?.also { onReplayStateRead = null }?.invoke()
                                return result
                            }
                            override fun putReplayRow(row: RuntimeReplayStoredRow) {
                                tx.putReplayRow(row); wroteReplay = true
                                onReplayWrite?.also { onReplayWrite = null }?.invoke()
                            }
                        }) }
                        if (wroteReplay) afterTransaction?.also { afterTransaction = null }?.invoke()
                        return result
                    }
                }
            }, legacyStateLoader = { initial(maximumExpiredSession) }, trustedSiteKey = KEY,
            leaseFactory = { RuntimeOwnershipLease { leaseCloses++ } },
            captureClock = object : RuntimeCaptureClock {
                override fun wallNowEpochMillis() = ownerWall ?: clock.wall
                override fun elapsedRealtimeNanos() = clock.nanos
            },
            // Synthetic existing browser fixture pair: storage tests only, no native proof claim.
            readbackProvenReplayTransports = if (proven) setOf(PAIR) else emptySet(),
            supportedReplayProtocolGenerations = supported,
            replayMaskingAdmission = ReplayMaskingAdmission { profile, config ->
                profileChecks++
                allowProfile && profileCurrent && profile.hash == ReplayFixtures.profile().hash &&
                    config.privacy.masking == V1ConfigJson.parseConfig(ReplayFixtures.resource("contracts/v2/fixtures/config-enabled.json")).privacy?.masking
            },
        ).get().also { it.bindConfigurationGate(gate).get() }
        fun activate() {
            owner.ensurePreparedReplayStorage().get(); driver.start(); worker.runNext()
            assertTrue(owner.submitCaptureAuthority(body, privacy()).get() is RuntimeCaptureAuthorityUpdateResult.Activated)
            reconcile()
        }
        fun privacy(): String {
            val config = V1ConfigJson.parseConfig(body)
            return PrivacyStateProjector.encode(PrivacyStateProjector.project(PrivacyProjectionInput(checkNotNull(config.privacy),
                checkNotNull(config.features), checkNotNull(config.replayCapabilities), owner.snapshot().get().state.identity,
                false, "2026-08-05T00:01:06.000Z", PrivacyReplayInput(true, true, true, 100, PAIR))))
        }
        fun request(change: (JSONObject) -> Unit = {}): PreparedReplayRequest {
            val privacy = JSONObject(privacy())
            return PreparedReplayRequest.parse(ReplayFixtures.bytes {
                it.getJSONObject("privacy").put("effectivePolicyHash", privacy.getString("effectivePolicyHash"))
                    .put("platformFallbackApplied", privacy.getJSONObject("effectiveMasking").getBoolean("platformFallbackApplied"))
                change(it)
            }, checkNotNull(V1ConfigJson.parseConfig(body).replayCapabilities?.replayProtocolGeneration))
        }
        fun append(request: PreparedReplayRequest = request()) = owner.appendPreparedReplay(request, ReplayFixtures.profile(), privacy()).get()
        fun rows() = owner.storedPreparedReplayForTesting().get()
        fun state() = backing.connection().use { it.transaction { tx -> checkNotNull(ReplayQueueStore.state(tx)) } }
        fun reconcile(retention: ReplayMaskingRetention = ReplayMaskingRetention { _, _ -> true }) = owner.reconcilePreparedReplay(privacy(), retention).get()
        fun renew(change: (JSONObject) -> Unit) {
            val json = JSONObject(body); json.put("issuedAt", "2026-08-05T00:01:05.000Z"); json.put("revision", "config-next")
            change(json); body = json.toString(); driver.onBackground(); driver.onForeground(); worker.runNext()
            owner.submitCaptureAuthority(body, privacy()).get()
        }
        fun delivery(): ReplayDeliveryQueue = queue ?: owner.openReplayDeliveryQueue(ReplayDeliveryPolicy(
            ReplayDeliveryPrivacy { config, identity, now -> PrivacyStateProjector.encode(PrivacyStateProjector.project(
                PrivacyProjectionInput(checkNotNull(config.privacy), checkNotNull(config.features), checkNotNull(config.replayCapabilities),
                    identity, false, RuntimeWallTimestamps.rfc3339(now), PrivacyReplayInput(false, true, false, 0, PAIR)))) },
            ReplayMaskingRetention { _, _ -> profileCurrent },
        )).get().also { queue = it }
        fun reopen() { owner.closeAsync().get(); openAfterClose() }
        fun openAfterClose() { owner = open(); queue = null; owner.submitCaptureAuthority(body, privacy()).get() }
        fun event() = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "event", "2026-08-05T00:01:06.000Z", emptyMap(), StandaloneRuntime.defaultVersions())
        override fun close() { runCatching { owner.closeAsync().get(3, TimeUnit.SECONDS) }; driver.close() }
    }
    private class Clock : V2ConfigClock {
        var wall = Instant.parse("2026-08-05T00:01:06Z").toEpochMilli(); var nanos = 1L
        override fun wallNowEpochMillis() = wall
        override fun monotonicNowNanos() = nanos
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
        val KEY = "elu_pk_live_" + "A".repeat(26)
        val PAIR = V1ReplayTransport("elu-browser-dom-v1", V1ReplayCompression.GZIP)
        fun initial(maximumExpiredSession: Boolean = false): PersistedCoreState {
            val chunk = ReplayFixtures.request().getJSONObject("chunk"); val identity = chunk.getJSONObject("identity")
            return PersistedCoreState(identity = IdentityState(revision = identity.getLong("revision"), contextRevision = chunk.getLong("contextRevision"),
                anonymousId = identity.getString("anonymousId"), userId = identity.getString("userId"), groups = emptyMap(), superProperties = emptyMap(),
                session = SessionState(chunk.getString("sessionId"), if (maximumExpiredSession) "2026-08-04T00:01:05.001Z" else "2026-08-05T00:01:00.000Z", "2026-08-05T00:01:05.000Z", 1800,
                    lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null), optedOut = false, updatedAt = "2026-08-05T00:01:05.000Z"),
                stream = StreamState(streamId = "stream_replay", nextSequence = 0), flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))
        }
    }
}
