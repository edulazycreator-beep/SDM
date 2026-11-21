package com.sdm.engine

import com.sdm.engine.connection.RateLimiter
import com.sdm.engine.connection.defaultEngineClient
import com.sdm.engine.hls.DashParser
import com.sdm.engine.hls.DashSegments
import com.sdm.engine.hls.HlsDownloader
import com.sdm.engine.hls.HlsListener
import com.sdm.engine.hls.HlsRequest
import com.sdm.engine.hls.StreamInspector
import com.sdm.engine.hls.StreamKind
import com.sdm.engine.hls.WebVtt
import com.sdm.engine.storage.FileSink
import com.sdm.testserver.TestServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashAndSubtitlesTest {
    private val server = TestServer()
    private val hls = HlsDownloader({ defaultEngineClient(5, 5) })
    private val files = mutableListOf<File>()

    @After fun tearDown() { server.close(); files.forEach { it.delete() } }
    private fun temp() = File.createTempFile("dash", ".mp4").also { files += it }
    private fun text(path: String, body: String, type: String = "application/dash+xml") = server.serve(path, TestServer.Resource(body.toByteArray(), contentType = type))

    private fun templateMpd(protected: Boolean = false, dynamic: Boolean = false) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="${if (dynamic) "dynamic" else "static"}" mediaPresentationDuration="PT12S">
          <Period>
            <AdaptationSet contentType="video" mimeType="video/mp4">
              ${if (protected) "<ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/>" else ""}
              <SegmentTemplate initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/seg${'$'}Number%03d${'$'}.m4s" startNumber="1" duration="4000" timescale="1000"/>
              <Representation id="v720" bandwidth="2000000" width="1280" height="720" codecs="avc1.64001f"/>
              <Representation id="v360" bandwidth="600000" width="640" height="360" codecs="avc1.4d401e"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
              <SegmentTemplate initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/t${'$'}Time${'$'}.m4s" timescale="48000">
                <SegmentTimeline><S t="0" d="192000" r="2"/></SegmentTimeline>
              </SegmentTemplate>
              <Representation id="aen" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
            <AdaptationSet contentType="text" mimeType="text/vtt" lang="id">
              <Representation id="sub-id" bandwidth="1000"><BaseURL>subs/id.vtt</BaseURL></Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test fun parsesTemplatesTimelinesAndTracks() {
        val m = DashParser.parse(templateMpd(), "https://cdn.example/v/manifest.mpd")
        assertEquals(12.0, m.durationSec!!, 0.001)
        val v = m.representations.first { it.id == "v720" }
        val segs = v.segments as DashSegments.Listed
        assertEquals("https://cdn.example/v/v720/init.mp4", segs.init)
        assertEquals(listOf("seg001", "seg002", "seg003"), segs.media.map { it.first.substringAfterLast('/').removeSuffix(".m4s") })
        val a = m.representations.first { it.id == "aen" }.segments as DashSegments.Listed
        assertEquals(listOf("t0", "t192000", "t384000"), a.media.map { it.first.substringAfterLast('/').removeSuffix(".m4s") })
        assertEquals(4.0, a.media[0].second, 0.001)
        assertEquals("https://cdn.example/v/subs/id.vtt", (m.representations.first { it.id == "sub-id" }.segments as DashSegments.Single).url)
        assertEquals(3723.5, DashParser.parseDuration("PT1H2M3.5S")!!, 0.001)
        assertEquals("a/00042-x.m4s", DashParser.substitute("a/${'$'}Number%05d${'$'}-${'$'}RepresentationID${'$'}.m4s", "x", null, 42, null))
    }

    @Test fun inspectorListsDashQualitiesAudioAndSubtitles() {
        text("/v/manifest.mpd", templateMpd())
        val info = runBlocking { StreamInspector(hls).inspect(server.url("/v/manifest.mpd"), emptyMap()) }
        assertEquals(StreamKind.DASH, info.kind)
        assertNull(info.blockedReason)
        assertEquals(listOf(720, 360), info.options.map { it.height })
        assertEquals(DashParser.ref(server.url("/v/manifest.mpd"), "aen"), info.options[0].audioPlaylistUrl)
        assertEquals((2_128_000 / 8.0 * 12).toLong(), info.options[0].estimatedBytes)
        assertEquals("id", info.subtitles.single().language)
    }

    @Test fun refusesProtectedAndLiveDash() {
        text("/p/manifest.mpd", templateMpd(protected = true))
        text("/l/manifest.mpd", templateMpd(dynamic = true))
        val p = runBlocking { StreamInspector(hls).inspect(server.url("/p/manifest.mpd"), emptyMap()) }
        val l = runBlocking { StreamInspector(hls).inspect(server.url("/l/manifest.mpd"), emptyMap()) }
        assertTrue(p.blockedReason!!.contains("DRM"))
        assertTrue(l.blockedReason!!.contains("live"))
        val r = runBlocking { FileSink(temp()).use { hls.run(HlsRequest(DashParser.ref(server.url("/p/manifest.mpd"), "v720")), it, RateLimiter(), object : HlsListener {}) } }
        assertTrue(r is TransferResult.Failed && r.error.kind == ErrorKind.UNSUPPORTED)
    }

    @Test fun downloadsDashRepresentationInitThenSegments() {
        text("/v/manifest.mpd", templateMpd())
        val init = TestServer.randomBytes(900, seed = 1).also { server.serve("/v/v360/init.mp4", TestServer.Resource(it)) }
        val segs = (1..3).map { n -> TestServer.randomBytes(20_000 + n, seed = n + 10).also { server.serve("/v/v360/seg%03d.m4s".format(n), TestServer.Resource(it)) } }
        val f = temp()
        val r = runBlocking { FileSink(f).use { hls.run(HlsRequest(DashParser.ref(server.url("/v/manifest.mpd"), "v360"), concurrency = 2), it, RateLimiter(), object : HlsListener {}) } }
        assertTrue("$r", r is TransferResult.Completed)
        assertArrayEquals(init + segs.reduce { a, b -> a + b }, f.readBytes())
    }

    @Test fun singleFileRepresentationIsFetchedInRanges() {
        val body = TestServer.randomBytes(3 * 1024 * 1024 + 123, seed = 7)
        server.serve("/s/video.mp4", TestServer.Resource(body))
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT10S"><Period>
              <AdaptationSet mimeType="video/mp4"><Representation id="v" bandwidth="1000" height="240"><BaseURL>video.mp4</BaseURL><SegmentBase indexRange="0-100"/></Representation></AdaptationSet>
            </Period></MPD>
        """.trimIndent()
        text("/s/manifest.mpd", mpd)
        val media = runBlocking { hls.resolveMedia(DashParser.ref(server.url("/s/manifest.mpd"), "v"), emptyMap()) }.first
        assertEquals(1, media.segments.size) // 3 MB fits in one 8 MB chunk
        val f = temp()
        val r = runBlocking { FileSink(f).use { hls.run(HlsRequest(DashParser.ref(server.url("/s/manifest.mpd"), "v")), it, RateLimiter(), object : HlsListener {}) } }
        assertTrue("$r", r is TransferResult.Completed)
        assertArrayEquals(body, f.readBytes())
        val chunks = DashParser.media(DashParser.parse(mpd, server.url("/s/manifest.mpd")).representations.single(), body.size.toLong(), chunkBytes = 1L shl 20)
        assertEquals(4, chunks.segments.size)
        assertEquals(body.size.toLong() - 1, chunks.segments.last().byteRange!!.last)
    }

    @Test fun webVttSegmentsBecomeOneSrtAlignedToVideo() {
        val a = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n1\n00:00:01.000 --> 00:00:03.500 align:start\n<c.yellow>Halo</c> <i>dunia</i>\n\n00:00:05.000 --> 00:00:06.000\nKedua &amp; terakhir\n"
        // Second segment starts 4 s later (360000 ticks of 90 kHz) and repeats the cue that spans the boundary.
        val b = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:1260000,LOCAL:00:00:00.000\n\n00:00:01.000 --> 00:00:02.000\nKedua &amp; terakhir\n\n00:00:03.000 --> 00:00:04.000\nKetiga\n"
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,500\nHalo <i>dunia</i>\n\n2\n00:00:05,000 --> 00:00:06,000\nKedua & terakhir\n\n3\n00:00:07,000 --> 00:00:08,000\nKetiga\n\n",
            WebVtt.toSrt(listOf(a, b)),
        )
    }

    @Test fun hlsSubtitlePlaylistIsDownloadedAsSrt() {
        server.serve("/h/sub0.vtt", TestServer.Resource("WEBVTT\n\n00:00:00.500 --> 00:00:01.000\nSatu\n".toByteArray(), contentType = "text/vtt"))
        server.serve("/h/sub1.vtt", TestServer.Resource("WEBVTT\n\n00:00:04.500 --> 00:00:05.000\nDua\n".toByteArray(), contentType = "text/vtt"))
        text("/h/subs.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nsub0.vtt\n#EXTINF:4.0,\nsub1.vtt\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
        val srt = runBlocking { hls.fetchSubtitles(server.url("/h/subs.m3u8"), mapOf("Cookie" to "a=1"), server.url("/h/master.m3u8")) }
        assertNotNull(srt)
        assertEquals("1\n00:00:00,500 --> 00:00:01,000\nSatu\n\n2\n00:00:04,500 --> 00:00:05,000\nDua\n\n", srt)
    }
}
