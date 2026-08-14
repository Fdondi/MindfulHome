package com.mindfulhome.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {

    @Test
    fun shouldSkipUsageAccess_whenAccessibilityEnabled() {
        assertTrue(OnboardingLogic.shouldSkipUsageAccess(true))
        assertFalse(OnboardingLogic.shouldSkipUsageAccess(false))
    }

    @Test
    fun notificationStepSkipsLegacyUsageAccess() {
        assertEquals(5, OnboardingLogic.STEP_AFTER_NOTIFICATIONS)
        assertEquals(5, OnboardingLogic.STEP_AFTER_LEGACY_USAGE)
    }

    @Test
    fun languagePickerAdvancesInPlaceWhenAppCompatWillNotRecreate() {
        assertTrue(OnboardingLogic.shouldAdvanceLanguagePickerInPlace(willRecreateActivity = false))
        assertFalse(OnboardingLogic.shouldAdvanceLanguagePickerInPlace(willRecreateActivity = true))
    }
}
