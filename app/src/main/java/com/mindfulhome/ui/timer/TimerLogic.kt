package com.mindfulhome.ui.timer

import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** Pure helpers extracted from [TimerScreen] for unit testing and CRAP reduction. */

const val TIMER_MAX_MINUTES = 120
const val TIMER_ITEM_HEIGHT_DP = 64
const val TIMER_VISIBLE_ITEMS = 5

fun formatTimerMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} hr"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}

fun formatTimerEndTime(nowMs: Long, minutesFromNow: Int, formatter: DateFormat): String {
    val endMs = nowMs + minutesFromNow.coerceAtLeast(1) * 60_000L
    return formatter.format(Date(endMs))
}

fun formatUsageDuration(durationMs: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(durationMs).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

fun formatUsageBreakdown(
    totalDurationMs: Long,
    timeChunksMsDesc: List<Long>,
    maxShownChunks: Int = 3,
): String {
    val total = formatUsageDuration(totalDurationMs)
    if (timeChunksMsDesc.size <= 1) return total
    val shownChunks = timeChunksMsDesc.take(maxShownChunks).map(::formatUsageDuration)
    val hasMore = timeChunksMsDesc.size > maxShownChunks
    val joined = buildString {
        append(shownChunks.joinToString(" + "))
        if (hasMore) append(" + ...")
    }
    return "$total ($joined)"
}

/** Odd visible row count so the picker highlight stays vertically centered. */
fun oddVisiblePickerCount(maxVisibleCount: Int, maxItems: Int = TIMER_VISIBLE_ITEMS): Int {
    val clamped = maxVisibleCount.coerceIn(1, maxItems)
    return when {
        clamped == 1 -> 1
        clamped % 2 == 0 -> clamped - 1
        else -> clamped
    }
}

fun hardDeadlineMinutesOrNull(enabled: Boolean, selectedMinutes: Int): Int? =
    if (enabled) selectedMinutes else null

fun msUntilNextMinuteBoundary(nowMs: Long): Long {
    val msIntoMinute = nowMs % 60_000L
    val step = 60_000L - msIntoMinute
    return (if (step <= 0L) 60_000L else step).coerceAtLeast(1_000L)
}

fun pickerCenterOffsetPx(viewportHeightPx: Int, itemHeightPx: Int): Int =
    -((viewportHeightPx - itemHeightPx) / 2)

fun coercePrefillMinutes(minutes: Int?, maxMinutes: Int = TIMER_MAX_MINUTES): Int? =
    minutes?.coerceIn(1, maxMinutes)

fun distanceAlpha(distanceFromCenter: Int): Float = when (distanceFromCenter) {
    0 -> 1f
    1 -> 0.6f
    else -> 0.3f
}
