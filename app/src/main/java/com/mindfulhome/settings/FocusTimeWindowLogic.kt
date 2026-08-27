package com.mindfulhome.settings

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure helpers for focus-time clock windows (minute-of-day math).
 */
object FocusTimeWindowLogic {
    const val MINUTES_PER_DAY = 24 * 60

    data class DurationParts(val hours: Int, val minutes: Int)

    fun minuteOfDayFromEpochMs(
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Int {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = nowMs
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    fun isMinuteWithinInterval(
        minuteOfDay: Int,
        startMinutes: Int,
        endMinutes: Int,
    ): Boolean {
        val start = startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    fun remainingMinutesUntilIntervalEnd(
        minuteOfDay: Int,
        startMinutes: Int,
        endMinutes: Int,
    ): Int {
        val start = startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) {
            val until = (end - minuteOfDay + MINUTES_PER_DAY) % MINUTES_PER_DAY
            return if (until == 0) MINUTES_PER_DAY else until
        }
        if (start < end) {
            return (end - minuteOfDay).coerceAtLeast(0)
        }
        return if (minuteOfDay >= start) {
            MINUTES_PER_DAY - minuteOfDay + end
        } else {
            (end - minuteOfDay).coerceAtLeast(0)
        }
    }

    fun remainingMinutesInActiveWindow(
        minuteOfDay: Int,
        intervals: List<SettingsManager.FocusInterval>,
    ): Int? {
        val match = intervals.firstOrNull { interval ->
            isMinuteWithinInterval(minuteOfDay, interval.startMinutes, interval.endMinutes)
        } ?: return null
        return remainingMinutesUntilIntervalEnd(
            minuteOfDay = minuteOfDay,
            startMinutes = match.startMinutes,
            endMinutes = match.endMinutes,
        )
    }

    fun durationParts(totalMinutes: Int): DurationParts {
        val clamped = totalMinutes.coerceAtLeast(0)
        return DurationParts(hours = clamped / 60, minutes = clamped % 60)
    }
}
