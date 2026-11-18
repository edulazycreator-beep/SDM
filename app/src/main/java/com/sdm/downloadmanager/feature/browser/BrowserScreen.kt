package com.sdm.downloadmanager.feature.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sdm.core.common.Formatters
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sdm.core.common.FileNames
import com.sdm.core.common.MimeTypes
import com.sdm.core.common.Urls
import com.sdm.engine.hls.HlsParser
import com.sdm.engine.hls.StreamKind
import com.sdm.core.data.browser.BrowserRepository
import com.sdm.core.data.download.DownloadRepository
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.downloadmanager.ui.AddRequest
import com.sdm.downloadmanager.ui.BookmarksRoute
import com.sdm.downloadmanager.ui.HistoryRoute
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.openInBrowser
import com.sdm.downloadmanager.ui.shareText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import javax.inject.Inject

/**
 * A video or audio the browser saw on the page: a direct file, an HLS stream (listed with its
 * qualities once its playlist has been read), or a stream SDM cannot save (DRM, live).
 */
data class DetectedMedia(
    val url: String,
    val name: String,
    val kind: String,
    val stream: Boolean = false,
    val title: String? = null,
    /** Qualities and duration, e.g. "1080p, 720p, 480p · 12:31". */
    val details: String? = null,
    val sizeBytes: Long? = null,
    val blockedReason: String? = null,
    /** Playlist is still being read. */
    val pending: Boolean = false,
    val playing: Boolean = false,
) {
    val downloadable: Boolean get() = blockedReason == null && !pending
}

data class TabUi(
    val id: String,
    val url: String = "",
    val title: String = "New tab",
    val progress: Int = 100,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isPrivate: Boolean = false,
    val secure: Boolean = false,
    val media: List<DetectedMedia> = emptyList(),
    /** Ad/tracker requests blocked on the current page. */
    val blocked: Int = 0,
    val reader: Boolean = false,
)

data class BrowserUi(val tabs: List<TabUi> = emptyList(), val currentId: String? = null, val findCount: Pair<Int, Int>? = null) {
    val current: TabUi? get() = tabs.firstOrNull { it.id == currentId }
}

private const val PRIVATE_PROFILE = "sdm_private"
private const val HOME_URL = "about:blank"

/**
 * Lightweight multi-tab WebView browser (spec section 10). WebViews live in this activity-scoped
 * ViewModel so tabs survive navigation; private tabs use a separate WebView profile when the
 * installed WebView supports it, and never write history.
 */
