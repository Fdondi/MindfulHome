package com.mindfulhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLaunchNormalizationTest {

    @Test
    fun blankSingle_removed() {
        val out = normalizeQuickLaunchSlots(
            listOf(
                QuickLaunchSlot.Single("  "),
                QuickLaunchSlot.Single("ok"),
            ),
        )
        assertEquals(listOf(QuickLaunchSlot.Single("ok")), out)
    }

    @Test
    fun folderWithOneApp_collapsesToSingle() {
        val out = normalizeQuickLaunchSlots(
            listOf(QuickLaunchSlot.Folder("N", listOf("only"))),
        )
        assertEquals(listOf(QuickLaunchSlot.Single("only")), out)
    }

    @Test
    fun folderWithNoAppsAfterFilter_removed() {
        val out = normalizeQuickLaunchSlots(
            listOf(QuickLaunchSlot.Folder("N", listOf("", "  "))),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun folderDuplicates_distinctPreservesOrder() {
        val out = normalizeQuickLaunchSlots(
            listOf(QuickLaunchSlot.Folder(null, listOf("a", "a", "b"))),
        )
        val folder = out.single() as QuickLaunchSlot.Folder
        assertEquals(listOf("a", "b"), folder.apps)
    }
}
