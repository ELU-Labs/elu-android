package dev.elu.analytics

import java.net.URI
import java.net.URISyntaxException

/**
 * Decides which `configHost` override the SDK may contact.
 *
 * Every application may use an ELU HTTPS origin (`https://elu.dev` or a
 * subdomain). A debuggable application may additionally point at a local
 * loopback origin over HTTP or HTTPS, on any port, for development against a
 * dev loader. Any other value is rejected so the override cannot redirect a
 * release build's configuration traffic to a third party.
 */
internal object EluConfigHostPolicy {
    private const val ELU_ROOT = "elu.dev"

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "10.0.2.2")

    /**
     * Returns the normalized origin (`scheme://host[:port]`) when [configHost]
     * is approved for an application whose debuggable flag is [debuggable],
     * or null when the override must be refused.
     */
    fun resolve(
        configHost: String,
        debuggable: Boolean,
    ): String? {
        val uri =
            try {
                URI(configHost.trim())
            } catch (e: URISyntaxException) {
                return null
            }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        if (uri.isOpaque) return null

        val isEluOrigin = scheme == "https" && uri.port == -1 && (host == ELU_ROOT || host.endsWith(".$ELU_ROOT"))
        if (isEluOrigin) return "https://$host"

        val isLoopback = debuggable && (scheme == "http" || scheme == "https") && host in LOOPBACK_HOSTS
        if (isLoopback) {
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }
        return null
    }
}
