package dev.elu.analytics.internal.facade

import dev.elu.analytics.EluRuntimeMode

/** One runtime the facade can drive: the sink calls go to, and the work that starts it. */
internal class EluFacadeLane(
    val sink: EluFacadeSink,
    val start: () -> Unit,
)

/**
 * Builds the lane for [mode] and only that lane. The runtime that is not selected is never
 * constructed, so it opens no storage, registers no callbacks, and cannot observe a call.
 */
internal fun selectFacadeLane(
    mode: EluRuntimeMode,
    wrapped: () -> EluFacadeLane,
    standalone: () -> EluFacadeLane,
): EluFacadeLane =
    when (mode) {
        EluRuntimeMode.WRAPPED -> wrapped()
        EluRuntimeMode.STANDALONE -> standalone()
    }
