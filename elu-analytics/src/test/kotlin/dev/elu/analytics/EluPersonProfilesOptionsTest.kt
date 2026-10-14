package dev.elu.analytics

import org.junit.Assert.*
import org.junit.Test

class EluPersonProfilesOptionsTest {
    @Test fun `all prior constructors preserve identified-only default`() {
        for (options in listOf(EluOptions(), EluOptions("https://elu.dev"),
            EluOptions("https://a.example.com", "https://a.example.com"),
            EluOptions(EluPerformanceOptions()), EluOptions(EluDiagnosticsOptions()),
            EluOptions(EluDiagnosticsOptions(), EluPerformanceOptions(), "https://a.example.com", "https://a.example.com"))) {
            assertEquals(EluPersonProfilesMode.IDENTIFIED_ONLY, options.personProfiles)
        }
    }

    @Test fun `typed profile option composes with diagnostics performance and selected prefix`() {
        val performance = EluPerformanceOptions()
        val diagnostics = EluDiagnosticsOptions()
        val options = EluOptions(EluPersonProfilesMode.NEVER, diagnostics, performance,
            "https://a.example.com/prefix", "https://a.example.com/prefix")
        assertEquals(EluPersonProfilesMode.NEVER, options.personProfiles)
        assertSame(performance, options.performance)
        assertSame(diagnostics, options.diagnostics)
        assertEquals("https://a.example.com/prefix", options.apiHost)
        assertEquals(options.apiHost, options.configHost)
    }
}
