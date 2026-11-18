package com.sdm.downloadmanager.feature.add

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.FileCategory
import com.sdm.core.common.FileNames
import com.sdm.core.common.Formatters
import com.sdm.core.common.Urls
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.download.LinkInfo
import com.sdm.core.data.download.NewDownload
import com.sdm.core.data.files.FileRepository
import com.sdm.core.data.files.GrantedFolder
import com.sdm.core.data.io.ImportExportRepository
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.designsystem.component.CategoryBadge
import com.sdm.core.designsystem.component.InfoRow
import com.sdm.core.designsystem.theme.LocalStatusColors
import com.sdm.domain.CONNECTION_PROFILES
import com.sdm.domain.DuplicateKind
import com.sdm.domain.DuplicateMatch
import com.sdm.domain.DuplicateResolution
import com.sdm.domain.NetworkPolicy
import com.sdm.domain.Priority
import com.sdm.domain.intelligence.Recommendation
import com.sdm.domain.rules.EnqueueDecision
import com.sdm.downloadmanager.ui.AddRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

sealed interface AddStep {
    data class Input(val text: String = "", val error: String? = null) : AddStep
    data object Inspecting : AddStep
    data class Confirm(
        val info: LinkInfo,
        val fileName: String,
        val decision: EnqueueDecision,
        val duplicate: DuplicateMatch?,
        val recommendation: Recommendation?,
        val connections: Int,
        val policy: NetworkPolicy,
        val priority: Priority,
        val category: FileCategory,
        val folder: GrantedFolder?,
        val startAt: Long?,
        val startPaused: Boolean,
        val speedLimitKb: String,
        val checksumAlgorithm: String,
        val checksum: String,
        val stream: com.sdm.engine.hls.StreamInfo? = null,
        val streamOption: com.sdm.engine.hls.StreamOption? = null,
    ) : AddStep
    data class Batch(val urls: List<String>, val startPaused: Boolean = false) : AddStep
    data class Done(val message: String) : AddStep
}

