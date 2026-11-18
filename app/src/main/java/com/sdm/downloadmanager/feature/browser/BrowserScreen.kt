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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import org.json.JSONArray
import java.util.UUID
import javax.inject.Inject

data class DetectedMedia(val url: String, val name: String, val kind: String)

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

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(context: Context, tab: TabUi): WebView = views.getOrPut(tab.id) {
        WebView(MutableContextWrapper(context)).apply {
            if (tab.isPrivate && privateSupported) runCatching {
                ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                WebViewCompat.setProfile(this, PRIVATE_PROFILE)
            }
            configure(this, tab)
            loadUrl(pendingLoad.remove(tab.id) ?: tab.url.ifBlank { HOME_URL })
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
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (tab.isPrivate) cacheMode = WebSettings.LOAD_NO_CACHE
            s?.userAgent?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
        }
        applyDesktopMode(wv, s?.desktopMode == true)
        cookieManagerFor(tab).setAcceptCookie(true)
        wv.addJavascriptInterface(Bridge(tab.id), "SDMBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                updateTab(tab.id) { it.copy(url = url, progress = 5, secure = url.startsWith("https://"), media = emptyList()) }
                if (tab.id == _ui.value.currentId) refreshBookmark()
            }

            override fun onPageFinished(view: WebView, url: String) {
                updateTab(tab.id) { it.copy(url = url, title = view.title ?: url, progress = 100, canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
                if (!tab.isPrivate) viewModelScope.launch { browser.recordVisit(url, view.title.orEmpty()) }
                persistTabs()
                view.evaluateJavascript(MEDIA_SCAN_JS) { result -> parseMediaScan(tab.id, result) }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Observes resource URLs only (never blocks or alters them) to list direct media files (spec section 11).
                val url = request.url.toString()
                val name = FileNames.fromUrl(url)
                val ext = name?.let { FileNames.extension(it) }
                if (ext != null && ext in MimeTypes.mediaExtensions && ext !in setOf("jpg", "jpeg", "png", "gif", "webp")) addMedia(tab.id, DetectedMedia(url, name, ext.uppercase()))
                return null
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { updateTab(tab.id) { it.copy(progress = newProgress, canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) } }
            override fun onReceivedTitle(view: WebView, title: String?) { updateTab(tab.id) { it.copy(title = title ?: it.title) } }
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

    private fun addMedia(tabId: String, m: DetectedMedia) = viewModelScope.launch {
        updateTab(tabId) { t -> if (t.media.any { it.url == m.url }) t else t.copy(media = (t.media + m).takeLast(50)) }
    }

    private fun parseMediaScan(tabId: String, json: String?) {
        val items = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return
        for (i in 0 until items.length()) {
            val url = items.optString(i)
            if (!Urls.isValidHttpUrl(url)) continue
            val name = FileNames.fromUrl(url) ?: "media"
            addMedia(tabId, DetectedMedia(url, name, FileNames.extension(name).uppercase().ifBlank { "MEDIA" }))
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
        views.values.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        views.clear()
    }

    companion object {
        private const val MEDIA_SCAN_JS = "(function(){var s=new Set();document.querySelectorAll('video,audio,video source,audio source').forEach(function(e){var u=e.currentSrc||e.src;if(u&&u.indexOf('http')===0)s.add(u)});return JSON.stringify(Array.from(s));})()"
        private fun blobJs(url: String, name: String): String {
            val u = url.replace("'", "\\'"); val n = name.replace("'", "\\'")
            return "(function(){fetch('$u').then(function(r){return r.blob()}).then(function(b){if(b.size>52428800){SDMBridge.onBlobError('File is larger than 50 MB');return;}var f=new FileReader();f.onloadend=function(){SDMBridge.onBlob(f.result,b.type||'','$n')};f.readAsDataURL(b)}).catch(function(e){SDMBridge.onBlobError(String(e))})})()"
        }
    }
}

@Composable
fun BrowserScreen(initialUrl: String? = null) {
    val activity = LocalContext.current as ComponentActivity
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
    LaunchedEffect(Unit) {
        vm.onDownloadRequest = app.requestAdd
        vm.onMessage = { msg -> vm.viewModelScope.launch { app.snackbar.showSnackbar(msg) } }
        vm.restore(initialUrl)
    }
    val tab = ui.current
    LaunchedEffect(tab?.id, tab?.url) { address = tab?.url?.takeIf { it != HOME_URL }.orEmpty() }
    BackHandler(enabled = tab?.canGoBack == true) { vm.back() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
                            DropdownMenuItem(text = { Text(if (settings?.desktopMode == true) "Mobile site" else "Desktop site") }, onClick = { menu = false; vm.toggleDesktopMode() })
                            DropdownMenuItem(text = { Text("Share link") }, enabled = tab?.url?.startsWith("http") == true, onClick = { menu = false; tab?.url?.let(ctx::shareText) })
                            DropdownMenuItem(text = { Text("Open in external browser") }, enabled = tab?.url?.startsWith("http") == true, onClick = { menu = false; tab?.url?.let(ctx::openInBrowser) })
                            DropdownMenuItem(text = { Text("Clear cookies and cache") }, onClick = { menu = false; vm.clearData() })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.back() }, enabled = tab?.canGoBack == true) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    IconButton(onClick = { vm.forward() }, enabled = tab?.canGoForward == true) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                    IconButton(onClick = { vm.reload() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { vm.toggleBookmark() }, enabled = tab?.url?.startsWith("http") == true) { Icon(if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder, "Bookmark") }
                    Spacer(Modifier.weight(1f))
                    val media = tab?.media.orEmpty()
                    TextButton(onClick = { mediaDialog = true }, enabled = media.isNotEmpty()) {
                        Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("Media (${media.size})")
                    }
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
        }
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

    if (mediaDialog) AlertDialog(
        onDismissRequest = { mediaDialog = false },
        title = { Text("Media on this page") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { Text("Direct media files found on the page. Streams and DRM-protected media cannot be downloaded.", style = MaterialTheme.typography.bodySmall) }
                items(tab?.media.orEmpty(), key = { it.url }) { m ->
                    Row(Modifier.fillMaxWidth().clickable {
                        mediaDialog = false
                        app.requestAdd(AddRequest(listOf(m.url), buildMap { tab?.url?.let { put("Referer", it) } }, tab?.url))
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.kind, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
                        Text(m.name, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { mediaDialog = false }) { Text("Close") } },
    )
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
