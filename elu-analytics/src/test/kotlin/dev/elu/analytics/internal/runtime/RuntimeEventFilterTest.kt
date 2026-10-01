package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluEvent
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class RuntimeEventFilterTest {
    private fun command() = RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, "original", "2026-08-04T00:01:00.123456789Z",
        emptyMap(), StandaloneRuntime.defaultVersions())
    private fun apply(policy: RuntimeEventFilter, properties: Map<String, Any?> = emptyMap(),
        person: RuntimeEventPerson = RuntimeEventPerson(), allowPerson: Boolean = true) =
        policy.apply(command(), properties, person, allowPerson)
    private fun rejected(result: RuntimeEventFilterResult, reason: RuntimeCaptureRejection) =
        assertEquals(reason, (result as RuntimeEventFilterResult.Refused).reason)

    @Test fun `denylist is copied exact top-level and precedes hook while reserved fields cannot return`() {
        val denied = mutableListOf("secret", "é")
        val policy = RuntimeEventFilter(denied, EluEvent.Filter {
            assertFalse(it.properties.containsKey("secret"))
            assertFalse(it.properties.containsKey("é"))
            assertEquals("decomposed", it.properties["e\u0301"])
            assertEquals(mapOf("secret" to "nested"), it.properties["nested"])
            assertFalse(it.properties.containsKey("\$device_id"))
            it.properties["secret"] = "deliberate replacement"
            it.properties["\$device_id"] = "forged"
            it.properties["\$elu_internal"] = true
            it.properties["\$epp"] = true
            it
        })
        denied.clear()
        val result = apply(policy, mapOf("secret" to "private", "é" to "composed", "e\u0301" to "decomposed",
            "nested" to mapOf("secret" to "nested"), "\$device_id" to "caller")) as RuntimeEventFilterResult.Prepared
        assertEquals("deliberate replacement", result.properties["secret"])
        assertFalse(result.properties.keys.any { it.startsWith("\$") })
        assertEquals(command().occurredAt, result.occurredAt) // No Date-induced submillisecond truncation.
    }

    @Test fun `input output dates and JSON containers are detached from caller and retained hook objects`() {
        val nested = mutableListOf<Any?>("original")
        var retained: EluEvent? = null
        val date = Date(1_786_233_661_000)
        val result = apply(RuntimeEventFilter(callback = EluEvent.Filter {
            (it.properties["nested"] as MutableList<*>).clear()
            it.properties["nested"] = mutableListOf("filtered")
            it.timestamp = date
            it.set = mutableMapOf("tier" to mutableListOf("paid"))
            retained = it
            it
        }), mapOf("nested" to nested)) as RuntimeEventFilterResult.Prepared
        nested[0] = "caller changed"
        retained!!.properties.clear(); retained!!.set!!.clear(); date.time = 0
        assertEquals(listOf("filtered"), result.properties["nested"])
        assertEquals(listOf("paid"), result.person.set!!["tier"])
        assertEquals(RuntimeWallTimestamps.rfc3339(1_786_233_661_000), result.occurredAt)
    }

    @Test fun `drop thrown hook malformed Unicode nonJSON cycles and budgets never fall back`() {
        rejected(apply(RuntimeEventFilter(callback = EluEvent.Filter { null })), RuntimeCaptureRejection.FILTER_DROPPED)
        rejected(apply(RuntimeEventFilter(callback = EluEvent.Filter { error("customer") })), RuntimeCaptureRejection.FILTER_INVALID)
        val mutations: List<(EluEvent) -> Unit> = listOf(
            { it.event = "" }, { it.event = "bad\uD800" },
            { it.properties["number"] = Double.NaN }, { it.properties["date"] = Date() },
            { val cycle = mutableListOf<Any?>(); cycle.add(cycle); it.properties["cycle"] = cycle },
            { it.properties["large"] = List(1025) { true } },
            { it.properties["bytes"] = "x".repeat(2_621_441) },
            { it.set = mutableMapOf("bad\uDC00" to true) },
        )
        mutations.forEach { change -> rejected(apply(RuntimeEventFilter(callback = EluEvent.Filter {
            change(it); it
        })), RuntimeCaptureRejection.FILTER_INVALID) }
    }

    @Test fun `automatic person output is explicitly refused but manual transformed maps remain separate`() {
        val policy = RuntimeEventFilter(callback = EluEvent.Filter { it.setOnce = mutableMapOf("source" to "hook"); it })
        rejected(apply(policy, allowPerson = false), RuntimeCaptureRejection.FILTER_PERSON_UNSUPPORTED)
        val manual = apply(policy, person = RuntimeEventPerson(set = mapOf("tier" to "paid"))) as RuntimeEventFilterResult.Prepared
        assertEquals(mapOf("tier" to "paid"), manual.person.set)
        assertEquals(mapOf("source" to "hook"), manual.person.setOnce)
        assertFalse(manual.properties.containsKey("\$set"))
    }

    @Test fun `original attempt calls once and refuses changed binding or withdrawn original admission`() {
        val owner = Any(); val original = command(); var current = true; var calls = 0
        val attempt = RuntimeEventFilterAttempt()
        fun prepare(input: String, candidateOwner: Any = owner) = attempt.prepare(candidateOwner, original, input, { current }) {
            calls++; apply(RuntimeEventFilter())
        }
        val first = prepare("one")
        assertSame(first, prepare("one")); assertEquals(1, calls)
        rejected(prepare("two"), RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED)
        current = false
        rejected(prepare("one"), RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED)
        assertThrows(IllegalStateException::class.java) { prepare("one", Any()) }
        assertEquals(1, calls)
    }
}
