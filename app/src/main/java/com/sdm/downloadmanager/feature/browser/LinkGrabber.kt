package com.sdm.downloadmanager.feature.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdm.core.common.FileCategory
import com.sdm.core.common.FileNames
import com.sdm.core.common.MimeTypes
import org.json.JSONArray

/** A link or media source found on a page. */
data class PageLink(val url: String, val name: String, val text: String?, val category: FileCategory, val hasFileExtension: Boolean)

object LinkGrabber {
    /** Collects link targets and media sources (already resolved to absolute URLs by the DOM). */
    const val SCAN_JS = """
        (function(){
          var out = [], seen = {};
          function add(u, t){ if (!u || seen[u] || !/^https?:/i.test(u) || out.length >= 3000) return; seen[u] = 1; out.push({u: u, t: (t || '').trim().slice(0, 120)}); }
          document.querySelectorAll('a[href]').forEach(function(a){ add(a.href, a.textContent || a.title); });
          document.querySelectorAll('img[src]').forEach(function(i){ add(i.currentSrc || i.src, i.alt); });
          document.querySelectorAll('video, audio, video source, audio source').forEach(function(m){ add(m.currentSrc || m.src, ''); });
          return JSON.stringify(out);
        })();
    """

    private val pageExtensions = setOf("html", "htm", "php", "asp", "aspx", "jsp", "cgi", "shtml", "xhtml")

    fun parse(json: String?): List<PageLink> {
        val raw = runCatching { org.json.JSONTokener(json ?: return emptyList()).nextValue() as? String }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("u").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val name = FileNames.fromUrl(url) ?: return@mapNotNull null
            val ext = FileNames.extension(name)
            val known = ext.isNotEmpty() && ext !in pageExtensions && MimeTypes.forExtension(ext) != null
            PageLink(url, name, o.optString("t").ifBlank { null }, FileCategory.of(name), known)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LinkGrabberSheet(links: List<PageLink>, onDismiss: () -> Unit, onDownload: (List<PageLink>) -> Unit) {
    var includePages by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<FileCategory?>(null) }
    val candidates = links.filter { includePages || it.hasFileExtension }
    val shown = candidates.filter { category == null || it.category == category }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Files on this page", style = MaterialTheme.typography.titleMedium)
            Text("${candidates.size} found. Pick the ones to download; each uses this tab's session.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(category == null, { category = null }, { Text("All (${candidates.size})") })
                candidates.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second }.forEach { (c, n) ->
                    FilterChip(category == c, { category = c }, { Text("${c.label} ($n)") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include web pages and links without a file type", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(includePages, { includePages = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val all = shown.isNotEmpty() && shown.all { it.url in selected }
                TextButton(onClick = { selected = if (all) selected - shown.map { it.url }.toSet() else selected + shown.map { it.url } }) { Text(if (all) "Select none" else "Select all") }
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(shown, key = { it.url }) { l ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (l.url in selected) selected - l.url else selected + l.url }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(l.url in selected, { selected = if (it) selected + l.url else selected - l.url })
                        Column(Modifier.weight(1f)) {
                            Text(l.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(l.text ?: l.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val chosen = links.filter { it.url in selected }
            Button(onClick = { onDownload(chosen) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(if (chosen.isEmpty()) "Select files to download" else "Download ${chosen.size} file(s)")
            }
        }
    }
}
