package com.sdm.core.data.files

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.sdm.core.common.FileCategory
import com.sdm.core.common.FileNames
import com.sdm.core.common.MimeTypes
import com.sdm.core.data.device.StorageInfo
import com.sdm.core.data.device.StorageStats
import com.sdm.core.data.storage.OutputStore
import com.sdm.core.database.FileIndexDao
import com.sdm.core.database.FileIndexEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class FileItem(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val category: FileCategory,
    val modifiedAt: Long,
    val folder: String,
    val downloadId: Long?,
    val sha256: String?,
    val sourceTreeUri: String?,
) {
    val extension: String get() = FileNames.extension(name)
}

data class DuplicateGroup(val sha256: String, val sizeBytes: Long, val files: List<FileItem>)

data class StorageAnalysis(val device: StorageStats, val byCategory: Map<FileCategory, Pair<Int, Long>>, val totalSdmBytes: Long, val incompleteBytes: Long)

data class GrantedFolder(val treeUri: String, val name: String)

/**
 * Built-in file manager over storage SDM may access under scoped storage (spec section 12,
 * ADR-0005): files SDM saved, plus folders the user granted through the system picker.
 */
@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FileIndexDao,
    private val outputs: OutputStore,
    private val storage: StorageInfo,
) {
    private val resolver get() = context.contentResolver

    val files: Flow<List<FileItem>> = dao.observeAll().map { l -> l.map { it.toItem() } }

    fun grantedFolders(): List<GrantedFolder> = resolver.persistedUriPermissions.filter { it.isWritePermission }.mapNotNull { p ->
        DocumentFile.fromTreeUri(context, p.uri)?.let { GrantedFolder(p.uri.toString(), it.name ?: "Folder") }
    }

    suspend fun addFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        refresh()
    }

    suspend fun removeFolder(treeUri: String) = withContext(Dispatchers.IO) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        dao.deleteFromSource(treeUri)
    }

    /** Re-scans SDM's MediaStore items, app storage and granted folders. */
    suspend fun refresh(): Int = withContext(Dispatchers.IO) {
        val found = mutableListOf<FileIndexEntity>()
        val existing = dao.all().associateBy { it.uri }
        if (Build.VERSION.SDK_INT >= 29) found += scanMediaStore(existing)
        found += scanPrivate(existing)
        for (folder in grantedFolders()) found += scanTree(folder, existing)
        val foundUris = found.map { it.uri }.toSet()
        val gone = existing.keys.filter { it !in foundUris && !outputs.exists(it) }
        if (gone.isNotEmpty()) dao.delete(gone)
        dao.upsert(found)
        found.size
    }

    private fun scanMediaStore(existing: Map<String, FileIndexEntity>): List<FileIndexEntity> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val out = mutableListOf<FileIndexEntity>()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.DATE_MODIFIED, MediaStore.Downloads.RELATIVE_PATH)
        resolver.query(collection, projection, "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 0", arrayOf("${Environment.DIRECTORY_DOWNLOADS}/SDM/%"), null)?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                val name = c.getString(1) ?: continue
                val mime = c.getString(3)
                val prev = existing[uri]
                out += FileIndexEntity(uri, name, c.getLong(2), mime, FileCategory.of(name, mime), c.getLong(4) * 1000, (c.getString(5) ?: "").trimEnd('/'), prev?.downloadId, prev?.sha256, prev?.sha256ComputedAt, null)
            }
        }
        return out
    }

    private fun scanPrivate(existing: Map<String, FileIndexEntity>): List<FileIndexEntity> {
        val root = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList(), "SDM")
        return root.walkTopDown().filter { it.isFile && !it.name.startsWith(".staging-") }.map { f ->
            val uri = Uri.fromFile(f).toString()
            val prev = existing[uri]
            FileIndexEntity(uri, f.name, f.length(), MimeTypes.forName(f.name), FileCategory.of(f.name), f.lastModified(), "App storage/" + f.parentFile!!.relativeTo(root.parentFile!!).path, prev?.downloadId, prev?.sha256, prev?.sha256ComputedAt, null)
        }.toList()
    }

    private fun scanTree(folder: GrantedFolder, existing: Map<String, FileIndexEntity>): List<FileIndexEntity> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri)) ?: return emptyList()
        val out = mutableListOf<FileIndexEntity>()
        fun walk(dir: DocumentFile, path: String, depth: Int) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) { if (depth < 3) walk(f, "$path/${f.name}", depth + 1); continue }
                val name = f.name ?: continue
                val uri = f.uri.toString()
                val prev = existing[uri]
                out += FileIndexEntity(uri, name, f.length(), f.type, FileCategory.of(name, f.type), f.lastModified(), path, prev?.downloadId, prev?.sha256, prev?.sha256ComputedAt, folder.treeUri)
            }
        }
        runCatching { walk(root, folder.name, 0) }
        return out
    }

    suspend fun delete(items: List<FileItem>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (i in items) if (outputs.delete(i.uri) || !outputs.exists(i.uri)) { dao.delete(listOf(i.uri)); n++ }
        n
    }

    suspend fun rename(item: FileItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        val renamed = outputs.rename(item.uri, newName) ?: return@withContext false
        dao.delete(listOf(item.uri))
        val name = FileNames.sanitize(newName)
        dao.upsert(listOf(FileIndexEntity(renamed, name, item.sizeBytes, item.mimeType, FileCategory.of(name, item.mimeType), System.currentTimeMillis(), item.folder, item.downloadId, item.sha256, null, item.sourceTreeUri)))
        true
    }

    /** Pattern tokens: {name} original base name, {n} running number, {ext} extension. */
    suspend fun batchRename(items: List<FileItem>, pattern: String): Int {
        var ok = 0
        items.forEachIndexed { i, item ->
            val ext = item.extension
            var name = pattern.replace("{name}", FileNames.baseName(item.name)).replace("{n}", (i + 1).toString()).replace("{ext}", ext)
            if (!pattern.contains("{ext}") && ext.isNotEmpty() && FileNames.extension(name).isEmpty()) name = "$name.$ext"
            if (rename(item, name)) ok++
        }
        return ok
    }

    /** Copies into a granted folder, then deletes the source once the copy is complete. */
    suspend fun moveTo(items: List<FileItem>, treeUri: String): Int = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: throw IOException("Folder is no longer accessible")
        var moved = 0
        for (i in items) {
            val target = tree.createFile(i.mimeType ?: MimeTypes.forName(i.name), i.name) ?: continue
            val copied = runCatching {
                resolver.openInputStream(Uri.parse(i.uri))!!.use { input -> resolver.openOutputStream(target.uri, "w")!!.use { input.copyTo(it, 256 * 1024) } }
                target.length() == i.sizeBytes || i.sizeBytes <= 0
            }.getOrDefault(false)
            if (!copied) { target.delete(); continue }
            outputs.delete(i.uri)
            dao.delete(listOf(i.uri))
            dao.upsert(listOf(FileIndexEntity(target.uri.toString(), target.name ?: i.name, i.sizeBytes, i.mimeType, i.category, System.currentTimeMillis(), tree.name ?: "Folder", i.downloadId, i.sha256, null, treeUri)))
            moved++
        }
        moved
    }

    suspend fun createFolder(treeUri: String, name: String): Boolean = withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.createDirectory(FileNames.sanitize(name)) != null
    }

    suspend fun checksum(item: FileItem, algorithm: String = "SHA-256"): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance(algorithm)
        resolver.openInputStream(Uri.parse(item.uri))?.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        } ?: throw IOException("Cannot read ${item.name}")
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (algorithm == "SHA-256") dao.setSha(item.uri, hex, System.currentTimeMillis())
        hex
    }

    /** Duplicates are files with identical SHA-256; size is only used to decide which files to hash. */
    suspend fun findDuplicates(all: List<FileItem>): List<DuplicateGroup> {
        val candidates = all.filter { it.sizeBytes > 0 }.groupBy { it.sizeBytes }.values.filter { it.size > 1 }.flatten()
        val hashed = candidates.map { f -> f to (f.sha256 ?: runCatching { checksum(f) }.getOrNull()) }
        return hashed.filter { it.second != null }.groupBy { it.second!! }.filter { it.value.size > 1 }
            .map { (sha, list) -> DuplicateGroup(sha, list.first().first.sizeBytes, list.map { it.first.copy(sha256 = sha) }) }
    }

    fun analysis(all: List<FileItem>, incompleteBytes: Long): StorageAnalysis = StorageAnalysis(
        storage.primary(),
        all.groupBy { it.category }.mapValues { (_, v) -> v.size to v.sumOf { it.sizeBytes } },
        all.sumOf { it.sizeBytes },
        incompleteBytes,
    )

    /** URI another app can open: content URIs as-is, app-private files through FileProvider. */
    fun shareableUri(uri: String): Uri {
        val u = Uri.parse(uri)
        return if (u.scheme == "file") FileProvider.getUriForFile(context, context.packageName + ".files", File(u.path!!)) else u
    }

    private fun FileIndexEntity.toItem() = FileItem(uri, name, sizeBytes, mimeType, category, modifiedAt, folder, downloadId, sha256, sourceTreeUri)
}
