package dev.elu.analytics.internal.replay

import dev.elu.analytics.EluReplayPrivateRegion
import org.junit.Assert.*
import org.junit.Test

class RequiredPrivacyIntentsTest {
    @Test fun `intent snapshot owns caller list and same final set cannot revive old revision`() {
        val a = EluReplayPrivateRegion.create(); val b = EluReplayPrivateRegion.create()
        val input = mutableListOf(a)
        val state = RequiredPrivacyIntents(); state.update(input)
        val original = state.revision
        input += b
        assertEquals(listOf(a), state.snapshot())
        state.update(input); state.update(listOf(a))
        assertEquals(listOf(a), state.snapshot()); assertTrue(state.revision > original)
    }
    @Test fun `duplicate and oversized intent never produce a partial successful declaration`() {
        val a = EluReplayPrivateRegion.create(); val state = RequiredPrivacyIntents()
        state.update(listOf(a, a)); assertFalse(state.valid)
        assertThrows(IllegalStateException::class.java) { state.snapshot() }
        state.update(List(65) { EluReplayPrivateRegion.create() }); assertFalse(state.valid)
        assertThrows(IllegalStateException::class.java) { state.snapshot() }
        state.update(listOf(a)); assertEquals(listOf(a), state.snapshot())
    }
    @Test fun `unchanged declaration keeps generation while replacement with new handle changes it`() {
        val a = EluReplayPrivateRegion.create(); val state = RequiredPrivacyIntents(); state.update(listOf(a))
        val original = state.revision
        state.update(listOf(a)); assertEquals(original, state.revision)
        state.update(listOf(EluReplayPrivateRegion.create())); assertTrue(state.revision > original)
    }
}
