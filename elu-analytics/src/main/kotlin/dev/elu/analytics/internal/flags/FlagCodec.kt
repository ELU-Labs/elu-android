package dev.elu.analytics.internal.flags

import dev.elu.analytics.internal.config.V1MalformedConfigException
import dev.elu.analytics.internal.runtime.RUNTIME_CONTRACT_VERSION
import dev.elu.analytics.internal.runtime.RuntimePlatform
import dev.elu.analytics.internal.runtime.RuntimeVersionComponent
import dev.elu.analytics.internal.runtime.RuntimeVersions
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections

internal object FlagCodec {
    private const val CACHE_MAX_NODES = FLAG_MAX_NODES * 3

    private val responseFields =
        setOf(
            "schemaVersion",
            "requestId",
            "contextRevision",
            "identityRevision",
            "flagsRevision",
            "evaluatedAt",
            "expiresAt",
            "flags",
            "payloads",
        )

    fun encodeRequest(
        requestId: String,
        witness: FlagEvaluationWitness,
    ): FlagRequest {
        requireString(requestId, "requestId", 1, 256)
        requireSafeRevision(witness.identityRevision, "identity.revision")
        requireSafeRevision(witness.contextRevision, "contextRevision")
        requireString(witness.anonymousId, "identity.anonymousId", 1, 256)
        witness.userId?.let { requireString(it, "identity.userId", 1, 512) }
        validateGroups(witness.groups)
        validateGroupProperties(witness.groups, witness.groupProperties)
        validateVersions(witness.versions)
        val root =
            obj(
                "schemaVersion" to number(FLAG_CONTRACT_SCHEMA_VERSION.toLong()),
                "requestId" to string(requestId),
                "contextRevision" to number(witness.contextRevision),
                "identity" to
                    obj(
                        "anonymousId" to string(witness.anonymousId),
                        "userId" to (witness.userId?.let(::string) ?: FlagJsonValue.NullValue),
                        "revision" to number(witness.identityRevision),
                    ),
                "personProperties" to witness.personProperties.deepCopy(),
                "groups" to witness.groups.deepCopy(),
                "groupProperties" to witness.groupProperties.deepCopy(),
                "versions" to encodeVersions(witness.versions),
            )
        val bytes = FlagJson.canonicalBytes(root)
        if (bytes.size > FLAG_MAX_WIRE_BYTES) protocol("Flag request exceeds $FLAG_MAX_WIRE_BYTES bytes")
        // Reparse so programmatic construction and persisted/wire decoding share identical limits.
        FlagJson.parse(bytes)
        return FlagRequest(requestId, witness.deepCopy(), bytes)
    }

    fun decodeResponse(bytes: ByteArray): FlagResponse {
        val root = FlagJson.requiredObject(FlagJson.parse(bytes), "response")
        FlagJson.requireFields(root, responseFields, emptySet(), "response")
        if (FlagJson.exactLong(root.member("schemaVersion"), "response.schemaVersion") != 1L) {
            protocol("Flag response schema version is unsupported")
        }
        val requestId = FlagJson.requiredString(root.member("requestId"), "response.requestId", 1, 256)
        val contextRevision = safeRevision(root.member("contextRevision"), "response.contextRevision")
        val identityRevision = safeRevision(root.member("identityRevision"), "response.identityRevision")
        val flagsRevision = FlagJson.requiredString(root.member("flagsRevision"), "response.flagsRevision", 1, 128)
        val evaluatedAt = parseInstant(root.member("evaluatedAt"), "response.evaluatedAt")
        val expiresAt = parseInstant(root.member("expiresAt"), "response.expiresAt")
        if (evaluatedAt >= expiresAt) protocol("Flag response evaluatedAt must be before expiresAt")
        val flags = FlagJson.requiredObject(root.member("flags"), "response.flags")
        val payloads = FlagJson.requiredObject(root.member("payloads"), "response.payloads")
        if (flags.members.size > FLAG_MAX_COLLECTION_ENTRIES) protocol("response.flags exceeds the entry limit")
        if (payloads.members.size > FLAG_MAX_COLLECTION_ENTRIES) protocol("response.payloads exceeds the entry limit")
        flags.members.forEach { member ->
            requireString(member.key, "response.flags key", 1, FLAG_MAX_KEY_SCALARS)
            when (member.value) {
                is FlagJsonValue.BooleanValue,
                is FlagJsonValue.StringValue,
                is FlagJsonValue.NumberValue,
                FlagJsonValue.NullValue,
                -> Unit
                else -> protocol("response.flags.${member.key} must be a scalar flag value")
            }
        }
        payloads.members.forEach { member -> requireString(member.key, "response.payloads key", 1, FLAG_MAX_KEY_SCALARS) }
        return FlagResponse(
            requestId,
            contextRevision,
            identityRevision,
            flagsRevision,
            evaluatedAt,
            expiresAt,
            flags.immutableCopy(),
            payloads.immutableCopy(),
        )
    }

