package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.JsonValues
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Collections
import java.util.GregorianCalendar
import java.util.LinkedHashMap
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal class RuntimeRecordCorruptionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class UnsupportedRuntimeRecordSchemaException(val foundVersion: Long) :
    IllegalStateException("Unsupported ELU runtime record schema version: $foundVersion")

internal class UnsupportedRuntimeRecordSchemaExtensionException(
    val recordPath: String,
    val unknownFields: Set<String>,
) : IllegalStateException(
        "$recordPath contains unsupported schema fields: ${unknownFields.sorted().joinToString()}",
    )

internal const val MAX_RUNTIME_RECORD_BYTES: Int = 10_485_760

/** Strict codecs for the frozen event and mutation schemas copied into test resources. */
internal object RuntimeRecordCodec {
    private val eventFields =
        setOf(
            "schemaVersion",
            "eventId",
            "streamId",
            "sequence",
            "contextRevision",
            "kind",
            "name",
            "occurredAt",
            "identity",
            "sessionId",
            "properties",
            "groups",
            "versions",
        )
    private val eventIdentityFields = setOf("anonymousId", "userId", "revision")
    private val versionFields = setOf("schemaVersion", "contractVersion", "platform", "runtime", "facade")
    private val versionComponentFields = setOf("name", "version")
    private val mutationEnvelopeFields = setOf("schemaVersion", "streamId", "versions", "mutations")
    private val mutationFields =
        setOf("mutationId", "sequence", "contextRevision", "occurredAt", "subject", "change")
    private val mutationSubjectFields = setOf("anonymousId", "userId", "identityRevision")

    fun encodeEvent(record: RuntimeEventRecord): ByteArray = encodeBounded(encodeEventObject(record))

    private fun encodeEventObject(record: RuntimeEventRecord): JSONObject {
        validateSchema(record.schemaVersion.toLong())
        if (record.identity.revision > record.contextRevision) {
            corrupt("event.identity.revision may not exceed event.contextRevision")
        }
        return JSONObject()
            .put("schemaVersion", RUNTIME_RECORD_SCHEMA_VERSION)
            .put("eventId", checkedString(record.eventId, 1, 256, "event.eventId"))
            .put("streamId", checkedString(record.streamId, 1, 256, "event.streamId"))
            .put("sequence", checkedNonNegative(record.sequence, "event.sequence"))
            .put(
                "contextRevision",
                checkedNonNegative(record.contextRevision, "event.contextRevision"),
            ).put("kind", record.kind.wireValue)
            .put("name", checkedString(record.name, 1, 512, "event.name"))
            .put("occurredAt", checkedTimestamp(record.occurredAt, "event.occurredAt"))
            .put("identity", encodeEventIdentity(record.identity))
            .put("sessionId", checkedString(record.sessionId, 1, 256, "event.sessionId"))
            .put("properties", encodeJsonObject(record.properties, "event.properties"))
            .put("groups", encodeGroups(record.groups))
            .put("versions", encodeVersions(record.versions))
    }

    fun decodeEvent(bytes: ByteArray): RuntimeEventRecord {
        val json = parseObject(bytes)
        readSchema(json)
        expectFields(json, eventFields, emptySet(), "event")
        val groupsJson = requiredObject(json, "groups", "event")
        if (groupsJson.length() > 64) corrupt("event.groups exceeds 64 entries")
        val groups = linkedMapOf<String, String>()
        groupsJson.keys().forEach { type ->
            requireWellFormedUnicode(type, "event.groups key")
            groups[type] = requiredString(groupsJson, type, 1, 512, "event.groups")
        }
        val kind = RuntimeEventKind.fromWireValue(requiredString(json, "kind", 1, 32, "event"))
        val occurredAt = requiredString(json, "occurredAt", 1, 128, "event")
        requireRfc3339(occurredAt, "event.occurredAt")
        val contextRevision = requiredNonNegativeLong(json, "contextRevision", "event")
        val identity = decodeEventIdentity(requiredObject(json, "identity", "event"))
        if (identity.revision > contextRevision) {
            corrupt("event.identity.revision may not exceed event.contextRevision")
        }
        return RuntimeEventRecord(
            eventId = requiredString(json, "eventId", 1, 256, "event"),
            streamId = requiredString(json, "streamId", 1, 256, "event"),
            sequence = requiredNonNegativeLong(json, "sequence", "event"),
            contextRevision = contextRevision,
            kind = kind,
            name = requiredString(json, "name", 1, 512, "event"),
            occurredAt = occurredAt,
            identity = identity,
            sessionId = requiredString(json, "sessionId", 1, 256, "event"),
            properties = decodeJsonObject(requiredObject(json, "properties", "event"), "event.properties"),
            groups = immutableMap(groups),
            versions = decodeVersions(requiredObject(json, "versions", "event")),
        )
    }

