package dev.elu.analytics.internal.runtime.delivery

import dev.elu.analytics.internal.runtime.RuntimeAcknowledgement
import dev.elu.analytics.internal.runtime.RuntimeQueuedRecord
import dev.elu.analytics.internal.runtime.RuntimeRecordCodec
import dev.elu.analytics.internal.runtime.RuntimeRecordKind
import dev.elu.analytics.internal.runtime.RuntimeRecordReference
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val MAX_RUNTIME_RECORD_AGE_SECONDS: Int = 604_800
internal const val MAX_TRANSPORT_ERROR_BYTES: Int = 65_536

internal class BatchProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal class BatchPackedRequest private constructor(
    val requestId: String,
    val streamId: String,
    val sentAt: BatchWallInstant,
    val versions: RuntimeVersions,
    records: List<RuntimeQueuedRecord>,
    body: ByteArray,
) {
    val records: List<RuntimeQueuedRecord> = Collections.unmodifiableList(records.toList())
    private val body = body.copyOf()

    val references: List<RuntimeRecordReference> =
        Collections.unmodifiableList(this.records.map(::referenceOf))

    fun bodyBytes(): ByteArray = body.copyOf()

    val bodySize: Int
        get() = body.size

    companion object {
        fun create(
            requestId: String,
            streamId: String,
            sentAt: BatchWallInstant,
            versions: RuntimeVersions,
            records: List<RuntimeQueuedRecord>,
            body: ByteArray,
        ): BatchPackedRequest = BatchPackedRequest(requestId, streamId, sentAt, versions, records, body)
    }
}

internal sealed interface BatchPackingResult {
    data class Packed(val request: BatchPackedRequest) : BatchPackingResult

    data class OversizedHead(val reference: RuntimeRecordReference) : BatchPackingResult
}

/** Canonical v1 request packing and deterministic request identity. */
internal object V1BatchRequestCodec {
    fun packLargest(
        queued: List<RuntimeQueuedRecord>,
        sentAt: BatchWallInstant,
        maximumCount: Int,
        maximumBytes: Int,
    ): BatchPackingResult {
        require(queued.isNotEmpty()) { "queued must not be empty" }
        require(maximumCount in 1..1_000)
        require(maximumBytes in 1_024..10_485_760)
        val head = queued.first()
        val versions = versionsOf(head)
        val streamId = head.streamId
        val eligible = ArrayList<RuntimeQueuedRecord>(minOf(queued.size, maximumCount))
        for (record in queued.take(maximumCount)) {
            if (record.streamId != streamId || versionsOf(record) != versions) break
            eligible += record
        }
        if (eligible.isEmpty()) throw BatchProtocolException("Queue head could not be selected")

        val versionBytes = RuntimeRecordCodec.encodeBatchVersions(versions)
        val placeholderRequestId = "request_" + "0".repeat(64)
        val prefix = encodePrefix(placeholderRequestId, streamId, sentAt.rfc3339, versionBytes)
        var recordsBytes = 0L
        var selectedCount = 0
        eligible.forEachIndexed { index, record ->
            val encoded = RuntimeRecordCodec.encodeBatchRecord(record)
            if (encoded.size != record.accountedBytes) {
                throw BatchProtocolException("Queued record byte accounting changed before delivery")
            }
            recordsBytes = Math.addExact(recordsBytes, encoded.size.toLong())
            val completeBytes =
                Math.addExact(
                    Math.addExact(prefix.size.toLong(), recordsBytes),
                    index.toLong() + BATCH_SUFFIX.size,
                )
            if (completeBytes <= maximumBytes) selectedCount = index + 1 else return@forEachIndexed
        }
        if (selectedCount == 0) return BatchPackingResult.OversizedHead(referenceOf(head))
        return BatchPackingResult.Packed(encodeExact(eligible.take(selectedCount), sentAt))
    }

