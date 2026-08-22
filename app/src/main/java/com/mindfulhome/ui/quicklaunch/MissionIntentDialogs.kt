package com.mindfulhome.ui.quicklaunch
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.AppSlotPlacement
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.locale.IntentFolderNames
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.common.AddAppsDialog
import com.mindfulhome.ui.common.AddFolderAppDialog
import com.mindfulhome.ui.icons.MaterialSymbolPickerDialog
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dialogs owned by [MissionIntentSection]: new intent, add app, folder, rename, symbol.
 */
@Composable
internal fun MissionIntentDialogs(
    repository: AppRepository,
    scope: CoroutineScope,
    installedApps: List<AppInfo>,
    rawSlots: List<QuickLaunchSlot>,
    stripPackages: Set<String>,
    placementByPackage: Map<String, List<AppSlotPlacement>>,
    showNewIntentDialog: Boolean,
    newIntentName: String,
    onShowNewIntentDialogChange: (Boolean) -> Unit,
    onNewIntentNameChange: (String) -> Unit,
    showAddDialog: Boolean,
    addDialogFolderSlotIndex: Int?,
    onShowAddDialogChange: (Boolean) -> Unit,
    onAddDialogFolderSlotIndexChange: (Int?) -> Unit,
    pendingFolderApp: AppInfo?,
    onPendingFolderAppChange: (AppInfo?) -> Unit,
    editingFolderApp: AppInfo?,
    onEditingFolderAppChange: (AppInfo?) -> Unit,
    folderToShow: QuickLaunchFolderOpen?,
    onFolderToShowChange: (QuickLaunchFolderOpen?) -> Unit,
    folderRenameSlotIndex: Int?,
    folderRenameText: String,
    onFolderRenameSlotIndexChange: (Int?) -> Unit,
    onFolderRenameTextChange: (String) -> Unit,
    folderSymbolSlotIndex: Int?,
    folderSymbolInitial: String?,
    onFolderSymbolSlotIndexChange: (Int?) -> Unit,
    onFolderSymbolInitialChange: (String?) -> Unit,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
) {
    MissionIntentCreateDialogs(
        repository = repository,
        scope = scope,
        installedApps = installedApps,
        placementByPackage = placementByPackage,
        showNewIntentDialog = showNewIntentDialog,
        newIntentName = newIntentName,
        onShowNewIntentDialogChange = onShowNewIntentDialogChange,
        onNewIntentNameChange = onNewIntentNameChange,
        showAddDialog = showAddDialog,
        addDialogFolderSlotIndex = addDialogFolderSlotIndex,
        onShowAddDialogChange = onShowAddDialogChange,
        onAddDialogFolderSlotIndexChange = onAddDialogFolderSlotIndexChange,
        pendingFolderApp = pendingFolderApp,
        onPendingFolderAppChange = onPendingFolderAppChange,
    )
    MissionIntentFolderDialogs(
        repository = repository,
        scope = scope,
        rawSlots = rawSlots,
        stripPackages = stripPackages,
        editingFolderApp = editingFolderApp,
        onEditingFolderAppChange = onEditingFolderAppChange,
        folderToShow = folderToShow,
        onFolderToShowChange = onFolderToShowChange,
        folderRenameSlotIndex = folderRenameSlotIndex,
        folderRenameText = folderRenameText,
        onFolderRenameSlotIndexChange = onFolderRenameSlotIndexChange,
        onFolderRenameTextChange = onFolderRenameTextChange,
        folderSymbolSlotIndex = folderSymbolSlotIndex,
        folderSymbolInitial = folderSymbolInitial,
        onFolderSymbolSlotIndexChange = onFolderSymbolSlotIndexChange,
        onFolderSymbolInitialChange = onFolderSymbolInitialChange,
        onAddAppsClick = {
            onAddDialogFolderSlotIndexChange(it)
            onShowAddDialogChange(true)
        },
        onQuickLaunchApp = onQuickLaunchApp,
    )
}

