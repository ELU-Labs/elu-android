package dev.elu.analytics.internal.compat

import dev.elu.analytics.internal.config.V1StrictCanonicalJson
import dev.elu.analytics.internal.core.CoreEpochClock
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.JsonValues
import dev.elu.analytics.internal.core.MigrationState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StartupMigrationCheckpoint
import dev.elu.analytics.internal.core.StreamState
import dev.elu.analytics.internal.core.StartupHistoryLedger
import dev.elu.analytics.internal.core.StartupHistoryRecord
import dev.elu.analytics.internal.runtime.RuntimeStoredRecord
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val ANDROID_LEGACY_SOURCE_SCHEMA = "elu-android-0.1.0/posthog-android-3.58.0"

/** Closed reasons: no legacy property, token, path or raw parser error is logged. */
internal enum class AndroidLegacyStartupRefusal {
    SOURCE_UNAVAILABLE, SOURCE_CHANGED, SOURCE_SHAPE, SOURCE_TOO_LARGE, QUEUE_NOT_EMPTY,
    MAPPING_UNAVAILABLE, IDENTITY_AMBIGUOUS, CHECKPOINT_MISMATCH, CLOCK_ROLLBACK,
}

internal class AndroidLegacyStartupException(val reason: AndroidLegacyStartupRefusal) :
    IllegalStateException("Legacy Android startup migration refused: " + reason.name)

internal fun legacyStartupRefuse(reason: AndroidLegacyStartupRefusal): Nothing =
    throw AndroidLegacyStartupException(reason)

/** Captured by the bounded source reader; source bytes are never changed. */
internal data class AndroidLegacyStartupSnapshot(
    val publicToken: String,
    val fingerprint: String,
    val preferences: Map<String, Any?>,
    val configurationDisabled: Boolean = false,
    val pendingHistory: List<AndroidLegacyHistoryEntry> = emptyList(),
)

internal fun interface AndroidLegacyStartupSource {
    /** Null means no evidenced ELU legacy installation, not an unreadable source. */
    fun observe(): AndroidLegacyStartupSnapshot?
}

