package com.sdm.downloadmanager.feature.details

import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.Formatters
import com.sdm.core.common.Redactor
import com.sdm.core.data.diagnostics.DiagnosticsRepository
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.files.FileRepository
import com.sdm.core.designsystem.component.AnimatedProgress
import com.sdm.core.designsystem.component.CategoryBadge
import com.sdm.core.designsystem.component.ConfirmDialog
import com.sdm.core.designsystem.component.InfoRow
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.component.SegmentBar
import com.sdm.core.designsystem.component.StatusChip
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.core.designsystem.theme.LocalStatusColors
import com.sdm.domain.CONNECTION_PROFILES
import com.sdm.domain.Download
import com.sdm.domain.DownloadEventRecord
import com.sdm.domain.DownloadState
import com.sdm.domain.LiveProgress
import com.sdm.domain.Priority
import com.sdm.downloadmanager.feature.downloads.holdLabel
import com.sdm.downloadmanager.ui.SdmScaffold
import com.sdm.downloadmanager.ui.copyToClipboard
import com.sdm.downloadmanager.ui.openFile
import com.sdm.downloadmanager.ui.shareFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val files: FileRepository,
    private val diagnostics: DiagnosticsRepository,
    private val archives: com.sdm.core.data.archive.ArchiveExtractor,
) : ViewModel() {
    val extracting = MutableStateFlow(false)

    fun extract(d: com.sdm.domain.Download) {
        val uri = d.destinationUri ?: return
        if (extracting.value) return
        extracting.value = true
        viewModelScope.launch {
            val r = runCatching { archives.extractZip(uri, d.fileName, d.destinationType, null) }
            extracting.value = false
            _message.value = r.fold(
                { res -> "Extracted ${res.files} file(s) into folder \"${res.folder}\"" + if (res.skipped > 0) " · ${res.skipped} skipped" else "" },
                { e -> e.message ?: "Could not extract" },
            )
        }
    }

    private val id = MutableStateFlow(-1L)
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val download: StateFlow<Download?> = id.flatMapLatest { repo.observe(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val live: StateFlow<LiveProgress?> = combine(id, repo.live) { i, l -> l[i] }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<DownloadEventRecord>> = id.flatMapLatest { repo.events(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun load(downloadId: Long) { id.value = downloadId }
    fun pause() = act { repo.pause(it) }
    fun resume() = act { repo.resume(it) }
    fun cancel() = act { repo.cancel(it) }
    fun restart() = act { repo.restart(it) }
    fun delete(files: Boolean) = act { repo.delete(listOf(it), files) }
    fun priority(p: Priority) = act { repo.setPriority(it, p) }
    fun connections(n: Int) = act { repo.setConnections(it, n) }
    fun speedLimit(kb: Long) = act { repo.setSpeedLimit(it, kb * 1024) }
    fun editUrl(url: String) = act { _message.value = if (repo.editUrl(it, url)) "Link updated; resume to continue" else "Pause the download first, and use a valid link" }
    fun rename(name: String) = act { _message.value = if (repo.rename(it, name)) "Renamed" else "Could not rename (pause first)" }
    fun changeFolder(uri: Uri) = act {
        val f = files.grant(uri)
        _message.value = if (repo.changeDestination(it, f.treeUri, f.name, null)) "Destination changed" else "Destination can only change before the download starts"
    }
    fun checksum(algorithm: String, expected: String) = act { repo.setChecksum(it, algorithm, expected) }
    fun verifyFile(d: Download) = viewModelScope.launch {
        val uri = d.destinationUri ?: return@launch
        val algorithm = d.checksumAlgorithm?.let { a -> when (a.uppercase().replace("-", "")) { "MD5" -> "MD5"; "SHA1" -> "SHA-1"; "SHA512" -> "SHA-512"; else -> "SHA-256" } } ?: "SHA-256"
        val item = com.sdm.core.data.files.FileItem(uri, d.fileName, d.totalBytes ?: 0, d.mimeType, d.category, 0, "", d.id, null, null)
        _message.value = runCatching { files.checksum(item, algorithm) }.fold(
            onSuccess = { hex ->
                val exp = d.checksumExpected
                if (exp == null) "$algorithm: $hex" else if (exp.equals(hex, true)) "Checksum matches ($algorithm)" else "Checksum does NOT match: $hex"
            },
            onFailure = { "Could not read the file: ${it.message}" },
        )
    }
    fun report(d: Download, ev: List<DownloadEventRecord>) = diagnostics.report(d, ev)
    fun shareUri(uri: String) = files.shareableUri(uri)
    fun consumeMessage() { _message.value = null }

    private fun act(block: suspend (Long) -> Unit) = viewModelScope.launch { id.value.takeIf { it > 0 }?.let { block(it) } }
}

private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(id: Long, vm: DetailsViewModel = hiltViewModel()) {
    remember(id) { vm.load(id); true }
    val d by vm.download.collectAsState()
    val live by vm.live.collectAsState()
    val events by vm.events.collectAsState()
    val message by vm.message.collectAsState()
    val app = com.sdm.downloadmanager.ui.LocalAppActions.current
    val ctx = LocalContext.current
    val sp = LocalSpacing.current
    var editDialog by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::changeFolder) }
    androidx.compose.runtime.LaunchedEffect(message) { message?.let { app.snackbar.showSnackbar(it); vm.consumeMessage() } }

    var overflow by remember { mutableStateOf(false) }
    SdmScaffold(title = "Download details", onBack = { app.nav.popBackStack() }, actions = {
        val cur = d
        if (cur != null) Box {
            IconButton(onClick = { overflow = true }) { Icon(Icons.Outlined.MoreVert, "More") }
            DropdownMenu(overflow, { overflow = false }) {
                if (!cur.state.isTerminal) DropdownMenuItem(text = { Text("Cancel") }, leadingIcon = { Icon(Icons.Outlined.Close, null) }, onClick = { overflow = false; vm.cancel() })
                DropdownMenuItem(text = { Text(if (cur.state == DownloadState.COMPLETED) "Download again" else "Restart") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { overflow = false; vm.restart() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { overflow = false; confirmDelete = true })
            }
        }
    }) { padding ->
        val dl = d ?: return@SdmScaffold
        val done = live?.downloadedBytes ?: dl.downloadedBytes
        val total = live?.totalBytes ?: dl.totalBytes
        LazyColumn(Modifier.padding(padding).padding(horizontal = sp.l)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(dl.category, size = 52)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dl.fileName, style = MaterialTheme.typography.titleMedium)
                        StatusChip(dl.state, labelOverride = holdLabel(dl))
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (dl.state != DownloadState.COMPLETED) AnimatedProgress(if (total != null && total > 0) done.toFloat() / total else if (dl.state.isActive) null else 0f)
                Spacer(Modifier.height(6.dp))
                Text("${Formatters.bytes(done)} of ${Formatters.bytes(total)} · ${Formatters.percent(done, total)}%", style = MaterialTheme.typography.bodyMedium)
                live?.let { l ->
                    Text("Speed ${Formatters.speed(l.speedBps)} · ${l.activeConnections} connection(s)" + (l.etaSeconds?.let { " · ${Formatters.duration(it)} left (estimate)" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (total != null && l.segments.size > 1) { Spacer(Modifier.height(6.dp)); SegmentBar(total, l.segments.map { Triple(it.start, it.end, it.position) }) }
                }
                dl.errorCode?.let { code ->
                    Spacer(Modifier.height(8.dp))
                    Text("${code.title}: ${code.description}", color = LocalStatusColors.current.error, style = MaterialTheme.typography.bodySmall)
                    Text("Suggestion: ${code.suggestion}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        dl.state.isActive || dl.state == DownloadState.QUEUED || dl.state == DownloadState.WAITING_FOR_NETWORK -> Button(onClick = vm::pause) { Text("Pause") }
                        dl.state == DownloadState.PAUSED -> Button(onClick = vm::resume) { Text("Resume") }
                        dl.state == DownloadState.FAILED -> Button(onClick = vm::resume) { Text("Retry") }
                        dl.state == DownloadState.COMPLETED -> dl.destinationUri?.let { uri ->
                            Button(onClick = { ctx.openFile(vm.shareUri(uri), dl.mimeType, dl.fileName) }) {
                                Text(if (com.sdm.downloadmanager.feature.player.PlayerActivity.canPlay(dl.mimeType)) "Play" else "Open")
                            }
                            if (com.sdm.downloadmanager.feature.player.PlayerActivity.canPlay(dl.mimeType)) OutlinedButton(onClick = { ctx.openFile(vm.shareUri(uri), dl.mimeType, chooser = true) }) { Text("Open with…") }
                            OutlinedButton(onClick = { ctx.shareFiles(listOf(vm.shareUri(uri)), listOf(dl.mimeType)) }) { Text("Share") }
                            if (com.sdm.core.data.archive.ArchiveExtractor.isZip(dl.fileName, dl.mimeType)) {
                                val busy by vm.extracting.collectAsState()
                                OutlinedButton(onClick = { vm.extract(dl) }, enabled = !busy) { Text(if (busy) "Extracting…" else "Extract here") }
                            }
                        }
                        else -> Unit
                    }
                }
                SectionHeader("File")
                InfoRow("File name", dl.fileName, onClick = { editDialog = "rename" })
                InfoRow("Type", "${dl.mimeType ?: "Unknown"} · ${dl.category.label}")
                InfoRow("Size", Formatters.bytes(dl.totalBytes))
                InfoRow("Destination", dl.destinationLabel)
                if (dl.destinationUri == null && dl.downloadedBytes == 0L) TextButton(onClick = { folderPicker.launch(null) }) { Text("Change destination folder") }
                SectionHeader("Source")
                InfoRow("Source URL", Redactor.url(dl.originalUrl), onClick = { editDialog = "url" })
                dl.resolvedUrl?.takeIf { it != dl.originalUrl }?.let { InfoRow("Resolved", Redactor.url(it)) }
                dl.sourcePageUrl?.let { InfoRow("Page", Redactor.url(it)) }
                InfoRow("Resume support", when (dl.rangeSupport) { com.sdm.domain.RangeSupport.SUPPORTED -> "Supported (ranges)"; com.sdm.domain.RangeSupport.UNSUPPORTED -> "Not supported"; else -> "Not checked yet" })
                SectionHeader("Transfer")
                InfoRow("Average speed", Formatters.speed(dl.averageSpeedBps))
                InfoRow("Active time", Formatters.duration(dl.activeMillis / 1000))
                InfoRow("Retries", dl.retryCount.toString())
                InfoRow("Created", timeFmt.format(Instant.ofEpochMilli(dl.createdAt)))
                dl.completedAt?.let { InfoRow("Completed", timeFmt.format(Instant.ofEpochMilli(it))) }
                dl.scheduledAt?.let { InfoRow("Scheduled", timeFmt.format(Instant.ofEpochMilli(it))) }
                Text("Connections", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { CONNECTION_PROFILES.forEach { n -> FilterChip(dl.maxConnections == n, { vm.connections(n) }, { Text("$n") }) } }
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Priority.entries.forEach { p -> FilterChip(dl.priority == p, { vm.priority(p) }, { Text(p.name.lowercase()) }) } }
                InfoRow("Speed limit", if (dl.speedLimitBps > 0) Formatters.speed(dl.speedLimitBps) else "Unlimited (tap to change)", onClick = { editDialog = "limit" })
                SectionHeader("Integrity")
                InfoRow("Checksum", dl.checksumExpected?.let { "${dl.checksumAlgorithm}: $it" } ?: "Not set (tap to add)", onClick = { editDialog = "checksum" })
                dl.checksumActual?.let { InfoRow("Verified", it) }
                if (dl.state == DownloadState.COMPLETED) TextButton(onClick = { vm.verifyFile(dl) }) { Text("Compute checksum of the file") }
                SectionHeader("Diagnostics", action = {
                    IconButton(onClick = { ctx.copyToClipboard("SDM diagnostics", vm.report(dl, events)) }) { Icon(Icons.Outlined.ContentCopy, "Copy diagnostic report") }
                })
            }
            items(events, key = { it.id }) { e ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${timeFmt.format(Instant.ofEpochMilli(e.timestamp))} · ${e.type.name.lowercase().replace('_', ' ')}" + (e.httpStatus?.let { " · HTTP $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }

        when (editDialog) {
            "rename" -> TextEditDialog("Rename", dl.fileName, { vm.rename(it) }) { editDialog = null }
            "url" -> TextEditDialog("Edit link (refresh)", dl.originalUrl, { vm.editUrl(it) }, "Use a fresh link to the same file; SDM keeps the downloaded part only if size and validators match.") { editDialog = null }
            "limit" -> TextEditDialog("Speed limit (KB/s, 0 = unlimited)", (dl.speedLimitBps / 1024).toString(), { vm.speedLimit(it.filter(Char::isDigit).toLongOrNull() ?: 0) }) { editDialog = null }
            "checksum" -> {
                var algo by remember { mutableStateOf(dl.checksumAlgorithm ?: "SHA-256") }
                var value by remember { mutableStateOf(dl.checksumExpected ?: "") }
                AlertDialog(
                    onDismissRequest = { editDialog = null },
                    title = { Text("Expected checksum") },
                    text = {
                        Column {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("MD5", "SHA-1", "SHA-256", "SHA-512").forEach { a -> FilterChip(algo == a, { algo = a }, { Text(a) }) } }
                            OutlinedTextField(value, { value = it.trim() }, Modifier.fillMaxWidth(), singleLine = true)
                        }
                    },
                    confirmButton = { TextButton(onClick = { vm.checksum(algo, value); editDialog = null }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { editDialog = null }) { Text("Cancel") } },
                )
            }
        }
        if (confirmDelete) {
            var withFile by remember { mutableStateOf(false) }
            ConfirmDialog("Delete download?", "Removes it from SDM.", "Delete", onConfirm = { vm.delete(withFile); app.nav.popBackStack() }, onDismiss = { confirmDelete = false }, destructive = true, extra = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(withFile, { withFile = it })
                    Text("Also delete the file")
                }
            })
        }
    }
}

@Composable
fun TextEditDialog(title: String, initial: String, onSave: (String) -> Unit, hint: String? = null, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { hint?.let { Text(it, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)) }; OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth()) } },
        confirmButton = { TextButton(onClick = { onSave(text); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
