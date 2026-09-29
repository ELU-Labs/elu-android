package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1ReplayCompression
import dev.elu.analytics.internal.config.V1ReplayTransport

/** Closed format identities only. A match grants no source, capture or durable permission. */
internal enum class NativeReplayProtocol(val codec: String, val generation: String, val chunkDomain: String) {
    V1("elu-native-wireframe-v1", "protocol-generation-v1", "elu-native-replay-chunk-v1"),
    V2("elu-native-wireframe-v2", "protocol-generation-v2", "elu-native-replay-chunk-v2");

    val transport: V1ReplayTransport get() = V1ReplayTransport(codec, V1ReplayCompression.GZIP)

    companion object {
        fun match(transport: V1ReplayTransport?, generation: String?): NativeReplayProtocol? =
            values().firstOrNull { it.transport == transport && it.generation == generation }

        /** Structural dispatch only; both native formats require the original native admission. */
        fun generationBindings(): Map<V1ReplayTransport, String> =
            java.util.Collections.unmodifiableMap(values().associate { it.transport to it.generation })

        fun isNativeCodec(codec: String): Boolean = values().any { it.codec == codec }
    }
}
