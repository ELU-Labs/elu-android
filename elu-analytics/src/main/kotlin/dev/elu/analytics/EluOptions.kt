package dev.elu.analytics

/**
 * Optional setup overrides. Production apps should not need this — the
 * default config host is ELU's public endpoint. [configHost] exists for
 * local development against a dev loader.
 */
public class EluOptions
    @JvmOverloads
    constructor(
        public val configHost: String = "https://elu.dev",
    )
