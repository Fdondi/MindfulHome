package com.mindfulhome.ui.quicklaunch

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.model.AppInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Resolved QuickLaunch tile for UI (single app or folder) with [AppInfo] for icons/labels. */
data class QuickLaunchSlotUi(
    val apps: List<AppInfo>,
    val folderName: String? = null,
)

data class QuickLaunchFolderOpen(
    val slotIndex: Int,
    val apps: List<AppInfo>,
    val folderName: String?,
)

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickLaunchFolderBody(
    apps: List<AppInfo>,
    onLaunchApp: (AppInfo) -> Unit,
    onDragRemove: (AppInfo) -> Unit,
    onDragExtractToOwnSlot: (AppInfo) -> Unit,
    dragHintText: String = "Drop on → exit folder, or ✕ to remove from QuickLaunch",
    removeDropContentDescription: String = "Drop to remove from QuickLaunch",
) {
    val appCoords = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    var draggingPackage by remember { mutableStateOf<String?>(null) }
    var lastPointerInRoot by remember { mutableStateOf(Offset.Zero) }
    var removeZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var extractZoneBounds by remember { mutableStateOf<Rect?>(null) }
    var hoveringRemove by remember { mutableStateOf(false) }
    var hoveringExtract by remember { mutableStateOf(false) }

    fun appByPackage(pkg: String) = apps.firstOrNull { it.packageName == pkg }

    fun updateFolderHover() {
        val finger = lastPointerInRoot
        if (draggingPackage == null) {
            hoveringRemove = false
            hoveringExtract = false
            return
        }
        hoveringRemove = removeZoneBounds?.contains(finger) == true
        hoveringExtract = extractZoneBounds?.contains(finger) == true && !hoveringRemove
    }

    val draggedApp = draggingPackage?.let { appByPackage(it) }
    val minCell = 76.dp
    var folderBodyRoot by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { folderBodyRoot = it.positionInRoot() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            apps.chunked(4).forEach { rowApps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowApps.forEach { app ->
                        val pkg = app.packageName
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(minCell)
                                .pointerInput(pkg, apps) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { startLocal ->
                                            draggingPackage = pkg
                                            val coords = appCoords[pkg]
                                            lastPointerInRoot = coords?.localToRoot(startLocal)
                                                ?: Offset.Zero
                                            updateFolderHover()
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val coords = appCoords[pkg]
                                            if (coords != null) {
                                                lastPointerInRoot = coords.localToRoot(change.position)
                                            }
                                            updateFolderHover()
                                        },
                                        onDragCancel = {
                                            draggingPackage = null
                                            hoveringRemove = false
                                            hoveringExtract = false
                                        },
                                        onDragEnd = {
                                            val draggedPkg = draggingPackage
                                                ?: return@detectDragGesturesAfterLongPress
                                            val droppedApp = appByPackage(draggedPkg)
                                            draggingPackage = null
                                            hoveringRemove = false
                                            hoveringExtract = false
                                            if (droppedApp == null) return@detectDragGesturesAfterLongPress
                                            when {
                                                removeZoneBounds?.contains(lastPointerInRoot) == true ->
                                                    onDragRemove(droppedApp)
                                                extractZoneBounds?.contains(lastPointerInRoot) == true ->
                                                    onDragExtractToOwnSlot(droppedApp)
                                                else -> { /* no drop */ }
                                            }
                                        },
                                    )
                                }
                                .combinedClickable(
                                    onClick = {
                                        if (draggingPackage != null) return@combinedClickable
                                        onLaunchApp(app)
                                    },
                                ),
                        ) {
                            Box(
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    appCoords[pkg] = coords
                                    if (draggingPackage != null) updateFolderHover()
                                },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (app.icon != null) {
                                        Image(
                                            painter = rememberDrawablePainter(app.icon),
                                            contentDescription = app.label,
                                            modifier = Modifier
                                                .size(42.dp)
                                                .then(
                                                    if (draggingPackage == pkg) Modifier.alpha(0.22f) else Modifier,
                                                ),
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(42.dp))
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = app.label,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (draggingPackage != null) {
                Text(
                    text = dragHintText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                extractZoneBounds = Rect(
                                    coords.positionInRoot(),
                                    Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                                )
                                if (draggingPackage != null) updateFolderHover()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (hoveringExtract) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Drop to move out of folder",
                                tint = if (hoveringExtract) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                removeZoneBounds = Rect(
                                    coords.positionInRoot(),
                                    Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                                )
                                if (draggingPackage != null) updateFolderHover()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (hoveringRemove) Color.Red else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = removeDropContentDescription,
                                tint = if (hoveringRemove) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (draggedApp != null && draggingPackage != null) {
            val rel = lastPointerInRoot - folderBodyRoot - Offset(30f, 30f)
            Box(
                Modifier.offset { IntOffset(rel.x.roundToInt(), rel.y.roundToInt()) },
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (draggedApp.icon != null) {
                        Image(
                            painter = rememberDrawablePainter(draggedApp.icon),
                            contentDescription = draggedApp.label,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class QuickLaunchGridTile(
    val apps: List<AppInfo>? = null,
    val folderName: String? = null,
    val slotIndex: Int? = null,
    val isAdd: Boolean = false,
    val isPlaceholder: Boolean = false,
)

private fun findGapInsertionBarRect(
    finger: Offset,
    rowChunks: List<List<QuickLaunchGridTile>>,
    slotBounds: Map<Int, Rect>,
    minGapPx: Float,
    barThicknessPx: Float,
): Rect? {
    for (row in rowChunks) {
        val slotIndices = row.mapNotNull { it.slotIndex }
        for (i in 0 until slotIndices.size - 1) {
            val left = slotIndices[i]
            val right = slotIndices[i + 1]
            val rl = slotBounds[left] ?: continue
            val rr = slotBounds[right] ?: continue
            val gap = quickLaunchHorizontalGapRect(rl, rr, minGapPx)
            if (gap.contains(finger)) {
                return horizontalGapInsertionBarRect(rl, rr, minGapPx, barThicknessPx)
            }
        }
    }
    for (rowIdx in 0 until rowChunks.size - 1) {
        val bottomSlots = rowChunks[rowIdx].mapNotNull { it.slotIndex }
        val topSlots = rowChunks[rowIdx + 1].mapNotNull { it.slotIndex }
        val bottomLast = bottomSlots.lastOrNull() ?: continue
        val topFirst = topSlots.firstOrNull() ?: continue
        val rb = slotBounds[bottomLast] ?: continue
        val rt = slotBounds[topFirst] ?: continue
        val vGap = quickLaunchVerticalGapRect(rb, rt, minGapPx)
        if (vGap.contains(finger)) {
            return verticalGapInsertionBarRect(rb, rt, minGapPx, barThicknessPx)
        }
    }
    return null
}

private const val QuickLaunchDragLogTag = "QuickLaunchDrag"

/** Insert-between preview (matches gap drop zone). */
private val QuickLaunchGapBarYellow = Color(0xFFEAB308)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickLaunchWrappedRow(
    slots: List<QuickLaunchSlotUi>,
    quickLaunchPackages: Set<String>,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>) -> Unit,
    onAddQuickLaunch: () -> Unit,
    onMoveSlot: (from: Int, to: Int) -> Unit,
    onMergeSlotInto: (from: Int, into: Int) -> Unit,
    onRemoveSlot: (List<AppInfo>) -> Unit,
    onOpenFolder: (slotIndex: Int, apps: List<AppInfo>, folderName: String?) -> Unit,
    addTileContentDescription: String = "Add QuickLaunch app",
    /** Reports each app-tile layout in root coordinates for external hit-testing (e.g. grid drag-and-drop). */
    onAppSlotBounds: (slotIndex: Int, topLeft: Offset, size: Size) -> Unit = { _, _, _ -> },
    /** If non-null, only the first [maxRows] grid rows are shown (e.g. pull-tab collapsed to one row). */
    maxRows: Int? = null,
) {
    var boxInRoot by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                boxInRoot = coords.positionInRoot()
            },
    ) {
        val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
        val tileCoords = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
        var removeZoneBounds by remember { mutableStateOf<Rect?>(null) }
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var lastPointerInRoot by remember { mutableStateOf(Offset.Zero) }
        var mergeHoverSlot by remember { mutableStateOf<Int?>(null) }
        var gapBarRectRoot by remember { mutableStateOf<Rect?>(null) }
        var hoveringRemoveZone by remember { mutableStateOf(false) }
        /** Edge reorder preview: (slotIndex, insertBefore) — sticky in middle band to avoid bar jitter. */
        var edgePreviewSticky by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

        val minGapPx = with(LocalDensity.current) { 8.dp.toPx() }
        val barThicknessPx = with(LocalDensity.current) { 4.dp.toPx() }
        val density = LocalDensity.current

        val minCellWidth = 74.dp
        val columns = (maxWidth / minCellWidth).toInt().coerceAtLeast(1)
        val horizontalGap = if (columns > 1) {
            ((maxWidth - (minCellWidth * columns)) / (columns - 1)).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        val rowChunks = remember(slots, columns) {
            val base = (
                slots.mapIndexed { index, slot ->
                    QuickLaunchGridTile(
                        apps = slot.apps,
                        folderName = slot.folderName,
                        slotIndex = index,
                    )
                } + QuickLaunchGridTile(isAdd = true)
                ).chunked(columns)
            base.map { row ->
                if (row.size >= columns) {
                    row
                } else {
                    row + List(columns - row.size) { QuickLaunchGridTile(isPlaceholder = true) }
                }
            }
        }
        val displayRowChunks = remember(rowChunks, maxRows) {
            if (maxRows != null) rowChunks.take(maxRows.coerceAtLeast(1)) else rowChunks
        }
        val draggedApps = draggingIndex?.let { idx -> slots.getOrNull(idx)?.apps }

        fun updateHoverState() {
            val finger = lastPointerInRoot
            val dragIdx = draggingIndex
            if (dragIdx == null) {
                mergeHoverSlot = null
                gapBarRectRoot = null
                hoveringRemoveZone = false
                edgePreviewSticky = null
                return
            }
            if (removeZoneBounds?.contains(finger) == true) {
                mergeHoverSlot = null
                gapBarRectRoot = null
                hoveringRemoveZone = true
                edgePreviewSticky = null
                return
            }
            hoveringRemoveZone = false
            val mergeSlot = slotBounds.entries
                .asSequence()
                .filter { it.key != dragIdx }
                .firstOrNull { (_, rect) -> quickLaunchMergeZoneRect(rect).contains(finger) }
                ?.key
            if (mergeSlot != null) {
                mergeHoverSlot = mergeSlot
                gapBarRectRoot = null
                edgePreviewSticky = null
                return
            }
            mergeHoverSlot = null
            val gapBar = findGapInsertionBarRect(
                finger = finger,
                rowChunks = displayRowChunks,
                slotBounds = slotBounds,
                minGapPx = minGapPx,
                barThicknessPx = barThicknessPx,
            )
            if (gapBar != null) {
                gapBarRectRoot = gapBar
                edgePreviewSticky = null
                return
            }
            var edgeBar: Rect? = null
            for ((idx, rect) in slotBounds) {
                if (idx == dragIdx) continue
                if (!rect.contains(finger)) continue
                if (quickLaunchMergeZoneRect(rect).contains(finger)) continue
                val w = rect.width.coerceAtLeast(1f)
                val rel = (finger.x - rect.left) / w
                val midX = rect.center.x
                val before = when {
                    rel <= 0.42f -> true
                    rel >= 0.58f -> false
                    else -> edgePreviewSticky?.takeIf { it.first == idx }?.second ?: (finger.x < midX)
                }
                edgePreviewSticky = idx to before
                edgeBar = if (before) {
                    Rect(rect.left, rect.top, rect.left + barThicknessPx, rect.bottom)
                } else {
                    Rect(rect.right - barThicknessPx, rect.top, rect.right, rect.bottom)
                }
                break
            }
            if (edgeBar == null) edgePreviewSticky = null
            gapBarRectRoot = edgeBar
        }

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
                                val apps = tile.apps
                                val slotIndex = tile.slotIndex
                                when {
                                    tile.isPlaceholder -> {
                                        Spacer(
                                            modifier = Modifier
                                                .width(minCellWidth)
                                                .height(1.dp),
                                        )
                                    }
                                    apps != null && slotIndex != null -> {
                                        val folderLabel = tile.folderName?.takeIf { it.isNotBlank() }
                                            ?: "Folder (${apps.size})"
                                        val dropHighlight = draggingIndex != null &&
                                            mergeHoverSlot == slotIndex &&
                                            draggingIndex != slotIndex
                                        val borderMod = if (dropHighlight) {
                                            Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(10.dp),
                                            )
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
                                                            tileCoords[slotIndex] = coords
                                                            val rootPos = coords.positionInRoot()
                                                            val sz = Size(
                                                                coords.size.width.toFloat(),
                                                                coords.size.height.toFloat(),
                                                            )
                                                            slotBounds[slotIndex] = Rect(rootPos, sz)
                                                            onAppSlotBounds(slotIndex, rootPos, sz)
                                                            if (draggingIndex != null) updateHoverState()
                                                        }
                                                        .pointerInput(slotIndex, slots) {
                                                            detectDragGesturesAfterLongPress(
                                                                onDragStart = { startLocal ->
                                                                    edgePreviewSticky = null
                                                                    draggingIndex = slotIndex
                                                                    val coords = tileCoords[slotIndex]
                                                                    lastPointerInRoot = coords?.localToRoot(startLocal)
                                                                        ?: slotBounds[slotIndex]?.center
                                                                        ?: Offset.Zero
                                                                    Log.d(
                                                                        QuickLaunchDragLogTag,
                                                                        "start slot=$slotIndex root=$lastPointerInRoot",
                                                                    )
                                                                    updateHoverState()
                                                                },
                                                                onDrag = { change, _ ->
                                                                    change.consume()
                                                                    val coords = tileCoords[slotIndex]
                                                                    if (coords != null) {
                                                                        lastPointerInRoot =
                                                                            coords.localToRoot(change.position)
                                                                    }
                                                                    updateHoverState()
                                                                },
                                                                onDragCancel = {
                                                                    Log.d(QuickLaunchDragLogTag, "cancel")
                                                                    draggingIndex = null
                                                                    mergeHoverSlot = null
                                                                    gapBarRectRoot = null
                                                                    hoveringRemoveZone = false
                                                                    edgePreviewSticky = null
                                                                },
                                                                onDragEnd = {
                                                                    val from = draggingIndex
                                                                    val finger = lastPointerInRoot
                                                                    val current = from?.let { slots.getOrNull(it)?.apps }
                                                                    val shouldRemove = hoveringRemoveZone
                                                                    val intoHover = mergeHoverSlot
                                                                    draggingIndex = null
                                                                    mergeHoverSlot = null
                                                                    gapBarRectRoot = null
                                                                    hoveringRemoveZone = false
                                                                    edgePreviewSticky = null
                                                                    if (from == null || current == null) {
                                                                        Log.d(
                                                                            QuickLaunchDragLogTag,
                                                                            "end aborted from=$from",
                                                                        )
                                                                        return@detectDragGesturesAfterLongPress
                                                                    }
                                                                    Log.d(
                                                                        QuickLaunchDragLogTag,
                                                                        "end from=$from finger=$finger remove=$shouldRemove hoverInto=$intoHover bounds=${slotBounds.keys}",
                                                                    )
                                                                    if (shouldRemove) {
                                                                        onRemoveSlot(current)
                                                                        return@detectDragGesturesAfterLongPress
                                                                    }
                                                                    for (row in displayRowChunks) {
                                                                        val slotIndices = row.mapNotNull { it.slotIndex }
                                                                        for (i in 0 until slotIndices.size - 1) {
                                                                            val left = slotIndices[i]
                                                                            val right = slotIndices[i + 1]
                                                                            val rl = slotBounds[left] ?: continue
                                                                            val rr = slotBounds[right] ?: continue
                                                                            val gap = quickLaunchHorizontalGapRect(rl, rr, minGapPx)
                                                                            if (gap.contains(finger)) {
                                                                                val to = insertIndexBeforeRight(from, right)
                                                                                if (to != from) {
                                                                                    Log.d(
                                                                                        QuickLaunchDragLogTag,
                                                                                        "gapH $from -> $to (before $right)",
                                                                                    )
                                                                                    onMoveSlot(from, to)
                                                                                }
                                                                                return@detectDragGesturesAfterLongPress
                                                                            }
                                                                        }
                                                                    }
                                                                    for (rowIdx in 0 until displayRowChunks.size - 1) {
                                                                        val bottomSlots =
                                                                            displayRowChunks[rowIdx].mapNotNull { it.slotIndex }
                                                                        val topSlots =
                                                                            displayRowChunks[rowIdx + 1].mapNotNull { it.slotIndex }
                                                                        val bottomLast = bottomSlots.lastOrNull() ?: continue
                                                                        val topFirst = topSlots.firstOrNull() ?: continue
                                                                        val rb = slotBounds[bottomLast] ?: continue
                                                                        val rt = slotBounds[topFirst] ?: continue
                                                                        val vGap = quickLaunchVerticalGapRect(rb, rt, minGapPx)
                                                                        if (vGap.contains(finger)) {
                                                                            val to = insertIndexBeforeRight(from, topFirst)
                                                                            if (to != from) {
                                                                                Log.d(
                                                                                    QuickLaunchDragLogTag,
                                                                                    "gapV $from -> $to (before $topFirst)",
                                                                                )
                                                                                onMoveSlot(from, to)
                                                                            }
                                                                            return@detectDragGesturesAfterLongPress
                                                                        }
                                                                    }
                                                                    val overlapTarget = slotBounds.entries
                                                                        .asSequence()
                                                                        .filter { it.key != from }
                                                                        .firstOrNull { (_, rect) -> rect.contains(finger) }
                                                                        ?.key
                                                                    if (overlapTarget != null) {
                                                                        val full = slotBounds[overlapTarget]!!
                                                                        if (quickLaunchMergeZoneRect(full).contains(finger)) {
                                                                            Log.d(
                                                                                QuickLaunchDragLogTag,
                                                                                "merge $from -> $overlapTarget",
                                                                            )
                                                                            onMergeSlotInto(from, overlapTarget)
                                                                            return@detectDragGesturesAfterLongPress
                                                                        }
                                                                        val to = if (finger.x < full.center.x) {
                                                                            insertIndexBeforeRight(from, overlapTarget)
                                                                        } else {
                                                                            insertIndexAfterSlot(from, overlapTarget)
                                                                        }
                                                                        if (to != from) {
                                                                            Log.d(
                                                                                QuickLaunchDragLogTag,
                                                                                "edgeReorder $from -> $to (slot $overlapTarget)",
                                                                            )
                                                                            onMoveSlot(from, to)
                                                                        }
                                                                        return@detectDragGesturesAfterLongPress
                                                                    }
                                                                    val closest = slotBounds.entries
                                                                        .asSequence()
                                                                        .filter { it.key != from }
                                                                        .minByOrNull { entry ->
                                                                            val c = entry.value.center
                                                                            val dx = c.x - finger.x
                                                                            val dy = c.y - finger.y
                                                                            dx * dx + dy * dy
                                                                        }
                                                                        ?.key
                                                                    if (closest != null) {
                                                                        Log.d(
                                                                            QuickLaunchDragLogTag,
                                                                            "move $from -> nearest $closest",
                                                                        )
                                                                        onMoveSlot(from, closest)
                                                                    } else {
                                                                        Log.d(
                                                                            QuickLaunchDragLogTag,
                                                                            "end no target",
                                                                        )
                                                                    }
                                                                },
                                                            )
                                                        }
                                                        .combinedClickable(
                                                            onClick = {
                                                                if (draggingIndex != null) return@combinedClickable
                                                                when {
                                                                    apps.size > 1 -> onOpenFolder(
                                                                        slotIndex,
                                                                        apps,
                                                                        tile.folderName,
                                                                    )
                                                                    else -> onQuickLaunchApp(
                                                                        apps.single().packageName,
                                                                        quickLaunchPackages,
                                                                    )
                                                                }
                                                            },
                                                        )
                                                        .then(
                                                            if (draggingIndex == slotIndex) Modifier.alpha(0.18f) else Modifier,
                                                        ),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                ) {
                                                    if (apps.size == 1) {
                                                        val app = apps.single()
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
                                                    } else {
                                                        Icon(
                                                            Icons.Default.Folder,
                                                            contentDescription = folderLabel,
                                                            modifier = Modifier.size(42.dp),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = folderLabel,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    tile.isAdd -> {
                                        OutlinedButton(
                                            onClick = onAddQuickLaunch,
                                            border = BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                            ),
                                            modifier = Modifier.width(minCellWidth),
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = addTileContentDescription)
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
                if (draggingIndex != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .onGloballyPositioned { coords ->
                                removeZoneBounds = Rect(
                                    coords.positionInRoot(),
                                    Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    if (hoveringRemoveZone) Color.Red else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Drop to remove",
                                tint = if (hoveringRemoveZone) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            gapBarRectRoot?.let { bar ->
                val ox = bar.left - boxInRoot.x
                val oy = bar.top - boxInRoot.y
                Box(
                    Modifier
                        .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                        .size(
                            width = with(density) { bar.width.toDp() },
                            height = with(density) { bar.height.toDp() },
                        )
                        .background(QuickLaunchGapBarYellow, RoundedCornerShape(3.dp)),
                )
            }
            if (draggingIndex != null && draggedApps != null) {
                val topLeft = lastPointerInRoot - boxInRoot - Offset(30f, 30f)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                        .size(60.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (draggedApps.size > 1) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    } else {
                        val app = draggedApps.first()
                        if (app.icon != null) {
                            Image(
                                painter = rememberDrawablePainter(app.icon),
                                contentDescription = app.label,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
