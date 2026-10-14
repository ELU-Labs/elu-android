package dev.elu.analytics.internal.facade

import dev.elu.analytics.internal.runtime.RuntimeWallTimestamps
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Date
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Projects the untyped values customer code passes to the `Elu` facade onto the JSON domain the
 * runtime persists, and removes the property names the runtime derives for itself.
 *
 * The projection follows JSON serialization rather than refusing the call: a value JSON cannot
 * carry is left out of an object and becomes null in an array, so one unsupported value never
 * discards a whole event. A [Date] becomes its RFC 3339 instant and a URI or URL its string form.
 */
internal object FacadeJson {
    /** Matches the nesting depth the runtime's JSON validation accepts. */
    private const val MAXIMUM_DEPTH = 64

    /** Bounds the work one call can cause, including shared or cyclic containers. */
    private const val MAXIMUM_NODES = 65_536

    /** The projected properties plus the number of reserved names and unsupported values removed. */
    class Projected(val properties: Map<String, Any?>, val reserved: Int, val invalid: Int)

    fun properties(properties: Map<String, Any>?, isReserved: (String) -> Boolean): Projected {
        if (properties.isNullOrEmpty()) return Projected(emptyMap(), 0, 0)
        val projection = Projection()
        val kept = LinkedHashMap<String, Any?>(properties.size)
        var reserved = 0
        var invalid = 0
        properties.forEach { (key, value) ->
            if (isReserved(key)) {
                reserved += 1
                return@forEach
            }
            val projected = projection.value(value, 0)
            if (projected === UNSUPPORTED) invalid += 1 else kept[key] = projected
        }
        return Projected(kept, reserved, invalid)
    }

    /** One value, or [UNSUPPORTED] when JSON cannot carry it. */
    fun value(value: Any?): Any? = Projection().value(value, 0)

    /** Marks a value the projection leaves out. Never stored. */
    val UNSUPPORTED: Any = Any()

    private class Projection {
        private var nodes = 0
        private val ancestors = IdentityHashMap<Any, Boolean>()

        fun value(value: Any?, depth: Int): Any? {
            if (depth > MAXIMUM_DEPTH || ++nodes > MAXIMUM_NODES) return UNSUPPORTED
            return when (value) {
                null, is String, is Boolean -> value
                is CharSequence -> value.toString()
                is Number -> number(value)
                is Date -> RuntimeWallTimestamps.rfc3339(value.time)
                is java.net.URI, is java.net.URL, is android.net.Uri -> value.toString()
                is Map<*, *> -> container(value) {
                    if (!value.keys.all { it is String }) return@container UNSUPPORTED
                    val members = LinkedHashMap<String, Any?>(value.size)
                    value.forEach { (key, member) ->
                        val projected = value(member, depth + 1)
                        if (projected !== UNSUPPORTED) members[key as String] = projected
                    }
                    members
                }
                is Collection<*> -> container(value) { list(value, depth) }
                is Array<*> -> container(value) { list(value.asList(), depth) }
                is IntArray -> list(value.asList(), depth)
                is LongArray -> list(value.asList(), depth)
                is ShortArray -> list(value.asList(), depth)
                is ByteArray -> list(value.asList(), depth)
                is DoubleArray -> list(value.asList(), depth)
                is FloatArray -> list(value.asList(), depth)
                is BooleanArray -> list(value.asList(), depth)
                else -> UNSUPPORTED
            }
        }

        /** A member JSON cannot carry becomes null, so the remaining members keep their positions. */
        private fun list(values: Collection<*>, depth: Int): List<Any?> =
            values.map { member -> value(member, depth + 1).takeUnless { it === UNSUPPORTED } }

        private inline fun container(value: Any, body: () -> Any?): Any? {
            if (ancestors.put(value, true) != null) return UNSUPPORTED
            try {
                return body()
            } finally {
                ancestors.remove(value)
            }
        }

        private fun number(value: Number): Any =
            when (value) {
                is Byte, is Short, is Int, is Long -> value
                is Float -> value.toDouble().let(::finite)
                is Double -> finite(value)
                is AtomicInteger -> value.get()
                is AtomicLong -> value.get()
                is BigInteger -> if (value.bitLength() < 64) value.toLong() else UNSUPPORTED
                is BigDecimal -> finite(value.toDouble())
                else -> UNSUPPORTED
            }

        /** Non-finite numbers have no JSON form. 2^63 is the one finite value Android JSON cannot keep. */
        private fun finite(value: Double): Any =
            if (!value.isFinite() || value == Long.MAX_VALUE.toDouble()) UNSUPPORTED else value
    }
}
