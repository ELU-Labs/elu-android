package dev.elu.analytics.sample

import android.app.Application
import dev.elu.analytics.Elu

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Elu.setup(this, "YOUR_SITE_KEY")
    }
}
