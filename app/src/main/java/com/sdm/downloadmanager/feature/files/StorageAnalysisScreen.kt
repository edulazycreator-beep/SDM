package com.sdm.downloadmanager.feature.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.Formatters
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.files.FileRepository
import com.sdm.core.data.files.StorageAnalysis
import com.sdm.core.designsystem.component.AnimatedProgress
import com.sdm.core.designsystem.component.HorizontalBars
import com.sdm.core.designsystem.component.InfoRow
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.domain.DownloadState
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StorageAnalysisViewModel @Inject constructor(files: FileRepository, downloads: DownloadRepository) : ViewModel() {
    val analysis = combine(files.files, downloads.downloads) { f, d ->
        val incomplete = d.filter { it.state != DownloadState.COMPLETED && it.destinationUri != null }.sumOf { it.downloadedBytes }
        files.analysis(f, incomplete)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun StorageAnalysisScreen(vm: StorageAnalysisViewModel = hiltViewModel()) {
    val a by vm.analysis.collectAsState()
    val app = LocalAppActions.current
    SdmScaffold("Storage analysis", onBack = { app.nav.popBackStack() }) { p ->
        val s: StorageAnalysis = a ?: return@SdmScaffold
        Column(Modifier.padding(p).padding(horizontal = LocalSpacing.current.l).verticalScroll(rememberScrollState())) {
            SectionHeader("Device storage")
            AnimatedProgress(if (s.device.totalBytes > 0) s.device.usedBytes.toFloat() / s.device.totalBytes else 0f)
            Spacer(Modifier.height(6.dp))
            Text("${Formatters.bytes(s.device.usedBytes)} used · ${Formatters.bytes(s.device.freeBytes)} free of ${Formatters.bytes(s.device.totalBytes)}")
            SectionHeader("SDM files by category")
            HorizontalBars(s.byCategory.entries.sortedByDescending { it.value.second }.map { it.key.label to it.value.second.toFloat() }, valueLabel = { Formatters.bytes(it.toLong()) })
            Spacer(Modifier.height(12.dp))
            s.byCategory.entries.sortedByDescending { it.value.second }.forEach { (c, v) -> InfoRow(c.label, "${v.first} file(s) · ${Formatters.bytes(v.second)}") }
            SectionHeader("Totals")
            InfoRow("Managed files", Formatters.bytes(s.totalSdmBytes))
            InfoRow("Unfinished downloads", Formatters.bytes(s.incompleteBytes))
            Text("Unfinished parts are removed only when you cancel or delete those downloads; SDM never deletes files without asking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}
