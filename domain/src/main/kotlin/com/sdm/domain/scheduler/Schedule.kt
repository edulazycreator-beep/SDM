package com.sdm.domain.scheduler

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Scheduling profiles from spec section 13. */
@Serializable
enum class ScheduleProfile(val label: String) {
    ANYTIME("Anytime"), WIFI_ONLY("Wi-Fi only"), NIGHT("Night download"), MANUAL("Manual schedule"), CUSTOM("Custom rules")
}

@Serializable
data class Schedule(
    val profile: ScheduleProfile = ScheduleProfile.ANYTIME,
    /** Minutes after midnight, local time. Used by NIGHT, MANUAL and CUSTOM. */
    val startMinute: Int = 60,
    val endMinute: Int = 360,
    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val daysMask: Int = 0b1111111,
    val requiresUnmetered: Boolean = false,
    val requiresCharging: Boolean = false,
    val minBatteryPercent: Int = 0,
    /** Optional absolute start date-time (epoch millis) for MANUAL schedules. */
    val startAt: Long? = null,
    /** Optional absolute stop (scheduled pause) for MANUAL schedules. */
    val stopAt: Long? = null,
)

data class Conditions(val connected: Boolean, val unmetered: Boolean, val charging: Boolean, val batteryPercent: Int)

sealed interface ScheduleVerdict {
    data object Allowed : ScheduleVerdict
    data class Blocked(val reason: String, val nextWindowStart: Long?) : ScheduleVerdict
}

object SchedulePolicy {
    fun evaluate(s: Schedule, nowMillis: Long, c: Conditions, zone: ZoneId = ZoneId.systemDefault()): ScheduleVerdict {
        val unmeteredNeeded = s.requiresUnmetered || s.profile == ScheduleProfile.WIFI_ONLY
        if (unmeteredNeeded && !c.unmetered) return ScheduleVerdict.Blocked("Waiting for Wi-Fi", null)
        if (s.requiresCharging && !c.charging) return ScheduleVerdict.Blocked("Waiting for charging", null)
        if (s.minBatteryPercent > 0 && !c.charging && c.batteryPercent < s.minBatteryPercent) {
            return ScheduleVerdict.Blocked("Battery below ${s.minBatteryPercent}%", null)
        }
        return when (s.profile) {
            ScheduleProfile.ANYTIME, ScheduleProfile.WIFI_ONLY -> ScheduleVerdict.Allowed
            ScheduleProfile.MANUAL -> manual(s, nowMillis, zone)
            ScheduleProfile.NIGHT, ScheduleProfile.CUSTOM -> window(s, nowMillis, zone)
        }
    }

    private fun manual(s: Schedule, now: Long, zone: ZoneId): ScheduleVerdict {
        if (s.startAt != null && now < s.startAt) return ScheduleVerdict.Blocked("Scheduled start", s.startAt)
        if (s.stopAt != null && now >= s.stopAt) return ScheduleVerdict.Blocked("Scheduled stop reached", null)
        return if (s.startAt == null) window(s, now, zone) else ScheduleVerdict.Allowed
    }

    fun inWindow(s: Schedule, now: Long, zone: ZoneId): Boolean {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val minute = t.hour * 60 + t.minute
        val start = s.startMinute.mod(1440); val end = s.endMinute.mod(1440)
        return if (start == end) dayAllowed(s, t.dayOfWeek)
        else if (start < end) dayAllowed(s, t.dayOfWeek) && minute in start until end
        else { // window crosses midnight: the day of the window start decides
            if (minute >= start) dayAllowed(s, t.dayOfWeek)
            else minute < end && dayAllowed(s, t.dayOfWeek.minus(1))
        }
    }

    private fun window(s: Schedule, now: Long, zone: ZoneId): ScheduleVerdict =
        if (inWindow(s, now, zone)) ScheduleVerdict.Allowed
        else ScheduleVerdict.Blocked("Outside operating hours", nextWindowStart(s, now, zone))

    private fun dayAllowed(s: Schedule, d: DayOfWeek) = s.daysMask and (1 shl (d.value - 1)) != 0

    fun nextWindowStart(s: Schedule, now: Long, zone: ZoneId): Long? {
        if (s.daysMask == 0) return null
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val startTime = LocalTime.of(s.startMinute.mod(1440) / 60, s.startMinute.mod(1440) % 60)
        for (i in 0..7) {
            val day = t.toLocalDate().plusDays(i.toLong())
            val candidate = day.atTime(startTime).atZone(zone)
            if (candidate.toInstant().toEpochMilli() > now && dayAllowed(s, candidate.dayOfWeek)) return candidate.toInstant().toEpochMilli()
        }
        return null
    }

    /** Spec defaults: night window 01:00-06:00. */
    fun preset(profile: ScheduleProfile): Schedule = when (profile) {
        ScheduleProfile.NIGHT -> Schedule(profile, startMinute = 60, endMinute = 360)
        ScheduleProfile.WIFI_ONLY -> Schedule(profile, requiresUnmetered = true)
        else -> Schedule(profile)
    }
}
