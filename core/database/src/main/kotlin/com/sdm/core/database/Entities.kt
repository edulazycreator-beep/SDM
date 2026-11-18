package com.sdm.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sdm.core.common.FileCategory
import com.sdm.domain.DestinationType
import com.sdm.domain.DownloadState
import com.sdm.domain.EventType
import com.sdm.domain.HoldReason
import com.sdm.domain.NetworkPolicy
import com.sdm.domain.Priority
import com.sdm.domain.RangeSupport

@Entity(tableName = "downloads", indices = [Index("state"), Index("queueId"), Index("originalUrl")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUrl: String,
    val resolvedUrl: String? = null,
    val fileName: String,
    val mimeType: String? = null,
    val category: FileCategory = FileCategory.OTHER,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val state: DownloadState = DownloadState.QUEUED,
    val holdReason: HoldReason = HoldReason.NONE,
    val priority: Priority = Priority.NORMAL,
    val important: Boolean = false,
    val queueId: Long = 1,
    val queuePosition: Long = 0,
    val maxConnections: Int = 4,
    val activeConnections: Int = 0,
    val speedLimitBps: Long = 0,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val destinationType: DestinationType = DestinationType.MEDIASTORE,
    /** Content URI of the output file once created (MediaStore item, SAF document or file:// path). */
    val destinationUri: String? = null,
    /** Tree URI chosen by the user for SAF destinations. */
    val destinationTreeUri: String? = null,
    val destinationLabel: String = "",
    val rangeSupport: RangeSupport = RangeSupport.UNKNOWN,
    val etag: String? = null,
    val lastModified: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val httpStatus: Int? = null,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    val scheduledAt: Long? = null,
    val checksumAlgorithm: String? = null,
    val checksumExpected: String? = null,
    val checksumActual: String? = null,
    val sourcePageUrl: String? = null,
    val hasSecrets: Boolean = false,
    val segmentFailures: Int = 0,
    val connectionsUsed: Int = 0,
    val throttled: Boolean = false,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long,
    val activeMillis: Long = 0,
    val version: Long = 0,
    /** null for files; "HLS" for streams saved by segment. Added in schema v2. */
    val streamType: String? = null,
    /** Separate audio rendition of an HLS stream, muxed in after download. */
    val streamAudioUrl: String? = null,
    /** Quality chosen by the user, e.g. "1080p · 4.0 Mbps". */
    val streamLabel: String? = null,
)

@Entity(
    tableName = "download_segments",
    primaryKeys = ["downloadId", "segmentIndex"],
    foreignKeys = [ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"], onDelete = ForeignKey.CASCADE)],
)
data class SegmentEntity(
    val downloadId: Long,
    val segmentIndex: Int,
    val startByte: Long,
    val endByte: Long,
    val checkpointByte: Long,
)

/** Cookies, Authorization and custom headers, AES-GCM encrypted with an Android Keystore key. */
@Entity(
    tableName = "download_secrets",
    foreignKeys = [ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"], onDelete = ForeignKey.CASCADE)],
)
data class SecretEntity(@PrimaryKey val downloadId: Long, val iv: ByteArray, val ciphertext: ByteArray) {
    override fun equals(other: Any?) = other is SecretEntity && other.downloadId == downloadId && other.iv.contentEquals(iv) && other.ciphertext.contentEquals(ciphertext)
    override fun hashCode() = downloadId.hashCode()
}

@Entity(tableName = "queues")
data class QueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val maxConcurrent: Int = 3,
    val ordering: String = "MANUAL",
    /** JSON-encoded domain Schedule. */
    val scheduleJson: String? = null,
    val paused: Boolean = false,
)

@Entity(tableName = "download_events", indices = [Index("downloadId"), Index("timestamp")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: Long?,
    val timestamp: Long,
    val type: EventType,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    /** Always passed through Redactor before insert. */
    val detail: String? = null,
)

@Entity(tableName = "throughput_samples", indices = [Index("timestamp"), Index("downloadId")])
data class ThroughputSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: Long,
    val host: String,
    val timestamp: Long,
    val bytes: Long,
    val intervalMillis: Long,
    val activeConnections: Int,
)

/** One finished transfer session: the input of Speed Intelligence. */
@Entity(tableName = "transfer_observations", indices = [Index("host")])
data class TransferObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: Long,
    val host: String,
    val connections: Int,
    val bytes: Long,
    val activeMillis: Long,
    val segmentFailures: Int,
    val segments: Int,
    val throttled: Boolean,
    val rangeSupported: Boolean,
    val succeeded: Boolean,
    val timestamp: Long,
)

@Entity(tableName = "daily_stats", primaryKeys = ["day", "category"])
data class DailyStatEntity(
    /** Local date as yyyy-MM-dd. */
    val day: String,
    val category: FileCategory,
    val bytes: Long = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val durationMillis: Long = 0,
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val conditionJson: String,
    val actionJson: String,
)

@Entity(tableName = "browser_tabs")
data class BrowserTabEntity(@PrimaryKey val id: String, val url: String, val title: String, val position: Int, val lastAccessed: Long)

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class BookmarkEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val url: String, val title: String, val folder: String = "", val createdAt: Long)

@Entity(tableName = "history", indices = [Index(value = ["url"], unique = true), Index("lastVisited")])
data class HistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val url: String, val title: String, val lastVisited: Long, val visitCount: Int = 1)

@Entity(tableName = "file_index", indices = [Index("category"), Index("sizeBytes"), Index("downloadId")])
data class FileIndexEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val category: FileCategory,
    val modifiedAt: Long,
    val folder: String,
    val downloadId: Long? = null,
    val sha256: String? = null,
    val sha256ComputedAt: Long? = null,
    val sourceTreeUri: String? = null,
)
