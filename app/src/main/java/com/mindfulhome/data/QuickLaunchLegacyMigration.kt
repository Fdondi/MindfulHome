package com.mindfulhome.data

import android.database.Cursor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the same JSON array format as [QuickLaunchJson] from legacy `quick_launch_items` rows.
 */
internal object QuickLaunchLegacyMigration {

    fun buildJsonFromLegacyCursor(cursor: Cursor): String {
        data class Row(val pkg: String, val slot: Int, val order: Int, val folderName: String?)
        val rows = mutableListOf<Row>()
        while (cursor.moveToNext()) {
            rows.add(
                Row(
                    pkg = cursor.getString(0),
                    slot = cursor.getInt(1),
                    order = cursor.getInt(2),
                    folderName = if (cursor.isNull(3)) null else cursor.getString(3),
                ),
            )
        }
        cursor.close()
        if (rows.isEmpty()) return "[]"

        val bySlot = rows.groupBy { it.slot }.toSortedMap()
        return buildJsonArray {
            for ((_, slotRows) in bySlot) {
                val sorted = slotRows.sortedBy { it.order }
                when (sorted.size) {
                    1 -> add(JsonPrimitive(sorted[0].pkg))
                    else -> {
                        val name = sorted
                            .firstOrNull { !it.folderName.isNullOrBlank() }
                            ?.folderName?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        add(
                            buildJsonObject {
                                if (name != null) put("name", name)
                                put(
                                    "apps",
                                    JsonArray(sorted.map { JsonPrimitive(it.pkg) }),
                                )
                            },
                        )
                    }
                }
            }
        }.toString()
    }
}
