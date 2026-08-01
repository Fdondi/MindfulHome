package com.mindfulhome.data

/**
 * Pure slot-merge helpers extracted from [AppRepository] for unit testing and CRAP reduction.
 */

fun folderAppsFromSlot(slot: QuickLaunchSlot): List<QuickLaunchFolderApp> = when (slot) {
    is QuickLaunchSlot.Single -> listOf(QuickLaunchFolderApp.unlimited(slot.packageName))
    is QuickLaunchSlot.Folder -> slot.apps
}

fun pickMergedFolderName(intoSlot: QuickLaunchSlot, fromSlot: QuickLaunchSlot): String? = when {
    intoSlot is QuickLaunchSlot.Folder && !intoSlot.name.isNullOrBlank() -> intoSlot.name
    fromSlot is QuickLaunchSlot.Folder && !fromSlot.name.isNullOrBlank() -> fromSlot.name
    else -> null
}

fun pickMergedFolderSymbol(intoSlot: QuickLaunchSlot, fromSlot: QuickLaunchSlot): String? = when {
    intoSlot is QuickLaunchSlot.Folder && !intoSlot.symbolIconName.isNullOrBlank() ->
        intoSlot.symbolIconName
    fromSlot is QuickLaunchSlot.Folder && !fromSlot.symbolIconName.isNullOrBlank() ->
        fromSlot.symbolIconName
    else -> null
}

fun intoIndexAfterRemove(fromIndex: Int, intoIndex: Int): Int =
    if (fromIndex < intoIndex) intoIndex - 1 else intoIndex

/**
 * Merges [fromUiIndex] into [intoUiIndex] for Favorites / classic QuickLaunch strips.
 * Collapses to [QuickLaunchSlot.Single] when only one app remains.
 * Mutates [slots]; returns null when indices are invalid or identical.
 */
fun mergeSlotsMutable(
    slots: MutableList<QuickLaunchSlot>,
    fromUiIndex: Int,
    intoUiIndex: Int,
): List<QuickLaunchSlot>? {
    if (fromUiIndex == intoUiIndex) return null
    if (fromUiIndex !in slots.indices || intoUiIndex !in slots.indices) return null
    val fromSlot = slots.removeAt(fromUiIndex)
    val intoIdx = intoIndexAfterRemove(fromUiIndex, intoUiIndex)
    val intoSlot = slots[intoIdx]
    val mergedApps = mergeFolderApps(folderAppsFromSlot(intoSlot), folderAppsFromSlot(fromSlot))
    val mergedName = pickMergedFolderName(intoSlot, fromSlot)
    val mergedSymbol = pickMergedFolderSymbol(intoSlot, fromSlot)
    slots[intoIdx] = if (mergedApps.size == 1) {
        QuickLaunchSlot.Single(mergedApps[0].packageName)
    } else {
        QuickLaunchSlot.Folder(mergedName, mergedApps, mergedSymbol)
    }
    return slots
}

/**
 * Merges intent folders: always keeps a Folder (never collapses to Single) and concatenates shortcuts.
 */
fun mergeIntentSlotsMutable(
    slots: MutableList<QuickLaunchSlot>,
    fromUiIndex: Int,
    intoUiIndex: Int,
): List<QuickLaunchSlot>? {
    if (fromUiIndex == intoUiIndex) return null
    if (fromUiIndex !in slots.indices || intoUiIndex !in slots.indices) return null
    val fromSlot = slots.removeAt(fromUiIndex)
    val intoIdx = intoIndexAfterRemove(fromUiIndex, intoUiIndex)
    val intoSlot = slots[intoIdx]
    val mergedApps = mergeFolderApps(folderAppsFromSlot(intoSlot), folderAppsFromSlot(fromSlot))
    val mergedName = pickMergedFolderName(intoSlot, fromSlot)
    val mergedSymbol = pickMergedFolderSymbol(intoSlot, fromSlot)
    val shortcuts = (
        (intoSlot as? QuickLaunchSlot.Folder)?.shortcuts.orEmpty() +
            (fromSlot as? QuickLaunchSlot.Folder)?.shortcuts.orEmpty()
        ).distinctBy { it.packageName to it.id }
    slots[intoIdx] = QuickLaunchSlot.Folder(mergedName, mergedApps, mergedSymbol, shortcuts)
    return slots
}

fun extractFromFolderSlot(
    slots: MutableList<QuickLaunchSlot>,
    packageName: String,
): List<QuickLaunchSlot>? {
    for (i in slots.indices) {
        val slot = slots[i]
        if (slot !is QuickLaunchSlot.Folder) continue
        if (packageName !in slot.packageNames()) continue
        if (slot.apps.size <= 1) return null
        val remaining = slot.apps.filter { it.packageName != packageName }
        slots[i] = when (remaining.size) {
            1 -> QuickLaunchSlot.Single(remaining[0].packageName)
            else -> QuickLaunchSlot.Folder(slot.name, remaining, slot.symbolIconName)
        }
        slots.add(i + 1, QuickLaunchSlot.Single(packageName))
        return slots
    }
    return null
}

fun extractFromIntentFolderSlot(
    slots: MutableList<QuickLaunchSlot>,
    packageName: String,
): List<QuickLaunchSlot>? {
    for (i in slots.indices) {
        val slot = slots[i]
        if (slot !is QuickLaunchSlot.Folder) continue
        if (packageName !in slot.packageNames()) continue
        if (slot.apps.size <= 1) return null
        val extracted = slot.apps.first { it.packageName == packageName }
        val remaining = slot.apps.filter { it.packageName != packageName }
        slots[i] = QuickLaunchSlot.Folder(slot.name, remaining, slot.symbolIconName)
        slots.add(i + 1, QuickLaunchSlot.Folder(null, listOf(extracted)))
        return slots
    }
    return null
}
