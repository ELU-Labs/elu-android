package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.SessionState
import dev.elu.analytics.internal.core.StreamState
import java.io.IOException
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class CaptureAuthorityRuntimeTest {
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
    fun `authority hashes witnesses and first capture commit session plus event atomically`() {
        val clock = FakeCaptureClock(NOW_MS, 1_000L)
        val owner = open(clock = clock, state = state(superProperties = mapOf("plan" to "free", "same" to "super")))

        val activated = owner.submitCaptureAuthority(config(), privacy(5)).await() as RuntimeCaptureAuthorityUpdateResult.Activated
        assertEquals(
            "sha256:69da989f31a6a3133dcebcdb64cd7665c666eb6af5a8aa766bc0036d8736ca4f",
            activated.authority.configSemanticHash,
        )
        assertEquals(
            "sha256:6b13dc5469370452e41767356bedd92bbbdf3acf8ff4024447d03b1f19ea72ce",
            activated.authority.policySourceHash,
        )
        assertEquals("stream_capture", activated.authority.streamId)

        val accepted =
            owner.capture(command("checkout", NOW, mapOf("same" to "explicit", "amount" to 42))).await()
                as RuntimeCaptureResult.Accepted

        assertEquals(1, accepted.snapshot.queuedCount)
        assertEquals(1L, accepted.snapshot.state.stream.nextSequence)
        assertEquals(accepted.record.record.sessionId, accepted.snapshot.state.identity.session?.id)
        assertEquals("free", accepted.record.record.properties["plan"])
        assertEquals("explicit", accepted.record.record.properties["same"])
        assertEquals(42, accepted.record.record.properties["amount"])
        assertEquals(RuntimeEventKind.CAPTURE, accepted.record.record.kind)
    }

    @Test
    fun `super-property changes invalidate witness emit no record and explicit values win after reauthorization`() {
        val owner = open(state = state(superProperties = mapOf("same" to "old")))
        owner.submitCaptureAuthority(config(), privacy(5)).await()

        val registered = owner.registerSuperProperties(mapOf("same" to "super", "tier" to "growth"), NOW).await()
            as RuntimeAppendResult.Accepted
        assertEquals(0, registered.snapshot.queuedCount)
        assertEquals(6L, registered.snapshot.state.identity.contextRevision)

        val stale = owner.capture(command("stale", NOW)).await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED, stale.reason)

        owner.submitCaptureAuthority(config(), privacy(6)).await()
        val accepted =
            owner.capture(command("fresh", NOW, mapOf("same" to "explicit"))).await()
                as RuntimeCaptureResult.Accepted
        assertEquals("growth", accepted.record.record.properties["tier"])
        assertEquals("explicit", accepted.record.record.properties["same"])

        val unregistered = owner.unregisterSuperProperties(listOf("tier"), LATER).await() as RuntimeAppendResult.Accepted
        assertEquals(7L, unregistered.snapshot.state.identity.contextRevision)
        assertNull(unregistered.snapshot.state.identity.superProperties["tier"])
        assertEquals(1, unregistered.snapshot.queuedCount)
    }

    @Test
    fun `invalid nested capture properties reject before storage without poisoning authority`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing = backing)
        owner.submitCaptureAuthority(config(), privacy(5)).await()
        val transactionsBefore = backing.transactionThreads.size

        val invalid =
            owner.capture(
                command(
                    "invalid-nested",
                    NOW,
                    mapOf("nested" to listOf(mapOf("unsupported" to Any()))),
                ),
            ).await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.EVENT_INVALID, invalid.reason)
        assertEquals(transactionsBefore, backing.transactionThreads.size)
        assertEquals(0, invalid.snapshot.queuedCount)

        val valid = owner.capture(command("still-authorized", NOW, mapOf("nested" to listOf("ok")))).await()
        assertTrue(valid is RuntimeCaptureResult.Accepted)
    }

    @Test
    fun `same-witness restriction dominates and higher context can freshly authorize`() {
        val owner = open()
        assertTrue(owner.submitCaptureAuthority(config(), privacy(5)).await() is RuntimeCaptureAuthorityUpdateResult.Activated)

        val blocked = owner.submitCaptureAuthority(config(), privacy(5, allowed = false)).await()
            as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.PRIVACY_BLOCKED, blocked.authority.reason)
        val staleLoosening = owner.submitCaptureAuthority(config(), privacy(5)).await()
        assertEquals(blocked, staleLoosening)

        owner.registerSuperProperties(mapOf("context" to "new"), NOW).await()
        val refreshed = owner.submitCaptureAuthority(config(), privacy(6)).await()
        assertTrue(refreshed is RuntimeCaptureAuthorityUpdateResult.Activated)
    }

    @Test
    fun `malformed privacy replaces active authority with a malformed terminal latch`() {
        val owner = open()
        owner.submitCaptureAuthority(config(), privacy(5)).await()
        val allowed = privacy(5).trim()
        val duplicate = allowed.dropLast(1) + ",\"\\u0063ontextRevision\":5}"

        val update = owner.submitCaptureAuthority(config(), duplicate).await()
            as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.MALFORMED, update.authority.reason)
        val rejected = owner.capture(command("blocked-after-malformed", NOW)).await()
            as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_TERMINAL, rejected.reason)
    }

    @Test
    fun `config conflict and owner-lifetime site change fail terminally closed`() {
        val owner = open()
        owner.submitCaptureAuthority(config(), privacy(5)).await()

        val conflictJson = JSONObject(config())
        conflictJson.getJSONObject("session").put("idleTimeoutSeconds", 900)
        val conflict = owner.submitCaptureAuthority(conflictJson.toString(), privacy(5)).await()
            as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.CONFLICT, conflict.authority.reason)

        val otherOwner = open()
        otherOwner.submitCaptureAuthority(config(), privacy(5, allowed = false)).await()
        val changedSite = JSONObject(config()).apply {
            put("issuedAt", "2026-08-04T00:01:30.000Z")
            put("expiresAt", "2026-08-04T00:06:30.000Z")
            put("revision", "config-changed-site")
            getJSONObject("site").put("id", "site_other")
        }
        val siteResult = otherOwner.submitCaptureAuthority(changedSite.toString(), privacy(5)).await()
            as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.SITE_CHANGED, siteResult.authority.reason)
    }

    @Test
    fun `accepted newer config latches pending before a fallible database read`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing = backing)
        owner.submitCaptureAuthority(config(), privacy(5)).await()
        val newer =
            JSONObject(config()).apply {
                put("revision", "config-2026-08-04-2")
                put("issuedAt", "2026-08-04T00:00:30.000Z")
                put("expiresAt", "2026-08-04T00:05:30.000Z")
            }
        backing.failNextCoreRead = IOException("injected activation read failure")

        val failure = assertThrows(ExecutionException::class.java) {
            owner.submitCaptureAuthority(newer.toString(), privacy(5)).await()
        }
        assertTrue(failure.cause is IOException)
        assertTrue(owner.captureAuthorityForTesting().await() is RuntimeCaptureAuthorityState.Pending)

        val rejected = owner.capture(command("old-authority-must-not-run", NOW)).await()
            as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_PENDING, rejected.reason)
        assertEquals(0, rejected.snapshot.queuedCount)
    }

    @Test
    fun `read completion ambiguity publishes neither authority nor site pin`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing = backing)
        backing.ambiguousNextReadOnlyTransaction = true

        val failure = assertThrows(ExecutionException::class.java) {
            owner.submitCaptureAuthority(config(), privacy(5)).await()
        }
        assertTrue(failure.cause is AmbiguousRuntimeCommitException)
        assertTrue(owner.captureAuthorityForTesting().await() is RuntimeCaptureAuthorityState.Pending)
        assertNull(owner.pinnedConfigSiteForTesting().await())

        val changedSite =
            JSONObject(config()).apply {
                put("revision", "config-after-ambiguous-read")
                put("issuedAt", "2026-08-04T00:00:30.000Z")
                put("expiresAt", "2026-08-04T00:05:30.000Z")
                getJSONObject("site").put("id", "site_after_ambiguity")
            }
        val activated = owner.submitCaptureAuthority(changedSite.toString(), privacy(5)).await()
            as RuntimeCaptureAuthorityUpdateResult.Activated
        assertEquals("site_after_ambiguity", activated.authority.configSiteId)
        assertEquals("site_after_ambiguity", owner.pinnedConfigSiteForTesting().await())
    }

    @Test
    fun `activation brackets authoritative wall time and expires if the transaction consumes its lease`() {
        val orderedClock =
            SequencedCaptureClock(
                wallSamples = listOf(NOW_MS, NOW_MS),
                monotonicSamples = listOf(100L, 101L),
            )
        val owner = open(clock = orderedClock)
        val activated = owner.submitCaptureAuthority(config(), privacy(5)).await()
            as RuntimeCaptureAuthorityUpdateResult.Activated
        assertEquals(100L, activated.authority.monotonicStartedAt)
        assertEquals(listOf("wall", "elapsed", "wall", "elapsed"), orderedClock.calls)

        val consumedClock =
            SequencedCaptureClock(
                wallSamples = listOf(NOW_MS, NOW_MS),
                monotonicSamples = listOf(100L, 240_000_000_100L),
            )
        val consumedOwner = open(clock = consumedClock)
        val expired = consumedOwner.submitCaptureAuthority(config(), privacy(5)).await()
            as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.EXPIRED, expired.authority.reason)
        assertTrue(consumedOwner.captureAuthorityForTesting().await() is RuntimeCaptureAuthorityState.Terminal)
    }

    @Test
    fun `invalid privacy classes fail precisely and valid current witnesses clear malformed or stale`() {
        val unsupported = JSONObject(privacy(5)).apply { put("schemaVersion", 2) }.toString()
        val hashMismatch = JSONObject(privacy(5)).apply {
            put("effectivePolicyHash", "sha256:" + "0".repeat(64))
        }.toString()
        listOf<String?>(null, unsupported, hashMismatch).forEach { invalidPrivacy ->
            val owner = open()
            val malformed = owner.submitCaptureAuthority(config(), invalidPrivacy).await()
                as RuntimeCaptureAuthorityUpdateResult.Terminated
            assertEquals(RuntimeCaptureAuthorityTerminalReason.MALFORMED, malformed.authority.reason)
            assertTrue(owner.submitCaptureAuthority(config(), privacy(5)).await() is RuntimeCaptureAuthorityUpdateResult.Activated)
        }

        val staleOwner = open()
        val stale = staleOwner.submitCaptureAuthority(config(), privacy(4)).await()
            as RuntimeCaptureAuthorityUpdateResult.Terminated
        assertEquals(RuntimeCaptureAuthorityTerminalReason.STALE, stale.authority.reason)
        assertEquals(4L, stale.authority.contextRevision)
        assertTrue(
            staleOwner.submitCaptureAuthority(config(), privacy(5)).await() is
                RuntimeCaptureAuthorityUpdateResult.Activated,
        )
    }

    @Test
    fun `opt-out clears session atomically and opt-in requires fresh authority plus session`() {
        val owner = open()
        owner.submitCaptureAuthority(config(), privacy(5)).await()
        val first = owner.capture(command("before-consent-gap", NOW)).await() as RuntimeCaptureResult.Accepted
        val firstSession = first.record.record.sessionId

        val optedOut =
            owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(true, LATER)).await()
                as RuntimeAppendResult.Accepted
        assertTrue(optedOut.snapshot.state.identity.optedOut)
        assertNull(optedOut.snapshot.state.identity.session)
        assertEquals(6L, optedOut.snapshot.state.identity.contextRevision)
        assertEquals(RuntimeCaptureRejection.OPTED_OUT, (owner.capture(command("blocked", LATER)).await() as RuntimeCaptureResult.Rejected).reason)

        val optedIn =
            owner.applyLocal(RuntimeLocalStateChange.SetOptedOut(false, EVEN_LATER)).await()
                as RuntimeAppendResult.Accepted
        assertEquals(7L, optedIn.snapshot.state.identity.contextRevision)
        assertNull(optedIn.snapshot.state.identity.session)
        assertEquals(
            RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED,
            (owner.capture(command("needs-fresh-authority", EVEN_LATER)).await() as RuntimeCaptureResult.Rejected).reason,
        )

        owner.submitCaptureAuthority(config(), privacy(7)).await()
        val after = owner.capture(command("after-consent-gap", EVEN_LATER)).await() as RuntimeCaptureResult.Accepted
        assertNotEquals(firstSession, after.record.record.sessionId)
    }

    @Test
    fun `background is authority independent ordered and same-time capture resumes`() {
        val owner = open()
        val noSession = owner.markBackgrounded(NOW).await() as RuntimeAppendResult.Accepted
        assertNull(noSession.snapshot.state.identity.session)

        owner.submitCaptureAuthority(config(), privacy(5)).await()
        val captured = owner.capture(command("foreground", NOW)).await() as RuntimeCaptureResult.Accepted
        val sessionId = captured.record.record.sessionId
        val background = owner.markBackgrounded(NOW).await() as RuntimeAppendResult.Accepted
        assertEquals(SessionLifecycle.BACKGROUND, background.snapshot.state.identity.session?.lifecycle)
        assertEquals(NOW, background.snapshot.state.identity.session?.backgroundedAt)

        val resumed = owner.capture(command("same-time-resume", NOW)).await() as RuntimeCaptureResult.Accepted
        assertEquals(sessionId, resumed.record.record.sessionId)
        assertEquals(SessionLifecycle.ACTIVE, resumed.snapshot.state.identity.session?.lifecycle)
        assertNull(resumed.snapshot.state.identity.session?.backgroundedAt)

        val finalBackground = owner.markBackgrounded(NOW).await() as RuntimeAppendResult.Accepted
        assertEquals(SessionLifecycle.BACKGROUND, finalBackground.snapshot.state.identity.session?.lifecycle)
    }

    @Test
    fun `manual screen and exact idle plus maximum boundaries create atomic replacement sessions`() {
        val screenOwner = open()
        screenOwner.submitCaptureAuthority(config(), privacy(5)).await()
        val screen =
            screenOwner.capture(command("Checkout", NOW, kind = RuntimeEventKind.SCREEN)).await()
                as RuntimeCaptureResult.Accepted
        assertEquals(RuntimeEventKind.SCREEN, screen.record.record.kind)
        assertEquals(screen.record.record.sessionId, screen.snapshot.state.identity.session?.id)

        val idleSession =
            SessionState(
                id = "session_idle",
                startedAt = ISSUED,
                lastActivityAt = NOW,
                timeoutSeconds = 60,
                lifecycle = SessionLifecycle.ACTIVE,
                backgroundedAt = null,
            )
        val idleOwner = open(state = state(session = idleSession, updatedAt = NOW))
        idleOwner.submitCaptureAuthority(config(), privacy(5)).await()
        val idleRotated = idleOwner.capture(command("idle-equality", LATER)).await() as RuntimeCaptureResult.Accepted
        assertNotEquals("session_idle", idleRotated.record.record.sessionId)

        val maximumSession =
            SessionState(
                id = "session_maximum",
                startedAt = "2026-08-03T00:01:00.000Z",
                lastActivityAt = ISSUED,
                timeoutSeconds = 1_800,
                lifecycle = SessionLifecycle.ACTIVE,
                backgroundedAt = null,
            )
        val maximumOwner = open(state = state(session = maximumSession, updatedAt = ISSUED))
        maximumOwner.submitCaptureAuthority(config(), privacy(5)).await()
        val maximumRotated = maximumOwner.capture(command("maximum-equality", NOW)).await() as RuntimeCaptureResult.Accepted
        assertNotEquals("session_maximum", maximumRotated.record.record.sessionId)
    }

    @Test
    fun `exact monotonic expiry and queue-full leave capture state unchanged`() {
        val clock = FakeCaptureClock(NOW_MS, 100L)
        val owner = open(clock = clock, limits = RuntimeQueueLimits(1, 16_777_216))
        val authority = owner.submitCaptureAuthority(config(), privacy(5)).await() as RuntimeCaptureAuthorityUpdateResult.Activated
        val first = owner.capture(command("first", NOW)).await() as RuntimeCaptureResult.Accepted
        val stateAfterFirst = first.snapshot.state

        val queueFull = owner.capture(command("second", LATER)).await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.QUEUE_LIMIT, queueFull.reason)
        assertEquals(stateAfterFirst, queueFull.snapshot.state)

        clock.monotonicNanos = authority.authority.monotonicStartedAt + authority.authority.monotonicBudget
        val expired = owner.capture(command("expired", LATER)).await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_EXPIRED, expired.reason)
    }

    @Test
    fun `negative monotonic elapsed fails closed without API 26 unsigned helpers`() {
        val clock = FakeCaptureClock(NOW_MS, 1_000L)
        val owner = open(clock = clock)
        val authority = owner.submitCaptureAuthority(config(), privacy(5)).await()
            as RuntimeCaptureAuthorityUpdateResult.Activated
        clock.monotonicNanos = authority.authority.monotonicStartedAt - 1L

        val expired = owner.capture(command("clock-regressed", NOW)).await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_EXPIRED, expired.reason)
        assertEquals(0, expired.snapshot.queuedCount)
    }

    @Test
    fun `ambiguous rollback retries only after fresh authority checks`() {
        val backing = FakeRuntimeQueueBacking()
        val clock = FakeCaptureClock(NOW_MS, 1_000L)
        val owner = open(backing = backing, clock = clock)
        val authority = owner.submitCaptureAuthority(config(), privacy(5)).await() as RuntimeCaptureAuthorityUpdateResult.Activated
        backing.ambiguousNextCommit = FakeAmbiguousOutcome.ROLLBACK
        clock.expireAfterSamples = 4
        clock.expiredMonotonicNanos = authority.authority.monotonicStartedAt + authority.authority.monotonicBudget

        val result = owner.capture(command("ambiguous", NOW)).await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_EXPIRED, result.reason)
        assertEquals(0, result.snapshot.queuedCount)
        assertEquals(0L, result.snapshot.state.stream.nextSequence)
    }

    @Test
    fun `explicit proven rollback retries only after fresh authority checks`() {
        val backing = FakeRuntimeQueueBacking()
        val clock = FakeCaptureClock(NOW_MS, 1_000L)
        val owner = open(backing = backing, clock = clock)
        val authority = owner.submitCaptureAuthority(config(), privacy(5)).await() as RuntimeCaptureAuthorityUpdateResult.Activated
        backing.failNextKnownCommit =
            ProvenNotCommittedRuntimeTransactionException("injected explicit rollback")
        clock.expireAfterSamples = 4
        clock.expiredMonotonicNanos = authority.authority.monotonicStartedAt + authority.authority.monotonicBudget

        val result = owner.capture(command("proven-rollback", NOW)).await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_EXPIRED, result.reason)
        assertEquals(0, result.snapshot.queuedCount)
    }

    @Test
    fun `unknown ambiguous state never retries capture`() {
        val backing = FakeRuntimeQueueBacking()
        val owner = open(backing = backing)
        owner.submitCaptureAuthority(config(), privacy(5)).await()
        val recordsBefore = backing.records.size
        val attemptsBefore = backing.mutatedTransactionAttempts
        backing.ambiguousNextCommit = FakeAmbiguousOutcome.DIVERGE

        val failure = assertThrows(ExecutionException::class.java) {
            owner.capture(command("unknown", NOW)).await()
        }
        assertTrue(failure.cause is AmbiguousRuntimeCommitException)
        assertEquals(recordsBefore, backing.records.size)
        assertEquals(attemptsBefore + 1, backing.mutatedTransactionAttempts)

        val later = owner.capture(command("witness-invalidated", NOW)).await() as RuntimeCaptureResult.Rejected
        assertEquals(RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED, later.reason)
        assertEquals(recordsBefore, later.snapshot.queuedCount)
    }

    @Test
    fun `legacy opted-out state with session clears only session during open`() {
        val legacy =
            state(optedOut = true).let { original ->
                original.copy(
                    identity =
                        original.identity.copy(
                            session =
                                dev.elu.analytics.internal.core.SessionState(
                                    id = "session_legacy",
                                    startedAt = ISSUED,
                                    lastActivityAt = ISSUED,
                                    timeoutSeconds = 1_800,
                                    lifecycle = SessionLifecycle.ACTIVE,
                                    backgroundedAt = null,
                                ),
                        ),
                )
            }
        val owner = open(state = legacy)
        val normalized = owner.snapshot().await().state
        assertNull(normalized.identity.session)
        assertEquals(legacy.identity.copy(session = null), normalized.identity)
        assertEquals(legacy.stream, normalized.stream)
        assertEquals(legacy.flagContext, normalized.flagContext)
    }

    private fun open(
        backing: FakeRuntimeQueueBacking = FakeRuntimeQueueBacking(),
        clock: RuntimeCaptureClock = FakeCaptureClock(NOW_MS, 1_000L),
        state: PersistedCoreState = state(),
        limits: RuntimeQueueLimits = RuntimeQueueLimits(10_000, 16_777_216),
    ): RuntimeQueueOwner {
        val owner =
            RuntimeQueueOwner.open(
                ownershipKey = "capture-owner-${keyCounter.incrementAndGet()}",
                limits = limits,
                databaseFactory = backing::connection,
                legacyStateLoader = { state },
                identifiers = CountingIdentifiers(),
                trustedSiteKey = "elu_pk_test_capture",
                captureClock = clock,
            ).await()
        owners += owner
        return owner
    }

    private fun command(
        name: String,
        occurredAt: String,
        properties: Map<String, Any?> = emptyMap(),
        kind: RuntimeEventKind = RuntimeEventKind.CAPTURE,
    ): RuntimeCaptureCommand =
        RuntimeCaptureCommand(
            kind = kind,
            name = name,
            occurredAt = occurredAt,
            properties = properties,
            versions =
                RuntimeVersions(
                    platform = RuntimePlatform.ANDROID,
                    runtime = RuntimeVersionComponent("elu-android", "0.1.0"),
                    facade = RuntimeVersionComponent("Elu", "0.1.0"),
                    build = "test",
                ),
        )

    private fun state(
        superProperties: Map<String, Any?> = emptyMap(),
        optedOut: Boolean = false,
        session: SessionState? = null,
        updatedAt: String = ISSUED,
    ): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 2,
                    contextRevision = 5,
                    anonymousId = "anon_capture",
                    userId = "user_capture",
                    groups = mapOf("organization" to "org_capture"),
                    superProperties = superProperties,
                    session = session,
                    optedOut = optedOut,
                    updatedAt = updatedAt,
                ),
            stream = StreamState(streamId = "stream_capture", nextSequence = 0),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )

    private fun config(): String = resourceText("contracts/v1/fixtures/config-enabled.json")

    private fun privacy(
        contextRevision: Long,
        allowed: Boolean = true,
    ): String {
        val fixture = if (allowed) "privacy-allowed.json" else "privacy-blocked.json"
        val json = JSONObject(resourceText("contracts/v1/fixtures/$fixture"))
        if (!allowed) json.put("policyRevision", "privacy-1")
        json.put("contextRevision", contextRevision)
        val root = V1StrictCanonicalJson.parse(json.toString()) as V1StrictCanonicalJson.Value.ObjectValue
        val withoutHash =
            V1StrictCanonicalJson.Value.ObjectValue(root.members.filterNot { it.first == "effectivePolicyHash" })
        json.put("effectivePolicyHash", V1StrictCanonicalJson.sha256(withoutHash))
        return json.toString()
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.classLoader?.getResource(path)) { "Missing test resource $path" }.readText()

    private fun <T> Future<T>.await(): T = get(10, TimeUnit.SECONDS)

    private class CountingIdentifiers : CoreIdentifierGenerator {
        private var next = 0

        override fun next(prefix: String): String = prefix + (next++).toString().padStart(8, '0')
    }

    private class SequencedCaptureClock(
        wallSamples: List<Long>,
        monotonicSamples: List<Long>,
    ) : RuntimeCaptureClock {
        private val walls = ArrayDeque(wallSamples)
        private val monotonic = ArrayDeque(monotonicSamples)
        val calls = mutableListOf<String>()

        override fun wallNowEpochMillis(): Long {
            calls += "wall"
            return checkNotNull(walls.pollFirst()) { "Unexpected wall clock sample" }
        }

        override fun elapsedRealtimeNanos(): Long {
            calls += "elapsed"
            return checkNotNull(monotonic.pollFirst()) { "Unexpected monotonic clock sample" }
        }
    }

    private class FakeCaptureClock(
        var wallEpochMillis: Long,
        var monotonicNanos: Long,
    ) : RuntimeCaptureClock {
        var expireAfterSamples: Int? = null
        var expiredMonotonicNanos: Long = monotonicNanos
        private var samples = 0

        override fun wallNowEpochMillis(): Long = wallEpochMillis

        override fun elapsedRealtimeNanos(): Long {
            samples += 1
            val threshold = expireAfterSamples
            return if (threshold != null && samples > threshold) expiredMonotonicNanos else monotonicNanos
        }
    }

    private companion object {
        const val ISSUED = "2026-08-04T00:00:00.000Z"
        const val NOW = "2026-08-04T00:01:00.000Z"
        const val LATER = "2026-08-04T00:02:00.000Z"
        const val EVEN_LATER = "2026-08-04T00:03:00.000Z"
        val NOW_MS: Long = Instant.parse(NOW).toEpochMilli()
    }
}
