package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "home_layout")
data class HomeLayoutItem(
    @PrimaryKey
    val packageName: String,
    val position: Int = 0,
    val isDocked: Boolean = false,
    val dockPosition: Int = 0
)
