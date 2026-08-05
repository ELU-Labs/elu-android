package dev.elu.analytics.internal.core

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.GregorianCalendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal class CoreStateCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class UnsupportedCoreSchemaException(val foundVersion: Long) :
    IllegalStateException("Unsupported ELU core state schema version: $foundVersion")

internal data class RecoverableCoreRecords(
    val identity: IdentityState? = null,
    val stream: StreamState? = null,
    val flagContext: FlagContextState? = null,
)

/** Strict codec for the durable provider-neutral state aggregate. */
internal object CoreStateCodec {
    private val aggregateFields = setOf("schemaVersion", "identity", "stream", "flagContext")
    private val identityRequiredFields =
        setOf(
            "schemaVersion",
            "revision",
            "contextRevision",
            "anonymousId",
            "userId",
            "groups",
            "superProperties",
            "session",
            "optedOut",
            "updatedAt",
        )
    private val sessionFields =
        setOf(
            "id",
            "startedAt",
            "lastActivityAt",
            "timeoutSeconds",
            "maximumDurationSeconds",
            "lifecycle",
            "backgroundedAt",
        )
    private val migrationFields = setOf("sourceSchema", "completedAt")
    private val streamFields = setOf("schemaVersion", "streamId", "nextSequence")
    private val flagContextFields = setOf("schemaVersion", "personProperties", "groupProperties")

    fun encode(state: PersistedCoreState): ByteArray {
        validateSchemaVersion(state.schemaVersion.toLong())
        val json =
            JSONObject()
                .put("schemaVersion", CORE_SCHEMA_VERSION)
                .put("identity", encodeIdentity(state.identity))
                .put("stream", encodeStream(state.stream))
                .put("flagContext", encodeFlagContext(state.flagContext))
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): PersistedCoreState {
        val root = parseObject(bytes)
        expectFields(root, aggregateFields, emptySet(), "core state")
        readSchemaVersion(root)
        return PersistedCoreState(
            identity = decodeIdentity(requiredObject(root, "identity")),
            stream = decodeStream(requiredObject(root, "stream")),
            flagContext = decodeFlagContext(requiredObject(root, "flagContext")),
        )
    }

    /**
     * Recovers only independently schema-valid records from a damaged v1 aggregate.
     * Unsupported schema majors still fail explicitly and are never overwritten.
     */
    fun recoverableRecords(bytes: ByteArray): RecoverableCoreRecords {
        val root =
            try {
                parseObject(bytes)
            } catch (_: CoreStateCorruptionException) {
                return RecoverableCoreRecords()
            }
        try {
            readSchemaVersion(root)
        } catch (_: CoreStateCorruptionException) {
            return RecoverableCoreRecords()
        }

        fun <T> recover(block: () -> T): T? =
            try {
                block()
            } catch (unsupported: UnsupportedCoreSchemaException) {
                throw unsupported
            } catch (_: CoreStateCorruptionException) {
                null
            } catch (_: JSONException) {
                null
            }

        return RecoverableCoreRecords(
            identity = recover { decodeIdentity(requiredObject(root, "identity")) },
            stream = recover { decodeStream(requiredObject(root, "stream")) },
            flagContext = recover { decodeFlagContext(requiredObject(root, "flagContext")) },
        )
    }

    /** Exposed internally so conformance tests can verify the closed identity shape directly. */
    fun encodeIdentity(state: IdentityState): JSONObject {
        validateSchemaVersion(state.schemaVersion.toLong())
        requireNonNegative(state.revision, "identity.revision")
        requireNonNegative(state.contextRevision, "identity.contextRevision")
        requireString(state.anonymousId, 1, 256, "identity.anonymousId")
        state.userId?.let { requireString(it, 1, 512, "identity.userId") }
        if (state.groups.size > 64) corrupt("identity.groups exceeds 64 entries")
        state.groups.values.forEach { requireString(it, 1, 512, "identity.groups value") }
        requireRfc3339(state.updatedAt, "identity.updatedAt")

        val groups = JSONObject()
        state.groups.forEach { (type, key) -> groups.put(type, key) }

        return JSONObject()
            .put("schemaVersion", CORE_SCHEMA_VERSION)
            .put("revision", state.revision)
            .put("contextRevision", state.contextRevision)
            .put("anonymousId", state.anonymousId)
            .put("userId", state.userId ?: JSONObject.NULL)
            .put("groups", groups)
            .put("superProperties", encodeJsonObject(state.superProperties, "identity.superProperties"))
            .put("session", state.session?.let(::encodeSession) ?: JSONObject.NULL)
            .put("optedOut", state.optedOut)
            .put("updatedAt", state.updatedAt)
            .apply {
                state.migration?.let { migration ->
                    requireString(migration.sourceSchema, 1, 128, "identity.migration.sourceSchema")
                    requireRfc3339(migration.completedAt, "identity.migration.completedAt")
                    put(
                        "migration",
                        JSONObject()
                            .put("sourceSchema", migration.sourceSchema)
                            .put("completedAt", migration.completedAt),
                    )
                }
            }
    }

