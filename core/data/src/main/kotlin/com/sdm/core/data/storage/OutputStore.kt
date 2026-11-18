package com.sdm.core.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import com.sdm.core.common.FileCategory
import com.sdm.core.common.FileNames
import com.sdm.domain.DestinationType
import com.sdm.engine.storage.FileSink
import com.sdm.engine.storage.RandomAccessSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Positional I/O on a file descriptor (MediaStore items and SAF documents). */
class FdSink(private val pfd: ParcelFileDescriptor) : RandomAccessSink {
    private val fd = pfd.fileDescriptor

    override fun write(position: Long, src: ByteArray, offset: Int, length: Int) = io {
        var done = 0
        while (done < length) done += Os.pwrite(fd, src, offset + done, length - done, position + done)
    }

    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int = io { Os.pread(fd, dst, offset, length, position) }
    override fun sync() = io { Os.fsync(fd) }
    override fun setLength(size: Long) = io { Os.ftruncate(fd, size) }
    override fun size(): Long = io { Os.fstat(fd).st_size }
    override fun close() = pfd.close()

    /** True when the descriptor supports seeking, i.e. segments can be written at any offset. */
    fun isSeekable(): Boolean = try { Os.lseek(fd, 0, OsConstants.SEEK_CUR); true } catch (_: ErrnoException) { false }

    private inline fun <T> io(block: () -> T): T = try { block() } catch (e: ErrnoException) { throw IOException(e.message, e) }
}

data class OutputHandle(val uri: String, val displayName: String, val label: String)

/**
 * Creates and opens download outputs (spec section 12, scoped storage):
 *  - MEDIASTORE: Download/SDM/<Category>/ via MediaStore, hidden from other apps until complete (Android 10+).
 *  - APP_PRIVATE: app-specific external storage (Android 8-9, no permission needed).
 *  - SAF_TREE: a folder the user granted; falls back to a private temp file + copy when the provider is not seekable.
 */
