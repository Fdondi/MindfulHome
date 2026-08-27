package com.mindfulhome.ui.quicklaunch

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.coachmark.coachmarkTargetIf
import com.mindfulhome.ui.icons.MaterialSymbolGlyph
import io.luminos.LocalCoachmarkController
import kotlin.math.max
import kotlin.math.min

/** Resolved QuickLaunch tile for UI (single app or folder) with [AppInfo] for icons/labels. */
data class QuickLaunchSlotUi(
    val apps: List<AppInfo>,
    val folderName: String? = null,
    /** Material Icons name (snake_case) for badge on folder glyph; null = folder only. */
    val folderSymbolIconName: String? = null,
    val limitMinutesByPackage: Map<String, Int> = emptyMap(),
)

data class QuickLaunchFolderOpen(
    val slotIndex: Int,
    val apps: List<AppInfo>,
    val folderName: String?,
    val folderSymbolIconName: String? = null,
    /** null value = unlimited; absent key should not occur for listed apps. */
    val appLimitsByPackage: Map<String, Int?> = emptyMap(),
)

fun formatFolderAppLimitBadge(limitMinutes: Int): String = "${limitMinutes}m"

enum class QuickLaunchTileContent {
    AppIcons,
    IntentLabels,
}

data class QuickLaunchAuxTile(
    val label: String,
    val subtitle: String? = null,
    val onClick: () -> Unit,
    val contentDescription: String,
    val coachmarkId: String? = null,
)

private val IntentTileWidth = 74.dp
private val IntentTileHeight = 56.dp
private val QuickLaunchGridMinCellWidth = 74.dp

internal data class QuickLaunchAdaptiveGrid(
    val columns: Int,
    val horizontalGap: Dp,
    val minCellWidth: Dp,
)

internal fun quickLaunchAdaptiveGrid(
    maxWidth: Dp,
    minCellWidth: Dp = QuickLaunchGridMinCellWidth,
): QuickLaunchAdaptiveGrid {
    val columns = (maxWidth / minCellWidth).toInt().coerceAtLeast(1)
    val horizontalGap = if (columns > 1) {
        ((maxWidth - (minCellWidth * columns)) / (columns - 1)).coerceAtLeast(0.dp)
    } else {
        0.dp
    }
    return QuickLaunchAdaptiveGrid(columns, horizontalGap, minCellWidth)
}

