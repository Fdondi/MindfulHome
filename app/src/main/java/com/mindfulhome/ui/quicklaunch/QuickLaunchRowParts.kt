package com.mindfulhome.ui.quicklaunch
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mindfulhome.locale.localizedIntentFolderName
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.icons.MaterialFolderWithSymbolOverlay
import kotlin.math.roundToInt

/** Callbacks + mutable drag state shared across QuickLaunch tiles in a wrapped row. */
internal class QuickLaunchRowDragHost(
    val tileCoords: MutableMap<Int, LayoutCoordinates>,
    val slotBounds: MutableMap<Int, Rect>,
    val onAppSlotBounds: (slotIndex: Int, topLeft: Offset, size: Size) -> Unit,
    val onUpdateHover: () -> Unit,
    val getDraggingIndex: () -> Int?,
    val setDraggingIndex: (Int?) -> Unit,
    val getLastPointerInRoot: () -> Offset,
    val setLastPointerInRoot: (Offset) -> Unit,
    val clearEdgePreviewSticky: () -> Unit,
    val getPendingIntentLongPressSlot: () -> Int?,
    val setPendingIntentLongPressSlot: (Int?) -> Unit,
    val getIntentLongPressDragActivated: () -> Boolean,
    val setIntentLongPressDragActivated: (Boolean) -> Unit,
    val getSuppressClickSlotIndex: () -> Int?,
    val setSuppressClickSlotIndex: (Int?) -> Unit,
    val getHoveringRemoveZone: () -> Boolean,
    val resetDragSession: () -> Unit,
    val onOpenFolder: (slotIndex: Int, apps: List<AppInfo>, folderName: String?, folderSymbolIconName: String?) -> Unit,
    val onResolvedDrop: (from: Int, currentApps: List<AppInfo>, finger: Offset, shouldRemove: Boolean) -> Unit,
    val intentLongPressDragThresholdPx: Float,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickLaunchTile(
    apps: List<AppInfo>,
    slotIndex: Int,
    tile: QuickLaunchGridTile,
    tileContent: QuickLaunchTileContent,
    minCellWidth: Dp,
    dropHighlight: Boolean,
    isDraggingThis: Boolean,
    quickLaunchPackages: Set<String>,
    slots: List<QuickLaunchSlotUi>,
    dragHost: QuickLaunchRowDragHost,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
) {
    val rawFolderLabel = folderLabelForTile(tileContent, tile.folderName, apps.size)
    val folderLabel = localizedIntentFolderName(rawFolderLabel) ?: rawFolderLabel
    val borderMod = if (dropHighlight) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
    } else {
        Modifier
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(minCellWidth),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .then(borderMod)
                    .padding(if (dropHighlight) 4.dp else 0.dp)
                    .width(minCellWidth)
                    .onGloballyPositioned { coords ->
                        dragHost.tileCoords[slotIndex] = coords
                        val rootPos = coords.positionInRoot()
                        val sz = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                        dragHost.slotBounds[slotIndex] = Rect(rootPos, sz)
                        dragHost.onAppSlotBounds(slotIndex, rootPos, sz)
                        if (dragHost.getDraggingIndex() != null) dragHost.onUpdateHover()
                    }
                    .pointerInput(slotIndex, slots, tileContent) {
                        var longPressAccum = Offset.Zero
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startLocal ->
                                handleTileDragStart(
                                    slotIndex = slotIndex,
                                    startLocal = startLocal,
                                    tileContent = tileContent,
                                    dragHost = dragHost,
                                )
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                longPressAccum += dragAmount
                                handleTileDrag(
                                    slotIndex = slotIndex,
                                    position = change.position,
                                    longPressAccum = longPressAccum,
                                    tileContent = tileContent,
                                    dragHost = dragHost,
                                )
                            },
                            onDragCancel = {
                                handleTileDragCancel(dragHost)
                            },
                            onDragEnd = {
                                handleTileDragEnd(
                                    slotIndex = slotIndex,
                                    apps = apps,
                                    folderName = tile.folderName,
                                    folderSymbolIconName = tile.folderSymbolIconName,
                                    tileContent = tileContent,
                                    dragHost = dragHost,
                                    slots = slots,
                                )
                            },
                        )
                    }
                    .combinedClickable(
                        onClick = {
                            handleTileClick(
                                slotIndex = slotIndex,
                                apps = apps,
                                tile = tile,
                                tileContent = tileContent,
                                quickLaunchPackages = quickLaunchPackages,
                                dragHost = dragHost,
                                onQuickLaunchApp = onQuickLaunchApp,
                            )
                        },
                    )
                    .then(if (isDraggingThis) Modifier.alpha(0.18f) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QuickLaunchTileVisual(
                    apps = apps,
                    tileContent = tileContent,
                    folderLabel = folderLabel,
                    folderSymbolIconName = tile.folderSymbolIconName,
                )
            }
        }
    }
}

