package com.sdm.downloadmanager.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.FilterChip
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdm.core.common.Formatters
import com.sdm.core.data.settings.AppSettings
import com.sdm.core.data.settings.DashboardWidget
import com.sdm.core.data.settings.DnsMode
import com.sdm.core.data.settings.LayoutDensity
import com.sdm.core.data.settings.ProxyType
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.data.settings.SpeedLimitProfile
import com.sdm.core.data.settings.ThemeMode
import com.sdm.core.designsystem.component.InfoRow
import com.sdm.core.designsystem.component.NavRow
import com.sdm.core.designsystem.component.SectionHeader
import com.sdm.core.designsystem.component.SettingsGroup
import com.sdm.core.designsystem.component.SwitchRow
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.core.designsystem.theme.SdmColors
import com.sdm.domain.CONNECTION_PROFILES
import com.sdm.domain.NetworkPolicy
import com.sdm.domain.intelligence.SpeedProfile
import com.sdm.downloadmanager.BuildConfig
import com.sdm.downloadmanager.ui.AboutRoute
import com.sdm.downloadmanager.ui.AnalyticsRoute
import com.sdm.downloadmanager.ui.AppearanceRoute
import com.sdm.downloadmanager.ui.DiagnosticsRoute
import com.sdm.downloadmanager.ui.DownloadSettingsRoute
import com.sdm.downloadmanager.ui.ImportExportRoute
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.NetworkSettingsRoute
import com.sdm.downloadmanager.ui.RulesRoute
import com.sdm.downloadmanager.ui.SchedulerRoute
import com.sdm.downloadmanager.ui.SdmScaffold
import com.sdm.downloadmanager.ui.StorageAnalysisRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository, premiumRepo: com.sdm.downloadmanager.premium.PremiumRepository) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val premium = premiumRepo.state
    fun update(f: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(f) }
}

