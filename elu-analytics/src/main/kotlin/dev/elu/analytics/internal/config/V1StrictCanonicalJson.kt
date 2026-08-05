package dev.elu.analytics.internal.config

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Duplicate-safe RFC 8259 parser plus the frozen ELU v1/JCS projection rules.
 *
 * Android's JSONObject is intentionally not used here: it collapses duplicate names and loses
 * the exact numeric token before the authority identity is computed. Decoded strings are kept as
 * exact UTF-16 code units (there is deliberately no Unicode normalization).
 */
internal object V1StrictCanonicalJson {
    private const val MAX_NESTING = 64

    sealed interface Value {
        data class ObjectValue(val members: List<Pair<String, Value>>) : Value {
            fun member(name: String): Value? = members.firstOrNull { it.first == name }?.second
        }

        data class ArrayValue(val values: List<Value>) : Value

        data class StringValue(val value: String) : Value

        data class NumberValue(val token: String) : Value

        data class BooleanValue(val value: Boolean) : Value

        data object NullValue : Value
    }

    fun parse(source: String): Value = Parser(source).parse()

    fun canonicalize(value: Value): String =
        buildString { appendCanonical(value) }

    fun canonicalBytes(value: Value): ByteArray = canonicalize(value).toByteArray(StandardCharsets.UTF_8)

    fun sha256(value: Value): String = sha256(canonicalBytes(value))

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun StringBuilder.appendCanonical(value: Value) {
        when (value) {
            is Value.ObjectValue -> {
                append('{')
                value.members.sortedWith(compareBy<Pair<String, Value>> { it.first }).forEachIndexed { index, member ->
                    if (index > 0) append(',')
                    appendQuoted(member.first)
                    append(':')
                    appendCanonical(member.second)
                }
                append('}')
            }
            is Value.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendCanonical(child)
                }
                append(']')
            }
            is Value.StringValue -> appendQuoted(value.value)
            is Value.NumberValue -> append(canonicalNumber(value.token))
            is Value.BooleanValue -> append(if (value.value) "true" else "false")
            Value.NullValue -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun canonicalNumber(token: String): String {
        val lexeme = DecimalLexeme.parse(token)
        lexeme.exactInt64()?.let { return it.toString() }

        val binary64 = token.toDoubleOrNull()
            ?: malformed("JSON number is outside the finite binary64 domain")
        if (!binary64.isFinite()) malformed("JSON number is outside the finite binary64 domain")
        if (binary64 == 0.0) return "0"
        return EcmaScriptBinary64.serialize(binary64)
    }

    /**
     * Bounded lexical projection used before any arbitrary-precision conversion. In particular,
     * an exponent such as `1e100000000` is rejected while it is still a handful of characters;
     * no scale-sized BigInteger is ever requested.
     */
    private data class DecimalLexeme(
        val negative: Boolean,
        val digits: String,
        val fractionDigits: Int,
        val exponent: Int,
    ) {
        fun exactInt64(): Long? {
            val firstNonZero = digits.indexOfFirst { it != '0' }
            if (firstNonZero < 0) return 0L
            val scale = fractionDigits - exponent
            val integerDigits =
                when {
                    scale <= 0 -> {
                        val significant = digits.substring(firstNonZero)
                        val appendedZeros = -scale
                        if (significant.length + appendedZeros > MAX_INT64_DECIMAL_DIGITS) return null
                        significant + "0".repeat(appendedZeros)
                    }
                    scale >= digits.length -> return null
                    digits.regionMatches(digits.length - scale, ZERO_PADDING, 0, scale) -> {
                        digits.substring(0, digits.length - scale).trimStart('0').ifEmpty { "0" }
                    }
                    else -> return null
                }
            if (integerDigits.length > MAX_INT64_DECIMAL_DIGITS) return null
            return (if (negative) "-$integerDigits" else integerDigits).toLongOrNull()
        }

        companion object {
            fun parse(token: String): DecimalLexeme {
                if (token.length > MAX_NUMBER_TOKEN_CHARACTERS) {
                    malformed("JSON number exceeds the bounded numeric token length")
                }
                var cursor = 0
                val negative = token.getOrNull(cursor) == '-'
                if (negative) cursor += 1
                val digits = StringBuilder(token.length)
                while (cursor < token.length && token[cursor] in '0'..'9') digits.append(token[cursor++])
                var fractionDigits = 0
                if (cursor < token.length && token[cursor] == '.') {
                    cursor += 1
                    val fractionStart = digits.length
                    while (cursor < token.length && token[cursor] in '0'..'9') digits.append(token[cursor++])
                    fractionDigits = digits.length - fractionStart
                }
                var exponent = 0
                if (cursor < token.length && (token[cursor] == 'e' || token[cursor] == 'E')) {
                    cursor += 1
                    val exponentNegative = token.getOrNull(cursor) == '-'
                    if (token.getOrNull(cursor) == '-' || token.getOrNull(cursor) == '+') cursor += 1
                    var magnitude = 0
                    while (cursor < token.length && token[cursor] in '0'..'9') {
                        val digit = token[cursor++].digitToInt()
                        if (magnitude > (MAX_ABSOLUTE_DECIMAL_EXPONENT - digit) / 10) {
                            malformed("JSON number exponent exceeds the bounded numeric domain")
                        }
                        magnitude = magnitude * 10 + digit
                    }
                    exponent = if (exponentNegative) -magnitude else magnitude
                }
                if (cursor != token.length || digits.isEmpty()) malformed("JSON number is malformed")
                if (digits.length > MAX_NUMBER_SIGNIFICAND_DIGITS) {
                    malformed("JSON number precision exceeds the bounded numeric domain")
                }
                return DecimalLexeme(negative, digits.toString(), fractionDigits, exponent)
            }
        }
    }

