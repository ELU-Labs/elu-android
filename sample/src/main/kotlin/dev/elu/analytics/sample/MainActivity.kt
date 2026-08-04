package dev.elu.analytics.sample

import android.app.Activity
import android.os.Bundle
import dev.elu.analytics.Elu

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exercisePublicFacade()
    }

    private fun exercisePublicFacade() {
        Elu.capture("sample_opened", mapOf("source" to "android-sample"))
        Elu.identify("sample-user", mapOf("plan" to "development"))
        Elu.screen("SampleHome")
        Elu.alias("sample-alias")
        Elu.register(mapOf("sample" to true))
        Elu.unregister("sample")
        Elu.setPersonProperties(mapOf("role" to "developer"))
        Elu.group("organization", "sample-org", mapOf("tier" to "development"))
        Elu.captureException(IllegalStateException("sample-handled"), mapOf("handled" to true))
        Elu.setPersonPropertiesForFlags(mapOf("role" to "developer"))
        Elu.setGroupPropertiesForFlags("organization", mapOf("tier" to "development"))
        Elu.getFeatureFlag("sample-flag")
        Elu.getFeatureFlagPayload("sample-flag")
        Elu.isFeatureEnabled("sample-flag")
        Elu.onFeatureFlagsLoaded { /* update sample UI */ }
        Elu.reloadFeatureFlags()
        Elu.distinctId()
        Elu.flush()
        Elu.reset()
    }
}
