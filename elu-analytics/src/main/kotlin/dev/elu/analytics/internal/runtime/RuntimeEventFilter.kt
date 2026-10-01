package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluEvent
import dev.elu.analytics.internal.core.JsonValues
import dev.elu.analytics.internal.core.IdentityState
import java.time.Instant
import java.util.Date
import java.util.IdentityHashMap

/** Setup is copied once; this policy neither grants capture authority nor owns storage. */
internal class RuntimeEventFilter(
    propertyDenylist: List<String> = emptyList(),
    private val callback: EluEvent.Filter? = null,
    private val admission: () -> (() -> Boolean) = { { true } },
) {
    private val denied = propertyDenylist.also { require(it.size <= 1024) }.toSet().also {
        it.forEach { name -> require(name.length <= 1024); requireUnicode(name) }
    }
    val enabled: Boolean get() = denied.isNotEmpty() || callback != null
    val hasCallback: Boolean get() = callback != null
    fun boundTo(originalAdmission: () -> (() -> Boolean)) = RuntimeEventFilter(denied.toList(), callback, originalAdmission)
    fun originalAdmission(): () -> Boolean = admission()

    fun apply(command: RuntimeCaptureCommand, merged: Map<String, Any?>,
        person: RuntimeEventPerson, allowsPersonChanges: Boolean): RuntimeEventFilterResult = try {
        val inputCopy = Copier()
        val properties = inputCopy.objectValue(merged).apply {
            keys.removeAll { protected(it) || it in denied }
        }
        val originalTime = Instant.parse(command.occurredAt).toEpochMilli()
        val input = EluEvent(command.name, properties, Date(originalTime),
            person.set?.let(inputCopy::objectValue), person.setOnce?.let(inputCopy::objectValue))
        // No SDK lock or database transaction is held at this call site.
        val output = if (callback == null) input else callback.filter(input)
        if (output == null) RuntimeEventFilterResult.Refused(RuntimeCaptureRejection.FILTER_DROPPED)
        else {
            require(output.event.length in 1..1024 && output.event.codePointCount(0, output.event.length) in 1..512)
            requireUnicode(output.event)
            val outputTime = output.timestamp.time
            val outputCopy = Copier()
            val props = outputCopy.objectValue(output.properties).apply { keys.removeAll(::protected) }
            val set = output.set?.let(outputCopy::objectValue)
            val once = output.setOnce?.let(outputCopy::objectValue)
            if (!allowsPersonChanges && (set != null || once != null))
                RuntimeEventFilterResult.Refused(RuntimeCaptureRejection.FILTER_PERSON_UNSUPPORTED)
            else RuntimeEventFilterResult.Prepared(output.event,
                if (outputTime == originalTime) command.occurredAt else RuntimeWallTimestamps.rfc3339(outputTime),
                JsonValues.objectValue(props, "filter.properties"), RuntimeEventPerson(
                    set?.let { JsonValues.objectValue(it, "filter.set") },
                    once?.let { JsonValues.objectValue(it, "filter.setOnce") }))
        }
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
        RuntimeEventFilterResult.Refused(RuntimeCaptureRejection.FILTER_INVALID)
    }

    /** Released mutation projections expose mutable properties, never mutable identity targets.
     * Called once on the original queue worker, outside SQLite and its retry loop. */
    fun applyMutations(drafts: List<RuntimeRecordDraft.Mutation>, identity: IdentityState): RuntimeMutationFilterResult = try {
        require(drafts.isNotEmpty())
        val first = drafts.first()
        fun project(name: String, properties: Map<String, Any?>,
            person: RuntimeEventPerson = RuntimeEventPerson()): RuntimeEventFilterResult.Prepared {
            val merged = LinkedHashMap(identity.superProperties).apply { putAll(properties) }
            return when (val result = apply(RuntimeCaptureCommand(RuntimeEventKind.CAPTURE, name,
                first.occurredAt, properties, first.versions), merged, person, true)) {
                is RuntimeEventFilterResult.Prepared -> result
                is RuntimeEventFilterResult.Refused -> throw MutationFilterRefusal(result.reason)
            }
        }
        fun objectValue(value: Any?): Map<String, Any?>? {
            if (value == null) return null
            require(value is Map<*, *> && value.keys.all { it is String })
            @Suppress("UNCHECKED_CAST")
            return JsonValues.objectValue(value as Map<String, Any?>, "filter.mutation")
        }
        fun row(change: RuntimeMutationChange) = first.copy(change = change)
        val group = drafts.last().change
        val changes: List<RuntimeRecordDraft.Mutation> = if (group is RuntimeMutationChange.AssociateGroup || group is RuntimeMutationChange.SetGroupProperties) {
            val type = when (group) { is RuntimeMutationChange.AssociateGroup -> group.groupType; else -> (group as RuntimeMutationChange.SetGroupProperties).groupType }
            val key = when (group) { is RuntimeMutationChange.AssociateGroup -> group.groupKey; else -> (group as RuntimeMutationChange.SetGroupProperties).groupKey }
            require(drafts.size == 1 || (drafts.size == 2 && drafts.first().change == RuntimeMutationChange.AssociateGroup(type, key)))
            require(drafts.all { it.occurredAt == first.occurredAt && it.versions == first.versions })
            if (group is RuntimeMutationChange.SetGroupProperties && (group.setOnce.isNotEmpty() || group.unset.isNotEmpty())) drafts
            else {
                val changed = identity.groups[type] != key
                if (!changed && group is RuntimeMutationChange.AssociateGroup) emptyList()
                else {
                    val properties = linkedMapOf<String, Any?>("\$group_type" to type, "\$group_key" to key)
                    if (group is RuntimeMutationChange.SetGroupProperties) properties["\$group_set"] = group.set
                    val projected = project("\$groupidentify", properties)
                    val set = objectValue(projected.properties["\$group_set"])
                    buildList {
                        if (changed) add(row(RuntimeMutationChange.AssociateGroup(type, key)))
                        if (set != null) add(row(RuntimeMutationChange.SetGroupProperties(type, key, set, emptyMap(), emptyList())))
                    }
                }
            }
        } else {
            require(drafts.size == 1)
            when (val change = first.change) {
                is RuntimeMutationChange.Identify -> {
                    val projected = project("\$identify", mapOf("distinct_id" to change.userId,
                        "\$anon_distinct_id" to (identity.userId ?: identity.anonymousId)), RuntimeEventPerson(change.set, change.setOnce))
                    listOf(row(change.copy(set = projected.person.set.orEmpty(), setOnce = projected.person.setOnce.orEmpty())))
                }
                is RuntimeMutationChange.LinkAlias -> {
                    project("\$create_alias", mapOf("alias" to change.aliasId, "distinct_id" to change.canonicalId))
                    drafts // The callback cannot redirect either original identity.
                }
                is RuntimeMutationChange.SetPersonProperties -> if (change.unset.isNotEmpty()) drafts else {
                    val projected = project("\$set", mapOf("\$set" to change.set, "\$set_once" to change.setOnce))
                    val set = objectValue(projected.properties["\$set"]).orEmpty()
                    val once = objectValue(projected.properties["\$set_once"]).orEmpty()
                    if (set.isEmpty() && once.isEmpty()) emptyList() else listOf(row(change.copy(set = set, setOnce = once)))
                }
                else -> error("Unsupported mutation projection")
            }
        }
        RuntimeMutationFilterResult.Prepared(changes)
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
        RuntimeMutationFilterResult.Refused((error as? MutationFilterRefusal)?.reason ?: RuntimeCaptureRejection.FILTER_INVALID)
    }

    private class MutationFilterRefusal(val reason: RuntimeCaptureRejection) : IllegalArgumentException()

    private class Copier {
        private var nodes = 0
        private var bytes = 0L
        private val visiting = IdentityHashMap<Any, Boolean>()
        @Suppress("UNCHECKED_CAST")
        fun objectValue(value: Map<String, Any?>): MutableMap<String, Any?> = copy(value, 0) as MutableMap<String, Any?>
        private fun text(value: String): String {
            bytes += value.length.toLong() * 4L
            require(bytes <= 10_485_760L)
            requireUnicode(value)
            return value
        }
        private fun copy(value: Any?, depth: Int): Any? {
            require(depth <= 16 && ++nodes <= 4096)
            return when (value) {
                null, is Boolean -> value
                is String -> text(value)
                is Byte, is Short, is Int, is Long -> value
                is Float -> value.also { require(it.isFinite()) }
                is Double -> value.also { require(it.isFinite()) }
                is Map<*, *> -> {
                    require(value.size <= 1024 && visiting.put(value, true) == null)
                    try { linkedMapOf<String, Any?>().apply {
                        value.forEach { (key, child) -> require(key is String); put(text(key), copy(child, depth + 1)) }
                    } } finally { visiting.remove(value) }
                }
                is List<*> -> {
                    require(value.size <= 1024 && visiting.put(value, true) == null)
                    try { value.mapTo(mutableListOf()) { copy(it, depth + 1) } } finally { visiting.remove(value) }
                }
                else -> throw IllegalArgumentException("Non-JSON event filter value")
            }
        }
    }

    private companion object {
        fun protected(key: String): Boolean = key.startsWith("\$elu_") || key in setOf(
            "distinct_id", "\$device_id", "\$user_id", "\$anon_distinct_id", "\$session_id", "\$window_id",
            "\$groups", "\$is_identified", "\$process_person_profile", "\$epp")
        fun requireUnicode(value: String) {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (Character.isHighSurrogate(char)) require(index < value.length && Character.isLowSurrogate(value[index++]))
                else require(!Character.isLowSurrogate(char))
            }
        }
    }
}

