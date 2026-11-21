package com.sdm.engine.hls

/** A downloadable quality of a stream, as shown to the user. */
data class StreamOption(
    val label: String,
    /** HLS media/variant playlist URL, or a [DashParser.ref] for DASH. */
    val videoPlaylistUrl: String,
    /** Separate audio track, when the stream keeps audio apart from video. */
    val audioPlaylistUrl: String?,
    val bandwidth: Long?,
    val height: Int?,
    val estimatedBytes: Long?,
    /** Group of alternative audio tracks this quality can be combined with (HLS AUDIO group). */
    val audioGroup: String? = null,
)

/** An alternative audio track (language or commentary). */
data class AudioTrack(val name: String, val language: String?, val url: String, val group: String?, val isDefault: Boolean, val bandwidth: Long? = null)

/** A subtitle track that can be saved next to the video as .srt. */
data class SubtitleTrack(val name: String, val language: String?, val url: String, val isDefault: Boolean)

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
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
) {
    /** Audio tracks that go with [option] (all of them when the stream doesn't group audio). */
    fun audioFor(option: StreamOption?): List<AudioTrack> = audioTracks.filter { option?.audioGroup == null || it.group == null || it.group == option.audioGroup }

    /** URL to download for "audio only": a separate audio track if there is one, else the smallest quality. */
    fun audioOnlySource(option: StreamOption?, audio: AudioTrack?): String? =
        audio?.url ?: option?.audioPlaylistUrl ?: options.minByOrNull { it.bandwidth ?: Long.MAX_VALUE }?.videoPlaylistUrl
}

