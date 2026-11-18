package com.sdm.engine

import com.sdm.engine.connection.RateLimiter
import com.sdm.engine.connection.defaultEngineClient
import com.sdm.engine.manager.DownloadEngine
import com.sdm.engine.segment.SegmentPlanner
import com.sdm.engine.storage.FileSink
import com.sdm.engine.storage.RandomAccessSink
import com.sdm.testserver.TestServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class DownloadEngineTest {
    private val server = TestServer()
    private val engine = DownloadEngine({ defaultEngineClient(5, 5) })
    private val files = mutableListOf<File>()

    @After fun tearDown() { server.close(); files.forEach { it.delete() } }

    private fun tempFile() = File.createTempFile("sdm", ".bin").also { files += it }

    private class Recorder : TransferListener {
        val events = CopyOnWriteArrayList<EngineEventType>()
        @Volatile var checkpoint: Checkpoint? = null
        @Volatile var probe: ProbeInfo? = null
        override fun onProbed(info: ProbeInfo) { probe = info }
        override suspend fun onCheckpoint(checkpoint: Checkpoint) { this.checkpoint = checkpoint }
        override fun onEvent(type: EngineEventType, detail: String) { events += type }
    }

    private fun request(path: String, connections: Int = 4, cp: Checkpoint? = null, checksum: ExpectedChecksum? = null) =
        TransferRequest(server.url(path), maxConnections = connections, minSegmentBytes = 64 * 1024, checkpoint = cp,
            expectedChecksum = checksum, retryBaseDelayMillis = 20, checkpointIntervalMillis = 100, progressIntervalMillis = 50)

    private fun download(req: TransferRequest, file: File, rec: Recorder = Recorder(), limiter: RateLimiter = RateLimiter()): TransferResult =
        runBlocking { FileSink(file).use { engine.run(req, it, limiter, rec) } }

    private fun completed(r: TransferResult): TransferResult.Completed {
        assertTrue("expected completion but was $r", r is TransferResult.Completed)
        return r as TransferResult.Completed
    }

    // 1, 3: HTTP + single connection
    @Test fun singleConnectionDownload() {
        val data = TestServer.randomBytes(300_000)
        server.serve("/a.bin", TestServer.Resource(data))
        val f = tempFile()
        val r = completed(download(request("/a.bin", connections = 1), f))
        assertArrayEquals(data, f.readBytes())
        assertEquals(1, r.stats.connectionsUsed)
        assertEquals("identity", server.requestsFor("/a.bin").first().headers["accept-encoding"])
    }

    // 4, 5: multi-connection with range detection
    @Test fun multiConnectionDownloadUsesRanges() {
        val data = TestServer.randomBytes(1_000_000)
        server.serve("/m.bin", TestServer.Resource(data))
        val f = tempFile()
        val rec = Recorder()
        val r = completed(download(request("/m.bin", connections = 4), f, rec))
        assertArrayEquals(data, f.readBytes())
        assertTrue(r.stats.rangeSupported)
        assertTrue(r.stats.connectionsUsed >= 2)
        assertTrue(server.requestsFor("/m.bin").count { it.headers["range"] != null } >= 4)
        assertTrue(rec.probe!!.rangeSupported)
    }

    // 11: server without Range support
    @Test fun serverWithoutRangesFallsBackToSingle() {
        val data = TestServer.randomBytes(500_000)
        server.serve("/n.bin", TestServer.Resource(data, supportsRanges = false))
        val f = tempFile()
        val r = completed(download(request("/n.bin", connections = 8), f))
        assertArrayEquals(data, f.readBytes())
        assertFalse(r.stats.rangeSupported)
        assertEquals(1, server.requestsFor("/n.bin").size)
    }

    // 6: range validation
    @Test fun lyingContentRangeIsDetectedWithoutCorruption() {
        val data = TestServer.randomBytes(800_000)
        val res = server.serve("/lie.bin", TestServer.Resource(data))
        res.lieAboutRangeBy = 0
        val f = tempFile()
        val rec = Recorder()
        // First request (bytes=0-) is honest; later segment requests lie.
        val req = request("/lie.bin", connections = 4)
        Thread { Thread.sleep(0) }.start()
        res.lieAboutRangeBy = 0
        val r = runBlocking {
            FileSink(f).use { sink ->
                engine.run(req, object : RandomAccessSink by sink {}, RateLimiter(), object : TransferListener by rec {
                    override fun onProbed(info: ProbeInfo) { rec.onProbed(info); res.lieAboutRangeBy = 1 }
                })
            }
        }
        completed(r)
        assertArrayEquals(data, f.readBytes())
        assertTrue(rec.events.contains(EngineEventType.FALLBACK_SINGLE_CONNECTION))
        assertTrue(r.stats.fellBackToSingle)
    }

    // 7, 9: pause and resume from a checkpoint with a new engine run (process death)
    @Test fun pauseThenResumeFromCheckpoint() {
        val data = TestServer.randomBytes(1_200_000)
        val res = server.serve("/p.bin", TestServer.Resource(data, bytesPerSecond = 600_000))
        val f = tempFile()
        val rec = Recorder()
        runBlocking {
            FileSink(f).use { sink ->
                val job = launch(Dispatchers.IO) { engine.run(request("/p.bin", connections = 2), sink, RateLimiter(), rec) }
                withTimeout(10_000) { while ((rec.checkpoint?.downloadedBytes ?: 0) < 200_000) kotlinx.coroutines.delay(20) }
                job.cancelAndJoin()
            }
        }
        val cp = assertNotNull(rec.checkpoint).let { rec.checkpoint!! }
        assertTrue(cp.downloadedBytes in 200_000 until data.size)
        res.bytesPerSecond = 0
        val before = server.requests.size
        val r = completed(download(request("/p.bin", connections = 2, cp = cp), f))
        assertArrayEquals(data, f.readBytes())
        val resumedRanges = server.requests.drop(before).mapNotNull { it.headers["range"] }
        assertTrue("resume must not restart from zero: $resumedRanges", resumedRanges.any { !it.startsWith("bytes=0-") })
        assertTrue(r.stats.bytesThisSession < data.size)
    }

    // 8: network interruption mid-body
    @Test fun networkInterruptionIsRetried() {
        val data = TestServer.randomBytes(600_000)
        val res = server.serve("/i.bin", TestServer.Resource(data))
        res.disconnectAfterBytes = 100_000
        res.disconnectsRemaining.set(2)
        val f = tempFile()
        val r = completed(download(request("/i.bin", connections = 2), f))
        assertArrayEquals(data, f.readBytes())
        assertTrue(r.stats.segmentFailures >= 1)
    }

    @Test fun resourceChangedBetweenRunsRestarts() {
        val v1 = TestServer.randomBytes(400_000, seed = 1)
        val res = server.serve("/c.bin", TestServer.Resource(v1, bytesPerSecond = 300_000))
        val f = tempFile()
        val rec = Recorder()
        runBlocking {
            FileSink(f).use { sink ->
                val job = launch(Dispatchers.IO) { engine.run(request("/c.bin", connections = 1), sink, RateLimiter(), rec) }
                withTimeout(10_000) { while ((rec.checkpoint?.downloadedBytes ?: 0) < 80_000) kotlinx.coroutines.delay(20) }
                job.cancelAndJoin()
            }
        }
        val v2 = TestServer.randomBytes(400_000, seed = 2)
        res.body = v2; res.etag = "\"v2\""; res.bytesPerSecond = 0
        val rec2 = Recorder()
        completed(download(request("/c.bin", connections = 2, cp = rec.checkpoint), f, rec2))
        assertArrayEquals(v2, f.readBytes())
        assertTrue(rec2.events.contains(EngineEventType.RESOURCE_CHANGED))
    }

    // 13: HTTP 403 (and expired link on resume)
    @Test fun forbiddenIsPermanentAndMarkedResumed() {
        val data = TestServer.randomBytes(200_000)
        val res = server.serve("/f.bin", TestServer.Resource(data, requiredCookie = "session=ok"))
        val f = tempFile()
        val fail = download(request("/f.bin"), f) as TransferResult.Failed
        assertEquals(403, fail.error.httpStatus)
        assertFalse(fail.error.retryable)
        assertFalse(fail.error.resumed)
        val cp = Checkpoint(200_000, true, Validators("\"v1\""), server.url("/f.bin"), listOf(SegmentState(0, 0, 199_999, 100_000)))
        val again = download(request("/f.bin", cp = cp), f) as TransferResult.Failed
        assertTrue(again.error.resumed)
        res.requiredCookie = null
    }

    @Test fun cookieHeaderIsForwarded() {
        val data = TestServer.randomBytes(100_000)
        server.serve("/auth.bin", TestServer.Resource(data, requiredCookie = "session=ok"))
        val f = tempFile()
        completed(download(request("/auth.bin").copy(headers = mapOf("Cookie" to "session=ok")), f))
        assertArrayEquals(data, f.readBytes())
    }

    @Test fun notFoundIsPermanent() {
        val f = tempFile()
        val fail = download(request("/missing.bin"), f) as TransferResult.Failed
        assertEquals(404, fail.error.httpStatus)
        assertFalse(fail.error.retryable)
    }

    // 14: HTTP 429 with Retry-After
    @Test fun tooManyRequestsCarriesRetryAfter() {
        val res = server.serve("/t.bin", TestServer.Resource(TestServer.randomBytes(1000)))
        res.scriptedStatuses += 429 to "120"
        val fail = download(request("/t.bin"), tempFile()) as TransferResult.Failed
        assertEquals(429, fail.error.httpStatus)
        assertEquals(120_000L, fail.error.retryAfterMillis)
        assertTrue(fail.error.retryable)
    }

    // 16: temporary server failures on segments are retried
    @Test fun temporarySegmentFailuresRecover() {
        val data = TestServer.randomBytes(400_000)
        val res = server.serve("/s.bin", TestServer.Resource(data))
        val f = tempFile()
        val rec = Recorder()
        val r = runBlocking {
            FileSink(f).use { sink ->
                engine.run(request("/s.bin", connections = 1), sink, RateLimiter(), object : TransferListener by rec {
                    override fun onProbed(info: ProbeInfo) { rec.onProbed(info) }
                })
            }
        }
        completed(r)
        assertArrayEquals(data, f.readBytes())
        // now with scripted 500s on the next requests: the probe fails once, engine reports retryable
        res.scriptedStatuses += 500 to null
        val fail = download(request("/s.bin"), tempFile()) as TransferResult.Failed
        assertTrue(fail.error.retryable)
    }

    @Test fun throttledExtraConnectionsAreDropped() {
        val data = TestServer.randomBytes(900_000)
        val res = server.serve("/th.bin", TestServer.Resource(data))
        val f = tempFile()
        val rec = Recorder()
        val r = runBlocking {
            FileSink(f).use { sink ->
                engine.run(request("/th.bin", connections = 4), sink, RateLimiter(), object : TransferListener by rec {
                    override fun onProbed(info: ProbeInfo) { rec.onProbed(info); repeat(2) { res.scriptedStatuses += 503 to "1" } }
                })
            }
        }
        completed(r)
        assertArrayEquals(data, f.readBytes())
        assertTrue(r.stats.throttled || r.stats.segmentFailures > 0)
    }

    // 15: HTTP 416 on a zero-byte file
    @Test fun emptyFileCompletes() {
        server.serve("/empty.bin", TestServer.Resource(ByteArray(0)))
        val f = tempFile()
        val r = completed(download(request("/empty.bin"), f))
        assertEquals(0, r.totalBytes)
        assertEquals(0, f.length())
    }

    @Test fun redirectsAreFollowedAndPinned() {
        val data = TestServer.randomBytes(300_000)
        server.serve("/real.bin", TestServer.Resource(data))
        server.redirect("/go", server.url("/real.bin"))
        val f = tempFile()
        val rec = Recorder()
        val r = completed(download(request("/go", connections = 4), f, rec))
        assertArrayEquals(data, f.readBytes())
        assertTrue(r.finalUrl.endsWith("/real.bin"))
        assertEquals(1, server.requestsFor("/go").size) // segments go straight to the pinned URL
    }

    @Test fun chunkedUnknownLength() {
        val data = TestServer.randomBytes(250_000)
        server.serve("/ch.bin", TestServer.Resource(data, supportsRanges = false, chunked = true))
        val f = tempFile()
        val r = completed(download(request("/ch.bin"), f))
        assertEquals(data.size.toLong(), r.totalBytes)
        assertArrayEquals(data, f.readBytes())
    }

    // 22: speed limiter enforced in the engine
    @Test fun speedLimitIsEnforced() {
        val data = TestServer.randomBytes(400_000)
        server.serve("/l.bin", TestServer.Resource(data))
        val f = tempFile()
        val start = System.nanoTime()
        completed(download(request("/l.bin", connections = 2), f, limiter = RateLimiter(200_000)))
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue("took $seconds s", seconds >= 1.4)
        assertArrayEquals(data, f.readBytes())
    }

    @Test fun checksumVerification() {
        val data = TestServer.randomBytes(200_000)
        server.serve("/sum.bin", TestServer.Resource(data))
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val ok = completed(download(request("/sum.bin", checksum = ExpectedChecksum("SHA-256", sha.uppercase())), tempFile()))
        assertEquals(sha, ok.checksumHex)
        val bad = download(request("/sum.bin", checksum = ExpectedChecksum("SHA256", "00")), tempFile()) as TransferResult.Failed
        assertEquals(ErrorKind.CHECKSUM_MISMATCH, bad.error.kind)
    }

    // 17: insufficient storage
    @Test fun storageFullIsReportedAsStorage() {
        server.serve("/full.bin", TestServer.Resource(TestServer.randomBytes(300_000)))
        val f = tempFile()
        val r = runBlocking {
            FileSink(f).use { real ->
                val failing = object : RandomAccessSink by real {
                    override fun write(position: Long, src: ByteArray, offset: Int, length: Int) {
                        if (position > 100_000) throw IOException("write failed: ENOSPC (No space left on device)")
                        real.write(position, src, offset, length)
                    }
                }
                engine.run(request("/full.bin", connections = 1), failing, RateLimiter(), Recorder())
            }
        }
        assertEquals(ErrorKind.STORAGE_FULL, (r as TransferResult.Failed).error.kind)
    }

    // 18: multiple simultaneous downloads
    @Test fun simultaneousDownloads() {
        val blobs = (1..3).map { TestServer.randomBytes(300_000, seed = it) }
        blobs.forEachIndexed { i, b -> server.serve("/sim$i.bin", TestServer.Resource(b)) }
        val outs = blobs.indices.map { tempFile() }
        runBlocking {
            blobs.indices.map { i ->
                async(Dispatchers.IO) { FileSink(outs[i]).use { engine.run(request("/sim$i.bin", connections = 2), it, RateLimiter(), Recorder()) } }
            }.awaitAll().forEach { completed(it) }
        }
        blobs.forEachIndexed { i, b -> assertArrayEquals(b, outs[i].readBytes()) }
    }

    @Test fun inspectReadsHeadersOnly() {
        server.serve("/info.zip", TestServer.Resource(TestServer.randomBytes(12_345), contentType = "application/zip", contentDisposition = "attachment; filename=\"pack.zip\""))
        val info = runBlocking { engine.inspect(server.url("/info.zip"), emptyMap()) }.getOrThrow()
        assertEquals(12_345L, info.totalBytes)
        assertTrue(info.rangeSupported)
        assertEquals("application/zip", info.contentType)
        assertTrue(info.contentDisposition!!.contains("pack.zip"))
    }

    // 10: large-file arithmetic (> 4 GB) without allocating the file
    @Test fun segmentPlanningHandlesFilesOver4GB() {
        val total = 5L * 1024 * 1024 * 1024 + 7
        val plan = SegmentPlanner.initialPlan(total, 16, 1L shl 20)
        assertEquals(16, plan.size)
        assertEquals(total - 1, plan.last().end)
        assertTrue(SegmentPlanner.coversCompletely(plan.map { it.copy(position = it.end + 1) }, total))
        assertEquals(null, SegmentPlanner.splitPoint(0, 100, 1L shl 20, 1))
        assertEquals(1L shl 21, SegmentPlanner.splitPoint(0, (1L shl 22) - 1, 1L shl 20, 1))
    }

    @Test fun limiterAllowsLiveChanges() {
        runBlocking {
            val limiter = RateLimiter(0)
            val start = System.nanoTime()
            repeat(10) { limiter.acquire(1_000_000) }
            assertTrue((System.nanoTime() - start) / 1e9 < 0.5)
            limiter.bytesPerSecond = 100_000
            val t0 = System.nanoTime()
            limiter.acquire(200_000)
            limiter.acquire(1)
            assertTrue((System.nanoTime() - t0) / 1e9 >= 1.0)
        }
    }
}
