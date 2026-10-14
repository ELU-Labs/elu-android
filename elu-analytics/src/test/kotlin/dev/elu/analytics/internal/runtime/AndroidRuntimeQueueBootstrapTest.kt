package dev.elu.analytics.internal.runtime

import dev.elu.analytics.internal.core.CoreEpochClock
import dev.elu.analytics.internal.core.CoreIdentifierGenerator
import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.core.UnsupportedCoreSchemaExtensionException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidRuntimeQueueBootstrapTest {
    @Test
    fun `fresh owned installation starts independently at the setup clock`() {
        val state = AndroidRuntimeQueue.freshState(identifiers(), CoreEpochClock { 5_000L }, 1_000L)
        assertEquals("anon_fresh", state.identity.anonymousId)
        assertEquals("stream_fresh", state.stream.streamId)
        assertNull(state.identity.userId)
        assertNull(state.identity.session)
        assertFalse(state.identity.optedOut)
        assertEquals(0L, state.stream.nextSequence)
        assertEquals("1970-01-01T00:00:01.000Z", state.identity.updatedAt)
    }

    @Test
    fun `fresh installation refuses a setup anchor later than the current clock`() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidRuntimeQueue.freshState(identifiers(), CoreEpochClock { 1_000L }, 5_000L)
        }
    }

    @Test
    fun `retired import checkpoints are refused rather than recovered as owned state`() {
        val current = CoreStateCodec.encode(AndroidRuntimeQueue.freshState(identifiers(), CoreEpochClock { 0L }))
        for (key in listOf("startupMigration", "startupHistory")) {
            val unsupported = JSONObject(current.decodeToString()).put(key, JSONObject()).toString().encodeToByteArray()
            assertThrows(UnsupportedCoreSchemaExtensionException::class.java) { CoreStateCodec.decode(unsupported) }
            assertThrows(UnsupportedCoreSchemaExtensionException::class.java) { CoreStateCodec.recoverableRecords(unsupported) }
            val damaged = JSONObject(unsupported.decodeToString()).put("schemaVersion", "damaged").toString().encodeToByteArray()
            assertThrows(UnsupportedCoreSchemaExtensionException::class.java) { CoreStateCodec.recoverableRecords(damaged) }
        }
    }

    private fun identifiers(): CoreIdentifierGenerator = CoreIdentifierGenerator { prefix -> prefix + "fresh" }
}
