package dev.elu.analytics

import org.junit.Assert.*
import org.junit.Test

class EluPersistenceOptionsTest {
    @Test fun `all prior option entry points keep persistent default`() {
        val performance = EluPerformanceOptions()
        val diagnostics = EluDiagnosticsOptions()
        val oldOptions = listOf(EluOptions(), EluOptions("https://elu.dev"),
            EluOptions(performance), EluOptions(performance, "https://elu.dev"),
            EluOptions("https://self.example", "https://self.example"),
            EluOptions(performance, "https://self.example", "https://self.example"),
            EluOptions(diagnostics), EluOptions(diagnostics, performance, "https://elu.dev"),
            EluOptions(diagnostics, performance, "https://self.example", "https://self.example"),
            EluOptions(EluPersonProfilesMode.NEVER))
        oldOptions.forEach { assertEquals(EluPersistenceMode.PERSISTENT, it.persistence) }
    }

    @Test fun `memory selection retains every independent customer option`() {
        val performance = EluPerformanceOptions()
        val diagnostics = EluDiagnosticsOptions()
        val options = EluOptions(EluPersistenceMode.MEMORY, EluPersonProfilesMode.ALWAYS,
            diagnostics, performance, "https://self.example/analytics", "https://self.example/analytics")
        assertEquals(EluPersistenceMode.MEMORY, options.persistence)
        assertEquals(EluPersonProfilesMode.ALWAYS, options.personProfiles)
        assertSame(performance, options.performance)
        assertSame(diagnostics, options.diagnostics)
        assertEquals("https://self.example/analytics", options.configHost)
        assertEquals("https://self.example/analytics", options.apiHost)
        assertEquals(EluPersonProfilesMode.IDENTIFIED_ONLY, EluOptions(EluPersistenceMode.MEMORY).personProfiles)
    }
}
