package dev.elu.analytics.internal.replay

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import dev.elu.analytics.compose.EluAnnotatedReplayRoot
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.SessionLifecycle
import dev.elu.analytics.internal.core.SessionState
import dev.elu.analytics.internal.runtime.RuntimePlatform
import dev.elu.analytics.internal.runtime.RuntimeVersionComponent
import dev.elu.analytics.internal.runtime.RuntimeVersions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual collector/sealer requests; controlled clocks isolate response grammar, not UI latency. */
@SdkSuppress(minSdkVersion = 29)
class AndroidNativeRasterResponseTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var host: View
    private lateinit var collector: AndroidAnnotatedReplayCollector
    private var installed = false
    private var nextAttempt = 1_000_000_000L

    @Test fun onlyExactSchemaThreeHttp200AcknowledgesTheOriginalSealedRequest() {
        val request = prepared()
        assertEquals("elu-native-raster-v1", request.codec)
        assertEquals("native-raster-generation-v1", request.captureProtocolGeneration)
        val body = encoded(ack(request))
        assertEquals(NativeRasterResponseOutcome.Accepted, classify(200, body, request))
        val equivalent = body.toString(Charsets.UTF_8)
            .replace("\"schemaVersion\":3", "\"schemaVersion\":3.0")
            .replace("\"sequence\":0", "\"sequence\":0e0").toByteArray()
        assertEquals(NativeRasterResponseOutcome.Accepted, classify(200, equivalent, request))
        for (status in listOf(201, 202, 204, 206, 299, 400, 409, 413, 429, 500)) {
            assertEquals("HTTP $status", NativeRasterResponseOutcome.ProtocolBlocked, classify(status, body, request))
        }
        for (schema in listOf(1, 2, 4)) {
            assertBlocked(200, ack(request).put("schemaVersion", schema), request)
        }
    }

    @Test fun everyAckIdentityIsBoundAndRepeatedClassificationLeavesOriginalBytesUnchanged() {
        val request = prepared(); val other = prepared("other-epoch")
        for ((key, value) in listOf("requestId" to "request_${"b".repeat(64)}", "replayId" to "other",
            "chunkId" to "other", "sequence" to 1, "result" to "other")) {
            assertBlocked(200, ack(request).put(key, value), request)
        }
        assertBlocked(200, ack(other), request); assertBlocked(200, ack(request), other)
        val original = request.copyBytes(); val wire = encoded(ack(request))
        val response = ReplayTransportResponse(200, wire)
        repeat(3) { assertEquals(NativeRasterResponseOutcome.Accepted,
            NativeRasterResponseClassifier.classify(response, request, 0, 0)) }
        assertArrayEquals(original, request.copyBytes()); assertArrayEquals(wire, response.copyBody())
        original.fill(0); wire.fill(0)
    }

    @Test fun utf16EscapesMatchButCanonicalEquivalentUnicodeIdentityDoesNot() {
        val request = prepared("r\u00e9play-\uD83D\uDE00")
        val escaped = ack(request).toString().replace("\u00e9", "\\u00e9")
            .replace("\uD83D\uDE00", "\\ud83d\\ude00")
        assertEquals(NativeRasterResponseOutcome.Accepted, classify(200, escaped.toByteArray(), request))
        assertBlocked(200, ack(request).put("replayId", "re\u0301play-\uD83D\uDE00"), request)
    }

    @Test fun closedAckStrictUtf8AndExactSafeIntegerRulesRejectAmbiguity() {
        val request = prepared()
        for (key in ack(request).keys().asSequence().toList()) {
            val missing = ack(request); missing.remove(key); assertBlocked(200, missing, request)
        }
        for (value in listOf(true, JSONObject.NULL, "0", -1, 0.5, 9_007_199_254_740_992L)) {
            assertBlocked(200, ack(request).put("sequence", value), request)
        }
        assertBlocked(200, ack(request).put("extra", true), request)
        val text = ack(request).toString()
        for (invalid in listOf(text.dropLast(1) + ",\"schemaVersion\":3}",
            text.dropLast(1) + ",\"schema\\u0056ersion\":3}", text + "false", "[]", "null",
            "{\"bad\":\"\\ud800\"}", "\uFEFF$text")) {
            assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(200, invalid.toByteArray(), request))
        }
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(200, byteArrayOf(0xC0.toByte(), 0xAF.toByte()), request))
    }

    @Test fun exactBoundedResponseAndOriginalCredentialRefusalPrecedenceRemain() {
        val request = prepared(); val body = encoded(ack(request))
        val exact = body + ByteArray(REPLAY_MAX_RESPONSE_BYTES - body.size) { 0x20 }
        assertEquals(NativeRasterResponseOutcome.Accepted, classify(200, exact, request))
        assertThrows(IllegalArgumentException::class.java) { ReplayTransportResponse(200, exact + byteArrayOf(0x20)) }
        for (status in listOf(401, 403)) {
            assertEquals(NativeRasterResponseOutcome.CredentialBlocked(status),
                classify(status, ByteArray(REPLAY_MAX_RESPONSE_BYTES) { 0xFF.toByte() }, request, "invalid"))
        }
        // Ambiguous raw headers are rejected by the original transport before this singular value.
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(200, byteArrayOf(), request))
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(200, body, request, "0"))
    }

    @Test fun onlyClosedOriginalSchemaThreeConflictsExposeTheirScope() {
        val request = prepared()
        for ((wire, scope) in listOf("request" to NativeRasterConflictScope.REQUEST,
            "chunk" to NativeRasterConflictScope.CHUNK, "sequence" to NativeRasterConflictScope.SEQUENCE)) {
            val body = encoded(conflict(request, wire))
            assertEquals(NativeRasterResponseOutcome.IdentityConflict(scope), classify(409, body, request))
            for (status in listOf(200, 201, 400, 413, 429, 500)) {
                assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(status, body, request))
            }
        }
    }

    @Test fun crossedMissingUnknownOrRetryableConflictsNeverResolveTheOriginalRequest() {
        val request = prepared()
        for ((key, value) in listOf("schemaVersion" to 2, "requestId" to "request_${"b".repeat(64)}",
            "status" to 200, "code" to "other", "disposition" to "retryable", "conflictScope" to "session",
            "message" to "not-in-closed-conflict")) {
            assertBlocked(409, conflict(request).put(key, value), request)
        }
        for (key in conflict(request).keys().asSequence().toList()) {
            val missing = conflict(request); missing.remove(key); assertBlocked(409, missing, request)
        }
        val text = conflict(request).toString()
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked,
            classify(409, (text.dropLast(1) + ",\"requestId\":\"other\"}").toByteArray(), request))
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(409, encoded(conflict(request)), request, "1"))
    }

    @Test fun schemaOneSizeRefusalKeepsExactOptionalRequestIdentity() {
        val request = prepared()
        assertEquals(NativeRasterResponseOutcome.RejectedTooLarge, classify(413, encoded(error(413, request)), request))
        val absent = error(413, request); absent.remove("requestId")
        assertEquals(NativeRasterResponseOutcome.RejectedTooLarge, classify(413, encoded(absent), request))
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(413, encoded(absent), request, "1"))
        for ((key, value) in listOf("schemaVersion" to 3, "status" to 429, "requestId" to "other",
            "requestId" to JSONObject.NULL, "disposition" to "retryable")) {
            assertBlocked(413, error(413, request).put(key, value), request)
        }
    }

    @Test fun retryAfterDatesClampAndOriginalBackoffArePreservedWithoutInventedAck() {
        val request = prepared(); val body = encoded(error(429, request))
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(429, body, request))
        assertEquals(NativeRasterResponseOutcome.Retry(2_000, true), classify(429, body, request, "2"))
        assertEquals(NativeRasterResponseOutcome.Retry(REPLAY_MAX_RETRY_MILLIS, true), classify(429, body, request, "999999"))
        assertEquals(NativeRasterResponseOutcome.Retry(3_000, true), classify(429, body, request, "Thu, 01 Jan 1970 00:00:03 GMT"))
        assertEquals(NativeRasterResponseOutcome.Retry(4_000, true), classify(429, body, request, "2", 4_000))
        assertEquals(NativeRasterResponseOutcome.Retry(0, true), classify(429, body, request, "0", -1))
        for (bad in listOf("invalid", "-1", "1.5", "1, 2")) {
            assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(429, body, request, bad))
        }
    }

    @Test fun serverFailuresRequireBoundedStructuredOriginalSchemaOneErrors() {
        val request = prepared()
        for (status in listOf(500, 503, 599)) {
            val body = encoded(error(status, request))
            assertEquals(NativeRasterResponseOutcome.Retry(0), classify(status, body, request))
            assertEquals(NativeRasterResponseOutcome.Retry(3_000), classify(status, body, request, "3"))
        }
        for ((key, value) in listOf("message" to "", "message" to "x".repeat(257), "code" to "Upper",
            "code" to "x".repeat(65), "requestId" to JSONObject.NULL, "status" to 500,
            "schemaVersion" to 3, "disposition" to "permanent", "extra" to true)) {
            assertBlocked(503, error(503, request).put(key, value), request)
        }
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(503, byteArrayOf(), request))
    }

    private fun ack(request: NativeRasterPreparedRequest) = JSONObject().put("schemaVersion", 3)
        .put("requestId", request.requestId).put("replayId", request.replayId).put("chunkId", request.chunkId)
        .put("sequence", request.sequence).put("result", "accepted")
    private fun conflict(request: NativeRasterPreparedRequest, scope: String = "request") = JSONObject()
        .put("schemaVersion", 3).put("requestId", request.requestId).put("status", 409)
        .put("code", "replay-identity-conflict").put("disposition", "permanent").put("conflictScope", scope)
    private fun error(status: Int, request: NativeRasterPreparedRequest) = JSONObject()
        .put("schemaVersion", 1).put("requestId", request.requestId).put("status", status).put("code", "fixture-error")
        .put("disposition", if (status == 413) "retry-after-reduction" else "retryable").put("message", "fixture")
    private fun encoded(value: JSONObject) = value.toString().toByteArray(Charsets.UTF_8)
    private fun classify(status: Int, body: ByteArray, request: NativeRasterPreparedRequest,
        retryAfter: String? = null, backoff: Long = 0) =
        NativeRasterResponseClassifier.classify(ReplayTransportResponse(status, body, retryAfter), request, 0, backoff)
    private fun assertBlocked(status: Int, body: JSONObject, request: NativeRasterPreparedRequest) {
        assertEquals(NativeRasterResponseOutcome.ProtocolBlocked, classify(status, encoded(body), request))
    }

    private fun prepared(replayId: String = "raster-response-epoch"): NativeRasterPreparedRequest {
        if (!installed) {
            rule.setContent {
                EluAnnotatedReplayRoot(emptyList(), Modifier.size(64.dp).background(Color.Red)) {
                    val view = LocalView.current; SideEffect { host = view }
                    Box(Modifier.size(16.dp).background(Color.Blue))
                }
            }
            rule.waitForIdle()
            rule.runOnIdle { collector = AndroidAnnotatedReplayCollector(checkNotNull(AnnotatedRootRegistry.fromHost(host))) }
            installed = true
        }
        // Compose idleness alone does not establish real Window focus/readiness.
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                host.rootView === rule.activity.window.peekDecorView() && host.isAttachedToWindow &&
                    host.hasWindowFocus() && host.isLaidOut && !host.isLayoutRequested && host.width > 0 && host.height > 0
            }
        }
        val frame = rule.runOnIdle {
            val at = nextAttempt; nextAttempt += AndroidAnnotatedReplayCollector.INTERVAL_NANOS
            collector.capture(rule.activity.window, { true }) { at }
        }
        return frame.use {
            val time = "2024-01-01T00:00:00.000Z"
            val identity = IdentityState(revision = 3, contextRevision = 7, anonymousId = "anon-raster", userId = "user-raster",
                groups = emptyMap(), superProperties = emptyMap(),
                session = SessionState("session-raster", time, time, 1800, lifecycle = SessionLifecycle.ACTIVE, backgroundedAt = null),
                optedOut = false, updatedAt = time)
            val versions = RuntimeVersions(platform = RuntimePlatform.ANDROID,
                runtime = RuntimeVersionComponent("elu-android", "1.0.0"), facade = RuntimeVersionComponent("EluAnalytics", "1.0.0"))
            NativeRasterSealer(replayId, identity,
                NativeRasterPolicyBinding("policy-1", "sha256:" + "a".repeat(64), 7, MAX_REPLAY_REQUEST_BYTES),
                versions, frame.sourceIdentity, { true }).seal(frame, 1_704_067_200_000L)
        }
    }
}
