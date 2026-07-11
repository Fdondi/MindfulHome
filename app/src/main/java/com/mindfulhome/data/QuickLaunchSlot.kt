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
        val apps: List<QuickLaunchFolderApp>,
        val symbolIconName: String? = null,
        val shortcuts: List<PinnedShortcut> = emptyList(),
    ) : QuickLaunchSlot() {
        fun limitMinutesFor(packageName: String): Int? =
            apps.firstOrNull { it.packageName == packageName }?.limitMinutes

        fun packageNames(): List<String> = apps.map { it.packageName }
    }

    fun flattenAllowedPackages(): List<String> = when (this) {
        is Single -> listOf(com.mindfulhome.util.QuickLaunchAppRef.ownerPackage(packageName))
        is Folder -> apps.filter { it.isUnlimited }.map { it.packageName } +
            shortcuts.map { it.packageName }
    }

    /** Launch keys shown in folder UI (plain packages + encoded shortcuts). */
    fun flattenLaunchKeys(): List<String> = when (this) {
        is Single -> listOf(packageName)
        is Folder -> apps.map { it.packageName } +
            shortcuts.map { com.mindfulhome.util.QuickLaunchAppRef.shortcutKey(it) }
    }

    fun flattenPackages(): List<String> = when (this) {
        is Single -> listOf(packageName)
        is Folder -> apps.map { it.packageName }
    }

    fun limitMinutesByPackage(): Map<String, Int> = when (this) {
        is Single -> emptyMap()
        is Folder -> apps.mapNotNull { app ->
            app.limitMinutes?.let { app.packageName to it }
        }.toMap()
    }

    fun itemCount(): Int = when (this) {
        is Single -> 1
        is Folder -> apps.size + shortcuts.size
    }
}

fun normalizeFolderApps(apps: List<QuickLaunchFolderApp>): List<QuickLaunchFolderApp> {
    val merged = LinkedHashMap<String, QuickLaunchFolderApp>()
    for (app in apps) {
        val pkg = app.packageName.trim()
        if (pkg.isEmpty()) continue
        merged.putIfAbsent(pkg, app.copy(packageName = pkg))
    }
    return merged.values.toList()
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
                val apps = normalizeFolderApps(slot.apps)
                val shortcuts = slot.shortcuts.filter {
                    it.packageName.isNotBlank() && it.id.isNotBlank()
                }
                when {
                    apps.isEmpty() && shortcuts.isEmpty() -> null
                    apps.size == 1 && shortcuts.isEmpty() ->
                        QuickLaunchSlot.Single(apps.single().packageName)
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
                else QuickLaunchSlot.Folder(null, listOf(QuickLaunchFolderApp.unlimited(slot.packageName)))
            }
            is QuickLaunchSlot.Folder -> {
                val apps = normalizeFolderApps(slot.apps)
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
