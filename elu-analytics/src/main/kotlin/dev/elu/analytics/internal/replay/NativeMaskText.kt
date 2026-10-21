package dev.elu.analytics.internal.replay

/**
 * Length-preserving replay masks, identical to the web rule. Every UTF-16 code unit that is not
 * whitespace becomes `*`; whitespace is kept. Inputs become one `*` per UTF-16 unit. Callers pass
 * a transient value and keep only the result: the original text never enters a snapshot,
 * encoder, buffer or log.
 */
internal object NativeMaskText {
    /** Engine text limit, in UTF-8 bytes. */
    const val MAXIMUM_BYTES: Int = 4_096
    const val STAR: Char = '*'

    /**
     * The 25 code units of ECMAScript `\s`. Platform whitespace predicates differ from this set
     * (NEL and ZWSP are starred, BOM is kept).
     */
    fun isWhitespace(unit: Char): Boolean = when (unit) {
        in '\u0009'..'\u000D', ' ', '\u00A0', '\u1680', in '\u2000'..'\u200A',
        '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> false
    }

    /**
     * Stops before the output's UTF-8 size would exceed [MAXIMUM_BYTES], so at most
     * [MAXIMUM_BYTES] source units are visited however long the text is. Only `length` and
     * `get` are called; callers pass a String or an exact framework CharSequence.
     */
    fun maskText(text: CharSequence): String {
        val length = text.length
        val output = StringBuilder(minOf(length, MAXIMUM_BYTES))
        var bytes = 0
        var index = 0
        // Every output unit takes at least one byte, so a full output stops before the next read.
        while (index < length && bytes < MAXIMUM_BYTES) {
            val unit = text[index]
            val kept = if (isWhitespace(unit)) unit else STAR
            val size = if (kept.code < 0x80) 1 else if (kept.code < 0x800) 2 else 3
            if (bytes + size > MAXIMUM_BYTES) break
            output.append(kept); bytes += size; index += 1
        }
        return output.toString()
    }

    /** `length` is the value's UTF-16 length; the value itself is never needed. */
    fun maskInput(utf16Length: Int): String = STAR.toString().repeat(utf16Length.coerceIn(0, MAXIMUM_BYTES))

    /**
     * True when every unit is `*` or whitespace, including the empty string. Such text cannot be
     * told apart from masked text on the wire.
     */
    fun isMaskShaped(text: String): Boolean = text.all { it == STAR || isWhitespace(it) }

    fun isInputMask(value: String): Boolean = value.all { it == STAR }

    /** UTF-8 size of a carried string; unpaired surrogates count as the 3-byte replacement. */
    fun utf8Bytes(text: String): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val unit = text[index]
            bytes += when {
                unit.code < 0x80 -> 1
                unit.code < 0x800 -> 2
                Character.isHighSurrogate(unit) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    index += 1; 4
                }
                else -> 3
            }
            index += 1
        }
        return bytes
    }
}
