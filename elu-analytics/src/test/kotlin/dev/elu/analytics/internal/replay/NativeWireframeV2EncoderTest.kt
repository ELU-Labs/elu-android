package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import java.util.UUID
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeWireframeV2EncoderTest {
    private val a = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val b = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val time = 1_788_883_200_000L
    private fun node(id: UUID = a, kind: NativeMaskedKind = NativeMaskedKind.Rectangle, x: Double = 10.0) =
        NativeMaskedNode(id, kind, NativeRect(x, 10.0, 80.0, 80.0), NativeRect(x, 10.0, 80.0, 80.0))
    private fun frame(ordinal: Long, at: Long = time, nodes: List<NativeMaskedNode> = listOf(node())) =
        NativeReplayV2Geometry(NativeMaskedSnapshot(ordinal, at, NativeViewport(300, 200), nodes))
    private fun point(at: Long = time, id: UUID = a, x: Int = 20, y: Int = 20) = NativeReplayTouchPoint(id, x, y, at)
    private fun encoder(events: Int = 10_000) = NativeWireframeV2Encoder(
        NativeWireframeEncoder.Limits(events = events), maskingProfile = NativeMaskingProfile.sensitiveMask())
    private fun json(chunk: NativeEncodedChunk) = JSONArray(chunk.bytes.toString(Charsets.UTF_8))
    private fun data(chunk: NativeEncodedChunk, index: Int = 0) = json(chunk).getJSONObject(index).getJSONObject("data")
    private fun fail(reason: NativeInteractionFailure, block: () -> Unit) =
        assertEquals(reason, assertThrows(NativeInteractionException::class.java) { block() }.failure)

    @Test fun `exact v2 discriminator and pointer grammar expose wire ids not local identities`() {
        val e = encoder(); val initial = e.encode(listOf(frame(0)))
        assertEquals(2, initial.eventCount); assertEquals(2, json(initial).getJSONObject(1).getInt("type"))
        assertEquals(NativeWireframeV2Encoder.CODEC, data(initial).getString("codec"))
        assertEquals(setOf("codec", "width", "height"), data(initial).keySet())
        val start = e.encode(listOf(NativeReplayInteraction.Start(point())))
        assertEquals(7, data(start).getInt("type")); assertEquals(2, data(start).getInt("source"))
        assertEquals(10_000_001L, data(start).getLong("id")); assertEquals(2, data(start).getInt("pointerType"))
        val end = e.encode(listOf(NativeReplayInteraction.End(point(time + 100))))
        assertEquals(9, data(end).getInt("type")); assertEquals(20, data(end).getInt("x"))
        val text = start.bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains(a.toString()))
        assertArrayEquals(start.bytes, V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(text)))
    }

    @Test fun `root cancel is coordinate free and initial gesture ordering is closed`() {
        val e = encoder()
        fail(NativeInteractionFailure.GESTURE) { e.encode(listOf(NativeReplayInteraction.Start(point()))) }
        fail(NativeInteractionFailure.GESTURE) { e.encode(listOf(frame(0), NativeReplayInteraction.Start(point()))) }
        assertEquals(0L, e.encode(listOf(frame(0))).sequence)
        fail(NativeInteractionFailure.GESTURE) { e.encode(listOf(NativeReplayInteraction.Cancel(time))) }
        e.encode(listOf(NativeReplayInteraction.Start(point())))
        fail(NativeInteractionFailure.GESTURE) { e.encode(listOf(NativeReplayInteraction.Start(point()))) }
        val cancel = e.encode(listOf(NativeReplayInteraction.Cancel(time + 1)))
        assertEquals(setOf("source", "type", "id", "pointerType"), data(cancel).keySet())
        assertEquals(10_000_000L, data(cancel).getLong("id")); assertEquals(10, data(cancel).getInt("type"))
        fail(NativeInteractionFailure.GESTURE) { e.encode(listOf(NativeReplayInteraction.End(point(time + 2)))) }
    }

    @Test fun `movement chunk span includes earliest logical sample and charges each sample exactly once`() {
        val e = encoder(events = 3); e.encode(listOf(frame(0))); e.encode(listOf(NativeReplayInteraction.Start(point())))
        val moves = NativeReplayInteraction.Moves(listOf(point(time + 100), point(time + 200), point(time + 300)))
        val chunk = e.encode(listOf(moves))
        assertEquals(time + 100, chunk.firstTimestamp); assertEquals(time + 300, chunk.lastTimestamp)
        assertEquals(1, chunk.eventCount); assertEquals(6, data(chunk).getInt("source"))
        val positions = data(chunk).getJSONArray("positions")
        assertEquals(listOf(-200L, -100L, 0L), (0..2).map { positions.getJSONObject(it).getLong("timeOffset") })
        val smaller = encoder(events = 2); smaller.encode(listOf(frame(0))); smaller.encode(listOf(NativeReplayInteraction.Start(point())))
        assertEquals(NativeEncodingFailure.EVENT_LIMIT,
            assertThrows(NativeEncodingException::class.java) { smaller.encode(listOf(moves)) }.failure)
    }

    @Test fun `sample time cannot precede committed event and movement rate survives chunk boundary`() {
        val e = encoder(); e.encode(listOf(frame(0))); e.encode(listOf(NativeReplayInteraction.Start(point(time + 200))))
        fail(NativeInteractionFailure.ORDER) { e.encode(listOf(NativeReplayInteraction.Moves(listOf(point(time + 100), point(time + 300))))) }
        e.encode(listOf(NativeReplayInteraction.Moves(listOf(point(time + 300)))))
        fail(NativeInteractionFailure.ORDER) { e.encode(listOf(NativeReplayInteraction.Moves(listOf(point(time + 399))))) }
        assertEquals(3L, e.encode(listOf(NativeReplayInteraction.Moves(listOf(point(time + 400))))).sequence)
    }

    @Test fun `blanket inputs placeholders masks layout bounds absent and out of clip targets refuse`() {
        val blanket = NativeWireframeV2Encoder(); blanket.encode(listOf(frame(0)))
        fail(NativeInteractionFailure.TARGET) { blanket.encode(listOf(NativeReplayInteraction.Start(point()))) }
        for (n in listOf(node(kind = NativeMaskedKind.Text), node(kind = NativeMaskedKind.Input(false)),
            node(kind = NativeMaskedKind.ReadableText(NativeReplayText.read("[masked]"))),
            node(kind = NativeMaskedKind.Placeholder), node().copy(geometry = NativeGeometryKind.LAYOUT_BOUNDS),
            node().copy(clip = NativeRect(10.0, 10.0, 0.0, 0.0)))) {
            val e = encoder(); e.encode(listOf(frame(0, nodes = listOf(n))))
            fail(NativeInteractionFailure.TARGET) { e.encode(listOf(NativeReplayInteraction.Start(point()))) }
        }
        val e = encoder(); e.encode(listOf(frame(0)))
        fail(NativeInteractionFailure.TARGET) { e.encode(listOf(NativeReplayInteraction.Start(point(id = b)))) }
        for (x in listOf(9, 90, 300)) fail(NativeInteractionFailure.POINT) {
            e.encode(listOf(NativeReplayInteraction.Start(point(x = x))))
        }
        e.encode(listOf(NativeReplayInteraction.Start(point(x = 89))))
    }

    @Test fun `actual full geometry retains same live leaf but needs cancel before removal or privacy change`() {
        val e = encoder(); e.encode(listOf(frame(0))); e.encode(listOf(NativeReplayInteraction.Start(point())))
        val moved = e.encode(listOf(frame(1, time + 200, listOf(node(x = 100.0)))))
        assertEquals(2, json(moved).getJSONObject(0).getInt("type"))
        fail(NativeInteractionFailure.POINT) { e.encode(listOf(NativeReplayInteraction.Moves(listOf(point(time + 300))))) }
        e.encode(listOf(NativeReplayInteraction.Moves(listOf(point(time + 300, x = 110)))))
        for (nodes in listOf(emptyList(), listOf(node(kind = NativeMaskedKind.Input(true), x = 100.0)),
            listOf(node(x = 100.0).copy(clip = NativeRect(100.0, 10.0, 0.0, 0.0)))))
            fail(NativeInteractionFailure.TARGET) { e.encode(listOf(frame(2, time + 400, nodes))) }
        val retired = e.encode(listOf(NativeReplayInteraction.Cancel(time + 400), frame(2, time + 400, emptyList())))
        assertEquals(10, data(retired).getInt("type")); assertEquals(0, data(retired, 1).getInt("source"))
        assertEquals(NativeEncodingFailure.RETIRED_IDENTITY,
            assertThrows(NativeEncodingException::class.java) { e.encode(listOf(frame(3, time + 600))) }.failure)
    }

    @Test fun `failed mixed chunk and fork leave original gesture and allocation unchanged`() {
        val e = encoder(); e.encode(listOf(frame(0)))
        fail(NativeInteractionFailure.POINT) { e.encode(listOf(NativeReplayInteraction.Start(point()),
            NativeReplayInteraction.Moves(listOf(point(time + 100, x = 99))))) }
        val copy = e.fork(); copy.encode(listOf(NativeReplayInteraction.Start(point())))
        assertEquals(1L, e.encode(listOf(NativeReplayInteraction.Start(point()))).sequence)
        val canceled = copy.encode(listOf(NativeReplayInteraction.Cancel(time + 100)))
        assertEquals(2L, canceled.sequence)
        assertEquals(2L, e.encode(listOf(NativeReplayInteraction.End(point(time + 100)))).sequence)
    }

    @Test fun `geometry grammar remains v1 apart from the required initial discriminator`() {
        val frames = listOf(frame(0), frame(1, time + 200, listOf(node(x = 100.0))),
            frame(2, time + 400, listOf(node(x = 100.0), node(b))), frame(3, time + 600, listOf(node(b))))
        val old = NativeWireframeEncoder(maskingProfile = NativeMaskingProfile.sensitiveMask()).encode(frames.map { it.frame })
        val current = json(encoder().encode(frames))
        current.getJSONObject(0).getJSONObject("data").remove("codec")
        assertArrayEquals(old.bytes, V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(current.toString())))
    }

    @Test fun `late encoded byte rejection cannot consume initial frame state`() {
        val input = listOf(frame(0), frame(1, time + 200))
        val size = encoder().encode(input).bytes.size
        val e = NativeWireframeV2Encoder(NativeWireframeEncoder.Limits(decodedBytes = size - 1),
            maskingProfile = NativeMaskingProfile.sensitiveMask())
        assertEquals(NativeEncodingFailure.BYTE_LIMIT,
            assertThrows(NativeEncodingException::class.java) { e.encode(input) }.failure)
        assertEquals(0L, e.encode(listOf(frame(0))).sequence)
        assertEquals(1L, e.encode(listOf(NativeReplayInteraction.Start(point()))).sequence)
    }

    @Test fun `geometry wire clock ceiling survives chunks and failure does not advance it`() {
        val e = encoder(); e.encode(listOf(frame(0)))
        assertEquals(NativeEncodingFailure.INVALID_TIMESTAMP,
            assertThrows(NativeEncodingException::class.java) { e.encode(listOf(frame(1, time + 199))) }.failure)
        assertEquals(1L, e.encode(listOf(frame(1, time + 200))).sequence)
        assertEquals(NativeEncodingFailure.INVALID_TIMESTAMP,
            assertThrows(NativeEncodingException::class.java) { e.encode(listOf(frame(2, time + 399))) }.failure)
        assertEquals(2L, e.encode(listOf(frame(2, time + 400))).sequence)
    }
}
