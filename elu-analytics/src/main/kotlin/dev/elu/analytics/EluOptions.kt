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
    private var declaredRegionReplay = false
    /** Explicit annotated-region replay opt-in; API29+, local annotations and server policy are also required. */
    public val declaredRegionReplayEnabled: Boolean get() = declaredRegionReplay
    private var selfHostedApiHost: String? = null
    private var performanceOptions = EluPerformanceOptions()
    private var diagnosticsOptions = EluDiagnosticsOptions()
    private var personProfilesMode = EluPersonProfilesMode.IDENTIFIED_ONLY
    private var persistenceMode = EluPersistenceMode.PERSISTENT
    private var rateLimitingOptions = EluRateLimitingOptions()
    public val rateLimiting: EluRateLimitingOptions get() = rateLimitingOptions
    public val persistence: EluPersistenceMode get() = persistenceMode
    public val performance: EluPerformanceOptions get() = performanceOptions
    public val diagnostics: EluDiagnosticsOptions get() = diagnosticsOptions
    public val apiHost: String? get() = selfHostedApiHost
    public val personProfiles: EluPersonProfilesMode get() = personProfilesMode

    /** Keeps all previously published constructors unchanged and defaults other setup paths to false. */
    @JvmOverloads
    public constructor(declaredRegionReplayEnabled: Boolean,
        rateLimiting: EluRateLimitingOptions = EluRateLimitingOptions(),
        persistence: EluPersistenceMode = EluPersistenceMode.PERSISTENT,
        personProfiles: EluPersonProfilesMode = EluPersonProfilesMode.IDENTIFIED_ONLY,
        diagnostics: EluDiagnosticsOptions = EluDiagnosticsOptions(), performance: EluPerformanceOptions = EluPerformanceOptions(),
        configHost: String = "https://elu.dev", apiHost: String? = null) :
        this(rateLimiting, persistence, personProfiles, diagnostics, performance, configHost, apiHost) {
        declaredRegionReplay = declaredRegionReplayEnabled
    }

    /** A shared local budget, independent of persistence, identity and remote authority. */
    @JvmOverloads
    public constructor(rateLimiting: EluRateLimitingOptions, persistence: EluPersistenceMode = EluPersistenceMode.PERSISTENT,
        personProfiles: EluPersonProfilesMode = EluPersonProfilesMode.IDENTIFIED_ONLY,
        diagnostics: EluDiagnosticsOptions = EluDiagnosticsOptions(), performance: EluPerformanceOptions = EluPerformanceOptions(),
        configHost: String = "https://elu.dev", apiHost: String? = null) : this(persistence, personProfiles, diagnostics, performance, configHost, apiHost) {
        rateLimitingOptions = rateLimiting
    }

    /** Selects memory storage without replacing any previous constructor descriptor. */
    @JvmOverloads
    public constructor(persistence: EluPersistenceMode, personProfiles: EluPersonProfilesMode = EluPersonProfilesMode.IDENTIFIED_ONLY,
        diagnostics: EluDiagnosticsOptions = EluDiagnosticsOptions(), performance: EluPerformanceOptions = EluPerformanceOptions(),
        configHost: String = "https://elu.dev", apiHost: String? = null) : this(personProfiles, diagnostics, performance, configHost, apiHost) {
        persistenceMode = persistence
    }

    /** Adds profile selection without replacing any previously published constructor. */
    @JvmOverloads
    public constructor(personProfiles: EluPersonProfilesMode, diagnostics: EluDiagnosticsOptions = EluDiagnosticsOptions(),
        performance: EluPerformanceOptions = EluPerformanceOptions(), configHost: String = "https://elu.dev",
        apiHost: String? = null) : this(diagnostics, performance, configHost, apiHost) {
        personProfilesMode = personProfiles
    }

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
