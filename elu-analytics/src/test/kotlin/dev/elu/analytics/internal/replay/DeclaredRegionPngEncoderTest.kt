package dev.elu.analytics.internal.replay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

class DeclaredRegionPngEncoderTest {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private fun chunk(name: String, value: ByteArray): ByteArray {
        val kind = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(kind); update(value) }.value
        return ByteBuffer.allocate(value.size + 12).putInt(value.size).put(kind).put(value).putInt(crc.toInt()).array()
    }
    private fun compressed(value: ByteArray): ByteArray {
        val d = Deflater(); val out = ByteArrayOutputStream()
        try {
            d.setInput(value); d.finish(); val block = ByteArray(256)
            while (!d.finished()) { val size = d.deflate(block); check(size > 0); out.write(block, 0, size) }
            return out.toByteArray()
        } finally { d.end() }
    }
    private fun header(width: Int = 1, height: Int = 1, color: Int = 6, interlace: Int = 0) = chunk("IHDR",
        ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(color.toByte()).put(0).put(0).put(interlace.toByte()).array())
    private fun png(raw: ByteArray = byteArrayOf(0, 1, 2, 3, -1), metadata: ByteArray = byteArrayOf(),
        body: ByteArray = compressed(raw), h: ByteArray = header()): ByteArray =
        signature + h + metadata + chunk("IDAT", body) + chunk("IEND", byteArrayOf())
    private fun denied(bytes: ByteArray, width: Int = 1, height: Int = 1) {
        assertThrows(Exception::class.java) { DeclaredRegionPngEncoder.validate(bytes, width, height) }
    }
    @Test fun `opaque RGB and RGBA are accepted without changing canonical bytes`() {
        for (bytes in listOf(png(), png(byteArrayOf(0, 1, 2, 3), h = header(color = 2)))) {
            DeclaredRegionPngEncoder.validate(bytes, 1, 1)
            assertArrayEquals(bytes, DeclaredRegionPngEncoder.normalize(bytes, 1, 1))
        }
    }
    @Test fun `metadata is stripped before output while closed validator rejects it`() {
        val metadata = chunk("eXIf", "private metadata".toByteArray()) + chunk("pHYs", ByteArray(9))
        val input = png(metadata = metadata); denied(input)
        assertArrayEquals(png(), DeclaredRegionPngEncoder.normalize(input, 1, 1))
    }
    @Test fun `only optional intent zero sRGB survives normalization`() {
        val accepted = png(metadata = chunk("sRGB", byteArrayOf(0)))
        assertArrayEquals(accepted, DeclaredRegionPngEncoder.normalize(accepted, 1, 1))
        val other = png(metadata = chunk("sRGB", byteArrayOf(1))); denied(other)
        assertArrayEquals(png(), DeclaredRegionPngEncoder.normalize(other, 1, 1))
        denied(png(metadata = chunk("sRGB", byteArrayOf(0)) + chunk("sRGB", byteArrayOf(0))))
    }
    @Test fun `CRC trailing bytes APNG and unknown critical chunks refuse`() {
        val corrupt = png().also { it[29] = (it[29].toInt() xor 1).toByte() }; denied(corrupt)
        denied(png() + 0)
        for (name in listOf("acTL", "ABCD")) {
            val input = png(metadata = chunk(name, byteArrayOf(0)))
            denied(input)
            assertThrows(IllegalArgumentException::class.java) { DeclaredRegionPngEncoder.normalize(input, 1, 1) }
        }
    }
    @Test fun `dimensions color interlace and encoded cap are bounded`() {
        denied(png(), 2, 1)
        denied(png(h = header(width = 2049)), 2049, 1)
        denied(png(h = header(width = 2048, height = 1024)), 2048, 1024)
        denied(png(h = header(color = 3)))
        denied(png(h = header(interlace = 1)))
        denied(ByteArray(DeclaredRegionPngEncoder.MAX_ENCODED_BYTES + 1))
    }
    @Test fun `zlib truncation excess scanline and concatenated stream refuse`() {
        val exact = compressed(byteArrayOf(0, 1, 2, 3, -1))
        denied(png(body = exact.copyOf(exact.size - 1)))
        denied(png(raw = byteArrayOf(0, 1, 2, 3, -1, 0)))
        denied(png(body = exact + exact))
        denied(png(body = exact + 0))
        denied(png(raw = byteArrayOf(5, 1, 2, 3, -1)))
    }
    @Test fun `all PNG filters reconstruct opacity rather than trusting compressed alpha bytes`() {
        // Two equal pixels in two equal rows; filters 1..4 alter the encoded second alpha.
        for (filter in 0..4) {
            val rows = ByteArrayOutputStream()
            val source = byteArrayOf(7, 8, 9, -1, 7, 8, 9, -1)
            var previous = ByteArray(8)
            repeat(2) {
                rows.write(filter)
                for (x in source.indices) {
                    val a = if (x >= 4) source[x - 4].toInt() and 255 else 0
                    val b = previous[x].toInt() and 255
                    val c = if (x >= 4) previous[x - 4].toInt() and 255 else 0
                    val p = a + b - c
                    val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                    val predictor = when (filter) { 0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2; else -> if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                    rows.write(((source[x].toInt() and 255) - predictor) and 255)
                }
                previous = source
            }
            DeclaredRegionPngEncoder.validate(png(raw = rows.toByteArray(), h = header(2, 2)), 2, 2)
        }
        denied(png(raw = byteArrayOf(0, 1, 2, 3, -2)))
    }
    @Test fun `empty or interrupted IDAT and excess chunks refuse`() {
        val encoded = compressed(byteArrayOf(0, 1, 2, 3, -1))
        val h = signature + header()
        denied(h + chunk("IDAT", byteArrayOf()) + chunk("IEND", byteArrayOf()))
        denied(h + chunk("IDAT", encoded.copyOfRange(0, 2)) + chunk("sRGB", byteArrayOf(0)) +
            chunk("IDAT", encoded.copyOfRange(2, encoded.size)) + chunk("IEND", byteArrayOf()))
        val many = ByteArrayOutputStream(); many.write(h)
        repeat(1023) { many.write(chunk("IDAT", byteArrayOf(0))) }
        many.write(chunk("IEND", byteArrayOf())); denied(many.toByteArray())
    }
}
