package com.sdm.downloadmanager.feature.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.Formatters
import com.sdm.core.data.analytics.AnalyticsRecorder
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.database.AnalyticsDao
import com.sdm.core.database.CategoryBytes
import com.sdm.core.database.DayTotal
import com.sdm.core.database.ThroughputSampleEntity
import com.sdm.core.designsystem.component.BarChart
import com.sdm.core.designsystem.component.ConfirmDialog
import com.sdm.core.designsystem.component.HorizontalBars
import com.sdm.core.designsystem.component.InfoRow
import com.sdm.core.designsystem.component.LineChart
import com.sdm.core.designsystem.component.RingProgress
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.component.StatTile
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.domain.intelligence.Recommendation
import com.sdm.domain.intelligence.SpeedIntelligence
import com.sdm.domain.intelligence.TransferObservation
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class AnalyticsUi(
    val currentBps: Long = 0,
    val samples: List<ThroughputSampleEntity> = emptyList(),
    val days: List<DayTotal> = emptyList(),
    val categories: List<CategoryBytes> = emptyList(),
    val observations: List<TransferObservation> = emptyList(),
    val recommendations: List<Pair<String, Recommendation>> = emptyList(),
) {
    private val sampleRates get() = samples.filter { it.intervalMillis > 0 }.map { it.bytes * 1000 / it.intervalMillis }
    val maxBps get() = (sampleRates.maxOrNull() ?: 0).coerceAtLeast(currentBps)
    val avgBps get() = observations.filter { it.activeMillis > 0 && it.bytes > 0 }.let { o -> if (o.isEmpty()) 0 else o.sumOf { it.bytes } * 1000 / o.sumOf { it.activeMillis } }
    val totalBytes get() = days.sumOf { it.bytes }
    val completed get() = days.sumOf { it.completed }
    val failed get() = days.sumOf { it.failed }
    val successRate get() = if (completed + failed == 0) null else completed.toFloat() / (completed + failed)
    val avgDurationMs get() = if (completed == 0) 0 else days.sumOf { it.durationMillis } / completed
    fun bytesSince(day: LocalDate) = days.filter { it.day >= day.toString() }.sumOf { it.bytes }
}