    fun encodeMutation(envelope: RuntimeMutationEnvelope): ByteArray {
        validateSchema(envelope.schemaVersion.toLong())
        val json =
            JSONObject()
                .put("schemaVersion", RUNTIME_RECORD_SCHEMA_VERSION)
                .put("streamId", checkedString(envelope.streamId, 1, 256, "mutation envelope.streamId"))
                .put("versions", encodeVersions(envelope.versions))
                .put("mutations", JSONArray().put(encodeMutationRecord(envelope.mutation)))
        return encodeBounded(json)
    }

    fun decodeMutation(bytes: ByteArray): RuntimeMutationEnvelope {
        val json = parseObject(bytes)
        readSchema(json)
        expectFields(json, mutationEnvelopeFields, emptySet(), "mutation envelope")
        val mutations = requiredArray(json, "mutations", "mutation envelope")
        if (mutations.length() != 1) {
            corrupt("A durable mutation queue row must contain exactly one mutation")
        }
        return RuntimeMutationEnvelope(
            streamId = requiredString(json, "streamId", 1, 256, "mutation envelope"),
            versions = decodeVersions(requiredObject(json, "versions", "mutation envelope")),
            mutation = decodeMutationRecord(requiredObject(mutations, 0, "mutation envelope.mutations")),
        )
    }

    /** Exact canonical V1BatchRecord bytes; request-envelope overhead is intentionally excluded. */
    fun encodeBatchRecord(record: RuntimeEventRecord): ByteArray =
        encodeBounded(
            JSONObject()
                .put("kind", RuntimeRecordKind.EVENT.wireValue)
                .put("event", encodeEventObject(record)),
        )

    /**
     * Mutation capture versions stay in [RuntimeMutationEnvelope] for future batch-envelope
     * packing, while the canonical V1BatchRecord contains only the mutation itself.
     */
    fun encodeBatchRecord(envelope: RuntimeMutationEnvelope): ByteArray {
        validateSchema(envelope.schemaVersion.toLong())
        checkedString(envelope.streamId, 1, 256, "mutation envelope.streamId")
        encodeVersions(envelope.versions)
        return encodeBounded(
            JSONObject()
                .put("kind", RuntimeRecordKind.MUTATION.wireValue)
                .put("mutation", encodeMutationRecord(envelope.mutation)),
        )
    }

    fun encodeBatchRecord(record: RuntimeQueuedRecord): ByteArray =
        when (record) {
            is RuntimeQueuedRecord.Event -> encodeBatchRecord(record.record)
            is RuntimeQueuedRecord.Mutation -> encodeBatchRecord(record.envelope)
        }

    fun decodeQueued(
        kind: RuntimeRecordKind,
        bytes: ByteArray,
        accountedBytes: Int,
    ): RuntimeQueuedRecord =
        when (kind) {
            RuntimeRecordKind.EVENT -> RuntimeQueuedRecord.Event(decodeEvent(bytes), accountedBytes)
            RuntimeRecordKind.MUTATION -> RuntimeQueuedRecord.Mutation(decodeMutation(bytes), accountedBytes)
        }

    /** Compares two validated RFC 3339 instants without relying on java.time below API 26. */
    fun compareTimestamps(
        left: String,
        right: String,
    ): Int {
        val leftTimestamp = parseRfc3339(left, "left timestamp")
        val rightTimestamp = parseRfc3339(right, "right timestamp")
        val seconds = leftTimestamp.epochSecond.compareTo(rightTimestamp.epochSecond)
        return if (seconds != 0) seconds else leftTimestamp.fraction.compareTo(rightTimestamp.fraction)
    }

