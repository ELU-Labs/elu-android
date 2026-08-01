package dev.elu.analytics

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches `GET <configHost>/v1/<siteKey>/config` and persists the last
 * successful body verbatim under `filesDir/elu/`. Cached config is valid
 * indefinitely (stale-while-revalidate: better yesterday's config than dark).
 * All methods are blocking — call only from the SDK's background executor.
 */
internal class EluConfigClient(
    filesDir: File,
    private val siteKey: String,
    private val configHost: String,
) {
    private val cacheDir = File(filesDir, "elu")
    private val cacheFile = File(cacheDir, "config-${fsSafe(siteKey)}.json")

    fun loadCached(): EluRemoteConfig? {
        return try {
            if (cacheFile.isFile) EluRemoteConfig.parse(cacheFile.readText(Charsets.UTF_8)) else null
        } catch (t: Throwable) {
            null
        }
    }

    /** Returns null on any network/HTTP/parse failure; persists only usable bodies. */
    fun fetch(): EluRemoteConfig? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${configHost.trimEnd('/')}/v1/$siteKey/config")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = EluRemoteConfig.parse(body) ?: return null
            persist(body)
            parsed
        } catch (t: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun persist(body: String) {
        try {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, cacheFile.name + ".tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(body, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (t: Throwable) {
            // cache is best-effort; the in-memory config still applies
        }
    }

    private fun fsSafe(key: String): String =
        key.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_' }
            .joinToString("")

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
