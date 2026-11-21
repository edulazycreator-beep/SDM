package com.sdm.core.data.files

import android.content.Context
import android.net.Uri
import com.sdm.core.common.FileCategory
import com.sdm.core.common.FileNames
import com.sdm.core.data.storage.OutputStore
import com.sdm.core.database.SdmDatabase
import com.sdm.domain.DestinationType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files kept in SDM's internal storage: other apps, the gallery and file managers can't see them,
 * and they are excluded from backups. The screen that shows them is behind the device lock.
 * Moving a file in or out always happens on an explicit user action.
 */
@Singleton
class PrivateVault @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outputs: OutputStore,
    private val db: SdmDatabase,
) {
    val dir: File get() = File(context.filesDir, "private").apply { mkdirs() }

    fun list(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Copies [uri] in, then removes the original (the user chose "Move"). */
    suspend fun moveIn(uri: String, name: String): File = withContext(Dispatchers.IO) {
        val target = unique(FileNames.sanitize(name))
        try {
            open(uri).use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
        } catch (e: Exception) { target.delete(); throw e }
        if (!outputs.delete(uri)) throw IOException("Copied into the private folder, but the original could not be removed")
        db.files().delete(listOf(uri))
        target
    }

    /** Copies a private file back to Downloads/SDM and removes it from the private folder. */
    suspend fun moveOut(file: File): String = withContext(Dispatchers.IO) {
        val category = FileCategory.of(file.name)
        val (uri, _) = outputs.writeNew(file.name, null, category, DestinationType.MEDIASTORE, null, null) { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
        file.delete()
        uri
    }

    fun delete(file: File): Boolean = file.canonicalPath.startsWith(dir.canonicalPath) && file.delete()

    private fun unique(name: String): File {
        var f = File(dir, name)
        var n = 1
        while (f.exists()) f = File(dir, FileNames.numbered(name, n++))
        if (!f.canonicalPath.startsWith(dir.canonicalPath)) throw IOException("Invalid file name")
        return f
    }

    private fun open(uri: String) = Uri.parse(uri).let { u ->
        if (u.scheme == "file") File(u.path!!).inputStream() else context.contentResolver.openInputStream(u) ?: throw IOException("Cannot open the file")
    }
}
