package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ExactTimestamp
import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.config.V1StrictCanonicalJson.Value
import java.util.UUID

/** Durable accounting values only. No decoded value grants source or recorder permission. */
internal data class NativeReplaySessionState(
    val namespaceHash: String,
    val streamId: String,
    val nextReplayOrdinal: Long = 0,
    val session: Session? = null,
) {
    internal data class Key(val siteId: String, val sessionId: String, val sessionStartedAt: String)

    internal data class Session(
        val key: Key,
        val samplingHash: String,
        val originalSampleRate: Double,
        val originalSelected: Boolean,
        val firstStartAt: String?,
        val maximumDurationSeconds: Int,
        val elapsedFloorMicroseconds: Long,
        val observedWallAt: String,
        val clockDenied: Boolean,
        val interrupted: Boolean,
        val activeEpoch: String?,
    ) {
        val remainingMicroseconds: Long
            get() = maxOf(0, maximumDurationSeconds * 1_000_000L - elapsedFloorMicroseconds)
        val remainingWholeSeconds: Int get() = (remainingMicroseconds / 1_000_000).toInt()
        fun selected(currentRate: Double): Boolean =
            originalSelected && selected(samplingHash, currentRate)
    }

    internal data class Allocation(val state: NativeReplaySessionState, val replayId: String)
    internal data class Stop(val state: NativeReplaySessionState, val matched: Boolean)

    fun validate(): NativeReplaySessionState {
        require(namespaceHash.matches(Regex("[a-f0-9]{64}")))
        identifier(streamId)
        require(nextReplayOrdinal in 0..MAX_REPLAY_SAFE_INTEGER)
        session?.let { s ->
            identifier(s.key.siteId); identifier(s.key.sessionId)
            require(validRate(s.originalSampleRate))
            require(s.maximumDurationSeconds in 0..86_400)
            require(s.elapsedFloorMicroseconds in 0..MAXIMUM_MICROSECONDS)
            require(s.samplingHash == samplingHash(s.key))
            require(s.originalSelected == selected(s.samplingHash, s.originalSampleRate))
            val start = timestamp(s.key.sessionStartedAt)
            val observed = timestamp(s.observedWallAt)
            require(observed >= start)
            require(s.activeEpoch == null || validEpoch(s.activeEpoch))
            require(s.activeEpoch == null || (s.originalSelected && s.firstStartAt != null))
            require(s.firstStartAt != null || s.elapsedFloorMicroseconds == 0L)
            s.firstStartAt?.let { first ->
                require(s.originalSelected)
                val instant = timestamp(first)
                require(instant >= start && observed >= instant)
            }
        }
        return this
    }

    fun encoded(): ByteArray {
        validate()
        val bytes = ReplayJson.encode(
            "schemaVersion" to ReplayJson.number(1), "namespaceHash" to ReplayJson.text(namespaceHash),
            "streamId" to ReplayJson.text(streamId), "nextReplayOrdinal" to ReplayJson.number(nextReplayOrdinal),
            "session" to (session?.let { s -> Value.ObjectValue(listOf(
                "siteId" to ReplayJson.text(s.key.siteId), "sessionId" to ReplayJson.text(s.key.sessionId),
                "sessionStartedAt" to ReplayJson.text(s.key.sessionStartedAt),
                "samplingHash" to ReplayJson.text(s.samplingHash),
                "originalSampleRate" to Value.NumberValue(s.originalSampleRate.toString()),
                "originalSelected" to Value.BooleanValue(s.originalSelected),
                "firstStartAt" to nullableText(s.firstStartAt),
                "maximumDurationSeconds" to ReplayJson.number(s.maximumDurationSeconds.toLong()),
                "elapsedFloorMicroseconds" to ReplayJson.number(s.elapsedFloorMicroseconds),
                "observedWallAt" to ReplayJson.text(s.observedWallAt),
                "clockDenied" to Value.BooleanValue(s.clockDenied),
                "interrupted" to Value.BooleanValue(s.interrupted), "activeEpoch" to nullableText(s.activeEpoch),
            )) } ?: Value.NullValue),
        )
        require(bytes.size in 1..MAXIMUM_BYTES)
        return bytes
    }

    fun allocateReplayId(): Allocation {
        validate()
        require(nextReplayOrdinal < MAX_REPLAY_SAFE_INTEGER) { "Native replay ordinal exhausted" }
        val hash = hashArray(listOf(ReplayJson.text("elu-native-replay-id-v1"), ReplayJson.text(namespaceHash),
            ReplayJson.text(streamId), ReplayJson.number(nextReplayOrdinal)))
        return Allocation(copy(nextReplayOrdinal = nextReplayOrdinal + 1), "replay_" + hash.removePrefix("sha256:"))
    }

    /** An observation may restrict the original draw and cap, but does not begin recording. */
    fun observe(key: Key, sampleRate: Double, maximumDurationSeconds: Int, wallAt: String,
        ownedEpoch: String?, continuousElapsedMicroseconds: Long?): NativeReplaySessionState {
        validate()
        identifier(key.siteId); identifier(key.sessionId)
        require(validRate(sampleRate) && maximumDurationSeconds in 0..86_400)
        val wall = timestamp(wallAt)
        require(wall >= timestamp(key.sessionStartedAt))
        continuousElapsedMicroseconds?.let { require(it in 0..MAXIMUM_MICROSECONDS) }
        var s = session?.takeIf { it.key == key } ?: run {
            val hash = samplingHash(key)
            Session(key, hash, if (sampleRate == 0.0) 0.0 else sampleRate, selected(hash, sampleRate), null,
                maximumDurationSeconds, 0, wallAt, false, false, null)
        }
        s = s.copy(maximumDurationSeconds = minOf(s.maximumDurationSeconds, maximumDurationSeconds),
            interrupted = s.interrupted || (s.activeEpoch != null && s.activeEpoch != ownedEpoch))
        s = if (wall < timestamp(s.observedWallAt)) s.copy(clockDenied = true)
        else s.copy(observedWallAt = wallAt, elapsedFloorMicroseconds = maxOf(s.elapsedFloorMicroseconds,
            s.firstStartAt?.let { checkNotNull(elapsedCeilMicroseconds(timestamp(it), wall)) } ?: 0))
        if (s.firstStartAt != null && continuousElapsedMicroseconds != null) {
            s = s.copy(elapsedFloorMicroseconds = maxOf(s.elapsedFloorMicroseconds, continuousElapsedMicroseconds))
        }
        return copy(session = s).validate()
    }

    /** Caller must commit the returned state before constructing any executable start permit. */
    fun begin(epoch: String, wallAt: String, currentRate: Double): NativeReplaySessionState {
        validate()
        var s = session ?: return this
        if (!s.selected(currentRate) || s.clockDenied || s.interrupted || s.activeEpoch != null ||
            s.remainingMicroseconds == 0L || !validEpoch(epoch)) return this
        val wall = timestamp(wallAt)
        if (wall < timestamp(s.observedWallAt)) return this
        if (s.firstStartAt != null) {
            val elapsed = elapsedCeilMicroseconds(timestamp(s.firstStartAt), wall) ?: return this
            s = s.copy(elapsedFloorMicroseconds = maxOf(s.elapsedFloorMicroseconds, elapsed))
            if (s.remainingMicroseconds == 0L) return copy(session = s.copy(observedWallAt = wallAt)).validate()
        }
        return copy(session = s.copy(firstStartAt = s.firstStartAt ?: wallAt,
            observedWallAt = wallAt, activeEpoch = epoch)).validate()
    }

    /** Match the complete original receipt before inspecting clocks or restricting any session. */
    fun stop(key: Key, firstStartAt: String, epoch: String, wallAt: String?,
        elapsedMicroseconds: Long?): Stop {
        validate()
        var s = session
        if (s == null || s.key != key || s.firstStartAt != firstStartAt || s.activeEpoch != epoch) return Stop(this, false)
        val wall = wallAt?.let { runCatching { timestamp(it) }.getOrNull() }
        val elapsed = wall?.let { elapsedCeilMicroseconds(timestamp(firstStartAt), it) }
        s = if (wall != null && elapsed != null && wall >= timestamp(s.observedWallAt)) {
            s.copy(observedWallAt = checkNotNull(wallAt), elapsedFloorMicroseconds = maxOf(s.elapsedFloorMicroseconds, elapsed))
        } else s.copy(clockDenied = true)
        s = if (elapsedMicroseconds != null && elapsedMicroseconds in 0..MAXIMUM_MICROSECONDS) {
            s.copy(elapsedFloorMicroseconds = maxOf(s.elapsedFloorMicroseconds, elapsedMicroseconds))
        } else s.copy(clockDenied = true)
        return Stop(copy(session = s.copy(activeEpoch = null)).validate(), true)
    }

    fun denyClock(key: Key): NativeReplaySessionState {
        validate()
        val s = session?.takeIf { it.key == key } ?: return this
        return copy(session = s.copy(clockDenied = true))
    }

    companion object {
        const val MAXIMUM_BYTES = 16_384
        const val MAXIMUM_MICROSECONDS = 86_400_000_000L
        private val rootKeys = setOf("schemaVersion", "namespaceHash", "streamId", "nextReplayOrdinal", "session")
        private val sessionKeys = setOf("siteId", "sessionId", "sessionStartedAt", "samplingHash", "originalSampleRate",
            "originalSelected", "firstStartAt", "maximumDurationSeconds", "elapsedFloorMicroseconds",
            "observedWallAt", "clockDenied", "interrupted", "activeEpoch")

        fun decode(input: ByteArray): NativeReplaySessionState {
            require(input.size in 1..MAXIMUM_BYTES)
            val bytes = input.copyOf()
            val value = ReplayJson.parse(bytes).obj(rootKeys)
            require(V1StrictCanonicalJson.canonicalBytes(value).contentEquals(bytes))
            require(value.number("schemaVersion") == 1L)
            val session = when (val nested = value.get("session")) {
                Value.NullValue -> null
                else -> {
                    val s = nested.obj(sessionKeys)
                    val rate = (s.get("originalSampleRate") as? Value.NumberValue)?.token?.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Invalid native sample rate")
                    val cap = s.number("maximumDurationSeconds").also { require(it <= 86_400) }.toInt()
                    Session(Key(s.string("siteId", 1, 256), s.string("sessionId", 1, 256), s.string("sessionStartedAt", 1, 128)),
                        s.hash("samplingHash"), rate, s.boolean("originalSelected"), s.nullableString("firstStartAt", 128),
                        cap, s.number("elapsedFloorMicroseconds"), s.string("observedWallAt", 1, 128),
                        s.boolean("clockDenied"), s.boolean("interrupted"), s.nullableString("activeEpoch", 36))
                }
            }
            return NativeReplaySessionState(value.string("namespaceHash", 64, 64), value.string("streamId", 1, 256),
                value.number("nextReplayOrdinal"), session).validate()
        }

        fun samplingHash(key: Key): String {
            identifier(key.siteId); identifier(key.sessionId); timestamp(key.sessionStartedAt)
            return hashArray(listOf(ReplayJson.text("elu-native-session-replay-sampling-v1"), ReplayJson.text(key.siteId),
                ReplayJson.text(key.sessionId), ReplayJson.text(key.sessionStartedAt)))
        }

        fun selected(hash: String, rate: Double): Boolean = validRate(rate) &&
            hash.matches(Regex("sha256:[a-f0-9]{64}")) &&
            hash.substring(7, 20).toLong(16).toDouble() / 4_503_599_627_370_496.0 < rate

        /** Round the original exact interval once; refresh calls do not accumulate rounding. */
        fun elapsedCeilMicroseconds(start: V1ExactTimestamp, end: V1ExactTimestamp): Long? {
            if (start.isLeapSecond || end.isLeapSecond || end < start) return null
            val seconds = try { Math.subtractExact(end.epochWholeSecond, start.epochWholeSecond) }
                catch (_: ArithmeticException) { return MAXIMUM_MICROSECONDS }
            if (seconds > 86_401) return MAXIMUM_MICROSECONDS
            fun micros(value: V1ExactTimestamp) = value.fractionalDigits.take(6).padEnd(6, '0').toLong()
            val left = start.fractionalDigits.drop(6)
            val right = end.fractionalDigits.drop(6)
            val width = maxOf(left.length, right.length)
            val extra = if (right.padEnd(width, '0') > left.padEnd(width, '0')) 1 else 0
            return (seconds * 1_000_000 + micros(end) - micros(start) + extra).coerceAtMost(MAXIMUM_MICROSECONDS)
        }

        private fun timestamp(source: String): V1ExactTimestamp {
            require(source.length in 1..128)
            return V1ConfigJson.parseExactTimestamp(source).also { require(!it.isLeapSecond) }
        }
        private fun identifier(value: String) {
            require(value.codePointCount(0, value.length) in 1..256)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (Character.isHighSurrogate(character)) {
                    require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))
                    index += 2
                } else {
                    require(!Character.isLowSurrogate(character))
                    index += 1
                }
            }
        }
        private fun validRate(rate: Double) = rate.isFinite() && rate in 0.0..1.0
        private fun validEpoch(value: String): Boolean = runCatching {
            value.length == 36 && UUID.fromString(value).toString().equals(value, ignoreCase = true)
        }.getOrDefault(false)
        private fun hashArray(values: List<Value>) = ReplayJson.digest(V1StrictCanonicalJson.canonicalBytes(Value.ArrayValue(values)))
        private fun nullableText(value: String?) = value?.let(ReplayJson::text) ?: Value.NullValue
        private fun Value.ObjectValue.nullableString(key: String, maximum: Int): String? =
            if (get(key) == Value.NullValue) null else string(key, 1, maximum)
    }
}
