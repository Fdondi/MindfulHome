package com.mindfulhome.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.mindfulhome.settings.SettingsManager
import java.util.Calendar

object UsageTracker {

    data class DailyAppUsage(
        val packageName: String,
        val foregroundTimeMs: Long,
        val timeChunksMsDesc: List<Long>,
    )

    private data class ForegroundCache(
        val packageName: String,
        val observedAtMs: Long,
    )

    // Keeps Quick Launch from hammering UsageStatsManager when we poll in short intervals.
    private var foregroundCache: ForegroundCache? = null

    private const val QUICK_LAUNCH_FOREGROUND_LOOKBACK_MS = 60_000L
    private const val DEFAULT_FOREGROUND_LOOKBACK_MS = 15_000L

    fun invalidateForegroundCache() {
        foregroundCache = null
    }

    fun getForegroundApp(
        context: Context,
        bypassCache: Boolean = false,
        lookbackMs: Long = DEFAULT_FOREGROUND_LOOKBACK_MS,
    ): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val cached = foregroundCache
        UsageTrackerLogic.cachedPackageIfFresh(
            cachedPackage = cached?.packageName,
            cachedObservedAtMs = cached?.observedAtMs,
            nowMs = now,
            cacheTtlMs = SettingsManager.getUsageForegroundCacheTtlMs(context),
            bypassCache = bypassCache,
        )?.let { return it }

        val fromEvents = latestForegroundFromEvents(
            usageStatsManager,
            now,
            UsageTrackerLogic.coerceLookbackMs(lookbackMs, DEFAULT_FOREGROUND_LOOKBACK_MS),
        )
        if (!fromEvents.isNullOrBlank()) {
            foregroundCache = ForegroundCache(fromEvents, now)
            return fromEvents
        }
        return fallbackForegroundFromStats(usageStatsManager, now)?.also {
            foregroundCache = ForegroundCache(it, now)
        }
    }

    private fun latestForegroundFromEvents(
        usageStatsManager: UsageStatsManager,
        now: Long,
        lookbackMs: Long,
    ): String? {
        val events = usageStatsManager.queryEvents(now - lookbackMs, now)
        var latestPackage: String? = null
        var latestTimestamp = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isFg = UsageTrackerLogic.isForegroundTransitionEvent(
                event.eventType,
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND,
            )
            if (UsageTrackerLogic.shouldReplaceLatestForeground(
                    event.packageName,
                    event.timeStamp,
                    latestTimestamp,
                    isFg,
                )
            ) {
                latestTimestamp = event.timeStamp
                latestPackage = event.packageName
            }
        }
        return latestPackage?.takeIf { it.isNotBlank() }
    }

    private fun fallbackForegroundFromStats(
        usageStatsManager: UsageStatsManager,
        now: Long,
    ): String? {
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 60 * 1000,
            now,
        )
        return stats
            ?.filter { it.totalTimeInForeground > 0 }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
            ?.takeIf { it.isNotBlank() }
    }

    fun getForegroundAppForQuickLaunchMonitor(context: Context): String? {
        return getForegroundApp(
            context = context,
            bypassCache = true,
            lookbackMs = QUICK_LAUNCH_FOREGROUND_LOOKBACK_MS,
        )
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
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
            now - UsageTrackerLogic.coerceLookbackMs(lookbackMs, 60_000L),
            now,
        )
        val event = UsageEvents.Event()
        var latestTimestamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (UsageTrackerLogic.isUserActivityEvent(
                    event.eventType,
                    UsageEvents.Event.USER_INTERACTION,
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    includeForegroundTransitions,
                ) && event.timeStamp > latestTimestamp
            ) {
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
            now,
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
