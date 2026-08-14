package com.mindfulhome.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mindfulhome.MainActivity
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.service.TimerService

class ScreenUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
        applyUnlockDecision(context, decideUnlockAction(context, intent))
    }

    private fun decideUnlockAction(
        context: Context,
        intent: Intent,
    ): ScreenUnlockReceiverLogic.UnlockAction {
        val screenOffTimestamp = SettingsManager.getScreenOffTimestamp(context)
        val nowMs = System.currentTimeMillis()
        val awayMs = ScreenUnlockReceiverLogic.awayMs(screenOffTimestamp, nowMs)
        val thresholdMs = SettingsManager.getQuickReturnMinutes(context) * 60_000L
        val savedSession = SettingsManager.getLastSession(context)
        val isUserPresent = intent.action == Intent.ACTION_USER_PRESENT
        if (isUserPresent) {
            Log.d(TAG, "awayMs=$awayMs thresholdMs=$thresholdMs savedSession=$savedSession")
        }
        return ScreenUnlockReceiverLogic.decideUnlockAction(
            isScreenOff = intent.action == Intent.ACTION_SCREEN_OFF,
            isUserPresent = isUserPresent,
            onboardingDone = context.getSharedPreferences("mindfulhome", Context.MODE_PRIVATE)
                .getBoolean("onboarding_done", false),
            hasRecordedAbsence = ScreenUnlockReceiverLogic.hasRecordedAbsence(screenOffTimestamp),
            quickLaunchSessionActive = SettingsManager.isQuickLaunchSessionActive(context),
            awayMs = awayMs,
            thresholdMs = thresholdMs,
            hasSavedSession = savedSession != null,
        )
    }

    private fun applyUnlockDecision(
        context: Context,
        decision: ScreenUnlockReceiverLogic.UnlockAction,
    ) {
        persistAbsenceState(context, decision)
        dispatchUnlockAction(context, decision)
    }

    private fun persistAbsenceState(
        context: Context,
        decision: ScreenUnlockReceiverLogic.UnlockAction,
    ) {
        when {
            decision == ScreenUnlockReceiverLogic.UnlockAction.RecordAbsence ->
                SettingsManager.saveScreenOffTimestamp(context)
            ScreenUnlockReceiverLogic.shouldConsumeAbsence(decision) ->
                SettingsManager.clearScreenOffTimestamp(context)
        }
    }

    private fun dispatchUnlockAction(
        context: Context,
        decision: ScreenUnlockReceiverLogic.UnlockAction,
    ) {
        when (decision) {
            ScreenUnlockReceiverLogic.UnlockAction.Ignore,
            ScreenUnlockReceiverLogic.UnlockAction.RecordAbsence,
            -> Unit
            ScreenUnlockReceiverLogic.UnlockAction.SkipNoAbsence ->
                Log.d(TAG, "USER_PRESENT with no recorded screen-off — ignoring")
            ScreenUnlockReceiverLogic.UnlockAction.SkipOnboarding ->
                Log.d(TAG, "Onboarding in progress — skipping unlock launch")
            ScreenUnlockReceiverLogic.UnlockAction.ResumeQuickLaunch -> resumeQuickLaunch(context)
            ScreenUnlockReceiverLogic.UnlockAction.SkipQuickReturn ->
                Log.d(TAG, "Quick return with saved session — skipping timer")
            ScreenUnlockReceiverLogic.UnlockAction.LaunchTimer -> launchTimerFromUnlock(context)
        }
    }

    private fun resumeQuickLaunch(context: Context) {
        Log.d(TAG, "Quick Launch session active on unlock — skipping timer")
        TimerService.resumeQuickLaunchMonitoring(context, SessionLogger.getActiveSessionHandle())
    }

    private fun launchTimerFromUnlock(context: Context) {
        Log.d(TAG, "Launching MainActivity with timer screen")
        MainActivity.shouldShowTimer = true
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_FROM_UNLOCK, true)
        }
        context.startActivity(launch)
    }

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
        const val EXTRA_FROM_UNLOCK = "from_unlock"
    }
}