@Composable
fun IntentLabelTile(
    label: String,
    subtitle: String? = null,
    symbolIconName: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
) {
    val shape = RoundedCornerShape(12.dp)
    val tileBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    Box(
        modifier = modifier
            .width(IntentTileWidth)
            .height(IntentTileHeight)
            .background(tileBackground, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!symbolIconName.isNullOrBlank()) {
                MaterialSymbolGlyph(
                    symbolIconName = symbolIconName,
                    size = 22.dp,
                    contentDescription = label,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Inner ~56% of tile: drop here on another app merges into a folder. */
internal fun quickLaunchMergeZoneRect(rect: Rect): Rect {
    val insetX = rect.width * 0.22f
    val insetY = rect.height * 0.22f
    return Rect(
        rect.left + insetX,
        rect.top + insetY,
        rect.right - insetX,
        rect.bottom - insetY,
    )
}

internal fun insertIndexBeforeRight(from: Int, right: Int): Int =
    if (from < right) right - 1 else right

internal fun insertIndexAfterSlot(from: Int, slotIdx: Int): Int {
    val idxAfterRemove = if (from < slotIdx) slotIdx - 1 else slotIdx
    return idxAfterRemove + 1
}

internal fun quickLaunchHorizontalGapRect(left: Rect, right: Rect, minGapPx: Float): Rect {
    val top = min(left.top, right.top)
    val bottom = max(left.bottom, right.bottom)
    val gapLeft = left.right
    val gapRight = right.left
    return if (gapRight > gapLeft) {
        Rect(gapLeft, top, gapRight, bottom)
    } else {
        val mid = (gapLeft + gapRight) / 2f
        Rect(mid - minGapPx / 2f, top, mid + minGapPx / 2f, bottom)
    }
}

internal fun quickLaunchVerticalGapRect(bottomSlot: Rect, topSlot: Rect, minGapPx: Float): Rect {
    val left = min(bottomSlot.left, topSlot.left)
    val right = max(bottomSlot.right, topSlot.right)
    val gTop = bottomSlot.bottom
    val gBottom = topSlot.top
    return if (gBottom > gTop) {
        Rect(left, gTop, right, gBottom)
    } else {
        val midY = (gTop + gBottom) / 2f
        Rect(left, midY - minGapPx / 2f, right, midY + minGapPx / 2f)
    }
}

/** Thin vertical bar in the middle of the horizontal gap between two tiles (reorder). */
internal fun horizontalGapInsertionBarRect(
    left: Rect,
    right: Rect,
    minGapPx: Float,
    barWidthPx: Float,
): Rect {
    val gap = quickLaunchHorizontalGapRect(left, right, minGapPx)
    val mid = (gap.left + gap.right) / 2f
    return Rect(
        mid - barWidthPx / 2f,
        gap.top,
        mid + barWidthPx / 2f,
        gap.bottom,
    )
}

/** Thin horizontal bar in the middle of the vertical gap between two rows (reorder). */
internal fun verticalGapInsertionBarRect(
    bottomSlot: Rect,
    topSlot: Rect,
    minGapPx: Float,
    barHeightPx: Float,
): Rect {
    val gap = quickLaunchVerticalGapRect(bottomSlot, topSlot, minGapPx)
    val mid = (gap.top + gap.bottom) / 2f
    return Rect(
        gap.left,
        mid - barHeightPx / 2f,
        gap.right,
        mid + barHeightPx / 2f,
    )
}

@Composable
fun QuickLaunchFolderBody(
    apps: List<AppInfo>,
    onLaunchApp: (AppInfo) -> Unit,
    onDragRemove: (AppInfo) -> Unit,
    onDragExtractToOwnSlot: (AppInfo) -> Unit = {},
    dragHintText: String = "Drop on → exit folder, or ✕ to remove from QuickLaunch",
    removeDropContentDescription: String = "Drop to remove from QuickLaunch",
    onAddAppsClick: (() -> Unit)? = null,
    addAppsContentDescription: String = "Add app to folder",
    appLimitsByPackage: Map<String, Int?> = emptyMap(),
    onEditAppLimit: ((AppInfo) -> Unit)? = null,
) {
    val appCoords = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    var draggingPackage by remember { mutableStateOf<String?>(null) }
    var lastPointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var secondaryDropZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var removeZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var hoveringSecondaryDrop by remember { mutableStateOf(false) }
    var hoveringRemove by remember { mutableStateOf(false) }
    val useEditTimerDrop = onEditAppLimit != null
    var folderBodyRoot by remember { mutableStateOf(Offset.Zero) }

    fun updateFolderHover() {
        val (remove, secondary) = resolveFolderHoverFlags(
            finger = lastPointerInRoot,
            dragging = draggingPackage != null,
            removeBounds = removeZoneBounds,
            secondaryBounds = secondaryDropZoneBounds,
        )
        hoveringRemove = remove
        hoveringSecondaryDrop = secondary
    }

    val dragHost = FolderDragHost(
        appCoords = appCoords,
        getDraggingPackage = { draggingPackage },
        setDraggingPackage = { draggingPackage = it },
        getLastPointerInRoot = { lastPointerInRoot },
        setLastPointerInRoot = { lastPointerInRoot = it },
        getRemoveZoneBounds = { removeZoneBounds },
        getSecondaryDropZoneBounds = { secondaryDropZoneBounds },
        setHoveringRemove = { hoveringRemove = it },
        setHoveringSecondary = { hoveringSecondaryDrop = it },
        onUpdateHover = { updateFolderHover() },
        onDropResolved = { app, action ->
            dispatchFolderDrop(
                action = action,
                app = app,
                useEditTimerDrop = useEditTimerDrop,
                onDragRemove = onDragRemove,
                onEditAppLimit = onEditAppLimit,
                onDragExtractToOwnSlot = onDragExtractToOwnSlot,
            )
        },
        findApp = { pkg -> apps.firstOrNull { it.packageName == pkg } },
    )
    val draggedApp = draggingPackage?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { folderBodyRoot = it.positionInRoot() },
    ) {
        QuickLaunchFolderBodyContent(
            maxWidth = maxWidth,
            apps = apps,
            draggingPackage = draggingPackage,
            dragHost = dragHost,
            onLaunchApp = onLaunchApp,
            appLimitsByPackage = appLimitsByPackage,
            onAddAppsClick = onAddAppsClick,
            addAppsContentDescription = addAppsContentDescription,
            dragHintText = dragHintText,
            removeDropContentDescription = removeDropContentDescription,
            useEditTimerDrop = useEditTimerDrop,
            hoveringSecondaryDrop = hoveringSecondaryDrop,
            hoveringRemove = hoveringRemove,
            onSecondaryBounds = { secondaryDropZoneBounds = it },
            onRemoveBounds = { removeZoneBounds = it },
            onBoundsUpdated = {
                if (draggingPackage != null) updateFolderHover()
            },
            draggedApp = draggedApp,
            lastPointerInRoot = lastPointerInRoot,
            folderBodyRoot = folderBodyRoot,
        )
    }
}

@Composable
private fun QuickLaunchFolderBodyContent(
    maxWidth: Dp,
    apps: List<AppInfo>,
    draggingPackage: String?,
    dragHost: FolderDragHost,
    onLaunchApp: (AppInfo) -> Unit,
    appLimitsByPackage: Map<String, Int?>,
    onAddAppsClick: (() -> Unit)?,
    addAppsContentDescription: String,
    dragHintText: String,
    removeDropContentDescription: String,
    useEditTimerDrop: Boolean,
    hoveringSecondaryDrop: Boolean,
    hoveringRemove: Boolean,
    onSecondaryBounds: (Rect) -> Unit,
    onRemoveBounds: (Rect) -> Unit,
    onBoundsUpdated: () -> Unit,
    draggedApp: AppInfo?,
    lastPointerInRoot: Offset,
    folderBodyRoot: Offset,
) {
    val grid = quickLaunchAdaptiveGrid(maxWidth)
    val minCell = grid.minCellWidth
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        apps.chunked(grid.columns).forEach { rowApps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(grid.horizontalGap),
            ) {
                rowApps.forEach { app ->
                    FolderAppCell(
                        app = app,
                        apps = apps,
                        minCellWidth = minCell,
                        limitMinutes = appLimitsByPackage[app.packageName],
                        isDraggingThis = draggingPackage == app.packageName,
                        dragHost = dragHost,
                        onLaunchApp = onLaunchApp,
                    )
                }
            }
        }
        if (onAddAppsClick != null) {
            FolderAddAppsButton(
                onClick = onAddAppsClick,
                minCellWidth = minCell,
                contentDescription = addAppsContentDescription,
            )
        }
        if (draggingPackage != null) {
            FolderDropZones(
                dragHintText = dragHintText,
                removeDropContentDescription = removeDropContentDescription,
                useEditTimerDrop = useEditTimerDrop,
                hoveringSecondary = hoveringSecondaryDrop,
                hoveringRemove = hoveringRemove,
                onSecondaryBounds = onSecondaryBounds,
                onRemoveBounds = onRemoveBounds,
                onBoundsUpdated = onBoundsUpdated,
            )
        }
    }
    FolderBodyDragGhostIfNeeded(
        draggedApp = draggedApp,
        draggingPackage = draggingPackage,
        lastPointerInRoot = lastPointerInRoot,
        folderBodyRoot = folderBodyRoot,
    )
}

@Composable
private fun FolderBodyDragGhostIfNeeded(
    draggedApp: AppInfo?,
    draggingPackage: String?,
    lastPointerInRoot: Offset,
    folderBodyRoot: Offset,
) {
    if (draggedApp == null || draggingPackage == null) return
    FolderDragGhost(
        draggedApp = draggedApp,
        pointerInRoot = lastPointerInRoot,
        bodyRoot = folderBodyRoot,
    )
}

@Composable
private fun FolderAddAppsButton(
    onClick: () -> Unit,
    minCellWidth: Dp,
    contentDescription: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        OutlinedButton(
            onClick = onClick,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            ),
            modifier = Modifier.width(minCellWidth),
        ) {
            Icon(Icons.Default.Add, contentDescription = contentDescription)
        }
    }
}

