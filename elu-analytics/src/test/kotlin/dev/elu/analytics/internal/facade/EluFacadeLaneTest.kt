package dev.elu.analytics.internal.facade

import dev.elu.analytics.EluRuntimeMode
import dev.elu.analytics.EluRuntimeSelector
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EluFacadeLaneTest {
    private val embedded = RecordingSink()
    private val standalone = RecordingSink()

    @Test
    fun `the compiled default drives the embedded runtime`() {
        assertEquals(EluRuntimeMode.WRAPPED, EluRuntimeSelector.mode())
    }

    @Test
    fun `the unselected runtime is never built`() {
        selectFacadeLane(
            EluRuntimeMode.WRAPPED,
            wrapped = { EluFacadeLane(embedded) {} },
            standalone = {
                fail("the standalone runtime must not be built while the embedded one is selected")
                EluFacadeLane(standalone) {}
            },
        )
        selectFacadeLane(
            EluRuntimeMode.STANDALONE,
            wrapped = {
                fail("the embedded runtime must not be built while the standalone one is selected")
                EluFacadeLane(embedded) {}
            },
            standalone = { EluFacadeLane(standalone) {} },
        )
    }

    @Test
    fun `the selected lane is started once and is the sink every call reaches`() {
        var starts = 0
        val lane =
            selectFacadeLane(
                EluRuntimeMode.STANDALONE,
                wrapped = { EluFacadeLane(embedded) { starts += 1 } },
                standalone = { EluFacadeLane(standalone) { starts += 1 } },
            )
        lane.start()

        assertSame(standalone, lane.sink)
        assertEquals(1, starts)
    }

    @Test
    fun `with the embedded runtime selected every call reaches it and none reaches the standalone one`() {
        val lane = lane(EluRuntimeMode.WRAPPED)

        exerciseEveryMethod(lane.sink)

        assertEquals(EVERY_METHOD, embedded.calls)
        assertTrue(standalone.calls.isEmpty())
    }

    @Test
    fun `with the standalone runtime selected every call reaches it and none reaches the embedded one`() {
        val lane = lane(EluRuntimeMode.STANDALONE)

        exerciseEveryMethod(lane.sink)

        assertEquals(EVERY_METHOD, standalone.calls)
        assertTrue(embedded.calls.isEmpty())
    }

    private fun lane(mode: EluRuntimeMode): EluFacadeLane =
        selectFacadeLane(
            mode,
            wrapped = { EluFacadeLane(embedded) {} },
            standalone = { EluFacadeLane(standalone) {} },
        )

    private fun exerciseEveryMethod(sink: EluFacadeSink) {
        sink.capture("checkout", null, Date(0))
        sink.identify("user_1", null)
        sink.screen("Home", null)
        sink.alias("alias_1")
        sink.reset()
        sink.captureException(IllegalStateException("boom"), null)
        sink.register(mapOf("plan" to "growth"))
        sink.unregister("plan")
        sink.setPersonProperties(mapOf("role" to "owner"))
        sink.group("organization", "org_1", null)
        sink.setPersonPropertiesForFlags(mapOf("role" to "owner"))
        sink.setGroupPropertiesForFlags("organization", mapOf("tier" to "free"))
        sink.reloadFeatureFlags(null)
        sink.onFeatureFlagsLoaded {}
        sink.flush()
        assertNull(sink.distinctId())
        assertNull(sink.getFeatureFlag("variant"))
        assertNull(sink.getFeatureFlagPayload("variant"))
        assertFalse(sink.isFeatureEnabled("variant"))
    }

    private class RecordingSink : EluFacadeSink {
        val calls = mutableListOf<String>()

        override fun capture(
            event: String,
            properties: Map<String, Any>?,
            timestamp: Date,
        ) {
            calls += "capture"
        }

        override fun identify(
            distinctId: String,
            userProperties: Map<String, Any>?,
        ) {
            calls += "identify"
        }

        override fun screen(
            name: String,
            properties: Map<String, Any>?,
        ) {
            calls += "screen"
        }

        override fun alias(alias: String) {
            calls += "alias"
        }

        override fun reset() {
            calls += "reset"
        }

        override fun captureException(
            error: Throwable,
            properties: Map<String, Any>?,
        ) {
            calls += "captureException"
        }

        override fun register(properties: Map<String, Any>) {
            calls += "register"
        }

        override fun unregister(key: String) {
            calls += "unregister"
        }

        override fun setPersonProperties(properties: Map<String, Any>) {
            calls += "setPersonProperties"
        }

        override fun group(
            type: String,
            key: String,
            properties: Map<String, Any>?,
        ) {
            calls += "group"
        }

        override fun distinctId(): String? {
            calls += "distinctId"
            return null
        }

        override fun getFeatureFlag(key: String): Any? {
            calls += "getFeatureFlag"
            return null
        }

        override fun getFeatureFlagPayload(key: String): Any? {
            calls += "getFeatureFlagPayload"
            return null
        }

        override fun isFeatureEnabled(key: String): Boolean {
            calls += "isFeatureEnabled"
            return false
        }

        override fun reloadFeatureFlags(completion: (() -> Unit)?) {
            calls += "reloadFeatureFlags"
        }

        override fun onFeatureFlagsLoaded(callback: () -> Unit) {
            calls += "onFeatureFlagsLoaded"
        }

        override fun setPersonPropertiesForFlags(properties: Map<String, Any>) {
            calls += "setPersonPropertiesForFlags"
        }

        override fun setGroupPropertiesForFlags(
            type: String,
            properties: Map<String, Any>,
        ) {
            calls += "setGroupPropertiesForFlags"
        }

        override fun flush() {
            calls += "flush"
        }
    }

    private companion object {
        val EVERY_METHOD =
            listOf(
                "capture",
                "identify",
                "screen",
                "alias",
                "reset",
                "captureException",
                "register",
                "unregister",
                "setPersonProperties",
                "group",
                "setPersonPropertiesForFlags",
                "setGroupPropertiesForFlags",
                "reloadFeatureFlags",
                "onFeatureFlagsLoaded",
                "flush",
                "distinctId",
                "getFeatureFlag",
                "getFeatureFlagPayload",
                "isFeatureEnabled",
            )
    }
}
