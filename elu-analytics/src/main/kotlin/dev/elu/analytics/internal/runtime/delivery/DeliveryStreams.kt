package dev.elu.analytics.internal.runtime.delivery

import dev.elu.analytics.EluPersistenceMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/**
 * The stream a queued record stream is sent on, and the head the server is holding a stream gap
 * at. [wireStreamId] equals [sourceStreamId] until the first move; [movedAtEpochMillis] is when
 * delivery moved to [wireStreamId] (wall clock), and null until then.
 */
internal data class DeliveryStreamState(
    val sourceStreamId: String,
    val wireStreamId: String,
    val movedAtEpochMillis: Long? = null,
    val gap: DeliveryStreamGap? = null,
) {
    init {
        require(sourceStreamId.length in 1..DeliveryStreams.MAXIMUM_ID_LENGTH) { "sourceStreamId length is invalid" }
        require(wireStreamId.length in 1..DeliveryStreams.MAXIMUM_ID_LENGTH) { "wireStreamId length is invalid" }
        require((wireStreamId == sourceStreamId) == (movedAtEpochMillis == null)) {
            "movedAtEpochMillis is set exactly when delivery has moved"
        }
        require(movedAtEpochMillis == null || movedAtEpochMillis >= 0) { "movedAtEpochMillis must be non-negative" }
    }
}

/**
 * The server answered `stream-gap` for the record at [sequence] on the current wire stream
 * [answers] times, the first at [firstAnsweredAtEpochMillis] (wall clock, so the wait survives a
 * process restart).
 */
internal data class DeliveryStreamGap(
    val sequence: Long,
    val recordId: String,
    val firstAnsweredAtEpochMillis: Long,
    val answers: Int,
) {
    init {
        require(sequence >= 0) { "sequence must be non-negative" }
        require(recordId.length in 1..DeliveryStreams.MAXIMUM_ID_LENGTH) { "recordId length is invalid" }
        require(firstAnsweredAtEpochMillis >= 0) { "firstAnsweredAtEpochMillis must be non-negative" }
        require(answers >= 1) { "answers must be positive" }
    }
}

/** Durable home of [DeliveryStreamState]. Reads and writes happen on the delivery executor. */
internal interface DeliveryStreamStore {
    /** The stored state, or null when there is none or it cannot be used. */
    @Throws(IOException::class)
    fun read(): DeliveryStreamState?

    /** Replaces the stored state; once this returns, a process death keeps the new state. */
    @Throws(IOException::class)
    fun write(state: DeliveryStreamState)
}

/** For a runtime that keeps nothing on disk, and for tests. */
internal class MemoryDeliveryStreamStore(
    @Volatile private var state: DeliveryStreamState? = null,
) : DeliveryStreamStore {
    override fun read(): DeliveryStreamState? = state

    override fun write(state: DeliveryStreamState) {
        this.state = state
    }
}

/**
 * A small sidecar file next to the runtime queue, under the same namespace lease. It is replaced
 * by writing and syncing a temporary file and renaming it over the old one, so a process death
 * leaves either the previous state or the new one. A missing, oversized or malformed file reads as
 * no state: the worst outcome is that a replaced stream is forgotten, and a stuck head is then
 * recovered again by [DeliveryStreams].
 */
