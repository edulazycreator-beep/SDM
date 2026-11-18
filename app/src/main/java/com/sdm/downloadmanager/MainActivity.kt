package com.sdm.downloadmanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.sdm.core.common.Urls
import com.sdm.core.data.notifications.Notifier
import com.sdm.downloadmanager.ui.AddRequest
import com.sdm.downloadmanager.ui.AppViewModel
import com.sdm.downloadmanager.ui.SdmApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    /** Set by the Quick Settings tile; the clipboard can only be read once this window has focus. */
    private var pendingPaste = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { appViewModel.settings.value == null }
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { SdmApp(appViewModel) }
    }

    @Deprecated("Only used for the Android 8–9 screen-lock confirmation")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        com.sdm.downloadmanager.ui.DeviceLock.onActivityResult(requestCode, resultCode)
    }

    /** The opening app's permission to read a .torrent ends soon, so SDM keeps its own copy. */
    private fun importTorrentFile(uri: android.net.Uri) {
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null || bytes.size > (32 shl 20)) { android.widget.Toast.makeText(this, "Could not read the torrent file", android.widget.Toast.LENGTH_SHORT).show(); return }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.let { com.sdm.core.common.FileNames.sanitize(it) } ?: "download.torrent"
        val dir = java.io.File(filesDir, "torrent-imports").apply { mkdirs() }
        val file = java.io.File(dir, "${System.currentTimeMillis()}-" + if (name.endsWith(".torrent", true)) name else "$name.torrent")
        file.writeBytes(bytes)
        appViewModel.requestAdd(AddRequest(listOf(android.net.Uri.fromFile(file).toString()), mime = "application/x-bittorrent"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused foreground app read the clipboard (spec section 9).
        if (hasFocus) {
            appViewModel.onWindowFocused()
            if (pendingPaste) {
                pendingPaste = false
                appViewModel.pasteFromClipboard { android.widget.Toast.makeText(this, "No link in the clipboard", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val downloadId = intent.getLongExtra(Notifier.EXTRA_DOWNLOAD_ID, -1)
        if (downloadId > 0) appViewModel.openDownload(downloadId)
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val urls = Urls.extract(text)
                if (urls.isNotEmpty()) appViewModel.requestAdd(AddRequest(urls))
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data != null && data.scheme == "content" && (intent.type == "application/x-bittorrent" || data.path?.endsWith(".torrent", true) == true)) importTorrentFile(data)
                else intent.dataString?.takeIf { Urls.isValidDownloadUrl(it) }?.let { appViewModel.requestAdd(AddRequest(listOf(it), mime = intent.type)) }
            }
            ACTION_PASTE_LINK -> pendingPaste = true
        }
    }
}

const val ACTION_PASTE_LINK = "com.sdm.downloadmanager.action.PASTE_LINK"
