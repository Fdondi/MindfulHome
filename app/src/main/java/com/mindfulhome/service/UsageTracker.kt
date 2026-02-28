package com.mindfulhome.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

object UsageTracker {

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
}
