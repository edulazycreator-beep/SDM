package com.sdm.core.data.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Copies the audio and video tracks of downloaded stream segments (MPEG-TS or fragmented MP4)
 * into a regular MP4 without re-encoding. Takes the first video track found and the first audio
 * track found across the inputs (video rendition first, separate audio rendition second).
 */
object Remuxer {
    data class Probe(val hasVideo: Boolean, val hasAudio: Boolean)

    fun probe(inputs: List<File>): Probe {
        var v = false; var a = false
        for (f in inputs) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.path)
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/")) v = true
                    if (mime.startsWith("audio/")) a = true
                }
            } catch (_: Exception) {
            } finally { ex.release() }
        }
        return Probe(v, a)
    }

    /** Returns true on success. On failure the caller keeps the original segments file. */
    fun toMp4(inputs: List<File>, output: FileDescriptor, audioOnly: Boolean = false): Boolean {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        try {
            data class Source(val extractor: MediaExtractor, val track: Int, var muxTrack: Int = -1, val maxInput: Int)
            val sources = mutableListOf<Source>()
            var haveVideo = false; var haveAudio = false
            for (f in inputs) {
                val ex = MediaExtractor().also { extractors += it }
                ex.setDataSource(f.path)
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    val take = (mime.startsWith("video/") && !haveVideo && !audioOnly) || (mime.startsWith("audio/") && !haveAudio)
                    if (!take) continue
                    if (mime.startsWith("video/")) haveVideo = true else haveAudio = true
                    val max = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                    sources += Source(ex, i, maxInput = maxOf(max, 2 * 1024 * 1024))
                }
            }
            if (sources.isEmpty()) return false
            val m = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            for (s in sources) s.muxTrack = m.addTrack(s.extractor.getTrackFormat(s.track))
            m.start()
            val buffer = ByteBuffer.allocateDirect(sources.maxOf { it.maxInput })
            val info = MediaCodec.BufferInfo()
            fun select(s: Source) {
                for (i in 0 until s.extractor.trackCount) s.extractor.unselectTrack(i)
                s.extractor.selectTrack(s.track)
                s.extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            // One time base for all tracks keeps audio and video in sync (streams rarely start at 0).
            val base = sources.minOf { s -> select(s); s.extractor.sampleTime.takeIf { it >= 0 } ?: 0L }
            for (s in sources) {
                val ex = s.extractor
                select(s)
                while (true) {
                    buffer.clear()
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) break
                    val pts = ex.sampleTime
                    info.set(0, size, (pts - base).coerceAtLeast(0), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(s.muxTrack, buffer, info)
                    ex.advance()
                }
            }
            m.stop()
            return true
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }
}
