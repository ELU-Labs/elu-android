package dev.elu.analytics

import java.util.TimeZone

/**
 * Timezone heuristic identical to the web loader's `euGuard.ts`. Fail-closed:
 * an empty/unreadable zone counts as EU. Knowingly over-blocks UK/CH/TR (web
 * parity); the server-side GeoIP drop transformation remains the backstop.
 */
internal object EluEuGuard {
    private val EXTRA_EU_ZONES =
        setOf(
            "Asia/Nicosia",
            "Asia/Famagusta",
            "Atlantic/Canary",
            "Atlantic/Madeira",
            "Atlantic/Azores",
            "Atlantic/Reykjavik",
            "Atlantic/Faroe",
            "Africa/Ceuta",
            "America/Cayenne",
            "America/Guadeloupe",
            "America/Martinique",
            "Indian/Reunion",
            "Indian/Mayotte",
        )

    fun isEuTimezone(): Boolean {
        val id =
            try {
                TimeZone.getDefault()?.id
            } catch (t: Throwable) {
                null
            }
        if (id.isNullOrBlank()) return true
        return id.startsWith("Europe/") || id in EXTRA_EU_ZONES
    }
}
