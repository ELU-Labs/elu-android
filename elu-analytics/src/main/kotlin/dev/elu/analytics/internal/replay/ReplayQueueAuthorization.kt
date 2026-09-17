package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1AuthorizedConfig

/**
 * Supplied once by a reviewed platform policy/producer join. The owner always passes the actual
 * transaction-current resolved configuration, including effective privacy. A profile's self-hash
 * cannot establish this relation. No canonical Android composition supplies an implementation.
 */
internal fun interface ReplayMaskingAdmission {
    fun mayCapture(profile: ReplayMaskingProfile, required: V1AuthorizedConfig): Boolean
}
