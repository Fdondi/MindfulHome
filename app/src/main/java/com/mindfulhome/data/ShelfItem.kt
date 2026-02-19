package com.mindfulhome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shelf_items")
data class ShelfItem(
    @PrimaryKey
    val packageName: String,
    val slotPosition: Int = 0,
    val orderInSlot: Int = 0
)
