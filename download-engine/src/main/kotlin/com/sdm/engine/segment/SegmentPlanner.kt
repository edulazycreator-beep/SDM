package com.sdm.engine.segment

import com.sdm.engine.SegmentState

object SegmentPlanner {
    /** Equal segments, never smaller than [minSegment], never more than [maxConnections]. */
    fun initialPlan(total: Long, maxConnections: Int, minSegment: Long): List<SegmentState> {
        require(total > 0) { "total must be positive" }
        val maxBySize = (total / minSegment.coerceAtLeast(1)).coerceAtLeast(1)
        val count = minOf(maxConnections.coerceAtLeast(1).toLong(), maxBySize).toInt()
        val base = total / count
        return (0 until count).map { i ->
            val start = i * base
            val end = if (i == count - 1) total - 1 else start + base - 1
            SegmentState(i, start, end, start)
        }
    }

    /**
     * Work stealing: picks the split point for a running segment so a free worker can take the
     * second half. Returns null when the remainder is too small to be worth a new connection.
     */
    fun splitPoint(position: Long, end: Long, minSegment: Long, guard: Long): Long? {
        val remaining = end - position + 1
        if (remaining < 2 * minSegment) return null
        val mid = position + remaining / 2
        return if (mid - position < guard || end - mid + 1 < minSegment) null else mid
    }

    /** True when segments cover [0, total) contiguously and every one is complete. */
    fun coversCompletely(segments: List<SegmentState>, total: Long): Boolean {
        if (total == 0L) return true
        val sorted = segments.sortedBy { it.start }
        if (sorted.isEmpty() || sorted.first().start != 0L) return false
        var expected = 0L
        for (s in sorted) {
            if (s.start != expected || !s.done) return false
            expected = s.end + 1
        }
        return expected == total
    }
}
