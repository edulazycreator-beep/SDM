package com.sdm.downloadmanager.feature.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import com.sdm.downloadmanager.ui.MediaThumbnail
import com.sdm.downloadmanager.ui.BrowserRoute
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.Formatters
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.designsystem.component.AnimatedProgress
import com.sdm.core.designsystem.component.CategoryBadge
import com.sdm.core.designsystem.component.ConfirmDialog
import com.sdm.core.designsystem.component.EmptyState
import com.sdm.core.designsystem.component.StatusChip
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.domain.Download
import com.sdm.domain.DownloadState
import com.sdm.domain.HoldReason
import com.sdm.domain.LiveProgress
import com.sdm.downloadmanager.ui.AddRequest
import com.sdm.downloadmanager.ui.DetailsRoute
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.QueueRoute
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DownloadFilter(val label: String, val states: Set<DownloadState>?) {
    ALL("All", null),
    ACTIVE("Active", setOf(DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.VERIFYING, DownloadState.RETRYING)),
    QUEUED("Queued", setOf(DownloadState.QUEUED, DownloadState.WAITING_FOR_NETWORK)),
    PAUSED("Paused", setOf(DownloadState.PAUSED)),
    COMPLETED("Completed", setOf(DownloadState.COMPLETED)),
    FAILED("Failed", setOf(DownloadState.FAILED, DownloadState.CANCELLED)),
}

enum class DownloadSort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), NAME("Name"), SIZE("Size"), PROGRESS("Progress") }

