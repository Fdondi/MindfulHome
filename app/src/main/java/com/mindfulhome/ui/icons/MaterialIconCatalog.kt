package com.mindfulhome.ui.icons

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads [material_icons_outlined.codepoints] from assets (Google Material Icons Outlined;
 * same snake_case names as https://fonts.google.com/icons ).
 */
object MaterialIconCatalog {

    private const val ASSET = "material_icons_outlined.codepoints"

    @Volatile
    private var loaded: Pair<Map<String, Int>, List<String>>? = null

    fun codepoint(context: Context, name: String): Int? = map(context)[name]

    /** Sorted icon names for search / picker. */
    fun allNames(context: Context): List<String> = sortedNames(context)

    fun filterNames(context: Context, query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return allNames(context)
        return allNames(context).filter { it.contains(q) }
    }

    private fun map(context: Context): Map<String, Int> {
        return loaded?.first ?: load(context).first
    }

    private fun sortedNames(context: Context): List<String> {
        return loaded?.second ?: load(context).second
    }

    private fun load(context: Context): Pair<Map<String, Int>, List<String>> {
        synchronized(this) {
            loaded?.let { return it }
            val map = LinkedHashMap<String, Int>(4096)
            context.assets.open(ASSET).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                        val space = trimmed.lastIndexOf(' ')
                        if (space <= 0) return@forEach
                        val name = trimmed.substring(0, space).trim()
                        val hex = trimmed.substring(space + 1).trim()
                        if (name.isEmpty()) return@forEach
                        val cp = hex.toIntOrNull(16) ?: return@forEach
                        map[name] = cp
                    }
                }
            }
            val sorted = map.keys.sorted()
            val pair = map to sorted
            loaded = pair
            return pair
        }
    }
}