private const val QuickLaunchDragLogTag = "QuickLaunchDrag"

/** Insert-between preview (matches gap drop zone). */
internal val QuickLaunchGapBarYellow = Color(0xFFEAB308)

@Composable
fun QuickLaunchWrappedRow(
    slots: List<QuickLaunchSlotUi>,
    quickLaunchPackages: Set<String>,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    onAddQuickLaunch: () -> Unit,
    onMoveSlot: (from: Int, to: Int) -> Unit,
    onMergeSlotInto: (from: Int, into: Int) -> Unit,
    onRemoveSlot: (List<AppInfo>) -> Unit,
    onOpenFolder: (slotIndex: Int, apps: List<AppInfo>, folderName: String?, folderSymbolIconName: String?) -> Unit,
    addTileContentDescription: String = "Add QuickLaunch app",
    /** Reports each app-tile layout in root coordinates for external hit-testing (e.g. grid drag-and-drop). */
    onAppSlotBounds: (slotIndex: Int, topLeft: Offset, size: Size) -> Unit = { _, _, _ -> },
    maxRows: Int? = null,
    tileContent: QuickLaunchTileContent = QuickLaunchTileContent.AppIcons,
    beforeAddAuxTiles: List<QuickLaunchAuxTile> = emptyList(),
    onRemoveSlotAt: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var boxInRoot by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                boxInRoot = coords.positionInRoot()
            },
    ) {
        QuickLaunchWrappedRowBody(
            maxWidth = maxWidth,
            boxInRoot = boxInRoot,
            slots = slots,
            quickLaunchPackages = quickLaunchPackages,
            onQuickLaunchApp = onQuickLaunchApp,
            onAddQuickLaunch = onAddQuickLaunch,
            onMoveSlot = onMoveSlot,
            onMergeSlotInto = onMergeSlotInto,
            onRemoveSlot = onRemoveSlot,
            onOpenFolder = onOpenFolder,
            addTileContentDescription = addTileContentDescription,
            onAppSlotBounds = onAppSlotBounds,
            maxRows = maxRows,
            tileContent = tileContent,
            beforeAddAuxTiles = beforeAddAuxTiles,
            onRemoveSlotAt = onRemoveSlotAt,
        )
    }
}

