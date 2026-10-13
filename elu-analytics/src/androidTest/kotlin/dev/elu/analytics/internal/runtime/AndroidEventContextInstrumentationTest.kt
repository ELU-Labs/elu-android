package dev.elu.analytics.internal.runtime

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEventContextInstrumentationTest {
    private val allowed = setOf("\$lib", "\$lib_version", "\$os", "\$os_name", "\$os_version", "\$device_type",
        "\$device_manufacturer", "\$device_model", "\$is_emulator", "\$app_name", "\$app_version", "\$app_build",
        "\$app_namespace", "\$screen_width", "\$screen_height", "\$locale", "\$timezone")

    @Test
    fun readsOnlyNonIdentifyingDeviceAndAppFactsOffTheMainThread() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = Executors.newSingleThreadExecutor()
        val properties = try {
            worker.submit(Callable {
                assertFalse(Looper.getMainLooper().isCurrentThread)
                AndroidEventContext.read(context, StandaloneRuntime.defaultVersions().runtime).properties
            }).get(10, TimeUnit.SECONDS)
        } finally {
            worker.shutdownNow()
        }
        assertTrue("${properties.keys - allowed}", allowed.containsAll(properties.keys))
        assertEquals("elu-android", properties["\$lib"])
        assertEquals("Android", properties["\$os"])
        assertEquals("Android", properties["\$os_name"])
        assertEquals(Build.VERSION.RELEASE, properties["\$os_version"])
        assertEquals(context.packageName, properties["\$app_namespace"])
        assertEquals(TimeZone.getDefault().id, properties["\$timezone"])
        assertTrue(properties["\$device_type"] in setOf("Mobile", "Tablet"))
        val width = properties["\$screen_width"] as Int
        val height = properties["\$screen_height"] as Int
        assertTrue(width in 1..height)
        val isEmulator = properties["\$is_emulator"] as Boolean
        if (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") assertTrue(isEmulator)
    }
}
