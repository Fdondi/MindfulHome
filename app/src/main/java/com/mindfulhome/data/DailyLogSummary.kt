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
    val summary: String,
    val generatedAtMs: Long,
    val sessionCount: Int,
    val eventCount: Int,
)

