package dev.elu.analytics.internal.replay

import java.util.Collections
import java.util.UUID
import kotlin.math.abs

internal enum class NativeEncodingFailure {
    INVALID_GEOMETRY, INVALID_VIEWPORT, INVALID_TIMESTAMP, FRAME_ORDER, DUPLICATE_IDENTITY,
    RETIRED_IDENTITY, NODE_LIMIT, REPRESENTATION_LIMIT, EVENT_LIMIT, BYTE_LIMIT,
    COUNTER_EXHAUSTED, INVALID_LIMITS, INVALID_TEXT,
}

internal class NativeEncodingException(val failure: NativeEncodingFailure) : IllegalArgumentException(failure.name)
internal fun nativeRequire(allowed: Boolean, failure: NativeEncodingFailure) {
    if (!allowed) throw NativeEncodingException(failure)
}

/** Masked value boundary only: no View, raw text, URL, image, content description or CSS input. */
internal data class NativeRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    init {
        nativeRequire(listOf(x, y, width, height).all { it.isFinite() } && abs(x) <= 1_000_000 &&
            abs(y) <= 1_000_000 && width in 0.0..1_000_000.0 && height in 0.0..1_000_000.0 &&
            (x + width).isFinite() && (y + height).isFinite(), NativeEncodingFailure.INVALID_GEOMETRY)
    }
}

internal data class NativeViewport(val width: Int, val height: Int) {
    init { nativeRequire(width in 1..16_384 && height in 1..16_384, NativeEncodingFailure.INVALID_VIEWPORT) }
}

internal data class NativeSolidColor(val red: Int, val green: Int, val blue: Int, val alpha: Int = 255) {
    init { nativeRequire(listOf(red, green, blue, alpha).all { it in 0..255 }, NativeEncodingFailure.INVALID_GEOMETRY) }
    val wireValue: String get() = "#" + listOf(red, green, blue).joinToString("") { it.toString(16).padStart(2, '0') } +
        if (alpha == 255) "" else alpha.toString(16).padStart(2, '0')
}

internal enum class NativeFont(val wireValue: String) {
    SANS_SERIF("sans-serif"), SERIF("serif"), MONOSPACE("monospace"), SYSTEM("system-ui"),
}

internal data class NativeStyle(
    val color: NativeSolidColor? = null,
    val backgroundColor: NativeSolidColor? = null,
    val fontSize: Double? = null,
    val fontFamily: NativeFont? = null,
) {
    init { nativeRequire(fontSize == null || fontSize.isFinite() && fontSize in 1.0..256.0, NativeEncodingFailure.INVALID_GEOMETRY) }
}

/** Detached plain text accepted only after the collector has applied all inherited restrictions. */
internal class NativeReplayText private constructor(val value: String, val utf8Bytes: Int) {
    override fun equals(other: Any?): Boolean = other is NativeReplayText && value == other.value
    override fun hashCode(): Int = value.hashCode()
    companion object {
        const val MAXIMUM_UTF8_BYTES = 4_096
        fun read(value: String): NativeReplayText {
            nativeRequire(value.length <= MAXIMUM_UTF8_BYTES, NativeEncodingFailure.INVALID_TEXT)
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val bytes = try { encoder.encode(java.nio.CharBuffer.wrap(value)).remaining() }
                catch (_: java.nio.charset.CharacterCodingException) { throw NativeEncodingException(NativeEncodingFailure.INVALID_TEXT) }
            nativeRequire(bytes <= MAXIMUM_UTF8_BYTES, NativeEncodingFailure.INVALID_TEXT)
            return NativeReplayText(value, bytes)
        }
    }
}

internal sealed interface NativeMaskedKind {
    data object Rectangle : NativeMaskedKind
    data object Text : NativeMaskedKind
    data class ReadableText(val text: NativeReplayText) : NativeMaskedKind
    data class Input(val secure: Boolean) : NativeMaskedKind
    data object Placeholder : NativeMaskedKind
}

/** Closed descriptive geometry provenance; this is never capture permission. */
internal enum class NativeGeometryKind { VISIBLE_CLIP, LAYOUT_BOUNDS }

internal data class NativeMaskedNode(
    /** Local projection identity never appears in encoded bytes. */
    val identity: UUID,
    val kind: NativeMaskedKind,
    val bounds: NativeRect,
    val clip: NativeRect,
    val style: NativeStyle = NativeStyle(),
    /** LAYOUT_BOUNDS may omit a cached outline or framework decoration/occlusion; every known clip is retained. */
    val geometry: NativeGeometryKind = NativeGeometryKind.VISIBLE_CLIP,
)

internal class NativeMaskedSnapshot(
    val ordinal: Long,
    /** Original positive integer Unix milliseconds; encoding never substitutes a clock. */
    val timestamp: Long,
    val viewport: NativeViewport,
    nodes: List<NativeMaskedNode>,
) {
    /** Immutable flat native paint order, with known clipping, explicit geometry provenance and privacy ancestry resolved. */
    val nodes: List<NativeMaskedNode> = Collections.unmodifiableList(ArrayList(nodes))
    val containsLayoutBounds: Boolean get() = nodes.any { it.geometry == NativeGeometryKind.LAYOUT_BOUNDS }
}

internal class NativeEncodedChunk(
    val sequence: Long,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val eventCount: Int,
    val nodeRepresentations: Int,
    bytes: ByteArray,
) {
    private val owned = bytes.copyOf()
    /** Canonical uncompressed inner events only, without envelope or permission. */
    val bytes: ByteArray get() = owned.copyOf()
}
