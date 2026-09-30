package dev.elu.analytics.internal.config

import dev.elu.analytics.EluConfigHostPolicy
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Minted only from the application's local setup declaration, never a configuration response. */
internal class LocalEndpointPolicy private constructor(val apiOrigin: String?) {
    // apiOrigin retains its original internal name, but now holds the entire
    // canonical local base. Storage hashes it; telemetry uses only apiHost.
    val apiHost: String? = apiOrigin?.let { URI(it).host }
    private val apiPrefix: String = apiOrigin?.let { URI(it).rawPath }.orEmpty()

    fun requireApproved(endpoint: URI, role: V1EndpointRole, schemaVersion: Int = V2_CONFIG_SCHEMA_VERSION) {
        require(endpoint.toString().all { it.code in 0x21..0x7e } && endpoint.isAbsolute &&
            endpoint.scheme == "https" && endpoint.rawUserInfo == null && endpoint.rawFragment == null &&
            (endpoint.port == -1 || endpoint.port == 443) && matchesRole(endpoint, role, schemaVersion)) {
            "Endpoint is outside the application's declared role origin"
        }
        endpoint.rawQuery?.split('&')?.forEach { part ->
            require(URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name()) != "site_key") {
                "Endpoint contains reserved authorization state"
            }
        }
    }

    /** Exact raster role; caller must also own the original native-v3 policy/claim. */
    fun requireNativeRasterApproved(endpoint: URI) {
        require(endpoint.rawPath == apiPrefix + "/v3/replay")
        // Reuse every original origin/credential/query restriction without broadening v1/v2 roles.
        val legacyRole = URI(endpoint.scheme + "://" + endpoint.rawAuthority + apiPrefix + "/v2/replay" +
            (endpoint.rawQuery?.let { "?$it" } ?: ""))
        requireApproved(legacyRole, V1EndpointRole.REPLAY)
        require(endpoint.rawFragment == null && endpoint.rawUserInfo == null && endpoint.toString().all { it.code in 0x21..0x7e })
    }

    fun matchesRole(endpoint: URI, role: V1EndpointRole, schemaVersion: Int): Boolean {
        val host = apiHost ?: if (role == V1EndpointRole.ASSETS) "assets.elu.dev" else "ingest.elu.dev"
        val path = when (role) {
            V1EndpointRole.EVENTS -> "/v1/events"
            V1EndpointRole.FLAGS -> "/v1/flags"
            V1EndpointRole.REPLAY -> if (schemaVersion == V2_CONFIG_SCHEMA_VERSION) "/v2/replay" else "/v1/replay"
            V1EndpointRole.ASSETS -> "/sdk/"
        }
        return endpoint.host?.lowercase(Locale.US) == host && endpoint.rawPath == apiPrefix + path
    }

    companion object {
        val CLOUD = LocalEndpointPolicy(null)
        fun fromApiHost(apiHost: String?): LocalEndpointPolicy = if (apiHost == null) CLOUD else
            LocalEndpointPolicy(requireNotNull(EluConfigHostPolicy.selfHostedOrigin(apiHost)) {
                "API host must be a declared HTTPS base on the default port"
            })
    }
}