@Composable
private fun QuickLaunchWrappedRowBody(
    maxWidth: Dp,
    boxInRoot: Offset,
    slots: List<QuickLaunchSlotUi>,
    quickLaunchPackages: Set<String>,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    onAddQuickLaunch: () -> Unit,
    onMoveSlot: (from: Int, to: Int) -> Unit,
    onMergeSlotInto: (from: Int, into: Int) -> Unit,
    onRemoveSlot: (List<AppInfo>) -> Unit,
    onOpenFolder: (slotIndex: Int, apps: List<AppInfo>, folderName: String?, folderSymbolIconName: String?) -> Unit,
    addTileContentDescription: String,
    onAppSlotBounds: (slotIndex: Int, topLeft: Offset, size: Size) -> Unit,
    maxRows: Int?,
    tileContent: QuickLaunchTileContent,
    beforeAddAuxTiles: List<QuickLaunchAuxTile>,
    onRemoveSlotAt: ((Int) -> Unit)?,
) {
    val grid = quickLaunchAdaptiveGrid(maxWidth)
    val rowChunks = remember(slots, grid.columns, beforeAddAuxTiles) {
        buildQuickLaunchRowChunks(slots, grid.columns, beforeAddAuxTiles)
    }
    val displayRowChunks = remember(rowChunks, maxRows) {
        takeQuickLaunchDisplayRows(rowChunks, maxRows)
    }
    QuickLaunchWrappedRowDragSession(
        boxInRoot = boxInRoot,
        slots = slots,
        quickLaunchPackages = quickLaunchPackages,
        onQuickLaunchApp = onQuickLaunchApp,
        onAddQuickLaunch = onAddQuickLaunch,
        onMoveSlot = onMoveSlot,
        onMergeSlotInto = onMergeSlotInto,
        onRemoveSlot = onRemoveSlot,
        onOpenFolder = onOpenFolder,
        addTileContentDescription = addTileContentDescription,
        onAppSlotBounds = onAppSlotBounds,
        tileContent = tileContent,
        onRemoveSlotAt = onRemoveSlotAt,
        displayRowChunks = displayRowChunks,
        minCellWidth = grid.minCellWidth,
        horizontalGap = grid.horizontalGap,
        minGapPx = with(LocalDensity.current) { 8.dp.toPx() },
        barThicknessPx = with(LocalDensity.current) { 4.dp.toPx() },
        intentLongPressDragThresholdPx = with(LocalDensity.current) { 10.dp.toPx() },
    )
}

