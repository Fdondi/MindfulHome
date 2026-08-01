package com.mindfulhome.ui.quicklaunch

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.AppSlotPlacement
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.data.placementsByPackage
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.common.AddAppsDialog
import com.mindfulhome.ui.icons.MaterialSymbolPickerDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dialogs owned by [AppSlotStripSection]: add apps, folder detail, symbol picker, rename.
 */
@Composable
internal fun AppSlotStripDialogs(
    repository: AppRepository,
    kind: AppSlotStripKind,
    copy: AppSlotStripCopy,
    scope: CoroutineScope,
    installedApps: List<AppInfo>,
    rawSlots: List<QuickLaunchSlot>,
    stripPackages: Set<String>,
    showAddDialog: Boolean,
    addDialogFolderSlotIndex: Int?,
    onShowAddDialogChange: (Boolean) -> Unit,
    onAddDialogFolderSlotIndexChange: (Int?) -> Unit,
    folderToShow: QuickLaunchFolderOpen?,
    onFolderToShowChange: (QuickLaunchFolderOpen?) -> Unit,
    folderRenameAnchorPackage: String?,
    folderRenameText: String,
    onFolderRenameAnchorPackageChange: (String?) -> Unit,
    onFolderRenameTextChange: (String) -> Unit,
    folderSymbolAnchorPackage: String?,
    folderSymbolInitial: String?,
    onFolderSymbolAnchorPackageChange: (String?) -> Unit,
    onFolderSymbolInitialChange: (String?) -> Unit,
    onLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val placementByPackage = placementsByPackage(rawSlots)

    if (showAddDialog) {
        AppSlotStripAddDialog(
            copy = copy,
            kind = kind,
            repository = repository,
            scope = scope,
            installedApps = installedApps,
            placementByPackage = placementByPackage,
            addDialogFolderSlotIndex = addDialogFolderSlotIndex,
            onDismiss = {
                onShowAddDialogChange(false)
                onAddDialogFolderSlotIndexChange(null)
            },
        )
    }

    folderToShow?.let { folder ->
        AppSlotStripFolderDialog(
            folder = folder,
            copy = copy,
            kind = kind,
            repository = repository,
            scope = scope,
            stripPackages = stripPackages,
            onFolderToShowChange = onFolderToShowChange,
            onRequestRename = { anchor, name ->
                onFolderRenameAnchorPackageChange(anchor)
                onFolderRenameTextChange(name)
            },
            onRequestSymbol = { anchor, initial ->
                onFolderSymbolAnchorPackageChange(anchor)
                onFolderSymbolInitialChange(initial)
            },
            onLaunchApp = onLaunchApp,
            onAddAppsClick = {
                onAddDialogFolderSlotIndexChange(folder.slotIndex)
                onShowAddDialogChange(true)
            },
        )
    }

    AppSlotStripSymbolDialog(
        anchorPackage = folderSymbolAnchorPackage,
        initial = folderSymbolInitial,
        kind = kind,
        repository = repository,
        scope = scope,
        onClear = {
            onFolderSymbolAnchorPackageChange(null)
            onFolderSymbolInitialChange(null)
        },
    )

    AppSlotStripRenameDialog(
        anchorPackage = folderRenameAnchorPackage,
        renameText = folderRenameText,
        onRenameTextChange = onFolderRenameTextChange,
        kind = kind,
        repository = repository,
        scope = scope,
        focusManagerClear = { focusManager.clearFocus(true) },
        onClearAnchor = {
            onFolderRenameAnchorPackageChange(null)
            onFolderRenameTextChange("")
        },
        onSaved = {
            onFolderRenameAnchorPackageChange(null)
            onFolderRenameTextChange("")
            onFolderToShowChange(null)
        },
    )
}

@Composable
private fun AppSlotStripSymbolDialog(
    anchorPackage: String?,
    initial: String?,
    kind: AppSlotStripKind,
    repository: AppRepository,
    scope: CoroutineScope,
    onClear: () -> Unit,
) {
    val anchorPkg = anchorPackage ?: return
    MaterialSymbolPickerDialog(
        initialSelection = initial,
        onDismiss = onClear,
        onConfirm = { symbol ->
            scope.launch { repository.setStripFolderSymbol(kind, anchorPkg, symbol) }
            onClear()
        },
    )
}

