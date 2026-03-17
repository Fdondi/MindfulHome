package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "todo_items")
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intentText: String,
    val expectedDurationMinutes: Int?,
    val deadlineEpochMs: Long?,
    val priority: Int = 2,
    val isCompleted: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis(),
)
