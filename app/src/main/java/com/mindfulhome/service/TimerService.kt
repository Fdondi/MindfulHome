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
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.mindfulhome.MainActivity
import com.mindfulhome.MindfulHomeApp
import com.mindfulhome.R
import com.mindfulhome.ai.LmPlaygroundManager
import com.mindfulhome.ai.NegotiationManager
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.BackendAuthHelper
import com.mindfulhome.data.AppRepository
import com.mindfulhome.locale.LocaleHelper
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.model.TimerState
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.util.QuickLaunchAppRef
import com.mindfulhome.util.QuickLaunchUtilityClassifier
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
import kotlin.math.max

class TimerService : Service() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    /** User-visible copy for the current in-app language (Services keep a stale config). */
    private fun locString(id: Int, vararg formatArgs: Any): String {
        val localized = LocaleHelper.wrap(this)
        return if (formatArgs.isEmpty()) {
            localized.getString(id)
        } else {
            localized.getString(id, *formatArgs)
        }
    }

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
    private var quickLaunchDetectedStatus: String = DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT
    private var quickLaunchFrameSuppressedForSensitiveApp: Boolean = false
    /** Green/yellow/red segment length for the Quick Launch overlay; shorter when the exit app has negative karma. */
    private var quickLaunchSemaphorePhaseMs: Long = 20_000L
    /** Debounce repeated instant overtime redirects while MainActivity is coming to the front. */
    private lateinit var repository: AppRepository
    private lateinit var karmaManager: KarmaManager
    private lateinit var overlayManager: OverlayNudgeManager
    private var conversationGraceUntilMs: Long = 0L
    private var conversationGraceExpiryJob: Job? = null
    /** Escalation time banked while conversation grace pauses birds; burned at 10× after grace expires. */
    private var catchUpDebtMs: Long = 0L
    private var suppressPredatoryKarmaThisTick: Boolean = false
    private var awaitingNotificationInteraction: Boolean = false
    private var preferBannerFallbackForOverlayTap: Boolean = false
    private var logSessionHandle: SessionLogger.SessionHandle? = null
    private var hardDeadlineAtMs: Long? = null
    private var softDeadlineAtMs: Long? = null
    private var userAwayOverlayActive: Boolean = false
    private var awayShieldShownForCurrentAwayEpisode: Boolean = false
    private var lastAwayOverlayTapAtMs: Long = 0L
    /**
     * Bumped whenever a new timer or Quick Launch session starts. Async [stopTimer] cleanup
     * aborts if this no longer matches the generation it captured — otherwise a stale stop
     * races with [ensureQuickLaunchMonitoringAtHome] and [stopSelf] kills the freshly
     * restarted monitor (no notification / no color countdown).
     */
    private var sessionGeneration: Int = 0
    private var launcherBackgroundProbeJob: Job? = null
    private val utilityClassifier: QuickLaunchUtilityClassifier by lazy {
        QuickLaunchUtilityClassifier(
            signals = QuickLaunchUtilityClassifier.AndroidPackageSignals(this),
            selfPackageName = packageName,
        )
    }

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
    private var lmManager: LmPlaygroundManager? = null
    private val nudgeMessages = mutableListOf<NudgeMessage>()
    private var pendingExtensionMinutes: Int? = null
    private var pendingExtensionKeepBannerVisible: Boolean = true
    private fun userPerson(): Person =
        Person.Builder().setName(locString(R.string.notif_sender_you)).setKey("user").build()

    private fun aiPerson(): Person =
        Person.Builder().setName(locString(R.string.app_name)).setKey("ai").setBot(true).build()

    private fun extendConfirmText(): String = locString(R.string.notif_extend_confirm)

    private fun extendDeclineText(): String = locString(R.string.notif_extend_decline)

    private data class NudgeMessage(
        val text: String,
        val isFromUser: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private data class QuickLaunchExitSnapshot(
        val deadlineMs: Long,
        val phaseMs: Long,
        val karmaScore: Int,
    ) {
        fun toLogicSnapshot() = QuickLaunchExitResumeSnapshot(
            deadlineMs = deadlineMs,
            phaseMs = phaseMs,
            karmaScore = karmaScore,
        )
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
        overlayManager.onBannerReplyFocusChanged = { onBannerReplyFocusChanged(it) }
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
        handleCommand(mapIntentExtrasToCommand(intent, action), intent)
        return START_STICKY
    }

    private fun mapIntentExtrasToCommand(intent: Intent?, action: String?): TimerServiceCommand =
        mapIntentToCommand(
            action = action,
            quickLaunchSessionActive = SettingsManager.isQuickLaunchSessionActive(this),
            durationMsExtra = intent?.getLongExtra(EXTRA_DURATION_MS, -1L) ?: -1L,
            durationMinutes = intent?.getIntExtra(EXTRA_DURATION_MINUTES, 5) ?: 5,
            packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME) ?: "",
            hardDeadlineRaw = intent?.getLongExtra(EXTRA_HARD_DEADLINE_AT_MS, 0L) ?: 0L,
            allowedPackages = intent?.getStringArrayListExtra(EXTRA_ALLOWED_PACKAGES),
            probeReason = intent?.getStringExtra(EXTRA_PROBE_REASON) ?: "probe",
        )

    private fun handleCommand(command: TimerServiceCommand, intent: Intent?) {
        if (dispatchStartCommands(command)) return
        if (dispatchQuickLaunchCommands(command)) return
        if (dispatchSessionCommands(command, intent)) return
    }

    private fun dispatchStartCommands(command: TimerServiceCommand): Boolean = when (command) {
        is TimerServiceCommand.Start -> {
            handleStartCommand(command); true
        }
        is TimerServiceCommand.StartQuickLaunch -> {
            handleStartQuickLaunchCommand(command); true
        }
        is TimerServiceCommand.Extend, TimerServiceCommand.Stop -> {
            handleExtendOrStop(command); true
        }
        else -> false
    }

    private fun handleStartCommand(command: TimerServiceCommand.Start) {
        logSessionEvent(
            "ACTION_START requested: durationMs=${command.durationMs} " +
                "package=${command.packageName.ifBlank { "<none>" }} " +
                "hardDeadlineAtMs=${command.hardDeadlineAtMs ?: 0L}",
        )
        startTimer(command.durationMs, command.packageName, command.hardDeadlineAtMs)
    }

    private fun handleStartQuickLaunchCommand(command: TimerServiceCommand.StartQuickLaunch) {
        logSessionEvent(
            "ACTION_START_QUICK_LAUNCH_SESSION requested: " +
                "initial=${command.packageName.ifBlank { "<none>" }} " +
                "allowed=${command.allowedPackages.size}",
        )
        startQuickLaunchSession(command.packageName, command.allowedPackages)
    }

    private fun handleExtendOrStop(command: TimerServiceCommand) {
        when (command) {
            is TimerServiceCommand.Extend -> {
                logSessionEvent("ACTION_EXTEND requested: +${command.extraMinutes} min")
                if (!extendTimer(command.extraMinutes)) {
                    logWithSession("Extension blocked due to hard deadline proximity")
                }
            }
            TimerServiceCommand.Stop -> {
                logSessionEvent("ACTION_STOP requested")
                stopTimer()
            }
            else -> Unit
        }
    }

    private fun dispatchQuickLaunchCommands(command: TimerServiceCommand): Boolean = when (command) {
        TimerServiceCommand.ResumeQuickLaunch,
        TimerServiceCommand.IgnoreResumeQuickLaunch,
        TimerServiceCommand.RestoreQuickLaunch,
        -> {
            handleQlSessionCommand(command); true
        }
        is TimerServiceCommand.ProbeQuickLaunch -> {
            handleProbeQuickLaunch(command.reason); true
        }
        is TimerServiceCommand.TrackApp -> {
            handleTrackAppCommand(command.packageName); true
        }
        is TimerServiceCommand.ForegroundAppChanged -> {
            handleForegroundAppChanged(command.packageName); true
        }
        else -> false
    }

    private fun handleQlSessionCommand(command: TimerServiceCommand) {
        when (command) {
            TimerServiceCommand.ResumeQuickLaunch -> {
                logSessionEvent("ACTION_RESUME_QUICK_LAUNCH_MONITORING requested")
                restoreQuickLaunchMonitoring(reason = "unlock")
            }
            TimerServiceCommand.IgnoreResumeQuickLaunch ->
                logSessionEvent("Ignoring quick-launch resume: session not active")
            TimerServiceCommand.RestoreQuickLaunch -> {
                Log.w(TAG, "Null intent restart - restoring quick launch monitoring")
                logSessionEvent("Service restarted with null intent — restoring quick launch monitor")
                restoreQuickLaunchMonitoring(reason = "null-intent restart")
            }
            else -> Unit
        }
    }

    private fun dispatchSessionCommands(command: TimerServiceCommand, intent: Intent?): Boolean =
        when (command) {
            TimerServiceCommand.EngageExtendChat,
            TimerServiceCommand.ClearVisibleNudges,
            TimerServiceCommand.HandleReply,
            TimerServiceCommand.NullIntentNoOp,
            TimerServiceCommand.Unknown,
            -> {
                handleSessionSideCommand(command, intent); true
            }
            else -> false
        }

    private fun handleSessionSideCommand(command: TimerServiceCommand, intent: Intent?) {
        when (command) {
            TimerServiceCommand.EngageExtendChat -> engageExtendChatCommand()
            TimerServiceCommand.ClearVisibleNudges -> clearVisibleNudgesCommand()
            TimerServiceCommand.HandleReply -> handleReplyCommand(intent)
            else -> Unit
        }
    }

    private fun handleReplyCommand(intent: Intent?) {
        if (intent != null) handleNudgeReply(intent)
    }

    private fun engageExtendChatCommand() {
        logSessionEvent("ACTION_ENGAGE_EXTEND_CHAT requested")
        engageExtendChat()
    }

    private fun clearVisibleNudgesCommand() {
        val cleared = overlayManager.dismissAllNudgesIfPresent()
        Log.d(
            TAG,
            if (cleared) "ACTION_CLEAR_VISIBLE_NUDGES: removed visible nudges"
            else "ACTION_CLEAR_VISIBLE_NUDGES: no-op (nothing visible)",
        )
    }


    private fun handleProbeQuickLaunch(reason: String) {
        logSessionEvent("ACTION_PROBE_QUICK_LAUNCH_FOREGROUND requested (reason=$reason)")
        runQuickLaunchForegroundProbe(reason)
        if (reason != "launcher-background") return
        launcherBackgroundProbeJob?.cancel()
        launcherBackgroundProbeJob = serviceScope.launch {
            delay(500L)
            runQuickLaunchForegroundProbe("launcher-background+500ms")
            delay(1_000L)
            runQuickLaunchForegroundProbe("launcher-background+1500ms")
            delay(1_500L)
            runQuickLaunchForegroundProbe("launcher-background+3000ms")
        }
    }

    private fun handleTrackAppCommand(packageName: String) {
        Log.d(TAG, "track app package=$packageName")
        UsageTracker.invalidateForegroundCache()
        if (packageName.isNotBlank() && packageName != _currentPackage.value) {
            val appLabel = getAppLabel(packageName)
            logWithSession("Foreground app detected: **$appLabel** (`$packageName`)")
        }
        _currentPackage.value = packageName
        serviceScope.launch {
            handleForegroundPackage(packageName, packageChanged = true)
        }
    }

    // ── Timer lifecycle ──────────────────────────────────────────────

    private fun startTimer(durationMs: Long, packageName: String, hardDeadlineAtMs: Long?) {
        prepareNewTimedSession(durationMs, packageName, hardDeadlineAtMs)
        startForeground(TIMER_NOTIFICATION_ID, buildTimerNotification(durationMs))
        timerJob?.cancel()
        timerJob = serviceScope.launch { runCountdownLoop(packageName) }
    }

    private fun prepareNewTimedSession(
        durationMs: Long,
        packageName: String,
        hardDeadlineAtMs: Long?,
    ) {
        sessionGeneration++
        // Committing to a timed session — expiry from here is birds/notification only.
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
            "Timer state -> Counting (totalMs=$durationMs, startedAtMs=${_sessionStartedAtMs.value}, " +
                "package=${packageName.ifBlank { "<none>" }}, hardDeadlineAtMs=${hardDeadlineAtMs ?: 0L})",
        )
    }

    private suspend fun runCountdownLoop(packageName: String) {
        val tickFloor = 1_000L
        while (true) {
            val endAt = timerEndAtMs
            if (countdownShouldAbort(endAt)) return
            if (!runOneCountdownTick(endAt, tickFloor)) break
        }
        if (shouldFireCountdownExpiry(timerEndAtMs, System.currentTimeMillis())) {
            _timerState.value = TimerState.Counting(0, timerSessionTotalMs)
            updateTimerNotification(0)
            onTimerExpired(packageName)
        }
    }

    /** @return false when the loop should break (time reached). */
    private suspend fun runOneCountdownTick(endAt: Long, tickFloor: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now >= endAt) return false
        val remaining = endAt - now
        _timerState.value = TimerState.Counting(remaining, timerSessionTotalMs)
        updateTimerNotification(remaining)
        val tick = SettingsManager.getTimerCountdownTickMs(this@TimerService)
            .coerceAtLeast(tickFloor)
        val untilEnd = endAt - System.currentTimeMillis()
        if (untilEnd <= 0L) return false
        delay(countdownDelayMs(tick, untilEnd))
        return true
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
        clearConversationGrace(reason = "nudge reset for new timer")
        catchUpDebtMs = 0L
        suppressPredatoryKarmaThisTick = false
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
        sessionGeneration++
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

    private fun maybeForceTimerForQuickLaunchSwitch(
        packageName: String,
        previousPackage: String = "",
    ) {
        if (!SettingsManager.isQuickLaunchSessionActive(this)) return
        if (packageName.isBlank()) return
        val label = getAppLabel(packageName)
        val allowedPackages = SettingsManager.getQuickLaunchPackages(this) + this.packageName
        val now = System.currentTimeMillis()
        val decision = decideQuickLaunchSwitch(
            packageName = packageName,
            allowedPackages = allowedPackages,
            utilityReason = systemOrUtilityReason(packageName),
            currentExitCandidatePackage = quickLaunchExitCandidatePackage,
            resume = quickLaunchExitResumeByPackage[packageName]?.toLogicSnapshot(),
            nowMs = now,
        )
        dispatchQuickLaunchSwitch(decision, packageName, previousPackage, label, now, allowedPackages.size)
    }

    private fun dispatchQuickLaunchSwitch(
        decision: QuickLaunchSwitchDecision,
        packageName: String,
        previousPackage: String,
        label: String,
        now: Long,
        allowlistSize: Int,
    ) {
        when (decision) {
            QuickLaunchSwitchDecision.Allowed ->
                handleQlAllowed(packageName, label, now, allowlistSize)
            is QuickLaunchSwitchDecision.Ignore ->
                handleQlIgnored(decision, packageName, label, now)
            QuickLaunchSwitchDecision.StartBirds ->
                handleQlStartBirds(packageName, label, now)
            QuickLaunchSwitchDecision.StartGrace ->
                handleQlStartGrace(packageName, label, now)
            QuickLaunchSwitchDecision.ContinueMonitor ->
                handleQlContinueMonitor(packageName, label, now)
        }
    }

    private fun handleQlAllowed(packageName: String, label: String, now: Long, allowlistSize: Int) {
        clearQuickLaunchExitCandidate(preserveProgress = true, nowMs = now)
        rememberQuickLaunchDetection(
            packageName = packageName,
            label = label,
            status = locString(R.string.notif_ql_detected_allowed, label),
        )
        Log.v(TAG, "quick-launch app allowed: $packageName")
        logDeveloperQuickLaunch(
            "detected package=$packageName label=$label decision=allowed " +
                "reason=in_quick_launch_allowlist allowlistSize=$allowlistSize",
        )
        refreshQuickLaunchMonitoringNotification()
    }

    private fun handleQlIgnored(
        decision: QuickLaunchSwitchDecision.Ignore,
        packageName: String,
        label: String,
        now: Long,
    ) {
        val statusReason = quickLaunchIgnoreStatusReason(decision.reason)
        rememberQuickLaunchDetection(
            packageName = packageName,
            label = label,
            status = locString(R.string.notif_ql_detected_ignored, label, statusReason),
        )
        clearQuickLaunchExitCandidate(preserveProgress = true, nowMs = now)
        Log.v(TAG, "quick-launch system/utility app ignored: $packageName (${decision.reason})")
        logDeveloperQuickLaunch(
            "detected package=$packageName label=$label decision=ignored reason=${decision.reason}",
        )
        refreshQuickLaunchMonitoringNotification()
    }

    private fun handleQlStartBirds(packageName: String, label: String, now: Long) {
        rememberQuickLaunchExitProgress(now)
        quickLaunchExitCandidatePackage = packageName
        quickLaunchExitCandidateLabel = label
        rememberQuickLaunchDetection(
            packageName = packageName,
            label = label,
            status = locString(R.string.notif_ql_detected_instant_gate, label),
        )
        logWithSession("Restricted app grace used up: **$label** — starting birds (never block)")
        logDeveloperQuickLaunch(
            "detected package=$packageName label=$label decision=start_birds",
        )
        triggerQuickLaunchExit(packageName)
    }

    private fun handleQlStartGrace(packageName: String, label: String, now: Long) {
        rememberQuickLaunchExitProgress(now)
        quickLaunchExitCandidatePackage = packageName
        quickLaunchExitCandidateLabel = label
        quickLaunchExitDeadlineMs = 0L
        cancelQuickLaunchExitDeadlineJob()
        rememberQuickLaunchDetection(
            packageName = packageName,
            label = label,
            status = locString(R.string.notif_ql_detected_starting_grace, label),
        )
        serviceScope.launch { configureQuickLaunchExitGrace(packageName, now) }
        logWithSession("Quick Launch switch observed: **$label** — green → yellow → red, then birds")
        logSessionEvent("Quick Launch grace window started for package=$packageName")
        logDeveloperQuickLaunch(
            "detected package=$packageName label=$label decision=monitor " +
                "reason=not_allowed_not_utility starting_grace=true",
        )
        refreshQuickLaunchMonitoringNotification()
    }

    private fun handleQlContinueMonitor(packageName: String, label: String, now: Long) {
        rememberQuickLaunchDetection(
            packageName = packageName,
            label = label,
            status = locString(R.string.notif_ql_detected_monitoring, label),
        )
        logDeveloperQuickLaunch(
            "detected package=$packageName label=$label decision=monitor " +
                "reason=same_exit_candidate enforcing_if_due=true",
        )
        enforceQuickLaunchExitIfDue(now)
        refreshQuickLaunchMonitoringNotification()
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
        val positiveMultiplier =
            SettingsManager.getQuickLaunchSemaphoreKarmaPositiveMultiplier(this@TimerService)
        val timing = computeQuickLaunchGraceTiming(
            karmaScore = k.karmaScore,
            isOptedOut = k.isOptedOut,
            normalPhaseMs = normalPhaseMs,
            positiveMultiplier = positiveMultiplier,
        )
        val phaseMs = timing.phaseMs
        val graceMs = timing.graceMs

        quickLaunchSemaphorePhaseMs = phaseMs
        quickLaunchExitCandidateKarmaScore = k.karmaScore

        val existingSnapshot = quickLaunchExitResumeByPackage[packageName]?.toLogicSnapshot()
        when (val resumeAction = decideQuickLaunchGraceResume(existingSnapshot, nowMs)) {
            is QuickLaunchGraceResumeAction.ResumeExisting -> {
                quickLaunchExitDeadlineMs = resumeAction.deadlineMs
                quickLaunchSemaphorePhaseMs = resumeAction.phaseMs
                quickLaunchExitCandidateKarmaScore = resumeAction.karmaScore
                quickLaunchExitCandidateStartedAtMs = resumeAction.startedAtMs
                logSessionEvent(
                    "Quick Launch grace resumed for package=$packageName (wall-clock deadline preserved)"
                )
                logDeveloperQuickLaunch(
                    "grace resumed package=$packageName karma=${resumeAction.karmaScore} phaseMs=${resumeAction.phaseMs} deadlineMs=${resumeAction.deadlineMs} remainingMs=${resumeAction.deadlineMs - nowMs}",
                )
                scheduleQuickLaunchExitEnforcement(packageName)
                refreshQuickLaunchMonitoringNotification()
                return
            }
            is QuickLaunchGraceResumeAction.EnforceExpired -> {
                logSessionEvent(
                    "Quick Launch grace expired for package=$packageName while away — enforcing exit"
                )
                logDeveloperQuickLaunch(
                    "grace expired_while_away package=$packageName karma=${resumeAction.karmaScore} enforcing_exit=true",
                )
                quickLaunchExitDeadlineMs = resumeAction.deadlineMs
                quickLaunchSemaphorePhaseMs = resumeAction.phaseMs
                quickLaunchExitCandidateKarmaScore = resumeAction.karmaScore
                quickLaunchExitCandidateStartedAtMs = resumeAction.startedAtMs
                quickLaunchExitResumeByPackage.remove(packageName)
                triggerQuickLaunchExit(packageName)
                return
            }
            QuickLaunchGraceResumeAction.ConfigureNew -> {
                // fall through to fresh configuration
            }
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
        utilityClassifier.isUtility(packageName)

    /**
     * @return human-readable reason if [packageName] should be ignored during Quick Launch,
     * or null if it should be monitored as a normal app switch.
     */
    private fun systemOrUtilityReason(packageName: String): String? =
        utilityClassifier.utilityReason(packageName)

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

        val level = resolveQuickLaunchFrameLevel(nowMs)
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

    private fun resolveQuickLaunchFrameLevel(nowMs: Long): OverlayNudgeManager.QuickLaunchFrameLevel {
        return when (
            quickLaunchFrameLevelForNow(
                nowMs = nowMs,
                startedAtMs = quickLaunchExitCandidateStartedAtMs,
                phaseMs = quickLaunchSemaphorePhaseMs,
            )
        ) {
            QuickLaunchFrameLevel.GREEN -> OverlayNudgeManager.QuickLaunchFrameLevel.GREEN
            QuickLaunchFrameLevel.YELLOW -> OverlayNudgeManager.QuickLaunchFrameLevel.YELLOW
            QuickLaunchFrameLevel.RED -> OverlayNudgeManager.QuickLaunchFrameLevel.RED
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
        launcherBackgroundProbeJob?.cancel()
        launcherBackgroundProbeJob = null
        val appLabel = getAppLabel(foregroundPackage)
        Log.d(TAG, "non-quick app still active after grace: $foregroundPackage")
        logWithSession(
            "Quick Launch exit detected: opened **$appLabel** — starting bird nudges (stay in app)",
        )
        clearQuickLaunchExitCandidate()
        SettingsManager.clearQuickLaunchSession(this)
        quickLaunchMonitorJob?.cancel()
        overlayManager.dismissQuickLaunchFrame()
        promoteToExpiredBirds(foregroundPackage)
    }

    /**
     * QL grace expired: keep service in [TimerState.Expired] and start bird nudges.
     * Never force home. Must not stop→Idle→default or QL monitoring restarts.
     */
    private fun promoteToExpiredBirds(packageName: String) {
        serviceScope.launch {
            repository.getKarma(packageName) // ensure tracked for Karma screen / opt-out
        }
        _currentPackage.value = packageName
        timerEndAtMs = 0L
        timerSessionTotalMs = 0L
        _timerState.value = TimerState.Expired(0)
        softDeadlineAtMs = System.currentTimeMillis()
        overlayManager.setDeadlineState(softDeadlineAtMs, hardDeadlineAtMs)
        SettingsManager.setTimerRunning(this, false)
        logSessionEvent(
            "Timer state -> Expired via QL birds (package=${packageName.ifBlank { "<none>" }})",
        )
        startNudgeConversation(packageName)
        _nudgeCount.value = 0
        startNudging(packageName)
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

    private fun maybeResumeSuspendedSessionForPackage(packageName: String): Boolean {
        val saved = SettingsManager.getLastSession(this) ?: return false
        val state = _timerState.value
        val timerIsIdleOrExpired = state is TimerState.Idle || state is TimerState.Expired
        if (!shouldAutoResumeSuspendedSession(
                foregroundPackage = packageName,
                foregroundOwnerPackage = QuickLaunchAppRef.ownerPackage(packageName),
                savedSessionPackage = saved.packageName,
                savedRemainingMs = saved.remainingMs,
                timerIsIdleOrExpired = timerIsIdleOrExpired,
            )
        ) {
            return false
        }
        val label = getAppLabel(saved.packageName)
        logWithSession(
            "Returning to suspended session app **$label** — resuming timer invisibly",
        )
        logSessionEvent(
            "Auto-resume suspended session for package=${saved.packageName} " +
                "remainingMs=${saved.remainingMs}",
        )
        clearQuickLaunchExitCandidate()
        overlayManager.dismissQuickLaunchFrame()
        startTimer(saved.remainingMs, saved.packageName, null)
        return true
    }

    private suspend fun maybeStartTimedQuickLaunchTimer(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == this.packageName) return false
        if (systemOrUtilityReason(packageName) != null) return false
        val limitMinutes = repository.quickLaunchLimitMinutesFor(packageName) ?: return false
        if (!shouldStartTimedQuickLaunchFromTimerState(_timerState.value is TimerState.Idle)) {
            return false
        }
        val label = getAppLabel(packageName)
        logSessionEvent(
            "Timed Quick Launch auto-start: $label ($limitMinutes min) package=$packageName",
        )
        logWithSession(
            "Timed app opened externally: **$label** — starting **$limitMinutes min** timer",
        )
        startTimer(limitMinutes * 60_000L, packageName, null)
        return true
    }

    private suspend fun handleForegroundPackage(
        packageName: String,
        packageChanged: Boolean,
        previousPackage: String = "",
    ) {
        if (packageName.isBlank()) return
        if (packageChanged && maybeStartTimedQuickLaunchTimer(packageName)) return
        if (packageChanged && maybeResumeSuspendedSessionForPackage(packageName)) return
        if (!SettingsManager.isQuickLaunchSessionActive(this)) return
        if (packageChanged) {
            maybeForceTimerForQuickLaunchSwitch(packageName, previousPackage)
        }
        evaluateQuickLaunchExitProgress(packageName)
        refreshQuickLaunchMonitoringNotification()
    }

    private fun runQuickLaunchForegroundProbe(reason: String) {
        UsageTracker.invalidateForegroundCache()
        val foregroundPackage = UsageTracker.getForegroundAppForQuickLaunchMonitor(this)
            ?: quickLaunchLastSeenPackage.ifBlank { _currentPackage.value }
        if (foregroundPackage.isBlank()) return
        applyForegroundPackageChange(foregroundPackage, reason)
    }

    /**
     * Event-driven counterpart to [runQuickLaunchForegroundProbe]: the foreground package is
     * supplied by [ForegroundAppAccessibilityService] instead of being polled from UsageStats.
     */
    private fun handleForegroundAppChanged(foregroundPackage: String) {
        if (foregroundPackage.isBlank()) return
        applyForegroundPackageChange(foregroundPackage, "a11y-event")
    }

    private fun applyForegroundPackageChange(foregroundPackage: String, reason: String) {
        val previousPackage = quickLaunchLastSeenPackage
        val packageChanged = foregroundPackage != previousPackage
        if (packageChanged) {
            Log.d(TAG, "foreground changed ($reason): $previousPackage -> $foregroundPackage")
            quickLaunchLastSeenPackage = foregroundPackage
            _currentPackage.value = foregroundPackage
        }
        serviceScope.launch {
            handleForegroundPackage(foregroundPackage, packageChanged, previousPackage)
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
        // Countdown finished after a committed timer — birds + notification only.
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
            val appLabel = getAppLabel(packageName)
            val initialDelayMs = SettingsManager
                .getNudgeInitialNotificationDelayMinutes(this@TimerService)
                .coerceAtLeast(0) * 60_000L
            val bubbleIntervalMs = SettingsManager
                .getNudgeBubbleIntervalSeconds(this@TimerService)
                .coerceAtLeast(1) * 1_000L
            logNudgeLoopStarted(packageName, initialDelayMs, bubbleIntervalMs)
            var state = NudgeLoopMutableState(
                lastUserActivityAtMs = UsageTracker.getLastUserActivityTimestampMs(
                    context = this@TimerService,
                    lookbackMs = USER_AWAY_SIGNAL_LOOKBACK_MS,
                    includeForegroundTransitions = false,
                ),
            )
            val nudgeStartedAtMs = System.currentTimeMillis()
            while (true) {
                if (!runOneNudgeLoopTick(state, nudgeStartedAtMs, initialDelayMs, bubbleIntervalMs, packageName, appLabel)) {
                    return@launch
                }
            }
        }
    }

    /** @return false when the nudge loop should stop. */
    private suspend fun runOneNudgeLoopTick(
        state: NudgeLoopMutableState,
        nudgeStartedAtMs: Long,
        initialDelayMs: Long,
        bubbleIntervalMs: Long,
        packageName: String,
        appLabel: String,
    ): Boolean {
        val nudgeTickMs = SettingsManager.getNudgeLoopTickMs(this@TimerService)
            .coerceAtLeast(1_000L)
        delay(nudgeTickMs)
        val now = System.currentTimeMillis()
        if (shouldStopNudgeLoop(now, nudgeStartedAtMs, MAX_NUDGE_LOOP_DURATION_MS)) {
            logAndStopNudgeTimeout()
            return false
        }
        if (tickAwayShield(state, now)) return true
        val catchUpDebtBefore = catchUpDebtMs
        val pace = resolveNudgeEscalationPace(
            nowMs = now,
            conversationGraceUntilMs = conversationGraceUntilMs,
            catchUpDebtMs = catchUpDebtMs,
        )
        val advance = computeNudgeEscalationTickAdvance(
            pace = pace,
            nudgeTickMs = nudgeTickMs,
            catchUpDebtMs = catchUpDebtMs,
        )
        catchUpDebtMs = advance.catchUpDebtMsAfter
        suppressPredatoryKarmaThisTick = shouldSuppressPredatoryKarmaForTick(
            pace = pace,
            catchUpDebtMsBefore = catchUpDebtBefore,
            catchUpDebtMsAfter = catchUpDebtMs,
        )
        if (pace == NudgeEscalationPace.ConversationGracePaused) {
            return true
        }
        tickNudgeEscalation(
            state,
            advance.stageAdvanceMs,
            advance.activeAdvanceMs,
            initialDelayMs,
            bubbleIntervalMs,
            packageName,
            appLabel,
        )
        return true
    }

    private class NudgeLoopMutableState(
        var lastUserActivityAtMs: Long?,
        var awaySignalUnavailableLogged: Boolean = false,
        var stage: NudgeStageLogic = NudgeStageLogic.WAITING_AFTER_NOTIFICATION,
        var stageElapsedMs: Long = 0L,
        var activeElapsedMs: Long = 0L,
        var bubbleCount: Int = 0,
        var predatoryPenaltyPending: Boolean = false,
    )

    private fun logNudgeLoopStarted(packageName: String, initialDelayMs: Long, bubbleIntervalMs: Long) {
        logWithSession(
            "Nudge schedule: notify now, wait ${initialDelayMs / 60000}m, " +
                "bubble every ${bubbleIntervalMs / 1000}s (no banner escalation)",
        )
        logSessionEvent(
            "Nudge loop started (initialDelayMs=$initialDelayMs, bubbleIntervalMs=$bubbleIntervalMs, " +
                "package=${packageName.ifBlank { "<none>" }})",
        )
    }

    private fun logAndStopNudgeTimeout() {
        logSessionEvent(
            "Nudge loop timed out after ${(MAX_NUDGE_LOOP_DURATION_MS / 60_000L)}m; stopping service",
        )
        logWithSession("Nudge session ended after a long overrun — returning to normal")
        stopTimer()
    }

    /** @return true when the loop should `continue` (user away). */
    private fun tickAwayShield(state: NudgeLoopMutableState, now: Long): Boolean {
        val detectedActivityAtMs = UsageTracker.getLastUserActivityTimestampMs(
            context = this,
            lookbackMs = USER_AWAY_SIGNAL_LOOKBACK_MS,
            includeForegroundTransitions = false,
        )
        state.lastUserActivityAtMs = mergeLastUserActivityAtMs(
            previous = state.lastUserActivityAtMs,
            detectedActivityAtMs = detectedActivityAtMs,
            tapActivityAtMs = lastAwayOverlayTapAtMs,
        )
        val away = inferAwayState(state.lastUserActivityAtMs, now)
        applyAwayShieldAction(
            decideAwayShieldAction(
                inference = away,
                shieldShownForEpisode = awayShieldShownForCurrentAwayEpisode,
                overlayActive = userAwayOverlayActive,
            ),
        )
        if (away.signalUnavailable && !state.awaySignalUnavailableLogged) {
            state.awaySignalUnavailableLogged = true
            logSessionEvent(
                "Away detection disabled: no USER_INTERACTION signal available on this device/interval",
            )
        }
        return away.isUserAway
    }

    private fun applyAwayShieldAction(shieldAction: AwayShieldAction) {
        when (shieldAction) {
            is AwayShieldAction.Show -> {
                awayShieldShownForCurrentAwayEpisode = true
                userAwayOverlayActive = true
                overlayManager.showAwayShield()
                logSessionEvent(
                    "User away inferred from inactivity (${shieldAction.inactivityMs / 1000}s); showing away shield",
                )
                logWithSession(
                    "User appears away (${shieldAction.inactivityMs / 1000}s idle) — pausing nudge escalation and overrun",
                )
            }
            AwayShieldAction.HideUnavailableSignal -> {
                userAwayOverlayActive = false
                overlayManager.dismissAwayShield()
                logSessionEvent("Away shield hidden: USER_INTERACTION signal unavailable")
                awayShieldShownForCurrentAwayEpisode = false
            }
            AwayShieldAction.HideActivityResumed -> {
                userAwayOverlayActive = false
                overlayManager.dismissAwayShield()
                logSessionEvent("User activity resumed; hiding away shield")
                awayShieldShownForCurrentAwayEpisode = false
            }
            AwayShieldAction.ClearEpisodeOnly -> {
                awayShieldShownForCurrentAwayEpisode = false
            }
            AwayShieldAction.None -> Unit
        }
    }

    private fun tickNudgeEscalation(
        state: NudgeLoopMutableState,
        stageAdvanceMs: Long,
        activeAdvanceMs: Long,
        initialDelayMs: Long,
        bubbleIntervalMs: Long,
        packageName: String,
        appLabel: String,
    ) {
        state.activeElapsedMs += activeAdvanceMs
        state.stageElapsedMs += stageAdvanceMs
        _timerState.value = TimerState.Expired(state.activeElapsedMs)
        when (
            val tick = tickNudgeStage(
                stage = state.stage,
                stageElapsedMs = state.stageElapsedMs,
                initialDelayMs = initialDelayMs,
                bubbleIntervalMs = bubbleIntervalMs,
                bubbleCount = state.bubbleCount,
                predatoryPenaltyPending = state.predatoryPenaltyPending,
            )
        ) {
            NudgeStageTickResult.NoOp -> Unit
            is NudgeStageTickResult.AdvanceToBubbles -> {
                state.stage = NudgeStageLogic.BUBBLES
                state.stageElapsedMs = tick.stageElapsedMs
                logSessionEvent("Nudge stage -> BUBBLES")
            }
            is NudgeStageTickResult.FireBubble ->
                fireNudgeBubble(state, tick, packageName, appLabel, state.activeElapsedMs)
        }
    }

    private fun fireNudgeBubble(
        state: NudgeLoopMutableState,
        tick: NudgeStageTickResult.FireBubble,
        packageName: String,
        appLabel: String,
        overrunMs: Long,
    ) {
        Log.d(
            TAG,
            "Bubble timer trigger: next=${tick.newBubbleCount} " +
                "stageElapsedMs=${state.stageElapsedMs} intervalMs=tick pkg=$packageName",
        )
        state.stageElapsedMs = tick.stageElapsedMs
        if (tick.applyPendingPredatoryPenalty && !suppressPredatoryKarmaThisTick) {
            serviceScope.launch {
                karmaManager.onNudgeIgnored(packageName)
            }
            logWithSession("Karma -1: predatory bird was ignored until the next bird ($appLabel)")
            logSessionEvent("Predatory bird penalty applied at nudge #${tick.newBubbleCount}")
        } else if (tick.applyPendingPredatoryPenalty && suppressPredatoryKarmaThisTick) {
            logSessionEvent(
                "Predatory bird penalty deferred (catch-up) at nudge #${tick.newBubbleCount}",
            )
        }
        state.bubbleCount = tick.newBubbleCount
        state.predatoryPenaltyPending = tick.predatoryPenaltyPendingAfter
        _nudgeCount.value = state.bubbleCount
        if (tick.isPredatory) {
            logWithSession(
                "Predatory bird #${state.bubbleCount} is hunting. " +
                    "Close before the next bird to avoid karma -1.",
            )
            logSessionEvent("Predatory bird shown at nudge #${state.bubbleCount}; penalty pending")
        }
        val canOverlayNow = overlayManager.canDrawOverlay()
        Log.d(TAG, "Bubble trigger dispatch: canOverlay=$canOverlayNow count=${state.bubbleCount}")
        if (canOverlayNow) {
            overlayManager.showBubble(nudgeCount = state.bubbleCount, isPredatory = tick.isPredatory)
            overlayManager.updateConversationMessage("", state.bubbleCount)
        } else {
            logSessionEvent("Bubble fallback notification suppressed (single notification mode)")
        }
        logWithSession(
            "${if (tick.isPredatory) "Predatory" else "Small"} bird nudge #${state.bubbleCount} " +
                "shown for $appLabel (overrun ${overrunMs / 1000}s)",
        )
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
        overlayManager.showConversationBanner(buildBannerPreviewLines())
        logSessionEvent("Bird tap opened banner (no conversation grace until reply field focused)")
    }

    private fun onBannerReplyFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            beginConversationGrace(source = "reply field focused")
        } else {
            expireConversationGrace()
        }
    }

    private fun onBannerReplySubmitted(replyText: String) {
        val payload = replyText.trim()
        if (payload.isBlank()) return
        markNotificationInteractionObserved("banner reply")
        logSessionEvent("User replied from banner (chars=${payload.length})")
        if (handlePendingExtensionConfirmationReply(payload, keepBannerVisible = true, source = "banner")) {
            return
        }
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

    private fun forceBackToTimer(reason: String, packageName: String = "") {
        logSessionEvent(
            "Force returning to timer screen (reason=$reason package=${packageName.ifBlank { "<none>" }})",
        )
        overlayManager.dismissAllNudges()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FORCE_TIMER, true)
            putExtra(MainActivity.EXTRA_FORCE_TIMER_REASON, reason)
            if (packageName.isNotBlank()) {
                putExtra(MainActivity.EXTRA_FORCE_TIMER_PACKAGE, packageName)
            }
        }
        startActivity(intent)
    }

    private fun engageExtendChat() {
        logSessionEvent("engageExtendChat ignored — extend gate uses in-app NegotiationScreen")
    }

    private fun stopTimer() {
        markNotificationInteractionObserved("timer stop")
        logSessionEvent("Stopping timer service workflow")
        val generationAtStop = sessionGeneration
        val pkgAtStop = _currentPackage.value
        timerJob?.cancel()
        timerEndAtMs = 0L
        timerSessionTotalMs = 0L
        nudgeJob?.cancel()
        quickLaunchMonitorJob?.cancel()
        val stateSnapshot = _timerState.value
        if (stateSnapshot !is TimerState.Idle) {
            _timerState.value = TimerState.Idle
            logSessionEvent("Timer state -> Idle (stopTimer sync)")
        }
        overlayManager.dismissAllNudges()
        overlayManager.dismissQuickLaunchFrame()
        serviceScope.launch { finishStopTimerCleanup(generationAtStop, pkgAtStop, stateSnapshot) }
    }

    private suspend fun finishStopTimerCleanup(
        generationAtStop: Int,
        pkg: String,
        state: TimerState,
    ) {
        applyStopTimerOutcome(pkg, state)
        if (generationAtStop != sessionGeneration) {
            logSessionEvent(
                "Timer service stop cleanup aborted — newer session gen=$sessionGeneration (was $generationAtStop)",
            )
            return
        }
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

    private suspend fun applyStopTimerOutcome(pkg: String, state: TimerState) {
        val appLabel = getAppLabel(pkg)
        val outcome = stopTimerOutcomeFromStateFlags(
            isIdle = state is TimerState.Idle,
            isCounting = state is TimerState.Counting,
            remainingMs = (state as? TimerState.Counting)?.remainingMs ?: 0L,
            totalMs = (state as? TimerState.Counting)?.totalMs ?: 0L,
            overrunMs = (state as? TimerState.Expired)?.overrunMs ?: 0L,
            graceWindowMs = KarmaManager.GRACE_WINDOW_MS,
        )
        applyClassifiedStopOutcome(pkg, appLabel, outcome)
    }

    private suspend fun applyClassifiedStopOutcome(
        pkg: String,
        appLabel: String,
        outcome: StopTimerOutcome,
    ) {
        when (outcome) {
            is StopTimerOutcome.ClosedOnTime -> {
                karmaManager.onClosedOnTime(pkg)
                logWithSession("App closed on time: $appLabel (karma +1)")
                saveResumableSessionOnStop(pkg, appLabel, outcome.remainingMs, outcome.totalMs)
            }
            is StopTimerOutcome.ClosedInGrace -> {
                karmaManager.onClosedInGraceWindow(pkg)
                logWithSession(
                    "App closed in grace window: $appLabel (overrun ${outcome.overrunMs / 1000}s)",
                )
            }
            is StopTimerOutcome.ClosedAfterOverrun -> {
                logWithSession(
                    "App closed after overrun: $appLabel (overrun ${outcome.overrunMs / 60000} min)",
                )
            }
            StopTimerOutcome.Idle -> Unit
        }
    }

    private fun saveResumableSessionOnStop(
        pkg: String,
        appLabel: String,
        remainingMs: Long,
        totalMs: Long,
    ) {
        val startedAtMs = _sessionStartedAtMs.value.takeIf { it > 0L }
            ?: (System.currentTimeMillis() - (totalMs - remainingMs).coerceAtLeast(0L))
        if (pkg.isEmpty()) return
        SettingsManager.saveLastSession(
            context = this,
            packageName = pkg,
            totalDurationMs = totalMs,
            startedAtMs = startedAtMs,
            suspendedAtMs = null,
        )
        val remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()
        logWithSession("Saved resumable session: $appLabel ($remainingMinutes min left)")
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
            persistScreenOffSessionState(state, pkg, appLabel, suspendedAtMs)
            _timerState.value = TimerState.Idle
            logSessionEvent("Timer state -> Idle (screen off suspend)")
            _sessionStartedAtMs.value = 0L
            _nudgeCount.value = 0
            softDeadlineAtMs = null
            hardDeadlineAtMs = null
            overlayManager.setDeadlineState(softDeadlineAtMs, hardDeadlineAtMs)
            SettingsManager.setTimerRunning(this@TimerService, false)

            endNudgeConversation()
            finishScreenOffSuspend(quickLaunchActive)
        }
    }

    private suspend fun persistScreenOffSessionState(
        state: TimerState,
        pkg: String,
        appLabel: String,
        suspendedAtMs: Long,
    ) {
        when (state) {
            is TimerState.Counting -> persistScreenOffCounting(state, pkg, appLabel, suspendedAtMs)
            is TimerState.Expired -> {
                karmaManager.onClosedInGraceWindow(pkg)
                logWithSession(
                    "Screen off during overrun: $appLabel — positive signal " +
                        "(overrun ${state.overrunMs / 1000}s)",
                )
            }
            is TimerState.Idle -> { }
        }
    }

    private fun persistScreenOffCounting(
        state: TimerState.Counting,
        pkg: String,
        appLabel: String,
        suspendedAtMs: Long,
    ) {
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
                "($remainingMinutes min remaining)",
        )
    }

    private fun finishScreenOffSuspend(quickLaunchActive: Boolean) {
        if (quickLaunchActive) {
            // Screen off should not end Quick Launch; keep session alive so unlock
            // doesn't steal focus back into the launcher.
            logSessionEvent("Screen off during Quick Launch — session preserved; monitoring paused")
            return
        }
        SettingsManager.clearQuickLaunchSession(this@TimerService)
        quickLaunchExitResumeByPackage.clear()
        _currentPackage.value = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        logSessionEvent("Timer service suspended and stopped")
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
        val lm = LmPlaygroundManager(ctx)
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
                lm.initialize()
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
                    NudgeMessage(locString(R.string.nudge_error_session_ended), isFromUser = false)
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
        nudgeMessages.add(NudgeMessage(payload, isFromUser = true))
        showConversationNotification(alertUser = false)
        logWithSession("You: $payload")
        handleNudgeReplyText(payload, keepBannerVisible = false)
    }

    private fun handleNudgeReplyText(replyText: String, keepBannerVisible: Boolean) {
        val manager = negotiationManager
        if (manager == null) {
            logSessionEvent("Nudge reply received but conversation manager is null")
            val fallback = locString(R.string.nudge_fallback_reflect)
            nudgeMessages.add(NudgeMessage(fallback, isFromUser = false))
            showConversationNotification(alertUser = false)
            overlayManager.updateConversationMessage(fallback, _nudgeCount.value)
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

                if (result.extensionMinutes > 0) {
                    handleExtension(result.extensionMinutes, keepBannerVisible)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling nudge reply", e)
                logSessionEvent("AI reply handling failed: ${e.javaClass.simpleName}")
                val fallback = locString(R.string.nudge_error_process_failed)
                nudgeMessages.add(NudgeMessage(fallback, isFromUser = false))
                showConversationNotification(alertUser = false)
                overlayManager.updateConversationMessage(fallback, _nudgeCount.value)
                if (keepBannerVisible) {
                    overlayManager.showConversationBanner(buildBannerPreviewLines())
                }
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
        return when (
            parseExtensionConfirmationReply(
                payload,
                confirmText = extendConfirmText(),
                declineText = extendDeclineText(),
            )
        ) {
            ExtensionConfirmationParse.NotADecision -> false
            ExtensionConfirmationParse.Confirm -> applyConfirmedExtension(
                payload, pendingMinutes, keepBannerVisible, source,
            )
            ExtensionConfirmationParse.Decline -> applyDeclinedExtension(
                payload, keepBannerVisible, source,
            )
        }
    }

    private fun applyConfirmedExtension(
        payload: String,
        pendingMinutes: Int,
        keepBannerVisible: Boolean,
        source: String,
    ): Boolean {
        nudgeMessages.add(NudgeMessage(payload, isFromUser = true))
        logSessionEvent("Pending extension decision via $source: \"$payload\"")
        val keepPendingBannerVisible = pendingExtensionKeepBannerVisible
        clearPendingExtensionConfirmation()
        if (extendTimer(pendingMinutes)) {
            logSessionEvent("Applying confirmed AI extension: +$pendingMinutes min")
            logWithSession("AI extension confirmed: **+$pendingMinutes min**")
            endNudgeConversation()
            return true
        }
        val blocked = locString(R.string.nudge_extension_blocked_deadline)
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

    private fun applyDeclinedExtension(
        payload: String,
        keepBannerVisible: Boolean,
        source: String,
    ): Boolean {
        nudgeMessages.add(NudgeMessage(payload, isFromUser = true))
        logSessionEvent("Pending extension decision via $source: \"$payload\"")
        val keepPendingBannerVisible = pendingExtensionKeepBannerVisible
        clearPendingExtensionConfirmation()
        clearConversationGrace(reason = "extension declined")
        val declinedMessage = locString(R.string.nudge_extension_declined)
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
        val formattedTime = projectedExpirationMs?.let {
            DateFormat.getTimeFormat(this).format(Date(it))
        }
        return formatExtensionConfirmationMessage(
            minutes,
            formattedTime,
            byMinutesFormat = locString(R.string.nudge_extension_confirm_by_minutes),
            atTimeFormat = locString(R.string.nudge_extension_confirm_at_time),
        )
    }

    private fun calculateProjectedExpirationTimeMs(extraMinutes: Int): Long? {
        val now = System.currentTimeMillis()
        val remainingMs = when (val state = _timerState.value) {
            is TimerState.Counting -> state.remainingMs
            is TimerState.Expired -> 0L
            is TimerState.Idle -> null
        }
        return projectExpirationTimeMs(
            nowMs = now,
            remainingMs = remainingMs,
            extraMinutes = extraMinutes,
            hardDeadlineAtMs = hardDeadlineAtMs,
        )
    }

    private fun clearPendingExtensionConfirmation() {
        pendingExtensionMinutes = null
        pendingExtensionKeepBannerVisible = true
    }

    private fun beginConversationGrace(source: String) {
        conversationGraceExpiryJob?.cancel()
        conversationGraceExpiryJob = null
        // Pause while the reply field stays focused; expire immediately on blur.
        conversationGraceUntilMs = Long.MAX_VALUE
        logSessionEvent(
            "Conversation grace active (source=$source, debtMs=$catchUpDebtMs)",
        )
    }

    private fun clearConversationGrace(reason: String) {
        conversationGraceExpiryJob?.cancel()
        conversationGraceExpiryJob = null
        if (conversationGraceUntilMs > 0L) {
            logSessionEvent("Conversation grace cleared ($reason)")
        }
        conversationGraceUntilMs = 0L
    }

    private fun expireConversationGrace() {
        if (conversationGraceUntilMs <= 0L) return
        clearConversationGrace(reason = "reply field unfocused")
        overlayManager.showTransientToast(locString(R.string.nudge_conversation_grace_expired))
        logWithSession("Conversation grace expired — birds catching up at 10×")
        logSessionEvent("Conversation grace expired; catch-up debtMs=$catchUpDebtMs")
    }

    private fun endNudgeConversation() {
        logSessionEvent("Ending nudge conversation and clearing overlays/notification")
        clearPendingExtensionConfirmation()
        clearConversationGrace(reason = "end nudge conversation")
        catchUpDebtMs = 0L
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
            "Posting conversation notification (alertUser=$alertUser, messages=${nudgeMessages.size})",
        )
        val notificationBuilder = NotificationCompat.Builder(this, MindfulHomeApp.NUDGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nudge_notification)
            .setContentIntent(buildConversationTapPendingIntent())
            .setStyle(buildConversationMessagingStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setSilent(!alertUser)
            .setAutoCancel(false)
            .setOngoing(false)
        attachConversationActions(notificationBuilder)
        getSystemService(NotificationManager::class.java)
            .notify(NUDGE_NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun buildConversationTapPendingIntent(): PendingIntent {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FORCE_TIMER, true)
            putExtra(MainActivity.EXTRA_FORCE_TIMER_REASON, MainActivity.FORCE_TIMER_REASON_EXPIRED)
        }
        return PendingIntent.getActivity(
            this, NUDGE_NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildConversationMessagingStyle(): NotificationCompat.MessagingStyle {
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson())
        for (msg in nudgeMessages) {
            val sender = if (msg.isFromUser) null else aiPerson()
            messagingStyle.addMessage(
                NotificationCompat.MessagingStyle.Message(msg.text, msg.timestamp, sender),
            )
        }
        return messagingStyle
    }

    private fun attachConversationActions(builder: NotificationCompat.Builder) {
        val replyAction = buildConversationReplyAction()
        if (conversationNotificationIncludesExtensionActions(pendingExtensionMinutes != null)) {
            builder.addAction(buildQuickConfirmExtensionAction())
            builder.addAction(buildQuickDeclineExtensionAction())
        }
        builder.addAction(replyAction)
    }

    private fun buildConversationReplyAction(): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(locString(R.string.notif_reply_hint))
            .build()
        val replyIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_HANDLE_REPLY
        }
        val replyPendingIntent = PendingIntent.getService(
            this, NUDGE_NOTIFICATION_ID, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_nudge_notification, locString(R.string.notif_reply), replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun buildQuickConfirmExtensionAction(): NotificationCompat.Action {
        val confirm = extendConfirmText()
        val intent = Intent(this, TimerService::class.java).apply {
            action = ACTION_HANDLE_REPLY
            putExtra(EXTRA_QUICK_REPLY_TEXT, confirm)
        }
        val pending = PendingIntent.getService(
            this, NUDGE_NOTIFICATION_ID + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_nudge_notification, confirm, pending,
        ).build()
    }

    private fun buildQuickDeclineExtensionAction(): NotificationCompat.Action {
        val decline = extendDeclineText()
        val intent = Intent(this, TimerService::class.java).apply {
            action = ACTION_HANDLE_REPLY
            putExtra(EXTRA_QUICK_REPLY_TEXT, decline)
        }
        val pending = PendingIntent.getService(
            this, NUDGE_NOTIFICATION_ID + 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_nudge_notification, decline, pending,
        ).build()
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
        if (nudgeMessages.isEmpty()) return listOf(locString(R.string.notif_new_message))
        return nudgeMessages.takeLast(3).map { message ->
            val sender = if (message.isFromUser) {
                locString(R.string.notif_sender_you)
            } else {
                locString(R.string.app_name)
            }
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
            .setContentTitle(locString(R.string.app_name))
            .setContentText(locString(R.string.notif_timer_remaining, minutes, seconds))
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
            .setContentTitle(locString(R.string.app_name))
            .setContentText(locString(R.string.notif_quick_launch_active))
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
            .setContentTitle(locString(R.string.app_name))
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
        val candidateLabel = if (candidatePackage.isNullOrBlank()) {
            null
        } else {
            quickLaunchExitCandidateLabel
                ?: quickLaunchDetectedLabel.takeIf { quickLaunchDetectedPackage == candidatePackage }
                ?: getAppLabel(candidatePackage)
        }
        return formatQuickLaunchMonitoringStatusText(
            candidatePackage = candidatePackage,
            candidateLabel = candidateLabel,
            deadlineMs = quickLaunchExitDeadlineMs,
            phaseMs = quickLaunchSemaphorePhaseMs,
            nowMs = System.currentTimeMillis(),
            detectedPackage = quickLaunchDetectedPackage,
            detectedStatus = quickLaunchDetectedStatus,
            defaultText = locString(R.string.notif_quick_launch_active),
            openingTimerNow = locString(R.string.notif_ql_opening_timer_now),
            openingTimerIn = locString(R.string.notif_ql_opening_timer_in),
            statusFormat = locString(R.string.notif_ql_status),
            phaseGreen = locString(R.string.notif_ql_phase_green),
            phaseYellow = locString(R.string.notif_ql_phase_yellow),
            phaseRed = locString(R.string.notif_ql_phase_red),
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun isHardDeadlineCloserThanSessionDeadline(nowMs: Long = System.currentTimeMillis()): Boolean {
        return hardDeadlineIsCloserThanSession(
            nowMs = nowMs,
            hardDeadlineAtMs = hardDeadlineAtMs,
            sessionDeadlineDistanceMs = currentSessionDeadlineDistanceMs(nowMs),
        )
    }

    private fun currentSessionDeadlineDistanceMs(nowMs: Long): Long? {
        return when (val state = _timerState.value) {
            is TimerState.Counting -> sessionDeadlineDistanceMs(
                remainingOrOverrunMs = state.remainingMs,
                idleElapsedMs = null,
            )
            is TimerState.Expired -> sessionDeadlineDistanceMs(
                remainingOrOverrunMs = state.overrunMs,
                idleElapsedMs = null,
            )
            is TimerState.Idle -> {
                val startedAt = _sessionStartedAtMs.value
                if (startedAt <= 0L) {
                    null
                } else {
                    sessionDeadlineDistanceMs(
                        remainingOrOverrunMs = null,
                        idleElapsedMs = nowMs - startedAt,
                    )
                }
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
        private const val USER_AWAY_SIGNAL_LOOKBACK_MS = 10 * 60_000L
        private const val MAX_NUDGE_LOOP_DURATION_MS = 30 * 60_000L
        /** Slow safety poll used while the accessibility service supplies switch events. */
        private const val EVENT_DRIVEN_SAFETY_POLL_MS = 30_000L
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
        const val ACTION_ENGAGE_EXTEND_CHAT = "com.mindfulhome.ACTION_ENGAGE_EXTEND_CHAT"
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

        /** Legacy no-op hook for extend chat (in-app NegotiationScreen owns that flow). */
        fun engageExtendChat(context: Context) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_ENGAGE_EXTEND_CHAT
            }
            context.startForegroundService(intent)
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
