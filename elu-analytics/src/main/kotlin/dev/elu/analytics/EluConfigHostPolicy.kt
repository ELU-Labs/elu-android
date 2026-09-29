package dev.elu.analytics

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Decides which `configHost` override the SDK may contact.
 *
 * Every application may use an ELU HTTPS origin (`https://elu.dev` or a
 * subdomain). An application that declares a self-hosted ELU instance as its
 * `apiHost` may use exactly that base: both must be HTTPS on the default
 * port with the same host and canonical path prefix, so the declaration
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
     * Returns the normalized origin or declared self-hosted base when [configHost]
     * is approved for an application whose debuggable flag is [debuggable] and
     * whose declared self-hosted instance is [apiHost], or null when the
     * override must be refused.
     */
    fun resolve(
        configHost: String,
        debuggable: Boolean,
        apiHost: String? = null,
    ): String? {
        val declaredApiOrigin = apiHost?.let { selfHostedOrigin(it) ?: return null }
        // Only the locally declared base admits a prefix. Cloud and debug overrides
        // retain their root-only allowlist, even when another API base is declared.
        if (declaredApiOrigin != null && selfHostedOrigin(configHost) == declaredApiOrigin) return declaredApiOrigin
        val uri = parseOrigin(configHost) ?: return null
        val scheme = uri.scheme.lowercase(Locale.US)
        val host = uri.host.lowercase(Locale.US)

        val isEluOrigin = scheme == "https" && uri.port == -1 && (host == ELU_ROOT || host.endsWith(".$ELU_ROOT"))
        if (isEluOrigin) return "https://$host"

        val isLoopback = debuggable && (scheme == "http" || scheme == "https") && host in LOOPBACK_HOSTS
        if (isLoopback) {
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }

        return null
    }

    /** Canonical HTTPS base, with an optional regular path prefix and no explicit port. */
    internal fun selfHostedOrigin(value: String): String? {
        // Preserve prior outer-whitespace normalization; nothing within the URL is rewritten.
        val candidate = value.trim()
        if (candidate.any { it.code !in 0x21..0x7e || it == '\\' || it == '@' }) return null
        val uri = parseUri(candidate) ?: return null
        val host = uri.host.lowercase(Locale.US)
        if (uri.scheme.lowercase(Locale.US) != "https" || uri.port != -1 || !uri.rawAuthority.equals(uri.host, ignoreCase = true)) return null
        if (host.isEmpty() || host.endsWith(".") || host.startsWith(".")) return null
        if (host in SELF_HOSTED_EXCLUDED_HOSTS) return null
        // Java URI does not perform WHATWG's dot-segment cleanup. Reject those
        // spellings explicitly before either HTTP client can reinterpret them.
        val prefix = uri.rawPath.orEmpty().removeSuffix("/")
        if (prefix.isNotEmpty()) {
            if (!prefix.startsWith('/')) return null
            for (segment in prefix.drop(1).split('/')) {
                val dotsDecoded = segment.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".")
                if (segment.isEmpty() || dotsDecoded == "." || dotsDecoded == "..") return null
                // Encoded separators, controls and double escapes have proxy-dependent routing.
                if (Regex("%(?:20|2f|5c|25|0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(segment)) return null
            }
        }
        if (uri.normalize().rawPath != uri.rawPath) return null
        return "https://$host$prefix"
    }

    /** The URI when [value] is a bare `scheme://host[:port]` origin (an optional `/` path), else null. */
    private fun parseOrigin(value: String): URI? {
        val uri = parseUri(value.trim()) ?: return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        return uri
    }

    private fun parseUri(value: String): URI? {
        val uri =
            try {
                URI(value)
            } catch (e: URISyntaxException) {
                return null
            }
        if (uri.isOpaque || uri.scheme == null || uri.host == null) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        return uri
    }
}
