package com.mindfulhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinShortcutLogicTest {

    @Test
    fun classifyPinShortcutIntent_paths() {
        assertTrue(
            classifyPinShortcutIntent(
                pinRequestValid = true,
                pinRequestType = 1,
                shortcutRequestType = 1,
                hasShortcutInfo = true,
                legacyIntentUri = null,
                legacyLabel = null,
                legacyPackage = null,
            ) is PinShortcutPath.ModernShortcut,
        )
        assertEquals(
            PinShortcutPath.UnsupportedPinType,
            classifyPinShortcutIntent(
                pinRequestValid = true,
                pinRequestType = 2,
                shortcutRequestType = 1,
                hasShortcutInfo = false,
                legacyIntentUri = null,
                legacyLabel = null,
                legacyPackage = null,
            ),
        )
        val legacy = classifyPinShortcutIntent(
            pinRequestValid = false,
            pinRequestType = null,
            shortcutRequestType = 1,
            hasShortcutInfo = false,
            legacyIntentUri = "intent://x",
            legacyLabel = "L",
            legacyPackage = "com.x",
        ) as PinShortcutPath.LegacyShortcut
        assertEquals("com.x", legacy.packageName)
        assertEquals(
            PinShortcutPath.None,
            classifyPinShortcutIntent(
                pinRequestValid = false,
                pinRequestType = null,
                shortcutRequestType = 1,
                hasShortcutInfo = false,
                legacyIntentUri = null,
                legacyLabel = null,
                legacyPackage = null,
            ),
        )
    }

    @Test
    fun resolveLegacyShortcutLabel() {
        assertEquals("Nice", resolveLegacyShortcutLabel("Nice", "com.x"))
        assertEquals("com.x", resolveLegacyShortcutLabel("  ", "com.x"))
        assertEquals("com.x", resolveLegacyShortcutLabel(null, "com.x"))
    }
}
