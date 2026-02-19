package com.mindfulhome.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.ai.EmbeddingManager
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.HomeLayoutItem
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.service.TimerService
import com.mindfulhome.ui.search.SearchOverlay
import com.mindfulhome.util.PackageManagerHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    durationMinutes: Int,
    unlockReason: String = "",
    repository: AppRepository,
    karmaManager: KarmaManager,
    onRequestAi: (packageName: String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    Log.d("HomeScreen", "HomeScreen composing: duration=$durationMinutes reason='$unlockReason'")

    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<HomeGridItem.FolderEntry?>(null) }

    // DB flows
    val hiddenApps by repository.hiddenApps().collectAsState(initial = emptyList())
    val folders by repository.allFolders().collectAsState(initial = emptyList())
    val allFolderApps by repository.allFolderApps().collectAsState(initial = emptyList())
    val layoutItems by repository.homeLayout().collectAsState(initial = emptyList())
    val dockedItems by repository.dockedApps().collectAsState(initial = emptyList())
    val allIntents by repository.allIntents().collectAsState(initial = emptyList())

    // Derived state
    val hiddenPackages = remember(hiddenApps) { hiddenApps.map { it.packageName }.toSet() }
    val visibleApps = remember(allApps, hiddenPackages) {
        allApps.filter { it.packageName !in hiddenPackages }
    }
    val folderAppsMap = remember(allFolderApps) { allFolderApps.groupBy { it.folderId } }
    val appsInFolders = remember(allFolderApps) { allFolderApps.map { it.packageName }.toSet() }

    // Suggested apps: rank by cosine similarity when an unlock reason is provided
    var suggestedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(unlockReason, visibleApps, allIntents) {
        suggestedApps = if (unlockReason.isBlank() || visibleApps.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val intentsByPkg = allIntents.groupBy { it.packageName }
                val appTexts = visibleApps.map { app ->
                    val pastIntents = intentsByPkg[app.packageName]
                        ?.joinToString(" ") { it.intentText } ?: ""
                    app.packageName to "${app.label} $pastIntents".trim()
                }
                val ranked = EmbeddingManager.rankApps(unlockReason, appTexts)
                ranked.take(5).mapNotNull { (pkg, _) ->
                    visibleApps.find { it.packageName == pkg }
                }
            }
        }
    }

    // Grid items: rebuild from DB data, but only when not dragging
    val baseGridItems = remember(
        visibleApps, folders, folderAppsMap, layoutItems, appsInFolders, allApps
    ) {
        buildGridItems(visibleApps, folders, folderAppsMap, layoutItems, appsInFolders, allApps)
    }

    val gridItems = remember { mutableStateListOf<HomeGridItem>() }
    val dragDropState = rememberDragDropState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(baseGridItems) {
        if (!dragDropState.isDragging) {
            gridItems.clear()
            gridItems.addAll(baseGridItems)
        }
    }

    // Dock items resolved to AppInfo
    val dockApps = remember(dockedItems, allApps) {
        dockedItems.sortedBy { it.dockPosition }.mapNotNull { docked ->
            allApps.find { it.packageName == docked.packageName }
        }
    }

    // Auto-delete empty folders
    LaunchedEffect(folders, folderAppsMap) {
        for (folder in folders) {
            if (folderAppsMap[folder.id].isNullOrEmpty()) {
                repository.deleteFolder(folder.id)
            }
        }
    }

    LaunchedEffect(Unit) {
        Log.d("HomeScreen", "Loading installed apps on IO thread...")
        allApps = withContext(Dispatchers.IO) {
            PackageManagerHelper.getInstalledApps(context)
        }
        Log.d("HomeScreen", "Loaded ${allApps.size} apps")
    }

    fun launchApp(appInfo: AppInfo) {
        scope.launch {
            SessionLogger.log("App opened: **${appInfo.label}** (`${appInfo.packageName}`)")
            karmaManager.onAppOpened(appInfo.packageName)
            TimerService.trackApp(context, appInfo.packageName)
            if (unlockReason.isNotBlank()) {
                repository.recordIntent(appInfo.packageName, unlockReason)
                EmbeddingManager.invalidateCache()
            }
            PackageManagerHelper.launchApp(context, appInfo.packageName)
        }
    }

    fun addToDock(packageName: String) {
        scope.launch {
            val count = repository.dockedCount()
            if (count < MAX_DOCK_SLOTS) {
                repository.setDocked(packageName, count)
            }
        }
    }

    fun removeFromDock(packageName: String) {
        scope.launch { repository.removeDocked(packageName) }
    }

    fun handleDrop(draggedItem: HomeGridItem, result: DropResult) {
        scope.launch {
            when {
                result.target is DropTarget.Dock && draggedItem is HomeGridItem.AppEntry -> {
                    addToDock(draggedItem.appInfo.packageName)
                }

                result.target is DropTarget.OnItem && result.wasLongHover -> {
                    val onItem = result.target as DropTarget.OnItem
                    when {
                        // App dropped on app → create folder
                        draggedItem is HomeGridItem.AppEntry && onItem.key.startsWith("app:") -> {
                            val targetPkg = onItem.key.removePrefix("app:")
                            val targetItem = gridItems.find { it.key == onItem.key }
                            val position = targetItem?.position
                                ?: gridItems.indexOfFirst { it.key == onItem.key }
                            val folderId = repository.createFolder("Folder", position)
                            repository.addAppToFolder(
                                folderId, draggedItem.appInfo.packageName, 0
                            )
                            repository.addAppToFolder(folderId, targetPkg, 1)
                        }
                        // App dropped on folder → add to folder
                        draggedItem is HomeGridItem.AppEntry && onItem.key.startsWith("folder:") -> {
                            val folderId = onItem.key.removePrefix("folder:").toLong()
                            val count = folderAppsMap[folderId]?.size ?: 0
                            repository.addAppToFolder(
                                folderId, draggedItem.appInfo.packageName, count
                            )
                        }
                    }
                }

                result.target is DropTarget.OnItem && !result.wasLongHover -> {
                    // Short hover → swap positions (reorder)
                    val onItem = result.target as DropTarget.OnItem
                    val fromIdx = gridItems.indexOfFirst { it.key == draggedItem.key }
                    val toIdx = gridItems.indexOfFirst { it.key == onItem.key }
                    if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                        val moved = gridItems.removeAt(fromIdx)
                        gridItems.add(toIdx, moved)
                        persistGridOrder(gridItems, dockedItems, repository)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            TopBar(
                durationMinutes = durationMinutes,
                onSearchClick = { showSearch = true },
                onLogsClick = onOpenLogs,
                onSettingsClick = onOpenSettings
            )

            // Suggested apps based on unlock reason
            if (suggestedApps.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "Suggested",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(
                            count = suggestedApps.size,
                            key = { suggestedApps[it].packageName }
                        ) { index ->
                            AppItem(
                                appInfo = suggestedApps[index],
                                onClick = { launchApp(suggestedApps[index]) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.Top,
                horizontalArrangement = Arrangement.SpaceEvenly,
                userScrollEnabled = !dragDropState.isDragging
            ) {
                items(
                    count = gridItems.size,
                    key = { gridItems[it].key }
                ) { index ->
                    val item = gridItems[index]
                    val hoverKey =
                        (dragDropState.hoverTarget as? DropTarget.OnItem)?.key
                    val isHoverTarget = hoverKey == item.key

                    DraggableGridCell(
                        item = item,
                        dragDropState = dragDropState,
                        isHoverTarget = isHoverTarget,
                        isLongHover = isHoverTarget && dragDropState.isLongHover,
                        onTap = {
                            when (item) {
                                is HomeGridItem.AppEntry -> launchApp(item.appInfo)
                                is HomeGridItem.FolderEntry -> openFolder = item
                            }
                        },
                        onDragStarted = { localOffset, itemTopLeft ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragDropState.startDrag(item, itemTopLeft, localOffset)
                        },
                        onDragDelta = { delta -> dragDropState.updateDrag(delta) },
                        onDragEnded = {
                            val captured = dragDropState.draggedItem
                            val result = dragDropState.endDrag()
                            if (captured != null) handleDrop(captured, result)
                        },
                        onDragCancelled = { dragDropState.cancelDrag() },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            BottomDock(
                apps = dockApps,
                isDragOver = dragDropState.hoverTarget is DropTarget.Dock,
                onAppClick = { launchApp(it) },
                onRemove = { removeFromDock(it.packageName) },
                onRegisterBounds = { topLeft, size ->
                    dragDropState.dockBounds =
                        androidx.compose.ui.geometry.Rect(topLeft, size)
                },
                modifier = Modifier.navigationBarsPadding()
            )
        }

        // FAB - hide during drag to avoid interference
        if (!dragDropState.isDragging) {
            FloatingActionButton(
                onClick = { onRequestAi("") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 80.dp)
                    .navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.Chat,
                    contentDescription = "Talk to AI",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // Drag overlay
        if (dragDropState.isDragging) {
            DragItemOverlay(dragDropState = dragDropState)
        }

        // Search overlay
        SearchOverlay(
            apps = visibleApps,
            visible = showSearch,
            onAppClick = { app ->
                showSearch = false
                launchApp(app)
            },
            onDismiss = { showSearch = false },
            onAddToDock = { app -> addToDock(app.packageName) }
        )

        // Folder dialog
        openFolder?.let { folder ->
            FolderDialog(
                folder = folder,
                onDismiss = { openFolder = null },
                onAppClick = { app ->
                    openFolder = null
                    launchApp(app)
                },
                onRename = { name ->
                    scope.launch { repository.renameFolder(folder.folderId, name) }
                },
                onRemoveApp = { pkg ->
                    scope.launch { repository.removeAppFromFolder(folder.folderId, pkg) }
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(
    durationMinutes: Int,
    onSearchClick: () -> Unit,
    onLogsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$durationMinutes min",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onSearchClick) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search apps",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        IconButton(onClick = onLogsClick) {
            Icon(
                Icons.Default.Article,
                contentDescription = "Session logs",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        IconButton(onClick = onSettingsClick) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Draggable grid cell wrapper
// ---------------------------------------------------------------------------

@Composable
private fun DraggableGridCell(
    item: HomeGridItem,
    dragDropState: DragDropState,
    isHoverTarget: Boolean,
    isLongHover: Boolean,
    onTap: () -> Unit,
    onDragStarted: (localOffset: Offset, itemTopLeft: Offset) -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnded: () -> Unit,
    onDragCancelled: () -> Unit,
    modifier: Modifier = Modifier
) {
    var itemTopLeft by remember { mutableStateOf(Offset.Zero) }
    val isDragged = dragDropState.draggedItem?.key == item.key

    val borderColor = when {
        isLongHover -> MaterialTheme.colorScheme.tertiary
        isHoverTarget -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                itemTopLeft = pos
                dragDropState.registerItemBounds(
                    item.key, pos, coords.size.toSize()
                )
            }
            .graphicsLayer { alpha = if (isDragged) 0f else 1f }
            .border(
                width = if (isHoverTarget) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .pointerInput(item.key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragStarted(offset, itemTopLeft) },
                    onDrag = { change, amount ->
                        change.consume()
                        onDragDelta(amount)
                    },
                    onDragEnd = { onDragEnded() },
                    onDragCancel = { onDragCancelled() }
                )
            }
            .clickable { onTap() }
    ) {
        when (item) {
            is HomeGridItem.AppEntry -> AppItem(
                appInfo = item.appInfo,
                gesturesEnabled = false
            )
            is HomeGridItem.FolderEntry -> FolderItem(
                name = item.name,
                apps = item.apps,
                gesturesEnabled = false
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Drag overlay (floating item following finger)
// ---------------------------------------------------------------------------

@Composable
private fun DragItemOverlay(dragDropState: DragDropState) {
    val item = dragDropState.draggedItem ?: return

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    dragDropState.overlayOffset.x.roundToInt(),
                    dragDropState.overlayOffset.y.roundToInt()
                )
            }
            .graphicsLayer {
                scaleX = 1.15f
                scaleY = 1.15f
                shadowElevation = 8f
                alpha = 0.9f
            }
    ) {
        when (item) {
            is HomeGridItem.AppEntry -> AppItem(
                appInfo = item.appInfo,
                gesturesEnabled = false
            )
            is HomeGridItem.FolderEntry -> FolderItem(
                name = item.name,
                apps = item.apps,
                gesturesEnabled = false
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom dock
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomDock(
    apps: List<AppInfo>,
    isDragOver: Boolean,
    onAppClick: (AppInfo) -> Unit,
    onRemove: (AppInfo) -> Unit,
    onRegisterBounds: (Offset, Size) -> Unit,
    modifier: Modifier = Modifier
) {
    val highlightColor = if (isDragOver) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .onGloballyPositioned { coords ->
                onRegisterBounds(
                    coords.positionInRoot(),
                    coords.size.toSize()
                )
            }
            .background(highlightColor)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (apps.isEmpty()) {
            repeat(MAX_DOCK_SLOTS) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            CircleShape
                        )
                )
            }
        } else {
            apps.forEach { app ->
                Column(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onAppClick(app) },
                            onLongClick = { onRemove(app) }
                        )
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (app.icon != null) {
                        Image(
                            painter = rememberDrawablePainter(drawable = app.icon),
                            contentDescription = app.label,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    Text(
                        text = app.label,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.width(56.dp)
                    )
                }
            }
            val remaining = MAX_DOCK_SLOTS - apps.size
            if (remaining > 0) {
                repeat(remaining) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Folder dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderDialog(
    folder: HomeGridItem.FolderEntry,
    onDismiss: () -> Unit,
    onAppClick: (AppInfo) -> Unit,
    onRename: (String) -> Unit,
    onRemoveApp: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf(folder.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (editing) {
                TextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = folder.name,
                    modifier = Modifier.clickable { editing = true }
                )
            }
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                items(
                    count = folder.apps.size,
                    key = { folder.apps[it].packageName }
                ) { index ->
                    val app = folder.apps[index]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { onAppClick(app) },
                                onLongClick = { onRemoveApp(app.packageName) }
                            )
                            .padding(8.dp)
                    ) {
                        if (app.icon != null) {
                            Image(
                                painter = rememberDrawablePainter(drawable = app.icon),
                                contentDescription = app.label,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Text(
                            text = app.label,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (editing) {
                TextButton(onClick = {
                    onRename(nameText)
                    editing = false
                }) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ---------------------------------------------------------------------------
// Grid-building logic
// ---------------------------------------------------------------------------

private fun buildGridItems(
    visibleApps: List<AppInfo>,
    folders: List<com.mindfulhome.data.AppFolder>,
    folderAppsMap: Map<Long, List<com.mindfulhome.data.FolderApp>>,
    layoutItems: List<HomeLayoutItem>,
    appsInFolders: Set<String>,
    allApps: List<AppInfo>
): List<HomeGridItem> {
    val layoutMap = layoutItems.associateBy { it.packageName }
    val items = mutableListOf<HomeGridItem>()

    for (folder in folders) {
        val folderAppInfos = folderAppsMap[folder.id]
            ?.sortedBy { it.position }
            ?.mapNotNull { fa -> allApps.find { it.packageName == fa.packageName } }
            ?: emptyList()

        if (folderAppInfos.isNotEmpty()) {
            items.add(
                HomeGridItem.FolderEntry(
                    folderId = folder.id,
                    name = folder.name,
                    apps = folderAppInfos,
                    position = folder.position
                )
            )
        }
    }

    for (app in visibleApps) {
        if (app.packageName in appsInFolders) continue
        val layout = layoutMap[app.packageName]
        items.add(
            HomeGridItem.AppEntry(
                appInfo = app,
                position = layout?.position ?: Int.MAX_VALUE
            )
        )
    }

    return items.sortedWith(
        compareBy<HomeGridItem> { it.position }
            .thenBy {
                when (it) {
                    is HomeGridItem.AppEntry -> it.appInfo.label.lowercase()
                    is HomeGridItem.FolderEntry -> it.name.lowercase()
                }
            }
    )
}

private suspend fun persistGridOrder(
    items: List<HomeGridItem>,
    dockedItems: List<HomeLayoutItem>,
    repository: AppRepository
) {
    val dockedMap = dockedItems.associateBy { it.packageName }
    val layoutUpdates = mutableListOf<HomeLayoutItem>()

    items.forEachIndexed { index, item ->
        when (item) {
            is HomeGridItem.AppEntry -> {
                val pkg = item.appInfo.packageName
                val docked = dockedMap[pkg]
                layoutUpdates.add(
                    HomeLayoutItem(
                        packageName = pkg,
                        position = index,
                        isDocked = docked?.isDocked == true,
                        dockPosition = docked?.dockPosition ?: 0
                    )
                )
            }
            is HomeGridItem.FolderEntry -> {
                repository.updateFolderPosition(item.folderId, index)
            }
        }
    }

    repository.updateGridPositions(layoutUpdates)
}
