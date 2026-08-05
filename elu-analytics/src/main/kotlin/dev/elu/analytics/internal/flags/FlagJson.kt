package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.V1MalformedConfigException
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

internal const val FLAG_MAX_WIRE_BYTES: Int = 1_048_576
internal const val FLAG_MAX_CACHE_BYTES: Int = 4_194_304
internal const val FLAG_MAX_DEPTH: Int = 16
internal const val FLAG_MAX_NODES: Int = 4_096
internal const val FLAG_MAX_COLLECTION_ENTRIES: Int = 1_024
internal const val FLAG_MAX_STRING_SCALARS: Int = 65_536
internal const val FLAG_MAX_KEY_SCALARS: Int = 256
internal const val FLAG_MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

internal class FlagProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Duplicate-safe flag JSON domain. Object members remain ordered pairs of exact UTF-16 keys for
 * validation, canonicalization, persistence, and lookup; no platform map is an authority.
 */
internal sealed interface FlagJsonValue {
    data class ObjectValue(val members: List<Member>) : FlagJsonValue {
        data class Member(val key: String, val value: FlagJsonValue)

        fun member(key: String): FlagJsonValue? = members.firstOrNull { it.key == key }?.value

        fun contains(key: String): Boolean = members.any { it.key == key }
    }

    data class ArrayValue(val values: List<FlagJsonValue>) : FlagJsonValue

    data class StringValue(val value: String) : FlagJsonValue

    /** [canonical] is the RFC 8785/ECMAScript spelling; negative zero is always stored as `0`. */
    data class NumberValue(val canonical: String, val value: Double) : FlagJsonValue

    data class BooleanValue(val value: Boolean) : FlagJsonValue

    data object NullValue : FlagJsonValue
}

internal object FlagJson {
    internal enum class StoredSchemaDisposition {
        CURRENT,
        FUTURE,
        CORRUPT,
    }

