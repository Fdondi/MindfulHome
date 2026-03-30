package com.mindfulhome.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSlotPlacementTest {

    @Test
    fun placementsByPackage_listsRootAndMultipleFolderEntries() {
        val slots = listOf(
            QuickLaunchSlot.Single("com.a"),
            QuickLaunchSlot.Folder("F", listOf("com.a", "com.b"), "star"),
        )
        val m = placementsByPackage(slots)
        assertEquals(
            listOf(
                AppSlotPlacement.Root,
                AppSlotPlacement.InFolder("star"),
            ),
            m["com.a"],
        )
        assertEquals(listOf(AppSlotPlacement.InFolder("star")), m["com.b"])
    }
}