@HiltViewModel
class AnalyticsViewModel @Inject constructor(private val dao: AnalyticsDao, recorder: AnalyticsRecorder, repo: DownloadRepository, settings: SettingsRepository) : ViewModel() {
    val ui = combine(
        repo.live, dao.observeSamples(System.currentTimeMillis() - 24L * 3600 * 1000), dao.observeDays(LocalDate.now().minusDays(365).toString()),
        dao.observeCategories(), combine(recorder.observeObservations(), settings.settings) { o, s -> o to s },
    ) { live, samples, days, cats, (obs, s) ->
        val hosts = obs.groupBy { it.host }.entries.sortedByDescending { it.value.size }.take(5)
        AnalyticsUi(live.values.sumOf { it.speedBps }, samples, days, cats, obs, hosts.map { (h, _) -> h to SpeedIntelligence.recommend(h, obs, 16, s.speedProfile) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUi())

    fun clearStatistics() = viewModelScope.launch { dao.clearDaily(); dao.clearSamples() }
    fun clearIntelligence() = viewModelScope.launch { dao.clearObservations() }
}

@Composable
fun AnalyticsScreen(vm: AnalyticsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val app = LocalAppActions.current
    val sp = LocalSpacing.current
    var confirm by remember { mutableStateOf<String?>(null) }
    SdmScaffold("Analytics", onBack = { app.nav.popBackStack() }, actions = { IconButton(onClick = { confirm = "stats" }) { Icon(Icons.Outlined.DeleteSweep, "Clear analytics") } }) { p ->
        Column(Modifier.padding(p).padding(horizontal = sp.l).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(sp.s)) {
            Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                StatTile("Current", Formatters.speed(ui.currentBps), Modifier.weight(1f))
                StatTile("Average", Formatters.speed(ui.avgBps), Modifier.weight(1f))
                StatTile("Maximum", Formatters.speed(ui.maxBps), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                StatTile("Today", Formatters.bytes(ui.bytesSince(LocalDate.now())), Modifier.weight(1f))
                StatTile("7 days", Formatters.bytes(ui.bytesSince(LocalDate.now().minusDays(6))), Modifier.weight(1f))
                StatTile("30 days", Formatters.bytes(ui.bytesSince(LocalDate.now().minusDays(29))), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(sp.s)) {
                StatTile("Total", Formatters.bytes(ui.totalBytes), Modifier.weight(1f))
                StatTile("Completed", ui.completed.toString(), Modifier.weight(1f))
                StatTile("Failed", ui.failed.toString(), Modifier.weight(1f))
            }
            SectionHeader("Success rate")
            Row {
                RingProgress(ui.successRate ?: 0f, ui.successRate?.let { "${(it * 100).toInt()}%" } ?: "—")
                Column(Modifier.padding(start = 16.dp)) {
                    InfoRow("Average duration", Formatters.duration(ui.avgDurationMs / 1000))
                    Text("Counts every finished or failed transfer session.", style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionHeader("Download speed, last 24 hours")
            if (ui.samples.size < 2) Text("Speed samples appear while downloads run (one every 5 seconds).", style = MaterialTheme.typography.bodySmall)
            else LineChart(ui.samples.takeLast(240).map { (it.bytes * 1000f / it.intervalMillis.coerceAtLeast(1)) }, description = "Speed history")
            SectionHeader("Daily activity, last 14 days")
            val last14 = (13 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
            BarChart(last14.map { it.dayOfMonth.toString() }, last14.map { d -> ui.days.firstOrNull { it.day == d.toString() }?.bytes?.toFloat() ?: 0f }, description = "Daily downloaded bytes")
            SectionHeader("Monthly volume")
            val months = (11 downTo 0).map { LocalDate.now().withDayOfMonth(1).minusMonths(it.toLong()) }
            BarChart(months.map { it.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }, months.map { m -> ui.days.filter { it.day.startsWith(m.toString().take(7)) }.sumOf { it.bytes }.toFloat() }, description = "Monthly downloaded bytes")
            SectionHeader("By category")
            if (ui.categories.isEmpty()) Text("No completed downloads yet.", style = MaterialTheme.typography.bodySmall)
            else HorizontalBars(ui.categories.sortedByDescending { it.bytes }.map { it.category.label to it.bytes.toFloat() }, valueLabel = { Formatters.bytes(it.toLong()) })
            SectionHeader("Speed Intelligence", action = { IconButton(onClick = { confirm = "intel" }) { Icon(Icons.Outlined.DeleteSweep, "Reset Speed Intelligence") } })
            Text("Rule-based recommendations from your own measured downloads (no machine learning).", style = MaterialTheme.typography.bodySmall)
            if (ui.recommendations.isEmpty()) Text("Recommendations appear after downloads finish.", style = MaterialTheme.typography.bodySmall)
            ui.recommendations.forEach { (host, r) ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(host, style = MaterialTheme.typography.titleSmall)
                    Text("${r.connections} connection(s) · ${r.profile.label} profile · segment ≥ ${Formatters.bytes(r.minSegmentBytes)} · ${r.maxRetries} retries" + if (r.unstable) " · unstable" else "", style = MaterialTheme.typography.bodySmall)
                    Text(r.basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    when (confirm) {
        "stats" -> ConfirmDialog("Clear analytics?", "Deletes statistics and speed history. Download list and Speed Intelligence data stay.", "Clear", onConfirm = vm::clearStatistics, onDismiss = { confirm = null }, destructive = true)
        "intel" -> ConfirmDialog("Reset Speed Intelligence?", "Deletes measured transfer results used for recommendations.", "Reset", onConfirm = vm::clearIntelligence, onDismiss = { confirm = null }, destructive = true)
    }
}
