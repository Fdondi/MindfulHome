package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_sessions")
data class UsageSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val startTimestamp: Long,
    val endTimestamp: Long? = null,
    val timerDurationMs: Long,
    val overrunMs: Long = 0,
    val closedOnTime: Boolean = false,
    val aiExtensionGranted: Boolean = false,
    val karmaChange: Int = 0
)
