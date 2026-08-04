package dev.elu.analytics

/** Pure transition policy for config-driven lifecycle changes. */
internal object EluStatePolicy {
    enum class State { PENDING, RUNNING, DISABLED }

    enum class Action { INITIALIZE, DISABLE, APPLY_RUNNING, NO_OP }

    fun actionFor(
        state: State,
        enabled: Boolean,
        euBlocked: Boolean,
        runtimeInitialized: Boolean,
    ): Action =
        when (state) {
            State.PENDING -> if (enabled && !euBlocked) Action.INITIALIZE else Action.DISABLE
            State.DISABLED -> {
                if (enabled && !euBlocked && !runtimeInitialized) Action.INITIALIZE else Action.NO_OP
            }
            State.RUNNING -> Action.APPLY_RUNNING
        }
}
