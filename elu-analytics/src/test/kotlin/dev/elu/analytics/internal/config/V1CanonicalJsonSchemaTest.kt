package dev.elu.analytics.internal.config

import java.math.BigDecimal
import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the pinned canonical fixtures against their copied draft-2020-12 schemas. */
class V1CanonicalJsonSchemaTest {
    @Test
    fun `manifest-routed canonical fixtures validate against executable schemas`() {
        val manifest = json("contracts/v1/manifest.json")
        val schemas = manifest.getJSONObject("schemas")
        val fixturePaths = manifest.getJSONArray("fixtures").strings().toSet()
        val validator = ResourceJsonSchemaValidator("contracts/v1")

        assertTrue("manifest must route enabled config fixture", "fixtures/config-enabled.json" in fixturePaths)
        assertTrue("manifest must route disabled config fixture", "fixtures/config-disabled.json" in fixturePaths)
        assertTrue("manifest must route allowed privacy fixture", "fixtures/privacy-allowed.json" in fixturePaths)
        assertTrue("manifest must route blocked privacy fixture", "fixtures/privacy-blocked.json" in fixturePaths)
        assertTrue("manifest must route canonical event fixture", "fixtures/event.json" in fixturePaths)
        assertTrue("manifest must route canonical mutations fixture", "fixtures/mutations.json" in fixturePaths)
        assertTrue("manifest must route canonical version fixture", "fixtures/version.json" in fixturePaths)
        assertTrue("manifest must route canonical batch acknowledgement", "fixtures/batch-ack.json" in fixturePaths)
        assertTrue("manifest must route canonical transport policy", "fixtures/transport-policy.json" in fixturePaths)

        val enabled = json("contracts/v1/fixtures/config-enabled.json")
        assertValid(validator, schemas.getString("config"), enabled)
        assertValid(validator, schemas.getString("config"), json("contracts/v1/fixtures/config-disabled.json"))
        assertValid(validator, schemas.getString("privacyPolicy"), enabled.getJSONObject("privacy"))
        assertValid(validator, schemas.getString("privacyState"), json("contracts/v1/fixtures/privacy-allowed.json"))
        assertValid(validator, schemas.getString("privacyState"), json("contracts/v1/fixtures/privacy-blocked.json"))
        assertValid(validator, schemas.getString("event"), json("contracts/v1/fixtures/event.json"))
        assertValid(validator, schemas.getString("mutation"), json("contracts/v1/fixtures/mutations.json"))
        assertValid(validator, schemas.getString("version"), json("contracts/v1/fixtures/version.json"))
        assertValid(validator, schemas.getString("batchRequest"), json("contracts/v1/fixtures/batch-request.json"))
        assertValid(validator, schemas.getString("batchAck"), json("contracts/v1/fixtures/batch-ack.json"))
        assertValid(validator, schemas.getString("batchAck"), json("contracts/v1/fixtures/batch-ack-retryable-head.json"))
        listOf(
            "unauthorized",
            "forbidden",
            "payload-too-large",
            "rate-limited",
            "service-unavailable",
        ).forEach { fixture ->
            assertValid(
                validator,
                schemas.getString("transportError"),
                json("contracts/v1/fixtures/transport-error-$fixture.json"),
            )
        }
        assertValid(validator, schemas.getString("transportPolicy"), json("contracts/v1/fixtures/transport-policy.json"))
    }

