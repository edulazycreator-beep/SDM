package com.sdm.domain

import com.sdm.domain.intelligence.QueueIntelligence
import com.sdm.domain.intelligence.QueueItem
import com.sdm.domain.intelligence.QueueOrdering
import com.sdm.domain.intelligence.SpeedIntelligence
import com.sdm.domain.intelligence.ThroughputMeter
import com.sdm.domain.intelligence.TransferObservation
import com.sdm.domain.io.DownloadListCodec
import com.sdm.domain.io.DownloadListFile
import com.sdm.domain.io.ExportedDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligenceIoTest {
    private fun obs(c: Int, mbps: Long, fail: Int = 0) = TransferObservation("h", c, 100L shl 20, (100L shl 20) * 1000 / (mbps shl 20), fail, c, false, true)

    @Test fun recommendsFastestMeasuredConnectionCount() {
        val history = listOf(obs(1, 2), obs(1, 2), obs(4, 8), obs(4, 9), obs(8, 7), obs(8, 7))
        val r = SpeedIntelligence.recommend("h", history, userMax = 16)
        assertEquals(4, r.connections)
        assertEquals(6, r.sampleCount)
    }

    @Test fun neverExceedsUserLimit() {
        val history = listOf(obs(16, 20), obs(16, 20))
        assertEquals(4, SpeedIntelligence.recommend("h", history, userMax = 4).connections)
    }

    @Test fun backsOffWhenUnstable() {
        val history = listOf(obs(8, 10, fail = 6), obs(8, 10, fail = 6))
        val r = SpeedIntelligence.recommend("h", history, userMax = 16)
        assertTrue(r.unstable)
        assertEquals(4, r.connections)
    }

    @Test fun noHistoryUsesProfileDefaults() {
        val r = SpeedIntelligence.recommend("x", emptyList(), userMax = 2)
        assertEquals(2, r.connections)
        assertEquals(0, r.sampleCount)
    }

    @Test fun throughputMeterUsesRealSamples() {
        val m = ThroughputMeter(alpha = 1.0)
        m.sample(0, 0)
        assertEquals(1000L, m.sample(1000, 1000))
    }

    @Test fun queueOrdering() {
        val items = listOf(QueueItem(1, 1, 0, 500, 3), QueueItem(2, 3, 1, 100, 2), QueueItem(3, 1, 2, 50, 1, important = true))
        assertEquals(listOf(3L, 2L, 1L), QueueIntelligence.order(items, QueueOrdering.PRIORITY).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), QueueIntelligence.order(items, QueueOrdering.SMALLEST_FIRST).map { it.id })
        assertEquals(10L, QueueIntelligence.estimateSeconds(listOf(500, 500), 100))
        assertNull(QueueIntelligence.estimateSeconds(listOf(500, null), 100))
        assertNull(QueueIntelligence.estimateSeconds(listOf(500), 0))
    }

    @Test fun importExportRoundTripAndValidation() {
        val file = DownloadListFile(exportedAt = 1, downloads = listOf(ExportedDownload("https://a/x.zip", "x.zip"), ExportedDownload("javascript:alert(1)")))
        val r = DownloadListCodec.decode(DownloadListCodec.encode(file))
        assertEquals(listOf("https://a/x.zip"), r.valid.map { it.url })
        assertEquals(1, r.rejected.size)
        assertEquals(1, DownloadListCodec.decode("[\"https://b/y\"]").valid.size)
        val txt = DownloadListCodec.decodeTxt("# list\nhttps://a/1.zip\nnot a url\n\nhttps://a/1.zip")
        assertEquals(1, txt.valid.size)
        assertEquals(listOf("not a url"), txt.rejected)
    }

    @Test fun duplicatesNotByNameAlone() {
        val existing = listOf(DuplicateCandidate(1, "https://a/x.zip", "x.zip", 100, DownloadState.COMPLETED, null))
        assertEquals(DuplicateKind.SAME_URL_COMPLETED, DuplicateDetector.find("https://a/x.zip", "x.zip", null, null, existing)!!.kind)
        assertEquals(DuplicateKind.SAME_NAME_ONLY, DuplicateDetector.find("https://b/x.zip", "x.zip", 999, null, existing)!!.kind)
        assertEquals(DuplicateKind.SAME_NAME_AND_SIZE, DuplicateDetector.find("https://b/x.zip", "x.zip", 100, null, existing)!!.kind)
        assertNull(DuplicateDetector.find("https://b/y.zip", "y.zip", 100, null, existing))
    }
}