    /** Compares `later - earlier` with an integral duration without losing fractional precision. */
    fun compareElapsedSeconds(
        later: String,
        earlier: String,
        seconds: Int,
    ): Int {
        require(seconds >= 0) { "seconds must be non-negative" }
        val laterTimestamp = parseRfc3339(later, "later timestamp")
        val earlierTimestamp = parseRfc3339(earlier, "earlier timestamp")
        val wholeSeconds = laterTimestamp.epochSecond - earlierTimestamp.epochSecond
        val wholeComparison = wholeSeconds.compareTo(seconds.toLong())
        return if (wholeComparison != 0) {
            wholeComparison
        } else {
            laterTimestamp.fraction.compareTo(earlierTimestamp.fraction)
        }
    }

    private fun encodeEventIdentity(identity: RuntimeEventIdentity): JSONObject =
        JSONObject()
            .put("anonymousId", checkedString(identity.anonymousId, 1, 256, "event.identity.anonymousId"))
            .put("userId", identity.userId?.let { checkedString(it, 1, 512, "event.identity.userId") } ?: JSONObject.NULL)
            .put("revision", checkedNonNegative(identity.revision, "event.identity.revision"))

    private fun decodeEventIdentity(json: JSONObject): RuntimeEventIdentity {
        expectFields(json, eventIdentityFields, emptySet(), "event.identity")
        return RuntimeEventIdentity(
            anonymousId = requiredString(json, "anonymousId", 1, 256, "event.identity"),
            userId = nullableString(json, "userId", 1, 512, "event.identity"),
            revision = requiredNonNegativeLong(json, "revision", "event.identity"),
        )
    }

    private fun encodeGroups(groups: Map<String, String>): JSONObject {
        if (groups.size > 64) corrupt("event.groups exceeds 64 entries")
        return JSONObject().apply {
            groups.forEach { (type, key) ->
                requireWellFormedUnicode(type, "event.groups key")
                put(type, checkedString(key, 1, 512, "event.groups.$type"))
            }
        }
    }

    private fun encodeVersions(versions: RuntimeVersions): JSONObject {
        validateSchema(versions.schemaVersion.toLong())
        if (versions.contractVersion != RUNTIME_CONTRACT_VERSION) {
            corrupt("versions.contractVersion must be $RUNTIME_CONTRACT_VERSION")
        }
        return JSONObject()
            .put("schemaVersion", RUNTIME_RECORD_SCHEMA_VERSION)
            .put("contractVersion", RUNTIME_CONTRACT_VERSION)
            .put("platform", versions.platform.wireValue)
            .put("runtime", encodeVersionComponent(versions.runtime, runtime = true))
            .put("facade", encodeVersionComponent(versions.facade, runtime = false))
            .apply {
                versions.build?.let { put("build", checkedString(it, 1, 128, "versions.build")) }
            }
    }

    private fun decodeVersions(json: JSONObject): RuntimeVersions {
        readSchema(json)
        expectFields(json, versionFields, setOf("build"), "versions")
        val contractVersion = requiredString(json, "contractVersion", 1, 64, "versions")
        if (contractVersion != RUNTIME_CONTRACT_VERSION) {
            corrupt("versions.contractVersion must be $RUNTIME_CONTRACT_VERSION")
        }
        return RuntimeVersions(
            contractVersion = contractVersion,
            platform = RuntimePlatform.fromWireValue(requiredString(json, "platform", 1, 32, "versions")),
            runtime = decodeVersionComponent(requiredObject(json, "runtime", "versions"), runtime = true),
            facade = decodeVersionComponent(requiredObject(json, "facade", "versions"), runtime = false),
            build = optionalString(json, "build", 1, 128, "versions"),
        )
    }

    private fun encodeVersionComponent(
        component: RuntimeVersionComponent,
        runtime: Boolean,
    ): JSONObject {
        val path = if (runtime) "versions.runtime" else "versions.facade"
        validateComponentName(component.name, runtime, "$path.name")
        return JSONObject()
            .put("name", component.name)
            .put("version", checkedString(component.version, 1, 64, "$path.version"))
    }

    private fun decodeVersionComponent(
        json: JSONObject,
        runtime: Boolean,
    ): RuntimeVersionComponent {
        val path = if (runtime) "versions.runtime" else "versions.facade"
        expectFields(json, versionComponentFields, emptySet(), path)
        val name = requiredString(json, "name", 1, 128, path)
        validateComponentName(name, runtime, "$path.name")
        return RuntimeVersionComponent(name, requiredString(json, "version", 1, 64, path))
    }

