package com.sdm.core.common

/**
 * Sources a build is not allowed to download from. The Google Play build turns this on because
 * Play removes apps that download YouTube videos (YouTube's terms forbid it); the GitHub build
 * leaves it off. Checked wherever a download can start: the add dialog, imports, the browser's
 * video detection and link lists, and finally when a download is queued.
 */
object SourcePolicy {
    /** Set once at app start from the build flavor. */
    @Volatile var blockVideoPlatforms: Boolean = false

    const val MESSAGE = "Downloads from YouTube aren't available in the Google Play version of SDM."

    private val blockedHosts = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "youtubekids.com")

    fun isBlockedHost(host: String?): Boolean {
        if (!blockVideoPlatforms || host.isNullOrBlank()) return false
        val h = host.lowercase().trimEnd('.')
        return blockedHosts.any { h == it || h.endsWith(".$it") }
    }

    fun isBlocked(url: String?): Boolean {
        if (!blockVideoPlatforms || url.isNullOrBlank()) return false
        return isBlockedHost(Urls.host(url.trim()))
    }
}