    fun encodeExact(
        records: List<RuntimeQueuedRecord>,
        sentAt: BatchWallInstant,
    ): BatchPackedRequest {
        require(records.size in 1..1_000)
        val head = records.first()
        val versions = versionsOf(head)
        val streamId = head.streamId
        var previousSequence: Long? = null
        records.forEach { record ->
            require(record.streamId == streamId) { "Batch records must share one stream" }
            require(versionsOf(record) == versions) { "Batch records must share capture-time versions" }
            previousSequence?.let { previous ->
                require(record.sequence == Math.addExact(previous, 1L)) { "Batch records must be contiguous and ordered" }
            }
            previousSequence = record.sequence
        }
        val requestId = requestId(streamId, versions, records)
        val prefix = encodePrefix(requestId, streamId, sentAt.rfc3339, RuntimeRecordCodec.encodeBatchVersions(versions))
        val output = ByteArrayOutputStream()
        output.write(prefix)
        records.forEachIndexed { index, record ->
            if (index > 0) output.write(','.code)
            val bytes = RuntimeRecordCodec.encodeBatchRecord(record)
            if (bytes.size != record.accountedBytes) {
                throw BatchProtocolException("Queued record byte accounting changed before delivery")
            }
            output.write(bytes)
        }
        output.write(BATCH_SUFFIX)
        return BatchPackedRequest.create(requestId, streamId, sentAt, versions, records, output.toByteArray())
    }