    private fun validateComponentName(
        name: String,
        runtime: Boolean,
        path: String,
    ) {
        val pattern = if (runtime) RUNTIME_NAME else FACADE_NAME
        if (!pattern.matches(name)) corrupt("$path is malformed")
    }

    private fun encodeMutationRecord(mutation: RuntimeMutationRecord): JSONObject =
        JSONObject().also {
            if (mutation.subject.identityRevision > mutation.contextRevision) {
                corrupt("mutation.subject.identityRevision may not exceed mutation.contextRevision")
            }
        }
            .put("mutationId", checkedString(mutation.mutationId, 1, 256, "mutation.mutationId"))
            .put("sequence", checkedNonNegative(mutation.sequence, "mutation.sequence"))
            .put(
                "contextRevision",
                checkedNonNegative(mutation.contextRevision, "mutation.contextRevision"),
            ).put("occurredAt", checkedTimestamp(mutation.occurredAt, "mutation.occurredAt"))
            .put("subject", encodeMutationSubject(mutation.subject))
            .put("change", encodeMutationChange(mutation.change))

    private fun decodeMutationRecord(json: JSONObject): RuntimeMutationRecord {
        expectFields(json, mutationFields, emptySet(), "mutation")
        val occurredAt = requiredString(json, "occurredAt", 1, 128, "mutation")
        requireRfc3339(occurredAt, "mutation.occurredAt")
        val contextRevision = requiredNonNegativeLong(json, "contextRevision", "mutation")
        val subject = decodeMutationSubject(requiredObject(json, "subject", "mutation"))
        if (subject.identityRevision > contextRevision) {
            corrupt("mutation.subject.identityRevision may not exceed mutation.contextRevision")
        }
        return RuntimeMutationRecord(
            mutationId = requiredString(json, "mutationId", 1, 256, "mutation"),
            sequence = requiredNonNegativeLong(json, "sequence", "mutation"),
            contextRevision = contextRevision,
            occurredAt = occurredAt,
            subject = subject,
            change = decodeMutationChange(requiredObject(json, "change", "mutation")),
        )
    }

    private fun encodeMutationSubject(subject: RuntimeMutationSubject): JSONObject =
        JSONObject()
            .put("anonymousId", checkedString(subject.anonymousId, 1, 256, "mutation.subject.anonymousId"))
            .put("userId", subject.userId?.let { checkedString(it, 1, 512, "mutation.subject.userId") } ?: JSONObject.NULL)
            .put(
                "identityRevision",
                checkedNonNegative(subject.identityRevision, "mutation.subject.identityRevision"),
            )

    private fun decodeMutationSubject(json: JSONObject): RuntimeMutationSubject {
        expectFields(json, mutationSubjectFields, emptySet(), "mutation.subject")
        return RuntimeMutationSubject(
            anonymousId = requiredString(json, "anonymousId", 1, 256, "mutation.subject"),
            userId = nullableString(json, "userId", 1, 512, "mutation.subject"),
            identityRevision = requiredNonNegativeLong(json, "identityRevision", "mutation.subject"),
        )
    }

    private fun encodeMutationChange(change: RuntimeMutationChange): JSONObject =
        when (change) {
            is RuntimeMutationChange.Identify ->
                JSONObject()
                    .put("type", change.type)
                    .put("userId", checkedString(change.userId, 1, 512, "mutation.change.userId"))
                    .put("set", encodeJsonObject(change.set, "mutation.change.set"))
                    .put("setOnce", encodeJsonObject(change.setOnce, "mutation.change.setOnce"))
            is RuntimeMutationChange.LinkAlias ->
                JSONObject()
                    .put("type", change.type)
                    .put("aliasId", checkedString(change.aliasId, 1, 512, "mutation.change.aliasId"))
                    .put(
                        "canonicalId",
                        checkedString(change.canonicalId, 1, 512, "mutation.change.canonicalId"),
                    )
            is RuntimeMutationChange.SetPersonProperties ->
                JSONObject()
                    .put("type", change.type)
                    .put("set", encodeJsonObject(change.set, "mutation.change.set"))
                    .put("setOnce", encodeJsonObject(change.setOnce, "mutation.change.setOnce"))
                    .put("unset", encodePropertyNames(change.unset, "mutation.change.unset"))
            is RuntimeMutationChange.AssociateGroup ->
                JSONObject()
                    .put("type", change.type)
                    .put(
                        "groupType",
                        checkedString(change.groupType, 1, 256, "mutation.change.groupType"),
                    ).put(
                        "groupKey",
                        checkedString(change.groupKey, 1, 512, "mutation.change.groupKey"),
                    )
            is RuntimeMutationChange.SetGroupProperties ->
                JSONObject()
                    .put("type", change.type)
                    .put(
                        "groupType",
                        checkedString(change.groupType, 1, 256, "mutation.change.groupType"),
                    ).put(
                        "groupKey",
                        checkedString(change.groupKey, 1, 512, "mutation.change.groupKey"),
                    ).put("set", encodeJsonObject(change.set, "mutation.change.set"))
                    .put("setOnce", encodeJsonObject(change.setOnce, "mutation.change.setOnce"))
                    .put("unset", encodePropertyNames(change.unset, "mutation.change.unset"))
        }

