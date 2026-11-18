package com.sdm.downloadmanager.feature.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.FileCategory
import com.sdm.core.common.Formatters
import com.sdm.core.data.files.DuplicateGroup
import com.sdm.core.data.files.FileItem
import com.sdm.core.data.files.FileRepository
import com.sdm.core.data.files.GrantedFolder
import com.sdm.core.designsystem.component.CategoryBadge
import com.sdm.core.designsystem.component.ConfirmDialog
import com.sdm.core.designsystem.component.EmptyState
import com.sdm.core.designsystem.component.InfoRow
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.downloadmanager.feature.details.TextEditDialog
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import com.sdm.downloadmanager.ui.StorageAnalysisRoute
import com.sdm.downloadmanager.ui.copyToClipboard
import com.sdm.downloadmanager.ui.openFile
import com.sdm.downloadmanager.ui.shareFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class FileSort(val label: String) { DATE("Date"), NAME("Name"), SIZE("Size"), TYPE("Type") }

data class FilesUi(
    val all: List<FileItem> = emptyList(),
    val visible: List<FileItem> = emptyList(),
    val category: FileCategory? = null,
    val query: String = "",
    val sort: FileSort = FileSort.DATE,
    val grid: Boolean = false,
    val selected: Set<String> = emptySet(),
    val minSizeMb: Int = 0,
)

@HiltViewModel
class FilesViewModel @Inject constructor(private val repo: FileRepository) : ViewModel() {
    private val category = MutableStateFlow<FileCategory?>(null)
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(FileSort.DATE)
    private val grid = MutableStateFlow(false)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val minSize = MutableStateFlow(0)
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _duplicates = MutableStateFlow<List<DuplicateGroup>?>(null)
    val duplicates = _duplicates.asStateFlow()
    private val _folders = MutableStateFlow(repo.grantedFolders())
    val folders = _folders.asStateFlow()

    val ui = combine(repo.files, combine(category, query, sort, grid, minSize) { c, q, s, g, m -> listOf(c, q, s, g, m) }, selected) { all, f, sel ->
        val c = f[0] as FileCategory?; val q = f[1] as String; val s = f[2] as FileSort; val g = f[3] as Boolean; val m = f[4] as Int
        val visible = all.filter { (c == null || it.category == c) && (q.isBlank() || it.name.contains(q, true)) && it.sizeBytes >= m * 1024L * 1024 }
            .let { l -> when (s) { FileSort.DATE -> l.sortedByDescending { it.modifiedAt }; FileSort.NAME -> l.sortedBy { it.name.lowercase() }; FileSort.SIZE -> l.sortedByDescending { it.sizeBytes }; FileSort.TYPE -> l.sortedBy { it.extension } } }
        FilesUi(all, visible, c, q, s, g, sel.intersect(all.map { it.uri }.toSet()), m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUi())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        runCatching { repo.refresh() }.onFailure { _message.value = "Scan failed: ${it.message}" }
        _folders.value = repo.grantedFolders()
        _refreshing.value = false
    }

    fun setCategory(c: FileCategory?) { category.value = c }
    fun setQuery(q: String) { query.value = q }
    fun setSort(s: FileSort) { sort.value = s }
    fun setMinSize(mb: Int) { minSize.value = mb }
    fun toggleGrid() { grid.value = !grid.value }
    fun toggle(uri: String) { selected.value = selected.value.let { if (uri in it) it - uri else it + uri } }
    fun selectAll() { selected.value = if (selected.value.size == ui.value.visible.size) emptySet() else ui.value.visible.map { it.uri }.toSet() }
    fun clear() { selected.value = emptySet() }
    private fun chosen() = ui.value.all.filter { it.uri in selected.value }

    fun delete() = viewModelScope.launch { val n = repo.delete(chosen()); clear(); _message.value = "Deleted $n file(s)" }
    fun rename(item: FileItem, name: String) = viewModelScope.launch { _message.value = if (repo.rename(item, name)) "Renamed" else "Could not rename" }
    fun batchRename(pattern: String) = viewModelScope.launch { val n = repo.batchRename(chosen(), pattern); clear(); _message.value = "Renamed $n file(s)" }
    fun moveTo(treeUri: String) = viewModelScope.launch { val n = runCatching { repo.moveTo(chosen(), treeUri) }.getOrElse { _message.value = it.message; 0 }; clear(); _message.value = "Moved $n file(s)" }
    fun addFolder(uri: android.net.Uri) = viewModelScope.launch { runCatching { repo.addFolder(uri) }; _folders.value = repo.grantedFolders() }
    fun removeFolder(f: GrantedFolder) = viewModelScope.launch { repo.removeFolder(f.treeUri); _folders.value = repo.grantedFolders() }
    fun createFolder(treeUri: String, name: String) = viewModelScope.launch { _message.value = if (repo.createFolder(treeUri, name)) "Folder created" else "Could not create folder" }
    fun findDuplicates() = viewModelScope.launch { _message.value = "Checking file contents…"; _duplicates.value = repo.findDuplicates(ui.value.all) }
    fun closeDuplicates() { _duplicates.value = null }
    suspend fun checksum(item: FileItem, algo: String) = runCatching { repo.checksum(item, algo) }.getOrElse { "Error: ${it.message}" }
    fun deleteItems(items: List<FileItem>) = viewModelScope.launch { repo.delete(items); _duplicates.value = _duplicates.value?.map { g -> g.copy(files = g.files - items.toSet()) }?.filter { it.files.size > 1 } }
    fun shareUri(item: FileItem) = repo.shareableUri(item.uri)
    fun consume() { _message.value = null }
}