    fun decodeIdentity(json: JSONObject): IdentityState {
        expectFields(json, identityRequiredFields, setOf("migration"), "identity")
        readSchemaVersion(json)
        val revision = requiredLong(json, "revision", "identity")
        val contextRevision = requiredLong(json, "contextRevision", "identity")
        requireNonNegative(revision, "identity.revision")
        requireNonNegative(contextRevision, "identity.contextRevision")
        val anonymousId = requiredString(json, "anonymousId", 1, 256, "identity")
        val userId = nullableString(json, "userId", 1, 512, "identity")

        val groupsJson = requiredObject(json, "groups")
        if (groupsJson.length() > 64) corrupt("identity.groups exceeds 64 entries")
        val groups = linkedMapOf<String, String>()
        groupsJson.keys().forEach { type ->
            groups[type] = requiredString(groupsJson, type, 1, 512, "identity.groups")
        }

        val session =
            if (json.isNull("session")) {
                null
            } else {
                decodeSession(requiredObject(json, "session"))
            }
        val updatedAt = requiredString(json, "updatedAt", 1, Int.MAX_VALUE, "identity")
        requireRfc3339(updatedAt, "identity.updatedAt")

        val migration =
            if (!json.has("migration")) {
                null
            } else {
                val migrationJson = requiredObject(json, "migration")
                expectFields(migrationJson, migrationFields, emptySet(), "identity.migration")
                val sourceSchema =
                    requiredString(migrationJson, "sourceSchema", 1, 128, "identity.migration")
                val completedAt =
                    requiredString(migrationJson, "completedAt", 1, Int.MAX_VALUE, "identity.migration")
                requireRfc3339(completedAt, "identity.migration.completedAt")
                MigrationState(sourceSchema, completedAt)
            }

        return IdentityState(
            revision = revision,
            contextRevision = contextRevision,
            anonymousId = anonymousId,
            userId = userId,
            groups = immutableMap(groups),
            superProperties = decodeJsonObject(requiredObject(json, "superProperties"), "identity.superProperties"),
            session = session,
            optedOut = requiredBoolean(json, "optedOut", "identity"),
            updatedAt = updatedAt,
            migration = migration,
        )
    }

    private fun encodeStream(state: StreamState): JSONObject {
        validateSchemaVersion(state.schemaVersion.toLong())
        requireString(state.streamId, 1, 256, "stream.streamId")
        requireNonNegative(state.nextSequence, "stream.nextSequence")
        return JSONObject()
            .put("schemaVersion", CORE_SCHEMA_VERSION)
            .put("streamId", state.streamId)
            .put("nextSequence", state.nextSequence)
    }

    private fun decodeStream(json: JSONObject): StreamState {
        expectFields(json, streamFields, emptySet(), "stream")
        readSchemaVersion(json)
        val nextSequence = requiredLong(json, "nextSequence", "stream")
        requireNonNegative(nextSequence, "stream.nextSequence")
        return StreamState(
            streamId = requiredString(json, "streamId", 1, 256, "stream"),
            nextSequence = nextSequence,
        )
    }

    private fun encodeFlagContext(state: FlagContextState): JSONObject {
        validateSchemaVersion(state.schemaVersion.toLong())
        if (state.groupProperties.size > 64) corrupt("flagContext.groupProperties exceeds 64 entries")
        val groupProperties = JSONObject()
        state.groupProperties.forEach { (type, properties) ->
            groupProperties.put(type, encodeJsonObject(properties, "flagContext.groupProperties.$type"))
        }
        return JSONObject()
            .put("schemaVersion", CORE_SCHEMA_VERSION)
            .put("personProperties", encodeJsonObject(state.personProperties, "flagContext.personProperties"))
            .put("groupProperties", groupProperties)
    }

