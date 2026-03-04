package com.mindfulhome.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object UsageTracker {

    data class DailyAppUsage(
        val packageName: String,
        val foregroundTimeMs: Long,
        val timeChunksMsDesc: List<Long>,
    )

    fun getForegroundApp(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null

        val now = System.currentTimeMillis()
        // Prefer UsageEvents for near-real-time foreground transitions (recents/home switches).
        val events = usageStatsManager.queryEvents(now - 15_000, now)
        var latestPackage: String? = null
        var latestTimestamp = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent =
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isForegroundEvent && event.packageName != null && event.timeStamp >= latestTimestamp) {
                latestTimestamp = event.timeStamp
                latestPackage = event.packageName
            }
        }
        if (!latestPackage.isNullOrBlank()) return latestPackage

        // Fallback for devices where event stream is sparse.
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 60 * 1000,
            now,
        )

        return stats
            ?.filter { it.totalTimeInForeground > 0 }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 60 * 1000,
            now
        )
        return stats != null && stats.isNotEmpty()
    }

    fun getLastUserActivityTimestampMs(
        context: Context,
        lookbackMs: Long = 5 * 60_000L,
        includeForegroundTransitions: Boolean = true,
    ): Long? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(
            now - lookbackMs.coerceAtLeast(60_000L),
            now,
        )
        val event = UsageEvents.Event()
        var latestTimestamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isUserActivityEvent =
                event.eventType == UsageEvents.Event.USER_INTERACTION ||
                    (
                        includeForegroundTransitions &&
                            (
                                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                                )
                        )
            if (isUserActivityEvent && event.timeStamp > latestTimestamp) {
                latestTimestamp = event.timeStamp
            }
        }
        return latestTimestamp.takeIf { it > 0L }
    }

    fun getMostUsedAppsToday(context: Context, maxItems: Int = 15): List<DailyAppUsage> {
        if (maxItems <= 0) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return emptyList()

        val now = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            now
        ).orEmpty()

        return stats
            .asSequence()
            .filter { it.packageName != context.packageName && it.totalTimeInForeground > 0L }
            .groupBy { it.packageName }
            .map { (packageName, entries) ->
                val chunks = entries
                    .map { it.totalTimeInForeground }
                    .filter { it > 0L }
                    .sortedDescending()
                DailyAppUsage(
                    packageName = packageName,
                    foregroundTimeMs = chunks.sum(),
                    timeChunksMsDesc = chunks,
                )
            }
            .sortedByDescending { it.foregroundTimeMs }
            .take(maxItems)
            .toList()
    }
}
