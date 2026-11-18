package com.sdm.downloadmanager.feature.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.Formatters
import com.sdm.core.data.device.NetworkMonitor
import com.sdm.core.data.diagnostics.DiagnosticsRepository
import com.sdm.core.data.diagnostics.PerformanceSnapshot
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.designsystem.component.InfoRow
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.theme.LocalStatusColors
import com.sdm.domain.DownloadState
import com.sdm.domain.ErrorCode
import com.sdm.downloadmanager.ui.DetailsRoute
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(repo: DownloadRepository, private val diagnostics: DiagnosticsRepository, network: NetworkMonitor) : ViewModel() {
    val failed = repo.downloads.map { l -> l.filter { it.state == DownloadState.FAILED || it.errorCode != null } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val problems = repo.problemEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val network = network.state
    suspend fun performance(): PerformanceSnapshot = diagnostics.performance()
}

private val fmt = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel = hiltViewModel()) {
    val failed by vm.failed.collectAsState()
    val problems by vm.problems.collectAsState()
    val net by vm.network.collectAsState()
    val app = LocalAppActions.current
    var perf by remember { mutableStateOf<PerformanceSnapshot?>(null) }
    LaunchedEffect(Unit) { while (true) { perf = vm.performance(); delay(2_000) } }
    SdmScaffold("Diagnostics", onBack = { app.nav.popBackStack() }) { p ->
        LazyColumn(Modifier.padding(p).padding(horizontal = 16.dp)) {
            item {
                SectionHeader("Performance (updates every 2 s)")
                perf?.let { s ->
                    InfoRow("Connection", net.label)
                    InfoRow("Active downloads", "${s.activeDownloads} · ${s.activeConnections} connection(s)")
                    InfoRow("Background mode", s.leaseMode)
                    InfoRow("Memory (app heap)", "${Formatters.bytes(s.heapUsedBytes)} of ${Formatters.bytes(s.heapMaxBytes)}")
                    InfoRow("Native memory", Formatters.bytes(s.nativeHeapBytes))
                    InfoRow("Device free memory", Formatters.bytes(s.deviceAvailableBytes) + if (s.lowMemory) " (low)" else "")
                    InfoRow("CPU (SDM)", s.cpuPercent?.let { "%.1f%%".format(it) } ?: "measuring…")
                    InfoRow("Segment failure rate", s.segmentFailureRate?.let { "%.1f%% (last 50 transfers)".format(it * 100) } ?: "No transfers yet")
                    InfoRow("Queue", "${s.queued} queued · ${s.waiting} waiting for network · ${s.failed} failed")
                    InfoRow("Database", "${Formatters.bytes(s.databaseBytes)} · query ${s.databaseQueryMillis} ms")
                }
                SectionHeader("Failed downloads")
                if (failed.isEmpty()) Text("No failed downloads.", style = MaterialTheme.typography.bodyMedium)
            }
            items(failed, key = { "f${it.id}" }) { d ->
                val code = d.errorCode ?: ErrorCode.UNKNOWN
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { app.nav.navigate(DetailsRoute(d.id)) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("${code.name} · ${code.title}" + (d.httpStatus?.let { " · HTTP $it" } ?: ""), color = LocalStatusColors.current.error, style = MaterialTheme.typography.bodySmall)
                        Text(code.description, style = MaterialTheme.typography.bodySmall)
                        Text("Suggestion: ${code.suggestion}", style = MaterialTheme.typography.bodySmall)
                        Text("Retries: ${d.retryCount} · open for the full report and copy button", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionHeader("Recent problems") }
            items(problems, key = { "e${it.id}" }) { e ->
                Column(Modifier.fillMaxWidth().clickable(enabled = e.downloadId != null) { e.downloadId?.let { app.nav.navigate(DetailsRoute(it)) } }.padding(vertical = 4.dp)) {
                    Text("${fmt.format(Instant.ofEpochMilli(e.timestamp))} · ${e.type.name.lowercase().replace('_', ' ')}" + (e.httpStatus?.let { " · HTTP $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                    e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { Text("Reports never contain cookies, passwords or authorization headers.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 24.dp)) }
        }
    }
}
