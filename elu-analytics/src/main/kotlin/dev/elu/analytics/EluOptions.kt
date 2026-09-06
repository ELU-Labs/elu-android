package dev.elu.analytics

/**
 * Optional setup overrides. Production apps should not need this — the
 * default config host is ELU's public endpoint.
 *
 * [configHost] accepts an ELU HTTPS origin (`https://elu.dev` or one of its
 * subdomains). A debuggable app may also use a loopback origin such as
 * `http://10.0.2.2:8787` or `http://localhost:8787` for local development
 * against a dev loader. Any other value makes [Elu.setup] log a warning and
 * leave the SDK idle, so the override cannot redirect a release build's
 * configuration traffic elsewhere.
 */
public class EluOptions
    @JvmOverloads
    constructor(
        public val configHost: String = "https://elu.dev",
    )
