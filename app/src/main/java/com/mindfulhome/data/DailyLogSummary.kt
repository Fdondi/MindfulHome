package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_log_summaries")
data class DailyLogSummary(
    /**
     * Local date key in ISO-8601 format: yyyy-MM-dd
     */
    @PrimaryKey
    val day: String,
    /** Body text from JSON `summary` (also stored denormalized for queries). */
    val summary: String,
    /** Short title/snippet from JSON `tagline`. */
    val tagline: String,
    /** Canonical `{"summary":"...","tagline":"..."}` from the model. */
    val summaryJson: String,
    val generatedAtMs: Long,
    val sessionCount: Int,
    val eventCount: Int,
    /** Version of the summarization prompt ([SettingsManager] daily-summary prompt) used when generated. */
    val promptVersion: Int,
)