@Composable
private fun QuickLaunchWrappedRowDragSession(
    boxInRoot: Offset,
    slots: List<QuickLaunchSlotUi>,
    quickLaunchPackages: Set<String>,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    onAddQuickLaunch: () -> Unit,
    onMoveSlot: (from: Int, to: Int) -> Unit,
    onMergeSlotInto: (from: Int, into: Int) -> Unit,
    onRemoveSlot: (List<AppInfo>) -> Unit,
    onOpenFolder: (slotIndex: Int, apps: List<AppInfo>, folderName: String?, folderSymbolIconName: String?) -> Unit,
    addTileContentDescription: String,
    onAppSlotBounds: (slotIndex: Int, topLeft: Offset, size: Size) -> Unit,
    tileContent: QuickLaunchTileContent,
    onRemoveSlotAt: ((Int) -> Unit)?,
    displayRowChunks: List<List<QuickLaunchGridTile>>,
    minCellWidth: Dp,
    horizontalGap: Dp,
    minGapPx: Float,
    barThicknessPx: Float,
    intentLongPressDragThresholdPx: Float,
) {
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    val tileCoords = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
    var removeZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var lastPointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var mergeHoverSlot by remember { mutableStateOf<Int?>(null) }
    var gapBarRectRoot by remember { mutableStateOf<Rect?>(null) }
    var hoveringRemoveZone by remember { mutableStateOf(false) }
    var edgePreviewSticky by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var pendingIntentLongPressSlot by remember { mutableStateOf<Int?>(null) }
    var intentLongPressDragActivated by remember { mutableStateOf(false) }
    var suppressClickSlotIndex by remember { mutableStateOf<Int?>(null) }

    val draggedApps = draggingIndex?.let { idx -> slots.getOrNull(idx)?.apps }

    fun applyHoverUi(ui: QuickLaunchHoverUi) {
        mergeHoverSlot = ui.mergeHoverSlot
        gapBarRectRoot = ui.gapBarRectRoot
        hoveringRemoveZone = ui.hoveringRemoveZone
        edgePreviewSticky = ui.edgePreviewSticky
    }

    fun updateHoverState() {
        applyHoverUi(
            hoverUiFromPreview(
                resolveHoverPreview(
                    finger = lastPointerInRoot,
                    dragIdx = draggingIndex,
                    removeZoneBounds = removeZoneBounds,
                    slotBounds = slotBounds,
                    rowChunks = displayRowChunks,
                    minGapPx = minGapPx,
                    barThicknessPx = barThicknessPx,
                    edgePreviewSticky = edgePreviewSticky,
                ),
            ),
        )
    }

    fun resetDragSession() {
        draggingIndex = null
        mergeHoverSlot = null
        gapBarRectRoot = null
        hoveringRemoveZone = false
        edgePreviewSticky = null
        pendingIntentLongPressSlot = null
        intentLongPressDragActivated = false
    }

    val dragHost = QuickLaunchRowDragHost(
        tileCoords = tileCoords,
        slotBounds = slotBounds,
        onAppSlotBounds = onAppSlotBounds,
        onUpdateHover = { updateHoverState() },
        getDraggingIndex = { draggingIndex },
        setDraggingIndex = { draggingIndex = it },
        getLastPointerInRoot = { lastPointerInRoot },
        setLastPointerInRoot = { lastPointerInRoot = it },
        clearEdgePreviewSticky = { edgePreviewSticky = null },
        getPendingIntentLongPressSlot = { pendingIntentLongPressSlot },
        setPendingIntentLongPressSlot = { pendingIntentLongPressSlot = it },
        getIntentLongPressDragActivated = { intentLongPressDragActivated },
        setIntentLongPressDragActivated = { intentLongPressDragActivated = it },
        getSuppressClickSlotIndex = { suppressClickSlotIndex },
        setSuppressClickSlotIndex = { suppressClickSlotIndex = it },
        getHoveringRemoveZone = { hoveringRemoveZone },
        resetDragSession = { resetDragSession() },
        onOpenFolder = onOpenFolder,
        onResolvedDrop = { from, current, finger, shouldRemove ->
            handleWrappedRowDrop(
                from = from,
                current = current,
                finger = finger,
                shouldRemove = shouldRemove,
                displayRowChunks = displayRowChunks,
                slotBounds = slotBounds,
                minGapPx = minGapPx,
                onRemoveSlotAt = onRemoveSlotAt,
                onRemoveSlot = onRemoveSlot,
                onMergeSlotInto = onMergeSlotInto,
                onMoveSlot = onMoveSlot,
            )
        },
        intentLongPressDragThresholdPx = intentLongPressDragThresholdPx,
    )

    QuickLaunchWrappedRowOverlay(
        displayRowChunks = displayRowChunks,
        horizontalGap = horizontalGap,
        minCellWidth = minCellWidth,
        slots = slots,
        quickLaunchPackages = quickLaunchPackages,
        draggingIndex = draggingIndex,
        mergeHoverSlot = mergeHoverSlot,
        dragHost = dragHost,
        onQuickLaunchApp = onQuickLaunchApp,
        onAddQuickLaunch = onAddQuickLaunch,
        addTileContentDescription = addTileContentDescription,
        tileContent = tileContent,
        hoveringRemoveZone = hoveringRemoveZone,
        onRemoveZoneBounds = { removeZoneBounds = it },
        gapBarRectRoot = gapBarRectRoot,
        boxInRoot = boxInRoot,
        draggedApps = draggedApps,
        lastPointerInRoot = lastPointerInRoot,
    )
}

