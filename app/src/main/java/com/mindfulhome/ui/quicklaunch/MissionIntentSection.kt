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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.data.placementsByPackage
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.common.AddAppsDialog
import com.mindfulhome.ui.common.AddFolderAppDialog
import com.mindfulhome.ui.icons.MaterialSymbolPickerDialog
import com.mindfulhome.util.PackageManagerHelper
import com.mindfulhome.util.ShortcutUiHelper
import kotlinx.coroutines.launch

@Composable
fun MissionIntentSection(
    repository: AppRepository,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    onOpenTimerPlain: () -> Unit,
    modifier: Modifier = Modifier,
    resumeSessionLabel: String? = null,
    resumeSessionMinutes: Int = 0,
    onResumeSession: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val rawSlots by repository.quickLaunchSlots().collectAsState(initial = emptyList())
    val stripPackages = remember(rawSlots) { rawSlots.flatMap { it.flattenAllowedPackages() }.toSet() }
    val placementByPackage = remember(rawSlots) { placementsByPackage(rawSlots) }

    var installedApps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogFolderSlotIndex by remember { mutableStateOf<Int?>(null) }
    var pendingFolderApp by remember { mutableStateOf<AppInfo?>(null) }
    var editingFolderApp by remember { mutableStateOf<AppInfo?>(null) }
    var showNewIntentDialog by remember { mutableStateOf(false) }
    var newIntentName by remember { mutableStateOf("") }
    var folderToShow by remember { mutableStateOf<QuickLaunchFolderOpen?>(null) }
    var folderRenameSlotIndex by remember { mutableStateOf<Int?>(null) }
    var folderRenameText by remember { mutableStateOf("") }
    var folderSymbolSlotIndex by remember { mutableStateOf<Int?>(null) }
    var folderSymbolInitial by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        installedApps = PackageManagerHelper.getInstalledApps(context)
        repository.ensureIntentQuickLaunchInitialized(installedApps.map { it.packageName }.toSet())
    }

    LaunchedEffect(rawSlots, installedApps) {
        val installed = installedApps.map { it.packageName }.toSet()
        if (installed.isEmpty()) return@LaunchedEffect
        val missing = rawSlots.flatMap { it.flattenPackages() }.filter { it !in installed }
        missing.forEach { pkg -> repository.removeFromQuickLaunch(pkg) }
    }

    LaunchedEffect(rawSlots, installedApps, folderToShow?.slotIndex) {
        val open = folderToShow ?: return@LaunchedEffect
        val idx = open.slotIndex
        if (idx !in rawSlots.indices) {
            folderToShow = null
            return@LaunchedEffect
        }
        when (val slot = rawSlots[idx]) {
            is QuickLaunchSlot.Single -> folderToShow = null
            is QuickLaunchSlot.Folder -> {
                val map = installedApps.associateBy { it.packageName }
                val apps = slot.apps.mapNotNull { map[it.packageName] } +
                    slot.shortcuts.map { ShortcutUiHelper.pinnedShortcutToAppInfo(context, it, map) }
                folderToShow = QuickLaunchFolderOpen(
                    idx,
                    apps,
                    slot.name,
                    slot.symbolIconName,
                    slot.apps.associate { it.packageName to it.limitMinutes },
                )
            }
        }
    }

    val slotUiRows = remember(rawSlots, installedApps) {
        val map = installedApps.associateBy { it.packageName }
        rawSlots.mapNotNull { slot ->
            when (slot) {
                is QuickLaunchSlot.Single -> null
                is QuickLaunchSlot.Folder -> {
                    val apps = slot.apps.mapNotNull { map[it.packageName] } +
                        slot.shortcuts.map { ShortcutUiHelper.pinnedShortcutToAppInfo(context, it, map) }
                    QuickLaunchSlotUi(
                        apps = apps,
                        folderName = slot.name?.takeIf { it.isNotBlank() } ?: "Unnamed",
                        folderSymbolIconName = slot.symbolIconName,
                        limitMinutesByPackage = slot.limitMinutesByPackage(),
                    )
                }
            }
        }
    }

    val hasEmptyNamedFolder = remember(rawSlots) {
        rawSlots.any { slot ->
            slot is QuickLaunchSlot.Folder &&
                !slot.name.isNullOrBlank() &&
                slot.apps.isEmpty()
        }
    }

    val resumeTile = if (
        resumeSessionLabel != null &&
        onResumeSession != null &&
        resumeSessionMinutes > 0
    ) {
        QuickLaunchAuxTile(
            label = "Resume",
            subtitle = "$resumeSessionLabel (${formatMinutes(resumeSessionMinutes)})",
            onClick = onResumeSession,
            contentDescription = "Resume $resumeSessionLabel",
        )
    } else {
        null
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasEmptyNamedFolder) {
            Text(
                text = "Name folders after what you're trying to do — then add the apps that help.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        QuickLaunchWrappedRow(
            slots = slotUiRows,
            quickLaunchPackages = stripPackages,
            onQuickLaunchApp = onQuickLaunchApp,
            onAddQuickLaunch = {
                addDialogFolderSlotIndex = null
                newIntentName = ""
                showNewIntentDialog = true
            },
            onMoveSlot = { from, to ->
                scope.launch { repository.moveQuickLaunchSlot(from, to) }
            },
            onMergeSlotInto = { from, into ->
                scope.launch { repository.mergeQuickLaunchSlots(from, into) }
            },
            onRemoveSlot = { apps ->
                scope.launch { apps.forEach { repository.removeLaunchKeyFromQuickLaunch(it.packageName) } }
            },
            onRemoveSlotAt = { slotIndex ->
                scope.launch { repository.removeQuickLaunchSlotAt(slotIndex) }
            },
            onOpenFolder = { slotIndex, apps, folderName, folderSymbolIconName ->
                folderToShow = QuickLaunchFolderOpen(slotIndex, apps, folderName, folderSymbolIconName)
            },
            addTileContentDescription = "Add intent folder",
            tileContent = QuickLaunchTileContent.IntentLabels,
            beforeAddAuxTiles = listOfNotNull(
                resumeTile,
                QuickLaunchAuxTile(
                    label = "something else?",
                    onClick = onOpenTimerPlain,
                    contentDescription = "Open timer without prefill",
                ),
            ),
        )
    }

    if (showNewIntentDialog) {
        AlertDialog(
            onDismissRequest = {
                focusManager.clearFocus(true)
                showNewIntentDialog = false
                newIntentName = ""
            },
            title = { Text("New intent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Name this folder after your goal, not the app — e.g. Learn, Connect, Reflect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = newIntentName,
                        onValueChange = { newIntentName = it },
                        label = { Text("Intent name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        focusManager.clearFocus(true)
                        val name = newIntentName.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                repository.addIntentFolder(name)
                                showNewIntentDialog = false
                                newIntentName = ""
                            }
                        }
                    },
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        focusManager.clearFocus(true)
                        showNewIntentDialog = false
                        newIntentName = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showAddDialog) {
        AddAppsDialog(
            title = "Add app to intent folder",
            apps = installedApps,
            placementByPackage = placementByPackage,
            onAdd = { packageName ->
                val app = installedApps.firstOrNull { it.packageName == packageName }
                if (app != null) {
                    showAddDialog = false
                    pendingFolderApp = app
                }
            },
            onDismiss = {
                showAddDialog = false
                addDialogFolderSlotIndex = null
            },
        )
    }

    pendingFolderApp?.let { app ->
        AddFolderAppDialog(
            appInfo = app,
            onConfirm = { limitMinutes ->
                scope.launch {
                    val folderIdx = addDialogFolderSlotIndex
                    if (folderIdx != null) {
                        repository.mergePackageIntoQuickLaunchAt(folderIdx, app.packageName, limitMinutes)
                    }
                    pendingFolderApp = null
                    addDialogFolderSlotIndex = null
                }
            },
            onDismiss = {
                pendingFolderApp = null
                addDialogFolderSlotIndex = null
            },
        )
    }

    editingFolderApp?.let { app ->
        val folderIdx = folderToShow?.slotIndex
        val slot = folderIdx?.let { rawSlots.getOrNull(it) as? QuickLaunchSlot.Folder }
        AddFolderAppDialog(
            appInfo = app,
            title = "App limit",
            confirmLabel = "Save",
            initialLimitMinutes = slot?.limitMinutesFor(app.packageName),
            onConfirm = { limitMinutes ->
                scope.launch {
                    if (folderIdx != null) {
                        repository.setQuickLaunchAppLimitAt(folderIdx, app.packageName, limitMinutes)
                    }
                    editingFolderApp = null
                }
            },
            onDismiss = { editingFolderApp = null },
        )
    }

    folderToShow?.let { folder ->
        AppFolderDetailDialog(
            folder = folder,
            onDismiss = { folderToShow = null },
            titleForFolder = { f ->
                f.folderName?.takeIf { it.isNotBlank() } ?: "Unnamed intent"
            },
            showRenameIcon = true,
            onRenameIconClick = {
                folderRenameSlotIndex = folder.slotIndex
                folderRenameText = folder.folderName.orEmpty()
            },
            showSymbolIconButton = true,
            onSymbolIconClick = {
                folderSymbolSlotIndex = folder.slotIndex
                folderSymbolInitial = folder.folderSymbolIconName
            },
            onLaunchApp = { app ->
                folderToShow = null
                val slot = rawSlots.getOrNull(folder.slotIndex) as? QuickLaunchSlot.Folder
                val limitMinutes = slot?.limitMinutesFor(app.packageName)
                onQuickLaunchApp(app.packageName, stripPackages, limitMinutes)
            },
            onDragRemove = { app ->
                scope.launch {
                    repository.removeLaunchKeyFromQuickLaunchAt(folder.slotIndex, app.packageName)
                }
                folderToShow = folderToShow?.let { f ->
                    val next = f.apps.filter { it.packageName != app.packageName }
                    f.copy(apps = next)
                }
            },
            dragHintText = "Drop on timer to edit limit, or ✕ to remove from folder",
            removeDropContentDescription = "Drop to remove from folder",
            onAddAppsClick = {
                addDialogFolderSlotIndex = folder.slotIndex
                showAddDialog = true
            },
            addAppsContentDescription = "Add app to intent folder",
            onEditAppLimit = { app -> editingFolderApp = app },
        )
    }

    folderSymbolSlotIndex?.let { slotIndex ->
        MaterialSymbolPickerDialog(
            initialSelection = folderSymbolInitial,
            onDismiss = {
                folderSymbolSlotIndex = null
                folderSymbolInitial = null
            },
            onConfirm = { symbol ->
                scope.launch {
                    repository.setQuickLaunchFolderSymbolIconAt(slotIndex, symbol)
                }
                folderSymbolSlotIndex = null
                folderSymbolInitial = null
            },
        )
    }

    folderRenameSlotIndex?.let { slotIndex ->
        AlertDialog(
            onDismissRequest = {
                focusManager.clearFocus(true)
                folderRenameSlotIndex = null
                folderRenameText = ""
            },
            title = { Text("Rename intent") },
            text = {
                OutlinedTextField(
                    value = folderRenameText,
                    onValueChange = { folderRenameText = it },
                    label = { Text("Intent name") },
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
                                folderRenameText.takeIf { it.isNotBlank() },
                            )
                            folderRenameSlotIndex = null
                            folderRenameText = ""
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
                        folderRenameSlotIndex = null
                        folderRenameText = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun formatMinutes(minutes: Int): String {
    return when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
