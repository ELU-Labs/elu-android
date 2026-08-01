package dev.elu.analytics

internal object EluVersion {
    // Keep in lockstep with elu-analytics/build.gradle.kts `version`.
    const val NAME: String = "0.1.0"

    // Bumps ONLY on a facade-surface change, in lockstep with the web
    // allowlist — both are generated from CONTRACT.md.
    const val FACADE_VERSION: Int = 1
}
