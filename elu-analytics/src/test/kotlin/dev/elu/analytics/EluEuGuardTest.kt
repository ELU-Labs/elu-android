package dev.elu.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EluEuGuardTest {
    @Test
    fun `fails closed for missing timezone`() {
        assertTrue(EluEuGuard.isEuTimezone(null))
        assertTrue(EluEuGuard.isEuTimezone(""))
        assertTrue(EluEuGuard.isEuTimezone("  "))
    }

    @Test
    fun `blocks Europe prefix and extra territories`() {
        assertTrue(EluEuGuard.isEuTimezone("Europe/Paris"))
        assertTrue(EluEuGuard.isEuTimezone("Atlantic/Canary"))
        assertTrue(EluEuGuard.isEuTimezone("Indian/Reunion"))
    }

    @Test
    fun `allows timezone outside blocked set`() {
        assertFalse(EluEuGuard.isEuTimezone("America/Los_Angeles"))
        assertFalse(EluEuGuard.isEuTimezone("Asia/Tokyo"))
    }
}
