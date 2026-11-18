package com.sdm.downloadmanager.feature.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.Formatters
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.rules.QueueRepository
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.designsystem.component.CategoryBadge
import com.sdm.core.designsystem.component.SwitchRow
import com.sdm.core.designsystem.component.StatusChip
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.domain.Download
import com.sdm.domain.DownloadState
import com.sdm.domain.LiveProgress
import com.sdm.domain.intelligence.QueueIntelligence
import com.sdm.domain.intelligence.QueueItem
import com.sdm.domain.intelligence.QueueOrdering
import com.sdm.downloadmanager.feature.downloads.detailLine
import com.sdm.downloadmanager.feature.downloads.holdLabel
import com.sdm.downloadmanager.ui.DetailsRoute
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class QueueUi(
    val active: List<Download> = emptyList(),
    val waiting: List<Download> = emptyList(),
    val live: Map<Long, LiveProgress> = emptyMap(),
    val ordering: QueueOrdering = QueueOrdering.MANUAL,
    val paused: Boolean = false,
    val maxConcurrent: Int = 3,
    val etaSeconds: Long? = null,
    val remainingBytes: Long? = null,
)

@HiltViewModel
class QueueViewModel @Inject constructor(private val repo: DownloadRepository, private val queues: QueueRepository, private val settings: SettingsRepository) : ViewModel() {
    val ui = combine(repo.downloads, repo.live, queues.queues, settings.settings) { all, live, qs, s ->
        val main = qs.firstOrNull()
        val ordering = main?.ordering ?: QueueOrdering.MANUAL
        val active = all.filter { it.state.isActive }
        val waitingRaw = all.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.WAITING_FOR_NETWORK }
        val ordered = QueueIntelligence.order(waitingRaw.map { QueueItem(it.id, it.priority.weight, it.queuePosition, it.totalBytes?.minus(it.downloadedBytes), it.createdAt, it.important) }, ordering)
            .mapNotNull { q -> waitingRaw.firstOrNull { it.id == q.id } }
        val remaining = (active + ordered).map { d -> d.totalBytes?.let { it - (live[d.id]?.downloadedBytes ?: d.downloadedBytes) } }
        val speed = live.values.sumOf { it.speedBps }
        QueueUi(active, ordered, live, ordering, main?.paused ?: false, s.maxConcurrent, QueueIntelligence.estimateSeconds(remaining, speed), if (remaining.any { it == null }) null else remaining.sumOf { it ?: 0 })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUi())

    fun setOrdering(o: QueueOrdering) = viewModelScope.launch { queues.save(queues.main().copy(ordering = o)) }
    fun setPaused(p: Boolean) = viewModelScope.launch { queues.save(queues.main().copy(paused = p)); if (p) repo.pauseAll() }
    fun setMax(n: Int) = viewModelScope.launch { settings.update { it.copy(maxConcurrent = n) } }
    fun reorder(ids: List<Long>) = viewModelScope.launch { repo.reorder(ids); if (ui.value.ordering != QueueOrdering.MANUAL) setOrdering(QueueOrdering.MANUAL) }
    fun pause(id: Long) = viewModelScope.launch { repo.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { repo.resume(id) }
    fun important(id: Long, v: Boolean) = viewModelScope.launch { repo.setImportant(id, v) }
}

