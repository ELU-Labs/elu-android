package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.SessionState
import dev.elu.analytics.internal.core.StreamState
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Executes the shared behavior vector; it is not a descriptive coverage inventory. */
class CaptureAdmissionVectorRuntimeTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val ownerCounter = AtomicInteger()

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        owners.asReversed().forEach { owner -> runCatching { owner.closeAsync().await() } }
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test
    fun `all Android-applicable shared scenarios execute and only the browser barrier is excluded`() {
        val vector = JSONObject(resourceText(VECTOR_RESOURCE))
        val scenarios = vector.getJSONArray("scenarios")
        val executed = linkedSetOf<String>()
        val excluded = linkedSetOf<String>()
        repeat(scenarios.length()) { index ->
            val scenario = scenarios.getJSONObject(index)
            val id = scenario.getString("id")
            if (id == BROWSER_ONLY_SCENARIO) {
                val steps = scenario.getJSONArray("steps")
                repeat(steps.length()) { stepIndex -> assertTrue(steps.getJSONObject(stepIndex).has("realm")) }
                excluded += id
            } else {
                ScenarioRuntime(vector, scenario).execute()
                executed += id
            }
        }

        assertEquals(setOf(BROWSER_ONLY_SCENARIO), excluded)
        assertEquals(scenarios.length() - 1, executed.size)
        assertFalse(BROWSER_ONLY_SCENARIO in executed)
    }

    private inner class ScenarioRuntime(
        private val vector: JSONObject,
        private val scenario: JSONObject,
    ) {
        private val id = scenario.getString("id")
        private val backing = FakeRuntimeQueueBacking()
        private val clock = MutableVectorClock()
        private val identifiers = VectorIdentifiers()
        private val ownershipKey = "capture-vector-${ownerCounter.incrementAndGet()}-$id"
        private val initialGeneration =
            vector.getJSONObject("initialStates").getJSONObject(scenario.getString("initialState")).getLong("generation")
        private var owner =
            openOwner(initialState()).also {
                // Fresh-state insertion establishes generation zero; it is not a user mutation.
                backing.committedMutationGeneration = initialGeneration
            }
        private var injectedOutcome: String? = null
        private var authorityBeforeAcknowledgement: RuntimeCaptureAuthorityState? = null

        fun execute() {
            val steps = scenario.getJSONArray("steps")
            repeat(steps.length()) { index -> executeStep(steps.getJSONObject(index)) }
        }

        private fun executeStep(step: JSONObject) {
            clock.wallEpochMillis = Instant.parse(step.getString("wallNow")).toEpochMilli()
            clock.monotonicNanos = step.getString("monotonicNanos").toLong()
            when (val type = step.getString("type")) {
                "submitCandidate" -> submitCandidate(step)
                "registerSuperProperties" -> registerSuperProperties(step)
                "unregisterSuperProperty" -> unregisterSuperProperty(step)
                "capture", "screen" -> capture(step, type)
                "setOptedOut" -> setOptedOut(step)
                "background" -> background(step)
                "reopen" -> reopen(step)
                "acknowledgePrefix" -> acknowledgePrefix(step)
                "injectStorageOutcome" -> injectStorageOutcome(step)
                else -> error("Android vector runner does not implement $id/$type")
            }
        }

        private fun submitCandidate(step: JSONObject) {
            val result =
                owner.submitCaptureAuthority(
                    resolveVariant(step.getString("config")),
                    if (step.isNull("privacy")) null else resolveVariant(step.getString("privacy")),
                ).await()
            val expected = step.getJSONObject("expect")
            when (expected.getString("authority")) {
                "authorized" -> {
                    val activated = result as RuntimeCaptureAuthorityUpdateResult.Activated
                    expected.optString("configSemanticHash").takeIf(String::isNotEmpty)?.let {
                        assertEquals(label("config hash"), it, activated.authority.configSemanticHash)
                    }
                    expected.optString("configSiteId").takeIf(String::isNotEmpty)?.let {
                        assertEquals(label("config site"), it, activated.authority.configSiteId)
                    }
                    expected.optString("namespaceDigest").takeIf(String::isNotEmpty)?.let {
                        assertEquals(label("namespace"), it, activated.authority.ownerNamespaceHash)
                    }
                    expected.optString("streamId").takeIf(String::isNotEmpty)?.let {
                        assertEquals(label("stream"), it, activated.authority.streamId)
                    }
                    if (expected.has("contextRevision")) {
                        assertEquals(expected.getLong("contextRevision"), activated.authority.contextRevision)
                    }
                    if (expected.has("monotonicBudgetNoGreaterThanNanos")) {
                        assertTrue(
                            label("monotonic budget"),
                            activated.authority.monotonicBudget <=
                                expected.getString("monotonicBudgetNoGreaterThanNanos").toLong(),
                        )
                    }
                }
                "terminal" -> {
                    val terminated = result as RuntimeCaptureAuthorityUpdateResult.Terminated
                    assertEquals(
                        label("terminal reason"),
                        terminalReason(expected.getString("reason")),
                        terminated.authority.reason,
                    )
                }
                else -> error("Unsupported authority expectation in $id")
            }
        }

        private fun registerSuperProperties(step: JSONObject) {
            val before = owner.snapshot().await()
            val result =
                owner.registerSuperProperties(
                    jsonObjectToMap(step.getJSONObject("properties")),
                    step.getString("wallNow"),
                ).await() as RuntimeAppendResult.Accepted
            assertEquals(step.getJSONObject("expect").getLong("contextRevision"), result.snapshot.state.identity.contextRevision)
            assertRecordsAdded(before, result.snapshot, step.getJSONObject("expect"))
        }

        private fun unregisterSuperProperty(step: JSONObject) {
            val before = owner.snapshot().await()
            val result =
                owner.unregisterSuperProperties(listOf(step.getString("key")), step.getString("wallNow")).await()
                    as RuntimeAppendResult.Accepted
            assertEquals(step.getJSONObject("expect").getLong("contextRevision"), result.snapshot.state.identity.contextRevision)
            assertRecordsAdded(before, result.snapshot, step.getJSONObject("expect"))
        }

        private fun capture(step: JSONObject, type: String) {
            step.optJSONObject("authorityOverrideForTest")?.let { override ->
                val current = owner.captureAuthorityForTesting().await() as RuntimeCaptureAuthorityState.Authorized
                owner.replaceCaptureAuthorityForTesting(
                    current.copy(ownerNamespaceHash = override.getString("namespaceDigest")),
                ).await()
            }
            val before = owner.snapshot().await()
            val authorityBefore = owner.captureAuthorityForTesting().await()
            val attemptsBefore = backing.mutatedTransactionAttempts
            val command =
                RuntimeCaptureCommand(
                    kind = if (type == "screen") RuntimeEventKind.SCREEN else RuntimeEventKind.CAPTURE,
                    name = step.getString("name"),
                    occurredAt = step.getString("wallNow"),
                    properties = jsonObjectToMap(step.getJSONObject("properties")),
                    versions = versions(),
                )
            val expected = step.getJSONObject("expect")
            if (injectedOutcome == "unknown") {
                val failure = assertThrows(label("unknown storage"), ExecutionException::class.java) {
                    owner.capture(command).await()
                }
                assertTrue(failure.cause is AmbiguousRuntimeCommitException)
                assertEquals(1, backing.mutatedTransactionAttempts - attemptsBefore)
                assertEquals(before.queuedCount, backing.records.size)
                val later = owner.capture(command.copy(name = command.name + "_later")).await()
                    as RuntimeCaptureResult.Rejected
                assertEquals(RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED, later.reason)
                injectedOutcome = null
                return
            }

            when (val result = owner.capture(command).await()) {
                is RuntimeCaptureResult.Accepted -> {
                    assertTrue(label("accepted"), expected.getBoolean("accepted"))
                    assertRecordsAdded(before, result.snapshot, expected)
                    expected.optString("kind").takeIf(String::isNotEmpty)?.let { kind ->
                        assertEquals(RuntimeEventKind.fromWireValue(kind), result.record.record.kind)
                    }
                    expected.optJSONObject("properties")?.let { properties ->
                        assertEquals(jsonObjectToMap(properties), result.record.record.properties)
                    }
                    if (expected.optBoolean("reservedVersionPropertiesAuthoritative")) {
                        assertEquals(versions(), result.record.record.versions)
                    }
                    if (expected.optBoolean("sessionCreated")) {
                        assertNull(before.state.identity.session)
                        assertEquals(result.record.record.sessionId, result.snapshot.state.identity.session?.id)
                    }
                    if (expected.optBoolean("sessionRotated")) {
                        assertNotEquals(before.state.identity.session?.id, result.record.record.sessionId)
                    }
                    expected.optString("lifecycle").takeIf(String::isNotEmpty)?.let { lifecycle ->
                        assertEquals(lifecycle, result.snapshot.state.identity.session?.lifecycle?.wireValue)
                    }
                    if (expected.optBoolean("atomicStateAndRecord")) {
                        val persisted = checkNotNull(backing.core)
                        val state = CoreStateCodec.decode(persisted.stateJson)
                        assertEquals(result.record.record.sessionId, state.identity.session?.id)
                        assertEquals(1L, persisted.queueCount)
                        assertTrue(backing.records.containsKey(result.record.sequence))
                    }
                    if (expected.has("generation")) {
                        assertEquals(
                            label("storage generation"),
                            expected.getLong("generation"),
                            backing.committedMutationGeneration,
                        )
                    }
                    if (injectedOutcome == "proven-not-committed-once") {
                        assertEquals(expected.getInt("attempts"), backing.mutatedTransactionAttempts - attemptsBefore)
                        injectedOutcome = null
                    }
                    if (id == "generation-is-not-authority" && step.getString("name") == "first") {
                        authorityBeforeAcknowledgement = owner.captureAuthorityForTesting().await()
                    }
                    if (expected.optBoolean("authorityUnchanged")) {
                        assertEquals(authorityBeforeAcknowledgement, authorityBefore)
                        assertEquals(authorityBeforeAcknowledgement, owner.captureAuthorityForTesting().await())
                    }
                }
                is RuntimeCaptureResult.Rejected -> {
                    assertFalse(label("rejected"), expected.getBoolean("accepted"))
                    expected.optString("reason").takeIf(String::isNotEmpty)?.let { reason ->
                        assertEquals(captureRejection(reason), result.reason)
                    }
                    assertRecordsAdded(before, result.snapshot, expected)
                }
            }
        }

        private fun setOptedOut(step: JSONObject) {
            val result =
                owner.applyLocal(
                    RuntimeLocalStateChange.SetOptedOut(step.getBoolean("value"), step.getString("wallNow")),
                ).await() as RuntimeAppendResult.Accepted
            val expected = step.getJSONObject("expect")
            assertEquals(expected.getLong("contextRevision"), result.snapshot.state.identity.contextRevision)
            assertTrue(result.snapshot.state.identity.session == null)
        }

        private fun background(step: JSONObject) {
            val before = owner.snapshot().await()
            val result = owner.markBackgrounded(step.getString("wallNow")).await() as RuntimeAppendResult.Accepted
            val expected = step.getJSONObject("expect")
            expected.optString("lifecycle").takeIf(String::isNotEmpty)?.let { lifecycle ->
                assertEquals(lifecycle, result.snapshot.state.identity.session?.lifecycle?.wireValue)
            }
            assertRecordsAdded(before, result.snapshot, expected)
            if (expected.optBoolean("idempotent")) assertEquals(before.state, result.snapshot.state)
        }

        private fun reopen(step: JSONObject) {
            owner.closeAsync().await()
            owners.remove(owner)
            owner = openOwner { error("Vector reopen must not consult legacy state") }
            val expected = step.getJSONObject("expect")
            assertEquals(expected.getString("namespaceDigest"), RuntimeSiteNamespace.digest(OWNER_SITE_KEY))
            assertTrue(owner.captureAuthorityForTesting().await() === RuntimeCaptureAuthorityState.Absent)
            assertNull(owner.pinnedConfigSiteForTesting().await())
        }

        private fun acknowledgePrefix(step: JSONObject) {
            val count = step.getInt("count")
            val queued = owner.peek(count, Long.MAX_VALUE).await()
            val references = queued.map { row -> RuntimeRecordReference(row.sequence, row.kind, row.recordId) }
            val authority = owner.captureAuthorityForTesting().await()
            owner.acknowledge(RuntimeAcknowledgement(STREAM_ID, references)).await()
            val expected = step.getJSONObject("expect")
            if (expected.has("generation")) {
                assertEquals(
                    label("storage generation"),
                    expected.getLong("generation"),
                    backing.committedMutationGeneration,
                )
            }
            assertEquals(authority, owner.captureAuthorityForTesting().await())
            assertEquals(authorityBeforeAcknowledgement, authority)
        }

        private fun injectStorageOutcome(step: JSONObject) {
            injectedOutcome = step.getString("outcome")
            when (injectedOutcome) {
                "proven-not-committed-once" ->
                    backing.failNextKnownCommit =
                        ProvenNotCommittedRuntimeTransactionException("vector injected rollback")
                "unknown" -> backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE
                else -> error("Unsupported injected vector outcome: $injectedOutcome")
            }
        }

        private fun openOwner(stateLoader: () -> PersistedCoreState): RuntimeQueueOwner {
            val opened =
                RuntimeQueueOwner.open(
                    ownershipKey = ownershipKey,
                    limits = RuntimeQueueLimits(10_000, 16_777_216),
                    databaseFactory = backing::connection,
                    legacyStateLoader = stateLoader,
                    identifiers = identifiers,
                    trustedSiteKey = OWNER_SITE_KEY,
                    captureClock = clock,
                ).await()
            owners += opened
            return opened
        }

        private fun openOwner(state: PersistedCoreState): RuntimeQueueOwner = openOwner { state }

        private fun initialState(): PersistedCoreState {
            val json = vector.getJSONObject("initialStates").getJSONObject(scenario.getString("initialState"))
            val sessionJson = json.optJSONObject("session")
            val session =
                sessionJson?.let {
                    SessionState(
                        id = it.getString("id"),
                        startedAt = it.getString("startedAt"),
                        lastActivityAt = it.getString("lastActivityAt"),
                        timeoutSeconds = it.getInt("timeoutSeconds"),
                        maximumDurationSeconds = it.getInt("maximumDurationSeconds"),
                        lifecycle = SessionLifecycle.valueOf(it.getString("lifecycle").uppercase()),
                        backgroundedAt = if (it.isNull("backgroundedAt")) null else it.getString("backgroundedAt"),
                    )
                }
            return PersistedCoreState(
                identity =
                    IdentityState(
                        revision = json.getLong("identityRevision"),
                        contextRevision = json.getLong("contextRevision"),
                        anonymousId = "anon_vector",
                        userId = null,
                        groups = emptyMap(),
                        superProperties = jsonObjectToMap(json.getJSONObject("superProperties")),
                        session = session,
                        optedOut = json.getBoolean("identityOptedOut"),
                        updatedAt = session?.lastActivityAt ?: CONFIG_ISSUED_AT,
                    ),
                stream = StreamState(streamId = json.getString("streamId"), nextSequence = json.getLong("nextSequence")),
                flagContext =
                    FlagContextState(
                        personProperties = emptyMap(),
                        groupProperties = emptyMap(),
                    ),
            )
        }

        private fun resolveVariant(name: String): String {
            val variants = vector.getJSONObject("documentVariants")
            val variant = variants.getJSONObject(name)
            val baseId = variant.getString("baseFixtureId")
            var body =
                if (vector.getJSONObject("fixtureCatalog").has(baseId)) {
                    val fixture = vector.getJSONObject("fixtureCatalog").getJSONObject(baseId)
                    resolveFixture(fixture.getString("kind"), fixture.getString("fixtureId"))
                } else {
                    resolveVariant(baseId)
                }
            when (variant.optString("serialization")) {
                "reverse-object-member-order-and-add-json-whitespace" -> body = " \n" + reverseJson(JSONObject(body)) + "\n "
                "append-escaped-equivalent-contextRevision-member" -> {
                    val context = JSONObject(body).getLong("contextRevision")
                    return body.trim().dropLast(1) + ",\"\\u0063ontextRevision\":$context}"
                }
            }
            val json = JSONObject(body)
            val replacements = variant.optJSONArray("orderedReplacements") ?: JSONArray()
            repeat(replacements.length()) { index ->
                val replacement = replacements.getJSONObject(index)
                replacePointer(json, replacement.getString("pointer"), replacement.get("value"))
            }
            if (variant.optString("effectivePolicyHash") == "recompute-after-replacements") {
                val parsed = V1StrictCanonicalJson.parse(json.toString()) as V1StrictCanonicalJson.Value.ObjectValue
                val withoutHash =
                    V1StrictCanonicalJson.Value.ObjectValue(
                        parsed.members.filterNot { member -> member.first == "effectivePolicyHash" },
                    )
                json.put("effectivePolicyHash", V1StrictCanonicalJson.sha256(withoutHash))
            }
            return json.toString()
        }

        private fun assertRecordsAdded(before: RuntimeQueueSnapshot, after: RuntimeQueueSnapshot, expected: JSONObject) {
            if (expected.has("recordsAdded")) {
                assertEquals(label("records added"), expected.getInt("recordsAdded"), after.queuedCount - before.queuedCount)
            }
        }

        private fun label(assertion: String): String = "$id: $assertion"
    }

    private class MutableVectorClock : RuntimeCaptureClock {
        var wallEpochMillis: Long = 0L
        var monotonicNanos: Long = 0L

        override fun wallNowEpochMillis(): Long = wallEpochMillis

        override fun elapsedRealtimeNanos(): Long = monotonicNanos
    }

    private class VectorIdentifiers : CoreIdentifierGenerator {
        private var next = 0

        override fun next(prefix: String): String = prefix + (next++).toString().padStart(8, '0')
    }

    private fun resolveFixture(kind: String, fixtureId: String): String =
        when (kind to fixtureId) {
            "config" to "config-enabled" -> resourceText("contracts/v1/fixtures/config-enabled.json")
            "config" to "config-disabled" -> resourceText("contracts/v1/fixtures/config-disabled.json")
            "effectivePrivacy" to "privacy-allowed" -> resourceText("contracts/v1/fixtures/privacy-allowed.json")
            "effectivePrivacy" to "privacy-blocked" -> resourceText("contracts/v1/fixtures/privacy-blocked.json")
            else -> error("Unsupported logical fixture: $kind/$fixtureId")
        }

    private fun replacePointer(root: JSONObject, pointer: String, value: Any) {
        val segments = pointer.removePrefix("/").split('/').map { it.replace("~1", "/").replace("~0", "~") }
        var current = root
        segments.dropLast(1).forEach { segment -> current = current.getJSONObject(segment) }
        current.put(segments.last(), value)
    }

    private fun reverseJson(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> {
                val keys = mutableListOf<String>()
                value.keys().forEachRemaining { key -> keys += key }
                keys.asReversed().joinToString(prefix = "{", postfix = "}") { key ->
                    JSONObject.quote(key) + ":" + reverseJson(value.get(key))
                }
            }
            is JSONArray ->
                (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
                    reverseJson(value.get(index))
                }
            is String -> JSONObject.quote(value)
            is Boolean, is Number -> value.toString()
            else -> error("Unsupported JSON value ${value::class.java.name}")
        }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> =
        buildMap {
            json.keys().forEachRemaining { key -> put(key, jsonValue(json.get(key))) }
        }

    private fun jsonValue(value: Any): Any? =
        when (value) {
            JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> List(value.length()) { index -> jsonValue(value.get(index)) }
            else -> value
        }

    private fun versions(): RuntimeVersions =
        RuntimeVersions(
            platform = RuntimePlatform.ANDROID,
            runtime = RuntimeVersionComponent("elu-android", "0.1.0"),
            facade = RuntimeVersionComponent("Elu", "0.1.0"),
            build = "vector",
        )

    private fun terminalReason(value: String): RuntimeCaptureAuthorityTerminalReason =
        when (value) {
            "conflict" -> RuntimeCaptureAuthorityTerminalReason.CONFLICT
            "revoked" -> RuntimeCaptureAuthorityTerminalReason.REVOKED
            "stale" -> RuntimeCaptureAuthorityTerminalReason.STALE
            "privacy-blocked" -> RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED
            "malformed" -> RuntimeCaptureAuthorityTerminalReason.MALFORMED
            "site-changed" -> RuntimeCaptureAuthorityTerminalReason.SITE_CHANGED
            else -> error("Unsupported vector terminal reason $value")
        }

    private fun captureRejection(value: String): RuntimeCaptureRejection =
        when (value) {
            "authority-witness-changed" -> RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED
            "authority-expired" -> RuntimeCaptureRejection.AUTHORITY_EXPIRED
            else -> error("Unsupported vector capture rejection $value")
        }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing test resource $path" }.readText()

    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)

    private companion object {
        const val VECTOR_RESOURCE = "contracts/v1/test-vectors/capture-admission-activity.json"
        const val BROWSER_ONLY_SCENARIO = "browser-shared-barrier"
        const val OWNER_SITE_KEY = "elu_pk_test_capture"
        const val STREAM_ID = "stream_capture_vector"
        const val CONFIG_ISSUED_AT = "2026-08-04T00:00:00.000Z"
    }
}
