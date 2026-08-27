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

    fun matchingInterval(
        minuteOfDay: Int,
        intervals: List<SettingsManager.FocusInterval>,
    ): SettingsManager.FocusInterval? = intervals.firstOrNull { interval ->
        isMinuteWithinInterval(minuteOfDay, interval.startMinutes, interval.endMinutes)
    }

    fun remainingMinutesInActiveWindow(
        minuteOfDay: Int,
        intervals: List<SettingsManager.FocusInterval>,
    ): Int? {
        val match = matchingInterval(minuteOfDay, intervals) ?: return null
        return remainingMinutesUntilIntervalEnd(
            minuteOfDay = minuteOfDay,
            startMinutes = match.startMinutes,
            endMinutes = match.endMinutes,
        )
    }

    fun elapsedMinutesSinceIntervalStart(
        minuteOfDay: Int,
        startMinutes: Int,
        endMinutes: Int,
    ): Int {
        val start = startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) {
            return (minuteOfDay - start + MINUTES_PER_DAY) % MINUTES_PER_DAY
        }
        if (start < end) {
            return (minuteOfDay - start).coerceAtLeast(0)
        }
        return if (minuteOfDay >= start) {
            minuteOfDay - start
        } else {
            MINUTES_PER_DAY - start + minuteOfDay
        }
    }

    fun extraMinRounds(elapsedMinutes: Int, extraRoundEveryMinutes: Int): Int {
        if (extraRoundEveryMinutes <= 0) return 0
        return (elapsedMinutes.coerceAtLeast(0) / extraRoundEveryMinutes)
    }

    fun effectiveMinRounds(baseMin: Int, extraMin: Int, cap: Int): Int =
        (baseMin.coerceAtLeast(1) + extraMin.coerceAtLeast(0)).coerceIn(1, cap.coerceAtLeast(1))

    fun focusGateRoundBudget(
        baseMin: Int,
        baseMax: Int,
        elapsedMinutes: Int,
        extraRoundEveryMinutes: Int,
        cap: Int,
    ): Pair<Int, Int> {
        val extra = extraMinRounds(elapsedMinutes, extraRoundEveryMinutes)
        val minRounds = effectiveMinRounds(baseMin, extra, cap)
        val maxRounds = baseMax.coerceAtLeast(minRounds)
        return minRounds to maxRounds
    }

    fun parseFocusIntervalToken(token: String): SettingsManager.FocusInterval? {
        val parts = token.split("-")
        if (parts.size !in 2..3) return null
        val start = parts[0].toIntOrNull() ?: return null
        val end = parts[1].toIntOrNull() ?: return null
        val every = if (parts.size == 3) {
            parts[2].toIntOrNull() ?: SettingsManager.DEFAULT_EXTRA_ROUND_EVERY_MINUTES
        } else {
            SettingsManager.DEFAULT_EXTRA_ROUND_EVERY_MINUTES
        }
        return SettingsManager.FocusInterval(
            startMinutes = start.coerceIn(0, MINUTES_PER_DAY - 1),
            endMinutes = end.coerceIn(0, MINUTES_PER_DAY - 1),
            extraRoundEveryMinutes = every.coerceIn(
                SettingsManager.MIN_EXTRA_ROUND_EVERY_MINUTES,
                SettingsManager.MAX_EXTRA_ROUND_EVERY_MINUTES,
            ),
        )
    }

    fun serializeFocusInterval(interval: SettingsManager.FocusInterval): String {
        val start = interval.startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = interval.endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val every = interval.extraRoundEveryMinutes.coerceIn(
            SettingsManager.MIN_EXTRA_ROUND_EVERY_MINUTES,
            SettingsManager.MAX_EXTRA_ROUND_EVERY_MINUTES,
        )
        return "$start-$end-$every"
    }

    fun durationParts(totalMinutes: Int): DurationParts {
        val clamped = totalMinutes.coerceAtLeast(0)
        return DurationParts(hours = clamped / 60, minutes = clamped % 60)
    }
}
