package com.mindfulhome

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.IntentFilter
import android.util.Log
import com.mindfulhome.data.AppDatabase
import com.mindfulhome.logging.DailyLogSummaryScheduler
import com.mindfulhome.logging.DailyLogSummaryStartupBackfill
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.receiver.ScreenUnlockReceiver
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MindfulHomeApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    private var unlockReceiver: ScreenUnlockReceiver? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        SessionLogger.init(this, database)
        DailyLogSummaryScheduler.ensureScheduled(this)
        appScope.launch(Dispatchers.IO) {
            DailyLogSummaryStartupBackfill.runIfNeeded(applicationContext)
        }
        createNotificationChannels()
        registerUnlockReceiver()
        PackageManagerHelper.precomputeInstalledApps(this)
    }

    private fun registerUnlockReceiver() {
        val receiver = ScreenUnlockReceiver()
        val filter = IntentFilter().apply {
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter)
        unlockReceiver = receiver
        Log.d("MindfulHomeApp", "Dynamically registered ScreenUnlockReceiver for USER_PRESENT")
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val timerChannel = NotificationChannel(
            TIMER_CHANNEL_ID,
            getString(R.string.timer_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.timer_channel_description)
        }

        val nudgeChannel = NotificationChannel(
            NUDGE_CHANNEL_ID,
            getString(R.string.nudge_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.nudge_channel_description)
        }

        notificationManager.createNotificationChannel(timerChannel)
        notificationManager.createNotificationChannel(nudgeChannel)
    }

    companion object {
        const val TIMER_CHANNEL_ID = "timer_countdown"
        const val NUDGE_CHANNEL_ID = "mindful_nudges"
    }
}
