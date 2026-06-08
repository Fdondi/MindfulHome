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
        val shortcuts: List<PinnedShortcut> = emptyList(),
    ) : QuickLaunchSlot()

    fun flattenAllowedPackages(): List<String> = when (this) {
        is Single -> listOf(com.mindfulhome.util.QuickLaunchAppRef.ownerPackage(packageName))
        is Folder -> apps + shortcuts.map { it.packageName }
    }

    /** Launch keys shown in folder UI (plain packages + encoded shortcuts). */
    fun flattenLaunchKeys(): List<String> = when (this) {
        is Single -> listOf(packageName)
        is Folder -> apps + shortcuts.map { com.mindfulhome.util.QuickLaunchAppRef.shortcutKey(it) }
    }

    fun flattenPackages(): List<String> = when (this) {
        is Single -> listOf(packageName)
        is Folder -> apps
    }

    fun itemCount(): Int = when (this) {
        is Single -> 1
        is Folder -> apps.size + shortcuts.size
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
                val shortcuts = slot.shortcuts.filter {
                    it.packageName.isNotBlank() && it.id.isNotBlank()
                }
                when {
                    apps.isEmpty() && shortcuts.isEmpty() -> null
                    apps.size == 1 && shortcuts.isEmpty() -> QuickLaunchSlot.Single(apps[0])
                    else -> slot.copy(apps = apps, shortcuts = shortcuts)
                }
            }
        }
    }
}

/**
 * Normalization for mission-intent folders on the default page: keeps named folders even when
 * empty or holding a single app; legacy singles are wrapped as unnamed folders.
 */
fun normalizeIntentQuickLaunchSlots(slots: List<QuickLaunchSlot>): List<QuickLaunchSlot> {
    return slots.mapNotNull { slot ->
        when (slot) {
            is QuickLaunchSlot.Single -> {
                if (slot.packageName.isBlank()) null
                else QuickLaunchSlot.Folder(null, listOf(slot.packageName))
            }
            is QuickLaunchSlot.Folder -> {
                val apps = slot.apps.filter { it.isNotBlank() }.distinct()
                val shortcuts = slot.shortcuts.filter {
                    it.packageName.isNotBlank() && it.id.isNotBlank()
                }
                val name = slot.name?.trim()?.takeIf { it.isNotEmpty() }
                if (apps.isEmpty() && shortcuts.isEmpty() && name == null) null
                else slot.copy(name = name, apps = apps, shortcuts = shortcuts)
            }
        }
    }
}
