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

        /**
         * The self-hosted ELU instance this app sends to, or null for ELU Cloud.
         * Declaring it is what lets [configHost] name that instance.
         */
        public val apiHost: String?
            get() = selfHostedApiHost

        public constructor(configHost: String, apiHost: String?) : this(configHost) {
            selfHostedApiHost = apiHost
        }
    }