    fun encodeCache(envelope: FlagCacheEnvelope): ByteArray {
        val root =
            obj(
                "schemaVersion" to number(FLAG_STORAGE_SCHEMA_VERSION.toLong()),
                "authorization" to encodeAuthorization(envelope.authorization),
                "witness" to encodeWitness(envelope.witness),
                "response" to encodeResponse(envelope.response),
            )
        val bytes = FlagJson.canonicalBytes(root)
        if (bytes.size > FLAG_MAX_CACHE_BYTES) protocol("Flag cache exceeds $FLAG_MAX_CACHE_BYTES bytes")
        FlagJson.parse(bytes, FLAG_MAX_CACHE_BYTES, CACHE_MAX_NODES)
        return bytes
    }

    fun decodeCache(bytes: ByteArray): FlagCacheEnvelope {
        val root = FlagJson.requiredObject(FlagJson.parse(bytes, FLAG_MAX_CACHE_BYTES, CACHE_MAX_NODES), "cache")
        FlagJson.requireFields(root, setOf("schemaVersion", "authorization", "witness", "response"), emptySet(), "cache")
        if (FlagJson.exactLong(root.member("schemaVersion"), "cache.schemaVersion") != 1L) {
            protocol("Flag cache schema version is unsupported")
        }
        return FlagCacheEnvelope(
            authorization = decodeAuthorization(FlagJson.requiredObject(root.member("authorization"), "cache.authorization")),
            witness = decodeWitness(FlagJson.requiredObject(root.member("witness"), "cache.witness")),
            response = decodeResponse(FlagJson.canonicalBytes(checkNotNull(root.member("response")))),
        )
    }

    fun witnessHash(witness: FlagEvaluationWitness): String = FlagJson.sha256(encodeWitness(witness))

    internal fun encodeResponse(response: FlagResponse): FlagJsonValue.ObjectValue =
        obj(
            "schemaVersion" to number(1),
            "requestId" to string(response.requestId),
            "contextRevision" to number(response.contextRevision),
            "identityRevision" to number(response.identityRevision),
            "flagsRevision" to string(response.flagsRevision),
            "evaluatedAt" to string(response.evaluatedAt.source),
            "expiresAt" to string(response.expiresAt.source),
            "flags" to response.flags.deepCopy(),
            "payloads" to response.payloads.deepCopy(),
        )

    internal fun encodeAuthorization(witness: FlagAuthorizationWitness): FlagJsonValue.ObjectValue =
        obj(
            "trustedSiteKey" to string(witness.trustedSiteKey),
            "siteNamespaceDigest" to string(witness.siteNamespaceDigest),
            "siteId" to string(witness.siteId),
            "endpoint" to string(witness.endpoint.toASCIIString()),
            "configRevision" to string(witness.configRevision),
            "configIssuedAt" to encodeInstant(witness.configIssuedAt),
            "configSemanticHash" to string(witness.configSemanticHash),
            "configExpiresAt" to encodeInstant(witness.configExpiresAt),
        )

