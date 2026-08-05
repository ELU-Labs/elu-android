package dev.elu.analytics

import java.net.URL

/** Builds a config URL with the site key encoded as exactly one RFC 3986 path segment. */
internal object EluConfigUrl {
    fun build(
        configHost: String,
        siteKey: String,
    ): URL = URL("${configHost.trimEnd('/')}/v1/${encodePathSegment(siteKey)}/config")

    private fun encodePathSegment(value: String): String =
        buildString {
            value.toByteArray(Charsets.UTF_8).forEach { rawByte ->
                val byte = rawByte.toInt() and 0xff
                val isUnreserved =
                    byte in 'a'.code..'z'.code ||
                        byte in 'A'.code..'Z'.code ||
                        byte in '0'.code..'9'.code ||
                        byte == '-'.code ||
                        byte == '.'.code ||
                        byte == '_'.code ||
                        byte == '~'.code
                if (isUnreserved) {
                    append(byte.toChar())
                } else {
                    append('%')
                    append(HEX[byte ushr 4])
                    append(HEX[byte and 0x0f])
                }
            }
        }

    private const val HEX = "0123456789ABCDEF"
}
