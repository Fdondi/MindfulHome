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
     * Does nothing if a summary already exists or there are no sessions with events in that local day.
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

        val instructions = SettingsManager.getDailySummaryPromptTextResolved(context)
        val promptVersion = SettingsManager.getDailySummaryPromptVersion(context)
        val model = SettingsManager.getBackendModel(context)

        val prompt = buildString {
            appendLine(instructions.trim())
            appendLine()
            appendLine(
                "Output a single JSON object only (no markdown fences). Use exactly two string keys, " +
                    "in this order: first \"summary\", then \"tagline\". " +
                    "The \"summary\" value is the full daily write-up. " +
                    "The \"tagline\" value must be written last: a very short line used as the collapsed preview " +
                    "and expanded title (compose it after the full summary is decided).",
            )
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
                model = model,
                contents = listOf(
                    BackendClient.BackendContent(
                        role = "user",
                        parts = listOf(BackendClient.BackendPart(prompt)),
                    )
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
            val summaryJson = DailyLogSummaryJson.buildJson(summaryText, taglineText)
            summaryDao.upsert(
                DailyLogSummary(
                    day = dayKey,
                    summary = summaryText,
                    tagline = taglineText,
                    summaryJson = summaryJson,
                    generatedAtMs = System.currentTimeMillis(),
                    sessionCount = sessions.size,
                    eventCount = totalEvents,
                    promptVersion = promptVersion,
                )
            )
            Log.i(TAG, "Saved daily summary for $dayKey (promptVersion=$promptVersion)")
            DailySummaryGenerateOutcome.Generated
        } catch (e: Exception) {
            Log.e(TAG, "Daily summary generation failed for $dayKey", e)
            DailySummaryGenerateOutcome.ApiError
        }
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
        if (count <= 0 || newPromptVersion <= 0) {
            return RegenerateSummaryResult(0, 0)
        }
        val dao = AppDatabase.getInstance(context).dailyLogSummaryDao()
        val days = dao.getDaysWithPromptVersionBefore(newPromptVersion, count)
        var generated = 0
        for (dayKey in days) {
            when (generateAndPersist(context, dayKey, token)) {
                DailySummaryGenerateOutcome.Generated -> generated++
                DailySummaryGenerateOutcome.ApiError ->
                    Log.w(TAG, "Regenerate failed for $dayKey; keeping previous summary row")
                DailySummaryGenerateOutcome.NoSessionsToSummarize ->
                    Log.w(TAG, "Regenerate skipped for $dayKey: no session logs in range")
                DailySummaryGenerateOutcome.AlreadyHad -> Unit
            }
        }
        return RegenerateSummaryResult(successCount = generated, candidateDays = days.size)
    }

    fun dayRangeMs(day: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }
}
