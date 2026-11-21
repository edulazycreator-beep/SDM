package com.sdm.engine

import com.sdm.engine.connection.RateLimiter
import com.sdm.engine.connection.defaultEngineClient
import com.sdm.engine.hls.HlsDownloader
import com.sdm.engine.hls.HlsListener
import com.sdm.engine.hls.HlsParser
import com.sdm.engine.hls.HlsRequest
import com.sdm.engine.hls.StreamInspector
import com.sdm.engine.hls.StreamKind
import com.sdm.engine.storage.FileSink
import com.sdm.testserver.TestServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsTest {
    private val server = TestServer()
    private val hls = HlsDownloader({ defaultEngineClient(5, 5) })
    private val files = mutableListOf<File>()

    @After fun tearDown() { server.close(); files.forEach { it.delete() } }
    private fun temp() = File.createTempFile("hls", ".ts").also { files += it }
    private fun text(path: String, body: String) = server.serve(path, TestServer.Resource(body.toByteArray(), contentType = "application/vnd.apple.mpegurl"))

    private fun segments(prefix: String, count: Int, size: Int = 50_000) = (0 until count).map { i ->
        TestServer.randomBytes(size + i * 7, seed = i + prefix.hashCode()).also { server.serve("/$prefix/seg$i.ts", TestServer.Resource(it)) }
    }

    private fun mediaPlaylist(prefix: String, count: Int, endList: Boolean = true, keyLine: String = "") = buildString {
        appendLine("#EXTM3U"); appendLine("#EXT-X-VERSION:3"); appendLine("#EXT-X-TARGETDURATION:4"); appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        if (keyLine.isNotEmpty()) appendLine(keyLine)
        repeat(count) { appendLine("#EXTINF:4.0,"); appendLine("seg$it.ts") }
        if (endList) appendLine("#EXT-X-ENDLIST")
    }

    private fun run(req: HlsRequest, f: File, listener: HlsListener = object : HlsListener {}) =
        runBlocking { FileSink(f).use { hls.run(req.copy(retryBaseDelayMillis = 20), it, RateLimiter(), listener) } }

    @Test fun downloadsSegmentsInOrder() {
        val segs = segments("v", 12)
        text("/v/index.m3u8", mediaPlaylist("v", 12))
        val f = temp()
        val r = run(HlsRequest(server.url("/v/index.m3u8"), concurrency = 4), f)
        assertTrue("$r", r is TransferResult.Completed)
        assertArrayEquals(segs.reduce { a, b -> a + b }, f.readBytes())
    }

    @Test fun decryptsAes128WithSequenceIv() {
        val key = ByteArray(16) { (it * 3 + 1).toByte() }
        server.serve("/e/key.bin", TestServer.Resource(key))
        val plain = (0 until 5).map { TestServer.randomBytes(30_000 + it, seed = 90 + it) }
        plain.forEachIndexed { i, p ->
            val iv = ByteBuffer.allocate(16).putLong(8, i.toLong()).array()
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            server.serve("/e/seg$i.ts", TestServer.Resource(c.doFinal(p)))
        }
        text("/e/index.m3u8", mediaPlaylist("e", 5, keyLine = "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\""))
        val f = temp()
        val r = run(HlsRequest(server.url("/e/index.m3u8")), f)
        assertTrue("$r", r is TransferResult.Completed)
        assertArrayEquals(plain.reduce { a, b -> a + b }, f.readBytes())
    }

    @Test fun refusesLiveAndDrm() {
        segments("l", 3)
        text("/l/live.m3u8", mediaPlaylist("l", 3, endList = false))
        val live = run(HlsRequest(server.url("/l/live.m3u8")), temp()) as TransferResult.Failed
        assertEquals(ErrorKind.UNSUPPORTED, live.error.kind)
        text("/l/drm.m3u8", mediaPlaylist("l", 3, keyLine = "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\""))
        val drm = run(HlsRequest(server.url("/l/drm.m3u8")), temp()) as TransferResult.Failed
        assertEquals(ErrorKind.UNSUPPORTED, drm.error.kind)
        assertTrue(drm.error.message.contains("DRM"))
    }

    @Test fun resumesFromCheckpoint() {
        val segs = segments("r", 6)
        text("/r/index.m3u8", mediaPlaylist("r", 6))
        val f = temp()
        val firstTwo = segs[0] + segs[1]
        f.writeBytes(firstTwo + ByteArray(1234)) // garbage after the checkpoint must be discarded
        val before = server.requests.size
        val r = run(HlsRequest(server.url("/r/index.m3u8"), resumeSegments = 2, resumeBytes = firstTwo.size.toLong()), f)
        assertTrue("$r", r is TransferResult.Completed)
        assertArrayEquals(segs.reduce { a, b -> a + b }, f.readBytes())
        val fetched = server.requests.drop(before).map { it.path }
        assertFalse(fetched.contains("/r/seg0.ts"))
        assertFalse(fetched.contains("/r/seg1.ts"))
    }

    @Test fun retriesFailedSegments() {
        val segs = segments("t", 4)
        text("/t/index.m3u8", mediaPlaylist("t", 4))
        server.serve("/t/seg2.ts", TestServer.Resource(segs[2])).scriptedStatuses += 503 to null
        val f = temp()
        val r = run(HlsRequest(server.url("/t/index.m3u8")), f)
        assertTrue("$r", r is TransferResult.Completed)
        assertArrayEquals(segs.reduce { a, b -> a + b }, f.readBytes())
        assertEquals(1, r.stats.segmentFailures)
    }

    @Test fun includesFmp4InitSegment() {
        val init = TestServer.randomBytes(1000, seed = 5)
        server.serve("/f/init.mp4", TestServer.Resource(init))
        val segs = (0 until 3).map { TestServer.randomBytes(20_000, seed = 40 + it).also { b -> server.serve("/f/seg$it.m4s", TestServer.Resource(b)) } }
        text("/f/index.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n" + (0 until 3).joinToString("") { "#EXTINF:2.0,\nseg$it.m4s\n" } + "#EXT-X-ENDLIST\n")
        val f = temp()
        var fmp4 = false
        val r = run(HlsRequest(server.url("/f/index.m3u8")), f, object : HlsListener { override fun onPlaylist(segments: Int, durationSec: Double, encrypted: Boolean, fragmentedMp4: Boolean) { fmp4 = fragmentedMp4 } })
        assertTrue("$r", r is TransferResult.Completed)
        assertTrue(fmp4)
        assertArrayEquals(init + segs.reduce { a, b -> a + b }, f.readBytes())
    }

    @Test fun inspectorListsQualitiesWithEstimates() {
        segments("m/lo", 3); segments("m/hi", 3)
        text("/m/lo/index.m3u8", mediaPlaylist("m/lo", 3))
        text("/m/hi/index.m3u8", mediaPlaylist("m/hi", 3))
        text("/m/audio/index.m3u8", mediaPlaylist("m/audio", 3))
        text("/m/master.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
            lo/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"
            hi/index.m3u8
        """.trimIndent())
        val info = runBlocking { StreamInspector(hls).inspect(server.url("/m/master.m3u8"), emptyMap()) }
        assertNull(info.blockedReason)
        assertEquals(12.0, info.durationSec!!, 0.01)
        assertEquals(listOf(1080, 360), info.options.map { it.height })
        assertEquals(4_000_000L / 8 * 12, info.options.first().estimatedBytes)
        assertTrue(info.options.first().audioPlaylistUrl!!.endsWith("/m/audio/index.m3u8"))
    }

    @Test fun parserHandlesByteRangesAndUrlKinds() {
        val m = HlsParser.parseMedia("#EXTM3U\n#EXTINF:1,\n#EXT-X-BYTERANGE:100@0\nall.ts\n#EXTINF:1,\n#EXT-X-BYTERANGE:50\nall.ts\n#EXT-X-ENDLIST", "https://h/p/x.m3u8")
        assertEquals(0L..99L, m.segments[0].byteRange)
        assertEquals(100L..149L, m.segments[1].byteRange)
        assertEquals("https://h/p/all.ts", m.segments[0].uri)
        assertEquals(StreamKind.HLS, HlsParser.streamKindOfUrl("https://cdn/x/playlist.m3u8?token=1"))
        assertEquals(StreamKind.DASH, HlsParser.streamKindOfUrl("https://cdn/x/manifest.mpd"))
        assertNull(HlsParser.streamKindOfUrl("https://cdn/x/video.mp4"))
        assertNotNull(HlsParser.parseMaster("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8", "https://h/").variants.single())
    }

    @Test
    fun credentialsOnlyGoToThePlaylistSite() {
        val h = mapOf("Cookie" to "sid=1", "Authorization" to "Bearer x", "User-Agent" to "UA", "Referer" to "https://site.example/")
        val same = HlsDownloader.scopedHeaders(h, "https://site.example/v/master.m3u8", "https://cdn.site.example/seg1.ts")
        assertEquals(h, same)
        val other = HlsDownloader.scopedHeaders(h, "https://site.example/v/master.m3u8", "https://othercdn.net/seg1.ts")
        assertEquals(setOf("User-Agent", "Referer"), other.keys)
    }
}
