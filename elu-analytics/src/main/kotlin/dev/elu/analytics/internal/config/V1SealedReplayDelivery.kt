package dev.elu.analytics.internal.config

import java.net.URI

/** Validated current policy for already sealed rows; the nested fresh result is not upgraded. */
internal data class V1SealedReplayDelivery(
    val config: V1AuthorizedConfig,
    val endpoint: URI,
    val transport: V1ReplayTransport,
    val protocolGeneration: String,
)
