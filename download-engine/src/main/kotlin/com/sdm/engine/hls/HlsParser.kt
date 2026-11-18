package com.sdm.engine.hls

import java.net.URI

data class HlsVariant(
    val uri: String,
    val bandwidth: Long?,
    val resolution: String?,
    val codecs: String?,
    val audioGroup: String?,
    val frameRate: Double?,
) {
    val height: Int? get() = resolution?.substringAfter('x', "")?.toIntOrNull()
}

data class HlsRendition(val type: String, val groupId: String, val name: String?, val language: String?, val uri: String?, val isDefault: Boolean)

data class HlsMaster(val variants: List<HlsVariant>, val renditions: List<HlsRendition>)

/** Encryption of a segment. AES-128 (full-segment, key served by URI) is standard HLS; everything else is treated as DRM. */
data class HlsKey(val method: String, val uri: String?, val iv: ByteArray?, val keyFormat: String?) {
    val isNone: Boolean get() = method.equals("NONE", true)
    val isAes128: Boolean get() = method.equals("AES-128", true) && (keyFormat == null || keyFormat.equals("identity", true)) && uri != null
    val isDrm: Boolean get() = !isNone && !isAes128

    override fun equals(other: Any?) = other is HlsKey && other.method == method && other.uri == uri && other.keyFormat == keyFormat && (other.iv?.contentEquals(iv) ?: (iv == null))
    override fun hashCode() = (method + uri + keyFormat).hashCode()
}

data class HlsSegment(val uri: String, val durationSec: Double, val byteRange: LongRange?, val key: HlsKey?, val sequence: Long)

data class HlsMedia(
    val segments: List<HlsSegment>,
    val initSegment: HlsSegment?,
    val endList: Boolean,
    val targetDurationSec: Int?,
) {
    val totalDurationSec: Double get() = segments.sumOf { it.durationSec }
    val encrypted: Boolean get() = segments.any { it.key != null && !it.key.isNone }
    val drm: Boolean get() = segments.any { it.key?.isDrm == true } || initSegment?.key?.isDrm == true
    val fragmentedMp4: Boolean get() = initSegment != null
}

/** RFC 8216 playlist parser covering what downloaders need. */
object HlsParser {
    private val attr = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun isPlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")
    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    private fun attributes(line: String): Map<String, String> =
        attr.findAll(line.substringAfter(':', "")).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun resolve(base: String, uri: String): String = runCatching { URI(base).resolve(uri.trim()).toString() }.getOrDefault(uri.trim())

    fun parseMaster(text: String, baseUrl: String): HlsMaster {
        val variants = mutableListOf<HlsVariant>()
        val renditions = mutableListOf<HlsRendition>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> pending = attributes(line)
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(line)
                    renditions += HlsRendition(a["TYPE"] ?: "", a["GROUP-ID"] ?: "", a["NAME"], a["LANGUAGE"], a["URI"]?.let { resolve(baseUrl, it) }, a["DEFAULT"] == "YES")
                }
                line.isNotEmpty() && !line.startsWith("#") && pending != null -> {
                    val a = pending
                    variants += HlsVariant(resolve(baseUrl, line), a["BANDWIDTH"]?.toLongOrNull(), a["RESOLUTION"], a["CODECS"], a["AUDIO"], a["FRAME-RATE"]?.toDoubleOrNull())
                    pending = null
                }
            }
        }
        return HlsMaster(variants.distinctBy { it.uri }, renditions)
    }

    fun parseMedia(text: String, baseUrl: String): HlsMedia {
        val segments = mutableListOf<HlsSegment>()
        var key: HlsKey? = null
        var duration = 0.0
        var sequence = 0L
        var targetDuration: Int? = null
        var endList = false
        var init: HlsSegment? = null
        var byteRange: LongRange? = null
        var lastRangeEnd = 0L
        var expectUri = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-TARGETDURATION:") -> targetDuration = line.substringAfter(':').trim().toIntOrNull()
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:VOD") -> endList = true
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line)
                    key = HlsKey(a["METHOD"] ?: "NONE", a["URI"]?.let { resolve(baseUrl, it) }, a["IV"]?.let(::hex), a["KEYFORMAT"])
                    if (key.isNone) key = null
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line)
                    val range = a["BYTERANGE"]?.let { parseRange(it, 0) }
                    init = a["URI"]?.let { HlsSegment(resolve(baseUrl, it), 0.0, range, key, -1) }
                }
                line.startsWith("#EXTINF:") -> { duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0; expectUri = true }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    byteRange = parseRange(line.substringAfter(':'), lastRangeEnd)
                }
                line.isNotEmpty() && !line.startsWith("#") && expectUri -> {
                    segments += HlsSegment(resolve(baseUrl, line), duration, byteRange, key, sequence + segments.size)
                    byteRange?.let { lastRangeEnd = it.last + 1 }
                    byteRange = null
                    expectUri = false
                }
            }
        }
        return HlsMedia(segments, init, endList, targetDuration)
    }

    /** "length[@offset]" -> inclusive range. */
    private fun parseRange(spec: String, defaultOffset: Long): LongRange? {
        val len = spec.substringBefore('@').trim().toLongOrNull() ?: return null
        val off = if (spec.contains('@')) spec.substringAfter('@').trim().toLongOrNull() ?: return null else defaultOffset
        return off until off + len
    }

    private fun hex(s: String): ByteArray? {
        val h = s.removePrefix("0x").removePrefix("0X")
        if (h.length % 2 != 0) return null
        return runCatching { ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    /** Recognizes HLS and DASH links by their URL alone (used before any request is made). */
    fun streamKindOfUrl(url: String): StreamKind? {
        val l = url.lowercase().substringBefore('#')
        val path = l.substringBefore('?')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || l.contains(".m3u8?") || l.contains("format=m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") || l.contains(".mpd?") -> StreamKind.DASH
            else -> null
        }
    }

    fun isHlsContentType(type: String?): Boolean = type?.lowercase()?.let { "mpegurl" in it } == true
}

enum class StreamKind { HLS, DASH }