    /**
     * Platform-independent implementation of ECMAScript Number::toString for finite binary64.
     * It searches the exact midpoint interval using BigInteger rationals instead of inheriting
     * Java/Android's platform-specific Double.toString spelling.
     */
    private object EcmaScriptBinary64 {
        private data class BinaryRational(
            val numerator: BigInteger,
            val denominatorPowerOfTwo: Int,
        )

        private data class Ratio(val numerator: BigInteger, val denominator: BigInteger)

        private data class Candidate(val coefficient: BigInteger, val decimalPower: Int)

        fun serialize(value: Double): String {
            val negative = value < 0.0
            val positiveBits = java.lang.Double.doubleToRawLongBits(value) and Long.MAX_VALUE
            val exact = rationalForBits(positiveBits)
            val previous = rationalForBits(positiveBits - 1L)
            val next =
                if (positiveBits == MAX_FINITE_BITS) {
                    BinaryRational(BigInteger.ONE.shiftLeft(1024), 0)
                } else {
                    rationalForBits(positiveBits + 1L)
                }
            val lower = midpoint(previous, exact)
            val upper = midpoint(exact, next)
            val midpointInclusive = positiveBits and 1L == 0L
            val decimalOrder = decimalOrder(exact, positiveBits)

            var best: Candidate? = null
            for (digits in 1..MAX_BINARY64_SIGNIFICANT_DIGITS) {
                val minimumCoefficient = powerOfTen(digits - 1)
                val maximumCoefficient = powerOfTen(digits).subtract(BigInteger.ONE)
                val basePower = decimalOrder - digits + 1
                for (decimalPower in basePower - 1..basePower + 2) {
                    val lowerScaled = divideByPowerOfTen(lower, decimalPower)
                    val upperScaled = divideByPowerOfTen(upper, decimalPower)
                    var minimum =
                        if (midpointInclusive) ceil(lowerScaled) else floor(lowerScaled).add(BigInteger.ONE)
                    var maximum =
                        if (midpointInclusive) floor(upperScaled) else ceil(upperScaled).subtract(BigInteger.ONE)
                    if (minimum < minimumCoefficient) minimum = minimumCoefficient
                    if (maximum > maximumCoefficient) maximum = maximumCoefficient
                    if (minimum > maximum) continue

                    val exactScaled = divideByPowerOfTen(exact, decimalPower)
                    val nearest = nearestInteger(exactScaled).coerceIn(minimum, maximum)
                    val candidate = Candidate(nearest, decimalPower)
                    val current = best
                    if (current == null || compareCandidates(candidate, current, exact) < 0) best = candidate
                }
                best?.let { candidate ->
                    val body = format(candidate)
                    return if (negative) "-$body" else body
                }
            }
            malformed("Finite binary64 value has no shortest decimal representation")
        }