internal data class RuntimeEventPerson(val set: Map<String, Any?>? = null, val setOnce: Map<String, Any?>? = null)
internal sealed interface RuntimeMutationFilterResult {
    data class Prepared(val drafts: List<RuntimeRecordDraft.Mutation>) : RuntimeMutationFilterResult
    data class Refused(val reason: RuntimeCaptureRejection) : RuntimeMutationFilterResult
}
internal sealed interface RuntimeEventFilterResult {
    data class Prepared(val name: String, val occurredAt: String, val properties: Map<String, Any?>,
        val person: RuntimeEventPerson) : RuntimeEventFilterResult
    data class Refused(val reason: RuntimeCaptureRejection) : RuntimeEventFilterResult
}

/** Queue-worker confined, and attached to the original attempt across authority/SQL retries. */
internal class RuntimeEventFilterAttempt(
    val person: RuntimeEventPerson = RuntimeEventPerson(), val allowsPersonChanges: Boolean = true,
    val deferPersonContinuation: Boolean = false,
) {
    private var owner: Any? = null
    private var command: RuntimeCaptureCommand? = null
    private var input: Any? = null
    private var current: (() -> Boolean)? = null
    private var result: RuntimeEventFilterResult? = null
    fun prepare(originalOwner: Any, originalCommand: RuntimeCaptureCommand, originalInput: Any,
        originalCurrent: () -> Boolean, body: () -> RuntimeEventFilterResult): RuntimeEventFilterResult {
        if (owner != null) {
            check(owner === originalOwner && command == originalCommand) { "Filter attempt reused outside original capture" }
            if (input != originalInput || !isCurrent())
                return RuntimeEventFilterResult.Refused(RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED)
            return checkNotNull(result)
        }
        owner = originalOwner; command = originalCommand; input = originalInput; current = originalCurrent
        // Mark ownership before entering customer code. No lock surrounds body().
        result = if (isCurrent()) body() else RuntimeEventFilterResult.Refused(RuntimeCaptureRejection.AUTHORITY_WITNESS_CHANGED)
        return checkNotNull(result)
    }
    fun isCurrent(): Boolean = current?.invoke() == true
    fun acceptedPerson(): RuntimeEventPerson? = (result as? RuntimeEventFilterResult.Prepared)?.person
}
