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

    private fun identity() = dev.elu.analytics.internal.core.IdentityState(revision = 2, contextRevision = 5,
        anonymousId = "anon", userId = "original-user", groups = mapOf("company" to "old"),
        superProperties = mapOf("super" to "original"), session = null, optedOut = false, updatedAt = "2026-08-04T00:00:00.000Z")
    private fun mutation(change: RuntimeMutationChange) = RuntimeRecordDraft.Mutation(command().occurredAt, change, command().versions)

    @Test fun `typed mutation projections filter maps but cannot redirect original identity group or timestamp`() {
        val names = mutableListOf<String>()
        val policy = RuntimeEventFilter(callback = EluEvent.Filter {
            names += it.event; assertEquals("original", it.properties["super"])
            it.event = "forged"; it.timestamp = Date(0)
            it.properties["distinct_id"] = "forged-user"; it.properties["alias"] = "forged-alias"
            it.properties["\$group_type"] = "forged-type"; it.properties["\$group_key"] = "forged-key"
            when (names.last()) {
                "\$identify" -> { it.set = mutableMapOf("changed" to true); it.setOnce = null }
                "\$set" -> { it.properties.remove("\$set"); it.properties["\$set_once"] = mutableMapOf("once" to true) }
                "\$groupidentify" -> it.properties["\$group_set"] = mutableMapOf("safe" to true)
            }
            it
        })
        val original = identity()
        fun project(vararg changes: RuntimeMutationChange) = (policy.applyMutations(changes.map(::mutation), original)
            as RuntimeMutationFilterResult.Prepared).drafts.also { result -> result.forEach { assertEquals(command().occurredAt, it.occurredAt) } }
        val identify = project(RuntimeMutationChange.Identify("next-user", mapOf("private" to true), mapOf("private" to true))).single().change as RuntimeMutationChange.Identify
        assertEquals("next-user", identify.userId); assertEquals(mapOf("changed" to true), identify.set); assertTrue(identify.setOnce.isEmpty())
        val person = project(RuntimeMutationChange.SetPersonProperties(mapOf("private" to true), emptyMap(), emptyList())).single().change as RuntimeMutationChange.SetPersonProperties
        assertTrue(person.set.isEmpty()); assertEquals(mapOf("once" to true), person.setOnce)
        val alias = RuntimeMutationChange.LinkAlias("next-alias", "original-user")
        assertEquals(alias, project(alias).single().change)
        val group = project(RuntimeMutationChange.AssociateGroup("company", "new"),
            RuntimeMutationChange.SetGroupProperties("company", "new", mapOf("private" to true), emptyMap(), emptyList()))
        assertEquals(RuntimeMutationChange.AssociateGroup("company", "new"), group[0].change)
        assertEquals(RuntimeMutationChange.SetGroupProperties("company", "new", mapOf("safe" to true), emptyMap(), emptyList()), group[1].change)
        assertEquals(listOf("\$identify", "\$set", "\$create_alias", "\$groupidentify"), names)
    }

    @Test fun `removed person maps are noop while group association survives removed group map and malformed maps refuse`() {
        val original = identity()
        val person = mutation(RuntimeMutationChange.SetPersonProperties(mapOf("private" to true), emptyMap(), emptyList()))
        val removed = RuntimeEventFilter(callback = EluEvent.Filter { it.properties.clear(); it })
        assertTrue((removed.applyMutations(listOf(person), original) as RuntimeMutationFilterResult.Prepared).drafts.isEmpty())
        val group = mutation(RuntimeMutationChange.SetGroupProperties("company", "new", mapOf("private" to true), emptyMap(), emptyList()))
        assertEquals(listOf(mutation(RuntimeMutationChange.AssociateGroup("company", "new"))),
            (removed.applyMutations(listOf(group), original) as RuntimeMutationFilterResult.Prepared).drafts)
        val same = group.copy(change = (group.change as RuntimeMutationChange.SetGroupProperties).copy(groupKey = "old"))
        assertTrue((removed.applyMutations(listOf(same), original) as RuntimeMutationFilterResult.Prepared).drafts.isEmpty())
        val invalid = RuntimeEventFilter(callback = EluEvent.Filter { it.properties["\$set"] = listOf("not-object"); it })
        assertEquals(RuntimeCaptureRejection.FILTER_INVALID,
            (invalid.applyMutations(listOf(person), original) as RuntimeMutationFilterResult.Refused).reason)
        assertEquals(RuntimeCaptureRejection.FILTER_DROPPED,
            (RuntimeEventFilter(callback = EluEvent.Filter { null }).applyMutations(listOf(person), original) as RuntimeMutationFilterResult.Refused).reason)
    }

    @Test fun `typed unset and group setOnce without a released projection remain unchanged`() {
        var calls = 0
        val policy = RuntimeEventFilter(callback = EluEvent.Filter { calls++; null })
        listOf(RuntimeMutationChange.SetPersonProperties(emptyMap(), emptyMap(), listOf("remove")),
            RuntimeMutationChange.SetGroupProperties("company", "old", emptyMap(), mapOf("once" to true), emptyList())).forEach {
            val drafts = listOf(mutation(it))
            assertEquals(drafts, (policy.applyMutations(drafts, identity()) as RuntimeMutationFilterResult.Prepared).drafts)
        }
        assertEquals(0, calls)
    }
}
