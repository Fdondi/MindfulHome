package com.mindfulhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLaunchJsonTest {

    @Test
    fun decode_nullOrBlank_returnsEmpty() {
        assertTrue(QuickLaunchJson.decode(null).isEmpty())
        assertTrue(QuickLaunchJson.decode("").isEmpty())
        assertTrue(QuickLaunchJson.decode("   ").isEmpty())
    }

    @Test
    fun decode_malformed_returnsEmpty() {
        assertTrue(QuickLaunchJson.decode("not json").isEmpty())
        assertTrue(QuickLaunchJson.decode("{}").isEmpty())
    }

    @Test
    fun encodeDecode_mixedArray_roundTrips() {
        val original = listOf(
            QuickLaunchSlot.Single("com.a"),
            QuickLaunchSlot.Folder(
                "F",
                listOf(
                    QuickLaunchFolderApp.unlimited("x"),
                    QuickLaunchFolderApp.unlimited("y"),
                ),
            ),
            QuickLaunchSlot.Single("com.b"),
        )
        val json = QuickLaunchJson.encode(original)
        val decoded = QuickLaunchJson.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun decodeIntentSlots_folderWithOneApp_staysFolder() {
        val json = """[{"name":"Search","apps":["only.pkg"]}]"""
        val slots = QuickLaunchJson.decodeIntentSlots(json)
        assertEquals(1, slots.size)
        assertTrue(slots[0] is QuickLaunchSlot.Folder)
        val folder = slots[0] as QuickLaunchSlot.Folder
        assertEquals("Search", folder.name)
        assertEquals(listOf("only.pkg"), folder.packageNames())
        assertTrue(folder.apps.all { it.isUnlimited })
    }

    @Test
    fun encodeDecode_timedFolderApp_roundTrips() {
        val original = listOf(
            QuickLaunchSlot.Folder(
                "Learn",
                listOf(
                    QuickLaunchFolderApp.timed("com.duolingo", 5),
                    QuickLaunchFolderApp.unlimited("com.chrome"),
                ),
            ),
        )
        val json = QuickLaunchJson.encodeIntentSlots(original)
        assertTrue(json.contains("\"limitMinutes\":5"))
        val decoded = QuickLaunchJson.decodeIntentSlots(json)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_timedFolderAppObject() {
        val json = """[{"name":"Learn","apps":[{"pkg":"com.duolingo","limitMinutes":3}]}]"""
        val slots = QuickLaunchJson.decodeIntentSlots(json)
        val folder = slots.single() as QuickLaunchSlot.Folder
        assertEquals(3, folder.limitMinutesFor("com.duolingo"))
    }

    @Test
    fun encodeDecode_folderWithShortcuts_roundTrips() {
        val original = listOf(
            QuickLaunchSlot.Folder(
                "Search",
                listOf(QuickLaunchFolderApp.unlimited("com.chrome")),
                "search",
                listOf(PinnedShortcut("com.chrome", "bookmark-1", "My bookmark")),
            ),
        )
        val json = QuickLaunchJson.encodeIntentSlots(original)
        val decoded = QuickLaunchJson.decodeIntentSlots(json)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeDecode_folderWithShortcutsAndIntentUri_roundTrips() {
        val original = listOf(
            QuickLaunchSlot.Folder(
                "Search",
                listOf(QuickLaunchFolderApp.unlimited("com.brave.browser")),
                "search",
                listOf(
                    PinnedShortcut(
                        "com.brave.browser",
                        "legacy-12345",
                        "Example",
                        "intent://example.com#Intent;scheme=https;end",
                    ),
                ),
            ),
        )
        val json = QuickLaunchJson.encodeIntentSlots(original)
        val decoded = QuickLaunchJson.decodeIntentSlots(json)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_folderWithOneApp_becomesSingle() {
        val json = """[{"apps":["only.pkg"]}]"""
        val slots = QuickLaunchJson.decode(json)
        assertEquals(1, slots.size)
        assertTrue(slots[0] is QuickLaunchSlot.Single)
        assertEquals("only.pkg", (slots[0] as QuickLaunchSlot.Single).packageName)
    }

    @Test
    fun decode_folderWithoutName() {
        val json = """[{"apps":["p1","p2"]}]"""
        val slots = QuickLaunchJson.decode(json)
        val folder = slots.single() as QuickLaunchSlot.Folder
        assertEquals(null, folder.name)
        assertEquals(listOf("p1", "p2"), folder.packageNames())
    }

    @Test
    fun encode_omitsEmptyFolderNameKey() {
        val json = QuickLaunchJson.encode(
            listOf(
                QuickLaunchSlot.Folder(
                    null,
                    listOf(
                        QuickLaunchFolderApp.unlimited("a"),
                        QuickLaunchFolderApp.unlimited("b"),
                    ),
                ),
            ),
        )
        assertTrue(!json.contains("\"name\""))
    }

    @Test
    fun encodeDecode_folderSymbolIcon_roundTrips() {
        val original = listOf(
            QuickLaunchSlot.Folder(
                "Travel",
                listOf(
                    QuickLaunchFolderApp.unlimited("x"),
                    QuickLaunchFolderApp.unlimited("y"),
                ),
                "flight_takeoff",
            ),
        )
        val decoded = QuickLaunchJson.decode(QuickLaunchJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun decode_folderWithSymbolIcon() {
        val json = """[{"name":"T","symbolIcon":"sms","apps":["a","b"]}]"""
        val slots = QuickLaunchJson.decode(json)
        val folder = slots.single() as QuickLaunchSlot.Folder
        assertEquals("sms", folder.symbolIconName)
    }
}
