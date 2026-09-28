package dev.elu.analytics

import java.net.URI
import java.net.URISyntaxException

/**
 * Decides which `configHost` override the SDK may contact.
 *
 * Every application may use an ELU HTTPS origin (`https://elu.dev` or a
 * subdomain). An application that declares a self-hosted ELU instance as its
 * `apiHost` may use exactly that origin: both must be HTTPS on the default
 * port with the same host, and nothing else is accepted, so the declaration
 * cannot widen the override to any other host. A debuggable application may
 * additionally point at a local loopback origin over HTTP or HTTPS, on any
 * port, for development against a dev loader. Any other value is rejected so
 * the override cannot redirect a release build's configuration traffic to a
 * third party.
 */
internal object EluConfigHostPolicy {
    private const val ELU_ROOT = "elu.dev"

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2")

    // Loopback names the self-hosted rule never accepts: the debug-only loopback rule governs them.
    private val SELF_HOSTED_EXCLUDED_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")

    /**
     * Returns the normalized origin (`scheme://host[:port]`) when [configHost]
     * is approved for an application whose debuggable flag is [debuggable] and
     * whose declared self-hosted instance is [apiHost], or null when the
     * override must be refused.
     */
    fun resolve(
        configHost: String,
        debuggable: Boolean,
        apiHost: String? = null,
    ): String? {
        val uri = parseOrigin(configHost) ?: return null
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()

        val isEluOrigin = scheme == "https" && uri.port == -1 && (host == ELU_ROOT || host.endsWith(".$ELU_ROOT"))
        if (isEluOrigin) return "https://$host"

        val isLoopback = debuggable && (scheme == "http" || scheme == "https") && host in LOOPBACK_HOSTS
        if (isLoopback) {
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }

        val selfHosted = apiHost?.let { selfHostedOrigin(it) }
        if (selfHosted != null && selfHostedOrigin(configHost) == selfHosted) return selfHosted
        return null
    }

    /** `https://host` for an HTTPS origin on the default port with a plain, non-loopback host, else null. */
    private fun selfHostedOrigin(value: String): String? {
        val uri = parseOrigin(value) ?: return null
        val host = uri.host.lowercase()
        if (uri.scheme.lowercase() != "https" || uri.port != -1) return null
        if (host.isEmpty() || host.endsWith(".") || host.startsWith(".")) return null
        if (host in SELF_HOSTED_EXCLUDED_HOSTS) return null
        return "https://$host"
    }

    /** The URI when [value] is a bare `scheme://host[:port]` origin (an optional `/` path), else null. */
    private fun parseOrigin(value: String): URI? {
        val uri =
            try {
                URI(value.trim())
            } catch (e: URISyntaxException) {
                return null
            }
        if (uri.isOpaque || uri.scheme == null || uri.host == null) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        return uri
    }
}
