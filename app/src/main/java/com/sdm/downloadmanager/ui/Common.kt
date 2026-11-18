package com.sdm.downloadmanager.ui

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.sdm.core.common.MimeTypes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdmScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = actions,
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = { Box(Modifier.padding(bottom = snackbarLift())) { floatingActionButton() } },
        bottomBar = bottomBar,
        content = content,
    )
}

/** Space to move floating buttons up while the app snackbar is showing, so it never covers them. */
@Composable
fun snackbarLift(): androidx.compose.ui.unit.Dp {
    val showing = LocalAppActions.current.snackbar.currentSnackbarData != null
    return androidx.compose.animation.core.animateDpAsState(if (showing) 64.dp else 0.dp, label = "snackbarLift").value
}

fun Context.copyToClipboard(label: String, text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, com.sdm.core.common.I18n.tr("Copied"), Toast.LENGTH_SHORT).show()
}

/** Opens a file: video and audio play in SDM's own player unless [chooser] is set. */
fun Context.openFile(uri: Uri, mime: String?, title: String? = null, chooser: Boolean = false) {
    if (!chooser && com.sdm.downloadmanager.feature.player.PlayerActivity.canPlay(mime)) {
        runCatching { com.sdm.downloadmanager.feature.player.PlayerActivity.start(this, uri, title, mime); return }
    }
    openWith(uri, mime)
}

fun Context.openWith(uri: Uri, mime: String?) {
    val type = mime ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { startActivity(Intent.createChooser(intent, com.sdm.core.common.I18n.tr("Open with")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { Toast.makeText(this, com.sdm.core.common.I18n.tr("No app can open this file"), Toast.LENGTH_SHORT).show() }
}

fun Context.shareFiles(uris: List<Uri>, mimes: List<String?>) {
    if (uris.isEmpty()) return
    val mime = mimes.filterNotNull().distinct().singleOrNull() ?: "*/*"
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris.first())
    else Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.shareText(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.openInBrowser(url: String) {
    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { Toast.makeText(this, com.sdm.core.common.I18n.tr("No browser installed"), Toast.LENGTH_SHORT).show() }
}

fun mimeFor(name: String, mime: String?) = mime ?: MimeTypes.forName(name)
