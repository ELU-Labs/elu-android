package dev.elu.analytics.internal.diagnostics

/**
 * Setup and close must be serialized with all host default-handler registrations. The public
 * Thread API has get/set, not compare-and-set: checking ownership before set cannot protect
 * against an unrelated concurrent registration. This interface does not claim that guarantee.
 */
internal interface NativeUncaughtExceptionRegistry {
    fun current(): Thread.UncaughtExceptionHandler?

    fun replace(handler: Thread.UncaughtExceptionHandler)
}

internal object AndroidUncaughtExceptionRegistry : NativeUncaughtExceptionRegistry {
    override fun current(): Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun replace(handler: Thread.UncaughtExceptionHandler) {
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }
}

/**
 * An injected, synchronous, best-effort intake boundary. Implementations must make their own
 * current consent/authority decision and must not wait on the SDK queue, main thread or network.
 * An observation contains no permission, identity, session, receipt time or persistence claim.
 *
 * Arbitrary injected code can block: this interface and the owner do not provide a hard latency
 * or deadlock guarantee. Close denies callbacks starting afterward; a callback already executing
 * may reach or finish its offer. Close does not join that callback or cancel arbitrary code.
 * Future asynchronous storage must separately own and revalidate its original admission permit.
 */
internal fun interface NativeUncaughtExceptionAdmission {
    /** Cheap original admission check before even detached type inspection. No queue/SQL hop. */
    fun allowsObservation(): Boolean = true
    /** Freeze the original intake for this invocation; a later arm must not adopt its observation. */
    fun snapshot(): NativeUncaughtExceptionAdmission? = takeIf { allowsObservation() }
    fun offer(observation: NativeExceptionObservation)
}
