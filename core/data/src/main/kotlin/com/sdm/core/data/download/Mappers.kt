package com.sdm.core.data.download

import com.sdm.core.database.DownloadEntity
import com.sdm.core.database.EventEntity
import com.sdm.domain.Download
import com.sdm.domain.DownloadEventRecord
import com.sdm.domain.ErrorCode

fun DownloadEntity.toDomain() = Download(
    id = id, originalUrl = originalUrl, resolvedUrl = resolvedUrl, fileName = fileName, mimeType = mimeType, category = category,
    totalBytes = totalBytes, downloadedBytes = downloadedBytes, state = state, holdReason = holdReason, priority = priority,
    queueId = queueId, queuePosition = queuePosition, maxConnections = maxConnections, activeConnections = activeConnections,
    speedLimitBps = speedLimitBps, networkPolicy = networkPolicy, destinationType = destinationType, destinationUri = destinationUri,
    destinationLabel = destinationLabel, rangeSupport = rangeSupport, errorCode = ErrorCode.parse(errorCode), errorMessage = errorMessage,
    httpStatus = httpStatus, retryCount = retryCount, nextRetryAt = nextRetryAt, scheduledAt = scheduledAt,
    checksumAlgorithm = checksumAlgorithm, checksumExpected = checksumExpected, checksumActual = checksumActual,
    sourcePageUrl = sourcePageUrl, createdAt = createdAt, startedAt = startedAt, completedAt = completedAt, activeMillis = activeMillis, important = important, streamType = streamType, streamLabel = streamLabel,
)

fun EventEntity.toDomain() = DownloadEventRecord(id, downloadId, timestamp, type, httpStatus, ErrorCode.parse(errorCode), detail)
