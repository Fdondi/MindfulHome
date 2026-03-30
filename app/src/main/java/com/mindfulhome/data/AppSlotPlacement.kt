package com.mindfulhome.data

/**
 * Where an app appears on the QuickLaunch / Favorites strip for UI (search list, badges).
 * The same package may appear multiple times (root + folder(s), or multiple folders).
 */
sealed class AppSlotPlacement {
    /** Own tile on the strip (not inside a folder). */
    data object Root : AppSlotPlacement()

    /** Inside a folder tile; [symbolIconName] is that folder’s optional badge icon. */
    data class InFolder(val symbolIconName: String?) : AppSlotPlacement()
}

/** Maps each package to every root or folder placement (order preserved). */
fun placementsByPackage(slots: List<QuickLaunchSlot>): Map<String, List<AppSlotPlacement>> {
    val map = mutableMapOf<String, MutableList<AppSlotPlacement>>()
    for (slot in slots) {
        when (slot) {
            is QuickLaunchSlot.Single -> {
                map.getOrPut(slot.packageName) { mutableListOf() }.add(AppSlotPlacement.Root)
            }
            is QuickLaunchSlot.Folder -> {
                for (pkg in slot.apps) {
                    map.getOrPut(pkg) { mutableListOf() }.add(AppSlotPlacement.InFolder(slot.symbolIconName))
                }
            }
        }
    }
    return map
}