@Composable
private fun MissionIntentCreateDialogs(
    repository: AppRepository,
    scope: CoroutineScope,
    installedApps: List<AppInfo>,
    placementByPackage: Map<String, List<AppSlotPlacement>>,
    showNewIntentDialog: Boolean,
    newIntentName: String,
    onShowNewIntentDialogChange: (Boolean) -> Unit,
    onNewIntentNameChange: (String) -> Unit,
    showAddDialog: Boolean,
    addDialogFolderSlotIndex: Int?,
    onShowAddDialogChange: (Boolean) -> Unit,
    onAddDialogFolderSlotIndexChange: (Int?) -> Unit,
    pendingFolderApp: AppInfo?,
    onPendingFolderAppChange: (AppInfo?) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    MissionNewIntentDialog(
        visible = showNewIntentDialog,
        name = newIntentName,
        onNameChange = onNewIntentNameChange,
        onDismiss = {
            focusManager.clearFocus(true)
            onShowNewIntentDialogChange(false)
            onNewIntentNameChange("")
        },
        onConfirm = {
            focusManager.clearFocus(true)
            val name = trimmedNonEmptyName(newIntentName) ?: return@MissionNewIntentDialog
            scope.launch {
                repository.addIntentFolder(name)
                onShowNewIntentDialogChange(false)
                onNewIntentNameChange("")
            }
        },
    )
    if (showAddDialog) {
        val context = LocalContext.current
        AddAppsDialog(
            title = stringResource(R.string.add_app_to_intent_folder),
            apps = installedApps,
            placementByPackage = placementByPackage,
            onAdd = { packageName ->
                installedApps.firstOrNull { it.packageName == packageName }?.let { app ->
                    onShowAddDialogChange(false)
                    onPendingFolderAppChange(app)
                }
            },
            onDismiss = {
                onShowAddDialogChange(false)
                onAddDialogFolderSlotIndexChange(null)
            },
            onRefresh = {
                PackageManagerHelper.getInstalledApps(context, forceRefresh = true)
            },
        )
    }
    pendingFolderApp?.let { app ->
        AddFolderAppDialog(
            appInfo = app,
            onConfirm = { limitMinutes ->
                scope.launch {
                    addDialogFolderSlotIndex?.let { folderIdx ->
                        repository.mergePackageIntoQuickLaunchAt(folderIdx, app.packageName, limitMinutes)
                    }
                    onPendingFolderAppChange(null)
                    onAddDialogFolderSlotIndexChange(null)
                }
            },
            onDismiss = {
                onPendingFolderAppChange(null)
                onAddDialogFolderSlotIndexChange(null)
            },
        )
    }
}