private fun handleWrappedRowDrop(
    from: Int,
    current: List<AppInfo>,
    finger: Offset,
    shouldRemove: Boolean,
    displayRowChunks: List<List<QuickLaunchGridTile>>,
    slotBounds: Map<Int, Rect>,
    minGapPx: Float,
    onRemoveSlotAt: ((Int) -> Unit)?,
    onRemoveSlot: (List<AppInfo>) -> Unit,
    onMergeSlotInto: (from: Int, into: Int) -> Unit,
    onMoveSlot: (from: Int, to: Int) -> Unit,
) {
    Log.d(
        QuickLaunchDragLogTag,
        "end from=$from finger=$finger remove=$shouldRemove bounds=${slotBounds.keys}",
    )
    val action = resolveDropAction(
        finger = finger,
        from = from,
        shouldRemove = shouldRemove,
        rowChunks = displayRowChunks,
        slotBounds = slotBounds,
        minGapPx = minGapPx,
    )
    dispatchDropAction(
        action = action,
        from = from,
        currentApps = current,
        onRemoveSlotAt = onRemoveSlotAt,
        onRemoveSlot = onRemoveSlot,
        onMergeSlotInto = onMergeSlotInto,
        onMoveSlot = onMoveSlot,
    )
}

@Composable
private fun QuickLaunchWrappedRowOverlay(
    displayRowChunks: List<List<QuickLaunchGridTile>>,
    horizontalGap: Dp,
    minCellWidth: Dp,
    slots: List<QuickLaunchSlotUi>,
    quickLaunchPackages: Set<String>,
    draggingIndex: Int?,
    mergeHoverSlot: Int?,
    dragHost: QuickLaunchRowDragHost,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    onAddQuickLaunch: () -> Unit,
    addTileContentDescription: String,
    tileContent: QuickLaunchTileContent,
    hoveringRemoveZone: Boolean,
    onRemoveZoneBounds: (Rect) -> Unit,
    gapBarRectRoot: Rect?,
    boxInRoot: Offset,
    draggedApps: List<AppInfo>?,
    lastPointerInRoot: Offset,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            displayRowChunks.forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(horizontalGap),
                ) {
                    rowTiles.forEach { tile ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            QuickLaunchGridCell(
                                tile = tile,
                                tileContent = tileContent,
                                minCellWidth = minCellWidth,
                                slots = slots,
                                quickLaunchPackages = quickLaunchPackages,
                                draggingIndex = draggingIndex,
                                mergeHoverSlot = mergeHoverSlot,
                                dragHost = dragHost,
                                onQuickLaunchApp = onQuickLaunchApp,
                                onAddQuickLaunch = onAddQuickLaunch,
                                addTileContentDescription = addTileContentDescription,
                            )
                        }
                    }
                }
            }
            if (draggingIndex != null) {
                QuickLaunchRemoveZone(
                    hovering = hoveringRemoveZone,
                    onBounds = onRemoveZoneBounds,
                )
            }
        }
        gapBarRectRoot?.let { bar ->
            GapInsertionBarOverlay(bar = bar, boxInRoot = boxInRoot, color = QuickLaunchGapBarYellow)
        }
        QuickLaunchDragGhostIfNeeded(
            draggingIndex = draggingIndex,
            draggedApps = draggedApps,
            lastPointerInRoot = lastPointerInRoot,
            boxInRoot = boxInRoot,
            tileContent = tileContent,
            slots = slots,
        )
    }
}

