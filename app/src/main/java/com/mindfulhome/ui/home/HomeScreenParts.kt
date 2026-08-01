package com.mindfulhome.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.mindfulhome.data.AppRepository
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.common.PullTabShelf
import com.mindfulhome.ui.quicklaunch.AppSlotStripKind
import com.mindfulhome.ui.quicklaunch.AppSlotStripSection
import kotlin.math.roundToInt

@Composable
internal fun HomeTopBar(
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onTimerClick) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to timer",
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Icon(
                Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
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
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Default.Search, contentDescription = "Search apps", tint = MaterialTheme.colorScheme.onBackground)
        }
        IconButton(onClick = onLogsClick) {
            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Session logs", tint = MaterialTheme.colorScheme.onBackground)
        }
        IconButton(onClick = onKarmaClick) {
            Icon(Icons.Default.Stars, contentDescription = "Karma", tint = MaterialTheme.colorScheme.onBackground)
        }
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onBackground)
        }
        IconButton(onClick = onAiClick) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Talk to AI", tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
internal fun HomeSuggestedAppsRow(
    suggestedApps: List<AppInfo>,
    negativeKarmaPackages: Set<String>,
    onAppTap: (AppInfo) -> Unit,
) {
    if (suggestedApps.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Suggested",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                count = suggestedApps.size,
                key = { suggestedApps[it].packageName },
            ) { index ->
                val app = suggestedApps[index]
                AppItem(
                    appInfo = app,
                    onClick = { onAppTap(app) },
                    isDimmed = shouldRequestAi(app.packageName, negativeKarmaPackages),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
internal fun HomeAppGrid(
    gridItems: SnapshotStateList<HomeGridItem>,
    gridState: LazyGridState,
    dragDropState: DragDropState,
    negativeKarmaPackages: Set<String>,
    onAppTap: (AppInfo) -> Unit,
    onDragStarted: (HomeGridItem, Offset, Offset) -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnded: () -> Unit,
    onDragCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(4),
        modifier = modifier.padding(horizontal = 8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.Top,
        horizontalArrangement = Arrangement.SpaceEvenly,
        userScrollEnabled = !dragDropState.isDragging,
    ) {
        items(
            count = gridItems.size,
            key = { gridItems[it].key },
        ) { index ->
            val item = gridItems[index]
            val hoverKey = gridHoverKey(dragDropState.hoverTarget)
            val isHoverTarget = hoverKey == item.key
            DraggableGridCell(
                item = item,
                dragDropState = dragDropState,
                isHoverTarget = isHoverTarget,
                isLongHover = isHoverTarget && dragDropState.isLongHover,
                onTap = {
                    if (item is HomeGridItem.AppEntry) onAppTap(item.appInfo)
                },
                isDimmed = item is HomeGridItem.AppEntry &&
                    shouldRequestAi(item.appInfo.packageName, negativeKarmaPackages),
                onDragStarted = { localOffset, itemTopLeft ->
                    onDragStarted(item, localOffset, itemTopLeft)
                },
                onDragDelta = onDragDelta,
                onDragEnded = onDragEnded,
                onDragCancelled = onDragCancelled,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
internal fun HomeFavoritesStrip(
    repository: AppRepository,
    dragDropState: DragDropState,
    favoritesStripExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLaunchApp: (String) -> Unit,
) {
    val highlight = favoritesStripHighlighted(dragDropState.hoverTarget)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .then(
                if (highlight) {
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
        PullTabShelf(
            expanded = favoritesStripExpanded,
            onExpandedChange = onExpandedChange,
            showBodyWhenCollapsed = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentDescriptionExpand = "Expand favorites",
            contentDescriptionCollapse = "Collapse favorites",
        ) {
            AppSlotStripSection(
                repository = repository,
                kind = AppSlotStripKind.Favorites,
                onLaunchApp = { pkg, _, _ -> onLaunchApp(pkg) },
                modifier = Modifier.fillMaxWidth(),
                onAppSlotBounds = { idx, topLeft, size ->
                    dragDropState.registerFavoriteSlotBounds(idx, topLeft, size)
                },
                maxRows = if (favoritesStripExpanded) null else 1,
            )
        }
    }
}

@Composable
internal fun DraggableGridCell(
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
    modifier: Modifier = Modifier,
) {
    var itemTopLeft by remember { mutableStateOf(Offset.Zero) }
    val isDragged = dragDropState.draggedItem?.key == item.key
    val borderColor = homeCellBorderColor(isLongHover, isHoverTarget)
    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                itemTopLeft = pos
                dragDropState.registerItemBounds(item.key, pos, coords.size.toSize())
            }
            .graphicsLayer { alpha = if (isDragged) 0f else 1f }
            .border(
                width = if (isHoverTarget) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .pointerInput(item.key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragStarted(offset, itemTopLeft) },
                    onDrag = { change, amount ->
                        change.consume()
                        onDragDelta(amount)
                    },
                    onDragEnd = { onDragEnded() },
                    onDragCancel = { onDragCancelled() },
                )
            }
            .clickable { onTap() },
    ) {
        if (item is HomeGridItem.AppEntry) {
            AppItem(appInfo = item.appInfo, isDimmed = isDimmed, gesturesEnabled = false)
        }
    }
}

@Composable
private fun homeCellBorderColor(isLongHover: Boolean, isHoverTarget: Boolean): Color = when {
    isLongHover -> MaterialTheme.colorScheme.tertiary
    isHoverTarget -> MaterialTheme.colorScheme.primary
    else -> Color.Transparent
}

@Composable
internal fun DragItemOverlay(
    dragDropState: DragDropState,
    isAppDimmed: (AppInfo) -> Boolean,
) {
    val item = dragDropState.draggedItem ?: return
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    dragDropState.overlayOffset.x.roundToInt(),
                    dragDropState.overlayOffset.y.roundToInt(),
                )
            }
            .graphicsLayer {
                scaleX = 1.15f
                scaleY = 1.15f
                shadowElevation = 8f
                alpha = 0.9f
            },
    ) {
        if (item is HomeGridItem.AppEntry) {
            AppItem(
                appInfo = item.appInfo,
                isDimmed = isAppDimmed(item.appInfo),
                gesturesEnabled = false,
            )
        }
    }
}