    @Test
    fun `schema validator exercises closed shapes conditional requirements and scalar constraints`() {
        val manifest = json("contracts/v1/manifest.json")
        val schemas = manifest.getJSONObject("schemas")
        val validator = ResourceJsonSchemaValidator("contracts/v1")

        val missingEnabledSite = json("contracts/v1/fixtures/config-enabled.json").apply { remove("site") }
        assertInvalid(validator, schemas.getString("config"), missingEnabledSite)

        val inactiveWithEndpoint = json("contracts/v1/fixtures/config-disabled.json").apply {
            put("endpoints", JSONObject().put("events", "https://ingest.elu.dev/v1/events").put("flags", "https://ingest.elu.dev/v1/flags"))
        }
        assertInvalid(validator, schemas.getString("config"), inactiveWithEndpoint)

        val unknownPrivacyField = json("contracts/v1/fixtures/privacy-allowed.json").put("futureField", true)
        assertInvalid(validator, schemas.getString("privacyState"), unknownPrivacyField)

        val malformedHash = json("contracts/v1/fixtures/privacy-allowed.json").put("effectivePolicyHash", "SHA256:bad")
        assertInvalid(validator, schemas.getString("privacyState"), malformedHash)

        val invalidLeapSecond =
            json("contracts/v1/fixtures/privacy-allowed.json").apply {
                getJSONObject("onDeviceDecision").put("evaluatedAt", "2030-06-30T12:34:60Z")
            }
        assertInvalid(validator, schemas.getString("privacyState"), invalidLeapSecond)

        val insecurePolicy =
            json("contracts/v1/fixtures/config-enabled.json")
                .getJSONObject("privacy")
                .apply { getJSONObject("masking").put("secureInputsMasked", false) }
        assertInvalid(validator, schemas.getString("privacyPolicy"), insecurePolicy)

        val eventWithFutureField = json("contracts/v1/fixtures/event.json").put("futureField", true)
        assertInvalid(validator, schemas.getString("event"), eventWithFutureField)

        val tooManyGroups = json("contracts/v1/fixtures/event.json")
        tooManyGroups.put(
            "groups",
            JSONObject().apply { repeat(65) { index -> put("type-$index", "group-$index") } },
        )
        assertInvalid(validator, schemas.getString("event"), tooManyGroups)

        val duplicateUnset = json("contracts/v1/fixtures/mutations.json")
        duplicateUnset.getJSONArray("mutations").getJSONObject(2).getJSONObject("change")
            .put("unset", JSONArray().put("role").put("role"))
        assertInvalid(validator, schemas.getString("mutation"), duplicateUnset)

        val malformedRuntimeName = json("contracts/v1/fixtures/version.json")
        malformedRuntimeName.getJSONObject("runtime").put("name", "invalid_runtime")
        assertInvalid(validator, schemas.getString("version"), malformedRuntimeName)
    }

    private fun assertValid(
        validator: ResourceJsonSchemaValidator,
        schemaPath: String,
        value: Any,
    ) {
        val errors = validator.validate(schemaPath, value)
        assertTrue("Expected $schemaPath to accept fixture, got ${errors.joinToString()}", errors.isEmpty())
    }

    private fun assertInvalid(
        validator: ResourceJsonSchemaValidator,
        schemaPath: String,
        value: Any,
    ) {
        assertFalse("Expected $schemaPath to reject mutation", validator.validate(schemaPath, value).isEmpty())
    }

    private fun json(path: String): JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing test resource $path" }
                .use { String(it.readBytes(), StandardCharsets.UTF_8) },
        )
}

