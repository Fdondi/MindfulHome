package com.mindfulhome.ui.quicklaunch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mindfulhome.model.AppInfo
import kotlin.math.hypot

/** Grid cell for QuickLaunch layout (app slot, aux, add, or placeholder). */
internal data class QuickLaunchGridTile(
    val apps: List<AppInfo>? = null,
    val folderName: String? = null,
    val folderSymbolIconName: String? = null,
    val limitMinutesByPackage: Map<String, Int> = emptyMap(),
    val isAdd: Boolean = false,
    val isPlaceholder: Boolean = false,
    val slotIndex: Int? = null,
    val auxTile: QuickLaunchAuxTile? = null,
)

sealed class QuickLaunchHoverPreview {
    data object Clear : QuickLaunchHoverPreview()
    data object RemoveZone : QuickLaunchHoverPreview()
    data class Merge(val slotIndex: Int) : QuickLaunchHoverPreview()
    data class GapOrEdgeBar(
        val barRect: Rect,
        val edgeSticky: Pair<Int, Boolean>?,
    ) : QuickLaunchHoverPreview()
}

sealed class QuickLaunchDropAction {
    data object None : QuickLaunchDropAction()
    data object Remove : QuickLaunchDropAction()
    data class Move(val toIndex: Int) : QuickLaunchDropAction()
    data class Merge(val intoIndex: Int) : QuickLaunchDropAction()
}

sealed class QuickLaunchSlotClickAction {
    data object LaunchSingle : QuickLaunchSlotClickAction()
    data object OpenFolder : QuickLaunchSlotClickAction()
}

sealed class FolderDropAction {
    data object None : FolderDropAction()
    data object Remove : FolderDropAction()
    data object Secondary : FolderDropAction()
}

internal fun buildQuickLaunchRowChunks(
    slots: List<QuickLaunchSlotUi>,
    columns: Int,
    beforeAddAuxTiles: List<QuickLaunchAuxTile> = emptyList(),
): List<List<QuickLaunchGridTile>> {
    val base = slots.mapIndexed { index, slot ->
        QuickLaunchGridTile(
            apps = slot.apps,
            folderName = slot.folderName,
            folderSymbolIconName = slot.folderSymbolIconName,
            limitMinutesByPackage = slot.limitMinutesByPackage,
            slotIndex = index,
        )
    } + beforeAddAuxTiles.map { QuickLaunchGridTile(auxTile = it) } + listOf(
        QuickLaunchGridTile(isAdd = true),
    )
    return base.chunked(columns).map { row ->
        if (row.size >= columns) {
            row
        } else {
            row + List(columns - row.size) { QuickLaunchGridTile(isPlaceholder = true) }
        }
    }
}

internal fun resolveEdgeInsertSide(
    relX: Float,
    stickyBefore: Boolean?,
    fingerLeftOfMid: Boolean,
): Boolean = when {
    relX <= 0.42f -> true
    relX >= 0.58f -> false
    else -> stickyBefore ?: fingerLeftOfMid
}

internal fun shouldActivateIntentDrag(
    accumulated: Offset,
    thresholdPx: Float,
): Boolean = hypot(accumulated.x.toDouble(), accumulated.y.toDouble()) >= thresholdPx

internal fun resolveSlotClick(
    tileContent: QuickLaunchTileContent,
    appCount: Int,
): QuickLaunchSlotClickAction = when {
    tileContent == QuickLaunchTileContent.IntentLabels && appCount == 1 ->
        QuickLaunchSlotClickAction.LaunchSingle
    tileContent == QuickLaunchTileContent.IntentLabels || appCount > 1 ->
        QuickLaunchSlotClickAction.OpenFolder
    else -> QuickLaunchSlotClickAction.LaunchSingle
}

internal fun resolveFolderDrop(
    finger: Offset,
    removeBounds: Rect?,
    secondaryBounds: Rect?,
): FolderDropAction = when {
    removeBounds?.contains(finger) == true -> FolderDropAction.Remove
    secondaryBounds?.contains(finger) == true -> FolderDropAction.Secondary
    else -> FolderDropAction.None
}

internal fun resolveFolderHoverFlags(
    finger: Offset,
    dragging: Boolean,
    removeBounds: Rect?,
    secondaryBounds: Rect?,
): Pair<Boolean, Boolean> {
    if (!dragging) return false to false
    val hoveringRemove = removeBounds?.contains(finger) == true
    val hoveringSecondary = secondaryBounds?.contains(finger) == true && !hoveringRemove
    return hoveringRemove to hoveringSecondary
}

