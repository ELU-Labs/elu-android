package dev.elu.analytics.internal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessRoleResolverTest {
    private val app = "com.example.shop"

    private class Recorded {
        val calls = mutableListOf<String>()
    }

    private fun sources(
        sdkInt: Int,
        recorded: Recorded = Recorded(),
        application: () -> String? = { null },
        running: () -> String? = { null },
        commandLine: () -> String? = { null },
    ) = ProcessNameSources(
        sdkInt = sdkInt,
        applicationProcessName = { recorded.calls += "application"; application() },
        runningProcessName = { recorded.calls += "running"; running() },
        commandLine = { recorded.calls += "cmdline"; commandLine() },
    )

    @Test fun `API 28 and later trust Application getProcessName without other lookups`() {
        for (sdk in listOf(28, 29, 35, 36)) {
            val recorded = Recorded()
            assertEquals(ProcessRole.DEFAULT, ProcessRoleResolver.resolve(app, sources(sdk, recorded, application = { app })))
            assertEquals(listOf("application"), recorded.calls)
            assertEquals(ProcessRole.SECONDARY,
                ProcessRoleResolver.resolve(app, sources(sdk, application = { "$app:remote" })))
            assertEquals(ProcessRole.SECONDARY,
                ProcessRoleResolver.resolve(app, sources(sdk, application = { "$app:sync" })))
        }
    }

    @Test fun `API 23 to 27 never call Application getProcessName and use the running process entry`() {
        for (sdk in 23..27) {
            val recorded = Recorded()
            assertEquals(ProcessRole.DEFAULT, ProcessRoleResolver.resolve(app, sources(sdk, recorded, running = { app })))
            assertEquals(listOf("running"), recorded.calls)
            assertEquals(ProcessRole.SECONDARY,
                ProcessRoleResolver.resolve(app, sources(sdk, running = { "$app:remote" })))
        }
    }

    @Test fun `a missing or failing exact name falls back in order`() {
        val recorded = Recorded()
        val role = ProcessRoleResolver.resolve(app, sources(28, recorded,
            application = { null }, running = { throw SecurityException("denied") }, commandLine = { "$app:sync\u0000" }))
        assertEquals(ProcessRole.SECONDARY, role)
        assertEquals(listOf("application", "running", "cmdline"), recorded.calls)

        assertEquals(ProcessRole.DEFAULT, ProcessRoleResolver.resolve(app,
            sources(28, application = { throw IllegalStateException("unavailable") }, running = { app })))
        assertEquals(ProcessRole.DEFAULT, ProcessRoleResolver.resolve(app,
            sources(28, application = { "  " }, running = { " $app " })))
        assertEquals(ProcessRole.DEFAULT, ProcessRoleResolver.resolve(app,
            sources(24, running = { null }, commandLine = { "$app\u0000\u0000\u0000" })))
    }

    @Test fun `the command line is read up to its first NUL`() {
        assertEquals(app, ProcessRoleResolver.commandLineName("$app\u0000--flag\u0000"))
        assertEquals("$app:remote", ProcessRoleResolver.commandLineName("$app:remote"))
        assertEquals(app, ProcessRoleResolver.commandLineName(" $app \n\u0000"))
        assertNull(ProcessRoleResolver.commandLineName("\u0000$app"))
        assertNull(ProcessRoleResolver.commandLineName(""))
        assertNull(ProcessRoleResolver.commandLineName(null))
    }

    @Test fun `a truncated command line that could be the default name stays unknown`() {
        val truncatedDefault = sources(26, running = { null }, commandLine = { app.take(10) + "\u0000" })
        assertEquals(ProcessRole.UNKNOWN, ProcessRoleResolver.resolve(app, truncatedDefault))
        // A cut that still keeps the separator proves a different process.
        val truncatedSecondary = sources(26, running = { null }, commandLine = { "$app:rem" })
        assertEquals(ProcessRole.SECONDARY, ProcessRoleResolver.resolve(app, truncatedSecondary))
        assertEquals(ProcessRole.SECONDARY, ProcessRoleResolver.resolve(app,
            sources(26, running = { null }, commandLine = { "com.example.other" })))
    }

    @Test fun `no usable name or default name is unknown`() {
        assertEquals(ProcessRole.UNKNOWN, ProcessRoleResolver.resolve(app,
            sources(36, application = { null }, running = { null }, commandLine = { null })))
        assertEquals(ProcessRole.UNKNOWN, ProcessRoleResolver.resolve(app,
            sources(23, running = { throw RuntimeException() }, commandLine = { throw java.io.IOException() })))
        val recorded = Recorded()
        assertEquals(ProcessRole.UNKNOWN, ProcessRoleResolver.resolve(null, sources(36, recorded)))
        assertEquals(ProcessRole.UNKNOWN, ProcessRoleResolver.resolve(" ", sources(36, recorded)))
        assertEquals(emptyList<String>(), recorded.calls)
    }

    @Test fun `a renamed default process is compared exactly, not by package name`() {
        // <application android:process=":ui"> makes "<package>:ui" the default process.
        val renamed = "$app:ui"
        assertEquals(ProcessRole.DEFAULT, ProcessRoleResolver.resolve(renamed, sources(30, application = { renamed })))
        assertEquals(ProcessRole.SECONDARY, ProcessRoleResolver.resolve(renamed, sources(30, application = { app })))
        assertEquals(ProcessRole.SECONDARY, ProcessRoleResolver.resolve(app, sources(30, application = { "com.example.shop.sync" })))
    }
}
