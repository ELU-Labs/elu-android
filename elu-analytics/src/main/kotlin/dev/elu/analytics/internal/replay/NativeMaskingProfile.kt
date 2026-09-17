package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1MaskingPolicy
import dev.elu.analytics.internal.config.V1PlatformRuleAction
import dev.elu.analytics.internal.config.V1PrivacyPlatform
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Compatibility metadata never grants source, recorder, channel or storage authority. */
internal enum class NativeMaskingCompatibility {
    COMPATIBLE,
    POLICY_UNAVAILABLE,
    UNSUPPORTED_PLATFORM,
    UNRESOLVED_BLOCK_RULE,
}

/** An unavailable policy is distinct from a restrictive current policy; no result purges rows. */
internal enum class NativeMaskingRetention {
    COMPATIBLE,
    POLICY_UNAVAILABLE,
    UNRECOGNIZED_STORED_PROFILE,
    RESTRICTIVE_POLICY,
}

/** Fixed native masking behavior, without arbitrary content, targets or authority fields. */
internal class NativeMaskingProfile private constructor() {
    val canonicalBytes: ByteArray get() = frozenBytes.copyOf()
    val hash: String get() = frozenHash

    /** The policy must have passed the original config validator. No target dialect is interpreted. */
    fun compatibility(policy: V1MaskingPolicy?, platform: V1PrivacyPlatform): NativeMaskingCompatibility {
        if (policy == null) return NativeMaskingCompatibility.POLICY_UNAVAILABLE
        if (platform != V1PrivacyPlatform.ANDROID && platform != V1PrivacyPlatform.IOS) {
            return NativeMaskingCompatibility.UNSUPPORTED_PLATFORM
        }
        if (policy.platformRules.any { it.platform == platform && it.action == V1PlatformRuleAction.BLOCK }) {
            return NativeMaskingCompatibility.UNRESOLVED_BLOCK_RULE
        }
        // All text/inputs masked and all images blocked satisfy every closed top-level level.
        return NativeMaskingCompatibility.COMPATIBLE
    }

    companion object {
        const val MAXIMUM_BYTES: Int = 1_024
        private val frozenBytes =
            """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"none","imageRule":"block","inputRule":"all","maskInheritance":"subtree","maskToken":"[masked]","opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-blanket-mask-v1","schemaVersion":1,"secureInputsMasked":true,"textRule":"all","webViewRule":"block"}"""
                .toByteArray(StandardCharsets.UTF_8)
        private val frozenHash = V1StrictCanonicalJson.sha256(frozenBytes)
        private val blanket = NativeMaskingProfile()

        /** Description of the implemented subset, never permission to collect it. */
        fun blanketMask(): NativeMaskingProfile = blanket

        fun parse(bytes: ByteArray): NativeMaskingProfile {
            require(bytes.size in 1..MAXIMUM_BYTES) { "Native masking profile size is invalid" }
            val owned = bytes.copyOf()
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(owned)).toString()
            val document = V1StrictCanonicalJson.parse(text)
            require(document is V1StrictCanonicalJson.Value.ObjectValue &&
                V1StrictCanonicalJson.canonicalBytes(document).contentEquals(owned) &&
                owned.contentEquals(frozenBytes)) { "Native masking profile is unrecognized" }
            // Neither caller bytes nor parser-owned buffers become retained profile storage.
            return blanket
        }

        fun retention(
            storedBytes: ByteArray,
            requiredPolicy: V1MaskingPolicy?,
            platform: V1PrivacyPlatform,
        ): NativeMaskingRetention {
            if (requiredPolicy == null) return NativeMaskingRetention.POLICY_UNAVAILABLE
            val profile = try {
                parse(storedBytes)
            } catch (_: IllegalArgumentException) {
                return NativeMaskingRetention.UNRECOGNIZED_STORED_PROFILE
            } catch (_: java.nio.charset.CharacterCodingException) {
                return NativeMaskingRetention.UNRECOGNIZED_STORED_PROFILE
            }
            return when (profile.compatibility(requiredPolicy, platform)) {
                NativeMaskingCompatibility.COMPATIBLE -> NativeMaskingRetention.COMPATIBLE
                NativeMaskingCompatibility.POLICY_UNAVAILABLE -> NativeMaskingRetention.POLICY_UNAVAILABLE
                NativeMaskingCompatibility.UNSUPPORTED_PLATFORM,
                NativeMaskingCompatibility.UNRESOLVED_BLOCK_RULE -> NativeMaskingRetention.RESTRICTIVE_POLICY
            }
        }
    }
}
