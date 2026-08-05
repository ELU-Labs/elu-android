package dev.elu.analytics.internal.config

import dev.elu.analytics.internal.core.IdentityState
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V1ConfigManagerTest {
    @Test
    fun `canonical contract snapshots remain byte exact and digest pinned`() {
        val expected =
            mapOf(
                "contracts/v1/manifest.json" to "98152d8725c286f29402ba3e420bda8dd364200fb6fdf1cfe49b2da9b8f63e54",
                "contracts/v1/schemas/config.schema.json" to "4cd1e8fce0298048ec60ded16f9a215d10dc1022477f059e01db0349ec478307",
                "contracts/v1/schemas/event.schema.json" to "4a0deeb19b8406d31aa519bf1d3978294d6d06eb451d4588668cb6f67f4edee9",
                "contracts/v1/schemas/mutation.schema.json" to "8482af6b66c04701b014acd27e6d59aaef3f27864086c755f9abde498e5c8f5f",
                "contracts/v1/schemas/privacy-policy.schema.json" to "73beb1856358f5e3cc45b225fdf0608294124e6d6f7c13e2ec3db1c285db6fc4",
                "contracts/v1/schemas/privacy.schema.json" to "830726002dce98eafce30981067ea892afe12db2b985296514a8da3597776b14",
                "contracts/v1/schemas/version.schema.json" to "3b4ca74e470efbf6610f2a1743f2bc78805882bd294a6984ef2c93fe42fea4ab",
                "contracts/v1/fixtures/config-enabled.json" to "91be45589959f53c73a78f916f5e722b77a853fa0a6b952601400bf107b591e5",
                "contracts/v1/fixtures/config-disabled.json" to "c60d32c9701ea726cac342a8c06b89d9a6bd0cf6cea3441bcdd37c2de9055270",
                "contracts/v1/fixtures/batch-request.json" to "c0446316c5b75c163b27e27abe7b079b1c88bdb198a4985f4ceb544a8d154bbb",
                "contracts/v1/fixtures/event.json" to "44ea5d14646ec08aaa1805dffd8ea6403487ba7cb48e8ce7ea7f3752b241809d",
                "contracts/v1/fixtures/mutations.json" to "a02a4db1d1ef0bf6b9eac0334fe83c3564526cbd21c79ccfe14aa303ff2ae3d4",
                "contracts/v1/fixtures/privacy-allowed.json" to "a0fa41fbb06f263510b35c8d27863e8c69ecc52c940eac8fa23bdd4411c68f40",
                "contracts/v1/fixtures/privacy-blocked.json" to "503a2d118737bff7206f05c3cb83f4098579a5c92e7f49670b80e86df2e1e24d",
                "contracts/v1/fixtures/version.json" to "61bf97e8eeea78df05df13434501a4bc9e81eaa3351fecaff2bdc06da9f1f8e2",
            )

        expected.forEach { (path, digest) -> assertEquals(path, digest, sha256(resourceBytes(path))) }
        val digestFile =
            resourceText("contracts/v1/SHA256SUMS")
                .lineSequence()
                .filter { it.isNotBlank() && !it.startsWith('#') }
                .associate { line ->
                    val (digest, path) = line.trim().split(Regex("\\s+"), limit = 2)
                    "contracts/v1/$path" to digest
                }
        assertEquals(expected, digestFile)

        val manifest = JSONObject(resourceText("contracts/v1/manifest.json"))
        assertEquals("1.0.0", manifest.getString("contractVersion"))
        assertEquals("frozen", manifest.getString("status"))
        assertEquals("specified-not-wired", manifest.getJSONObject("transport").getString("status"))
        assertEquals("schemas/config.schema.json", manifest.getJSONObject("schemas").getString("config"))
        assertEquals("schemas/privacy-policy.schema.json", manifest.getJSONObject("schemas").getString("privacyPolicy"))
        assertEquals("schemas/privacy.schema.json", manifest.getJSONObject("schemas").getString("privacyState"))
    }

    @Test
    fun `canonical browser-oriented evidence authorizes Android capture but not replay or assets by default`() {
        val manager = V1ConfigManager()
        assertEnabled(manager.install(canonicalEnabledConfig().toString(), NOW_MS))

        val config = assertAuthorized(manager.authorize(canonicalAllowedPrivacy().toString(), identity(5), NOW_MS))

        assertEquals("https://ingest.elu.dev/v1/events", config.endpoints.events.toString())
        assertNull(config.endpoints.replay)
        assertEquals("https://ingest.elu.dev/v1/flags", config.endpoints.flags.toString())
        assertNull(config.endpoints.assets)
        assertNull(config.negotiatedReplayTransport)
    }

    @Test
    fun `missing privacy suppresses capture and replay but retains flags with machine-readable reasons`() {
        val manager = V1ConfigManager()
        assertEnabled(manager.install(enabledConfig().toString(), NOW_MS))

        val config = assertAuthorized(manager.authorize(null, identity(5), NOW_MS))

        assertNull(config.endpoints.events)
        assertNull(config.endpoints.replay)
        assertTrue(config.endpoints.flags != null)
        assertNull(config.endpoints.assets)
        assertNull(config.effectivePrivacy)
        assertEquals(V1ChannelAuthorizationStatus.INVALID, config.captureAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.PRIVACY_STATE_MISSING, config.captureAuthorization.reason)
        assertEquals(config.captureAuthorization, config.replayAuthorization)
    }

    @Test
    fun `readback-proven exact pair plus native fallback authorizes replay and returns negotiation`() {
        val manager = managerWithReplay()
        val config = authorized(enabledConfig(), androidAllowedPrivacy(), identity(5), manager = manager)

        assertEquals(1, config.schemaVersion)
        assertEquals("config-2026-08-04-1", config.revision)
        assertEquals("site_demo", config.siteId)
        assertEquals("https://ingest.elu.dev/v1/events", config.endpoints.events.toString())
        assertEquals("https://ingest.elu.dev/v1/replay", config.endpoints.replay.toString())
        assertEquals("https://ingest.elu.dev/v1/flags", config.endpoints.flags.toString())
        assertNull(config.endpoints.assets)
        assertEquals(PROVEN_REPLAY, config.negotiatedReplayTransport)
        assertEquals(0.25, config.privacy.replay.sampleRate, 0.0)
        assertEquals(1_800, config.session.idleTimeoutSeconds)
        assertEquals(86_400, config.session.maximumDurationSeconds)
        assertEquals(16_777_216, config.limits.queueBytes)
    }

    @Test
    fun `empty local readback set restricts replay without suppressing capture or flags`() {
        val config = authorized(enabledConfig(), androidAllowedPrivacy(), identity(5), manager = V1ConfigManager())

        assertTrue(config.endpoints.events != null)
        assertNull(config.endpoints.replay)
        assertTrue(config.endpoints.flags != null)
        assertEquals(V1ChannelAuthorizationStatus.AUTHORIZED, config.captureAuthorization.status)
        assertEquals(V1ChannelAuthorizationStatus.RESTRICTED, config.replayAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.LOCAL_TRANSPORT_UNPROVEN, config.replayAuthorization.reason)
        assertNull(config.negotiatedReplayTransport)
    }

    @Test
    fun `replay support is copied at construction and is exact-pair not cartesian`() {
        val mutableSupport = linkedSetOf(PROVEN_REPLAY)
        val frozenManager = V1ConfigManager(mutableSupport)
        mutableSupport.clear()
        assertTrue(authorized(enabledConfig(), androidAllowedPrivacy(), identity(5), manager = frozenManager).endpoints.replay != null)

        val config =
            enabledConfig().apply {
                getJSONObject("capabilities").getJSONObject("replay").apply {
                    put("acceptedCodecs", JSONArray(listOf(PROVEN_REPLAY.codec, "elu-android-other-v1")))
                    put("acceptedCompressions", JSONArray(listOf("gzip", "none")))
                }
            }
        val privacy =
            androidAllowedPrivacy().apply {
                getJSONObject("replayTransport").apply {
                    put("codec", "elu-android-other-v1")
                    put("compression", "gzip")
                }
                rehash(this)
            }
        val onlyDifferentExactPair =
            V1ConfigManager(setOf(V1ReplayTransport(PROVEN_REPLAY.codec, V1ReplayCompression.NONE)))
        val authorized = authorized(config, privacy, identity(5), manager = onlyDifferentExactPair)
        assertNull(authorized.endpoints.replay)
        assertNull(authorized.negotiatedReplayTransport)
    }

    @Test
    fun `disabled and revoked config expose no authority without privacy state`() {
        assertRejected(disabledConfig(), null, identity(), V1ConfigRejection.INACTIVE)
        val revoked = disabledConfig().put("status", "revoked")
        assertRejected(revoked, null, identity(), V1ConfigRejection.INACTIVE)
    }

    @Test
    fun `expiry is inclusive while issuedAt remains ordering metadata rather than a not-before gate`() {
        val config = enabledConfig()
        assertRejected(config, androidAllowedPrivacy(), identity(5), V1ConfigRejection.EXPIRED, EXPIRES_AT_MS)
        authorized(config, androidAllowedPrivacy(), identity(5), EXPIRES_AT_MS - 1)

        val subMillisecondExpiry = config.put("expiresAt", "2026-08-04T00:05:00.0001Z")
        authorized(subMillisecondExpiry, androidAllowedPrivacy(), identity(5), EXPIRES_AT_MS)
        assertRejected(
            subMillisecondExpiry,
            androidAllowedPrivacy(),
            identity(5),
            V1ConfigRejection.EXPIRED,
            EXPIRES_AT_MS + 1,
        )

        config.put("issuedAt", "2026-08-04T00:04:59.999Z")
        authorized(config, androidAllowedPrivacy(), identity(5), ISSUED_AT_MS)
    }

    @Test
    fun `validity windows require strict timestamps positive duration and real leap boundaries`() {
        listOf(
            "2026-08-04T00:05:00.000Z" to "2026-08-04T00:05:00.000Z",
            "2026-08-04T00:05:00.001Z" to "2026-08-04T00:05:00.000Z",
            "2026-08-04T00:00:00.0002Z" to "2026-08-04T00:00:00.0001Z",
            "2026-02-30T00:00:00.000Z" to "2026-08-04T00:05:00.000Z",
            "2026-08-04 00:00:00Z" to "2026-08-04T00:05:00.000Z",
            "2026-06-30T12:34:60Z" to "2026-07-01T00:00:00Z",
            "2026-12-31T23:59:61Z" to "2027-01-01T00:00:00Z",
        ).forEach { (issuedAt, expiresAt) ->
            val malformed = enabledConfig().put("issuedAt", issuedAt).put("expiresAt", expiresAt)
            assertRejected(malformed, androidAllowedPrivacy(), identity(5), V1ConfigRejection.MALFORMED)
        }

        val offset =
            enabledConfig()
                .put("issuedAt", "2026-08-03T17:00:00.000-07:00")
                .put("expiresAt", "2026-08-03T17:05:00.000-07:00")
        authorized(offset, androidAllowedPrivacy(), identity(5))

        val yearZero =
            enabledConfig()
                .put("issuedAt", "0000-01-01T00:00:00Z")
                .put("expiresAt", "0000-01-01T00:05:00Z")
        authorized(
            yearZero,
            androidAllowedPrivacy(),
            identity(5),
            Instant.parse("0000-01-01T00:01:00Z").toEpochMilli(),
        )

        val shiftedLeap =
            enabledConfig()
                .put("issuedAt", "1990-12-31T15:59:60-08:00")
                .put("expiresAt", "1990-12-31T16:00:00-08:00")
        authorized(
            shiftedLeap,
            androidAllowedPrivacy(),
            identity(5),
            Instant.parse("1990-12-31T23:59:59.999Z").toEpochMilli(),
        )

        val leapBeforeMidnight =
            enabledConfig()
                .put("issuedAt", "2016-12-31T23:59:60.999Z")
                .put("expiresAt", "2017-01-01T00:00:00Z")
        authorized(
            leapBeforeMidnight,
            androidAllowedPrivacy(),
            identity(5),
            Instant.parse("2016-12-31T23:59:59.999Z").toEpochMilli(),
        )

        listOf(
            Triple("2030-06-30T23:59:60Z", "2030-07-01T00:00:00Z", "2030-06-30T23:59:59.999Z"),
            Triple("2030-12-31T23:59:60Z", "2031-01-01T00:00:00Z", "2030-12-31T23:59:59.999Z"),
        ).forEach { (issuedAt, expiresAt, now) ->
            authorized(
                enabledConfig().put("issuedAt", issuedAt).put("expiresAt", expiresAt),
                androidAllowedPrivacy(),
                identity(5),
                Instant.parse(now).toEpochMilli(),
            )
        }
    }

    @Test
    fun `unsupported schema major fails closed at every versioned boundary`() {
        assertRejected(
            enabledConfig().put("schemaVersion", 2),
            androidAllowedPrivacy(),
            identity(5),
            V1ConfigRejection.UNSUPPORTED_SCHEMA,
        )
        val nested = enabledConfig().apply { getJSONObject("privacy").put("schemaVersion", 2) }
        assertRejected(nested, androidAllowedPrivacy(), identity(5), V1ConfigRejection.UNSUPPORTED_SCHEMA)
        assertBothInvalid(
            enabledConfig(),
            androidAllowedPrivacy().put("schemaVersion", 2),
            identity(5),
            V1ChannelAuthorizationReason.PRIVACY_SCHEMA_UNSUPPORTED,
        )
        assertBothInvalid(
            enabledConfig(),
            androidAllowedPrivacy(),
            identity(5).copy(schemaVersion = 2),
            V1ChannelAuthorizationReason.IDENTITY_SCHEMA_UNSUPPORTED,
        )
    }

    @Test
    fun `closed schemas and config allOf requirements reject malformed documents`() {
        val malformedConfigs =
            listOf(
                enabledConfig().put("futureField", true),
                enabledConfig().apply { remove("site") },
                enabledConfig().apply { getJSONObject("privacy").getJSONObject("capture").put("extra", true) },
                enabledConfig().apply { getJSONObject("session").put("idleTimeoutSeconds", 59) },
                enabledConfig().apply { getJSONObject("limits").put("eventBatchCount", 1.5) },
                enabledConfig().apply { getJSONObject("endpoints").remove("replay") },
                enabledConfig().apply { getJSONObject("endpoints").remove("assets") },
                disabledConfig().put("site", JSONObject().put("id", "site_demo")),
                disabledConfig().apply { remove("reason") },
            )
        malformedConfigs.forEach { config ->
            assertRejected(config, androidAllowedPrivacy(), identity(5), V1ConfigRejection.MALFORMED)
        }

        val canonicalBody = enabledConfig().toString()
        listOf(
            canonicalBody.replace('"', '\''),
            canonicalBody.dropLast(1) + ",}",
            canonicalBody.dropLast(1) + ",\"status\":\"disabled\"}",
            canonicalBody.replace("\"eventBatchCount\":100", "\"eventBatchCount\":0100"),
        ).forEach { nonStandardJson ->
            assertRejected(nonStandardJson, androidAllowedPrivacy().toString(), identity(5), V1ConfigRejection.MALFORMED)
        }

        assertBothInvalid(
            enabledConfig(),
            androidAllowedPrivacy().put("futureField", true),
            identity(5),
            V1ChannelAuthorizationReason.PRIVACY_STATE_MALFORMED,
        )
    }

    @Test
    fun `config and privacy body byte ceilings fail closed`() {
        val oversizedConfig = enabledConfig().toString() + " ".repeat(65_536)
        assertRejected(oversizedConfig, androidAllowedPrivacy().toString(), identity(5), V1ConfigRejection.MALFORMED)

        val oversizedPrivacy = androidAllowedPrivacy().toString() + " ".repeat(32_768)
        assertBothInvalid(
            enabledConfig().toString(),
            oversizedPrivacy,
            identity(5),
            V1ChannelAuthorizationReason.PRIVACY_STATE_MALFORMED,
        )
    }

    @Test
    fun `role endpoint allowlists reject untrusted origin path and URL authority including assets`() {
        val cases =
            listOf(
                "events" to "https://ingest.elu.dev.attacker.example/v1/events",
                "events" to "https://events.elu.dev/v1/events",
                "events" to "https://assets.elu.dev/v1/events",
                "events" to "http://ingest.elu.dev/v1/events",
                "events" to "https://ingest.elu.dev/v1/replay",
                "events" to "https://ingest.elu.dev/v1/events/",
                "events" to "https://ingest.elu.dev/v1/%65vents",
                "events" to "https://user@ingest.elu.dev/v1/events",
                "events" to "https://ingest.elu.dev:444/v1/events",
                "events" to "https://ingest.elu.dev/v1/events#fragment",
                "events" to "https://ingest.elu.dev/v1/events?site_key=other",
                "events" to "https://ingest.elu.dev/v1/events?site%5Fkey=other",
                "events" to "https://ingest.elu.dev/v1/events?region=é",
                "assets" to "https://ingest.elu.dev/sdk/",
                "assets" to "https://assets.elu.dev/sdk/recorder.js",
            )
        cases.forEach { (role, endpoint) ->
            val config = enabledConfig().apply { getJSONObject("endpoints").put(role, endpoint) }
            assertRejected(config, androidAllowedPrivacy(), identity(5), V1ConfigRejection.UNAUTHORIZED)
        }
    }

    @Test
    fun `native roles preserve non-reserved query parameters while assets remain isolated`() {
        val config =
            enabledConfig().apply {
                getJSONObject("endpoints").apply {
                    put("events", "https://ingest.elu.dev:443/v1/events?region=%C3%A9")
                    put("replay", "https://ingest.elu.dev/v1/replay?region=us")
                    put("flags", "https://ingest.elu.dev/v1/flags?region=us")
                    put("assets", "https://assets.elu.dev/sdk/?region=us")
                }
            }
        val authorized = authorized(config, androidAllowedPrivacy(), identity(5))
        assertEquals("region=%C3%A9", authorized.endpoints.events?.rawQuery)
        listOf(V1EndpointRole.REPLAY, V1EndpointRole.FLAGS).forEach { role ->
            assertEquals("region=us", authorized.endpoints[role]?.rawQuery)
        }
        assertNull(authorized.endpoints.assets)
    }

    @Test
    fun `blocked privacy denies capture and replay but retains feature-scoped flags only`() {
        val privacy = blockedPrivacy()
        val config = enabledConfig().apply { getJSONObject("privacy").put("revision", privacy.getString("policyRevision")) }
        val authorized = authorized(config, privacy, identity(7))

        assertNull(authorized.endpoints.events)
        assertNull(authorized.endpoints.replay)
        assertEquals("https://ingest.elu.dev/v1/flags", authorized.endpoints.flags.toString())
        assertNull(authorized.endpoints.assets)
    }

    @Test
    fun `identity opt-out gates capture and replay without inventing a flags gate`() {
        assertBothInvalid(
            enabledConfig(),
            androidAllowedPrivacy(),
            identity(contextRevision = 5, optedOut = true),
            V1ChannelAuthorizationReason.IDENTITY_OPT_STATE_MISMATCH,
        )

        val optedOutPrivacy =
            androidAllowedPrivacy().apply {
                put("identityOptedOut", true)
                put("captureAllowed", false)
                put("replayAllowed", false)
                rehash(this)
            }
        val authorized = authorized(enabledConfig(), optedOutPrivacy, identity(5, optedOut = true))
        assertNull(authorized.endpoints.events)
        assertNull(authorized.endpoints.replay)
        assertTrue(authorized.endpoints.flags != null)
        assertEquals(V1ChannelAuthorizationStatus.RESTRICTED, authorized.captureAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.IDENTITY_OPTED_OUT, authorized.captureAuthorization.reason)
        assertEquals(V1ChannelAuthorizationStatus.RESTRICTED, authorized.replayAuthorization.status)
    }

    @Test
    fun `privacy failures are per-channel and never suppress flags`() {
        val sharedFailures =
            listOf(
                androidAllowedPrivacy().apply {
                    put("policyRevision", "stale-policy")
                    rehash(this)
                } to V1ChannelAuthorizationReason.POLICY_REVISION_MISMATCH,
                androidAllowedPrivacy().apply {
                    put("contextRevision", 6)
                    rehash(this)
                } to V1ChannelAuthorizationReason.CONTEXT_REVISION_MISMATCH,
                androidAllowedPrivacy().apply { put("effectivePolicyHash", "sha256:" + "0".repeat(64)) } to
                    V1ChannelAuthorizationReason.POLICY_HASH_MISMATCH,
            )
        sharedFailures.forEach { (privacy, reason) ->
            assertBothInvalid(enabledConfig(), privacy, identity(5), reason)
        }

        val captureMismatch =
            androidAllowedPrivacy().apply {
                put("captureAllowed", false)
                rehash(this)
            }
        val result = authorized(enabledConfig(), captureMismatch, identity(5))
        assertNull(result.endpoints.events)
        assertNull(result.endpoints.replay)
        assertTrue(result.endpoints.flags != null)
        assertEquals(V1ChannelAuthorizationStatus.INVALID, result.captureAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.DERIVED_CLAIM_MISMATCH, result.captureAuthorization.reason)
        assertEquals(V1ChannelAuthorizationStatus.RESTRICTED, result.replayAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.CAPTURE_RESTRICTED, result.replayAuthorization.reason)
    }

    @Test
    fun `unsupported advertised replay selection invalidates privacy even when replay is locally false`() {
        val privacy =
            androidAllowedPrivacy().apply {
                put("replayAllowed", false)
                getJSONObject("replayTransport").put("codec", "elu-android-unknown-v1")
                rehash(this)
            }
        val result = authorized(enabledConfig(), privacy, identity(5))
        assertTrue(result.endpoints.events != null)
        assertNull(result.endpoints.replay)
        assertTrue(result.endpoints.flags != null)
        assertEquals(V1ChannelAuthorizationStatus.INVALID, result.replayAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.TRANSPORT_NOT_ADVERTISED, result.replayAuthorization.reason)
    }

    @Test
    fun `capture and replay feature and server policy switches gate only their roles`() {
        val captureOffConfig = enabledConfig().apply { getJSONObject("features").put("capture", false) }
        val captureOffPrivacy =
            androidAllowedPrivacy().apply {
                put("captureAllowed", false)
                put("replayAllowed", false)
                rehash(this)
            }
        val captureOff = authorized(captureOffConfig, captureOffPrivacy, identity(5))
        assertNull(captureOff.endpoints.events)
        assertNull(captureOff.endpoints.replay)
        assertTrue(captureOff.endpoints.flags != null)

        val replayOffConfig = enabledConfig().apply { getJSONObject("privacy").getJSONObject("replay").put("enabled", false) }
        val replayOffPrivacy =
            androidAllowedPrivacy().apply {
                put("replayAllowed", false)
                rehash(this)
            }
        val replayOff = authorized(replayOffConfig, replayOffPrivacy, identity(5))
        assertTrue(replayOff.endpoints.events != null)
        assertNull(replayOff.endpoints.replay)
    }

    @Test
    fun `flags follow their feature switch while browser assets never enter native authority`() {
        val config = enabledConfig().apply { getJSONObject("features").put("flags", false) }
        val authorized = authorized(config, androidAllowedPrivacy(), identity(5))
        assertTrue(authorized.endpoints.events != null)
        assertTrue(authorized.endpoints.replay != null)
        assertNull(authorized.endpoints.flags)
        assertNull(authorized.endpoints.assets)
    }

    @Test
    fun `effective masking may tighten but never loosen server policy`() {
        val tighter =
            androidAllowedPrivacy().apply {
                getJSONObject("effectiveMasking").put("text", "all")
                rehash(this)
            }
        authorized(enabledConfig(), tighter, identity(5))

        val looser =
            androidAllowedPrivacy().apply {
                getJSONObject("effectiveMasking").put("inputs", "sensitive")
                rehash(this)
            }
        val result = authorized(enabledConfig(), looser, identity(5))
        assertTrue(result.endpoints.events != null)
        assertNull(result.endpoints.replay)
        assertTrue(result.endpoints.flags != null)
        assertEquals(V1ChannelAuthorizationStatus.INVALID, result.replayAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.MASKING_POLICY_VIOLATION, result.replayAuthorization.reason)
    }

    @Test
    fun `missing Android rule and unknown Android dialect both require explicit fallback for replay`() {
        val manager = managerWithReplay()
        val missingFallback = authorized(enabledConfig(), androidPrivacyWithoutFallback(), identity(5), manager = manager)
        assertTrue(missingFallback.endpoints.events != null)
        assertNull(missingFallback.endpoints.replay)

        val unknownRuleConfig =
            enabledConfig().apply {
                getJSONObject("privacy")
                    .getJSONObject("masking")
                    .getJSONArray("platformRules")
                    .put(
                        JSONObject()
                            .put("platform", "android")
                            .put("action", "mask")
                            .put("targetDialect", "elu-android-unknown-v1")
                            .put("target", "sensitive-view"),
                    )
            }
        val unknownWithoutFallback =
            authorized(unknownRuleConfig, androidPrivacyWithoutFallback(), identity(5), manager = managerWithReplay())
        assertNull(unknownWithoutFallback.endpoints.replay)

        val withFallback = authorized(unknownRuleConfig, androidAllowedPrivacy(), identity(5), manager = managerWithReplay())
        assertTrue(withFallback.endpoints.replay != null)
        assertEquals(PROVEN_REPLAY, withFallback.negotiatedReplayTransport)
    }

    @Test
    fun `server region block cannot be contradicted by an allow decision`() {
        val config =
            enabledConfig().apply {
                getJSONObject("privacy").getJSONObject("regionPolicy").apply {
                    put("mode", "block")
                    remove("evaluator")
                }
            }
        val result = authorized(config, androidAllowedPrivacy(), identity(5))
        assertNull(result.endpoints.events)
        assertNull(result.endpoints.replay)
        assertTrue(result.endpoints.flags != null)
        assertEquals(V1ChannelAuthorizationStatus.INVALID, result.captureAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.REGION_POLICY_CONFLICT, result.captureAuthorization.reason)
        assertEquals(V1ChannelAuthorizationStatus.RESTRICTED, result.replayAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.CAPTURE_RESTRICTED, result.replayAuthorization.reason)
    }

    @Test
    fun `integer fields do not truncate and decimal values survive Android numeric checks`() {
        val fractionalInteger = enabledConfig().apply { getJSONObject("limits").put("eventBatchCount", 1.5) }
        assertRejected(fractionalInteger, androidAllowedPrivacy(), identity(5), V1ConfigRejection.MALFORMED)

        val oversizedContext = androidAllowedPrivacy().put("contextRevision", BigDecimal("9223372036854775808"))
        assertBothInvalid(
            enabledConfig(),
            oversizedContext,
            identity(5),
            V1ChannelAuthorizationReason.PRIVACY_STATE_MALFORMED,
        )

        val exactIntegerToken = androidAllowedPrivacy().put("contextRevision", BigDecimal("5.0")).also(::rehash)
        authorized(enabledConfig(), exactIntegerToken, identity(5))

        val lossySample =
            enabledConfig().apply {
                getJSONObject("privacy").getJSONObject("replay").put(
                    "sampleRate",
                    BigDecimal("0.100000000000000000000000000000000001"),
                )
            }
        assertRejected(lossySample, androidAllowedPrivacy(), identity(5), V1ConfigRejection.MALFORMED)
    }

    @Test
    fun `stale response never rolls back the active config`() {
        val manager = managerWithReplay()
        val newest = enabledConfig().put("revision", "newest").put("issuedAt", "2026-08-04T00:01:00Z")
        assertEnabled(manager.install(newest.toString(), NOW_MS))

        assertEquals(
            V1ConfigUpdateResult.Rejected(V1ConfigRejection.STALE),
            manager.install(enabledConfig().toString(), NOW_MS),
        )
        assertEquals("newest", assertAuthorized(manager.authorize(androidAllowedPrivacy().toString(), identity(5), NOW_MS)).revision)
    }

    @Test
    fun `new disabled expired and invalid-policy boundaries prevent an older config from returning`() {
        listOf(
            disabledConfig().put("revision", "new-disabled").put("issuedAt", "2026-08-04T00:01:00Z"),
            enabledConfig()
                .put("revision", "new-expired")
                .put("issuedAt", "2026-08-04T00:01:00Z")
                .put("expiresAt", "2026-08-04T00:01:15Z"),
            enabledConfig().apply {
                put("revision", "new-invalid-policy")
                put("issuedAt", "2026-08-04T00:01:00Z")
                getJSONObject("privacy").getJSONObject("capture").put("unsupported", true)
            },
        ).forEachIndexed { index, newer ->
            val manager = managerWithReplay()
            assertEnabled(manager.install(enabledConfig().toString(), NOW_MS))
            val outcome = manager.install(newer.toString(), NOW_MS)
            when (index) {
                0 -> assertTrue(outcome is V1ConfigUpdateResult.Inactive)
                1 -> assertEquals(V1ConfigUpdateResult.Rejected(V1ConfigRejection.EXPIRED), outcome)
                else -> assertEquals(V1ConfigUpdateResult.Rejected(V1ConfigRejection.MALFORMED), outcome)
            }
            assertEquals(
                V1ConfigUpdateResult.Rejected(V1ConfigRejection.STALE),
                manager.install(enabledConfig().toString(), NOW_MS),
            )
            assertFalse(manager.authorize(androidAllowedPrivacy().toString(), identity(5), NOW_MS) is V1ConfigResolution.Authorized)
        }
    }

    @Test
    fun `invalid effective privacy cannot erase the installed config or its boundary`() {
        val manager = managerWithReplay()
        assertEnabled(manager.install(enabledConfig().toString(), NOW_MS))
        val invalid = androidAllowedPrivacy().put("effectivePolicyHash", "sha256:" + "0".repeat(64))
        val invalidResult = assertAuthorized(manager.authorize(invalid.toString(), identity(5), NOW_MS))
        assertNull(invalidResult.endpoints.events)
        assertNull(invalidResult.endpoints.replay)
        assertTrue(invalidResult.endpoints.flags != null)
        assertEquals(V1ChannelAuthorizationStatus.INVALID, invalidResult.captureAuthorization.status)
        assertEquals(V1ChannelAuthorizationReason.POLICY_HASH_MISMATCH, invalidResult.captureAuthorization.reason)
        assertAuthorized(manager.authorize(androidAllowedPrivacy().toString(), identity(5), NOW_MS))
        assertEnabled(manager.install(enabledConfig().toString(), NOW_MS))
    }

    @Test
    fun `equal issuance conflict poisons the boundary and identical update is idempotent`() {
        val idempotentManager = managerWithReplay()
        val body = enabledConfig().toString()
        assertEnabled(idempotentManager.install(body, NOW_MS))
        assertEnabled(idempotentManager.install(body, NOW_MS))
        assertAuthorized(idempotentManager.authorize(androidAllowedPrivacy().toString(), identity(5), NOW_MS))

        val conflicting = enabledConfig().put("revision", "same-time-conflict")
        assertEquals(
            V1ConfigUpdateResult.Rejected(V1ConfigRejection.CONFLICT),
            idempotentManager.install(conflicting.toString(), NOW_MS),
        )
        assertEquals(
            V1ConfigUpdateResult.Rejected(V1ConfigRejection.CONFLICT),
            idempotentManager.install(body, NOW_MS),
        )
        assertEquals(
            V1ConfigResolution.Rejected(V1ConfigRejection.CONFLICT),
            idempotentManager.authorize(androidAllowedPrivacy().toString(), identity(5), NOW_MS),
        )
    }

    @Test
    fun `concurrent out-of-order installs converge on the newest config`() {
        val manager = managerWithReplay()
        val documents =
            listOf(
                enabledConfig().put("revision", "oldest").put("issuedAt", "2026-08-04T00:00:00Z").toString(),
                enabledConfig().put("revision", "middle").put("issuedAt", "2026-08-04T00:00:30Z").toString(),
                enabledConfig().put("revision", "newest").put("issuedAt", "2026-08-04T00:01:00Z").toString(),
            )
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<V1ConfigUpdateResult>())
        val futures =
            (0 until 120).map { index ->
                executor.submit {
                    start.await()
                    results += manager.install(documents[index % documents.size], NOW_MS)
                }
            }
        start.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(120, results.size)
        val final = manager.install(documents.last(), NOW_MS)
        assertEquals("newest", (final as V1ConfigUpdateResult.Enabled).revision)
        assertEquals("newest", assertAuthorized(manager.authorize(androidAllowedPrivacy().toString(), identity(5), NOW_MS)).revision)
    }

    @Test
    fun `concurrent equal-boundary conflicts fail closed regardless of winner`() {
        val manager = managerWithReplay()
        val first = enabledConfig().put("revision", "equal-a").toString()
        val second = enabledConfig().put("revision", "equal-b").toString()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<V1ConfigUpdateResult>())
        val futures =
            listOf(first, second).map { body ->
                executor.submit {
                    start.await()
                    results += manager.install(body, NOW_MS)
                }
            }
        start.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()

        assertTrue(results.any { it == V1ConfigUpdateResult.Rejected(V1ConfigRejection.CONFLICT) })
        assertEquals(
            V1ConfigResolution.Rejected(V1ConfigRejection.CONFLICT),
            manager.authorize(androidAllowedPrivacy().toString(), identity(5), NOW_MS),
        )
    }

    private fun authorized(
        config: JSONObject,
        privacy: JSONObject,
        identity: IdentityState,
        nowEpochMillis: Long = NOW_MS,
        manager: V1ConfigManager = managerWithReplay(),
    ): V1AuthorizedConfig = authorized(config.toString(), privacy.toString(), identity, nowEpochMillis, manager)

    private fun authorized(
        config: String,
        privacy: String,
        identity: IdentityState,
        nowEpochMillis: Long = NOW_MS,
        manager: V1ConfigManager = managerWithReplay(),
    ): V1AuthorizedConfig {
        assertEnabled(manager.install(config, nowEpochMillis))
        return assertAuthorized(manager.authorize(privacy, identity, nowEpochMillis))
    }

    private fun assertEnabled(result: V1ConfigUpdateResult): V1ConfigUpdateResult.Enabled {
        assertTrue("Expected enabled update but got $result", result is V1ConfigUpdateResult.Enabled)
        return result as V1ConfigUpdateResult.Enabled
    }

    private fun assertAuthorized(result: V1ConfigResolution): V1AuthorizedConfig {
        assertTrue("Expected authorization but got $result", result is V1ConfigResolution.Authorized)
        return (result as V1ConfigResolution.Authorized).config
    }

    private fun assertBothInvalid(
        config: JSONObject,
        privacy: JSONObject,
        identity: IdentityState,
        reason: V1ChannelAuthorizationReason,
    ) = assertBothInvalid(config.toString(), privacy.toString(), identity, reason)

    private fun assertBothInvalid(
        config: String,
        privacy: String,
        identity: IdentityState,
        reason: V1ChannelAuthorizationReason,
    ) {
        val manager = managerWithReplay()
        assertEnabled(manager.install(config, NOW_MS))
        val result = assertAuthorized(manager.authorize(privacy, identity, NOW_MS))
        assertNull(result.endpoints.events)
        assertNull(result.endpoints.replay)
        assertTrue(result.endpoints.flags != null)
        assertEquals(V1ChannelAuthorization(V1ChannelAuthorizationStatus.INVALID, reason), result.captureAuthorization)
        assertEquals(V1ChannelAuthorization(V1ChannelAuthorizationStatus.INVALID, reason), result.replayAuthorization)
    }

    private fun assertRejected(
        config: JSONObject,
        privacy: JSONObject?,
        identity: IdentityState,
        expected: V1ConfigRejection,
        nowEpochMillis: Long = NOW_MS,
    ) = assertRejected(config.toString(), privacy?.toString(), identity, expected, nowEpochMillis)

    private fun assertRejected(
        config: String,
        privacy: String?,
        identity: IdentityState,
        expected: V1ConfigRejection,
        nowEpochMillis: Long = NOW_MS,
    ) {
        val manager = managerWithReplay()
        when (val update = manager.install(config, nowEpochMillis)) {
            is V1ConfigUpdateResult.Rejected -> {
                assertEquals(expected, update.reason)
                return
            }
            is V1ConfigUpdateResult.Inactive -> {
                assertEquals(V1ConfigRejection.INACTIVE, expected)
                return
            }
            is V1ConfigUpdateResult.Enabled -> Unit
        }
        assertEquals(V1ConfigResolution.Rejected(expected), manager.authorize(privacy, identity, nowEpochMillis))
    }

    private fun managerWithReplay(): V1ConfigManager = V1ConfigManager(setOf(PROVEN_REPLAY))

    private fun canonicalEnabledConfig(): JSONObject =
        JSONObject(resourceText("contracts/v1/fixtures/config-enabled.json"))

    private fun enabledConfig(): JSONObject =
        canonicalEnabledConfig().apply {
            getJSONObject("capabilities").getJSONObject("replay").put(
                "acceptedCodecs",
                JSONArray(listOf(PROVEN_REPLAY.codec)),
            )
        }

    private fun disabledConfig(): JSONObject = JSONObject(resourceText("contracts/v1/fixtures/config-disabled.json"))

    private fun canonicalAllowedPrivacy(): JSONObject =
        JSONObject(resourceText("contracts/v1/fixtures/privacy-allowed.json"))

    private fun androidAllowedPrivacy(): JSONObject =
        androidPrivacyWithoutFallback().apply {
            getJSONObject("effectiveMasking").put("platformFallbackApplied", true)
            rehash(this)
        }

    private fun androidPrivacyWithoutFallback(): JSONObject =
        canonicalAllowedPrivacy().apply {
            getJSONObject("replayTransport").put("codec", PROVEN_REPLAY.codec)
            rehash(this)
        }

    private fun blockedPrivacy(): JSONObject = JSONObject(resourceText("contracts/v1/fixtures/privacy-blocked.json"))

    private fun identity(
        contextRevision: Long = 0,
        optedOut: Boolean = false,
    ): IdentityState =
        IdentityState(
            revision = 0,
            contextRevision = contextRevision,
            anonymousId = "anon_test",
            userId = null,
            groups = emptyMap(),
            superProperties = emptyMap(),
            session = null,
            optedOut = optedOut,
            updatedAt = "2026-08-04T00:00:00.000Z",
        )

    private fun rehash(json: JSONObject): JSONObject {
        json.remove("effectivePolicyHash")
        val digest = sha256(canonicalize(json).toByteArray(StandardCharsets.UTF_8))
        json.put("effectivePolicyHash", "sha256:$digest")
        return json
    }

    private fun canonicalize(value: Any?): String =
        when (value) {
            null, JSONObject.NULL -> "null"
            is Boolean -> value.toString()
            is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            is String -> quote(value)
            is JSONArray ->
                (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") { index ->
                    canonicalize(value.get(index))
                }
            is JSONObject -> {
                val sorted = TreeMap<String, Any?>()
                value.keys().forEach { key -> sorted[key] = value.get(key) }
                sorted.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, child) ->
                    quote(key) + ":" + canonicalize(child)
                }
            }
            else -> error("Unsupported canonical test value")
        }

    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\u000c' -> append("\\f")
                    '\r' -> append("\\r")
                    else ->
                        if (character.code < 0x20) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                }
            }
            append('"')
        }

    private fun resourceText(path: String): String = String(resourceBytes(path), StandardCharsets.UTF_8)

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing test resource $path" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val PROVEN_REPLAY = V1ReplayTransport("elu-android-replay-v1", V1ReplayCompression.GZIP)
        val ISSUED_AT_MS: Long = Instant.parse("2026-08-04T00:00:00.000Z").toEpochMilli()
        val NOW_MS: Long = Instant.parse("2026-08-04T00:01:30.000Z").toEpochMilli()
        val EXPIRES_AT_MS: Long = Instant.parse("2026-08-04T00:05:00.000Z").toEpochMilli()
    }
}
