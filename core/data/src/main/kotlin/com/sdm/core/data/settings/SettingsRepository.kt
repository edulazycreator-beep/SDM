package com.sdm.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sdm.domain.NetworkPolicy
import com.sdm.domain.intelligence.QueueOrdering
import com.sdm.domain.intelligence.SpeedProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class LayoutDensity { COMFORTABLE, COMPACT }
enum class DnsMode(val label: String) { SYSTEM("System DNS"), CLOUDFLARE("Cloudflare DNS over HTTPS"), GOOGLE("Google DNS over HTTPS") }
enum class ProxyType { NONE, HTTP, SOCKS }

/** Speed limit presets from spec section 18. CUSTOM uses [AppSettings.customLimitBps]. */
enum class SpeedLimitProfile(val label: String, val bytesPerSecond: Long) {
    UNLIMITED("Unlimited", 0), LOW("Low · 512 KB/s", 512L * 1024), MEDIUM("Medium · 2 MB/s", 2L * 1024 * 1024),
    HIGH("High · 5 MB/s", 5L * 1024 * 1024), CUSTOM("Custom", -1),
}

enum class DashboardWidget(val label: String) {
    STATUS("Download status"), SPEED("Current speed"), STORAGE("Storage"), NETWORK("Network"), QUICK_ACTIONS("Quick actions"), RECENT("Recent activity")
}