@Composable
private fun QuickLaunchDragGhostIfNeeded(
    draggingIndex: Int?,
    draggedApps: List<AppInfo>?,
    lastPointerInRoot: Offset,
    boxInRoot: Offset,
    tileContent: QuickLaunchTileContent,
    slots: List<QuickLaunchSlotUi>,
) {
    if (draggingIndex == null || draggedApps == null) return
    DragGhostOverlay(
        pointerInRoot = lastPointerInRoot,
        boxInRoot = boxInRoot,
        tileContent = tileContent,
        draggedApps = draggedApps,
        folderName = slots.getOrNull(draggingIndex)?.folderName,
        folderSymbolIconName = slots.getOrNull(draggingIndex)?.folderSymbolIconName,
    )
}

@Composable
private fun QuickLaunchGridCell(
    tile: QuickLaunchGridTile,
    tileContent: QuickLaunchTileContent,
    minCellWidth: Dp,
    slots: List<QuickLaunchSlotUi>,
    quickLaunchPackages: Set<String>,
    draggingIndex: Int?,
    mergeHoverSlot: Int?,
    dragHost: QuickLaunchRowDragHost,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
    onAddQuickLaunch: () -> Unit,
    addTileContentDescription: String,
) {
    if (tile.isPlaceholder) {
        Spacer(modifier = Modifier.width(minCellWidth).height(1.dp))
        return
    }
    tile.auxTile?.let {
        QuickLaunchAuxGridCell(it)
        return
    }
    if (tile.apps != null && tile.slotIndex != null) {
        QuickLaunchSlotGridCell(
            apps = tile.apps,
            slotIndex = tile.slotIndex,
            tile = tile,
            tileContent = tileContent,
            minCellWidth = minCellWidth,
            slots = slots,
            quickLaunchPackages = quickLaunchPackages,
            draggingIndex = draggingIndex,
            mergeHoverSlot = mergeHoverSlot,
            dragHost = dragHost,
            onQuickLaunchApp = onQuickLaunchApp,
        )
        return
    }
    if (tile.isAdd) {
        QuickLaunchAddCell(
            tileContent = tileContent,
            minCellWidth = minCellWidth,
            onAddQuickLaunch = onAddQuickLaunch,
            addTileContentDescription = addTileContentDescription,
        )
    }
}

