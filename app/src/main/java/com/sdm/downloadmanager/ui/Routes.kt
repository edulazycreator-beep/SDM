package com.sdm.downloadmanager.ui

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object DownloadsRoute
@Serializable data class BrowserRoute(val url: String? = null)
@Serializable data object FilesRoute
@Serializable data object SettingsRoute

@Serializable data class DetailsRoute(val id: Long)
@Serializable data object QueueRoute
@Serializable data object AnalyticsRoute
@Serializable data object SchedulerRoute
@Serializable data object RulesRoute
@Serializable data object NetworkSettingsRoute
@Serializable data object BookmarksRoute
@Serializable data object HistoryRoute
@Serializable data object DiagnosticsRoute
@Serializable data object StorageAnalysisRoute
@Serializable data object AboutRoute
@Serializable data object SearchRoute
@Serializable data object ImportExportRoute
@Serializable data object DownloadSettingsRoute
@Serializable data object AppearanceRoute
@Serializable data object LanReceiveRoute
