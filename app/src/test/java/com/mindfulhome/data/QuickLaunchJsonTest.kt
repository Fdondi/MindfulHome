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
            QuickLaunchSlot.Folder("F", listOf("x", "y")),
            QuickLaunchSlot.Single("com.b"),
        )
        val json = QuickLaunchJson.encode(original)
        val decoded = QuickLaunchJson.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_folderWithOneApp_becomesSingle() {
        val json = """[{"name":"Solo","apps":["only.pkg"]}]"""
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
        assertEquals(listOf("p1", "p2"), folder.apps)
    }

    @Test
    fun encode_omitsEmptyFolderNameKey() {
        val json = QuickLaunchJson.encode(
            listOf(QuickLaunchSlot.Folder(null, listOf("a", "b"))),
        )
        assertTrue(!json.contains("\"name\""))
    }

    @Test
    fun encodeDecode_folderSymbolIcon_roundTrips() {
        val original = listOf(
            QuickLaunchSlot.Folder("Travel", listOf("x", "y"), "flight_takeoff"),
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
