package dev.elu.analytics.internal.runtime

import dev.elu.analytics.EluEvent
import dev.elu.analytics.internal.core.JsonValues
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
internal sealed interface RuntimeEventFilterResult {
    data class Prepared(val name: String, val occurredAt: String, val properties: Map<String, Any?>,
        val person: RuntimeEventPerson) : RuntimeEventFilterResult
    data class Refused(val reason: RuntimeCaptureRejection) : RuntimeEventFilterResult
}

/** Queue-worker confined, and attached to the original attempt across authority/SQL retries. */
internal class RuntimeEventFilterAttempt(
    val person: RuntimeEventPerson = RuntimeEventPerson(), val allowsPersonChanges: Boolean = false,
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
