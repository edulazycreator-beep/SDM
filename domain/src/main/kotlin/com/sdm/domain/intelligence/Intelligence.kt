package com.sdm.domain.intelligence

import kotlin.math.max
import kotlin.math.min

/** Intelligent profiles from spec section 15. Values are starting points, never above the user's limit. */
enum class SpeedProfile(val label: String, val connections: Int, val minSegmentBytes: Long, val maxRetries: Int) {
    CONSERVATIVE("Conservative", 2, 4L shl 20, 3),
    BALANCED("Balanced", 4, 2L shl 20, 5),
    FAST("Fast", 8, 1L shl 20, 5),
    MAXIMUM("Maximum", 16, 512L shl 10, 8),
}

/** One finished transfer as recorded by analytics; the only input the recommender trusts. */
data class TransferObservation(
    val host: String,
    val connections: Int,
    val bytes: Long,
    val activeMillis: Long,
    val segmentFailures: Int,
    val segments: Int,
    val throttled: Boolean,
    val rangeSupported: Boolean,
) {
    val throughputBps: Long get() = if (activeMillis <= 0) 0 else bytes * 1000 / activeMillis
}

data class Recommendation(
    val connections: Int,
    val minSegmentBytes: Long,
    val maxRetries: Int,
    val profile: SpeedProfile,
    val basis: String,
    val sampleCount: Int,
    val unstable: Boolean,
)

/**
 * Rule-based recommender (spec section 15: lightweight statistics, no machine learning).
 * Compares median throughput per connection count for a host; backs off when segments fail
 * or the server throttles.
 */
object SpeedIntelligence {
    private const val MIN_SAMPLES = 2
    private const val MIN_BYTES = 1L shl 20

    fun recommend(host: String?, history: List<TransferObservation>, userMax: Int, fallback: SpeedProfile = SpeedProfile.BALANCED): Recommendation {
        val cap = max(1, userMax)
        val relevant = history.filter { it.bytes >= MIN_BYTES && it.activeMillis > 0 && (host == null || it.host == host) }
        if (relevant.isEmpty()) {
            val c = min(fallback.connections, cap)
            return Recommendation(c, fallback.minSegmentBytes, fallback.maxRetries, fallback, "No history${host?.let { " for $it" } ?: ""}; using the ${fallback.label} profile", 0, false)
        }
        if (relevant.any { !it.rangeSupported } && relevant.none { it.rangeSupported }) {
            return Recommendation(1, fallback.minSegmentBytes, fallback.maxRetries, SpeedProfile.CONSERVATIVE, "Server does not support ranges; one connection is the only option", relevant.size, false)
        }
        val failureRate = relevant.sumOf { it.segmentFailures }.toDouble() / max(1, relevant.sumOf { it.segments })
        val throttled = relevant.count { it.throttled }
        val unstable = failureRate > 0.2 || throttled * 2 >= relevant.size
        val byConn = relevant.groupBy { it.connections }.mapValues { (_, v) -> median(v.map { it.throughputBps }) to v.size }
        val qualified = byConn.filter { it.value.second >= MIN_SAMPLES }
        var best = (qualified.ifEmpty { byConn }).maxByOrNull { it.value.first }!!.key
        if (unstable) best = max(1, best / 2)
        best = nearestProfileCount(min(best, cap))
        val profile = SpeedProfile.entries.minByOrNull { kotlin.math.abs(it.connections - best) } ?: fallback
        val retries = if (unstable) profile.maxRetries + 2 else profile.maxRetries
        val segment = if (failureRate > 0.2) profile.minSegmentBytes * 2 else profile.minSegmentBytes
        val basis = buildString {
            append("Based on ${relevant.size} download(s)")
            host?.let { append(" from $it") }
            append(": best median speed with $best connection(s)")
            if (unstable) append("; reduced because ${(failureRate * 100).toInt()}% of segments failed or the server throttled")
        }
        return Recommendation(best, segment, retries, profile, basis, relevant.size, unstable)
    }

    /** Snap to the allowed connection profiles (1, 2, 4, 8, 16). */
    fun nearestProfileCount(n: Int): Int = com.sdm.domain.CONNECTION_PROFILES.lastOrNull { it <= n } ?: 1

    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/** Exponentially weighted throughput over real samples; source of every ETA (spec section 16). */
class ThroughputMeter(private val alpha: Double = 0.3) {
    private var lastBytes = -1L
    private var lastTime = 0L
    var rateBps: Long = 0
        private set

    fun sample(totalBytes: Long, nowMillis: Long): Long {
        if (lastBytes >= 0 && nowMillis > lastTime) {
            val instant = (totalBytes - lastBytes).coerceAtLeast(0) * 1000 / (nowMillis - lastTime)
            rateBps = if (rateBps == 0L) instant else (alpha * instant + (1 - alpha) * rateBps).toLong()
        }
        lastBytes = totalBytes
        lastTime = nowMillis
        return rateBps
    }
}

/** Queue ordering modes from spec section 16. */
enum class QueueOrdering(val label: String) { MANUAL("Manual order"), PRIORITY("Priority first"), SMALLEST_FIRST("Small files first"), OLDEST_FIRST("Oldest first") }

data class QueueItem(val id: Long, val priority: Int, val position: Long, val remainingBytes: Long?, val createdAt: Long, val important: Boolean = false)

object QueueIntelligence {
    fun order(items: List<QueueItem>, mode: QueueOrdering): List<QueueItem> {
        val important = compareByDescending<QueueItem> { it.important }
        val cmp = when (mode) {
            QueueOrdering.MANUAL -> important.thenBy { it.position }
            QueueOrdering.PRIORITY -> important.thenByDescending { it.priority }.thenBy { it.position }
            QueueOrdering.SMALLEST_FIRST -> important.thenBy { it.remainingBytes ?: Long.MAX_VALUE }.thenBy { it.position }
            QueueOrdering.OLDEST_FIRST -> important.thenBy { it.createdAt }
        }
        return items.sortedWith(cmp)
    }

    /**
     * Estimated seconds until the queue finishes, from measured throughput only. Returns null when
     * there is no measurement or a size is unknown, so the UI never shows an invented number.
     */
    fun estimateSeconds(remainingBytes: List<Long?>, measuredBps: Long): Long? {
        if (measuredBps <= 0 || remainingBytes.any { it == null }) return null
        return remainingBytes.sumOf { it ?: 0L } / measuredBps
    }
}