    private fun decodeFlagContext(json: JSONObject): FlagContextState {
        expectFields(json, flagContextFields, emptySet(), "flagContext")
        readSchemaVersion(json)
        val groupsJson = requiredObject(json, "groupProperties")
        if (groupsJson.length() > 64) corrupt("flagContext.groupProperties exceeds 64 entries")
        val groups = linkedMapOf<String, Map<String, Any?>>()
        groupsJson.keys().forEach { type ->
            groups[type] = decodeJsonObject(requiredObject(groupsJson, type), "flagContext.groupProperties.$type")
        }
        return FlagContextState(
            personProperties =
                decodeJsonObject(requiredObject(json, "personProperties"), "flagContext.personProperties"),
            groupProperties = immutableMap(groups),
        )
    }

    private fun encodeSession(state: SessionState): JSONObject {
        requireString(state.id, 1, 256, "identity.session.id")
        requireRfc3339(state.startedAt, "identity.session.startedAt")
        requireRfc3339(state.lastActivityAt, "identity.session.lastActivityAt")
        if (state.timeoutSeconds !in MIN_SESSION_TIMEOUT_SECONDS..MAX_SESSION_TIMEOUT_SECONDS) {
            corrupt("identity.session.timeoutSeconds is outside 60..36000")
        }
        if (state.maximumDurationSeconds != SESSION_MAXIMUM_DURATION_SECONDS) {
            corrupt("identity.session.maximumDurationSeconds must be 86400")
        }
        state.backgroundedAt?.let { requireRfc3339(it, "identity.session.backgroundedAt") }
        return JSONObject()
            .put("id", state.id)
            .put("startedAt", state.startedAt)
            .put("lastActivityAt", state.lastActivityAt)
            .put("timeoutSeconds", state.timeoutSeconds)
            .put("maximumDurationSeconds", state.maximumDurationSeconds)
            .put("lifecycle", state.lifecycle.wireValue)
            .put("backgroundedAt", state.backgroundedAt ?: JSONObject.NULL)
    }

    private fun decodeSession(json: JSONObject): SessionState {
        expectFields(json, sessionFields, emptySet(), "identity.session")
        val startedAt = requiredString(json, "startedAt", 1, Int.MAX_VALUE, "identity.session")
        val lastActivityAt = requiredString(json, "lastActivityAt", 1, Int.MAX_VALUE, "identity.session")
        val backgroundedAt = nullableString(json, "backgroundedAt", 1, Int.MAX_VALUE, "identity.session")
        requireRfc3339(startedAt, "identity.session.startedAt")
        requireRfc3339(lastActivityAt, "identity.session.lastActivityAt")
        backgroundedAt?.let { requireRfc3339(it, "identity.session.backgroundedAt") }
        val timeoutSeconds = requiredInt(json, "timeoutSeconds", "identity.session")
        if (timeoutSeconds !in MIN_SESSION_TIMEOUT_SECONDS..MAX_SESSION_TIMEOUT_SECONDS) {
            corrupt("identity.session.timeoutSeconds is outside 60..36000")
        }
        val maximumDurationSeconds = requiredInt(json, "maximumDurationSeconds", "identity.session")
        if (maximumDurationSeconds != SESSION_MAXIMUM_DURATION_SECONDS) {
            corrupt("identity.session.maximumDurationSeconds must be 86400")
        }
        return SessionState(
            id = requiredString(json, "id", 1, 256, "identity.session"),
            startedAt = startedAt,
            lastActivityAt = lastActivityAt,
            timeoutSeconds = timeoutSeconds,
            maximumDurationSeconds = maximumDurationSeconds,
            lifecycle =
                SessionLifecycle.fromWireValue(
                    requiredString(json, "lifecycle", 1, Int.MAX_VALUE, "identity.session"),
                ),
            backgroundedAt = backgroundedAt,
        )
    }

