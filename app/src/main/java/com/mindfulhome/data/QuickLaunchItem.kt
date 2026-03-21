package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quick_launch_items")
data class QuickLaunchItem(
    @PrimaryKey
    val packageName: String,
    val position: Int = 0,
    val folderId: Long? = null,
)
