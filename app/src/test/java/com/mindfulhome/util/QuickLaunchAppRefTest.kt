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
        assertEquals(shortcut, QuickLaunchAppRef.parseShortcut(key))
    }

    @Test
    fun roundTrip_urlLikeShortcutId() {
        val shortcut = PinnedShortcut(
            "com.brave.browser",
            "https://example.com/path/to/page",
            "Example",
        )
        val key = QuickLaunchAppRef.shortcutKey(shortcut)
        assertEquals(shortcut, QuickLaunchAppRef.parseShortcut(key))
        assertEquals("com.brave.browser", QuickLaunchAppRef.ownerPackage(key))
    }

    @Test
    fun parseShortcut_rejectsMalformedKeys() {
        assertNull(QuickLaunchAppRef.parseShortcut("com.brave.browser/id"))
        assertNull(QuickLaunchAppRef.parseShortcut("sc:onlypkg"))
    }
}