/**
 * Hover preview while dragging: remove zone > merge > gap bar > edge insert bar.
 */
internal fun resolveHoverPreview(
    finger: Offset,
    dragIdx: Int?,
    removeZoneBounds: Rect?,
    slotBounds: Map<Int, Rect>,
    rowChunks: List<List<QuickLaunchGridTile>>,
    minGapPx: Float,
    barThicknessPx: Float,
    edgePreviewSticky: Pair<Int, Boolean>?,
): QuickLaunchHoverPreview {
    if (dragIdx == null) return QuickLaunchHoverPreview.Clear
    hoverRemoveZone(finger, removeZoneBounds)?.let { return it }
    hoverMergeSlot(finger, dragIdx, slotBounds)?.let { return it }
    hoverGapBar(finger, rowChunks, slotBounds, minGapPx, barThicknessPx)?.let { return it }
    return hoverEdgeBar(finger, dragIdx, slotBounds, barThicknessPx, edgePreviewSticky)
}

internal fun hoverRemoveZone(finger: Offset, removeZoneBounds: Rect?): QuickLaunchHoverPreview? =
    if (removeZoneBounds?.contains(finger) == true) QuickLaunchHoverPreview.RemoveZone else null

internal fun hoverMergeSlot(
    finger: Offset,
    dragIdx: Int,
    slotBounds: Map<Int, Rect>,
): QuickLaunchHoverPreview? {
    val mergeSlot = slotBounds.entries
        .asSequence()
        .filter { it.key != dragIdx }
        .firstOrNull { (_, rect) -> quickLaunchMergeZoneRect(rect).contains(finger) }
        ?.key
    return mergeSlot?.let { QuickLaunchHoverPreview.Merge(it) }
}

internal fun hoverGapBar(
    finger: Offset,
    rowChunks: List<List<QuickLaunchGridTile>>,
    slotBounds: Map<Int, Rect>,
    minGapPx: Float,
    barThicknessPx: Float,
): QuickLaunchHoverPreview? {
    val gapBar = findGapInsertionBarRect(
        finger = finger,
        rowChunks = rowChunks,
        slotBounds = slotBounds,
        minGapPx = minGapPx,
        barThicknessPx = barThicknessPx,
    ) ?: return null
    return QuickLaunchHoverPreview.GapOrEdgeBar(gapBar, edgeSticky = null)
}

internal fun hoverEdgeBar(
    finger: Offset,
    dragIdx: Int,
    slotBounds: Map<Int, Rect>,
    barThicknessPx: Float,
    edgePreviewSticky: Pair<Int, Boolean>?,
): QuickLaunchHoverPreview {
    for ((idx, rect) in slotBounds) {
        if (idx == dragIdx) continue
        if (!rect.contains(finger)) continue
        if (quickLaunchMergeZoneRect(rect).contains(finger)) continue
        val w = rect.width.coerceAtLeast(1f)
        val rel = (finger.x - rect.left) / w
        val midX = rect.center.x
        val stickyForIdx = edgePreviewSticky?.takeIf { it.first == idx }?.second
        val before = resolveEdgeInsertSide(rel, stickyForIdx, finger.x < midX)
        val edgeBar = if (before) {
            Rect(rect.left, rect.top, rect.left + barThicknessPx, rect.bottom)
        } else {
            Rect(rect.right - barThicknessPx, rect.top, rect.right, rect.bottom)
        }
        return QuickLaunchHoverPreview.GapOrEdgeBar(edgeBar, idx to before)
    }
    return QuickLaunchHoverPreview.Clear
}


/**
 * Horizontal then vertical gap hits. Returns null when finger is not in any gap.
 * When a gap is hit but the computed index equals [from], returns [QuickLaunchDropAction.None]
 * (does not fall through to overlap/nearest).
 */
