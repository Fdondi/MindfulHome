package com.mindfulhome.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ApplicationInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Process
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class TimerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null
    private var nudgeJob: Job? = null
    private var quickLaunchMonitorJob: Job? = null
    private var quickLaunchGraceJob: Job? = null
    private var notificationInteractionTimeoutJob: Job? = null
    private var quickLaunchExitCandidatePackage: String? = null
    private var quickLaunchExitCandidateStartedAtMs: Long = 0L
    private var quickLaunchExitCandidateLabel: String? = null
    private var lastQuickLaunchNotificationText: String? = null
    private var quickLaunchFrameSuppressedForSensitiveApp: Boolean = false
    private lateinit var repository: AppRepository
    private lateinit var karmaManager: KarmaManager
    private lateinit var overlayManager: OverlayNudgeManager
    private var nudgePauseUntilMs: Long = 0L
    private var nudgeResetRequested: Boolean = false
    private var awaitingNotificationInteraction: Boolean = false
    private var preferBannerFallbackForOverlayTap: Boolean = false
    private var logSessionHandle: SessionLogger.SessionHandle? = null
    private var hardDeadlineAtMs: Long? = null
    private var softDeadlineAtMs: Long? = null
    private var userAwayOverlayActive: Boolean = false
    private var awayShieldShownForCurrentAwayEpisode: Boolean = false
    private var lastAwayOverlayTapAtMs: Long = 0L

    // Nudge conversation: notification is the single chat surface.
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

    private enum class NudgeStage {
        WAITING_AFTER_NOTIFICATION,
        BUBBLES,
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off — stopping timer session")
                logWithSession("Screen turned off — ending/suspending session")
                suspendForScreenOff()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        logSessionEvent("Timer service created")
        val app = application as MindfulHomeApp
        repository = AppRepository(app.database)
        karmaManager = KarmaManager(this, repository)
        serviceScope.launch {
            karmaManager.runDailyRecoveryIfDue()
        }
        overlayManager = OverlayNudgeManager(this)
        overlayManager.onDismissed = { onOverlayDismissed() }
        overlayManager.onNotificationRequested = { onOverlayNotificationRequested() }
        overlayManager.onBannerReplySubmitted = { onBannerReplySubmitted(it) }
        overlayManager.onAwayShieldTapped = { onAwayShieldTapped() }
        overlayManager.onAwayReturnRequested = { onAwayReturnRequested() }
        preferBannerFallbackForOverlayTap = SettingsManager.isNudgeBannerFallbackArmed(this)
        logSessionHandle = SessionLogger.getActiveSessionHandle()

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        updateLogSessionHandleFromIntent(intent)
        Log.d(
            TAG,
            "onStartCommand action=$action startId=$startId flags=$flags sessionToken=${sessionTokenForLogs()}",
        )
        logSessionEvent("Service command received: ${action ?: "null"}")

        if (action == null) {
            // Service can be recreated with a null intent after process death.
            // Restore quick-launch monitoring if it was active.
            if (SettingsManager.isQuickLaunchSessionActive(this)) {
                Log.w(TAG, "Null intent restart - restoring quick launch monitoring")
                logSessionEvent("Service restarted with null intent — restoring quick launch monitor")
                startForeground(
                    QUICK_LAUNCH_NOTIFICATION_ID,
                    buildQuickLaunchMonitoringNotification(),
                )
                val currentForeground = UsageTracker.getForegroundApp(this)
                    ?: _currentPackage.value
                updateQuickLaunchFrameVisibility(currentForeground)
                startQuickLaunchMonitoringLoop()
            }
            return START_STICKY
        }

        when (action) {
            ACTION_START -> {
                val explicitDurationMs = intent.getLongExtra(EXTRA_DURATION_MS, -1L)
                val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 5)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                val hardDeadlineRaw = intent.getLongExtra(EXTRA_HARD_DEADLINE_AT_MS, 0L)
                val hardDeadlineAtMs = hardDeadlineRaw.takeIf { it > 0L }
                val durationMs = if (explicitDurationMs > 0L) {
                    explicitDurationMs
                } else {
                    durationMinutes * 60 * 1000L
                }
                logSessionEvent(
                    "ACTION_START requested: durationMs=$durationMs package=${packageName.ifBlank { "<none>" }} hardDeadlineAtMs=${hardDeadlineAtMs ?: 0L}"
                )
                startTimer(durationMs, packageName, hardDeadlineAtMs)
            }
            ACTION_START_QUICK_LAUNCH_SESSION -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                val allowedPackages = intent.getStringArrayListExtra(EXTRA_ALLOWED_PACKAGES)
                    ?.toSet()
                    ?: emptySet()
                logSessionEvent(
                    "ACTION_START_QUICK_LAUNCH_SESSION requested: initial=${packageName.ifBlank { "<none>" }} allowed=${allowedPackages.size}"
                )
                startQuickLaunchSession(packageName, allowedPackages)
            }
            ACTION_TRACK_APP -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                Log.d(TAG, "track app package=$packageName")
                if (packageName.isNotBlank() && packageName != _currentPackage.value) {
                    val appLabel = getAppLabel(packageName)
                    logWithSession("Foreground app detected: **$appLabel** (`$packageName`)")
                }
                _currentPackage.value = packageName
                maybeForceTimerForQuickLaunchSwitch(packageName)
            }
            ACTION_EXTEND -> {
                val extraMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 5)
                logSessionEvent("ACTION_EXTEND requested: +$extraMinutes min")
                val extended = extendTimer(extraMinutes)
                if (!extended) {
                    logWithSession("Extension blocked due to hard deadline proximity")
                }
            }
            ACTION_STOP -> {
                logSessionEvent("ACTION_STOP requested")
                stopTimer()
            }
            ACTION_CLEAR_VISIBLE_NUDGES -> {
                val cleared = overlayManager.dismissAllNudgesIfPresent()
                if (cleared) {
                    Log.d(TAG, "ACTION_CLEAR_VISIBLE_NUDGES: removed visible nudges")
                } else {
                    Log.d(TAG, "ACTION_CLEAR_VISIBLE_NUDGES: no-op (nothing visible)")
                }
            }
            ACTION_HANDLE_REPLY -> {
                handleNudgeReply(intent)
            }
        }
        return START_STICKY
    }

    // ── Timer lifecycle ──────────────────────────────────────────────

    private fun startTimer(durationMs: Long, packageName: String, hardDeadlineAtMs: Long?) {
        resetNudgesForNewTimer()
        softDeadlineAtMs = null
        this.hardDeadlineAtMs = hardDeadlineAtMs
        overlayManager.setDeadlineState(softDeadlineAtMs, this.hardDeadlineAtMs)
        SettingsManager.clearQuickLaunchSession(this)
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissQuickLaunchFrame()
        SettingsManager.clearLastSession(this)
        SettingsManager.setTimerRunning(this, true)
        _sessionStartedAtMs.value = System.currentTimeMillis()
        _currentPackage.value = packageName
        _timerState.value = TimerState.Counting(durationMs, durationMs)
        logSessionEvent(
            "Timer state -> Counting (totalMs=$durationMs, startedAtMs=${_sessionStartedAtMs.value}, package=${packageName.ifBlank { "<none>" }}, hardDeadlineAtMs=${hardDeadlineAtMs ?: 0L})"
        )

        val durationMinutesDisplay = ((durationMs + 59_999L) / 60_000L).toInt()
        val appLabel = getAppLabel(packageName)
        logWithSession("Session timer started: **$durationMinutesDisplay min** ($appLabel)")

        startForeground(TIMER_NOTIFICATION_ID, buildTimerNotification(durationMs))

        timerJob?.cancel()
        timerJob = serviceScope.launch {
            var remainingMs = durationMs
            while (remainingMs > 0) {
                delay(TIMER_TICK_MS)
                remainingMs = (remainingMs - TIMER_TICK_MS).coerceAtLeast(0L)
                _timerState.value = TimerState.Counting(remainingMs, durationMs)
                updateTimerNotification(remainingMs)
            }
            onTimerExpired(packageName)
        }
    }

    private fun resetNudgesForNewTimer() {
        logSessionEvent("Resetting nudge state for new timer/session")
        nudgeJob?.cancel()
        clearNotificationInteractionWatch(
            reason = "nudge reset for new timer",
            markSuccess = false,
        )
        preferBannerFallbackForOverlayTap = false
        SettingsManager.setNudgeBannerFallbackArmed(this, false)
        overlayManager.dismissAllNudges()
        nudgePauseUntilMs = 0L
        nudgeResetRequested = false
        _nudgeCount.value = 0
        userAwayOverlayActive = false
        awayShieldShownForCurrentAwayEpisode = false
        lastAwayOverlayTapAtMs = 0L
        endNudgeConversation()
    }

    private fun startQuickLaunchSession(
        initialPackageName: String,
        allowedPackages: Set<String>,
    ) {
        // Suspend any running timer session so it can be resumed later.
        val state = _timerState.value
        val pkg = _currentPackage.value
        if (state is TimerState.Counting && pkg.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val startedAtMs = _sessionStartedAtMs.value.takeIf { it > 0L }
                ?: (now - (state.totalMs - state.remainingMs).coerceAtLeast(0L))
            SettingsManager.saveLastSession(
                context = this,
                packageName = pkg,
                totalDurationMs = state.totalMs,
                startedAtMs = startedAtMs,
                suspendedAtMs = now,
            )
            logWithSession("Timer suspended for Quick Launch: $pkg")
        }

        timerJob?.cancel()
        resetNudgesForNewTimer()
        clearQuickLaunchExitCandidate()
        Log.d(
            TAG,
            "startQuickLaunchSession initial=$initialPackageName allowedCount=${allowedPackages.size}",
        )
        val normalizedAllowed = allowedPackages + initialPackageName
        logSessionEvent(
            "Quick Launch session activated (initial=${initialPackageName.ifBlank { "<none>" }}, allowed=${normalizedAllowed.size})"
        )
        SettingsManager.startQuickLaunchSession(this, normalizedAllowed)
        SettingsManager.setTimerRunning(this, false)

        _sessionStartedAtMs.value = 0L
        _currentPackage.value = initialPackageName
        _timerState.value = TimerState.Idle
        softDeadlineAtMs = null
        hardDeadlineAtMs = null
        overlayManager.setDeadlineState(softDeadlineAtMs, hardDeadlineAtMs)

        val appLabel = getAppLabel(initialPackageName)
        logWithSession("Quick Launch started: **$appLabel** (no timer running)")

        // Keep service alive while monitoring app switches outside launcher taps.
        startForeground(
            QUICK_LAUNCH_NOTIFICATION_ID,
            buildQuickLaunchMonitoringNotification(),
        )
        refreshQuickLaunchMonitoringNotification()
        quickLaunchFrameSuppressedForSensitiveApp = false
        updateQuickLaunchFrameVisibility(initialPackageName)
        startQuickLaunchMonitoringLoop()
    }

    private fun maybeForceTimerForQuickLaunchSwitch(packageName: String) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) return

        val allowedPackages = SettingsManager.getQuickLaunchPackages(this) + this.packageName
        val isAllowedQuickLaunchPackage = packageName in allowedPackages
        val isSystemUtilityPackage = isSystemOrUtilityPackage(packageName)
        if (isAllowedQuickLaunchPackage || isSystemUtilityPackage) {
            clearQuickLaunchExitCandidate()
            if (isAllowedQuickLaunchPackage) {
                Log.v(TAG, "quick-launch app allowed: $packageName")
            } else {
                Log.v(TAG, "quick-launch system/utility app ignored: $packageName")
            }
            return
        }
        if (packageName.isBlank()) return

        val now = System.currentTimeMillis()
        if (quickLaunchExitCandidatePackage != packageName) {
            quickLaunchExitCandidatePackage = packageName
            quickLaunchExitCandidateStartedAtMs = now
            quickLaunchExitCandidateLabel = getAppLabel(packageName)
            val appLabel = getAppLabel(packageName)
            logWithSession(
                "Quick Launch switch observed: **$appLabel** — waiting 1m grace period"
            )
            logSessionEvent(
                "Quick Launch grace window started for package=$packageName"
            )
            refreshQuickLaunchMonitoringNotification()
            scheduleQuickLaunchGraceCheck(expectedPackage = packageName)
            return
        }
    }

    private fun clearQuickLaunchExitCandidate() {
        quickLaunchGraceJob?.cancel()
        quickLaunchGraceJob = null
        quickLaunchExitCandidatePackage = null
        quickLaunchExitCandidateStartedAtMs = 0L
        quickLaunchExitCandidateLabel = null
        refreshQuickLaunchMonitoringNotification()
    }

    private fun scheduleQuickLaunchGraceCheck(expectedPackage: String) {
        quickLaunchGraceJob?.cancel()
        quickLaunchGraceJob = serviceScope.launch {
            delay(QUICK_LAUNCH_SWITCH_GRACE_MS)
            if (!SettingsManager.isQuickLaunchSessionActive(this@TimerService)) return@launch
            if (quickLaunchExitCandidatePackage != expectedPackage) return@launch

            val currentForeground = UsageTracker.getForegroundApp(this@TimerService) ?: _currentPackage.value
            val allowedPackages = SettingsManager.getQuickLaunchPackages(this@TimerService) + packageName
            if (currentForeground.isBlank()) return@launch
            if (currentForeground in allowedPackages || isSystemOrUtilityPackage(currentForeground)) {
                clearQuickLaunchExitCandidate()
                return@launch
            }

            val appLabel = getAppLabel(currentForeground)
            Log.d(TAG, "non-quick app still active after grace: $currentForeground")
            logWithSession(
                "Quick Launch exit detected: opened **$appLabel** — returning to timer"
            )
            clearQuickLaunchExitCandidate()
            SettingsManager.clearQuickLaunchSession(this@TimerService)
            quickLaunchMonitorJob?.cancel()
            overlayManager.dismissQuickLaunchFrame()
            forceBackToTimer(MainActivity.FORCE_TIMER_REASON_QUICK_LAUNCH)
        }
    }

    private fun isSystemOrUtilityPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == this.packageName) return true

        val normalized = packageName.lowercase()
        if (
            normalized in QUICK_LAUNCH_UTILITY_PACKAGES_EXACT ||
            QUICK_LAUNCH_UTILITY_PACKAGE_PREFIXES.any { normalized.startsWith(it) } ||
            QUICK_LAUNCH_UTILITY_PACKAGE_KEYWORDS.any { normalized.contains(it) }
        ) {
            return true
        }
        val label = getAppLabel(packageName).lowercase()
        if (QUICK_LAUNCH_UTILITY_LABEL_KEYWORDS.any { label.contains(it) }) {
            return true
        }

        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val isSystemApp =
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
                    appInfo.uid < Process.FIRST_APPLICATION_UID
            val isMediaCategory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_IMAGE ||
                    appInfo.category == ApplicationInfo.CATEGORY_VIDEO
            } else {
                false
            }
            isSystemApp || isMediaCategory
        } catch (_: Exception) {
            false
        }
    }

    private fun updateQuickLaunchFrameVisibility(foregroundPackage: String) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) {
            overlayManager.dismissQuickLaunchFrame()
            quickLaunchFrameSuppressedForSensitiveApp = false
            return
        }

        if (isQuickLaunchFrameRestrictedPackage(foregroundPackage)) {
            overlayManager.dismissQuickLaunchFrame()
            if (!quickLaunchFrameSuppressedForSensitiveApp) {
                logSessionEvent(
                    "Quick Launch frame suppressed for sensitive app: ${foregroundPackage.ifBlank { "<none>" }}"
                )
            }
            quickLaunchFrameSuppressedForSensitiveApp = true
            return
        }

        overlayManager.showQuickLaunchFrame()
        if (quickLaunchFrameSuppressedForSensitiveApp) {
            logSessionEvent("Quick Launch frame restored after leaving sensitive app")
        }
        quickLaunchFrameSuppressedForSensitiveApp = false
    }

    private fun isQuickLaunchFrameRestrictedPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val normalized = packageName.lowercase()
        return normalized in QUICK_LAUNCH_FRAME_RESTRICTED_PACKAGES_EXACT ||
            QUICK_LAUNCH_FRAME_RESTRICTED_PACKAGE_PREFIXES.any { normalized.startsWith(it) }
    }

    private fun startQuickLaunchMonitoringLoop() {
        quickLaunchMonitorJob?.cancel()
        Log.d(TAG, "startQuickLaunchMonitoringLoop")
        logSessionEvent("Quick Launch monitor loop started")
        quickLaunchMonitorJob = serviceScope.launch {
            var lastSeenPackage = _currentPackage.value
            updateQuickLaunchFrameVisibility(lastSeenPackage)
            while (SettingsManager.isQuickLaunchSessionActive(this@TimerService)) {
                val foregroundPackage = UsageTracker.getForegroundApp(this@TimerService)
                if (!foregroundPackage.isNullOrBlank() && foregroundPackage != lastSeenPackage) {
                    Log.d(TAG, "foreground changed: $lastSeenPackage -> $foregroundPackage")
                    lastSeenPackage = foregroundPackage
                    _currentPackage.value = foregroundPackage
                    updateQuickLaunchFrameVisibility(foregroundPackage)
                    maybeForceTimerForQuickLaunchSwitch(foregroundPackage)
                }
                delay(QUICK_LAUNCH_MONITOR_POLL_MS)
            }
            Log.d(TAG, "quick-launch monitoring loop ended")
            logSessionEvent("Quick Launch monitor loop ended")
        }
    }

    private fun extendTimer(extraMinutes: Int): Boolean {
        val state = _timerState.value
        val extraMs = extraMinutes * 60 * 1000L
        if (isHardDeadlineCloserThanSessionDeadline()) {
            logSessionEvent(
                "Extension denied (+$extraMinutes min): hard deadline is closer than session deadline",
            )
            return false
        }

        nudgeJob?.cancel()
        _nudgeCount.value = 0
        overlayManager.dismissAllNudges()

        val appLabel = getAppLabel(_currentPackage.value)
        logWithSession("Timer extended: **+$extraMinutes min** for $appLabel")

        when (state) {
            is TimerState.Expired -> {
                val pkg = _currentPackage.value
                startTimer(extraMinutes * 60 * 1000L, pkg, hardDeadlineAtMs)
            }
            is TimerState.Counting -> {
                val newRemaining = state.remainingMs + extraMs
                val newTotal = state.totalMs + extraMs
                _timerState.value = TimerState.Counting(newRemaining, newTotal)
            }
            is TimerState.Idle -> { }
        }
        return true
    }

    private fun onTimerExpired(packageName: String) {
        _timerState.value = TimerState.Expired(0)
        softDeadlineAtMs = System.currentTimeMillis()
        overlayManager.setDeadlineState(softDeadlineAtMs, hardDeadlineAtMs)
        logSessionEvent("Timer state -> Expired (package=${packageName.ifBlank { "<none>" }})")
        val appLabel = getAppLabel(packageName)
        logWithSession("**Time's up!** Session timer expired (was using $appLabel)")

        val canOverlay = overlayManager.canDrawOverlay()
        Log.d(TAG, "onTimerExpired: canDrawOverlay=$canOverlay")

        if (!canOverlay) {
            Log.w(TAG, "Overlay permission not granted — nudges will appear as notifications only")
            logWithSession("(Overlay permission not granted — nudges will appear as notifications only)")
        }

        startNudgeConversation(packageName)
        _nudgeCount.value = 0
        startNudging(packageName)
    }

    private fun startNudging(packageName: String) {
        nudgeJob?.cancel()
        nudgeJob = serviceScope.launch {
            var overrunMs = 0L
            var bubbleCount = 0
            var predatoryPenaltyPending = false
            var lastUserActivityAtMs: Long? = UsageTracker.getLastUserActivityTimestampMs(
                context = this@TimerService,
                lookbackMs = USER_AWAY_SIGNAL_LOOKBACK_MS,
                includeForegroundTransitions = false,
            )
            var awaySignalUnavailableLogged = false
            val initialDelayMs = SettingsManager
                .getNudgeInitialNotificationDelayMinutes(this@TimerService)
                .coerceAtLeast(0) * 60_000L
            val bubbleIntervalMs = SettingsManager
                .getNudgeBubbleIntervalSeconds(this@TimerService)
                .coerceAtLeast(1) * 1_000L
            val appLabel = getAppLabel(packageName)
            logWithSession(
                "Nudge schedule: notify now, wait ${initialDelayMs / 60000}m, " +
                    "bubble every ${bubbleIntervalMs / 1000}s (no banner escalation)"
            )
            logSessionEvent(
                "Nudge loop started (initialDelayMs=$initialDelayMs, bubbleIntervalMs=$bubbleIntervalMs, package=${packageName.ifBlank { "<none>" }})"
            )

            var stage = NudgeStage.WAITING_AFTER_NOTIFICATION
            var stageElapsedMs = 0L
            var activeElapsedMs = 0L

            while (true) {
                delay(NUDGE_LOOP_TICK_MS)
                val now = System.currentTimeMillis()
                val detectedActivityAtMs = UsageTracker.getLastUserActivityTimestampMs(
                    context = this@TimerService,
                    lookbackMs = USER_AWAY_SIGNAL_LOOKBACK_MS,
                    includeForegroundTransitions = false,
                )
                if (detectedActivityAtMs != null) {
                    val current = lastUserActivityAtMs
                    lastUserActivityAtMs = if (current == null) {
                        detectedActivityAtMs
                    } else {
                        max(current, detectedActivityAtMs)
                    }
                }
                val tapActivityAtMs = lastAwayOverlayTapAtMs
                if (tapActivityAtMs > 0L) {
                    val current = lastUserActivityAtMs
                    lastUserActivityAtMs = if (current == null) {
                        tapActivityAtMs
                    } else {
                        max(current, tapActivityAtMs)
                    }
                }
                val lastActivityAtMs = lastUserActivityAtMs
                if (lastActivityAtMs == null) {
                    if (userAwayOverlayActive) {
                        userAwayOverlayActive = false
                        overlayManager.dismissAwayShield()
                        logSessionEvent("Away shield hidden: USER_INTERACTION signal unavailable")
                    }
                    awayShieldShownForCurrentAwayEpisode = false
                    if (!awaySignalUnavailableLogged) {
                        awaySignalUnavailableLogged = true
                        logSessionEvent(
                            "Away detection disabled: no USER_INTERACTION signal available on this device/interval",
                        )
                    }
                }
                val inactivityMs = lastActivityAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
                val isUserAway =
                    lastActivityAtMs != null && inactivityMs >= USER_AWAY_INACTIVITY_THRESHOLD_MS
                if (isUserAway) {
                    if (!awayShieldShownForCurrentAwayEpisode) {
                        awayShieldShownForCurrentAwayEpisode = true
                        userAwayOverlayActive = true
                        overlayManager.showAwayShield()
                        logSessionEvent(
                            "User away inferred from inactivity (${inactivityMs / 1000}s); showing away shield",
                        )
                        logWithSession(
                            "User appears away (${inactivityMs / 1000}s idle) — pausing nudge escalation and overrun",
                        )
                    }
                    continue
                } else if (userAwayOverlayActive) {
                    userAwayOverlayActive = false
                    overlayManager.dismissAwayShield()
                    logSessionEvent("User activity resumed; hiding away shield")
                    awayShieldShownForCurrentAwayEpisode = false
                } else {
                    awayShieldShownForCurrentAwayEpisode = false
                }
                if (nudgeResetRequested) {
                    stage = NudgeStage.WAITING_AFTER_NOTIFICATION
                    stageElapsedMs = 0L
                    bubbleCount = 0
                    _nudgeCount.value = 0
                    nudgeResetRequested = false
                    nudgePauseUntilMs = max(nudgePauseUntilMs, now + initialDelayMs)
                    logSessionEvent(
                        "Nudge loop reset after interaction; pauseUntilMs=$nudgePauseUntilMs"
                    )
                }
                if (now < nudgePauseUntilMs) {
                    continue
                }
                activeElapsedMs += NUDGE_LOOP_TICK_MS
                stageElapsedMs += NUDGE_LOOP_TICK_MS
                overrunMs = activeElapsedMs
                _timerState.value = TimerState.Expired(overrunMs)

                when (stage) {
                    NudgeStage.WAITING_AFTER_NOTIFICATION -> {
                        if (stageElapsedMs >= initialDelayMs) {
                            stage = NudgeStage.BUBBLES
                            stageElapsedMs = max(0L, stageElapsedMs - initialDelayMs)
                            logSessionEvent("Nudge stage -> BUBBLES")
                        }
                    }
                    NudgeStage.BUBBLES -> {
                        if (stageElapsedMs >= bubbleIntervalMs) {
                            val nextBubbleIndex = bubbleCount + 1
                            Log.d(
                                TAG,
                                "Bubble timer trigger: next=$nextBubbleIndex " +
                                    "stageElapsedMs=$stageElapsedMs intervalMs=$bubbleIntervalMs " +
                                    "pkg=$packageName"
                            )
                            stageElapsedMs = max(0L, stageElapsedMs - bubbleIntervalMs)

                            if (predatoryPenaltyPending) {
                                karmaManager.onNudgeIgnored(packageName)
                                predatoryPenaltyPending = false
                                logWithSession(
                                    "Karma -1: predatory bird was ignored until the next bird ($appLabel)"
                                )
                                logSessionEvent("Predatory bird penalty applied at nudge #$nextBubbleIndex")
                            }

                            bubbleCount++
                            _nudgeCount.value = bubbleCount
                            val isPredatoryBird =
                                bubbleCount % PREDATORY_BIRD_EVERY_N_BIRDS == 0
                            if (isPredatoryBird) {
                                predatoryPenaltyPending = true
                                logWithSession(
                                    "Predatory bird #$bubbleCount is hunting. " +
                                        "Close before the next bird to avoid karma -1."
                                )
                                logSessionEvent(
                                    "Predatory bird shown at nudge #$bubbleCount; penalty pending"
                                )
                            }

                            val canOverlayNow = overlayManager.canDrawOverlay()
                            Log.d(
                                TAG,
                                "Bubble trigger dispatch: canOverlay=$canOverlayNow count=$bubbleCount"
                            )
                            if (canOverlayNow) {
                                overlayManager.showBubble(
                                    nudgeCount = bubbleCount,
                                    isPredatory = isPredatoryBird,
                                )
                                overlayManager.updateConversationMessage("", bubbleCount)
                            } else {
                                logSessionEvent("Bubble fallback notification suppressed (single notification mode)")
                            }

                            logWithSession(
                                "${if (isPredatoryBird) "Predatory" else "Small"} bird nudge #$bubbleCount shown for $appLabel " +
                                    "(overrun ${overrunMs / 1000}s)"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onOverlayDismissed() {
        val pkg = _currentPackage.value
        if (pkg.isEmpty()) return
        val appLabel = getAppLabel(pkg)
        markNotificationInteractionObserved("overlay dismissed")

        logSessionEvent("Overlay dismissed by user (package=$pkg)")
        logWithSession("User dismissed overlay for $appLabel — treating as positive signal")
        nudgeJob?.cancel()
        _nudgeCount.value = 0

        serviceScope.launch {
            karmaManager.onClosedInGraceWindow(pkg)
        }
        overlayManager.dismissAllNudges()
        endNudgeConversation()
    }

    private fun onOverlayNotificationRequested() {
        logSessionEvent("Overlay tapped to open notification conversation")
        logWithSession("Overlay requested notification conversation")
        overlayManager.dismissAllNudges()
        nudgePauseUntilMs = System.currentTimeMillis() + (
            SettingsManager.getNudgeTypingIdleTimeoutMinutes(this).coerceAtLeast(1) * 60_000L
            )
        if (preferBannerFallbackForOverlayTap) {
            overlayManager.showConversationBanner(buildBannerPreviewLines())
            logWithSession("Banner fallback shown after prior notification-open failure")
            logSessionEvent("Overlay tap used banner fallback")
        } else {
            overlayManager.showConversationBanner(buildBannerPreviewLines())
            logSessionEvent("Overlay tap kept in birds/banner flow (no repost)")
        }
        clearNotificationInteractionWatch(reason = "overlay tap handled in birds/banner flow", markSuccess = false)
    }

    private fun onBannerReplySubmitted(replyText: String) {
        val payload = replyText.trim()
        if (payload.isBlank()) return
        markNotificationInteractionObserved("banner reply")
        logSessionEvent("User replied from banner (chars=${payload.length})")
        nudgeResetRequested = true
        nudgePauseUntilMs = System.currentTimeMillis() + (
            SettingsManager.getNudgeTypingIdleTimeoutMinutes(this).coerceAtLeast(1) * 60_000L
            )
        nudgeMessages.add(NudgeMessage(payload, isFromUser = true))
        overlayManager.showConversationBanner(buildBannerPreviewLines())
        showConversationNotification(alertUser = false)
        logWithSession("You (banner): $payload")
        handleNudgeReplyText(payload, keepBannerVisible = true)
    }

    private fun onAwayReturnRequested() {
        userAwayOverlayActive = false
        awayShieldShownForCurrentAwayEpisode = false
        overlayManager.dismissAwayShield()
        logSessionEvent("Away shield acknowledged by user")
        logWithSession("Away shield acknowledged — returning to timer")
        forceBackToTimer(MainActivity.FORCE_TIMER_REASON_AWAY_RETURN)
    }

    private fun onAwayShieldTapped() {
        userAwayOverlayActive = false
        // Keep current away episode marked as already-shown to avoid immediate re-show spam.
        awayShieldShownForCurrentAwayEpisode = true
        lastAwayOverlayTapAtMs = System.currentTimeMillis()
        logSessionEvent("Away shield dismissed by passive tap")
    }

    private fun forceBackToTimer(reason: String) {
        logSessionEvent("Force returning to timer screen (reason=$reason)")
        overlayManager.dismissAllNudges()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FORCE_TIMER, true)
            putExtra(MainActivity.EXTRA_FORCE_TIMER_REASON, reason)
        }
        startActivity(intent)
    }

    private fun stopTimer() {
        markNotificationInteractionObserved("timer stop")
        logSessionEvent("Stopping timer service workflow")
        timerJob?.cancel()
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissAllNudges()
        overlayManager.dismissQuickLaunchFrame()

        serviceScope.launch {
            val pkg = _currentPackage.value
            val state = _timerState.value
            val appLabel = getAppLabel(pkg)

            when (state) {
                is TimerState.Counting -> {
                    karmaManager.onClosedOnTime(pkg)
                    logWithSession("App closed on time: $appLabel (karma +1)")

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
                        logWithSession(
                            "Saved resumable session: $appLabel ($remainingMinutes min left)"
                        )
                    }
                }
                is TimerState.Expired -> {
                    if (state.overrunMs <= KarmaManager.GRACE_WINDOW_MS) {
                        karmaManager.onClosedInGraceWindow(pkg)
                        logWithSession(
                            "App closed in grace window: $appLabel " +
                                "(overrun ${state.overrunMs / 1000}s)"
                        )
                    } else {
                        logWithSession(
                            "App closed after overrun: $appLabel " +
                                "(overrun ${state.overrunMs / 60000} min)"
                        )
                    }
                }
                is TimerState.Idle -> { }
            }

            _timerState.value = TimerState.Idle
            logSessionEvent("Timer state -> Idle (stopTimer)")
            _sessionStartedAtMs.value = 0L
            _currentPackage.value = ""
            _nudgeCount.value = 0
            softDeadlineAtMs = null
            hardDeadlineAtMs = null
            overlayManager.setDeadlineState(softDeadlineAtMs, hardDeadlineAtMs)
            SettingsManager.setTimerRunning(this@TimerService, false)
            SettingsManager.clearQuickLaunchSession(this@TimerService)

            endNudgeConversation()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            logSessionEvent("Timer service stop completed")
        }
    }

    private fun suspendForScreenOff() {
        logSessionEvent("Suspending timer workflow due to screen off")
        timerJob?.cancel()
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissAllNudges()
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
                    logWithSession(
                        "Session suspended (screen off): $appLabel " +
                            "($remainingMinutes min remaining)"
                    )
                }
                is TimerState.Expired -> {
                    karmaManager.onClosedInGraceWindow(pkg)
                    logWithSession(
                        "Screen off during overrun: $appLabel — positive signal " +
                            "(overrun ${state.overrunMs / 1000}s)"
                    )
                }
                is TimerState.Idle -> { }
            }

            _timerState.value = TimerState.Idle
            logSessionEvent("Timer state -> Idle (screen off suspend)")
            _sessionStartedAtMs.value = 0L
            _currentPackage.value = ""
            _nudgeCount.value = 0
            softDeadlineAtMs = null
            hardDeadlineAtMs = null
            overlayManager.setDeadlineState(softDeadlineAtMs, hardDeadlineAtMs)
            SettingsManager.setTimerRunning(this@TimerService, false)
            SettingsManager.clearQuickLaunchSession(this@TimerService)

            endNudgeConversation()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            logSessionEvent("Timer service suspended and stopped")
        }
    }

    // ── Nudge conversation (notification + overlays) ─────────────────

    private fun startNudgeConversation(packageName: String) {
        nudgeMessages.clear()
        logSessionEvent("Starting nudge conversation (package=${packageName.ifBlank { "<none>" }})")

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
                showConversationNotification(alertUser = true)
                overlayManager.updateConversationMessage(result.responseText, _nudgeCount.value)
                logSessionEvent("Initial AI nudge response received")

                if (result.extensionMinutes > 0) {
                    handleExtension(result.extensionMinutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting nudge conversation", e)
                logSessionEvent("Nudge conversation start failed: ${e.javaClass.simpleName}")
                nudgeMessages.add(
                    NudgeMessage("Time's up! Your session has ended.", isFromUser = false)
                )
                showConversationNotification(alertUser = true)
            }
        }
    }

    private fun handleNudgeReply(intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()
        if (replyText.isNullOrBlank()) return
        val payload = replyText.trim()
        if (payload.isBlank()) return
        markNotificationInteractionObserved("inline reply")
        logSessionEvent("User replied to nudge (chars=${payload.length})")
        overlayManager.dismissAllNudges()
        nudgeResetRequested = true
        nudgePauseUntilMs = System.currentTimeMillis() + (
            SettingsManager.getNudgeTypingIdleTimeoutMinutes(this).coerceAtLeast(1) * 60_000L
            )
        nudgeMessages.add(NudgeMessage(payload, isFromUser = true))
        showConversationNotification(alertUser = false)
        logWithSession("You: $payload")
        handleNudgeReplyText(payload, keepBannerVisible = false)
    }

    private fun handleNudgeReplyText(replyText: String, keepBannerVisible: Boolean) {
        val manager = negotiationManager
        if (manager == null) {
            logSessionEvent("Nudge reply received but conversation manager is null")
            val fallback = "Take a moment to reflect on whether you still need this app."
            nudgeMessages.add(NudgeMessage(fallback, isFromUser = false))
            showConversationNotification(alertUser = false)
            overlayManager.updateConversationMessage(fallback, _nudgeCount.value)
            nudgeResetRequested = true
            nudgePauseUntilMs = System.currentTimeMillis() + (
                SettingsManager.getNudgeInitialNotificationDelayMinutes(this)
                    .coerceAtLeast(0) * 60_000L
                )
            return
        }

        serviceScope.launch {
            try {
                val result = manager.reply(replyText)
                nudgeMessages.add(NudgeMessage(result.responseText, isFromUser = false))
                showConversationNotification(alertUser = false)
                overlayManager.updateConversationMessage(result.responseText, _nudgeCount.value)
                if (keepBannerVisible) {
                    overlayManager.showConversationBanner(buildBannerPreviewLines())
                }
                logWithSession("MindfulHome: ${result.responseText}")
                logSessionEvent("AI reply processed")
                nudgeResetRequested = true
                nudgePauseUntilMs = System.currentTimeMillis() + (
                    SettingsManager.getNudgeInitialNotificationDelayMinutes(this@TimerService)
                        .coerceAtLeast(0) * 60_000L
                    )

                if (result.extensionMinutes > 0) {
                    handleExtension(result.extensionMinutes, keepBannerVisible)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling nudge reply", e)
                logSessionEvent("AI reply handling failed: ${e.javaClass.simpleName}")
                val fallback = "Sorry, I couldn't process that. Take a moment to reflect."
                nudgeMessages.add(NudgeMessage(fallback, isFromUser = false))
                showConversationNotification(alertUser = false)
                overlayManager.updateConversationMessage(fallback, _nudgeCount.value)
                if (keepBannerVisible) {
                    overlayManager.showConversationBanner(buildBannerPreviewLines())
                }
                nudgeResetRequested = true
                nudgePauseUntilMs = System.currentTimeMillis() + (
                    SettingsManager.getNudgeInitialNotificationDelayMinutes(this@TimerService)
                        .coerceAtLeast(0) * 60_000L
                    )
            }
        }
    }

    private fun handleExtension(minutes: Int, keepBannerVisible: Boolean = true) {
        val extended = extendTimer(minutes)
        if (extended) {
            logSessionEvent("Applying AI extension: +$minutes min")
            logWithSession("AI granted extension: **$minutes min**")
            endNudgeConversation()
            return
        }

        val message =
            "I can't grant an extension now - your hard deadline is now the closest limit."
        nudgeMessages.add(NudgeMessage(message, isFromUser = false))
        showConversationNotification(alertUser = false)
        overlayManager.updateConversationMessage(message, _nudgeCount.value)
        if (keepBannerVisible) {
            overlayManager.showConversationBanner(buildBannerPreviewLines())
        }
        logWithSession("AI extension blocked by hard deadline")
        logSessionEvent("AI extension blocked by hard deadline")
    }

    private fun endNudgeConversation() {
        logSessionEvent("Ending nudge conversation and clearing overlays/notification")
        negotiationManager?.endConversation()
        negotiationManager = null
        lmManager?.shutdown()
        lmManager = null
        nudgeMessages.clear()
        overlayManager.dismissAllNudges()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NUDGE_NOTIFICATION_ID)
    }

    // ── Notification builders ────────────────────────────────────────

    private fun showConversationNotification(alertUser: Boolean) {
        if (nudgeMessages.isEmpty()) return
        logSessionEvent(
            "Posting conversation notification (alertUser=$alertUser, messages=${nudgeMessages.size})"
        )

        // Tapping the notification brings the user directly to the timer screen.
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FORCE_TIMER, true)
            putExtra(MainActivity.EXTRA_FORCE_TIMER_REASON, MainActivity.FORCE_TIMER_REASON_EXPIRED)
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, NUDGE_NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
            R.drawable.ic_nudge_notification, "Reply", replyPendingIntent,
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
            .setSmallIcon(R.drawable.ic_nudge_notification)
            .setContentIntent(tapPendingIntent)
            .setStyle(messagingStyle)
            .addAction(replyAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setSilent(!alertUser)
            .setAutoCancel(false)
            .setOngoing(false)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NUDGE_NOTIFICATION_ID, notification)
    }

    private fun armNotificationInteractionWatch(source: String) {
        notificationInteractionTimeoutJob?.cancel()
        awaitingNotificationInteraction = true
        val timeoutMs = SettingsManager
            .getNudgeInteractionWatchTimeoutMinutes(this)
            .coerceIn(
                SettingsManager.MIN_NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES,
                SettingsManager.MAX_NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES,
            ) * 60_000L
        logSessionEvent(
            "Armed notification interaction watch (source=$source, timeoutMs=$timeoutMs)"
        )
        notificationInteractionTimeoutJob = serviceScope.launch {
            delay(timeoutMs)
            if (!awaitingNotificationInteraction) return@launch
            awaitingNotificationInteraction = false
            preferBannerFallbackForOverlayTap = true
            SettingsManager.setNudgeBannerFallbackArmed(this@TimerService, true)
            overlayManager.showConversationBanner(buildBannerPreviewLines())
            logWithSession(
                "No interaction detected after bubble tap; banner fallback shown"
            )
            logSessionEvent("Notification interaction watch timed out; banner fallback shown")
        }
    }

    private fun markNotificationInteractionObserved(reason: String) {
        if (!awaitingNotificationInteraction) return
        clearNotificationInteractionWatch(reason = reason, markSuccess = true)
    }

    private fun clearNotificationInteractionWatch(reason: String, markSuccess: Boolean) {
        notificationInteractionTimeoutJob?.cancel()
        notificationInteractionTimeoutJob = null
        if (awaitingNotificationInteraction) {
            logSessionEvent(
                "Cleared notification interaction watch (reason=$reason, success=$markSuccess)"
            )
        }
        awaitingNotificationInteraction = false
        if (markSuccess) {
            preferBannerFallbackForOverlayTap = false
            SettingsManager.setNudgeBannerFallbackArmed(this, false)
        }
    }

    private fun buildBannerPreviewLines(): List<String> {
        if (nudgeMessages.isEmpty()) return listOf("MindfulHome has a new message.")
        return nudgeMessages.takeLast(3).map { message ->
            val sender = if (message.isFromUser) "You" else "MindfulHome"
            "$sender: ${message.text}"
        }
    }

    private fun logWithSession(entry: String) {
        SessionLogger.log(logSessionHandle, entry)
    }

    private fun sessionTokenForLogs(): String {
        val token = logSessionHandle?.token ?: 0L
        return if (token > 0L) token.toString() else "none"
    }

    private fun updateLogSessionHandleFromIntent(intent: Intent?) {
        val token = intent?.getLongExtra(EXTRA_SESSION_TOKEN, 0L) ?: 0L
        if (token <= 0L) return
        logSessionHandle = SessionLogger.handleFromToken(token)
    }

    private fun buildTimerNotification(remainingMs: Long): Notification {
        val minutes = (remainingMs / 60000).toInt()
        val seconds = ((remainingMs % 60000) / 1000).toInt()

        return NotificationCompat.Builder(this, MindfulHomeApp.TIMER_CHANNEL_ID)
            .setContentTitle("MindfulHome")
            .setContentText("$minutes:${seconds.toString().padStart(2, '0')} remaining")
            .setSmallIcon(R.drawable.ic_nudge_notification)
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
            .setContentText(DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT)
            .setSmallIcon(R.drawable.ic_nudge_notification)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun refreshQuickLaunchMonitoringNotification() {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) {
            lastQuickLaunchNotificationText = null
            return
        }

        val contentText = buildQuickLaunchMonitoringStatusText()
        if (contentText == lastQuickLaunchNotificationText) return
        lastQuickLaunchNotificationText = contentText

        val notification = NotificationCompat.Builder(this, MindfulHomeApp.TIMER_CHANNEL_ID)
            .setContentTitle("MindfulHome")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_nudge_notification)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(QUICK_LAUNCH_NOTIFICATION_ID, notification)
    }

    private fun buildQuickLaunchMonitoringStatusText(): String {
        val candidatePackage = quickLaunchExitCandidatePackage
        val candidateStartedAt = quickLaunchExitCandidateStartedAtMs
        if (candidatePackage.isNullOrBlank() || candidateStartedAt <= 0L) {
            return DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT
        }

        val candidateLabel = quickLaunchExitCandidateLabel ?: getAppLabel(candidatePackage)
        val detectedAt = formatQuickLaunchDetectionTime(candidateStartedAt)
        return "Detected $candidateLabel at $detectedAt. Will check again in 1m."
    }

    private fun formatQuickLaunchDetectionTime(timestampMs: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun isHardDeadlineCloserThanSessionDeadline(nowMs: Long = System.currentTimeMillis()): Boolean {
        val hardDeadline = hardDeadlineAtMs ?: return false
        val softDistanceMs = currentSessionDeadlineDistanceMs(nowMs) ?: return false
        val hardDistanceMs = abs(hardDeadline - nowMs)
        return hardDistanceMs < softDistanceMs
    }

    private fun currentSessionDeadlineDistanceMs(nowMs: Long): Long? {
        return when (val state = _timerState.value) {
            is TimerState.Counting -> state.remainingMs.coerceAtLeast(0L)
            is TimerState.Expired -> state.overrunMs.coerceAtLeast(0L)
            is TimerState.Idle -> {
                val startedAt = _sessionStartedAtMs.value
                if (startedAt <= 0L) null else (nowMs - startedAt).coerceAtLeast(0L)
            }
        }
    }

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
        logSessionEvent("Timer service onDestroy")
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: IllegalArgumentException) { }
        timerJob?.cancel()
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        clearQuickLaunchExitCandidate()
        clearNotificationInteractionWatch(reason = "service destroy", markSuccess = false)
        negotiationManager?.endConversation()
        lmManager?.shutdown()
        overlayManager.dismissAllNudges()
        overlayManager.dismissQuickLaunchFrame()
        SettingsManager.setTimerRunning(this, false)
        SettingsManager.clearQuickLaunchSession(this)
        _sessionStartedAtMs.value = 0L
        softDeadlineAtMs = null
        hardDeadlineAtMs = null
        overlayManager.setDeadlineState(softDeadlineAtMs, hardDeadlineAtMs)
        super.onDestroy()
    }

    private fun logSessionEvent(event: String) {
        val snapshot = "sessionToken=${sessionTokenForLogs()} state=${timerStateName(_timerState.value)} pkg=${_currentPackage.value.ifBlank { "<none>" }} nudgeCount=${_nudgeCount.value}"
        Log.d(TAG, "$event | $snapshot")
    }

    private fun timerStateName(state: TimerState): String {
        return when (state) {
            is TimerState.Idle -> "Idle"
            is TimerState.Counting -> "Counting(remainingMs=${state.remainingMs},totalMs=${state.totalMs})"
            is TimerState.Expired -> "Expired(overrunMs=${state.overrunMs})"
        }
    }

    companion object {
        private const val TAG = "TimerService"
        private const val TIMER_NOTIFICATION_ID = 1001
        private const val NUDGE_NOTIFICATION_ID = 1002
        private const val QUICK_LAUNCH_NOTIFICATION_ID = 1003
        private const val TIMER_TICK_MS = 20_000L
        private const val NUDGE_LOOP_TICK_MS = 20_000L
        private const val QUICK_LAUNCH_MONITOR_POLL_MS = 20_000L
        private const val QUICK_LAUNCH_SWITCH_GRACE_MS = 60_000L
        private const val USER_AWAY_INACTIVITY_THRESHOLD_MS = 60_000L
        private const val USER_AWAY_SIGNAL_LOOKBACK_MS = 10 * 60_000L
        private const val PREDATORY_BIRD_EVERY_N_BIRDS = 10
        private const val DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT =
            "Quick Launch active - monitoring app switches"
        private val QUICK_LAUNCH_UTILITY_PACKAGES_EXACT = setOf(
            "com.android.camera",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.photos",
            "com.android.gallery3d",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.android.providers.media.module",
            "com.android.permissioncontroller",
            "com.android.systemui",
        )
        private val QUICK_LAUNCH_UTILITY_PACKAGE_PREFIXES = setOf(
            "com.android.camera",
            "com.android.gallery",
            "com.google.android.apps.photos",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.android.providers.media",
            "com.android.providers.downloads",
        )
        private val QUICK_LAUNCH_UTILITY_PACKAGE_KEYWORDS = setOf(
            "camera",
            "gallery",
            "photos",
            "media",
            "picker",
            "documentsui",
            "filemanager",
            "files",
        )
        private val QUICK_LAUNCH_UTILITY_LABEL_KEYWORDS = setOf(
            "camera",
            "gallery",
            "photos",
            "photo",
            "media",
            "files",
            "file manager",
            "file picker",
        )
        private val QUICK_LAUNCH_FRAME_RESTRICTED_PACKAGES_EXACT = setOf(
            "com.samsung.knox.securefolder",
        )
        private val QUICK_LAUNCH_FRAME_RESTRICTED_PACKAGE_PREFIXES = setOf(
            "com.samsung.knox.securefolder",
        )

        const val ACTION_START = "com.mindfulhome.ACTION_START_TIMER"
        const val ACTION_START_QUICK_LAUNCH_SESSION = "com.mindfulhome.ACTION_START_QUICK_LAUNCH_SESSION"
        const val ACTION_TRACK_APP = "com.mindfulhome.ACTION_TRACK_APP"
        const val ACTION_EXTEND = "com.mindfulhome.ACTION_EXTEND_TIMER"
        const val ACTION_STOP = "com.mindfulhome.ACTION_STOP_TIMER"
        const val ACTION_CLEAR_VISIBLE_NUDGES = "com.mindfulhome.ACTION_CLEAR_VISIBLE_NUDGES"
        const val ACTION_HANDLE_REPLY = "com.mindfulhome.ACTION_HANDLE_REPLY"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_HARD_DEADLINE_AT_MS = "hard_deadline_at_ms"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_ALLOWED_PACKAGES = "allowed_packages"
        const val EXTRA_SESSION_TOKEN = "session_token"

        private const val KEY_TEXT_REPLY = "key_text_reply"

        private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
        val timerState: StateFlow<TimerState> = _timerState

        private val _currentPackage = MutableStateFlow("")
        val currentPackage: StateFlow<String> = _currentPackage

        private val _sessionStartedAtMs = MutableStateFlow(0L)
        val sessionStartedAtMs: StateFlow<Long> = _sessionStartedAtMs

        private val _nudgeCount = MutableStateFlow(0)
        val nudgeCount: StateFlow<Int> = _nudgeCount

        fun start(
            context: Context,
            durationMinutes: Int,
            packageName: String,
            sessionHandle: SessionLogger.SessionHandle? = null,
            hardDeadlineMinutes: Int? = null,
        ) {
            val hardDeadlineAtMs = hardDeadlineMinutes
                ?.coerceAtLeast(1)
                ?.let { System.currentTimeMillis() + it * 60_000L }
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                if (hardDeadlineAtMs != null) {
                    putExtra(EXTRA_HARD_DEADLINE_AT_MS, hardDeadlineAtMs)
                }
                attachSession(sessionHandle)
            }
            context.startForegroundService(intent)
        }

        fun startWithDurationMs(
            context: Context,
            durationMs: Long,
            packageName: String,
            sessionHandle: SessionLogger.SessionHandle? = null,
        ) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_MS, durationMs.coerceAtLeast(1_000L))
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                attachSession(sessionHandle)
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

        fun trackApp(
            context: Context,
            packageName: String,
            sessionHandle: SessionLogger.SessionHandle? = null,
        ) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_TRACK_APP
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                attachSession(sessionHandle)
            }
            context.startService(intent)
        }

        fun startQuickLaunchSession(
            context: Context,
            initialPackageName: String,
            allowedQuickLaunchPackages: List<String>,
            sessionHandle: SessionLogger.SessionHandle? = null,
        ) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START_QUICK_LAUNCH_SESSION
                putExtra(EXTRA_PACKAGE_NAME, initialPackageName)
                putStringArrayListExtra(
                    EXTRA_ALLOWED_PACKAGES,
                    ArrayList(allowedQuickLaunchPackages),
                )
                attachSession(sessionHandle)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun clearVisibleNudges(
            context: Context,
            sessionHandle: SessionLogger.SessionHandle? = null,
        ) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_CLEAR_VISIBLE_NUDGES
                attachSession(sessionHandle)
            }
            context.startService(intent)
        }

        private fun Intent.attachSession(sessionHandle: SessionLogger.SessionHandle?) {
            val token = sessionHandle?.token ?: return
            putExtra(EXTRA_SESSION_TOKEN, token)
        }
    }
}
