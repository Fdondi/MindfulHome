package com.mindfulhome.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingLogicTest {

    @Test
    fun notificationStepSkipsLegacyUsageAccess() {
        assertEquals(5, OnboardingLogic.STEP_AFTER_NOTIFICATIONS)
        assertEquals(5, OnboardingLogic.STEP_AFTER_LEGACY_USAGE)
    }
}
