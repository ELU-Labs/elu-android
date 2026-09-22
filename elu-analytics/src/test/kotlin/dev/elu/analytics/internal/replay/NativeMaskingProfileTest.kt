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
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeMaskingProfileTest {
    private val expectedHash = "sha256:fdb4151f4d1525b0bcf95d16099278b9d82a1e13e5eba381a1d19c4f35084d77"
    private val profile = NativeMaskingProfile.blanketMask()

    @Test fun `golden profile has exact fourteen fields bytes and cross-platform hash`() {
        assertEquals(372, profile.canonicalBytes.size)
        assertEquals(expectedHash, profile.hash)
        assertEquals(14, objectValue().members.size)
        assertFalse(objectValue().members.any { it.first == "targetDialect" })
        assertSame(profile, NativeMaskingProfile.parse(profile.canonicalBytes))
        assertArrayEquals(profile.canonicalBytes, V1StrictCanonicalJson.canonicalBytes(objectValue()))
    }

    @Test fun `every missing or altered field fails closed`() {
        val original = objectValue()
        original.members.forEach { (key, _) ->
            rejects(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(
                original.members.filterNot { it.first == key },
            )))
            rejects(V1StrictCanonicalJson.canonicalBytes(V1StrictCanonicalJson.Value.ObjectValue(
                original.members.map { if (it.first == key) key to V1StrictCanonicalJson.Value.StringValue("unsupported") else it },
            )))
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
        val text = profile.canonicalBytes.toString(Charsets.UTF_8)
        for (value in listOf(text + "\n", " " + text, text.replace(":1", ":1.0"), text.replace(":1", ":1e0"), "[]", "null")) {
            rejects(value.toByteArray())
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
        assertEquals(387, sensitive.canonicalBytes.size)
        assertEquals("sha256:54e0419b953cc0b9531247c4017f4ac7d0e1eff1a72eb24c926fca00f9bb2526", sensitive.hash)
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

    private fun objectValue() = V1StrictCanonicalJson.parse(profile.canonicalBytes.toString(Charsets.UTF_8)) as
        V1StrictCanonicalJson.Value.ObjectValue
    private fun rejects(bytes: ByteArray) { assertThrows(Exception::class.java) { NativeMaskingProfile.parse(bytes) } }
    private fun policy(rules: List<V1PlatformMaskingRule> = emptyList()) =
        V1MaskingPolicy(V1TextMasking.ALL, V1TextMasking.ALL, V1ImageMasking.BLOCK, true, rules)
    private fun rule(platform: V1PrivacyPlatform, action: V1PlatformRuleAction, dialect: String = "elu-unknown-native-v9") =
        V1PlatformMaskingRule(platform, action, dialect, "opaque-private-target")
}