@Composable
private fun QuickLaunchTileVisual(
    apps: List<AppInfo>,
    tileContent: QuickLaunchTileContent,
    folderLabel: String,
    folderSymbolIconName: String?,
) {
    when {
        tileContent == QuickLaunchTileContent.IntentLabels -> {
            IntentLabelTile(
                label = folderLabel,
                symbolIconName = folderSymbolIconName,
                onClick = null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        apps.size == 1 -> {
            QuickLaunchSingleAppVisual(apps.single())
        }
        else -> {
            QuickLaunchMultiAppVisual(
                apps = apps,
                folderLabel = folderLabel,
                folderSymbolIconName = folderSymbolIconName,
            )
        }
    }
}

@Composable
private fun QuickLaunchSingleAppVisual(app: AppInfo) {
    if (app.icon != null) {
        Image(
            painter = rememberDrawablePainter(app.icon),
            contentDescription = app.label,
            modifier = Modifier.size(42.dp),
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = app.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun QuickLaunchMultiAppVisual(
    apps: List<AppInfo>,
    folderLabel: String,
    folderSymbolIconName: String?,
) {
    val symbol = folderSymbolIconName
    if (!symbol.isNullOrBlank()) {
        MaterialFolderWithSymbolOverlay(
            symbolIconName = symbol,
            contentDescription = folderLabel,
            modifier = Modifier.size(42.dp),
        )
    } else {
        val preview = apps.firstOrNull()
        if (preview?.icon != null) {
            Image(
                painter = rememberDrawablePainter(preview.icon),
                contentDescription = preview.label,
                modifier = Modifier.size(42.dp),
            )
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = folderLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
    )
}

internal fun handleTileDragStart(
    slotIndex: Int,
    startLocal: Offset,
    tileContent: QuickLaunchTileContent,
    dragHost: QuickLaunchRowDragHost,
) {
    dragHost.clearEdgePreviewSticky()
    val coords = dragHost.tileCoords[slotIndex]
    dragHost.setLastPointerInRoot(
        coords?.localToRoot(startLocal)
            ?: dragHost.slotBounds[slotIndex]?.center
            ?: Offset.Zero,
    )
    if (isIntentLongPressMode(tileContent)) {
        dragHost.setPendingIntentLongPressSlot(slotIndex)
        dragHost.setIntentLongPressDragActivated(false)
    } else {
        dragHost.setDraggingIndex(slotIndex)
        dragHost.onUpdateHover()
    }
}

internal fun handleTileDrag(
    slotIndex: Int,
    position: Offset,
    longPressAccum: Offset,
    tileContent: QuickLaunchTileContent,
    dragHost: QuickLaunchRowDragHost,
) {
    val coords = dragHost.tileCoords[slotIndex]
    if (coords != null) {
        dragHost.setLastPointerInRoot(coords.localToRoot(position))
    }
    when (
        resolveTileDragStep(
            tileContent = tileContent,
            pendingSlot = dragHost.getPendingIntentLongPressSlot(),
            slotIndex = slotIndex,
            intentDragActivated = dragHost.getIntentLongPressDragActivated(),
            accumulated = longPressAccum,
            thresholdPx = dragHost.intentLongPressDragThresholdPx,
            isDragging = dragHost.getDraggingIndex() != null,
        )
    ) {
        TileDragStep.ActivateAndHover -> {
            dragHost.setIntentLongPressDragActivated(true)
            dragHost.setDraggingIndex(slotIndex)
            dragHost.onUpdateHover()
        }
        TileDragStep.UpdateHover -> dragHost.onUpdateHover()
        TileDragStep.None -> Unit
    }
}

internal fun handleTileDragCancel(dragHost: QuickLaunchRowDragHost) {
    dragHost.resetDragSession()
}

internal fun handleTileDragEnd(
    slotIndex: Int,
    apps: List<AppInfo>,
    folderName: String?,
    folderSymbolIconName: String?,
    tileContent: QuickLaunchTileContent,
    dragHost: QuickLaunchRowDragHost,
    slots: List<QuickLaunchSlotUi>,
) {
    if (
        shouldOpenFolderOnIntentLongPressEnd(
            tileContent,
            dragHost.getPendingIntentLongPressSlot(),
            slotIndex,
            dragHost.getIntentLongPressDragActivated(),
        )
    ) {
        dragHost.onOpenFolder(slotIndex, apps, folderName, folderSymbolIconName)
        dragHost.setSuppressClickSlotIndex(slotIndex)
        dragHost.setPendingIntentLongPressSlot(null)
        dragHost.setIntentLongPressDragActivated(false)
        return
    }
    val from = dragHost.getDraggingIndex()
    val finger = dragHost.getLastPointerInRoot()
    val current = from?.let { slots.getOrNull(it)?.apps }
    val shouldRemove = dragHost.getHoveringRemoveZone()
    dragHost.resetDragSession()
    if (from == null || current == null) return
    dragHost.onResolvedDrop(from, current, finger, shouldRemove)
}

internal fun handleTileClick(
    slotIndex: Int,
    apps: List<AppInfo>,
    tile: QuickLaunchGridTile,
    tileContent: QuickLaunchTileContent,
    quickLaunchPackages: Set<String>,
    dragHost: QuickLaunchRowDragHost,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
) {
    if (dragHost.getDraggingIndex() != null) return
    if (dragHost.getSuppressClickSlotIndex() == slotIndex) {
        dragHost.setSuppressClickSlotIndex(null)
        return
    }
    when (resolveSlotClick(tileContent, apps.size)) {
        QuickLaunchSlotClickAction.LaunchSingle ->
            onQuickLaunchApp(
                apps.single().packageName,
                quickLaunchPackages,
                tile.limitMinutesByPackage[apps.single().packageName],
            )
        QuickLaunchSlotClickAction.OpenFolder ->
            dragHost.onOpenFolder(slotIndex, apps, tile.folderName, tile.folderSymbolIconName)
    }
}

@Composable
internal fun QuickLaunchRemoveZone(
    hovering: Boolean,
    onBounds: (Rect) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .onGloballyPositioned { coords ->
                onBounds(
                    Rect(
                        coords.positionInRoot(),
                        Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (hovering) Color.Red else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.drop_to_remove),
                tint = if (hovering) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun GapInsertionBarOverlay(
    bar: Rect,
    boxInRoot: Offset,
    color: Color,
) {
    val density = LocalDensity.current
    val ox = bar.left - boxInRoot.x
    val oy = bar.top - boxInRoot.y
    Box(
        Modifier
            .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
            .size(
                width = with(density) { bar.width.toDp() },
                height = with(density) { bar.height.toDp() },
            )
            .background(color, RoundedCornerShape(3.dp)),
    )
}

@Composable
internal fun DragGhostOverlay(
    pointerInRoot: Offset,
    boxInRoot: Offset,
    tileContent: QuickLaunchTileContent,
    draggedApps: List<AppInfo>,
    folderName: String?,
    folderSymbolIconName: String?,
) {
    val topLeft = pointerInRoot - boxInRoot - Offset(30f, 30f)
    Box(
        modifier = Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .size(60.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            tileContent == QuickLaunchTileContent.IntentLabels -> {
                val rawGhost = ghostLabelForDrag(tileContent, folderName)
                Text(
                    text = localizedIntentFolderName(rawGhost) ?: rawGhost,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            draggedApps.size > 1 -> DragGhostMultiApp(draggedApps, folderSymbolIconName)
            else -> DragGhostSingleApp(draggedApps.first())
        }
    }
}

@Composable
private fun DragGhostMultiApp(draggedApps: List<AppInfo>, folderSymbolIconName: String?) {
    if (!folderSymbolIconName.isNullOrBlank()) {
        MaterialFolderWithSymbolOverlay(
            symbolIconName = folderSymbolIconName,
            contentDescription = "Folder",
            modifier = Modifier.size(36.dp),
            folderSize = 36.dp,
        )
        return
    }
    val app = draggedApps.firstOrNull()
    if (app?.icon != null) {
        Image(
            painter = rememberDrawablePainter(app.icon),
            contentDescription = app.label,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun DragGhostSingleApp(app: AppInfo) {
    if (app.icon != null) {
        Image(
            painter = rememberDrawablePainter(app.icon),
            contentDescription = app.label,
            modifier = Modifier.size(36.dp),
        )
    }
}
