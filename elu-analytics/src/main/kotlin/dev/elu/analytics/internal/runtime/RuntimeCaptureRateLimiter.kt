package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluRateLimitingOptions
import org.json.JSONObject

internal const val RUNTIME_CAPTURE_RATE_SCHEMA_OFFSET = 42
internal const val MAX_RUNTIME_CAPTURE_RATE_BYTES = 256

internal data class RuntimeCaptureRateBucket(val tokens: Double, val last: Double) {
    init { require(tokens.isFinite() && last.isFinite()) }
}

/** Independent metadata: changing this never changes core generation or anonymous identity. */
internal data class RuntimeCaptureRateState(val streamId: String, val bucket: RuntimeCaptureRateBucket?) {
    init { RuntimePersonState(streamId, streamId) }

    fun encodeBucket(): ByteArray = (bucket?.let { JSONObject().put("tokens", it.tokens).put("last", it.last).toString() }
        ?: "null").toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_RUNTIME_CAPTURE_RATE_BYTES) }

    companion object {
        fun decode(streamId: String, bytes: ByteArray): RuntimeCaptureRateState = try {
            require(bytes.size in 1..MAX_RUNTIME_CAPTURE_RATE_BYTES)
            val text = String(bytes, Charsets.UTF_8)
            val bucket = if (text == "null") null else JSONObject(text).let {
                require(it.get("tokens") is Number && it.get("last") is Number)
                RuntimeCaptureRateBucket(it.getDouble("tokens"), it.getDouble("last"))
            }
            RuntimeCaptureRateState(streamId, bucket).also { require(it.encodeBucket().contentEquals(bytes)) }
        } catch (error: Exception) { throw RuntimeQueueCorruptionException("Invalid capture rate state", error) }
    }
}

internal data class RuntimeCaptureRateDecision(val bucket: RuntimeCaptureRateBucket, val limited: Boolean, val warn: Boolean)

/** Exact selected-store-first browser arithmetic, including signed wall-clock debt. */
internal class RuntimeCaptureRateLimiter(val options: EluRateLimitingOptions) {
    private var held: RuntimeCaptureRateBucket? = null
    private var previouslyLimited = false

    fun check(stored: RuntimeCaptureRateBucket?, now: Double, checkOnly: Boolean = false): RuntimeCaptureRateDecision {
        require(now.isFinite())
        val previous = stored ?: held ?: RuntimeCaptureRateBucket(options.eventsBurstLimit, now)
        val refilled = previous.tokens + ((now - previous.last) / 1000.0) * options.eventsPerSecond
        require(!refilled.isNaN()) { "Invalid capture rate clock arithmetic" }
        var tokens = when {
            refilled == Double.NEGATIVE_INFINITY -> -Double.MAX_VALUE
            refilled > options.eventsBurstLimit -> options.eventsBurstLimit
            else -> refilled
        }
        val limited = tokens < 1.0
        if (!limited && !checkOnly) tokens = maxOf(0.0, tokens - 1.0)
        val warn = limited && !previouslyLimited && !checkOnly
        previouslyLimited = limited
        val bucket = RuntimeCaptureRateBucket(tokens, now)
        held = bucket
        return RuntimeCaptureRateDecision(bucket, limited, warn)
    }

    fun warningMessage(): String = "Analytics SDK client rate limited. Config is set to " +
        "${number(options.eventsPerSecond)} events per second and ${number(options.eventsBurstLimit)} events burst limit."

    private fun number(value: Double): String = JSONObject.numberToString(value)
}

/** One original call may renew authority, but never acquire another debit or a caller bypass. */
internal class RuntimeCaptureRateAttempt(
    val filter: RuntimeEventFilterAttempt = RuntimeEventFilterAttempt(),
) {
    private var owner: Any? = null
    private var command: RuntimeCaptureCommand? = null

    @Synchronized fun claim(originalOwner: Any, originalCommand: RuntimeCaptureCommand): Boolean {
        if (owner == null) { owner = originalOwner; command = originalCommand; return true }
        check(owner === originalOwner && command == originalCommand) { "Capture attempt was reused outside its original call" }
        return false
    }
}

/** Only a known no-BEGIN/rollback or read I/O failure can select the held bucket. */
internal class RuntimeCaptureRateStorageUnavailable(cause: Throwable) : java.io.IOException("Capture rate storage unavailable", cause)
