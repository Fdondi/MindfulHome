package com.mindfulhome.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.mindfulhome.settings.SettingsManager

class SettingsLogicTest {

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
        val granted = permissionCardCopy(SettingsPermissionKind.UsageAccess, granted = true)
        assertEquals("Usage Access", granted.title)
        assertNull(granted.actionLabel)
        assertTrueContains(granted.description, "Granted")

        val skipped = permissionCardCopy(
            SettingsPermissionKind.UsageAccess,
            granted = false,
            skippedPrompt = true,
        )
        assertEquals("Grant", skipped.actionLabel)
        assertTrueContains(skipped.description, "skip")

        val missing = permissionCardCopy(SettingsPermissionKind.UsageAccess, granted = false)
        assertEquals("Grant", missing.actionLabel)
        assertTrueContains(missing.description, "karma")
    }

    @Test
    fun permissionCardCopy_notificationKeepsOpenSettingsWhenGranted() {
        val granted = permissionCardCopy(SettingsPermissionKind.Notification, granted = true)
        assertEquals("Open Settings", granted.actionLabel)
    }

    @Test
    fun permissionCardCopy_accessibilityTitles() {
        val on = permissionCardCopy(SettingsPermissionKind.Accessibility, granted = true)
        assertTrueContains(on.title, "On")
        assertEquals("Open Accessibility settings", on.actionLabel)

        val off = permissionCardCopy(SettingsPermissionKind.Accessibility, granted = false)
        assertTrueContains(off.title, "Off")
        assertEquals("Enable", off.actionLabel)
    }

    private fun assertTrueContains(haystack: String, needle: String) {
        assertTrue(haystack.contains(needle), "Expected \"$haystack\" to contain \"$needle\"")
    }

    private fun assertTrue(condition: Boolean, message: String) {
        org.junit.Assert.assertTrue(message, condition)
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
