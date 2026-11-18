package com.sdm.downloadmanager.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.ui.graphics.Color
import com.sdm.core.common.FileCategory
import com.sdm.core.designsystem.component.CategoryBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small in-memory cache of decoded thumbnails, keyed by URI and size. */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val misses = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun cached(uri: String, px: Int): Bitmap? = cache.get("$uri@$px")

    suspend fun load(context: Context, uri: String, category: FileCategory, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri@$px"
        cache.get(key)?.let { return@withContext it }
        if (key in misses) return@withContext null
        val bmp = runCatching { decode(context, Uri.parse(uri), category, px) }.getOrNull()
        if (bmp != null) cache.put(key, bmp) else misses += key
        bmp
    }

    private fun decode(context: Context, u: Uri, category: FileCategory, px: Int): Bitmap? {
        if (u.scheme == "content" && Build.VERSION.SDK_INT >= 29) {
            runCatching { return context.contentResolver.loadThumbnail(u, Size(px, px), null) }
        }
        return when (category) {
            FileCategory.VIDEO -> MediaMetadataRetriever().run {
                try {
                    if (u.scheme == "file") setDataSource(u.path) else setDataSource(context, u)
                    getFrameAtTime(1_000_000)?.let { scale(it, px) }
                } finally { release() }
            }
            FileCategory.IMAGE -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }
            else -> null
        }
    }

    private fun scale(b: Bitmap, px: Int): Bitmap {
        val f = px.toFloat() / maxOf(b.width, b.height)
        return if (f >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }
}

/** Thumbnail for a finished image or video, falling back to the category badge. */
@Composable
fun MediaThumbnail(uri: String?, category: FileCategory, modifier: Modifier = Modifier, sizeDp: Int = 40) {
    val context = LocalContext.current
    val px = (sizeDp * context.resources.displayMetrics.density).toInt()
    val wants = uri != null && (category == FileCategory.VIDEO || category == FileCategory.IMAGE)
    val bitmap by produceState(if (wants) Thumbnails.cached(uri!!, px) else null, uri, category) {
        if (wants && value == null) value = Thumbnails.load(context, uri!!, category, px)
    }
    val b = bitmap
    if (b == null) { CategoryBadge(category, modifier, sizeDp); return }
    Box(modifier.size(sizeDp.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Image(b.asImageBitmap(), contentDescription = category.label, contentScale = ContentScale.Crop, modifier = Modifier.size(sizeDp.dp))
        if (category == FileCategory.VIDEO) Icon(Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size((sizeDp * 0.45).dp))
    }
}
