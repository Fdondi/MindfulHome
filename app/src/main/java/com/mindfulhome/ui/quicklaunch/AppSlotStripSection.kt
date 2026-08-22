package com.mindfulhome.ui.quicklaunch

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.model.AppInfo
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.CoroutineScope
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
    onLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    modifier: Modifier = Modifier,
    onAppSlotBounds: (uiIndex: Int, topLeft: Offset, size: Size) -> Unit = { _, _, _ -> },
    maxRows: Int? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rawSlots by repository.stripSlotsFlow(kind).collectAsState(initial = emptyList())
    val stripPackages = remember(rawSlots) { rawSlots.flatMap { it.flattenPackages() }.toSet() }
    val catalogGen by PackageManagerHelper.catalogGeneration.collectAsState()
    val installedApps = remember(catalogGen) { PackageManagerHelper.peekInstalledApps(context) }
    val resolvedByPkg = remember(stripPackages, catalogGen) {
        PackageManagerHelper.resolveAppsByPackage(context, stripPackages)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogFolderSlotIndex by remember { mutableStateOf<Int?>(null) }
    var folderToShow by remember { mutableStateOf<QuickLaunchFolderOpen?>(null) }
    var folderRenameAnchorPackage by remember { mutableStateOf<String?>(null) }
    var folderRenameText by remember { mutableStateOf("") }
    var folderSymbolAnchorPackage by remember { mutableStateOf<String?>(null) }
    var folderSymbolInitial by remember { mutableStateOf<String?>(null) }

    AppSlotStripEffects(
        repository = repository,
        kind = kind,
        rawSlots = rawSlots,
        installedApps = installedApps,
        resolvedByPkg = resolvedByPkg,
        folderToShow = folderToShow,
        onFolderToShowChange = { folderToShow = it },
    )

    val slotUiRows = remember(rawSlots, resolvedByPkg) {
        mapSlotsToUi(rawSlots, resolvedByPkg)
    }
    val copy = remember(kind) { stripCopy(kind) }

    AppSlotStripColumn(
        modifier = modifier,
        copy = copy,
        slotUiRows = slotUiRows,
        stripPackages = stripPackages,
        onLaunchApp = onLaunchApp,
        onAddQuickLaunch = {
            addDialogFolderSlotIndex = null
            showAddDialog = true
        },
        scope = scope,
        repository = repository,
        kind = kind,
        onOpenFolder = { slotIndex, apps, folderName, folderSymbolIconName ->
            folderToShow = QuickLaunchFolderOpen(slotIndex, apps, folderName, folderSymbolIconName)
        },
        onAppSlotBounds = onAppSlotBounds,
        maxRows = maxRows,
    )

    AppSlotStripDialogs(
        repository = repository,
        kind = kind,
        copy = copy,
        scope = scope,
        installedApps = installedApps,
        rawSlots = rawSlots,
        stripPackages = stripPackages,
        showAddDialog = showAddDialog,
        addDialogFolderSlotIndex = addDialogFolderSlotIndex,
        onShowAddDialogChange = { showAddDialog = it },
        onAddDialogFolderSlotIndexChange = { addDialogFolderSlotIndex = it },
        folderToShow = folderToShow,
        onFolderToShowChange = { folderToShow = it },
        folderRenameAnchorPackage = folderRenameAnchorPackage,
        folderRenameText = folderRenameText,
        onFolderRenameAnchorPackageChange = { folderRenameAnchorPackage = it },
        onFolderRenameTextChange = { folderRenameText = it },
        folderSymbolAnchorPackage = folderSymbolAnchorPackage,
        folderSymbolInitial = folderSymbolInitial,
        onFolderSymbolAnchorPackageChange = { folderSymbolAnchorPackage = it },
        onFolderSymbolInitialChange = { folderSymbolInitial = it },
        onLaunchApp = onLaunchApp,
    )
}

@Composable
private fun AppSlotStripEffects(
    repository: AppRepository,
    kind: AppSlotStripKind,
    rawSlots: List<QuickLaunchSlot>,
    installedApps: List<AppInfo>,
    resolvedByPkg: Map<String, AppInfo>,
    folderToShow: QuickLaunchFolderOpen?,
    onFolderToShowChange: (QuickLaunchFolderOpen?) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        PackageManagerHelper.getInstalledApps(context)
    }
    // Prune only after a successful full catalog scan — never against an empty/loading list.
    LaunchedEffect(rawSlots, installedApps, kind) {
        if (!shouldPruneUninstalledPackages(
                PackageManagerHelper.hasCatalog(),
                installedApps.size,
            )
        ) {
            return@LaunchedEffect
        }
        val installed = installedApps.map { it.packageName }.toSet()
        missingStripPackages(rawSlots, installed).forEach { pkg ->
            repository.removeFromStrip(kind, pkg)
        }
    }
    LaunchedEffect(rawSlots, resolvedByPkg, folderToShow?.slotIndex) {
        val open = folderToShow ?: return@LaunchedEffect
        onFolderToShowChange(reconcileOpenFolder(open, rawSlots, resolvedByPkg))
    }
}

@Composable
private fun AppSlotStripColumn(
    modifier: Modifier,
    copy: AppSlotStripCopy,
    slotUiRows: List<QuickLaunchSlotUi>,
    stripPackages: Set<String>,
    onLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    onAddQuickLaunch: () -> Unit,
    scope: CoroutineScope,
    repository: AppRepository,
    kind: AppSlotStripKind,
    onOpenFolder: (slotIndex: Int, apps: List<AppInfo>, folderName: String?, folderSymbolIconName: String?) -> Unit,
    onAppSlotBounds: (uiIndex: Int, topLeft: Offset, size: Size) -> Unit,
    maxRows: Int?,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = copy.stripTitle,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        QuickLaunchWrappedRow(
            slots = slotUiRows,
            quickLaunchPackages = stripPackages,
            onQuickLaunchApp = { packageName, allowed, limitMinutes ->
                onLaunchApp(packageName, allowed, limitMinutes)
            },
            onAddQuickLaunch = onAddQuickLaunch,
            onMoveSlot = { from, to ->
                scope.launch { repository.moveStripSlot(kind, from, to) }
            },
            onMergeSlotInto = { from, into ->
                scope.launch { repository.mergeStripSlots(kind, from, into) }
            },
            onRemoveSlot = { apps ->
                scope.launch {
                    apps.forEach { repository.removeFromStrip(kind, it.packageName) }
                }
            },
            onRemoveSlotAt = { slotIndex ->
                scope.launch { repository.removeStripSlotAt(kind, slotIndex) }
            },
            onOpenFolder = onOpenFolder,
            addTileContentDescription = copy.addTileContentDescription,
            onAppSlotBounds = onAppSlotBounds,
            maxRows = maxRows,
        )
    }
}
