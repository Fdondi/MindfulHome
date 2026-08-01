package com.mindfulhome.ui.icons

/** Parses one line from material icon codepoints asset: `name hex`. */
fun parseMaterialIconCodepointLine(line: String): Pair<String, Int>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val space = trimmed.lastIndexOf(' ')
    if (space <= 0) return null
    val name = trimmed.substring(0, space).trim()
    val hex = trimmed.substring(space + 1).trim()
    if (name.isEmpty()) return null
    val cp = hex.toIntOrNull(16) ?: return null
    return name to cp
}
