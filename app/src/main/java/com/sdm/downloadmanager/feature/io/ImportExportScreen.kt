package com.sdm.downloadmanager.feature.io

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.data.io.ImportExportRepository
import com.sdm.core.designsystem.component.NavRow
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.component.SwitchRow
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ImportExportViewModel @Inject constructor(private val io: ImportExportRepository) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private fun run(block: suspend () -> String) = viewModelScope.launch { _message.value = runCatching { block() }.getOrElse { "Failed: ${it.message}" } }
    fun exportList(uri: Uri) = run { "Exported ${io.exportDownloads(uri)} download(s)" }
    fun exportHistory(uri: Uri) = run { "Exported ${io.exportHistory(uri)} row(s)" }
    fun import(uri: Uri, paused: Boolean) = run {
        val r = io.importFile(uri, paused)
        "Imported ${r.imported}" + (if (r.skippedDuplicates > 0) ", skipped ${r.skippedDuplicates} duplicate(s)" else "") + (if (r.rejected.isNotEmpty()) ", rejected ${r.rejected.size} invalid line(s)" else "")
    }
    fun backup(uri: Uri) = run { io.backupSettings(uri); "Settings, rules and schedule saved" }
    fun restore(uri: Uri) = run { "Restored settings and ${io.restoreSettings(uri)} rule(s)" }
    fun consume() { _message.value = null }
}

@Composable
fun ImportExportScreen(vm: ImportExportViewModel = hiltViewModel()) {
    val app = LocalAppActions.current
    val message by vm.message.collectAsState()
    var paused by remember { mutableStateOf(true) }
    LaunchedEffect(message) { message?.let { app.snackbar.showSnackbar(it); vm.consume() } }
    val today = LocalDate.now().toString()
    val exportList = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportList) }
    val exportHistory = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::exportHistory) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.import(u, paused) } }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::backup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restore) }
    SdmScaffold("Import, export and backup", onBack = { app.nav.popBackStack() }) { p ->
        Column(Modifier.padding(p).padding(horizontal = LocalSpacing.current.l).verticalScroll(rememberScrollState())) {
            SectionHeader("Import")
            NavRow("Import links", "TXT (one link per line) or an SDM JSON list", Icons.Outlined.FileUpload) { import.launch(arrayOf("text/plain", "application/json", "text/*")) }
            SwitchRow("Add imported downloads paused", paused, { paused = it }, "Imported links are checked when they start; they may have expired")
            SectionHeader("Export")
            NavRow("Export download list", "JSON that SDM can import again", Icons.Outlined.FileDownload) { exportList.launch("sdm-downloads-$today.json") }
            NavRow("Export history", "CSV for spreadsheets", Icons.Outlined.History) { exportHistory.launch("sdm-history-$today.csv") }
            Text("Exports never include cookies, sign-in headers or tokens in links.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader("Backup")
            NavRow("Back up settings", "Settings, smart rules and schedule", Icons.Outlined.Backup) { backup.launch("sdm-settings-$today.json") }
            NavRow("Restore settings", "Replaces current settings and rules", Icons.Outlined.Restore) { restore.launch(arrayOf("application/json", "text/*")) }
        }
    }
}
