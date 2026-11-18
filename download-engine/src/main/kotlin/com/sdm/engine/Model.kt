package com.sdm.engine

/** HTTP validators used to prove a resumed download still points at the same resource. */
data class Validators(val etag: String? = null, val lastModified: String? = null) {
    val strongEtag: String? get() = etag?.takeIf { !it.startsWith("W/") }

    /** Value for If-Range: a strong ETag, else Last-Modified (RFC 9110 13.1.5). */
    val ifRange: String? get() = strongEtag ?: lastModified

    val isEmpty: Boolean get() = etag == null && lastModified == null

    /** true = same, false = changed, null = cannot tell (no comparable validator). */
    fun sameAs(other: Validators): Boolean? {
        if (strongEtag != null && other.strongEtag != null) return strongEtag == other.strongEtag
        if (etag != null && other.etag != null) return etag == other.etag
        if (lastModified != null && other.lastModified != null) return lastModified == other.lastModified
        return null
    }
}

/** Persisted segment progress. [end] is inclusive; the segment is done when position > end. */
data class SegmentState(val index: Int, val start: Long, val end: Long, val position: Long) {
    val done: Boolean get() = position > end
    val downloaded: Long get() = (minOf(position, end + 1) - start).coerceAtLeast(0)
}

/** Everything needed to resume after pause, crash or reboot. Stored by the caller in its database. */
data class Checkpoint(
    val totalBytes: Long?,
    val rangeSupported: Boolean,
    val validators: Validators,
    val resolvedUrl: String,
    val segments: List<SegmentState>,
) {
    val downloadedBytes: Long get() = segments.sumOf { it.downloaded }
}

data class ExpectedChecksum(val algorithm: String, val hex: String) {
    val javaAlgorithm: String get() = when (algorithm.uppercase().replace("-", "")) {
        "MD5" -> "MD5"; "SHA1" -> "SHA-1"; "SHA256" -> "SHA-256"; "SHA512" -> "SHA-512"
        else -> throw IllegalArgumentException("Unsupported checksum algorithm $algorithm")
    }
}

data class TransferRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val maxConnections: Int = 4,
    val minSegmentBytes: Long = 1L shl 20,
    val checkpoint: Checkpoint? = null,
    val expectedChecksum: ExpectedChecksum? = null,
    val segmentRetries: Int = 3,
    val checkpointIntervalMillis: Long = 2_000,
    val progressIntervalMillis: Long = 500,
    val retryBaseDelayMillis: Long = 1_000,
)

data class ProbeInfo(
    val finalUrl: String,
    val statusCode: Int,
    val totalBytes: Long?,
    val rangeSupported: Boolean,
    val validators: Validators,
    val contentType: String?,
    val contentDisposition: String?,
)

enum class ErrorKind { NETWORK, TIMEOUT, HTTP, RANGE_MISMATCH, RESOURCE_CHANGED, STORAGE_FULL, FILE_TOO_LARGE, STORAGE, CHECKSUM_MISMATCH, INVALID_RESPONSE, UNSUPPORTED }

data class TransferError(
    val kind: ErrorKind,
    val message: String,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    /** True when the failure happened while resuming earlier progress (signed links expire). */
    val resumed: Boolean = false,
) {
    val retryable: Boolean get() = when (kind) {
        ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.RANGE_MISMATCH -> true
        ErrorKind.HTTP -> httpStatus == 408 || httpStatus == 429 || (httpStatus != null && httpStatus >= 500)
        else -> false
    }
}

data class TransferStats(
    val segments: Int,
    val segmentFailures: Int,
    val throttled: Boolean,
    val fellBackToSingle: Boolean,
    val connectionsUsed: Int,
    val bytesThisSession: Long,
    val activeMillis: Long,
    val rangeSupported: Boolean,
)

sealed interface TransferResult {
    val stats: TransferStats
    data class Completed(val totalBytes: Long, val checksumHex: String?, val finalUrl: String, override val stats: TransferStats) : TransferResult
    data class Failed(val error: TransferError, override val stats: TransferStats) : TransferResult
}

/** Engine callbacks. Progress is frequent and cheap; checkpoints are the durable, slower path. */
interface TransferListener {
    fun onProbed(info: ProbeInfo) {}
    fun onProgress(downloadedBytes: Long, totalBytes: Long?, speedBps: Long, activeConnections: Int, segments: List<SegmentState>) {}
    suspend fun onCheckpoint(checkpoint: Checkpoint) {}
    fun onEvent(type: EngineEventType, detail: String) {}
    /** Called once all bytes are written, before size and checksum verification. */
    fun onVerifying() {}
}

enum class EngineEventType { FALLBACK_SINGLE_CONNECTION, RESOURCE_CHANGED, SEGMENT_FAILED, CONNECTIONS_REDUCED, RESTARTED_FROM_ZERO, VERIFIED }