    internal fun decodeAuthorization(root: FlagJsonValue.ObjectValue): FlagAuthorizationWitness {
        FlagJson.requireFields(
            root,
            setOf(
                "trustedSiteKey",
                "siteNamespaceDigest",
                "siteId",
                "endpoint",
                "configRevision",
                "configIssuedAt",
                "configSemanticHash",
                "configExpiresAt",
            ),
            emptySet(),
            "cache.authorization",
        )
        val endpointSource = FlagJson.requiredString(root.member("endpoint"), "cache.authorization.endpoint", 1, 2_048)
        val endpoint =
            try {
                URI(endpointSource)
            } catch (error: URISyntaxException) {
                protocol("cache.authorization.endpoint is malformed", error)
            }
        if (!endpoint.isAbsolute || endpoint.scheme != "https") protocol("cache.authorization.endpoint is unauthorized")
        val namespaceDigest =
            FlagJson.requiredString(root.member("siteNamespaceDigest"), "cache.authorization.siteNamespaceDigest", 64, 64)
        if (!HEX_SHA256.matches(namespaceDigest)) protocol("cache.authorization.siteNamespaceDigest is malformed")
        val semanticHash =
            FlagJson.requiredString(root.member("configSemanticHash"), "cache.authorization.configSemanticHash", 71, 71)
        if (!PREFIXED_SHA256.matches(semanticHash)) protocol("cache.authorization.configSemanticHash is malformed")
        return FlagAuthorizationWitness(
            trustedSiteKey = FlagJson.requiredString(root.member("trustedSiteKey"), "cache.authorization.trustedSiteKey", 1, 1_024),
            siteNamespaceDigest = namespaceDigest,
            siteId = FlagJson.requiredString(root.member("siteId"), "cache.authorization.siteId", 1, 128),
            endpoint = endpoint,
            configRevision = FlagJson.requiredString(root.member("configRevision"), "cache.authorization.configRevision", 1, 128),
            configIssuedAt = decodeInstant(FlagJson.requiredObject(root.member("configIssuedAt"), "cache.authorization.configIssuedAt")),
            configSemanticHash = semanticHash,
            configExpiresAt = decodeInstant(FlagJson.requiredObject(root.member("configExpiresAt"), "cache.authorization.configExpiresAt")),
        )
    }

    internal fun encodeWitness(witness: FlagEvaluationWitness): FlagJsonValue.ObjectValue =
        obj(
            "anonymousId" to string(witness.anonymousId),
            "userId" to (witness.userId?.let(::string) ?: FlagJsonValue.NullValue),
            "identityRevision" to number(witness.identityRevision),
            "contextRevision" to number(witness.contextRevision),
            "optedOut" to FlagJsonValue.BooleanValue(witness.optedOut),
            "personProperties" to witness.personProperties.deepCopy(),
            "groups" to witness.groups.deepCopy(),
            "groupProperties" to witness.groupProperties.deepCopy(),
            "versions" to encodeVersions(witness.versions),
        )

    internal fun decodeWitness(root: FlagJsonValue.ObjectValue): FlagEvaluationWitness {
        FlagJson.requireFields(
            root,
            setOf(
                "anonymousId",
                "userId",
                "identityRevision",
                "contextRevision",
                "optedOut",
                "personProperties",
                "groups",
                "groupProperties",
                "versions",
            ),
            emptySet(),
            "cache.witness",
        )
        val userValue = root.member("userId")
        val userId =
            when (userValue) {
                FlagJsonValue.NullValue -> null
                else -> FlagJson.requiredString(userValue, "cache.witness.userId", 1, 512)
            }
        val groups = FlagJson.requiredObject(root.member("groups"), "cache.witness.groups")
        val groupProperties = FlagJson.requiredObject(root.member("groupProperties"), "cache.witness.groupProperties")
        validateGroups(groups)
        validateGroupProperties(groups, groupProperties)
        return FlagEvaluationWitness(
            anonymousId = FlagJson.requiredString(root.member("anonymousId"), "cache.witness.anonymousId", 1, 256),
            userId = userId,
            identityRevision = safeRevision(root.member("identityRevision"), "cache.witness.identityRevision"),
            contextRevision = safeRevision(root.member("contextRevision"), "cache.witness.contextRevision"),
            optedOut = FlagJson.requiredBoolean(root.member("optedOut"), "cache.witness.optedOut"),
            personProperties = FlagJson.requiredObject(root.member("personProperties"), "cache.witness.personProperties").immutableCopy(),
            groups = groups.immutableCopy(),
            groupProperties = groupProperties.immutableCopy(),
            versions = decodeVersions(FlagJson.requiredObject(root.member("versions"), "cache.witness.versions")),
        )
    }

