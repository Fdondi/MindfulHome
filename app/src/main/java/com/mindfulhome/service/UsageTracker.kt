package com.mindfulhome.service

import android.app.usage.UsageStatsManager
import android.content.Context

object UsageTracker {

    fun getForegroundApp(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null

        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 60 * 1000,
            now
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
