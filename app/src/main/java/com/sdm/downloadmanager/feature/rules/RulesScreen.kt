package com.sdm.downloadmanager.feature.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.FileCategory
import com.sdm.core.data.rules.RulesRepository
import com.sdm.domain.NetworkPolicy
import com.sdm.domain.rules.Rule
import com.sdm.domain.rules.RuleAction
import com.sdm.domain.rules.RuleCondition
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RulesViewModel @Inject constructor(private val repo: RulesRepository) : ViewModel() {
    val rules = repo.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(r: Rule) = viewModelScope.launch { repo.save(r) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun move(list: List<Rule>, index: Int, by: Int) = viewModelScope.launch {
        val j = index + by
        if (j !in list.indices) return@launch
        val reordered = list.toMutableList().apply { add(j, removeAt(index)) }
        reordered.forEachIndexed { i, r -> if (r.priority != (i + 1) * 10) repo.save(r.copy(priority = (i + 1) * 10)) }
    }
}

private enum class CondKind(val label: String) { SIZE("File size above"), EXT("Extension is"), CATEGORY("File type is"), HOST("Host is"), METERED("On mobile data"), UNMETERED("On Wi-Fi"), BATTERY("Battery below"), STORAGE("Storage below"), FAILED("Download fails") }
private enum class ActKind(val label: String) { ASK("Ask confirmation"), CATEGORY("Save to category folder"), POLICY("Set network policy"), LIMIT("Limit speed"), CONN("Set connections"), PAUSE("Pause downloads"), RESUME("Resume downloads"), NOTIFY("Notify"), RETRY("Retry with policy") }

@Composable
fun RulesScreen(vm: RulesViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsState()
    val app = LocalAppActions.current
    var editing by remember { mutableStateOf<Rule?>(null) }
    SdmScaffold("Smart rules", onBack = { app.nav.popBackStack() }, floatingActionButton = {
        FloatingActionButton(onClick = { editing = Rule(0, "New rule", true, (rules.size + 1) * 10, RuleCondition.SizeAbove(1L shl 30), RuleAction.AskConfirmation) }) { Icon(Icons.Filled.Add, "Add rule") }
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item { Text("Rules run top to bottom; for each kind of action the first matching rule wins.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
            itemsIndexed(rules, key = { _, r -> r.id }) { i, r ->
                Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.bodyLarge)
                        Text("IF ${r.condition.label}\nTHEN ${r.action.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.move(rules, i, -1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, "Higher priority") }
                    IconButton(onClick = { vm.move(rules, i, 1) }, enabled = i < rules.lastIndex) { Icon(Icons.Filled.ArrowDownward, "Lower priority") }
                    Switch(r.enabled, { vm.save(r.copy(enabled = it)) })
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { r -> RuleEditor(r, onSave = { vm.save(it); editing = null }, onDelete = { vm.delete(r.id); editing = null }, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(rule: Rule, onSave: (Rule) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(rule.name) }
    var cond by remember { mutableStateOf(when (rule.condition) { is RuleCondition.SizeAbove -> CondKind.SIZE; is RuleCondition.ExtensionIn -> CondKind.EXT; is RuleCondition.CategoryIs -> CondKind.CATEGORY; is RuleCondition.HostIs -> CondKind.HOST; RuleCondition.OnMeteredNetwork -> CondKind.METERED; RuleCondition.OnUnmeteredNetwork -> CondKind.UNMETERED; is RuleCondition.BatteryBelow -> CondKind.BATTERY; is RuleCondition.StorageBelow -> CondKind.STORAGE; RuleCondition.DownloadFailed -> CondKind.FAILED }) }
    var condValue by remember { mutableStateOf(when (val c = rule.condition) { is RuleCondition.SizeAbove -> (c.bytes shr 20).toString(); is RuleCondition.ExtensionIn -> c.extensions.joinToString(","); is RuleCondition.HostIs -> c.host; is RuleCondition.BatteryBelow -> c.percent.toString(); is RuleCondition.StorageBelow -> (c.bytes shr 20).toString(); else -> "" }) }
    var category by remember { mutableStateOf((rule.condition as? RuleCondition.CategoryIs)?.category ?: (rule.action as? RuleAction.SaveToCategoryFolder)?.category ?: FileCategory.VIDEO) }
    var act by remember { mutableStateOf(when (rule.action) { RuleAction.AskConfirmation -> ActKind.ASK; is RuleAction.SaveToCategoryFolder, is RuleAction.SaveToFolder -> ActKind.CATEGORY; is RuleAction.SetNetworkPolicy -> ActKind.POLICY; is RuleAction.SetSpeedLimit -> ActKind.LIMIT; is RuleAction.SetConnections -> ActKind.CONN; RuleAction.PauseDownloads -> ActKind.PAUSE; RuleAction.ResumeDownloads -> ActKind.RESUME; is RuleAction.Notify -> ActKind.NOTIFY; RuleAction.RetryWithPolicy -> ActKind.RETRY }) }
    var actValue by remember { mutableStateOf(when (val a = rule.action) { is RuleAction.SetSpeedLimit -> (a.bytesPerSecond / 1024).toString(); is RuleAction.SetConnections -> a.connections.toString(); is RuleAction.Notify -> a.message; else -> "" }) }
    var policy by remember { mutableStateOf((rule.action as? RuleAction.SetNetworkPolicy)?.policy ?: NetworkPolicy.UNMETERED_ONLY) }
    val number = condValue.toLongOrNull()
    val condition: RuleCondition? = when (cond) {
        CondKind.SIZE -> number?.let { RuleCondition.SizeAbove(it shl 20) }
        CondKind.EXT -> condValue.split(',', ' ').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { RuleCondition.ExtensionIn(it) }
        CondKind.CATEGORY -> RuleCondition.CategoryIs(category)
        CondKind.HOST -> condValue.trim().lowercase().takeIf { it.isNotEmpty() }?.let { RuleCondition.HostIs(it) }
        CondKind.METERED -> RuleCondition.OnMeteredNetwork
        CondKind.UNMETERED -> RuleCondition.OnUnmeteredNetwork
        CondKind.BATTERY -> number?.toInt()?.takeIf { it in 1..99 }?.let { RuleCondition.BatteryBelow(it) }
        CondKind.STORAGE -> number?.let { RuleCondition.StorageBelow(it shl 20) }
        CondKind.FAILED -> RuleCondition.DownloadFailed
    }
    val action: RuleAction? = when (act) {
        ActKind.ASK -> RuleAction.AskConfirmation
        ActKind.CATEGORY -> RuleAction.SaveToCategoryFolder(category)
        ActKind.POLICY -> RuleAction.SetNetworkPolicy(policy)
        ActKind.LIMIT -> actValue.toLongOrNull()?.let { RuleAction.SetSpeedLimit(it * 1024) }
        ActKind.CONN -> actValue.toIntOrNull()?.takeIf { it in 1..16 }?.let { RuleAction.SetConnections(it) }
        ActKind.PAUSE -> RuleAction.PauseDownloads
        ActKind.RESUME -> RuleAction.ResumeDownloads
        ActKind.NOTIFY -> actValue.takeIf { it.isNotBlank() }?.let { RuleAction.Notify(it) }
        ActKind.RETRY -> RuleAction.RetryWithPolicy
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                Text("IF", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { CondKind.entries.forEach { k -> FilterChip(cond == k, { cond = k }, { Text(k.label) }) } }
                when (cond) {
                    CondKind.SIZE, CondKind.STORAGE -> OutlinedTextField(condValue, { condValue = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Megabytes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    CondKind.BATTERY -> OutlinedTextField(condValue, { condValue = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text("Percent") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    CondKind.EXT -> OutlinedTextField(condValue, { condValue = it }, Modifier.fillMaxWidth(), label = { Text("Extensions, e.g. apk, zip") })
                    CondKind.HOST -> OutlinedTextField(condValue, { condValue = it }, Modifier.fillMaxWidth(), label = { Text("Host, e.g. example.com") })
                    CondKind.CATEGORY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FileCategory.entries.forEach { c -> FilterChip(category == c, { category = c }, { Text(c.label) }) } }
                    else -> Unit
                }
                Text("THEN", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ActKind.entries.forEach { k -> FilterChip(act == k, { act = k }, { Text(k.label) }) } }
                when (act) {
                    ActKind.CATEGORY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FileCategory.entries.forEach { c -> FilterChip(category == c, { category = c }, { Text(c.folder) }) } }
                    ActKind.POLICY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NetworkPolicy.entries.forEach { n -> FilterChip(policy == n, { policy = n }, { Text(n.name.lowercase().replace('_', ' ')) }) } }
                    ActKind.LIMIT -> OutlinedTextField(actValue, { actValue = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("KB/s") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    ActKind.CONN -> OutlinedTextField(actValue, { actValue = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text("Connections (1-16)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    ActKind.NOTIFY -> OutlinedTextField(actValue, { actValue = it }, Modifier.fillMaxWidth(), label = { Text("Message") })
                    else -> Unit
                }
                if (condition == null || action == null) Text("Fill in the values above.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = condition != null && action != null, onClick = { onSave(rule.copy(name = name, condition = condition!!, action = action!!)) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (rule.id != 0L) TextButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null); Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
