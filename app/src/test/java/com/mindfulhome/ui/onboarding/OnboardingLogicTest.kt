package com.mindfulhome.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingLogicTest {

    @Test
    fun notificationStepSkipsLegacyUsageAccess() {
        assertEquals(5, OnboardingLogic.STEP_AFTER_NOTIFICATIONS)
        assertEquals(5, OnboardingLogic.STEP_AFTER_LEGACY_USAGE)
    }

    @Test
    fun resumeOnboarding_skipsRemovedExplanationPages() {
        assertEquals(OnboardingLogic.Resume.Continue(0), OnboardingLogic.resumeOnboarding(0))
        assertEquals(OnboardingLogic.Resume.Continue(2), OnboardingLogic.resumeOnboarding(1))
        assertEquals(OnboardingLogic.Resume.Continue(8), OnboardingLogic.resumeOnboarding(8))
        assertEquals(OnboardingLogic.Resume.Complete, OnboardingLogic.resumeOnboarding(9))
        assertEquals(OnboardingLogic.Resume.Complete, OnboardingLogic.resumeOnboarding(11))
    }
}
