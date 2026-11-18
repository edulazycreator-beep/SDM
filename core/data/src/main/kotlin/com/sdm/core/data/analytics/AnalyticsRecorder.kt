package com.sdm.core.data.analytics

import com.sdm.core.common.Urls
import com.sdm.core.database.AnalyticsDao
import com.sdm.core.database.DownloadEntity
import com.sdm.core.database.ThroughputSampleEntity
import com.sdm.core.database.TransferObservationEntity
import com.sdm.domain.intelligence.TransferObservation
import com.sdm.engine.TransferStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Records only real events from the engine (spec section 17: analytics reflect real events). */
@Singleton
class AnalyticsRecorder @Inject constructor(private val dao: AnalyticsDao) {
    suspend fun sample(downloadId: Long, url: String, bytes: Long, intervalMillis: Long, connections: Int) {
        if (bytes <= 0) return
        dao.insertSample(ThroughputSampleEntity(downloadId = downloadId, host = Urls.host(url) ?: "unknown", timestamp = System.currentTimeMillis(), bytes = bytes, intervalMillis = intervalMillis, activeConnections = connections))
    }

    suspend fun session(d: DownloadEntity, stats: TransferStats, succeeded: Boolean) {
        val host = Urls.host(d.resolvedUrl ?: d.originalUrl) ?: "unknown"
        if (stats.bytesThisSession > 0 || !succeeded) {
            dao.insertObservation(TransferObservationEntity(
                downloadId = d.id, host = host, connections = stats.connectionsUsed.coerceAtLeast(1), bytes = stats.bytesThisSession,
                activeMillis = stats.activeMillis, segmentFailures = stats.segmentFailures, segments = stats.segments.coerceAtLeast(1),
                throttled = stats.throttled, rangeSupported = stats.rangeSupported, succeeded = succeeded, timestamp = System.currentTimeMillis(),
            ))
        }
        dao.record(LocalDate.now().toString(), d.category, stats.bytesThisSession, if (succeeded) 1 else 0, if (succeeded) 0 else 1, stats.activeMillis)
    }

    suspend fun failedWithoutTransfer(d: DownloadEntity) = dao.record(LocalDate.now().toString(), d.category, 0, 0, 1, 0)

    suspend fun observations(): List<TransferObservation> = dao.observations().map { it.toDomain() }
    fun observeObservations(): Flow<List<TransferObservation>> = dao.observeObservations().map { list -> list.map { it.toDomain() } }

    suspend fun prune() = dao.pruneSamples(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)

    private fun TransferObservationEntity.toDomain() = TransferObservation(host, connections, bytes, activeMillis, segmentFailures, segments, throttled, rangeSupported)
}
