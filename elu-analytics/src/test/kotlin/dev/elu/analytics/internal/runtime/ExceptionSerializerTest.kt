package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.JsonValues
import java.lang.reflect.InvocationTargetException
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
        // This test lives in the SDK's own package, so its frame is not app code.
        assertEquals(false, top["in_app"])
        // The server rejects the whole list if any link has a frame without its platform.
        for (entry in list) {
            val linkFrames = ((entry as Map<*, *>)["stacktrace"] as Map<*, *>)["frames"] as List<*>
            assertTrue(linkFrames.isNotEmpty())
            assertTrue(linkFrames.all { (it as Map<*, *>)["platform"] == ExceptionSerializer.FRAME_PLATFORM })
        }
        assertEquals("java", ExceptionSerializer.FRAME_PLATFORM)

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
    fun `a link without a message sends no value instead of null`() {
        // The server reads `value` as a string; a JSON null in any link fails the whole list.
        val root = RuntimeException()
        val reflective = InvocationTargetException(root)
        val outer = IllegalStateException("Could not execute method for android:onClick", reflective)

        val list = ExceptionSerializer.properties(outer)[ExceptionSerializer.LIST_PROPERTY] as List<*>

        val entries = list.map { it as Map<*, *> }
        assertEquals(
            listOf("IllegalStateException", "InvocationTargetException", "RuntimeException"),
            entries.map { it["type"] },
        )
        assertEquals("Could not execute method for android:onClick", entries[0]["value"])
        assertFalse(entries[1].containsKey("value"))
        assertFalse(entries[2].containsKey("value"))

        val single = ExceptionSerializer.properties(RuntimeException())[ExceptionSerializer.LIST_PROPERTY] as List<*>
        assertFalse((single.single() as Map<*, *>).containsKey("value"))
        val empty = ExceptionSerializer.properties(RuntimeException(""))[ExceptionSerializer.LIST_PROPERTY] as List<*>
        assertEquals("", (empty.single() as Map<*, *>)["value"])
    }

    @Test
    fun `runtime and sdk frames are not in-app and everything else is`() {
        val cases =
            listOf(
                "com.example.shop.CheckoutActivity" to true,
                "a.b" to true,
                "a" to true,
                // A platform name inside an app package is still the app.
                "com.example.android.shop.CheckoutActivity" to true,
                "com.example.java.Main" to true,
                // Near misses: each runtime prefix ends at a package boundary.
                "javaapp.Main" to true,
                "javaxapp.Main" to true,
                "jdkapp.Main" to true,
                "sunrise.App" to true,
                "kotlinapp.Main" to true,
                "kotlinxapp.Main" to true,
                "androidapp.Main" to true,
                "androidxtra.Widget" to true,
                "com.androidapp.Main" to true,
                "dalvikapp.Main" to true,
                "libcoreapp.Main" to true,
                "org.jsonapp.Main" to true,
                "org.xmlpullapp.Main" to true,
                "org.w3c.domapp.Main" to true,
                "org.xml.saxapp.Main" to true,
                "org.apache.httpapp.Main" to true,
                "org.apache.commons.lang3.Validate" to true,
                "org.apache.harmonyapp.Main" to true,
                "org.kxml2app.Main" to true,
                "org.ccil.cowan.tagsoupapp.Main" to true,
                "j\$app.Main" to true,
                "dev.elu.analytics.sample.MainActivity" to true,
                "dev.elu.analytics.composeconsumer.MainActivity" to true,
                "dev.elu.fixture.smoke.ConsumerSmoke" to true,
                "java.lang.reflect.Method" to false,
                "javax.net.ssl.SSLSocket" to false,
                "jdk.internal.reflect.DirectMethodHandleAccessor" to false,
                "sun.nio.ch.SocketDispatcher" to false,
                "kotlin.coroutines.jvm.internal.BaseContinuationImpl" to false,
                "kotlinx.coroutines.DispatchedTask" to false,
                "android.os.Looper" to false,
                "androidx.activity.ComponentActivity" to false,
                "com.android.internal.os.ZygoteInit" to false,
                "dalvik.system.VMStack" to false,
                "libcore.io.Linux" to false,
                "org.json.JSONObject" to false,
                "org.xmlpull.v1.XmlPullParserException" to false,
                "org.w3c.dom.Element" to false,
                "org.xml.sax.helpers.DefaultHandler" to false,
                "org.apache.http.impl.client.DefaultHttpClient" to false,
                "org.apache.harmony.xml.ExpatParser" to false,
                "org.kxml2.io.KXmlParser" to false,
                "org.ccil.cowan.tagsoup.Parser" to false,
                "j\$.util.stream.ReferencePipeline" to false,
                "dev.elu.analytics.Elu" to false,
                "dev.elu.analytics.Elu\$captureException\$1" to false,
                "dev.elu.analytics.internal.runtime.StandaloneRuntime" to false,
                "dev.elu.analytics.compose.EluComposeReplayKt" to false,
                "dev.elu.analytics.compose.internal.ComposeReplayNodes" to false,
            )
        for ((className, expected) in cases) {
            assertEquals(className, expected, ExceptionSerializer.isInApp(className))
        }

        // As in a JSONException: the platform's org.json frame sits above the app's own frame.
        val throwable = Throwable("tap")
        throwable.stackTrace =
            arrayOf(
                StackTraceElement("org.json.JSONObject", "getString", "JSONObject.java", 600),
                StackTraceElement("com.example.shop.CheckoutActivity", "onPay", "CheckoutActivity.kt", 42),
                StackTraceElement("android.view.View", "performClick", "View.java", 7_000),
                StackTraceElement("dev.elu.analytics.Elu", "capture", "Elu.kt", 10),
            )
        val entry = (ExceptionSerializer.properties(throwable)[ExceptionSerializer.LIST_PROPERTY] as List<*>).single() as Map<*, *>
        val frames = (entry["stacktrace"] as Map<*, *>)["frames"] as List<*>
        assertEquals(listOf(false, true, false, false), frames.map { (it as Map<*, *>)["in_app"] })
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