/** Called only by the existing SQLite owner before channel publication. */
internal class LegacyAndroidStartupMigration(
    private val source: AndroidLegacyStartupSource,
    private val identifiers: CoreIdentifierGenerator,
    private val clock: CoreEpochClock,
    private val assertCurrent: () -> Unit,
) {
    private var completedState: PersistedCoreState? = null
    private var completedPublicToken: String? = null
    private var completedRecords: List<RuntimeStoredRecord> = emptyList()

    fun prepare(): PersistedCoreState? {
        assertCurrent()
        val captured = source.observe() ?: return null
        val now = clock.nowEpochMillis()
        assertCurrent()
        return state(captured, identifiers.next("stream_"), timestamp(now))
    }

    fun complete(pending: PersistedCoreState): PersistedCoreState {
        val checkpoint = pending.startupMigration
            ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.CHECKPOINT_MISMATCH)
        if (checkpoint.sourceSchema != ANDROID_LEGACY_SOURCE_SCHEMA) {
            legacyStartupRefuse(AndroidLegacyStartupRefusal.CHECKPOINT_MISMATCH)
        }
        assertCurrent()
        val current = source.observe() ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_CHANGED)
        if (current.publicToken != checkpoint.publicToken || current.fingerprint != checkpoint.sourceFingerprint) {
            legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_CHANGED)
        }
        // The durable pending identity must also match the observed source, not just its hash.
        if (state(current, pending.stream.streamId, pending.identity.updatedAt) != pending) {
            legacyStartupRefuse(AndroidLegacyStartupRefusal.CHECKPOINT_MISMATCH)
        }
        val now = clock.nowEpochMillis()
        if (now < parseTimestamp(pending.identity.updatedAt)) {
            legacyStartupRefuse(AndroidLegacyStartupRefusal.CLOCK_ROLLBACK)
        }
        assertCurrent()
        val completedAt = timestamp(now)
        val imported = if (current.pendingHistory.isEmpty()) emptyList() else
            LegacyAndroidPendingHistory.convert(current.pendingHistory, pending.stream.streamId, completedAt)
        val ledger = if (imported.isEmpty()) null else StartupHistoryLedger(
            ANDROID_LEGACY_SOURCE_SCHEMA, checkpoint.sourceFingerprint, pending.stream.streamId, completedAt,
            imported.map { source -> StartupHistoryRecord(source.row.sequence, source.row.kind.wireValue,
                source.row.recordId, MessageDigest.getInstance("SHA-256").digest(source.row.internalPayload)
                    .joinToString("") { "%02x".format(it) },
                source.sourceFilename, source.sourceSha256, source.sourceModifiedAt, source.occurredAt, source.sourceSessionId) },
        )
        val count = imported.size.toLong()
        val result = pending.copy(
            startupMigration = null, startupHistory = ledger,
            stream = pending.stream.copy(nextSequence = count),
            identity = pending.identity.copy(
                revision = if (ledger == null) pending.identity.revision else count,
                contextRevision = if (ledger == null) pending.identity.contextRevision else count,
                migration = MigrationState(ANDROID_LEGACY_SOURCE_SCHEMA, completedAt)),
        )
        // Freeze through the real aggregate codec; later source reads cannot change this result.
        val canonical = CoreStateCodec.decode(CoreStateCodec.encode(result))
        assertCurrent()
        completedRecords = imported.map { it.row.copy(internalPayload = it.row.internalPayload.copyOf()) }
        completedPublicToken = current.publicToken
        completedState = canonical
        return canonical
    }

    /** Same owner lane only; a foreign/completion-free state cannot obtain admitted rows. */
    fun recordsFor(completed: PersistedCoreState): List<RuntimeStoredRecord> {
        assertCurrent()
        if (completed != completedState || completed.startupMigration != null || completed.startupHistory == null) {
            legacyStartupRefuse(AndroidLegacyStartupRefusal.CHECKPOINT_MISMATCH)
        }
        // Last source observation precedes atomic insertion. This proves the fixed reader's
        // stable snapshot; it cannot create a filesystem/database atomic transaction.
        val observed = source.observe() ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_CHANGED)
        if (observed.publicToken != completedPublicToken || observed.fingerprint != completed.startupHistory.sourceFingerprint) {
            legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_CHANGED)
        }
        assertCurrent()
        return completedRecords.map { it.copy(internalPayload = it.internalPayload.copyOf()) }
    }

    private fun state(snapshot: AndroidLegacyStartupSnapshot, streamId: String, updatedAt: String): PersistedCoreState {
        if (!Regex("[A-Za-z0-9_-]{1,512}").matches(snapshot.publicToken) ||
            !Regex("[a-f0-9]{64}").matches(snapshot.fingerprint)) {
            legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
        }
        val values = snapshot.preferences
        val anonymous = requiredText(values["anonymousId"], 256)
        val distinct = if (values.containsKey("distinctId")) requiredText(values["distinctId"], 512) else anonymous
        val identified = if (values.containsKey("isIdentified")) requiredBoolean(values["isIdentified"]) else distinct != anonymous
        // Explicit false is authoritative. Conflicting distinct identity cannot be silently dropped.
        if (!identified && distinct != anonymous) legacyStartupRefuse(AndroidLegacyStartupRefusal.IDENTITY_AMBIGUOUS)
        val storedOptOut = if (values.containsKey("opt-out")) requiredBoolean(values["opt-out"]) else false
        val optedOut = storedOptOut || snapshot.configurationDisabled
        val stringified = if (values.containsKey("stringifiedKeys")) {
            val value = values["stringifiedKeys"] as? Set<*> ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
            if (value.any { it !is String }) legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
            value.filterIsInstance<String>().toSet()
        } else emptySet()
        fun property(key: String): Any? {
            val value = values[key]
            return if (key == "groups" || key in stringified) {
                val text = value as? String ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
                try { jsonValue(V1StrictCanonicalJson.parse(text)) }
                catch (failure: AndroidLegacyStartupException) { throw failure }
                catch (_: IllegalArgumentException) { legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE) }
            } else value
        }
        fun objectProperty(key: String): Map<String, Any?> {
            if (!values.containsKey(key)) return emptyMap()
            val value = property(key) as? Map<*, *> ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
            if (value.keys.any { it !is String }) legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }
        val groups = linkedMapOf<String, String>()
        for ((key, value) in objectProperty("groups")) groups[key] = requiredText(value, 512)
        val superProperties = linkedMapOf<String, Any?>()
        for (key in values.keys) if (key !in INTERNAL_KEYS) superProperties[key] = property(key)
        val groupProperties = linkedMapOf<String, Map<String, Any?>>()
        for ((key, value) in objectProperty("groupPropertiesForFlags")) {
            val objectValue = value as? Map<*, *> ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
            if (objectValue.keys.any { it !is String }) legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
            @Suppress("UNCHECKED_CAST")
            groupProperties[key] = objectValue as Map<String, Any?>
        }
        val result = PersistedCoreState(
            identity = IdentityState(
                revision = 0, contextRevision = 0, anonymousId = anonymous,
                userId = if (identified) distinct else null, groups = groups,
                superProperties = JsonValues.objectValue(superProperties, "legacy super properties"),
                session = null, optedOut = optedOut, updatedAt = updatedAt,
            ),
            stream = StreamState(streamId = streamId, nextSequence = 0),
            flagContext = FlagContextState(
                personProperties = JsonValues.objectValue(objectProperty("personPropertiesForFlags"), "legacy flag properties"),
                groupProperties = groupProperties,
            ),
            startupMigration = StartupMigrationCheckpoint(ANDROID_LEGACY_SOURCE_SCHEMA, snapshot.fingerprint, snapshot.publicToken),
        )
        // The actual aggregate codec is the destination shape and size authority.
        return CoreStateCodec.decode(CoreStateCodec.encode(result))
    }

    private fun requiredText(value: Any?, maximum: Int): String {
        val text = value as? String ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
        if (text.codePointCount(0, text.length) !in 1..maximum) legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
        return text
    }

    private fun requiredBoolean(value: Any?): Boolean =
        value as? Boolean ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)

    private fun jsonValue(value: V1StrictCanonicalJson.Value): Any? = when (value) {
        is V1StrictCanonicalJson.Value.ObjectValue -> value.members.associateTo(linkedMapOf()) { it.first to jsonValue(it.second) }
        is V1StrictCanonicalJson.Value.ArrayValue -> value.values.map(::jsonValue)
        is V1StrictCanonicalJson.Value.StringValue -> value.value
        is V1StrictCanonicalJson.Value.BooleanValue -> value.value
        is V1StrictCanonicalJson.Value.NumberValue -> value.token.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: legacyStartupRefuse(AndroidLegacyStartupRefusal.SOURCE_SHAPE)
        V1StrictCanonicalJson.Value.NullValue -> null
    }

    private fun formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private fun timestamp(value: Long): String {
        if (value < 0) legacyStartupRefuse(AndroidLegacyStartupRefusal.CLOCK_ROLLBACK)
        return formatter().format(Date(value))
    }
    private fun parseTimestamp(value: String): Long {
        val parser = formatter(); val position = ParsePosition(0)
        val parsed = parser.parse(value, position)
        if (parsed == null || position.index != value.length || parser.format(parsed) != value) {
            legacyStartupRefuse(AndroidLegacyStartupRefusal.CHECKPOINT_MISMATCH)
        }
        return parsed.time
    }

    companion object {
        // Exact common PostHog 6.29.0 ALL_INTERNAL_KEYS, required by the retained Android 3.58.0 POM.
        private val INTERNAL_KEYS = setOf(
            "groups", "anonymousId", "distinctId", "isIdentified", "personProcessingEnabled", "opt-out",
            "featureFlags", "featureFlagsPayload", "sessionReplay", "surveys", "surveySeen", "lastSeenSurveyDate",
            "version", "build", "deviceId", "stringifiedKeys", "feature_flag_request_id", "feature_flag_evaluated_at",
            "minimal_flag_called_events", "flags", "personPropertiesForFlags", "groupPropertiesForFlags",
            "errorTracking", "capturePerformance",
        )
    }
}
