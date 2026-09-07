package dev.elu.analytics

/**
 * Which runtime the public facade drives for the life of a process.
 *
 * [WRAPPED] keeps the published behavior: every facade method reaches the embedded analytics
 * runtime that ships as a compile dependency. [STANDALONE] drives the ELU-owned event runtime and
 * feature-flag client instead. A process runs exactly one of them — the facade never sends the
 * same call to both, and the two never both mutate identity.
 */
internal enum class EluRuntimeMode {
    WRAPPED,
    STANDALONE,
}

/**
 * The compiled runtime selection, read once by [Elu.setup].
 *
 * It is deliberately not part of the public API: which runtime a released binary drives is an ELU
 * release decision, not a host setting, and the two runtimes keep separate identity storage. The
 * default is [EluRuntimeMode.WRAPPED]; selecting [EluRuntimeMode.STANDALONE] for a release
 * requires event acceptance and readback evidence from the ELU engine.
 */
internal object EluRuntimeSelector {
    @Volatile
    private var mode: EluRuntimeMode = EluRuntimeMode.WRAPPED

    fun mode(): EluRuntimeMode = mode

    fun select(mode: EluRuntimeMode) {
        this.mode = mode
    }
}
