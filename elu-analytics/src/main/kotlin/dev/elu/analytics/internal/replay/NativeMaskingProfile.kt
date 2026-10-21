package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1MaskingPolicy
import dev.elu.analytics.internal.config.V1TextMasking
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
    RESTRICTIVE_POLICY,
}

/** An unavailable policy is distinct from a restrictive current policy; no result purges rows. */
internal enum class NativeMaskingRetention {
    COMPATIBLE,
    POLICY_UNAVAILABLE,
    UNRECOGNIZED_STORED_PROFILE,
    RESTRICTIVE_POLICY,
}

/** Fixed native masking behavior, without arbitrary content, targets or authority fields. */
internal class NativeMaskingProfile private constructor(
    val maskingClass: MaskingClass,
    val rule: MaskRule,
    private val bytes: ByteArray,
) {
    /** Exact class equality is the only substitution the service accepts. */
    enum class MaskingClass { BLANKET, SENSITIVE }

    /** STAR describes current capture: length-preserving `*` masks. TOKEN describes chunks sealed by earlier versions; nothing new uses it. */
    enum class MaskRule { TOKEN, STAR }

    val readsText: Boolean get() = maskingClass == MaskingClass.SENSITIVE
    val canonicalBytes: ByteArray get() = bytes.copyOf()
    val hash: String = V1StrictCanonicalJson.sha256(bytes)
    val textMasking: V1TextMasking get() = if (readsText) V1TextMasking.SENSITIVE else V1TextMasking.ALL

    /** The policy must have passed the original config validator. No target dialect is interpreted. */
    fun compatibility(policy: V1MaskingPolicy?, platform: V1PrivacyPlatform): NativeMaskingCompatibility {
        if (policy == null) return NativeMaskingCompatibility.POLICY_UNAVAILABLE
        if (platform != V1PrivacyPlatform.ANDROID && platform != V1PrivacyPlatform.IOS) {
            return NativeMaskingCompatibility.UNSUPPORTED_PLATFORM
        }
        if (policy.platformRules.any { it.platform == platform && it.action == V1PlatformRuleAction.BLOCK }) {
            return NativeMaskingCompatibility.UNRESOLVED_BLOCK_RULE
        }
        if (readsText && (policy.text != V1TextMasking.SENSITIVE || policy.platformRules.any { it.platform == platform })) {
            return NativeMaskingCompatibility.RESTRICTIVE_POLICY
        }
        // Inputs stay masked and images and opaque views stay blocked in every profile.
        return NativeMaskingCompatibility.COMPATIBLE
    }

    companion object {
        const val MAXIMUM_BYTES: Int = 1_024
        private fun bytes(value: String) = value.toByteArray(StandardCharsets.UTF_8)
        private val blanketTokenBytes = bytes(
            """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"none","imageRule":"block","inputRule":"all","maskInheritance":"subtree","maskToken":"[masked]","opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-blanket-mask-v1","schemaVersion":1,"secureInputsMasked":true,"textRule":"all","webViewRule":"block"}""")
        private val sensitiveTokenBytes = bytes(
            """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"native-text","imageRule":"block","inputRule":"all","maskInheritance":"subtree","maskToken":"[masked]","opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-sensitive-mask-v1","schemaVersion":1,"secureInputsMasked":true,"textRule":"sensitive","webViewRule":"block"}""")
        private val blanketStarBytes = bytes(
            """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"masked-shape","imageRule":"block","inputMask":"star-per-utf16-unit","inputRule":"all","maskInheritance":"subtree","maskedTextMaxBytes":4096,"opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-blanket-star-mask-v1","schemaVersion":2,"secureInputsMasked":true,"textMask":"star-non-whitespace-utf16","textRule":"all","webViewRule":"block"}""")
        private val sensitiveStarBytes = bytes(
            """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"native-text","imageRule":"block","inputMask":"star-per-utf16-unit","inputRule":"all","maskInheritance":"subtree","maskedTextMaxBytes":4096,"opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-sensitive-star-mask-v1","schemaVersion":2,"secureInputsMasked":true,"textMask":"star-non-whitespace-utf16","textRule":"sensitive","webViewRule":"block"}""")
        private val blanket = NativeMaskingProfile(MaskingClass.BLANKET, MaskRule.STAR, blanketStarBytes)
        private val sensitive = NativeMaskingProfile(MaskingClass.SENSITIVE, MaskRule.STAR, sensitiveStarBytes)
        private val blanketToken = NativeMaskingProfile(MaskingClass.BLANKET, MaskRule.TOKEN, blanketTokenBytes)
        private val sensitiveToken = NativeMaskingProfile(MaskingClass.SENSITIVE, MaskRule.TOKEN, sensitiveTokenBytes)
        private val known = listOf(blanket, sensitive, blanketToken, sensitiveToken)

        /** Description of the implemented subset, never permission to collect it. Both describe current `*` capture. */
        fun blanketMask(): NativeMaskingProfile = blanket
        fun sensitiveMask(): NativeMaskingProfile = sensitive

        fun select(policy: V1MaskingPolicy, platform: V1PrivacyPlatform): NativeMaskingProfile =
            if (policy.text == V1TextMasking.SENSITIVE && policy.platformRules.none { it.platform == platform }) sensitive else blanket

        /** Accepts all four closed documents, so chunks sealed before an upgrade keep their original description and remain deliverable. */
        fun parse(bytes: ByteArray): NativeMaskingProfile {
            require(bytes.size in 1..MAXIMUM_BYTES) { "Native masking profile size is invalid" }
            val owned = bytes.copyOf()
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(owned)).toString()
            val document = V1StrictCanonicalJson.parse(text)
            require(document is V1StrictCanonicalJson.Value.ObjectValue &&
                V1StrictCanonicalJson.canonicalBytes(document).contentEquals(owned)) { "Native masking profile is unrecognized" }
            // Neither caller bytes nor parser-owned buffers become retained profile storage.
            return requireNotNull(known.firstOrNull { it.bytes.contentEquals(owned) }) { "Native masking profile is unrecognized" }
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
                NativeMaskingCompatibility.UNRESOLVED_BLOCK_RULE,
                NativeMaskingCompatibility.RESTRICTIVE_POLICY -> NativeMaskingRetention.RESTRICTIVE_POLICY
            }
        }
    }
}