@kotlinx.serialization.Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentArgb: Long = 0xFF1F5EFF,
    val density: LayoutDensity = LayoutDensity.COMFORTABLE,
    val haptics: Boolean = true,
    val animations: Boolean = true,
    val defaultConnections: Int = 4,
    val speedProfile: SpeedProfile = SpeedProfile.BALANCED,
    val autoConnections: Boolean = false,
    val speedLimitProfile: SpeedLimitProfile = SpeedLimitProfile.UNLIMITED,
    val customLimitBps: Long = 1024L * 1024,
    val maxConcurrent: Int = 3,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val retryMaxAttempts: Int = 5,
    val retryBackoffCapSeconds: Int = 300,
    val connectTimeoutSeconds: Int = 20,
    val readTimeoutSeconds: Int = 30,
    val userAgent: String = "",
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val clipboardDetection: Boolean = true,
    val onboardingDone: Boolean = false,
    val dashboardWidgets: List<DashboardWidget> = DashboardWidget.entries,
    val searchEngine: String = "https://duckduckgo.com/?q=",
    val desktopMode: Boolean = false,
    val queueOrdering: QueueOrdering = QueueOrdering.MANUAL,
    val autoResumeOnStart: Boolean = true,
    val perDownloadNotifications: Boolean = true,
    val lastClipboardHash: Int = 0,
    val exactAlarms: Boolean = true,
    /** Unpack .zip downloads into a folder next to them when they finish (the .zip is kept). */
    val autoExtractZip: Boolean = false,
    /** Block requests to known ad and tracker hosts in the browser. */
    val adBlock: Boolean = true,
    /** Offer "Video detected — Download" when a video starts playing in the browser. */
    val announceVideos: Boolean = true,
) {
    val globalLimitBps: Long get() = if (speedLimitProfile == SpeedLimitProfile.CUSTOM) customLimitBps else speedLimitProfile.bytesPerSecond
}

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme"); val dynamic = booleanPreferencesKey("dynamic"); val accent = longPreferencesKey("accent")
        val density = stringPreferencesKey("density"); val haptics = booleanPreferencesKey("haptics"); val animations = booleanPreferencesKey("animations")
        val connections = intPreferencesKey("connections"); val profile = stringPreferencesKey("profile"); val autoConn = booleanPreferencesKey("auto_conn")
        val limitProfile = stringPreferencesKey("limit_profile"); val customLimit = longPreferencesKey("custom_limit"); val maxConcurrent = intPreferencesKey("max_concurrent")
        val policy = stringPreferencesKey("policy"); val retries = intPreferencesKey("retries"); val backoffCap = intPreferencesKey("backoff_cap")
        val connectTimeout = intPreferencesKey("connect_timeout"); val readTimeout = intPreferencesKey("read_timeout"); val ua = stringPreferencesKey("ua")
        val dns = stringPreferencesKey("dns"); val proxyType = stringPreferencesKey("proxy_type"); val proxyHost = stringPreferencesKey("proxy_host"); val proxyPort = intPreferencesKey("proxy_port")
        val clipboard = booleanPreferencesKey("clipboard"); val onboarding = booleanPreferencesKey("onboarding"); val widgets = stringPreferencesKey("widgets")
        val search = stringPreferencesKey("search"); val desktop = booleanPreferencesKey("desktop"); val ordering = stringPreferencesKey("ordering")
        val autoExtract = booleanPreferencesKey("auto_extract_zip"); val adBlock = booleanPreferencesKey("ad_block"); val announce = booleanPreferencesKey("announce_videos")
        val autoResume = booleanPreferencesKey("auto_resume"); val perNotif = booleanPreferencesKey("per_notif"); val clipHash = intPreferencesKey("clip_hash")
        val exactAlarms = booleanPreferencesKey("exact_alarms")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    val settings: Flow<AppSettings> = context.store.data.map { parse(it) }

    private fun parse(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = p.enum(K.theme, d.themeMode), dynamicColor = p[K.dynamic] ?: d.dynamicColor, accentArgb = p[K.accent] ?: d.accentArgb,
            density = p.enum(K.density, d.density), haptics = p[K.haptics] ?: d.haptics, animations = p[K.animations] ?: d.animations,
            defaultConnections = p[K.connections] ?: d.defaultConnections, speedProfile = p.enum(K.profile, d.speedProfile), autoConnections = p[K.autoConn] ?: d.autoConnections,
            speedLimitProfile = p.enum(K.limitProfile, d.speedLimitProfile), customLimitBps = p[K.customLimit] ?: d.customLimitBps, maxConcurrent = p[K.maxConcurrent] ?: d.maxConcurrent,
            networkPolicy = p.enum(K.policy, d.networkPolicy), retryMaxAttempts = p[K.retries] ?: d.retryMaxAttempts, retryBackoffCapSeconds = p[K.backoffCap] ?: d.retryBackoffCapSeconds,
            connectTimeoutSeconds = p[K.connectTimeout] ?: d.connectTimeoutSeconds, readTimeoutSeconds = p[K.readTimeout] ?: d.readTimeoutSeconds, userAgent = p[K.ua] ?: d.userAgent,
            dnsMode = p.enum(K.dns, d.dnsMode), proxyType = p.enum(K.proxyType, d.proxyType), proxyHost = p[K.proxyHost] ?: d.proxyHost, proxyPort = p[K.proxyPort] ?: d.proxyPort,
            clipboardDetection = p[K.clipboard] ?: d.clipboardDetection, onboardingDone = p[K.onboarding] ?: d.onboardingDone,
            dashboardWidgets = p[K.widgets]?.split(',')?.mapNotNull { n -> DashboardWidget.entries.firstOrNull { it.name == n } } ?: d.dashboardWidgets,
            searchEngine = p[K.search] ?: d.searchEngine, desktopMode = p[K.desktop] ?: d.desktopMode, queueOrdering = p.enum(K.ordering, d.queueOrdering),
            autoResumeOnStart = p[K.autoResume] ?: d.autoResumeOnStart, perDownloadNotifications = p[K.perNotif] ?: d.perDownloadNotifications,
            autoExtractZip = p[K.autoExtract] ?: d.autoExtractZip, adBlock = p[K.adBlock] ?: d.adBlock, announceVideos = p[K.announce] ?: d.announceVideos,
            lastClipboardHash = p[K.clipHash] ?: d.lastClipboardHash, exactAlarms = p[K.exactAlarms] ?: d.exactAlarms,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.store.edit { p ->
            val s = transform(parse(p))
            p[K.theme] = s.themeMode.name; p[K.dynamic] = s.dynamicColor; p[K.accent] = s.accentArgb; p[K.density] = s.density.name
            p[K.haptics] = s.haptics; p[K.animations] = s.animations; p[K.connections] = s.defaultConnections; p[K.profile] = s.speedProfile.name
            p[K.autoConn] = s.autoConnections; p[K.limitProfile] = s.speedLimitProfile.name; p[K.customLimit] = s.customLimitBps
            p[K.maxConcurrent] = s.maxConcurrent.coerceIn(1, 10); p[K.policy] = s.networkPolicy.name; p[K.retries] = s.retryMaxAttempts.coerceIn(0, 50)
            p[K.backoffCap] = s.retryBackoffCapSeconds.coerceIn(5, 3600); p[K.connectTimeout] = s.connectTimeoutSeconds.coerceIn(3, 300)
            p[K.readTimeout] = s.readTimeoutSeconds.coerceIn(3, 600); p[K.ua] = s.userAgent; p[K.dns] = s.dnsMode.name; p[K.proxyType] = s.proxyType.name
            p[K.proxyHost] = s.proxyHost.trim(); p[K.proxyPort] = s.proxyPort.coerceIn(1, 65535); p[K.clipboard] = s.clipboardDetection; p[K.onboarding] = s.onboardingDone
            p[K.widgets] = s.dashboardWidgets.joinToString(",") { it.name }; p[K.search] = s.searchEngine; p[K.desktop] = s.desktopMode; p[K.ordering] = s.queueOrdering.name
            p[K.autoResume] = s.autoResumeOnStart; p[K.perNotif] = s.perDownloadNotifications; p[K.clipHash] = s.lastClipboardHash; p[K.exactAlarms] = s.exactAlarms
            p[K.autoExtract] = s.autoExtractZip; p[K.adBlock] = s.adBlock; p[K.announce] = s.announceVideos
        }
    }
}
