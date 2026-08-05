package dev.elu.analytics.internal.core

import java.math.BigDecimal
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

/**
 * A record claims the supported schema major but contains fields this SDK does not understand.
 *
 * This is deliberately not a [CoreStateCorruptionException]. Treating a forward-compatible
 * extension as corruption would allow startup recovery to overwrite state written by a newer SDK.
 */
internal class UnsupportedCoreSchemaExtensionException(
    val recordPath: String,
    val unknownFields: Set<String>,
) : IllegalStateException(
        "$recordPath contains unsupported schema fields: ${unknownFields.sorted().joinToString()}",
    )

internal data class RecoverableCoreRecords(
    val identity: IdentityState? = null,
    val stream: StreamState? = null,
    val flagContext: FlagContextState? = null,
)

/**
 * Normalizes customer numbers to the concrete types supported by Android's org.json runtime.
 *
 * Byte, Short, and Float are convenient Kotlin inputs, but Android JSONTokener materializes only
 * Int, Long, and Double. Integral values are canonicalized to the Int/Long type Android will read
 * back. Arbitrary Number implementations and the one finite Double that Android serializes to a
 * different integer are rejected before JSONObject can silently change their value.
 */
private fun normalizeAndroidJsonNumber(
    value: Number,
    path: String,
    fail: (String) -> Nothing,
): Number =
    when (value) {
        is Byte -> normalizeAndroidJsonInteger(value.toLong())
        is Short -> normalizeAndroidJsonInteger(value.toLong())
        is Int -> value
        is Long -> normalizeAndroidJsonInteger(value)
        is Float -> normalizeAndroidJsonDouble(value.toDouble(), path, fail)
        is Double -> normalizeAndroidJsonDouble(value, path, fail)
        else ->
            fail(
                "$path contains unsupported JSON number type ${value::class.java.name}; " +
                    "use Int, Long, or finite Double",
            )
    }

private fun normalizeAndroidJsonInteger(value: Long): Number =
    if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value

