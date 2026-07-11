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
            listOf(QuickLaunchSlot.Folder("N", listOf(QuickLaunchFolderApp.unlimited("only")))),
        )
        assertEquals(listOf(QuickLaunchSlot.Single("only")), out)
    }

    @Test
    fun folderWithNoAppsAfterFilter_removed() {
        val out = normalizeQuickLaunchSlots(
            listOf(
                QuickLaunchSlot.Folder(
                    "N",
                    listOf(
                        QuickLaunchFolderApp.unlimited(""),
                        QuickLaunchFolderApp.unlimited("  "),
                    ),
                ),
            ),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun intentFolderWithOneApp_staysFolder() {
        val out = normalizeIntentQuickLaunchSlots(
            listOf(QuickLaunchSlot.Folder("Search", listOf(QuickLaunchFolderApp.unlimited("com.chrome")))),
        )
        val folder = out.single() as QuickLaunchSlot.Folder
        assertEquals("Search", folder.name)
        assertEquals(listOf("com.chrome"), folder.packageNames())
    }

    @Test
    fun intentEmptyNamedFolder_kept() {
        val out = normalizeIntentQuickLaunchSlots(
            listOf(QuickLaunchSlot.Folder("Reflect", emptyList())),
        )
        val folder = out.single() as QuickLaunchSlot.Folder
        assertEquals("Reflect", folder.name)
        assertTrue(folder.apps.isEmpty())
    }

    @Test
    fun folderDuplicates_distinctPreservesOrder() {
        val out = normalizeQuickLaunchSlots(
            listOf(
                QuickLaunchSlot.Folder(
                    null,
                    listOf(
                        QuickLaunchFolderApp.unlimited("a"),
                        QuickLaunchFolderApp.unlimited("a"),
                        QuickLaunchFolderApp.unlimited("b"),
                    ),
                ),
            ),
        )
        val folder = out.single() as QuickLaunchSlot.Folder
        assertEquals(listOf("a", "b"), folder.packageNames())
    }

    @Test
    fun flattenAllowedPackages_excludesTimedApps() {
        val slot = QuickLaunchSlot.Folder(
            "Mix",
            listOf(
                QuickLaunchFolderApp.unlimited("com.allowed"),
                QuickLaunchFolderApp.timed("com.timed", 3),
            ),
        )
        assertEquals(listOf("com.allowed"), slot.flattenAllowedPackages())
        assertEquals(mapOf("com.timed" to 3), slot.limitMinutesByPackage())
    }
}
