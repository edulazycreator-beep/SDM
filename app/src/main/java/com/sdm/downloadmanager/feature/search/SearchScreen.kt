package com.sdm.downloadmanager.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.FileNames
import com.sdm.core.common.Formatters
import com.sdm.core.data.browser.BrowserRepository
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.database.SdmDatabase
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.component.StatusChip
import com.sdm.domain.DownloadState
import com.sdm.downloadmanager.ui.BrowserRoute
import com.sdm.downloadmanager.ui.DetailsRoute
import com.sdm.downloadmanager.ui.FilesRoute
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class SizeFilter(val label: String, val minBytes: Long) { ANY("Any size", 0), MB10("> 10 MB", 10L shl 20), MB100("> 100 MB", 100L shl 20), GB1("> 1 GB", 1L shl 30) }
enum class DateFilter(val label: String, val days: Long?) { ANY("Any time", null), DAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30) }
data class SearchFilters(val query: String = "", val status: DownloadState? = null, val size: SizeFilter = SizeFilter.ANY, val date: DateFilter = DateFilter.ANY, val ext: String = "")

data class SearchResults(
    val downloads: List<com.sdm.domain.Download> = emptyList(),
    val files: List<com.sdm.core.database.FileIndexEntity> = emptyList(),
    val bookmarks: List<com.sdm.core.database.BookmarkEntity> = emptyList(),
    val history: List<com.sdm.core.database.HistoryEntity> = emptyList(),
)

/** Universal search across downloads, files, bookmarks and history (spec section 23). */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(downloads: DownloadRepository, browser: BrowserRepository, db: SdmDatabase) : ViewModel() {
    val filters = MutableStateFlow(SearchFilters())
    val results = filters.debounce(200).mapLatest { f ->
        if (f.query.isBlank() && f.status == null && f.ext.isBlank() && f.size == SizeFilter.ANY && f.date == DateFilter.ANY) return@mapLatest SearchResults()
        val since = f.date.days?.let { System.currentTimeMillis() - it * 24 * 3600 * 1000 } ?: 0
        val q = "%${f.query.trim()}%"
        val ext = f.ext.trim().trimStart('.').lowercase()
        val d = downloads.search(f.query)
            .filter { (f.status == null || it.state == f.status) && (it.totalBytes ?: 0) >= f.size.minBytes && it.createdAt >= since && (ext.isEmpty() || FileNames.extension(it.fileName) == ext) }
        val files = db.files().search(q).filter { it.sizeBytes >= f.size.minBytes && it.modifiedAt >= since && (ext.isEmpty() || FileNames.extension(it.name) == ext) }
        val bm = if (f.query.isBlank() || f.status != null) emptyList() else browser.searchBookmarks(f.query.trim())
        val hist = if (f.query.isBlank() || f.status != null) emptyList() else browser.searchHistory(f.query.trim()).filter { it.lastVisited >= since }
        SearchResults(d, if (f.status != null) emptyList() else files, bm, hist)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun update(f: (SearchFilters) -> SearchFilters) { filters.value = f(filters.value) }
}

@Composable
fun SearchScreen(vm: SearchViewModel = hiltViewModel()) {
    val f by vm.filters.collectAsState()
    val r by vm.results.collectAsState()
    val app = LocalAppActions.current
    SdmScaffold("Search", onBack = { app.nav.popBackStack() }) { p ->
        LazyColumn(Modifier.padding(p).padding(horizontal = 16.dp)) {
            item {
                OutlinedTextField(f.query, { q -> vm.update { it.copy(query = q) } }, Modifier.fillMaxWidth(), placeholder = { Text("File name, link, page title…") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(f.status == null, { vm.update { it.copy(status = null) } }, { Text("Any status") })
                    listOf(DownloadState.DOWNLOADING, DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.COMPLETED, DownloadState.FAILED).forEach { s ->
                        FilterChip(f.status == s, { vm.update { it.copy(status = s) } }, { Text(s.name.lowercase().replaceFirstChar { c -> c.uppercase() }) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { SizeFilter.entries.forEach { s -> FilterChip(f.size == s, { vm.update { it.copy(size = s) } }, { Text(s.label) }) } }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { DateFilter.entries.forEach { d -> FilterChip(f.date == d, { vm.update { it.copy(date = d) } }, { Text(d.label) }) } }
                OutlinedTextField(f.ext, { e -> vm.update { it.copy(ext = e.take(8)) } }, Modifier.fillMaxWidth(), placeholder = { Text("Extension (e.g. zip)") }, singleLine = true)
            }
            if (r.downloads.isNotEmpty()) item { SectionHeader("Downloads (${r.downloads.size})") }
            items(r.downloads, key = { "d${it.id}" }) { d ->
                Column(Modifier.fillMaxWidth().clickable { app.nav.navigate(DetailsRoute(d.id)) }.padding(vertical = 6.dp)) {
                    Text(d.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Row { StatusChip(d.state); Text("  ${Formatters.bytes(d.totalBytes)} · ${d.host ?: ""}", style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (r.files.isNotEmpty()) item { SectionHeader("Files (${r.files.size})") }
            items(r.files, key = { "f${it.uri}" }) { file ->
                Column(Modifier.fillMaxWidth().clickable { app.nav.navigate(FilesRoute) }.padding(vertical = 6.dp)) {
                    Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text("${Formatters.bytes(file.sizeBytes)} · ${file.folder}", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (r.bookmarks.isNotEmpty()) item { SectionHeader("Bookmarks") }
            items(r.bookmarks, key = { "b${it.id}" }) { b -> Text(b.title, Modifier.fillMaxWidth().clickable { app.nav.navigate(BrowserRoute(b.url)) }.padding(vertical = 8.dp), maxLines = 1) }
            if (r.history.isNotEmpty()) item { SectionHeader("History") }
            items(r.history, key = { "h${it.id}" }) { h -> Text(h.title, Modifier.fillMaxWidth().clickable { app.nav.navigate(BrowserRoute(h.url)) }.padding(vertical = 8.dp), maxLines = 1) }
        }
    }
}
