package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1ImageMasking
import dev.elu.analytics.internal.config.V1MalformedConfigException
import dev.elu.analytics.internal.config.V1MaskingPolicy
import dev.elu.analytics.internal.config.V1PlatformMaskingRule
import dev.elu.analytics.internal.config.V1PlatformRuleAction
import dev.elu.analytics.internal.config.V1PrivacyPlatform
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.config.V1TextMasking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMaskingProfileTest {
    private val expectedHash = BLANKET_STAR_HASH
    private val profile = NativeMaskingProfile.blanketMask()

    @Test fun `golden profile has exact sixteen fields bytes and cross-platform hash`() {
        assertEquals(461, profile.canonicalBytes.size)
        assertEquals(expectedHash, profile.hash)
        assertEquals(NativeMaskingProfile.MaskingClass.BLANKET, profile.maskingClass)
        assertEquals(NativeMaskingProfile.MaskRule.STAR, profile.rule)
        assertFalse(profile.readsText)
        val members = objectValue().members.toMap()
        assertEquals(16, members.size)
        assertFalse(members.containsKey("targetDialect")); assertFalse(members.containsKey("maskToken"))
        assertEquals(V1StrictCanonicalJson.Value.StringValue("masked-shape"), members["contentAccess"])
        assertEquals(V1StrictCanonicalJson.Value.StringValue("star-non-whitespace-utf16"), members["textMask"])
        assertEquals(V1StrictCanonicalJson.Value.StringValue("star-per-utf16-unit"), members["inputMask"])
        assertEquals(V1StrictCanonicalJson.Value.NumberValue(NativeMaskText.MAXIMUM_BYTES.toString()), members["maskedTextMaxBytes"])
        assertSame(profile, NativeMaskingProfile.parse(profile.canonicalBytes))
        assertArrayEquals(profile.canonicalBytes, V1StrictCanonicalJson.canonicalBytes(objectValue()))
    }

    @Test fun `star profile bytes and hashes match the shared iOS contract`() {
        assertArrayEquals(BLANKET_STAR.toByteArray(), NativeMaskingProfile.blanketMask().canonicalBytes)
        assertArrayEquals(SENSITIVE_STAR.toByteArray(), NativeMaskingProfile.sensitiveMask().canonicalBytes)
        assertEquals(BLANKET_STAR_HASH, V1StrictCanonicalJson.sha256(BLANKET_STAR.toByteArray()))
        assertEquals(SENSITIVE_STAR_HASH, V1StrictCanonicalJson.sha256(SENSITIVE_STAR.toByteArray()))
        assertEquals(BLANKET_STAR_HASH, NativeMaskingProfile.blanketMask().hash)
        assertEquals(SENSITIVE_STAR_HASH, NativeMaskingProfile.sensitiveMask().hash)
    }

    @Test fun `earlier token profiles still parse by class but are never selected`() {
        val blanket = NativeMaskingProfile.parse(TOKEN_BLANKET.toByteArray())
        val sensitive = NativeMaskingProfile.parse(TOKEN_SENSITIVE.toByteArray())
        assertEquals("sha256:fdb4151f4d1525b0bcf95d16099278b9d82a1e13e5eba381a1d19c4f35084d77", blanket.hash)
        assertEquals("sha256:54e0419b953cc0b9531247c4017f4ac7d0e1eff1a72eb24c926fca00f9bb2526", sensitive.hash)
        assertEquals(372, blanket.canonicalBytes.size); assertEquals(387, sensitive.canonicalBytes.size)
        assertEquals(NativeMaskingProfile.MaskingClass.BLANKET, blanket.maskingClass)
        assertEquals(NativeMaskingProfile.MaskRule.TOKEN, blanket.rule)
        assertEquals(NativeMaskingProfile.MaskingClass.SENSITIVE, sensitive.maskingClass)
        assertEquals(NativeMaskingProfile.MaskRule.TOKEN, sensitive.rule)
        assertFalse(blanket.readsText); assertTrue(sensitive.readsText)
        assertEquals(profile.textMasking, blanket.textMasking)
        assertEquals(NativeMaskingProfile.sensitiveMask().textMasking, sensitive.textMasking)
        assertNotSame(profile, blanket); assertNotSame(NativeMaskingProfile.sensitiveMask(), sensitive)
        assertSame(blanket, NativeMaskingProfile.parse(TOKEN_BLANKET.toByteArray()))
        for (text in V1TextMasking.entries) for (rules in listOf(emptyList(), listOf(rule(V1PrivacyPlatform.ANDROID, V1PlatformRuleAction.MASK)))) {
            val selected = NativeMaskingProfile.select(policy(rules).copy(text = text), V1PrivacyPlatform.ANDROID)
            assertEquals(NativeMaskingProfile.MaskRule.STAR, selected.rule)
        }
        // Queued chunks sealed before an upgrade remain retainable under the same class rules.
        val sensitivePolicy = policy().copy(text = V1TextMasking.SENSITIVE)
        assertEquals(NativeMaskingRetention.COMPATIBLE,
            NativeMaskingProfile.retention(TOKEN_BLANKET.toByteArray(), policy(), V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingRetention.COMPATIBLE,
            NativeMaskingProfile.retention(TOKEN_SENSITIVE.toByteArray(), sensitivePolicy, V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingRetention.RESTRICTIVE_POLICY,
            NativeMaskingProfile.retention(TOKEN_SENSITIVE.toByteArray(), policy(), V1PrivacyPlatform.ANDROID))
    }

    @Test fun `mixing token and star fields is rejected`() {
        val star = objectValue().members
        rejects(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(
            star + ("maskToken" to V1StrictCanonicalJson.Value.StringValue("[masked]")))))
        val token = objectValue(TOKEN_BLANKET.toByteArray()).members
        rejects(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(
            token + ("textMask" to V1StrictCanonicalJson.Value.StringValue("star-non-whitespace-utf16")))))
        rejects(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(star.map {
            if (it.first == "maskedTextMaxBytes") it.first to V1StrictCanonicalJson.Value.NumberValue("8192") else it
        })))
    }

    @Test fun `every missing or altered field fails closed`() {
        for (bytes in allProfiles()) {
            val original = objectValue(bytes)
            original.members.forEach { (key, _) ->
                rejects(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(
                    original.members.filterNot { it.first == key },
                )))
                rejects(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(
                    original.members.map { if (it.first == key) key to V1StrictCanonicalJson.Value.StringValue("unsupported") else it },
                )))
            }
        }
    }

    @Test fun `arbitrary content targets and dialect cannot enter profile`() {
        for (key in listOf("targetDialect", "text", "url", "nativeClass", "resolvedMaskSelectors")) {
            rejects(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(
                objectValue().members + (key to V1StrictCanonicalJson.Value.StringValue("PRIVATE_UNTRUSTED_CONTENT")),
            )))
        }
    }

    @Test fun `decoded duplicate keys fail before object projection`() {
        val prefix = profile.canonicalBytes.toString(Charsets.UTF_8).dropLast(1)
        for (suffix in listOf(",\"textRule\":\"all\"}", ",\"text\\u0052ule\":\"all\"}")) {
            assertThrows(V1MalformedConfigException::class.java) { NativeMaskingProfile.parse((prefix + suffix).toByteArray()) }
        }
    }

    @Test fun `noncanonical roots numbers and whitespace are rejected`() {
        for (bytes in allProfiles()) {
            val text = bytes.toString(Charsets.UTF_8)
            val values = mutableListOf(text + "\n", " " + text, "[]", "null")
            for (number in listOf(":1,", ":2,", ":4096,")) if (text.contains(number)) {
                val digits = number.drop(1).dropLast(1)
                values += text.replace(number, ":$digits.0,"); values += text.replace(number, ":${digits}e0,")
            }
            assertTrue(values.size >= 6)
            for (value in values) {
                assertFalse(value.toByteArray().contentEquals(bytes))
                rejects(value.toByteArray())
            }
        }
    }

    @Test fun `invalid UTF8 and bounded input fail closed for parse and retention`() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xff.toByte()), ByteArray(1_025) { 32 })) {
            rejects(bytes)
            assertEquals(NativeMaskingRetention.UNRECOGNIZED_STORED_PROFILE,
                NativeMaskingProfile.retention(bytes, policy(), V1PrivacyPlatform.ANDROID))
        }
    }

    @Test fun `caller input and returned arrays cannot mutate stored bytes or hash`() {
        val input = profile.canonicalBytes
        val parsed = NativeMaskingProfile.parse(input)
        input[0] = 0
        val output = parsed.canonicalBytes
        output[1] = 0
        assertEquals(expectedHash, parsed.hash)
        assertArrayEquals(profile.canonicalBytes, parsed.canonicalBytes)
        assertEquals('{'.code.toByte(), parsed.canonicalBytes[0])
    }

    @Test fun `blanket behavior covers every closed top-level level`() {
        for (text in V1TextMasking.entries) for (inputs in V1TextMasking.entries) for (images in V1ImageMasking.entries) {
            val required = policy().copy(text = text, inputs = inputs, images = images)
            for (platform in listOf(V1PrivacyPlatform.ANDROID, V1PrivacyPlatform.IOS)) {
                assertEquals(NativeMaskingCompatibility.COMPATIBLE, profile.compatibility(required, platform))
            }
        }
    }

    @Test fun `every applicable block denies without recognizing target dialect`() {
        for (platform in listOf(V1PrivacyPlatform.ANDROID, V1PrivacyPlatform.IOS)) {
            for (dialect in listOf("elu-css-selector-v1", "elu-unknown-native-v9")) {
                val required = policy(listOf(rule(platform, V1PlatformRuleAction.BLOCK, dialect)))
                assertEquals(NativeMaskingCompatibility.UNRESOLVED_BLOCK_RULE, profile.compatibility(required, platform))
                assertEquals(NativeMaskingRetention.RESTRICTIVE_POLICY,
                    NativeMaskingProfile.retention(profile.canonicalBytes, required, platform))
            }
        }
    }

    @Test fun `mask-only and other-platform rules never claim target recognition`() {
        val required = policy(listOf(rule(V1PrivacyPlatform.ANDROID, V1PlatformRuleAction.MASK),
            rule(V1PrivacyPlatform.IOS, V1PlatformRuleAction.BLOCK)))
        assertEquals(NativeMaskingCompatibility.COMPATIBLE, profile.compatibility(required, V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingCompatibility.UNRESOLVED_BLOCK_RULE, profile.compatibility(required, V1PrivacyPlatform.IOS))
    }

    @Test fun `unavailable policy is distinct from restriction and unrecognized profile`() {
        assertEquals(NativeMaskingCompatibility.POLICY_UNAVAILABLE, profile.compatibility(null, V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingCompatibility.UNSUPPORTED_PLATFORM, profile.compatibility(policy(), V1PrivacyPlatform.BROWSER))
        assertEquals(NativeMaskingRetention.POLICY_UNAVAILABLE,
            NativeMaskingProfile.retention(byteArrayOf(), null, V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingRetention.COMPATIBLE,
            NativeMaskingProfile.retention(profile.canonicalBytes, policy(), V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingRetention.UNRECOGNIZED_STORED_PROFILE,
            NativeMaskingProfile.retention("{}".toByteArray(), policy(), V1PrivacyPlatform.ANDROID))
    }

    @Test fun `sensitive profile has canonical cross-platform bytes and is selected only without native rules`() {
        val sensitive = NativeMaskingProfile.sensitiveMask()
        assertEquals(468, sensitive.canonicalBytes.size)
        assertEquals(SENSITIVE_STAR_HASH, sensitive.hash)
        assertTrue(sensitive.readsText)
        assertEquals(NativeMaskingProfile.MaskingClass.SENSITIVE, sensitive.maskingClass)
        assertEquals(NativeMaskingProfile.MaskRule.STAR, sensitive.rule)
        assertSame(sensitive, NativeMaskingProfile.parse(sensitive.canonicalBytes))
        val required = policy().copy(text = V1TextMasking.SENSITIVE)
        assertSame(sensitive, NativeMaskingProfile.select(required, V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingCompatibility.COMPATIBLE, sensitive.compatibility(required, V1PrivacyPlatform.ANDROID))
        val withMask = required.copy(platformRules = listOf(rule(V1PrivacyPlatform.ANDROID, V1PlatformRuleAction.MASK)))
        assertSame(profile, NativeMaskingProfile.select(withMask, V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingCompatibility.RESTRICTIVE_POLICY, sensitive.compatibility(withMask, V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingRetention.RESTRICTIVE_POLICY,
            NativeMaskingProfile.retention(sensitive.canonicalBytes, policy(), V1PrivacyPlatform.ANDROID))
        assertEquals(NativeMaskingRetention.COMPATIBLE,
            NativeMaskingProfile.retention(profile.canonicalBytes, required, V1PrivacyPlatform.ANDROID))
    }

    private fun allProfiles() = listOf(profile.canonicalBytes, NativeMaskingProfile.sensitiveMask().canonicalBytes,
        TOKEN_BLANKET.toByteArray(), TOKEN_SENSITIVE.toByteArray())
    private fun objectValue(bytes: ByteArray = profile.canonicalBytes) = V1StrictCanonicalJson.parse(bytes.toString(Charsets.UTF_8)) as
        V1StrictCanonicalJson.Value.ObjectValue
    private fun rejects(bytes: ByteArray) { assertThrows(Exception::class.java) { NativeMaskingProfile.parse(bytes) } }
    private fun policy(rules: List<V1PlatformMaskingRule> = emptyList()) =
        V1MaskingPolicy(V1TextMasking.ALL, V1TextMasking.ALL, V1ImageMasking.BLOCK, true, rules)
    private fun rule(platform: V1PrivacyPlatform, action: V1PlatformRuleAction, dialect: String = "elu-unknown-native-v9") =
        V1PlatformMaskingRule(platform, action, dialect, "opaque-private-target")

    companion object {
        const val BLANKET_STAR_HASH = "sha256:f6e340ee9a350f07e3645dc5e30bc6383e27d0bb42b94e08d552c26a52418281"
        const val SENSITIVE_STAR_HASH = "sha256:3aae84c0ddeb5414d509433eb6285532bd90eea846a246787b9622c64d32da04"
        const val BLANKET_STAR = """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"masked-shape","imageRule":"block","inputMask":"star-per-utf16-unit","inputRule":"all","maskInheritance":"subtree","maskedTextMaxBytes":4096,"opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-blanket-star-mask-v1","schemaVersion":2,"secureInputsMasked":true,"textMask":"star-non-whitespace-utf16","textRule":"all","webViewRule":"block"}"""
        const val SENSITIVE_STAR = """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"native-text","imageRule":"block","inputMask":"star-per-utf16-unit","inputRule":"all","maskInheritance":"subtree","maskedTextMaxBytes":4096,"opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-sensitive-star-mask-v1","schemaVersion":2,"secureInputsMasked":true,"textMask":"star-non-whitespace-utf16","textRule":"sensitive","webViewRule":"block"}"""
        /** Exact bytes sealed by earlier SDK versions; queued chunks still carry them. */
        const val TOKEN_BLANKET = """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"none","imageRule":"block","inputRule":"all","maskInheritance":"subtree","maskToken":"[masked]","opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-blanket-mask-v1","schemaVersion":1,"secureInputsMasked":true,"textRule":"all","webViewRule":"block"}"""
        const val TOKEN_SENSITIVE = """{"blockTraversal":"stop","configuredBlockRuleHandling":"deny-unresolved","contentAccess":"native-text","imageRule":"block","inputRule":"all","maskInheritance":"subtree","maskToken":"[masked]","opaqueViewRule":"block","placeholderToken":"Content hidden","profileKind":"elu-native-sensitive-mask-v1","schemaVersion":1,"secureInputsMasked":true,"textRule":"sensitive","webViewRule":"block"}"""
    }
}
