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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.mindfulhome.data.flattenPackages
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.model.TimerState
import com.mindfulhome.service.TimerService
import com.mindfulhome.ui.quicklaunch.AppSlotStripKind
import com.mindfulhome.ui.quicklaunch.AppSlotStripSection
import com.mindfulhome.ui.search.SearchOverlay
import com.mindfulhome.util.PackageManagerHelper
import android.util.Log
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Chat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    durationMinutes: Int,
    unlockReason: String = "",
    sessionHandle: SessionLogger.SessionHandle?,
    repository: AppRepository,
    karmaManager: KarmaManager,
    onRequestAi: (packageName: String) -> Unit,
    onTimerClick: () -> Unit = {},
    onOpenDefault: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenKarma: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }

    // DB flows
    val karmaEntries by repository.allKarma().collectAsState(initial = emptyList())
    val layoutItems by repository.homeLayout().collectAsState(initial = emptyList())
    val favoritesEntries by repository.favoritesSlots().collectAsState(initial = emptyList())
    val allIntents by repository.allIntents().collectAsState(initial = emptyList())

    // Derived state
    val karmaByPackage = remember(karmaEntries) {
        karmaEntries.associateBy { it.packageName }
    }
    val negativeKarmaPackages = remember(karmaByPackage) {
        karmaByPackage.values
            .asSequence()
            .filter { it.karmaScore < 0 && !it.isOptedOut }
            .map { it.packageName }
            .toSet()
    }

    // Suggested apps: rank by cosine similarity when an unlock reason is provided
    var suggestedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(unlockReason, allApps, allIntents) {
        suggestedApps = if (unlockReason.isBlank() || allApps.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val intentsByPkg = allIntents.groupBy { it.packageName }
                val appTexts = allApps.map { app ->
                    val pastIntents = intentsByPkg[app.packageName]
                        ?.joinToString(" ") { it.intentText } ?: ""
                    app.packageName to "${app.label} $pastIntents".trim()
                }
                val ranked = EmbeddingManager.rankApps(unlockReason, appTexts)
                ranked.take(5).mapNotNull { (pkg, _) ->
                    allApps.find { it.packageName == pkg }
                }
            }
        }
    }

    // Grid items: rebuild from DB data, but only when not dragging
    val baseGridItems = remember(allApps, layoutItems) {
        buildGridItems(allApps, layoutItems)
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

    val favoritePackages = remember(favoritesEntries) {
        favoritesEntries.flatMap { it.flattenPackages() }.toSet()
    }

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

    fun hasNegativeKarma(packageName: String): Boolean {
        return packageName in negativeKarmaPackages
    }

    fun handleAppTap(appInfo: AppInfo) {
        if (hasNegativeKarma(appInfo.packageName)) {
            onRequestAi(appInfo.packageName)
        } else {
            launchApp(appInfo)
        }
    }

    fun addToFavorites(packageName: String) {
        scope.launch {
            if (packageName !in favoritePackages) {
                repository.addToFavorites(packageName)
            }
        }
    }

    fun removeFromFavorites(packageName: String) {
        scope.launch { repository.removeFromFavorites(packageName) }
    }

    fun handleDrop(draggedItem: HomeGridItem, result: DropResult) {
        scope.launch {
            when {
                result.target is DropTarget.Dock && draggedItem is HomeGridItem.AppEntry -> {
                    addToFavorites(draggedItem.appInfo.packageName)
                }

                result.target is DropTarget.OnFavoriteSlot && draggedItem is HomeGridItem.AppEntry -> {
                    val favoriteSlot = result.target as DropTarget.OnFavoriteSlot
                    repository.mergePackageIntoFavoritesAt(
                        favoriteSlot.slot,
                        draggedItem.appInfo.packageName,
                    )
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
                onHomeClick = onOpenDefault,
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
                                onClick = { handleAppTap(suggestedApps[index]) },
                                isDimmed = hasNegativeKarma(suggestedApps[index].packageName),
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
                            if (item is HomeGridItem.AppEntry) handleAppTap(item.appInfo)
                        },
                        isDimmed = item is HomeGridItem.AppEntry &&
                            hasNegativeKarma(item.appInfo.packageName),
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

            val favoritesStripHighlight =
                dragDropState.hoverTarget is DropTarget.Dock ||
                    dragDropState.hoverTarget is DropTarget.OnFavoriteSlot
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .then(
                        if (favoritesStripHighlight) {
                            Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f))
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        },
                    )
                    .onGloballyPositioned { coords ->
                        dragDropState.dockBounds = androidx.compose.ui.geometry.Rect(
                            coords.positionInRoot(),
                            coords.size.toSize(),
                        )
                    },
            ) {
                AppSlotStripSection(
                    repository = repository,
                    kind = AppSlotStripKind.Favorites,
                    onLaunchApp = { pkg, _ ->
                        allApps.find { it.packageName == pkg }?.let { handleAppTap(it) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    onAppSlotBounds = { idx, topLeft, size ->
                        dragDropState.registerFavoriteSlotBounds(idx, topLeft, size)
                    },
                )
            }
        }

        // Drag overlay
        if (dragDropState.isDragging) {
            DragItemOverlay(
                dragDropState = dragDropState,
                isAppDimmed = { app -> hasNegativeKarma(app.packageName) },
            )
        }

        // Search overlay
        SearchOverlay(
            apps = allApps,
            dimmedPackages = negativeKarmaPackages,
            visible = showSearch,
            onAppClick = { app ->
                showSearch = false
                handleAppTap(app)
            },
            onDismiss = { showSearch = false },
            onAddToDock = { app -> addToFavorites(app.packageName) }
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
    onHomeClick: () -> Unit,
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
        OutlinedButton(onClick = onTimerClick) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to timer",
                tint = MaterialTheme.colorScheme.onBackground
            )
            Icon(
                Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$durationMinutes min",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OutlinedButton(onClick = onHomeClick) {
            Icon(
                Icons.Default.Home,
                contentDescription = "Home",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

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
    isDimmed: Boolean,
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
            AppItem(
                appInfo = item.appInfo,
                isDimmed = isDimmed,
                gesturesEnabled = false
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Drag overlay (floating item following finger)
// ---------------------------------------------------------------------------

@Composable
private fun DragItemOverlay(
    dragDropState: DragDropState,
    isAppDimmed: (AppInfo) -> Boolean
) {
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
            AppItem(
                appInfo = item.appInfo,
                isDimmed = isAppDimmed(item.appInfo),
                gesturesEnabled = false
            )
        }
    }
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

