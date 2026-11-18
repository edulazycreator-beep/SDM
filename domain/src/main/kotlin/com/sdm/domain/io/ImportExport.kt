package com.sdm.domain.io

import com.sdm.core.common.Urls
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Portable download list (spec section 21). Never contains cookies or authorization headers. */
@Serializable
data class ExportedDownload(
    val url: String,
    val fileName: String? = null,
    val totalBytes: Long? = null,
    val state: String? = null,
    val category: String? = null,
    val priority: String? = null,
    val connections: Int? = null,
    val checksumAlgorithm: String? = null,
    val checksumExpected: String? = null,
    val createdAt: Long? = null,
    val completedAt: Long? = null,
)

@Serializable
data class DownloadListFile(val format: String = FORMAT, val version: Int = 1, val exportedAt: Long, val downloads: List<ExportedDownload>) {
    companion object { const val FORMAT = "sdm-download-list" }
}

data class ImportResult(val valid: List<ExportedDownload>, val rejected: List<String>)

object DownloadListCodec {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false; explicitNulls = false }

    fun encode(file: DownloadListFile): String = json.encodeToString(DownloadListFile.serializer(), file)

    /** Accepts SDM JSON lists or plain JSON arrays of URLs/objects; validates every URL. */
    fun decode(text: String): ImportResult {
        val trimmed = text.trim()
        val items: List<ExportedDownload> = runCatching { json.decodeFromString(DownloadListFile.serializer(), trimmed).downloads }
            .recoverCatching { json.decodeFromString(ListSerializer(ExportedDownload.serializer()), trimmed) }
            .recoverCatching { json.decodeFromString(ListSerializer(String.serializer()), trimmed).map { ExportedDownload(url = it) } }
            .getOrElse { throw IllegalArgumentException("Not a valid SDM download list: ${it.message?.take(120)}") }
        val (ok, bad) = items.partition { Urls.isValidHttpUrl(it.url) }
        return ImportResult(ok.distinctBy { it.url }, bad.map { it.url })
    }

    /** TXT import: one URL per line; comments (#) and blank lines ignored; other text rejected. */
    fun decodeTxt(text: String): ImportResult {
        val valid = mutableListOf<ExportedDownload>()
        val rejected = mutableListOf<String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val urls = Urls.extract(line)
            if (urls.isEmpty()) rejected += line.take(200) else urls.forEach { valid += ExportedDownload(url = it) }
        }
        return ImportResult(valid.distinctBy { it.url }, rejected)
    }
}
