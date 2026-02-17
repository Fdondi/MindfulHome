package com.mindfulhome.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mindfulhome.MindfulHomeApp
import com.mindfulhome.R
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.model.TimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TimerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null
    private var nudgeJob: Job? = null
    private lateinit var repository: AppRepository
    private lateinit var karmaManager: KarmaManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as MindfulHomeApp
        repository = AppRepository(app.database)
        karmaManager = KarmaManager(repository)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 5)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                startTimer(durationMinutes, packageName)
            }
            ACTION_EXTEND -> {
                val extraMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 5)
                extendTimer(extraMinutes)
            }
            ACTION_STOP -> {
                stopTimer()
            }
        }
        return START_STICKY
    }

    private fun startTimer(durationMinutes: Int, packageName: String) {
        val durationMs = durationMinutes * 60 * 1000L
        _currentPackage.value = packageName
        _timerState.value = TimerState.Counting(durationMs, durationMs)

        val appLabel = getAppLabel(packageName)
        SessionLogger.log("Timer started: **$durationMinutes min** for $appLabel")

        startForeground(TIMER_NOTIFICATION_ID, buildTimerNotification(durationMs))

        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var remainingMs = durationMs
            while (remainingMs > 0) {
                delay(1000)
                remainingMs -= 1000
                _timerState.value = TimerState.Counting(remainingMs, durationMs)
                updateTimerNotification(remainingMs)
            }
            onTimerExpired(packageName)
        }
    }

    private fun extendTimer(extraMinutes: Int) {
        val state = _timerState.value
        val extraMs = extraMinutes * 60 * 1000L

        nudgeJob?.cancel()
        _nudgeCount.value = 0

        val appLabel = getAppLabel(_currentPackage.value)
        SessionLogger.log("Timer extended: **+$extraMinutes min** for $appLabel")

        when (state) {
            is TimerState.Expired -> {
                val pkg = _currentPackage.value
                startTimer(extraMinutes, pkg)
            }
            is TimerState.Counting -> {
                val newRemaining = state.remainingMs + extraMs
                val newTotal = state.totalMs + extraMs
                _timerState.value = TimerState.Counting(newRemaining, newTotal)
            }
            is TimerState.Idle -> { }
        }
    }

    private fun onTimerExpired(packageName: String) {
        _timerState.value = TimerState.Expired(0)
        val appLabel = getAppLabel(packageName)
        SessionLogger.log("Timer expired for $appLabel")
        startNudging(packageName)
    }

    private fun startNudging(packageName: String) {
        nudgeJob?.cancel()
        nudgeJob = serviceScope.launch {
            var overrunMs = 0L
            var nudgeCount = 0

            while (true) {
                delay(KarmaManager.NUDGE_INTERVAL_MS)
                overrunMs += KarmaManager.NUDGE_INTERVAL_MS
                nudgeCount++
                _nudgeCount.value = nudgeCount

                _timerState.value = TimerState.Expired(overrunMs)

                // Each nudge interval accrues karma penalty
                karmaManager.onNudgeIgnored(packageName)

                val appLabel = getAppLabel(packageName)
                SessionLogger.log("Nudge #$nudgeCount sent for $appLabel (overrun ${overrunMs / 60000} min)")

                // Send increasingly insistent nudge notifications
                sendNudgeNotification(nudgeCount, packageName)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        nudgeJob?.cancel()

        serviceScope.launch {
            val pkg = _currentPackage.value
            val state = _timerState.value
            val appLabel = getAppLabel(pkg)

            when (state) {
                is TimerState.Counting -> {
                    karmaManager.onClosedOnTime(pkg)
                    SessionLogger.log("App closed on time: $appLabel (karma +1)")
                }
                is TimerState.Expired -> {
                    if (state.overrunMs <= KarmaManager.GRACE_WINDOW_MS) {
                        karmaManager.onClosedInGraceWindow(pkg)
                        SessionLogger.log("App closed in grace window: $appLabel (overrun ${state.overrunMs / 1000}s)")
                    } else {
                        SessionLogger.log("App closed after overrun: $appLabel (overrun ${state.overrunMs / 60000} min)")
                    }
                }
                is TimerState.Idle -> { /* nothing */ }
            }

            _timerState.value = TimerState.Idle
            _currentPackage.value = ""
            _nudgeCount.value = 0

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(NUDGE_NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildTimerNotification(remainingMs: Long): Notification {
        val minutes = (remainingMs / 60000).toInt()
        val seconds = ((remainingMs % 60000) / 1000).toInt()

        return NotificationCompat.Builder(this, MindfulHomeApp.TIMER_CHANNEL_ID)
            .setContentTitle("MindfulHome")
            .setContentText("$minutes:${seconds.toString().padStart(2, '0')} remaining")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateTimerNotification(remainingMs: Long) {
        val notification = buildTimerNotification(remainingMs)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(TIMER_NOTIFICATION_ID, notification)
    }

    private fun sendNudgeNotification(nudgeCount: Int, packageName: String) {
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "this app"
        }

        val message = when {
            nudgeCount <= 1 -> "Your time is up. Ready to put down $appLabel?"
            nudgeCount <= 3 -> "You've been on $appLabel for a while past your limit."
            nudgeCount <= 5 -> "Still on $appLabel... this is starting to cost karma."
            else -> "$appLabel is losing karma fast. Maybe time for a break?"
        }

        // Tapping the notification opens the AI nudge chat
        val chatIntent = Intent(this, com.mindfulhome.MainActivity::class.java).apply {
            action = INTENT_ACTION_NUDGE_CHAT
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MindfulHomeApp.NUDGE_CHANNEL_ID)
            .setContentTitle("Mindful Nudge")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NUDGE_NOTIFICATION_ID, notification)
    }

    private fun getAppLabel(packageName: String): String {
        if (packageName.isEmpty()) return "unknown"
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        nudgeJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TIMER_NOTIFICATION_ID = 1001
        private const val NUDGE_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.mindfulhome.ACTION_START_TIMER"
        const val ACTION_EXTEND = "com.mindfulhome.ACTION_EXTEND_TIMER"
        const val ACTION_STOP = "com.mindfulhome.ACTION_STOP_TIMER"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val INTENT_ACTION_NUDGE_CHAT = "com.mindfulhome.NUDGE_CHAT"

        private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
        val timerState: StateFlow<TimerState> = _timerState

        private val _currentPackage = MutableStateFlow("")
        val currentPackage: StateFlow<String> = _currentPackage

        private val _nudgeCount = MutableStateFlow(0)
        val nudgeCount: StateFlow<Int> = _nudgeCount

        fun start(context: Context, durationMinutes: Int, packageName: String) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
            context.startForegroundService(intent)
        }

        fun extend(context: Context, extraMinutes: Int) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_EXTEND
                putExtra(EXTRA_DURATION_MINUTES, extraMinutes)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
