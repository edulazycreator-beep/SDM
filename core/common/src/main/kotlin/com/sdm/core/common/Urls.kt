package com.sdm.core.common

import java.net.URI

object Urls {
    private val urlRegex = Regex("""(?i)\b(?:(?:https?|s?ftps?)://|magnet:\?)[^\s<>"'`{}|\\^]+""")

    /** Only absolute http(s) URLs with a host are accepted (spec section 29, URL validation). */
    fun isValidHttpUrl(text: String): Boolean {
        val t = text.trim()
        if (t.length > 8192 || t.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(t) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    /** BitTorrent magnet link with an info hash (BEP 9). */
    fun isMagnet(text: String): Boolean {
        val t = text.trim()
        return t.length <= 8192 && t.startsWith("magnet:?", ignoreCase = true) && Regex("(?i)[?&]xt=urn:bt(ih|mh):[0-9a-z]{32,}").containsMatchIn(t)
    }

    /** Display name of a magnet link (dn=), if it has one. */
    fun magnetName(text: String): String? = Regex("(?i)[?&]dn=([^&]+)").find(text)?.groupValues?.get(1)
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()?.ifBlank { null }

    /** http(s) plus FTP, FTPS and SFTP links (file transfer servers) and magnet links. */
    fun isValidDownloadUrl(text: String): Boolean {
        if (isValidHttpUrl(text) || isMagnet(text)) return true
        val t = text.trim()
        if (t.length > 8192 || t.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(t) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("ftp", "ftps", "sftp") && !uri.host.isNullOrBlank()
    }

    /** Extracts every http(s) URL from free text (paste box, TXT import, clipboard). Order kept, duplicates removed. */
    fun extract(text: String): List<String> =
        urlRegex.findAll(text).map { it.value.trimEnd('.', ',', ';', ')', ']') }.filter(::isValidDownloadUrl).distinct().toList()

    fun host(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()

    fun looksLikeDirectFile(url: String): Boolean {
        val name = FileNames.fromUrl(url) ?: return false
        return FileNames.extension(name) in MimeTypes.downloadableExtensions
    }
}