internal fun resolveGapDrop(
    finger: Offset,
    from: Int,
    rowChunks: List<List<QuickLaunchGridTile>>,
    slotBounds: Map<Int, Rect>,
    minGapPx: Float,
): QuickLaunchDropAction? {
    for (row in rowChunks) {
        val slotIndices = row.mapNotNull { it.slotIndex }
        for (i in 0 until slotIndices.size - 1) {
            val left = slotIndices[i]
            val right = slotIndices[i + 1]
            val rl = slotBounds[left] ?: continue
            val rr = slotBounds[right] ?: continue
            val gap = quickLaunchHorizontalGapRect(rl, rr, minGapPx)
            if (gap.contains(finger)) {
                val to = insertIndexBeforeRight(from, right)
                return if (to != from) QuickLaunchDropAction.Move(to) else QuickLaunchDropAction.None
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
            val to = insertIndexBeforeRight(from, topFirst)
            return if (to != from) QuickLaunchDropAction.Move(to) else QuickLaunchDropAction.None
        }
    }
    return null
}

/**
 * Finger inside another slot: merge zone → Merge; else edge insert Move/None.
 * Returns null when finger is not inside any other slot.
 */
internal fun resolveOverlapDrop(
    finger: Offset,
    from: Int,
    slotBounds: Map<Int, Rect>,
): QuickLaunchDropAction? {
    val overlapTarget = slotBounds.entries
        .asSequence()
        .filter { it.key != from }
        .firstOrNull { (_, rect) -> rect.contains(finger) }
        ?.key
        ?: return null
    val full = slotBounds[overlapTarget] ?: return null
    if (quickLaunchMergeZoneRect(full).contains(finger)) {
        return QuickLaunchDropAction.Merge(overlapTarget)
    }
    val to = if (finger.x < full.center.x) {
        insertIndexBeforeRight(from, overlapTarget)
    } else {
        insertIndexAfterSlot(from, overlapTarget)
    }
    return if (to != from) QuickLaunchDropAction.Move(to) else QuickLaunchDropAction.None
}

/** Nearest other slot center, or None when no other slots exist. */
internal fun resolveNearestDrop(
    finger: Offset,
    from: Int,
    slotBounds: Map<Int, Rect>,
): QuickLaunchDropAction {
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
    return if (closest != null) {
        QuickLaunchDropAction.Move(closest)
    } else {
        QuickLaunchDropAction.None
    }
}

/**
 * Drop resolution on drag end. Priority: remove > H/V gaps > merge/edge on overlap > nearest.
 */
internal fun resolveDropAction(
    finger: Offset,
    from: Int,
    shouldRemove: Boolean,
    rowChunks: List<List<QuickLaunchGridTile>>,
    slotBounds: Map<Int, Rect>,
    minGapPx: Float,
): QuickLaunchDropAction {
    if (shouldRemove) return QuickLaunchDropAction.Remove
    resolveGapDrop(finger, from, rowChunks, slotBounds, minGapPx)?.let { return it }
    resolveOverlapDrop(finger, from, slotBounds)?.let { return it }
    return resolveNearestDrop(finger, from, slotBounds)
}

/** UI fields derived from [resolveHoverPreview] for the wrapped-row drag overlay. */
internal data class QuickLaunchHoverUi(
    val mergeHoverSlot: Int? = null,
    val gapBarRectRoot: Rect? = null,
    val hoveringRemoveZone: Boolean = false,
    val edgePreviewSticky: Pair<Int, Boolean>? = null,
)

internal fun hoverUiFromPreview(preview: QuickLaunchHoverPreview): QuickLaunchHoverUi = when (preview) {
    QuickLaunchHoverPreview.Clear -> QuickLaunchHoverUi()
    QuickLaunchHoverPreview.RemoveZone -> QuickLaunchHoverUi(hoveringRemoveZone = true)
    is QuickLaunchHoverPreview.Merge -> QuickLaunchHoverUi(mergeHoverSlot = preview.slotIndex)
    is QuickLaunchHoverPreview.GapOrEdgeBar -> QuickLaunchHoverUi(
        gapBarRectRoot = preview.barRect,
        edgePreviewSticky = preview.edgeSticky,
    )
}

internal fun dispatchDropAction(
    action: QuickLaunchDropAction,
    from: Int,
    currentApps: List<AppInfo>,
    onRemoveSlotAt: ((Int) -> Unit)?,
    onRemoveSlot: (List<AppInfo>) -> Unit,
    onMergeSlotInto: (Int, Int) -> Unit,
    onMoveSlot: (Int, Int) -> Unit,
) {
    when (action) {
        QuickLaunchDropAction.Remove -> {
            if (onRemoveSlotAt != null) onRemoveSlotAt(from) else onRemoveSlot(currentApps)
        }
        is QuickLaunchDropAction.Merge -> onMergeSlotInto(from, action.intoIndex)
        is QuickLaunchDropAction.Move -> onMoveSlot(from, action.toIndex)
        QuickLaunchDropAction.None -> Unit
    }
}

internal fun dispatchFolderDrop(
    action: FolderDropAction,
    app: AppInfo,
    useEditTimerDrop: Boolean,
    onDragRemove: (AppInfo) -> Unit,
    onEditAppLimit: ((AppInfo) -> Unit)?,
    onDragExtractToOwnSlot: (AppInfo) -> Unit,
) {
    when (action) {
        FolderDropAction.Remove -> onDragRemove(app)
        FolderDropAction.Secondary -> {
            if (useEditTimerDrop) {
                onEditAppLimit?.invoke(app)
            } else {
                onDragExtractToOwnSlot(app)
            }
        }
        FolderDropAction.None -> Unit
    }
}

/** Long-press without drag on an intent tile opens the folder. */
internal fun shouldOpenFolderOnIntentLongPressEnd(
    tileContent: QuickLaunchTileContent,
    pendingSlot: Int?,
    slotIndex: Int,
    dragActivated: Boolean,
): Boolean =
    tileContent == QuickLaunchTileContent.IntentLabels &&
        pendingSlot == slotIndex &&
        !dragActivated

/** Whether intent-tile long-press should begin tracking (vs immediate drag for app icons). */
internal fun isIntentLongPressMode(tileContent: QuickLaunchTileContent): Boolean =
    tileContent == QuickLaunchTileContent.IntentLabels

/**
 * During intent long-press drag: activate when threshold crossed, else keep updating hover.
 * Returns true when [draggingIndex] should be set to [slotIndex] (first activation).
 */
internal fun shouldActivatePendingIntentDrag(
    tileContent: QuickLaunchTileContent,
    pendingSlot: Int?,
    slotIndex: Int,
    alreadyActivated: Boolean,
    accumulated: Offset,
    thresholdPx: Float,
): Boolean =
    isIntentLongPressMode(tileContent) &&
        pendingSlot == slotIndex &&
        !alreadyActivated &&
        shouldActivateIntentDrag(accumulated, thresholdPx)

/** What the tile drag handler should do on each move after long-press. */
internal enum class TileDragStep {
    None,
    UpdateHover,
    ActivateAndHover,
}

internal fun resolveTileDragStep(
    tileContent: QuickLaunchTileContent,
    pendingSlot: Int?,
    slotIndex: Int,
    intentDragActivated: Boolean,
    accumulated: Offset,
    thresholdPx: Float,
    isDragging: Boolean,
): TileDragStep {
    if (isIntentLongPressMode(tileContent) && pendingSlot == slotIndex) {
        return when {
            shouldActivatePendingIntentDrag(
                tileContent, pendingSlot, slotIndex, intentDragActivated, accumulated, thresholdPx,
            ) -> TileDragStep.ActivateAndHover
            intentDragActivated -> TileDragStep.UpdateHover
            else -> TileDragStep.None
        }
    }
    return if (isDragging) TileDragStep.UpdateHover else TileDragStep.None
}

internal fun folderLabelForTile(
    tileContent: QuickLaunchTileContent,
    folderName: String?,
    appCount: Int,
): String =
    folderName?.takeIf { it.isNotBlank() }
        ?: if (tileContent == QuickLaunchTileContent.IntentLabels) {
            "Unnamed"
        } else {
            "Folder ($appCount)"
        }

internal fun ghostLabelForDrag(
    tileContent: QuickLaunchTileContent,
    folderName: String?,
): String =
    if (tileContent == QuickLaunchTileContent.IntentLabels) {
        folderName?.takeIf { it.isNotBlank() } ?: "Intent"
    } else {
        folderName.orEmpty()
    }


/** Gap insertion preview bar (horizontal gaps, then vertical wrap gaps). */
internal fun findGapInsertionBarRect(
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

internal fun takeQuickLaunchDisplayRows(
    rowChunks: List<List<QuickLaunchGridTile>>,
    maxRows: Int?,
): List<List<QuickLaunchGridTile>> =
    if (maxRows != null) rowChunks.take(maxRows.coerceAtLeast(1)) else rowChunks

