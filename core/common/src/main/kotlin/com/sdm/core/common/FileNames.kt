package com.sdm.core.common

import java.net.URLDecoder

/** File-name extraction and sanitizing. Sanitizing is the path-traversal guard for every write. */
object FileNames {
    private val forbidden = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private const val MAX_LENGTH = 120

    fun sanitize(raw: String, fallback: String = "download"): String {
        var name = raw.trim().replace(forbidden, "_")
        name = name.replace(Regex("\\.{2,}"), ".").trimStart('.', ' ').trimEnd('.', ' ')
        if (name.isEmpty() || name == "_") name = fallback
        if (name.length > MAX_LENGTH) {
            val ext = extension(name)
            val keep = MAX_LENGTH - (if (ext.isEmpty()) 0 else ext.length + 1)
            name = name.take(keep) + if (ext.isEmpty()) "" else ".$ext"
        }
        return name
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /** RFC 6266 Content-Disposition: prefers filename* (RFC 5987) over filename. */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("(?i)filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)").find(header)
        if (star != null) {
            val charset = star.groupValues[1].ifBlank { "UTF-8" }
            val decoded = runCatching { URLDecoder.decode(star.groupValues[2].trim().trim('"').replace("+", "%2B"), charset) }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        val plain = Regex("(?i)filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)").find(header) ?: return null
        val value = plain.groupValues[2].ifEmpty { plain.groupValues[1] }.trim().trim('"')
        return value.ifBlank { null }
    }

    fun fromUrl(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        val last = path.substringAfterLast('/')
        if (last.isBlank()) return null
        return runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last).ifBlank { null }
    }

    /** Best file name for a download: header, then URL, then host; extension added from MIME type. */
    fun resolve(url: String, contentDisposition: String?, mimeType: String?): String {
        val raw = fromContentDisposition(contentDisposition) ?: fromUrl(url) ?: (Urls.host(url) ?: "download")
        var name = sanitize(raw)
        if (extension(name).isEmpty()) {
            val ext = MimeTypes.extensionFor(mimeType)
            if (ext != null) name = "$name.$ext"
        }
        return name
    }

    /** "file.zip" -> "file (1).zip" style name for collision avoidance. */
    fun numbered(name: String, n: Int): String {
        val ext = extension(name)
        return if (ext.isEmpty()) "$name ($n)" else "${baseName(name)} ($n).$ext"
    }
}