    private fun encodeInstant(instant: FlagExactInstant): FlagJsonValue.ObjectValue =
        obj(
            "source" to string(instant.source),
            "epochWholeSecond" to number(instant.epochWholeSecond),
            "fractionalDigits" to string(instant.fractionalDigits),
            "leapSecond" to FlagJsonValue.BooleanValue(instant.isLeapSecond),
        )

    private fun decodeInstant(root: FlagJsonValue.ObjectValue): FlagExactInstant {
        FlagJson.requireFields(root, setOf("source", "epochWholeSecond", "fractionalDigits", "leapSecond"), emptySet(), "cache.instant")
        val source = FlagJson.requiredString(root.member("source"), "cache.instant.source", 1, 128)
        val parsed = parseInstant(FlagJsonValue.StringValue(source), "cache.instant.source")
        val stored =
            FlagExactInstant(
                source,
                FlagJson.exactLong(root.member("epochWholeSecond"), "cache.instant.epochWholeSecond"),
                FlagJson.requiredString(root.member("fractionalDigits"), "cache.instant.fractionalDigits", 0, 128),
                FlagJson.requiredBoolean(root.member("leapSecond"), "cache.instant.leapSecond"),
            )
        if (parsed != stored) protocol("Persisted flag instant fields disagree with their source")
        return stored
    }

    private fun parseInstant(value: FlagJsonValue?, path: String): FlagExactInstant {
        val source = FlagJson.requiredString(value, path, 1, 128)
        return try {
            FlagExactInstant.parse(source)
        } catch (error: V1MalformedConfigException) {
            protocol("$path must be an exact RFC 3339 timestamp", error)
        }
    }

    private fun encodeVersions(versions: RuntimeVersions): FlagJsonValue.ObjectValue {
        val members =
            mutableListOf<Pair<String, FlagJsonValue>>(
                "schemaVersion" to number(versions.schemaVersion.toLong()),
                "contractVersion" to string(versions.contractVersion),
                "platform" to string(versions.platform.wireValue),
                "runtime" to encodeVersionComponent(versions.runtime),
                "facade" to encodeVersionComponent(versions.facade),
            )
        versions.build?.let { members += "build" to string(it) }
        return obj(*members.toTypedArray())
    }

    private fun decodeVersions(root: FlagJsonValue.ObjectValue): RuntimeVersions {
        FlagJson.requireFields(root, setOf("schemaVersion", "contractVersion", "platform", "runtime", "facade"), setOf("build"), "versions")
        if (FlagJson.exactLong(root.member("schemaVersion"), "versions.schemaVersion") != 1L) protocol("versions schema is unsupported")
        val contract = FlagJson.requiredString(root.member("contractVersion"), "versions.contractVersion", 1, 64)
        if (contract != RUNTIME_CONTRACT_VERSION) protocol("versions.contractVersion is unsupported")
        val platformSource = FlagJson.requiredString(root.member("platform"), "versions.platform", 1, 32)
        val platform = RuntimePlatform.values().firstOrNull { it.wireValue == platformSource }
            ?: protocol("versions.platform is unsupported")
        val build = root.member("build")?.let { FlagJson.requiredString(it, "versions.build", 1, 128) }
        return RuntimeVersions(
            contractVersion = contract,
            platform = platform,
            runtime = decodeVersionComponent(FlagJson.requiredObject(root.member("runtime"), "versions.runtime"), runtime = true),
            facade = decodeVersionComponent(FlagJson.requiredObject(root.member("facade"), "versions.facade"), runtime = false),
            build = build,
        )
    }

    private fun encodeVersionComponent(component: RuntimeVersionComponent): FlagJsonValue.ObjectValue =
        obj("name" to string(component.name), "version" to string(component.version))

    private fun decodeVersionComponent(
        root: FlagJsonValue.ObjectValue,
        runtime: Boolean,
    ): RuntimeVersionComponent {
        val path = if (runtime) "versions.runtime" else "versions.facade"
        FlagJson.requireFields(root, setOf("name", "version"), emptySet(), path)
        val name = FlagJson.requiredString(root.member("name"), "$path.name", 1, 128)
        val pattern = if (runtime) RUNTIME_NAME else FACADE_NAME
        if (!pattern.matches(name)) protocol("$path.name is malformed")
        return RuntimeVersionComponent(name, FlagJson.requiredString(root.member("version"), "$path.version", 1, 64))
    }

