package com.sdm.downloadmanager.feature.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.data.browser.BrowserRepository
import com.sdm.core.designsystem.component.ConfirmDialog
import com.sdm.core.designsystem.component.EmptyState
import com.sdm.downloadmanager.ui.BrowserRoute
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class BookmarksHistoryViewModel @Inject constructor(private val repo: BrowserRepository) : ViewModel() {
    val bookmarks = repo.bookmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun removeBookmark(url: String) = viewModelScope.launch { repo.removeBookmark(url) }
    fun deleteHistory(id: Long) = viewModelScope.launch { repo.deleteHistory(id) }
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }
}

private val fmt = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun BookmarksScreen(vm: BookmarksHistoryViewModel = hiltViewModel()) {
    val items by vm.bookmarks.collectAsState()
    val app = LocalAppActions.current
    SdmScaffold("Bookmarks", onBack = { app.nav.popBackStack() }) { p ->
        if (items.isEmpty()) EmptyState(Icons.Outlined.Bookmarks, "No bookmarks", "Tap the star in the browser to save a page.", Modifier.padding(p))
        else LazyColumn(Modifier.padding(p)) {
            items(items, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().clickable { app.nav.navigate(BrowserRoute(b.url)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(b.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.removeBookmark(b.url) }) { Icon(Icons.Outlined.Delete, "Remove bookmark") }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: BookmarksHistoryViewModel = hiltViewModel()) {
    val items by vm.history.collectAsState()
    val app = LocalAppActions.current
    var confirm by remember { mutableStateOf(false) }
    SdmScaffold("History", onBack = { app.nav.popBackStack() }, actions = { IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.DeleteSweep, "Clear history") } }) { p ->
        if (items.isEmpty()) EmptyState(Icons.Outlined.History, "No history", "Pages you visit (outside private tabs) appear here.", Modifier.padding(p))
        else LazyColumn(Modifier.padding(p)) {
            items(items, key = { it.id }) { h ->
                Row(Modifier.fillMaxWidth().clickable { app.nav.navigate(BrowserRoute(h.url)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text("${fmt.format(Instant.ofEpochMilli(h.lastVisited))} · ${h.url}", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.deleteHistory(h.id) }) { Icon(Icons.Outlined.Delete, "Delete entry") }
                }
            }
        }
    }
    if (confirm) ConfirmDialog("Clear history?", "Removes all browsing history.", "Clear", onConfirm = { vm.clearHistory() }, onDismiss = { confirm = false }, destructive = true)
}