    /**
     * Reads only the top-level storage schema discriminator. Future envelopes must be recognized
     * before v1 depth/node budgets are applied so their bytes can be preserved verbatim.
     */
    fun probeStoredSchema(
        bytes: ByteArray,
        maximumBytes: Int,
    ): StoredSchemaDisposition {
        if (bytes.isEmpty() || bytes.size > maximumBytes) return StoredSchemaDisposition.CORRUPT
        if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
            return StoredSchemaDisposition.CORRUPT
        }
        val source =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: Throwable) {
                return StoredSchemaDisposition.CORRUPT
            }
        return StorageSchemaScanner(source).probe()
    }

    fun parse(
        bytes: ByteArray,
        maximumBytes: Int = FLAG_MAX_WIRE_BYTES,
        maximumNodes: Int = FLAG_MAX_NODES,
    ): FlagJsonValue {
        if (bytes.size > maximumBytes) protocol("Flag JSON exceeds $maximumBytes bytes")
        if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
            protocol("Flag JSON must not start with a UTF-8 BOM")
        }
        val source =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: Throwable) {
                protocol("Flag JSON is not valid UTF-8", error)
            }
        val parsed =
            try {
                V1StrictCanonicalJson.parse(source)
            } catch (error: V1MalformedConfigException) {
                protocol("Flag JSON is malformed", error)
            }
        return Converter(maximumNodes).convert(parsed, depth = 0, key = false)
    }

    fun fromPlatform(
        value: Any?,
        maximumNodes: Int = FLAG_MAX_NODES,
    ): FlagJsonValue = PlatformConverter(maximumNodes).convert(value, depth = 0, key = false)

    fun canonicalBytes(value: FlagJsonValue): ByteArray =
        buildString { appendCanonical(value) }.toByteArray(StandardCharsets.UTF_8)

    fun canonicalString(value: FlagJsonValue): String = String(canonicalBytes(value), StandardCharsets.UTF_8)

    fun sha256(value: FlagJsonValue): String = sha256(canonicalBytes(value))

    fun sha256(bytes: ByteArray): String =
        "sha256:" +
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    fun exactLong(value: FlagJsonValue?, path: String): Long {
        val number = value as? FlagJsonValue.NumberValue ?: protocol("$path must be an integer")
        val decimal = BigDecimal(number.canonical)
        val integer = runCatching { decimal.toBigIntegerExact() }.getOrElse { protocol("$path must be an integer") }
        if (integer.abs() > MAX_SAFE_INTEGER_BIG) protocol("$path exceeds the safe-integer domain")
        return integer.toLong()
    }

    fun requiredString(value: FlagJsonValue?, path: String, minimum: Int, maximum: Int): String {
        val string = (value as? FlagJsonValue.StringValue)?.value ?: protocol("$path must be a string")
        val length = scalarCount(string, path)
        if (length !in minimum..maximum) protocol("$path is outside its string-length bounds")
        return string
    }

    fun requiredObject(value: FlagJsonValue?, path: String): FlagJsonValue.ObjectValue =
        value as? FlagJsonValue.ObjectValue ?: protocol("$path must be an object")

    fun requiredBoolean(value: FlagJsonValue?, path: String): Boolean =
        (value as? FlagJsonValue.BooleanValue)?.value ?: protocol("$path must be a boolean")

    fun requireFields(
        value: FlagJsonValue.ObjectValue,
        required: Set<String>,
        optional: Set<String>,
        path: String,
    ) {
        val names = value.members.map { it.key }.toSet()
        val missing = required - names
        if (missing.isNotEmpty()) protocol("$path is missing ${missing.sorted().joinToString()}")
        val unknown = names - required - optional
        if (unknown.isNotEmpty()) protocol("$path contains unknown fields ${unknown.sorted().joinToString()}")
    }

    private fun StringBuilder.appendCanonical(value: FlagJsonValue) {
        when (value) {
            is FlagJsonValue.ObjectValue -> {
                append('{')
                value.members
                    .sortedWith(compareBy<FlagJsonValue.ObjectValue.Member> { it.key })
                    .forEachIndexed { index, member ->
                        if (index > 0) append(',')
                        appendQuoted(member.key)
                        append(':')
                        appendCanonical(member.value)
                    }
                append('}')
            }
            is FlagJsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, child ->
                    if (index > 0) append(',')
                    appendCanonical(child)
                }
                append(']')
            }
            is FlagJsonValue.StringValue -> appendQuoted(value.value)
            is FlagJsonValue.NumberValue -> append(value.canonical)
            is FlagJsonValue.BooleanValue -> append(if (value.value) "true" else "false")
            FlagJsonValue.NullValue -> append("null")
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
                else ->
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }

    private class Converter(private val maximumNodes: Int) {
        private var nodes = 0

        fun convert(
            value: V1StrictCanonicalJson.Value,
            depth: Int,
            key: Boolean,
        ): FlagJsonValue {
            addNode(depth)
            return when (value) {
                is V1StrictCanonicalJson.Value.ObjectValue -> {
                    if (value.members.size > FLAG_MAX_COLLECTION_ENTRIES) protocol("Flag JSON object exceeds the entry limit")
                    val members =
                        value.members
                            .sortedWith(compareBy<Pair<String, V1StrictCanonicalJson.Value>> { it.first })
                            .map { (name, child) ->
                                validateString(name, key = true)
                                FlagJsonValue.ObjectValue.Member(name, convert(child, depth + 1, key = false))
                            }
                    FlagJsonValue.ObjectValue(Collections.unmodifiableList(members))
                }
                is V1StrictCanonicalJson.Value.ArrayValue -> {
                    if (value.values.size > FLAG_MAX_COLLECTION_ENTRIES) protocol("Flag JSON array exceeds the entry limit")
                    FlagJsonValue.ArrayValue(
                        Collections.unmodifiableList(value.values.map { convert(it, depth + 1, key = false) }),
                    )
                }
                is V1StrictCanonicalJson.Value.StringValue -> {
                    validateString(value.value, key)
                    FlagJsonValue.StringValue(value.value)
                }
                is V1StrictCanonicalJson.Value.NumberValue -> normalizeNumber(value.token)
                is V1StrictCanonicalJson.Value.BooleanValue -> FlagJsonValue.BooleanValue(value.value)
                V1StrictCanonicalJson.Value.NullValue -> FlagJsonValue.NullValue
            }
        }

        private fun addNode(depth: Int) {
            if (depth > FLAG_MAX_DEPTH) protocol("Flag JSON exceeds depth $FLAG_MAX_DEPTH")
            nodes += 1
            if (nodes > maximumNodes) protocol("Flag JSON exceeds $maximumNodes nodes")
        }
    }

    private class PlatformConverter(private val maximumNodes: Int) {
        private var nodes = 0

        fun convert(value: Any?, depth: Int, key: Boolean): FlagJsonValue {
            if (depth > FLAG_MAX_DEPTH) protocol("Flag JSON exceeds depth $FLAG_MAX_DEPTH")
            nodes += 1
            if (nodes > maximumNodes) protocol("Flag JSON exceeds $maximumNodes nodes")
            return when (value) {
                null -> FlagJsonValue.NullValue
                is Boolean -> FlagJsonValue.BooleanValue(value)
                is String -> {
                    validateString(value, key)
                    FlagJsonValue.StringValue(value)
                }
                is Number -> normalizeNumber(value.toString())
                is Map<*, *> -> {
                    if (value.size > FLAG_MAX_COLLECTION_ENTRIES) protocol("Flag JSON object exceeds the entry limit")
                    val seen = HashSet<String>()
                    val members =
                        value.entries
                            .sortedWith(
                                compareBy { entry ->
                                    entry.key as? String ?: protocol("Flag JSON object keys must be strings")
                                },
                            )
                            .map { entry ->
                                val name = entry.key as? String ?: protocol("Flag JSON object keys must be strings")
                                validateString(name, key = true)
                                if (!seen.add(name)) protocol("Flag JSON object contains a duplicate decoded member")
                                FlagJsonValue.ObjectValue.Member(name, convert(entry.value, depth + 1, key = false))
                            }
                    FlagJsonValue.ObjectValue(Collections.unmodifiableList(members))
                }
                is List<*> -> {
                    if (value.size > FLAG_MAX_COLLECTION_ENTRIES) protocol("Flag JSON array exceeds the entry limit")
                    FlagJsonValue.ArrayValue(Collections.unmodifiableList(value.map { convert(it, depth + 1, key = false) }))
                }
                else -> protocol("Flag JSON contains unsupported value ${value.javaClass.name}")
            }
        }
    }

    private fun normalizeNumber(token: String): FlagJsonValue.NumberValue {
        // Run the bounded lexical/binary64 implementation first. Constructing a BigDecimal before
        // that check could expand an adversarial exponent while deciding the safe-integer rule.
        val canonical =
            try {
                V1StrictCanonicalJson.canonicalize(V1StrictCanonicalJson.Value.NumberValue(token))
            } catch (error: V1MalformedConfigException) {
                protocol("Flag JSON number is outside the supported domain", error)
            }
        if (INTEGER_TOKEN.matches(canonical)) {
            val normalizedInteger =
                try {
                    BigDecimal(canonical).toBigIntegerExact()
                } catch (error: NumberFormatException) {
                    protocol("Flag JSON number is malformed", error)
                }
            if (normalizedInteger.abs() > MAX_SAFE_INTEGER_BIG) {
                protocol("Flag JSON integer exceeds the safe-integer domain")
            }
        }
        val binary64 = token.toDoubleOrNull() ?: protocol("Flag JSON number is outside binary64")
        if (!binary64.isFinite()) protocol("Flag JSON number is outside finite binary64")
        return if (binary64 == 0.0) {
            FlagJsonValue.NumberValue("0", 0.0)
        } else {
            FlagJsonValue.NumberValue(canonical, binary64)
        }
    }

    private fun validateString(value: String, key: Boolean) {
        val count = scalarCount(value, if (key) "Flag JSON key" else "Flag JSON string")
        val maximum = if (key) FLAG_MAX_KEY_SCALARS else FLAG_MAX_STRING_SCALARS
        if (count > maximum) protocol("Flag JSON ${if (key) "key" else "string"} exceeds $maximum scalars")
    }

    private fun scalarCount(value: String, path: String): Int {
        var index = 0
        var count = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        protocol("$path contains an unpaired Unicode surrogate")
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> protocol("$path contains an unpaired Unicode surrogate")
                else -> index += 1
            }
            count += 1
        }
        return count
    }

    private val MAX_SAFE_INTEGER_BIG = BigInteger.valueOf(FLAG_MAX_SAFE_INTEGER)
    private val INTEGER_TOKEN = Regex("^-?(?:0|[1-9][0-9]*)$")

    /** Bounded-allocation lexical scanner; nested future content is skipped without recursion. */
    private class StorageSchemaScanner(private val source: String) {
        private var index: Int = 0

        fun probe(): StoredSchemaDisposition {
            skipWhitespace()
            if (!consume('{')) return StoredSchemaDisposition.CORRUPT
            skipWhitespace()
            if (consume('}')) return StoredSchemaDisposition.CORRUPT
            while (index < source.length) {
                val key = parseString() ?: return StoredSchemaDisposition.CORRUPT
                skipWhitespace()
                if (!consume(':')) return StoredSchemaDisposition.CORRUPT
                skipWhitespace()
                if (key == "schemaVersion") {
                    val start = index
                    if (index < source.length && source[index] == '-') index += 1
                    if (index >= source.length || !source[index].isDigit()) return StoredSchemaDisposition.CORRUPT
                    if (source[index] == '0') {
                        index += 1
                    } else {
                        while (index < source.length && source[index].isDigit()) index += 1
                    }
                    val token = source.substring(start, index)
                    val version = token.toLongOrNull() ?: return StoredSchemaDisposition.CORRUPT
                    return when {
                        version == FLAG_STORAGE_SCHEMA_VERSION.toLong() -> StoredSchemaDisposition.CURRENT
                        version > FLAG_STORAGE_SCHEMA_VERSION.toLong() -> StoredSchemaDisposition.FUTURE
                        else -> StoredSchemaDisposition.CORRUPT
                    }
                }
                if (!skipValue()) return StoredSchemaDisposition.CORRUPT
                skipWhitespace()
                when {
                    consume(',') -> {
                        skipWhitespace()
                        continue
                    }
                    consume('}') -> return StoredSchemaDisposition.CORRUPT
                    else -> return StoredSchemaDisposition.CORRUPT
                }
            }
            return StoredSchemaDisposition.CORRUPT
        }

        private fun skipValue(): Boolean {
            if (index >= source.length) return false
            return when (source[index]) {
                '"' -> parseString() != null
                '{', '[' -> skipComposite()
                else -> {
                    val start = index
                    while (index < source.length && source[index] !in charArrayOf(',', '}', ']') && !source[index].isWhitespace()) {
                        index += 1
                    }
                    index > start
                }
            }
        }

        private fun skipComposite(): Boolean {
            var depth = 0
            while (index < source.length) {
                when (source[index]) {
                    '"' -> if (parseString() == null) return false
                    '{', '[' -> {
                        depth += 1
                        index += 1
                    }
                    '}', ']' -> {
                        depth -= 1
                        index += 1
                        if (depth == 0) return true
                        if (depth < 0) return false
                    }
                    else -> index += 1
                }
            }
            return false
        }

        private fun parseString(): String? {
            if (!consume('"')) return null
            val result = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> {
                        if (index >= source.length) return null
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (index + 4 > source.length) return null
                                val code = source.substring(index, index + 4).toIntOrNull(16) ?: return null
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> return null
                        }
                    }
                    character.code < 0x20 -> return null
                    else -> result.append(character)
                }
            }
            return null
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in charArrayOf(' ', '\n', '\r', '\t')) index += 1
        }

        private fun consume(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index += 1
            return true
        }
    }

    private fun protocol(message: String, cause: Throwable? = null): Nothing =
        throw FlagProtocolException(message, cause)
}

private fun protocol(message: String, cause: Throwable? = null): Nothing =
    throw FlagProtocolException(message, cause)
