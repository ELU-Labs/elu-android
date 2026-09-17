package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeWireframeEncoderTest {
    private val first = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val second = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val third = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val time = 1_788_883_200_000L

    @Test fun `initial wire uses only fixed tokens integer time and no local identities`() {
        val e = NativeWireframeEncoder()
        val chunk = e.encode(listOf(frame(0, listOf(node(first), node(second, NativeMaskedKind.Input(true)),
            node(third, NativeMaskedKind.Placeholder)))))
        assertEquals(0L, chunk.sequence); assertEquals(2, chunk.eventCount)
        assertEquals(time, chunk.firstTimestamp); assertEquals(time, chunk.lastTimestamp)
        val events = decode(chunk)
        assertEquals(4, events.getJSONObject(0).getInt("type"))
        val leaves = leaves(events.getJSONObject(1))
        assertEquals("[masked]", leaves.getJSONObject(0).getString("text"))
        assertEquals("[masked]", leaves.getJSONObject(1).getString("value"))
        assertEquals("password", leaves.getJSONObject(1).getString("inputType"))
        assertEquals(true, leaves.getJSONObject(1).getBoolean("disabled"))
        assertEquals("Content hidden", leaves.getJSONObject(2).getString("label"))
        assertEquals(10_000_001L, leaves.getJSONObject(0).getLong("id"))
        val text = chunk.bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains(first.toString())); assertFalse(text.contains("ordinal"))
        assertArrayEquals(chunk.bytes, V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.parse(text)))
    }

    @Test fun `duplicate frame failure rolls back earlier frames and same millisecond remains legal`() {
        val e = NativeWireframeEncoder(); val a = frame(0, listOf(node(first)))
        fails(NativeEncodingFailure.FRAME_ORDER) { e.encode(listOf(a, a)) }
        val result = e.encode(listOf(a, frame(1, listOf(node(first)))))
        assertEquals(0L, result.sequence); assertEquals(3, result.eventCount)
        repeat(3) { assertEquals(time, decode(result).getJSONObject(it).getLong("timestamp")) }
    }

    @Test fun `invalid or rotated viewport never consumes valid frame`() {
        for (n in listOf(0, -1, 16_385)) fails(NativeEncodingFailure.INVALID_VIEWPORT) { NativeViewport(n, 844) }
        val e = NativeWireframeEncoder(); e.encode(listOf(frame(0)))
        fails(NativeEncodingFailure.INVALID_VIEWPORT) {
            e.encode(listOf(NativeMaskedSnapshot(1, time, NativeViewport(844, 390), emptyList())))
        }
        assertEquals(1L, e.encode(listOf(frame(1))).sequence)
    }

    @Test fun `geometry clip and style reject invalid numeric values`() {
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1_000_001.0, 1_000_001.0)) {
            fails(NativeEncodingFailure.INVALID_GEOMETRY) { NativeRect(x, 0.0, 10.0, 10.0) }
        }
        fails(NativeEncodingFailure.INVALID_GEOMETRY) { NativeRect(0.0, 0.0, -1.0, 1.0) }
        fails(NativeEncodingFailure.INVALID_GEOMETRY) { NativeStyle(fontSize = Double.NaN) }
        fails(NativeEncodingFailure.INVALID_GEOMETRY) { NativeSolidColor(256, 0, 0) }
        val e = NativeWireframeEncoder()
        val bad = node(first).copy(clip = NativeRect(0.0, 0.0, 500.0, 50.0))
        fails(NativeEncodingFailure.INVALID_GEOMETRY) { e.encode(listOf(frame(0, listOf(bad)))) }
        assertEquals(0L, e.encode(listOf(frame(0, listOf(node(first))))).sequence)
    }

    @Test fun `clipping keeps original geometry and permits empty visible area`() {
        val a = node(first).copy(bounds = NativeRect(-20.0, 20.0, 100.0, 30.0), clip = NativeRect(0.0, 25.0, 50.0, 20.0))
        val b = node(second, NativeMaskedKind.Placeholder).copy(bounds = NativeRect(900.0, 20.0, 10.0, 10.0),
            clip = NativeRect(390.0, 20.0, 0.0, 0.0))
        val result = leaves(decode(NativeWireframeEncoder().encode(listOf(frame(0, listOf(a,b))))).getJSONObject(1))
        assertEquals(-20, result.getJSONObject(0).getInt("x"))
        assertEquals(0, result.getJSONObject(0).getJSONObject("clip").getInt("x"))
        assertEquals(0, result.getJSONObject(1).getJSONObject("clip").getInt("width"))
    }

    @Test fun `duplicate node rejection preserves sequence and suffix ID allocation`() {
        val e = NativeWireframeEncoder(); val a = node(first)
        e.encode(listOf(frame(0,listOf(a))))
        fails(NativeEncodingFailure.DUPLICATE_IDENTITY) { e.encode(listOf(frame(1,listOf(a,a)))) }
        val c = e.encode(listOf(frame(1,listOf(a,node(second)))))
        val data = decode(c).getJSONObject(0).getJSONObject("data")
        assertEquals(1L,c.sequence); assertEquals(0,data.getJSONArray("updates").length())
        assertEquals(10_000_002L,data.getJSONArray("adds").getJSONObject(0).getJSONObject("wireframe").getLong("id"))
    }

    @Test fun `only pure removals and suffix additions use mutation`() {
        val e = NativeWireframeEncoder(); val a=node(first); val b=node(second,NativeMaskedKind.Rectangle)
        e.encode(listOf(frame(0,listOf(a,b))))
        val event=decode(e.encode(listOf(frame(1,listOf(b,node(third,NativeMaskedKind.Placeholder)))))).getJSONObject(0)
        assertEquals(3,event.getInt("type"))
        val data=event.getJSONObject("data")
        assertEquals(10_000_001L,data.getJSONArray("removes").getJSONObject(0).getLong("id"))
        assertEquals(10_000_003L,data.getJSONArray("adds").getJSONObject(0).getJSONObject("wireframe").getLong("id"))
    }

    @Test fun `changes reorder middle insertion and no change use full preserving stable IDs`() {
        val a=node(first); val b=node(second,NativeMaskedKind.Rectangle); val c=node(third,NativeMaskedKind.Placeholder)
        for (nodes in listOf(listOf(node(first,x=15.0),b), listOf(b,a), listOf(a,c,b), listOf(a,b))) {
            val e=NativeWireframeEncoder();e.encode(listOf(frame(0,listOf(a,b))))
            val event=decode(e.encode(listOf(frame(1,nodes)))).getJSONObject(0)
            assertEquals(2,event.getInt("type"))
            val expected=nodes.map { when(it.identity){first->10_000_001L;second->10_000_002L;else->10_000_003L} }
            val actual=leaves(event);assertEquals(expected,(0 until actual.length()).map{actual.getJSONObject(it).getLong("id")})
        }
    }

    @Test fun `retired identities cannot return after either removal form`() {
        for(changed in listOf(false,true)) {
            val e=NativeWireframeEncoder();val a=node(first);val b=node(second,NativeMaskedKind.Rectangle)
            e.encode(listOf(frame(0,listOf(a,b))))
            e.encode(listOf(frame(1,listOf(if(changed) node(second,NativeMaskedKind.Rectangle,15.0) else b))))
            fails(NativeEncodingFailure.RETIRED_IDENTITY){e.encode(listOf(frame(2,listOf(a,b))))}
            val good=e.encode(listOf(frame(2,listOf(b,node(third)))))
            assertEquals(2L,good.sequence)
        }
    }

    @Test fun `full never refills lifetime IDs and last safe ID is used exactly once`() {
        val e=NativeWireframeEncoder(NativeWireframeEncoder.Limits(liveNodes=3,lifetimeIds=3))
        e.encode(listOf(frame(0,listOf(node(first),node(second)))))
        e.encode(listOf(frame(1,listOf(node(first,x=15.0)))))
        fails(NativeEncodingFailure.NODE_LIMIT){e.encode(listOf(frame(2,listOf(node(first),node(third)))))}
        val near=NativeWireframeEncoder(firstNodeId=NativeWireframeEncoder.MAXIMUM_SAFE_INTEGER-1)
        val chunk=near.encode(listOf(frame(0,listOf(node(first)))))
        assertEquals(NativeWireframeEncoder.MAXIMUM_SAFE_INTEGER,leaves(decode(chunk).getJSONObject(1)).getJSONObject(0).getLong("id"))
        fails(NativeEncodingFailure.COUNTER_EXHAUSTED){near.encode(listOf(frame(1,listOf(node(first),node(second)))))}
    }

    @Test fun `exact byte bound and late failure leave first frame retryable`() {
        val frames=listOf(frame(0,listOf(node(first))),frame(1,listOf(node(second,NativeMaskedKind.Input(false)))))
        val expected=NativeWireframeEncoder().encode(frames)
        val exact=NativeWireframeEncoder(NativeWireframeEncoder.Limits(decodedBytes=expected.bytes.size))
        assertArrayEquals(expected.bytes,exact.encode(frames).bytes)
        val short=NativeWireframeEncoder(NativeWireframeEncoder.Limits(decodedBytes=expected.bytes.size-1))
        fails(NativeEncodingFailure.BYTE_LIMIT){short.encode(frames)}
        assertEquals(0L,short.encode(listOf(frames.first())).sequence)
    }

    @Test fun `event and representation bounds reject chunk atomically`() {
        for(limit in listOf(NativeWireframeEncoder.Limits(events=2),NativeWireframeEncoder.Limits(representations=3))) {
            val e=NativeWireframeEncoder(limit)
            assertThrows(NativeEncodingException::class.java){e.encode(listOf(frame(0,listOf(node(first))),frame(1,listOf(node(first)))))}
            assertEquals(0L,e.encode(listOf(frame(0,listOf(node(first))))).sequence)
        }
    }

    @Test fun `ten thousand live limit includes root`() {
        val nodes=(0 until 9_999).map{node(UUID(0L,it.toLong()))};val e=NativeWireframeEncoder()
        val c=e.encode(listOf(frame(0,nodes)));assertEquals(10_000,c.nodeRepresentations)
        assertEquals(9_999,leaves(decode(c).getJSONObject(1)).length())
        fails(NativeEncodingFailure.NODE_LIMIT){e.encode(listOf(frame(1,nodes+node(UUID(1L,0L)))))}
    }

    @Test fun `timestamp rejection cannot consume ordinal and original time is preserved`() {
        val e=NativeWireframeEncoder();e.encode(listOf(frame(0)))
        for(t in listOf(0L,time-1,NativeWireframeEncoder.MAXIMUM_SAFE_INTEGER+1)) {
            fails(NativeEncodingFailure.INVALID_TIMESTAMP){e.encode(listOf(frame(1,timestamp=t)))}
        }
        val c=e.encode(listOf(frame(1,timestamp=time+123)));assertEquals(time+123,c.firstTimestamp);assertEquals(1L,c.sequence)
    }

    @Test fun `style is closed numeric and color components only`() {
        val style=NativeStyle(NativeSolidColor(255,0,16),NativeSolidColor(0,20,30,128),12.5,NativeFont.SYSTEM)
        val n=node(first,NativeMaskedKind.Input(false)).copy(style=style)
        val l=leaves(decode(NativeWireframeEncoder().encode(listOf(frame(0,listOf(n))))).getJSONObject(1)).getJSONObject(0)
        assertEquals("text",l.getString("inputType"));val s=l.getJSONObject("style")
        assertEquals(setOf("color","backgroundColor","fontSize","fontFamily"),s.keys().asSequence().toSet())
        assertEquals("#ff0010",s.getString("color"));assertEquals("#00141e80",s.getString("backgroundColor"))
    }

    @Test fun `caller node list and output arrays cannot mutate immutable frames or chunks`() {
        val nodes=mutableListOf(node(first));val f=frame(0,nodes);nodes.clear()
        assertEquals(1,f.nodes.size)
        assertThrows(UnsupportedOperationException::class.java){(f.nodes as MutableList<NativeMaskedNode>).clear()}
        val c=NativeWireframeEncoder().encode(listOf(f));val original=c.bytes;val copy=c.bytes;copy[0]=0
        assertArrayEquals(original,c.bytes)
    }

    @Test fun `layout provenance is leaf only and visible legacy bytes omit it`() {
        val visible = node(first)
        val marked = node(second).copy(geometry = NativeGeometryKind.LAYOUT_BOUNDS)
        val snapshot = frame(0, listOf(visible, marked))
        assertEquals(true, snapshot.containsLayoutBounds)
        val events = decode(NativeWireframeEncoder().encode(listOf(snapshot)))
        val full = events.getJSONObject(1)
        val root = full.getJSONObject("data").getJSONArray("wireframes").getJSONObject(0)
        assertFalse(root.has("geometry")); assertFalse(full.has("geometry"))
        assertFalse(events.getJSONObject(0).getJSONObject("data").has("geometry"))
        assertFalse(leaves(full).getJSONObject(0).has("geometry"))
        assertEquals("layout-bounds", leaves(full).getJSONObject(1).getString("geometry"))
        val old = NativeWireframeEncoder().encode(listOf(frame(0, listOf(visible))))
        assertFalse(old.bytes.toString(Charsets.UTF_8).contains("geometry"))
        assertFalse(frame(0, listOf(visible)).containsLayoutBounds)
    }

    @Test fun `geometry mode changes force full snapshots in both directions without replacing IDs`() {
        val e = NativeWireframeEncoder(); val visible = node(first)
        e.encode(listOf(frame(0, listOf(visible))))
        val marked = visible.copy(geometry = NativeGeometryKind.LAYOUT_BOUNDS)
        for ((ordinal, value) in listOf(marked, visible).withIndex()) {
            val event = decode(e.encode(listOf(frame(ordinal + 1L, listOf(value))))).getJSONObject(0)
            assertEquals(2, event.getInt("type"))
            val leaf = leaves(event).getJSONObject(0)
            assertEquals(10_000_001L, leaf.getLong("id"))
            assertEquals(ordinal == 0, leaf.has("geometry"))
        }
    }

    @Test fun `marked suffix add and removal carry no stale aggregate`() {
        val e = NativeWireframeEncoder(); val a = node(first)
        val b = node(second, NativeMaskedKind.Placeholder).copy(geometry = NativeGeometryKind.LAYOUT_BOUNDS)
        e.encode(listOf(frame(0, listOf(a))))
        val added = decode(e.encode(listOf(frame(1, listOf(a, b))))).getJSONObject(0)
        assertEquals(3, added.getInt("type"))
        val data = added.getJSONObject("data")
        assertFalse(data.has("geometry"))
        assertEquals("layout-bounds", data.getJSONArray("adds").getJSONObject(0).getJSONObject("wireframe").getString("geometry"))
        val removedFrame = frame(2, listOf(a))
        assertFalse(removedFrame.containsLayoutBounds)
        val removed = decode(e.encode(listOf(removedFrame))).getJSONObject(0)
        assertEquals(3, removed.getInt("type"))
        assertEquals(10_000_002L, removed.getJSONObject("data").getJSONArray("removes").getJSONObject(0).getLong("id"))
        assertFalse(removed.toString().contains("geometry"))
    }

    @Test fun `failed marked fork preserves original encoder prefix IDs and sequence`() {
        val e = NativeWireframeEncoder(); val clean = NativeWireframeEncoder(); val a = node(first)
        val prefix = e.encode(listOf(frame(0, listOf(a)))).bytes
        assertArrayEquals(prefix, clean.encode(listOf(frame(0, listOf(a)))).bytes)
        val marked = a.copy(geometry = NativeGeometryKind.LAYOUT_BOUNDS)
        val fork = e.fork()
        fails(NativeEncodingFailure.FRAME_ORDER) { fork.encode(listOf(frame(1, listOf(marked)), frame(1))) }
        val retry = listOf(frame(1, listOf(a, node(second))))
        assertArrayEquals(clean.encode(retry).bytes, e.encode(retry).bytes)
        assertEquals(1L, fork.encode(listOf(frame(1, listOf(marked)))).sequence)
    }

    private fun frame(ordinal:Long,nodes:List<NativeMaskedNode> = emptyList(),timestamp:Long=time)=
        NativeMaskedSnapshot(ordinal,timestamp,NativeViewport(390,844),nodes)
    private fun node(id:UUID,kind:NativeMaskedKind=NativeMaskedKind.Text,x:Double=10.0):NativeMaskedNode {
        val r=NativeRect(x,20.0,100.0,30.0);return NativeMaskedNode(id,kind,r,r)
    }
    private fun decode(c:NativeEncodedChunk)=JSONArray(c.bytes.toString(Charsets.UTF_8))
    private fun leaves(e:JSONObject)=e.getJSONObject("data").getJSONArray("wireframes").getJSONObject(0).getJSONArray("childWireframes")
    private fun fails(expected:NativeEncodingFailure,action:()->Unit) {
        assertEquals(expected,assertThrows(NativeEncodingException::class.java,action).failure)
    }
}
