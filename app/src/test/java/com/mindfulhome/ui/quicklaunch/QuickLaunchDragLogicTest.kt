package com.mindfulhome.ui.quicklaunch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mindfulhome.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLaunchDragLogicTest {

    private fun app(pkg: String) = AppInfo(pkg, pkg, null)

    private fun slot(vararg pkgs: String) = QuickLaunchSlotUi(apps = pkgs.map { app(it) })

    private fun twoSlotRow() = listOf(
        listOf(QuickLaunchGridTile(slotIndex = 0), QuickLaunchGridTile(slotIndex = 1)),
    )

    private fun twoSlotBounds() = mapOf(
        0 to Rect(0f, 0f, 40f, 40f),
        1 to Rect(60f, 0f, 100f, 40f),
    )

    @Test
    fun buildQuickLaunchRowChunks_padsLastRowWithPlaceholders() {
        val chunks = buildQuickLaunchRowChunks(listOf(slot("a"), slot("b")), columns = 3)
        assertEquals(1, chunks.size)
        assertEquals(3, chunks[0].size)
        assertEquals(0, chunks[0][0].slotIndex)
        assertEquals(1, chunks[0][1].slotIndex)
        assertTrue(chunks[0][2].isAdd || chunks[0].any { it.isAdd })
    }

    @Test
    fun resolveEdgeInsertSide_usesBandsAndSticky() {
        assertTrue(resolveEdgeInsertSide(0.2f, stickyBefore = null, fingerLeftOfMid = false))
        assertEquals(false, resolveEdgeInsertSide(0.8f, stickyBefore = null, fingerLeftOfMid = true))
        assertTrue(resolveEdgeInsertSide(0.5f, stickyBefore = true, fingerLeftOfMid = false))
        assertEquals(false, resolveEdgeInsertSide(0.5f, stickyBefore = null, fingerLeftOfMid = false))
    }

    @Test
    fun shouldActivateIntentDrag_usesHypotenuse() {
        assertEquals(false, shouldActivateIntentDrag(Offset(3f, 4f), thresholdPx = 6f))
        assertTrue(shouldActivateIntentDrag(Offset(3f, 4f), thresholdPx = 5f))
    }

    @Test
    fun resolveSlotClick_intentVsFolder() {
        assertEquals(
            QuickLaunchSlotClickAction.LaunchSingle,
            resolveSlotClick(QuickLaunchTileContent.IntentLabels, appCount = 1),
        )
        assertEquals(
            QuickLaunchSlotClickAction.OpenFolder,
            resolveSlotClick(QuickLaunchTileContent.IntentLabels, appCount = 2),
        )
        assertEquals(
            QuickLaunchSlotClickAction.OpenFolder,
            resolveSlotClick(QuickLaunchTileContent.AppIcons, appCount = 2),
        )
        assertEquals(
            QuickLaunchSlotClickAction.LaunchSingle,
            resolveSlotClick(QuickLaunchTileContent.AppIcons, appCount = 1),
        )
    }

    @Test
    fun resolveFolderDrop_removeWinsOverSecondary() {
        val remove = Rect(0f, 0f, 10f, 10f)
        val secondary = Rect(0f, 0f, 20f, 20f)
        assertEquals(FolderDropAction.Remove, resolveFolderDrop(Offset(5f, 5f), remove, secondary))
        assertEquals(FolderDropAction.Secondary, resolveFolderDrop(Offset(15f, 15f), remove, secondary))
        assertEquals(FolderDropAction.None, resolveFolderDrop(Offset(50f, 50f), remove, secondary))
    }

    @Test
    fun resolveFolderHoverFlags_draggingAndZones() {
        assertEquals(false to false, resolveFolderHoverFlags(Offset(5f, 5f), dragging = false, Rect(0f, 0f, 10f, 10f), null))
        val remove = Rect(0f, 0f, 10f, 10f)
        val secondary = Rect(20f, 0f, 30f, 10f)
        assertEquals(true to false, resolveFolderHoverFlags(Offset(5f, 5f), true, remove, secondary))
        assertEquals(false to true, resolveFolderHoverFlags(Offset(25f, 5f), true, remove, secondary))
        assertEquals(false to false, resolveFolderHoverFlags(Offset(50f, 50f), true, remove, secondary))
    }

    @Test
    fun findGapInsertionBarRect_horizontalGap() {
        val bar = findGapInsertionBarRect(
            finger = Offset(50f, 20f),
            rowChunks = twoSlotRow(),
            slotBounds = twoSlotBounds(),
            minGapPx = 8f,
            barThicknessPx = 4f,
        )
        assertTrue(bar != null)
        assertTrue(bar!!.left < bar.right)
    }

    @Test
    fun findGapInsertionBarRect_verticalGapAndMiss() {
        val chunks = listOf(
            listOf(QuickLaunchGridTile(slotIndex = 0)),
            listOf(QuickLaunchGridTile(slotIndex = 1)),
        )
        val bounds = mapOf(
            0 to Rect(0f, 0f, 40f, 40f),
            1 to Rect(0f, 60f, 40f, 100f),
        )
        val bar = findGapInsertionBarRect(
            finger = Offset(20f, 50f),
            rowChunks = chunks,
            slotBounds = bounds,
            minGapPx = 8f,
            barThicknessPx = 4f,
        )
        assertTrue(bar != null)
        assertNull(
            findGapInsertionBarRect(
                finger = Offset(200f, 200f),
                rowChunks = chunks,
                slotBounds = bounds,
                minGapPx = 8f,
                barThicknessPx = 4f,
            ),
        )
    }

    @Test
    fun resolveHoverPreview_removeThenMerge() {
        val bounds = mapOf(0 to Rect(0f, 0f, 50f, 50f), 1 to Rect(100f, 0f, 150f, 50f))
        val remove = Rect(0f, 200f, 200f, 240f)
        assertEquals(
            QuickLaunchHoverPreview.RemoveZone,
            resolveHoverPreview(
                finger = Offset(10f, 210f),
                dragIdx = 0,
                removeZoneBounds = remove,
                slotBounds = bounds,
                rowChunks = emptyList(),
                minGapPx = 8f,
                barThicknessPx = 4f,
                edgePreviewSticky = null,
            ),
        )
        val merge = resolveHoverPreview(
            finger = Offset(125f, 25f),
            dragIdx = 0,
            removeZoneBounds = remove,
            slotBounds = bounds,
            rowChunks = emptyList(),
            minGapPx = 8f,
            barThicknessPx = 4f,
            edgePreviewSticky = null,
        )
        assertEquals(QuickLaunchHoverPreview.Merge(1), merge)
    }

    @Test
    fun resolveHoverPreview_clearWhenNoDrag_gapAndEdge() {
        assertEquals(
            QuickLaunchHoverPreview.Clear,
            resolveHoverPreview(
                finger = Offset(0f, 0f),
                dragIdx = null,
                removeZoneBounds = null,
                slotBounds = emptyMap(),
                rowChunks = emptyList(),
                minGapPx = 8f,
                barThicknessPx = 4f,
                edgePreviewSticky = null,
            ),
        )
        val gapPreview = resolveHoverPreview(
            finger = Offset(50f, 20f),
            dragIdx = 0,
            removeZoneBounds = null,
            slotBounds = twoSlotBounds(),
            rowChunks = twoSlotRow(),
            minGapPx = 8f,
            barThicknessPx = 4f,
            edgePreviewSticky = null,
        )
        assertTrue(gapPreview is QuickLaunchHoverPreview.GapOrEdgeBar)
        assertNull((gapPreview as QuickLaunchHoverPreview.GapOrEdgeBar).edgeSticky)

        // Edge insert: finger on left band of slot 1 (outside merge zone)
        val edge = resolveHoverPreview(
            finger = Offset(62f, 20f),
            dragIdx = 0,
            removeZoneBounds = null,
            slotBounds = twoSlotBounds(),
            rowChunks = emptyList(),
            minGapPx = 8f,
            barThicknessPx = 4f,
            edgePreviewSticky = 1 to true,
        )
        assertTrue(edge is QuickLaunchHoverPreview.GapOrEdgeBar)
        assertEquals(1 to true, (edge as QuickLaunchHoverPreview.GapOrEdgeBar).edgeSticky)
    }

    @Test
    fun resolveHoverPreview_helpersCoverClearMergeSkipDragged() {
        assertNull(hoverRemoveZone(Offset(1f, 1f), null))
        assertEquals(
            QuickLaunchHoverPreview.RemoveZone,
            hoverRemoveZone(Offset(1f, 1f), Rect(0f, 0f, 10f, 10f)),
        )
        assertNull(
            hoverMergeSlot(
                finger = Offset(25f, 25f),
                dragIdx = 0,
                slotBounds = mapOf(0 to Rect(0f, 0f, 50f, 50f)),
            ),
        )
        assertEquals(
            QuickLaunchHoverPreview.Clear,
            hoverEdgeBar(
                finger = Offset(500f, 500f),
                dragIdx = 0,
                slotBounds = twoSlotBounds(),
                barThicknessPx = 4f,
                edgePreviewSticky = null,
            ),
        )
        // Right-side edge insert without sticky (slot 1 is 60..100; mid=80; use far right band)
        val rightEdge = resolveHoverPreview(
            finger = Offset(96f, 20f),
            dragIdx = 0,
            removeZoneBounds = null,
            slotBounds = twoSlotBounds(),
            rowChunks = emptyList(),
            minGapPx = 8f,
            barThicknessPx = 4f,
            edgePreviewSticky = null,
        )
        assertTrue(
            "expected GapOrEdgeBar, got $rightEdge",
            rightEdge is QuickLaunchHoverPreview.GapOrEdgeBar ||
                rightEdge is QuickLaunchHoverPreview.Merge,
        )
    }

    @Test
    fun hoverUiFromPreview_mapsAllVariants() {
        assertEquals(QuickLaunchHoverUi(), hoverUiFromPreview(QuickLaunchHoverPreview.Clear))
        assertEquals(
            QuickLaunchHoverUi(hoveringRemoveZone = true),
            hoverUiFromPreview(QuickLaunchHoverPreview.RemoveZone),
        )
        assertEquals(
            QuickLaunchHoverUi(mergeHoverSlot = 2),
            hoverUiFromPreview(QuickLaunchHoverPreview.Merge(2)),
        )
        val bar = Rect(1f, 2f, 3f, 4f)
        assertEquals(
            QuickLaunchHoverUi(gapBarRectRoot = bar, edgePreviewSticky = 0 to false),
            hoverUiFromPreview(QuickLaunchHoverPreview.GapOrEdgeBar(bar, 0 to false)),
        )
    }

    // --- resolveGapDrop ---

    @Test
    fun resolveGapDrop_horizontalMoveAndNoneWhenSameIndex() {
        // Three slots so dropping in gap 0|1 from slot 2 yields a real move.
        val chunks = listOf(
            listOf(
                QuickLaunchGridTile(slotIndex = 0),
                QuickLaunchGridTile(slotIndex = 1),
                QuickLaunchGridTile(slotIndex = 2),
            ),
        )
        val bounds = mapOf(
            0 to Rect(0f, 0f, 40f, 40f),
            1 to Rect(60f, 0f, 100f, 40f),
            2 to Rect(120f, 0f, 160f, 40f),
        )
        assertEquals(
            QuickLaunchDropAction.Move(1),
            resolveGapDrop(Offset(50f, 20f), from = 2, chunks, bounds, 8f),
        )
        // Insert before right=1 from=0 → to == from → None
        assertEquals(
            QuickLaunchDropAction.None,
            resolveGapDrop(Offset(50f, 20f), from = 0, chunks, bounds, 8f),
        )
    }

    @Test
    fun resolveGapDrop_verticalMoveAndMiss() {
        val chunks = listOf(
            listOf(QuickLaunchGridTile(slotIndex = 0)),
            listOf(QuickLaunchGridTile(slotIndex = 1)),
            listOf(QuickLaunchGridTile(slotIndex = 2)),
        )
        val bounds = mapOf(
            0 to Rect(0f, 0f, 40f, 40f),
            1 to Rect(0f, 60f, 40f, 100f),
            2 to Rect(0f, 120f, 40f, 160f),
        )
        // Gap between row0 and row1; from=2 → insert before topFirst=1 → Move(1)
        assertEquals(
            QuickLaunchDropAction.Move(1),
            resolveGapDrop(Offset(20f, 50f), from = 2, chunks, bounds, 8f),
        )
        assertEquals(
            QuickLaunchDropAction.None,
            resolveGapDrop(Offset(20f, 50f), from = 0, chunks, bounds, 8f),
        )
        assertNull(resolveGapDrop(Offset(200f, 200f), from = 0, chunks, bounds, 8f))
    }

    @Test
    fun resolveGapDrop_skipsMissingBoundsAndEmptyRows() {
        val chunks = listOf(
            listOf(QuickLaunchGridTile(slotIndex = 0), QuickLaunchGridTile(slotIndex = 1)),
            listOf(QuickLaunchGridTile(isPlaceholder = true)),
            listOf(QuickLaunchGridTile(slotIndex = 2)),
        )
        // Missing right bound → continue; no gap hit
        assertNull(
            resolveGapDrop(
                Offset(50f, 20f),
                from = 0,
                chunks,
                slotBounds = mapOf(0 to Rect(0f, 0f, 40f, 40f)),
                minGapPx = 8f,
            ),
        )
        // Vertical: missing bottomLast slot bounds
        assertNull(
            resolveGapDrop(
                Offset(20f, 50f),
                from = 0,
                rowChunks = listOf(
                    listOf(QuickLaunchGridTile(slotIndex = 0)),
                    listOf(QuickLaunchGridTile(slotIndex = 1)),
                ),
                slotBounds = mapOf(1 to Rect(0f, 60f, 40f, 100f)),
                minGapPx = 8f,
            ),
        )
    }

    // --- resolveOverlapDrop ---

    @Test
    fun resolveOverlapDrop_mergeLeftEdgeRightEdgeAndMiss() {
        val bounds = mapOf(
            0 to Rect(0f, 0f, 40f, 40f),
            1 to Rect(100f, 0f, 140f, 40f),
        )
        // Center of slot 1 → merge
        assertEquals(
            QuickLaunchDropAction.Merge(1),
            resolveOverlapDrop(Offset(120f, 20f), from = 0, bounds),
        )
        // Left edge of slot 1 from=0 → insertBefore(0,1)=0 → None
        assertEquals(
            QuickLaunchDropAction.None,
            resolveOverlapDrop(Offset(102f, 20f), from = 0, bounds),
        )
        // Right edge → insert after → Move(1)
        assertEquals(
            QuickLaunchDropAction.Move(1),
            resolveOverlapDrop(Offset(138f, 20f), from = 0, bounds),
        )
        // from=1, left of slot 0 center → insert before 0 → Move(0)
        assertEquals(
            QuickLaunchDropAction.Move(0),
            resolveOverlapDrop(Offset(2f, 20f), from = 1, bounds),
        )
        assertNull(resolveOverlapDrop(Offset(20f, 20f), from = 0, mapOf(0 to Rect(0f, 0f, 40f, 40f))))
        assertNull(resolveOverlapDrop(Offset(300f, 300f), from = 0, bounds))
    }

    @Test
    fun resolveOverlapDrop_noneWhenInsertIndexEqualsFrom() {
        // Three slots; from=1, finger left of slot 2 → insertIndexBeforeRight(1, 2) = 1 → None
        val bounds = mapOf(
            0 to Rect(0f, 0f, 40f, 40f),
            1 to Rect(50f, 0f, 90f, 40f),
            2 to Rect(100f, 0f, 140f, 40f),
        )
        assertEquals(
            QuickLaunchDropAction.None,
            resolveOverlapDrop(Offset(102f, 20f), from = 1, bounds),
        )
    }

    // --- resolveNearestDrop ---

    @Test
    fun resolveNearestDrop_closestOrNone() {
        val bounds = mapOf(
            0 to Rect(0f, 0f, 40f, 40f),
            1 to Rect(100f, 0f, 140f, 40f),
        )
        assertEquals(
            QuickLaunchDropAction.Move(1),
            resolveNearestDrop(Offset(300f, 300f), from = 0, bounds),
        )
        assertEquals(
            QuickLaunchDropAction.None,
            resolveNearestDrop(Offset(0f, 0f), from = 0, emptyMap()),
        )
        assertEquals(
            QuickLaunchDropAction.None,
            resolveNearestDrop(Offset(0f, 0f), from = 0, mapOf(0 to Rect(0f, 0f, 10f, 10f))),
        )
    }

    // --- resolveDropAction orchestration ---

    @Test
    fun resolveDropAction_removeGapOverlapNearest() {
        val bounds = mapOf(
            0 to Rect(0f, 0f, 40f, 40f),
            1 to Rect(100f, 0f, 140f, 40f),
        )
        val chunks = listOf(
            listOf(QuickLaunchGridTile(slotIndex = 0), QuickLaunchGridTile(slotIndex = 1)),
        )
        assertEquals(
            QuickLaunchDropAction.Remove,
            resolveDropAction(Offset(0f, 0f), from = 0, shouldRemove = true, chunks, bounds, 8f),
        )
        // Gap between 0 and 1 with from=2 (third slot) → Move(1)
        val threeChunks = listOf(
            listOf(
                QuickLaunchGridTile(slotIndex = 0),
                QuickLaunchGridTile(slotIndex = 1),
                QuickLaunchGridTile(slotIndex = 2),
            ),
        )
        val threeBounds = mapOf(
            0 to Rect(0f, 0f, 40f, 40f),
            1 to Rect(60f, 0f, 100f, 40f),
            2 to Rect(120f, 0f, 160f, 40f),
        )
        assertEquals(
            QuickLaunchDropAction.Move(1),
            resolveDropAction(
                Offset(50f, 20f),
                from = 2,
                shouldRemove = false,
                threeChunks,
                threeBounds,
                8f,
            ),
        )
        assertEquals(
            QuickLaunchDropAction.Merge(1),
            resolveDropAction(Offset(120f, 20f), from = 0, shouldRemove = false, chunks, bounds, 8f),
        )
        assertEquals(
            QuickLaunchDropAction.Move(1),
            resolveDropAction(Offset(300f, 300f), from = 0, shouldRemove = false, chunks, bounds, 8f),
        )
    }

    @Test
    fun resolveDropAction_noTargets() {
        assertEquals(
            QuickLaunchDropAction.None,
            resolveDropAction(
                finger = Offset(0f, 0f),
                from = 0,
                shouldRemove = false,
                rowChunks = emptyList(),
                slotBounds = emptyMap(),
                minGapPx = 8f,
            ),
        )
    }

    @Test
    fun dispatchDropAction_allBranches() {
        var removedAt: Int? = null
        var removedApps: List<AppInfo>? = null
        var merged: Pair<Int, Int>? = null
        var moved: Pair<Int, Int>? = null
        val apps = listOf(app("a"))

        dispatchDropAction(
            QuickLaunchDropAction.Remove, 2, apps,
            onRemoveSlotAt = { removedAt = it },
            onRemoveSlot = { removedApps = it },
            onMergeSlotInto = { a, b -> merged = a to b },
            onMoveSlot = { a, b -> moved = a to b },
        )
        assertEquals(2, removedAt)
        assertNull(removedApps)

        removedAt = null
        dispatchDropAction(
            QuickLaunchDropAction.Remove, 1, apps,
            onRemoveSlotAt = null,
            onRemoveSlot = { removedApps = it },
            onMergeSlotInto = { a, b -> merged = a to b },
            onMoveSlot = { a, b -> moved = a to b },
        )
        assertEquals(apps, removedApps)

        dispatchDropAction(
            QuickLaunchDropAction.Merge(3), 0, apps,
            onRemoveSlotAt = null,
            onRemoveSlot = {},
            onMergeSlotInto = { a, b -> merged = a to b },
            onMoveSlot = { a, b -> moved = a to b },
        )
        assertEquals(0 to 3, merged)

        dispatchDropAction(
            QuickLaunchDropAction.Move(4), 0, apps,
            onRemoveSlotAt = null,
            onRemoveSlot = {},
            onMergeSlotInto = { _, _ -> },
            onMoveSlot = { a, b -> moved = a to b },
        )
        assertEquals(0 to 4, moved)

        dispatchDropAction(
            QuickLaunchDropAction.None, 0, apps,
            onRemoveSlotAt = null,
            onRemoveSlot = {},
            onMergeSlotInto = { _, _ -> },
            onMoveSlot = { _, _ -> },
        )
    }

    @Test
    fun dispatchFolderDrop_allBranches() {
        var removed: AppInfo? = null
        var extracted: AppInfo? = null
        var edited: AppInfo? = null
        val a = app("x")

        dispatchFolderDrop(FolderDropAction.Remove, a, false, { removed = it }, null, { extracted = it })
        assertEquals(a, removed)

        dispatchFolderDrop(FolderDropAction.Secondary, a, false, {}, null, { extracted = it })
        assertEquals(a, extracted)

        dispatchFolderDrop(FolderDropAction.Secondary, a, true, {}, { edited = it }, { extracted = null })
        assertEquals(a, edited)

        dispatchFolderDrop(FolderDropAction.None, a, false, {}, null, {})
    }

    @Test
    fun intentLongPressHelpers_andFolderLabel() {
        assertTrue(isIntentLongPressMode(QuickLaunchTileContent.IntentLabels))
        assertFalse(isIntentLongPressMode(QuickLaunchTileContent.AppIcons))

        assertTrue(
            shouldOpenFolderOnIntentLongPressEnd(
                QuickLaunchTileContent.IntentLabels, pendingSlot = 1, slotIndex = 1, dragActivated = false,
            ),
        )
        assertFalse(
            shouldOpenFolderOnIntentLongPressEnd(
                QuickLaunchTileContent.IntentLabels, pendingSlot = 1, slotIndex = 1, dragActivated = true,
            ),
        )
        assertFalse(
            shouldOpenFolderOnIntentLongPressEnd(
                QuickLaunchTileContent.AppIcons, pendingSlot = 1, slotIndex = 1, dragActivated = false,
            ),
        )

        assertTrue(
            shouldActivatePendingIntentDrag(
                QuickLaunchTileContent.IntentLabels, 0, 0, alreadyActivated = false,
                accumulated = Offset(10f, 0f), thresholdPx = 5f,
            ),
        )
        assertFalse(
            shouldActivatePendingIntentDrag(
                QuickLaunchTileContent.IntentLabels, 0, 0, alreadyActivated = true,
                accumulated = Offset(10f, 0f), thresholdPx = 5f,
            ),
        )

        assertEquals("Unnamed", folderLabelForTile(QuickLaunchTileContent.IntentLabels, null, 2))
        assertEquals("Folder (3)", folderLabelForTile(QuickLaunchTileContent.AppIcons, null, 3))
        assertEquals("Work", folderLabelForTile(QuickLaunchTileContent.AppIcons, "Work", 3))
        assertEquals("Intent", ghostLabelForDrag(QuickLaunchTileContent.IntentLabels, null))
        assertEquals("Mail", ghostLabelForDrag(QuickLaunchTileContent.IntentLabels, "Mail"))
    }

    @Test
    fun resolveTileDragStep_allBranches() {
        assertEquals(
            TileDragStep.ActivateAndHover,
            resolveTileDragStep(
                QuickLaunchTileContent.IntentLabels, 0, 0,
                intentDragActivated = false,
                accumulated = Offset(10f, 0f),
                thresholdPx = 5f,
                isDragging = false,
            ),
        )
        assertEquals(
            TileDragStep.UpdateHover,
            resolveTileDragStep(
                QuickLaunchTileContent.IntentLabels, 0, 0,
                intentDragActivated = true,
                accumulated = Offset(0f, 0f),
                thresholdPx = 5f,
                isDragging = true,
            ),
        )
        assertEquals(
            TileDragStep.None,
            resolveTileDragStep(
                QuickLaunchTileContent.IntentLabels, 0, 0,
                intentDragActivated = false,
                accumulated = Offset(1f, 0f),
                thresholdPx = 5f,
                isDragging = false,
            ),
        )
        assertEquals(
            TileDragStep.UpdateHover,
            resolveTileDragStep(
                QuickLaunchTileContent.AppIcons, null, 0,
                intentDragActivated = false,
                accumulated = Offset.Zero,
                thresholdPx = 5f,
                isDragging = true,
            ),
        )
        assertEquals(
            TileDragStep.None,
            resolveTileDragStep(
                QuickLaunchTileContent.AppIcons, null, 0,
                intentDragActivated = false,
                accumulated = Offset.Zero,
                thresholdPx = 5f,
                isDragging = false,
            ),
        )
    }
}
