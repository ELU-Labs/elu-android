package dev.elu.analytics.internal.facade

/** One pending choice, ordered with publication; runtime construction never holds this lock. */
internal class EluConsentHandoff {
    private val lock = Any()
    @Volatile var sink: EluFacadeSink? = null
        private set
    private data class Choice(val optedOut: Boolean, val eventName: String?, val properties: Map<String, Any>?) {
        fun apply(target: EluFacadeSink) {
            if (optedOut) target.optOut() else target.optIn(eventName, properties)
        }
    }
    private var pending: Choice? = null

    fun optOut() = accept(Choice(true, null, null))

    fun optIn(eventName: String?, properties: Map<String, Any>?) {
        // Invalid opt-in must never replace a valid denial, including before setup.
        if (eventName != null && (eventName.isEmpty() || eventName.length > 512)) return
        accept(Choice(false, eventName, properties?.toMap()))
    }

    private fun accept(choice: Choice) = synchronized(lock) {
        val current = sink
        if (current == null) pending = choice else choice.apply(current)
    }

    fun isOptedOut(): Boolean = synchronized(lock) { sink?.isOptedOut() ?: (pending?.optedOut == true) }

    /** [start] only enqueues startup. Pending consent reaches the facade before it can open. */
    fun install(target: EluFacadeSink, start: () -> Unit) = synchronized(lock) {
        check(sink == null)
        pending?.apply(target)
        sink = target
        try {
            start()
            pending = null
        } catch (error: Throwable) {
            sink = null
            throw error
        }
    }
}
