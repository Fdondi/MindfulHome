package com.mindfulhome.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenUnlockReceiverLogicTest {

    @Test
    fun hasRecordedAbsence_requiresScreenOffTimestamp() {
        assertFalse(ScreenUnlockReceiverLogic.hasRecordedAbsence(0L))
        assertTrue(ScreenUnlockReceiverLogic.hasRecordedAbsence(10_000L))
    }

    @Test
    fun awayMs_usesElapsedWhenScreenOffKnown() {
        assertEquals(5_000L, ScreenUnlockReceiverLogic.awayMs(screenOffTimestamp = 10_000L, nowMs = 15_000L))
        assertEquals(Long.MAX_VALUE, ScreenUnlockReceiverLogic.awayMs(screenOffTimestamp = 0L, nowMs = 15_000L))
    }

    @Test
    fun decideUnlockAction_recordsScreenOff() {
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.RecordAbsence,
            decide(isScreenOff = true, isUserPresent = false),
        )
    }

    @Test
    fun decideUnlockAction_ignoresNonUserPresent() {
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.Ignore,
            decide(isUserPresent = false),
        )
    }

    @Test
    fun decideUnlockAction_ignoresUserPresentWithoutRecordedAbsence() {
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.SkipNoAbsence,
            decide(isUserPresent = true, hasRecordedAbsence = false, onboardingDone = true),
        )
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.SkipNoAbsence,
            decide(
                isUserPresent = true,
                hasRecordedAbsence = false,
                onboardingDone = true,
                quickLaunchSessionActive = true,
            ),
        )
    }

    @Test
    fun decideUnlockAction_skipsDuringOnboardingAfterAbsence() {
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.SkipOnboarding,
            decide(isUserPresent = true, hasRecordedAbsence = true, onboardingDone = false),
        )
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.SkipOnboarding,
            decide(
                isUserPresent = true,
                hasRecordedAbsence = true,
                onboardingDone = false,
                quickLaunchSessionActive = true,
            ),
        )
    }

    @Test
    fun decideUnlockAction_resumesQuickLaunchWhenOnboardingDone() {
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.ResumeQuickLaunch,
            decide(
                isUserPresent = true,
                hasRecordedAbsence = true,
                onboardingDone = true,
                quickLaunchSessionActive = true,
            ),
        )
    }

    @Test
    fun decideUnlockAction_skipsQuickReturnWithSavedSession() {
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.SkipQuickReturn,
            decide(
                isUserPresent = true,
                hasRecordedAbsence = true,
                onboardingDone = true,
                awayMs = 10_000L,
                thresholdMs = 180_000L,
                hasSavedSession = true,
            ),
        )
    }

    @Test
    fun decideUnlockAction_launchesTimerAfterRecordedAbsence() {
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.LaunchTimer,
            decide(isUserPresent = true, hasRecordedAbsence = true, onboardingDone = true),
        )
        assertEquals(
            ScreenUnlockReceiverLogic.UnlockAction.LaunchTimer,
            decide(
                isUserPresent = true,
                hasRecordedAbsence = true,
                onboardingDone = true,
                awayMs = 10_000L,
                thresholdMs = 180_000L,
                hasSavedSession = false,
            ),
        )
    }

    @Test
    fun shouldConsumeAbsence_onlyAfterHandlingARealReturn() {
        assertFalse(
            ScreenUnlockReceiverLogic.shouldConsumeAbsence(
                ScreenUnlockReceiverLogic.UnlockAction.Ignore,
            ),
        )
        assertFalse(
            ScreenUnlockReceiverLogic.shouldConsumeAbsence(
                ScreenUnlockReceiverLogic.UnlockAction.RecordAbsence,
            ),
        )
        assertFalse(
            ScreenUnlockReceiverLogic.shouldConsumeAbsence(
                ScreenUnlockReceiverLogic.UnlockAction.SkipNoAbsence,
            ),
        )
        assertTrue(
            ScreenUnlockReceiverLogic.shouldConsumeAbsence(
                ScreenUnlockReceiverLogic.UnlockAction.LaunchTimer,
            ),
        )
        assertTrue(
            ScreenUnlockReceiverLogic.shouldConsumeAbsence(
                ScreenUnlockReceiverLogic.UnlockAction.SkipOnboarding,
            ),
        )
    }

    private fun decide(
        isScreenOff: Boolean = false,
        isUserPresent: Boolean,
        onboardingDone: Boolean = true,
        hasRecordedAbsence: Boolean = false,
        quickLaunchSessionActive: Boolean = false,
        awayMs: Long = Long.MAX_VALUE,
        thresholdMs: Long = 180_000L,
        hasSavedSession: Boolean = false,
    ) = ScreenUnlockReceiverLogic.decideUnlockAction(
        isScreenOff = isScreenOff,
        isUserPresent = isUserPresent,
        onboardingDone = onboardingDone,
        hasRecordedAbsence = hasRecordedAbsence,
        quickLaunchSessionActive = quickLaunchSessionActive,
        awayMs = awayMs,
        thresholdMs = thresholdMs,
        hasSavedSession = hasSavedSession,
    )
}