@Singleton
class OutputStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver get() = context.contentResolver

    fun defaultType(): DestinationType = if (Build.VERSION.SDK_INT >= 29) DestinationType.MEDIASTORE else DestinationType.APP_PRIVATE

    suspend fun create(name: String, mime: String?, category: FileCategory, type: DestinationType, treeUri: String?): OutputHandle = withContext(Dispatchers.IO) {
        val safeName = FileNames.sanitize(name)
        val mimeType = mime?.substringBefore(';')?.trim()?.ifBlank { null } ?: com.sdm.core.common.MimeTypes.forName(safeName)
        when (type) {
            DestinationType.MEDIASTORE -> if (Build.VERSION.SDK_INT >= 29) createMediaStore(safeName, mimeType, category) else createPrivate(safeName, category)
            DestinationType.APP_PRIVATE -> createPrivate(safeName, category)
            DestinationType.SAF_TREE -> createSaf(safeName, mimeType, treeUri ?: throw IOException("No folder selected"))
        }
    }

    private fun createMediaStore(name: String, mime: String, category: FileCategory): OutputHandle {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SDM/${category.folder}")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused to create $name")
        val display = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
        return OutputHandle(uri.toString(), display, "Download/SDM/${category.folder}")
    }

    private fun privateDir(category: FileCategory): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads"), "SDM/${category.folder}").apply { mkdirs() }

    private fun createPrivate(name: String, category: FileCategory): OutputHandle {
        val dir = privateDir(category)
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, FileNames.numbered(name, n++))
        if (!file.createNewFile()) throw IOException("Cannot create ${file.name}")
        // Canonical-path check: the file must stay inside the download directory (path traversal guard).
        if (!file.canonicalPath.startsWith(dir.canonicalPath)) { file.delete(); throw IOException("Invalid file name") }
        return OutputHandle(Uri.fromFile(file).toString(), file.name, "App storage/SDM/${category.folder}")
    }

    private fun createSaf(name: String, mime: String, treeUri: String): OutputHandle {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: throw IOException("Folder is no longer accessible")
        if (!tree.canWrite()) throw IOException("SDM cannot write to ${tree.name}")
        val doc = tree.createFile(mime, name) ?: throw IOException("Could not create $name in ${tree.name}")
        val seekable = runCatching {
            resolver.openFileDescriptor(doc.uri, "rw")?.use { pfd ->
                try { Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR); true } catch (_: ErrnoException) { false }
            } ?: false
        }.getOrDefault(false)
        if (seekable) return OutputHandle(doc.uri.toString(), doc.name ?: name, tree.name ?: "Selected folder")
        // Provider cannot seek (cloud folders): stage privately, copy into the folder when complete (risk R9).
        doc.delete()
        val staged = createPrivate(".staging-$name", FileCategory.OTHER)
        return staged.copy(displayName = name, label = (tree.name ?: "Selected folder") + " (copied when complete)")
    }

    fun open(uri: String): RandomAccessSink {
        val u = Uri.parse(uri)
        if (u.scheme == "file") {
            val f = File(u.path ?: throw FileNotFoundException(uri))
            if (!f.exists()) throw FileNotFoundException(uri)
            return FileSink(f)
        }
        val pfd = resolver.openFileDescriptor(u, "rw") ?: throw FileNotFoundException(uri)
        return FdSink(pfd)
    }

    /** Read-write descriptor for writers that need one (MediaMuxer). */
    fun openDescriptor(uri: String): ParcelFileDescriptor {
        val u = Uri.parse(uri)
        if (u.scheme == "file") return ParcelFileDescriptor.open(File(u.path!!), ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
        return resolver.openFileDescriptor(u, "rw") ?: throw FileNotFoundException(uri)
    }

    /** Copies a finished private file into an output that was created for it. */
    suspend fun copyInto(source: File, uri: String) = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        val out = if (u.scheme == "file") java.io.FileOutputStream(File(u.path!!)) else resolver.openOutputStream(u, "w") ?: throw IOException("Cannot write $uri")
        out.use { o -> source.inputStream().use { it.copyTo(o, 256 * 1024) } }
    }

    /** Makes a completed download visible and returns its final URI. */
    suspend fun finalize(uri: String, type: DestinationType, treeUri: String?, displayName: String, mime: String?): String = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        when {
            type == DestinationType.SAF_TREE && u.scheme == "file" && treeUri != null -> {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: throw IOException("Folder is no longer accessible")
                val doc = tree.createFile(mime ?: com.sdm.core.common.MimeTypes.forName(displayName), displayName) ?: throw IOException("Could not create $displayName")
                val src = File(u.path!!)
                resolver.openOutputStream(doc.uri, "w")?.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } } ?: throw IOException("Cannot write $displayName")
                src.delete()
                doc.uri.toString()
            }
            u.scheme == "content" && u.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= 29 -> {
                resolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                uri
            }
            else -> uri
        }
    }

    suspend fun delete(uri: String): Boolean = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        runCatching {
            when (u.scheme) {
                "file" -> File(u.path!!).delete()
                else -> if (DocumentsContract.isDocumentUri(context, u)) DocumentsContract.deleteDocument(resolver, u) else resolver.delete(u, null, null) > 0
            }
        }.getOrDefault(false)
    }

    suspend fun rename(uri: String, newName: String): String? = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        val name = FileNames.sanitize(newName)
        runCatching {
            when {
                u.scheme == "file" -> {
                    val f = File(u.path!!); val target = File(f.parentFile, name)
                    if (target.exists() || !f.renameTo(target)) null else Uri.fromFile(target).toString()
                }
                DocumentsContract.isDocumentUri(context, u) -> DocumentsContract.renameDocument(resolver, u, name)?.toString()
                else -> if (resolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0) uri else null
            }
        }.getOrNull()
    }

    fun exists(uri: String): Boolean {
        val u = Uri.parse(uri)
        return if (u.scheme == "file") File(u.path ?: return false).exists()
        else runCatching { resolver.openFileDescriptor(u, "r")?.use { true } ?: false }.getOrDefault(false)
    }
}
