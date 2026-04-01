package com.mindfulhome.logging

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object DailyLogSummaryScheduler {
    private const val UNIQUE_PERIODIC_WORK = "daily_log_summary"
    private const val UNIQUE_CATCHUP_WORK = "daily_log_summary_catchup"

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val initialDelay = computeInitialDelayToLocalTime(hour = 23, minute = 55)

        val periodic = PeriodicWorkRequestBuilder<DailyLogSummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        // Catch up a missed previous-day summary (e.g. if the app wasn't running at 23:55).
        val catchup = OneTimeWorkRequestBuilder<DailyLogSummaryWorker>().build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_CATCHUP_WORK,
            androidx.work.ExistingWorkPolicy.KEEP,
            catchup,
        )
    }

    private fun computeInitialDelayToLocalTime(hour: Int, minute: Int): Duration {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return Duration.between(now, target)
    }
}

