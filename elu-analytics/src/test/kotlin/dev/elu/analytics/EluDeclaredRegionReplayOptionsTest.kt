package dev.elu.analytics

import org.junit.Assert.*
import org.junit.Test

class EluDeclaredRegionReplayOptionsTest {
    @Test fun `all existing setup families stay default off`() {
        val options = listOf(EluOptions(), EluOptions("https://elu.dev"),
            EluOptions(configHost = "https://elu.dev", apiHost = null),
            EluOptions(EluPerformanceOptions()), EluOptions(EluDiagnosticsOptions()),
            EluOptions(EluPersonProfilesMode.IDENTIFIED_ONLY), EluOptions(EluPersistenceMode.PERSISTENT),
            EluOptions(EluRateLimitingOptions()))
        options.forEach { assertFalse(it.declaredRegionReplayEnabled) }
    }
    @Test fun `explicit flag composes with original options without changing them`() {
        val rate = EluRateLimitingOptions(); val diagnostics = EluDiagnosticsOptions()
        val performance = EluPerformanceOptions()
        val option = EluOptions(true, rate, EluPersistenceMode.MEMORY, EluPersonProfilesMode.IDENTIFIED_ONLY,
            diagnostics, performance, "https://analytics.example.com", "https://analytics.example.com")
        assertTrue(option.declaredRegionReplayEnabled)
        assertSame(rate, option.rateLimiting); assertSame(diagnostics, option.diagnostics)
        assertSame(performance, option.performance); assertEquals(EluPersistenceMode.MEMORY, option.persistence)
        assertEquals("https://analytics.example.com", option.configHost)
        assertEquals("https://analytics.example.com", option.apiHost)
        assertFalse(EluOptions(declaredRegionReplayEnabled = false).declaredRegionReplayEnabled)
    }
}
