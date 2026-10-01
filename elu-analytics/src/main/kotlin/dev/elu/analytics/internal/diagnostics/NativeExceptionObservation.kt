package dev.elu.analytics.internal.diagnostics

/** Detached metadata only. This is neither a capture command nor evidence of durable admission. */
internal class NativeExceptionObservation private constructor(
    val type: String,
    val typeTruncated: Boolean,
) {
    val mechanism: NativeExceptionMechanism = NativeExceptionMechanism.JVM_UNCAUGHT
    val handled: Boolean = false

    companion object {
        const val MAX_TYPE_UTF16_UNITS: Int = 256

        /**
         * Object.getClass and Class.getName do not invoke Throwable's overridable detail getters.
         * Do not substitute the manual exception serializer here. VM allocation failures can still
         * occur; the owner must delegate to the original handler even when this factory fails.
         */
        fun from(throwable: Throwable): NativeExceptionObservation {
            val name = throwable.javaClass.name
            var end = minOf(name.length, MAX_TYPE_UTF16_UNITS)
            if (end < name.length && end > 0 && Character.isHighSurrogate(name[end - 1])) end -= 1
            val bounded = StringBuilder(end)
            for (index in 0 until end) {
                val character = name[index]
                bounded.append(if (Character.isISOControl(character)) '?' else character)
            }
            return NativeExceptionObservation(bounded.toString(), end < name.length)
        }
    }
}

internal enum class NativeExceptionMechanism {
    JVM_UNCAUGHT,
}
