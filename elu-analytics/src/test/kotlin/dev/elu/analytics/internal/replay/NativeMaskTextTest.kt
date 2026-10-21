package dev.elu.analytics.internal.replay

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs every case of the shared vector file that the web rule generated. The iOS SDK and the
 * service validate the same file, so output must match exactly.
 */
class NativeMaskTextTest {
    private class Vectors(
        val maxBytes: Int,
        val whitespace: List<Char>,
        val text: List<Pair<String, String>>,
        val input: List<Triple<String, Int, String>>,
        val shaped: List<Pair<String, Boolean>>,
    )

    @Test fun `vector file is byte-identical to the iOS conformance copy`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes()).joinToString("") { "%02x".format(it) }
        assertEquals("69d807f9e703eb1f1f1f850f76587fef818d95ec4ec33cd87d11f69079b58179", digest)
    }

    @Test fun `every text vector matches the web rule`() {
        val vectors = load()
        assertEquals(NativeMaskText.MAXIMUM_BYTES, vectors.maxBytes)
        assertEquals(21, vectors.text.size)
        for ((index, item) in vectors.text.withIndex()) {
            val actual = NativeMaskText.maskText(item.first)
            assertEquals("text vector $index", item.second, actual)
            assertTrue("text vector $index", actual.toByteArray(Charsets.UTF_8).size <= vectors.maxBytes)
            assertEquals("text vector $index", actual.toByteArray(Charsets.UTF_8).size, NativeMaskText.utf8Bytes(actual))
            assertTrue("text vector $index", NativeMaskText.isMaskShaped(actual))
        }
    }

    @Test fun `every input vector matches the web rule`() {
        val vectors = load()
        assertEquals(9, vectors.input.size)
        for ((index, item) in vectors.input.withIndex()) {
            val (input, length, expected) = item
            assertEquals("input vector $index", length, input.length)
            val actual = NativeMaskText.maskInput(length)
            assertEquals("input vector $index", expected, actual)
            assertTrue("input vector $index", NativeMaskText.isInputMask(actual))
        }
    }

    @Test fun `every mask shape vector matches`() {
        val vectors = load()
        assertEquals(10, vectors.shaped.size)
        for ((index, item) in vectors.shaped.withIndex()) {
            assertEquals("shape vector $index", item.second, NativeMaskText.isMaskShaped(item.first))
        }
    }

    @Test fun `whitespace is exactly the shared units not the platform predicate`() {
        val vectors = load()
        assertEquals(25, vectors.whitespace.size)
        val actual = (0..0xFFFF).map { it.toChar() }.filter { NativeMaskText.isWhitespace(it) }
        assertEquals(vectors.whitespace, actual)
        // Platform whitespace differs: U+001C counts on the JVM, BOM does not; NEL and ZWSP are starred.
        assertTrue('\u001C'.isWhitespace()); assertFalse(NativeMaskText.isWhitespace('\u001C'))
        assertFalse('\uFEFF'.isWhitespace()); assertTrue(NativeMaskText.isWhitespace('\uFEFF'))
        assertEquals("***\uFEFF*", NativeMaskText.maskText("a\u0085\u200B\uFEFFb"))
    }

    @Test fun `encoder emits every vector exactly and refuses unmasked values`() {
        val vectors = load()
        val viewport = NativeViewport(390, 844)
        val bounds = NativeRect(0.0, 0.0, 100.0, 30.0)
        fun encode(kind: NativeMaskedKind): JSONObject {
            val node = NativeMaskedNode(UUID.randomUUID(), kind, bounds, bounds)
            val chunk = NativeWireframeEncoder().encode(listOf(NativeMaskedSnapshot(0, 1, viewport, listOf(node))))
            val events = JSONArray(chunk.bytes.toString(Charsets.UTF_8))
            return events.getJSONObject(1).getJSONObject("data").getJSONArray("wireframes").getJSONObject(0)
                .getJSONArray("childWireframes").getJSONObject(0)
        }
        for ((_, expected) in vectors.text) assertEquals(expected, encode(NativeMaskedKind.MaskedText(expected)).getString("text"))
        for ((_, _, expected) in vectors.input) {
            assertEquals(expected, encode(NativeMaskedKind.Input(true, expected)).getString("value"))
        }
        val encoder = NativeWireframeEncoder()
        for (kind in listOf(NativeMaskedKind.MaskedText("[masked]"), NativeMaskedKind.MaskedText("PRIVATE"),
            NativeMaskedKind.Input(false, "** *"), NativeMaskedKind.Input(true, "secret"),
            NativeMaskedKind.MaskedText("*".repeat(4_097)))) {
            val node = NativeMaskedNode(UUID.randomUUID(), kind, bounds, bounds)
            assertEquals(NativeEncodingFailure.INVALID_TEXT, assertThrows(NativeEncodingException::class.java) {
                encoder.encode(listOf(NativeMaskedSnapshot(0, 1, viewport, listOf(node))))
            }.failure)
        }
    }

    @Test fun `long text visits only the capped prefix`() {
        var reads = 0
        val long = object : CharSequence {
            override val length = 1_000_000
            override fun get(index: Int): Char { reads += 1; return 'x' }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("not called")
        }
        assertEquals("*".repeat(4_096), NativeMaskText.maskText(long))
        assertEquals(4_096, reads)
        assertEquals(4_096, NativeMaskText.maskInput(1_000_000).toByteArray(Charsets.UTF_8).size)
        assertEquals("", NativeMaskText.maskInput(0)); assertEquals("", NativeMaskText.maskInput(-1))
    }

    @Test fun `utf8 size counts pairs once and lone surrogates as replacement`() {
        for (value in listOf("", "*", "\u00A0", "\u3000", "a\uD83D\uDE00b", "\uD800", "\uDC00x")) {
            val expected = Charsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                .replaceWith(byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte()))
                .encode(java.nio.CharBuffer.wrap(value)).remaining()
            assertEquals(value, expected, NativeMaskText.utf8Bytes(value))
        }
        assertArrayEquals(byteArrayOf(0x2A), NativeMaskText.maskText("\u00E9").toByteArray(Charsets.UTF_8))
    }

    private fun bytes(): ByteArray = checkNotNull(javaClass.classLoader?.getResourceAsStream("contracts/native-mask-vectors.json"))
        .use { it.readBytes() }

    private fun load(): Vectors {
        val root = JSONObject(bytes().toString(Charsets.UTF_8))
        val whitespace = root.getJSONArray("whitespaceUtf16Units").let { array ->
            (0 until array.length()).map { array.getString(it).toInt(16).toChar() }
        }
        val text = root.getJSONArray("text").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).let { item -> item.getString("input") to item.getString("expected") } }
        }
        val input = root.getJSONArray("input").let { array ->
            (0 until array.length()).map {
                array.getJSONObject(it).let { item -> Triple(item.getString("input"), item.getInt("utf16Length"), item.getString("expected")) }
            }
        }
        val shaped = root.getJSONArray("maskShaped").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).let { item -> item.getString("value") to item.getBoolean("expected") } }
        }
        return Vectors(root.getInt("maxBytes"), whitespace, text, input, shaped)
    }
}
