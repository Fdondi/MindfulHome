package com.mindfulhome.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.mindfulhome.MainActivity
import com.mindfulhome.MindfulHomeApp
import com.mindfulhome.R
import com.mindfulhome.ai.LiteRtLmManager
import com.mindfulhome.ai.NegotiationManager
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.BackendAuthHelper
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.model.TimerState
import com.mindfulhome.settings.SettingsManager
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
    private var quickLaunchMonitorJob: Job? = null
    private lateinit var repository: AppRepository
    private lateinit var karmaManager: KarmaManager
    private lateinit var overlayManager: OverlayNudgeManager

    // Nudge conversation: AI interaction lives entirely in the notification
    private var negotiationManager: NegotiationManager? = null
    private var lmManager: LiteRtLmManager? = null
    private val nudgeMessages = mutableListOf<NudgeMessage>()
    private val userPerson = Person.Builder().setName("You").setKey("user").build()
    private val aiPerson =
        Person.Builder().setName("MindfulHome").setKey("ai").setBot(true).build()

    private data class NudgeMessage(
        val text: String,
        val isFromUser: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off — stopping timer session")
                SessionLogger.log("Screen turned off — ending/suspending session")
                suspendForScreenOff()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        val app = application as MindfulHomeApp
        repository = AppRepository(app.database)
        karmaManager = KarmaManager(repository)
        overlayManager = OverlayNudgeManager(this)
        overlayManager.onDismissed = { onOverlayDismissed() }

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action startId=$startId flags=$flags")

        if (action == null) {
            // Service can be recreated with a null intent after process death.
            // Restore quick-launch monitoring if it was active.
            if (SettingsManager.isQuickLaunchSessionActive(this)) {
                Log.w(TAG, "Null intent restart - restoring quick launch monitoring")
                startForeground(
                    QUICK_LAUNCH_NOTIFICATION_ID,
                    buildQuickLaunchMonitoringNotification(),
                )
                overlayManager.showQuickLaunchFrame()
                startQuickLaunchMonitoringLoop()
            }
            return START_STICKY
        }

        when (action) {
            ACTION_START -> {
                val explicitDurationMs = intent.getLongExtra(EXTRA_DURATION_MS, -1L)
                val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 5)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                val durationMs = if (explicitDurationMs > 0L) {
                    explicitDurationMs
                } else {
                    durationMinutes * 60 * 1000L
                }
                startTimer(durationMs, packageName)
            }
            ACTION_START_QUICK_LAUNCH_SESSION -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                val allowedPackages = intent.getStringArrayListExtra(EXTRA_ALLOWED_PACKAGES)
                    ?.toSet()
                    ?: emptySet()
                startQuickLaunchSession(packageName, allowedPackages)
            }
            ACTION_TRACK_APP -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                Log.d(TAG, "track app package=$packageName")
                _currentPackage.value = packageName
                maybeForceTimerForQuickLaunchSwitch(packageName)
            }
            ACTION_EXTEND -> {
                val extraMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 5)
                extendTimer(extraMinutes)
            }
            ACTION_STOP -> {
                stopTimer()
            }
            ACTION_HANDLE_REPLY -> {
                handleNudgeReply(intent)
            }
        }
        return START_STICKY
    }

    // ── Timer lifecycle ──────────────────────────────────────────────

    private fun startTimer(durationMs: Long, packageName: String) {
        SettingsManager.clearQuickLaunchSession(this)
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissQuickLaunchFrame()
        SettingsManager.clearLastSession(this)
        SettingsManager.setTimerRunning(this, true)
        _sessionStartedAtMs.value = System.currentTimeMillis()
        _currentPackage.value = packageName
        _timerState.value = TimerState.Counting(durationMs, durationMs)

        val durationMinutesDisplay = ((durationMs + 59_999L) / 60_000L).toInt()
        val appLabel = getAppLabel(packageName)
        SessionLogger.log("Session timer started: **$durationMinutesDisplay min** ($appLabel)")

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

    private fun startQuickLaunchSession(
        initialPackageName: String,
        allowedPackages: Set<String>,
    ) {
        Log.d(
            TAG,
            "startQuickLaunchSession initial=$initialPackageName allowedCount=${allowedPackages.size}",
        )
        val normalizedAllowed = allowedPackages + initialPackageName
        SettingsManager.startQuickLaunchSession(this, normalizedAllowed)
        SettingsManager.setTimerRunning(this, false)
        SettingsManager.clearLastSession(this)
        _sessionStartedAtMs.value = 0L
        _currentPackage.value = initialPackageName
        _timerState.value = TimerState.Idle

        val appLabel = getAppLabel(initialPackageName)
        SessionLogger.log("Quick Launch started: **$appLabel** (no timer running)")

        // Keep service alive while monitoring app switches outside launcher taps.
        startForeground(
            QUICK_LAUNCH_NOTIFICATION_ID,
            buildQuickLaunchMonitoringNotification(),
        )
        overlayManager.showQuickLaunchFrame()
        startQuickLaunchMonitoringLoop()
    }

    private fun maybeForceTimerForQuickLaunchSwitch(packageName: String) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) return

        val allowedPackages = SettingsManager.getQuickLaunchPackages(this) + this.packageName
        if (packageName in allowedPackages) {
            Log.v(TAG, "quick-launch app allowed: $packageName")
            return
        }
        if (packageName.isBlank()) return

        val appLabel = getAppLabel(packageName)
        Log.d(TAG, "non-quick app detected: $packageName")
        SessionLogger.log(
            "Quick Launch exit detected: opened **$appLabel** — returning to timer"
        )
        SettingsManager.clearQuickLaunchSession(this)
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissQuickLaunchFrame()
        forceBackToTimer()
    }

    private fun startQuickLaunchMonitoringLoop() {
        quickLaunchMonitorJob?.cancel()
        Log.d(TAG, "startQuickLaunchMonitoringLoop")
        quickLaunchMonitorJob = serviceScope.launch {
            var lastSeenPackage = _currentPackage.value
            while (SettingsManager.isQuickLaunchSessionActive(this@TimerService)) {
                val foregroundPackage = UsageTracker.getForegroundApp(this@TimerService)
                if (!foregroundPackage.isNullOrBlank() && foregroundPackage != lastSeenPackage) {
                    Log.d(TAG, "foreground changed: $lastSeenPackage -> $foregroundPackage")
                    lastSeenPackage = foregroundPackage
                    _currentPackage.value = foregroundPackage
                    maybeForceTimerForQuickLaunchSwitch(foregroundPackage)
                }
                delay(QUICK_LAUNCH_MONITOR_POLL_MS)
            }
            Log.d(TAG, "quick-launch monitoring loop ended")
        }
    }

    private fun extendTimer(extraMinutes: Int) {
        val state = _timerState.value
        val extraMs = extraMinutes * 60 * 1000L

        nudgeJob?.cancel()
        _nudgeCount.value = 0
        overlayManager.dismiss()

        val appLabel = getAppLabel(_currentPackage.value)
        SessionLogger.log("Timer extended: **+$extraMinutes min** for $appLabel")

        when (state) {
            is TimerState.Expired -> {
                val pkg = _currentPackage.value
                startTimer(extraMinutes * 60 * 1000L, pkg)
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
        SessionLogger.log("**Time's up!** Session timer expired (was using $appLabel)")

        val initialMessage = "Time's up! Ready to put down $appLabel?"
        if (overlayManager.canDrawOverlay()) {
            overlayManager.show(initialMessage)
        }

        startNudgeConversation(packageName)
        _nudgeCount.value = 0
        startNudging(packageName)
    }

    private fun startNudging(packageName: String) {
        nudgeJob?.cancel()
        nudgeJob = serviceScope.launch {
            var overrunMs = 0L
            var nudgeCount = 0
            val escalationThreshold = SettingsManager.getEscalationThreshold(
                this@TimerService
            )

            while (true) {
                delay(KarmaManager.NUDGE_INTERVAL_MS)
                overrunMs += KarmaManager.NUDGE_INTERVAL_MS
                nudgeCount++
                _nudgeCount.value = nudgeCount

                _timerState.value = TimerState.Expired(overrunMs)

                karmaManager.onNudgeIgnored(packageName)

                val appLabel = getAppLabel(packageName)
                SessionLogger.log(
                    "Nudge #$nudgeCount for $appLabel (overrun ${overrunMs / 60000} min)"
                )

                val message = when {
                    nudgeCount <= 1 -> "Your time is up. Ready to put down $appLabel?"
                    nudgeCount <= 3 ->
                        "You've been on $appLabel for a while past your limit."
                    nudgeCount <= 5 ->
                        "Still on $appLabel... this is starting to cost karma."
                    else -> "Karma is dropping fast. Maybe time for a break?"
                }

                if (nudgeCount >= escalationThreshold) {
                    SessionLogger.log(
                        "Escalation: forcing back to timer after $nudgeCount nudges"
                    )
                    overlayManager.dismiss()
                    forceBackToTimer()
                    return@launch
                }

                nudgeMessages.add(NudgeMessage(message, isFromUser = false))

                if (overlayManager.canDrawOverlay()) {
                    overlayManager.update(message)
                } else {
                    showConversationNotification(packageName)
                }
            }
        }
    }

    private fun onOverlayDismissed() {
        val pkg = _currentPackage.value
        if (pkg.isEmpty()) return
        val appLabel = getAppLabel(pkg)

        SessionLogger.log("User dismissed overlay for $appLabel — treating as positive signal")
        nudgeJob?.cancel()
        _nudgeCount.value = 0

        serviceScope.launch {
            karmaManager.onClosedInGraceWindow(pkg)
        }
        overlayManager.dismiss()
        endNudgeConversation()
    }

    private fun forceBackToTimer() {
        overlayManager.dismiss()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FORCE_TIMER, true)
        }
        startActivity(intent)
    }

    private fun stopTimer() {
        timerJob?.cancel()
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissQuickLaunchFrame()

        serviceScope.launch {
            val pkg = _currentPackage.value
            val state = _timerState.value
            val appLabel = getAppLabel(pkg)

            when (state) {
                is TimerState.Counting -> {
                    karmaManager.onClosedOnTime(pkg)
                    SessionLogger.log("App closed on time: $appLabel (karma +1)")

                    val startedAtMs = _sessionStartedAtMs.value.takeIf { it > 0L }
                        ?: (System.currentTimeMillis() - (state.totalMs - state.remainingMs).coerceAtLeast(0L))
                    if (pkg.isNotEmpty()) {
                        SettingsManager.saveLastSession(
                            context = this@TimerService,
                            packageName = pkg,
                            totalDurationMs = state.totalMs,
                            startedAtMs = startedAtMs,
                            suspendedAtMs = null,
                        )
                        val remainingMinutes = ((state.remainingMs + 59_999L) / 60_000L).toInt()
                        SessionLogger.log(
                            "Saved resumable session: $appLabel ($remainingMinutes min left)"
                        )
                    }
                }
                is TimerState.Expired -> {
                    if (state.overrunMs <= KarmaManager.GRACE_WINDOW_MS) {
                        karmaManager.onClosedInGraceWindow(pkg)
                        SessionLogger.log(
                            "App closed in grace window: $appLabel " +
                                "(overrun ${state.overrunMs / 1000}s)"
                        )
                    } else {
                        SessionLogger.log(
                            "App closed after overrun: $appLabel " +
                                "(overrun ${state.overrunMs / 60000} min)"
                        )
                    }
                }
                is TimerState.Idle -> { }
            }

            _timerState.value = TimerState.Idle
            _sessionStartedAtMs.value = 0L
            _currentPackage.value = ""
            _nudgeCount.value = 0
            SettingsManager.setTimerRunning(this@TimerService, false)
            SettingsManager.clearQuickLaunchSession(this@TimerService)

            endNudgeConversation()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun suspendForScreenOff() {
        timerJob?.cancel()
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissQuickLaunchFrame()

        SettingsManager.saveScreenOffTimestamp(this)

        val pkg = _currentPackage.value
        val state = _timerState.value
        val appLabel = getAppLabel(pkg)
        val suspendedAtMs = System.currentTimeMillis()

        serviceScope.launch {
            when (state) {
                is TimerState.Counting -> {
                    val startedAtMs = _sessionStartedAtMs.value.takeIf { it > 0L }
                        ?: (suspendedAtMs - (state.totalMs - state.remainingMs).coerceAtLeast(0L))
                    if (pkg.isNotEmpty()) {
                        SettingsManager.saveLastSession(
                            context = this@TimerService,
                            packageName = pkg,
                            totalDurationMs = state.totalMs,
                            startedAtMs = startedAtMs,
                            suspendedAtMs = suspendedAtMs,
                        )
                    }
                    val elapsedMs = (suspendedAtMs - startedAtMs).coerceAtLeast(0L)
                    val remainingMs = (state.totalMs - elapsedMs).coerceAtLeast(0L)
                    val remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()
                    SessionLogger.log(
                        "Session suspended (screen off): $appLabel " +
                            "($remainingMinutes min remaining)"
                    )
                }
                is TimerState.Expired -> {
                    karmaManager.onClosedInGraceWindow(pkg)
                    SessionLogger.log(
                        "Screen off during overrun: $appLabel — positive signal " +
                            "(overrun ${state.overrunMs / 1000}s)"
                    )
                }
                is TimerState.Idle -> { }
            }

            _timerState.value = TimerState.Idle
            _sessionStartedAtMs.value = 0L
            _currentPackage.value = ""
            _nudgeCount.value = 0
            SettingsManager.setTimerRunning(this@TimerService, false)
            SettingsManager.clearQuickLaunchSession(this@TimerService)

            endNudgeConversation()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Nudge conversation (notification-only) ───────────────────────

    private fun startNudgeConversation(packageName: String) {
        nudgeMessages.clear()

        val ctx: Context = this
        val lm = LiteRtLmManager(ctx)
        lmManager = lm

        val useBackend =
            SettingsManager.getAIMode(ctx) == SettingsManager.AI_MODE_BACKEND
        val selectedModel = SettingsManager.getBackendModel(ctx)
        val backendAuth = if (useBackend) {
            BackendAuthHelper(
                signIn = { null }, // no interactive sign-in from a service
                getAppToken = { ApiKeyManager.getAppToken(ctx) },
                saveAppToken = { token, expiresAtMs ->
                    ApiKeyManager.saveAppToken(ctx, token, expiresAtMs)
                },
                clearAppToken = { ApiKeyManager.clearAppToken(ctx) },
                getGoogleIdToken = { ApiKeyManager.getGoogleIdToken(ctx) },
            )
        } else {
            null
        }

        val manager = NegotiationManager(
            lm, repository, karmaManager, backendAuth, selectedModel,
        )
        negotiationManager = manager

        val appLabel = getAppLabel(packageName)

        serviceScope.launch {
            try {
                val result = manager.startNudgeNegotiation(
                    packageName, appLabel,
                    overrunMinutes = 0, nudgeCount = 0,
                )
                nudgeMessages.add(NudgeMessage(result.responseText, isFromUser = false))
                showConversationNotification(packageName)

                if (result.extensionMinutes > 0) {
                    handleExtension(result.extensionMinutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting nudge conversation", e)
                nudgeMessages.add(
                    NudgeMessage("Time's up! Your session has ended.", isFromUser = false)
                )
                showConversationNotification(packageName)
            }
        }
    }

    private fun handleNudgeReply(intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()
        if (replyText.isNullOrBlank()) return

        val packageName = _currentPackage.value
        nudgeMessages.add(NudgeMessage(replyText, isFromUser = true))
        // Update notification immediately so the user sees their own message
        showConversationNotification(packageName)

        SessionLogger.log("User replied to nudge: ${replyText.take(120)}")

        val manager = negotiationManager
        if (manager == null) {
            nudgeMessages.add(
                NudgeMessage(
                    "Take a moment to reflect on whether you still need this app.",
                    isFromUser = false,
                )
            )
            showConversationNotification(packageName)
            return
        }

        serviceScope.launch {
            try {
                val result = manager.reply(replyText)
                nudgeMessages.add(NudgeMessage(result.responseText, isFromUser = false))
                showConversationNotification(packageName)

                SessionLogger.log("AI responded: ${result.responseText.take(120)}")

                if (result.extensionMinutes > 0) {
                    handleExtension(result.extensionMinutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling nudge reply", e)
                nudgeMessages.add(
                    NudgeMessage(
                        "Sorry, I couldn't process that. Take a moment to reflect.",
                        isFromUser = false,
                    )
                )
                showConversationNotification(packageName)
            }
        }
    }

    private fun handleExtension(minutes: Int) {
        SessionLogger.log("AI granted extension: **$minutes min**")
        endNudgeConversation()
        extendTimer(minutes)
    }

    private fun endNudgeConversation() {
        negotiationManager?.endConversation()
        negotiationManager = null
        lmManager?.shutdown()
        lmManager = null
        nudgeMessages.clear()
        overlayManager.dismiss()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NUDGE_NOTIFICATION_ID)
    }

    // ── Notification builders ────────────────────────────────────────

    private fun showConversationNotification(packageName: String) {
        if (nudgeMessages.isEmpty()) return

        // RemoteInput for inline reply
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply...")
            .build()

        val replyIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_HANDLE_REPLY
        }
        val replyPendingIntent = PendingIntent.getService(
            this, NUDGE_NOTIFICATION_ID, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground, "Reply", replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // Build the conversation as a MessagingStyle notification
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
        for (msg in nudgeMessages) {
            // In MessagingStyle, null sender = message from the device user
            val sender = if (msg.isFromUser) null else aiPerson
            messagingStyle.addMessage(
                NotificationCompat.MessagingStyle.Message(msg.text, msg.timestamp, sender)
            )
        }

        val notification = NotificationCompat.Builder(this, MindfulHomeApp.NUDGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(messagingStyle)
            .addAction(replyAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(false)
            .setOngoing(false)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NUDGE_NOTIFICATION_ID, notification)
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

    private fun buildQuickLaunchMonitoringNotification(): Notification {
        return NotificationCompat.Builder(this, MindfulHomeApp.TIMER_CHANNEL_ID)
            .setContentTitle("MindfulHome")
            .setContentText("Quick Launch active - monitoring app switches")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun getAppLabel(packageName: String): String {
        if (packageName.isEmpty()) return "your phone"
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: IllegalArgumentException) { }
        timerJob?.cancel()
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        negotiationManager?.endConversation()
        lmManager?.shutdown()
        overlayManager.dismiss()
        overlayManager.dismissQuickLaunchFrame()
        SettingsManager.setTimerRunning(this, false)
        SettingsManager.clearQuickLaunchSession(this)
        _sessionStartedAtMs.value = 0L
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TimerService"
        private const val TIMER_NOTIFICATION_ID = 1001
        private const val NUDGE_NOTIFICATION_ID = 1002
        private const val QUICK_LAUNCH_NOTIFICATION_ID = 1003
        private const val QUICK_LAUNCH_MONITOR_POLL_MS = 1500L

        const val ACTION_START = "com.mindfulhome.ACTION_START_TIMER"
        const val ACTION_START_QUICK_LAUNCH_SESSION = "com.mindfulhome.ACTION_START_QUICK_LAUNCH_SESSION"
        const val ACTION_TRACK_APP = "com.mindfulhome.ACTION_TRACK_APP"
        const val ACTION_EXTEND = "com.mindfulhome.ACTION_EXTEND_TIMER"
        const val ACTION_STOP = "com.mindfulhome.ACTION_STOP_TIMER"
        const val ACTION_HANDLE_REPLY = "com.mindfulhome.ACTION_HANDLE_REPLY"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ALLOWED_PACKAGES = "allowed_packages"

        private const val KEY_TEXT_REPLY = "key_text_reply"

        private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
        val timerState: StateFlow<TimerState> = _timerState

        private val _currentPackage = MutableStateFlow("")
        val currentPackage: StateFlow<String> = _currentPackage

        private val _sessionStartedAtMs = MutableStateFlow(0L)
        val sessionStartedAtMs: StateFlow<Long> = _sessionStartedAtMs

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

        fun startWithDurationMs(context: Context, durationMs: Long, packageName: String) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_MS, durationMs.coerceAtLeast(1_000L))
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

        fun trackApp(context: Context, packageName: String) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_TRACK_APP
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
            context.startService(intent)
        }

        fun startQuickLaunchSession(
            context: Context,
            initialPackageName: String,
            allowedQuickLaunchPackages: List<String>,
        ) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START_QUICK_LAUNCH_SESSION
                putExtra(EXTRA_PACKAGE_NAME, initialPackageName)
                putStringArrayListExtra(
                    EXTRA_ALLOWED_PACKAGES,
                    ArrayList(allowedQuickLaunchPackages),
                )
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