internal class FileDeliveryStreamStore(
    private val file: File,
) : DeliveryStreamStore {
    private val temporary = File(file.path + ".tmp")

    override fun read(): DeliveryStreamState? {
        if (!file.isFile) return null
        val bytes =
            FileInputStream(file).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAXIMUM_BYTES) return null
                }
                output.toByteArray()
            }
        return decode(bytes)
    }

    override fun write(state: DeliveryStreamState) {
        val bytes = encode(state)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("Delivery stream state could not be replaced")
    }

    internal companion object {
        const val FILE_NAME = "delivery-stream-v1.json"
        private const val MAXIMUM_BYTES = 2_048
        private val stateFields = setOf("schemaVersion", "sourceStreamId", "wireStreamId", "movedAt", "gap")
        private val gapFields = setOf("sequence", "recordId", "firstAnsweredAt", "answers")

        fun encode(state: DeliveryStreamState): ByteArray =
            buildString {
                append("{\"schemaVersion\":1,\"sourceStreamId\":")
                append(JSONObject.quote(state.sourceStreamId))
                append(",\"wireStreamId\":")
                append(JSONObject.quote(state.wireStreamId))
                append(",\"movedAt\":")
                append(state.movedAtEpochMillis?.toString() ?: "null")
                append(",\"gap\":")
                val gap = state.gap
                if (gap == null) {
                    append("null")
                } else {
                    append("{\"sequence\":")
                    append(gap.sequence)
                    append(",\"recordId\":")
                    append(JSONObject.quote(gap.recordId))
                    append(",\"firstAnsweredAt\":")
                    append(gap.firstAnsweredAtEpochMillis)
                    append(",\"answers\":")
                    append(gap.answers)
                    append('}')
                }
                append('}')
            }.toByteArray(Charsets.UTF_8)

        /** Accepts only the exact bytes [encode] writes; anything else is no state. */
        fun decode(bytes: ByteArray): DeliveryStreamState? {
            if (bytes.isEmpty() || bytes.size > MAXIMUM_BYTES) return null
            val decoded =
                try {
                    val root = JSONObject(String(bytes, Charsets.UTF_8))
                    if (root.fieldNames() != stateFields || root.get("schemaVersion") != 1) return null
                    val gap =
                        if (root.isNull("gap")) {
                            null
                        } else {
                            val value = root.get("gap") as? JSONObject ?: return null
                            if (value.fieldNames() != gapFields) return null
                            DeliveryStreamGap(
                                sequence = exactLong(value.get("sequence")) ?: return null,
                                recordId = value.get("recordId") as? String ?: return null,
                                firstAnsweredAtEpochMillis = exactLong(value.get("firstAnsweredAt")) ?: return null,
                                answers = (exactLong(value.get("answers")) ?: return null).let { answers ->
                                    if (answers > Int.MAX_VALUE) return null else answers.toInt()
                                },
                            )
                        }
                    DeliveryStreamState(
                        sourceStreamId = root.get("sourceStreamId") as? String ?: return null,
                        wireStreamId = root.get("wireStreamId") as? String ?: return null,
                        movedAtEpochMillis = if (root.isNull("movedAt")) null else exactLong(root.get("movedAt")) ?: return null,
                        gap = gap,
                    )
                } catch (_: JSONException) {
                    return null
                } catch (_: IllegalArgumentException) {
                    return null
                }
            return decoded.takeIf { encode(it).contentEquals(bytes) }
        }

        private fun exactLong(value: Any?): Long? =
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }

        private fun JSONObject.fieldNames(): Set<String> {
            val keys = LinkedHashSet<String>()
            val iterator = keys()
            while (iterator.hasNext()) keys += iterator.next()
            return keys
        }
    }
}

/**
 * Chooses the stream each queued record stream is sent on.
 *
 * The server accepts a stream's records only in sequence order: it keeps the next sequence it
 * expects and answers `stream-gap` (retryable, nothing stored) for any batch that starts past it.
 * A record the SDK resolves locally without sending it therefore leaves a gap the server waits on
 * for good, and every later record of that stream would be refused. Three things prevent that:
 *
 * - Before a record is discarded locally, [rotate] moves delivery to a stream the server has
 *   never seen. The server starts a new stream at the first sequence it receives, so the next
 *   record is accepted where it is.
 * - An installation whose stream already has such a gap (from an earlier version, or a state file
 *   lost to a crash) is recovered by [streamGapAnswered]: when the same head has been answered
 *   `stream-gap` at least [rotateAfterAnswers] times over at least [rotateAfterMillis], delivery
 *   moves to a new stream and the head is sent again there.
 * - A head close to the server's idempotency retention is never resent on a stream the server may
 *   have stored it on that long ago, since its acceptance row may be gone: [rotateForAge] moves
 *   delivery first, unless delivery moved recently.
 *
 * Records keep their sequence and record identity on the new stream; only the stream named in the
 * request and in each event changes. The server's idempotency is per stream, so a record it
 * already stored on the old stream, whose acknowledgement was lost, can be stored again on the
 * new one. The state, with the time of the last move, is written before it is used, so after a
 * process death the SDK resumes on the same stream. A write that fails still takes effect for this
 * process; a later process at worst goes back to the stuck stream and recovers it again.
 */
