package com.mindfulhome.ui.onboarding

object OnboardingLogic {
    /** Overlay; skip the legacy Usage Access step (4). Asked later only if Accessibility is skipped. */
    const val STEP_AFTER_NOTIFICATIONS = 5
    const val STEP_AFTER_LEGACY_USAGE = 5

    fun shouldSkipUsageAccess(accessibilityEnabled: Boolean): Boolean = accessibilityEnabled

    /**
     * Language Continue waits for AppCompat recreation so Welcome loads in the new locale.
     * When the locale list does not change (System default on first launch), advance in place.
     */
    fun shouldAdvanceLanguagePickerInPlace(willRecreateActivity: Boolean): Boolean =
        !willRecreateActivity
}
