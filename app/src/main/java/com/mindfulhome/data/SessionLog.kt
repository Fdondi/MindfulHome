package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_logs",
    indices = [Index(value = ["startedAtMs"])]
)
data class SessionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAtMs: Long,
    val title: String,
)

@Entity(
    tableName = "session_log_events",
    foreignKeys = [
        ForeignKey(
            entity = SessionLog::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["timestampMs"])]
)
data class SessionLogEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampMs: Long,
    val entry: String,
)

