package com.mindfulhome.logging

import android.content.Context
import android.util.Log
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.data.AppDatabase
import com.mindfulhome.data.DailyLogSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DailySummaryGenerateOutcome {
    /** Row was already present. */
    AlreadyHad,

    /** Nothing to summarize (no sessions with events in range). */
    NoSessionsToSummarize,

    /** Persisted a new summary. */
    Generated,

    /** Backend call failed or returned blank. */
    ApiError,
}

object DailyLogSummaryGenerator {

    private const val TAG = "DailyLogSummaryGen"
    private const val MODEL_FLASH = "gemini-2.5-flash"
    private const val MAX_EVENTS_PER_SESSION = 250

    /**
     * Ensures a daily summary exists for [dayKey] (yyyy-MM-dd) when there is log data for that day.
     * Does nothing if a summary already exists or there are no sessions with events in that local day.
     */
    suspend fun generateIfMissing(
        context: Context,
        dayKey: String,
        token: String,
    ): DailySummaryGenerateOutcome {
        val db = AppDatabase.getInstance(context)
        val summaryDao = db.dailyLogSummaryDao()
        if (summaryDao.getByDay(dayKey) != null) {
            return DailySummaryGenerateOutcome.AlreadyHad
        }

        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse(dayKey)
        val sessionDao = db.sessionLogDao()
        val (startMs, endMs) = dayRangeMs(day, zone)
        val sessions = sessionDao.getSessionsWithCountsInRange(startMs, endMs)
        if (sessions.isEmpty()) {
            return DailySummaryGenerateOutcome.NoSessionsToSummarize
        }

        var totalEvents = 0
        val rawLogText = buildString {
            sessions.forEach { session ->
                appendLine("## ${session.title} (${Instant.ofEpochMilli(session.startedAtMs)})")
                val events = sessionDao.getEventsForSession(session.id)
                totalEvents += events.size
                val capped = if (events.size > MAX_EVENTS_PER_SESSION) {
                    events.take(MAX_EVENTS_PER_SESSION)
                } else {
                    events
                }
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

        val prompt = buildString {
            appendLine("You are generating an end-of-day summary for the user's phone usage sessions.")
            appendLine("Highlight what's interesting or notable about what happened today.")
            appendLine("Be concise, with 3-7 bullet points max, and one short concluding sentence.")
            appendLine()
            appendLine("Day: $dayKey")
            appendLine("Sessions: ${sessions.size}")
            appendLine("Events: $totalEvents")
            appendLine()
            appendLine("Session logs:")
            appendLine(rawLogText)
        }.trim()

        return try {
            val response = BackendClient.generate(
                token = token,
                model = MODEL_FLASH,
                contents = listOf(
                    BackendClient.BackendContent(
                        role = "user",
                        parts = listOf(BackendClient.BackendPart(prompt)),
                    )
                ),
                tools = null,
            )
            val summary = response.result?.trim().orEmpty()
            if (summary.isBlank()) {
                Log.w(TAG, "Backend returned blank summary; skipping persist for $dayKey")
                return DailySummaryGenerateOutcome.ApiError
            }
            summaryDao.upsert(
                DailyLogSummary(
                    day = dayKey,
                    summary = summary,
                    generatedAtMs = System.currentTimeMillis(),
                    sessionCount = sessions.size,
                    eventCount = totalEvents,
                )
            )
            Log.i(TAG, "Saved daily summary for $dayKey")
            DailySummaryGenerateOutcome.Generated
        } catch (e: Exception) {
            Log.e(TAG, "Daily summary generation failed for $dayKey", e)
            DailySummaryGenerateOutcome.ApiError
        }
    }

    fun dayRangeMs(day: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }
}
