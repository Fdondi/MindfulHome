package com.mindfulhome.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mindfulhome.data.HomeLayoutItem
import com.mindfulhome.model.AppInfo

/** Pure helpers extracted from [HomeScreen] / [DragDropState] for unit testing and CRAP reduction. */

fun negativeKarmaPackageSet(
    karmaByPackage: Map<String, KarmaScoreLike>,
): Set<String> =
    karmaByPackage.values
        .asSequence()
        .filter { it.karmaScore < 0 && !it.isOptedOut }
        .map { it.packageName }
        .toSet()

/** Minimal karma row shape so tests need not depend on Room entities. */
interface KarmaScoreLike {
    val packageName: String
    val karmaScore: Int
    val isOptedOut: Boolean
}

data class SimpleKarmaScore(
    override val packageName: String,
    override val karmaScore: Int,
    override val isOptedOut: Boolean = false,
) : KarmaScoreLike

fun shouldRequestAi(packageName: String, negativeKarmaPackages: Set<String>): Boolean =
    packageName in negativeKarmaPackages

fun buildGridItems(
    visibleApps: List<AppInfo>,
    layoutItems: List<HomeLayoutItem>,
): List<HomeGridItem> {
    val layoutMap = layoutItems.associateBy { it.packageName }
    return visibleApps.map { app ->
        HomeGridItem.AppEntry(
            appInfo = app,
            position = layoutMap[app.packageName]?.position ?: Int.MAX_VALUE,
        )
    }.sortedWith(
        compareBy<HomeGridItem.AppEntry> { it.position }
            .thenBy { it.appInfo.label.lowercase() },
    )
}

fun layoutUpdatesFromGrid(items: List<HomeGridItem>): List<HomeLayoutItem> =
    items.mapIndexedNotNull { index, item ->
        if (item is HomeGridItem.AppEntry) {
            HomeLayoutItem(
                packageName = item.appInfo.packageName,
                position = index,
                isDocked = false,
                dockPosition = 0,
            )
        } else {
            null
        }
    }

sealed class HomeDropAction {
    data class AddToFavorites(val packageName: String) : HomeDropAction()
    data class MergeIntoFavoriteSlot(val slot: Int, val packageName: String) : HomeDropAction()
    data class ReorderGrid(val fromKey: String, val toKey: String) : HomeDropAction()
    data object None : HomeDropAction()
}

fun resolveHomeDropAction(
    draggedItem: HomeGridItem,
    result: DropResult,
): HomeDropAction {
    val target = result.target
    return when {
        target is DropTarget.Dock && draggedItem is HomeGridItem.AppEntry ->
            HomeDropAction.AddToFavorites(draggedItem.appInfo.packageName)
        target is DropTarget.OnFavoriteSlot && draggedItem is HomeGridItem.AppEntry ->
            HomeDropAction.MergeIntoFavoriteSlot(target.slot, draggedItem.appInfo.packageName)
        target is DropTarget.OnItem ->
            HomeDropAction.ReorderGrid(draggedItem.key, target.key)
        else -> HomeDropAction.None
    }
}

/**
 * Hit-test for home-grid drag. Favorites slots win over the dock strip; dock wins over grid items.
 */
fun findHomeDropTargetAt(
    position: Offset,
    favoriteSlotBounds: Map<Int, Rect>,
    dockBounds: Rect,
    itemBounds: Map<String, Rect>,
    draggedKey: String?,
): DropTarget {
    for ((slot, bounds) in favoriteSlotBounds) {
        if (bounds.contains(position)) return DropTarget.OnFavoriteSlot(slot)
    }
    if (dockBounds.contains(position)) return DropTarget.Dock
    if (draggedKey == null) return DropTarget.None
    for ((key, bounds) in itemBounds) {
        if (key != draggedKey && bounds.contains(position)) return DropTarget.OnItem(key)
    }
    return DropTarget.None
}

fun favoritesStripHighlighted(hoverTarget: DropTarget): Boolean =
    hoverTarget is DropTarget.Dock || hoverTarget is DropTarget.OnFavoriteSlot

fun gridHoverKey(hoverTarget: DropTarget): String? =
    (hoverTarget as? DropTarget.OnItem)?.key

fun applyGridReorder(
    keys: List<String>,
    fromKey: String,
    toKey: String,
): List<String>? {
    val fromIdx = keys.indexOf(fromKey)
    val toIdx = keys.indexOf(toKey)
    if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return null
    val mutable = keys.toMutableList()
    val moved = mutable.removeAt(fromIdx)
    mutable.add(toIdx, moved)
    return mutable
}

suspend fun applyHomeDropAction(
    action: HomeDropAction,
    favoritePackages: Set<String>,
    gridKeys: List<String>,
    addToFavorites: suspend (String) -> Unit,
    mergeIntoFavorite: suspend (slot: Int, packageName: String) -> Unit,
    reorderGrid: suspend (reorderedKeys: List<String>) -> Unit,
) {
    when (action) {
        is HomeDropAction.AddToFavorites -> {
            if (action.packageName !in favoritePackages) addToFavorites(action.packageName)
        }
        is HomeDropAction.MergeIntoFavoriteSlot ->
            mergeIntoFavorite(action.slot, action.packageName)
        is HomeDropAction.ReorderGrid -> {
            val reordered = applyGridReorder(gridKeys, action.fromKey, action.toKey) ?: return
            reorderGrid(reordered)
        }
        HomeDropAction.None -> Unit
    }
}

fun shouldComputeSuggestedApps(unlockReason: String, allAppsEmpty: Boolean): Boolean =
    unlockReason.isNotBlank() && !allAppsEmpty
