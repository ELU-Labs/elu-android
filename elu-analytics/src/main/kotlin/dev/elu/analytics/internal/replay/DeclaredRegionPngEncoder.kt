package dev.elu.analytics.internal.replay

import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.math.abs

/** Closed pixel grammar only, never proof of privacy. Input comes solely from validated output. */
internal object DeclaredRegionPngEncoder {
    const val MAX_ENCODED_BYTES = 2_097_152
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private data class Chunk(val type: String, val start: Int, val length: Int)
    private data class Parsed(val chunks: List<Chunk>, val width: Int, val height: Int, val channels: Int)

    fun normalize(input: ByteArray, expectedWidth: Int, expectedHeight: Int): ByteArray {
        val parsed = parse(input, strict = false)
        require(parsed.width == expectedWidth && parsed.height == expectedHeight) { "png-dimensions" }
        val output = LimitedBytes(MAX_ENCODED_BYTES)
        var bytes: ByteArray? = null
        try {
            output.write(signature)
            for (chunk in parsed.chunks) if (chunk.type in setOf("IHDR", "IDAT", "IEND") ||
                chunk.type == "sRGB" && chunk.length == 1 && input[chunk.start + 8] == 0.toByte()) {
                output.write(input, chunk.start, chunk.length + 12)
            }
            bytes = output.bytes()
            validate(checkNotNull(bytes), expectedWidth, expectedHeight)
            return checkNotNull(bytes).also { bytes = null }
        } finally { bytes?.fill(0); output.close() }
    }

    fun validate(input: ByteArray, expectedWidth: Int, expectedHeight: Int) {
        val parsed = parse(input, strict = true)
        require(parsed.width == expectedWidth && parsed.height == expectedHeight) { "png-dimensions" }
        val compressedSize = parsed.chunks.filter { it.type == "IDAT" }.sumOf { it.length }
        val compressed = ByteArray(compressedSize)
        var position = 0
        parsed.chunks.filter { it.type == "IDAT" }.forEach {
            input.copyInto(compressed, position, it.start + 8, it.start + 8 + it.length); position += it.length
        }
        val inflater = Inflater()
        val line = ByteArray(1 + parsed.width * parsed.channels)
        var previous = ByteArray(line.size - 1)
        var current = ByteArray(previous.size)
        try {
            inflater.setInput(compressed)
            repeat(parsed.height) {
                var offset = 0
                while (offset < line.size) {
                    val count = inflater.inflate(line, offset, line.size - offset)
                    require(count > 0 && !inflater.needsDictionary()) { "png-zlib-truncated" }
                    offset += count
                }
                val filter = line[0].toInt() and 255
                require(filter in 0..4) { "png-filter" }
                for (x in current.indices) {
                    val left = if (x >= parsed.channels) current[x - parsed.channels].toInt() and 255 else 0
                    val up = previous[x].toInt() and 255
                    val corner = if (x >= parsed.channels) previous[x - parsed.channels].toInt() and 255 else 0
                    val prediction = when (filter) {
                        0 -> 0
                        1 -> left
                        2 -> up
                        3 -> (left + up) / 2
                        else -> paeth(left, up, corner)
                    }
                    current[x] = ((line[x + 1].toInt() and 255) + prediction).toByte()
                }
                if (parsed.channels == 4) for (x in 3 until current.size step 4) {
                    require(current[x] == (-1).toByte()) { "png-nonopaque" }
                }
                val swap = previous; previous = current; current = swap
            }
            val extra = ByteArray(1)
            try {
                require(inflater.inflate(extra) == 0 && inflater.finished() && inflater.remaining == 0 && !inflater.needsDictionary()) {
                    "png-zlib-extent-or-trailing"
                }
            } finally { extra.fill(0) }
        } finally { inflater.end(); compressed.fill(0); line.fill(0); previous.fill(0); current.fill(0) }
    }

    private fun parse(input: ByteArray, strict: Boolean): Parsed {
        require(input.size in 45..MAX_ENCODED_BYTES && input.copyOfRange(0, 8).contentEquals(signature)) { "png-size-signature" }
        val chunks = ArrayList<Chunk>()
        var at = 8
        var width = 0; var height = 0; var channels = 0
        var idatSeen = false; var idatEnded = false; var srgbSeen = false; var ended = false
        while (at < input.size) {
            require(!ended && chunks.size < 1024 && at <= input.size - 12) { "png-chunk-limit" }
            val length = integer(input, at)
            require(length <= (input.size - at - 12).toLong()) { "png-chunk-length" }
            val count = length.toInt()
            val typeBytes = input.copyOfRange(at + 4, at + 8)
            require(typeBytes.all { (it.toInt() and 255) in 65..90 || (it.toInt() and 255) in 97..122 } &&
                typeBytes[2].toInt() in 65..90) { "png-chunk-type" }
            val type = String(typeBytes, Charsets.US_ASCII)
            val crc = CRC32().apply { update(input, at + 4, count + 4) }.value
            require(crc == integer(input, at + count + 8)) { "png-crc" }
            require(chunks.isNotEmpty() || type == "IHDR") { "png-first-chunk" }
            if (idatSeen && type != "IDAT") idatEnded = true
            when (type) {
                "IHDR" -> {
                    require(chunks.isEmpty() && count == 13) { "png-header" }
                    val w = integer(input, at + 8); val h = integer(input, at + 12)
                    require(w in 1..2048 && h in 1..2048 && w * h <= 1_048_576) { "png-pixel-limit" }
                    width = w.toInt(); height = h.toInt()
                    val color = input[at + 17].toInt() and 255
                    require(input[at + 16].toInt() == 8 && color in setOf(2, 6) &&
                        input[at + 18].toInt() == 0 && input[at + 19].toInt() == 0 && input[at + 20].toInt() == 0) { "png-format" }
                    channels = if (color == 2) 3 else 4
                }
                "IDAT" -> { require(count > 0 && !idatEnded) { "png-idat-order" }; idatSeen = true }
                "IEND" -> { require(idatSeen && count == 0 && at + 12 == input.size) { "png-end" }; ended = true }
                "sRGB" -> {
                    require(!idatSeen && !srgbSeen && count == 1 && (input[at + 8].toInt() and 255) in 0..3) { "png-srgb" }
                    if (strict) require(input[at + 8].toInt() == 0) { "png-srgb-intent" }
                    srgbSeen = true
                }
                else -> {
                    require(!strict && typeBytes[0].toInt() in 97..122 && type !in setOf("acTL", "fcTL", "fdAT")) { "png-unsupported-chunk" }
                    // Ancillary data is never copied to normalized output. It still has checked bounds/CRC.
                }
            }
            chunks += Chunk(type, at, count)
            at += count + 12
        }
        require(ended && at == input.size) { "png-incomplete" }
        return Parsed(chunks, width, height, channels)
    }
    private fun integer(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset <= bytes.size - 4)
        var result = 0L
        repeat(4) { result = (result shl 8) or (bytes[offset + it].toLong() and 255) }
        return result
    }
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c; val x = abs(p - a); val y = abs(p - b); val z = abs(p - c)
        return if (x <= y && x <= z) a else if (y <= z) b else c
    }
}
