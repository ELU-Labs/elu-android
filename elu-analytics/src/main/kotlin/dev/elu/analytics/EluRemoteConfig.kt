package dev.elu.analytics

import org.json.JSONObject

/**
 * Privacy semantics mirror `ResolvedPrivacyConfig` in the web loader.
 * Per-field parse failure falls back to the web defaults (blockEu +
 * maskTextInputs true, everything else off/0) — NOT to all-tight.
 */
internal data class EluPrivacyConfig(
    val blockEu: Boolean,
    val maskTextInputs: Boolean,
    val maskAllText: Boolean,
    val maskImages: Boolean,
    val replayNewUsersOnly: Boolean,
    val replayMaxMinutes: Int,
) {
    companion object {
        val DEFAULTS =
            EluPrivacyConfig(
                blockEu = true,
                maskTextInputs = true,
                maskAllText = false,
                maskImages = false,
                replayNewUsersOnly = false,
                replayMaxMinutes = 0,
            )
    }
}

internal data class EluRemoteConfig(
    val schemaVersion: Int,
    val enabled: Boolean,
    val publicToken: String? = null,
    val host: String? = null,
    val privacy: EluPrivacyConfig = EluPrivacyConfig.DEFAULTS,
) {
    companion object {
        /**
         * Tolerant parse. Returns null for anything unusable — the caller MUST
         * treat null as a fetch failure (keep cache / stay pending), never as
         * `enabled:false`. An enabled response missing token or host is
         * unusable, hence also null. Unknown fields are ignored.
         */
        fun parse(body: String?): EluRemoteConfig? {
            if (body.isNullOrBlank()) return null
            val root =
                try {
                    JSONObject(body)
                } catch (t: Throwable) {
                    return null
                }
            val rawVersion = root.opt("v") as? Number ?: return null
            if (rawVersion.toDouble() != SUPPORTED_SCHEMA_VERSION.toDouble()) return null
            val enabled = root.opt("enabled") as? Boolean ?: return null
            if (!enabled) {
                return EluRemoteConfig(
                    schemaVersion = SUPPORTED_SCHEMA_VERSION,
                    enabled = false,
                )
            }

            val token = (root.opt("publicToken") as? String)?.trim()
            val host = (root.opt("host") as? String)?.trim()
            if (token.isNullOrEmpty() || host.isNullOrEmpty()) return null

            return EluRemoteConfig(
                schemaVersion = SUPPORTED_SCHEMA_VERSION,
                enabled = true,
                publicToken = token,
                host = host,
                privacy = parsePrivacy(root.opt("privacy")),
            )
        }

        private const val SUPPORTED_SCHEMA_VERSION = 1

        private fun parsePrivacy(node: Any?): EluPrivacyConfig {
            val obj = node as? JSONObject ?: return EluPrivacyConfig.DEFAULTS
            val d = EluPrivacyConfig.DEFAULTS
            // Web parity (privacyConfig.ts): out-of-range means UNLIMITED (0),
            // never a silent clamp to the cap.
            val rawMinutes = (obj.opt("replayMaxMinutes") as? Number)?.toInt() ?: d.replayMaxMinutes
            val minutes = if (rawMinutes < 0 || rawMinutes > 60) 0 else rawMinutes
            return EluPrivacyConfig(
                blockEu = obj.opt("blockEu") as? Boolean ?: d.blockEu,
                maskTextInputs = obj.opt("maskTextInputs") as? Boolean ?: d.maskTextInputs,
                maskAllText = obj.opt("maskAllText") as? Boolean ?: d.maskAllText,
                maskImages = obj.opt("maskImages") as? Boolean ?: d.maskImages,
                replayNewUsersOnly = obj.opt("replayNewUsersOnly") as? Boolean ?: d.replayNewUsersOnly,
                replayMaxMinutes = minutes,
            )
        }
    }
}
