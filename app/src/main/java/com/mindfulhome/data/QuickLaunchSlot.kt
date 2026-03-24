package com.mindfulhome.data

/**
 * One QuickLaunch tile: a single app, or a folder of apps with an optional custom name.
 * Serialized as a JSON array: `["pkg", {"name":"…","apps":["a","b"]}, …]`.
 */
sealed class QuickLaunchSlot {
    data class Single(val packageName: String) : QuickLaunchSlot()
    data class Folder(val name: String?, val apps: List<String>) : QuickLaunchSlot()
}

fun QuickLaunchSlot.flattenPackages(): List<String> = when (this) {
    is QuickLaunchSlot.Single -> listOf(packageName)
    is QuickLaunchSlot.Folder -> apps
}

/** Drops invalid entries; collapses single-app folders to [QuickLaunchSlot.Single]. */
internal fun normalizeQuickLaunchSlots(slots: List<QuickLaunchSlot>): List<QuickLaunchSlot> =
    slots.mapNotNull { slot ->
        when (slot) {
            is QuickLaunchSlot.Single ->
                if (slot.packageName.isBlank()) null else slot
            is QuickLaunchSlot.Folder -> {
                val apps = slot.apps.filter { it.isNotBlank() }.distinct()
                when (apps.size) {
                    0 -> null
                    1 -> QuickLaunchSlot.Single(apps[0])
                    else -> QuickLaunchSlot.Folder(slot.name, apps)
                }
            }
        }
    }
