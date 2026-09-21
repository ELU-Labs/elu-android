package dev.elu.analytics.internal.performance

import dev.elu.analytics.EluPerformanceOptions
import dev.elu.analytics.internal.config.V1CapturePerformance
import org.junit.Assert.*
import org.junit.Test

class NativePerformanceSamplerTest {
    private class Rig(options: EluPerformanceOptions = EluPerformanceOptions(true, sampleIntervalMillis = 5_000)) {
        var nanos = 0L
        var current: NativePerformanceContext? = NativePerformanceContext(Any(), 1, 1, "session-a", 0, Any(),
            V1CapturePerformance(true, true, 5_000))
        var memory: Long? = 42_000L
        var onMemory: (() -> Unit)? = null
        val main = ArrayList<Runnable>()
        val samples = ArrayList<Pair<NativePerformanceContext, Map<String, Any>>>()
        val sampler = NativePerformanceSampler(options, { current }, { nanos }, { main.add(it); true },
            { main.remove(it) }, { onMemory?.invoke(); memory }, { c, p -> samples.add(c to p) })
        fun advance(millis: Long) { nanos += millis * 1_000_000 }
        fun respond() { main.removeAt(0).run() }
        fun tick() = sampler.tick()
    }

    @Test fun `disabled default starts no probes reads no memory and emits nothing`() {
        val rig = Rig(EluPerformanceOptions())
        rig.onMemory = { fail("disabled sampler read memory") }
        rig.tick(); rig.advance(60_000); rig.tick()
        assertTrue(rig.main.isEmpty()); assertTrue(rig.samples.isEmpty())
    }

    @Test fun `bounded probe aggregates only sampled stalls and omits unavailable PSS`() {
        val rig = Rig(); rig.memory = null
        rig.tick(); repeat(4) { rig.advance(100); rig.tick() }
        assertEquals(1, rig.main.size)
        rig.respond(); rig.advance(4_600); rig.tick()
        val values = rig.samples.single().second
        assertEquals("android", values["\$performance_platform"])
        assertEquals(true, values["\$app_foreground"])
        assertEquals(1L, values["\$main_thread_stall_count"])
        assertEquals(400L, values["\$main_thread_stall_total_ms"])
        assertEquals(400L, values["\$main_thread_stall_max_ms"])
        assertEquals(250L, values["\$main_thread_stall_threshold_ms"])
        assertFalse(values.containsKey("\$memory_process_pss_bytes"))
        assertFalse(values.keys.any { "longtask" in it || "heap" in it })
    }

    @Test fun `identity session config and intent transitions clear old aggregate and late probes`() {
        for (change in listOf<(NativePerformanceContext) -> NativePerformanceContext>(
            { it.copy(identityRevision = 2) }, { it.copy(contextRevision = 2) }, { it.copy(source = Any()) },
            { it.copy(sessionId = "session-b") }, { it.copy(intentRevision = 2) }, { it.copy(lifecycleEpoch = Any()) })) {
            val rig = Rig(); rig.tick(); val late = rig.main.single()
            rig.advance(500); rig.current = change(checkNotNull(rig.current)); rig.tick(); late.run()
            rig.advance(5_000); rig.tick()
            assertEquals(0L, rig.samples.single().second["\$main_thread_stall_count"])
            assertEquals(1, rig.main.size)
        }
    }

    @Test fun `background or optout clears original probe and requires a new complete interval`() {
        val rig = Rig(); rig.tick(); val old = rig.main.single()
        val original = rig.current
        rig.advance(1_000); rig.current = null; rig.tick(); old.run()
        assertTrue(rig.main.isEmpty())
        rig.advance(10_000); rig.tick(); assertTrue(rig.samples.isEmpty())
        rig.current = original; rig.tick(); rig.advance(4_999); rig.tick(); assertTrue(rig.samples.isEmpty())
        rig.advance(1); rig.tick(); assertEquals(1, rig.samples.size)
        assertEquals(0L, rig.samples.single().second["\$main_thread_stall_count"])
    }

    @Test fun `remote flags narrow local settings and remote interval is a floor`() {
        val rig = Rig(); rig.current = rig.current!!.copy(policy = V1CapturePerformance(true, false, 10_000))
        rig.tick(); assertTrue(rig.main.isEmpty()); rig.advance(5_000); rig.tick(); assertTrue(rig.samples.isEmpty())
        rig.advance(5_000); rig.tick()
        val values = rig.samples.single().second
        assertEquals(42_000L, values["\$memory_process_pss_bytes"])
        assertEquals(10_000L, values["\$performance_sample_interval_ms"])
        assertFalse(values.containsKey("\$main_thread_stall_count"))
    }

    @Test fun `configuration change during memory read drops sample and close removes pending work`() {
        val rig = Rig(); rig.tick(); rig.advance(5_000)
        rig.onMemory = { rig.current = null }; rig.tick()
        assertTrue(rig.samples.isEmpty())
        rig.sampler.close(); assertTrue(rig.main.isEmpty())
        rig.advance(5_000); rig.tick(); assertTrue(rig.samples.isEmpty())
    }

    @Test fun `memory only unknown measurement emits no empty metric event`() {
        val rig = Rig(EluPerformanceOptions(true, mainThreadStalls = false, sampleIntervalMillis = 5_000))
        rig.memory = null
        rig.tick(); rig.advance(5_000); rig.tick()
        assertTrue(rig.samples.isEmpty()); assertTrue(rig.main.isEmpty())
    }

    @Test fun `monotonic rollback starts a fresh window without negative measurements`() {
        val rig = Rig(); rig.tick(); rig.advance(2_000); rig.respond(); rig.nanos = 0; rig.tick()
        rig.advance(5_000); rig.tick()
        assertEquals(0L, rig.samples.single().second["\$main_thread_stall_count"])
    }
}
