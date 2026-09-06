package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ImageMasking
import dev.elu.analytics.internal.config.V1OnDeviceDecisionSource
import dev.elu.analytics.internal.config.V1OnDeviceDecisionValue
import dev.elu.analytics.internal.config.V1ParsedConfig
import dev.elu.analytics.internal.config.V1PlatformMaskingRule
import dev.elu.analytics.internal.config.V1PlatformRuleAction
import dev.elu.analytics.internal.config.V1PrivacyPlatform
import dev.elu.analytics.internal.config.V1RegionPolicy
import dev.elu.analytics.internal.config.V1RegionPolicyMode
import dev.elu.analytics.internal.config.V1ReplayCompression
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.config.V1ServerPrivacyPolicy
import dev.elu.analytics.internal.config.V1TextMasking
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import java.time.Instant
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyStateProjectorTest {
    private val owners = mutableListOf<RuntimeQueueOwner>()
    private val keyCounter = AtomicInteger()

    @Before
    fun setUp() {
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @After
    fun tearDown() {
        owners.asReversed().forEach { owner -> runCatching { owner.closeAsync().await() } }
        RuntimeQueueOwner.clearOwnershipForTesting()
    }

    @Test
    fun `non-EU device under block-eu-on-device projects an allowed capture state`() {
        val state = PrivacyStateProjector.project(input(config(), deviceInEu = false))

        assertEquals("privacy-1", state.policyRevision)
        assertEquals(5L, state.contextRevision)
        assertEquals(V1OnDeviceDecisionValue.ALLOW, state.onDeviceDecision.decision)
        assertEquals(V1OnDeviceDecisionSource.DEVICE_REGION, state.onDeviceDecision.source)
        assertNull(state.onDeviceDecision.reason)
        assertEquals(NOW, state.onDeviceDecision.evaluatedAt)
        assertTrue(state.captureAllowed)
        assertFalse(state.identityOptedOut)
        assertFalse(state.replayAllowed)
        assertFalse(state.replaySampled)
        assertFalse(state.maskingValidated)
        assertFalse(state.replaySessionEligible)
        assertEquals(0, state.replayBudgetRemainingSeconds)
        assertNull(state.replayTransport)
        assertTrue(state.effectivePolicyHash.startsWith("sha256:"))
    }

    @Test
    fun `EU device under block-eu-on-device projects a blocked decision`() {
        val state = PrivacyStateProjector.project(input(config(), deviceInEu = true))

        assertEquals(V1OnDeviceDecisionValue.BLOCK, state.onDeviceDecision.decision)
        assertEquals(V1OnDeviceDecisionSource.DEVICE_REGION, state.onDeviceDecision.source)
        assertEquals("regional-policy", state.onDeviceDecision.reason)
        assertFalse(state.captureAllowed)
        assertFalse(state.replayAllowed)
    }

    @Test
    fun `region modes allow everywhere block everywhere and fail closed on an unknown evaluator`() {
        val allowEverywhere = PrivacyStateProjector.project(input(config(), deviceInEu = true, region = V1RegionPolicy(V1RegionPolicyMode.ALLOW, null)))
        assertEquals(V1OnDeviceDecisionValue.ALLOW, allowEverywhere.onDeviceDecision.decision)
        assertEquals(V1OnDeviceDecisionSource.NOT_EVALUATED, allowEverywhere.onDeviceDecision.source)
        assertTrue(allowEverywhere.captureAllowed)

        val blockEverywhere = PrivacyStateProjector.project(input(config(), deviceInEu = false, region = V1RegionPolicy(V1RegionPolicyMode.BLOCK, null)))
        assertEquals(V1OnDeviceDecisionValue.BLOCK, blockEverywhere.onDeviceDecision.decision)
        assertEquals(V1OnDeviceDecisionSource.REMOTE_KILL_SWITCH, blockEverywhere.onDeviceDecision.source)
        assertFalse(blockEverywhere.captureAllowed)

        val unknownEvaluator =
            PrivacyStateProjector.project(
                input(config(), deviceInEu = false, region = V1RegionPolicy(V1RegionPolicyMode.BLOCK_EU_ON_DEVICE, "elu-other-v9")),
            )
        assertEquals(V1OnDeviceDecisionValue.UNKNOWN, unknownEvaluator.onDeviceDecision.decision)
        assertEquals("unsupported-region-evaluator", unknownEvaluator.onDeviceDecision.reason)
        assertFalse(unknownEvaluator.captureAllowed)
    }

    @Test
    fun `opted-out identity carries its opt state and never claims capture`() {
        val state = PrivacyStateProjector.project(input(config(), deviceInEu = false, optedOut = true))

        assertTrue(state.identityOptedOut)
        assertFalse(state.captureAllowed)
        assertEquals(V1OnDeviceDecisionValue.ALLOW, state.onDeviceDecision.decision)
    }

    @Test
    fun `masking falls back to the strictest posture without a recognized Android rule`() {
        val fallback = PrivacyStateProjector.project(input(config(), deviceInEu = false)).effectiveMasking
        assertTrue(fallback.platformFallbackApplied)
        assertEquals(V1TextMasking.ALL, fallback.text)
        assertEquals(V1TextMasking.ALL, fallback.inputs)
        assertEquals(V1ImageMasking.BLOCK, fallback.images)
        assertTrue(fallback.secureInputsMasked)

        val rule = V1PlatformMaskingRule(V1PrivacyPlatform.ANDROID, V1PlatformRuleAction.MASK, "elu-view-id-v1", "secret")
        val parsed = config()
        val policy = checkNotNull(parsed.privacy)
        val withRule =
            policy.copy(
                masking =
                    policy.masking.copy(
                        text = V1TextMasking.SENSITIVE,
                        inputs = V1TextMasking.SENSITIVE,
                        images = V1ImageMasking.ALLOW,
                        platformRules = policy.masking.platformRules + rule,
                    ),
            )
        val unrecognized = PrivacyStateProjector.project(input(parsed, deviceInEu = false, policy = withRule)).effectiveMasking
        assertTrue(unrecognized.platformFallbackApplied)

        val recognized =
            PrivacyStateProjector.project(
                input(parsed, deviceInEu = false, policy = withRule, recognizedDialects = setOf("elu-view-id-v1")),
            ).effectiveMasking
        assertFalse(recognized.platformFallbackApplied)
        assertEquals(V1TextMasking.SENSITIVE, recognized.text)
        assertEquals(V1TextMasking.ALL, recognized.inputs)
        assertEquals(V1ImageMasking.ALLOW, recognized.images)

        val allInputs = withRule.copy(masking = withRule.masking.copy(inputs = V1TextMasking.ALL))
        val singleKnob =
            PrivacyStateProjector.project(
                input(parsed, deviceInEu = false, policy = allInputs, recognizedDialects = setOf("elu-view-id-v1")),
            ).effectiveMasking
        assertEquals(V1TextMasking.ALL, singleKnob.text)
    }

    @Test
    fun `replay claims require every local proof and an advertised transport`() {
        val advertised = V1ReplayTransport("elu-browser-dom-v1", V1ReplayCompression.GZIP)
        val proven = PrivacyReplayInput(sampled = true, maskingValidated = true, sessionEligible = true, budgetRemainingSeconds = 120, transport = advertised)

        val allowed = PrivacyStateProjector.project(input(config(), deviceInEu = false, replay = proven))
        assertTrue(allowed.replayAllowed)
        assertEquals("elu-browser-dom-v1", allowed.replayTransport?.codec)
        assertEquals(V1ReplayCompression.GZIP, allowed.replayTransport?.compression)

        val unadvertised = proven.copy(transport = V1ReplayTransport("elu-android-view-v1", V1ReplayCompression.NONE))
        val withoutTransport = PrivacyStateProjector.project(input(config(), deviceInEu = false, replay = unadvertised))
        assertFalse(withoutTransport.replayAllowed)
        assertNull(withoutTransport.replayTransport)

        val exhausted = PrivacyStateProjector.project(input(config(), deviceInEu = false, replay = proven.copy(budgetRemainingSeconds = 0)))
        assertFalse(exhausted.replayAllowed)

        val blocked = PrivacyStateProjector.project(input(config(), deviceInEu = true, replay = proven))
        assertFalse(blocked.replayAllowed)
    }

    @Test
    fun `encoded state round-trips through the strict parser and its hash verifies`() {
        val state = PrivacyStateProjector.project(input(config(), deviceInEu = false))
        val encoded = PrivacyStateProjector.encode(state)

        assertEquals(state, V1ConfigJson.parseEffectivePrivacy(encoded))
        val json = JSONObject(encoded)
        assertTrue(json.isNull("replayTransport"))
        assertEquals(state.effectivePolicyHash, json.getString("effectivePolicyHash"))

        val owner = open()
        val activated = owner.submitCaptureAuthority(configBody(), encoded).await()
        assertTrue(activated is RuntimeCaptureAuthorityUpdateResult.Activated)
        assertEquals(
            state.effectivePolicyHash,
            (activated as RuntimeCaptureAuthorityUpdateResult.Activated).authority.decisionHash,
        )
    }

    @Test
    fun `blocked and opted-out projections terminate the owner authority as privacy blocked`() {
        val euOwner = open()
        val blocked = PrivacyStateProjector.encode(PrivacyStateProjector.project(input(config(), deviceInEu = true)))
        val terminated = euOwner.submitCaptureAuthority(configBody(), blocked).await() as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED, terminated.authority.reason)

        val optedOutOwner = open(state = state(optedOut = true))
        val optedOut = PrivacyStateProjector.encode(PrivacyStateProjector.project(input(config(), deviceInEu = false, optedOut = true)))
        val optOutResult = optedOutOwner.submitCaptureAuthority(configBody(), optedOut).await() as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED, optOutResult.authority.reason)
    }

    @Test
    fun `a projection for a stale context revision is rejected by the owner`() {
        val owner = open()
        val stale = PrivacyStateProjector.encode(PrivacyStateProjector.project(input(config(), deviceInEu = false, contextRevision = 4)))
        val result = owner.submitCaptureAuthority(configBody(), stale).await() as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.STALE, result.authority.reason)
    }

    private fun input(
        parsed: V1ParsedConfig,
        deviceInEu: Boolean,
        optedOut: Boolean = false,
        contextRevision: Long = 5,
        region: V1RegionPolicy? = null,
        policy: V1ServerPrivacyPolicy? = null,
        replay: PrivacyReplayInput = PrivacyReplayInput.UNAVAILABLE,
        recognizedDialects: Set<String> = emptySet(),
    ): PrivacyProjectionInput {
        val basePolicy = policy ?: checkNotNull(parsed.privacy)
        return PrivacyProjectionInput(
            policy = region?.let { basePolicy.copy(region = it) } ?: basePolicy,
            features = checkNotNull(parsed.features),
            replayCapabilities = checkNotNull(parsed.replayCapabilities),
            identity = state(optedOut = optedOut, contextRevision = contextRevision).identity,
            deviceInEuTimezone = deviceInEu,
            evaluatedAt = NOW,
            replay = replay,
            recognizedMaskingDialects = recognizedDialects,
        )
    }

    private fun open(state: PersistedCoreState = state()): RuntimeQueueOwner {
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = "privacy-projector-${keyCounter.incrementAndGet()}",
                limits = RuntimeQueueLimits(10_000, 16_777_216),
                databaseFactory = FakeRuntimeQueueBacking()::connection,
                legacyStateLoader = { state },
                trustedSiteKey = "elu_pk_test_privacy",
                captureClock = FixedCaptureClock(NOW_MS),
            ).await()
        owners += owner
        return owner
    }

    private fun state(
        optedOut: Boolean = false,
        contextRevision: Long = 5,
    ): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 2,
                    contextRevision = contextRevision,
                    anonymousId = "anon_privacy",
                    userId = "user_privacy",
                    groups = emptyMap(),
                    superProperties = emptyMap(),
                    session = null,
                    optedOut = optedOut,
                    updatedAt = ISSUED,
                ),
            stream = StreamState(streamId = "stream_privacy", nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )

    private fun config(): V1ParsedConfig = V1ConfigJson.parseConfig(configBody())

    private fun configBody(): String =
        checkNotNull(javaClass.classLoader?.getResource("contracts/v1/fixtures/config-enabled.json")).readText()

    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)

    private class FixedCaptureClock(private val wallEpochMillis: Long) : RuntimeCaptureClock {
        override fun wallNowEpochMillis(): Long = wallEpochMillis

        override fun elapsedRealtimeNanos(): Long = 1_000L
    }

    private companion object {
        const val ISSUED = "2026-08-04T00:00:00.000Z"
        const val NOW = "2026-08-04T00:01:00.000Z"
        val NOW_MS: Long = Instant.parse(NOW).toEpochMilli()
    }
}
