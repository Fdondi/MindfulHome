package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_karma")
data class AppKarma(
    @PrimaryKey
    val packageName: String,
    val karmaScore: Int = 0,
    val totalOpens: Int = 0,
    val totalOverruns: Int = 0,
    val closedOnTimeCount: Int = 0,
    val lastOpenedTimestamp: Long = 0,
    val isHidden: Boolean = false
)
