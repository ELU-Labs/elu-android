package dev.elu.analytics

/**
 * Optional setup overrides. Production apps on ELU Cloud should not need this
 * — the default config host is ELU's public endpoint.
 *
 * [configHost] accepts an ELU HTTPS origin (`https://elu.dev` or one of its
 * subdomains). A debuggable app may also use a loopback origin such as
 * `http://10.0.2.2:8787` or `http://localhost:8787` for local development
 * against a dev loader.
 *
 * An app that sends to a self-hosted ELU instance names that instance as
 * [apiHost] and points [configHost] at the same origin:
 * `EluOptions(configHost = "https://analytics.example.com", apiHost = "https://analytics.example.com")`.
 * A config host outside `elu.dev` is accepted only when it is exactly the
 * [apiHost] the app was built with, over HTTPS on the default port.
 *
 * Any other value makes [Elu.setup] log a warning and leave the SDK idle, so
 * the override cannot redirect a release build's configuration traffic
 * elsewhere.
 */
public class EluOptions
    @JvmOverloads
    constructor(
        public val configHost: String = "https://elu.dev",
    ) {
    private var selfHostedApiHost: String? = null
    private var performanceOptions = EluPerformanceOptions()
    private var diagnosticsOptions = EluDiagnosticsOptions()
    public val performance: EluPerformanceOptions get() = performanceOptions
    public val diagnostics: EluDiagnosticsOptions get() = diagnosticsOptions
    public val apiHost: String? get() = selfHostedApiHost

    /** Keeps every original setup constructor available to compiled callers. */
    @JvmOverloads
    public constructor(performance: EluPerformanceOptions, configHost: String = "https://elu.dev") : this(configHost) {
        performanceOptions = performance
    }

    public constructor(configHost: String, apiHost: String?) : this(configHost) {
        selfHostedApiHost = apiHost
    }

    public constructor(performance: EluPerformanceOptions, configHost: String, apiHost: String?) : this(configHost, apiHost) {
        performanceOptions = performance
    }

    @JvmOverloads
    public constructor(diagnostics: EluDiagnosticsOptions, performance: EluPerformanceOptions = EluPerformanceOptions(),
        configHost: String = "https://elu.dev") : this(performance, configHost) {
        diagnosticsOptions = diagnostics
    }

    /** Combines explicit diagnostics with the application's declared API origin. */
    public constructor(diagnostics: EluDiagnosticsOptions, performance: EluPerformanceOptions = EluPerformanceOptions(),
        configHost: String = "https://elu.dev", apiHost: String?) : this(performance, configHost, apiHost) {
        diagnosticsOptions = diagnostics
    }
}
