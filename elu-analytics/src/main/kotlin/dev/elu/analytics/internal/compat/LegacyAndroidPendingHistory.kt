package dev.elu.analytics.internal.compat

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.JsonValues
import dev.elu.analytics.internal.runtime.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Captured from the one fixed Events directory, with the original File.lastModified order. */
internal class AndroidLegacyHistoryEntry private constructor(
    val filename: String, val modifiedAt: Long, private val readBody: () -> ByteArray,
) {
    constructor(filename: String, modifiedAt: Long, bytes: ByteArray) :
        this(filename, modifiedAt, readBody = bytes.copyOf().let { retained -> { retained.copyOf() } })
    fun bytes(): ByteArray = readBody()

    companion object {
        // The bounded OS reader closes every descriptor and retains only exact metadata/hash.
        // Conversion reopens one file at a time and rechecks that captured identity and content.
        fun observed(filename: String, modifiedAt: Long, readBody: () -> ByteArray) =
            AndroidLegacyHistoryEntry(filename, modifiedAt, readBody)
    }
}

internal enum class AndroidLegacyHistoryRefusal {
    SOURCE_SHAPE, SOURCE_TOO_LARGE, AMBIGUOUS_ORDER, UNANCHORED_IDENTITY,
    UNSUPPORTED_HISTORY, FUTURE_TIMESTAMP, DUPLICATE_ID,
}
internal class AndroidLegacyHistoryException(val reason: AndroidLegacyHistoryRefusal) :
    IllegalStateException("Legacy Android history refused: " + reason.name)

/**
 * Import metadata names the transformation; revision numbers belong to the new
 * stream's import order, never to an invented historical SDK revision. The raw
 * source is retained. Mutation-only source session metadata is carried separately
 * because the closed current mutation envelope has no session/property bag.
 */
internal data class AndroidLegacyImportedRecord(
    val row: RuntimeStoredRecord,
    val sourceFilename: String,
    val sourceModifiedAt: Long,
    val sourceSha256: String,
    val sourceSessionId: String?,
    val occurredAt: String,
)

internal object LegacyAndroidPendingHistory {
    const val MAXIMUM_RECORDS = 1000
    const val MAXIMUM_EVENT_BYTES = 256 * 1024
    const val MAXIMUM_SOURCE_BYTES = MAXIMUM_RECORDS * MAXIMUM_EVENT_BYTES
    const val MAXIMUM_RETAINED_BYTES = 16 * 1024 * 1024

    private val versions = RuntimeVersions(
        platform = RuntimePlatform.ANDROID,
        runtime = RuntimeVersionComponent("elu-android-legacy-import", "1.0.0"),
        facade = RuntimeVersionComponent("EluAnalytics", "0.1.0"),
        build = "elu-android-0.1.0-migration",
    )
    private val topFields = setOf("event", "distinct_id", "properties", "timestamp", "uuid")
    private val mutationKeys = setOf(
        "\$anon_distinct_id", "\$set", "\$set_once", "\$unset", "\$add", "\$append", "\$remove",
        "\$union", "\$delete", "\$group_type", "\$group_key", "\$group_set", "\$group_set_once", "alias",
    )

