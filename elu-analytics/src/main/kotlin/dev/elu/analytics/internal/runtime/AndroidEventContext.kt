package dev.elu.analytics.internal.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import java.util.Locale
import java.util.TimeZone

/**
 * Reads [RuntimeEventContext] once from the application. The SDK calls this on its own setup
 * worker, never on the main thread. Each value is read independently: one the platform cannot
 * provide is omitted, and no failure here can stop setup.
 */
internal object AndroidEventContext {
    fun read(context: Context, lib: RuntimeVersionComponent): RuntimeEventContext =
        try {
            readValues(context.applicationContext ?: context, lib)
        } catch (_: Exception) {
            RuntimeEventContext(lib)
        }

    private fun readValues(app: Context, lib: RuntimeVersionComponent): RuntimeEventContext {
        val packageInfo = attempt { packageInfo(app) }
        val metrics = attempt { displayMetrics(app) }
        val screen = metrics?.let { RuntimeEventContext.portraitSize(it.widthPixels, it.heightPixels, it.density) }
        val configuration = attempt { app.resources.configuration }
        val uiModeType = configuration?.let { it.uiMode and Configuration.UI_MODE_TYPE_MASK }
            ?: Configuration.UI_MODE_TYPE_UNDEFINED
        val smallestWidth = configuration?.smallestScreenWidthDp
            ?.takeIf { it != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED } ?: screen?.first
        return RuntimeEventContext(
            lib = lib,
            osVersion = RuntimeEventContext.text(attempt { Build.VERSION.RELEASE }),
            deviceManufacturer = RuntimeEventContext.buildText(attempt { Build.MANUFACTURER }),
            deviceModel = RuntimeEventContext.buildText(attempt { Build.MODEL }),
            deviceType = RuntimeEventContext.deviceType(uiModeType, smallestWidth),
            isEmulator = attempt {
                RuntimeEventContext.isEmulator(Build.FINGERPRINT, Build.HARDWARE, Build.MODEL, Build.MANUFACTURER,
                    Build.BRAND, Build.DEVICE, Build.PRODUCT)
            } ?: false,
            appName = RuntimeEventContext.text(attempt { app.applicationInfo.loadLabel(app.packageManager) }),
            appVersion = RuntimeEventContext.text(packageInfo?.versionName),
            appBuild = packageInfo?.let { info -> attempt { versionCode(info).toString() } },
            appNamespace = RuntimeEventContext.text(attempt { app.packageName }),
            screenWidth = screen?.first,
            screenHeight = screen?.second,
            locale = attempt { RuntimeEventContext.localeTag(Locale.getDefault()) },
            timeZone = RuntimeEventContext.text(attempt { TimeZone.getDefault().id }),
        )
    }

    private fun packageInfo(app: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0)
        }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /**
     * The default display's full size. The display service needs no visual context, so this is
     * safe from the application context on every supported API level.
     */
    private fun displayMetrics(app: Context): DisplayMetrics? {
        val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) return metrics
        }
        return app.resources.displayMetrics
    }

    private inline fun <T> attempt(read: () -> T?): T? =
        try {
            read()
        } catch (_: Exception) {
            null
        }
}
