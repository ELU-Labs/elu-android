package dev.elu.analytics.internal.runtime

import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEventContextTest {
    private val lib = RuntimeVersionComponent("elu-android", "0.2.0")

    @Test fun `properties use the mobile and web names`() {
        val context = RuntimeEventContext(lib, osVersion = "15", deviceManufacturer = "Google", deviceModel = "Pixel 9",
            deviceType = "Mobile", isEmulator = false, appName = "Shop", appVersion = "4.2", appBuild = "512",
            appNamespace = "com.example.shop", screenWidth = 412, screenHeight = 915, locale = "en-US",
            timeZone = "America/New_York")
        assertEquals(mapOf(
            "\$lib" to "elu-android", "\$lib_version" to "0.2.0",
            "\$os" to "Android", "\$os_name" to "Android", "\$os_version" to "15",
            "\$device_type" to "Mobile", "\$device_manufacturer" to "Google", "\$device_model" to "Pixel 9",
            "\$is_emulator" to false,
            "\$app_name" to "Shop", "\$app_version" to "4.2", "\$app_build" to "512", "\$app_namespace" to "com.example.shop",
            "\$screen_width" to 412, "\$screen_height" to 915,
            "\$locale" to "en-US", "\$timezone" to "America/New_York",
        ), context.properties)
    }

    @Test fun `unknown empty or partial values are omitted`() {
        val context = RuntimeEventContext(lib, osVersion = "", deviceModel = null, isEmulator = true, appName = " ",
            appVersion = null, screenWidth = 412, screenHeight = 0, locale = "en\u0000US")
        assertEquals(mapOf(
            "\$lib" to "elu-android", "\$lib_version" to "0.2.0", "\$os" to "Android", "\$os_name" to "Android",
            "\$is_emulator" to true,
        ), context.properties)
    }

    @Test fun `only non-identifying keys are produced`() {
        val allowed = setOf("\$lib", "\$lib_version", "\$os", "\$os_name", "\$os_version", "\$device_type",
            "\$device_manufacturer", "\$device_model", "\$is_emulator", "\$app_name", "\$app_version", "\$app_build",
            "\$app_namespace", "\$screen_width", "\$screen_height", "\$locale", "\$timezone")
        val full = RuntimeEventContext(lib, "15", "Google", "Pixel", "Tablet", true, "App", "1", "2", "a.b", 1, 2, "en", "UTC")
        assertEquals(allowed, full.properties.keys)
        assertTrue(full.properties.keys.none { it.startsWith("\$elu_") })
    }

    @Test fun `device type follows the platform tablet boundary and leaves non-handhelds unset`() {
        val normal = Configuration.UI_MODE_TYPE_NORMAL
        assertEquals("Mobile", RuntimeEventContext.deviceType(normal, 411))
        assertEquals("Mobile", RuntimeEventContext.deviceType(normal, 599))
        assertEquals("Tablet", RuntimeEventContext.deviceType(normal, 600))
        assertEquals("Tablet", RuntimeEventContext.deviceType(normal, 800))
        assertEquals("Mobile", RuntimeEventContext.deviceType(Configuration.UI_MODE_TYPE_UNDEFINED, 360))
        // A phone docked or projecting to a car is still the same handheld device.
        assertEquals("Mobile", RuntimeEventContext.deviceType(Configuration.UI_MODE_TYPE_DESK, 360))
        assertEquals("Mobile", RuntimeEventContext.deviceType(Configuration.UI_MODE_TYPE_CAR, 360))
        for (type in listOf(Configuration.UI_MODE_TYPE_TELEVISION, Configuration.UI_MODE_TYPE_APPLIANCE,
            Configuration.UI_MODE_TYPE_WATCH, 7)) {
            assertNull(RuntimeEventContext.deviceType(type, 960))
        }
        assertNull(RuntimeEventContext.deviceType(normal, null))
        assertNull(RuntimeEventContext.deviceType(normal, 0))
    }

    @Test fun `emulators are recognized from build identifiers`() {
        fun emulator(fingerprint: String = "google/husky/husky:15/AP4A/1:user/release-keys", hardware: String = "husky",
            model: String = "Pixel 8 Pro", manufacturer: String = "Google", brand: String = "google",
            device: String = "husky", product: String = "husky") =
            RuntimeEventContext.isEmulator(fingerprint, hardware, model, manufacturer, brand, device, product)
        assertFalse(emulator())
        assertFalse(emulator(fingerprint = "samsung/dm3qxxx/dm3q:14/UP1A/S918B:user/release-keys", hardware = "qcom",
            model = "SM-S918B", manufacturer = "samsung", brand = "samsung", device = "dm3q", product = "dm3qxxx"))
        assertFalse(RuntimeEventContext.isEmulator(null, null, null, null, null, null, null))
        // Current Android Studio emulator images.
        assertTrue(emulator(fingerprint = "google/sdk_gphone64_arm64/emu64a:15/AE3A/1:userdebug/dev-keys",
            hardware = "ranchu", model = "sdk_gphone64_arm64", device = "emu64a", product = "sdk_gphone64_arm64"))
        assertTrue(emulator(hardware = "ranchu"))
        assertTrue(emulator(hardware = "goldfish"))
        assertTrue(emulator(fingerprint = "generic/sdk/generic:6.0/MASTER/1:eng/test-keys"))
        assertTrue(emulator(model = "Android SDK built for x86"))
        assertTrue(emulator(model = "google_sdk"))
        assertTrue(emulator(manufacturer = "Genymotion"))
        assertTrue(emulator(product = "vbox86p"))
        assertTrue(emulator(product = "sdk_google_phone_x86"))
        assertTrue(emulator(brand = "generic_x86", device = "generic_x86"))
    }

    @Test fun `screen size is portrait density independent and orientation free`() {
        assertEquals(411 to 914, RuntimeEventContext.portraitSize(1080, 2400, 2.625f))
        assertEquals(411 to 914, RuntimeEventContext.portraitSize(2400, 1080, 2.625f))
        assertEquals(800 to 1280, RuntimeEventContext.portraitSize(1600, 2560, 2f))
        assertNull(RuntimeEventContext.portraitSize(0, 2400, 2.625f))
        assertNull(RuntimeEventContext.portraitSize(1080, 2400, 0f))
        assertNull(RuntimeEventContext.portraitSize(1080, 2400, Float.NaN))
    }

    @Test fun `locale tags timezone and build text are normalized`() {
        assertEquals("en-US", RuntimeEventContext.localeTag(Locale.US))
        assertEquals("pt-BR", RuntimeEventContext.localeTag(Locale.forLanguageTag("pt-BR")))
        assertEquals("zh-Hant-TW", RuntimeEventContext.localeTag(Locale.forLanguageTag("zh-Hant-TW")))
        assertNull(RuntimeEventContext.localeTag(Locale.ROOT))
        assertNull(RuntimeEventContext.localeTag(null))
        assertNull(RuntimeEventContext.buildText("unknown"))
        assertNull(RuntimeEventContext.buildText(""))
        assertEquals("Pixel 9", RuntimeEventContext.buildText(" Pixel 9 "))
        assertNull(RuntimeEventContext.text("a".repeat(513)))
        assertNull(RuntimeEventContext.text("broken\ud800"))
        assertNull(RuntimeEventContext.text("line\nbreak"))
        assertEquals("Café 😀", RuntimeEventContext.text("Café 😀"))
    }
}
