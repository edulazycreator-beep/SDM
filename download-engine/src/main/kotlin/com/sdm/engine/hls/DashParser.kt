package com.sdm.engine.hls

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import org.xml.sax.InputSource

/** How a DASH representation's media is addressed. */
sealed interface DashSegments {
    /** Init segment plus media segments, each a URL (SegmentTemplate or SegmentList). */
    data class Listed(val init: String?, val media: List<Pair<String, Double>>) : DashSegments
    /** One file (SegmentBase / BaseURL only). */
    data class Single(val url: String) : DashSegments
}

data class DashRepresentation(
    val id: String,
    /** "video", "audio" or "text". */
    val contentType: String,
    val mimeType: String?,
    val codecs: String?,
    val bandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val language: String?,
    val label: String?,
    val isProtected: Boolean,
    val segments: DashSegments?,
)

data class DashManifest(
    val dynamic: Boolean,
    val durationSec: Double?,
    val periods: Int,
    val representations: List<DashRepresentation>,
) {
    val isProtected: Boolean get() = representations.filter { it.contentType == "video" }.let { v -> v.isNotEmpty() && v.all { it.isProtected } }
}

/**
 * MPEG-DASH (ISO/IEC 23009-1) manifest parser for on-demand, unprotected streams: SegmentTemplate
 * (with `$Number$` or a SegmentTimeline), SegmentList and single-file representations. Content
 * protection is reported, never handled.
 */
object DashParser {
    const val REP_MARKER = "#sdm-rep="

    fun isManifest(text: String): Boolean = text.trimStart('﻿', ' ', '\n', '\r', '\t').let { it.startsWith("<?xml") || it.startsWith("<MPD") } && text.contains("<MPD")

    /** A stable reference to one representation of a manifest, usable where a playlist URL is expected. */
    fun ref(mpdUrl: String, repId: String): String = mpdUrl.substringBefore('#') + REP_MARKER + java.net.URLEncoder.encode(repId, "UTF-8")

    fun parseRef(ref: String): Pair<String, String>? {
        val i = ref.indexOf(REP_MARKER)
        if (i < 0) return null
        return ref.substring(0, i) to java.net.URLDecoder.decode(ref.substring(i + REP_MARKER.length), "UTF-8")
    }

    fun parse(xml: String, baseUrl: String): DashManifest {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val mpd = doc.documentElement
        val dynamic = mpd.getAttribute("type").equals("dynamic", true)
        val duration = parseDuration(mpd.getAttribute("mediaPresentationDuration"))
        val periods = children(mpd, "Period")
        val mpdBase = resolve(baseUrl, child(mpd, "BaseURL")?.textContent)
        val reps = mutableListOf<DashRepresentation>()
        periods.firstOrNull()?.let { period ->
            val periodDuration = parseDuration(period.getAttribute("duration")) ?: duration
            val periodBase = resolve(mpdBase, child(period, "BaseURL")?.textContent)
            for (set in children(period, "AdaptationSet")) {
                val setBase = resolve(periodBase, child(set, "BaseURL")?.textContent)
                val setProtected = children(set, "ContentProtection").isNotEmpty()
                val setTemplate = child(set, "SegmentTemplate")
                val setList = child(set, "SegmentList")
                for (rep in children(set, "Representation")) {
                    val mime = attr(rep, "mimeType") ?: attr(set, "mimeType")
                    val type = (attr(set, "contentType") ?: mime?.substringBefore('/'))?.lowercase().let { t ->
                        when {
                            t == "video" || t == "audio" -> t
                            t == "text" || mime == "application/ttml+xml" || mime?.contains("vtt") == true -> "text"
                            else -> t ?: "unknown"
                        }
                    }
                    val id = attr(rep, "id") ?: continue
                    val bandwidth = attr(rep, "bandwidth")?.toLongOrNull()
                    val repBase = resolve(setBase, child(rep, "BaseURL")?.textContent)
                    val template = merge(setTemplate, child(rep, "SegmentTemplate"))
                    val list = child(rep, "SegmentList") ?: setList
                    val segments = when {
                        template != null -> templateSegments(template, repBase, id, bandwidth, periodDuration)
                        list != null -> listSegments(list, repBase)
                        child(rep, "BaseURL") != null || child(set, "BaseURL") != null -> DashSegments.Single(repBase)
                        else -> null
                    }
                    reps += DashRepresentation(
                        id = id, contentType = type, mimeType = mime, codecs = attr(rep, "codecs") ?: attr(set, "codecs"), bandwidth = bandwidth,
                        width = (attr(rep, "width") ?: attr(set, "width"))?.toIntOrNull(), height = (attr(rep, "height") ?: attr(set, "height"))?.toIntOrNull(),
                        language = attr(set, "lang") ?: attr(rep, "lang"), label = child(set, "Label")?.textContent?.trim()?.ifBlank { null },
                        isProtected = setProtected || children(rep, "ContentProtection").isNotEmpty(), segments = segments,
                    )
                }
            }
        }
        return DashManifest(dynamic, duration, periods.size, reps)
    }

