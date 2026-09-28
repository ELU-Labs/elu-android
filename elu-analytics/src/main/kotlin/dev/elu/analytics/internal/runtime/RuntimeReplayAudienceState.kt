package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.core.SessionState

/** Installation history, deliberately independent of resettable identity and replay accounting. */
internal sealed interface RuntimeReplayAudienceState {
    /** An older owned store cannot establish whether an earlier capture session existed. */
    data object Unknown : RuntimeReplayAudienceState
    data object Unseen : RuntimeReplayAudienceState
    data class FirstSession(val sessionId: String, val startedAt: String) : RuntimeReplayAudienceState {
        init {
            require(sessionId.isNotEmpty() && sessionId.length <= 256)
            require(startedAt.length <= 128)
            V1ConfigJson.parseExactTimestamp(startedAt)
        }
    }

    /** Called only while preparing a session-bearing event's atomic durable transaction. */
    fun observe(session: SessionState): RuntimeReplayAudienceState =
        if (this === Unseen) FirstSession(session.id, session.startedAt) else this

    fun permits(session: SessionState?): Boolean =
        this is FirstSession && session != null && sessionId == session.id && startedAt == session.startedAt
}

/** Schema 7–12 adds installation audience history to the original six table combinations. */
internal const val RUNTIME_AUDIENCE_SCHEMA_OFFSET: Int = 6
internal const val RUNTIME_DATABASE_SCHEMA_VERSION_WITH_AUDIENCE: Int = 7

internal fun runtimeBaseDatabaseVersion(version: Long): Long {
    if (version !in 1L..12L) throw UnsupportedRuntimeStorageSchemaException(version)
    return if (version > RUNTIME_AUDIENCE_SCHEMA_OFFSET) version - RUNTIME_AUDIENCE_SCHEMA_OFFSET else version
}
