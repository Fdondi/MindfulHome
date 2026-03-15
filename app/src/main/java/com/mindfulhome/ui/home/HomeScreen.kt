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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.ai.EmbeddingManager
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.HomeLayoutItem
import com.mindfulhome.data.ShelfItem
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.model.TimerState
import com.mindfulhome.service.TimerService
import com.mindfulhome.ui.common.AddAppsDialog
import com.mindfulhome.ui.common.AppShelf
import com.mindfulhome.ui.common.AppShelfEntry
import com.mindfulhome.ui.search.SearchOverlay
import com.mindfulhome.util.PackageManagerHelper
import android.util.Log
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Chat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.ceil

private const val FAVORITES_COLLAPSED_ROWS = 1

@Composable
fun HomeScreen(
    durationMinutes: Int,
    unlockReason: String = "",
    sessionHandle: SessionLogger.SessionHandle?,
    repository: AppRepository,
    karmaManager: KarmaManager,
    onRequestAi: (packageName: String) -> Unit,
    onTimerClick: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenKarma: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    // DB flows
    val hiddenApps by repository.hiddenApps().collectAsState(initial = emptyList())
    val layoutItems by repository.homeLayout().collectAsState(initial = emptyList())
    val shelfItems by repository.shelfApps().collectAsState(initial = emptyList())
    val allIntents by repository.allIntents().collectAsState(initial = emptyList())

    // Derived state
    val hiddenPackages = remember(hiddenApps) { hiddenApps.map { it.packageName }.toSet() }
    val visibleApps = remember(allApps, hiddenPackages) {
        allApps.filter { it.packageName !in hiddenPackages }
    }

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
    val baseGridItems = remember(visibleApps, layoutItems) {
        buildGridItems(visibleApps, layoutItems)
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

    val favoriteSlots = remember(shelfItems, allApps) {
        buildFavoriteSlots(shelfItems, allApps)
    }
    val favoritePackages = remember(shelfItems) { shelfItems.map { it.packageName }.toSet() }
    var openFolderSlot by remember { mutableStateOf<FavoriteSlot?>(null) }

    LaunchedEffect(Unit) {
        if (TimerService.timerState.value !is TimerState.Idle) {
            TimerService.clearVisibleNudges(context, sessionHandle)
        }
        Log.d("HomeScreen", "Loading installed apps from shared cache...")
        allApps = PackageManagerHelper.getInstalledApps(context)
        Log.d("HomeScreen", "Loaded ${allApps.size} apps")
    }

    fun launchApp(appInfo: AppInfo) {
        scope.launch {
            SessionLogger.log(sessionHandle, "App opened: **${appInfo.label}** (`${appInfo.packageName}`)")
            karmaManager.onAppOpened(appInfo.packageName)
            TimerService.trackApp(context, appInfo.packageName, sessionHandle)
            if (unlockReason.isNotBlank()) {
                repository.recordIntent(appInfo.packageName, unlockReason)
                EmbeddingManager.invalidateCache()
            }
            PackageManagerHelper.launchApp(context, appInfo.packageName)
        }
    }

    fun addToFavorites(packageName: String) {
        scope.launch {
            if (packageName !in favoritePackages) {
                repository.addToShelf(packageName)
            }
        }
    }

    fun removeFromFavorites(packageName: String) {
        scope.launch { repository.removeFromShelf(packageName) }
    }

    fun handleDrop(draggedItem: HomeGridItem, result: DropResult) {
        scope.launch {
            when {
                result.target is DropTarget.Dock && draggedItem is HomeGridItem.AppEntry -> {
                    addToFavorites(draggedItem.appInfo.packageName)
                }

                result.target is DropTarget.OnFavoriteSlot && draggedItem is HomeGridItem.AppEntry -> {
                    val favoriteSlot = result.target
                    repository.addToShelfSlot(draggedItem.appInfo.packageName, favoriteSlot.slot)
                }

                result.target is DropTarget.OnItem -> {
                    val onItem = result.target
                    val fromIdx = gridItems.indexOfFirst { it.key == draggedItem.key }
                    val toIdx = gridItems.indexOfFirst { it.key == onItem.key }
                    if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                        val moved = gridItems.removeAt(fromIdx)
                        gridItems.add(toIdx, moved)
                        persistGridOrder(gridItems, repository)
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
                onTimerClick = onTimerClick,
                onSearchClick = { showSearch = true },
                onLogsClick = onOpenLogs,
                onKarmaClick = onOpenKarma,
                onSettingsClick = onOpenSettings,
                onAiClick = { onRequestAi("") },
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
                            if (item is HomeGridItem.AppEntry) launchApp(item.appInfo)
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

            FavoriteDock(
                slots = favoriteSlots,
                isDragOver = dragDropState.hoverTarget is DropTarget.Dock ||
                    dragDropState.hoverTarget is DropTarget.OnFavoriteSlot,
                hoveredSlot = (dragDropState.hoverTarget as? DropTarget.OnFavoriteSlot)?.slot,
                onAppClick = { launchApp(it) },
                onOpenFolder = { openFolderSlot = it },
                onRemove = { removeFromFavorites(it.packageName) },
                onAddClick = { showAddDialog = true },
                onRegisterBounds = { topLeft, size ->
                    dragDropState.dockBounds =
                        androidx.compose.ui.geometry.Rect(topLeft, size)
                },
                onRegisterSlotBounds = { slot, topLeft, size ->
                    dragDropState.registerFavoriteSlotBounds(slot, topLeft, size)
                },
                modifier = Modifier
                    .fillMaxWidth()
            )
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
            onAddToDock = { app -> addToFavorites(app.packageName) }
        )

        if (showAddDialog) {
            AddAppsDialog(
                title = "Add to Favorites",
                apps = visibleApps,
                excludedPackages = favoritePackages,
                onAdd = { packageName -> addToFavorites(packageName) },
                onDismiss = { showAddDialog = false },
            )
        }

    }

    openFolderSlot?.let { folder ->
        AlertDialog(
            onDismissRequest = { openFolderSlot = null },
            title = { Text("Favorites folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    folder.apps.forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    openFolderSlot = null
                                    launchApp(app)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = app.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { removeFromFavorites(app.packageName) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { openFolderSlot = null }) {
                    Text("Done")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(
    durationMinutes: Int,
    onTimerClick: () -> Unit,
    onSearchClick: () -> Unit,
    onLogsClick: () -> Unit,
    onKarmaClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAiClick: () -> Unit,
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
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onTimerClick)
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
                Icons.AutoMirrored.Filled.Article,
                contentDescription = "Session logs",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        IconButton(onClick = onKarmaClick) {
            Icon(
                Icons.Default.Stars,
                contentDescription = "Karma",
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
        IconButton(onClick = onAiClick) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = "Talk to AI",
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
        if (item is HomeGridItem.AppEntry) {
            AppItem(appInfo = item.appInfo, gesturesEnabled = false)
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
        if (item is HomeGridItem.AppEntry) {
            AppItem(appInfo = item.appInfo, gesturesEnabled = false)
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom dock
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteDock(
    slots: List<FavoriteSlot>,
    isDragOver: Boolean,
    hoveredSlot: Int?,
    onAppClick: (AppInfo) -> Unit,
    onOpenFolder: (FavoriteSlot) -> Unit,
    onRemove: (AppInfo) -> Unit,
    onAddClick: () -> Unit,
    onRegisterBounds: (Offset, Size) -> Unit,
    onRegisterSlotBounds: (Int, Offset, Size) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val highlightColor = if (isDragOver) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val entries = remember(slots, hoveredSlot, onAppClick, onOpenFolder, onRemove, onRegisterSlotBounds) {
        slots.mapNotNull { slot ->
            val firstApp = slot.apps.firstOrNull() ?: return@mapNotNull null
            val isFolder = slot.apps.size > 1
            AppShelfEntry(
                key = "slot_${slot.slotPosition}",
                label = if (isFolder) "Folder (${slot.apps.size})" else firstApp.label,
                icon = firstApp.icon,
                isHighlighted = hoveredSlot == slot.slotPosition,
                onClick = {
                    if (isFolder) onOpenFolder(slot) else onAppClick(firstApp)
                },
                onLongClick = {
                    if (isFolder) onOpenFolder(slot) else onRemove(firstApp)
                },
                onPositioned = { x, y, w, h ->
                    onRegisterSlotBounds(
                        slot.slotPosition,
                        Offset(x, y),
                        Size(w, h)
                    )
                }
            )
        }
    }

    AppShelf(
        entries = entries,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        collapsedRows = FAVORITES_COLLAPSED_ROWS,
        showBodyWhenCollapsed = true,
        onAddClick = onAddClick,
        addContentDescription = "Add app to favorites",
        contentDescriptionExpand = "Expand favorites",
        contentDescriptionCollapse = "Collapse favorites",
        modifier = modifier
            .onGloballyPositioned { coords ->
                onRegisterBounds(coords.positionInRoot(), coords.size.toSize())
            },
        containerColor = highlightColor,
    )
}

// ---------------------------------------------------------------------------
// Grid-building logic
// ---------------------------------------------------------------------------

private fun buildGridItems(
    visibleApps: List<AppInfo>,
    layoutItems: List<HomeLayoutItem>
): List<HomeGridItem> {
    val layoutMap = layoutItems.associateBy { it.packageName }

    return visibleApps.map { app ->
        HomeGridItem.AppEntry(
            appInfo = app,
            position = layoutMap[app.packageName]?.position ?: Int.MAX_VALUE
        )
    }.sortedWith(
        compareBy<HomeGridItem.AppEntry> { it.position }
            .thenBy { it.appInfo.label.lowercase() }
    )
}

private suspend fun persistGridOrder(
    items: List<HomeGridItem>,
    repository: AppRepository
) {
    val layoutUpdates = items.mapIndexedNotNull { index, item ->
        if (item is HomeGridItem.AppEntry) {
            val pkg = item.appInfo.packageName
            HomeLayoutItem(
                packageName = pkg,
                position = index,
                isDocked = false,
                dockPosition = 0
            )
        } else null
    }
    repository.updateGridPositions(layoutUpdates)
}

private data class FavoriteSlot(
    val slotPosition: Int,
    val apps: List<AppInfo>
)

private fun buildFavoriteSlots(
    shelfItems: List<ShelfItem>,
    allApps: List<AppInfo>
): List<FavoriteSlot> {
    if (shelfItems.isEmpty()) return emptyList()
    val appsByPackage = allApps.associateBy { it.packageName }
    return shelfItems
        .groupBy { it.slotPosition }
        .toSortedMap()
        .mapNotNull { (slot, itemsInSlot) ->
            val apps = itemsInSlot
                .sortedBy { it.orderInSlot }
                .mapNotNull { item -> appsByPackage[item.packageName] }
            if (apps.isEmpty()) null else FavoriteSlot(slot, apps)
        }
}
