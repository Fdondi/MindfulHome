package com.mindfulhome.ui.quicklaunch

import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.model.AppInfo
import kotlinx.coroutines.flow.Flow

/** Packages present in [rawSlots] but missing from [installed]. */
fun missingStripPackages(
    rawSlots: List<QuickLaunchSlot>,
    installed: Set<String>,
): List<String> =
    rawSlots.flatMap { it.flattenPackages() }.filter { it !in installed }

suspend fun AppRepository.removeFromStrip(kind: AppSlotStripKind, packageName: String) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> removeFromQuickLaunch(packageName)
        AppSlotStripKind.Favorites -> removeFromFavorites(packageName)
    }
}

suspend fun AppRepository.moveStripSlot(kind: AppSlotStripKind, from: Int, to: Int) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> moveQuickLaunchSlot(from, to)
        AppSlotStripKind.Favorites -> moveFavoritesSlot(from, to)
    }
}

suspend fun AppRepository.mergeStripSlots(kind: AppSlotStripKind, from: Int, into: Int) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> mergeQuickLaunchSlots(from, into)
        AppSlotStripKind.Favorites -> mergeFavoritesSlots(from, into)
    }
}

suspend fun AppRepository.removeStripSlotAt(kind: AppSlotStripKind, slotIndex: Int) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> removeQuickLaunchSlotAt(slotIndex)
        AppSlotStripKind.Favorites -> removeFavoritesSlotAt(slotIndex)
    }
}

suspend fun AppRepository.addToStrip(kind: AppSlotStripKind, packageName: String) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> addToQuickLaunch(packageName)
        AppSlotStripKind.Favorites -> addToFavorites(packageName)
    }
}

suspend fun AppRepository.mergePackageIntoStripAt(
    kind: AppSlotStripKind,
    folderIdx: Int,
    packageName: String,
) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> mergePackageIntoQuickLaunchAt(folderIdx, packageName)
        AppSlotStripKind.Favorites -> mergePackageIntoFavoritesAt(folderIdx, packageName)
    }
}

suspend fun AppRepository.setStripFolderName(
    kind: AppSlotStripKind,
    anchorPackage: String,
    name: String?,
) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> setQuickLaunchFolderName(anchorPackage, name)
        AppSlotStripKind.Favorites -> setFavoritesFolderName(anchorPackage, name)
    }
}

suspend fun AppRepository.setStripFolderSymbol(
    kind: AppSlotStripKind,
    anchorPackage: String,
    symbol: String?,
) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> setQuickLaunchFolderSymbolIcon(anchorPackage, symbol)
        AppSlotStripKind.Favorites -> setFavoritesFolderSymbolIcon(anchorPackage, symbol)
    }
}

suspend fun AppRepository.removeFromStripAt(
    kind: AppSlotStripKind,
    slotIndex: Int,
    packageName: String,
) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> removeLaunchKeyFromQuickLaunchAt(slotIndex, packageName)
        AppSlotStripKind.Favorites -> removePackageFromFavoritesAt(slotIndex, packageName)
    }
}

suspend fun AppRepository.extractStripAppToOwnSlot(kind: AppSlotStripKind, packageName: String) {
    when (kind) {
        AppSlotStripKind.QuickLaunch -> extractQuickLaunchAppToOwnSlot(packageName)
        AppSlotStripKind.Favorites -> extractFavoritesAppToOwnSlot(packageName)
    }
}

fun AppRepository.stripSlotsFlow(kind: AppSlotStripKind): Flow<List<QuickLaunchSlot>> = when (kind) {
    AppSlotStripKind.QuickLaunch -> quickLaunchSlots()
    AppSlotStripKind.Favorites -> favoritesSlots()
}

data class AppSlotStripCopy(
    val stripTitle: String,
    val addDialogTitle: String,
    val addTileContentDescription: String,
    val folderHintRemove: String,
    val folderRemoveContentDescription: String,
    val addToFolderTitle: String,
    val addAppsContentDescription: String,
)

fun stripCopy(kind: AppSlotStripKind): AppSlotStripCopy = when (kind) {
    AppSlotStripKind.QuickLaunch -> AppSlotStripCopy(
        stripTitle = "QuickLaunch",
        addDialogTitle = "Add to QuickLaunch",
        addTileContentDescription = "Add QuickLaunch app",
        folderHintRemove = "Drop on → exit folder, or ✕ to remove from QuickLaunch",
        folderRemoveContentDescription = "Drop to remove from QuickLaunch",
        addToFolderTitle = "Add to QuickLaunch folder",
        addAppsContentDescription = "Add app to QuickLaunch folder",
    )
    AppSlotStripKind.Favorites -> AppSlotStripCopy(
        stripTitle = "Favorites",
        addDialogTitle = "Add to Favorites",
        addTileContentDescription = "Add Favorites app",
        folderHintRemove = "Drop on → exit folder, or ✕ to remove from Favorites",
        folderRemoveContentDescription = "Drop to remove from Favorites",
        addToFolderTitle = "Add to Favorites folder",
        addAppsContentDescription = "Add app to Favorites folder",
    )
}

fun mapSlotsToUi(
    rawSlots: List<QuickLaunchSlot>,
    installedByPkg: Map<String, AppInfo>,
): List<QuickLaunchSlotUi> =
    rawSlots.mapNotNull { slot ->
        when (slot) {
            is QuickLaunchSlot.Single -> {
                val app = installedByPkg[slot.packageName] ?: return@mapNotNull null
                QuickLaunchSlotUi(apps = listOf(app), folderName = null)
            }
            is QuickLaunchSlot.Folder -> {
                val apps = slot.apps.mapNotNull { installedByPkg[it.packageName] }
                if (apps.isEmpty()) return@mapNotNull null
                QuickLaunchSlotUi(
                    apps = apps,
                    folderName = slot.name?.takeIf { it.isNotBlank() }?.takeIf { apps.size > 1 },
                    folderSymbolIconName = slot.symbolIconName?.takeIf { apps.size > 1 },
                )
            }
        }
    }

/**
 * Reconciles an open folder dialog with the current strip slots and installed apps.
 * Returns null when the folder should close (slot gone, collapsed to single, or empty).
 */
fun reconcileOpenFolder(
    open: QuickLaunchFolderOpen,
    rawSlots: List<QuickLaunchSlot>,
    installed: Map<String, AppInfo>,
): QuickLaunchFolderOpen? {
    val idx = open.slotIndex
    if (idx !in rawSlots.indices) return null
    return when (val slot = rawSlots[idx]) {
        is QuickLaunchSlot.Single -> null
        is QuickLaunchSlot.Folder -> {
            val apps = slot.apps.mapNotNull { installed[it.packageName] }
            when {
                apps.size <= 1 -> null
                else -> QuickLaunchFolderOpen(
                    idx,
                    apps,
                    slot.name,
                    slot.symbolIconName,
                )
            }
        }
    }
}

/** Next open-folder state after removing [pkg] from the folder UI (null closes the dialog). */
fun nextFolderAfterAppRemoved(
    folder: QuickLaunchFolderOpen,
    pkg: String,
): QuickLaunchFolderOpen? {
    val next = folder.apps.filter { it.packageName != pkg }
    return when {
        next.size <= 1 -> null
        else -> folder.copy(apps = next)
    }
}
