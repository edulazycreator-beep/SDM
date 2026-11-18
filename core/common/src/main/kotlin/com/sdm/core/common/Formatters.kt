package com.sdm.core.common

import java.util.Locale
import kotlin.math.abs

object Formatters {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Binary units (1 KB = 1024 B), the convention download managers use. */
    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "Unknown"
        if (value < 1024) return "$value B"
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) "0 B/s" else bytes(bytesPerSecond) + "/s"

    fun duration(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "—"
        val s = abs(seconds)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when {
            h > 99 -> ">99 h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }

    fun percent(done: Long, total: Long?): Int =
        if (total == null || total <= 0) 0 else ((done.coerceAtMost(total) * 100) / total).toInt()
}