@Composable
private fun AppSlotStripRenameDialog(
    anchorPackage: String?,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    kind: AppSlotStripKind,
    repository: AppRepository,
    scope: CoroutineScope,
    focusManagerClear: () -> Unit,
    onClearAnchor: () -> Unit,
    onSaved: () -> Unit,
) {
    val anchorPkg = anchorPackage ?: return
    AlertDialog(
        onDismissRequest = {
            focusManagerClear()
            onClearAnchor()
        },
        title = { Text("Rename folder") },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = onRenameTextChange,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    focusManagerClear()
                    scope.launch {
                        val name = renameText.takeIf { it.isNotBlank() }
                        repository.setStripFolderName(kind, anchorPkg, name)
                        onSaved()
                    }
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    focusManagerClear()
                    onClearAnchor()
                },
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AppSlotStripAddDialog(
    copy: AppSlotStripCopy,
    kind: AppSlotStripKind,
    repository: AppRepository,
    scope: CoroutineScope,
    installedApps: List<AppInfo>,
    placementByPackage: Map<String, List<AppSlotPlacement>>,
    addDialogFolderSlotIndex: Int?,
    onDismiss: () -> Unit,
) {
    val addTitle = if (addDialogFolderSlotIndex != null) copy.addToFolderTitle else copy.addDialogTitle
    AddAppsDialog(
        title = addTitle,
        apps = installedApps,
        placementByPackage = placementByPackage,
        onAdd = { packageName ->
            scope.launch {
                addPackageToStrip(repository, kind, addDialogFolderSlotIndex, packageName)
            }
        },
        onDismiss = onDismiss,
    )
}

internal suspend fun addPackageToStrip(
    repository: AppRepository,
    kind: AppSlotStripKind,
    folderIdx: Int?,
    packageName: String,
) {
    if (folderIdx != null) {
        repository.mergePackageIntoStripAt(kind, folderIdx, packageName)
    } else {
        repository.addToStrip(kind, packageName)
    }
}

@Composable
private fun AppSlotStripFolderDialog(
    folder: QuickLaunchFolderOpen,
    copy: AppSlotStripCopy,
    kind: AppSlotStripKind,
    repository: AppRepository,
    scope: CoroutineScope,
    stripPackages: Set<String>,
    onFolderToShowChange: (QuickLaunchFolderOpen?) -> Unit,
    onRequestRename: (anchorPackage: String, currentName: String) -> Unit,
    onRequestSymbol: (anchorPackage: String, initial: String?) -> Unit,
    onLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    onAddAppsClick: () -> Unit,
) {
    AppFolderDetailDialog(
        folder = folder,
        onDismiss = { onFolderToShowChange(null) },
        titleForFolder = { f ->
            f.folderName?.takeIf { it.isNotBlank() } ?: "Folder (${f.apps.size})"
        },
        showRenameIcon = true,
        onRenameIconClick = {
            folder.apps.firstOrNull()?.packageName?.let { anchor ->
                onRequestRename(anchor, folder.folderName.orEmpty())
            }
        },
        onSymbolIconClick = {
            folder.apps.firstOrNull()?.packageName?.let { anchor ->
                onRequestSymbol(anchor, folder.folderSymbolIconName)
            }
        },
        onLaunchApp = { app ->
            onFolderToShowChange(null)
            onLaunchApp(app.packageName, stripPackages, null)
        },
        onDragRemove = { app ->
            scope.launch {
                repository.removeFromStripAt(kind, folder.slotIndex, app.packageName)
            }
            onFolderToShowChange(nextFolderAfterAppRemoved(folder, app.packageName))
        },
        onDragExtractToOwnSlot = { app ->
            scope.launch {
                repository.extractStripAppToOwnSlot(kind, app.packageName)
            }
            onFolderToShowChange(nextFolderAfterAppRemoved(folder, app.packageName))
        },
        dragHintText = copy.folderHintRemove,
        removeDropContentDescription = copy.folderRemoveContentDescription,
        onAddAppsClick = onAddAppsClick,
        addAppsContentDescription = copy.addAppsContentDescription,
    )
}