    private fun validateVersions(versions: RuntimeVersions) {
        if (versions.schemaVersion != 1 || versions.contractVersion != RUNTIME_CONTRACT_VERSION) {
            protocol("Flag versions are outside contract v1")
        }
        if (!RUNTIME_NAME.matches(versions.runtime.name)) protocol("versions.runtime.name is malformed")
        if (!FACADE_NAME.matches(versions.facade.name)) protocol("versions.facade.name is malformed")
        requireString(versions.runtime.version, "versions.runtime.version", 1, 64)
        requireString(versions.facade.version, "versions.facade.version", 1, 64)
        versions.build?.let { requireString(it, "versions.build", 1, 128) }
    }

    private fun validateGroups(groups: FlagJsonValue.ObjectValue) {
        if (groups.members.size > 64) protocol("groups exceeds 64 entries")
        groups.members.forEach { member ->
            requireString(member.key, "groups key", 1, FLAG_MAX_KEY_SCALARS)
            val value = member.value as? FlagJsonValue.StringValue ?: protocol("groups.${member.key} must be a string")
            requireString(value.value, "groups.${member.key}", 1, 512)
        }
    }

    private fun validateGroupProperties(
        groups: FlagJsonValue.ObjectValue,
        groupProperties: FlagJsonValue.ObjectValue,
    ) {
        if (groupProperties.members.size > 64) protocol("groupProperties exceeds 64 entries")
        groupProperties.members.forEach { member ->
            if (!groups.contains(member.key)) protocol("groupProperties contains an orphan group type")
            if (member.value !is FlagJsonValue.ObjectValue) protocol("groupProperties.${member.key} must be an object")
        }
    }

    private fun FlagEvaluationWitness.deepCopy(): FlagEvaluationWitness =
        copy(
            personProperties = personProperties.immutableCopy(),
            groups = groups.immutableCopy(),
            groupProperties = groupProperties.immutableCopy(),
            versions = versions.copy(runtime = versions.runtime.copy(), facade = versions.facade.copy()),
        )

    private fun safeRevision(value: FlagJsonValue?, path: String): Long =
        FlagJson.exactLong(value, path).also { requireSafeRevision(it, path) }

    private fun requireSafeRevision(value: Long, path: String) {
        if (value !in 0..FLAG_MAX_SAFE_INTEGER) protocol("$path is outside the safe-integer domain")
    }

    private fun requireString(value: String, path: String, minimum: Int, maximum: Int) {
        val parsed = FlagJson.fromPlatform(value) as FlagJsonValue.StringValue
        val scalars = value.codePointCount(0, value.length)
        if (scalars !in minimum..maximum) protocol("$path is outside its string-length bounds")
        check(parsed.value == value)
    }

    private fun obj(vararg members: Pair<String, FlagJsonValue>): FlagJsonValue.ObjectValue =
        FlagJsonValue.ObjectValue(
            Collections.unmodifiableList(members.map { (key, value) -> FlagJsonValue.ObjectValue.Member(key, value) }),
        )

    private fun string(value: String): FlagJsonValue.StringValue = FlagJsonValue.StringValue(value)

    private fun number(value: Long): FlagJsonValue.NumberValue {
        if (value !in -FLAG_MAX_SAFE_INTEGER..FLAG_MAX_SAFE_INTEGER) protocol("Flag integer exceeds safe range")
        return FlagJsonValue.NumberValue(value.toString(), value.toDouble())
    }

    private val RUNTIME_NAME = Regex("^elu-[a-z0-9-]+$")
    private val FACADE_NAME = Regex("^[A-Za-z][A-Za-z0-9._-]+$")
    private val HEX_SHA256 = Regex("^[0-9a-f]{64}$")
    private val PREFIXED_SHA256 = Regex("^sha256:[0-9a-f]{64}$")

    private fun protocol(message: String, cause: Throwable? = null): Nothing =
        throw FlagProtocolException(message, cause)
}
