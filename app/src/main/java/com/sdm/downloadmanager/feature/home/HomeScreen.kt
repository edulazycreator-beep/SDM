package com.sdm.downloadmanager.feature.home

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.Formatters
import com.sdm.core.common.Urls
import com.sdm.core.data.device.NetworkMonitor
import com.sdm.core.data.device.NetworkState
import com.sdm.core.data.device.StorageInfo
import com.sdm.core.data.device.StorageStats
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.settings.DashboardWidget
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.designsystem.component.AnimatedProgress
import com.sdm.core.designsystem.component.CategoryBadge
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.component.StatTile
import com.sdm.core.designsystem.component.StatusChip
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.core.designsystem.theme.LocalStatusColors
import com.sdm.domain.Download
import com.sdm.domain.DownloadState
import com.sdm.domain.LiveProgress
import com.sdm.downloadmanager.ui.AddRequest
import com.sdm.downloadmanager.ui.AnalyticsRoute
import com.sdm.downloadmanager.ui.BrowserRoute
import com.sdm.downloadmanager.ui.DetailsRoute
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.QueueRoute
import com.sdm.downloadmanager.ui.SdmScaffold
import com.sdm.downloadmanager.ui.SearchRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeState(
    val counts: Map<DownloadState, Int> = emptyMap(),
    val live: Map<Long, LiveProgress> = emptyMap(),
    val completedBytes: Long = 0,
    val storage: StorageStats = StorageStats(0, 0),
    val network: NetworkState = NetworkState.OFFLINE,
    val recent: List<Download> = emptyList(),
    val widgets: List<DashboardWidget> = DashboardWidget.entries,
) {
    val active get() = (counts[DownloadState.DOWNLOADING] ?: 0) + (counts[DownloadState.PREPARING] ?: 0) + (counts[DownloadState.VERIFYING] ?: 0) + (counts[DownloadState.RETRYING] ?: 0)
    val speed get() = live.values.sumOf { it.speedBps }
}

