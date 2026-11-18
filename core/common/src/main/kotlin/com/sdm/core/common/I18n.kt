package com.sdm.core.common

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * App translations. Texts are written in English in the code; this maps them to the user's
 * language at the point they are shown (screen text, icon descriptions, snackbars, notifications,
 * the widget). Texts with values inside (counts, names) are matched as patterns where `{}` is the
 * value; the values themselves are translated too when they are known words ("Dark theme").
 * Unknown texts — file names, page titles, links — are shown unchanged.
 */
object I18n {
    /** "en", "in"/"id", or null to follow the device language. */
    @Volatile var override: String? = null

    private class Table(val exact: Map<String, String>, val patterns: List<Pair<Regex, String>>)

    private val tables = ConcurrentHashMap<String, Table>()
    private val cache = ConcurrentHashMap<String, String>()
    private val separator = " · "

    fun language(): String {
        val l = (override ?: Locale.getDefault().language).lowercase()
        return if (l == "in" || l == "id") "id" else l
    }

    private fun table(lang: String): Table? {
        if (lang == "en") return null
        return tables.getOrPut(lang) { load(lang) }.takeIf { it.exact.isNotEmpty() }
    }

    private fun load(lang: String): Table {
        val text = I18n::class.java.getResourceAsStream("/sdm-i18n/$lang.tsv")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return Table(emptyMap(), emptyList())
        val exact = HashMap<String, String>()
        val patterns = mutableListOf<Pair<Regex, String>>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val en = unescape(line.substring(0, tab))
            val tr = unescape(line.substring(tab + 1))
            if ("{}" in en) {
                val regex = en.split("{}").joinToString("(.*?)") { Regex.escape(it) }
                patterns += Regex("^$regex$", RegexOption.DOT_MATCHES_ALL) to tr
            } else exact[en] = tr
        }
        // Longer literal text first, so specific patterns win over general ones.
        patterns.sortByDescending { (r, _) -> r.pattern.replace("(.*?)", "").length }
        return Table(exact, patterns)
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\\", "\\")

    fun tr(text: String): String {
        if (text.isEmpty()) return text
        val lang = language()
        val t = table(lang) ?: return text
        val key = "$lang|$text"
        cache[key]?.let { return it }
        val result = translate(t, text, depth = 0)
        if (cache.size > 4000) cache.clear()
        cache[key] = result
        return result
    }

    private fun translate(t: Table, text: String, depth: Int): String {
        t.exact[text]?.let { return it }
        val trimmed = text.trim()
        if (trimmed != text && trimmed.isNotEmpty()) t.exact[trimmed]?.let { return text.replace(trimmed, it) }
        for ((regex, target) in t.patterns) {
            val m = regex.matchEntire(text) ?: continue
            val values = m.groupValues.drop(1).map { v -> if (depth < 1) translate(t, v, depth + 1) else v }
            val pieces = target.split("{}")
            return buildString {
                pieces.forEachIndexed { i, piece -> append(piece); if (i < pieces.size - 1) append(values.getOrElse(i) { "" }) }
            }
        }
        // "Site settings · 3 blocked": translate each part.
        if (depth < 2 && separator in text) {
            val parts = text.split(separator)
            val translated = parts.map { translate(t, it, depth + 1) }
            if (translated != parts) return translated.joinToString(separator)
        }
        return text
    }
}
