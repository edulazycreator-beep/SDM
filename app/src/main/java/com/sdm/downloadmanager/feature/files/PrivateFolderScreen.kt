package com.sdm.downloadmanager.feature.files

import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.FileCategory
import com.sdm.core.common.Formatters
import com.sdm.core.common.MimeTypes
import com.sdm.core.data.files.PrivateVault
import com.sdm.core.designsystem.component.EmptyState
import com.sdm.core.designsystem.component.ConfirmDialog
import com.sdm.downloadmanager.ui.DeviceLock
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.MediaThumbnail
import com.sdm.downloadmanager.ui.SdmScaffold
import com.sdm.downloadmanager.ui.openFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PrivateFolderViewModel @Inject constructor(private val vault: PrivateVault) : ViewModel() {
    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files = _files.asStateFlow()
    val unlocked = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun reload() { _files.value = vault.list() }
    fun delete(f: File) { vault.delete(f); reload() }
    fun moveOut(f: File) = viewModelScope.launch {
        message.value = runCatching { vault.moveOut(f); "Moved ${f.name} to Downloads/SDM" }.getOrElse { it.message ?: "Could not move the file" }
        reload()
    }
}

@Composable
fun PrivateFolderScreen(vm: PrivateFolderViewModel = hiltViewModel()) {
    val app = LocalAppActions.current
    val activity = LocalActivity.current
    val ctx = LocalContext.current
    val unlocked by vm.unlocked.collectAsState()
    val files by vm.files.collectAsState()
    val message by vm.message.collectAsState()
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    // Keeps private files out of screenshots and the recent-apps preview.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun unlock() { activity?.let { a -> DeviceLock.authenticate(a, "Open the private folder") { ok -> vm.unlocked.value = ok; if (ok) vm.reload() } } }
    LaunchedEffect(Unit) { if (!vm.unlocked.value && DeviceLock.available(ctx)) unlock() }
    LaunchedEffect(message) { message?.let { app.snackbar.showSnackbar(it); vm.message.value = null } }
    SdmScaffold(title = "Private folder", onBack = { app.nav.popBackStack() }) { p ->
        when {
            !DeviceLock.available(ctx) -> EmptyState(Icons.Filled.Lock, "Set a screen lock first", "The private folder opens with your fingerprint, face or screen lock. Add a PIN, pattern or password in the system settings.", Modifier.padding(p)) {
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open security settings") }
            }
            !unlocked -> EmptyState(Icons.Filled.Lock, "Locked", "Files here are only visible in SDM and are not backed up.", Modifier.padding(p)) {
                Button(onClick = { unlock() }) { Text("Unlock") }
            }
            files.isEmpty() -> EmptyState(Icons.Filled.Lock, "Nothing here yet", "In Files, open a file's menu and choose \"Move to private folder\".", Modifier.padding(p))
            else -> LazyColumn(Modifier.fillMaxSize().padding(p)) {
                items(files, key = { it.path }) { f ->
                    val mime = MimeTypes.forName(f.name)
                    val category = FileCategory.of(f.name, mime)
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clickable { ctx.openFile(uri, mime, f.name) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MediaThumbnail(android.net.Uri.fromFile(f).toString(), category)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text("${Formatters.bytes(f.length())} · ${Formatters.relativeDate(f.lastModified())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for ${f.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; ctx.openFile(uri, mime, chooser = true) })
                                DropdownMenuItem(text = { Text("Move back to Downloads") }, onClick = { menu = false; vm.moveOut(f) })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = f })
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    confirmDelete?.let { f ->
        ConfirmDialog(title = "Delete ${f.name}?", message = "The file is removed permanently.", confirm = "Delete", destructive = true,
            onConfirm = { vm.delete(f) }, onDismiss = { confirmDelete = null })
    }
}
