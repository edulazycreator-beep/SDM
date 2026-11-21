package com.sdm.core.common

/** File categories used by the file manager, default folders and analytics (spec section 12). */
enum class FileCategory(val label: String, val folder: String) {
    VIDEO("Videos", "Videos"),
    MUSIC("Music", "Music"),
    IMAGE("Images", "Images"),
    DOCUMENT("Documents", "Documents"),
    ARCHIVE("Archives", "Archives"),
    APPLICATION("Applications", "Applications"),
    OTHER("Other", "Other");

    companion object {
        private val ext = mapOf(
            VIDEO to setOf("mp4", "mkv", "mov", "webm", "avi", "m4v", "3gp", "flv", "wmv", "ts"),
            MUSIC to setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma"),
            IMAGE to setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg"),
            DOCUMENT to setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "odt", "rtf", "md"),
            ARCHIVE to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg"),
            APPLICATION to setOf("apk", "apks", "xapk", "exe", "msi", "deb", "rpm", "appimage", "aab"),
        )

        fun of(fileName: String, mimeType: String? = null): FileCategory {
            val e = FileNames.extension(fileName)
            ext.entries.firstOrNull { e in it.value }?.let { return it.key }
            val m = mimeType?.lowercase().orEmpty()
            return when {
                m.startsWith("video/") -> VIDEO
                m.startsWith("audio/") -> MUSIC
                m.startsWith("image/") -> IMAGE
                m.startsWith("text/") || m == "application/pdf" -> DOCUMENT
                m.contains("zip") || m.contains("compressed") || m.contains("tar") -> ARCHIVE
                m == "application/vnd.android.package-archive" -> APPLICATION
                else -> OTHER
            }
        }
    }
}