    fun requestId(
        streamId: String,
        versions: RuntimeVersions,
        records: List<RuntimeQueuedRecord>,
    ): String {
        require(records.isNotEmpty())
        val material = ByteArrayOutputStream()
        DataOutputStream(material).use { output ->
            output.write(DIGEST_DOMAIN)
            writeString(output, streamId)
            writeString(output, versions.schemaVersion.toString())
            writeString(output, versions.contractVersion)
            writeString(output, versions.platform.wireValue)
            writeString(output, versions.runtime.name)
            writeString(output, versions.runtime.version)
            writeString(output, versions.facade.name)
            writeString(output, versions.facade.version)
            if (versions.build == null) {
                output.writeByte(0)
            } else {
                output.writeByte(1)
                writeString(output, versions.build)
            }
            output.writeInt(records.size)
            records.forEach { record ->
                output.writeByte(if (record.kind == RuntimeRecordKind.EVENT) EVENT_KIND_BYTE else MUTATION_KIND_BYTE)
                output.writeLong(record.sequence)
                writeString(output, record.recordId)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        return "request_" + digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun encodePrefix(
        requestId: String,
        streamId: String,
        sentAt: String,
        versionBytes: ByteArray,
    ): ByteArray {
        RuntimeRecordCodec.compareTimestamps(sentAt, sentAt)
        return buildString {
            append("{\"schemaVersion\":1,\"requestId\":")
            append(JSONObject.quote(requestId))
            append(",\"streamId\":")
            append(JSONObject.quote(streamId))
            append(",\"sentAt\":")
            append(JSONObject.quote(sentAt))
            append(",\"versions\":")
        }.toByteArray(StandardCharsets.UTF_8) + versionBytes + ",\"records\":[".toByteArray(StandardCharsets.UTF_8)
    }

    private fun writeString(
        output: DataOutputStream,
        value: String,
    ) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private val BATCH_SUFFIX = "]}".toByteArray(StandardCharsets.UTF_8)
    private val DIGEST_DOMAIN = "elu-sdk-batch-request-v1\u0000".toByteArray(StandardCharsets.UTF_8)
    private const val EVENT_KIND_BYTE = 1
    private const val MUTATION_KIND_BYTE = 4
}

internal data class BatchAcknowledgementResolution(
    val acknowledgement: RuntimeAcknowledgement,
    val hasRetryableRecords: Boolean,
)

internal object V1BatchResponseCodec {
    private val ackFields =
        setOf("schemaVersion", "requestId", "streamId", "resolvedThroughSequence", "retryFromSequence", "outcomes")
    private val outcomeRequired = setOf("sequence", "recordId", "kind", "result")
    private val errorRequired = setOf("schemaVersion", "status", "code", "disposition", "message")
    private val codePattern = Regex("^[a-z][a-z0-9-]{0,63}$")

    fun parseAcknowledgement(
        body: ByteArray,
        request: BatchPackedRequest,
    ): BatchAcknowledgementResolution {
        val root = StrictJson.parseObject(body)
        expectFields(root, ackFields, emptySet(), "acknowledgement")
        requireInt(root, "schemaVersion", "acknowledgement", 1L, 1L)
        requireString(root, "requestId", "acknowledgement", 1, 256).also {
            if (it != request.requestId) protocol("Acknowledgement requestId does not match the request")
        }
        requireString(root, "streamId", "acknowledgement", 1, 256).also {
            if (it != request.streamId) protocol("Acknowledgement streamId does not match the request")
        }
        val outcomes = root.opt("outcomes") as? JSONArray ?: protocol("acknowledgement.outcomes must be an array")
        if (outcomes.length() !in 1..request.records.size) {
            protocol("Acknowledgement outcomes must be an ordered request prefix")
        }

        val resolved = ArrayList<RuntimeRecordReference>()
        var retryFrom: Long? = null
        repeat(outcomes.length()) { index ->
            val record = request.records[index]
            val outcome = outcomes.opt(index) as? JSONObject ?: protocol("acknowledgement.outcomes[$index] must be an object")
            expectFields(outcome, outcomeRequired, setOf("code"), "acknowledgement.outcomes[$index]")
            val sequence = requireInt(outcome, "sequence", "outcome", 0, Long.MAX_VALUE)
            val recordId = requireString(outcome, "recordId", "outcome", 1, 256)
            val kind = requireString(outcome, "kind", "outcome", 1, 16)
            if (sequence != record.sequence || recordId != record.recordId || kind != record.kind.wireValue) {
                protocol("Acknowledgement outcome is not bound to the ordered request record")
            }
            when (requireString(outcome, "result", "outcome", 1, 32)) {
                "accepted" -> {
                    if (outcome.has("code")) protocol("Accepted outcome must not contain code")
                    if (retryFrom != null) protocol("Resolved outcome appears after a retryable head")
                    resolved += referenceOf(record)
                }
                "terminally-rejected" -> {
                    requireCode(outcome, "outcome")
                    if (retryFrom != null) protocol("Resolved outcome appears after a retryable head")
                    resolved += referenceOf(record)
                }
                "retryable" -> {
                    requireCode(outcome, "outcome")
                    if (retryFrom != null || index != outcomes.length() - 1) {
                        protocol("Retryable outcome must be the single final reported outcome")
                    }
                    retryFrom = sequence
                }
                else -> protocol("Unsupported acknowledgement outcome result")
            }
        }

        if (retryFrom == null && outcomes.length() != request.records.size) {
            protocol("Acknowledgement without a retryable head must resolve the complete request")
        }

        val resolvedThrough = optionalRequiredLong(root, "resolvedThroughSequence", "acknowledgement")
        val expectedResolved = resolved.lastOrNull()?.sequence
        if (resolvedThrough != expectedResolved) protocol("resolvedThroughSequence does not match the resolved prefix")
        val responseRetryFrom = optionalRequiredLong(root, "retryFromSequence", "acknowledgement")
        if (responseRetryFrom != retryFrom) protocol("retryFromSequence does not match the retryable head")
        return BatchAcknowledgementResolution(
            RuntimeAcknowledgement(request.streamId, Collections.unmodifiableList(resolved)),
            retryFrom != null,
        )
    }

    fun validateTransportError(
        body: ByteArray,
        httpStatus: Int,
        requestId: String,
    ) {
        if (body.isEmpty() || body.size > MAX_TRANSPORT_ERROR_BYTES) {
            protocol("Transport error body size is invalid")
        }
        val root = StrictJson.parseObject(body)
        expectFields(root, errorRequired, setOf("requestId"), "transport error")
        requireInt(root, "schemaVersion", "transport error", 1, 1)
        if (requireInt(root, "status", "transport error", 100, 599) != httpStatus.toLong()) {
            protocol("Transport error status does not match HTTP status")
        }
        requireCode(root, "transport error")
        requireString(root, "message", "transport error", 1, 256)
        val expectedDisposition =
            when {
                httpStatus == 401 || httpStatus == 403 -> "permanent"
                httpStatus == 413 -> "retry-after-reduction"
                httpStatus == 429 || httpStatus in 500..599 -> "retryable"
                else -> protocol("HTTP status has no v1 transport error disposition")
            }
        if (requireString(root, "disposition", "transport error", 1, 64) != expectedDisposition) {
            protocol("Transport error disposition does not match HTTP status")
        }
        if (root.has("requestId") && requireString(root, "requestId", "transport error", 1, 256) != requestId) {
            protocol("Transport error requestId does not match the request")
        }
    }

    private fun requireCode(
        json: JSONObject,
        path: String,
    ) {
        val code = requireString(json, "code", path, 1, 64)
        if (!codePattern.matches(code)) protocol("$path.code is malformed")
    }
}

internal object RetryAfterParser {
    fun parseDelayMillis(
        value: String,
        responseEpochMillis: Long,
    ): Long {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.length > 128) {
            protocol("Retry-After is malformed")
        }
        if (normalized.all { it in '0'..'9' }) {
            val seconds = runCatching { BigInteger(normalized) }.getOrElse { protocol("Retry-After is malformed") }
            val milliseconds = seconds.multiply(BigInteger.valueOf(1_000L))
            return if (milliseconds > LONG_MAX) Long.MAX_VALUE else milliseconds.toLong()
        }
        HTTP_DATE_FORMATS.forEach { dateFormat ->
            if (dateFormat.requiresGmtSuffix && !normalized.endsWith(" GMT")) return@forEach
            val formatter = SimpleDateFormat(dateFormat.pattern, Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("GMT")
            formatter.isLenient = false
            if (dateFormat.twoDigitYear) {
                val start = GregorianCalendar(TimeZone.getTimeZone("GMT"), Locale.US)
                start.timeInMillis = responseEpochMillis
                start.add(Calendar.YEAR, -50)
                formatter.set2DigitYearStart(start.time)
            }
            val position = ParsePosition(0)
            val parsed = formatter.parse(normalized, position)
            if (
                parsed != null &&
                position.index == normalized.length &&
                formatter.format(parsed) == normalized
            ) {
                return if (parsed.time <= responseEpochMillis) 0 else parsed.time - responseEpochMillis
            }
        }
        protocol("Retry-After is malformed")
    }

    private val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
    private data class HttpDateFormat(
        val pattern: String,
        val requiresGmtSuffix: Boolean = false,
        val twoDigitYear: Boolean = false,
    )

    private val HTTP_DATE_FORMATS =
        listOf(
            HttpDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", requiresGmtSuffix = true),
            HttpDateFormat("EEEE, dd-MMM-yy HH:mm:ss 'GMT'", requiresGmtSuffix = true, twoDigitYear = true),
            HttpDateFormat("EEE MMM dd HH:mm:ss yyyy"),
            HttpDateFormat("EEE MMM  d HH:mm:ss yyyy"),
        )
}

internal fun referenceOf(record: RuntimeQueuedRecord): RuntimeRecordReference =
    RuntimeRecordReference(record.sequence, record.kind, record.recordId)

internal fun versionsOf(record: RuntimeQueuedRecord): RuntimeVersions =
    when (record) {
        is RuntimeQueuedRecord.Event -> record.record.versions
        is RuntimeQueuedRecord.Mutation -> record.envelope.versions
    }

internal fun occurredAtOf(record: RuntimeQueuedRecord): String =
    when (record) {
        is RuntimeQueuedRecord.Event -> record.record.occurredAt
        is RuntimeQueuedRecord.Mutation -> record.envelope.mutation.occurredAt
    }

private object StrictJson {
    fun parseObject(bytes: ByteArray): JSONObject {
        if (bytes.isEmpty() || bytes.size > MAX_BATCH_RESPONSE_BYTES) protocol("Response body size is invalid")
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: Exception) {
                protocol("Response body is not valid UTF-8", error)
            }
        StrictJsonScanner(text).validate()
        return try {
            JSONObject(text)
        } catch (error: JSONException) {
            protocol("Response body is not valid JSON", error)
        }
    }
}

private class StrictJsonScanner(
    private val text: String,
) {
    private var index = 0

    fun validate() {
        skipWhitespace()
        if (peek() != '{') protocol("Response root must be an object")
        parseObject(0)
        skipWhitespace()
        if (index != text.length) protocol("Response body contains trailing content")
    }

    private fun parseValue(depth: Int) {
        if (depth > 64) protocol("Response body exceeds the maximum JSON nesting depth")
        skipWhitespace()
        when (peek()) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()
            't' -> consumeLiteral("true")
            'f' -> consumeLiteral("false")
            'n' -> consumeLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> protocol("Response body contains invalid JSON")
        }
    }

    private fun parseObject(depth: Int) {
        consume('{')
        skipWhitespace()
        if (peek() == '}') {
            index += 1
            return
        }
        val keys = HashSet<String>()
        while (true) {
            skipWhitespace()
            if (peek() != '"') protocol("JSON object key must be a string")
            val key = parseString()
            if (!keys.add(key)) protocol("Response body contains a duplicate JSON object key")
            skipWhitespace()
            consume(':')
            parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> index += 1
                '}' -> {
                    index += 1
                    return
                }
                else -> protocol("Response body contains an invalid JSON object")
            }
        }
    }