@SuppressLint("RequiresFeature")
@HiltViewModel
class BrowserViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val browser: BrowserRepository,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(BrowserUi())
    val ui = _ui.asStateFlow()
    private val views = mutableMapOf<String, WebView>()
    val privateSupported: Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    var onDownloadRequest: ((AddRequest) -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    /** A video started playing in the current tab; the screen offers to download it. */
    val playing = kotlinx.coroutines.flow.MutableSharedFlow<DetectedMedia>(extraBufferCapacity = 4)
    /** Page video in fullscreen (WebChromeClient custom view). */
    val fullscreen = MutableStateFlow<Pair<android.view.View, WebChromeClient.CustomViewCallback>?>(null)
    private val scans = java.util.concurrent.ConcurrentHashMap<String, TabScan>()
    private val userAgents = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Per-page detection state; replaced when a new page starts loading. */
    private class TabScan {
        val seen: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** Variant and audio playlists of a master playlist already listed (shown once, as the master). */
        val children: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val announced: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        @Volatile var title: String? = null
    }

    private fun scan(tabId: String): TabScan = scans.computeIfAbsent(tabId) { TabScan() }
    private var restored = false
    val bookmarked = MutableStateFlow(false)
    val settingsState = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun restore(initialUrl: String?) {
        if (restored) { initialUrl?.let { load(it) }; return }
        restored = true
        viewModelScope.launch {
            val saved = browser.savedTabs()
            if (saved.isEmpty()) newTab(initialUrl ?: HOME_URL, false)
            else {
                _ui.value = BrowserUi(saved.map { TabUi(it.id, it.url, it.title) }, saved.first().id)
                initialUrl?.let { load(it) }
            }
        }
    }

    fun newTab(url: String = HOME_URL, private: Boolean = false) {
        if (private && !privateSupported) { onMessage?.invoke("Private tabs need a newer Android System WebView. Update it from the Play Store."); return }
        val tab = TabUi(UUID.randomUUID().toString(), url, isPrivate = private)
        _ui.update { it.copy(tabs = it.tabs + tab, currentId = tab.id) }
        pendingLoad[tab.id] = url
        persistTabs()
    }

    private val pendingLoad = mutableMapOf<String, String>()

    fun select(id: String) { _ui.update { it.copy(currentId = id) }; refreshBookmark() }

    fun close(id: String) {
        val closing = _ui.value.tabs.firstOrNull { it.id == id }
        views.remove(id)?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        _ui.update { s ->
            val rest = s.tabs.filterNot { it.id == id }
            s.copy(tabs = rest, currentId = if (s.currentId == id) rest.lastOrNull()?.id else s.currentId)
        }
        viewModelScope.launch { browser.deleteTab(id) }
        if (closing?.isPrivate == true && _ui.value.tabs.none { it.isPrivate }) clearPrivateProfile()
        if (_ui.value.tabs.isEmpty()) newTab()
    }

    private fun clearPrivateProfile() {
        runCatching {
            ProfileStore.getInstance().getProfile(PRIVATE_PROFILE)?.let { p ->
                p.cookieManager.removeAllCookies(null)
                p.webStorage.deleteAllData()
            }
        }
    }

    val sites by lazy { SiteSettingsStore(appContext) }
    private val pageHosts = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun site(): SiteSettings = sites.get(_ui.value.current?.url)

    fun updateSite(transform: (SiteSettings) -> SiteSettings) {
        val tab = _ui.value.current ?: return
        sites.set(tab.url, transform(sites.get(tab.url)))
        views[tab.id]?.let { wv -> applySite(wv, tab, tab.url); wv.reload() }
    }

    private fun adBlockOn(url: String?) = sites.get(url).adBlock ?: (settingsState.value?.adBlock ?: true)

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySite(wv: WebView, tab: TabUi, url: String) {
        val site = sites.get(url)
        wv.settings.javaScriptEnabled = site.javascript
        wv.settings.javaScriptCanOpenWindowsAutomatically = site.popups
        applyDesktopMode(wv, site.desktop ?: (settingsState.value?.desktopMode == true))
        runCatching { cookieManagerFor(tab).setAcceptThirdPartyCookies(wv, site.thirdPartyCookies) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(context: Context, tab: TabUi): WebView = views.getOrPut(tab.id) {
        viewModelScope.launch(Dispatchers.IO) { AdBlocker.load(context.applicationContext) }
        WebView(MutableContextWrapper(context)).apply {
            if (tab.isPrivate && privateSupported) runCatching {
                ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                WebViewCompat.setProfile(this, PRIVATE_PROFILE)
            }
            configure(this, tab)
            val pending = pendingLoad.remove(tab.id)
            if (pending != POPUP) loadUrl(pending ?: tab.url.ifBlank { HOME_URL })
        }
    }.also { wv -> (wv.context as? MutableContextWrapper)?.baseContext = context }

    private fun cookieManagerFor(tab: TabUi): CookieManager =
        if (tab.isPrivate && privateSupported) runCatching { ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager }.getOrDefault(CookieManager.getInstance())
        else CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configure(wv: WebView, tab: TabUi) {
        val s = settingsState.value
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (tab.isPrivate) cacheMode = WebSettings.LOAD_NO_CACHE
            s?.userAgent?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
        }
        applyDesktopMode(wv, s?.desktopMode == true)
        cookieManagerFor(tab).setAcceptCookie(true)
        wv.addJavascriptInterface(Bridge(tab.id), "SDMBridge")
        val documentStart = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)
        if (documentStart) runCatching { WebViewCompat.addDocumentStartJavaScript(wv, MEDIA_HOOK_JS, setOf("*")) }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                scans[tab.id] = TabScan()
                sites.hostOf(url)?.let { pageHosts[tab.id] = it }
                applySite(view, tab, url)
                userAgents[tab.id] = view.settings.userAgentString
                updateTab(tab.id) { it.copy(url = url, progress = 5, secure = url.startsWith("https://"), media = emptyList(), blocked = 0, reader = false) }
                if (!documentStart) view.evaluateJavascript(MEDIA_HOOK_JS, null)
                if (tab.id == _ui.value.currentId) refreshBookmark()
            }

            override fun onPageFinished(view: WebView, url: String) {
                updateTab(tab.id) { it.copy(url = url, title = view.title ?: url, progress = 100, canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
                if (!tab.isPrivate) viewModelScope.launch { browser.recordVisit(url, view.title.orEmpty()) }
                persistTabs()
                if (!documentStart) view.evaluateJavascript(MEDIA_HOOK_JS, null)
                view.evaluateJavascript(MEDIA_SCAN_JS) { result -> parseMediaScan(tab.id, result) }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (Urls.isMagnet(u)) { onDownloadRequest?.invoke(AddRequest(listOf(u), sourcePage = tab.url)); return true }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Ad/tracker hosts get an empty answer (never the page itself, and only when blocking is on).
                if (!request.isForMainFrame && AdBlocker.ready) {
                    val page = pageHosts[tab.id]
                    val host = request.url.host
                    if (page != null && host != null && !host.endsWith(page) && adBlockOn("https://$page/") && AdBlocker.isBlocked(host)) {
                        updateTab(tab.id) { it.copy(blocked = it.blocked + 1) }
                        return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                // Otherwise only observes resource URLs to find media files and stream playlists (spec section 11).
                observe(tab.id, request.url.toString())
                return null
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { updateTab(tab.id) { it.copy(progress = newProgress, canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) } }
            override fun onReceivedTitle(view: WebView, title: String?) { updateTab(tab.id) { it.copy(title = title ?: it.title) } }
            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                fullscreen.value?.second?.onCustomViewHidden()
                fullscreen.value = view to callback
            }
            override fun onHideCustomView() { fullscreen.value = null }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                // Pop-ups open as a new tab when the user tapped something, or the site is allowed pop-ups.
                if (!isUserGesture && !sites.get(view.url).popups) {
                    onMessage?.invoke("Blocked a pop-up. Allow pop-ups for this site in Site settings.")
                    return false
                }
                val child = TabUi(UUID.randomUUID().toString(), "", isPrivate = tab.isPrivate)
                _ui.update { it.copy(tabs = it.tabs + child, currentId = child.id) }
                pendingLoad[child.id] = POPUP
                val ctx = (view.context as? MutableContextWrapper)?.baseContext ?: view.context
                val wv = webView(ctx, child)
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = wv
                resultMsg.sendToTarget()
                persistTabs()
                return true
            }
        }
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            when {
                url.startsWith("blob:") -> wv.evaluateJavascript(blobJs(url, URLUtil.guessFileName(url, contentDisposition, mimeType)), null)
                url.startsWith("data:") -> saveDataUrl(url, URLUtil.guessFileName(url, contentDisposition, mimeType), wv.url)
                else -> {
                    val cookies = cookieManagerFor(tab).getCookie(url)
                    val headers = buildMap {
                        put("User-Agent", userAgent)
                        wv.url?.let { put("Referer", it) }
                        if (!cookies.isNullOrBlank()) put("Cookie", cookies)
                    }
                    onDownloadRequest?.invoke(AddRequest(listOf(url), headers, wv.url, contentDisposition, mimeType, contentLength.takeIf { it > 0 }, URLUtil.guessFileName(url, contentDisposition, mimeType)))
                }
            }
        }
        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            val link = hit.extra
            if (link != null && Urls.isValidHttpUrl(link) && (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE || hit.type == WebView.HitTestResult.IMAGE_TYPE)) {
                val cookies = cookieManagerFor(tab).getCookie(link)
                onDownloadRequest?.invoke(AddRequest(listOf(link), buildMap { wv.url?.let { put("Referer", it) }; if (!cookies.isNullOrBlank()) put("Cookie", cookies) }, wv.url))
                true
            } else false
        }
    }

    private fun applyDesktopMode(wv: WebView, desktop: Boolean) {
        val custom = settingsState.value?.userAgent?.takeIf { it.isNotBlank() }
        val base = custom ?: WebSettings.getDefaultUserAgent(wv.context)
        wv.settings.userAgentString = if (desktop) base.replace(Regex("\\(Linux; Android [^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "") else base
    }

    fun toggleDesktopMode() = viewModelScope.launch {
        settings.update { it.copy(desktopMode = !it.desktopMode) }
        val desktop = !(settingsState.value?.desktopMode ?: false)
        views.values.forEach { applyDesktopMode(it, desktop); it.reload() }
    }

    private inner class Bridge(private val tabId: String) {
        @JavascriptInterface
        fun onBlob(dataUrl: String, mime: String, name: String) {
            saveDataUrl(dataUrl, name, _ui.value.tabs.firstOrNull { it.id == tabId }?.url, mime.ifBlank { null })
        }

        @JavascriptInterface
        fun onBlobError(message: String) { viewModelScope.launch { onMessage?.invoke("Could not save this file: $message") } }

        /** Called by [MEDIA_HOOK_JS] when a video/audio element loads or plays, or a playlist is fetched. */
        @JavascriptInterface
        fun onMedia(event: String, url: String, tag: String, durationSec: Double, height: Int, title: String) {
            onMediaEvent(tabId, event, url, tag, durationSec, height, title)
        }
    }

    // ------------------------------------------------------------------ media detection

    private fun observe(tabId: String, url: String) {
        if (!Urls.isValidHttpUrl(url)) return
        when (HlsParser.streamKindOfUrl(url)) {
            StreamKind.HLS -> onStreamUrl(tabId, url, "HLS")
            StreamKind.DASH -> onStreamUrl(tabId, url, "DASH")
            null -> {
                val name = FileNames.fromUrl(url) ?: return
                val ext = FileNames.extension(name)
                if (ext in MimeTypes.mediaExtensions && ext !in IMAGE_EXTENSIONS && scan(tabId).seen.add(url)) {
                    addMedia(tabId, DetectedMedia(url, name, ext.uppercase()))
                }
            }
        }
    }

    private fun onStreamUrl(tabId: String, url: String, kind: String = "HLS") {
        val scan = scan(tabId)
        if (url in scan.children || !scan.seen.add(url)) return
        addMedia(tabId, DetectedMedia(url, "Video stream", kind, stream = true, title = scan.title, pending = true))
        viewModelScope.launch(Dispatchers.IO) {
            val result = downloads.inspectStream(url, headersFor(tabId, url))
            val info = result.getOrNull()
            if (info == null) {
                updateMedia(tabId, url) { it.copy(pending = false, blockedReason = "Could not read this stream: ${com.sdm.core.common.Redactor.text(result.exceptionOrNull()?.message ?: "error")}") }
                return@launch
            }
            val isMaster = info.options.any { it.videoPlaylistUrl != info.sourceUrl && it.videoPlaylistUrl != url }
            if (isMaster) {
                info.options.forEach { o -> scan.children += o.videoPlaylistUrl; o.audioPlaylistUrl?.let { scan.children += it } }
                updateTab(tabId) { t -> t.copy(media = t.media.filterNot { it.url in scan.children && it.url != url }) }
            } else if (url in scan.children) {
                updateTab(tabId) { t -> t.copy(media = t.media.filterNot { it.url == url }) }
                return@launch
            }
            val qualities = info.options.mapNotNull { o -> o.height?.let { "${it}p" } }.distinct()
            val details = listOfNotNull(
                qualities.takeIf { it.isNotEmpty() }?.joinToString(", "),
                info.durationSec?.let { com.sdm.core.common.Formatters.duration(it.toLong()) },
                if (info.encrypted && !info.drm) "AES-128" else null,
                info.subtitles.takeIf { it.isNotEmpty() }?.let { "${it.size} subtitle(s)" },
            ).joinToString(" · ").ifBlank { null }
            updateMedia(tabId, url) {
                it.copy(kind = info.kind.name, pending = false, details = details, sizeBytes = info.options.firstOrNull()?.estimatedBytes, blockedReason = info.blockedReason, title = it.title ?: scan.title)
            }
        }
    }

    private fun onMediaEvent(tabId: String, event: String, rawUrl: String, tag: String, durationSec: Double, height: Int, title: String) {
        val scan = scan(tabId)
        if (title.isNotBlank()) scan.title = title.take(120)
        val url = rawUrl.trim()
        if (event == "stream" || event == "dash") { if (Urls.isValidHttpUrl(url)) onStreamUrl(tabId, url, if (event == "dash") "DASH" else "HLS"); return }
        val direct = Urls.isValidHttpUrl(url)
        if (direct) {
            if (HlsParser.streamKindOfUrl(url) != null) observe(tabId, url)
            else if (scan.seen.add(url)) {
                val name = FileNames.fromUrl(url) ?: "video"
                val kind = FileNames.extension(name).uppercase().ifBlank { if (tag.equals("AUDIO", true)) "AUDIO" else "VIDEO" }
                addMedia(tabId, DetectedMedia(url, name, kind, title = scan.title))
            }
            val details = listOfNotNull(height.takeIf { it > 0 }?.let { "${it}p" }, durationSec.takeIf { it > 0 && it.isFinite() }?.let { com.sdm.core.common.Formatters.duration(it.toLong()) }).joinToString(" · ").ifBlank { null }
            if (details != null) updateMedia(tabId, url) { if (it.stream) it else it.copy(details = details) }
        }
        if (event != "play" && event != "playing") return
        // Short clips (UI sounds, previews) are not worth interrupting for.
        if (durationSec > 0 && durationSec < 8) return
        viewModelScope.launch {
            // Players using Media Source Extensions play a blob: URL; the downloadable thing is the
            // playlist they fetched, which may still be being read.
            var found: DetectedMedia? = null
            for (attempt in 0 until 10) {
                val media = _ui.value.tabs.firstOrNull { it.id == tabId }?.media.orEmpty()
                found = if (direct) media.firstOrNull { it.url == url } ?: media.lastOrNull { it.stream }
                else media.lastOrNull { it.stream && it.downloadable } ?: media.lastOrNull { it.stream && !it.pending } ?: media.lastOrNull { it.stream }
                if (found != null && !found.pending) break
                kotlinx.coroutines.delay(300)
            }
            val key = found?.url ?: "unsupported:$url"
            if (!scan.announced.add(key)) return@launch
            updateTab(tabId) { t -> t.copy(media = t.media.map { it.copy(playing = it.url == found?.url) }) }
            if (tabId != _ui.value.currentId || !settings.current().announceVideos) return@launch
            playing.emit(found ?: DetectedMedia(url, "Video", "VIDEO", title = scan.title, blockedReason = "This video's source isn't visible to SDM (it may be protected or use an unsupported format)"))
        }
    }

    private fun updateMedia(tabId: String, url: String, f: (DetectedMedia) -> DetectedMedia) =
        updateTab(tabId) { t -> t.copy(media = t.media.map { if (it.url == url) f(it) else it }) }

    private fun headersFor(tabId: String, url: String): Map<String, String> {
        val tab = _ui.value.tabs.firstOrNull { it.id == tabId }
        val cookies = tab?.let { runCatching { cookieManagerFor(it).getCookie(url) }.getOrNull() }
        return buildMap {
            userAgents[tabId]?.let { put("User-Agent", it) }
            tab?.url?.takeIf { it.startsWith("http") }?.let { put("Referer", it) }
            if (!cookies.isNullOrBlank()) put("Cookie", cookies)
        }
    }

    /** What the add dialog needs to download [m] with this tab's session. */
    fun requestFor(m: DetectedMedia): AddRequest? {
        val tab = _ui.value.current ?: return null
        val title = m.title ?: scans[tab.id]?.title ?: tab.title
        return AddRequest(
            urls = listOf(m.url), headers = headersFor(tab.id, m.url), sourcePage = tab.url,
            mime = if (m.stream) (if (m.kind == "DASH") "application/dash+xml" else "application/vnd.apple.mpegurl") else null,
            sizeHint = m.sizeBytes.takeIf { !m.stream }, fileNameHint = if (m.stream) null else m.name,
            streamHint = m.stream, titleHint = title,
        )
    }

    /** Lists file links and media on the current page. */
    fun scanLinks(onResult: (List<PageLink>) -> Unit) {
        val tab = _ui.value.current ?: return onResult(emptyList())
        val wv = views[tab.id] ?: return onResult(emptyList())
        wv.evaluateJavascript(LinkGrabber.SCAN_JS) { json -> onResult(LinkGrabber.parse(json)) }
    }

    /** Queues the chosen links directly; each gets the cookies of its own site from this tab. */
    fun downloadLinks(links: List<PageLink>) {
        val tab = _ui.value.current ?: return
        viewModelScope.launch {
            var added = 0; var skipped = 0
            for (l in links) {
                if (downloads.findDuplicate(l.url, l.name, null) != null) { skipped++; continue }
                runCatching {
                    downloads.enqueue(com.sdm.core.data.download.NewDownload(url = l.url, fileName = FileNames.sanitize(l.name), headers = headersFor(tab.id, l.url), sourcePageUrl = tab.url))
                    added++
                }
            }
            onMessage?.invoke("Added $added download(s)" + if (skipped > 0) ", skipped $skipped already in SDM" else "")
        }
    }

    /** Reader view: Mozilla Readability turns the article into plain, large text; toggling again reloads. */
    fun toggleReader() {
        val tab = _ui.value.current ?: return
        val wv = views[tab.id] ?: return
        if (tab.reader) { updateTab(tab.id) { it.copy(reader = false) }; wv.reload(); return }
        viewModelScope.launch {
            val lib = withContext(Dispatchers.IO) { runCatching { appContext.assets.open("reader/Readability.js").bufferedReader().readText() }.getOrNull() } ?: return@launch
            wv.evaluateJavascript(lib + READER_JS) { r ->
                if (r == "true") updateTab(tab.id) { it.copy(reader = true) } else onMessage?.invoke("This page has no article SDM can show in reader view")
            }
        }
    }

    fun exitFullscreen() {
        val (_, callback) = fullscreen.value ?: return
        fullscreen.value = null
        runCatching { callback.onCustomViewHidden() }
    }

    private fun saveDataUrl(dataUrl: String, name: String, page: String?, mimeHint: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            val comma = dataUrl.indexOf(',')
            require(comma > 0) { "Invalid data" }
            val meta = dataUrl.substring(5, comma)
            val mime = mimeHint ?: meta.substringBefore(';').ifBlank { "application/octet-stream" }
            val bytes = if (meta.contains(";base64")) Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT) else java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8").toByteArray()
            require(bytes.size <= 50 * 1024 * 1024) { "File is larger than 50 MB" }
            val fileName = if (FileNames.extension(name).isEmpty()) "$name.${MimeTypes.extensionFor(mime) ?: "bin"}" else name
            downloads.saveBytes(fileName, mime, bytes, page)
            fileName
        }
        launch(Dispatchers.Main) { onMessage?.invoke(result.fold({ "Saved $it" }, { "Could not save: ${it.message}" })) }
    }

    private fun addMedia(tabId: String, m: DetectedMedia) =
        updateTab(tabId) { t -> if (t.media.any { it.url == m.url }) t else t.copy(media = (t.media + m).takeLast(50)) }

    private fun parseMediaScan(tabId: String, json: String?) {
        val items = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return
        for (i in 0 until items.length()) {
            val url = items.optString(i)
            if (!Urls.isValidHttpUrl(url)) continue
            if (HlsParser.streamKindOfUrl(url) != null) { observe(tabId, url); continue }
            if (!scan(tabId).seen.add(url)) continue
            val name = FileNames.fromUrl(url) ?: "media"
            addMedia(tabId, DetectedMedia(url, name, FileNames.extension(name).uppercase().ifBlank { "MEDIA" }, title = scan(tabId).title))
        }
    }

    private fun updateTab(id: String, f: (TabUi) -> TabUi) = _ui.update { s -> s.copy(tabs = s.tabs.map { if (it.id == id) f(it) else it }) }

    private fun persistTabs() = viewModelScope.launch {
        _ui.value.tabs.filterNot { it.isPrivate }.forEachIndexed { i, t -> if (t.url.startsWith("http")) browser.saveTab(t.id, t.url, t.title, i) }
    }

    private fun current(): WebView? = _ui.value.currentId?.let { views[it] }

    fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            Urls.isValidHttpUrl(text) -> text
            !text.contains(' ') && text.contains('.') && Urls.isValidHttpUrl("https://$text") -> "https://$text"
            else -> (settingsState.value?.searchEngine ?: "https://duckduckgo.com/?q=") + java.net.URLEncoder.encode(text, "UTF-8")
        }
        val tab = _ui.value.current
        if (tab == null) { newTab(url); return }
        val wv = views[tab.id]
        if (wv == null) pendingLoad[tab.id] = url else wv.loadUrl(url)
    }

    fun back() = current()?.goBack()
    fun forward() = current()?.goForward()
    fun reload() = current()?.reload()
    fun canGoBack() = current()?.canGoBack() == true
    fun find(q: String) = current()?.let { wv ->
        wv.setFindListener { active, count, done -> if (done) _ui.update { it.copy(findCount = (if (count == 0) 0 else active + 1) to count) } }
        wv.findAllAsync(q)
    }
    fun findNext(forward: Boolean) = current()?.findNext(forward)
    fun clearFind() { current()?.clearMatches(); _ui.update { it.copy(findCount = null) } }

    fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        views.values.forEach { it.clearCache(true) }
        android.webkit.WebStorage.getInstance().deleteAllData()
        clearPrivateProfile()
        onMessage?.invoke("Cookies, cache and site data cleared")
    }

    fun refreshBookmark() {
        val url = _ui.value.current?.url ?: return
        viewModelScope.launch { bookmarked.value = browser.isBookmarked(url).first() }
    }

    fun toggleBookmark() = viewModelScope.launch {
        val t = _ui.value.current ?: return@launch
        browser.toggleBookmark(t.url, t.title)
        bookmarked.value = !bookmarked.value
    }

    override fun onCleared() {
        exitFullscreen()
        views.values.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        views.clear()
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
        private const val POPUP = "sdm:popup"

        private val READER_JS = """
            ;(function(){
              try {
                var a = new Readability(document.cloneNode(true)).parse();
                if (!a || !a.content || (a.textContent || '').length < 200) return false;
                function esc(t){ return String(t || '').replace(/[&<>"]/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }
                var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
                var css = 'body{font:19px/1.65 Georgia,serif;max-width:38em;margin:0 auto;padding:20px 18px 60px;' + (dark ? 'background:#121212;color:#e6e1e5' : 'background:#fffdf8;color:#1b1b1f') + '}' +
                  'h1{font:600 1.5em/1.25 system-ui,sans-serif}img,video,figure{max-width:100%;height:auto}a{color:' + (dark ? '#a8c7fa' : '#3657c2') + '}pre{white-space:pre-wrap}.by{opacity:.7;font:14px system-ui,sans-serif}';
                document.open();
                document.write('<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>' + esc(a.title) + '</title><style>' + css + '</style></head><body><h1>' + esc(a.title) + '</h1>' + (a.byline ? '<p class="by">' + esc(a.byline) + '</p>' : '') + a.content + '</body></html>');
                document.close();
                return true;
              } catch (e) { return false; }
            })();
        """.trimIndent()

        /**
         * Reports media elements that load or play (capture-phase listeners, so they see events from
         * every element) and fetch/XHR responses served as HLS playlists. It only observes: requests
         * and playback are passed through unchanged.
         */
        private val MEDIA_HOOK_JS = """
            (function(){
              if (window.__sdmMediaHook) return; window.__sdmMediaHook = true;
              var B = window.SDMBridge; if (!B || !B.onMedia) return;
              function rep(ev, url, el){
                try {
                  var d = el && isFinite(el.duration) ? el.duration : -1;
                  B.onMedia(ev, String(url || ''), el ? String(el.tagName || '') : '', d, el && el.videoHeight ? el.videoHeight : 0, String(document.title || ''));
                } catch (e) {}
              }
              ['loadedmetadata','play','playing'].forEach(function(t){
                document.addEventListener(t, function(e){
                  var el = e.target;
                  if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO')) rep(t, el.currentSrc || el.src, el);
                }, true);
              });
              function hls(ct){ return !!ct && /mpegurl/i.test(ct); }
              function dash(ct){ return !!ct && /dash\+xml/i.test(ct); }
              var f = window.fetch;
              if (f) window.fetch = function(){
                var p = f.apply(this, arguments);
                p.then(function(r){ try { var ct = r.headers.get('content-type'); if (hls(ct)) rep('stream', r.url, null); else if (dash(ct)) rep('dash', r.url, null); } catch (e) {} }, function(){});
                return p;
              };
              var X = window.XMLHttpRequest && XMLHttpRequest.prototype;
              if (X) {
                var send = X.send;
                X.send = function(){
                  var x = this;
                  try { x.addEventListener('load', function(){ try { var ct = x.getResponseHeader('content-type'); if (hls(ct)) rep('stream', x.responseURL, null); else if (dash(ct)) rep('dash', x.responseURL, null); } catch (e) {} }); } catch (e) {}
                  return send.apply(this, arguments);
                };
              }
            })();
        """.trimIndent()

        private const val MEDIA_SCAN_JS = "(function(){var s=new Set();document.querySelectorAll('video,audio,video source,audio source').forEach(function(e){var u=e.currentSrc||e.src;if(u&&u.indexOf('http')===0)s.add(u)});return JSON.stringify(Array.from(s));})()"
        private fun blobJs(url: String, name: String): String {
            val u = url.replace("'", "\\'"); val n = name.replace("'", "\\'")
            return "(function(){fetch('$u').then(function(r){return r.blob()}).then(function(b){if(b.size>52428800){SDMBridge.onBlobError('File is larger than 50 MB');return;}var f=new FileReader();f.onloadend=function(){SDMBridge.onBlob(f.result,b.type||'','$n')};f.readAsDataURL(b)}).catch(function(e){SDMBridge.onBlobError(String(e))})})()"
        }
    }
}