@HiltViewModel
class AddDownloadViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val settings: SettingsRepository,
    private val files: FileRepository,
    private val io: ImportExportRepository,
) : ViewModel() {
    private val _step = MutableStateFlow<AddStep>(AddStep.Input())
    val step = _step.asStateFlow()
    private var request: AddRequest? = null

    fun start(r: AddRequest) {
        if (request === r) return
        request = r
        when {
            r.urls.size > 1 -> _step.value = AddStep.Batch(r.urls)
            r.urls.size == 1 -> inspect(r.urls.first())
            else -> _step.value = AddStep.Input()
        }
    }

    fun edit(text: String) { _step.value = AddStep.Input(text) }

    fun submitInput(text: String) {
        val urls = Urls.extract(text)
        when {
            urls.isEmpty() -> _step.value = AddStep.Input(text, "Enter a valid http:// or https:// link")
            urls.size > 1 -> _step.value = AddStep.Batch(urls)
            else -> inspect(urls.first())
        }
    }

    private fun inspect(url: String) = viewModelScope.launch {
        _step.value = AddStep.Inspecting
        val r = request
        if (r?.streamHint == true || com.sdm.engine.hls.HlsParser.streamKindOfUrl(url) != null || com.sdm.engine.hls.HlsParser.isHlsContentType(r?.mime)) {
            inspectStream(url); return@launch
        }
        val info = repo.inspect(url, r?.headers.orEmpty(), r?.contentDisposition, r?.mime).let { i ->
            val hinted = r?.fileNameHint?.let { FileNames.sanitize(it) }
            if (i.sizeBytes == null && r?.sizeHint != null && r.sizeHint > 0) i.copy(sizeBytes = r.sizeHint, fileName = hinted ?: i.fileName) else if (hinted != null) i.copy(fileName = hinted) else i
        }
        if (com.sdm.engine.hls.HlsParser.isHlsContentType(info.mimeType)) { inspectStream(url); return@launch }
        val s = settings.current()
        val decision = repo.applyRules(info)
        val dup = repo.findDuplicate(info.url, info.fileName, info.sizeBytes)
        val rec = runCatching { repo.recommendation(info.finalUrl, s.defaultConnections) }.getOrNull()
        val connections = decision.connections ?: if (s.autoConnections && rec != null) rec.connections else s.defaultConnections
        val folder = decision.treeUri?.let { t -> files.grantedFolders().firstOrNull { it.treeUri == t } }
        _step.value = AddStep.Confirm(
            info = info, fileName = info.fileName, decision = decision, duplicate = dup, recommendation = rec,
            connections = if (info.rangeSupported) connections else 1, policy = decision.networkPolicy ?: s.networkPolicy,
            priority = Priority.NORMAL, category = decision.category ?: info.category, folder = folder, startAt = null,
            startPaused = false, speedLimitKb = decision.speedLimitBps?.let { (it / 1024).toString() } ?: "", checksumAlgorithm = "SHA-256", checksum = "",
        )
    }

    private suspend fun inspectStream(url: String) {
        val r = request
        val s = settings.current()
        val result = repo.inspectStream(url, r?.headers.orEmpty())
        val stream = result.getOrElse { e ->
            com.sdm.engine.hls.StreamInfo(com.sdm.engine.hls.StreamKind.HLS, url, emptyList(), null, false, false, false, "Could not read the stream: ${com.sdm.core.common.Redactor.text(e.message ?: e.javaClass.simpleName)}")
        }
        val option = stream.options.firstOrNull()
        val title = (r?.titleHint?.takeIf { it.isNotBlank() } ?: FileNames.fromUrl(r?.sourcePage ?: url)?.let { FileNames.baseName(it) } ?: "video").take(80)
        val name = FileNames.sanitize("$title.mp4")
        val info = LinkInfo(url, url, name, "video/mp4", option?.estimatedBytes, FileCategory.VIDEO, true, stream.blockedReason)
        val decision = repo.applyRules(info)
        val dup = repo.findDuplicate(option?.videoPlaylistUrl ?: url, name, null)
        val folder = decision.treeUri?.let { t -> files.grantedFolders().firstOrNull { it.treeUri == t } }
        _step.value = AddStep.Confirm(
            info = info, fileName = name, decision = decision, duplicate = dup, recommendation = null, connections = 4,
            policy = decision.networkPolicy ?: s.networkPolicy, priority = Priority.NORMAL, category = FileCategory.VIDEO, folder = folder,
            startAt = null, startPaused = false, speedLimitKb = "", checksumAlgorithm = "SHA-256", checksum = "", stream = stream, streamOption = option,
        )
    }

    fun update(transform: (AddStep.Confirm) -> AddStep.Confirm) {
        (_step.value as? AddStep.Confirm)?.let { _step.value = transform(it) }
    }

    fun pickFolder(uri: Uri) {
        val f = runCatching { files.grant(uri) }.getOrNull() ?: return
        update { it.copy(folder = f) }
    }

    fun confirm(resolution: DuplicateResolution = DuplicateResolution.DOWNLOAD_ANYWAY) = viewModelScope.launch {
        val c = _step.value as? AddStep.Confirm ?: return@launch
        if (resolution == DuplicateResolution.CANCEL) { _step.value = AddStep.Done("Cancelled"); return@launch }
        var name = FileNames.sanitize(c.fileName)
        if (resolution == DuplicateResolution.REPLACE_EXISTING) c.duplicate?.let { repo.delete(listOf(it.existing.downloadId), deleteFiles = true) }
        if (resolution == DuplicateResolution.RENAME_NEW) name = FileNames.numbered(name, 2)
        val r = request
        val opt = c.streamOption
        if (c.stream != null) {
            if (opt == null || c.stream.blockedReason != null) return@launch
            repo.enqueue(NewDownload(
                url = opt.videoPlaylistUrl, fileName = name, mimeType = "video/mp4", sizeBytes = opt.estimatedBytes, headers = r?.headers.orEmpty(),
                sourcePageUrl = r?.sourcePage, category = FileCategory.VIDEO, treeUri = c.folder?.treeUri, treeLabel = c.folder?.name,
                connections = c.connections, networkPolicy = c.policy, speedLimitBps = (c.speedLimitKb.toLongOrNull() ?: 0) * 1024,
                priority = c.priority, scheduledAt = c.startAt, startPaused = c.startPaused,
                streamType = com.sdm.core.data.download.STREAM_HLS, streamAudioUrl = opt.audioPlaylistUrl, streamLabel = opt.label,
            ))
            _step.value = AddStep.Done("Added $name")
            return@launch
        }
        repo.enqueue(NewDownload(
            url = c.info.url, fileName = name, mimeType = c.info.mimeType, sizeBytes = c.info.sizeBytes, headers = r?.headers.orEmpty(),
            sourcePageUrl = r?.sourcePage, category = c.category, treeUri = c.folder?.treeUri, treeLabel = c.folder?.name,
            connections = c.connections, networkPolicy = c.policy, speedLimitBps = (c.speedLimitKb.toLongOrNull() ?: 0) * 1024,
            priority = c.priority, scheduledAt = c.startAt, checksumAlgorithm = c.checksumAlgorithm.takeIf { c.checksum.isNotBlank() },
            checksumExpected = c.checksum.ifBlank { null }, startPaused = c.startPaused,
        ))
        _step.value = AddStep.Done("Added ${name}")
    }

    fun confirmBatch(startPaused: Boolean) = viewModelScope.launch {
        val b = _step.value as? AddStep.Batch ?: return@launch
        val result = io.importUrls(b.urls.joinToString("\n"), startPaused)
        _step.value = AddStep.Done("Added ${result.imported} download(s)" + if (result.skippedDuplicates > 0) ", skipped ${result.skippedDuplicates} already in SDM" else "")
    }

    fun grantedFolders() = files.grantedFolders()
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddDownloadDialog(request: AddRequest, onDismiss: () -> Unit, vm: AddDownloadViewModel = hiltViewModel(key = "add-${request.hashCode()}")) {
    LaunchedEffect(request) { vm.start(request) }
    val step by vm.step.collectAsState()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::pickFolder) }
    var timePicker by remember { mutableStateOf(false) }
    val status = LocalStatusColors.current

    when (val s = step) {
        is AddStep.Done -> LaunchedEffect(s) { onDismiss() }
        is AddStep.Input -> {
            var text by remember(s) { mutableStateOf(s.text) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Add download") },
                text = {
                    Column {
                        OutlinedTextField(
                            text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Link") }, placeholder = { Text("https://example.com/file.zip") },
                            isError = s.error != null, supportingText = { s.error?.let { Text(it) } }, singleLine = false, maxLines = 4,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            trailingIcon = {
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                IconButton(onClick = {
                                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { text = it }
                                }) { Icon(Icons.Filled.ContentPaste, "Paste") }
                            },
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { vm.submitInput(text) }) { Text("Next") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        AddStep.Inspecting -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Checking link") },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(24.dp).height(24.dp)); Spacer(Modifier.width(12.dp)); Text("Reading file name, size and type…") } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is AddStep.Batch -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add ${s.urls.size} downloads") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Links are validated now; file details are read when each download starts. Links already in SDM are skipped.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    s.urls.take(50).forEach { Text("• " + (FileNames.fromUrl(it) ?: it), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    if (s.urls.size > 50) Text("…and ${s.urls.size - 50} more", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmBatch(false) }) { Text("Download all") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.confirmBatch(true) }) { Text("Add paused") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )
        is AddStep.Confirm -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Download file") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(s.category)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(Formatters.bytes(s.info.sizeBytes), style = MaterialTheme.typography.titleMedium)
                                Text((s.info.mimeType ?: "Unknown type") + " · " + s.category.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        s.info.error?.let { Warning(it) }
                        s.stream?.let { st ->
                            Text(
                                "HLS video stream" + (st.durationSec?.let { " · ${Formatters.duration(it.toLong())}" } ?: "") + if (st.encrypted && !st.drm) " · AES-128" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                            if (st.options.isNotEmpty()) {
                                Text("Quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    st.options.forEach { o ->
                                        FilterChip(s.streamOption == o, { vm.update { it.copy(streamOption = o, info = it.info.copy(sizeBytes = o.estimatedBytes)) } }, {
                                            Text(o.label + (o.estimatedBytes?.let { " · ~${Formatters.bytes(it)}" } ?: ""))
                                        })
                                    }
                                }
                                Text("Saved as MP4 (audio and video combined). Sizes are estimates from the stream bitrate.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (s.decision.askConfirmation) Warning("Rule: this is a large file (${Formatters.bytes(s.info.sizeBytes)}). Confirm to download.")
                        s.duplicate?.let { Warning(it.kind.message + " (${it.existing.fileName})") }
                        if (s.stream == null && !s.info.rangeSupported && s.info.error == null) Text("The server does not support resuming; SDM will use one connection.", style = MaterialTheme.typography.bodySmall, color = status.warning)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(s.fileName, { v -> vm.update { it.copy(fileName = v) } }, Modifier.fillMaxWidth(), label = { Text("File name") }, singleLine = true)
                        InfoRow("Source", com.sdm.core.common.Redactor.url(s.info.finalUrl))
                        InfoRow("Destination", s.folder?.name ?: "Download/SDM/${s.category.folder}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(s.folder == null, { vm.update { it.copy(folder = null) } }, { Text("Default folder") })
                            vm.grantedFolders().take(3).forEach { f -> FilterChip(s.folder?.treeUri == f.treeUri, { vm.update { it.copy(folder = f) } }, { Text(f.name) }) }
                            FilterChip(false, { folderPicker.launch(null) }, { Text("Choose folder…") })
                        }
                        Text(if (s.stream != null) "Parallel segments" else "Connections", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (if (s.stream != null) listOf(1, 2, 4, 8) else CONNECTION_PROFILES).forEach { n -> FilterChip(s.connections == n, { vm.update { it.copy(connections = n) } }, { Text("$n") }, enabled = s.info.rangeSupported || n == 1) }
                        }
                        s.recommendation?.takeIf { it.sampleCount > 0 }?.let { Text("Suggested: ${it.connections} · ${it.basis}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        Text("Network", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(s.policy == NetworkPolicy.ANY, { vm.update { it.copy(policy = NetworkPolicy.ANY) } }, { Text("Any network") })
                            FilterChip(s.policy == NetworkPolicy.UNMETERED_ONLY, { vm.update { it.copy(policy = NetworkPolicy.UNMETERED_ONLY) } }, { Text("Unmetered only") })
                            FilterChip(s.policy == NetworkPolicy.WIFI_ONLY, { vm.update { it.copy(policy = NetworkPolicy.WIFI_ONLY) } }, { Text("Wi-Fi only") })
                        }
                        Text("Priority", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Priority.entries.forEach { p -> FilterChip(s.priority == p, { vm.update { it.copy(priority = p) } }, { Text(p.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }) }
                        }
                        Text("Start", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(s.startAt == null && !s.startPaused, { vm.update { it.copy(startAt = null, startPaused = false) } }, { Text("Now") })
                            FilterChip(s.startAt != null, { timePicker = true }, { Text(s.startAt?.let { "At " + java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0) } ?: "At a time…") })
                            FilterChip(s.startPaused, { vm.update { it.copy(startPaused = !it.startPaused, startAt = null) } }, { Text("Add paused") })
                        }
                        OutlinedTextField(s.speedLimitKb, { v -> vm.update { it.copy(speedLimitKb = v.filter(Char::isDigit).take(7)) } }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Speed limit (KB/s, empty = unlimited)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        if (s.stream == null) OutlinedTextField(s.checksum, { v -> vm.update { it.copy(checksum = v.trim()) } }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Expected checksum (optional)") }, singleLine = true)
                        if (s.stream == null && s.checksum.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("MD5", "SHA-1", "SHA-256", "SHA-512").forEach { a -> FilterChip(s.checksumAlgorithm == a, { vm.update { it.copy(checksumAlgorithm = a) } }, { Text(a) }) }
                        }
                        if (s.decision.appliedRules.isNotEmpty()) Text("Rules applied: ${s.decision.appliedRules.joinToString()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    if (s.duplicate == null) TextButton(onClick = { vm.confirm() }, enabled = s.fileName.isNotBlank() && (s.stream == null || (s.stream.blockedReason == null && s.streamOption != null))) { Text(if (s.decision.askConfirmation) "Confirm download" else "Download") }
                    else Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { vm.confirm(DuplicateResolution.DOWNLOAD_ANYWAY) }) { Text("Download anyway") }
                        if (s.duplicate.kind != DuplicateKind.SAME_URL_ACTIVE) TextButton(onClick = { vm.confirm(DuplicateResolution.REPLACE_EXISTING) }) { Text("Replace existing") }
                        TextButton(onClick = { vm.confirm(DuplicateResolution.RENAME_NEW) }) { Text("Rename new file") }
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
            if (timePicker) {
                val state = rememberTimePickerState(LocalTime.now().hour, (LocalTime.now().minute / 5 * 5 + 5) % 60, true)
                AlertDialog(
                    onDismissRequest = { timePicker = false },
                    title = { Text("Start at") },
                    text = { TimePicker(state) },
                    confirmButton = {
                        TextButton(onClick = {
                            var at = LocalDate.now().atTime(state.hour, state.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            if (at <= System.currentTimeMillis()) at += 24L * 3600 * 1000
                            vm.update { it.copy(startAt = at, startPaused = false) }
                            timePicker = false
                        }) { Text("Set") }
                    },
                    dismissButton = { TextButton(onClick = { timePicker = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = LocalStatusColors.current.warning.copy(alpha = 0.14f))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = LocalStatusColors.current.warning)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
