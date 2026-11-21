package com.sdm.core.data.archive

import android.content.Context
import android.net.Uri
import com.sdm.core.common.FileCategory
import com.sdm.core.common.FileNames
import com.sdm.core.data.storage.OutputStore
import com.sdm.core.database.FileIndexEntity
import com.sdm.core.database.SdmDatabase
import com.sdm.domain.DestinationType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unpacks a ZIP download into a folder named after it, next to it. The archive itself is never
 * deleted. Guards: entry names cannot leave the folder (zip-slip), and extraction stops when the
 * unpacked size passes a limit or the free space (zip bombs).
 */
@Singleton
class ArchiveExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outputs: OutputStore,
    private val db: SdmDatabase,
) {
    data class Result(val files: Int, val bytes: Long, val folder: String, val skipped: Int)

    suspend fun extractZip(
        uri: String, archiveName: String, destinationType: DestinationType, treeUri: String?,
        maxBytes: Long = 8L shl 30, onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val folder = FileNames.sanitize(FileNames.baseName(archiveName)).ifBlank { "archive" }
        var files = 0; var bytes = 0L; var skipped = 0
        val free = runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        val limit = minOf(maxBytes, free - (256L shl 20)).coerceAtLeast(0)
        val input = open(uri)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = try { zip.nextEntry } catch (e: ZipException) { throw IOException("This ZIP file is damaged or uses an unsupported format (${e.message})") } ?: break
                if (entry.isDirectory) continue
                val parts = entry.name.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
                if (parts.isEmpty()) { skipped++; continue }
                val name = FileNames.sanitize(parts.last())
                val sub = (listOf(folder) + parts.dropLast(1).map { FileNames.sanitize(it) }).joinToString("/")
                val category = FileCategory.of(name)
                val counted = object : FilterInputStream(zip) {
                    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }
                    override fun close() {} // keep the archive stream open for the next entry
                    private fun count(n: Long) { bytes += n; if (bytes > limit) throw IOException("Stopped: the unpacked files would need more than ${com.sdm.core.common.Formatters.bytes(limit)} of space") }
                }
                val (outUri, display) = try {
                    outputs.writeNew(name, null, FileCategory.ARCHIVE, destinationType, treeUri, sub) { out -> counted.copyTo(out, 128 * 1024) }
                } catch (e: IOException) {
                    if (e.message?.startsWith("Stopped") == true) throw e
                    if (e.message?.contains("encrypt", true) == true) throw IOException("This ZIP file is password-protected; SDM can't open it")
                    skipped++; continue
                }
                db.files().upsert(listOf(FileIndexEntity(outUri, display, entry.size.takeIf { it >= 0 } ?: 0, com.sdm.core.common.MimeTypes.forName(display), category, System.currentTimeMillis(), "SDM/${FileCategory.ARCHIVE.folder}/$sub", null, null, null, treeUri)))
                files++
                onProgress(files)
            }
        }
        Result(files, bytes, folder, skipped)
    }

    private fun open(uri: String): InputStream {
        val u = Uri.parse(uri)
        return if (u.scheme == "file") java.io.File(u.path!!).inputStream() else context.contentResolver.openInputStream(u) ?: throw IOException("Cannot open the archive")
    }

    companion object {
        fun isZip(name: String, mime: String?) = FileNames.extension(name).equals("zip", true) || mime == "application/zip" || mime == "application/x-zip-compressed"
    }
}
