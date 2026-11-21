package com.sdm.core.common

object MimeTypes {
    private val byExt = mapOf(
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar", "gz" to "application/gzip", "apk" to "application/vnd.android.package-archive",
        "pdf" to "application/pdf", "mp3" to "audio/mpeg", "wav" to "audio/wav", "flac" to "audio/flac",
        "aac" to "audio/aac", "ogg" to "audio/ogg", "m4a" to "audio/mp4", "mp4" to "video/mp4",
        "mkv" to "video/x-matroska", "mov" to "video/quicktime", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "iso" to "application/x-iso9660-image", "exe" to "application/vnd.microsoft.portable-executable",
        "msi" to "application/x-msi", "txt" to "text/plain", "csv" to "text/csv", "json" to "application/json",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "epub" to "application/epub+zip", "torrent" to "application/x-bittorrent", "dmg" to "application/x-apple-diskimage",
        "deb" to "application/vnd.debian.binary-package", "bin" to "application/octet-stream", "html" to "text/html",
    )
    private val byMime: Map<String, String> = byExt.entries.associate { (k, v) -> v to k } + mapOf("image/jpeg" to "jpg")

    fun forExtension(ext: String): String? = byExt[ext.lowercase()]
    fun forName(name: String): String = forExtension(FileNames.extension(name)) ?: "application/octet-stream"

    fun extensionFor(mime: String?): String? {
        val clean = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (clean == "application/octet-stream" || clean.isEmpty()) return null
        return byMime[clean]
    }

    /** Known downloadable extensions (spec section 8) used by link and media detection. */
    val downloadableExtensions: Set<String> = byExt.keys - setOf("html", "txt", "json", "csv")
    val mediaExtensions: Set<String> = setOf("mp4", "mkv", "mov", "webm", "avi", "mp3", "wav", "flac", "aac", "ogg", "m4a", "jpg", "jpeg", "png", "gif", "webp")
}
