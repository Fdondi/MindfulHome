package com.mindfulhome.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mindfulhome.MainActivity
import com.mindfulhome.logging.SessionLogger

class ScreenUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            SessionLogger.startSession()
            MainActivity.shouldShowTimer = true
        }
    }
}
