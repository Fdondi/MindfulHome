package com.mindfulhome.data

import android.database.Cursor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds the same JSON array format as [QuickLaunchJson] from legacy `shelf_items` rows. */
internal object FavoritesShelfLegacyMigration {

    fun buildJsonFromShelfCursor(cursor: Cursor): String {
        data class Row(val pkg: String, val slot: Int, val order: Int)
        val rows = mutableListOf<Row>()
        while (cursor.moveToNext()) {
            rows.add(
                Row(
                    pkg = cursor.getString(0),
                    slot = cursor.getInt(1),
                    order = cursor.getInt(2),
                ),
            )
        }
        if (rows.isEmpty()) return "[]"

        val bySlot = rows.groupBy { it.slot }.toSortedMap()
        return buildJsonArray {
            for ((_, slotRows) in bySlot) {
                val sorted = slotRows.sortedBy { it.order }
                when (sorted.size) {
                    1 -> add(JsonPrimitive(sorted[0].pkg))
                    else -> add(
                        buildJsonObject {
                            put(
                                "apps",
                                JsonArray(sorted.map { JsonPrimitive(it.pkg) }),
                            )
                        },
                    )
                }
            }
        }.toString()
    }
}