    private fun decodeMutationChange(json: JSONObject): RuntimeMutationChange =
        when (val type = requiredString(json, "type", 1, 64, "mutation.change")) {
            "identify" -> {
                expectFields(json, setOf("type", "userId", "set", "setOnce"), emptySet(), "mutation.change")
                RuntimeMutationChange.Identify(
                    userId = requiredString(json, "userId", 1, 512, "mutation.change"),
                    set = decodeJsonObject(requiredObject(json, "set", "mutation.change"), "mutation.change.set"),
                    setOnce =
                        decodeJsonObject(
                            requiredObject(json, "setOnce", "mutation.change"),
                            "mutation.change.setOnce",
                        ),
                )
            }
            "linkAlias" -> {
                expectFields(json, setOf("type", "aliasId", "canonicalId"), emptySet(), "mutation.change")
                RuntimeMutationChange.LinkAlias(
                    aliasId = requiredString(json, "aliasId", 1, 512, "mutation.change"),
                    canonicalId = requiredString(json, "canonicalId", 1, 512, "mutation.change"),
                )
            }
            "setPersonProperties" -> {
                expectFields(
                    json,
                    setOf("type", "set", "setOnce", "unset"),
                    emptySet(),
                    "mutation.change",
                )
                RuntimeMutationChange.SetPersonProperties(
                    set = decodeJsonObject(requiredObject(json, "set", "mutation.change"), "mutation.change.set"),
                    setOnce =
                        decodeJsonObject(
                            requiredObject(json, "setOnce", "mutation.change"),
                            "mutation.change.setOnce",
                        ),
                    unset = decodePropertyNames(requiredArray(json, "unset", "mutation.change"), "mutation.change.unset"),
                )
            }
            "associateGroup" -> {
                expectFields(json, setOf("type", "groupType", "groupKey"), emptySet(), "mutation.change")
                RuntimeMutationChange.AssociateGroup(
                    groupType = requiredString(json, "groupType", 1, 256, "mutation.change"),
                    groupKey = requiredString(json, "groupKey", 1, 512, "mutation.change"),
                )
            }
            "setGroupProperties" -> {
                expectFields(
                    json,
                    setOf("type", "groupType", "groupKey", "set", "setOnce", "unset"),
                    emptySet(),
                    "mutation.change",
                )
                RuntimeMutationChange.SetGroupProperties(
                    groupType = requiredString(json, "groupType", 1, 256, "mutation.change"),
                    groupKey = requiredString(json, "groupKey", 1, 512, "mutation.change"),
                    set = decodeJsonObject(requiredObject(json, "set", "mutation.change"), "mutation.change.set"),
                    setOnce =
                        decodeJsonObject(
                            requiredObject(json, "setOnce", "mutation.change"),
                            "mutation.change.setOnce",
                        ),
                    unset = decodePropertyNames(requiredArray(json, "unset", "mutation.change"), "mutation.change.unset"),
                )
            }
            else -> corrupt("Unsupported mutation change type: $type")
        }

    private fun encodePropertyNames(
        names: List<String>,
        path: String,
    ): JSONArray {
        if (names.size > 256) corrupt("$path exceeds 256 entries")
        if (names.toSet().size != names.size) corrupt("$path contains duplicate property names")
        return JSONArray().apply {
            names.forEach { put(checkedString(it, 1, 512, path)) }
        }
    }

