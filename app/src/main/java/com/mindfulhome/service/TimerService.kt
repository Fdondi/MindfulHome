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
import android.text.format.DateFormat
import android.util.Log
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TimerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null
    private var nudgeJob: Job? = null
    private var quickLaunchMonitorJob: Job? = null
    private var quickLaunchExitDeadlineJob: Job? = null
    private var notificationInteractionTimeoutJob: Job? = null
    private var quickLaunchExitCandidatePackage: String? = null
    private var quickLaunchLastSeenPackage: String = ""
    private var quickLaunchExitCandidateStartedAtMs: Long = 0L
    private var quickLaunchExitCandidateLabel: String? = null
    private var quickLaunchExitDeadlineMs: Long = 0L
    private var quickLaunchExitCandidateKarmaScore: Int = 0
    private val quickLaunchExitResumeByPackage = mutableMapOf<String, QuickLaunchExitSnapshot>()
    private var lastQuickLaunchNotificationText: String? = null
    /** Foreground app currently shown in the Quick Launch monitoring notification (even when allowed/ignored). */
    private var quickLaunchDetectedPackage: String = ""
    private var quickLaunchDetectedLabel: String = ""
    private var quickLaunchDetectedStatus: String = "Quick Launch active - monitoring app switches"
    private var quickLaunchFrameSuppressedForSensitiveApp: Boolean = false
    /** Green/yellow/red segment length for the Quick Launch overlay; shorter when the exit app has negative karma. */
    private var quickLaunchSemaphorePhaseMs: Long = 20_000L
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

    /**
     * Wall-clock instant when the active session timer reaches zero. Driven only by this deadline
     * (not by subtracting fixed ticks), so the last sleep ends exactly at expiry. 0 = no countdown.
     */
    @Volatile
    private var timerEndAtMs: Long = 0L

    /** Matches [TimerState.Counting.totalMs] for the active countdown (updated on extend). */
    @Volatile
    private var timerSessionTotalMs: Long = 0L

    // Nudge conversation: notification is the single chat surface.
    private var negotiationManager: NegotiationManager? = null
    private var lmManager: LiteRtLmManager? = null
    private val nudgeMessages = mutableListOf<NudgeMessage>()
    private var pendingExtensionMinutes: Int? = null
    private var pendingExtensionKeepBannerVisible: Boolean = true
    private val userPerson = Person.Builder().setName("You").setKey("user").build()
    private val aiPerson =
        Person.Builder().setName("MindfulHome").setKey("ai").setBot(true).build()

    private data class NudgeMessage(
        val text: String,
        val isFromUser: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private data class QuickLaunchExitSnapshot(
        val deadlineMs: Long,
        val phaseMs: Long,
        val karmaScore: Int,
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

        quickLaunchSemaphorePhaseMs = SettingsManager.getQuickLaunchSemaphorePhaseNormalMs(this)
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
                restoreQuickLaunchMonitoring(reason = "null-intent restart")
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
            ACTION_RESUME_QUICK_LAUNCH_MONITORING -> {
                if (SettingsManager.isQuickLaunchSessionActive(this)) {
                    logSessionEvent("ACTION_RESUME_QUICK_LAUNCH_MONITORING requested")
                    restoreQuickLaunchMonitoring(reason = "unlock")
                } else {
                    logSessionEvent("Ignoring quick-launch resume: session not active")
                }
            }
            ACTION_PROBE_QUICK_LAUNCH_FOREGROUND -> {
                val reason = intent.getStringExtra(EXTRA_PROBE_REASON) ?: "probe"
                logSessionEvent("ACTION_PROBE_QUICK_LAUNCH_FOREGROUND requested (reason=$reason)")
                runQuickLaunchForegroundProbe(reason)
                if (reason == "launcher-background") {
                    serviceScope.launch {
                        delay(500L)
                        runQuickLaunchForegroundProbe("launcher-background+500ms")
                        delay(1_000L)
                        runQuickLaunchForegroundProbe("launcher-background+1500ms")
                        delay(1_500L)
                        runQuickLaunchForegroundProbe("launcher-background+3000ms")
                    }
                }
            }
            ACTION_TRACK_APP -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                Log.d(TAG, "track app package=$packageName")
                UsageTracker.invalidateForegroundCache()
                if (packageName.isNotBlank() && packageName != _currentPackage.value) {
                    val appLabel = getAppLabel(packageName)
                    logWithSession("Foreground app detected: **$appLabel** (`$packageName`)")
                }
                _currentPackage.value = packageName
                maybeForceTimerForQuickLaunchSwitch(packageName)
                evaluateQuickLaunchExitProgress(packageName)
            }
            ACTION_FOREGROUND_APP_CHANGED -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                handleForegroundAppChanged(packageName)
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
        timerSessionTotalMs = durationMs
        timerEndAtMs = System.currentTimeMillis() + durationMs
        _timerState.value = TimerState.Counting(durationMs, durationMs)
        logSessionEvent(
            "Timer state -> Counting (totalMs=$durationMs, startedAtMs=${_sessionStartedAtMs.value}, package=${packageName.ifBlank { "<none>" }}, hardDeadlineAtMs=${hardDeadlineAtMs ?: 0L})"
        )

        val durationMinutesDisplay = ((durationMs + 59_999L) / 60_000L).toInt()
        val appLabel = getAppLabel(packageName)

        startForeground(TIMER_NOTIFICATION_ID, buildTimerNotification(durationMs))

        timerJob?.cancel()
        timerJob = serviceScope.launch {
            val tickFloor = 1_000L
            while (true) {
                val endAt = timerEndAtMs
                if (endAt <= 0L) return@launch
                val now = System.currentTimeMillis()
                if (now >= endAt) break
                val remaining = endAt - now
                _timerState.value = TimerState.Counting(remaining, timerSessionTotalMs)
                updateTimerNotification(remaining)
                val tick = SettingsManager.getTimerCountdownTickMs(this@TimerService)
                    .coerceAtLeast(tickFloor)
                val untilEnd = endAt - System.currentTimeMillis()
                if (untilEnd <= 0L) break
                delay(min(tick, untilEnd).coerceAtLeast(1L))
            }
            if (timerEndAtMs > 0L && System.currentTimeMillis() >= timerEndAtMs) {
                _timerState.value = TimerState.Counting(0, timerSessionTotalMs)
                updateTimerNotification(0)
                onTimerExpired(packageName)
            }
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
        timerEndAtMs = 0L
        timerSessionTotalMs = 0L
        resetNudgesForNewTimer()
        quickLaunchExitResumeByPackage.clear()
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
        updateQuickLaunchFrameVisibility(initialPackageName, System.currentTimeMillis())
        maybeForceTimerForQuickLaunchSwitch(initialPackageName)
        startQuickLaunchMonitoringLoop()
    }

    private fun maybeForceTimerForQuickLaunchSwitch(packageName: String) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) return
        if (packageName.isBlank()) return

        val label = getAppLabel(packageName)
        val allowedPackages = SettingsManager.getQuickLaunchPackages(this) + this.packageName
        val utilityReason = systemOrUtilityReason(packageName)
        val now = System.currentTimeMillis()

        when {
            packageName in allowedPackages -> {
                clearQuickLaunchExitCandidate(preserveProgress = true, nowMs = now)
                rememberQuickLaunchDetection(
                    packageName = packageName,
                    label = label,
                    status = "Detected $label — allowed Quick Launch app",
                )
                Log.v(TAG, "quick-launch app allowed: $packageName")
                logDeveloperQuickLaunch(
                    "detected package=$packageName label=$label decision=allowed reason=in_quick_launch_allowlist allowlistSize=${allowedPackages.size}",
                )
                refreshQuickLaunchMonitoringNotification()
            }
            utilityReason != null -> {
                clearQuickLaunchExitCandidate(preserveProgress = true, nowMs = now)
                rememberQuickLaunchDetection(
                    packageName = packageName,
                    label = label,
                    status = "Detected $label — ignored ($utilityReason)",
                )
                Log.v(TAG, "quick-launch system/utility app ignored: $packageName ($utilityReason)")
                logDeveloperQuickLaunch(
                    "detected package=$packageName label=$label decision=ignored reason=$utilityReason",
                )
                refreshQuickLaunchMonitoringNotification()
            }
            quickLaunchExitCandidatePackage != packageName -> {
                rememberQuickLaunchExitProgress(now)
                quickLaunchExitCandidatePackage = packageName
                quickLaunchExitCandidateLabel = label
                quickLaunchExitDeadlineMs = 0L
                cancelQuickLaunchExitDeadlineJob()
                rememberQuickLaunchDetection(
                    packageName = packageName,
                    label = label,
                    status = "Detected $label — starting grace",
                )
                serviceScope.launch {
                    configureQuickLaunchExitGrace(packageName, now)
                }
                logWithSession(
                    "Quick Launch switch observed: **$label** — green → yellow → red, then return to timer"
                )
                logSessionEvent(
                    "Quick Launch grace window started for package=$packageName"
                )
                logDeveloperQuickLaunch(
                    "detected package=$packageName label=$label decision=monitor reason=not_allowed_not_utility starting_grace=true",
                )
                refreshQuickLaunchMonitoringNotification()
            }
            else -> {
                rememberQuickLaunchDetection(
                    packageName = packageName,
                    label = label,
                    status = "Detected $label — monitoring",
                )
                logDeveloperQuickLaunch(
                    "detected package=$packageName label=$label decision=monitor reason=same_exit_candidate enforcing_if_due=true",
                )
                enforceQuickLaunchExitIfDue(now)
                refreshQuickLaunchMonitoringNotification()
            }
        }
    }

    private fun rememberQuickLaunchDetection(packageName: String, label: String, status: String) {
        quickLaunchDetectedPackage = packageName
        quickLaunchDetectedLabel = label
        quickLaunchDetectedStatus = status
    }

    private fun logDeveloperQuickLaunch(event: String) {
        if (!SettingsManager.isDeveloperLogsEnabled(this)) return
        val entry = "[DEV][quick-launch] $event"
        Log.d(TAG, entry)
        SessionLogger.log(logSessionHandle ?: SessionLogger.getActiveSessionHandle(), entry)
    }

    private suspend fun configureQuickLaunchExitGrace(packageName: String, nowMs: Long) {
        val k = repository.getKarma(packageName)
        val normalPhaseMs = SettingsManager.getQuickLaunchSemaphorePhaseNormalMs(this@TimerService)
        val baseGraceMs = normalPhaseMs * QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES

        val (phaseMs, graceMs) = when {
            k.isOptedOut -> normalPhaseMs to baseGraceMs
            k.karmaScore > 0 -> {
                val multiplier = SettingsManager.getQuickLaunchSemaphoreKarmaPositiveMultiplier(this@TimerService)
                val phase = (normalPhaseMs * multiplier).toLong().coerceAtLeast(5_000L)
                phase to phase * QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES
            }
            k.karmaScore < 0 -> {
                val grace = KarmaManager.quickLaunchAllowedStayMs(k.karmaScore, baseGraceMs)
                val phase = (grace / QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES).coerceAtLeast(1_000L)
                phase to grace
            }
            else -> normalPhaseMs to baseGraceMs
        }

        quickLaunchSemaphorePhaseMs = phaseMs
        quickLaunchExitCandidateKarmaScore = k.karmaScore

        val existingSnapshot = quickLaunchExitResumeByPackage[packageName]
        if (existingSnapshot != null) {
            if (existingSnapshot.deadlineMs > nowMs) {
                quickLaunchExitDeadlineMs = existingSnapshot.deadlineMs
                quickLaunchSemaphorePhaseMs = existingSnapshot.phaseMs
                quickLaunchExitCandidateKarmaScore = existingSnapshot.karmaScore
                quickLaunchExitCandidateStartedAtMs =
                    existingSnapshot.deadlineMs - (existingSnapshot.phaseMs * QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES)
                logSessionEvent(
                    "Quick Launch grace resumed for package=$packageName (wall-clock deadline preserved)"
                )
                logDeveloperQuickLaunch(
                    "grace resumed package=$packageName karma=${existingSnapshot.karmaScore} phaseMs=${existingSnapshot.phaseMs} deadlineMs=${existingSnapshot.deadlineMs} remainingMs=${existingSnapshot.deadlineMs - nowMs}",
                )
                scheduleQuickLaunchExitEnforcement(packageName)
                refreshQuickLaunchMonitoringNotification()
                return
            }
            logSessionEvent(
                "Quick Launch grace expired for package=$packageName while away — enforcing exit"
            )
            logDeveloperQuickLaunch(
                "grace expired_while_away package=$packageName karma=${existingSnapshot.karmaScore} enforcing_exit=true",
            )
            quickLaunchExitDeadlineMs = existingSnapshot.deadlineMs
            quickLaunchSemaphorePhaseMs = existingSnapshot.phaseMs
            quickLaunchExitCandidateKarmaScore = existingSnapshot.karmaScore
            quickLaunchExitCandidateStartedAtMs =
                existingSnapshot.deadlineMs - (existingSnapshot.phaseMs * QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES)
            quickLaunchExitResumeByPackage.remove(packageName)
            triggerQuickLaunchExit(packageName)
            return
        }

        val configuredAtMs = System.currentTimeMillis()
        quickLaunchExitDeadlineMs = configuredAtMs + graceMs
        quickLaunchExitCandidateStartedAtMs = configuredAtMs
        quickLaunchExitResumeByPackage[packageName] = QuickLaunchExitSnapshot(
            deadlineMs = quickLaunchExitDeadlineMs,
            phaseMs = phaseMs,
            karmaScore = k.karmaScore,
        )
        logDeveloperQuickLaunch(
            "grace configured package=$packageName karma=${k.karmaScore} optedOut=${k.isOptedOut} phaseMs=$phaseMs graceMs=$graceMs deadlineMs=$quickLaunchExitDeadlineMs",
        )
        scheduleQuickLaunchExitEnforcement(packageName)
        refreshQuickLaunchMonitoringNotification()
    }

    private fun scheduleQuickLaunchExitEnforcement(packageName: String) {
        quickLaunchExitDeadlineJob?.cancel()
        val deadlineMs = quickLaunchExitDeadlineMs
        if (deadlineMs <= 0L || packageName.isBlank()) return
        val delayMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        quickLaunchExitDeadlineJob = serviceScope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (quickLaunchExitDeadlineMs != deadlineMs || quickLaunchExitDeadlineMs <= 0L) return@launch
            enforceQuickLaunchExitIfDue()
        }
    }

    private fun enforceQuickLaunchExitIfDue(nowMs: Long = System.currentTimeMillis()) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) return
        val candidatePackage = quickLaunchExitCandidatePackage ?: return
        if (quickLaunchExitDeadlineMs <= 0L) return
        if (nowMs < quickLaunchExitDeadlineMs) return
        logSessionEvent("Quick Launch grace expired for package=$candidatePackage — enforcing exit")
        triggerQuickLaunchExit(candidatePackage)
    }

    private fun cancelQuickLaunchExitDeadlineJob() {
        quickLaunchExitDeadlineJob?.cancel()
        quickLaunchExitDeadlineJob = null
    }

    private fun clearQuickLaunchExitCandidate(
        preserveProgress: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (preserveProgress) {
            rememberQuickLaunchExitProgress(nowMs)
        } else {
            quickLaunchExitCandidatePackage?.let { quickLaunchExitResumeByPackage.remove(it) }
        }
        cancelQuickLaunchExitDeadlineJob()
        quickLaunchExitCandidatePackage = null
        quickLaunchExitCandidateStartedAtMs = 0L
        quickLaunchExitCandidateLabel = null
        quickLaunchExitDeadlineMs = 0L
        quickLaunchExitCandidateKarmaScore = 0
        quickLaunchSemaphorePhaseMs = SettingsManager.getQuickLaunchSemaphorePhaseNormalMs(this)
        refreshQuickLaunchMonitoringNotification()
    }

    private fun rememberQuickLaunchExitProgress(nowMs: Long = System.currentTimeMillis()) {
        val candidatePackage = quickLaunchExitCandidatePackage ?: return
        if (candidatePackage.isBlank() || quickLaunchExitDeadlineMs <= 0L) return
        quickLaunchExitResumeByPackage[candidatePackage] = QuickLaunchExitSnapshot(
            deadlineMs = quickLaunchExitDeadlineMs,
            phaseMs = quickLaunchSemaphorePhaseMs,
            karmaScore = quickLaunchExitCandidateKarmaScore,
        )
    }

    private fun isSystemOrUtilityPackage(packageName: String): Boolean =
        systemOrUtilityReason(packageName) != null

    /**
     * @return human-readable reason if [packageName] should be ignored during Quick Launch,
     * or null if it should be monitored as a normal app switch.
     */
    private fun systemOrUtilityReason(packageName: String): String? {
        if (packageName.isBlank()) return null
        if (packageName == this.packageName) return "self"
        // Keyboards (IMEs) surface as window changes but the user never "left" the current app.
        if (isInputMethodPackage(packageName)) return "keyboard/IME"

        val normalized = packageName.lowercase()
        if (normalized in QUICK_LAUNCH_UTILITY_PACKAGES_EXACT) {
            return "utility exact package"
        }
        val matchedPrefix = QUICK_LAUNCH_UTILITY_PACKAGE_PREFIXES.firstOrNull { normalized.startsWith(it) }
        if (matchedPrefix != null) {
            return "utility package prefix=$matchedPrefix"
        }
        val matchedKeyword = QUICK_LAUNCH_UTILITY_PACKAGE_KEYWORDS.firstOrNull { normalized.contains(it) }
        if (matchedKeyword != null) {
            return "utility package keyword=$matchedKeyword"
        }
        val label = getAppLabel(packageName).lowercase()
        val matchedLabel = QUICK_LAUNCH_UTILITY_LABEL_KEYWORDS.firstOrNull { label.contains(it) }
        if (matchedLabel != null) {
            return "utility label keyword=$matchedLabel"
        }

        // Do NOT treat FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP as "utility".
        // OEM-preinstalled apps (Instagram, etc.) carry those flags and must still be monitored.
        // Keyboards are already filtered via isInputMethodPackage; camera/gallery/files via the
        // lists above. Only skip image/video category apps (share-sheet pickers / media UIs).
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (appInfo.category) {
                    ApplicationInfo.CATEGORY_IMAGE -> "media category=IMAGE"
                    ApplicationInfo.CATEGORY_VIDEO -> "media category=VIDEO"
                    else -> null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private var imePackages: Set<String> = emptySet()
    private var imePackagesFetchedAtMs: Long = 0L

    private fun isInputMethodPackage(packageName: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (imePackages.isEmpty() || now - imePackagesFetchedAtMs > IME_CACHE_TTL_MS) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePackages = imm.inputMethodList.mapTo(mutableSetOf()) { it.packageName }
            imePackagesFetchedAtMs = now
        }
        return packageName in imePackages
    }

    private fun updateQuickLaunchFrameVisibility(foregroundPackage: String, nowMs: Long) {
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

        if (!shouldShowQuickLaunchFrameForPackage(foregroundPackage)) {
            overlayManager.dismissQuickLaunchFrame()
            quickLaunchFrameSuppressedForSensitiveApp = false
            return
        }

        val level = quickLaunchFrameLevelForNow(nowMs)
        overlayManager.showQuickLaunchFrame(level)
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

    private fun shouldShowQuickLaunchFrameForPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (quickLaunchExitCandidatePackage != packageName) return false
        if (quickLaunchExitDeadlineMs <= 0L) return false
        val allowedPackages = SettingsManager.getQuickLaunchPackages(this) + this.packageName
        return packageName !in allowedPackages && !isSystemOrUtilityPackage(packageName)
    }

    private fun quickLaunchFrameLevelForNow(nowMs: Long): OverlayNudgeManager.QuickLaunchFrameLevel {
        val startedAtMs = quickLaunchExitCandidateStartedAtMs
        if (startedAtMs <= 0L) return OverlayNudgeManager.QuickLaunchFrameLevel.GREEN
        val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        val phaseMs = quickLaunchSemaphorePhaseMs
        return when {
            elapsedMs < phaseMs -> OverlayNudgeManager.QuickLaunchFrameLevel.GREEN
            elapsedMs < phaseMs * 2 -> OverlayNudgeManager.QuickLaunchFrameLevel.YELLOW
            else -> OverlayNudgeManager.QuickLaunchFrameLevel.RED
        }
    }

    private fun evaluateQuickLaunchExitProgress(foregroundPackage: String, nowMs: Long = System.currentTimeMillis()) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) {
            overlayManager.dismissQuickLaunchFrame()
            return
        }
        updateQuickLaunchFrameVisibility(foregroundPackage, nowMs)
        enforceQuickLaunchExitIfDue(nowMs)
    }

    private fun triggerQuickLaunchExit(foregroundPackage: String) {
        if (quickLaunchExitCandidatePackage == null) return
        cancelQuickLaunchExitDeadlineJob()
        val karmaScore = quickLaunchExitCandidateKarmaScore
        val appLabel = getAppLabel(foregroundPackage)
        Log.d(TAG, "non-quick app still active after grace: $foregroundPackage")
        logWithSession("Quick Launch exit detected: opened **$appLabel** — returning to timer (karma -1)")
        clearQuickLaunchExitCandidate()
        SettingsManager.clearQuickLaunchSession(this)
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissQuickLaunchFrame()
        serviceScope.launch {
            karmaManager.onQuickLaunchExitAfterRed(foregroundPackage)
        }
        val cheatMs = KarmaManager.cheatScreenDurationMs(karmaScore)
        if (cheatMs != null && cheatMs > 0L) {
            overlayManager.showCheatScreen(cheatMs) {
                forceBackToTimer(MainActivity.FORCE_TIMER_REASON_QUICK_LAUNCH)
            }
        } else {
            forceBackToTimer(MainActivity.FORCE_TIMER_REASON_QUICK_LAUNCH)
        }
    }

    private fun startQuickLaunchMonitoringLoop() {
        quickLaunchMonitorJob?.cancel()
        quickLaunchLastSeenPackage = _currentPackage.value
        Log.d(TAG, "startQuickLaunchMonitoringLoop")
        logSessionEvent("Quick Launch monitor loop started")
        quickLaunchMonitorJob = serviceScope.launch(Dispatchers.Default) {
            while (SettingsManager.isQuickLaunchSessionActive(this@TimerService)) {
                withContext(Dispatchers.Main) {
                    runQuickLaunchForegroundProbe("poll")
                }
                // When the accessibility service is enabled, app switches arrive as events,
                // so the poll only needs to be a slow safety net (catches anything missed);
                // otherwise fall back to the user-configured tight poll.
                val intervalMs = if (ForegroundAppAccessibilityService.isEnabled(this@TimerService)) {
                    EVENT_DRIVEN_SAFETY_POLL_MS
                } else {
                    SettingsManager.getQuickLaunchMonitorMs(this@TimerService)
                }
                delay(intervalMs)
            }
            withContext(Dispatchers.Main) {
                Log.d(TAG, "quick-launch monitoring loop ended")
                logSessionEvent("Quick Launch monitor loop ended")
            }
        }
    }

    private fun runQuickLaunchForegroundProbe(reason: String) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) return
        UsageTracker.invalidateForegroundCache()
        val foregroundPackage = UsageTracker.getForegroundAppForQuickLaunchMonitor(this)
            ?: quickLaunchLastSeenPackage.ifBlank { _currentPackage.value }
        if (foregroundPackage.isBlank()) return
        if (foregroundPackage != quickLaunchLastSeenPackage) {
            Log.d(TAG, "foreground changed ($reason): $quickLaunchLastSeenPackage -> $foregroundPackage")
            quickLaunchLastSeenPackage = foregroundPackage
            _currentPackage.value = foregroundPackage
            maybeForceTimerForQuickLaunchSwitch(foregroundPackage)
        }
        evaluateQuickLaunchExitProgress(foregroundPackage)
        refreshQuickLaunchMonitoringNotification()
    }

    /**
     * Event-driven counterpart to [runQuickLaunchForegroundProbe]: the foreground package is
     * supplied by [ForegroundAppAccessibilityService] instead of being polled from UsageStats.
     * Same downstream logic (allowed/utility filtering, semaphore, forced return).
     */
    private fun handleForegroundAppChanged(foregroundPackage: String) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) return
        if (foregroundPackage.isBlank()) return
        if (foregroundPackage != quickLaunchLastSeenPackage) {
            Log.d(TAG, "foreground changed (a11y-event): $quickLaunchLastSeenPackage -> $foregroundPackage")
            quickLaunchLastSeenPackage = foregroundPackage
            _currentPackage.value = foregroundPackage
            maybeForceTimerForQuickLaunchSwitch(foregroundPackage)
        }
        evaluateQuickLaunchExitProgress(foregroundPackage)
        refreshQuickLaunchMonitoringNotification()
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
                timerSessionTotalMs = newTotal
                timerEndAtMs = System.currentTimeMillis() + newRemaining
                _timerState.value = TimerState.Counting(newRemaining, newTotal)
                updateTimerNotification(newRemaining)
            }
            is TimerState.Idle -> { }
        }
        return true
    }

    private fun onTimerExpired(packageName: String) {
        timerEndAtMs = 0L
        timerSessionTotalMs = 0L
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
            val nudgeStartedAtMs = System.currentTimeMillis()

            while (true) {
                val nudgeTickMs = SettingsManager.getNudgeLoopTickMs(this@TimerService)
                    .coerceAtLeast(1_000L)
                delay(nudgeTickMs)
                val now = System.currentTimeMillis()
                if (now - nudgeStartedAtMs >= MAX_NUDGE_LOOP_DURATION_MS) {
                    logSessionEvent("Nudge loop timed out after ${(MAX_NUDGE_LOOP_DURATION_MS / 60_000L)}m; stopping service")
                    logWithSession("Nudge session ended after a long overrun — returning to normal")
                    stopTimer()
                    return@launch
                }
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
                activeElapsedMs += nudgeTickMs
                stageElapsedMs += nudgeTickMs
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
        if (handlePendingExtensionConfirmationReply(payload, keepBannerVisible = true, source = "banner")) {
            return
        }
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
        timerEndAtMs = 0L
        timerSessionTotalMs = 0L
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
            quickLaunchExitResumeByPackage.clear()

            endNudgeConversation()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            logSessionEvent("Timer service stop completed")
        }
    }

    private fun suspendForScreenOff() {
        logSessionEvent("Suspending timer workflow due to screen off")
        timerJob?.cancel()
        timerEndAtMs = 0L
        timerSessionTotalMs = 0L
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        cancelQuickLaunchExitDeadlineJob()
        overlayManager.dismissAllNudges()
        overlayManager.dismissQuickLaunchFrame()

        SettingsManager.saveScreenOffTimestamp(this)

        val pkg = _currentPackage.value
        val state = _timerState.value
        val appLabel = getAppLabel(pkg)
        val suspendedAtMs = System.currentTimeMillis()
        val quickLaunchActive = SettingsManager.isQuickLaunchSessionActive(this)

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
            _nudgeCount.value = 0
            softDeadlineAtMs = null
            hardDeadlineAtMs = null
            overlayManager.setDeadlineState(softDeadlineAtMs, hardDeadlineAtMs)
            SettingsManager.setTimerRunning(this@TimerService, false)

            endNudgeConversation()
            if (quickLaunchActive) {
                // Screen off should not end Quick Launch; keep session alive so unlock
                // doesn't steal focus back into the launcher.
                logSessionEvent("Screen off during Quick Launch — session preserved; monitoring paused")
            } else {
                SettingsManager.clearQuickLaunchSession(this@TimerService)
                quickLaunchExitResumeByPackage.clear()
                _currentPackage.value = ""
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                logSessionEvent("Timer service suspended and stopped")
            }
        }
    }

    private fun restoreQuickLaunchMonitoring(reason: String) {
        startForeground(
            QUICK_LAUNCH_NOTIFICATION_ID,
            buildQuickLaunchMonitoringNotification(),
        )
        UsageTracker.invalidateForegroundCache()
        val currentForeground = UsageTracker.getForegroundAppForQuickLaunchMonitor(this)
            ?: _currentPackage.value
        logSessionEvent("Restoring quick-launch monitoring (reason=$reason foreground=${currentForeground.ifBlank { "<none>" }})")
        if (currentForeground.isNotBlank()) {
            maybeForceTimerForQuickLaunchSwitch(currentForeground)
        }
        enforceQuickLaunchExitIfDue()
        quickLaunchExitCandidatePackage?.let { candidate ->
            if (quickLaunchExitDeadlineMs > System.currentTimeMillis()) {
                scheduleQuickLaunchExitEnforcement(candidate)
            }
        }
        startQuickLaunchMonitoringLoop()
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
                // Services can't show Google UI, so return null and let the
                // caller decide to fall back to on-device when no session is
                // available. This also handles the "session expired in
                // background" edge: the service won't try to reauth.
                signInForExchange = { null },
                getSessionToken = { ApiKeyManager.getSessionToken(ctx) },
                saveSessionToken = { token, exp ->
                    ApiKeyManager.saveSessionToken(ctx, token, exp)
                },
                clearSessionToken = { ApiKeyManager.clearSessionToken(ctx) },
                isSessionExpiringSoon = { ApiKeyManager.isSessionExpiringSoon(ctx) },
            )
        } else {
            null
        }

        val manager = NegotiationManager(
            context = ctx,
            lmManager = lm,
            repository = repository,
            karmaManager = karmaManager,
            backendAuth = backendAuth,
            backendModel = selectedModel,
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
            ?: intent.getStringExtra(EXTRA_QUICK_REPLY_TEXT)
        if (replyText.isNullOrBlank()) return
        val payload = replyText.trim()
        if (payload.isBlank()) return
        markNotificationInteractionObserved("inline reply")
        logSessionEvent("User replied to nudge (chars=${payload.length})")
        if (handlePendingExtensionConfirmationReply(payload, keepBannerVisible = false, source = "inline")) {
            return
        }
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
        pendingExtensionMinutes = minutes
        pendingExtensionKeepBannerVisible = keepBannerVisible
        val message = buildExtensionConfirmationMessage(minutes)
        nudgeMessages.add(NudgeMessage(message, isFromUser = false))
        showConversationNotification(alertUser = false)
        overlayManager.updateConversationMessage(message, _nudgeCount.value)
        if (keepBannerVisible) {
            overlayManager.showConversationBanner(buildBannerPreviewLines())
        }
        logWithSession("AI offered extension: **$minutes min** (awaiting confirmation)")
        logSessionEvent("AI extension pending confirmation: +$minutes min")
    }

    private fun handlePendingExtensionConfirmationReply(
        payload: String,
        keepBannerVisible: Boolean,
        source: String,
    ): Boolean {
        val pendingMinutes = pendingExtensionMinutes ?: return false
        val normalized = payload.trim().lowercase()
        val isConfirm = normalized == QUICK_REPLY_CONFIRM_EXTENSION ||
            normalized == "y" ||
            normalized.startsWith("${QUICK_REPLY_CONFIRM_EXTENSION} ")
        val isDecline = normalized == QUICK_REPLY_DECLINE_EXTENSION.lowercase() ||
            normalized.contains("i'll close")
        if (!isConfirm && !isDecline) return false

        nudgeMessages.add(NudgeMessage(payload, isFromUser = true))
        logSessionEvent("Pending extension decision via $source: \"$payload\"")
        val keepPendingBannerVisible = pendingExtensionKeepBannerVisible

        if (isConfirm) {
            clearPendingExtensionConfirmation()
            val extended = extendTimer(pendingMinutes)
            if (extended) {
                logSessionEvent("Applying confirmed AI extension: +$pendingMinutes min")
                logWithSession("AI extension confirmed: **+$pendingMinutes min**")
                endNudgeConversation()
                return true
            }

            val blocked =
                "I can't grant that extension now - your hard deadline is now the closest limit."
            nudgeMessages.add(NudgeMessage(blocked, isFromUser = false))
            showConversationNotification(alertUser = false)
            overlayManager.updateConversationMessage(blocked, _nudgeCount.value)
            if (keepBannerVisible || keepPendingBannerVisible) {
                overlayManager.showConversationBanner(buildBannerPreviewLines())
            }
            logWithSession("Confirmed extension blocked by hard deadline")
            logSessionEvent("Confirmed extension blocked by hard deadline")
            return true
        }

        clearPendingExtensionConfirmation()
        nudgeResetRequested = true
        nudgePauseUntilMs = System.currentTimeMillis() + (
            SettingsManager.getNudgeInitialNotificationDelayMinutes(this)
                .coerceAtLeast(0) * 60_000L
            )
        val declinedMessage = "Understood - no extension applied. If it is late, close the app now."
        nudgeMessages.add(NudgeMessage(declinedMessage, isFromUser = false))
        showConversationNotification(alertUser = false)
        overlayManager.updateConversationMessage(declinedMessage, _nudgeCount.value)
        if (keepBannerVisible || keepPendingBannerVisible) {
            overlayManager.showConversationBanner(buildBannerPreviewLines())
        }
        logWithSession("AI extension declined by user")
        logSessionEvent("AI extension declined by user")
        return true
    }

    private fun buildExtensionConfirmationMessage(minutes: Int): String {
        val projectedExpirationMs = calculateProjectedExpirationTimeMs(minutes)
        if (projectedExpirationMs == null) {
            return "This will now make your timer expire later by $minutes minutes. Are you sure?"
        }
        val formattedTime = DateFormat.getTimeFormat(this).format(Date(projectedExpirationMs))
        return "This will now make your timer expire at $formattedTime. Are you sure?"
    }

    private fun calculateProjectedExpirationTimeMs(extraMinutes: Int): Long? {
        val now = System.currentTimeMillis()
        val remainingMs = when (val state = _timerState.value) {
            is TimerState.Counting -> state.remainingMs
            is TimerState.Expired -> 0L
            is TimerState.Idle -> return null
        }
        val projected = now + remainingMs + extraMinutes.coerceAtLeast(0) * 60_000L
        val hardDeadline = hardDeadlineAtMs
        return if (hardDeadline != null && hardDeadline > 0L) {
            minOf(projected, hardDeadline)
        } else {
            projected
        }
    }

    private fun clearPendingExtensionConfirmation() {
        pendingExtensionMinutes = null
        pendingExtensionKeepBannerVisible = true
    }

    private fun endNudgeConversation() {
        logSessionEvent("Ending nudge conversation and clearing overlays/notification")
        clearPendingExtensionConfirmation()
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
        val hasPendingExtensionDecision = pendingExtensionMinutes != null
        val quickConfirmIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_HANDLE_REPLY
            putExtra(EXTRA_QUICK_REPLY_TEXT, QUICK_REPLY_CONFIRM_EXTENSION)
        }
        val quickConfirmPendingIntent = PendingIntent.getService(
            this,
            NUDGE_NOTIFICATION_ID + 1,
            quickConfirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val quickConfirmAction = NotificationCompat.Action.Builder(
            R.drawable.ic_nudge_notification,
            QUICK_REPLY_CONFIRM_EXTENSION,
            quickConfirmPendingIntent,
        ).build()
        val quickDeclineIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_HANDLE_REPLY
            putExtra(EXTRA_QUICK_REPLY_TEXT, QUICK_REPLY_DECLINE_EXTENSION)
        }
        val quickDeclinePendingIntent = PendingIntent.getService(
            this,
            NUDGE_NOTIFICATION_ID + 2,
            quickDeclineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val quickDeclineAction = NotificationCompat.Action.Builder(
            R.drawable.ic_nudge_notification,
            QUICK_REPLY_DECLINE_EXTENSION,
            quickDeclinePendingIntent,
        ).build()

        // Build the conversation as a MessagingStyle notification
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
        for (msg in nudgeMessages) {
            // In MessagingStyle, null sender = message from the device user
            val sender = if (msg.isFromUser) null else aiPerson
            messagingStyle.addMessage(
                NotificationCompat.MessagingStyle.Message(msg.text, msg.timestamp, sender)
            )
        }

        val notificationBuilder = NotificationCompat.Builder(this, MindfulHomeApp.NUDGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nudge_notification)
            .setContentIntent(tapPendingIntent)
            .setStyle(messagingStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setSilent(!alertUser)
            .setAutoCancel(false)
            .setOngoing(false)
        if (hasPendingExtensionDecision) {
            notificationBuilder.addAction(quickConfirmAction)
            notificationBuilder.addAction(quickDeclineAction)
            notificationBuilder.addAction(replyAction)
        } else {
            notificationBuilder.addAction(replyAction)
        }
        val notification = notificationBuilder.build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NUDGE_NOTIFICATION_ID, notification)
    }

    private fun armNotificationInteractionWatch(source: String) {
        notificationInteractionTimeoutJob?.cancel()
        awaitingNotificationInteraction = true
        val timeoutMs = SettingsManager
            .getNudgeInteractionWatchTimeoutMinutes(this) * 60_000L
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
        if (candidatePackage.isNullOrBlank() || quickLaunchExitDeadlineMs <= 0L) {
            return if (quickLaunchDetectedPackage.isNotBlank()) {
                quickLaunchDetectedStatus
            } else {
                DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT
            }
        }

        val candidateLabel = quickLaunchExitCandidateLabel
            ?: quickLaunchDetectedLabel.takeIf { quickLaunchDetectedPackage == candidatePackage }
            ?: getAppLabel(candidatePackage)
        val phaseMs = quickLaunchSemaphorePhaseMs
        val elapsedMs = if (quickLaunchExitDeadlineMs > 0L) {
            (phaseMs * QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES) -
                (quickLaunchExitDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        } else {
            0L
        }
        val phase = when {
            elapsedMs < phaseMs -> "green"
            elapsedMs < phaseMs * 2 -> "yellow"
            else -> "red"
        }
        val remainingMs = (quickLaunchExitDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val remainingSeconds = (remainingMs + 999L) / 1_000L
        val countdownLabel = if (remainingMs <= 0L) {
            "redirecting now"
        } else {
            "forcing home in ${remainingSeconds}s"
        }
        return "Detected $candidateLabel. Phase $phase, $countdownLabel."
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
        timerEndAtMs = 0L
        timerSessionTotalMs = 0L
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        clearQuickLaunchExitCandidate()
        clearNotificationInteractionWatch(reason = "service destroy", markSuccess = false)
        negotiationManager?.endConversation()
        lmManager?.shutdown()
        overlayManager.dismissAllNudges()
        overlayManager.dismissQuickLaunchFrame()
        SettingsManager.setTimerRunning(this, false)
        quickLaunchExitResumeByPackage.clear()
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
        private const val QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES = 3
        private const val USER_AWAY_INACTIVITY_THRESHOLD_MS = 60_000L
        private const val USER_AWAY_SIGNAL_LOOKBACK_MS = 10 * 60_000L
        private const val MAX_NUDGE_LOOP_DURATION_MS = 30 * 60_000L
        /** Slow safety poll used while the accessibility service supplies switch events. */
        private const val EVENT_DRIVEN_SAFETY_POLL_MS = 30_000L
        private const val PREDATORY_BIRD_EVERY_N_BIRDS = 10
        private const val DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT =
            "Quick Launch active - monitoring app switches"
        private const val IME_CACHE_TTL_MS = 5 * 60_000L
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
        const val ACTION_RESUME_QUICK_LAUNCH_MONITORING = "com.mindfulhome.ACTION_RESUME_QUICK_LAUNCH_MONITORING"
        const val ACTION_PROBE_QUICK_LAUNCH_FOREGROUND = "com.mindfulhome.ACTION_PROBE_QUICK_LAUNCH_FOREGROUND"
        const val ACTION_TRACK_APP = "com.mindfulhome.ACTION_TRACK_APP"
        const val ACTION_FOREGROUND_APP_CHANGED = "com.mindfulhome.ACTION_FOREGROUND_APP_CHANGED"
        const val ACTION_EXTEND = "com.mindfulhome.ACTION_EXTEND_TIMER"
        const val ACTION_STOP = "com.mindfulhome.ACTION_STOP_TIMER"
        const val ACTION_CLEAR_VISIBLE_NUDGES = "com.mindfulhome.ACTION_CLEAR_VISIBLE_NUDGES"
        const val ACTION_HANDLE_REPLY = "com.mindfulhome.ACTION_HANDLE_REPLY"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_HARD_DEADLINE_AT_MS = "hard_deadline_at_ms"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_PROBE_REASON = "probe_reason"
        const val EXTRA_ALLOWED_PACKAGES = "allowed_packages"
        const val EXTRA_SESSION_TOKEN = "session_token"

        private const val KEY_TEXT_REPLY = "key_text_reply"
        private const val EXTRA_QUICK_REPLY_TEXT = "extra_quick_reply_text"
        private const val QUICK_REPLY_CONFIRM_EXTENSION = "yes"
        private const val QUICK_REPLY_DECLINE_EXTENSION = "oh, it IS late, I'll close"

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
            context.startForegroundService(intent)
        }

        /**
         * Push a foreground-app change detected out-of-band (e.g. by
         * [ForegroundAppAccessibilityService]) into the running service. Cheap: when the
         * Quick Launch foreground service is already alive this just delivers an intent to
         * the existing instance — no new process, no poll.
         */
        fun notifyForegroundApp(context: Context, packageName: String) {
            if (packageName.isBlank()) return
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_FOREGROUND_APP_CHANGED
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
            context.startForegroundService(intent)
        }

        fun probeQuickLaunchForeground(
            context: Context,
            reason: String,
            sessionHandle: SessionLogger.SessionHandle? = null,
        ) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_PROBE_QUICK_LAUNCH_FOREGROUND
                putExtra(EXTRA_PROBE_REASON, reason)
                attachSession(sessionHandle)
            }
            context.startForegroundService(intent)
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

        fun resumeQuickLaunchMonitoring(
            context: Context,
            sessionHandle: SessionLogger.SessionHandle? = null,
        ) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_RESUME_QUICK_LAUNCH_MONITORING
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
