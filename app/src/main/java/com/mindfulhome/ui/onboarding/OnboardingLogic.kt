package com.mindfulhome.ui.onboarding

object OnboardingLogic {
    /** Overlay; skip the legacy Usage Access step (4). Asked later only if Accessibility is skipped. */
    const val STEP_AFTER_NOTIFICATIONS = 5
    const val STEP_AFTER_LEGACY_USAGE = 5
    const val LAST_SETUP_STEP = 8
    const val FIRST_REMOVED_EXPLANATION_STEP = 9
    const val WELCOME_STEP = 0
    const val SKIPPED_PHILOSOPHY_STEP = 1
    const val DEFAULT_HOME_STEP = 2

    sealed interface Resume {
        data class Continue(val step: Int) : Resume
        data object Complete : Resume
    }

    fun resumeOnboarding(savedStep: Int): Resume {
        if (savedStep >= FIRST_REMOVED_EXPLANATION_STEP) return Resume.Complete
        if (savedStep == SKIPPED_PHILOSOPHY_STEP) return Resume.Continue(DEFAULT_HOME_STEP)
        return Resume.Continue(savedStep.coerceIn(WELCOME_STEP, LAST_SETUP_STEP))
    }
}