internal class DeliveryStreams(
    private val store: DeliveryStreamStore,
    private val newStreamId: () -> String = { randomStreamId() },
    private val rotateAfterMillis: Long = STREAM_GAP_ROTATE_AFTER_MILLIS,
    private val rotateAfterAnswers: Int = STREAM_GAP_ROTATE_AFTER_ANSWERS,
) {
    private val lock = Any()
    private var loaded = false
    private var state: DeliveryStreamState? = null

    init {
        require(rotateAfterMillis >= 0) { "rotateAfterMillis must be non-negative" }
        require(rotateAfterAnswers >= 1) { "rotateAfterAnswers must be positive" }
    }

    /** The stream to send records of [sourceStreamId] on. */
    fun wireStreamId(sourceStreamId: String): String =
        synchronized(lock) { stateFor(sourceStreamId)?.wireStreamId ?: sourceStreamId }

    /** Call before a record of [sourceStreamId] is resolved without the server's outcome. */
    fun rotate(
        sourceStreamId: String,
        nowEpochMillis: Long,
    ): String = synchronized(lock) { moveLocked(sourceStreamId, nowEpochMillis) }

    /**
     * Call before a head that is [RESEND_AGE_LIMIT_SECONDS] old or older is sent. The server may
     * have swept the acceptance row of a record it stored about [IDEMPOTENCY_RETENTION_SECONDS]
     * ago, and would then answer a resend of it `record-identity-conflict`, a permanent answer that
     * blocks the whole queue. On a stream the server has never seen the record is simply stored as
     * `record-too-old`. Returns true when delivery has moved to a new stream, so the head should be
     * packed again.
     *
     * Every record first sent on a stream delivery moved to was sent after the move, so the server
     * keeps its acceptance row until at least [IDEMPOTENCY_RETENTION_SECONDS] after it. A stream
     * moved to less than [AGED_REUSE_MILLIS] ago (for any reason, and also in an earlier process) is
     * therefore kept, and the aged records behind the head share one move.
     */
    fun rotateForAge(
        sourceStreamId: String,
        nowEpochMillis: Long,
    ): Boolean =
        synchronized(lock) {
            val movedAt = stateFor(sourceStreamId)?.movedAtEpochMillis
            // A clock that went back cannot tell how old the stream is: move again.
            if (movedAt != null && nowEpochMillis >= movedAt && nowEpochMillis - movedAt < AGED_REUSE_MILLIS) {
                return false
            }
            moveLocked(sourceStreamId, nowEpochMillis)
            true
        }

    /**
     * The server answered `stream-gap` for the record ([sequence], [recordId]) sent on
     * [wireStreamId]. Returns true when delivery has moved to a new stream, so the record should
     * be sent again at once.
     */
    fun streamGapAnswered(
        sourceStreamId: String,
        wireStreamId: String,
        sequence: Long,
        recordId: String,
        nowEpochMillis: Long,
    ): Boolean =
        synchronized(lock) {
            val stored = stateFor(sourceStreamId)
            val current = stored?.wireStreamId ?: sourceStreamId
            if (wireStreamId != current) return false
            val previous = stored?.gap?.takeIf { it.sequence == sequence && it.recordId == recordId }
            val gap =
                when {
                    previous == null -> DeliveryStreamGap(sequence, recordId, maxOf(nowEpochMillis, 0L), 1)
                    // The wall clock moved back: measure the wait again from now.
                    nowEpochMillis < previous.firstAnsweredAtEpochMillis ->
                        previous.copy(firstAnsweredAtEpochMillis = maxOf(nowEpochMillis, 0L), answers = previous.answers.next())
                    else -> previous.copy(answers = previous.answers.next())
                }
            if (gap.answers >= rotateAfterAnswers && nowEpochMillis - gap.firstAnsweredAtEpochMillis >= rotateAfterMillis) {
                moveLocked(sourceStreamId, nowEpochMillis)
                return true
            }
            save(DeliveryStreamState(sourceStreamId, current, stored?.movedAtEpochMillis, gap))
            false
        }

    /** Records of [sourceStreamId] through [sequence] have the server's outcome. */
    fun resolvedThrough(
        sourceStreamId: String,
        sequence: Long,
    ) {
        synchronized(lock) {
            val current = stateFor(sourceStreamId) ?: return
            val gap = current.gap ?: return
            if (gap.sequence <= sequence) save(current.copy(gap = null))
        }
    }

    private fun wireStreamIdLocked(sourceStreamId: String): String = stateFor(sourceStreamId)?.wireStreamId ?: sourceStreamId

    private fun moveLocked(
        sourceStreamId: String,
        nowEpochMillis: Long,
    ): String {
        val replacement = freshStreamId(sourceStreamId, wireStreamIdLocked(sourceStreamId))
        save(DeliveryStreamState(sourceStreamId, replacement, maxOf(nowEpochMillis, 0L), gap = null))
        return replacement
    }

    private fun stateFor(sourceStreamId: String): DeliveryStreamState? {
        if (!loaded) {
            state =
                try {
                    store.read()
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            loaded = true
        }
        return state?.takeIf { it.sourceStreamId == sourceStreamId }
    }

    private fun save(next: DeliveryStreamState) {
        state = next
        loaded = true
        try {
            store.write(next)
        } catch (_: IOException) {
            // Kept in memory for this process; see the class comment.
        } catch (_: SecurityException) {
        }
    }

    private fun freshStreamId(
        sourceStreamId: String,
        currentWireStreamId: String,
    ): String {
        repeat(MAXIMUM_GENERATION_ATTEMPTS) {
            // Never longer than the record stream's own ID: an event names its stream in its body,
            // so a longer one could push a record that fits a batch on one stream past the limit.
            // Real IDs are "stream_" plus 32 hex digits, so the cut keeps all 32 random digits. A
            // much shorter source ID would leave few random characters: other installations could
            // then share a replacement, and a clash with this one's own IDs ends in the exception.
            val candidate = newStreamId().take(sourceStreamId.length)
            if (candidate.length in 1..MAXIMUM_ID_LENGTH && candidate != sourceStreamId && candidate != currentWireStreamId) {
                return candidate
            }
        }
        throw IllegalStateException("Could not generate a new delivery stream ID")
    }

    private fun Int.next(): Int = if (this == Int.MAX_VALUE) this else this + 1

    internal companion object {
        /**
         * How long the same head must keep getting `stream-gap` before delivery moves to a new
         * stream. A server that closes such gaps itself does so after 30 minutes; this waits 15
         * minutes longer, so a gap that server can close keeps its stream even if the clocks
         * disagree.
         */
        const val STREAM_GAP_ROTATE_AFTER_MILLIS = 45L * 60L * 1_000L

        /** And at least this many such answers, so one stale answer after a long pause never rotates. */
        const val STREAM_GAP_ROTATE_AFTER_ANSWERS = 5

        /**
         * How long the server keeps the acceptance row of a record, counted from its first answer
         * to it (8 days).
         */
        const val IDEMPOTENCY_RETENTION_SECONDS = 691_200

        /**
         * A head this old or older is resent only on a stream delivery moved to recently (see
         * [rotateForAge]). Twelve hours inside [IDEMPOTENCY_RETENTION_SECONDS] for a clock that
         * changed since the record occurred, the margin the browser SDK keeps too.
         */
        const val RESEND_AGE_LIMIT_SECONDS = 648_000

        /**
         * A stream delivery moved to less than this long ago is kept for a head past
         * [RESEND_AGE_LIMIT_SECONDS]: a day inside [IDEMPOTENCY_RETENTION_SECONDS].
         */
        const val AGED_REUSE_MILLIS = 7L * 24L * 60L * 60L * 1_000L

        /** The server's limit for a stream or record ID. */
        const val MAXIMUM_ID_LENGTH = 256

        private const val MAXIMUM_GENERATION_ATTEMPTS = 8

        fun randomStreamId(): String = "stream_" + UUID.randomUUID().toString().replace("-", "")

        /**
         * The delivery streams of a runtime whose queue is [queueDatabaseFile]: kept in
         * [FileDeliveryStreamStore.FILE_NAME] next to a persistent queue, under the same namespace
         * lease, so a restart resumes on the same stream, and in memory for a memory queue.
         */
        fun forQueue(
            persistence: EluPersistenceMode,
            queueDatabaseFile: File,
        ): DeliveryStreams =
            DeliveryStreams(
                if (persistence == EluPersistenceMode.PERSISTENT) {
                    FileDeliveryStreamStore(File(queueDatabaseFile.canonicalFile.parentFile, FileDeliveryStreamStore.FILE_NAME))
                } else {
                    MemoryDeliveryStreamStore()
                },
            )
    }
}