/** Reads a stream's playlists or manifest (never its media) to list qualities, tracks and restrictions. */
class StreamInspector(private val hls: HlsDownloader) {
    suspend fun inspect(url: String, headers: Map<String, String>): StreamInfo {
        val (text, finalUrl) = hls.fetchText(url, headers)
        if (DashParser.isManifest(text)) return dash(text, finalUrl)
        require(HlsParser.isPlaylist(text)) { "Not an HLS playlist or DASH manifest" }
        if (!HlsParser.isMaster(text)) {
            val media = HlsParser.parseMedia(text, finalUrl)
            val option = StreamOption("Original", finalUrl, null, null, null, null)
            return info(finalUrl, listOf(option), media)
        }
        val master = HlsParser.parseMaster(text, finalUrl)
        val first = master.variants.maxByOrNull { it.bandwidth ?: 0 } ?: return StreamInfo(StreamKind.HLS, finalUrl, emptyList(), null, false, false, false, "Playlist has no variants")
        val probe = runCatching { hls.fetchText(first.uri, headers, finalUrl).let { (t, u) -> HlsParser.parseMedia(t, u) } }.getOrNull()
        val duration = probe?.totalDurationSec
        val audio = master.renditions.filter { it.type.equals("AUDIO", true) && it.uri != null }.map { r ->
            AudioTrack(r.name ?: r.language ?: "Audio", r.language, r.uri!!, r.groupId, r.isDefault)
        }.distinctBy { it.url }
        val subtitles = master.renditions.filter { it.type.equals("SUBTITLES", true) && it.uri != null }.map { r ->
            SubtitleTrack(r.name ?: r.language ?: "Subtitles", r.language, r.uri!!, r.isDefault)
        }.distinctBy { it.language to it.name }
        val options = master.variants.sortedWith(compareByDescending<HlsVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth ?: 0 }).map { v ->
            val group = audio.filter { it.group == v.audioGroup }
            val track = v.audioGroup?.let { (group.firstOrNull { it.isDefault } ?: group.firstOrNull())?.url }
            StreamOption(label(v.height, v.resolution, v.bandwidth, v.height == null && v.codecs?.contains("mp4a") == true && !v.codecs.contains("avc")), v.uri, track, v.bandwidth, v.height,
                estimate(v.bandwidth, duration), v.audioGroup)
        }
        val base = if (probe == null) StreamInfo(StreamKind.HLS, finalUrl, options, null, false, false, false, null) else info(finalUrl, options, probe)
        return base.copy(audioTracks = audio, subtitles = subtitles)
    }

    private fun dash(xml: String, url: String): StreamInfo {
        val m = runCatching { DashParser.parse(xml, url) }.getOrElse {
            return StreamInfo(StreamKind.DASH, url, emptyList(), null, false, false, false, "Could not read this DASH manifest")
        }
        val usable = m.representations.filter { it.segments != null }
        val videos = usable.filter { it.contentType == "video" && !it.isProtected }
        val audios = usable.filter { it.contentType == "audio" && !it.isProtected }
        val blocked = when {
            m.dynamic -> "This is a live stream; only finished videos can be downloaded"
            m.isProtected -> "This stream is DRM-protected; SDM does not bypass DRM"
            m.periods > 1 -> "This stream is split into several periods (often ads), which SDM can't join yet"
            videos.isEmpty() && audios.isEmpty() -> "This manifest has no media SDM can download"
            else -> null
        }
        val audioTracks = audios.groupBy { it.language ?: "" }.map { (_, reps) ->
            val best = reps.maxBy { it.bandwidth ?: 0 }
            AudioTrack(best.label ?: best.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Audio", best.language, DashParser.ref(url, best.id), null, false, best.bandwidth)
        }.let { tracks -> tracks.mapIndexed { i, t -> if (i == 0) t.copy(isDefault = true) else t } }
        val defaultAudio = audioTracks.firstOrNull()
        val options = if (videos.isEmpty()) audios.sortedByDescending { it.bandwidth ?: 0 }.map { a ->
            StreamOption(label(null, null, a.bandwidth, true), DashParser.ref(url, a.id), null, a.bandwidth, null, estimate(a.bandwidth, m.durationSec))
        } else videos.sortedWith(compareByDescending<DashRepresentation> { it.height ?: 0 }.thenByDescending { it.bandwidth ?: 0 }).map { v ->
            val total = (v.bandwidth ?: 0) + (defaultAudio?.bandwidth ?: 0)
            StreamOption(label(v.height, v.width?.let { w -> "${w}x${v.height}" }, v.bandwidth, false), DashParser.ref(url, v.id), defaultAudio?.url, v.bandwidth, v.height,
                estimate(total.takeIf { it > 0 }, m.durationSec))
        }
        val subtitles = usable.filter { it.contentType == "text" && it.mimeType?.contains("vtt") == true }.map { t ->
            SubtitleTrack(t.label ?: t.language ?: "Subtitles", t.language, DashParser.ref(url, t.id), false)
        }
        return StreamInfo(StreamKind.DASH, url, options, m.durationSec, m.dynamic, m.isProtected, false, blocked, audioTracks, subtitles)
    }

    private fun label(height: Int?, resolution: String?, bandwidth: Long?, audioOnly: Boolean) = buildString {
        append(height?.let { "${it}p" } ?: resolution ?: if (audioOnly) "Audio" else "Auto")
        bandwidth?.let { append(if (audioOnly) " · ${it / 1000} kbps" else " · ${"%.1f".format(java.util.Locale.US, it / 1_000_000.0)} Mbps") }
    }

    private fun estimate(bandwidth: Long?, duration: Double?) = if (duration != null && bandwidth != null) (bandwidth / 8.0 * duration).toLong() else null

    private fun info(url: String, options: List<StreamOption>, media: HlsMedia) = StreamInfo(
        StreamKind.HLS, url, options, media.totalDurationSec, !media.endList, media.drm, media.encrypted,
        when {
            media.drm -> "This stream is DRM-protected; SDM does not bypass DRM"
            !media.endList -> "This is a live stream; only finished videos can be downloaded"
            else -> null
        },
    )
}
