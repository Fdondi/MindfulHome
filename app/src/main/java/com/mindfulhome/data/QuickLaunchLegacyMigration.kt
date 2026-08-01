package com.mindfulhome.data

import android.database.Cursor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the same JSON array format as [QuickLaunchJson] from legacy `quick_launch_items` rows.
 */
internal object QuickLaunchLegacyMigration {

    private data class Row(val pkg: String, val slot: Int, val order: Int, val folderName: String?)

    fun buildJsonFromLegacyCursor(cursor: Cursor): String {
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
        if (rows.isEmpty()) return "[]"

        val bySlot = rows.groupBy { it.slot }.toSortedMap()
        return buildJsonArray {
            for ((_, slotRows) in bySlot) {
                add(encodeLegacySlot(slotRows.sortedBy { it.order }))
            }
        }.toString()
    }

    private fun encodeLegacySlot(sorted: List<Row>): JsonElement {
        if (sorted.size == 1) return JsonPrimitive(sorted[0].pkg)
        val name = sorted
            .firstOrNull { !it.folderName.isNullOrBlank() }
            ?.folderName?.trim()
            ?.takeIf { it.isNotEmpty() }
        return buildJsonObject {
            if (name != null) put("name", name)
            put("apps", JsonArray(sorted.map { JsonPrimitive(it.pkg) }))
        }
    }
}
