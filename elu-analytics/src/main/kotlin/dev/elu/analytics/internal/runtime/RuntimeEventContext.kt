package dev.elu.analytics.internal.runtime

import android.content.res.Configuration
import java.util.Collections
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Library, OS, device and app context for every new event, read once when the runtime opens.
 * It sits under registered super properties and the call's own properties, and the event
 * filter may replace or remove any of it.
 *
 * Names and meanings match the iOS SDK (`$os_name`, `$device_model`, `$app_build`, ...) and
 * add the web's `$os`, so OS, app-version and device breakdowns read the same across
 * platforms. No advertising ID, Android ID, serial number, user-assigned device name or
 * account name is read.
 */
internal data class RuntimeEventContext(
    val lib: RuntimeVersionComponent,
    val osVersion: String? = null,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val isEmulator: Boolean = false,
    val appName: String? = null,
    val appVersion: String? = null,
    val appBuild: String? = null,
    val appNamespace: String? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val locale: String? = null,
    val timeZone: String? = null,
) {
    /** Unknown, empty or partial values are omitted rather than sent empty. */
    val properties: Map<String, Any?>
        get() {
            val result = linkedMapOf<String, Any?>(
                "\$lib" to lib.name,
                "\$lib_version" to lib.version,
                "\$os" to OS_NAME,
                "\$os_name" to OS_NAME,
            )
            fun put(key: String, value: String?) {
                text(value)?.let { result[key] = it }
            }
            put("\$os_version", osVersion)
            put("\$device_type", deviceType)
            put("\$device_manufacturer", deviceManufacturer)
            put("\$device_model", deviceModel)
            result["\$is_emulator"] = isEmulator
            put("\$app_name", appName)
            put("\$app_version", appVersion)
            put("\$app_build", appBuild)
            put("\$app_namespace", appNamespace)
            if (screenWidth != null && screenHeight != null && screenWidth > 0 && screenHeight > 0) {
                result["\$screen_width"] = screenWidth
                result["\$screen_height"] = screenHeight
            }
            put("\$locale", locale)
            put("\$timezone", timeZone)
            return Collections.unmodifiableMap(result)
        }

    companion object {
        const val OS_NAME: String = "Android"
        const val MOBILE: String = "Mobile"
        const val TABLET: String = "Tablet"

        /** The platform's own tablet boundary, the `sw600dp` resource qualifier. */
        private const val TABLET_SMALLEST_WIDTH_DP = 600
        private const val MAXIMUM_TEXT_LENGTH = 512
        // Configuration.UI_MODE_TYPE_VR_HEADSET, named on API 26 and later.
        private const val UI_MODE_TYPE_VR_HEADSET = 7

        private val NON_HANDHELD_UI_MODE_TYPES = setOf(
            Configuration.UI_MODE_TYPE_TELEVISION,
            Configuration.UI_MODE_TYPE_APPLIANCE,
            Configuration.UI_MODE_TYPE_WATCH,
            UI_MODE_TYPE_VR_HEADSET,
        )

        /**
         * `Mobile` or `Tablet` for handheld devices, by their smallest width in dp. Televisions,
         * watches, appliances and headsets, and an unknown width, leave the type unset.
         */
        fun deviceType(uiModeType: Int, smallestWidthDp: Int?): String? {
            if (uiModeType in NON_HANDHELD_UI_MODE_TYPES) return null
            val width = smallestWidthDp?.takeIf { it > 0 } ?: return null
            return if (width >= TABLET_SMALLEST_WIDTH_DP) TABLET else MOBILE
        }

        /** True for the Android emulator and common virtual devices, from build identifiers only. */
        fun isEmulator(
            fingerprint: String?,
            hardware: String?,
            model: String?,
            manufacturer: String?,
            brand: String?,
            device: String?,
            product: String?,
        ): Boolean {
            fun lower(value: String?) = value.orEmpty().lowercase(Locale.US)
            val fingerprintValue = lower(fingerprint)
            val hardwareValue = lower(hardware)
            val modelValue = lower(model)
            val productValue = lower(product)
            return hardwareValue == "goldfish" || hardwareValue == "ranchu" || hardwareValue.contains("vbox86") ||
                fingerprintValue.startsWith("generic") || fingerprintValue.contains("/sdk_gphone") ||
                modelValue.contains("google_sdk") || modelValue.contains("emulator") ||
                modelValue.contains("android sdk built for") ||
                lower(manufacturer).contains("genymotion") ||
                (lower(brand).startsWith("generic") && lower(device).startsWith("generic")) ||
                productValue == "sdk" || productValue == "google_sdk" || productValue.startsWith("sdk_") ||
                productValue.contains("_sdk_") || productValue.endsWith("_sdk") ||
                productValue.contains("emulator") || productValue.contains("simulator") || productValue.contains("vbox86p")
        }

        /**
         * Density-independent screen size with the shorter edge as the width, so the value does
         * not depend on launch orientation. Null when the display size or density is unknown.
         */
        fun portraitSize(widthPixels: Int, heightPixels: Int, density: Float): Pair<Int, Int>? {
            if (widthPixels <= 0 || heightPixels <= 0 || !density.isFinite() || density <= 0f) return null
            val width = (widthPixels / density).roundToInt()
            val height = (heightPixels / density).roundToInt()
            if (width <= 0 || height <= 0) return null
            return minOf(width, height) to maxOf(width, height)
        }

        /** A BCP 47 tag such as `en-US`, or null for an undetermined locale. */
        fun localeTag(locale: Locale?): String? = text(locale?.toLanguageTag())?.takeUnless { it == "und" }

        /** A Build field, where the platform reports a missing value as `unknown`. */
        fun buildText(value: String?): String? = text(value)?.takeUnless { it.equals("unknown", ignoreCase = true) }

        /** Trimmed, bounded, well-formed text; anything else is omitted. */
        fun text(value: CharSequence?): String? {
            val text = value?.toString()?.trim() ?: return null
            if (text.isEmpty() || text.length > MAXIMUM_TEXT_LENGTH || !wellFormed(text)) return null
            return text
        }

        private fun wellFormed(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (Character.isHighSurrogate(char)) {
                    if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
                } else if (Character.isLowSurrogate(char) || char.code < 0x20 || char.code == 0x7f) {
                    return false
                }
            }
            return true
        }
    }
}
