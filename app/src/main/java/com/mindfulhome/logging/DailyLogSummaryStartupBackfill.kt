package com.mindfulhome.logging

import android.content.Context
import android.util.Log
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.data.AppDatabase
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fills in missing daily summaries for the most recent [BACKFILL_LOG_DAY_COUNT] distinct local days
 * that actually have session logs (not empty calendar days).
 */
object DailyLogSummaryStartupBackfill {

    private const val TAG = "DailyLogSummaryBackfill"
    private const val BACKFILL_LOG_DAY_COUNT = 5

    suspend fun runIfNeeded(context: Context) {
        val token = ApiKeyManager.getSessionToken(context)
        if (token.isNullOrBlank()) {
            Log.d(TAG, "No backend session; skipping summary backfill")
            return
        }

        val db = AppDatabase.getInstance(context)
        val sessionDao = db.sessionLogDao()
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val dayKeys = sessionDao.getRecentDistinctLocalDaysWithLogs(BACKFILL_LOG_DAY_COUNT + 1)
            .filter { it < today }
            .take(BACKFILL_LOG_DAY_COUNT)
        if (dayKeys.isEmpty()) return

        for (dayKey in dayKeys) {
            when (
                DailyLogSummaryGenerator.generateIfMissing(context, dayKey, token)
            ) {
                DailySummaryGenerateOutcome.ApiError ->
                    Log.w(TAG, "API error for $dayKey; continuing with other days")
                DailySummaryGenerateOutcome.AlreadyHad,
                DailySummaryGenerateOutcome.NoSessionsToSummarize,
                DailySummaryGenerateOutcome.Generated -> Unit
            }
        }
    }
}
