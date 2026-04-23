package com.mindfulhome.data

/**
 * One QuickLaunch tile: a single app, or a folder of apps with an optional custom name.
 * Serialized as a JSON array: `["pkg", {"name":"…","apps":["a","b"]}, …]`.
 */
sealed class QuickLaunchSlot {
    data class Single(val packageName: String) : QuickLaunchSlot()

    /**
     * @param symbolIconName Optional Material Icons name (snake_case, see fonts.google.com/icons)
     *  drawn as a badge over the folder glyph; must exist in [material_icons_outlined.codepoints].
     */
    data class Folder(
        val name: String?,
        val apps: List<String>,
        val symbolIconName: String? = null,
    ) : QuickLaunchSlot()

    fun flattenPackages(): List<String> = when (this) {
        is Single -> listOf(packageName)
        is Folder -> apps
    }
}

/**
 * Removes blank package names and empty folders.
 * Drops invalid entries; collapses single-app folders to [QuickLaunchSlot.Single].
 */
fun normalizeQuickLaunchSlots(slots: List<QuickLaunchSlot>): List<QuickLaunchSlot> {
    return slots.mapNotNull { slot ->
        when (slot) {
            is QuickLaunchSlot.Single -> {
                if (slot.packageName.isBlank()) null else slot
            }
            is QuickLaunchSlot.Folder -> {
                val apps = slot.apps.filter { it.isNotBlank() }.distinct()
                when (apps.size) {
                    0 -> null
                    1 -> QuickLaunchSlot.Single(apps[0])
                    else -> slot.copy(apps = apps)
                }
            }
        }
    }
}
