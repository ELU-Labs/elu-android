package dev.elu.analytics.internal.runtime

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Converts a [Throwable] into bounded, JSON-safe exception event properties. Messages and
 * frames are capped so a pathological throwable cannot exceed the queue's record ceiling, and
 * cause chains are walked by object identity so a self-referential cause cannot loop.
 */
internal object ExceptionSerializer {
    const val EVENT_NAME: String = "\$exception"
    const val TYPE_PROPERTY: String = "\$exception_type"
    const val MESSAGE_PROPERTY: String = "\$exception_message"
    const val LIST_PROPERTY: String = "\$exception_list"
    const val MAX_MESSAGE_CODE_POINTS: Int = 1_024
    const val MAX_FRAMES_PER_THROWABLE: Int = 64
    const val MAX_CHAIN_LENGTH: Int = 8

    fun properties(throwable: Throwable): Map<String, Any?> {
        val chain = causeChain(throwable)
        val entries =
            chain.map { link ->
                val entry = LinkedHashMap<String, Any?>()
                entry["type"] = typeName(link)
                entry["value"] = message(link)
                entry["module"] = moduleName(link)
                entry["stacktrace"] = mapOf("type" to "raw", "frames" to frames(link))
                Collections.unmodifiableMap(entry)
            }
        val properties = LinkedHashMap<String, Any?>()
        properties[TYPE_PROPERTY] = typeName(throwable)
        properties[MESSAGE_PROPERTY] = message(throwable)
        properties[LIST_PROPERTY] = Collections.unmodifiableList(entries)
        return Collections.unmodifiableMap(properties)
    }

    fun command(
        throwable: Throwable,
        occurredAt: String,
        versions: RuntimeVersions,
        explicitProperties: Map<String, Any?> = emptyMap(),
    ): RuntimeCaptureCommand {
        val merged = LinkedHashMap(properties(throwable))
        merged.putAll(explicitProperties)
        return RuntimeCaptureCommand(
            kind = RuntimeEventKind.EXCEPTION,
            name = EVENT_NAME,
            occurredAt = occurredAt,
            properties = Collections.unmodifiableMap(merged),
            versions = versions,
        )
    }

    /** Replaces unpaired UTF-16 surrogates so the value survives strict JSON validation. */
    fun sanitize(
        value: String,
        maximumCodePoints: Int,
    ): String {
        val out = StringBuilder(minOf(value.length, maximumCodePoints * 2))
        var index = 0
        var codePoints = 0
        while (index < value.length && codePoints < maximumCodePoints) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                        out.append(character).append(value[index + 1])
                        index += 2
                    } else {
                        out.append(REPLACEMENT)
                        index += 1
                    }
                }
                Character.isLowSurrogate(character) -> {
                    out.append(REPLACEMENT)
                    index += 1
                }
                else -> {
                    out.append(character)
                    index += 1
                }
            }
            codePoints += 1
        }
        return out.toString()
    }

    private fun causeChain(throwable: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val chain = ArrayList<Throwable>(MAX_CHAIN_LENGTH)
        var current: Throwable? = throwable
        while (current != null && chain.size < MAX_CHAIN_LENGTH && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private fun typeName(throwable: Throwable): String {
        val type = throwable.javaClass
        val simple = type.simpleName
        return sanitize(if (simple.isEmpty()) type.name else simple, MAX_MESSAGE_CODE_POINTS)
    }

    private fun moduleName(throwable: Throwable): String? =
        throwable.javaClass.`package`?.name?.takeIf { it.isNotEmpty() }?.let { sanitize(it, MAX_MESSAGE_CODE_POINTS) }

    private fun message(throwable: Throwable): String? {
        val raw =
            try {
                throwable.message
            } catch (_: RuntimeException) {
                null
            }
        return raw?.let { sanitize(it, MAX_MESSAGE_CODE_POINTS) }
    }

    private fun frames(throwable: Throwable): List<Map<String, Any?>> {
        val trace =
            try {
                throwable.stackTrace
            } catch (_: RuntimeException) {
                emptyArray()
            }
        return Collections.unmodifiableList(
            trace.take(MAX_FRAMES_PER_THROWABLE).map { element ->
                val frame = LinkedHashMap<String, Any?>()
                frame["module"] = sanitize(element.className, MAX_MESSAGE_CODE_POINTS)
                frame["function"] = sanitize(element.methodName, MAX_MESSAGE_CODE_POINTS)
                frame["filename"] = element.fileName?.let { sanitize(it, MAX_MESSAGE_CODE_POINTS) }
                frame["lineno"] = element.lineNumber.takeIf { it >= 0 }
                frame["native"] = element.isNativeMethod
                Collections.unmodifiableMap(frame)
            },
        )
    }

    private const val REPLACEMENT = '\uFFFD'
}