    private fun parseArray(depth: Int) {
        consume('[')
        skipWhitespace()
        if (peek() == ']') {
            index += 1
            return
        }
        while (true) {
            parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> index += 1
                ']' -> {
                    index += 1
                    return
                }
                else -> protocol("Response body contains an invalid JSON array")
            }
        }
    }

    private fun parseString(): String {
        consume('"')
        val out = StringBuilder()
        while (index < text.length) {
            val character = text[index++]
            when {
                character == '"' -> {
                    validateUnicode(out)
                    return out.toString()
                }
                character == '\\' -> {
                    if (index >= text.length) protocol("Response body contains an invalid JSON escape")
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) protocol("Response body contains an invalid Unicode escape")
                            val value = text.substring(index, index + 4)
                            if (!value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                protocol("Response body contains an invalid Unicode escape")
                            }
                            out.append(value.toInt(16).toChar())
                            index += 4
                        }
                        else -> protocol("Response body contains an invalid JSON escape")
                    }
                }
                character.code < 0x20 -> protocol("Response body contains an unescaped control character")
                else -> out.append(character)
            }
        }
        protocol("Response body contains an unterminated JSON string")
    }

    private fun validateUnicode(value: CharSequence) {
        var cursor = 0
        while (cursor < value.length) {
            val character = value[cursor]
            when {
                Character.isHighSurrogate(character) -> {
                    if (cursor + 1 >= value.length || !Character.isLowSurrogate(value[cursor + 1])) {
                        protocol("Response body contains malformed Unicode")
                    }
                    cursor += 2
                }
                Character.isLowSurrogate(character) -> protocol("Response body contains malformed Unicode")
                else -> cursor += 1
            }
        }
    }

    private fun parseNumber() {
        if (peek() == '-') index += 1
        when (peek()) {
            '0' -> index += 1
            in '1'..'9' -> while (peek() in '0'..'9') index += 1
            else -> protocol("Response body contains an invalid JSON number")
        }
        if (peek() == '.') {
            index += 1
            if (peek() !in '0'..'9') protocol("Response body contains an invalid JSON number")
            while (peek() in '0'..'9') index += 1
        }
        if (peek() == 'e' || peek() == 'E') {
            index += 1
            if (peek() == '+' || peek() == '-') index += 1
            if (peek() !in '0'..'9') protocol("Response body contains an invalid JSON number")
            while (peek() in '0'..'9') index += 1
        }
    }

    private fun consumeLiteral(value: String) {
        if (!text.regionMatches(index, value, 0, value.length)) protocol("Response body contains invalid JSON")
        index += value.length
    }

    private fun consume(expected: Char) {
        if (peek() != expected) protocol("Response body contains invalid JSON")
        index += 1
    }

    private fun skipWhitespace() {
        while (peek() in JSON_WHITESPACE) index += 1
    }

    private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

    private companion object {
        val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
    }
}

