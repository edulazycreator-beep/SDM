package com.sdm.core.data.io

import android.content.Context
import android.net.Uri
import com.sdm.core.common.FileNames
import com.sdm.core.common.Redactor
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.download.NewDownload
import com.sdm.core.data.rules.QueueRepository
import com.sdm.core.data.rules.RulesRepository
import com.sdm.core.data.settings.AppSettings
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.database.SdmDatabase
import com.sdm.domain.Priority
import com.sdm.domain.io.DownloadListCodec
import com.sdm.domain.io.DownloadListFile
import com.sdm.domain.io.ExportedDownload
import com.sdm.domain.rules.Rule
import com.sdm.domain.rules.RuleAction
import com.sdm.domain.rules.RuleCondition
import com.sdm.domain.scheduler.Schedule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RuleBackup(val name: String, val enabled: Boolean, val priority: Int, val condition: RuleCondition, val action: RuleAction)

@Serializable
data class SettingsBackup(val format: String = "sdm-settings", val version: Int = 1, val createdAt: Long, val settings: AppSettings, val rules: List<RuleBackup>, val schedule: Schedule)

data class ImportSummary(val imported: Int, val skippedDuplicates: Int, val rejected: List<String>)

/** Import/export and backup (spec section 21). Exports never contain cookies or authorization headers. */
@Singleton
class ImportExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SdmDatabase,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    private val rules: RulesRepository,
    private val queues: QueueRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val resolver get() = context.contentResolver

    private suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("Cannot write the selected file")
    }

    private suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytes(MAX_IMPORT_BYTES + 1)
            if (bytes.size > MAX_IMPORT_BYTES) throw IOException("File is larger than 5 MB")
            String(bytes)
        } ?: throw IOException("Cannot read the selected file")
    }

    suspend fun exportDownloads(uri: Uri): Int {
        val all = db.downloads().all()
        val file = DownloadListFile(exportedAt = System.currentTimeMillis(), downloads = all.map {
            ExportedDownload(it.originalUrl, it.fileName, it.totalBytes, it.state.name, it.category.name, it.priority.name, it.maxConnections, it.checksumAlgorithm, it.checksumExpected, it.createdAt, it.completedAt)
        })
        write(uri, DownloadListCodec.encode(file))
        return all.size
    }

    /** CSV history: one row per download, URL query secrets redacted. */
    suspend fun exportHistory(uri: Uri): Int {
        val all = db.downloads().all().sortedByDescending { it.createdAt }
        val csv = buildString {
            appendLine("file_name,status,size_bytes,created,completed,url,error")
            all.forEach { d ->
                fun q(v: String?) = "\"" + (v ?: "").replace("\"", "\"\"") + "\""
                appendLine(listOf(q(d.fileName), d.state.name, d.totalBytes?.toString() ?: "", Instant.ofEpochMilli(d.createdAt).toString(), d.completedAt?.let { Instant.ofEpochMilli(it).toString() } ?: "", q(Redactor.url(d.originalUrl)), q(d.errorCode)).joinToString(","))
            }
        }
        write(uri, csv)
        return all.size
    }

    /** Accepts SDM JSON lists, JSON arrays and TXT files; every URL is validated, none is assumed to still work. */
    suspend fun importFile(uri: Uri, startPaused: Boolean): ImportSummary {
        val text = read(uri)
        val trimmed = text.trimStart()
        val parsed = if (trimmed.startsWith("{") || trimmed.startsWith("[")) DownloadListCodec.decode(text) else DownloadListCodec.decodeTxt(text)
        return importList(parsed.valid, parsed.rejected, startPaused)
    }

    suspend fun importUrls(text: String, startPaused: Boolean): ImportSummary {
        val parsed = DownloadListCodec.decodeTxt(text)
        return importList(parsed.valid, parsed.rejected, startPaused)
    }

    private suspend fun importList(items: List<ExportedDownload>, rejected: List<String>, startPaused: Boolean): ImportSummary {
        val known = db.downloads().all().map { it.originalUrl }.toSet()
        var imported = 0; var dup = 0
        val blocked = mutableListOf<String>()
        val s = settings.current()
        for (e in items) {
            if (e.url in known) { dup++; continue }
            if (com.sdm.core.common.SourcePolicy.isBlocked(e.url)) { blocked += e.url; continue }
            val name = e.fileName?.let { FileNames.sanitize(it) } ?: FileNames.resolve(e.url, null, null)
            downloads.enqueue(NewDownload(
                url = e.url, fileName = name, sizeBytes = null, connections = e.connections ?: s.defaultConnections,
                priority = Priority.entries.firstOrNull { it.name == e.priority } ?: Priority.NORMAL,
                checksumAlgorithm = e.checksumAlgorithm, checksumExpected = e.checksumExpected, startPaused = startPaused, networkPolicy = s.networkPolicy,
            ))
            imported++
        }
        return ImportSummary(imported, dup, rejected + blocked)
    }

    suspend fun backupSettings(uri: Uri) {
        val backup = SettingsBackup(
            createdAt = System.currentTimeMillis(),
            settings = settings.current().copy(lastClipboardHash = 0),
            rules = rules.current().map { RuleBackup(it.name, it.enabled, it.priority, it.condition, it.action) },
            schedule = queues.main().schedule,
        )
        write(uri, json.encodeToString(SettingsBackup.serializer(), backup))
    }

    suspend fun restoreSettings(uri: Uri): Int {
        val backup = runCatching { json.decodeFromString(SettingsBackup.serializer(), read(uri)) }.getOrElse { throw IOException("Not an SDM settings backup") }
        if (backup.format != "sdm-settings") throw IOException("Not an SDM settings backup")
        settings.update { backup.settings.copy(onboardingDone = true, lastClipboardHash = it.lastClipboardHash) }
        rules.replaceAll(backup.rules.map { Rule(0, it.name, it.enabled, it.priority, it.condition, it.action) })
        queues.save(queues.main().copy(schedule = backup.schedule))
        return backup.rules.size
    }

    private companion object { const val MAX_IMPORT_BYTES = 5 * 1024 * 1024 }
}
