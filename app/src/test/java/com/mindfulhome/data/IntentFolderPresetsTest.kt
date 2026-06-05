package com.mindfulhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentFolderPresetsTest {

    @Test
    fun buildInitialSlots_includesAllPresetsWithInstalledMatches() {
        val installed = setOf(
            "com.android.chrome",
            "com.duolingo",
            "com.whatsapp",
        )
        val slots = IntentFolderPresets.buildInitialSlots(installed)
        assertEquals(IntentFolderPresets.all.size, slots.size)
        val search = slots.first { it.name == "Search" }
        assertEquals(listOf("com.android.chrome"), search.apps)
        assertEquals("search", search.symbolIconName)
        val learn = slots.first { it.name == "Learn" }
        assertEquals(listOf("com.duolingo"), learn.apps)
        val connect = slots.first { it.name == "Connect" }
        assertEquals(listOf("com.whatsapp"), connect.apps)
        val util = slots.first { it.name == "Util" }
        assertTrue(util.apps.isEmpty())
    }

    @Test
    fun migrateLegacySlots_mapsSinglesIntoPresets() {
        val installed = setOf("com.android.chrome", "com.example.other")
        val legacy = listOf(
            QuickLaunchSlot.Single("com.android.chrome"),
            QuickLaunchSlot.Single("com.example.other"),
        )
        val migrated = IntentFolderPresets.migrateLegacySlots(legacy, installed)
        val search = migrated.first { it.name == "Search" }
        assertEquals(listOf("com.android.chrome"), search.apps)
        val util = migrated.first { it.name == "Util" }
        assertEquals(listOf("com.example.other"), util.apps)
    }
}
