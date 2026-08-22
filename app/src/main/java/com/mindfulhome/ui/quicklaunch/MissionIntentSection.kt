package com.mindfulhome.ui.quicklaunch
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.placementsByPackage
import com.mindfulhome.model.AppInfo
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
    val scope = rememberCoroutineScope()
    val rawSlots by repository.quickLaunchSlots().collectAsState(initial = emptyList())
    val stripPackages = remember(rawSlots) { rawSlots.flatMap { it.flattenAllowedPackages() }.toSet() }
    val placementByPackage = remember(rawSlots) { placementsByPackage(rawSlots) }
    val slotPackages = remember(rawSlots) { rawSlots.flatMap { it.flattenPackages() }.toSet() }
    val catalogGen by PackageManagerHelper.catalogGeneration.collectAsState()
    val installedApps = remember(catalogGen) { PackageManagerHelper.peekInstalledApps(context) }
    val resolvedByPkg = remember(slotPackages, catalogGen) {
        PackageManagerHelper.resolveAppsByPackage(context, slotPackages)
    }

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
        PackageManagerHelper.getInstalledApps(context)
    }

    LaunchedEffect(installedApps) {
        if (!PackageManagerHelper.hasCatalog()) return@LaunchedEffect
        repository.ensureIntentQuickLaunchInitialized(installedApps.map { it.packageName }.toSet())
    }

    LaunchedEffect(rawSlots, installedApps) {
        if (!shouldPruneUninstalledPackages(
                PackageManagerHelper.hasCatalog(),
                installedApps.size,
            )
        ) {
            return@LaunchedEffect
        }
        missingPackagesInSlots(rawSlots, installedApps.map { it.packageName }.toSet())
            .forEach { pkg -> repository.removeFromQuickLaunch(pkg) }
    }

    LaunchedEffect(rawSlots, resolvedByPkg, folderToShow?.slotIndex) {
        val open = folderToShow ?: return@LaunchedEffect
        folderToShow = reconcileOpenIntentFolder(open, rawSlots, resolvedByPkg) { folder ->
            folder.shortcuts.map { ShortcutUiHelper.pinnedShortcutToAppInfo(context, it, resolvedByPkg) }
        }
    }

    val slotUiRows = remember(rawSlots, resolvedByPkg) {
        mapIntentSlotsToUi(rawSlots, resolvedByPkg) { folder ->
            folder.shortcuts.map { ShortcutUiHelper.pinnedShortcutToAppInfo(context, it, resolvedByPkg) }
        }
    }

    val hasEmptyNamedFolder = remember(rawSlots) { hasEmptyNamedIntentFolder(rawSlots) }
    val resumeTile = buildResumeAuxTile(resumeSessionLabel, resumeSessionMinutes, onResumeSession)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasEmptyNamedFolder) {
            Text(
                text = stringResource(R.string.name_folders_after_what_you_re_trying_to_do_then),
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
                    label = stringResource(R.string.something_else_question),
                    onClick = onOpenTimerPlain,
                    contentDescription = stringResource(R.string.open_timer_without_prefill),
                ),
            ),
        )
    }

    MissionIntentDialogs(
        repository = repository,
        scope = scope,
        installedApps = installedApps,
        rawSlots = rawSlots,
        stripPackages = stripPackages,
        placementByPackage = placementByPackage,
        showNewIntentDialog = showNewIntentDialog,
        newIntentName = newIntentName,
        onShowNewIntentDialogChange = { showNewIntentDialog = it },
        onNewIntentNameChange = { newIntentName = it },
        showAddDialog = showAddDialog,
        addDialogFolderSlotIndex = addDialogFolderSlotIndex,
        onShowAddDialogChange = { showAddDialog = it },
        onAddDialogFolderSlotIndexChange = { addDialogFolderSlotIndex = it },
        pendingFolderApp = pendingFolderApp,
        onPendingFolderAppChange = { pendingFolderApp = it },
        editingFolderApp = editingFolderApp,
        onEditingFolderAppChange = { editingFolderApp = it },
        folderToShow = folderToShow,
        onFolderToShowChange = { folderToShow = it },
        folderRenameSlotIndex = folderRenameSlotIndex,
        folderRenameText = folderRenameText,
        onFolderRenameSlotIndexChange = { folderRenameSlotIndex = it },
        onFolderRenameTextChange = { folderRenameText = it },
        folderSymbolSlotIndex = folderSymbolSlotIndex,
        folderSymbolInitial = folderSymbolInitial,
        onFolderSymbolSlotIndexChange = { folderSymbolSlotIndex = it },
        onFolderSymbolInitialChange = { folderSymbolInitial = it },
        onQuickLaunchApp = onQuickLaunchApp,
    )
}