    private fun decodePropertyNames(
        names: JSONArray,
        path: String,
    ): List<String> {
        if (names.length() > 256) corrupt("$path exceeds 256 entries")
        val decoded = List(names.length()) { index -> requiredString(names, index, 1, 512, path) }
        if (decoded.toSet().size != decoded.size) corrupt("$path contains duplicate property names")
        return Collections.unmodifiableList(decoded)
    }

    private fun encodeJsonObject(
        value: Map<String, Any?>,
        path: String,
    ): JSONObject {
        val normalized =
            try {
                JsonValues.objectValue(value, path)
            } catch (error: IllegalArgumentException) {
                corrupt(error.message ?: "$path contains an invalid JSON value", error)
            }
        return encodeNormalizedObject(normalized, path, 0)
    }

    private fun encodeNormalizedObject(
        value: Map<String, Any?>,
        path: String,
        depth: Int,
    ): JSONObject {
        if (depth > MAX_JSON_DEPTH) corrupt("$path exceeds the maximum JSON nesting depth")
        return JSONObject().apply {
            value.forEach { (key, child) ->
                requireWellFormedUnicode(key, "$path key")
                put(key, encodeJsonValue(child, "$path.$key", depth + 1))
            }
        }
    }

    private fun encodeJsonValue(
        value: Any?,
        path: String,
        depth: Int,
    ): Any {
        if (depth > MAX_JSON_DEPTH) corrupt("$path exceeds the maximum JSON nesting depth")
        return when (value) {
            null -> JSONObject.NULL
            is String -> checkedString(value, 0, Int.MAX_VALUE, path)
            is Boolean, is Number -> value
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                encodeNormalizedObject(value as Map<String, Any?>, path, depth)
            }
            is List<*> -> JSONArray().apply {
                value.forEachIndexed { index, child -> put(encodeJsonValue(child, "$path[$index]", depth + 1)) }
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
            requireWellFormedUnicode(key, "$path key")
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
            is String -> checkedString(value, 0, Int.MAX_VALUE, path)
            is Boolean -> value
            is BigDecimal -> decodeHostDecimal(value, path)
            is Number -> normalizeNumber(value, path)
            is JSONObject -> decodeJsonObject(value, path, depth)
            is JSONArray ->
                Collections.unmodifiableList(
                    List(value.length()) { index -> decodeJsonValue(value.get(index), "$path[$index]", depth + 1) },
                )
            else -> corrupt("$path contains a non-JSON value of type ${value::class.java.name}")
        }
    }

    private fun normalizeNumber(
        value: Number,
        path: String,
    ): Number =
        try {
            JsonValues.objectValue(mapOf("value" to value), path).getValue("value") as Number
        } catch (error: IllegalArgumentException) {
            corrupt(error.message ?: "$path contains an invalid number", error)
        }

    private fun decodeHostDecimal(
        value: BigDecimal,
        path: String,
    ): Number {
        val doubleValue = value.toDouble()
        if (!doubleValue.isFinite() || BigDecimal.valueOf(doubleValue).compareTo(value) != 0) {
            corrupt("$path cannot be represented exactly as a finite Android JSON number")
        }
        return normalizeNumber(doubleValue, path)
    }

    private fun parseObject(bytes: ByteArray): JSONObject {
        if (bytes.size > MAX_RUNTIME_RECORD_BYTES) corrupt("Runtime record exceeds the maximum size")
        try {
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            if ('\u0000' in text) corrupt("Runtime record contains an invalid null character")
            validateStructuralDepth(text)
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            if (value !is JSONObject) corrupt("Runtime record root must be an object")
            if (tokener.nextClean() != '\u0000') corrupt("Runtime record contains trailing content")
            return value
        } catch (error: RuntimeRecordCorruptionException) {
            throw error
        } catch (error: JSONException) {
            throw RuntimeRecordCorruptionException("Runtime record is not valid JSON", error)
        } catch (error: CharacterCodingException) {
            throw RuntimeRecordCorruptionException("Runtime record is not valid UTF-8", error)
        } catch (error: RuntimeException) {
            throw RuntimeRecordCorruptionException("Runtime record could not be decoded", error)
        }
    }

