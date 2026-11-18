package com.sdm.core.designsystem.component

import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdm.core.common.FileCategory
import com.sdm.core.designsystem.theme.LocalAnimationsEnabled
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.core.designsystem.theme.LocalStatusColors
import com.sdm.domain.DownloadState

fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.VIDEO -> Icons.Outlined.Movie
    FileCategory.MUSIC -> Icons.Outlined.Audiotrack
    FileCategory.IMAGE -> Icons.Outlined.Image
    FileCategory.DOCUMENT -> Icons.Outlined.Description
    FileCategory.ARCHIVE -> Icons.Outlined.Archive
    FileCategory.APPLICATION -> Icons.Outlined.Android
    FileCategory.OTHER -> Icons.Outlined.InsertDriveFile
}

@Composable
fun CategoryBadge(category: FileCategory, modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(category.icon(), contentDescription = category.label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size((size * 0.55).dp))
    }
}

data class StateStyle(val label: String, val color: Color)

@Composable
fun DownloadState.style(): StateStyle {
    val s = LocalStatusColors.current
    return when (this) {
        DownloadState.QUEUED -> StateStyle("Queued", s.neutral)
        DownloadState.PREPARING -> StateStyle("Preparing", s.info)
        DownloadState.DOWNLOADING -> StateStyle("Downloading", s.info)
        DownloadState.PAUSED -> StateStyle("Paused", s.warning)
        DownloadState.WAITING_FOR_NETWORK -> StateStyle("Waiting for network", s.warning)
        DownloadState.RETRYING -> StateStyle("Retrying", s.warning)
        DownloadState.VERIFYING -> StateStyle("Verifying", s.info)
        DownloadState.COMPLETED -> StateStyle("Completed", s.success)
        DownloadState.FAILED -> StateStyle("Failed", s.error)
        DownloadState.CANCELLED -> StateStyle("Cancelled", s.neutral)
    }
}

@Composable
fun StatusChip(state: DownloadState, modifier: Modifier = Modifier, labelOverride: String? = null) {
    val st = state.style()
    Surface(modifier, shape = RoundedCornerShape(50), color = st.color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(st.color))
            Spacer(Modifier.width(5.dp))
            Text(labelOverride ?: st.label, style = MaterialTheme.typography.labelSmall, color = st.color, maxLines = 1)
        }
    }
}

@Composable
fun AnimatedProgress(fraction: Float?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (fraction == null) {
        LinearProgressIndicator(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = color)
        return
    }
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), if (LocalAnimationsEnabled.current) tween(400) else tween(0), label = "progress")
    LinearProgressIndicator(
        progress = { animated }, modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null, accent: Color = MaterialTheme.colorScheme.primary, onClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(LocalSpacing.current.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false, extra: (@Composable () -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { Text(message); extra?.invoke() } },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = LocalSpacing.current.listItemVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A titled card that groups related settings rows. */
@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
        }
    }
}

@Composable
fun NavRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = LocalSpacing.current.listItemVertical), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Visualizes segment progress of a multi-connection download. */
@Composable
fun SegmentBar(total: Long, segments: List<Triple<Long, Long, Long>>, modifier: Modifier = Modifier) {
    val done = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val edge = MaterialTheme.colorScheme.surface
    Canvas(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).semantics { contentDescription = "${segments.size} segments" }) {
        drawRect(track)
        if (total <= 0) return@Canvas
        for ((start, end, pos) in segments) {
            val x0 = size.width * start / total
            val x1 = size.width * (minOf(pos, end + 1)) / total
            if (x1 > x0) drawRect(done, topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height))
            drawLine(edge, Offset(x0, 0f), Offset(x0, size.height), strokeWidth = 2f)
        }
    }
}

// ------------------------------------------------------------------ charts (no chart dependency)

@Composable
fun LineChart(values: List<Float>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, description: String = "Line chart") {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description }) {
        for (i in 0..3) { val y = size.height * i / 3; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * step, size.height - size.height * (v / max) * 0.92f)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
        val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, color.copy(alpha = 0.12f))
    }
}

@Composable
fun BarChart(labels: List<String>, values: List<Float>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, description: String = "Bar chart") {
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            if (values.isEmpty()) return@Canvas
            val max = values.max().coerceAtLeast(1f)
            val slot = size.width / values.size
            val w = slot * 0.62f
            values.forEachIndexed { i, v ->
                val h = size.height * (v / max)
                drawRoundRect(color, topLeft = Offset(i * slot + (slot - w) / 2, size.height - h), size = Size(w, h.coerceAtLeast(2f)), cornerRadius = CornerRadius(6f, 6f))
            }
        }
        if (labels.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1) }
        }
    }
}

@Composable
fun HorizontalBars(items: List<Pair<String, Float>>, valueLabel: (Float) -> String, modifier: Modifier = Modifier) {
    val max = items.maxOfOrNull { it.second }?.coerceAtLeast(1f) ?: 1f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(v / max).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
                }
                Text(valueLabel(v), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(72.dp).padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

@Composable
fun RingProgress(fraction: Float, label: String, modifier: Modifier = Modifier, color: Color = LocalStatusColors.current.success) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = label }) {
            val stroke = Stroke(width = 18f, cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, style = stroke)
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = stroke)
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