data class DownloadsUi(val items: List<Download> = emptyList(), val live: Map<Long, LiveProgress> = emptyMap(), val filter: DownloadFilter = DownloadFilter.ALL, val sort: DownloadSort = DownloadSort.NEWEST, val selected: Set<Long> = emptySet(), val hidden: Set<Long> = emptySet())

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val repo: DownloadRepository) : ViewModel() {
    private val filter = MutableStateFlow(DownloadFilter.ALL)
    private val sort = MutableStateFlow(DownloadSort.NEWEST)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    /** Swiped away, waiting for the Undo snackbar to time out before being deleted. */
    private val hiddenIds = MutableStateFlow<Set<Long>>(emptySet())

    fun hide(id: Long) { hiddenIds.value += id }
    fun unhide(id: Long) { hiddenIds.value -= id }

    val ui = combine(repo.downloads, repo.live, filter, sort, selected) { all, live, f, s, sel ->
        val filtered = all.filter { f.states == null || it.state in f.states }
        val sorted = when (s) {
            DownloadSort.NEWEST -> filtered.sortedByDescending { it.createdAt }
            DownloadSort.OLDEST -> filtered.sortedBy { it.createdAt }
            DownloadSort.NAME -> filtered.sortedBy { it.fileName.lowercase() }
            DownloadSort.SIZE -> filtered.sortedByDescending { it.totalBytes ?: 0 }
            DownloadSort.PROGRESS -> filtered.sortedByDescending { it.progressPercent }
        }
        DownloadsUi(sorted, live, f, s, sel.intersect(all.map { it.id }.toSet()))
    }.combine(hiddenIds) { ui, h -> ui.copy(hidden = h) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUi())

    fun setFilter(f: DownloadFilter) { filter.value = f }
    fun setSort(s: DownloadSort) { sort.value = s }
    fun toggle(id: Long) { selected.value = selected.value.let { if (id in it) it - id else it + id } }
    fun selectAll(ids: List<Long>) { selected.value = if (selected.value.containsAll(ids)) emptySet() else ids.toSet() }
    fun clearSelection() { selected.value = emptySet() }

    fun pause(id: Long) = viewModelScope.launch { repo.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { repo.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { repo.cancel(id) }
    fun restart(id: Long) = viewModelScope.launch { repo.restart(id) }
    fun delete(ids: Collection<Long>, files: Boolean) = viewModelScope.launch { repo.delete(ids, files); hiddenIds.value -= ids.toSet(); clearSelection() }
    fun batch(action: (Long) -> Unit) { selected.value.forEach(action); clearSelection() }
    fun pauseAll() = viewModelScope.launch { repo.pauseAll() }
    fun resumeAll() = viewModelScope.launch { repo.resumeAll() }
    fun removeCompleted() = viewModelScope.launch { repo.removeCompleted() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(vm: DownloadsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val app = LocalAppActions.current
    val sp = LocalSpacing.current
    var sortMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Collection<Long>?>(null) }
    var deleteFiles by remember { mutableStateOf(false) }
    val selecting = ui.selected.isNotEmpty()
    val scope = rememberCoroutineScope()
    SdmScaffold(
        title = if (selecting) "${ui.selected.size} selected" else "Downloads",
        onBack = if (selecting) vm::clearSelection else null,
        actions = {
            if (selecting) {
                IconButton(onClick = { vm.selectAll(ui.items.map { it.id }) }) { Icon(Icons.Filled.SelectAll, "Select all") }
                IconButton(onClick = { vm.batch { vm.resume(it) } }) { Icon(Icons.Filled.PlayArrow, "Resume selected") }
                IconButton(onClick = { vm.batch { vm.pause(it) } }) { Icon(Icons.Filled.Pause, "Pause selected") }
                IconButton(onClick = { confirmDelete = ui.selected }) { Icon(Icons.Filled.Delete, "Delete selected") }
            } else {
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        DownloadSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label + if (s == ui.sort) "  ✓" else "") }, onClick = { vm.setSort(s); sortMenu = false }) }
                    }
                }
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(overflow, { overflow = false }) {
                        DropdownMenuItem(text = { Text("Download queue") }, onClick = { overflow = false; app.nav.navigate(QueueRoute) })
                        DropdownMenuItem(text = { Text("Pause all") }, onClick = { overflow = false; vm.pauseAll() })
                        DropdownMenuItem(text = { Text("Resume all") }, onClick = { overflow = false; vm.resumeAll() })
                        DropdownMenuItem(text = { Text("Remove completed from list") }, onClick = { overflow = false; vm.removeCompleted() })
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selecting) FloatingActionButton(onClick = { app.requestAdd(AddRequest(emptyList())) }) { Icon(Icons.Filled.Add, "Add download") }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = sp.l), horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                DownloadFilter.entries.forEach { f -> FilterChip(selected = ui.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) }) }
            }
            if (ui.items.none { it.id !in ui.hidden }) {
                if (ui.filter == DownloadFilter.ALL) EmptyState(Icons.Filled.Download, "No downloads yet", "Paste a link, or open the browser and play a video or tap a download link.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = app.pasteFromClipboard) { Text("Paste link") }
                        OutlinedButton(onClick = { app.nav.navigate(BrowserRoute()) }) { Text("Open browser") }
                    }
                } else EmptyState(Icons.Filled.Download, "Nothing here", "No downloads match \"${ui.filter.label}\".") {
                    OutlinedButton(onClick = { vm.setFilter(DownloadFilter.ALL) }) { Text("Show all") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                    items(ui.items.filter { it.id !in ui.hidden }, key = { it.id }) { d ->
                        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                            when (v) {
                                SwipeToDismissBoxValue.EndToStart -> {
                                    vm.hide(d.id)
                                    scope.launch {
                                        val r = app.snackbar.showSnackbar("Removed ${d.fileName.take(40)}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                        if (r == SnackbarResult.ActionPerformed) vm.unhide(d.id) else vm.delete(listOf(d.id), false)
                                    }
                                    false
                                }
                                SwipeToDismissBoxValue.StartToEnd -> { if (d.state.isActive || d.state == DownloadState.QUEUED) vm.pause(d.id) else vm.resume(d.id); false }
                                else -> false
                            }
                        })
                        SwipeToDismissBox(dismiss, backgroundContent = {
                            val left = dismiss.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (left) Arrangement.Start else Arrangement.End) {
                                Icon(if (left) Icons.Filled.Pause else Icons.Filled.Delete, null, tint = if (left) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }) {
                            DownloadRow(
                                d, ui.live[d.id], selected = d.id in ui.selected, selecting = selecting,
                                onClick = { if (selecting) vm.toggle(d.id) else app.nav.navigate(DetailsRoute(d.id)) },
                                onLongClick = { app.haptic(); vm.toggle(d.id) },
                                onPause = { vm.pause(d.id) }, onResume = { vm.resume(d.id) }, onCancel = { vm.cancel(d.id) },
                                onRestart = { vm.restart(d.id) }, onDelete = { confirmDelete = listOf(d.id) },
                            )
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { ids ->
        ConfirmDialog(
            title = "Delete ${ids.size} download(s)?",
            message = "Removes them from SDM. Unfinished parts are always deleted.",
            confirm = "Delete", destructive = true,
            onConfirm = { vm.delete(ids, deleteFiles) },
            onDismiss = { confirmDelete = null; deleteFiles = false },
            extra = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteFiles, { deleteFiles = it })
                    Text("Also delete completed files from storage")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadRow(
    d: Download, live: LiveProgress?, selected: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onRestart: () -> Unit, onDelete: () -> Unit,
) {
    val sp = LocalSpacing.current
    var menu by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
    androidx.compose.material3.Surface(color = bg) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = sp.l, vertical = sp.listItemVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) Checkbox(selected, { onClick() })
            else if (d.state == DownloadState.COMPLETED) MediaThumbnail(d.destinationUri, d.category)
            else CategoryBadge(d.category)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.isStream) {
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.padding(end = 6.dp)) {
                            Text(listOfNotNull(d.streamType, d.streamLabel?.substringBefore(" ·")).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                    Text(d.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                Spacer(Modifier.height(4.dp))
                val done = live?.downloadedBytes ?: d.downloadedBytes
                val total = live?.totalBytes ?: d.totalBytes
                if (d.state != DownloadState.COMPLETED && d.state != DownloadState.CANCELLED) {
                    AnimatedProgress(if (total != null && total > 0) done.toFloat() / total else if (d.state.isActive) null else 0f)
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.state != DownloadState.COMPLETED) { StatusChip(d.state, labelOverride = holdLabel(d)); Spacer(Modifier.width(8.dp)) }
                    Text(
                        detailLine(d, live), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (d.state == DownloadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!selecting) {
                when {
                    d.state.isActive || d.state == DownloadState.QUEUED || d.state == DownloadState.WAITING_FOR_NETWORK -> IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, "Pause") }
                    d.state == DownloadState.PAUSED || d.state == DownloadState.FAILED -> IconButton(onClick = onResume) { Icon(if (d.state == DownloadState.FAILED) Icons.Filled.Refresh else Icons.Filled.PlayArrow, "Resume") }
                    else -> Unit
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
                    DropdownMenu(menu, { menu = false }) {
                        if (!d.state.isTerminal) DropdownMenuItem(text = { Text("Cancel") }, leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = { menu = false; onCancel() })
                        DropdownMenuItem(text = { Text(if (d.state == DownloadState.COMPLETED) "Download again" else "Restart from the beginning") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { menu = false; onRestart() })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Color.Unspecified) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

fun holdLabel(d: Download): String? = if (d.state != DownloadState.QUEUED) null else when (d.holdReason) {
    HoldReason.NONE -> null
    HoldReason.SCHEDULE -> "Scheduled"
    HoldReason.METERED_NETWORK -> "Waiting for Wi-Fi"
    HoldReason.BATTERY -> "Waiting for battery"
    HoldReason.STORAGE -> "Not enough storage"
    HoldReason.RULE -> "Paused by rule"
    HoldReason.USER_CONFIRMATION -> "Needs confirmation"
    HoldReason.START_TIME -> "Starts later"
}

fun detailLine(d: Download, live: LiveProgress?): String {
    val total = live?.totalBytes ?: d.totalBytes
    val done = live?.downloadedBytes ?: d.downloadedBytes
    val approx = if (d.isStream && d.state != DownloadState.COMPLETED) "~" else ""
    return when {
        live != null -> buildString {
            append("${Formatters.percent(done, total)}% · ${Formatters.speed(live.speedBps)}")
            live.etaSeconds?.let { append(" · ${Formatters.duration(it)} left") }
            append(" · ${Formatters.bytes(done)} / $approx${Formatters.bytes(total)}")
        }
        d.state == DownloadState.COMPLETED -> "${Formatters.bytes(total)} · ${Formatters.relativeDate(d.completedAt ?: d.createdAt)} · avg ${Formatters.speed(d.averageSpeedBps)}"
        d.state == DownloadState.FAILED -> (d.errorCode?.title ?: "Failed") + " · tap for details"
        else -> "${Formatters.bytes(done)} / $approx${Formatters.bytes(total)} · ${Formatters.percent(done, total)}%"
    }
}
