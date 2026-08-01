package com.mindfulhome.logging

import android.content.Context
import android.util.Log
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.data.AppDatabase
import com.mindfulhome.data.DailyLogSummary
import com.mindfulhome.settings.SettingsManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DailySummaryGenerateOutcome {
    /** Row was already present. */
    AlreadyHad,

    /** Local calendar day has not ended yet; summaries run only for past days. */
    DayNotConcluded,

    /** Nothing to summarize (no sessions with events in range). */
    NoSessionsToSummarize,

    /** Persisted a new summary. */
    Generated,

    /** Backend call failed or returned blank. */
    ApiError,
}

object DailyLogSummaryGenerator {

    private const val TAG = "DailyLogSummaryGen"
    private const val MAX_EVENTS_PER_SESSION = 250

    /**
     * Ensures a daily summary exists for [dayKey] (yyyy-MM-dd) when there is log data for that day.
     * Does nothing if a summary already exists, the local calendar day has not finished yet,
     * or there are no sessions with events in that local day.
     */
    suspend fun generateIfMissing(
        context: Context,
        dayKey: String,
        token: String,
    ): DailySummaryGenerateOutcome {
        val summaryDao = AppDatabase.getInstance(context).dailyLogSummaryDao()
        if (summaryDao.getByDay(dayKey) != null) {
            return DailySummaryGenerateOutcome.AlreadyHad
        }
        return generateAndPersist(context, dayKey, token)
    }

    /**
     * Loads session logs for [dayKey], calls the model, and upserts a row. Overwrites an existing row on success.
     * On failure, any previous row is left unchanged (caller must not delete beforehand).
     */
    private suspend fun generateAndPersist(
        context: Context,
        dayKey: String,
        token: String,
    ): DailySummaryGenerateOutcome {
        val db = AppDatabase.getInstance(context)
        val summaryDao = db.dailyLogSummaryDao()
        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse(dayKey)
        if (gateDailySummaryDay(day, LocalDate.now(zone)) != DailySummaryDayGate.Ok) {
            return DailySummaryGenerateOutcome.DayNotConcluded
        }
        val sessionDao = db.sessionLogDao()
        val (startMs, endMs) = dayRangeMs(day, zone)
        val sessions = sessionDao.getSessionsWithCountsInRange(startMs, endMs)
        if (sessions.isEmpty()) return DailySummaryGenerateOutcome.NoSessionsToSummarize

        var totalEvents = 0
        val rawLogText = buildString {
            sessions.forEach { session ->
                appendLine("## ${session.title} (${Instant.ofEpochMilli(session.startedAtMs)})")
                val events = sessionDao.getEventsForSession(session.id)
                totalEvents += events.size
                val capped = events.take(MAX_EVENTS_PER_SESSION)
                capped.forEach { e ->
                    append("- ")
                    append(Instant.ofEpochMilli(e.timestampMs))
                    append(" ")
                    appendLine(e.entry)
                }
                if (events.size > capped.size) {
                    appendLine("- _(truncated: ${events.size - capped.size} more events)_")
                }
                appendLine()
            }
        }.trim()

        val prompt = buildDailySummaryPrompt(
            instructions = SettingsManager.getDailySummaryPromptTextResolved(context),
            dayKey = dayKey,
            sessionCount = sessions.size,
            eventCount = totalEvents,
            rawLogText = rawLogText,
        )
        val promptVersion = SettingsManager.getDailySummaryPromptVersion(context)
        val model = SettingsManager.getBackendModel(context)

        return persistDailySummaryFromModel(
            token = token,
            model = model,
            prompt = prompt,
            dayKey = dayKey,
            sessionsSize = sessions.size,
            totalEvents = totalEvents,
            promptVersion = promptVersion,
            summaryDao = summaryDao,
        )
    }

    private suspend fun persistDailySummaryFromModel(
        token: String,
        model: String,
        prompt: String,
        dayKey: String,
        sessionsSize: Int,
        totalEvents: Int,
        promptVersion: Int,
        summaryDao: com.mindfulhome.data.DailyLogSummaryDao,
    ): DailySummaryGenerateOutcome = try {
        val response = BackendClient.generate(
            token = token,
            model = model,
            contents = listOf(
                BackendClient.BackendContent(
                    role = "user",
                    parts = listOf(BackendClient.BackendPart(prompt)),
                ),
            ),
            tools = null,
        )
        val raw = response.result?.trim().orEmpty()
        if (raw.isBlank()) {
            Log.w(TAG, "Backend returned blank summary; skipping persist for $dayKey")
            return DailySummaryGenerateOutcome.ApiError
        }
        val parsed = DailyLogSummaryJson.parseModelOutput(raw).getOrElse { e ->
            Log.w(TAG, "Invalid JSON summary for $dayKey: ${e.message}")
            return DailySummaryGenerateOutcome.ApiError
        }
        val (summaryText, taglineText) = parsed
        summaryDao.upsert(
            DailyLogSummary(
                day = dayKey,
                summary = summaryText,
                tagline = taglineText,
                summaryJson = DailyLogSummaryJson.buildJson(summaryText, taglineText),
                generatedAtMs = System.currentTimeMillis(),
                sessionCount = sessionsSize,
                eventCount = totalEvents,
                promptVersion = promptVersion,
            ),
        )
        Log.i(TAG, "Saved daily summary for $dayKey (promptVersion=$promptVersion)")
        DailySummaryGenerateOutcome.Generated
    } catch (e: Exception) {
        Log.e(TAG, "Daily summary generation failed for $dayKey", e)
        DailySummaryGenerateOutcome.ApiError
    }

    data class RegenerateSummaryResult(
        val successCount: Int,
        /** Days that matched prompt-version criteria (may be 0 if none stored yet). */
        val candidateDays: Int,
    )

    /**
     * Regenerates up to [count] most recent summaries whose [DailyLogSummary.promptVersion]
     * is strictly less than [newPromptVersion]. Existing rows are **not** deleted until a new summary
     * is successfully persisted (so API/JSON failures cannot wipe stored summaries).
     */
    suspend fun regenerateSummariesWithOlderPrompt(
        context: Context,
        token: String,
        newPromptVersion: Int,
        count: Int,
    ): RegenerateSummaryResult {
        if (!shouldRunDailySummaryRegenerate(count, newPromptVersion)) {
            return RegenerateSummaryResult(0, 0)
        }
        val dao = AppDatabase.getInstance(context).dailyLogSummaryDao()
        val days = dao.getDaysWithPromptVersionBefore(newPromptVersion, count)
        var generated = 0
        for (dayKey in days) {
            val outcome = generateAndPersist(context, dayKey, token)
            logRegenerateOutcome(dayKey, outcome)
            if (outcome == DailySummaryGenerateOutcome.Generated) generated++
        }
        return RegenerateSummaryResult(successCount = generated, candidateDays = days.size)
    }

    private fun logRegenerateOutcome(dayKey: String, outcome: DailySummaryGenerateOutcome) {
        when (outcome) {
            DailySummaryGenerateOutcome.Generated -> Unit
            DailySummaryGenerateOutcome.ApiError ->
                Log.w(TAG, "Regenerate failed for $dayKey; keeping previous summary row")
            DailySummaryGenerateOutcome.NoSessionsToSummarize ->
                Log.w(TAG, "Regenerate skipped for $dayKey: no session logs in range")
            DailySummaryGenerateOutcome.DayNotConcluded ->
                Log.w(TAG, "Regenerate skipped for $dayKey: day not concluded")
            DailySummaryGenerateOutcome.AlreadyHad -> Unit
        }
    }

    fun dayRangeMs(day: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }
}
