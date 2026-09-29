package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.replay.NativeReplayAccounting
import dev.elu.analytics.internal.core.CoreStateCodec

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File
import java.io.IOException

internal interface AndroidRuntimeDatabaseFaults {
    fun connectionConfigured(settings: AndroidRuntimeConnectionSettings) = Unit

    fun beforeCommit() = Unit

    fun afterCommit() = Unit

    data object None : AndroidRuntimeDatabaseFaults
}

internal data class AndroidRuntimeConnectionSettings(
    val journalMode: String,
    val synchronous: Long,
    val busyTimeoutMillis: Long,
)

/** System-SQLite implementation. One runtime worker owns an instance for its full lifetime. */
internal class AndroidSQLiteRuntimeDatabase private constructor(
    private val sqlite: SQLiteDatabase,
    private val ownerThread: Thread,
    private val faults: AndroidRuntimeDatabaseFaults,
) : RuntimeQueueDatabase {
    override fun ensurePersonSchema() {
        assertOwnerThread()
        val version = pragmaLong(sqlite, "PRAGMA user_version")
        val base = runtimeBaseDatabaseVersion(version)
        validateSchemaObjects(sqlite, version)
        if (version > RUNTIME_PERSON_SCHEMA_OFFSET) return
        check(version > RUNTIME_AUDIENCE_SCHEMA_OFFSET) { "Person metadata requires validated audience schema" }
        sqlite.beginTransaction()
        var markedSuccessful = false
        try {
            val core = SQLiteTransaction(sqlite).readCore() ?: error("Person metadata requires an owned core")
            check(core.person == null)
            if (version <= RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET) {
                sqlite.execSQL(CREATE_DIAGNOSTICS)
                writeDiagnostics(sqlite, RuntimeDiagnosticsState(), insert = true)
            }
            sqlite.execSQL(CREATE_PERSON)
            writePerson(sqlite, RuntimePersonState.initial(CoreStateCodec.decode(core.stateJson)), insert = true)
            executePragma(sqlite, "PRAGMA user_version = ${base + RUNTIME_PERSON_SCHEMA_OFFSET}")
            faults.beforeCommit()
            sqlite.setTransactionSuccessful(); markedSuccessful = true
        } finally {
            try { sqlite.endTransaction() }
            catch (error: Throwable) {
                if (markedSuccessful) throw AmbiguousRuntimeCommitException("Uncertain person schema transaction", error)
                throw error
            }
        }
        try { faults.afterCommit() }
        catch (error: Throwable) { throw AmbiguousRuntimeCommitException("Uncertain person schema durability", error) }
        validateSchemaObjects(sqlite, base + RUNTIME_PERSON_SCHEMA_OFFSET)
    }

    override fun initialReplayAudienceState(): RuntimeReplayAudienceState {
        assertOwnerThread()
        val version = pragmaLong(sqlite, "PRAGMA user_version")
        runtimeBaseDatabaseVersion(version)
        return if (version > RUNTIME_AUDIENCE_SCHEMA_OFFSET) RuntimeReplayAudienceState.Unseen else RuntimeReplayAudienceState.Unknown
    }

    override fun ensureReplayAudienceSchema() {
        assertOwnerThread()
        val version = pragmaLong(sqlite, "PRAGMA user_version")
        runtimeBaseDatabaseVersion(version)
        validateSchemaObjects(sqlite, version)
        if (version > RUNTIME_AUDIENCE_SCHEMA_OFFSET) return
        sqlite.beginTransaction()
        var markedSuccessful = false
        try {
            // The owner has already validated every existing core/queue/replay payload.
            check(SQLiteTransaction(sqlite).readCore() != null) { "Audience upgrade requires an owned core" }
            sqlite.execSQL(CREATE_AUDIENCE)
            writeAudience(sqlite, RuntimeReplayAudienceState.Unknown, insert = true)
            executePragma(sqlite, "PRAGMA user_version = ${version + RUNTIME_AUDIENCE_SCHEMA_OFFSET}")
            faults.beforeCommit()
            sqlite.setTransactionSuccessful(); markedSuccessful = true
        } finally {
            try { sqlite.endTransaction() }
            catch (error: Throwable) {
                if (markedSuccessful) throw AmbiguousRuntimeCommitException("Uncertain audience schema transaction", error)
                throw error
            }
        }
        try { faults.afterCommit() }
        catch (error: Throwable) { throw AmbiguousRuntimeCommitException("Uncertain audience schema durability", error) }
        validateSchemaObjects(sqlite, version + RUNTIME_AUDIENCE_SCHEMA_OFFSET)
    }

    override fun ensureDiagnosticsSchema() {
        assertOwnerThread()
        val version = pragmaLong(sqlite, "PRAGMA user_version")
        val base = runtimeBaseDatabaseVersion(version)
        validateSchemaObjects(sqlite, version)
        if (version > RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET) return
        check(version > RUNTIME_AUDIENCE_SCHEMA_OFFSET) { "Diagnostics require validated audience schema" }
        sqlite.beginTransaction()
        var markedSuccessful = false
        try {
            check(SQLiteTransaction(sqlite).readCore() != null) { "Diagnostics require owned core" }
            sqlite.execSQL(CREATE_DIAGNOSTICS)
            writeDiagnostics(sqlite, RuntimeDiagnosticsState(), insert = true)
            executePragma(sqlite, "PRAGMA user_version = ${base + RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET}")
            faults.beforeCommit()
            sqlite.setTransactionSuccessful(); markedSuccessful = true
        } finally {
            try { sqlite.endTransaction() }
            catch (error: Throwable) {
                if (markedSuccessful) throw AmbiguousRuntimeCommitException("Uncertain diagnostics schema transaction", error)
                throw error
            }
        }
        try { faults.afterCommit() }
        catch (error: Throwable) { throw AmbiguousRuntimeCommitException("Uncertain diagnostics schema durability", error) }
        validateSchemaObjects(sqlite, base + RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET)
    }

    override fun ensureFlagSchema(initialAuthority: RuntimeFlagStoredRow) {
        require(initialAuthority.key == RUNTIME_FLAG_AUTHORITY_KEY)
        ensureAdditiveSchema(false, initialAuthority.key, initialAuthority.storageSchemaVersion, initialAuthority.payload)
    }

    override fun ensureReplaySchema(initialState: RuntimeReplayStoredRow) {
        require(initialState.key == "state")
        ensureAdditiveSchema(true, initialState.key, initialState.storageSchemaVersion, initialState.payload)
    }

    override fun ensureNativeReplaySchema(initialAuthority: RuntimeReplayStoredRow) {
        assertOwnerThread()
        val initial = NativeReplayAccounting.read(initialAuthority)
        val version = pragmaLong(sqlite, "PRAGMA user_version")
        val baseVersion = runtimeBaseDatabaseVersion(version)
        validateSchemaObjects(sqlite, version)
        check(baseVersion in 3L..6L) { "Native accounting requires explicit replay storage" }
        if (baseVersion == 5L || baseVersion == 6L) {
            transaction { tx ->
                val current = NativeReplayAccounting.read(tx.readReplayRow(NativeReplayAccounting.KEY)
                    ?: corrupt("Missing native replay accounting"))
                if (current.namespaceHash != initial.namespaceHash || current.streamId != initial.streamId) {
                    corrupt("Native accounting installation mismatch")
                }
            }
            return
        }
        val target = (if (baseVersion == 3L) 5 else 6) + runtimeDatabaseFeatureOffset(version)
        sqlite.beginTransaction()
        var markedSuccessful = false
        try {
            val tx = SQLiteTransaction(sqlite)
            val core = tx.readCore() ?: corrupt("Native accounting without runtime core")
            if (CoreStateCodec.decode(core.stateJson).stream.streamId != initial.streamId) corrupt("Native accounting stream mismatch")
            if (tx.readReplayRow(NativeReplayAccounting.KEY) != null) corrupt("Native metadata precedes its schema")
            val values = ContentValues().apply {
                put("record_key", NativeReplayAccounting.KEY)
                put("storage_schema_version", 1L)
                put("payload", initial.encoded())
            }
            sqlite.insertOrThrow(REPLAY_TABLE, null, values)
            executePragma(sqlite, "PRAGMA user_version = $target")
            if (pragmaLong(sqlite, "PRAGMA user_version") != target.toLong()) corrupt("Native schema version was not persisted")
            faults.beforeCommit()
            sqlite.setTransactionSuccessful(); markedSuccessful = true
        } finally {
            try { sqlite.endTransaction() }
            catch (error: Throwable) {
                if (markedSuccessful) throw AmbiguousRuntimeCommitException("Uncertain native schema transaction", error)
                throw error
            }
        }
        try { faults.afterCommit() }
        catch (error: Throwable) { throw AmbiguousRuntimeCommitException("Uncertain native schema durability", error) }
        validateSchemaObjects(sqlite, target.toLong())
    }

    private fun ensureAdditiveSchema(replay: Boolean, key: String, rowVersion: Long, payload: ByteArray) {
        assertOwnerThread()
        require(rowVersion == 1L && payload.isNotEmpty())
        require(payload.size <= if (replay) REPLAY_ROW_BYTES else MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES)
        val version = pragmaLong(sqlite, "PRAGMA user_version")
        val baseVersion = runtimeBaseDatabaseVersion(version)
        validateSchemaObjects(sqlite, version)
        val alreadyPresent = if (replay) baseVersion in setOf(3L, 4L, 5L, 6L) else baseVersion in setOf(2L, 4L, 6L)
        if (alreadyPresent) return
        val target = (if (replay) { if (baseVersion == 1L) 3 else 4 } else { when (baseVersion) { 1L -> 2; 5L -> 6; else -> 4 } }) +
            runtimeDatabaseFeatureOffset(version)
        sqlite.beginTransaction()
        var markedSuccessful = false
        try {
            sqlite.execSQL(if (replay) CREATE_REPLAY else CREATE_FLAG_CACHE)
            val values = ContentValues().apply {
                put("record_key", key)
                put("storage_schema_version", rowVersion)
                put("payload", payload)
            }
            sqlite.insertOrThrow(if (replay) REPLAY_TABLE else FLAG_CACHE_TABLE, null, values)
            executePragma(sqlite, "PRAGMA user_version = $target")
            if (pragmaLong(sqlite, "PRAGMA user_version") != target.toLong()) corrupt("Additive schema version was not persisted")
            faults.beforeCommit()
            sqlite.setTransactionSuccessful()
            markedSuccessful = true
        } finally {
            try { sqlite.endTransaction() }
            catch (error: Throwable) {
                if (markedSuccessful) throw AmbiguousRuntimeCommitException("Uncertain additive schema transaction", error)
                throw error
            }
        }
        try { faults.afterCommit() }
        catch (error: Throwable) { throw AmbiguousRuntimeCommitException("Uncertain additive schema durability", error) }
        validateSchemaObjects(sqlite, target.toLong())
    }

    override fun <T> transaction(block: (RuntimeQueueTransaction) -> T): T {
        assertOwnerThread()
        sqlite.beginTransaction()
        val transaction = SQLiteTransaction(sqlite)
        var markedSuccessful = false
        try {
            val result = block(transaction)
            if (transaction.mutated) faults.beforeCommit()
            sqlite.setTransactionSuccessful()
            markedSuccessful = true
            try {
                sqlite.endTransaction()
            } catch (error: Throwable) {
                throw AmbiguousRuntimeCommitException(
                    "SQLite could not report a definitive transaction outcome",
                    error,
                )
            }
            if (transaction.mutated) {
                try {
                    faults.afterCommit()
                } catch (error: Throwable) {
                    throw AmbiguousRuntimeCommitException(
                        "SQLite commit durability was intentionally reported as ambiguous",
                        error,
                    )
                }
            }
            return result
        } catch (error: Throwable) {
            var rollbackReported = false
            if (sqlite.inTransaction()) {
                try {
                    sqlite.endTransaction()
                    rollbackReported = !markedSuccessful
                } catch (endError: Throwable) {
                    if (markedSuccessful) {
                        throw AmbiguousRuntimeCommitException(
                            "SQLite could not report a definitive transaction outcome",
                            endError,
                        ).apply { addSuppressed(error) }
                    }
                    throw AmbiguousRuntimeCommitException(
                        "SQLite could not prove that its unsuccessful transaction rolled back",
                        endError,
                    ).apply { addSuppressed(error) }
                }
            }
            if (transaction.mutated && !markedSuccessful && rollbackReported) {
                if (error is ProvenNotCommittedRuntimeTransactionException) throw error
                throw ProvenNotCommittedRuntimeTransactionException(
                    "SQLite explicitly rolled back a mutated runtime transaction",
                    error,
                )
            }
            throw error
        }
    }

    override fun close() {
        assertOwnerThread()
        sqlite.close()
    }

    private fun assertOwnerThread() {
        check(Thread.currentThread() === ownerThread) {
            "SQLite runtime database accessed outside its dedicated worker"
        }
    }

    private class SQLiteTransaction(private val sqlite: SQLiteDatabase) : RuntimeQueueTransaction {
        var mutated: Boolean = false
            private set

        override fun readCore(): RuntimeStoredCore? {
            requireTransaction()
            sqlite.query(
                CORE_TABLE,
                arrayOf("state_json", "queue_count", "queue_bytes"),
                "singleton_id = ?",
                arrayOf(SINGLETON_ID.toString()),
                null,
                null,
                null,
                "2",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_AUDIENCE_SCHEMA_OFFSET && readAudience(sqlite) != null)
                        corrupt("Audience history exists without an owned core")
                    if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET && readDiagnostics(sqlite) != null)
                        corrupt("Diagnostics history exists without an owned core")
                    if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_PERSON_SCHEMA_OFFSET && readPerson(sqlite) != null)
                        corrupt("Person state exists without an owned core")
                    return null
                }
                val core =
                    RuntimeStoredCore(
                        stateJson = cursor.requiredBlob(0, "core_state.state_json"),
                        queueCount = cursor.getLong(1),
                        queueBytes = cursor.getLong(2),
                        replayAudience = if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_AUDIENCE_SCHEMA_OFFSET)
                            readAudience(sqlite) ?: corrupt("Missing installation audience history")
                            else RuntimeReplayAudienceState.Unknown,
                        diagnostics = if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET)
                            readDiagnostics(sqlite) ?: corrupt("Missing diagnostics state") else RuntimeDiagnosticsState(),
                        person = if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_PERSON_SCHEMA_OFFSET)
                            readPerson(sqlite) ?: corrupt("Missing person state") else null,
                    )
                if (cursor.moveToNext()) corrupt("Runtime database contains duplicate core rows")
                return core
            }
        }

        override fun insertCore(core: RuntimeStoredCore) {
            requireTransaction()
            val version = pragmaLong(sqlite, "PRAGMA user_version")
            if (version <= RUNTIME_AUDIENCE_SCHEMA_OFFSET) {
                check(core.replayAudience === RuntimeReplayAudienceState.Unknown)
                sqlite.execSQL(CREATE_AUDIENCE)
                executePragma(sqlite, "PRAGMA user_version = ${version + RUNTIME_AUDIENCE_SCHEMA_OFFSET}")
            }
            val values =
                ContentValues().apply {
                    put("singleton_id", SINGLETON_ID)
                    put("state_json", core.stateJson)
                    put("queue_count", core.queueCount)
                    put("queue_bytes", core.queueBytes)
                }
            sqlite.insertOrThrow(CORE_TABLE, null, values)
            writeAudience(sqlite, core.replayAudience, insert = true)
            if (version > RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET) writeDiagnostics(sqlite, core.diagnostics, insert = true)
            else check(core.diagnostics == RuntimeDiagnosticsState())
            if (version > RUNTIME_PERSON_SCHEMA_OFFSET) writePerson(sqlite, checkNotNull(core.person), insert = true)
            else check(core.person == null)
            mutated = true
        }

        override fun updateCore(core: RuntimeStoredCore) {
            requireTransaction()
            val values =
                ContentValues().apply {
                    put("state_json", core.stateJson)
                    put("queue_count", core.queueCount)
                    put("queue_bytes", core.queueBytes)
                }
            val changed =
                sqlite.update(
                    CORE_TABLE,
                    values,
                    "singleton_id = ?",
                    arrayOf(SINGLETON_ID.toString()),
                )
            if (changed != 1) corrupt("Runtime core update did not affect exactly one row")
            if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_AUDIENCE_SCHEMA_OFFSET) {
                val previous = readAudience(sqlite) ?: corrupt("Missing installation audience history")
                if (previous != core.replayAudience) {
                    check(previous === RuntimeReplayAudienceState.Unseen && core.replayAudience is RuntimeReplayAudienceState.FirstSession) {
                        "Installation audience history cannot be reset or replaced"
                    }
                    writeAudience(sqlite, core.replayAudience, insert = false)
                }
            } else check(core.replayAudience === RuntimeReplayAudienceState.Unknown)
            if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET)
                writeDiagnostics(sqlite, core.diagnostics, insert = false)
            else check(core.diagnostics == RuntimeDiagnosticsState())
            if (pragmaLong(sqlite, "PRAGMA user_version") > RUNTIME_PERSON_SCHEMA_OFFSET)
                writePerson(sqlite, checkNotNull(core.person), insert = false)
            else check(core.person == null)
            mutated = true
        }

        override fun readRecord(sequence: Long): RuntimeStoredRecord? {
            requireTransaction()
            require(sequence >= 0) { "sequence must be non-negative" }
            sqlite.query(
                QUEUE_TABLE,
                RECORD_COLUMNS,
                "sequence = ?",
                arrayOf(sequence.toString()),
                null,
                null,
                null,
                "2",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val record = cursor.storedRecord()
                if (cursor.moveToNext()) corrupt("Runtime database contains duplicate queue sequences")
                return record
            }
        }

        override fun scanRecords(visitor: (RuntimeStoredRecord) -> Unit) {
            requireTransaction()
            sqlite.query(
                QUEUE_TABLE,
                RECORD_COLUMNS,
                null,
                null,
                null,
                null,
                "sequence ASC",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    visitor(cursor.storedRecord())
                }
            }
        }

        override fun insertRecord(record: RuntimeStoredRecord) {
            requireTransaction()
            require(record.internalPayload.isNotEmpty()) { "Runtime internal payload must not be empty" }
            require(record.internalPayload.size <= MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES) {
                "Runtime internal payload exceeds the Android SQLite row limit"
            }
            require(record.accountedBytes in 1..MAX_RUNTIME_QUEUE_BYTES.toInt()) {
                "Runtime accounted bytes are outside the supported queue range"
            }
            val values =
                ContentValues().apply {
                    put("sequence", record.sequence)
                    put("stream_id", record.streamId)
                    put("kind", record.kind.wireValue)
                    put("record_id", record.recordId)
                    put("internal_payload", record.internalPayload)
                    put("accounted_bytes", record.accountedBytes)
                }
            sqlite.insertOrThrow(QUEUE_TABLE, null, values)
            mutated = true
        }

        override fun deleteRecord(sequence: Long): Boolean {
            requireTransaction()
            val changed = sqlite.delete(QUEUE_TABLE, "sequence = ?", arrayOf(sequence.toString()))
            if (changed > 1) corrupt("Queue deletion affected more than one primary-key row")
            if (changed == 1) mutated = true
            return changed == 1
        }

        override fun replaySchemaPresent(): Boolean {
            requireTransaction()
            return runtimeBaseDatabaseVersion(pragmaLong(sqlite, "PRAGMA user_version")) in setOf(3L, 4L, 5L, 6L)
        }

        override fun nativeReplaySchemaPresent(): Boolean {
            requireTransaction()
            return runtimeBaseDatabaseVersion(pragmaLong(sqlite, "PRAGMA user_version")) in setOf(5L, 6L)
        }

        override fun readReplayRow(key: String): RuntimeReplayStoredRow? {
            requireTransaction()
            requireReplayTable()
            require(key.isNotEmpty()) { "Replay row key must not be empty" }
            sqlite.query(
                REPLAY_TABLE,
                REPLAY_ROW_COLUMNS,
                "record_key = ?",
                arrayOf(key),
                null,
                null,
                null,
                "2",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val row = cursor.replayRow()
                if (cursor.moveToNext()) corrupt("Runtime database contains duplicate replay row keys")
                return row
            }
        }

        override fun scanReplayRows(prefix: String, visitor: (RuntimeReplayStoredRow) -> Unit) {
            requireTransaction()
            requireReplayTable()
            sqlite.query(
                REPLAY_TABLE,
                REPLAY_ROW_COLUMNS,
                "substr(record_key, 1, ?) = ?",
                arrayOf(prefix.length.toString(), prefix),
                null,
                null,
                "record_key ASC",
                null,
            ).use { cursor -> while (cursor.moveToNext()) visitor(cursor.replayRow()) }
        }

        override fun putReplayRow(row: RuntimeReplayStoredRow) {
            requireTransaction()
            requireReplayTable()
            require(row.key.isNotEmpty() && row.key.length <= MAX_REPLAY_ROW_KEY_CHARS) {
                "Replay row key is outside its bounds"
            }
            require(row.storageSchemaVersion >= 1L) { "Replay row schema version must be positive" }
            require(row.payload.isNotEmpty() && row.payload.size <= REPLAY_ROW_BYTES) {
                "Replay row payload is outside the Android SQLite row limit"
            }
            val values =
                ContentValues().apply {
                    put("record_key", row.key)
                    put("storage_schema_version", row.storageSchemaVersion)
                    put("payload", row.payload)
                }
            sqlite.insertWithOnConflict(REPLAY_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE).also { inserted ->
                if (inserted == -1L) corrupt("Replay row upsert failed")
            }
            mutated = true
        }

        override fun deleteReplayRow(key: String): Boolean {
            requireTransaction()
            requireReplayTable()
            val changed = sqlite.delete(REPLAY_TABLE, "record_key = ?", arrayOf(key))
            if (changed > 1) corrupt("Replay row deletion affected more than one key")
            if (changed == 1) mutated = true
            return changed == 1
        }

        override fun readFlagRow(key: String): RuntimeFlagStoredRow? {
            requireTransaction()
            requireFlagTable()
            require(key.isNotEmpty()) { "Flag row key must not be empty" }
            sqlite.query(
                FLAG_CACHE_TABLE,
                FLAG_ROW_COLUMNS,
                "record_key = ?",
                arrayOf(key),
                null,
                null,
                null,
                "2",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val row = cursor.flagRow()
                if (cursor.moveToNext()) corrupt("Runtime database contains duplicate flag row keys")
                return row
            }
        }

        override fun scanFlagRows(prefix: String, visitor: (RuntimeFlagStoredRow) -> Unit) {
            requireTransaction()
            requireFlagTable()
            sqlite.query(
                FLAG_CACHE_TABLE,
                FLAG_ROW_COLUMNS,
                "substr(record_key, 1, ?) = ?",
                arrayOf(prefix.length.toString(), prefix),
                null,
                null,
                "record_key ASC",
                null,
            ).use { cursor -> while (cursor.moveToNext()) visitor(cursor.flagRow()) }
        }

        override fun putFlagRow(row: RuntimeFlagStoredRow) {
            requireTransaction()
            requireFlagTable()
            require(row.key.isNotEmpty() && row.key.length <= MAX_FLAG_ROW_KEY_CHARS) {
                "Flag row key is outside its bounds"
            }
            require(row.storageSchemaVersion >= 1L) { "Flag row schema version must be positive" }
            require(row.payload.isNotEmpty() && row.payload.size <= MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES) {
                "Flag row payload is outside the Android SQLite row limit"
            }
            val values =
                ContentValues().apply {
                    put("record_key", row.key)
                    put("storage_schema_version", row.storageSchemaVersion)
                    put("payload", row.payload)
                }
            sqlite.insertWithOnConflict(FLAG_CACHE_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE).also { inserted ->
                if (inserted == -1L) corrupt("Flag row upsert failed")
            }
            mutated = true
        }

        override fun deleteFlagRow(key: String): Boolean {
            requireTransaction()
            requireFlagTable()
            val changed = sqlite.delete(FLAG_CACHE_TABLE, "record_key = ?", arrayOf(key))
            if (changed > 1) corrupt("Flag row deletion affected more than one key")
            if (changed == 1) mutated = true
            return changed == 1
        }

        override fun invalidateCurrentFlagCache() {
            requireTransaction()
            // The core context revision committed by this same transaction makes every old flag
            // witness unreadable. Deletion belongs to FlagDurableStore, which can distinguish a
            // corrupt v1 envelope from a byte-preserved future schema.
        }

        private fun requireTransaction() {
            check(sqlite.inTransaction()) { "Runtime database operation requires an active transaction" }
        }

        private fun requireFlagTable() {
            if (runtimeBaseDatabaseVersion(pragmaLong(sqlite, "PRAGMA user_version")) !in setOf(2L, 4L, 6L)) {
                throw IllegalStateException("Flag storage schema has not been initialized")
            }
        }

        private fun requireReplayTable() {
            check(replaySchemaPresent()) { "Replay storage schema has not been initialized" }
        }

        private fun Cursor.replayRow(): RuntimeReplayStoredRow = RuntimeReplayStoredRow(
            requiredString(0, "replay_queue.record_key"), getLong(1), requiredBlob(2, "replay_queue.payload"))

        private fun Cursor.storedRecord(): RuntimeStoredRecord =
            RuntimeStoredRecord(
                sequence = getLong(0),
                streamId = requiredString(1, "queue_records.stream_id"),
                kind = RuntimeRecordKind.fromWireValue(requiredString(2, "queue_records.kind")),
                recordId = requiredString(3, "queue_records.record_id"),
                internalPayload = requiredBlob(4, "queue_records.internal_payload"),
                accountedBytes = getInt(5),
            )

        private fun Cursor.flagRow(): RuntimeFlagStoredRow =
            RuntimeFlagStoredRow(
                key = requiredString(0, "flag_cache.record_key"),
                storageSchemaVersion = getLong(1),
                payload = requiredBlob(2, "flag_cache.payload"),
            )
    }

    internal companion object {
        private const val PERSON_TABLE = "person_state"
        private val CREATE_PERSON = """
            CREATE TABLE person_state (
                singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
                payload BLOB NOT NULL CHECK (length(payload) BETWEEN 1 AND 4096)
            )
        """.trimIndent()

        private fun readPerson(sqlite: SQLiteDatabase): RuntimePersonState? =
            sqlite.query(PERSON_TABLE, arrayOf("payload"), null, null, null, null, null, "2").use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val result = RuntimePersonState.decode(cursor.requiredBlob(0, "person_state.payload"))
                if (cursor.moveToNext()) corrupt("Duplicate person state")
                result
            }

        private fun writePerson(sqlite: SQLiteDatabase, state: RuntimePersonState, insert: Boolean) {
            val values = ContentValues().apply { put("singleton_id", SINGLETON_ID); put("payload", state.encode()) }
            if (insert) sqlite.insertOrThrow(PERSON_TABLE, null, values)
            else if (sqlite.update(PERSON_TABLE, values, "singleton_id = ?", arrayOf(SINGLETON_ID.toString())) != 1)
                corrupt("Person update did not affect exactly one row")
        }

        private const val DIAGNOSTICS_TABLE = "native_diagnostics"
        private val CREATE_DIAGNOSTICS = """
            CREATE TABLE native_diagnostics (
                singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
                payload BLOB NOT NULL CHECK (length(payload) BETWEEN 1 AND 2048)
            )
        """.trimIndent()

        private fun readDiagnostics(sqlite: SQLiteDatabase): RuntimeDiagnosticsState? =
            sqlite.query(DIAGNOSTICS_TABLE, arrayOf("payload"), null, null, null, null, null, "2").use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val result = RuntimeDiagnosticsState.decode(cursor.requiredBlob(0, "native_diagnostics.payload"))
                if (cursor.moveToNext()) corrupt("Duplicate diagnostics state")
                result
            }

        private fun writeDiagnostics(sqlite: SQLiteDatabase, state: RuntimeDiagnosticsState, insert: Boolean) {
            val values = ContentValues().apply { put("singleton_id", SINGLETON_ID); put("payload", state.encode()) }
            if (insert) sqlite.insertOrThrow(DIAGNOSTICS_TABLE, null, values)
            else if (sqlite.update(DIAGNOSTICS_TABLE, values, "singleton_id = ?", arrayOf(SINGLETON_ID.toString())) != 1)
                corrupt("Diagnostics update did not affect exactly one row")
        }

        private const val AUDIENCE_TABLE = "replay_audience"
        private val CREATE_AUDIENCE = """
            CREATE TABLE replay_audience (
                singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
                status TEXT NOT NULL CHECK (status IN ('unknown', 'unseen', 'first-session')),
                session_id TEXT,
                session_started_at TEXT,
                CHECK ((status = 'first-session' AND session_id IS NOT NULL AND session_started_at IS NOT NULL AND length(session_id) BETWEEN 1 AND 256 AND length(session_started_at) BETWEEN 1 AND 128)
                    OR (status IN ('unknown', 'unseen') AND session_id IS NULL AND session_started_at IS NULL))
            )
        """.trimIndent()

        private fun readAudience(sqlite: SQLiteDatabase): RuntimeReplayAudienceState? =
            sqlite.query(AUDIENCE_TABLE, arrayOf("status", "session_id", "session_started_at"), null, null, null, null, null, "2").use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val status = cursor.getString(0)
                val id = if (cursor.isNull(1)) null else cursor.getString(1)
                val start = if (cursor.isNull(2)) null else cursor.getString(2)
                val result = when (status) {
                    "unknown", "unseen" -> {
                        if (id != null || start != null) corrupt("Non-session audience contains a session")
                        if (status == "unknown") RuntimeReplayAudienceState.Unknown else RuntimeReplayAudienceState.Unseen
                    }
                    "first-session" -> {
                        if (id == null || start == null || start.length > 128) corrupt("Incomplete audience session")
                        try { RuntimeReplayAudienceState.FirstSession(id, start) }
                        catch (error: IllegalArgumentException) { throw RuntimeQueueCorruptionException("Invalid audience session", error) }
                    }
                    else -> corrupt("Unsupported audience status")
                }
                if (cursor.moveToNext()) corrupt("Duplicate audience history")
                result
            }

        private fun writeAudience(sqlite: SQLiteDatabase, state: RuntimeReplayAudienceState, insert: Boolean) {
            val values = ContentValues().apply {
                put("singleton_id", SINGLETON_ID)
                put("status", when (state) {
                    RuntimeReplayAudienceState.Unknown -> "unknown"
                    RuntimeReplayAudienceState.Unseen -> "unseen"
                    is RuntimeReplayAudienceState.FirstSession -> "first-session"
                })
                put("session_id", (state as? RuntimeReplayAudienceState.FirstSession)?.sessionId)
                put("session_started_at", (state as? RuntimeReplayAudienceState.FirstSession)?.startedAt)
            }
            if (insert) sqlite.insertOrThrow(AUDIENCE_TABLE, null, values)
            else if (sqlite.update(AUDIENCE_TABLE, values, "singleton_id = ?", arrayOf(SINGLETON_ID.toString())) != 1)
                corrupt("Audience update did not affect exactly one row")
        }

        private const val CORE_TABLE = "core_state"
        private const val QUEUE_TABLE = "queue_records"
        private const val REPLAY_TABLE = "replay_queue"
        private const val MAX_REPLAY_ROW_KEY_CHARS = 128
        // Conservative segment policy, not a universal device CursorWindow capacity assertion.
        private const val REPLAY_ROW_BYTES = 262_144
        private val REPLAY_ROW_COLUMNS = arrayOf("record_key", "storage_schema_version", "payload")
        private val CREATE_REPLAY = """
            CREATE TABLE replay_queue (
                record_key TEXT NOT NULL PRIMARY KEY CHECK (length(record_key) BETWEEN 1 AND $MAX_REPLAY_ROW_KEY_CHARS),
                storage_schema_version INTEGER NOT NULL CHECK (storage_schema_version >= 1),
                payload BLOB NOT NULL CHECK (length(payload) BETWEEN 1 AND $REPLAY_ROW_BYTES)
            )
        """.trimIndent()
        private const val FLAG_CACHE_TABLE = "flag_cache"
        private const val SINGLETON_ID = 1
        private const val MAX_FLAG_ROW_KEY_CHARS = 512
        private val RECORD_COLUMNS =
            arrayOf("sequence", "stream_id", "kind", "record_id", "internal_payload", "accounted_bytes")
        private val FLAG_ROW_COLUMNS = arrayOf("record_key", "storage_schema_version", "payload")

        private val CREATE_CORE =
            """
            CREATE TABLE core_state (
                singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
                state_json BLOB NOT NULL,
                queue_count INTEGER NOT NULL CHECK (queue_count BETWEEN 0 AND $MAX_RUNTIME_QUEUE_RECORDS),
                queue_bytes INTEGER NOT NULL CHECK (queue_bytes BETWEEN 0 AND $MAX_RUNTIME_QUEUE_BYTES)
            )
            """.trimIndent()

        private val CREATE_QUEUE =
            """
            CREATE TABLE queue_records (
                sequence INTEGER NOT NULL PRIMARY KEY CHECK (sequence >= 0),
                stream_id TEXT NOT NULL CHECK (length(stream_id) BETWEEN 1 AND 256),
                kind TEXT NOT NULL CHECK (kind IN ('event', 'mutation')),
                record_id TEXT NOT NULL UNIQUE CHECK (length(record_id) BETWEEN 1 AND 256),
                internal_payload BLOB NOT NULL CHECK (
                    length(internal_payload) BETWEEN 1 AND $MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES
                ),
                accounted_bytes INTEGER NOT NULL CHECK (
                    accounted_bytes BETWEEN 1 AND $MAX_RUNTIME_QUEUE_BYTES
                )
            )
            """.trimIndent()

        private val CREATE_FLAG_CACHE =
            """
            CREATE TABLE flag_cache (
                record_key TEXT NOT NULL PRIMARY KEY CHECK (length(record_key) BETWEEN 1 AND $MAX_FLAG_ROW_KEY_CHARS),
                storage_schema_version INTEGER NOT NULL CHECK (storage_schema_version >= 1),
                payload BLOB NOT NULL CHECK (
                    length(payload) BETWEEN 1 AND $MAX_ANDROID_SQLITE_RUNTIME_RECORD_BYTES
                )
            )
            """.trimIndent()

        @Throws(IOException::class)
        internal fun open(
            file: File,
            faults: AndroidRuntimeDatabaseFaults = AndroidRuntimeDatabaseFaults.None,
        ): AndroidSQLiteRuntimeDatabase {
            val parent = file.parentFile ?: throw IOException("Runtime database must have a parent directory")
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Could not create runtime database directory")
            }
            // The original owner already holds the process/file lease. Even SQLite READONLY
            // can rewrite original SHM; validate a private DB/WAL/journal copy first.
            if (file.exists()) AndroidRuntimeDatabasePreflight.withSnapshot(file) { validateExistingSnapshot(it) }
            val sqlite =
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or
                        SQLiteDatabase.CREATE_IF_NECESSARY or
                        SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    REFUSE_CORRUPTION_RECOVERY,
                )
            try {
                // Android 15 can safely execute row-returning PRAGMAs on every pooled connection.
                // Older releases stay single-connection so these durability settings cannot
                // accidentally land on a read connection while writes use another.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    sqlite.enableWriteAheadLogging()
                } else {
                    // Clear both explicit and framework compatibility WAL flags.
                    sqlite.disableWriteAheadLogging()
                }
                configureConnection(sqlite, faults)
                validateIntegrity(sqlite)
                initializeOrValidateSchema(sqlite)
                return AndroidSQLiteRuntimeDatabase(sqlite, Thread.currentThread(), faults)
            } catch (error: Throwable) {
                sqlite.close()
                throw error
            }
        }

        private val REFUSE_CORRUPTION_RECOVERY = DatabaseErrorHandler {
            throw RuntimeQueueCorruptionException("Runtime SQLite corruption refused without recovery")
        }

        private fun validateExistingSnapshot(file: File) {
            // Writable recovery is confined to the disposable copy so a valid interrupted
            // rollback-journal transaction can be recovered before its strict metadata checks.
            SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                REFUSE_CORRUPTION_RECOVERY).use { readOnly ->
                validateIntegrity(readOnly)
                val version = pragmaLong(readOnly, "PRAGMA user_version")
                if (version == 0L) {
                    if (applicationSchemaObjects(readOnly).isNotEmpty())
                        corrupt("Unversioned runtime database contains unexpected schema objects")
                    return
                }
                validateSchemaObjects(readOnly, version)
                if (version > RUNTIME_PERSON_SCHEMA_OFFSET) {
                    val person = readPerson(readOnly) ?: corrupt("Missing person state")
                    val state = readOnly.rawQuery("SELECT state_json FROM core_state WHERE singleton_id = 1 LIMIT 2", null).use { cursor ->
                        if (!cursor.moveToFirst()) corrupt("Person state exists without an owned core")
                        val parsed = try { CoreStateCodec.decode(cursor.requiredBlob(0, "core_state.state_json")) }
                        catch (error: Exception) { throw RuntimeQueueCorruptionException("Invalid person core binding", error) }
                        if (cursor.moveToNext()) corrupt("Runtime database contains duplicate core rows")
                        parsed
                    }
                    if (person.streamId != state.stream.streamId) corrupt("Person state stream binding differs")
                }
            }
        }

        private fun configureConnection(
            sqlite: SQLiteDatabase,
            faults: AndroidRuntimeDatabaseFaults,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                sqlite.execPerConnectionSQL("PRAGMA synchronous = FULL", emptyArray())
                sqlite.execPerConnectionSQL(
                    "PRAGMA busy_timeout = $SQLITE_BUSY_TIMEOUT_MILLIS",
                    emptyArray(),
                )
            }
            executePragma(sqlite, "PRAGMA synchronous = FULL")
            executePragma(sqlite, "PRAGMA busy_timeout = $SQLITE_BUSY_TIMEOUT_MILLIS")
            val settings =
                AndroidRuntimeConnectionSettings(
                    journalMode = pragmaString(sqlite, "PRAGMA journal_mode").lowercase(),
                    synchronous = pragmaLong(sqlite, "PRAGMA synchronous"),
                    busyTimeoutMillis = pragmaLong(sqlite, "PRAGMA busy_timeout"),
                )
            val shouldUseWal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
            if ((settings.journalMode == SQLITE_JOURNAL_MODE_WAL) != shouldUseWal) {
                corrupt("Runtime database did not apply its required SQLite journal mode")
            }
            if (settings.synchronous != SQLITE_SYNCHRONOUS_FULL) {
                corrupt("Runtime database did not apply SQLite synchronous=FULL")
            }
            if (settings.busyTimeoutMillis != SQLITE_BUSY_TIMEOUT_MILLIS) {
                corrupt("Runtime database did not apply the SQLite busy timeout")
            }
            faults.connectionConfigured(settings)
        }

        /** PRAGMA assignments may return rows, so API 35 requires the query path. */
        private fun executePragma(
            sqlite: SQLiteDatabase,
            statement: String,
        ) {
            sqlite.rawQuery(statement, null).use { cursor ->
                while (cursor.moveToNext()) {
                    // Consume every row so the assignment executes on the acquired connection.
                }
            }
        }

        private fun validateIntegrity(sqlite: SQLiteDatabase) {
            sqlite.rawQuery("PRAGMA integrity_check(1)", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok" || cursor.moveToNext()) {
                    corrupt("Runtime database failed SQLite integrity_check")
                }
            }
        }

        private fun initializeOrValidateSchema(sqlite: SQLiteDatabase) {
            val version = pragmaLong(sqlite, "PRAGMA user_version")
            when {
                version == 0L -> {
                    val existing = applicationSchemaObjects(sqlite)
                    if (existing.isNotEmpty()) {
                        corrupt("Unversioned runtime database contains unexpected schema objects: ${existing.joinToString()}")
                    }
                    sqlite.beginTransaction()
                    var markedSuccessful = false
                    try {
                        sqlite.execSQL(CREATE_CORE)
                        sqlite.execSQL(CREATE_QUEUE)
                        sqlite.execSQL(CREATE_AUDIENCE)
                        executePragma(
                            sqlite,
                            "PRAGMA user_version = $RUNTIME_DATABASE_SCHEMA_VERSION_WITH_AUDIENCE",
                        )
                        if (pragmaLong(sqlite, "PRAGMA user_version") != RUNTIME_DATABASE_SCHEMA_VERSION_WITH_AUDIENCE.toLong()) {
                            corrupt("Runtime database could not persist its schema version")
                        }
                        sqlite.setTransactionSuccessful()
                        markedSuccessful = true
                    } finally {
                        try {
                            sqlite.endTransaction()
                        } catch (error: Throwable) {
                            if (markedSuccessful) {
                                throw AmbiguousRuntimeCommitException(
                                    "SQLite could not report a definitive schema transaction outcome",
                                    error,
                                )
                            }
                            throw error
                        }
                    }
                }
                version !in 1L..12L && version !in 25L..36L ->
                    throw UnsupportedRuntimeStorageSchemaException(version)
            }
            validateSchemaObjects(sqlite, pragmaLong(sqlite, "PRAGMA user_version"))
        }

        private fun validateSchemaObjects(sqlite: SQLiteDatabase, version: Long) {
            val baseVersion = runtimeBaseDatabaseVersion(version)
            val flagsPresent = baseVersion in setOf(2L, 4L, 6L)
            val replayPresent = baseVersion in setOf(3L, 4L, 5L, 6L)
            val expected = mutableSetOf("table:$CORE_TABLE", "table:$QUEUE_TABLE")
            if (version > RUNTIME_AUDIENCE_SCHEMA_OFFSET) expected += "table:$AUDIENCE_TABLE"
            if (version > RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET) expected += "table:$DIAGNOSTICS_TABLE"
            if (version > RUNTIME_PERSON_SCHEMA_OFFSET) expected += "table:$PERSON_TABLE"
            if (flagsPresent) expected += "table:$FLAG_CACHE_TABLE"
            if (replayPresent) expected += "table:$REPLAY_TABLE"
            val objects = applicationSchemaObjects(sqlite)
            if (objects != expected) corrupt("Runtime database schema object set is unsupported: ${objects.joinToString()}")
            validateTableSql(sqlite, CORE_TABLE, CREATE_CORE)
            validateTableSql(sqlite, QUEUE_TABLE, CREATE_QUEUE)
            if (version > RUNTIME_AUDIENCE_SCHEMA_OFFSET) validateTableSql(sqlite, AUDIENCE_TABLE, CREATE_AUDIENCE)
            if (version > RUNTIME_DIAGNOSTICS_SCHEMA_OFFSET) validateTableSql(sqlite, DIAGNOSTICS_TABLE, CREATE_DIAGNOSTICS)
            if (version > RUNTIME_PERSON_SCHEMA_OFFSET) validateTableSql(sqlite, PERSON_TABLE, CREATE_PERSON)
            if (flagsPresent) validateTableSql(sqlite, FLAG_CACHE_TABLE, CREATE_FLAG_CACHE)
            if (replayPresent) validateTableSql(sqlite, REPLAY_TABLE, CREATE_REPLAY)
        }

        private const val SQLITE_SYNCHRONOUS_FULL = 2L
        private const val SQLITE_BUSY_TIMEOUT_MILLIS = 5_000L
        private const val SQLITE_JOURNAL_MODE_WAL = "wal"

        private fun applicationSchemaObjects(sqlite: SQLiteDatabase): Set<String> {
            sqlite.rawQuery(
                "SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
                null,
            ).use { cursor ->
                val objects = linkedSetOf<String>()
                while (cursor.moveToNext()) {
                    objects += "${cursor.getString(0)}:${cursor.getString(1)}"
                }
                return objects
            }
        }

        private fun validateTableSql(
            sqlite: SQLiteDatabase,
            table: String,
            expectedSql: String,
        ) {
            sqlite.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { cursor ->
                if (!cursor.moveToFirst()) corrupt("Runtime database is missing table $table")
                val actual = cursor.getString(0) ?: corrupt("Runtime table $table has no defining SQL")
                if (normalizeSql(actual) != normalizeSql(expectedSql)) {
                    corrupt("Runtime table $table has an unsupported schema")
                }
                if (cursor.moveToNext()) corrupt("Runtime database has duplicate table definitions for $table")
            }
        }

        private fun normalizeSql(value: String): String =
            value.trim().trimEnd(';').replace(Regex("\\s+"), " ").lowercase()

        private fun pragmaLong(
            sqlite: SQLiteDatabase,
            pragma: String,
        ): Long =
            sqlite.rawQuery(pragma, null).use { cursor ->
                if (!cursor.moveToFirst()) corrupt("$pragma returned no value")
                cursor.getLong(0)
            }

        private fun pragmaString(
            sqlite: SQLiteDatabase,
            pragma: String,
        ): String =
            sqlite.rawQuery(pragma, null).use { cursor ->
                if (!cursor.moveToFirst()) corrupt("$pragma returned no value")
                cursor.getString(0)
            }

        private fun Cursor.requiredBlob(
            index: Int,
            path: String,
        ): ByteArray {
            if (isNull(index)) corrupt("$path must not be null")
            return getBlob(index)
        }

        private fun Cursor.requiredString(
            index: Int,
            path: String,
        ): String {
            if (isNull(index)) corrupt("$path must not be null")
            return getString(index)
        }

        private fun corrupt(message: String): Nothing = throw RuntimeQueueCorruptionException(message)
    }
}
