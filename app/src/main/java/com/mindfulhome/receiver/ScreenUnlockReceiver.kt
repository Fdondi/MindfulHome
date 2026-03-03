package com.mindfulhome.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mindfulhome.MainActivity
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.settings.SettingsManager

class ScreenUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")

        if (intent.action != Intent.ACTION_USER_PRESENT) return

        val screenOffTimestamp = SettingsManager.getScreenOffTimestamp(context)
        val awayMs = if (screenOffTimestamp > 0)
            System.currentTimeMillis() - screenOffTimestamp
        else
            Long.MAX_VALUE

        val thresholdMs =
            SettingsManager.getQuickReturnMinutes(context) * 60_000L
        val savedSession = SettingsManager.getLastSession(context)

        Log.d(TAG, "awayMs=$awayMs thresholdMs=$thresholdMs savedSession=$savedSession")

        if (awayMs < thresholdMs && savedSession != null) {
            Log.d(TAG, "Quick return with saved session — skipping timer")
            return
        }

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
