package com.sdm.engine.hls

/**
 * Joins WebVTT segments (as served by HLS subtitle playlists) into one SubRip file. Handles
 * `X-TIMESTAMP-MAP` so cue times line up with the video, drops cues repeated across segments and
 * keeps only the formatting SubRip supports (italic, bold, underline).
 */
object WebVtt {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val timing = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s+-->\s+((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val mpegTs = Regex("""MPEGTS:(\d+)""")
    private val localTime = Regex("""LOCAL:([\d:.,]+)""")
    private val tag = Regex("""</?([a-zA-Z]+)[^>]*>""")

    fun parse(parts: List<String>): List<Cue> {
        val cues = LinkedHashMap<Triple<Long, Long, String>, Cue>()
        var firstMpegTs: Long? = null
        for (raw in parts) {
            val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
            var offsetMs = 0L
            lines.firstOrNull { it.startsWith("X-TIMESTAMP-MAP") }?.let { line ->
                val mpegts = mpegTs.find(line)?.groupValues?.get(1)?.toLongOrNull()
                val local = localTime.find(line)?.groupValues?.get(1)?.let { parseTime(it) } ?: 0L
                if (mpegts != null) {
                    val first = firstMpegTs ?: mpegts.also { firstMpegTs = it }
                    offsetMs = (mpegts - first) / 90 - local
                }
            }
            var i = 0
            while (i < lines.size) {
                val t = timing.find(lines[i])
                if (t == null) { i++; continue }
                val start = parseTime(t.groupValues[1])
                val end = parseTime(t.groupValues[2])
                i++
                val text = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) { if (text.isNotEmpty()) text.append('\n'); text.append(lines[i]); i++ }
                if (start == null || end == null) continue
                val clean = cleanText(text.toString())
                if (clean.isBlank()) continue
                val cue = Cue((start + offsetMs).coerceAtLeast(0), (end + offsetMs).coerceAtLeast(0), clean)
                cues.putIfAbsent(Triple(cue.startMs, cue.endMs, cue.text), cue)
            }
        }
        return cues.values.sortedBy { it.startMs }
    }

    fun toSrt(parts: List<String>): String = buildString {
        parse(parts).forEachIndexed { n, c ->
            append(n + 1).append('\n')
            append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    private fun cleanText(text: String): String =
        tag.replace(text) { m ->
            val name = m.groupValues[1].lowercase()
            if (name in setOf("i", "b", "u")) (if (m.value.startsWith("</")) "</$name>" else "<$name>") else ""
        }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()

    fun parseTime(s: String): Long? {
        val parts = s.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val m = parts[parts.size - 2].toLongOrNull() ?: return null
        val secParts = parts.last().split('.')
        val sec = secParts[0].toLongOrNull() ?: return null
        val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        return ((h * 60 + m) * 60 + sec) * 1000 + ms
    }

    fun srtTime(ms: Long): String {
        val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val r = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, r)
    }
}