        private fun rationalForBits(bits: Long): BinaryRational {
            if (bits == 0L) return BinaryRational(BigInteger.ZERO, 0)
            val rawExponent = ((bits ushr 52) and 0x7ffL).toInt()
            val fraction = bits and FRACTION_MASK
            val significand =
                if (rawExponent == 0) {
                    BigInteger.valueOf(fraction)
                } else {
                    BigInteger.valueOf((1L shl 52) or fraction)
                }
            val binaryPower = if (rawExponent == 0) -1074 else rawExponent - 1023 - 52
            return if (binaryPower >= 0) {
                BinaryRational(significand.shiftLeft(binaryPower), 0)
            } else {
                BinaryRational(significand, -binaryPower)
            }
        }

        private fun midpoint(left: BinaryRational, right: BinaryRational): BinaryRational {
            val commonPower = maxOf(left.denominatorPowerOfTwo, right.denominatorPowerOfTwo)
            val numerator =
                left.numerator.shiftLeft(commonPower - left.denominatorPowerOfTwo)
                    .add(right.numerator.shiftLeft(commonPower - right.denominatorPowerOfTwo))
            return BinaryRational(numerator, commonPower + 1)
        }

        private fun decimalOrder(value: BinaryRational, bits: Long): Int {
            val rawExponent = ((bits ushr 52) and 0x7ffL).toInt()
            val fraction = bits and FRACTION_MASK
            val binaryOrder =
                if (rawExponent == 0) {
                    63 - java.lang.Long.numberOfLeadingZeros(fraction) - 1074
                } else {
                    rawExponent - 1023
                }
            var order = kotlin.math.floor(binaryOrder * LOG10_OF_TWO).toInt()
            while (compareToPowerOfTen(value, order) < 0) order -= 1
            while (compareToPowerOfTen(value, order + 1) >= 0) order += 1
            return order
        }

        private fun compareToPowerOfTen(value: BinaryRational, decimalPower: Int): Int {
            val denominator = BigInteger.ONE.shiftLeft(value.denominatorPowerOfTwo)
            return if (decimalPower >= 0) {
                value.numerator.compareTo(powerOfTen(decimalPower).multiply(denominator))
            } else {
                value.numerator.multiply(powerOfTen(-decimalPower)).compareTo(denominator)
            }
        }

        private fun divideByPowerOfTen(value: BinaryRational, decimalPower: Int): Ratio {
            val denominator = BigInteger.ONE.shiftLeft(value.denominatorPowerOfTwo)
            return if (decimalPower >= 0) {
                Ratio(value.numerator, denominator.multiply(powerOfTen(decimalPower)))
            } else {
                Ratio(value.numerator.multiply(powerOfTen(-decimalPower)), denominator)
            }
        }

        private fun floor(value: Ratio): BigInteger = value.numerator.divide(value.denominator)

        private fun ceil(value: Ratio): BigInteger {
            val division = value.numerator.divideAndRemainder(value.denominator)
            return if (division[1].signum() == 0) division[0] else division[0].add(BigInteger.ONE)
        }

        private fun nearestInteger(value: Ratio): BigInteger {
            val division = value.numerator.divideAndRemainder(value.denominator)
            val floor = division[0]
            val comparison = division[1].shiftLeft(1).compareTo(value.denominator)
            return when {
                comparison < 0 -> floor
                comparison > 0 -> floor.add(BigInteger.ONE)
                floor.and(BigInteger.ONE) == BigInteger.ZERO -> floor
                else -> floor.add(BigInteger.ONE)
            }
        }

        private fun BigInteger.coerceIn(minimum: BigInteger, maximum: BigInteger): BigInteger =
            when {
                this < minimum -> minimum
                this > maximum -> maximum
                else -> this
            }

        private fun compareCandidates(left: Candidate, right: Candidate, exact: BinaryRational): Int {
            val leftDistance = distance(left, exact)
            val rightDistance = distance(right, exact)
            val distanceComparison =
                leftDistance.numerator.multiply(rightDistance.denominator)
                    .compareTo(rightDistance.numerator.multiply(leftDistance.denominator))
            if (distanceComparison != 0) return distanceComparison
            val leftEven = left.coefficient.and(BigInteger.ONE) == BigInteger.ZERO
            val rightEven = right.coefficient.and(BigInteger.ONE) == BigInteger.ZERO
            if (leftEven != rightEven) return if (leftEven) -1 else 1
            return format(left).compareTo(format(right))
        }

