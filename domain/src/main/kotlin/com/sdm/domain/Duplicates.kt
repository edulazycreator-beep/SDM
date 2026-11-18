package com.sdm.domain

/** Duplicate detection (spec section 20). Never calls two files duplicates on the file name alone. */
enum class DuplicateKind(val message: String) {
    SAME_URL_ACTIVE("This link is already downloading or queued."),
    SAME_URL_COMPLETED("This link was already downloaded."),
    SAME_NAME_AND_SIZE("A file with the same name and size exists; it is probably the same file."),
    SAME_NAME_ONLY("A file with the same name exists. It may be a different file."),
    SAME_CHECKSUM("A file with identical content (same checksum) exists."),
}

enum class DuplicateResolution { DOWNLOAD_ANYWAY, REPLACE_EXISTING, RENAME_NEW, CANCEL }

data class DuplicateCandidate(val downloadId: Long, val url: String, val fileName: String, val sizeBytes: Long?, val state: DownloadState, val sha256: String?)

data class DuplicateMatch(val kind: DuplicateKind, val existing: DuplicateCandidate)

object DuplicateDetector {
    private fun normalize(url: String) = url.trim().substringBefore('#').trimEnd('/').lowercase()

    fun find(url: String, fileName: String, sizeBytes: Long?, sha256: String?, existing: List<DuplicateCandidate>): DuplicateMatch? {
        val n = normalize(url)
        existing.firstOrNull { sha256 != null && it.sha256 != null && it.sha256.equals(sha256, ignoreCase = true) }?.let { return DuplicateMatch(DuplicateKind.SAME_CHECKSUM, it) }
        existing.firstOrNull { normalize(it.url) == n && !it.state.isTerminal }?.let { return DuplicateMatch(DuplicateKind.SAME_URL_ACTIVE, it) }
        existing.firstOrNull { normalize(it.url) == n && it.state == DownloadState.COMPLETED }?.let { return DuplicateMatch(DuplicateKind.SAME_URL_COMPLETED, it) }
        val sameName = existing.filter { it.fileName.equals(fileName, ignoreCase = true) && it.state != DownloadState.CANCELLED }
        sameName.firstOrNull { sizeBytes != null && it.sizeBytes == sizeBytes }?.let { return DuplicateMatch(DuplicateKind.SAME_NAME_AND_SIZE, it) }
        sameName.firstOrNull()?.let { return DuplicateMatch(DuplicateKind.SAME_NAME_ONLY, it) }
        return null
    }
}