private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(vm: FilesViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val message by vm.message.collectAsState()
    val duplicates by vm.duplicates.collectAsState()
    val folders by vm.folders.collectAsState()
    val app = LocalAppActions.current
    val ctx = LocalContext.current
    val sp = LocalSpacing.current
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<FileItem?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var batchRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var newFolderIn by remember { mutableStateOf<GrantedFolder?>(null) }
    var foldersDialog by remember { mutableStateOf(false) }
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addFolder) }
    LaunchedEffect(message) { message?.let { app.snackbar.showSnackbar(it); vm.consume() } }
    val selecting = ui.selected.isNotEmpty()

    SdmScaffold(
        title = if (selecting) "${ui.selected.size} selected" else "Files",
        onBack = if (selecting) vm::clear else null,
        actions = {
            if (selecting) {
                IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
                IconButton(onClick = { val items = ui.all.filter { it.uri in ui.selected }; ctx.shareFiles(items.map(vm::shareUri), items.map { it.mimeType }) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { batchRename = true }) { Icon(Icons.Filled.DriveFileRenameOutline, "Batch rename") }
                IconButton(onClick = { moveDialog = true }) { Icon(Icons.Filled.DriveFileMove, "Move") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
            } else {
                IconButton(onClick = { searching = !searching; if (!searching) vm.setQuery("") }) { Icon(Icons.Filled.Search, "Search files") }
                IconButton(onClick = vm::toggleGrid) { Icon(if (ui.grid) Icons.Filled.ViewList else Icons.Filled.GridView, "Toggle grid") }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) { FileSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label + if (ui.sort == s) "  ✓" else "") }, onClick = { vm.setSort(s); sortMenu = false }) } }
                }
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(overflow, { overflow = false }) {
                        DropdownMenuItem(text = { Text("Folders and access") }, onClick = { overflow = false; foldersDialog = true })
                        DropdownMenuItem(text = { Text("Find duplicates") }, onClick = { overflow = false; vm.findDuplicates() })
                        DropdownMenuItem(text = { Text("Storage analysis") }, onClick = { overflow = false; app.nav.navigate(StorageAnalysisRoute) })
                        DropdownMenuItem(text = { Text("Only files over 100 MB") }, onClick = { overflow = false; vm.setMinSize(if (ui.minSizeMb == 100) 0 else 100) })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (searching) OutlinedTextField(ui.query, vm::setQuery, Modifier.fillMaxWidth().padding(horizontal = sp.l), placeholder = { Text("Search file names") }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = sp.l), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(ui.category == null, { vm.setCategory(null) }, { Text("All files") })
                FileCategory.entries.forEach { c -> FilterChip(ui.category == c, { vm.setCategory(c) }, { Text(c.label) }) }
            }
            if (ui.minSizeMb > 0) Text("Showing files over ${ui.minSizeMb} MB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = sp.l))
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                if (ui.visible.isEmpty()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { EmptyState(Icons.Filled.Folder, "No files", "Completed downloads appear here. Add folders under ⋮ › Folders to manage other files.", Modifier.heightIn(min = 360.dp)) }
                    }
                } else if (ui.grid) {
                    LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(sp.m)) {
                        items(ui.visible, key = { it.uri }) { f ->
                            Card(Modifier.padding(4.dp).combinedClickable(onClick = { if (selecting) vm.toggle(f.uri) else ctx.openFile(vm.shareUri(f), f.mimeType, f.name) }, onLongClick = { app.haptic(); vm.toggle(f.uri) })) {
                                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box { CategoryBadge(f.category, size = 56); if (f.uri in ui.selected) Checkbox(true, { vm.toggle(f.uri) }) }
                                    Text(f.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(Formatters.bytes(f.sizeBytes), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(ui.visible, key = { it.uri }) { f ->
                            var menu by remember { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) vm.toggle(f.uri) else ctx.openFile(vm.shareUri(f), f.mimeType, f.name) }, onLongClick = { app.haptic(); vm.toggle(f.uri) })
                                    .padding(horizontal = sp.l, vertical = sp.listItemVertical),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selecting) Checkbox(f.uri in ui.selected, { vm.toggle(f.uri) }) else CategoryBadge(f.category)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                    Text("${Formatters.bytes(f.sizeBytes)} · ${dateFmt.format(Instant.ofEpochMilli(f.modifiedAt))} · ${f.folder}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "File actions") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; ctx.openFile(vm.shareUri(f), f.mimeType, f.name) })
                                        if (com.sdm.downloadmanager.feature.player.PlayerActivity.canPlay(f.mimeType)) DropdownMenuItem(text = { Text("Open with…") }, onClick = { menu = false; ctx.openFile(vm.shareUri(f), f.mimeType, chooser = true) })
                                        DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; ctx.shareFiles(listOf(vm.shareUri(f)), listOf(f.mimeType)) })
                                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renameItem = f })
                                        DropdownMenuItem(text = { Text("Details and checksum") }, onClick = { menu = false; details = f })
                                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.clear(); vm.toggle(f.uri); confirmDelete = true })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) ConfirmDialog("Delete ${ui.selected.size} file(s)?", "Files are removed from storage. This cannot be undone.", "Delete", onConfirm = vm::delete, onDismiss = { confirmDelete = false }, destructive = true)
    renameItem?.let { item -> TextEditDialog("Rename", item.name, { vm.rename(item, it) }) { renameItem = null } }
    if (batchRename) TextEditDialog("Batch rename", "{name}_{n}", { vm.batchRename(it) }, "Tokens: {name} original name, {n} number, {ext} extension. The extension is kept automatically.") { batchRename = false }
    if (moveDialog) FolderPickerDialog(folders, "Move to", onPick = { vm.moveTo(it.treeUri); moveDialog = false }, onAdd = { addFolder.launch(null) }, onDismiss = { moveDialog = false })
    if (foldersDialog) AlertDialog(
        onDismissRequest = { foldersDialog = false },
        title = { Text("Folders") },
        text = {
            Column {
                Text("SDM manages the files it downloads. To manage another folder, grant access to it here (Android scoped storage).", style = MaterialTheme.typography.bodySmall)
                folders.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, Modifier.weight(1f))
                        IconButton(onClick = { newFolderIn = f }) { Icon(Icons.Filled.CreateNewFolder, "Create folder in ${f.name}") }
                        IconButton(onClick = { vm.removeFolder(f) }) { Icon(Icons.Filled.Delete, "Remove access") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { addFolder.launch(null) }) { Text("Add folder") } },
        dismissButton = { TextButton(onClick = { foldersDialog = false }) { Text("Close") } },
    )
    newFolderIn?.let { f -> TextEditDialog("New folder in ${f.name}", "New folder", { vm.createFolder(f.treeUri, it) }) { newFolderIn = null } }
    details?.let { f -> FileDetailsDialog(f, vm, onDismiss = { details = null }) }
    duplicates?.let { groups ->
        AlertDialog(
            onDismissRequest = vm::closeDuplicates,
            title = { Text("Duplicates") },
            text = {
                if (groups.isEmpty()) Text("No duplicate files found. Files are compared by SHA-256 content hash, not by name.")
                else LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(groups, key = { it.sha256 }) { g ->
                        Text("${g.files.size} identical files · ${Formatters.bytes(g.sizeBytes)} each", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        g.files.forEachIndexed { i, f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${f.name}\n${f.folder}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if (i > 0) TextButton(onClick = { vm.deleteItems(listOf(f)) }) { Text("Delete") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeDuplicates) { Text("Close") } },
        )
    }
}

@Composable
fun FolderPickerDialog(folders: List<GrantedFolder>, title: String, onPick: (GrantedFolder) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (folders.isEmpty()) Text("No folders yet. Add one to move files into it.")
                folders.forEach { f -> TextButton(onClick = { onPick(f) }) { Icon(Icons.Filled.Folder, null); Spacer(Modifier.width(8.dp)); Text(f.name) } }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Add folder…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FileDetailsDialog(f: FileItem, vm: FilesViewModel, onDismiss: () -> Unit) {
    var hash by remember { mutableStateOf(f.sha256?.let { "SHA-256: $it" }) }
    var busy by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var algo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(algo) { algo?.let { a -> busy = true; hash = "$a: " + vm.checksum(f, a); busy = false } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.name) },
        text = {
            Column {
                InfoRow("Size", "${Formatters.bytes(f.sizeBytes)} (${f.sizeBytes} bytes)")
                InfoRow("Type", f.mimeType ?: "Unknown")
                InfoRow("Category", f.category.label)
                InfoRow("Modified", dateFmt.format(Instant.ofEpochMilli(f.modifiedAt)))
                InfoRow("Location", f.folder)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("MD5", "SHA-1", "SHA-256").forEach { a -> FilterChip(false, { algo = a }, { Text(a) }, enabled = !busy) } }
                if (busy) Text("Computing…")
                hash?.let { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { ctx.copyToClipboard("Checksum", h.substringAfter(": ")) }) { Icon(Icons.Filled.ContentCopy, "Copy checksum") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
