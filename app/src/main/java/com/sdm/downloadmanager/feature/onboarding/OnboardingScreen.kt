package com.sdm.downloadmanager.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.Button
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class Page(val icon: ImageVector, val title: String, val body: String)

private val pages = listOf(
    Page(Icons.Filled.Download, "Smart Download Manager", "Download Smarter. Faster. Better.\n\nSDM splits large files into parts and downloads them in parallel when the server allows it, pauses and resumes safely, and checks every file before calling it complete."),
    Page(Icons.Filled.Notifications, "Stay informed", "SDM shows progress, speed and remaining time in notifications, with pause and cancel buttons. Android asks for permission to show them.\n\nWithout it, downloads still run; you will see them in the system's task manager instead."),
    Page(Icons.Filled.Bolt, "Works in the background", "Android limits background work to save battery. SDM uses the system's long-running download jobs, so transfers keep going when you switch apps. If your phone stops them, open SDM and they continue from where they stopped."),
    Page(Icons.Filled.TipsAndUpdates, "Quick start", "• Copy a link: SDM offers to download it when you open the app.\n• Use the built-in browser: downloads carry your sign-in cookies.\n• Share a link to SDM from any app.\n• Set rules and schedules in Settings."),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { index++ }
    val page = pages[index.coerceAtMost(pages.lastIndex)]
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
                    Icon(page.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(page.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(page.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} / ${pages.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (index < pages.lastIndex) TextButton(onClick = onDone) { Text("Skip") }
                Button(onClick = {
                    when {
                        index == 1 && Build.VERSION.SDK_INT >= 33 -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        index < pages.lastIndex -> index++
                        else -> onDone()
                    }
                }) { Text(when { index == 1 && Build.VERSION.SDK_INT >= 33 -> "Allow notifications"; index < pages.lastIndex -> "Next"; else -> "Get started" }) }
            }
        }
    }
}