private fun normalizeAndroidJsonDouble(
    value: Double,
    path: String,
    fail: (String) -> Nothing,
): Number {
    if (!value.isFinite()) fail("$path must be a finite JSON number")

    // Android's numberToString() compares a Double to number.longValue(). Long.MAX_VALUE rounds
    // to 2^63 as a Double, so 2^63 satisfies that comparison and is incorrectly written as
    // 9223372036854775807. Reject this single alias instead of corrupting it across a restart.
    if (value == Long.MAX_VALUE.toDouble()) {
        fail("$path cannot be represented by Android JSON without changing its value")
    }

    val integerValue = value.toLong()
    return if (value == integerValue.toDouble()) {
        normalizeAndroidJsonInteger(integerValue)
    } else {
        value
    }
}

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
        validatePreEncodeBudget(state)
        val json =
            JSONObject()
                .put("schemaVersion", CORE_SCHEMA_VERSION)
                .put("identity", encodeIdentity(state.identity))
                .put("stream", encodeStream(state.stream))
                .put("flagContext", encodeFlagContext(state.flagContext))
        return encodeBounded(json)
    }

    /**
     * Bounds aggregate work before any JSONObject/JSONArray tree is materialized. The estimate
     * deliberately over-counts separators and numeric representations; the serialized byte cap
     * remains the final source of truth after encoding.
     */
    private fun validatePreEncodeBudget(state: PersistedCoreState) {
        val budget = PreEncodeBudget()
        budget.addObjectNode()
        budget.addObjectEntry("schemaVersion")
        budget.addInteger(state.schemaVersion.toLong())
        budget.addObjectEntry("identity")
        budgetIdentity(state.identity, budget)
        budget.addObjectEntry("stream")
        budgetStream(state.stream, budget)
        budget.addObjectEntry("flagContext")
        budgetFlagContext(state.flagContext, budget)
    }

    private fun budgetIdentity(
        state: IdentityState,
        budget: PreEncodeBudget,
    ) {
        budget.addObjectNode()
        budget.addObjectEntry("schemaVersion")
        budget.addInteger(state.schemaVersion.toLong())
        budget.addObjectEntry("revision")
        budget.addInteger(state.revision)
        budget.addObjectEntry("contextRevision")
        budget.addInteger(state.contextRevision)
        budget.addObjectEntry("anonymousId")
        budget.addString(state.anonymousId)
        budget.addObjectEntry("userId")
        state.userId?.let(budget::addString) ?: budget.addNull()
        budget.addObjectEntry("groups")
        budgetStringMap(state.groups, budget)
        budget.addObjectEntry("superProperties")
        budgetJsonObject(state.superProperties, budget)
        budget.addObjectEntry("session")
        state.session?.let { budgetSession(it, budget) } ?: budget.addNull()
        budget.addObjectEntry("optedOut")
        budget.addBoolean(state.optedOut)
        budget.addObjectEntry("updatedAt")
        budget.addString(state.updatedAt)
        state.migration?.let { migration ->
            budget.addObjectEntry("migration")
            budget.addObjectNode()
            budget.addObjectEntry("sourceSchema")
            budget.addString(migration.sourceSchema)
            budget.addObjectEntry("completedAt")
            budget.addString(migration.completedAt)
        }
    }

    private fun budgetSession(
        state: SessionState,
        budget: PreEncodeBudget,
    ) {
        budget.addObjectNode()
        budget.addObjectEntry("id")
        budget.addString(state.id)
        budget.addObjectEntry("startedAt")
        budget.addString(state.startedAt)
        budget.addObjectEntry("lastActivityAt")
        budget.addString(state.lastActivityAt)
        budget.addObjectEntry("timeoutSeconds")
        budget.addInteger(state.timeoutSeconds.toLong())
        budget.addObjectEntry("maximumDurationSeconds")
        budget.addInteger(state.maximumDurationSeconds.toLong())
        budget.addObjectEntry("lifecycle")
        budget.addString(state.lifecycle.wireValue)
        budget.addObjectEntry("backgroundedAt")
        state.backgroundedAt?.let(budget::addString) ?: budget.addNull()
    }

    private fun budgetStream(
        state: StreamState,
        budget: PreEncodeBudget,
    ) {
        budget.addObjectNode()
        budget.addObjectEntry("schemaVersion")
        budget.addInteger(state.schemaVersion.toLong())
        budget.addObjectEntry("streamId")
        budget.addString(state.streamId)
        budget.addObjectEntry("nextSequence")
        budget.addInteger(state.nextSequence)
    }

    private fun budgetFlagContext(
        state: FlagContextState,
        budget: PreEncodeBudget,
    ) {
        budget.addObjectNode()
        budget.addObjectEntry("schemaVersion")
        budget.addInteger(state.schemaVersion.toLong())
        budget.addObjectEntry("personProperties")
        budgetJsonObject(state.personProperties, budget)
        budget.addObjectEntry("groupProperties")
        budget.addObjectNode()
        state.groupProperties.forEach { (type, properties) ->
            budget.addObjectEntry(type)
            budgetJsonObject(properties, budget)
        }
    }

    private fun budgetStringMap(
        value: Map<String, String>,
        budget: PreEncodeBudget,
    ) {
        budget.addObjectNode()
        value.forEach { (key, child) ->
            budget.addObjectEntry(key)
            budget.addString(child)
        }
    }

    private fun budgetJsonObject(
        value: Map<*, *>,
        budget: PreEncodeBudget,
        depth: Int = 0,
    ) {
        if (depth > MAX_JSON_DEPTH) corrupt("Core state exceeds the maximum JSON nesting depth")
        budget.addObjectNode()
        value.forEach { (key, child) ->
            if (key !is String) corrupt("Core state contains a non-string JSON object key")
            budget.addObjectEntry(key)
            budgetJsonValue(child, budget, depth + 1)
        }
    }

    private fun budgetJsonValue(
        value: Any?,
        budget: PreEncodeBudget,
        depth: Int,
    ) {
        if (depth > MAX_JSON_DEPTH) corrupt("Core state exceeds the maximum JSON nesting depth")
        when (value) {
            null -> budget.addNull()
            is String -> budget.addString(value)
            is Boolean -> budget.addBoolean(value)
            is Number -> {
                when (
                    val normalized =
                        normalizeAndroidJsonNumber(value, "Core state") { message -> corrupt(message) }
                ) {
                    is Int -> budget.addInteger(normalized.toLong())
                    is Long -> budget.addInteger(normalized)
                    is Double -> budget.addFloatingPoint(normalized)
                    else -> corrupt("Core state contains an unsupported normalized JSON number")
                }
            }
            is Map<*, *> -> budgetJsonObject(value, budget, depth)
            is List<*> -> {
                budget.addArrayNode()
                value.forEach { child ->
                    budget.addArrayEntry()
                    budgetJsonValue(child, budget, depth + 1)
                }
            }
            else -> corrupt("Core state contains a non-JSON value of type ${value::class.java.name}")
        }
    }

    fun decode(bytes: ByteArray): PersistedCoreState {
        val root = parseObject(bytes)
        readSchemaVersion(root)
        expectFields(root, aggregateFields, emptySet(), "core state")
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
        val hasValidAggregateSchema =
            try {
                readSchemaVersion(root)
                true
            } catch (unsupported: UnsupportedCoreSchemaException) {
                throw unsupported
            } catch (_: CoreStateCorruptionException) {
                // The independently versioned children remain recoverable when only
                // the aggregate marker is absent or malformed.
                false
            }
        if (hasValidAggregateSchema) {
            rejectUnknownFields(root, aggregateFields, emptySet(), "core state")
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
        readSchemaVersion(json)
        expectFields(json, identityRequiredFields, setOf("migration"), "identity")
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
        readSchemaVersion(json)
        expectFields(json, streamFields, emptySet(), "stream")
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
        readSchemaVersion(json)
        expectFields(json, flagContextFields, emptySet(), "flagContext")
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
        if (bytes.size > MAX_PERSISTED_CORE_STATE_BYTES) {
            corrupt("Core state exceeds the maximum persisted size")
        }
        try {
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            if ('\u0000' in text) corrupt("Core state contains an invalid null character")
            validateStructuralDepth(text)
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

    private fun encodeBounded(json: JSONObject): ByteArray {
        val text = json.toString()
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_PERSISTED_CORE_STATE_BYTES) {
            corrupt("Core state exceeds the maximum persisted size")
        }
        validateStructuralDepth(text)
        return bytes
    }

    /**
     * Bounds recursive work before [JSONTokener] materializes the document. Quotes and escapes
     * are handled here so delimiter-looking customer strings do not affect the structural depth.
     */
    private fun validateStructuralDepth(text: String) {
        val containers = CharArray(MAX_STRUCTURAL_JSON_DEPTH)
        var depth = 0
        var inString = false
        var escaped = false

        text.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                return@forEach
            }

            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    if (depth == MAX_STRUCTURAL_JSON_DEPTH) {
                        corrupt("Core state exceeds the maximum JSON nesting depth")
                    }
                    containers[depth] = character
                    depth += 1
                }
                '}', ']' -> {
                    if (depth == 0) corrupt("Core state contains unmatched JSON delimiters")
                    val expectedOpening = if (character == '}') '{' else '['
                    if (containers[depth - 1] != expectedOpening) {
                        corrupt("Core state contains mismatched JSON delimiters")
                    }
                    depth -= 1
                }
            }
        }

        if (inString) corrupt("Core state contains an unterminated JSON string")
        if (depth != 0) corrupt("Core state contains unterminated JSON containers")
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
        rejectUnknownFields(actual, required, optional, path)
        val missing = required - actual
        if (missing.isNotEmpty()) corrupt("$path is missing fields: ${missing.sorted().joinToString()}")
    }

    private fun rejectUnknownFields(
        json: JSONObject,
        required: Set<String>,
        optional: Set<String>,
        path: String,
    ) = rejectUnknownFields(json.keys().asSequence().toSet(), required, optional, path)

    private fun rejectUnknownFields(
        actual: Set<String>,
        required: Set<String>,
        optional: Set<String>,
        path: String,
    ) {
        val unknown = actual - required - optional
        if (unknown.isNotEmpty()) {
            throw UnsupportedCoreSchemaExtensionException(path, unknown)
        }
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
            is String, is Boolean -> value
            is Number -> normalizeAndroidJsonNumber(value, path) { message -> corrupt(message) }
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
        depth: Int = 0,
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
            is String, is Boolean -> value
            // json-java uses BigDecimal for decimal tokens in host unit tests, while Android's
            // JSONTokener returns Double. Normalize only values that survive that conversion
            // exactly, so JVM tests model Android without masking arbitrary-precision loss.
            is BigDecimal -> decodeHostDecimal(value, path)
            is Number -> normalizeAndroidJsonNumber(value, path) { message -> corrupt(message) }
            is JSONObject -> decodeJsonObject(value, path, depth)
            is JSONArray -> {
                val out = ArrayList<Any?>(value.length())
                repeat(value.length()) { index -> out += decodeJsonValue(value.get(index), "$path[$index]", depth + 1) }
                Collections.unmodifiableList(out)
            }
            else -> corrupt("$path contains a non-JSON value of type ${value::class.java.name}")
        }
    }

    private fun decodeHostDecimal(
        value: BigDecimal,
        path: String,
    ): Number {
        val doubleValue = value.toDouble()
        if (!doubleValue.isFinite() || BigDecimal.valueOf(doubleValue).compareTo(value) != 0) {
            corrupt("$path cannot be represented exactly as a finite Android JSON number")
        }
        return normalizeAndroidJsonDouble(doubleValue, path) { message -> corrupt(message) }
    }

    private fun corrupt(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw CoreStateCorruptionException(message, cause)

    private fun <K, V> immutableMap(value: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(value))

    private class PreEncodeBudget {
        private var nodes: Int = 0
        private var estimatedBytes: Long = 0

        fun addObjectNode() {
            addNode()
            addBytes(2) // Braces.
        }

        fun addObjectEntry(key: String) {
            addQuotedStringBytes(key)
            addBytes(2) // Colon plus a conservatively counted comma.
        }

        fun addArrayNode() {
            addNode()
            addBytes(2) // Brackets.
        }

        fun addArrayEntry() {
            addBytes(1) // Conservatively count a comma for every element.
        }

        fun addString(value: String) {
            addNode()
            addQuotedStringBytes(value)
        }

        fun addNull() {
            addNode()
            addBytes(4)
        }

        fun addBoolean(value: Boolean) {
            addNode()
            addBytes(if (value) 4 else 5)
        }

        fun addInteger(value: Long) {
            addNode()
            var remaining = value
            var length = if (value < 0) 1L else 0L
            do {
                length += 1
                remaining /= 10
            } while (remaining != 0L)
            addBytes(length)
        }

        fun addFloatingPoint(value: Double) {
            if (!value.isFinite()) corruptBudget("Core state contains a non-finite JSON number")
            addNode()
            addBytes(MAX_FLOATING_POINT_JSON_BYTES)
        }

        private fun addQuotedStringBytes(value: String) {
            addBytes(2) // Quotes.
            var index = 0
            while (index < value.length) {
                val codePoint = Character.codePointAt(value, index)
                val bytes =
                    when {
                        codePoint > 0xffff -> 4L
                        codePoint == 0x22 || codePoint == 0x5c || codePoint == 0x2f -> 2L
                        codePoint < 0x20 -> 6L
                        codePoint in 0x80..0x9f || codePoint in 0x2000..0x20ff -> 6L
                        codePoint in 0xd800..0xdfff -> 6L // Unpaired surrogate.
                        codePoint < 0x80 -> 1L
                        codePoint < 0x800 -> 2L
                        else -> 3L
                    }
                addBytes(bytes)
                index += Character.charCount(codePoint)
            }
        }

        private fun addNode() {
            if (nodes >= MAX_PRE_ENCODE_JSON_NODES) {
                corruptBudget("Core state exceeds the pre-encode JSON node budget before materialization")
            }
            nodes += 1
        }

        private fun addBytes(amount: Long) {
            val limit = MAX_PERSISTED_CORE_STATE_BYTES.toLong()
            if (amount < 0 || estimatedBytes > limit - amount) {
                corruptBudget("Core state exceeds the maximum persisted size estimate before JSON materialization")
            }
            estimatedBytes += amount
        }

        private fun corruptBudget(message: String): Nothing =
            throw CoreStateCorruptionException(message)
    }

    private const val MAX_JSON_DEPTH = 64
    // A customer properties object can begin at aggregate -> flagContext ->
    // groupProperties -> group key (structural depth four), then use all 64
    // relative child levels accepted by the customer JSON validators.
    private const val CORE_STATE_WRAPPER_DEPTH = 4
    private const val MAX_STRUCTURAL_JSON_DEPTH = MAX_JSON_DEPTH + CORE_STATE_WRAPPER_DEPTH
    private const val MAX_PRE_ENCODE_JSON_NODES = 65_536
    private const val MAX_FLOATING_POINT_JSON_BYTES = 32L

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
            is String, is Boolean -> value
            is Number ->
                normalizeAndroidJsonNumber(value, path) { message ->
                    throw IllegalArgumentException(message)
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