@HiltViewModel
class HomeViewModel @Inject constructor(repo: DownloadRepository, network: NetworkMonitor, storage: StorageInfo, settings: SettingsRepository) : ViewModel() {
    private val storageFlow = flow { while (true) { emit(storage.primary()); delay(30_000) } }
    val state = combine(
        combine(repo.counts, repo.live, repo.completedBytes) { c, l, b -> Triple(c, l, b) },
        storageFlow, network.state, repo.recent(6), settings.settings,
    ) { (c, l, b), s, n, r, st -> HomeState(c, l, b, s, n, r, st.dashboardWidgets) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val app = LocalAppActions.current
    val sp = LocalSpacing.current
    var multiDialog by remember { mutableStateOf(false) }
    SdmScaffold(
        title = "Smart Download Manager",
        actions = { IconButton(onClick = { app.nav.navigate(SearchRoute) }) { Icon(Icons.Outlined.Search, "Search") } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = sp.l), verticalArrangement = Arrangement.spacedBy(sp.m)) {
            s.widgets.forEach { w ->
                when (w) {
                    DashboardWidget.STATUS -> item("status") {
                        val status = LocalStatusColors.current
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                            StatTile("Active", s.active.toString(), Modifier.weight(1f), Icons.Outlined.Download, onClick = { app.nav.navigate(QueueRoute) })
                            StatTile("Completed", (s.counts[DownloadState.COMPLETED] ?: 0).toString(), Modifier.weight(1f), Icons.Outlined.CheckCircle, status.success)
                        }
                        Spacer(Modifier.height(sp.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                            StatTile("Paused", ((s.counts[DownloadState.PAUSED] ?: 0) + (s.counts[DownloadState.WAITING_FOR_NETWORK] ?: 0)).toString(), Modifier.weight(1f), Icons.Outlined.PauseCircle, status.warning)
                            StatTile("Failed", (s.counts[DownloadState.FAILED] ?: 0).toString(), Modifier.weight(1f), Icons.Outlined.Error, status.error)
                        }
                    }
                    DashboardWidget.SPEED -> item("speed") {
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                            StatTile("Overall speed", Formatters.speed(s.speed), Modifier.weight(1f), Icons.Outlined.Speed, onClick = { app.nav.navigate(AnalyticsRoute) })
                            StatTile("Total downloaded", Formatters.bytes(s.completedBytes), Modifier.weight(1f), Icons.Outlined.BarChart)
                        }
                    }
                    DashboardWidget.STORAGE -> item("storage") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(sp.m)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.SdStorage, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Available storage", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    Text("${Formatters.bytes(s.storage.freeBytes)} free of ${Formatters.bytes(s.storage.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(Modifier.height(8.dp))
                                AnimatedProgress(if (s.storage.totalBytes > 0) s.storage.usedBytes.toFloat() / s.storage.totalBytes else 0f)
                            }
                        }
                    }
                    DashboardWidget.NETWORK -> item("network") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Row(Modifier.fillMaxWidth().padding(sp.m), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (s.network.connected) Icons.Outlined.Wifi else Icons.Outlined.WifiOff, null, tint = if (s.network.connected) LocalStatusColors.current.success else LocalStatusColors.current.error)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Network", style = MaterialTheme.typography.labelLarge)
                                    Text(s.network.label + if (s.network.connected && !s.network.validated) " · no internet verified" else "", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    DashboardWidget.QUICK_ACTIONS -> item("actions") {
                        Column {
                            SectionHeader("Quick actions")
                            // Three primary actions; the rest stay one tap away in "More".
                            Row(horizontalArrangement = Arrangement.spacedBy(sp.s), modifier = Modifier.fillMaxWidth()) {
                                QuickAction("Paste link", Icons.Outlined.ContentPaste, Modifier.weight(1f), primary = true, onClick = app.pasteFromClipboard)
                                QuickAction("Browser", Icons.Outlined.Language, Modifier.weight(1f)) { app.nav.navigate(BrowserRoute()) }
                                QuickAction("Add", Icons.Outlined.Add, Modifier.weight(1f)) { app.requestAdd(AddRequest(emptyList())) }
                            }
                            var more by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { more = true }) { Text("More actions") }
                                DropdownMenu(more, { more = false }) {
                                    DropdownMenuItem(text = { Text("Add multiple URLs") }, leadingIcon = { Icon(Icons.Outlined.PlaylistAdd, null) }, onClick = { more = false; multiDialog = true })
                                    DropdownMenuItem(text = { Text("Download queue") }, leadingIcon = { Icon(Icons.Outlined.Queue, null) }, onClick = { more = false; app.nav.navigate(QueueRoute) })
                                    DropdownMenuItem(text = { Text("Send links from a computer") }, leadingIcon = { Icon(Icons.Outlined.Computer, null) }, onClick = { more = false; app.nav.navigate(com.sdm.downloadmanager.ui.LanReceiveRoute) })
                                    DropdownMenuItem(text = { Text("Analytics") }, leadingIcon = { Icon(Icons.Outlined.BarChart, null) }, onClick = { more = false; app.nav.navigate(AnalyticsRoute) })
                                }
                            }
                        }
                    }
                    DashboardWidget.RECENT -> {
                        item("recent-h") { SectionHeader("Recent activity") }
                        if (s.recent.isEmpty()) item("recent-empty") {
                            Text("No downloads yet. Paste a link, or open the browser and play a video.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        s.recent.forEach { d ->
                            item("r${d.id}") { RecentRow(d, s.live[d.id]) { app.nav.navigate(DetailsRoute(d.id)) } }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (multiDialog) MultiUrlDialog(onDismiss = { multiDialog = false }, onSubmit = { text -> app.requestAdd(AddRequest(Urls.extract(text))) })
}

@Composable
private fun RecentRow(d: Download, live: LiveProgress?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(d.category, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(d.state)
                Spacer(Modifier.width(6.dp))
                Text(live?.let { Formatters.speed(it.speedBps) } ?: Formatters.bytes(d.totalBytes ?: d.downloadedBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MultiUrlDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val count = Urls.extract(text).size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add multiple URLs") },
        text = {
            Column {
                Text("Paste links separated by new lines or spaces. Each link is checked before it is added.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(180.dp), placeholder = { Text("https://…") })
                Text("$count valid link(s)", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(enabled = count > 0, onClick = { onSubmit(text); onDismiss() }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val colors = if (primary) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    Card(onClick = onClick, modifier = modifier.heightIn(min = 72.dp).semantics(mergeDescendants = true) { contentDescription = label }, colors = colors) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
