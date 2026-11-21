package com.sdm.downloadmanager.feature.scheduler

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.data.execution.WakeupScheduler
import com.sdm.core.data.rules.QueueRepository
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.designsystem.component.InfoRow
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.component.SwitchRow
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.domain.scheduler.Conditions
import com.sdm.domain.scheduler.Schedule
import com.sdm.domain.scheduler.ScheduleProfile
import com.sdm.domain.scheduler.SchedulePolicy
import com.sdm.domain.scheduler.ScheduleVerdict
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SchedulerViewModel @Inject constructor(private val queues: QueueRepository, private val settings: SettingsRepository, private val wakeups: WakeupScheduler) : ViewModel() {
    val schedule = queues.queues.map { it.firstOrNull()?.schedule ?: Schedule() }.stateIn(viewModelScope, SharingStarted.Eagerly, Schedule())
    val exactAlarms = settings.settings.map { it.exactAlarms }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun canExact() = wakeups.canUseExactAlarms()
    fun update(f: (Schedule) -> Schedule) = viewModelScope.launch { queues.save(queues.main().let { it.copy(schedule = f(it.schedule)) }) }
    fun setExact(v: Boolean) = viewModelScope.launch { settings.update { it.copy(exactAlarms = v) } }
}

private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private fun minuteLabel(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SchedulerScreen(vm: SchedulerViewModel = hiltViewModel()) {
    val s by vm.schedule.collectAsState()
    val exact by vm.exactAlarms.collectAsState()
    val app = LocalAppActions.current
    val ctx = LocalContext.current
    var picker by remember { mutableStateOf<String?>(null) }
    SdmScaffold("Scheduler", onBack = { app.nav.popBackStack() }) { p ->
        Column(Modifier.padding(p).padding(horizontal = LocalSpacing.current.l).verticalScroll(rememberScrollState())) {
            SectionHeader("Profile")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScheduleProfile.entries.forEach { pr -> FilterChip(s.profile == pr, { vm.update { SchedulePolicy.preset(pr).copy(daysMask = it.daysMask) } }, { Text(pr.label) }) }
            }
            val verdict = SchedulePolicy.evaluate(s, System.currentTimeMillis(), Conditions(true, true, true, 100))
            Text(
                when (verdict) {
                    ScheduleVerdict.Allowed -> "Downloads may run now (subject to network and battery conditions)."
                    is ScheduleVerdict.Blocked -> verdict.reason + (verdict.nextWindowStart?.let { " · next start ${fmt.format(Instant.ofEpochMilli(it))}" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
            if (s.profile == ScheduleProfile.NIGHT || s.profile == ScheduleProfile.CUSTOM || (s.profile == ScheduleProfile.MANUAL && s.startAt == null)) {
                SectionHeader("Operating hours")
                InfoRow("Start", minuteLabel(s.startMinute), onClick = { picker = "start" })
                InfoRow("End", minuteLabel(s.endMinute), onClick = { picker = "end" })
                Text("Days", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayNames.forEachIndexed { i, d -> FilterChip(s.daysMask and (1 shl i) != 0, { vm.update { it.copy(daysMask = it.daysMask xor (1 shl i)) } }, { Text(d) }) }
                }
            }
            if (s.profile == ScheduleProfile.MANUAL) {
                SectionHeader("Scheduled start and pause")
                InfoRow("Start at", s.startAt?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: "Not set (uses hours above)", onClick = { picker = "startAt" })
                InfoRow("Pause at", s.stopAt?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: "Not set", onClick = { picker = "stopAt" })
                TextButton(onClick = { vm.update { it.copy(startAt = null, stopAt = null) } }) { Text("Clear dates") }
            }
            SectionHeader("Conditions")
            SwitchRow("Only on unmetered networks", s.requiresUnmetered || s.profile == ScheduleProfile.WIFI_ONLY, { v -> vm.update { it.copy(requiresUnmetered = v) } }, enabled = s.profile != ScheduleProfile.WIFI_ONLY)
            SwitchRow("Only while charging", s.requiresCharging, { v -> vm.update { it.copy(requiresCharging = v) } })
            Text("Minimum battery when not charging: ${s.minBatteryPercent}%")
            Slider(s.minBatteryPercent.toFloat(), { v -> vm.update { it.copy(minBatteryPercent = (v / 5).roundToInt() * 5) } }, valueRange = 0f..80f)
            SectionHeader("Starting on time")
            SwitchRow("Use exact alarms", exact, vm::setExact, "Starts scheduled downloads at the exact minute")
            val can = vm.canExact()
            Text(
                if (can) "Exact alarms are allowed." else "Android has not granted exact alarms to SDM, so scheduled starts happen in Android's next maintenance window (usually within minutes).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!can && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Allow exact alarms") }
            Spacer(Modifier.height(32.dp))
        }
    }
    picker?.let { which ->
        val initial = when (which) { "start" -> s.startMinute; "end" -> s.endMinute; else -> java.time.LocalTime.now().let { it.hour * 60 + it.minute } }
        val state = rememberTimePickerState(initial / 60, initial % 60, true)
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (which == "start" || which == "startAt") "Start time" else "End / pause time") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = state.hour * 60 + state.minute
                    var at = LocalDate.now().atTime(state.hour, state.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (at <= System.currentTimeMillis()) at += 24L * 3600 * 1000
                    vm.update {
                        when (which) {
                            "start" -> it.copy(startMinute = minute)
                            "end" -> it.copy(endMinute = minute)
                            "startAt" -> it.copy(startAt = at)
                            else -> it.copy(stopAt = it.startAt?.let { st -> if (at <= st) at + 24L * 3600 * 1000 else at } ?: at)
                        }
                    }
                    picker = null
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } },
        )
    }
}