@Composable
private fun MissionIntentFolderDialogs(
    repository: AppRepository,
    scope: CoroutineScope,
    rawSlots: List<QuickLaunchSlot>,
    stripPackages: Set<String>,
    editingFolderApp: AppInfo?,
    onEditingFolderAppChange: (AppInfo?) -> Unit,
    folderToShow: QuickLaunchFolderOpen?,
    onFolderToShowChange: (QuickLaunchFolderOpen?) -> Unit,
    folderRenameSlotIndex: Int?,
    folderRenameText: String,
    onFolderRenameSlotIndexChange: (Int?) -> Unit,
    onFolderRenameTextChange: (String) -> Unit,
    folderSymbolSlotIndex: Int?,
    folderSymbolInitial: String?,
    onFolderSymbolSlotIndexChange: (Int?) -> Unit,
    onFolderSymbolInitialChange: (String?) -> Unit,
    onAddAppsClick: (slotIndex: Int) -> Unit,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val resources = LocalContext.current.resources
    MissionIntentEditingAppDialog(
        repository = repository,
        scope = scope,
        rawSlots = rawSlots,
        editingFolderApp = editingFolderApp,
        folderToShow = folderToShow,
        onEditingFolderAppChange = onEditingFolderAppChange,
    )
    folderToShow?.let { folder ->
        AppFolderDetailDialog(
            folder = folder,
            onDismiss = { onFolderToShowChange(null) },
            titleForFolder = {
                val raw = folderTitleForIntent(it)
                IntentFolderNames.localize(raw, resources) ?: raw
            },
            showRenameIcon = true,
            onRenameIconClick = {
                onFolderRenameSlotIndexChange(folder.slotIndex)
                onFolderRenameTextChange(
                    IntentFolderNames.localize(folder.folderName, resources)
                        ?: folder.folderName.orEmpty(),
                )
            },
            showSymbolIconButton = true,
            onSymbolIconClick = {
                onFolderSymbolSlotIndexChange(folder.slotIndex)
                onFolderSymbolInitialChange(folder.folderSymbolIconName)
            },
            onLaunchApp = { app ->
                onFolderToShowChange(null)
                val slot = rawSlots.getOrNull(folder.slotIndex) as? QuickLaunchSlot.Folder
                onQuickLaunchApp(app.packageName, stripPackages, slot?.limitMinutesFor(app.packageName))
            },
            onDragRemove = { app ->
                scope.launch {
                    repository.removeLaunchKeyFromQuickLaunchAt(folder.slotIndex, app.packageName)
                }
                onFolderToShowChange(
                    folder.copy(apps = folder.apps.filter { it.packageName != app.packageName }),
                )
            },
            dragHintText = "Drop on timer to edit limit, or ✕ to remove from folder",
            removeDropContentDescription = "Drop to remove from folder",
            onAddAppsClick = { onAddAppsClick(folder.slotIndex) },
            addAppsContentDescription = stringResource(R.string.add_app_to_intent_folder),
            onEditAppLimit = { app -> onEditingFolderAppChange(app) },
        )
    }
    folderSymbolSlotIndex?.let { slotIndex ->
        MaterialSymbolPickerDialog(
            initialSelection = folderSymbolInitial,
            onDismiss = {
                onFolderSymbolSlotIndexChange(null)
                onFolderSymbolInitialChange(null)
            },
            onConfirm = { symbol ->
                scope.launch { repository.setQuickLaunchFolderSymbolIconAt(slotIndex, symbol) }
                onFolderSymbolSlotIndexChange(null)
                onFolderSymbolInitialChange(null)
            },
        )
    }
    folderRenameSlotIndex?.let { slotIndex ->
        AlertDialog(
            onDismissRequest = {
                focusManager.clearFocus(true)
                onFolderRenameSlotIndexChange(null)
                onFolderRenameTextChange("")
            },
            title = { Text(stringResource(R.string.rename_intent)) },
            text = {
                OutlinedTextField(
                    value = folderRenameText,
                    onValueChange = onFolderRenameTextChange,
                    label = { Text(stringResource(R.string.intent_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        focusManager.clearFocus(true)
                        scope.launch {
                            repository.setQuickLaunchFolderNameAt(
                                slotIndex,
                                IntentFolderNames.canonicalize(folderRenameText, resources),
                            )
                            onFolderRenameSlotIndexChange(null)
                            onFolderRenameTextChange("")
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        focusManager.clearFocus(true)
                        onFolderRenameSlotIndexChange(null)
                        onFolderRenameTextChange("")
                    },
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun MissionNewIntentDialog(
    visible: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_intent)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.name_this_folder_after_your_goal_not_the_app_e_g),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.intent_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MissionIntentEditingAppDialog(
    repository: AppRepository,
    scope: CoroutineScope,
    rawSlots: List<QuickLaunchSlot>,
    editingFolderApp: AppInfo?,
    folderToShow: QuickLaunchFolderOpen?,
    onEditingFolderAppChange: (AppInfo?) -> Unit,
) {
    val app = editingFolderApp ?: return
    val folderIdx = folderToShow?.slotIndex
    val slot = folderIdx?.let { rawSlots.getOrNull(it) as? QuickLaunchSlot.Folder }
    AddFolderAppDialog(
        appInfo = app,
        title = stringResource(R.string.app_limit),
        confirmLabel = stringResource(R.string.save),
        initialLimitMinutes = slot?.limitMinutesFor(app.packageName),
        onConfirm = { limitMinutes ->
            scope.launch {
                if (folderIdx != null) {
                    repository.setQuickLaunchAppLimitAt(folderIdx, app.packageName, limitMinutes)
                }
                onEditingFolderAppChange(null)
            }
        },
        onDismiss = { onEditingFolderAppChange(null) },
    )
}
