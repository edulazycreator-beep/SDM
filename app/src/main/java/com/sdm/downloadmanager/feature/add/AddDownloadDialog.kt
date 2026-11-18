package com.sdm.downloadmanager.feature.add

import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Switch
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
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.sdm.core.designsystem.i18n.Text
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
        val audioTrack: com.sdm.engine.hls.AudioTrack? = null,
        /** URLs of subtitle tracks to save next to the video. */
        val subtitles: Set<String> = emptySet(),
        val audioOnly: Boolean = false,
        /** Alternative links for the same file, one per line. */
        val mirrorsText: String = "",
        /** The link is a magnet or .torrent: [torrent] downloads its contents instead of the file itself. */
        val torrentCapable: Boolean = false,
        val torrent: Boolean = false,
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
            urls.isEmpty() -> _step.value = AddStep.Input(text, "Enter a valid link (http, https, FTP or SFTP)")
            urls.size > 1 -> _step.value = AddStep.Batch(urls)
            else -> inspect(urls.first())
        }
    }

    private fun inspect(rawUrl: String) = viewModelScope.launch {
        _step.value = AddStep.Inspecting
        // ftp://user:password@host/... : the login moves into the encrypted headers, never into the stored link.
        var url = rawUrl
        if (com.sdm.core.common.Urls.isMagnet(rawUrl)) { magnet(rawUrl); return@launch }
        if (rawUrl.startsWith("file:") && rawUrl.endsWith(".torrent", true)) { magnet(rawUrl); return@launch }
        if (com.sdm.engine.remote.RemoteFileEngine.handles(rawUrl)) {
            val (clean, login) = com.sdm.engine.remote.RemoteLogin.split(rawUrl)
            if (login != null) {
                url = clean
                request = (request ?: AddRequest(listOf(clean))).let { it.copy(urls = listOf(clean), headers = it.headers + login.header()) }
            }
        }
        val r = request
        if (r?.streamHint == true || com.sdm.engine.hls.HlsParser.streamKindOfUrl(url) != null || com.sdm.engine.hls.HlsParser.isHlsContentType(r?.mime) || r?.mime?.contains("dash+xml") == true) {
            inspectStream(url); return@launch
        }
        val info = repo.inspect(url, r?.headers.orEmpty(), r?.contentDisposition, r?.mime).let { i ->
            val hinted = r?.fileNameHint?.let { FileNames.sanitize(it) }
            if (i.sizeBytes == null && r?.sizeHint != null && r.sizeHint > 0) i.copy(sizeBytes = r.sizeHint, fileName = hinted ?: i.fileName) else if (hinted != null) i.copy(fileName = hinted) else i
        }
        if (com.sdm.engine.hls.HlsParser.isHlsContentType(info.mimeType) || info.mimeType?.contains("dash+xml") == true) { inspectStream(url); return@launch }
        val isTorrentFile = info.mimeType == "application/x-bittorrent" || FileNames.extension(info.fileName).equals("torrent", true)
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
        if (isTorrentFile) markTorrent()
    }

    private fun r0Name(url: String): String? = if (url.startsWith("file:")) url.substringAfterLast('/').removeSuffix(".torrent").removeSuffix(".TORRENT").substringAfter('-').ifBlank { null } else null

    private suspend fun magnet(url: String) {
        val s = settings.current()
        val name = FileNames.sanitize(com.sdm.core.common.Urls.magnetName(url) ?: r0Name(url) ?: "Torrent")
        val info = LinkInfo(url, url, name, null, null, FileCategory.OTHER, true, null)
        val decision = repo.applyRules(info)
        _step.value = AddStep.Confirm(
            info = info, fileName = name, decision = decision, duplicate = repo.findDuplicate(url, name, null), recommendation = null, connections = 1,
            policy = decision.networkPolicy ?: s.networkPolicy, priority = Priority.NORMAL, category = FileCategory.OTHER, folder = null,
            startAt = null, startPaused = false, speedLimitKb = "", checksumAlgorithm = "SHA-256", checksum = "", torrentCapable = true, torrent = true,
        )
    }

    /** Pre-selects subtitles in the device language (or the stream's default track). */
    private fun defaultSubtitles(stream: com.sdm.engine.hls.StreamInfo): Set<String> {
        val lang = java.util.Locale.getDefault().language
        val match = stream.subtitles.filter { it.language?.substringBefore('-')?.equals(lang, true) == true }
        return (match.ifEmpty { stream.subtitles.filter { it.isDefault } }).take(1).map { it.url }.toSet()
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
        val name = FileNames.sanitize(if (stream.options.isNotEmpty() && stream.options.all { it.height == null }) "$title.m4a" else "$title.mp4")
        val info = LinkInfo(url, url, name, "video/mp4", option?.estimatedBytes, FileCategory.VIDEO, true, stream.blockedReason)
        val decision = repo.applyRules(info)
        val dup = repo.findDuplicate(option?.videoPlaylistUrl ?: url, name, null)
        val folder = decision.treeUri?.let { t -> files.grantedFolders().firstOrNull { it.treeUri == t } }
        _step.value = AddStep.Confirm(
            info = info, fileName = name, decision = decision, duplicate = dup, recommendation = null, connections = 4,
            policy = decision.networkPolicy ?: s.networkPolicy, priority = Priority.NORMAL, category = FileCategory.VIDEO, folder = folder,
            startAt = null, startPaused = false, speedLimitKb = "", checksumAlgorithm = "SHA-256", checksum = "", stream = stream, streamOption = option,
            audioTrack = stream.audioFor(option).let { a -> a.firstOrNull { it.url == option?.audioPlaylistUrl } ?: a.firstOrNull { it.isDefault } ?: a.firstOrNull() },
            subtitles = defaultSubtitles(stream),
            audioOnly = option != null && option.height == null && stream.options.all { it.height == null },
        )
    }

    private fun markTorrent() { (_step.value as? AddStep.Confirm)?.let { c -> _step.value = c.copy(torrentCapable = true, torrent = true) } }

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
            val audio = c.audioTrack
            val source = if (c.audioOnly) c.stream.audioOnlySource(opt, audio) ?: opt.videoPlaylistUrl else opt.videoPlaylistUrl
            val streamName = if (c.audioOnly) FileNames.baseName(name) + ".m4a" else FileNames.baseName(name) + ".mp4"
            repo.enqueue(NewDownload(
                url = source, fileName = streamName, mimeType = if (c.audioOnly) "audio/mp4" else "video/mp4",
                sizeBytes = if (c.audioOnly) audio?.bandwidth?.let { b -> c.stream.durationSec?.let { (b / 8.0 * it).toLong() } } else opt.estimatedBytes,
                headers = r?.headers.orEmpty(), sourcePageUrl = r?.sourcePage, category = if (c.audioOnly) FileCategory.MUSIC else FileCategory.VIDEO,
                treeUri = c.folder?.treeUri, treeLabel = c.folder?.name,
                connections = c.connections, networkPolicy = c.policy, speedLimitBps = (c.speedLimitKb.toLongOrNull() ?: 0) * 1024,
                priority = c.priority, scheduledAt = c.startAt, startPaused = c.startPaused,
                streamType = if (c.stream.kind == com.sdm.engine.hls.StreamKind.DASH) com.sdm.core.data.download.STREAM_DASH else com.sdm.core.data.download.STREAM_HLS,
                streamAudioUrl = if (c.audioOnly) null else (audio?.url ?: opt.audioPlaylistUrl),
                streamLabel = if (c.audioOnly) "Audio" + (audio?.let { " · ${it.name}" } ?: "") else opt.label + (audio?.takeIf { c.stream.audioFor(opt).size > 1 }?.let { " · ${it.name}" } ?: ""),
                streamSubtitles = if (c.audioOnly) emptyList() else c.stream.subtitles.filter { it.url in c.subtitles },
                streamAudioOnly = c.audioOnly,
            ))
            _step.value = AddStep.Done("Added $streamName")
            return@launch
        }
        if (c.torrent) {
            val tName = FileNames.baseName(name).ifBlank { "Torrent" }
            repo.enqueue(NewDownload(
                url = c.info.url, fileName = tName, headers = r?.headers.orEmpty(), sourcePageUrl = r?.sourcePage, category = FileCategory.OTHER,
                treeUri = c.folder?.treeUri, treeLabel = c.folder?.name, connections = 1, networkPolicy = c.policy,
                speedLimitBps = (c.speedLimitKb.toLongOrNull() ?: 0) * 1024, priority = c.priority, scheduledAt = c.startAt, startPaused = c.startPaused,
                streamType = com.sdm.core.data.download.STREAM_TORRENT,
            ))
            _step.value = AddStep.Done("Added torrent $tName")
            return@launch
        }
        repo.enqueue(NewDownload(
            url = c.info.url, fileName = name, mimeType = c.info.mimeType, sizeBytes = c.info.sizeBytes, headers = r?.headers.orEmpty(),
            sourcePageUrl = r?.sourcePage, category = c.category, treeUri = c.folder?.treeUri, treeLabel = c.folder?.name,
            connections = c.connections, networkPolicy = c.policy, speedLimitBps = (c.speedLimitKb.toLongOrNull() ?: 0) * 1024,
            priority = c.priority, scheduledAt = c.startAt, checksumAlgorithm = c.checksumAlgorithm.takeIf { c.checksum.isNotBlank() },
            checksumExpected = c.checksum.ifBlank { null }, startPaused = c.startPaused,
            mirrors = c.mirrorsText.lines(),
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
                                }) { Icon(Icons.Outlined.ContentPaste, "Paste") }
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
            var advanced by rememberSaveable { mutableStateOf(false) }
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
                        if (s.torrentCapable) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                Text("Download the torrent's files", modifier = Modifier.weight(1f))
                                Switch(s.torrent, { v -> vm.update { it.copy(torrent = v) } }, enabled = !com.sdm.core.common.Urls.isMagnet(s.info.url))
                            }
                            if (s.torrent) Text(
                                "BitTorrent: details and files come from other peers, so the size appears once the torrent is found. While downloading, SDM also uploads pieces to others, as the protocol requires; it stops when the download finishes. Only download content you have the right to.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        s.stream?.let { st ->
                            Text(
                                "${st.kind.name} video stream" + (st.durationSec?.let { " · ${Formatters.duration(it.toLong())}" } ?: "") + if (st.encrypted && !st.drm) " · AES-128" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                            if (st.options.isNotEmpty()) {
                                Text("Quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    st.options.forEach { o ->
                                        FilterChip(s.streamOption == o, {
                                            vm.update { c ->
                                                val tracks = st.audioFor(o)
                                                val keep = tracks.firstOrNull { it.language == c.audioTrack?.language && it.name == c.audioTrack?.name }
                                                c.copy(streamOption = o, info = c.info.copy(sizeBytes = o.estimatedBytes), audioTrack = keep ?: tracks.firstOrNull { it.url == o.audioPlaylistUrl } ?: tracks.firstOrNull())
                                            }
                                        }, {
                                            Text(o.label + (o.estimatedBytes?.let { " · ~${Formatters.bytes(it)}" } ?: ""))
                                        })
                                    }
                                }
                                val audios = st.audioFor(s.streamOption)
                                if (audios.size > 1) {
                                    Text("Audio", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        audios.forEach { a -> FilterChip(s.audioTrack?.url == a.url, { vm.update { it.copy(audioTrack = a) } }, { Text(a.name) }) }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                    Text("Audio only (.m4a)", modifier = Modifier.weight(1f))
                                    Switch(s.audioOnly, { v -> vm.update { it.copy(audioOnly = v) } })
                                }
                                if (!s.audioOnly && st.subtitles.isNotEmpty()) {
                                    Text("Subtitles (saved as .srt next to the video)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        st.subtitles.forEach { t ->
                                            FilterChip(t.url in s.subtitles, { vm.update { c -> c.copy(subtitles = if (t.url in c.subtitles) c.subtitles - t.url else c.subtitles + t.url) } }, { Text(t.name) })
                                        }
                                    }
                                }
                                Text(if (s.audioOnly) "Saves only the sound as M4A." else "Saved as MP4 (audio and video combined). Sizes are estimates from the stream bitrate.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (s.decision.askConfirmation) Warning("Rule: this is a large file (${Formatters.bytes(s.info.sizeBytes)}). Confirm to download.")
                        s.duplicate?.let { Warning(it.kind.message + " (${it.existing.fileName})") }
                        if (s.stream == null && !s.torrent && !s.info.rangeSupported && s.info.error == null) Text("The server does not support resuming; SDM will use one connection.", style = MaterialTheme.typography.bodySmall, color = status.warning)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(s.fileName, { v -> vm.update { it.copy(fileName = v) } }, Modifier.fillMaxWidth(), label = { Text("File name") }, singleLine = true)
                        InfoRow("Source", com.sdm.core.common.Redactor.url(s.info.finalUrl))
                        InfoRow("Destination", s.folder?.name ?: "Download/SDM/${s.category.folder}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(s.folder == null, { vm.update { it.copy(folder = null) } }, { Text("Default folder") })
                            vm.grantedFolders().take(3).forEach { f -> FilterChip(s.folder?.treeUri == f.treeUri, { vm.update { it.copy(folder = f) } }, { Text(f.name) }) }
                            FilterChip(false, { folderPicker.launch(null) }, { Text("Choose folder…") })
                        }
                        // Everyday downloads only need the name and destination; the rest stays one tap away.
                        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Advanced options", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    listOfNotNull(if (!s.torrent) "${s.connections} connections" else null, when (s.policy) { NetworkPolicy.ANY -> "Any network"; NetworkPolicy.UNMETERED_ONLY -> "Unmetered only"; NetworkPolicy.WIFI_ONLY -> "Wi-Fi only" }, if (s.startPaused) "Add paused" else if (s.startAt != null) "Scheduled" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                        }
                        androidx.compose.animation.AnimatedVisibility(visible = advanced) {
                            Column {
                            if (!s.torrent) Text(if (s.stream != null) "Parallel segments" else "Connections", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            if (!s.torrent) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            if (s.stream == null && !s.torrent) OutlinedTextField(s.checksum, { v -> vm.update { it.copy(checksum = v.trim()) } }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Expected checksum (optional)") }, singleLine = true)
                            if (s.stream == null && !s.torrent) OutlinedTextField(
                                s.mirrorsText, { v -> vm.update { it.copy(mirrorsText = v) } }, Modifier.fillMaxWidth().padding(top = 4.dp),
                                label = { Text("Mirror links (optional)") }, supportingText = { Text("One per line. Used in order if this link fails.") }, minLines = 1, maxLines = 4,
                            )
                            if (s.stream == null && s.checksum.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("MD5", "SHA-1", "SHA-256", "SHA-512").forEach { a -> FilterChip(s.checksumAlgorithm == a, { vm.update { it.copy(checksumAlgorithm = a) } }, { Text(a) }) }
                            }
                            }
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
            Icon(Icons.Outlined.Warning, null, tint = LocalStatusColors.current.warning)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
