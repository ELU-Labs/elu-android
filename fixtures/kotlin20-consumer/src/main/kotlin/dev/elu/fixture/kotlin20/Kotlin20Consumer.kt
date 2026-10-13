package dev.elu.fixture.kotlin20

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import dev.elu.analytics.Elu
import dev.elu.analytics.EluCaptureOptions
import dev.elu.analytics.EluDiagnosticsOptions
import dev.elu.analytics.EluEvent
import dev.elu.analytics.EluFeatureFlagSnapshot
import dev.elu.analytics.EluOptions
import dev.elu.analytics.EluPersonProfilesMode

/** Compile-only public API consumer built with the Kotlin 2.0 compiler; never installed. */
class Kotlin20Application : Application() {
    override fun onCreate() {
        super.onCreate()
        val options = EluOptions(
            personProfiles = EluPersonProfilesMode.IDENTIFIED_ONLY,
            diagnostics = EluDiagnosticsOptions(enabled = true),
        ).apply {
            propertyDenylist = listOf("password")
            beforeSend = EluEvent.Filter { event -> event.takeUnless { it.event.startsWith("internal_") } }
        }
        Elu.setup(this, "YOUR_SITE_KEY", options)
    }
}

class Kotlin20Activity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = View(this)
        setContentView(content)
        Elu.capture("signed_up", mapOf("plan" to "pro"))
        Elu.capture("upgraded", mapOf("plan" to "team"), EluCaptureOptions(set = mapOf("plan" to "team")))
        Elu.screen("Home")
        Elu.identify("user-1", userProperties = mapOf("plan" to "pro"))
        Elu.group(type = "company", key = "acme")
        Elu.maskView(content)
        Elu.captureException(IllegalStateException("handled"), mapOf("screen" to "Home"))
        val subscription = Elu.subscribeToFeatureFlags(EluFeatureFlagSnapshot.Listener { _ -> })
        Elu.reloadFeatureFlags { subscription.cancel() }
        if (Elu.isFeatureEnabled("new-checkout") && Elu.getFeatureFlag("new-checkout") != null) Elu.flush()
        if (Elu.distinctId() == null) Elu.optIn(captureEventName = null)
    }
}