        private fun distance(candidate: Candidate, exact: BinaryRational): Ratio {
            val exactDenominator = BigInteger.ONE.shiftLeft(exact.denominatorPowerOfTwo)
            val candidateNumerator: BigInteger
            val candidateDenominator: BigInteger
            if (candidate.decimalPower >= 0) {
                candidateNumerator = candidate.coefficient.multiply(powerOfTen(candidate.decimalPower))
                candidateDenominator = BigInteger.ONE
            } else {
                candidateNumerator = candidate.coefficient
                candidateDenominator = powerOfTen(-candidate.decimalPower)
            }
            val numerator =
                candidateNumerator.multiply(exactDenominator)
                    .subtract(exact.numerator.multiply(candidateDenominator))
                    .abs()
            return Ratio(numerator, candidateDenominator.multiply(exactDenominator))
        }

        private fun format(candidate: Candidate): String {
            val digits = candidate.coefficient.toString()
            val exponent = candidate.decimalPower + digits.length - 1
            return when {
                exponent in 0..20 -> {
                    val integerDigits = exponent + 1
                    if (digits.length <= integerDigits) {
                        digits + "0".repeat(integerDigits - digits.length)
                    } else {
                        digits.substring(0, integerDigits) + "." + digits.substring(integerDigits)
                    }
                }
                exponent in -6..-1 -> "0." + "0".repeat(-exponent - 1) + digits
                else ->
                    buildString {
                        append(digits[0])
                        if (digits.length > 1) {
                            append('.')
                            append(digits, 1, digits.length)
                        }
                        append('e')
                        if (exponent >= 0) append('+')
                        append(exponent)
                    }
            }
        }

        private fun powerOfTen(exponent: Int): BigInteger {
            if (exponent !in 0..MAX_POWER_OF_TEN) malformed("Binary64 decimal exponent is outside the supported range")
            return POWERS_OF_TEN[exponent]
        }

        private val POWERS_OF_TEN: Array<BigInteger> =
            Array(MAX_POWER_OF_TEN + 1) { exponent -> BigInteger.TEN.pow(exponent) }
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Value {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (index != source.length) fail("JSON contains trailing data")
            return value
        }

        private fun parseValue(depth: Int): Value {
            if (index >= source.length) fail("JSON value is missing")
            return when (source[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> Value.StringValue(parseString())
                't' -> parseLiteral("true", Value.BooleanValue(true))
                'f' -> parseLiteral("false", Value.BooleanValue(false))
                'n' -> parseLiteral("null", Value.NullValue)
                '-', in '0'..'9' -> Value.NumberValue(parseNumber())
                else -> fail("JSON contains a non-standard value")
            }
        }

        private fun parseObject(depth: Int): Value.ObjectValue {
            requireDepth(depth)
            index += 1
            skipWhitespace()
            if (consume('}')) return Value.ObjectValue(emptyList())
            val names = HashSet<String>()
            val members = ArrayList<Pair<String, Value>>()
            while (true) {
                if (index >= source.length || source[index] != '"') fail("JSON object name must use double quotes")
                val name = parseString()
                if (!names.add(name)) fail("JSON object contains a duplicate decoded member")
                skipWhitespace()
                requireCharacter(':')
                skipWhitespace()
                members += name to parseValue(depth)
                skipWhitespace()
                when {
                    consume('}') -> return Value.ObjectValue(members)
                    consume(',') -> {
                        skipWhitespace()
                        if (index < source.length && source[index] == '}') fail("JSON object contains a trailing comma")
                    }
                    else -> fail("JSON object members must be comma-separated")
                }
            }
        }

        private fun parseArray(depth: Int): Value.ArrayValue {
            requireDepth(depth)
            index += 1
            skipWhitespace()
            if (consume(']')) return Value.ArrayValue(emptyList())
            val values = ArrayList<Value>()
            while (true) {
                values += parseValue(depth)
                skipWhitespace()
                when {
                    consume(']') -> return Value.ArrayValue(values)
                    consume(',') -> {
                        skipWhitespace()
                        if (index < source.length && source[index] == ']') fail("JSON array contains a trailing comma")
                    }
                    else -> fail("JSON array values must be comma-separated")
                }
            }
        }

        private fun parseString(): String {
            requireCharacter('"')
            val decoded = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> {
                        validateUnicodeScalarSequence(decoded)
                        return decoded.toString()
                    }
                    character == '\\' -> decoded.append(parseEscape())
                    character.code < 0x20 -> fail("JSON string contains an unescaped control character")
                    else -> decoded.append(character)
                }
            }
            fail("JSON string is unterminated")
        }