    private fun encodeBounded(json: JSONObject): ByteArray {
        val text = json.toString()
        validateStructuralDepth(text)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_RUNTIME_RECORD_BYTES) corrupt("Runtime record exceeds the maximum size")
        return bytes
    }

    private fun validateStructuralDepth(text: String) {
        val containers = CharArray(MAX_STRUCTURAL_DEPTH)
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
                    if (depth == MAX_STRUCTURAL_DEPTH) corrupt("Runtime record exceeds the maximum JSON nesting depth")
                    containers[depth++] = character
                }
                '}', ']' -> {
                    if (depth == 0) corrupt("Runtime record contains unmatched JSON delimiters")
                    val expected = if (character == '}') '{' else '['
                    if (containers[depth - 1] != expected) corrupt("Runtime record contains mismatched JSON delimiters")
                    depth -= 1
                }
            }
        }
        if (inString) corrupt("Runtime record contains an unterminated JSON string")
        if (depth != 0) corrupt("Runtime record contains unterminated JSON containers")
    }

    private fun readSchema(json: JSONObject) {
        validateSchema(requiredLong(json, "schemaVersion", "record"))
    }

    private fun validateSchema(version: Long) {
        if (version != RUNTIME_RECORD_SCHEMA_VERSION.toLong()) {
            throw UnsupportedRuntimeRecordSchemaException(version)
        }
    }

    private fun expectFields(
        json: JSONObject,
        required: Set<String>,
        optional: Set<String>,
        path: String,
    ) {
        val actual = json.keys().asSequence().toSet()
        val unknown = actual - required - optional
        if (unknown.isNotEmpty()) throw UnsupportedRuntimeRecordSchemaExtensionException(path, unknown)
        val missing = required - actual
        if (missing.isNotEmpty()) corrupt("$path is missing fields: ${missing.sorted().joinToString()}")
    }

    private fun requiredObject(
        json: JSONObject,
        key: String,
        path: String,
    ): JSONObject = json.opt(key) as? JSONObject ?: corrupt("$path.$key must be an object")

    private fun requiredObject(
        array: JSONArray,
        index: Int,
        path: String,
    ): JSONObject = array.opt(index) as? JSONObject ?: corrupt("$path[$index] must be an object")

    private fun requiredArray(
        json: JSONObject,
        key: String,
        path: String,
    ): JSONArray = json.opt(key) as? JSONArray ?: corrupt("$path.$key must be an array")

    private fun requiredString(
        json: JSONObject,
        key: String,
        minLength: Int,
        maxLength: Int,
        path: String,
    ): String =
        checkedString(
            json.opt(key) as? String ?: corrupt("$path.$key must be a string"),
            minLength,
            maxLength,
            "$path.$key",
        )

    private fun requiredString(
        array: JSONArray,
        index: Int,
        minLength: Int,
        maxLength: Int,
        path: String,
    ): String =
        checkedString(
            array.opt(index) as? String ?: corrupt("$path[$index] must be a string"),
            minLength,
            maxLength,
            "$path[$index]",
        )

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

    private fun optionalString(
        json: JSONObject,
        key: String,
        minLength: Int,
        maxLength: Int,
        path: String,
    ): String? = if (json.has(key)) requiredString(json, key, minLength, maxLength, path) else null

    private fun requiredNonNegativeLong(
        json: JSONObject,
        key: String,
        path: String,
    ): Long = checkedNonNegative(requiredLong(json, key, path), "$path.$key")

    private fun requiredLong(
        json: JSONObject,
        key: String,
        path: String,
    ): Long {
        if (!json.has(key) || json.isNull(key)) corrupt("$path.$key must be an integer")
        val value = json.get(key)
        if (value !is Number || value is Float && !value.isFinite() || value is Double && !value.isFinite()) {
            corrupt("$path.$key must be a finite integer")
        }
        return try {
            BigDecimal(value.toString()).longValueExact()
        } catch (error: RuntimeException) {
            corrupt("$path.$key must be an integer in the signed 64-bit range", error)
        }
    }

    private fun checkedNonNegative(
        value: Long,
        path: String,
    ): Long {
        if (value < 0) corrupt("$path must be non-negative")
        return value
    }

    private fun checkedString(
        value: String,
        minLength: Int,
        maxLength: Int,
        path: String,
    ): String {
        requireWellFormedUnicode(value, path)
        val length = value.codePointCount(0, value.length)
        if (length !in minLength..maxLength) corrupt("$path length must be in $minLength..$maxLength")
        return value
    }

    private fun requireWellFormedUnicode(
        value: String,
        path: String,
    ) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        corrupt("$path contains an unpaired UTF-16 surrogate")
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> corrupt("$path contains an unpaired UTF-16 surrogate")
                else -> index += 1
            }
        }
    }

    private fun checkedTimestamp(
        value: String,
        path: String,
    ): String {
        checkedString(value, 1, 128, path)
        requireRfc3339(value, path)
        return value
    }

    private fun requireRfc3339(
        value: String,
        path: String,
    ) {
        parseRfc3339(value, path)
    }

    private fun parseRfc3339(
        value: String,
        path: String,
    ): ParsedTimestamp {
        checkedString(value, 1, 128, path)
        val match = RFC_3339.matchEntire(value) ?: corrupt("$path must be an RFC 3339 timestamp")
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        val offsetHours = match.groupValues[10].ifEmpty { "0" }.toInt()
        val offsetMinutes = match.groupValues[11].ifEmpty { "0" }.toInt()
        if (offsetHours > 23 || offsetMinutes > 59 || hour > 23 || minute > 59 || second > 60) {
            corrupt("$path contains an invalid timestamp component")
        }
        val localEpochSecond =
            try {
                GregorianCalendar(UTC).apply {
                    isLenient = false
                    gregorianChange = java.util.Date(Long.MIN_VALUE)
                    clear()
                    set(Calendar.ERA, GregorianCalendar.AD)
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month - 1)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, minOf(second, 59))
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis.let { millis -> Math.floorDiv(millis, 1_000L) }
            } catch (error: IllegalArgumentException) {
                corrupt("$path contains an invalid calendar date", error)
            }
        val sign = if (match.groupValues[9] == "-") -1 else 1
        val offsetSeconds = sign * (offsetHours * 3_600L + offsetMinutes * 60L)
        if (second == 60) {
            val utcBoundary =
                daysFromCivil(year, month, day) * SECONDS_PER_DAY +
                    hour * 3_600L + minute * 60L + 60L - offsetSeconds
            val utcDay = Math.floorDiv(utcBoundary, SECONDS_PER_DAY)
            if (
                Math.floorMod(utcBoundary, SECONDS_PER_DAY) != 0L ||
                (year - 1..year + 1).none { candidate ->
                    utcDay == daysFromCivil(candidate, 1, 1) || utcDay == daysFromCivil(candidate, 7, 1)
                }
            ) {
                corrupt("$path contains an invalid leap second")
            }
        }
        val epochSecond =
            try {
                Math.addExact(Math.subtractExact(localEpochSecond, offsetSeconds), if (second == 60) 1L else 0L)
            } catch (error: ArithmeticException) {
                corrupt("$path is outside the supported timestamp range", error)
            }
        val fraction =
            match.groupValues[7]
                .takeIf(String::isNotEmpty)
                ?.let { digits -> BigDecimal("0.$digits") }
                ?: BigDecimal.ZERO
        return ParsedTimestamp(epochSecond, fraction)
    }

    private fun daysFromCivil(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        val adjustedYear = year - if (month <= 2) 1 else 0
        val era = Math.floorDiv(adjustedYear, 400)
        val yearOfEra = adjustedYear - era * 400
        val shiftedMonth = month + if (month > 2) -3 else 9
        val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
        return era * 146_097L + yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear - 719_468L
    }

    private fun <K, V> immutableMap(value: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(value))

    private fun corrupt(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw RuntimeRecordCorruptionException(message, cause)

    private const val MAX_JSON_DEPTH = 64
    private const val MAX_STRUCTURAL_DEPTH = MAX_JSON_DEPTH + 5
    private const val SECONDS_PER_DAY = 86_400L

    private data class ParsedTimestamp(
        val epochSecond: Long,
        val fraction: BigDecimal,
    )

    private val UTC = TimeZone.getTimeZone("UTC")
    private val RUNTIME_NAME = Regex("^elu-[a-z0-9-]+$")
    private val FACADE_NAME = Regex("^[A-Za-z][A-Za-z0-9._-]+$")
    private val RFC_3339 =
        Regex(
            "^(\\d{4})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?" +
                "([Zz]|([+-])(\\d{2}):(\\d{2}))$",
        )
}
