package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_kv")
data class AppKv(
    @PrimaryKey val key: String,
    val value: String,
)
