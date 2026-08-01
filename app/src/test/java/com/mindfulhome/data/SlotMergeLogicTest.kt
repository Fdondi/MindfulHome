package com.mindfulhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotMergeLogicTest {

    private fun slots(vararg items: QuickLaunchSlot): MutableList<QuickLaunchSlot> =
        items.toMutableList()

    @Test
    fun mergeSlotsMutable_rejectsSameOrOutOfRange() {
        val list = slots(QuickLaunchSlot.Single("a"), QuickLaunchSlot.Single("b"))
        assertNull(mergeSlotsMutable(list, 0, 0))
        assertNull(mergeSlotsMutable(list, -1, 0))
        assertNull(mergeSlotsMutable(list, 0, 9))
    }

    @Test
    fun mergeSlotsMutable_collapsesToSingleAndKeepsIntoName() {
        val list = slots(
            QuickLaunchSlot.Folder("Tools", listOf(QuickLaunchFolderApp.unlimited("a"))),
            QuickLaunchSlot.Single("b"),
        )
        val result = mergeSlotsMutable(list, 1, 0)!!
        assertEquals(1, result.size)
        val folder = result[0] as QuickLaunchSlot.Folder
        assertEquals("Tools", folder.name)
        assertEquals(listOf("a", "b"), folder.apps.map { it.packageName })
    }

    @Test
    fun mergeSlotsMutable_twoSinglesBecomeFolderThenSingleWhenOne() {
        val list = slots(QuickLaunchSlot.Single("a"), QuickLaunchSlot.Single("a"))
        val result = mergeSlotsMutable(list, 1, 0)!!
        assertEquals(1, result.size)
        assertTrue(result[0] is QuickLaunchSlot.Single)
        assertEquals("a", (result[0] as QuickLaunchSlot.Single).packageName)
    }

    @Test
    fun mergeIntentSlotsMutable_keepsFolderAndShortcuts() {
        val shortcut = PinnedShortcut("com.app", "id1", "Label", null)
        val list = slots(
            QuickLaunchSlot.Folder("A", listOf(QuickLaunchFolderApp.unlimited("a")), shortcuts = listOf(shortcut)),
            QuickLaunchSlot.Folder("B", listOf(QuickLaunchFolderApp.unlimited("b"))),
        )
        val result = mergeIntentSlotsMutable(list, 1, 0)!!
        assertEquals(1, result.size)
        val folder = result[0] as QuickLaunchSlot.Folder
        assertEquals("A", folder.name)
        assertEquals(listOf("a", "b"), folder.apps.map { it.packageName })
        assertEquals(1, folder.shortcuts.size)
    }

    @Test
    fun extractFromFolderSlot_and_intentVariant() {
        val classic = slots(
            QuickLaunchSlot.Folder(
                "F",
                listOf(
                    QuickLaunchFolderApp.unlimited("a"),
                    QuickLaunchFolderApp.unlimited("b"),
                    QuickLaunchFolderApp.unlimited("c"),
                ),
            ),
        )
        extractFromFolderSlot(classic, "b")
        assertEquals(2, classic.size)
        assertTrue(classic[0] is QuickLaunchSlot.Folder)
        assertEquals("b", (classic[1] as QuickLaunchSlot.Single).packageName)

        val intent = slots(
            QuickLaunchSlot.Folder(
                "F",
                listOf(QuickLaunchFolderApp.unlimited("a"), QuickLaunchFolderApp.unlimited("b")),
            ),
        )
        extractFromIntentFolderSlot(intent, "a")
        assertEquals(2, intent.size)
        assertTrue(intent[1] is QuickLaunchSlot.Folder)
        assertEquals(listOf("a"), (intent[1] as QuickLaunchSlot.Folder).apps.map { it.packageName })
    }

    @Test
    fun pickMergedNameAndSymbol_preferInto() {
        val into = QuickLaunchSlot.Folder("Into", listOf(QuickLaunchFolderApp.unlimited("a")), "star")
        val from = QuickLaunchSlot.Folder("From", listOf(QuickLaunchFolderApp.unlimited("b")), "home")
        assertEquals("Into", pickMergedFolderName(into, from))
        assertEquals("star", pickMergedFolderSymbol(into, from))
        assertEquals(0, intoIndexAfterRemove(1, 0))
        assertEquals(1, intoIndexAfterRemove(0, 2))
    }
}
