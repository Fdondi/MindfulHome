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

    var installedApps by remember { mutableStateOf(emptyList<AppInfo>()) }
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
        folderToShow = folderToShow,
        onInstalledApps = { installedApps = it },
        onFolderToShowChange = { folderToShow = it },
        loadInstalled = { PackageManagerHelper.getInstalledApps(context) },
    )

    val slotUiRows = remember(rawSlots, installedApps) {
        mapSlotsToUi(rawSlots, installedApps.associateBy { it.packageName })
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
    folderToShow: QuickLaunchFolderOpen?,
    onInstalledApps: (List<AppInfo>) -> Unit,
    onFolderToShowChange: (QuickLaunchFolderOpen?) -> Unit,
    loadInstalled: suspend () -> List<AppInfo>,
) {
    LaunchedEffect(Unit) {
        onInstalledApps(loadInstalled())
    }
    LaunchedEffect(rawSlots, installedApps, kind) {
        val installed = installedApps.map { it.packageName }.toSet()
        if (installed.isEmpty()) return@LaunchedEffect
        missingStripPackages(rawSlots, installed).forEach { pkg ->
            repository.removeFromStrip(kind, pkg)
        }
    }
    LaunchedEffect(rawSlots, installedApps, folderToShow?.slotIndex) {
        val open = folderToShow ?: return@LaunchedEffect
        val installed = installedApps.associateBy { it.packageName }
        onFolderToShowChange(reconcileOpenFolder(open, rawSlots, installed))
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
