package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.CoreEpochClock
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.CoreStateCorruptionException
import dev.elu.analytics.internal.core.CoreStateStore
import dev.elu.analytics.internal.core.CoreStateWriteOutcome
import dev.elu.analytics.internal.core.FlagContextState
import dev.elu.analytics.internal.core.IdentityState
import dev.elu.analytics.internal.core.PersistedCoreState
import dev.elu.analytics.internal.core.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRuntimeQueueBootstrapTest {
    @Test
    fun `valid legacy state imports without writing the legacy store`() {
        val legacy = RecordingStore(CoreStateCodec.encode(state()))

        val imported =
            AndroidRuntimeQueue.bootstrapFromLegacy(
                legacy,
                identifiers = identifiers(),
                clock = CoreEpochClock { 0L },
            )

        assertEquals(state(), imported)
        assertEquals(1, legacy.reads)
        assertEquals(0, legacy.writes)
    }

    @Test
    fun `corrupt legacy state uses core recovery in memory and never rewrites legacy bytes`() {
        val legacy = RecordingStore(null, CoreStateCorruptionException("damaged legacy file"))

        val imported =
            AndroidRuntimeQueue.bootstrapFromLegacy(
                legacy,
                identifiers = identifiers(),
                clock = CoreEpochClock { 0L },
            )

        assertTrue(imported.identity.optedOut)
        assertEquals("anon_recovered", imported.identity.anonymousId)
        assertEquals("stream_recovered", imported.stream.streamId)
        assertEquals(0L, imported.stream.nextSequence)
        assertEquals(1, legacy.reads)
        assertEquals(0, legacy.writes)
    }

    private fun identifiers(): CoreIdentifierGenerator =
        CoreIdentifierGenerator { prefix ->
            when (prefix) {
                "anon_" -> "anon_recovered"
                "stream_" -> "stream_recovered"
                else -> error("Unexpected identifier prefix: $prefix")
            }
        }

    private fun state(): PersistedCoreState =
        PersistedCoreState(
            identity =
                IdentityState(
                    revision = 2,
                    contextRevision = 3,
                    anonymousId = "anon_legacy",
                    userId = "user_legacy",
                    groups = emptyMap(),
                    superProperties = emptyMap(),
                    session = null,
                    optedOut = false,
                    updatedAt = "2026-08-05T00:00:00.000Z",
                ),
            stream = StreamState(streamId = "stream_legacy", nextSequence = 7),
            flagContext = FlagContextState(personProperties = emptyMap(), groupProperties = emptyMap()),
        )

    private class RecordingStore(
        private val bytes: ByteArray?,
        private val readFailure: Throwable? = null,
    ) : CoreStateStore {
        var reads: Int = 0
            private set
        var writes: Int = 0
            private set

        override fun read(): ByteArray? {
            reads += 1
            readFailure?.let { throw it }
            return bytes?.copyOf()
        }

        override fun write(bytes: ByteArray): CoreStateWriteOutcome {
            writes += 1
            return CoreStateWriteOutcome.Durable
        }
    }
}
