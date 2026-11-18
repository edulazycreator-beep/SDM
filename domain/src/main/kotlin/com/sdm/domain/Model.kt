package com.sdm.domain

import kotlinx.serialization.Serializable

/** Why a QUEUED download is not being started. Keeps the spec's ten states intact (see ADR-0004). */
enum class HoldReason { NONE, SCHEDULE, METERED_NETWORK, BATTERY, STORAGE, RULE, USER_CONFIRMATION, START_TIME }

enum class Priority(val weight: Int) { LOW(0), NORMAL(1), HIGH(2), CRITICAL(3) }

@Serializable
enum class NetworkPolicy { ANY, UNMETERED_ONLY, WIFI_ONLY }

enum class DestinationType { MEDIASTORE, SAF_TREE, APP_PRIVATE }

enum class RangeSupport { UNKNOWN, SUPPORTED, UNSUPPORTED }

/** Connection profiles allowed by spec section 6.1. */
val CONNECTION_PROFILES = listOf(1, 2, 4, 8, 16)

data class Download(
    val id: Long,
    val originalUrl: String,
    val resolvedUrl: String?,
    val fileName: String,
    val mimeType: String?,
    val category: com.sdm.core.common.FileCategory,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val state: DownloadState,
    val holdReason: HoldReason,
    val priority: Priority,
    val queueId: Long,
    val queuePosition: Long,
    val maxConnections: Int,
    val activeConnections: Int,
    val speedLimitBps: Long,
    val networkPolicy: NetworkPolicy,
    val destinationType: DestinationType,
    val destinationUri: String?,
    val destinationLabel: String,
    val rangeSupport: RangeSupport,
    val errorCode: ErrorCode?,
    val errorMessage: String?,
    val httpStatus: Int?,
    val retryCount: Int,
    val nextRetryAt: Long?,
    val scheduledAt: Long?,
    val checksumAlgorithm: String?,
    val checksumExpected: String?,
    val checksumActual: String?,
    val sourcePageUrl: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val activeMillis: Long,
    val important: Boolean = false,
) {
    val progressPercent: Int get() = com.sdm.core.common.Formatters.percent(downloadedBytes, totalBytes)
    val averageSpeedBps: Long get() = if (activeMillis <= 0) 0 else downloadedBytes * 1000 / activeMillis
    val host: String? get() = com.sdm.core.common.Urls.host(originalUrl)
}

/** Live values that change several times a second; never written to the database per tick. */
data class LiveProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val speedBps: Long,
    val activeConnections: Int,
    val segments: List<SegmentProgressView> = emptyList(),
) {
    val etaSeconds: Long? get() = if (totalBytes == null || speedBps <= 0) null else (totalBytes - downloadedBytes).coerceAtLeast(0) / speedBps
}

data class SegmentProgressView(val start: Long, val end: Long, val position: Long, val active: Boolean)

data class DownloadEventRecord(
    val id: Long,
    val downloadId: Long?,
    val timestamp: Long,
    val type: EventType,
    val httpStatus: Int?,
    val errorCode: ErrorCode?,
    val detail: String?,
)

enum class EventType {
    CREATED, STARTED, PAUSED, RESUMED, PROGRESS_CHECKPOINT, RETRY_SCHEDULED, SEGMENT_FAILED, FALLBACK_SINGLE_CONNECTION,
    CONNECTIONS_REDUCED, RESOURCE_CHANGED, NETWORK_LOST, NETWORK_RESTORED, VERIFIED, COMPLETED, FAILED, CANCELLED,
    RESTARTED, LEASE_REVOKED, RULE_APPLIED, IMPORTED,
}