@Composable
private fun QuickLaunchAuxGridCell(aux: QuickLaunchAuxTile) {
    val coachmarks = LocalCoachmarkController.current
    IntentLabelTile(
        label = aux.label,
        subtitle = aux.subtitle,
        onClick = aux.onClick,
        contentDescription = aux.contentDescription,
        modifier = aux.coachmarkId?.let { id ->
            Modifier.coachmarkTargetIf(coachmarks, id)
        } ?: Modifier,
    )
}

@Composable
private fun QuickLaunchSlotGridCell(
    apps: List<AppInfo>,
    slotIndex: Int,
    tile: QuickLaunchGridTile,
    tileContent: QuickLaunchTileContent,
    minCellWidth: Dp,
    slots: List<QuickLaunchSlotUi>,
    quickLaunchPackages: Set<String>,
    draggingIndex: Int?,
    mergeHoverSlot: Int?,
    dragHost: QuickLaunchRowDragHost,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>, limitMinutes: Int?) -> Unit,
) {
    val dropHighlight = draggingIndex != null &&
        mergeHoverSlot == slotIndex &&
        draggingIndex != slotIndex
    QuickLaunchTile(
        apps = apps,
        slotIndex = slotIndex,
        tile = tile,
        tileContent = tileContent,
        minCellWidth = minCellWidth,
        dropHighlight = dropHighlight,
        isDraggingThis = draggingIndex == slotIndex,
        quickLaunchPackages = quickLaunchPackages,
        slots = slots,
        dragHost = dragHost,
        onQuickLaunchApp = onQuickLaunchApp,
    )
}

@Composable
private fun QuickLaunchAddCell(
    tileContent: QuickLaunchTileContent,
    minCellWidth: Dp,
    onAddQuickLaunch: () -> Unit,
    addTileContentDescription: String,
) {
    if (tileContent == QuickLaunchTileContent.IntentLabels) {
        IntentLabelTile(
            label = "+",
            onClick = onAddQuickLaunch,
            contentDescription = addTileContentDescription,
        )
    } else {
        OutlinedButton(
            onClick = onAddQuickLaunch,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            modifier = Modifier.width(minCellWidth),
        ) {
            Icon(Icons.Default.Add, contentDescription = addTileContentDescription)
        }
    }
}
