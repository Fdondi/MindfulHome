package com.mindfulhome.util

import com.mindfulhome.data.PinnedShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickLaunchAppRefTest {

    @Test
    fun roundTrip_uuidShortcutId() {
        val shortcut = PinnedShortcut("com.android.chrome", "729fe25f-bd6e-4d67-bf1e-85a579044ef4", "Site")
        val key = QuickLaunchAppRef.shortcutKey(shortcut)
        assertEquals("sc:com.android.chrome/729fe25f-bd6e-4d67-bf1e-85a579044ef4", key)
        val parsed = QuickLaunchAppRef.parseShortcut(key)!!
        assertEquals(shortcut.packageName, parsed.packageName)
        assertEquals(shortcut.id, parsed.id)
    }

    @Test
    fun roundTrip_urlLikeShortcutId() {
        val shortcut = PinnedShortcut(
            "com.brave.browser",
            "https://example.com/path/to/page",
            "Example",
        )
        val key = QuickLaunchAppRef.shortcutKey(shortcut)
        val parsed = QuickLaunchAppRef.parseShortcut(key)!!
        assertEquals(shortcut.packageName, parsed.packageName)
        assertEquals(shortcut.id, parsed.id)
        assertEquals("com.brave.browser", QuickLaunchAppRef.ownerPackage(key))
    }

    @Test
    fun parseShortcut_rejectsMalformedKeys() {
        assertNull(QuickLaunchAppRef.parseShortcut("com.brave.browser/id"))
        assertNull(QuickLaunchAppRef.parseShortcut("sc:onlypkg"))
    }
}
