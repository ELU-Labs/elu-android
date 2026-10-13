package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluEvent
import dev.elu.analytics.EluPersonProfilesMode
import dev.elu.analytics.EluVersion
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event context and version stamps on the owner's capture path, with and without an event filter. */
class RuntimeEventContextQueueTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val clock = object : RuntimeCaptureClock {
        override fun wallNowEpochMillis() = Instant.parse(NOW).toEpochMilli()
        override fun elapsedRealtimeNanos(): Long = 1000L
    }

    @After fun close() { owners.asReversed().forEach { runCatching { it.closeAsync().await() } } }

    @Test fun `event context sits under super and call properties with or without a filter`() {
        val context = mapOf("\$os" to "Android", "\$app_version" to "1.0", "\$lib" to "elu-android", "plan" to "context")
        var calls = 0
        for (filter in listOf(RuntimeEventFilter(), RuntimeEventFilter(callback = EluEvent.Filter { event ->
            calls++
            assertEquals("Android", event.properties["\$os"])
            assertEquals("super", event.properties["\$app_version"])
            event
        }))) {
            val owner = open(filter, context, superProperties = mapOf("\$app_version" to "super", "plan" to "super"))
            authorize(owner)
            assertTrue(owner.capture(command(mapOf("\$lib" to "call", "plan" to "call"))).await() is RuntimeCaptureResult.Accepted)
            val properties = events(owner).single().record.properties
            assertEquals("Android", properties["\$os"])
            assertEquals("super", properties["\$app_version"])
            assertEquals("call", properties["\$lib"])
            assertEquals("call", properties["plan"])
        }
        assertEquals(1, calls)
    }

    @Test fun `every event kind receives the context`() {
        val owner = open(context = mapOf("\$os" to "Android", "\$device_model" to "Pixel 9"))
        authorize(owner)
        assertTrue(owner.capture(command(emptyMap())).await() is RuntimeCaptureResult.Accepted)
        assertTrue(owner.capture(command(mapOf("\$screen_name" to "Home"), kind = RuntimeEventKind.SCREEN, name = "Home"))
            .await() is RuntimeCaptureResult.Accepted)
        val records = events(owner).map { it.record }
        assertEquals(listOf(RuntimeEventKind.CAPTURE, RuntimeEventKind.SCREEN), records.map { it.kind })
        for (record in records) {
            assertEquals("Android", record.properties["\$os"])
            assertEquals("Pixel 9", record.properties["\$device_model"])
        }
        assertEquals("Home", records.last().properties["\$screen_name"])
    }

    @Test fun `property denylist and beforeSend can remove the context`() {
        val owner = open(RuntimeEventFilter(listOf("\$timezone"), EluEvent.Filter { event ->
            event.properties.remove("\$locale")
            event.properties["\$device_model"] = "replaced"
            event
        }), mapOf("\$timezone" to "UTC", "\$locale" to "en-US", "\$os" to "Android", "\$device_model" to "Pixel 9"))
        authorize(owner)
        assertTrue(owner.capture(command(emptyMap())).await() is RuntimeCaptureResult.Accepted)
        val properties = events(owner).single().record.properties
        assertFalse(properties.containsKey("\$timezone"))
        assertFalse(properties.containsKey("\$locale"))
        assertEquals("Android", properties["\$os"])
        assertEquals("replaced", properties["\$device_model"])
    }

    @Test fun `version properties are stamped after filtering and cannot be supplied`() {
        val forged = mapOf("\$elu_sdk_version" to "call", "\$elu_contract_version" to "call", "\$elu_facade_version" to "call")
        for (filter in listOf(RuntimeEventFilter(), RuntimeEventFilter(callback = EluEvent.Filter { event ->
            assertTrue(event.properties.keys.none { it.startsWith("\$elu_") })
            event.properties["\$elu_sdk_version"] = "filter"
            event
        }))) {
            val owner = open(filter, superProperties = mapOf("\$elu_sdk_version" to "super"))
            authorize(owner)
            assertTrue(owner.capture(command(forged)).await() is RuntimeCaptureResult.Accepted)
            val properties = events(owner).single().record.properties
            assertEquals("1.0.0", properties["\$elu_contract_version"])
            assertEquals(EluVersion.NAME, properties["\$elu_sdk_version"])
            assertEquals(EluVersion.FACADE_VERSION.toString(), properties["\$elu_facade_version"])
        }
    }

    @Test fun `an owner without context sends only the call and version properties`() {
        val owner = open()
        authorize(owner)
        assertTrue(owner.capture(command(mapOf("plan" to "call"))).await() is RuntimeCaptureResult.Accepted)
        val properties = events(owner).single().record.properties
        assertEquals("call", properties["plan"])
        assertNull(properties["\$lib"])
        assertEquals(setOf("plan", "\$elu_contract_version", "\$elu_sdk_version", "\$elu_facade_version", "\$device_id",
            "\$is_identified", "\$process_person_profile"), properties.keys)
    }

    @Test fun `context values must be JSON`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeQueueOwner.open("context-${UUID.randomUUID()}", RuntimeQueueLimits(100, 1_000_000),
                { FakeRuntimeQueueBacking().connection() }, { fresh(emptyMap()) }, eventContext = mapOf("\$os" to Any()))
        }
    }

    private fun open(
        filter: RuntimeEventFilter = RuntimeEventFilter(),
        context: Map<String, Any?> = emptyMap(),
        superProperties: Map<String, Any?> = emptyMap(),
    ): RuntimeQueueOwner {
        val backing = FakeRuntimeQueueBacking()
        return RuntimeQueueOwner.open("context-${UUID.randomUUID()}", RuntimeQueueLimits(100, 1_000_000),
            { backing.connection() }, { fresh(superProperties) }, trustedSiteKey = "elu_pk_test_capture", captureClock = clock,
            personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY, eventFilter = filter, eventContext = context)
            .await().also { owners += it }
    }

    private fun fresh(superProperties: Map<String, Any?>) = PersistedCoreState(
        identity = IdentityState(revision = 2, contextRevision = 5, anonymousId = "anon_context", userId = null,
            groups = emptyMap(), superProperties = superProperties, session = null, optedOut = false, updatedAt = ISSUED),
        stream = StreamState(streamId = "stream_context", nextSequence = 0),
        flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()))

    private fun command(properties: Map<String, Any?>, kind: RuntimeEventKind = RuntimeEventKind.CAPTURE,
        name: String = "ordinary") = RuntimeCaptureCommand(kind, name, NOW, properties, StandaloneRuntime.defaultVersions())

    private fun authorize(owner: RuntimeQueueOwner) {
        val revision = owner.snapshot().await().state.identity.contextRevision
        val json = JSONObject(resource("privacy-allowed.json")).put("contextRevision", revision)
        val parsed = V1StrictCanonicalJson.parse(json.toString()) as V1StrictCanonicalJson.Value.ObjectValue
        json.put("effectivePolicyHash", V1StrictCanonicalJson.sha256(V1StrictCanonicalJson.Value.ObjectValue(
            parsed.members.filterNot { it.first == "effectivePolicyHash" })))
        assertTrue(owner.submitCaptureAuthority(resource("config-enabled.json"), json.toString()).await()
            is RuntimeCaptureAuthorityUpdateResult.Activated)
    }

    private fun resource(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("contracts/v1/fixtures/$name")).readText()

    private fun events(owner: RuntimeQueueOwner) =
        owner.peek(100, Long.MAX_VALUE).await().filterIsInstance<RuntimeQueuedRecord.Event>()

    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)

    private companion object {
        const val NOW = "2026-08-04T00:01:00.000Z"
        const val ISSUED = "2026-08-04T00:00:00.000Z"
    }
}