    private fun parseObject(bytes: ByteArray): JSONObject {
        try {
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            if ('\u0000' in text) corrupt("Core state contains an invalid null character")
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            if (value !is JSONObject) corrupt("Core state root must be an object")
            if (tokener.nextClean() != '\u0000') corrupt("Core state contains trailing content")
            return value
        } catch (error: CoreStateCorruptionException) {
            throw error
        } catch (error: JSONException) {
            throw CoreStateCorruptionException("Core state is not valid JSON", error)
        } catch (error: CharacterCodingException) {
            throw CoreStateCorruptionException("Core state is not valid UTF-8", error)
        } catch (error: RuntimeException) {
            throw CoreStateCorruptionException("Core state could not be decoded", error)
        }
    }

    private fun readSchemaVersion(json: JSONObject) {
        validateSchemaVersion(requiredLong(json, "schemaVersion", "record"))
    }

    private fun validateSchemaVersion(value: Long) {
        if (value != CORE_SCHEMA_VERSION.toLong()) throw UnsupportedCoreSchemaException(value)
    }

    private fun expectFields(
        json: JSONObject,
        required: Set<String>,
        optional: Set<String>,
        path: String,
    ) {
        val actual = json.keys().asSequence().toSet()
        val missing = required - actual
        if (missing.isNotEmpty()) corrupt("$path is missing fields: ${missing.sorted().joinToString()}")
        val unknown = actual - required - optional
        if (unknown.isNotEmpty()) corrupt("$path contains unknown fields: ${unknown.sorted().joinToString()}")
    }

    private fun requiredObject(
        json: JSONObject,
        key: String,
    ): JSONObject =
        json.opt(key) as? JSONObject
            ?: corrupt("$key must be an object")

    private fun requiredBoolean(
        json: JSONObject,
        key: String,
        path: String,
    ): Boolean =
        json.opt(key) as? Boolean
            ?: corrupt("$path.$key must be a boolean")

    private fun requiredString(
        json: JSONObject,
        key: String,
        minLength: Int,
        maxLength: Int,
        path: String,
    ): String {
        val value = json.opt(key) as? String ?: corrupt("$path.$key must be a string")
        requireString(value, minLength, maxLength, "$path.$key")
        return value
    }

    private fun nullableString(
        json: JSONObject,
        key: String,
        minLength: Int,
        maxLength: Int,
        path: String,
    ): String? {
        if (!json.has(key)) corrupt("$path is missing field: $key")
        if (json.isNull(key)) return null
        return requiredString(json, key, minLength, maxLength, path)
    }

    private fun requiredLong(
        json: JSONObject,
        key: String,
        path: String,
    ): Long {
        if (!json.has(key) || json.isNull(key)) corrupt("$path.$key must be an integer")
        return exactLong(json.get(key), "$path.$key")
    }