@Composable
fun QueueScreen(vm: QueueViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val app = LocalAppActions.current
    val sp = LocalSpacing.current
    val order = remember { mutableStateListOf<Download>() }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var itemHeight by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(ui.waiting) { if (dragging == null) { order.clear(); order.addAll(ui.waiting) } }

    SdmScaffold(title = "Download queue", onBack = { app.nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = sp.l)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(sp.m)) {
                        Text("Queue status", style = MaterialTheme.typography.titleSmall)
                        Text("${ui.active.size} running · ${ui.waiting.size} waiting · ${Formatters.speed(ui.live.values.sumOf { it.speedBps })}")
                        Text(
                            ui.etaSeconds?.let { "Estimated completion: ${Formatters.duration(it)} (estimate from current measured speed)" }
                                ?: if (ui.remainingBytes == null) "Estimate unavailable: some file sizes are unknown" else "Estimate available once downloads are running",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SwitchRow("Pause queue", ui.paused, vm::setPaused, "No new downloads start while the queue is paused")
                        Text("Simultaneous downloads: ${ui.maxConcurrent}", style = MaterialTheme.typography.bodyMedium)
                        Slider(ui.maxConcurrent.toFloat(), { vm.setMax(it.roundToInt()) }, valueRange = 1f..10f, steps = 8)
                        Text("Order", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QueueOrdering.entries.forEach { o -> FilterChip(ui.ordering == o, { vm.setOrdering(o) }, { Text(o.label) }) }
                        }
                        Text("Long-press the handle and drag to reorder. Starred downloads always go first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (ui.active.isNotEmpty()) item { Text("Running", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            ui.active.forEach { d -> item(key = "a${d.id}") { QueueRow(d, ui.live[d.id], null, Modifier, onOpen = { app.nav.navigate(DetailsRoute(d.id)) }, onToggle = { vm.pause(d.id) }, onStar = { vm.important(d.id, !it) }) } }
            item { Text("Waiting", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            if (order.isEmpty()) item { Text("Nothing is waiting.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(order, key = { _, d -> "w${d.id}" }) { index, d ->
                val isDragged = dragging == d.id
                val handle = Modifier.pointerInput(d.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = d.id; dragOffset = 0f; app.haptic() },
                        onDragEnd = { dragging = null; dragOffset = 0f; vm.reorder(order.map { it.id }) },
                        onDragCancel = { dragging = null; dragOffset = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            val cur = order.indexOfFirst { it.id == d.id }
                            if (dragOffset > itemHeight * 0.6f && cur < order.lastIndex) { order.add(cur + 1, order.removeAt(cur)); dragOffset -= itemHeight }
                            else if (dragOffset < -itemHeight * 0.6f && cur > 0) { order.add(cur - 1, order.removeAt(cur)); dragOffset += itemHeight }
                        },
                    )
                }
                QueueRow(
                    d, null, handle,
                    Modifier.onSizeChanged { itemHeight = it.height.toFloat().coerceAtLeast(1f) }
                        .then(if (isDragged) Modifier.offset { IntOffset(0, dragOffset.roundToInt()) }.background(MaterialTheme.colorScheme.primaryContainer) else Modifier),
                    onOpen = { app.nav.navigate(DetailsRoute(d.id)) }, onToggle = { vm.pause(d.id) }, onStar = { vm.important(d.id, !it) }, position = index + 1,
                )
            }
            item { Spacer(Modifier.padding(24.dp)) }
        }
    }
}

@Composable
private fun QueueRow(d: Download, live: LiveProgress?, handle: Modifier?, modifier: Modifier, onOpen: () -> Unit, onToggle: () -> Unit, onStar: (Boolean) -> Unit, position: Int? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (handle != null) Icon(Icons.Filled.DragHandle, "Drag to reorder", handle.padding(end = 8.dp))
        CategoryBadge(d.category, size = 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text((position?.let { "$it. " } ?: "") + d.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(d.state, labelOverride = holdLabel(d))
                Spacer(Modifier.width(6.dp))
                Text(detailLine(d, live), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        val important = d.important
        IconButton(onClick = { onStar(important) }) { Icon(if (important) Icons.Filled.Star else Icons.Filled.StarBorder, "Important") }
        IconButton(onClick = onToggle) { Icon(if (d.state.isActive || d.state == DownloadState.QUEUED) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Pause or resume") }
        IconButton(onClick = onOpen) { Text("›", style = MaterialTheme.typography.titleLarge) }
    }
}
