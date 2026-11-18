package com.sdm.engine.hls

/** A downloadable quality of a stream, as shown to the user. */
data class StreamOption(
    val label: String,
    val videoPlaylistUrl: String,
    val audioPlaylistUrl: String?,
    val bandwidth: Long?,
    val height: Int?,
    val estimatedBytes: Long?,
)

data class StreamInfo(
    val kind: StreamKind,
    val sourceUrl: String,
    val options: List<StreamOption>,
    val durationSec: Double?,
    val live: Boolean,
    val drm: Boolean,
    val encrypted: Boolean,
    /** Why it cannot be downloaded, or null when it can. */
    val blockedReason: String?,
)

/** Reads a stream's playlists (never its media) to list qualities, duration and restrictions. */
class StreamInspector(private val hls: HlsDownloader) {
    suspend fun inspect(url: String, headers: Map<String, String>): StreamInfo {
        if (HlsParser.streamKindOfUrl(url) == StreamKind.DASH) {
            return StreamInfo(StreamKind.DASH, url, emptyList(), null, false, false, false, "DASH streams (.mpd) are not supported yet; only HLS and direct video files")
        }
        val (text, finalUrl) = hls.fetchText(url, headers)
        require(HlsParser.isPlaylist(text)) { "Not an HLS playlist" }
        if (!HlsParser.isMaster(text)) {
            val media = HlsParser.parseMedia(text, finalUrl)
            val option = StreamOption("Original", finalUrl, null, null, null, null)
            return info(finalUrl, listOf(option), media)
        }
        val master = HlsParser.parseMaster(text, finalUrl)
        val first = master.variants.maxByOrNull { it.bandwidth ?: 0 } ?: return StreamInfo(StreamKind.HLS, finalUrl, emptyList(), null, false, false, false, "Playlist has no variants")
        val probe = runCatching { hls.fetchText(first.uri, headers, finalUrl).let { (t, u) -> HlsParser.parseMedia(t, u) } }.getOrNull()
        val duration = probe?.totalDurationSec
        val options = master.variants.sortedWith(compareByDescending<HlsVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth ?: 0 }).map { v ->
            val audio = v.audioGroup?.let { g ->
                val group = master.renditions.filter { it.type.equals("AUDIO", true) && it.groupId == g && it.uri != null }
                (group.firstOrNull { it.isDefault } ?: group.firstOrNull())?.uri
            }
            val label = buildString {
                append(v.height?.let { "${it}p" } ?: v.resolution ?: "Auto")
                v.bandwidth?.let { append(" · ${"%.1f".format(it / 1_000_000.0)} Mbps") }
                if (v.height == null && v.codecs?.contains("mp4a") == true && v.codecs.contains("avc").not()) append(" (audio)")
            }
            StreamOption(label, v.uri, audio, v.bandwidth, v.height, if (duration != null && v.bandwidth != null) (v.bandwidth / 8.0 * duration).toLong() else null)
        }
        return if (probe == null) StreamInfo(StreamKind.HLS, finalUrl, options, null, false, false, false, null) else info(finalUrl, options, probe)
    }

    private fun info(url: String, options: List<StreamOption>, media: HlsMedia) = StreamInfo(
        StreamKind.HLS, url, options, media.totalDurationSec, !media.endList, media.drm, media.encrypted,
        when {
            media.drm -> "This stream is DRM-protected; SDM does not bypass DRM"
            !media.endList -> "This is a live stream; only finished videos can be downloaded"
            else -> null
        },
    )
}