@Composable
private fun SettingsPage(title: String, root: Boolean = false, content: @Composable () -> Unit) {
    val app = LocalAppActions.current
    SdmScaffold(title, onBack = if (root) null else { { app.nav.popBackStack(); Unit } }) { p ->
        Column(Modifier.padding(p).padding(horizontal = LocalSpacing.current.l).verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val app = LocalAppActions.current
    val premium by vm.premium.collectAsState()
    SettingsPage("Settings", root = true) {
        if (!premium.includedInBuild) PremiumBanner(premium.unlocked) { app.nav.navigate(com.sdm.downloadmanager.ui.PremiumRoute) }
        SettingsGroup("General") {
            NavRow("Appearance", "${s.themeMode.name.lowercase().replaceFirstChar { it.uppercase() }} theme · ${if (s.density == LayoutDensity.COMPACT) "Compact" else "Comfortable"}", Icons.Outlined.Palette) { app.nav.navigate(AppearanceRoute) }
            NavRow("Downloads", "${s.defaultConnections} connections · ${s.maxConcurrent} at once · ${s.speedLimitProfile.label}", Icons.Outlined.Download) { app.nav.navigate(DownloadSettingsRoute) }
            NavRow("Network", "${s.networkPolicy.name.lowercase().replace('_', ' ')} · ${s.dnsMode.label}", Icons.Outlined.NetworkCheck) { app.nav.navigate(NetworkSettingsRoute) }
            SwitchRow("Detect copied links", s.clipboardDetection, { v -> vm.update { it.copy(clipboardDetection = v) } }, "Checks the clipboard only when SDM is open")
        }
        SettingsGroup("Automation") {
            NavRow("Scheduler", "Profiles, operating hours and conditions", Icons.Outlined.Schedule) { app.nav.navigate(SchedulerRoute) }
            NavRow("Smart rules", "Automatic actions for files and network changes", Icons.Outlined.Rule) { app.nav.navigate(RulesRoute) }
        }
        SettingsGroup("Insights") {
            NavRow("Analytics and Speed Intelligence", "Statistics and connection recommendations", Icons.Outlined.Analytics) { app.nav.navigate(AnalyticsRoute) }
            NavRow("Storage analysis", null, Icons.Outlined.Storage) { app.nav.navigate(StorageAnalysisRoute) }
            NavRow("Diagnostics", "Errors, reports and performance", Icons.Outlined.BugReport) { app.nav.navigate(DiagnosticsRoute) }
        }
        SettingsGroup("Data") {
            NavRow("Import, export and backup", null, Icons.Outlined.ImportExport) { app.nav.navigate(ImportExportRoute) }
            NavRow("About SDM", "Version ${BuildConfig.VERSION_NAME}", Icons.Outlined.Info) { app.nav.navigate(AboutRoute) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val premium = vm.premium.collectAsState().value.unlocked
    val app = LocalAppActions.current
    val openPremium = { app.nav.navigate(com.sdm.downloadmanager.ui.PremiumRoute) }
    SettingsPage("Appearance") {
        SectionHeader("Language")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system" to "System", "en" to "English", "id" to "Bahasa Indonesia").forEach { (code, label) ->
                FilterChip(s.language == code, { vm.update { it.copy(language = code) } }, { Text(label) })
            }
        }
        SectionHeader("Theme")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { m ->
                val locked = m == ThemeMode.AMOLED && !premium
                FilterChip(
                    s.themeMode == m && !locked,
                    { if (locked) openPremium() else vm.update { it.copy(themeMode = m) } },
                    { Text(when (m) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark"; ThemeMode.AMOLED -> "AMOLED black" }) },
                    leadingIcon = if (locked) { { Icon(Icons.Outlined.Lock, "Premium", Modifier.size(16.dp)) } } else null,
                )
            }
        }
        SwitchRow(
            "Dynamic color", premium && s.dynamicColor,
            { v -> if (!premium) openPremium() else vm.update { it.copy(dynamicColor = v) } },
            when { Build.VERSION.SDK_INT < 31 -> "Requires Android 12"; !premium -> "Premium · Use colors from your wallpaper"; else -> "Use colors from your wallpaper" },
            enabled = Build.VERSION.SDK_INT >= 31,
        )
        Text("Accent color", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            SdmColors.accents.forEach { a ->
                val locked = !premium && a != SdmColors.accents.first()
                val selected = if (premium) s.accentArgb == a && !s.dynamicColor else a == SdmColors.accents.first()
                Box(
                    Modifier.size(36.dp).background(Color(a.toInt()), CircleShape)
                        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { if (locked) openPremium() else vm.update { it.copy(accentArgb = a, dynamicColor = false) } },
                    contentAlignment = Alignment.Center,
                ) { if (locked) Icon(Icons.Outlined.Lock, "Premium", Modifier.size(16.dp), tint = Color.White) }
            }
        }
        if (!premium) Text("More colors, wallpaper colors and AMOLED black come with SDM Premium.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionHeader("Layout")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(s.density == LayoutDensity.COMFORTABLE, { vm.update { it.copy(density = LayoutDensity.COMFORTABLE) } }, { Text("Comfortable") })
            FilterChip(s.density == LayoutDensity.COMPACT, { vm.update { it.copy(density = LayoutDensity.COMPACT) } }, { Text("Compact") })
        }
        SwitchRow("Haptic feedback", s.haptics, { v -> vm.update { it.copy(haptics = v) } })
        SwitchRow("Animations", s.animations, { v -> vm.update { it.copy(animations = v) } }, "Turn off to reduce motion")
        SectionHeader("Dashboard widgets")
        Text("Show, hide and reorder the cards on Home.", style = MaterialTheme.typography.bodySmall)
        val enabled = s.dashboardWidgets
        val ordered = enabled + DashboardWidget.entries.filter { it !in enabled }
        ordered.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SwitchRow(w.label, w in enabled, { on -> vm.update { st -> st.copy(dashboardWidgets = if (on) st.dashboardWidgets + w else st.dashboardWidgets - w) } }) }
                IconButton(onClick = { vm.update { st -> st.copy(dashboardWidgets = st.dashboardWidgets.move(w, -1)) } }, enabled = w in enabled) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
                IconButton(onClick = { vm.update { st -> st.copy(dashboardWidgets = st.dashboardWidgets.move(w, 1)) } }, enabled = w in enabled) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
            }
        }
    }
}

private fun <T> List<T>.move(item: T, by: Int): List<T> {
    val i = indexOf(item); val j = i + by
    if (i < 0 || j !in indices) return this
    return toMutableList().apply { add(j, removeAt(i)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadSettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    var custom by remember(s.customLimitBps) { mutableStateOf((s.customLimitBps / 1024).toString()) }
    val premium = com.sdm.downloadmanager.ui.LocalPremium.current
    val app = LocalAppActions.current
    val openPremium = { app.nav.navigate(com.sdm.downloadmanager.ui.PremiumRoute) }
    val freeConcurrent = com.sdm.core.common.Entitlements.FREE_MAX_CONCURRENT
    SettingsPage("Downloads") {
        SectionHeader("Connections")
        Text("Default connections per download", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CONNECTION_PROFILES.forEach { n ->
                val locked = !premium && n > com.sdm.core.common.Entitlements.FREE_MAX_CONNECTIONS
                FilterChip(
                    s.defaultConnections == n && !locked, { if (locked) openPremium() else vm.update { it.copy(defaultConnections = n) } }, { Text("$n") },
                    leadingIcon = if (locked) { { Icon(Icons.Outlined.Lock, "Premium", Modifier.size(14.dp)) } } else null,
                )
            }
        }
        Text("More connections are not always faster. SDM only uses several when the server supports ranges.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Speed profile", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SpeedProfile.entries.forEach { p -> FilterChip(s.speedProfile == p, { vm.update { it.copy(speedProfile = p) } }, { Text(p.label) }) } }
        SwitchRow("Speed Intelligence picks connections", s.autoConnections, { v -> vm.update { it.copy(autoConnections = v) } }, "Uses measured results per server, never above the download's limit")
        val shownConcurrent = com.sdm.core.common.Entitlements.maxConcurrent(s.maxConcurrent)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Simultaneous downloads: $shownConcurrent", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (!premium) com.sdm.downloadmanager.ui.PremiumTag()
        }
        Slider(shownConcurrent.toFloat(), { v ->
            val n = v.roundToInt()
            if (!premium && n > freeConcurrent) openPremium() else vm.update { it.copy(maxConcurrent = n) }
        }, valueRange = 1f..10f, steps = 8)
        if (!premium) Text("Up to $freeConcurrent at once; Premium allows up to 10.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionHeader("Speed limit")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SpeedLimitProfile.entries.forEach { p -> FilterChip(s.speedLimitProfile == p, { vm.update { it.copy(speedLimitProfile = p) } }, { Text(p.label) }) } }
        if (s.speedLimitProfile == SpeedLimitProfile.CUSTOM) OutlinedTextField(
            custom, { v -> custom = v.filter(Char::isDigit).take(7); custom.toLongOrNull()?.let { kb -> vm.update { it.copy(customLimitBps = kb * 1024) } } },
            Modifier.fillMaxWidth(), label = { Text("Custom limit (KB/s)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text("Applies to all downloads together. Each download can also have its own limit in its details.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionHeader("Reliability")
        Text("Automatic retries: ${s.retryMaxAttempts}", style = MaterialTheme.typography.bodyLarge)
        Slider(s.retryMaxAttempts.toFloat(), { v -> vm.update { it.copy(retryMaxAttempts = v.roundToInt()) } }, valueRange = 0f..20f, steps = 19)
        Text("Longest wait between retries: ${Formatters.duration(s.retryBackoffCapSeconds.toLong())}", style = MaterialTheme.typography.bodyLarge)
        Slider(s.retryBackoffCapSeconds.toFloat(), { v -> vm.update { it.copy(retryBackoffCapSeconds = v.roundToInt()) } }, valueRange = 10f..3600f)
        SwitchRow("Resume interrupted downloads on start", s.autoResumeOnStart, { v -> vm.update { it.copy(autoResumeOnStart = v) } })
        SwitchRow(
            "Extract ZIP files automatically", premium && s.autoExtractZip, { v -> if (!premium) openPremium() else vm.update { it.copy(autoExtractZip = v) } },
            if (premium) "Unpacks into a folder next to the .zip when it finishes. The .zip is kept." else "Premium · You can still extract any ZIP from its details.",
        )
        SectionHeader("Browser")
        SwitchRow("Block ads and trackers", s.adBlock, { v -> vm.update { it.copy(adBlock = v) } }, "Faster pages and fewer ad videos in the media list. Can be turned off per site.")
        SwitchRow("Offer to download playing videos", s.announceVideos, { v -> vm.update { it.copy(announceVideos = v) } }, "Shows \"Video detected — Download\" when a video starts playing")
        SectionHeader("Notifications")
        SwitchRow("Notification per download", s.perDownloadNotifications, { v -> vm.update { it.copy(perDownloadNotifications = v) } }, "Progress, speed and remaining time with pause and cancel buttons")
        val ctx = LocalContext.current
        TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("System notification settings") }
        TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Battery optimization settings") }
        Text("Some phones stop background downloads aggressively. Exempting SDM from battery optimization keeps long downloads running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkSettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    var ua by remember(s.userAgent) { mutableStateOf(s.userAgent) }
    var host by remember(s.proxyHost) { mutableStateOf(s.proxyHost) }
    var port by remember(s.proxyPort) { mutableStateOf(s.proxyPort.toString()) }
    SettingsPage("Network") {
        SectionHeader("Network selection")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(s.networkPolicy == NetworkPolicy.ANY, { vm.update { it.copy(networkPolicy = NetworkPolicy.ANY) } }, { Text("Any network") })
            FilterChip(s.networkPolicy == NetworkPolicy.UNMETERED_ONLY, { vm.update { it.copy(networkPolicy = NetworkPolicy.UNMETERED_ONLY) } }, { Text("Unmetered only") })
            FilterChip(s.networkPolicy == NetworkPolicy.WIFI_ONLY, { vm.update { it.copy(networkPolicy = NetworkPolicy.WIFI_ONLY) } }, { Text("Wi-Fi only") })
        }
        Text("Unmetered treats a metered Wi-Fi hotspot like mobile data. Downloads pause on a disallowed network and resume automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionHeader("Timeouts")
        Text("Connection timeout: ${s.connectTimeoutSeconds}s")
        Slider(s.connectTimeoutSeconds.toFloat(), { v -> vm.update { it.copy(connectTimeoutSeconds = v.roundToInt()) } }, valueRange = 5f..120f)
        Text("Read timeout: ${s.readTimeoutSeconds}s")
        Slider(s.readTimeoutSeconds.toFloat(), { v -> vm.update { it.copy(readTimeoutSeconds = v.roundToInt()) } }, valueRange = 5f..300f)
        SectionHeader("User agent")
        OutlinedTextField(ua, { ua = it }, Modifier.fillMaxWidth(), placeholder = { Text("Default SDM user agent", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, singleLine = true)
        TextButton(onClick = { vm.update { it.copy(userAgent = ua.trim()) } }) { Text("Save user agent") }
        SectionHeader("DNS")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DnsMode.entries.forEach { m -> FilterChip(s.dnsMode == m, { vm.update { it.copy(dnsMode = m) } }, { Text(m.label) }) } }
        Text("DNS over HTTPS applies to downloads (not the browser) and is skipped while a proxy is set.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionHeader("Proxy")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ProxyType.entries.forEach { t -> FilterChip(s.proxyType == t, { vm.update { it.copy(proxyType = t) } }, { Text(t.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }) } }
        if (s.proxyType != ProxyType.NONE) {
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Proxy host") }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = { vm.update { it.copy(proxyHost = host.trim(), proxyPort = port.toIntOrNull() ?: 8080) } }) { Text("Save proxy") }
        }
        SectionHeader("HTTP handling")
        Text("SDM follows 301/302 redirects, resumes with 206 Partial Content, waits as Retry-After asks on 429/503, retries 5xx with backoff, and does not retry permanent errors (403, 404, 416).", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    SettingsPage("About SDM") {
        Spacer(Modifier.height(12.dp))
        Text("Smart Download Manager", style = MaterialTheme.typography.headlineSmall)
        Text("Download Smarter. Faster. Better.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        SectionHeader("Privacy")
        Text("SDM has no accounts, ads or analytics servers. Statistics stay on this device. Cookies used for downloads are encrypted with a key that never leaves the device and are excluded from exports and diagnostic reports.", style = MaterialTheme.typography.bodyMedium)
        SectionHeader("Limits")
        Text("SDM downloads files and unprotected streams your browser session can access. It does not bypass logins, paywalls or DRM.", style = MaterialTheme.typography.bodyMedium)
        SectionHeader("Open-source components")
        Text("Built with AndroidX, Jetpack Compose, Media3, OkHttp, Apache Commons Net, JSch, libtorrent (libtorrent4j), Mozilla Readability and the StevenBlack/hosts list (MIT). Full list and licenses: docs/THIRD_PARTY_NOTICES.md in the source repository.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("App info and permissions") }
        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Settings entry for Premium: an invitation before purchase, a thank-you after. */
@Composable
private fun PremiumBanner(unlocked: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            Modifier.background(com.sdm.downloadmanager.ui.BrandGradient).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WorkspacePremium, null, tint = Color.White, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(if (unlocked) "SDM Premium is active" else "SDM Premium", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(if (unlocked) "Thank you for supporting SDM" else "Torrents, FTP/SFTP, 16 connections and more · 3-day free trial", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Color.White)
        }
    }
}
