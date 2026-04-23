package com.mindfulhome.logging

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mindfulhome.ai.backend.ApiKeyManager
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyLogSummaryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val token = ApiKeyManager.getSessionToken(applicationContext)
        if (token.isNullOrBlank()) {
            Log.d(TAG, "No backend session; skipping daily summary generation")
            return Result.success()
        }

        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val candidates = listOf(now.toLocalDate().minusDays(1))

        for (day in candidates) {
            val dayKey = day.toString()
            when (
                DailyLogSummaryGenerator.generateIfMissing(applicationContext, dayKey, token)
            ) {
                DailySummaryGenerateOutcome.ApiError -> return Result.retry()
                DailySummaryGenerateOutcome.AlreadyHad,
                DailySummaryGenerateOutcome.NoSessionsToSummarize,
                DailySummaryGenerateOutcome.Generated -> Unit
            }
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "DailyLogSummaryWorker"
    }
}
