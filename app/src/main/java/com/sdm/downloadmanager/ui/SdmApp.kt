package com.sdm.downloadmanager.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sdm.core.data.settings.LayoutDensity
import com.sdm.core.data.settings.ThemeMode
import com.sdm.core.designsystem.theme.SdmTheme
import com.sdm.core.designsystem.theme.SdmThemeMode
import com.sdm.downloadmanager.feature.add.AddDownloadDialog
import com.sdm.downloadmanager.feature.analytics.AnalyticsScreen
import com.sdm.downloadmanager.feature.browser.BookmarksScreen
import com.sdm.downloadmanager.feature.browser.BrowserScreen
import com.sdm.downloadmanager.feature.browser.HistoryScreen
import com.sdm.downloadmanager.feature.details.DetailsScreen
import com.sdm.downloadmanager.feature.diagnostics.DiagnosticsScreen
import com.sdm.downloadmanager.feature.downloads.DownloadsScreen
import com.sdm.downloadmanager.feature.files.FilesScreen
import com.sdm.downloadmanager.feature.files.StorageAnalysisScreen
import com.sdm.downloadmanager.feature.home.HomeScreen
import com.sdm.downloadmanager.feature.io.ImportExportScreen
import com.sdm.downloadmanager.feature.onboarding.OnboardingScreen
import com.sdm.downloadmanager.feature.queue.QueueScreen
import com.sdm.downloadmanager.feature.rules.RulesScreen
import com.sdm.downloadmanager.feature.scheduler.SchedulerScreen
import com.sdm.downloadmanager.feature.search.SearchScreen
import com.sdm.downloadmanager.feature.settings.AboutScreen
import com.sdm.downloadmanager.feature.settings.AppearanceScreen
import com.sdm.downloadmanager.feature.settings.DownloadSettingsScreen
import com.sdm.downloadmanager.feature.settings.NetworkSettingsScreen
import com.sdm.downloadmanager.feature.settings.SettingsScreen

/** Shared helpers every screen needs: navigation, add dialog, snackbars, haptics. */
class AppActions(
    val nav: NavHostController,
    val snackbar: SnackbarHostState,
    val requestAdd: (AddRequest) -> Unit,
    val pasteFromClipboard: () -> Unit,
    val haptic: () -> Unit,
)

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

private data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val matches: (androidx.navigation.NavDestination) -> Boolean)

private val topLevels = listOf(
    TopLevel("Home", Icons.Filled.Home, HomeRoute) { it.hasRoute(HomeRoute::class) },
    TopLevel("Downloads", Icons.Filled.Download, DownloadsRoute) { it.hasRoute(DownloadsRoute::class) },
    TopLevel("Browser", Icons.Filled.Language, BrowserRoute()) { it.hasRoute(BrowserRoute::class) },
    TopLevel("Files", Icons.Filled.Folder, FilesRoute) { it.hasRoute(FilesRoute::class) },
    TopLevel("Settings", Icons.Filled.Settings, SettingsRoute) { it.hasRoute(SettingsRoute::class) },
)

@Composable
fun SdmApp(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val s = settings ?: return
    SdmTheme(
        mode = when (s.themeMode) { ThemeMode.SYSTEM -> SdmThemeMode.SYSTEM; ThemeMode.LIGHT -> SdmThemeMode.LIGHT; ThemeMode.DARK -> SdmThemeMode.DARK; ThemeMode.AMOLED -> SdmThemeMode.AMOLED },
        dynamicColor = s.dynamicColor,
        accent = Color(s.accentArgb.toInt()),
        compactLayout = s.density == LayoutDensity.COMPACT,
        animations = s.animations,
    ) {
        if (!s.onboardingDone) {
            OnboardingScreen(onDone = { vm.completeOnboarding() })
            return@SdmTheme
        }
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val haptics = LocalHapticFeedback.current
        val actions = remember(nav, snackbar) {
            AppActions(
                nav = nav, snackbar = snackbar, requestAdd = vm::requestAdd,
                pasteFromClipboard = { vm.pasteFromClipboard { /* empty */ } },
                haptic = { if (s.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
        }
        val addRequest by vm.addRequest.collectAsState()
        val clip by vm.clipboardSuggestion.collectAsState()
        val openDownload by vm.openDownload.collectAsState()
        LaunchedEffect(clip) {
            val url = clip ?: return@LaunchedEffect
            val r = snackbar.showSnackbar("Copied link: ${url.take(60)}", actionLabel = "Download", withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.requestAdd(AddRequest(listOf(url)))
            vm.dismissClipboard()
        }
        LaunchedEffect(openDownload) {
            openDownload?.let { nav.navigate(DetailsRoute(it)); vm.consumeOpenDownload() }
        }
        CompositionLocalProvider(LocalAppActions provides actions) {
            val wide = LocalConfiguration.current.screenWidthDp >= 600
            val entry by nav.currentBackStackEntryAsState()
            val dest = entry?.destination
            val showBars = dest == null || topLevels.any { t -> dest.hierarchy.any { t.matches(it) } }
            fun go(route: Any) = nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            Row(Modifier.fillMaxSize()) {
                if (wide && showBars) NavigationRail {
                    topLevels.forEach { t ->
                        NavigationRailItem(selected = dest?.hierarchy?.any { t.matches(it) } == true, onClick = { go(t.route) }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (!wide && showBars) NavigationBar {
                            topLevels.forEach { t ->
                                NavigationBarItem(selected = dest?.hierarchy?.any { t.matches(it) } == true, onClick = { go(t.route) }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                            }
                        }
                    },
                ) { padding ->
                    NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
                        composable<HomeRoute> { HomeScreen() }
                        composable<DownloadsRoute> { DownloadsScreen() }
                        composable<BrowserRoute> { BrowserScreen(initialUrl = it.toRoute<BrowserRoute>().url) }
                        composable<FilesRoute> { FilesScreen() }
                        composable<SettingsRoute> { SettingsScreen() }
                        composable<DetailsRoute> { DetailsScreen(id = it.toRoute<DetailsRoute>().id) }
                        composable<QueueRoute> { QueueScreen() }
                        composable<AnalyticsRoute> { AnalyticsScreen() }
                        composable<SchedulerRoute> { SchedulerScreen() }
                        composable<RulesRoute> { RulesScreen() }
                        composable<NetworkSettingsRoute> { NetworkSettingsScreen() }
                        composable<BookmarksRoute> { BookmarksScreen() }
                        composable<HistoryRoute> { HistoryScreen() }
                        composable<DiagnosticsRoute> { DiagnosticsScreen() }
                        composable<StorageAnalysisRoute> { StorageAnalysisScreen() }
                        composable<AboutRoute> { AboutScreen() }
                        composable<SearchRoute> { SearchScreen() }
                        composable<ImportExportRoute> { ImportExportScreen() }
                        composable<DownloadSettingsRoute> { DownloadSettingsScreen() }
                        composable<AppearanceRoute> { AppearanceScreen() }
                    }
                }
            }
            addRequest?.let { req -> AddDownloadDialog(request = req, onDismiss = vm::dismissAdd) }
        }
    }
}
