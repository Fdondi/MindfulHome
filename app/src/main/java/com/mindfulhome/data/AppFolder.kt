package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_folders")
data class AppFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val position: Int = 0
)

@Entity(
    tableName = "folder_apps",
    primaryKeys = ["folderId", "packageName"]
)
data class FolderApp(
    val folderId: Long,
    val packageName: String,
    val position: Int = 0
)