        private fun parseEscape(): Char {
            if (index >= source.length) fail("JSON string escape is unterminated")
            return when (val escape = source[index++]) {
                '"', '\\', '/' -> escape
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > source.length) fail("JSON Unicode escape is truncated")
                    val digits = source.substring(index, index + 4)
                    if (digits.any { it.digitToIntOrNull(16) == null }) fail("JSON Unicode escape is malformed")
                    index += 4
                    digits.toInt(16).toChar()
                }
                else -> fail("JSON string contains an unsupported escape")
            }
        }

        private fun parseNumber(): String {
            val start = index
            consume('-')
            if (index >= source.length) fail("JSON number is truncated")
            if (consume('0')) {
                if (index < source.length && source[index] in '0'..'9') fail("JSON number contains a leading zero")
            } else {
                if (source[index] !in '1'..'9') fail("JSON number integer part is malformed")
                while (index < source.length && source[index] in '0'..'9') index += 1
            }
            if (consume('.')) {
                val fractionStart = index
                while (index < source.length && source[index] in '0'..'9') index += 1
                if (index == fractionStart) fail("JSON number fraction is missing digits")
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index += 1
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index += 1
                val exponentStart = index
                while (index < source.length && source[index] in '0'..'9') index += 1
                if (index == exponentStart) fail("JSON number exponent is missing digits")
            }
            val token = source.substring(start, index)
            canonicalNumber(token) // Validate the supported numeric domain now.
            return token
        }

        private fun <T : Value> parseLiteral(literal: String, value: T): T {
            if (!source.regionMatches(index, literal, 0, literal.length)) fail("JSON literal is malformed")
            index += literal.length
            return value
        }

        private fun validateUnicodeScalarSequence(value: CharSequence) {
            var characterIndex = 0
            while (characterIndex < value.length) {
                val character = value[characterIndex]
                when {
                    Character.isHighSurrogate(character) -> {
                        if (characterIndex + 1 >= value.length || !Character.isLowSurrogate(value[characterIndex + 1])) {
                            fail("JSON string contains an unpaired Unicode surrogate")
                        }
                        characterIndex += 2
                    }
                    Character.isLowSurrogate(character) -> fail("JSON string contains an unpaired Unicode surrogate")
                    else -> characterIndex += 1
                }
            }
        }

        private fun requireDepth(depth: Int) {
            if (depth > MAX_NESTING) fail("JSON exceeds the maximum nesting depth")
        }

        private fun requireCharacter(expected: Char) {
            if (!consume(expected)) fail("JSON is missing '$expected'")
        }

        private fun consume(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index += 1
            return true
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in JSON_WHITESPACE) index += 1
        }

        private fun fail(message: String): Nothing = malformed(message)
    }

    private fun malformed(message: String, cause: Throwable? = null): Nothing =
        throw V1MalformedConfigException(message, cause)

    private const val MAX_NUMBER_TOKEN_CHARACTERS = 2_048
    private const val MAX_NUMBER_SIGNIFICAND_DIGITS = 1_024
    private const val MAX_ABSOLUTE_DECIMAL_EXPONENT = 10_000
    private const val MAX_INT64_DECIMAL_DIGITS = 19
    private const val MAX_BINARY64_SIGNIFICANT_DIGITS = 17
    private const val MAX_POWER_OF_TEN = 400
    private const val MAX_FINITE_BITS = 0x7fefffffffffffffL
    private const val FRACTION_MASK = 0x000fffffffffffffL
    private const val LOG10_OF_TWO = 0.3010299956639812
    private val ZERO_PADDING = "0".repeat(MAX_NUMBER_SIGNIFICAND_DIGITS)
    private val JSON_WHITESPACE = setOf(' ', '\t', '\n', '\r')
}