private fun expectFields(
    json: JSONObject,
    required: Set<String>,
    optional: Set<String>,
    path: String,
) {
    val actual = json.keys().asSequence().toSet()
    if (actual - required - optional != emptySet<String>()) protocol("$path contains unsupported fields")
    if (required - actual != emptySet<String>()) protocol("$path is missing required fields")
}

private fun requireString(
    json: JSONObject,
    key: String,
    path: String,
    minimumLength: Int,
    maximumLength: Int,
): String {
    val value = json.opt(key) as? String ?: protocol("$path.$key must be a string")
    val length = value.codePointCount(0, value.length)
    if (length !in minimumLength..maximumLength) protocol("$path.$key has invalid length")
    return value
}

private fun requireInt(
    json: JSONObject,
    key: String,
    path: String,
    minimum: Long,
    maximum: Long,
): Long {
    val number = json.opt(key) as? Number ?: protocol("$path.$key must be an integer")
    val decimal = runCatching { BigDecimal(number.toString()) }.getOrElse { protocol("$path.$key must be an integer") }
    val value = runCatching { decimal.toBigIntegerExact().longValueExact() }.getOrElse {
        protocol("$path.$key must be an integer")
    }
    if (value !in minimum..maximum) protocol("$path.$key is outside the supported range")
    return value
}

private fun optionalRequiredLong(
    json: JSONObject,
    key: String,
    path: String,
): Long? {
    if (!json.has(key)) protocol("$path.$key is required")
    if (json.opt(key) == JSONObject.NULL) return null
    return requireInt(json, key, path, 0, Long.MAX_VALUE)
}

private fun protocol(
    message: String,
    cause: Throwable? = null,
): Nothing = throw BatchProtocolException(message, cause)
