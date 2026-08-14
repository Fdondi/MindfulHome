package com.mindfulhome.receiver

/**
 * Pure unlock-receive decisions extracted from [ScreenUnlockReceiver].
 *
 * `ACTION_USER_PRESENT` is also fired for things that are not a real unlock (overlay
 * permission, some system UI). Only treat it as a return-from-absence when we previously
 * recorded [UnlockAction.RecordAbsence].
 */
object ScreenUnlockReceiverLogic {

    sealed class UnlockAction {
        data object Ignore : UnlockAction()
        data object RecordAbsence : UnlockAction()
        data object SkipNoAbsence : UnlockAction()
        data object SkipOnboarding : UnlockAction()
        data object ResumeQuickLaunch : UnlockAction()
        data object SkipQuickReturn : UnlockAction()
        data object LaunchTimer : UnlockAction()
    }

    fun hasRecordedAbsence(screenOffTimestamp: Long): Boolean = screenOffTimestamp > 0L

    fun awayMs(screenOffTimestamp: Long, nowMs: Long): Long =
        if (hasRecordedAbsence(screenOffTimestamp)) nowMs - screenOffTimestamp else Long.MAX_VALUE

    fun shouldConsumeAbsence(action: UnlockAction): Boolean = when (action) {
        UnlockAction.Ignore,
        UnlockAction.RecordAbsence,
        UnlockAction.SkipNoAbsence,
        -> false
        else -> true
    }

    fun decideUnlockAction(
        isScreenOff: Boolean,
        isUserPresent: Boolean,
        onboardingDone: Boolean,
        hasRecordedAbsence: Boolean,
        quickLaunchSessionActive: Boolean,
        awayMs: Long,
        thresholdMs: Long,
        hasSavedSession: Boolean,
    ): UnlockAction {
        if (isScreenOff) return UnlockAction.RecordAbsence
        if (!isUserPresent) return UnlockAction.Ignore
        if (!hasRecordedAbsence) return UnlockAction.SkipNoAbsence
        return decidePresentAfterAbsence(
            onboardingDone = onboardingDone,
            quickLaunchSessionActive = quickLaunchSessionActive,
            awayMs = awayMs,
            thresholdMs = thresholdMs,
            hasSavedSession = hasSavedSession,
        )
    }

    fun decidePresentAfterAbsence(
        onboardingDone: Boolean,
        quickLaunchSessionActive: Boolean,
        awayMs: Long,
        thresholdMs: Long,
        hasSavedSession: Boolean,
    ): UnlockAction = when {
        !onboardingDone -> UnlockAction.SkipOnboarding
        quickLaunchSessionActive -> UnlockAction.ResumeQuickLaunch
        awayMs < thresholdMs && hasSavedSession -> UnlockAction.SkipQuickReturn
        else -> UnlockAction.LaunchTimer
    }
}