@Composable
fun BrowserScreen(initialUrl: String? = null) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val vm: BrowserViewModel = hiltViewModel(activity)
    val app = LocalAppActions.current
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    val bookmarked by vm.bookmarked.collectAsState()
    val settings by vm.settingsState.collectAsState()
    var address by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var tabsDialog by remember { mutableStateOf(false) }
    var mediaDialog by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf<String?>(null) }
    val fullscreen by vm.fullscreen.collectAsState()
    var pageLinks by remember { mutableStateOf<List<PageLink>?>(null) }
    var siteSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.onDownloadRequest = app.requestAdd
        vm.onMessage = { msg -> vm.viewModelScope.launch { app.snackbar.showSnackbar(msg) } }
        vm.restore(initialUrl)
    }
    fun download(m: DetectedMedia) {
        if (!m.downloadable) { mediaDialog = true; return }
        vm.requestFor(m)?.let(app.requestAdd)
    }
    LaunchedEffect(Unit) {
        vm.playing.collect { m ->
            val label = m.title?.takeIf { it.isNotBlank() }?.let { " · ${it.take(40)}" }.orEmpty()
            val text = if (m.downloadable) "Video detected$label" else "Video playing$label — can't be downloaded"
            val r = app.snackbar.showSnackbar(text, actionLabel = if (m.downloadable) "Download" else "Details", withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) { vm.exitFullscreen(); if (m.downloadable) download(m) else mediaDialog = true }
        }
    }
    val tab = ui.current
    LaunchedEffect(tab?.id, tab?.url) { address = tab?.url?.takeIf { it != HOME_URL }.orEmpty() }
    BackHandler(enabled = tab?.canGoBack == true) { vm.back() }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Surface(tonalElevation = 2.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tab?.isPrivate == true) Text("Private", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(start = 6.dp))
                    OutlinedTextField(
                        address, { address = it }, Modifier.weight(1f).padding(4.dp), singleLine = true,
                        placeholder = { Text("Search or enter address") },
                        leadingIcon = if (tab?.secure == true) { { Icon(Icons.Filled.Lock, "Secure connection") } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { vm.load(address) }),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { tabsDialog = true }) {
                        BadgedBox(badge = { Badge { Text(ui.tabs.size.toString()) } }) { Icon(Icons.Filled.Tab, "Tabs") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Browser menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("New tab") }, onClick = { menu = false; vm.newTab() })
                            DropdownMenuItem(text = { Text("New private tab") }, enabled = vm.privateSupported, onClick = { menu = false; vm.newTab(private = true) })
                            DropdownMenuItem(text = { Text("Bookmarks") }, onClick = { menu = false; app.nav.navigate(BookmarksRoute) })
                            DropdownMenuItem(text = { Text("History") }, onClick = { menu = false; app.nav.navigate(HistoryRoute) })
                            DropdownMenuItem(text = { Text("Find in page") }, onClick = { menu = false; findQuery = "" })
                            DropdownMenuItem(text = { Text(if (tab?.reader == true) "Exit reader view" else "Reader view") }, enabled = tab?.url?.startsWith("http") == true, onClick = { menu = false; vm.toggleReader() })
                            DropdownMenuItem(text = { Text("Site settings" + (tab?.blocked?.takeIf { it > 0 }?.let { " · $it blocked" } ?: "")) }, enabled = tab?.url?.startsWith("http") == true, onClick = { menu = false; siteSheet = true })
                            DropdownMenuItem(text = { Text("Download files on this page") }, enabled = tab?.url?.startsWith("http") == true, onClick = { menu = false; vm.scanLinks { pageLinks = it } })
                            DropdownMenuItem(text = { Text(if (settings?.desktopMode == true) "Mobile site" else "Desktop site") }, onClick = { menu = false; vm.toggleDesktopMode() })
                            DropdownMenuItem(text = { Text("Share link") }, enabled = tab?.url?.startsWith("http") == true, onClick = { menu = false; tab?.url?.let(ctx::shareText) })
                            DropdownMenuItem(text = { Text("Open in external browser") }, enabled = tab?.url?.startsWith("http") == true, onClick = { menu = false; tab?.url?.let(ctx::openInBrowser) })
                            DropdownMenuItem(text = { Text("Clear cookies and cache") }, onClick = { menu = false; vm.clearData() })
                        }
                    }
                }
                // Full 48 dp touch targets for the toolbar buttons (accessibility).
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.back() }, enabled = tab?.canGoBack == true, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    IconButton(onClick = { vm.forward() }, enabled = tab?.canGoForward == true, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                    IconButton(onClick = { vm.reload() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { vm.toggleBookmark() }, enabled = tab?.url?.startsWith("http") == true, modifier = Modifier.size(48.dp)) { Icon(if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder, "Bookmark") }
                }
                if ((tab?.progress ?: 100) < 100) LinearProgressIndicator(progress = { (tab?.progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                findQuery?.let { q ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        OutlinedTextField(q, { findQuery = it; vm.find(it) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find in page") })
                        Text(ui.findCount?.let { "${it.first}/${it.second}" } ?: "", modifier = Modifier.padding(horizontal = 6.dp))
                        TextButton(onClick = { vm.findNext(false) }) { Text("▲") }
                        TextButton(onClick = { vm.findNext(true) }) { Text("▼") }
                        IconButton(onClick = { findQuery = null; vm.clearFind() }) { Icon(Icons.Filled.Close, "Close find") }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab != null) key(tab.id) {
                AndroidView(
                    factory = { c -> vm.webView(c, tab).also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (tab == null || tab.url == HOME_URL || tab.url.isBlank()) StartPage(onOpen = vm::load)
            val media = tab?.media.orEmpty()
            if (media.isNotEmpty()) {
                val ready = media.count { it.downloadable }
                ExtendedFloatingActionButton(
                    onClick = { val single = media.singleOrNull(); if (single != null && single.downloadable) download(single) else mediaDialog = true },
                    icon = {
                        BadgedBox(badge = { Badge { Text(media.size.toString()) } }) {
                            Icon(if (media.any { it.stream }) Icons.Filled.Movie else Icons.Filled.Download, null)
                        }
                    },
                    text = { Text(if (ready > 0) "Download" else "Media") },
                    expanded = media.any { it.playing } || media.size == 1,
                    containerColor = if (ready > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = com.sdm.downloadmanager.ui.snackbarLift()).semantics { contentDescription = "Detected media (${media.size})" },
                )
            }
        }
    }

    fullscreen?.let { (view, _) ->
        FullscreenVideo(view, onExit = vm::exitFullscreen, onDownload = { vm.exitFullscreen(); tab?.media?.let { m -> (m.firstOrNull { it.playing } ?: m.singleOrNull())?.let(::download) ?: run { mediaDialog = true } } })
    }

    if (tabsDialog) AlertDialog(
        onDismissRequest = { tabsDialog = false },
        title = { Text("Tabs") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(ui.tabs, key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().clickable { vm.select(t.id); tabsDialog = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (t.isPrivate) "🔒 " else "") + t.title, maxLines = 1, style = if (t.id == ui.currentId) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                            Text(t.url, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { vm.close(t.id) }) { Icon(Icons.Filled.Close, "Close tab") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.newTab(); tabsDialog = false }) { Text("New tab") } },
        dismissButton = { TextButton(onClick = { vm.newTab(private = true); tabsDialog = false }, enabled = vm.privateSupported) { Text("New private tab") } },
    )

    if (siteSheet) SiteSettingsSheet(vm, tab, globalAdBlock = settings?.adBlock ?: true, globalDesktop = settings?.desktopMode == true, onDismiss = { siteSheet = false })
    pageLinks?.let { links -> LinkGrabberSheet(links, onDismiss = { pageLinks = null }, onDownload = { chosen -> pageLinks = null; vm.downloadLinks(chosen) }) }
    if (mediaDialog) MediaSheet(tab?.media.orEmpty(), onDismiss = { mediaDialog = false }, onDownload = { m -> mediaDialog = false; download(m) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSheet(media: List<DetectedMedia>, onDismiss: () -> Unit, onDownload: (DetectedMedia) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Media on this page", style = MaterialTheme.typography.titleMedium)
            Text(
                "Videos and audio this page loaded or played. HLS and DASH streams are saved as MP4. DRM-protected and live streams can't be downloaded.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
            )
            if (media.isEmpty()) Text("Nothing detected yet. Start playing the video, then check here again.", modifier = Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(media.sortedByDescending { it.playing }, key = { it.url }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = m.downloadable) { onDownload(m) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = MaterialTheme.shapes.small, color = if (m.stream) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
                            Text(m.kind.take(5), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                (if (m.playing) "▶ " else "") + (m.title?.takeIf { m.stream && it.isNotBlank() } ?: m.name),
                                maxLines = 2, style = MaterialTheme.typography.bodyMedium,
                            )
                            val info = listOfNotNull(m.details, m.sizeBytes?.let { (if (m.stream) "~" else "") + Formatters.bytes(it) }).joinToString(" · ")
                            when {
                                m.pending -> Text("Reading stream…", style = MaterialTheme.typography.bodySmall)
                                m.blockedReason != null -> Text(m.blockedReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                info.isNotBlank() -> Text(info, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (m.pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (m.downloadable) IconButton(onClick = { onDownload(m) }) { Icon(Icons.Filled.Download, "Download ${m.name}") }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Page video in fullscreen: covers the app's bars and hides the system bars until closed. */
@Composable
private fun FullscreenVideo(view: android.view.View, onExit: () -> Unit, onDownload: () -> Unit) {
    Dialog(onDismissRequest = onExit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "Download this video", tint = Color.White) }
                IconButton(onClick = onExit) { Icon(Icons.Filled.FullscreenExit, "Exit fullscreen", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun StartPage(onOpen: (String) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp)) {
            Text("SDM Browser", style = MaterialTheme.typography.headlineSmall)
            Text("Downloads you start here carry this site's cookies, so signed-in downloads work.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(8.dp))
            listOf("https://duckduckgo.com", "https://www.wikipedia.org", "https://archive.org").forEach { u ->
                TextButton(onClick = { onOpen(u) }) { Text(u.removePrefix("https://")) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteSettingsSheet(vm: BrowserViewModel, tab: TabUi?, globalAdBlock: Boolean, globalDesktop: Boolean, onDismiss: () -> Unit) {
    var site by remember { mutableStateOf(vm.site()) }
    fun change(f: (SiteSettings) -> SiteSettings) { site = f(site); vm.updateSite { site } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Site settings", style = MaterialTheme.typography.titleMedium)
            Text(vm.sites.hostOf(tab?.url) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable fun row(label: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label)
                        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Switch(value, onChange)
                }
            }
            row("Block ads and trackers", tab?.blocked?.takeIf { it > 0 }?.let { "$it requests blocked on this page" }, site.adBlock ?: globalAdBlock) { v -> change { it.copy(adBlock = v) } }
            row("Desktop site", null, site.desktop ?: globalDesktop) { v -> change { it.copy(desktop = v) } }
            row("JavaScript", "Turning it off can break pages and video players", site.javascript) { v -> change { it.copy(javascript = v) } }
            row("Allow pop-ups", "Pop-ups open as new tabs", site.popups) { v -> change { it.copy(popups = v) } }
            row("Third-party cookies", "Some sign-in and video embeds need them", site.thirdPartyCookies) { v -> change { it.copy(thirdPartyCookies = v) } }
            TextButton(onClick = { site = SiteSettings(); vm.updateSite { SiteSettings() } }) { Text("Reset to defaults") }
        }
    }
}
