package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.JsonValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionSerializerTest {
    @Test
    fun `properties carry type message and a raw frame list that normalizes as JSON`() {
        val cause = IllegalStateException("inner failure")
        val throwable = RuntimeException("outer failure", cause)

        val properties = ExceptionSerializer.properties(throwable)

        assertEquals("RuntimeException", properties[ExceptionSerializer.TYPE_PROPERTY])
        assertEquals("outer failure", properties[ExceptionSerializer.MESSAGE_PROPERTY])
        val list = properties[ExceptionSerializer.LIST_PROPERTY] as List<*>
        assertEquals(2, list.size)
        val outer = list[0] as Map<*, *>
        val inner = list[1] as Map<*, *>
        assertEquals("RuntimeException", outer["type"])
        assertEquals("java.lang", outer["module"])
        assertEquals("IllegalStateException", inner["type"])
        assertEquals("inner failure", inner["value"])
        val stacktrace = outer["stacktrace"] as Map<*, *>
        assertEquals("raw", stacktrace["type"])
        val frames = stacktrace["frames"] as List<*>
        assertTrue(frames.isNotEmpty())
        val top = frames.first() as Map<*, *>
        assertEquals(javaClass.name, top["module"])
        assertTrue((top["function"] as String).isNotEmpty())
        assertEquals(false, top["native"])
        assertTrue((top["lineno"] as Int) > 0)

        val normalized = JsonValues.objectValue(properties, "exception")
        assertEquals(properties.keys, normalized.keys)
    }

    @Test
    fun `frames causes and messages are bounded and cycles terminate`() {
        val deep = Throwable("deep")
        deep.stackTrace = Array(500) { index -> StackTraceElement("Frame$index", "call", null, -1) }
        val frames = ((ExceptionSerializer.properties(deep)[ExceptionSerializer.LIST_PROPERTY] as List<*>)[0] as Map<*, *>)
        val frameList = (frames["stacktrace"] as Map<*, *>)["frames"] as List<*>
        assertEquals(ExceptionSerializer.MAX_FRAMES_PER_THROWABLE, frameList.size)
        val first = frameList.first() as Map<*, *>
        assertNull(first["filename"])
        assertNull(first["lineno"])

        var chain: Throwable = Throwable("link-0")
        repeat(20) { index -> chain = Throwable("link-${index + 1}", chain) }
        val chainList = ExceptionSerializer.properties(chain)[ExceptionSerializer.LIST_PROPERTY] as List<*>
        assertEquals(ExceptionSerializer.MAX_CHAIN_LENGTH, chainList.size)

        val cycleA = Throwable("a")
        val cycleB = Throwable("b", cycleA)
        cycleA.initCause(cycleB)
        val cycleList = ExceptionSerializer.properties(cycleA)[ExceptionSerializer.LIST_PROPERTY] as List<*>
        assertEquals(2, cycleList.size)

        val long = Throwable("m".repeat(5_000))
        val message = ExceptionSerializer.properties(long)[ExceptionSerializer.MESSAGE_PROPERTY] as String
        assertEquals(ExceptionSerializer.MAX_MESSAGE_CODE_POINTS, message.codePointCount(0, message.length))
    }

    @Test
    fun `unpaired surrogates are replaced and well-formed pairs survive`() {
        val lone = "before\uD83Dafter\uDE00"
        assertEquals("before\uFFFDafter\uFFFD", ExceptionSerializer.sanitize(lone, 64))
        val pair = "ok 😀 end"
        assertEquals(pair, ExceptionSerializer.sanitize(pair, 64))
        assertEquals("😀", ExceptionSerializer.sanitize("😀😀", 1))

        val message = ExceptionSerializer.properties(Throwable(lone))[ExceptionSerializer.MESSAGE_PROPERTY] as String
        assertFalse(message.contains('\uD83D'))
    }

    @Test
    fun `command uses the exception kind and lets explicit properties win`() {
        val command =
            ExceptionSerializer.command(
                IllegalArgumentException("bad input"),
                "2026-08-04T00:01:00.000Z",
                versions(),
                explicitProperties = mapOf(ExceptionSerializer.MESSAGE_PROPERTY to "redacted", "screen" to "Checkout"),
            )

        assertEquals(RuntimeEventKind.EXCEPTION, command.kind)
        assertEquals(ExceptionSerializer.EVENT_NAME, command.name)
        assertEquals("IllegalArgumentException", command.properties[ExceptionSerializer.TYPE_PROPERTY])
        assertEquals("redacted", command.properties[ExceptionSerializer.MESSAGE_PROPERTY])
        assertEquals("Checkout", command.properties["screen"])
    }

    private fun versions(): RuntimeVersions =
        RuntimeVersions(
            platform = RuntimePlatform.ANDROID,
            runtime = RuntimeVersionComponent("elu-android", "0.1.0"),
            facade = RuntimeVersionComponent("Elu", "1"),
        )
}
