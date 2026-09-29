package dev.elu.analytics.internal.runtime

/** Local coverage, never capture authority. OS/process identifiers are not event properties. */
internal data class RuntimeDiagnosticsEpoch(
    val id: String,
    val streamId: String,
    val identityRevision: Long,
    val bootCount: Long,
    val startedWallMillis: Long,
    val startedUptimeNanos: Long,
    val startedElapsedNanos: Long,
    val launchTimings: Boolean,
) {
    init {
        require(id.isNotBlank() && id.length <= 128 && streamId.isNotBlank() && streamId.length <= 128)
        require(identityRevision >= 0 && bootCount >= 0 && startedWallMillis >= 0 &&
            startedUptimeNanos >= 0 && startedElapsedNanos >= startedUptimeNanos)
    }
}

internal data class RuntimeDiagnosticsState(
    val epoch: RuntimeDiagnosticsEpoch? = null,
    val lastLaunchUptimeNanos: Long? = null,
) {
    init {
        require(lastLaunchUptimeNanos == null ||
            (epoch != null && lastLaunchUptimeNanos >= epoch.startedUptimeNanos))
    }

    fun encode(): ByteArray {
        val value = org.json.JSONObject().put("epoch", epoch?.let {
            org.json.JSONObject().put("id", it.id).put("streamId", it.streamId)
                .put("identityRevision", it.identityRevision).put("bootCount", it.bootCount)
                .put("startedWallMillis", it.startedWallMillis).put("startedUptimeNanos", it.startedUptimeNanos)
                .put("startedElapsedNanos", it.startedElapsedNanos).put("launchTimings", it.launchTimings)
        } ?: org.json.JSONObject.NULL).put("lastLaunchUptimeNanos", lastLaunchUptimeNanos ?: org.json.JSONObject.NULL)
        return value.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 2048) }
    }

    companion object {
        fun decode(bytes: ByteArray): RuntimeDiagnosticsState = try {
            require(bytes.size in 1..2048)
            val value = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            val epoch = if (value.isNull("epoch")) null else value.getJSONObject("epoch").let {
                RuntimeDiagnosticsEpoch(it.getString("id"), it.getString("streamId"), it.getLong("identityRevision"),
                    it.getLong("bootCount"), it.getLong("startedWallMillis"), it.getLong("startedUptimeNanos"),
                    it.getLong("startedElapsedNanos"), it.getBoolean("launchTimings"))
            }
            RuntimeDiagnosticsState(epoch, if (value.isNull("lastLaunchUptimeNanos")) null
                else value.getLong("lastLaunchUptimeNanos")).also {
                require(it.encode().contentEquals(bytes)) { "Noncanonical diagnostics state" }
            }
        } catch (error: Exception) { throw RuntimeQueueCorruptionException("Invalid diagnostics state", error) }
    }
}

internal data class RuntimeDiagnosticsClockReading(
    val bootCount: Long,
    val wallMillis: Long,
    val uptimeNanos: Long,
    val elapsedNanos: Long,
) {
    fun valid(): Boolean = bootCount >= 0 && wallMillis >= 0 && uptimeNanos >= 0 && elapsedNanos >= uptimeNanos
    fun continues(epoch: RuntimeDiagnosticsEpoch): Boolean = valid() && bootCount == epoch.bootCount &&
        wallMillis >= epoch.startedWallMillis && uptimeNanos >= epoch.startedUptimeNanos &&
        elapsedNanos >= epoch.startedElapsedNanos
}

internal fun interface RuntimeDiagnosticsClock {
    /** Missing or inaccessible boot/time values deny historical coverage. */
    fun read(): RuntimeDiagnosticsClockReading?
}

internal data class RuntimeDiagnosticsConfiguration(
    val enabled: Boolean = false,
    val launchTimings: Boolean = false,
)

internal const val RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET = 24
internal fun runtimeDatabaseFeatureOffset(version: Long): Int = when (version) {
    in 1L..6L -> 0
    in 7L..12L -> RUNTIME_AUDIENCE_SCHEMA_OFFSET
    in 25L..30L -> RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET
    in 31L..36L -> RUNTIME_PERSON_SCHEMA_OFFSET
    else -> throw UnsupportedRuntimeStorageSchemaException(version)
}
