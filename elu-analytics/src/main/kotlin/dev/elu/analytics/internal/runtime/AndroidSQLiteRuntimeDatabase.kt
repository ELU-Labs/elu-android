package dev.elu.analytics.internal.runtime

import android.content.ContentValues
import android.database.Cursor
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
            if (sqlite.inTransaction()) {
                try {
                    sqlite.endTransaction()
                } catch (endError: Throwable) {
                    if (markedSuccessful) {
                        throw AmbiguousRuntimeCommitException(
                            "SQLite could not report a definitive transaction outcome",
                            endError,
                        ).apply { addSuppressed(error) }
                    }
                    error.addSuppressed(endError)
                }
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
                if (!cursor.moveToFirst()) return null
                val core =
                    RuntimeStoredCore(
                        stateJson = cursor.requiredBlob(0, "core_state.state_json"),
                        queueCount = cursor.getLong(1),
                        queueBytes = cursor.getLong(2),
                    )
                if (cursor.moveToNext()) corrupt("Runtime database contains duplicate core rows")
                return core
            }
        }

        override fun insertCore(core: RuntimeStoredCore) {
            requireTransaction()
            val values =
                ContentValues().apply {
                    put("singleton_id", SINGLETON_ID)
                    put("state_json", core.stateJson)
                    put("queue_count", core.queueCount)
                    put("queue_bytes", core.queueBytes)
                }
            sqlite.insertOrThrow(CORE_TABLE, null, values)
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

        private fun requireTransaction() {
            check(sqlite.inTransaction()) { "Runtime database operation requires an active transaction" }
        }

        private fun Cursor.storedRecord(): RuntimeStoredRecord =
            RuntimeStoredRecord(
                sequence = getLong(0),
                streamId = requiredString(1, "queue_records.stream_id"),
                kind = RuntimeRecordKind.fromWireValue(requiredString(2, "queue_records.kind")),
                recordId = requiredString(3, "queue_records.record_id"),
                internalPayload = requiredBlob(4, "queue_records.internal_payload"),
                accountedBytes = getInt(5),
            )
    }

    internal companion object {
        private const val CORE_TABLE = "core_state"
        private const val QUEUE_TABLE = "queue_records"
        private const val SINGLETON_ID = 1
        private val RECORD_COLUMNS =
            arrayOf("sequence", "stream_id", "kind", "record_id", "internal_payload", "accounted_bytes")

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

        @Throws(IOException::class)
        internal fun open(
            file: File,
            faults: AndroidRuntimeDatabaseFaults = AndroidRuntimeDatabaseFaults.None,
        ): AndroidSQLiteRuntimeDatabase {
            val parent = file.parentFile ?: throw IOException("Runtime database must have a parent directory")
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("Could not create runtime database directory")
            }
            val sqlite =
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or
                        SQLiteDatabase.CREATE_IF_NECESSARY or
                        SQLiteDatabase.NO_LOCALIZED_COLLATORS,
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
                        executePragma(
                            sqlite,
                            "PRAGMA user_version = $RUNTIME_STORAGE_SCHEMA_VERSION",
                        )
                        if (pragmaLong(sqlite, "PRAGMA user_version") != RUNTIME_STORAGE_SCHEMA_VERSION.toLong()) {
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
                version != RUNTIME_STORAGE_SCHEMA_VERSION.toLong() ->
                    throw UnsupportedRuntimeStorageSchemaException(version)
            }
            val objects = applicationSchemaObjects(sqlite)
            if (objects != setOf("table:$CORE_TABLE", "table:$QUEUE_TABLE")) {
                corrupt("Runtime database schema object set is unsupported: ${objects.joinToString()}")
            }
            validateTableSql(sqlite, CORE_TABLE, CREATE_CORE)
            validateTableSql(sqlite, QUEUE_TABLE, CREATE_QUEUE)
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
