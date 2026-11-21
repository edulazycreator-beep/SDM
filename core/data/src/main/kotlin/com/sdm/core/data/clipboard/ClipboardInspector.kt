package com.sdm.core.data.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import com.sdm.core.common.Urls
import com.sdm.core.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground-only clipboard check (spec section 9). Called when SDM gains window focus; reads the
 * clip description first (no system toast) and only reads text that is likely a URL. Stores just
 * a hash of the last suggestion so the same link is not suggested twice; never stores contents.
 */
@Singleton
class ClipboardInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    suspend fun checkForNewUrl(force: Boolean = false): String? {
        val s = settings.current()
        if (!s.clipboardDetection && !force) return null
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val desc = cm.primaryClipDescription ?: return null
        val texty = desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) || desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        if (!texty) return null
        if (!force && Build.VERSION.SDK_INT >= 31 && desc.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
            desc.getConfidenceScore(TextClassifier.TYPE_URL) < 0.5f) return null
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
        val url = Urls.extract(text).firstOrNull() ?: return null
        val hash = url.hashCode()
        if (!force && hash == s.lastClipboardHash) return null
        settings.update { it.copy(lastClipboardHash = hash) }
        return url
    }

    /** Every URL on the clipboard, for the user-triggered "paste" and batch import. */
    fun readAllUrls(): List<String> {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return emptyList()
        val clip = cm.primaryClip ?: return emptyList()
        return (0 until clip.itemCount).flatMap { Urls.extract(clip.getItemAt(it).coerceToText(context).toString()) }.distinct()
    }
}
