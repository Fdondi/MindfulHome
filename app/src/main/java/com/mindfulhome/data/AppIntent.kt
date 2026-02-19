package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Stores a declared intent (unlock reason) associated with an app launch. */
@Entity(tableName = "app_intent")
data class AppIntent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val intentText: String,
    val timestamp: Long = System.currentTimeMillis()
)