private class ResourceJsonSchemaValidator(
    private val contractRoot: String,
) {
    private val documents = mutableMapOf<String, JSONObject>()

    fun validate(
        schemaRelativePath: String,
        value: Any?,
    ): List<String> {
        val path = normalizePath(schemaRelativePath)
        val schema = load(path)
        assertSupportedSchema(schema, "$")
        return buildList { evaluate(schema, value.normalized(), Context(path, schema), "$", this) }
    }

    private fun evaluate(
        schemaValue: Any?,
        value: Any?,
        context: Context,
        instancePath: String,
        errors: MutableList<String>,
    ) {
        if (schemaValue is Boolean) {
            if (!schemaValue) errors += "$instancePath is forbidden"
            return
        }
        val schema = schemaValue as? JSONObject ?: error("Schema at $instancePath is not an object or boolean")

        if (schema.has("\$ref")) {
            val resolved = resolveReference(schema.getString("\$ref"), context)
            evaluate(resolved.schema, value, resolved, instancePath, errors)
        }

        val types =
            when (val type = schema.opt("type")) {
                is String -> listOf(type)
                is JSONArray -> type.strings()
                else -> emptyList()
            }
        if (types.isNotEmpty() && types.none { matchesType(value, it) }) {
            errors += "$instancePath must have type ${types.joinToString(" or ")}"
            return
        }

        if (schema.has("const") && stableJson(value) != stableJson(schema.get("const").normalized())) {
            errors += "$instancePath does not equal const"
        }
        (schema.opt("enum") as? JSONArray)?.let { allowed ->
            if ((0 until allowed.length()).none { stableJson(value) == stableJson(allowed.get(it).normalized()) }) {
                errors += "$instancePath is not an enum value"
            }
        }

        evaluateBranches(schema.optJSONArray("allOf"), value, context, instancePath, errors)
        schema.optJSONArray("anyOf")?.let { branches ->
            val matches = branches.schemas().count { branch -> branchMatches(branch, value, context, instancePath) }
            if (matches == 0) errors += "$instancePath must match anyOf"
        }
        schema.optJSONArray("oneOf")?.let { branches ->
            val matches = branches.schemas().count { branch -> branchMatches(branch, value, context, instancePath) }
            if (matches != 1) errors += "$instancePath must match exactly one oneOf branch"
        }
        if (schema.has("not") && branchMatches(schema.get("not"), value, context, instancePath)) {
            errors += "$instancePath matches forbidden schema"
        }
        if (schema.has("if")) {
            if (branchMatches(schema.get("if"), value, context, instancePath)) {
                if (schema.has("then")) evaluate(schema.get("then"), value, context, instancePath, errors)
            } else if (schema.has("else")) {
                evaluate(schema.get("else"), value, context, instancePath, errors)
            }
        }

        when (value) {
            is String -> validateString(schema, value, instancePath, errors)
            is Number -> validateNumber(schema, value, instancePath, errors)
            is JSONArray -> validateArray(schema, value, context, instancePath, errors)
            is JSONObject -> validateObject(schema, value, context, instancePath, errors)
        }
    }

    private fun validateString(
        schema: JSONObject,
        value: String,
        path: String,
        errors: MutableList<String>,
    ) {
        val codePointLength = value.codePointCount(0, value.length)
        if (schema.has("minLength") && codePointLength < schema.getInt("minLength")) errors += "$path is too short"
        if (schema.has("maxLength") && codePointLength > schema.getInt("maxLength")) errors += "$path is too long"
        if (schema.has("pattern") && !Regex(schema.getString("pattern")).containsMatchIn(value)) {
            errors += "$path does not match pattern"
        }
        when (schema.optString("format", "")) {
            "date-time" -> if (!isRfc3339(value)) errors += "$path is not an RFC 3339 date-time"
            "uri" -> {
                val valid =
                    value.all { it.code in 0x21..0x7e } &&
                        runCatching { URI(value) }.getOrNull()?.isAbsolute == true
                if (!valid) errors += "$path is not an absolute URI"
            }
        }
    }

    private fun isRfc3339(value: String): Boolean {
        val match = RFC_3339.matchEntire(value) ?: return false
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        val offsetHours = match.groupValues[9].ifEmpty { "0" }.toInt()
        val offsetMinutes = match.groupValues[10].ifEmpty { "0" }.toInt()
        if (
            month !in 1..12 || day !in 1..daysInMonth(year, month) || hour > 23 || minute > 59 ||
            second > 60 || offsetHours > 23 || offsetMinutes > 59
        ) {
            return false
        }
        if (second != 60) return true
        val sign = if (match.groupValues[8] == "-") -1 else 1
        val offsetSeconds = sign * (offsetHours * 3_600L + offsetMinutes * 60L)
        val utcBoundary =
            daysFromCivil(year, month, day) * SECONDS_PER_DAY +
                hour * 3_600L + minute * 60L + 60L - offsetSeconds
        if (Math.floorMod(utcBoundary, SECONDS_PER_DAY) != 0L) return false
        val utcDay = Math.floorDiv(utcBoundary, SECONDS_PER_DAY)
        return (year - 1..year + 1).any { candidate ->
            utcDay == daysFromCivil(candidate, 1, 1) || utcDay == daysFromCivil(candidate, 7, 1)
        }
    }

    private fun daysInMonth(
        year: Int,
        month: Int,
    ): Int =
        when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
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

    private fun validateNumber(
        schema: JSONObject,
        value: Number,
        path: String,
        errors: MutableList<String>,
    ) {
        val decimal = BigDecimal(value.toString())
        if (schema.has("minimum") && decimal < BigDecimal(schema.get("minimum").toString())) errors += "$path is below minimum"
        if (schema.has("maximum") && decimal > BigDecimal(schema.get("maximum").toString())) errors += "$path is above maximum"
    }

    private fun validateArray(
        schema: JSONObject,
        value: JSONArray,
        context: Context,
        path: String,
        errors: MutableList<String>,
    ) {
        if (schema.has("minItems") && value.length() < schema.getInt("minItems")) errors += "$path has too few items"
        if (schema.has("maxItems") && value.length() > schema.getInt("maxItems")) errors += "$path has too many items"
        if (schema.optBoolean("uniqueItems", false)) {
            val unique = (0 until value.length()).map { stableJson(value.get(it).normalized()) }.toSet()
            if (unique.size != value.length()) errors += "$path items are not unique"
        }
        val prefixItems = schema.optJSONArray("prefixItems")
        prefixItems?.let { prefix ->
            repeat(minOf(prefix.length(), value.length())) { index ->
                evaluate(prefix.get(index), value.get(index).normalized(), context, "$path/$index", errors)
            }
        }
        if (schema.has("items")) {
            val start = prefixItems?.length() ?: 0
            for (index in start until value.length()) {
                evaluate(schema.get("items"), value.get(index).normalized(), context, "$path/$index", errors)
            }
        }
    }

    private fun validateObject(
        schema: JSONObject,
        value: JSONObject,
        context: Context,
        path: String,
        errors: MutableList<String>,
    ) {
        val keys = value.keys().asSequence().toSet()
        if (schema.has("maxProperties") && keys.size > schema.getInt("maxProperties")) {
            errors += "$path has too many properties"
        }
        schema.optJSONArray("required")?.strings()?.forEach { required ->
            if (!value.has(required)) errors += "$path/$required is required"
        }
        val properties = schema.optJSONObject("properties")
        properties?.keys()?.forEach { key ->
            if (value.has(key)) evaluate(properties.get(key), value.get(key).normalized(), context, "$path/$key", errors)
        }
        if (schema.has("additionalProperties")) {
            val known = properties?.keys()?.asSequence()?.toSet().orEmpty()
            keys.filterNot { it in known }.forEach { key ->
                when (val additional = schema.get("additionalProperties")) {
                    false -> errors += "$path/$key is an additional property"
                    is JSONObject, is Boolean -> evaluate(additional, value.get(key).normalized(), context, "$path/$key", errors)
                }
            }
        }
    }

    private fun branchMatches(
        schema: Any?,
        value: Any?,
        context: Context,
        path: String,
    ): Boolean = buildList { evaluate(schema, value, context, path, this) }.isEmpty()

    private fun evaluateBranches(
        branches: JSONArray?,
        value: Any?,
        context: Context,
        path: String,
        errors: MutableList<String>,
    ) {
        branches?.schemas()?.forEach { evaluate(it, value, context, path, errors) }
    }

    private fun matchesType(
        value: Any?,
        type: String,
    ): Boolean =
        when (type) {
            "null" -> value == null
            "array" -> value is JSONArray
            "object" -> value is JSONObject
            "integer" -> value is Number && runCatching { BigDecimal(value.toString()).toBigIntegerExact() }.isSuccess
            "number" -> value is Number && runCatching { BigDecimal(value.toString()) }.isSuccess
            "string" -> value is String
            "boolean" -> value is Boolean
            else -> error("Unsupported JSON Schema type $type")
        }

    private fun resolveReference(
        reference: String,
        context: Context,
    ): Context {
        val parts = reference.split('#', limit = 2)
        val documentPath =
            if (parts.first().isEmpty()) {
                context.path
            } else {
                normalizePath(context.path.substringBeforeLast('/', "") + "/" + parts.first())
            }
        val document = load(documentPath)
        var schema: Any = document
        if (parts.size == 2 && parts[1].isNotEmpty()) {
            require(parts[1].startsWith('/')) { "Unsupported schema fragment ${parts[1]}" }
            parts[1].removePrefix("/").split('/').forEach { rawToken ->
                val token = rawToken.replace("~1", "/").replace("~0", "~")
                schema = (schema as JSONObject).get(token)
            }
        }
        return Context(documentPath, document, schema)
    }

    private fun load(relativePath: String): JSONObject =
        documents.getOrPut(relativePath) {
            val resourcePath = "$contractRoot/$relativePath"
            JSONObject(
                checkNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) { "Missing schema $resourcePath" }
                    .use { String(it.readBytes(), StandardCharsets.UTF_8) },
            )
        }

    private fun assertSupportedSchema(
        schemaValue: Any?,
        path: String,
    ) {
        if (schemaValue is Boolean) return
        val schema = schemaValue as? JSONObject ?: error("Schema at $path is not an object or boolean")
        schema.keys().forEach { keyword -> require(keyword in SUPPORTED_KEYWORDS) { "Unsupported keyword $keyword at $path" } }
        listOf("properties", "\$defs").forEach { keyword ->
            schema.optJSONObject(keyword)?.keys()?.forEach { key ->
                assertSupportedSchema(schema.getJSONObject(keyword).get(key), "$path/$keyword/$key")
            }
        }
        listOf("allOf", "anyOf", "oneOf", "prefixItems").forEach { keyword ->
            schema.optJSONArray(keyword)?.schemas()?.forEachIndexed { index, child ->
                assertSupportedSchema(child, "$path/$keyword/$index")
            }
        }
        listOf("not", "if", "then", "else", "items", "additionalProperties").forEach { keyword ->
            if (schema.has(keyword)) assertSupportedSchema(schema.get(keyword), "$path/$keyword")
        }
    }

    private fun stableJson(value: Any?): String =
        when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            is String -> JSONObject.quote(value)
            is JSONArray ->
                (0 until value.length()).joinToString(prefix = "[", separator = ",", postfix = "]") {
                    stableJson(value.get(it).normalized())
                }
            is JSONObject ->
                value.keys().asSequence().sorted().joinToString(prefix = "{", separator = ",", postfix = "}") { key ->
                    JSONObject.quote(key) + ":" + stableJson(value.get(key).normalized())
                }
            else -> error("Unsupported JSON value")
        }

    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLast()
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private data class Context(
        val path: String,
        val document: JSONObject,
        val schema: Any = document,
    )

    private companion object {
        val SUPPORTED_KEYWORDS =
            setOf(
                "\$schema",
                "\$id",
                "\$ref",
                "\$defs",
                "title",
                "description",
                "type",
                "const",
                "enum",
                "allOf",
                "anyOf",
                "oneOf",
                "not",
                "if",
                "then",
                "else",
                "required",
                "properties",
                "additionalProperties",
                "minimum",
                "maximum",
                "minLength",
                "maxLength",
                "pattern",
                "format",
                "minItems",
                "maxItems",
                "maxProperties",
                "uniqueItems",
                "prefixItems",
                "items",
            )
        val RFC_3339 =
            Regex(
                "^(\\d{4})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?" +
                    "([Zz]|([+-])(\\d{2}):(\\d{2}))$",
            )
        const val SECONDS_PER_DAY = 86_400L
    }
}

private fun Any?.normalized(): Any? = if (this == JSONObject.NULL) null else this

private fun JSONArray.strings(): List<String> = List(length()) { index -> getString(index) }

private fun JSONArray.schemas(): List<Any> = List(length()) { index -> get(index) }