    /** No current aggregate identity is an input: old rows cannot be reattributed. */
    fun convert(entries: List<AndroidLegacyHistoryEntry>, streamId: String, importedAt: String): List<AndroidLegacyImportedRecord> {
        if (entries.size !in 1..MAXIMUM_RECORDS) refuse(AndroidLegacyHistoryRefusal.SOURCE_TOO_LARGE)
        text(streamId, 256)
        val importTime = timestamp(importedAt)
        val identities = linkedMapOf<String, String>() // authenticated retained $identify only
        val knownAnonymous = linkedSetOf<String>()
        val ids = linkedSetOf<String>()
        var previousModified: Long? = null
        var total = 0
        var payloadBytes = 0
        var accountedBytes = 0
        return entries.mapIndexed { index, entry ->
            if (entry.modifiedAt <= 0 || previousModified?.let { entry.modifiedAt <= it } == true) {
                refuse(AndroidLegacyHistoryRefusal.AMBIGUOUS_ORDER)
            }
            previousModified = entry.modifiedAt
            val bytes = entry.bytes()
            if (bytes.isEmpty() || bytes.size > MAXIMUM_EVENT_BYTES || bytes.size > MAXIMUM_SOURCE_BYTES - total) {
                refuse(AndroidLegacyHistoryRefusal.SOURCE_TOO_LARGE)
            }
            total += bytes.size
            val root = parse(bytes)
            if (root.keys != topFields) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
            val id = text(root["uuid"], 36)
            if (!uuid7(id) || entry.filename != "$id.event") refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
            if (!ids.add(id)) refuse(AndroidLegacyHistoryRefusal.DUPLICATE_ID)
            val name = text(root["event"], 512)
            val distinct = text(root["distinct_id"], 512)
            val occurredAt = text(root["timestamp"], 128)
            if (timestamp(occurredAt) > importTime) refuse(AndroidLegacyHistoryRefusal.FUTURE_TIMESTAMP)
            val properties = objectValue(root["properties"])
            val groupMutation = name == "\$groupidentify"
            // Original Android SDK context supplies these values to every ordinary capture.
            // Group's separate stateless path is admitted only with the same exact provenance.
            if (properties["\$lib"] != "posthog-android" || properties["\$lib_version"] != "3.58.0") {
                refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
            }
            val session = if (groupMutation && !properties.containsKey("\$session_id")) null
                else text(properties["\$session_id"], 36).also {
                    if (!uuid7(it)) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
                }
            if (properties.containsKey("distinct_id") || properties.containsKey("\$device_id")) {
                // These do not come from the observed Android context and may override identity downstream.
                refuse(AndroidLegacyHistoryRefusal.UNSUPPORTED_HISTORY)
            }
            val identified = if (groupMutation && !properties.containsKey("\$is_identified")) null
                else properties["\$is_identified"] as? Boolean ?: refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
            val allowed = when (name) {
                "\$identify" -> setOf("\$anon_distinct_id", "\$set", "\$set_once")
                "\$set" -> setOf("\$set", "\$set_once")
                "\$create_alias" -> setOf("alias")
                "\$groupidentify" -> setOf("\$group_type", "\$group_key", "\$group_set")
                else -> emptySet()
            }
            if (properties.keys.any { it in mutationKeys && it !in allowed }) refuse(AndroidLegacyHistoryRefusal.UNSUPPORTED_HISTORY)
            val anonymous: String
            val user: String?
            if (name == "\$identify") {
                if (identified != true) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
                anonymous = text(properties["\$anon_distinct_id"], 256)
                if (anonymous == distinct) refuse(AndroidLegacyHistoryRefusal.UNANCHORED_IDENTITY)
                user = distinct
                identities.clear(); knownAnonymous.clear()
                identities[distinct] = anonymous
                knownAnonymous.add(anonymous)
            } else if (identified == false) {
                // A later anonymous source boundary cannot reuse an earlier identified anchor.
                identities.clear(); knownAnonymous.clear()
                anonymous = text(distinct, 256); user = null; knownAnonymous.add(anonymous)
            } else {
                val anchored = identities[distinct]
                if (anchored != null) { anonymous = anchored; user = distinct }
                else if (groupMutation && identified == null && distinct in knownAnonymous) { anonymous = distinct; user = null }
                else refuse(AndroidLegacyHistoryRefusal.UNANCHORED_IDENTITY)
            }
            val sequence = index.toLong()
            // Import-owned monotonically ordered revisions; raw old identity/time are untouched.
            val revision = sequence
            val change: RuntimeMutationChange? = when (name) {
                "\$identify" -> RuntimeMutationChange.Identify(distinct, optionalObject(properties, "\$set"), optionalObject(properties, "\$set_once"))
                "\$set" -> RuntimeMutationChange.SetPersonProperties(optionalObject(properties, "\$set"), optionalObject(properties, "\$set_once"), emptyList())
                "\$create_alias" -> RuntimeMutationChange.LinkAlias(text(properties["alias"], 512), distinct)
                "\$groupidentify" -> RuntimeMutationChange.SetGroupProperties(text(properties["\$group_type"], 512), text(properties["\$group_key"], 512), optionalObject(properties, "\$group_set"), emptyMap(), emptyList())
                else -> {
                    // Unknown reserved provider events, including replay/mutations, never become captures.
                    if (name.startsWith("$")) refuse(AndroidLegacyHistoryRefusal.UNSUPPORTED_HISTORY)
                    null
                }
            }
            val row = if (change == null) {
                val groups = optionalObject(properties, "\$groups").mapValues { text(it.value, 512) }
                val record = RuntimeEventRecord(eventId = id, streamId = streamId, sequence = sequence,
                    contextRevision = revision, kind = RuntimeEventKind.CAPTURE, name = name,
                    occurredAt = occurredAt, identity = RuntimeEventIdentity(anonymous, user, revision),
                    sessionId = session ?: refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE),
                    properties = properties, groups = groups, versions = versions)
                val payload = RuntimeRecordCodec.encodeEvent(record)
                val canonical = RuntimeRecordCodec.decodeEvent(payload)
                // No numeric precision loss through the actual destination codec is admitted.
                val encodedProperties = parse(payload)["properties"]
                if (canonicalJson(encodedProperties) != canonicalJson(root["properties"])) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
                RuntimeStoredRecord(sequence, streamId, RuntimeRecordKind.EVENT, id, payload, RuntimeRecordCodec.encodeBatchRecord(canonical).size)
            } else {
                val envelope = RuntimeMutationEnvelope(streamId = streamId, versions = versions,
                    mutation = RuntimeMutationRecord(id, sequence, revision, occurredAt,
                        RuntimeMutationSubject(anonymous, user, revision), change))
                val payload = RuntimeRecordCodec.encodeMutation(envelope)
                val canonical = RuntimeRecordCodec.decodeMutation(payload)
                if (canonical != envelope) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
                RuntimeStoredRecord(sequence, streamId, RuntimeRecordKind.MUTATION, id, payload, RuntimeRecordCodec.encodeBatchRecord(canonical).size)
            }
            if (row.internalPayload.size > MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES) refuse(AndroidLegacyHistoryRefusal.SOURCE_TOO_LARGE)
            // Bound accumulated destination payloads as well as their wire accounting before
            // retaining this row. Source files are reopened singly; the raw backlog is never held.
            if (row.internalPayload.size > MAXIMUM_RETAINED_BYTES - payloadBytes ||
                row.accountedBytes > MAXIMUM_RETAINED_BYTES - accountedBytes) refuse(AndroidLegacyHistoryRefusal.SOURCE_TOO_LARGE)
            payloadBytes += row.internalPayload.size
            accountedBytes += row.accountedBytes
            AndroidLegacyImportedRecord(row, entry.filename, entry.modifiedAt, sha256(bytes), session, occurredAt)
        }
    }

    private fun optionalObject(values: Map<String, Any?>, key: String): Map<String, Any?> =
        if (values.containsKey(key)) objectValue(values[key]) else emptyMap()
    private fun objectValue(value: Any?): Map<String, Any?> {
        val objectValue = value as? Map<*, *> ?: refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
        if (objectValue.keys.any { it !is String }) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
        @Suppress("UNCHECKED_CAST")
        return JsonValues.objectValue(objectValue as Map<String, Any?>, "legacy history")
    }
    private fun text(value: Any?, maximum: Int): String {
        val value = value as? String ?: refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
        if (value.codePointCount(0, value.length) !in 1..maximum) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
        return value
    }
    private fun parse(bytes: ByteArray): Map<String, Any?> {
        val raw = try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE) }
        val value = try { V1StrictCanonicalJson.parse(raw) }
        catch (_: IllegalArgumentException) { refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE) }
        val converted = objectValue(jsonValue(value))
        if (canonicalJson(converted) != V1StrictCanonicalJson.canonicalize(value)) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
        return converted
    }
    private fun jsonValue(value: V1StrictCanonicalJson.Value): Any? = when (value) {
        is V1StrictCanonicalJson.Value.ObjectValue -> value.members.associateTo(linkedMapOf()) { it.first to jsonValue(it.second) }
        is V1StrictCanonicalJson.Value.ArrayValue -> value.values.map(::jsonValue)
        is V1StrictCanonicalJson.Value.StringValue -> value.value
        is V1StrictCanonicalJson.Value.BooleanValue -> value.value
        is V1StrictCanonicalJson.Value.NumberValue -> value.token.toLongOrNull()
            ?: value.token.toDoubleOrNull()?.takeIf { it.isFinite() } ?: refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
        V1StrictCanonicalJson.Value.NullValue -> null
    }
    private fun canonicalJson(value: Any?): String = V1StrictCanonicalJson.canonicalize(toJson(value))
    private fun toJson(value: Any?): V1StrictCanonicalJson.Value = when (value) {
        null -> V1StrictCanonicalJson.Value.NullValue
        is String -> V1StrictCanonicalJson.Value.StringValue(value)
        is Boolean -> V1StrictCanonicalJson.Value.BooleanValue(value)
        is Number -> V1StrictCanonicalJson.Value.NumberValue(value.toString())
        is Map<*, *> -> V1StrictCanonicalJson.Value.ObjectValue(value.entries.map { (key, child) -> text(key, 4096) to toJson(child) })
        is List<*> -> V1StrictCanonicalJson.Value.ArrayValue(value.map(::toJson))
        else -> refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
    }
    private fun timestamp(value: String): Long {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z").matches(value)) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        val position = ParsePosition(0); val parsed = parser.parse(value, position)
        if (parsed == null || position.index != value.length || parser.format(parsed) != value || parsed.time < 0) refuse(AndroidLegacyHistoryRefusal.SOURCE_SHAPE)
        return parsed.time
    }
    private fun uuid7(value: String) = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(value)
    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun refuse(reason: AndroidLegacyHistoryRefusal): Nothing = throw AndroidLegacyHistoryException(reason)
}
