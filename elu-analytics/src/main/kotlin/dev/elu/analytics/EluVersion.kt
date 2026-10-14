package dev.elu.analytics

internal object EluVersion {
    // Maven coordinates read this version directly.
    const val NAME: String = "0.2.0"

    // Existing facade protocol marker; additive APIs retain the same wire contract.
    const val FACADE_VERSION: Int = 1
}