    private fun requiredInt(
        json: JSONObject,
        key: String,
        path: String,
    ): Int {
        val value = requiredLong(json, key, path)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) corrupt("$path.$key is outside the integer range")
        return value.toInt()
    }

    private fun exactLong(
        value: Any,
        path: String,
    ): Long {
        if (value !is Number || value is Float && !value.isFinite() || value is Double && !value.isFinite()) {
            corrupt("$path must be a finite integer")
        }
        return try {
            BigDecimal(value.toString()).longValueExact()
        } catch (error: ArithmeticException) {
            corrupt("$path must be an integer in the signed 64-bit range", error)
        } catch (error: NumberFormatException) {
            corrupt("$path must be a finite integer", error)
        }
    }

    private fun requireNonNegative(
        value: Long,
        path: String,
    ) {
        if (value < 0) corrupt("$path must be non-negative")
    }

    private fun requireString(
        value: String,
        minLength: Int,
        maxLength: Int,
        path: String,
    ) {
        val length = value.codePointCount(0, value.length)
        if (length !in minLength..maxLength) {
            corrupt("$path length must be in $minLength..$maxLength")
        }
    }

    private fun requireRfc3339(
        value: String,
        path: String,
    ) {
        val match = RFC_3339.matchEntire(value) ?: corrupt("$path must be an RFC 3339 timestamp")
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        try {
            GregorianCalendar(TimeZone.getTimeZone("UTC"))
                .apply {
                    isLenient = false
                    clear()
                    set(year, month - 1, day)
                }.time
        } catch (error: IllegalArgumentException) {
            corrupt("$path contains an invalid calendar date", error)
        }
    }

    private fun encodeJsonObject(
        value: Map<String, Any?>,
        path: String,
    ): JSONObject {
        val out = JSONObject()
        value.forEach { (key, child) -> out.put(key, encodeJsonValue(child, "$path.$key", 1)) }
        return out
    }

    private fun encodeJsonValue(
        value: Any?,
        path: String,
        depth: Int,
    ): Any {
        if (depth > MAX_JSON_DEPTH) corrupt("$path exceeds the maximum JSON nesting depth")
        return when (value) {
            null -> JSONObject.NULL
            is String, is Boolean, is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> value
            is Float -> if (value.isFinite()) value else corrupt("$path must be a finite JSON number")
            is Double -> if (value.isFinite()) value else corrupt("$path must be a finite JSON number")
            is Map<*, *> -> {
                val out = JSONObject()
                value.forEach { (key, child) ->
                    if (key !is String) corrupt("$path contains a non-string object key")
                    out.put(key, encodeJsonValue(child, "$path.$key", depth + 1))
                }
                out
            }
            is List<*> -> {
                val out = JSONArray()
                value.forEachIndexed { index, child -> out.put(encodeJsonValue(child, "$path[$index]", depth + 1)) }
                out
            }
            else -> corrupt("$path contains a non-JSON value of type ${value::class.java.name}")
        }
    }

    private fun decodeJsonObject(
        value: JSONObject,
        path: String,
        depth: Int = 1,
    ): Map<String, Any?> {
        if (depth > MAX_JSON_DEPTH) corrupt("$path exceeds the maximum JSON nesting depth")
        val out = linkedMapOf<String, Any?>()
        value.keys().forEach { key ->
            out[key] = decodeJsonValue(value.get(key), "$path.$key", depth + 1)
        }
        return immutableMap(out)
    }

    private fun decodeJsonValue(
        value: Any,
        path: String,
        depth: Int,
    ): Any? {
        if (depth > MAX_JSON_DEPTH) corrupt("$path exceeds the maximum JSON nesting depth")
        return when (value) {
            JSONObject.NULL -> null
            is String, is Boolean, is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> value
            is Float -> if (value.isFinite()) value else corrupt("$path must be a finite JSON number")
            is Double -> if (value.isFinite()) value else corrupt("$path must be a finite JSON number")
            is JSONObject -> decodeJsonObject(value, path, depth)
            is JSONArray -> {
                val out = ArrayList<Any?>(value.length())
                repeat(value.length()) { index -> out += decodeJsonValue(value.get(index), "$path[$index]", depth + 1) }
                Collections.unmodifiableList(out)
            }
            else -> corrupt("$path contains a non-JSON value of type ${value::class.java.name}")
        }
    }

    private fun corrupt(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw CoreStateCorruptionException(message, cause)

    private fun <K, V> immutableMap(value: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(value))

    private const val MAX_JSON_DEPTH = 64

    private val RFC_3339 =
        Regex(
            "^(\\d{4})-(0[1-9]|1[0-2])-([0-2]\\d|3[01])T" +
                "(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d+)?" +
                "Z$",
        )
}

/** Validates and defensively copies customer-provided JSON values. */
internal object JsonValues {
    fun objectValue(
        value: Map<String, Any?>,
        path: String,
    ): Map<String, Any?> {
        val normalized = normalize(value, path, 0)
        @Suppress("UNCHECKED_CAST")
        return normalized as Map<String, Any?>
    }

    private fun normalize(
        value: Any?,
        path: String,
        depth: Int,
    ): Any? {
        require(depth <= 64) { "$path exceeds the maximum JSON nesting depth" }
        return when (value) {
            null -> null
            is String, is Boolean, is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> value
            is Float -> {
                require(value.isFinite()) { "$path must be a finite JSON number" }
                value
            }
            is Double -> {
                require(value.isFinite()) { "$path must be a finite JSON number" }
                value
            }
            is Map<*, *> -> {
                val out = linkedMapOf<String, Any?>()
                value.forEach { (key, child) ->
                    require(key is String) { "$path contains a non-string object key" }
                    out[key] = normalize(child, "$path.$key", depth + 1)
                }
                Collections.unmodifiableMap(out)
            }
            is List<*> -> {
                val out = value.mapIndexed { index, child -> normalize(child, "$path[$index]", depth + 1) }
                Collections.unmodifiableList(out)
            }
            else -> throw IllegalArgumentException("$path contains a non-JSON value of type ${value::class.java.name}")
        }
    }
}
