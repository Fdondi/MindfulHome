package com.mindfulhome

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.mindfulhome.data.AppDatabase
import com.mindfulhome.logging.SessionLogger

class MindfulHomeApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        SessionLogger.init(this)
        createNotificationChannels()
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
