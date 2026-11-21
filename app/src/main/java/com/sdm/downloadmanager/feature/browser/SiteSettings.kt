package com.sdm.downloadmanager.feature.browser

import android.content.Context
import com.sdm.core.common.Urls
import java.util.concurrent.ConcurrentHashMap

/** Per-site browser choices; null means "use the app-wide setting". */
data class SiteSettings(
    val adBlock: Boolean? = null,
    val desktop: Boolean? = null,
    val javascript: Boolean = true,
    val popups: Boolean = false,
    val thirdPartyCookies: Boolean = false,
)

/** Small key-value store of [SiteSettings] by host (without "www."). */
class SiteSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("site_settings", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, SiteSettings>()

    fun hostOf(url: String?): String? = url?.let { Urls.host(it) }?.removePrefix("www.")

    fun get(url: String?): SiteSettings {
        val host = hostOf(url) ?: return SiteSettings()
        return cache.getOrPut(host) { decode(prefs.getString(host, null)) }
    }

    fun set(url: String?, s: SiteSettings) {
        val host = hostOf(url) ?: return
        cache[host] = s
        prefs.edit().apply { if (s == SiteSettings()) remove(host) else putString(host, encode(s)) }.apply()
    }

    fun all(): Map<String, SiteSettings> = prefs.all.keys.associateWith { decode(prefs.getString(it, null)) }

    private fun encode(s: SiteSettings) = listOf(s.adBlock.code(), s.desktop.code(), s.javascript.code(), s.popups.code(), s.thirdPartyCookies.code()).joinToString(",")
    private fun Boolean?.code() = when (this) { null -> "-"; true -> "1"; false -> "0" }
    private fun decode(v: String?): SiteSettings {
        val p = v?.split(',') ?: return SiteSettings()
        fun at(i: Int): Boolean? = when (p.getOrNull(i)) { "1" -> true; "0" -> false; else -> null }
        return SiteSettings(at(0), at(1), at(2) ?: true, at(3) ?: false, at(4) ?: false)
    }
}

/** Blocks requests to hosts on the bundled ad/tracker list (StevenBlack/hosts, MIT). */
object AdBlocker {
    @Volatile private var hosts: Set<String>? = null

    fun load(context: Context) {
        if (hosts != null) return
        synchronized(this) {
            if (hosts != null) return
            hosts = runCatching {
                context.assets.open("adblock/hosts.txt").bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim() }.toHashSet()
                }
            }.getOrDefault(emptySet())
        }
    }

    val ready: Boolean get() = hosts != null

    /** True when the host or one of its parent domains is listed. */
    fun isBlocked(host: String?): Boolean {
        val set = hosts ?: return false
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
