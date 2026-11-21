package com.sdm.core.common

/**
 * What this install may use. The GitHub build includes everything; the Google Play build updates
 * [premium] from Play billing (ADR-0010). Free users keep every core download feature; Premium adds
 * the advanced ones listed in [PremiumFeature]. Checked in the UI (to explain and offer Premium)
 * and again where work runs, so a download queued earlier can't bypass it.
 */
object Entitlements {
    @Volatile var premium: Boolean = true

    const val FREE_MAX_CONNECTIONS = 8
    const val FREE_MAX_CONCURRENT = 3
    const val MAX_CONNECTIONS = 16

    fun maxConnections() = if (premium) MAX_CONNECTIONS else FREE_MAX_CONNECTIONS
    fun maxConcurrent(requested: Int) = if (premium) requested else requested.coerceAtMost(FREE_MAX_CONCURRENT)

    const val REQUIRES_PREMIUM = "This needs SDM Premium."
}

enum class PremiumFeature(val title: String) {
    TORRENT("Torrents and magnet links"),
    REMOTE("FTP, FTPS and SFTP downloads"),
    CONNECTIONS("Up to 16 connections per download"),
    CONCURRENT("More than 3 downloads at once"),
    PAGE_LINKS("Download all files on a page"),
    AUTO_EXTRACT("Extract ZIP files automatically"),
    PRIVATE_FOLDER("Private folder"),
    LAN("Send links from a computer"),
    APPEARANCE("Accent colors, wallpaper colors, AMOLED black"),
}
