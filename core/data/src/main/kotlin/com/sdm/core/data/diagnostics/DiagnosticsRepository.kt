package com.sdm.core.data.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.sdm.core.common.Formatters
import com.sdm.core.common.Redactor
import com.sdm.core.data.analytics.AnalyticsRecorder
import com.sdm.core.data.device.NetworkMonitor
import com.sdm.core.data.download.DownloadCoordinator
import com.sdm.core.data.execution.ExecutionLeaseManager
import com.sdm.core.database.SdmDatabase
import com.sdm.domain.Download
import com.sdm.domain.DownloadEventRecord
import com.sdm.domain.DownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class PerformanceSnapshot(
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val nativeHeapBytes: Long,
    val deviceAvailableBytes: Long,
    val lowMemory: Boolean,
    val cpuPercent: Double?,
    val activeDownloads: Int,
    val activeConnections: Int,
    val leaseMode: String,
    val databaseBytes: Long,
    val databaseQueryMillis: Long,
    val segmentFailureRate: Double?,
    val queued: Int,
    val waiting: Int,
    val failed: Int,
)

/** Error diagnostics and lightweight performance monitoring (spec sections 25 and 26). */
@Singleton
class DiagnosticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SdmDatabase,
    private val network: NetworkMonitor,
    private val lease: ExecutionLeaseManager,
    private val coordinator: DownloadCoordinator,
    private val analytics: AnalyticsRecorder,
) {
    private var lastCpu = Process.getElapsedCpuTime()
    private var lastWall = SystemClock.elapsedRealtime()

    /** Copyable report. Cookies, passwords and authorization never appear (URLs and text are redacted). */
    fun report(d: Download, events: List<DownloadEventRecord>): String = Redactor.text(buildString {
        val code = d.errorCode
        appendLine("SDM diagnostic report")
        appendLine("Generated: ${Instant.now()}")
        appendLine("App: ${context.packageName} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine("File: ${d.fileName}")
        appendLine("Status: ${d.state}")
        appendLine("Error code: ${code?.name ?: "none"}")
        code?.let { appendLine("Error: ${it.title} — ${it.description}"); appendLine("Suggested solution: ${it.suggestion}") }
        d.errorMessage?.let { appendLine("Detail: $it") }
        appendLine("HTTP status: ${d.httpStatus ?: "—"}")
        appendLine("Failed URL: ${Redactor.url(d.resolvedUrl ?: d.originalUrl)}")
        appendLine("Connection status: ${network.state.value.label}")
        appendLine("Retry count: ${d.retryCount}")
        appendLine("Progress: ${Formatters.bytes(d.downloadedBytes)} of ${Formatters.bytes(d.totalBytes)}")
        appendLine("Range support: ${d.rangeSupport}")
        appendLine("Connections: ${d.maxConnections}")
        appendLine("Failure timestamp: ${events.firstOrNull { it.errorCode != null }?.let { Instant.ofEpochMilli(it.timestamp) } ?: "—"}")
        appendLine()
        appendLine("Recent events:")
        events.take(25).forEach { e -> appendLine("${Instant.ofEpochMilli(e.timestamp)} ${e.type}${e.httpStatus?.let { " HTTP $it" } ?: ""} ${e.detail ?: ""}") }
    })

    suspend fun performance(): PerformanceSnapshot {
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val cpu = Process.getElapsedCpuTime(); val wall = SystemClock.elapsedRealtime()
        val cpuPct = if (wall > lastWall) (cpu - lastCpu).toDouble() / (wall - lastWall) / Runtime.getRuntime().availableProcessors() * 100 else null
        lastCpu = cpu; lastWall = wall
        val t0 = SystemClock.elapsedRealtimeNanos()
        val all = db.downloads().all()
        val queryMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        val obs = analytics.observations().take(50)
        val failRate = if (obs.isEmpty()) null else obs.sumOf { it.segmentFailures }.toDouble() / obs.sumOf { it.segments }.coerceAtLeast(1)
        return PerformanceSnapshot(
            heapUsedBytes = rt.totalMemory() - rt.freeMemory(), heapMaxBytes = rt.maxMemory(), nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            deviceAvailableBytes = mi.availMem, lowMemory = mi.lowMemory, cpuPercent = cpuPct, activeDownloads = coordinator.activeCount,
            activeConnections = coordinator.live.value.values.sumOf { it.activeConnections }, leaseMode = lease.mode.value.label,
            databaseBytes = context.getDatabasePath(SdmDatabase.NAME).length(), databaseQueryMillis = queryMs, segmentFailureRate = failRate,
            queued = all.count { it.state == DownloadState.QUEUED }, waiting = all.count { it.state == DownloadState.WAITING_FOR_NETWORK }, failed = all.count { it.state == DownloadState.FAILED },
        )
    }
}
