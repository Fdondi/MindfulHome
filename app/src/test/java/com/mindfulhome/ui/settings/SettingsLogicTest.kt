package com.mindfulhome.ui.settings

import com.mindfulhome.R
import com.mindfulhome.settings.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {

    private val enStrings = mapOf(
        R.string.grant to "Grant",
        R.string.enable to "Enable",
        R.string.open_settings to "Open Settings",
        R.string.open_accessibility_settings to "Open Accessibility settings",
        R.string.perm_skipped to "Missing. You chose to skip permission reminders. Grant anytime from here.",
        R.string.perm_usage_granted to "Granted. MindfulHome can track which app is in the foreground.",
        R.string.perm_usage_required to "Required for karma tracking. Tap to grant.",
        R.string.perm_notification_granted to "Granted. MindfulHome can show timer and nudge notifications.",
        R.string.perm_notification_required to "Required for timer countdown and nudge notifications.",
        R.string.perm_overlay_granted to "Granted. Nudge reminders will appear over any app.",
        R.string.perm_overlay_required to "Not granted. Nudges will only appear as notifications.",
        R.string.perm_accessibility_on_title to "App-switch detection — On ✓",
        R.string.perm_accessibility_off_title to "App-switch detection — Off (optional)",
        R.string.perm_accessibility_on_description to "On. MindfulHome is notified.",
        R.string.perm_accessibility_off_description to "Off. Right now MindfulHome checks.",
    )

    private fun copy(
        kind: SettingsPermissionKind,
        granted: Boolean,
        skippedPrompt: Boolean = false,
        permissionTitle: String? = null,
    ) = permissionCardCopy(
        getString = { id -> enStrings.getValue(id) },
        kind = kind,
        granted = granted,
        skippedPrompt = skippedPrompt,
        permissionTitle = permissionTitle,
    )

    @Test
    fun formatMinutesOfDay_padsAndClamps() {
        assertEquals("00:00", formatMinutesOfDay(0))
        assertEquals("09:05", formatMinutesOfDay(9 * 60 + 5))
        assertEquals("23:59", formatMinutesOfDay(1439))
        assertEquals("23:59", formatMinutesOfDay(99999))
        assertEquals("00:00", formatMinutesOfDay(-5))
    }

    @Test
    fun suggestedNewInterval_defaultsAndChainsFromLastEnd() {
        val first = suggestedNewInterval(emptyList())
        assertEquals(9 * 60, first.startMinutes)
        assertEquals(10 * 60, first.endMinutes)

        val next = suggestedNewInterval(
            listOf(SettingsManager.FocusInterval(8 * 60, 9 * 60 + 30)),
        )
        assertEquals(9 * 60 + 30, next.startMinutes)
        assertEquals(10 * 60 + 30, next.endMinutes)

        val wraps = suggestedNewInterval(
            listOf(SettingsManager.FocusInterval(23 * 60, 23 * 60 + 30)),
        )
        assertEquals(23 * 60 + 30, wraps.startMinutes)
        assertEquals(30, wraps.endMinutes)
    }

    @Test
    fun parseNonNegativeIntOrEmpty_acceptsDigitsAndEmpty() {
        assertEquals("", parseNonNegativeIntOrEmpty(""))
        assertEquals("0", parseNonNegativeIntOrEmpty("0"))
        assertEquals("42", parseNonNegativeIntOrEmpty("42"))
        assertNull(parseNonNegativeIntOrEmpty("1a"))
        assertNull(parseNonNegativeIntOrEmpty("-1"))
        assertNull(parseNonNegativeIntOrEmpty(" "))
    }

    @Test
    fun permissionCardCopy_usageAccessStates() {
        val granted = copy(
            SettingsPermissionKind.UsageAccess,
            granted = true,
            permissionTitle = "Usage Access",
        )
        assertEquals("Usage Access", granted.title)
        assertNull(granted.actionLabel)
        assertTrueContains(granted.description, "Granted")

        val skipped = copy(
            SettingsPermissionKind.UsageAccess,
            granted = false,
            skippedPrompt = true,
            permissionTitle = "Usage Access",
        )
        assertEquals("Grant", skipped.actionLabel)
        assertTrueContains(skipped.description, "skip")

        val missing = copy(
            SettingsPermissionKind.UsageAccess,
            granted = false,
            permissionTitle = "Usage Access",
        )
        assertEquals("Grant", missing.actionLabel)
        assertTrueContains(missing.description, "karma")
    }

    @Test
    fun permissionCardCopy_notificationKeepsOpenSettingsWhenGranted() {
        val granted = copy(
            SettingsPermissionKind.Notification,
            granted = true,
            permissionTitle = "Notification Permission",
        )
        assertEquals("Open Settings", granted.actionLabel)
    }

    @Test
    fun permissionCardCopy_accessibilityTitles() {
        val on = copy(SettingsPermissionKind.Accessibility, granted = true)
        assertTrueContains(on.title, "On")
        assertEquals("Open Accessibility settings", on.actionLabel)

        val off = copy(SettingsPermissionKind.Accessibility, granted = false)
        assertTrueContains(off.title, "Off")
        assertEquals("Enable", off.actionLabel)
    }

    private fun assertTrueContains(haystack: String, needle: String) {
        org.junit.Assert.assertTrue(
            "Expected \"$haystack\" to contain \"$needle\"",
            haystack.contains(needle),
        )
    }

    @Test
    fun onDeviceModelAndDailySummaryHelpers() {
        assertTrueContains(onDeviceModelDescription(true, "/x"), "installed")
        assertTrueContains(onDeviceModelDescription(false, "/models"), "adb push")
        assertEquals(0, coerceDailySummaryRegenerateN("abc", 0, 10))
        assertEquals(5, coerceDailySummaryRegenerateN("5", 0, 10))
        assertEquals(10, coerceDailySummaryRegenerateN("99", 0, 10))
        assertTrueContains(dailySummaryPromptVersionLabel(3), "3")
        assertEquals("", dailySummaryRegenerateToastSuffix(0, false, 0, 0))
        assertTrueContains(dailySummaryRegenerateToastSuffix(2, true, 0, 0), "Sign in")
        assertTrueContains(dailySummaryRegenerateToastSuffix(2, false, 0, 0), "nothing to refresh")
        assertTrueContains(dailySummaryRegenerateToastSuffix(2, false, 3, 3), "Refreshed 3")
        assertTrueContains(dailySummaryRegenerateToastSuffix(2, false, 3, 1), "of 3")
        assertEquals("Sign-in rejected. Please try again.", backendSignInErrorMessage(401, null))
        assertEquals("Your account is pending approval.", backendSignInErrorMessage(403, "PENDING_APPROVAL"))
        assertEquals("Your account access has been refused.", backendSignInErrorMessage(403, "ACCESS_REFUSED"))
        assertTrueContains(backendSignInErrorMessage(429, null), "Too many")
        assertTrueContains(backendSignInErrorMessage(500, null), "HTTP 500")
    }
}
