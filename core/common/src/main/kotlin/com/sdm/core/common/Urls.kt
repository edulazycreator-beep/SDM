package com.sdm.core.common

import java.net.URI

object Urls {
    private val urlRegex = Regex("""(?i)\b(?:https?|s?ftps?)://[^\s<>"'`{}|\\^]+""")

    /** Only absolute http(s) URLs with a host are accepted (spec section 29, URL validation). */
    fun isValidHttpUrl(text: String): Boolean {
        val t = text.trim()
        if (t.length > 8192 || t.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(t) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    /** http(s) plus FTP, FTPS and SFTP links (file transfer servers). */
    fun isValidDownloadUrl(text: String): Boolean {
        if (isValidHttpUrl(text)) return true
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