    /** Media for one representation in the shape the segment downloader uses. */
    fun media(rep: DashRepresentation, singleFileLength: Long?, chunkBytes: Long = 8L shl 20): HlsMedia {
        val segs = rep.segments ?: throw IllegalArgumentException("Representation ${rep.id} has no addressable media")
        return when (segs) {
            is DashSegments.Listed -> HlsMedia(
                segments = segs.media.mapIndexed { i, (u, d) -> HlsSegment(u, d, null, null, i.toLong()) },
                initSegment = segs.init?.let { HlsSegment(it, 0.0, null, null, -1) },
                endList = true, targetDurationSec = null,
            )
            is DashSegments.Single -> {
                val parts = if (singleFileLength == null || singleFileLength <= 0) listOf(HlsSegment(segs.url, 0.0, null, null, 0))
                else (0 until ceil(singleFileLength / chunkBytes.toDouble()).toInt()).map { i ->
                    val start = i * chunkBytes
                    HlsSegment(segs.url, 0.0, start..minOf(singleFileLength, start + chunkBytes) - 1, null, i.toLong())
                }
                HlsMedia(parts, null, endList = true, targetDurationSec = null)
            }
        }
    }

    private fun templateSegments(t: Map<String, String>, base: String, id: String, bandwidth: Long?, periodDuration: Double?): DashSegments? {
        val media = t["media"] ?: return null
        val timescale = t["timescale"]?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        val startNumber = t["startNumber"]?.toLongOrNull() ?: 1L
        fun fill(pattern: String, number: Long?, time: Long?) = resolve(base, substitute(pattern, id, bandwidth, number, time))
        val init = t["initialization"]?.let { fill(it, null, null) }
        val timeline = t["timeline"]
        val list = mutableListOf<Pair<String, Double>>()
        if (timeline != null) {
            var time = 0L
            var number = startNumber
            val endTime = periodDuration?.let { (it * timescale).toLong() }
            for (entry in timeline.split(';').filter { it.isNotBlank() }) {
                val (tAttr, dAttr, rAttr) = entry.split(',').let { Triple(it[0], it[1], it[2]) }
                if (tAttr.isNotEmpty()) time = tAttr.toLong()
                val d = dAttr.toLong()
                var repeat = rAttr.toIntOrNull() ?: 0
                if (repeat < 0) repeat = if (endTime != null && d > 0) (ceil((endTime - time) / d.toDouble()).toInt() - 1).coerceAtLeast(0) else 0
                repeat(repeat + 1) {
                    list += fill(media, number, time) to d / timescale.toDouble()
                    time += d; number++
                }
            }
        } else {
            val d = t["duration"]?.toLongOrNull() ?: return null
            val total = periodDuration ?: return null
            val count = ceil(total * timescale / d).toInt()
            for (i in 0 until count) {
                val segDur = minOf(d.toDouble() / timescale, total - i * d.toDouble() / timescale)
                list += fill(media, startNumber + i, i * d) to segDur
            }
        }
        return DashSegments.Listed(init, list)
    }

    private fun listSegments(list: Element, base: String): DashSegments {
        val timescale = attr(list, "timescale")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        val d = attr(list, "duration")?.toLongOrNull()
        val init = child(list, "Initialization")?.let { attr(it, "sourceURL") }?.let { resolve(base, it) }
        val media = children(list, "SegmentURL").mapNotNull { attr(it, "media") }.map { resolve(base, it) to (d?.let { it / timescale.toDouble() } ?: 0.0) }
        return DashSegments.Listed(init, media)
    }

    /** Representation-level SegmentTemplate attributes override the AdaptationSet's. */
    private fun merge(set: Element?, rep: Element?): Map<String, String>? {
        if (set == null && rep == null) return null
        val out = mutableMapOf<String, String>()
        for (e in listOfNotNull(set, rep)) {
            for (name in listOf("media", "initialization", "timescale", "startNumber", "duration")) attr(e, name)?.let { out[name] = it }
            child(e, "SegmentTimeline")?.let { tl ->
                out["timeline"] = children(tl, "S").joinToString(";") { s -> "${s.getAttribute("t")},${s.getAttribute("d")},${s.getAttribute("r")}" }
            }
        }
        return out
    }

    private val identifier = Regex("""\$(RepresentationID|Number|Time|Bandwidth)(%0(\d+)d)?\$""")

    fun substitute(pattern: String, id: String, bandwidth: Long?, number: Long?, time: Long?): String =
        identifier.replace(pattern) { m ->
            val value: Any? = when (m.groupValues[1]) {
                "RepresentationID" -> id
                "Number" -> number
                "Time" -> time
                else -> bandwidth
            }
            val width = m.groupValues[3].toIntOrNull()
            when {
                value == null -> m.value
                width != null && value is Long -> value.toString().padStart(width, '0')
                else -> value.toString()
            }
        }.replace("$$", "$")

    /** ISO 8601 durations as used by DASH, e.g. "PT1H2M3.5S" or "P0Y0M0DT0H10M0S". */
    fun parseDuration(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val m = Regex("""P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?""").matchEntire(value.trim()) ?: return null
        val g = m.groupValues
        fun n(i: Int) = g[i].toDoubleOrNull() ?: 0.0
        return n(3) * 86400 + n(4) * 3600 + n(5) * 60 + n(6) + n(1) * 365 * 86400 + n(2) * 30 * 86400
    }

    private fun resolve(base: String, uri: String?): String = if (uri.isNullOrBlank()) base else HlsParser.resolve(base, uri.trim())
    private fun attr(e: Element, name: String): String? = e.getAttribute(name).ifBlank { null }
    private fun local(e: Element) = e.tagName.substringAfter(':')
    private fun children(e: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.takeIf { local(it) == name }?.let { out += it }
        return out
    }
    private fun child(e: Element, name: String): Element? = children(e, name).firstOrNull()
}
