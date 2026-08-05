package dev.elu.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class EluStatePolicyTest {
    @Test
    fun `pending initializes only for enabled non-EU config`() {
        assertAction(EluStatePolicy.Action.INITIALIZE, EluStatePolicy.State.PENDING, enabled = true, euBlocked = false)
        assertAction(EluStatePolicy.Action.DISABLE, EluStatePolicy.State.PENDING, enabled = false, euBlocked = false)
        assertAction(EluStatePolicy.Action.DISABLE, EluStatePolicy.State.PENDING, enabled = true, euBlocked = true)
    }

    @Test
    fun `disabled can initialize only before runtime has existed`() {
        assertAction(EluStatePolicy.Action.INITIALIZE, EluStatePolicy.State.DISABLED, enabled = true, euBlocked = false, initialized = false)
        assertAction(EluStatePolicy.Action.NO_OP, EluStatePolicy.State.DISABLED, enabled = true, euBlocked = false, initialized = true)
        assertAction(EluStatePolicy.Action.NO_OP, EluStatePolicy.State.DISABLED, enabled = false, euBlocked = false)
        assertAction(EluStatePolicy.Action.NO_OP, EluStatePolicy.State.DISABLED, enabled = true, euBlocked = true)
    }

    @Test
    fun `running always applies tightening policy`() {
        for (enabled in listOf(false, true)) {
            for (euBlocked in listOf(false, true)) {
                assertAction(EluStatePolicy.Action.APPLY_RUNNING, EluStatePolicy.State.RUNNING, enabled, euBlocked)
            }
        }
    }

    private fun assertAction(
        expected: EluStatePolicy.Action,
        state: EluStatePolicy.State,
        enabled: Boolean,
        euBlocked: Boolean,
        initialized: Boolean = false,
    ) {
        assertEquals(expected, EluStatePolicy.actionFor(state, enabled, euBlocked, initialized))
    }
}
