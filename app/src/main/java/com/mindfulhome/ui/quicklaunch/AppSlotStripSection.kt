package com.mindfulhome.ui.quicklaunch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.data.flattenPackages
import com.mindfulhome.data.placementsByPackage
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.common.AddAppsDialog
import com.mindfulhome.ui.icons.MaterialSymbolPickerDialog
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.launch

enum class AppSlotStripKind {
    QuickLaunch,
    Favorites,
}

/**
 * Shared strip UI for QuickLaunch and Favorites: same [QuickLaunchWrappedRow], folder dialog, rename, and add flow.
 */
@Composable
fun AppSlotStripSection(
    repository: AppRepository,
    kind: AppSlotStripKind,
    onLaunchApp: (packageName: String, allowedPackages: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    onAppSlotBounds: (uiIndex: Int, topLeft: Offset, size: Size) -> Unit = { _, _, _ -> },
    maxRows: Int? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val rawSlots by when (kind) {
        AppSlotStripKind.QuickLaunch -> repository.quickLaunchSlots().collectAsState(initial = emptyList())
        AppSlotStripKind.Favorites -> repository.favoritesSlots().collectAsState(initial = emptyList())
    }
    val stripPackages = remember(rawSlots) { rawSlots.flatMap { it.flattenPackages() }.toSet() }
    val placementByPackage = remember(rawSlots) { placementsByPackage(rawSlots) }

    var installedApps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    /** When non-null, [onAdd] merges into this strip slot index (folder add). */
    var addDialogFolderSlotIndex by remember { mutableStateOf<Int?>(null) }
    var folderToShow by remember { mutableStateOf<QuickLaunchFolderOpen?>(null) }
    var folderRenameAnchorPackage by remember { mutableStateOf<String?>(null) }
    var folderRenameText by remember { mutableStateOf("") }
    var folderSymbolAnchorPackage by remember { mutableStateOf<String?>(null) }
    var folderSymbolInitial by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        installedApps = PackageManagerHelper.getInstalledApps(context)
    }

    LaunchedEffect(rawSlots, installedApps, kind) {
        val installed = installedApps.map { it.packageName }.toSet()
        if (installed.isEmpty()) return@LaunchedEffect
        val missing = rawSlots.flatMap { it.flattenPackages() }.filter { it !in installed }
        when (kind) {
            AppSlotStripKind.QuickLaunch -> missing.forEach { pkg -> repository.removeFromQuickLaunch(pkg) }
            AppSlotStripKind.Favorites -> missing.forEach { pkg -> repository.removeFromFavorites(pkg) }
        }
    }

    LaunchedEffect(rawSlots, installedApps, folderToShow?.slotIndex) {
        val open = folderToShow ?: return@LaunchedEffect
        val idx = open.slotIndex
        if (idx !in rawSlots.indices) {
            folderToShow = null
            return@LaunchedEffect
        }
        when (val slot = rawSlots[idx]) {
            is QuickLaunchSlot.Single -> {
                folderToShow = null
            }
            is QuickLaunchSlot.Folder -> {
                val map = installedApps.associateBy { it.packageName }
                val apps = slot.apps.mapNotNull { map[it] }
                folderToShow = when {
                    apps.isEmpty() -> null
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

    val slotUiRows = remember(rawSlots, installedApps) {
        val map = installedApps.associateBy { it.packageName }
        rawSlots.mapNotNull { slot ->
            when (slot) {
                is QuickLaunchSlot.Single -> {
                    val app = map[slot.packageName] ?: return@mapNotNull null
                    QuickLaunchSlotUi(apps = listOf(app), folderName = null)
                }
                is QuickLaunchSlot.Folder -> {
                    val apps = slot.apps.mapNotNull { map[it] }
                    if (apps.isEmpty()) return@mapNotNull null
                    QuickLaunchSlotUi(
                        apps = apps,
                        folderName = slot.name?.takeIf { it.isNotBlank() }?.takeIf { apps.size > 1 },
                        folderSymbolIconName = slot.symbolIconName?.takeIf { apps.size > 1 },
                    )
                }
            }
        }
    }

    val stripTitle = when (kind) {
        AppSlotStripKind.QuickLaunch -> "QuickLaunch"
        AppSlotStripKind.Favorites -> "Favorites"
    }
    val addDialogTitle = when (kind) {
        AppSlotStripKind.QuickLaunch -> "Add to QuickLaunch"
        AppSlotStripKind.Favorites -> "Add to Favorites"
    }
    val addTileCd = when (kind) {
        AppSlotStripKind.QuickLaunch -> "Add QuickLaunch app"
        AppSlotStripKind.Favorites -> "Add Favorites app"
    }
    val folderHintRemove = when (kind) {
        AppSlotStripKind.QuickLaunch -> "Drop on → exit folder, or ✕ to remove from QuickLaunch"
        AppSlotStripKind.Favorites -> "Drop on → exit folder, or ✕ to remove from Favorites"
    }
    val folderRemoveCd = when (kind) {
        AppSlotStripKind.QuickLaunch -> "Drop to remove from QuickLaunch"
        AppSlotStripKind.Favorites -> "Drop to remove from Favorites"
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stripTitle,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        QuickLaunchWrappedRow(
            slots = slotUiRows,
            quickLaunchPackages = stripPackages,
            onQuickLaunchApp = onLaunchApp,
            onAddQuickLaunch = {
                addDialogFolderSlotIndex = null
                showAddDialog = true
            },
            onMoveSlot = { from, to ->
                scope.launch {
                    when (kind) {
                        AppSlotStripKind.QuickLaunch -> repository.moveQuickLaunchSlot(from, to)
                        AppSlotStripKind.Favorites -> repository.moveFavoritesSlot(from, to)
                    }
                }
            },
            onMergeSlotInto = { from, into ->
                scope.launch {
                    when (kind) {
                        AppSlotStripKind.QuickLaunch -> repository.mergeQuickLaunchSlots(from, into)
                        AppSlotStripKind.Favorites -> repository.mergeFavoritesSlots(from, into)
                    }
                }
            },
            onRemoveSlot = { apps ->
                scope.launch {
                    when (kind) {
                        AppSlotStripKind.QuickLaunch -> apps.forEach { repository.removeFromQuickLaunch(it.packageName) }
                        AppSlotStripKind.Favorites -> apps.forEach { repository.removeFromFavorites(it.packageName) }
                    }
                }
            },
            onOpenFolder = { slotIndex, apps, folderName, folderSymbolIconName ->
                folderToShow = QuickLaunchFolderOpen(slotIndex, apps, folderName, folderSymbolIconName)
            },
            addTileContentDescription = addTileCd,
            onAppSlotBounds = onAppSlotBounds,
            maxRows = maxRows,
        )
    }

    if (showAddDialog) {
        val addTitle = when {
            addDialogFolderSlotIndex != null -> when (kind) {
                AppSlotStripKind.QuickLaunch -> "Add to QuickLaunch folder"
                AppSlotStripKind.Favorites -> "Add to Favorites folder"
            }
            else -> addDialogTitle
        }
        AddAppsDialog(
            title = addTitle,
            apps = installedApps,
            placementByPackage = placementByPackage,
            onAdd = { packageName ->
                scope.launch {
                    val folderIdx = addDialogFolderSlotIndex
                    when {
                        folderIdx != null -> {
                            when (kind) {
                                AppSlotStripKind.QuickLaunch ->
                                    repository.mergePackageIntoQuickLaunchAt(folderIdx, packageName)
                                AppSlotStripKind.Favorites ->
                                    repository.mergePackageIntoFavoritesAt(folderIdx, packageName)
                            }
                        }
                        else -> {
                            when (kind) {
                                AppSlotStripKind.QuickLaunch -> repository.addToQuickLaunch(packageName)
                                AppSlotStripKind.Favorites -> repository.addToFavorites(packageName)
                            }
                        }
                    }
                }
            },
            onDismiss = {
                showAddDialog = false
                addDialogFolderSlotIndex = null
            },
        )
    }

    folderToShow?.let { folder ->
        AppFolderDetailDialog(
            folder = folder,
            onDismiss = { folderToShow = null },
            titleForFolder = { f ->
                f.folderName?.takeIf { it.isNotBlank() } ?: "Folder (${f.apps.size})"
            },
            showRenameIcon = true,
            onRenameIconClick = {
                folder.apps.firstOrNull()?.packageName?.let { anchor ->
                    folderRenameAnchorPackage = anchor
                    folderRenameText = folder.folderName.orEmpty()
                }
            },
            onSymbolIconClick = {
                folder.apps.firstOrNull()?.packageName?.let { anchor ->
                    folderSymbolAnchorPackage = anchor
                    folderSymbolInitial = folder.folderSymbolIconName
                }
            },
            onLaunchApp = { app ->
                folderToShow = null
                onLaunchApp(app.packageName, stripPackages)
            },
            onDragRemove = { app ->
                scope.launch {
                    when (kind) {
                        AppSlotStripKind.QuickLaunch -> repository.removeFromQuickLaunch(app.packageName)
                        AppSlotStripKind.Favorites -> repository.removeFromFavorites(app.packageName)
                    }
                }
                folderToShow = folderToShow?.let { f ->
                    val next = f.apps.filter { it.packageName != app.packageName }
                    when {
                        next.isEmpty() -> null
                        next.size <= 1 -> null
                        else -> f.copy(apps = next)
                    }
                }
            },
            onDragExtractToOwnSlot = { app ->
                scope.launch {
                    when (kind) {
                        AppSlotStripKind.QuickLaunch -> repository.extractQuickLaunchAppToOwnSlot(app.packageName)
                        AppSlotStripKind.Favorites -> repository.extractFavoritesAppToOwnSlot(app.packageName)
                    }
                }
                folderToShow = folderToShow?.let { f ->
                    val next = f.apps.filter { it.packageName != app.packageName }
                    when {
                        next.isEmpty() -> null
                        next.size <= 1 -> null
                        else -> f.copy(apps = next)
                    }
                }
            },
            dragHintText = folderHintRemove,
            removeDropContentDescription = folderRemoveCd,
            onAddAppsClick = {
                addDialogFolderSlotIndex = folder.slotIndex
                showAddDialog = true
            },
            addAppsContentDescription = when (kind) {
                AppSlotStripKind.QuickLaunch -> "Add app to QuickLaunch folder"
                AppSlotStripKind.Favorites -> "Add app to Favorites folder"
            },
        )
    }

    folderSymbolAnchorPackage?.let { anchorPkg ->
        MaterialSymbolPickerDialog(
            initialSelection = folderSymbolInitial,
            onDismiss = {
                folderSymbolAnchorPackage = null
                folderSymbolInitial = null
            },
            onConfirm = { symbol ->
                scope.launch {
                    when (kind) {
                        AppSlotStripKind.QuickLaunch -> repository.setQuickLaunchFolderSymbolIcon(anchorPkg, symbol)
                        AppSlotStripKind.Favorites -> repository.setFavoritesFolderSymbolIcon(anchorPkg, symbol)
                    }
                }
                folderSymbolAnchorPackage = null
                folderSymbolInitial = null
            },
        )
    }

    folderRenameAnchorPackage?.let { anchorPkg ->
        AlertDialog(
            onDismissRequest = {
                focusManager.clearFocus(true)
                folderRenameAnchorPackage = null
                folderRenameText = ""
            },
            title = { Text("Rename folder") },
            text = {
                OutlinedTextField(
                    value = folderRenameText,
                    onValueChange = { folderRenameText = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        focusManager.clearFocus(true)
                        scope.launch {
                            val name = folderRenameText.takeIf { it.isNotBlank() }
                            when (kind) {
                                AppSlotStripKind.QuickLaunch -> repository.setQuickLaunchFolderName(anchorPkg, name)
                                AppSlotStripKind.Favorites -> repository.setFavoritesFolderName(anchorPkg, name)
                            }
                            folderRenameAnchorPackage = null
                            folderRenameText = ""
                            folderToShow = null
                        }
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        focusManager.clearFocus(true)
                        folderRenameAnchorPackage = null
                        folderRenameText = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}
