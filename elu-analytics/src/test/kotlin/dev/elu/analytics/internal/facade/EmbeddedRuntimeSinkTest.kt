package dev.elu.analytics.internal.facade

import dev.elu.analytics.EmbeddedRuntimeCalls
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedRuntimeSinkTest {
    private val gate = RecordingGate()
    private val runtime = RecordingRuntime()
    private val sink = EmbeddedRuntimeSink(gate, runtime)

    @Test
    fun `every facade method reaches the embedded runtime`() {
        val timestamp = Date(1_754_265_660_000L)
        val failure = IllegalStateException("boom")

        sink.capture("checkout", mapOf("amount" to 42), timestamp)
        sink.identify("user_1", mapOf("plan" to "growth"))
        sink.screen("Home", mapOf("source" to "tab"))
        sink.alias("alias_1")
        sink.reset()
        sink.captureException(failure, mapOf("handled" to true))
        sink.register(mapOf("plan" to "growth"))
        sink.unregister("plan")
        sink.setPersonProperties(mapOf("role" to "owner"))
        sink.group("organization", "org_1", mapOf("tier" to "design-partner"))
        sink.setPersonPropertiesForFlags(mapOf("role" to "owner"))
        sink.setGroupPropertiesForFlags("organization", mapOf("tier" to "design-partner"))
        sink.reloadFeatureFlags(null)
        sink.flush()

        assertEquals(
            listOf(
                "capture(checkout, {amount=42}, $timestamp)",
                "identify(user_1, {plan=growth})",
                "screen(Home, {source=tab})",
                "alias(alias_1)",
                "reset()",
                "captureException($failure, {handled=true})",
                "register({plan=growth})",
                "unregister(plan)",
                "setPersonProperties({role=owner})",
                "group(organization, org_1, {tier=design-partner})",
                "setPersonPropertiesForFlags({role=owner})",
                "setGroupPropertiesForFlags(organization, {tier=design-partner})",
                "reloadFeatureFlags(null)",
                "flush()",
            ),
            runtime.calls,
        )
    }

    @Test
    fun `getters read through the gate and return the embedded runtime values`() {
        runtime.distinctId = "user_1"
        runtime.flags["new-checkout"] = "variant-a"
        runtime.payloads["new-checkout"] = mapOf("buttonColor" to "violet")
        runtime.enabled += "new-checkout"

        assertEquals("user_1", sink.distinctId())
        assertEquals("variant-a", sink.getFeatureFlag("new-checkout"))
        assertEquals(mapOf("buttonColor" to "violet"), sink.getFeatureFlagPayload("new-checkout"))
        assertTrue(sink.isFeatureEnabled("new-checkout"))
        assertNull(sink.getFeatureFlag("absent"))
        assertFalse(sink.isFeatureEnabled("absent"))
        assertEquals(
            listOf("distinctId()", "getFeatureFlag(new-checkout)", "getFeatureFlagPayload(new-checkout)")
                .plus(listOf("isFeatureEnabled(new-checkout)", "getFeatureFlag(absent)", "isFeatureEnabled(absent)")),
            runtime.calls,
        )
    }

    @Test
    fun `a getter falls back to its default when the gate is not running`() {
        gate.running = false
        runtime.distinctId = "user_1"
        runtime.enabled += "new-checkout"

        assertNull(sink.distinctId())
        assertNull(sink.getFeatureFlag("new-checkout"))
        assertNull(sink.getFeatureFlagPayload("new-checkout"))
        assertFalse(sink.isFeatureEnabled("new-checkout"))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `reset re-registers the ELU super properties the runtime wipes`() {
        sink.reset()

        assertEquals(listOf("reset()"), runtime.calls)
        assertEquals(1, gate.superPropertyRegistrations)
    }

    @Test
    fun `each method uses the dispatch class the frozen contract promises`() {
        sink.capture("checkout", null, Date(0))
        sink.reloadFeatureFlags(null)
        sink.flush()
        sink.onFeatureFlagsLoaded {}

        assertEquals(listOf("dispatch", "runningOnly", "runningOnly"), gate.dispatches)
        assertEquals(1, gate.flagListeners)
    }

    @Test
    fun `a reload completion is handed to the embedded runtime unchanged`() {
        var completions = 0
        val completion = { completions += 1 }

        sink.reloadFeatureFlags(completion)
        runtime.lastCompletion?.invoke()

        assertEquals(1, completions)
    }

    private class RecordingGate : EluLifecycleGate {
        var running = true
        val dispatches = mutableListOf<String>()
        var superPropertyRegistrations = 0
        var flagListeners = 0

        override fun dispatch(op: () -> Unit) {
            dispatches += "dispatch"
            if (running) op()
        }

        override fun dispatchRunningOnly(op: () -> Unit) {
            dispatches += "runningOnly"
            if (running) op()
        }

        override fun <T> read(
            default: T,
            op: () -> T,
        ): T = if (running) op() else default

        override fun addOnFeatureFlagsLoaded(callback: () -> Unit) {
            flagListeners += 1
        }

        override fun registerEluSuperProperties() {
            superPropertyRegistrations += 1
        }
    }

    private class RecordingRuntime : EmbeddedRuntimeCalls {
        val calls = mutableListOf<String>()
        val flags = mutableMapOf<String, Any>()
        val payloads = mutableMapOf<String, Any>()
        val enabled = mutableSetOf<String>()
        var distinctId: String? = null
        var lastCompletion: (() -> Unit)? = null

        override fun capture(
            event: String,
            properties: Map<String, Any>?,
            timestamp: Date,
        ) {
            calls += "capture($event, $properties, $timestamp)"
        }

        override fun identify(
            distinctId: String,
            userProperties: Map<String, Any>?,
        ) {
            calls += "identify($distinctId, $userProperties)"
        }

        override fun screen(
            name: String,
            properties: Map<String, Any>?,
        ) {
            calls += "screen($name, $properties)"
        }

        override fun alias(alias: String) {
            calls += "alias($alias)"
        }

        override fun reset() {
            calls += "reset()"
        }

        override fun captureException(
            error: Throwable,
            properties: Map<String, Any>?,
        ) {
            calls += "captureException($error, $properties)"
        }

        override fun register(properties: Map<String, Any>) {
            calls += "register($properties)"
        }

        override fun unregister(key: String) {
            calls += "unregister($key)"
        }

        override fun setPersonProperties(properties: Map<String, Any>) {
            calls += "setPersonProperties($properties)"
        }

        override fun group(
            type: String,
            key: String,
            properties: Map<String, Any>?,
        ) {
            calls += "group($type, $key, $properties)"
        }

        override fun distinctId(): String? {
            calls += "distinctId()"
            return distinctId
        }

        override fun getFeatureFlag(key: String): Any? {
            calls += "getFeatureFlag($key)"
            return flags[key]
        }

        override fun getFeatureFlagPayload(key: String): Any? {
            calls += "getFeatureFlagPayload($key)"
            return payloads[key]
        }

        override fun isFeatureEnabled(key: String): Boolean {
            calls += "isFeatureEnabled($key)"
            return key in enabled
        }

        override fun reloadFeatureFlags(completion: (() -> Unit)?) {
            calls += "reloadFeatureFlags(${completion?.let { "completion" }})"
            lastCompletion = completion
        }

        override fun setPersonPropertiesForFlags(properties: Map<String, Any>) {
            calls += "setPersonPropertiesForFlags($properties)"
        }

        override fun setGroupPropertiesForFlags(
            type: String,
            properties: Map<String, Any>,
        ) {
            calls += "setGroupPropertiesForFlags($type, $properties)"
        }

        override fun flush() {
            calls += "flush()"
        }
    }
}
