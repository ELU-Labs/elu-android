package dev.elu.analytics.internal.runtime.delivery

import dev.elu.analytics.internal.runtime.RuntimeEventIdentity
import dev.elu.analytics.internal.runtime.RuntimeEventKind
import dev.elu.analytics.internal.runtime.RuntimeEventRecord
import dev.elu.analytics.internal.runtime.RuntimeMutationChange
import dev.elu.analytics.internal.runtime.RuntimeMutationEnvelope
import dev.elu.analytics.internal.runtime.RuntimeMutationRecord
import dev.elu.analytics.internal.runtime.RuntimeMutationSubject
import dev.elu.analytics.internal.runtime.RuntimePlatform
import dev.elu.analytics.internal.runtime.RuntimeQueuedRecord
import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
import dev.elu.analytics.internal.runtime.RuntimeRecordIdentity
import dev.elu.analytics.internal.runtime.RuntimeRecordKind
import dev.elu.analytics.internal.runtime.RuntimeRecordReference
import dev.elu.analytics.internal.runtime.RuntimeVersionComponent
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeBatchDeliveryCoordinatorTest {
    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        executor = Executors.newSingleThreadExecutor()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `packer selects largest homogeneous exact-byte prefix and stable request identity`() {
        val firstVersions = versions("0.1.0")
        val queued =
            listOf(
                mutation(0, START, firstVersions),
                event(1, plusSeconds(START, 1), firstVersions),
                event(2, plusSeconds(START, 2), versions("0.2.0")),
            )
        val sentAt = instant(plusSeconds(START, 60))
        val broad = V1BatchRequestCodec.packLargest(queued, sentAt, 100, 10_485_760) as BatchPackingResult.Packed

        assertEquals(listOf(0L, 1L), broad.request.records.map { it.sequence })
        assertEquals(broad.request.bodySize, broad.request.bodyBytes().size)
        val exact =
            V1BatchRequestCodec.packLargest(queued, sentAt, 100, broad.request.bodySize) as BatchPackingResult.Packed
        assertEquals(2, exact.request.records.size)
        val oneByteBelow =
            V1BatchRequestCodec.packLargest(queued, sentAt, 100, broad.request.bodySize - 1) as BatchPackingResult.Packed
        assertEquals(1, oneByteBelow.request.records.size)

        val laterAttempt = V1BatchRequestCodec.encodeExact(broad.request.records, instant(plusSeconds(START, 120)))
        assertEquals(broad.request.requestId, laterAttempt.requestId)
        assertNotEquals(broad.request.bodyBytes().decodeToString(), laterAttempt.bodyBytes().decodeToString())
        assertNotEquals(broad.request.requestId, V1BatchRequestCodec.encodeExact(listOf(queued.first()), sentAt).requestId)
        assertArrayEquals(
            broad.request.bodyBytes(),
            V1BatchRequestCodec.encodeExact(broad.request.records, sentAt).bodyBytes(),
        )
    }

    @Test
    fun `request identity matches the frozen cross-platform Unicode vector`() {
        val streamId = "stream_élü"
        val vectorVersions =
            RuntimeVersions(
                schemaVersion = 1,
                contractVersion = "1",
                platform = RuntimePlatform.BROWSER,
                runtime = RuntimeVersionComponent("javascript", "1.2.3"),
                facade = RuntimeVersionComponent("elu", "0.1.0"),
                build = "build-β",
            )
        val vectorEvent =
            RuntimeQueuedRecord.Event(
                RuntimeEventRecord(
                    eventId = "rec-event-1",
                    streamId = streamId,
                    sequence = 1,
                    contextRevision = 0,
                    kind = RuntimeEventKind.CAPTURE,
                    name = "event",
                    occurredAt = START,
                    identity = RuntimeEventIdentity("anon", null, 0),
                    sessionId = "session",
                    properties = emptyMap(),
                    groups = emptyMap(),
                    versions = vectorVersions,
                ),
                accountedBytes = 1,
            )
        val vectorMutation =
            RuntimeQueuedRecord.Mutation(
                RuntimeMutationEnvelope(
                    streamId = streamId,
                    versions = vectorVersions,
                    mutation =
                        RuntimeMutationRecord(
                            mutationId = "rec-mutation-2",
                            sequence = 2,
                            contextRevision = 0,
                            occurredAt = START,
                            subject = RuntimeMutationSubject("anon", null, 0),
                            change = RuntimeMutationChange.AssociateGroup("group", "key"),
                        ),
                ),
                accountedBytes = 1,
            )

        assertEquals(
            "request_82bb0b782bd26908053d215014afe39ce34b66560eec3d2e151d8e108eb6dc6b",
            V1BatchRequestCodec.requestId(streamId, vectorVersions, listOf(vectorEvent, vectorMutation)),
        )
    }

    @Test
    fun `single complete request over byte ceiling is terminally classified without truncation`() {
        val record = event(0, START, properties = mapOf("large" to "x".repeat(2_000)))
        val result = V1BatchRequestCodec.packLargest(listOf(record), instant(plusSeconds(START, 1)), 1, 1_024)

        assertTrue(result is BatchPackingResult.OversizedHead)
        assertEquals(referenceOf(record), (result as BatchPackingResult.OversizedHead).reference)
    }

    @Test
    fun `acknowledgement parser binds every outcome and rejects duplicate or sparse authority`() {
        val records = listOf(mutation(0, START), event(1, plusSeconds(START, 1)))
        val request = V1BatchRequestCodec.encodeExact(records, instant(plusSeconds(START, 2)))
        val retryable = acknowledgement(request, resolvedCount = 1)

        val parsed = V1BatchResponseCodec.parseAcknowledgement(retryable, request)
        assertEquals(listOf(referenceOf(records.first())), parsed.acknowledgement.references)
        assertTrue(parsed.hasRetryableRecords)

        val mismatch = JSONObject(retryable.decodeToString()).put("requestId", "request_wrong").toString().encodeToByteArray()
        assertThrows(BatchProtocolException::class.java) {
            V1BatchResponseCodec.parseAcknowledgement(mismatch, request)
        }
        val sparse = JSONObject(retryable.decodeToString()).apply {
            getJSONArray("outcomes").remove(1)
        }.toString().encodeToByteArray()
        assertThrows(BatchProtocolException::class.java) {
            V1BatchResponseCodec.parseAcknowledgement(sparse, request)
        }
        val duplicate =
            retryable.decodeToString().replaceFirst(
                "{",
                "{\"requestId\":\"${request.requestId}\",",
            ).encodeToByteArray()
        assertThrows(BatchProtocolException::class.java) {
            V1BatchResponseCodec.parseAcknowledgement(duplicate, request)
        }

        val threeRecords = records + event(2, plusSeconds(START, 2))
        val threeRequest = V1BatchRequestCodec.encodeExact(threeRecords, instant(plusSeconds(START, 3)))
        val prefixRetry = V1BatchResponseCodec.parseAcknowledgement(acknowledgement(threeRequest, 1), threeRequest)
        assertEquals(listOf(referenceOf(threeRecords.first())), prefixRetry.acknowledgement.references)
        assertTrue(prefixRetry.hasRetryableRecords)
    }

    @Test
    fun `transport error parser accepts 64 KiB exactly and rejects one byte over`() {
        val requestId = "request_test"
        val base = transportError(503, requestId)
        val exact = base + ByteArray(MAX_TRANSPORT_ERROR_BYTES - base.size) { ' '.code.toByte() }

        V1BatchResponseCodec.validateTransportError(exact, 503, requestId)
        assertThrows(BatchProtocolException::class.java) {
            V1BatchResponseCodec.validateTransportError(exact + ' '.code.toByte(), 503, requestId)
        }

        val queue = FakeDeliveryQueue(listOf(event(0, START)))
        val coordinator =
            coordinator(
                queue,
                RecordingTransport { raw ->
                    val id = JSONObject(raw.bodyBytes().decodeToString()).getString("requestId")
                    val valid = transportError(503, id)
                    val oversized = valid + ByteArray(MAX_TRANSPORT_ERROR_BYTES + 1 - valid.size) { ' '.code.toByte() }
                    BatchHTTPResponse(503, oversized)
                },
            )
        assertEquals(BatchDeliveryStop.PROTOCOL_FAILURE, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(listOf(0L), queue.records().map { it.sequence })
        coordinator.close()
    }

    @Test
    fun `validated success deletes the exact request while concurrent triggers coalesce`() {
        val records = listOf(event(0, START), event(1, plusSeconds(START, 1)))
        val queue = FakeDeliveryQueue(records)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport =
            RecordingTransport { request ->
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                val packed = requestFrom(request, records)
                BatchHTTPResponse(200, acknowledgement(packed, resolvedCount = records.size))
            }
        val coordinator = coordinator(queue, transport)

        val first = coordinator.trigger()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(BatchDeliveryStop.COALESCED, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        release.countDown()

        val result = first.get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.DRAINED, result.stop)
        assertEquals(1, result.networkRequests)
        assertEquals(2, result.resolvedRecords)
        assertTrue(queue.records().isEmpty())
        assertEquals(1, transport.requests.size)
        coordinator.close()
    }

    @Test
    fun `recursive 413 resolves ceiling halves in order and a one-record 413 deletes only that head`() {
        val records = (0L..3L).map { sequence -> event(sequence, plusSeconds(START, sequence)) }
        val queue = FakeDeliveryQueue(records)
        val observed = mutableListOf<List<Long>>()
        val transport =
            RecordingTransport { raw ->
                val body = JSONObject(raw.bodyBytes().decodeToString())
                val sequences = body.getJSONArray("records").sequences()
                observed += sequences
                if (sequences.size > 1) {
                    BatchHTTPResponse(413, transportError(413, body.getString("requestId")))
                } else {
                    val packed = requestFrom(raw, queue.records().filter { it.sequence in sequences })
                    BatchHTTPResponse(200, acknowledgement(packed, 1))
                }
            }
        val coordinator = coordinator(queue, transport)

        val result = coordinator.trigger().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.DRAINED, result.stop)
        assertEquals(
            listOf(listOf(0L, 1L, 2L, 3L), listOf(0L, 1L), listOf(0L), listOf(1L), listOf(2L, 3L), listOf(2L), listOf(3L)),
            observed,
        )
        assertTrue(queue.records().isEmpty())
        coordinator.close()

        val single = event(4, plusSeconds(START, 4))
        val singleQueue = FakeDeliveryQueue(listOf(single))
        val singleCoordinator =
            coordinator(
                singleQueue,
                RecordingTransport { request ->
                    BatchHTTPResponse(413, transportError(413, JSONObject(request.bodyBytes().decodeToString()).getString("requestId")))
                },
            )
        val singleResult = singleCoordinator.trigger().get(5, TimeUnit.SECONDS)
        assertEquals(1, singleResult.resolvedRecords)
        assertTrue(singleQueue.records().isEmpty())
        singleCoordinator.close()
    }

    @Test
    fun `recursive 413 stops before a row that expires between parent and child requests`() {
        val initialNow = plusSeconds(START, MAX_RUNTIME_RECORD_AGE_SECONDS.toLong() - 1)
        val records =
            listOf(
                event(0, plusSeconds(START, 10)),
                event(1, START),
                event(2, plusSeconds(START, 20)),
            )
        val queue = FakeDeliveryQueue(records)
        val clock = FakeClock(initialNow)
        val observed = mutableListOf<List<Long>>()
        var calls = 0
        val transport =
            RecordingTransport { raw ->
                val request = requestFrom(raw, queue.records())
                observed += request.records.map { it.sequence }
                calls += 1
                if (calls == 1) {
                    clock.wallMillis += 2_000L
                    BatchHTTPResponse(413, transportError(413, request.requestId))
                } else {
                    BatchHTTPResponse(200, acknowledgement(request, request.records.size))
                }
            }
        val coordinator = coordinator(queue, transport, clock)

        val result = coordinator.trigger().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.DRAINED, result.stop)
        assertEquals(listOf(listOf(0L, 1L, 2L), listOf(0L), listOf(2L)), observed)
        assertEquals(3, result.networkRequests)
        assertEquals(3, result.resolvedRecords)
        assertTrue(queue.records().isEmpty())
        coordinator.close()
    }

    @Test
    fun `recursive 413 reports resolved prefix exactly when eleven request budget stops later half`() {
        val records = (0L..7L).map { sequence -> event(sequence, plusSeconds(START, sequence)) }
        val queue = FakeDeliveryQueue(records)
        val observed = mutableListOf<List<Long>>()
        val transport =
            RecordingTransport { raw ->
                val body = JSONObject(raw.bodyBytes().decodeToString())
                observed += body.getJSONArray("records").sequences()
                BatchHTTPResponse(413, transportError(413, body.getString("requestId")))
            }
        val coordinator = coordinator(queue, transport)

        val result = coordinator.trigger().get(5, TimeUnit.SECONDS)

        assertEquals(BatchDeliveryStop.BOUNDED, result.stop)
        assertEquals(11, result.networkRequests)
        assertEquals(5, result.resolvedRecords)
        assertEquals(records.size - queue.records().size, result.resolvedRecords)
        assertEquals(listOf(5L, 6L, 7L), queue.records().map { it.sequence })
        assertEquals(
            listOf(
                (0L..7L).toList(),
                (0L..3L).toList(),
                listOf(0L, 1L),
                listOf(0L),
                listOf(1L),
                listOf(2L, 3L),
                listOf(2L),
                listOf(3L),
                (4L..7L).toList(),
                listOf(4L, 5L),
                listOf(4L),
            ),
            observed,
        )
        assertEquals(11, transport.requests.size)
        coordinator.close()
    }

    @Test
    fun `one thousand record 413 reduction reaches and resolves an exact head within eleven requests`() {
        val records = (0L until 1_000L).map { sequence -> event(sequence, START) }
        val queue = FakeDeliveryQueue(records)
        val observedSizes = mutableListOf<Int>()
        val transport =
            RecordingTransport { raw ->
                val body = JSONObject(raw.bodyBytes().decodeToString())
                observedSizes += body.getJSONArray("records").length()
                BatchHTTPResponse(413, transportError(413, body.getString("requestId")))
            }
        val coordinator = coordinator(queue, transport)

        val result = coordinator.trigger().get(5, TimeUnit.SECONDS)

        assertEquals(BatchDeliveryStop.BOUNDED, result.stop)
        assertEquals(11, result.networkRequests)
        assertEquals(1, result.resolvedRecords)
        assertEquals(999, queue.records().size)
        assertEquals(1L, queue.records().first().sequence)
        assertEquals(records.size - queue.records().size, result.resolvedRecords)
        assertEquals(listOf(1_000, 500, 250, 125, 63, 32, 16, 8, 4, 2, 1), observedSizes)
        coordinator.close()
    }

    @Test
    fun `malformed successful response preserves queue and blocks the unchanged request`() {
        val record = event(0, START)
        val queue = FakeDeliveryQueue(listOf(record))
        val transport = RecordingTransport { BatchHTTPResponse(200, "{}".encodeToByteArray()) }
        val coordinator = coordinator(queue, transport)

        assertEquals(BatchDeliveryStop.PROTOCOL_FAILURE, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(1, queue.records().size)
        assertEquals(BatchDeliveryStop.PROTOCOL_FAILURE, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(1, transport.requests.size)
        coordinator.close()
    }

    @Test
    fun `record becomes terminal exactly at seven-day boundary without network`() {
        val beforeClock = FakeClock(plusMillis(plusSeconds(START, MAX_RUNTIME_RECORD_AGE_SECONDS.toLong()), -1))
        val beforeQueue = FakeDeliveryQueue(listOf(event(0, START)))
        val beforeTransport = acceptingTransport(beforeQueue)
        val before = coordinator(beforeQueue, beforeTransport, beforeClock)
        assertEquals(BatchDeliveryStop.DRAINED, before.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(1, beforeTransport.requests.size)
        before.close()

        val boundaryClock = FakeClock(plusSeconds(START, MAX_RUNTIME_RECORD_AGE_SECONDS.toLong()))
        val boundaryQueue = FakeDeliveryQueue(listOf(event(0, START)))
        val boundaryTransport = RecordingTransport { throw AssertionError("aged record must not reach transport") }
        val boundary = coordinator(boundaryQueue, boundaryTransport, boundaryClock)
        val result = boundary.trigger().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.DRAINED, result.stop)
        assertEquals(1, result.resolvedRecords)
        assertTrue(boundaryTransport.requests.isEmpty())
        boundary.close()
    }

    @Test
    fun `fresh expired fresh queue sends only safe prefixes and resumes after exact head deletion`() {
        val now = plusSeconds(START, MAX_RUNTIME_RECORD_AGE_SECONDS.toLong())
        val records =
            listOf(
                event(0, plusSeconds(START, 1)),
                event(1, START),
                event(2, plusSeconds(START, 2)),
            )
        val queue = FakeDeliveryQueue(records)
        val observed = mutableListOf<List<Long>>()
        val transport =
            RecordingTransport { raw ->
                observed += JSONObject(raw.bodyBytes().decodeToString()).getJSONArray("records").sequences()
                val request = requestFrom(raw, queue.records())
                BatchHTTPResponse(200, acknowledgement(request, request.records.size))
            }
        val coordinator = coordinator(queue, transport, FakeClock(now))

        val result = coordinator.trigger().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.DRAINED, result.stop)
        assertEquals(listOf(listOf(0L), listOf(2L)), observed)
        assertEquals(2, result.networkRequests)
        assertEquals(3, result.resolvedRecords)
        assertTrue(queue.records().isEmpty())
        coordinator.close()
    }

    @Test
    fun `retry wait wakes at the seven-day boundary without another network request`() {
        val record = event(0, START)
        val queue = FakeDeliveryQueue(listOf(record))
        val clock = FakeClock(plusSeconds(START, MAX_RUNTIME_RECORD_AGE_SECONDS.toLong() - 1))
        val scheduler = FakeScheduler()
        val transport =
            RecordingTransport { raw ->
                val request = requestFrom(raw, queue.records())
                BatchHTTPResponse(429, transportError(429, request.requestId), retryAfter = "30")
            }
        val coordinator = coordinator(queue, transport, clock, scheduler)

        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(1_000L, scheduler.entries.single().delayMillis)
        clock.wallMillis += 1_000L
        clock.monotonicNanos += TimeUnit.SECONDS.toNanos(1)
        scheduler.runNext()

        awaitCondition { queue.records().isEmpty() }
        assertEquals(1, transport.requests.size)
        coordinator.close()
    }

    @Test
    fun `retry after uses monotonic deadline coalesces triggers and preserves request identity`() {
        val record = event(0, START)
        val queue = FakeDeliveryQueue(listOf(record))
        val clock = FakeClock(plusSeconds(START, 10))
        val scheduler = FakeScheduler()
        var calls = 0
        val requestIds = mutableListOf<String>()
        val transport =
            RecordingTransport { raw ->
                val request = requestFrom(raw, queue.records())
                requestIds += request.requestId
                calls += 1
                if (calls == 1) {
                    BatchHTTPResponse(429, transportError(429, request.requestId), retryAfter = "30")
                } else {
                    BatchHTTPResponse(200, acknowledgement(request, 1))
                }
            }
        val coordinator = coordinator(queue, transport, clock, scheduler)

        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(30_000L, scheduler.entries.single().delayMillis)
        assertEquals(BatchDeliveryStop.COALESCED, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)

        clock.wallMillis -= 120_000L
        clock.monotonicNanos += TimeUnit.SECONDS.toNanos(29)
        scheduler.runNext()
        assertEquals(1, calls)
        assertEquals(1_000L, scheduler.entries.single().delayMillis)

        clock.monotonicNanos += TimeUnit.SECONDS.toNanos(1)
        scheduler.runNext()
        awaitCondition { calls == 2 && queue.records().isEmpty() }
        assertEquals(listOf(requestIds.first(), requestIds.first()), requestIds)
        coordinator.close()
    }

    @Test
    fun `retry keeps the original request prefix when later records are appended`() {
        val firstRecord = event(0, START)
        val queue = FakeDeliveryQueue(listOf(firstRecord))
        val clock = FakeClock(plusSeconds(START, 10))
        val scheduler = FakeScheduler()
        val requestIds = mutableListOf<String>()
        val requestSequences = mutableListOf<List<Long>>()
        var calls = 0
        val transport =
            RecordingTransport { raw ->
                val body = JSONObject(raw.bodyBytes().decodeToString())
                requestIds += body.getString("requestId")
                requestSequences += body.getJSONArray("records").sequences()
                val request = requestFrom(raw, queue.records())
                calls += 1
                if (calls == 1) {
                    BatchHTTPResponse(429, transportError(429, request.requestId), retryAfter = "1")
                } else {
                    BatchHTTPResponse(200, acknowledgement(request, request.records.size))
                }
            }
        val coordinator = coordinator(queue, transport, clock, scheduler)

        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        queue.append(event(1, plusSeconds(START, 1)))
        clock.monotonicNanos += TimeUnit.SECONDS.toNanos(1)
        scheduler.runNext()

        awaitCondition { calls == 3 && queue.records().isEmpty() }
        assertEquals(listOf(listOf(0L), listOf(0L), listOf(1L)), requestSequences)
        assertEquals(requestIds[0], requestIds[1])
        assertNotEquals(requestIds[1], requestIds[2])
        coordinator.close()
    }

    @Test
    fun `retry cuts at a newly expired middle row then resumes with the later fresh row`() {
        val initialNow = plusSeconds(START, MAX_RUNTIME_RECORD_AGE_SECONDS.toLong() - 1)
        val records =
            listOf(
                event(0, plusSeconds(START, 10)),
                event(1, START),
                event(2, plusSeconds(START, 20)),
            )
        val queue = FakeDeliveryQueue(records)
        val clock = FakeClock(initialNow)
        val scheduler = FakeScheduler()
        val observed = mutableListOf<List<Long>>()
        var calls = 0
        val transport =
            RecordingTransport { raw ->
                val sequences = JSONObject(raw.bodyBytes().decodeToString()).getJSONArray("records").sequences()
                observed += sequences
                val request = requestFrom(raw, queue.records())
                calls += 1
                if (calls == 1) {
                    BatchHTTPResponse(429, transportError(429, request.requestId), retryAfter = "2")
                } else {
                    BatchHTTPResponse(200, acknowledgement(request, request.records.size))
                }
            }
        val coordinator = coordinator(queue, transport, clock, scheduler)

        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(2_000L, scheduler.entries.single().delayMillis)
        clock.wallMillis += 2_000L
        clock.monotonicNanos += TimeUnit.SECONDS.toNanos(2)
        scheduler.runNext()

        awaitCondition { calls == 3 && queue.records().isEmpty() }
        assertEquals(listOf(listOf(0L, 1L, 2L), listOf(0L), listOf(2L)), observed)
        coordinator.close()
    }

    @Test
    fun `monotonic retry deadline remains correct across signed nano time wrap`() {
        val record = event(0, START)
        val queue = FakeDeliveryQueue(listOf(record))
        val clock = FakeClock(plusSeconds(START, 10)).apply {
            monotonicNanos = Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(500)
        }
        val scheduler = FakeScheduler()
        var calls = 0
        val transport =
            RecordingTransport { raw ->
                val request = requestFrom(raw, queue.records())
                calls += 1
                if (calls == 1) {
                    BatchHTTPResponse(429, transportError(429, request.requestId), retryAfter = "2")
                } else {
                    BatchHTTPResponse(200, acknowledgement(request, request.records.size))
                }
            }
        val coordinator = coordinator(queue, transport, clock, scheduler)

        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(2_000L, scheduler.entries.single().delayMillis)
        clock.monotonicNanos += TimeUnit.SECONDS.toNanos(1)
        scheduler.runNext()
        assertEquals(1, calls)
        assertEquals(1_000L, scheduler.entries.single().delayMillis)
        clock.monotonicNanos += TimeUnit.SECONDS.toNanos(1)
        scheduler.runNext()
        awaitCondition { calls == 2 && queue.records().isEmpty() }
        coordinator.close()
    }

    @Test
    fun `retryable 2xx honors Retry-After while malformed or resolved headers delete nothing`() {
        val records = listOf(event(0, START), event(1, plusSeconds(START, 1)))
        val retryQueue = FakeDeliveryQueue(records)
        val retryScheduler = FakeScheduler()
        val retryCoordinator =
            coordinator(
                retryQueue,
                RecordingTransport { raw ->
                    val request = requestFrom(raw, retryQueue.records())
                    BatchHTTPResponse(200, acknowledgement(request, resolvedCount = 1), retryAfter = "30")
                },
                scheduler = retryScheduler,
            )
        val retryResult = retryCoordinator.trigger().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, retryResult.stop)
        assertEquals(1, retryResult.resolvedRecords)
        assertEquals(listOf(1L), retryQueue.records().map { it.sequence })
        assertEquals(30_000L, retryScheduler.entries.single().delayMillis)
        retryCoordinator.close()

        listOf(
            1 to "30.5",
            2 to "30",
        ).forEach { (resolvedCount, retryAfter) ->
            val queue = FakeDeliveryQueue(records)
            val transport =
                RecordingTransport { raw ->
                    val request = requestFrom(raw, queue.records())
                    BatchHTTPResponse(200, acknowledgement(request, resolvedCount), retryAfter)
                }
            val coordinator = coordinator(queue, transport)
            assertEquals(BatchDeliveryStop.PROTOCOL_FAILURE, coordinator.trigger().get(5, TimeUnit.SECONDS).stop)
            assertEquals(listOf(0L, 1L), queue.records().map { it.sequence })
            coordinator.close()
        }
    }

    @Test
    fun `retry wait never schedules through expiry and cancels when wall time expires early`() {
        val record = event(0, START)
        val tooLateQueue = FakeDeliveryQueue(listOf(record))
        val tooLateClock = FakeClock(plusSeconds(START, 10))
        val tooLateScheduler = FakeScheduler()
        val tooLate =
            coordinator(
                tooLateQueue,
                RecordingTransport { raw ->
                    val request = requestFrom(raw, tooLateQueue.records())
                    BatchHTTPResponse(429, transportError(429, request.requestId), retryAfter = "30")
                },
                tooLateClock,
                tooLateScheduler,
                expiresAt = plusSeconds(START, 40),
            )
        assertEquals(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE, tooLate.trigger().get(5, TimeUnit.SECONDS).stop)
        assertTrue(tooLateScheduler.entries.isEmpty())
        assertEquals(1, tooLateQueue.records().size)
        assertEquals(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE, tooLate.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(1, tooLateQueue.records().size)
        tooLate.close()

        val jumpQueue = FakeDeliveryQueue(listOf(record))
        val jumpClock = FakeClock(plusSeconds(START, 10))
        val jumpScheduler = FakeScheduler()
        val jumpTransport =
            RecordingTransport { raw ->
                val request = requestFrom(raw, jumpQueue.records())
                BatchHTTPResponse(429, transportError(429, request.requestId), retryAfter = "30")
            }
        val jump =
            coordinator(
                jumpQueue,
                jumpTransport,
                jumpClock,
                jumpScheduler,
                expiresAt = plusSeconds(START, 60),
            )
        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, jump.trigger().get(5, TimeUnit.SECONDS).stop)
        jumpClock.wallMillis = RuntimeRecordCodec.timestampToEpochMillisFloor(plusSeconds(START, 60))
        jumpScheduler.runNext()
        assertTrue(jumpScheduler.entries.isEmpty())
        assertEquals(1, jumpTransport.requests.size)
        assertEquals(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE, jump.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(1, jumpQueue.records().size)
        jump.close()
    }

    @Test
    fun `network and server failures back off while authorization errors preserve and latch`() {
        val record = event(0, START)
        val networkQueue = FakeDeliveryQueue(listOf(record))
        val networkScheduler = FakeScheduler()
        val network =
            coordinator(
                networkQueue,
                BatchHTTPTransport { throw IOException("offline") },
                scheduler = networkScheduler,
            )
        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, network.trigger().get(5, TimeUnit.SECONDS).stop)
        assertTrue(networkScheduler.entries.single().delayMillis in 500..999)
        assertEquals(1, networkQueue.records().size)
        network.close()

        val serverQueue = FakeDeliveryQueue(listOf(record))
        val serverScheduler = FakeScheduler()
        val server =
            coordinator(
                serverQueue,
                RecordingTransport { raw ->
                    val id = JSONObject(raw.bodyBytes().decodeToString()).getString("requestId")
                    BatchHTTPResponse(503, transportError(503, id))
                },
                scheduler = serverScheduler,
            )
        assertEquals(BatchDeliveryStop.RETRY_SCHEDULED, server.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(500L, serverScheduler.entries.single().delayMillis)
        assertEquals(1, serverQueue.records().size)
        server.close()

        val forbiddenQueue = FakeDeliveryQueue(listOf(record))
        val forbiddenTransport =
            RecordingTransport { request ->
                val id = JSONObject(request.bodyBytes().decodeToString()).getString("requestId")
                BatchHTTPResponse(403, transportError(403, id))
            }
        val forbidden = coordinator(forbiddenQueue, forbiddenTransport)
        assertEquals(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE, forbidden.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(BatchDeliveryStop.AUTHORIZATION_UNAVAILABLE, forbidden.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(1, forbiddenTransport.requests.size)
        assertEquals(1, forbiddenQueue.records().size)
        forbidden.close()
    }

    @Test
    fun `one pass enforces exact eleven network and sixty four local terminal caps`() {
        val networkRecords =
            (0L..11L).map { sequence ->
                event(sequence, START, versions = versions("0.1.$sequence"))
            }
        val networkQueue = FakeDeliveryQueue(networkRecords)
        val networkTransport = acceptingTransport(networkQueue)
        val networkCoordinator = coordinator(networkQueue, networkTransport)

        val boundedNetwork = networkCoordinator.trigger().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.BOUNDED, boundedNetwork.stop)
        assertEquals(11, boundedNetwork.networkRequests)
        assertEquals(11, boundedNetwork.resolvedRecords)
        assertEquals(listOf(11L), networkQueue.records().map { it.sequence })
        assertEquals(BatchDeliveryStop.DRAINED, networkCoordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertEquals(12, networkTransport.requests.size)
        networkCoordinator.close()

        val agedRecords = (0L..64L).map { sequence -> event(sequence, START) }
        val localQueue = FakeDeliveryQueue(agedRecords)
        val beforeBoundary = plusMillis(plusSeconds(START, MAX_RUNTIME_RECORD_AGE_SECONDS.toLong()), -1)
        val atBoundary = plusSeconds(START, MAX_RUNTIME_RECORD_AGE_SECONDS.toLong())
        val transitionClock = ScriptedClock(listOf(instant(beforeBoundary), instant(atBoundary)))
        val localTransport = RecordingTransport { throw AssertionError("aged records must not reach transport") }
        val localCoordinator = coordinator(localQueue, localTransport, transitionClock)

        val boundedLocal = localCoordinator.trigger().get(5, TimeUnit.SECONDS)
        assertEquals(BatchDeliveryStop.BOUNDED, boundedLocal.stop)
        assertEquals(0, boundedLocal.networkRequests)
        assertEquals(64, boundedLocal.resolvedRecords)
        assertEquals(listOf(64L), localQueue.records().map { it.sequence })
        assertEquals(BatchDeliveryStop.DRAINED, localCoordinator.trigger().get(5, TimeUnit.SECONDS).stop)
        assertTrue(localQueue.records().isEmpty())
        assertTrue(localTransport.requests.isEmpty())
        localCoordinator.close()
    }

    @Test
    fun `retry after parser handles delta and HTTP date without allowing past dates to shorten backoff`() {
        val response = instant("2030-01-08T00:00:00.000Z")
        assertEquals(30_000L, RetryAfterParser.parseDelayMillis("30", response.epochMillis))
        assertEquals(
            60_000L,
            RetryAfterParser.parseDelayMillis("Tue, 08 Jan 2030 00:01:00 GMT", response.epochMillis),
        )
        assertEquals(0L, RetryAfterParser.parseDelayMillis("Tue, 08 Jan 2030 00:00:00 GMT", response.epochMillis))
        assertEquals(30_000L, RetryAfterParser.parseDelayMillis("\t 30 \r\n", response.epochMillis))
        assertEquals(
            60_000L,
            RetryAfterParser.parseDelayMillis("  Tue, 08 Jan 2030 00:01:00 GMT\n", response.epochMillis),
        )

        val canonicalNow = instant("1994-11-06T08:49:07.000Z")
        listOf(
            "Sun, 06 Nov 1994 08:49:37 GMT",
            "Sunday, 06-Nov-94 08:49:37 GMT",
            "Sun Nov  6 08:49:37 1994",
        ).forEach { value ->
            assertEquals(30_000L, RetryAfterParser.parseDelayMillis(value, canonicalNow.epochMillis))
        }
        listOf(
            "Mon, 06 Nov 1994 08:49:37 GMT",
            "Monday, 06-Nov-94 08:49:37 GMT",
            "Mon Nov  6 08:49:37 1994",
            "Sun, 31 Feb 1994 08:49:37 GMT",
        ).forEach { value ->
            assertThrows(BatchProtocolException::class.java) {
                RetryAfterParser.parseDelayMillis(value, canonicalNow.epochMillis)
            }
        }
    }

    private fun coordinator(
        queue: FakeDeliveryQueue,
        transport: BatchHTTPTransport,
        clock: BatchDeliveryClock = FakeClock(plusSeconds(START, 60)),
        scheduler: FakeScheduler = FakeScheduler(),
        expiresAt: String = plusSeconds(START, 2_000_000),
    ): RuntimeBatchDeliveryCoordinator =
        RuntimeBatchDeliveryCoordinator(
            authorization =
                V1BatchAuthorizationSnapshot(
                    "elu_pk_test",
                    URI("https://ingest.elu.dev/v1/events"),
                    expiresAt,
                    1_000,
                    10_485_760,
                ),
            queue = queue,
            transport = transport,
            clock = clock,
            scheduler = scheduler,
            jitter = BatchJitterSource { 0.0 },
            executor = executor,
        )

    private fun acceptingTransport(queue: FakeDeliveryQueue): RecordingTransport =
        RecordingTransport { raw ->
            val request = requestFrom(raw, queue.records())
            BatchHTTPResponse(200, acknowledgement(request, request.records.size))
        }

    private fun requestFrom(
        request: BatchHTTPRequest,
        available: List<RuntimeQueuedRecord>,
    ): BatchPackedRequest {
        val json = JSONObject(request.bodyBytes().decodeToString())
        val sequences = json.getJSONArray("records").sequences()
        val selected = sequences.map { sequence -> available.single { it.sequence == sequence } }
        return V1BatchRequestCodec.encodeExact(selected, instant(json.getString("sentAt"))).also {
            assertEquals(json.getString("requestId"), it.requestId)
        }
    }

    private fun acknowledgement(
        request: BatchPackedRequest,
        resolvedCount: Int,
    ): ByteArray {
        val outcomes = JSONArray()
        val reportedCount = if (resolvedCount < request.records.size) resolvedCount + 1 else request.records.size
        request.records.take(reportedCount).forEachIndexed { index, record ->
            val resolved = index < resolvedCount
            outcomes.put(
                JSONObject()
                    .put("sequence", record.sequence)
                    .put("recordId", record.recordId)
                    .put("kind", record.kind.wireValue)
                    .put("result", if (resolved) "accepted" else "retryable")
                    .apply { if (!resolved) put("code", "temporarily-unavailable") },
            )
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("requestId", request.requestId)
            .put("streamId", request.streamId)
            .put(
                "resolvedThroughSequence",
                request.records.getOrNull(resolvedCount - 1)?.sequence ?: JSONObject.NULL,
            ).put(
                "retryFromSequence",
                request.records.getOrNull(resolvedCount)?.sequence ?: JSONObject.NULL,
            ).put("outcomes", outcomes)
            .toString()
            .encodeToByteArray()
    }

    private fun transportError(
        status: Int,
        requestId: String,
    ): ByteArray {
        val disposition =
            when {
                status == 401 || status == 403 -> "permanent"
                status == 413 -> "retry-after-reduction"
                else -> "retryable"
            }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("status", status)
            .put("code", if (status == 413) "payload-too-large" else "request-failed")
            .put("disposition", disposition)
            .put("message", "Request could not be processed.")
            .put("requestId", requestId)
            .toString()
            .encodeToByteArray()
    }

    private fun event(
        sequence: Long,
        occurredAt: String,
        versions: RuntimeVersions = versions(),
        properties: Map<String, Any?> = emptyMap(),
    ): RuntimeQueuedRecord.Event {
        val record =
            RuntimeEventRecord(
                eventId = RuntimeRecordIdentity.recordId(STREAM_ID, sequence, RuntimeRecordKind.EVENT),
                streamId = STREAM_ID,
                sequence = sequence,
                contextRevision = 0,
                kind = RuntimeEventKind.CAPTURE,
                name = "event-$sequence",
                occurredAt = occurredAt,
                identity = RuntimeEventIdentity("anon_test", null, 0),
                sessionId = "session_test",
                properties = properties,
                groups = emptyMap(),
                versions = versions,
            )
        return RuntimeQueuedRecord.Event(record, RuntimeRecordCodec.encodeBatchRecord(record).size)
    }

    private fun mutation(
        sequence: Long,
        occurredAt: String,
        versions: RuntimeVersions = versions(),
    ): RuntimeQueuedRecord.Mutation {
        val envelope =
            RuntimeMutationEnvelope(
                streamId = STREAM_ID,
                versions = versions,
                mutation =
                    RuntimeMutationRecord(
                        mutationId = RuntimeRecordIdentity.recordId(STREAM_ID, sequence, RuntimeRecordKind.MUTATION),
                        sequence = sequence,
                        contextRevision = 0,
                        occurredAt = occurredAt,
                        subject = RuntimeMutationSubject("anon_test", null, 0),
                        change = RuntimeMutationChange.AssociateGroup("organization", "org_test"),
                    ),
            )
        return RuntimeQueuedRecord.Mutation(envelope, RuntimeRecordCodec.encodeBatchRecord(envelope).size)
    }

    private fun versions(version: String = "0.1.0"): RuntimeVersions =
        RuntimeVersions(
            platform = RuntimePlatform.ANDROID,
            runtime = RuntimeVersionComponent("elu-android", version),
            facade = RuntimeVersionComponent("Elu", version),
            build = "test",
        )

    private fun instant(value: String): BatchWallInstant =
        BatchWallInstant(RuntimeRecordCodec.timestampToEpochMillisFloor(value), value)

    private fun plusSeconds(
        value: String,
        seconds: Long,
    ): String = plusMillis(value, Math.multiplyExact(seconds, 1_000L))

    private fun plusMillis(
        value: String,
        milliseconds: Long,
    ): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(Math.addExact(RuntimeRecordCodec.timestampToEpochMillisFloor(value), milliseconds)))
    }

    private fun awaitCondition(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition was not satisfied", condition())
    }

    private class FakeDeliveryQueue(initial: List<RuntimeQueuedRecord>) : RuntimeDeliveryQueue {
        private val records = initial.toMutableList()

        @Synchronized
        override fun peek(
            maximumCount: Int,
            maximumBytes: Long,
        ): List<RuntimeQueuedRecord> {
            val out = mutableListOf<RuntimeQueuedRecord>()
            var bytes = 0L
            for (record in records.take(maximumCount)) {
                if (bytes + record.accountedBytes > maximumBytes) break
                out += record
                bytes += record.accountedBytes
            }
            return out
        }

        @Synchronized
        override fun acknowledge(acknowledgement: dev.elu.analytics.internal.runtime.RuntimeAcknowledgement): DeliveryQueueAcknowledgement {
            if (acknowledgement.references.isEmpty()) return DeliveryQueueAcknowledgement.Empty
            if (records.isEmpty() || acknowledgement.references.last().sequence < records.first().sequence) {
                return DeliveryQueueAcknowledgement.AlreadyApplied
            }
            acknowledgement.references.forEachIndexed { index, reference ->
                val record = records.getOrNull(index) ?: error("acknowledgement exceeds queue")
                check(reference == referenceOf(record)) { "acknowledgement is not the exact queue prefix" }
            }
            repeat(acknowledgement.references.size) { records.removeAt(0) }
            return DeliveryQueueAcknowledgement.Deleted(acknowledgement.references.size)
        }

        @Synchronized
        fun records(): List<RuntimeQueuedRecord> = records.toList()

        @Synchronized
        fun append(record: RuntimeQueuedRecord) {
            records += record
        }
    }

    private class RecordingTransport(
        private val handler: (BatchHTTPRequest) -> BatchHTTPResponse,
    ) : BatchHTTPTransport {
        val requests = mutableListOf<BatchHTTPRequest>()

        @Synchronized
        override fun execute(request: BatchHTTPRequest): BatchHTTPResponse {
            requests += request
            return handler(request)
        }
    }

    private class FakeClock(initial: String) : BatchDeliveryClock {
        var wallMillis: Long = RuntimeRecordCodec.timestampToEpochMillisFloor(initial)
        var monotonicNanos: Long = 0

        override fun wallNow(): BatchWallInstant {
            val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return BatchWallInstant(wallMillis, formatter.format(Date(wallMillis)))
        }

        override fun monotonicNowNanos(): Long = monotonicNanos
    }

    private class ScriptedClock(
        private val wallInstants: List<BatchWallInstant>,
    ) : BatchDeliveryClock {
        private var index = 0

        init {
            require(wallInstants.isNotEmpty())
        }

        override fun wallNow(): BatchWallInstant =
            wallInstants[minOf(index++, wallInstants.lastIndex)]

        override fun monotonicNowNanos(): Long = 0
    }

    private class FakeScheduler : BatchRetryScheduler {
        data class Entry(
            val delayMillis: Long,
            val task: Runnable,
            var cancelled: Boolean = false,
        )

        val entries = mutableListOf<Entry>()

        @Synchronized
        override fun schedule(
            delayMillis: Long,
            task: Runnable,
        ): BatchScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return BatchScheduledTask { entry.cancelled = true }
        }

        fun runNext() {
            val entry = synchronized(this) { entries.removeAt(0) }
            if (!entry.cancelled) entry.task.run()
        }
    }

    private fun JSONArray.sequences(): List<Long> =
        (0 until length()).map { index ->
            val record = getJSONObject(index)
            val payload = if (record.getString("kind") == "event") record.getJSONObject("event") else record.getJSONObject("mutation")
            payload.getLong("sequence")
        }

    private companion object {
        const val STREAM_ID = "stream_test"
        const val START = "2030-01-01T00:00:00.000Z"
    }
}
