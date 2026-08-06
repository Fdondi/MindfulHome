package com.mindfulhome

import android.Manifest
import android.app.AlertDialog
import android.util.Log
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.exceptions.NoCredentialException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.AuthManager
import com.mindfulhome.ai.backend.BackendAuthHelper
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.model.TimerState
import com.mindfulhome.service.TimerService
import com.mindfulhome.service.UsageTracker
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.ui.defaultpage.DefaultPageScreen
import com.mindfulhome.ui.home.HomeScreen
import com.mindfulhome.ui.logs.LogsScreen
import com.mindfulhome.ui.negotiation.NegotiationScreen
import com.mindfulhome.ui.onboarding.OnboardingScreen
import com.mindfulhome.ui.karma.KarmaScreen
import com.mindfulhome.ui.settings.IntervalSettingsScreen
import com.mindfulhome.ui.settings.SettingsScreen
import com.mindfulhome.ui.theme.MindfulHomeTheme
import com.mindfulhome.ui.timer.TimerScreen
import com.mindfulhome.util.PackageManagerHelper
import com.mindfulhome.util.QuickLaunchAppRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repository: AppRepository
    private lateinit var karmaManager: KarmaManager
    private var navController: NavHostController? = null

    // The last timer duration set by the user (persists across navigation)
    private var lastDurationMinutes by mutableIntStateOf(5)

    // Optional reason provided when starting the timer; consumed by the chat screen
    private var unlockReason by mutableStateOf("")
    private var pendingPrefillMinutes by mutableStateOf<Int?>(null)
    private var pendingPrefillReason by mutableStateOf<String?>(null)
    private var pendingPrefillToken by mutableLongStateOf(0L)
    private var permissionDialogShowing = false
    private var sessionHandle: SessionLogger.SessionHandle? = null
    private var backendAuthPreflightInProgress = false
    private var backendAuthPreflightLastAttemptMs = 0L

    companion object {
        const val EXTRA_FORCE_TIMER = "force_timer"
        const val EXTRA_FORCE_TIMER_REASON = "force_timer_reason"
        const val EXTRA_FORCE_TIMER_PACKAGE = "force_timer_package"
        const val FORCE_TIMER_REASON_EXPIRED = "expired_timer"
        const val FORCE_TIMER_REASON_QUICK_LAUNCH = "quick_launch_exit"
        const val FORCE_TIMER_REASON_AWAY_RETURN = "away_return"
        const val EXTRA_OPEN_TIMER_PREFILL = "todo_open_timer_prefill"
        const val EXTRA_PREFILL_MINUTES = "todo_prefill_minutes"
        const val EXTRA_PREFILL_REASON = "todo_prefill_reason"

        var shouldShowTimer by mutableStateOf(false)

        // Survives activity recreation (lives in the companion, not the instance).
        // Set in onStop, cleared in onResume.
        var wentToBackground = false
        private var backgroundTimestampMs = 0L

        @JvmStatic
        internal fun postBackgroundDestination(
            quickLaunchSessionActive: Boolean,
            awayMs: Long,
            timerWasRunning: Boolean,
            quickReturnMs: Long,
        ): String? {
            if (quickLaunchSessionActive) return null
            return if (awayMs < quickReturnMs && timerWasRunning) "home" else "default"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            SettingsManager.setPermissionPromptSuppressed(
                this,
                SettingsManager.PermissionPrompt.NOTIFICATIONS,
                false,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        logInstallAndPermissionState()

        Log.d("MainActivity", "onCreate: shouldShowTimer=$shouldShowTimer intent.extras=${intent?.extras}")

        val app = application as MindfulHomeApp
        repository = AppRepository(app.database)
        karmaManager = KarmaManager(this, repository)
        lifecycleScope.launch {
            karmaManager.runDailyRecoveryIfDue()
            com.mindfulhome.bootstrap.SystemAppsBootstrap.runIfNeeded(this@MainActivity, karmaManager)
        }
        PackageManagerHelper.precomputeInstalledApps(this)

        // Handle intent on cold launch (onNewIntent is only called for warm launches)
        handleIncomingIntent(intent)

        val prefs = getSharedPreferences("mindfulhome", MODE_PRIVATE)
        val onboardingDone = prefs.getBoolean("onboarding_done", false)

        setContent {
            MindfulHomeTheme {
                val navCtrl = rememberNavController()
                navController = navCtrl
                val startDestination = remember {
                    val timerIsRunning = TimerService.timerState.value is TimerState.Counting
                    MainActivityLogic.resolveStartDestination(
                        onboardingDone = onboardingDone,
                        shouldShowTimer = shouldShowTimer,
                        timerIsRunning = timerIsRunning,
                        postTimerRoute = postTimerTargetRoute(),
                    )
                }
                MindfulNavHost(
                    navCtrl = navCtrl,
                    startDestination = startDestination,
                    prefs = prefs,
                )
            }
        }
    }

    @Composable
    private fun MindfulNavHost(
        navCtrl: NavHostController,
        startDestination: String,
        prefs: SharedPreferences,
    ) {
        NavHost(navController = navCtrl, startDestination = startDestination, route = "root") {
            composable("onboarding") { OnboardingRoute(navCtrl, prefs) }
            composable("timer") { TimerRoute(navCtrl) }
            composable("extend/{packageName}") { entry ->
                ExtendGateRoute(navCtrl, entry.arguments?.getString("packageName").orEmpty())
            }
            composable("default") { DefaultRoute(navCtrl) }
            composable("home") { HomeRoute(navCtrl) }
            composable("negotiate/{packageName}") { entry ->
                NegotiateRoute(navCtrl, entry.arguments?.getString("packageName").orEmpty())
            }
            composable("assistant") { AssistantRoute(navCtrl) }
            composable("karma") {
                KarmaScreen(
                    repository = repository,
                    karmaManager = karmaManager,
                    onBack = { navCtrl.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navCtrl.popBackStack() },
                    onOpenIntervalSettings = { navCtrl.navigate("settings_intervals") },
                )
            }
            composable("settings_intervals") {
                IntervalSettingsScreen(onBack = { navCtrl.popBackStack() })
            }
            composable("logs") {
                LogsScreen(onBack = { navCtrl.popBackStack() })
            }
        }
    }

    @Composable
    private fun OnboardingRoute(navCtrl: NavHostController, prefs: SharedPreferences) {
        OnboardingScreen(
            karmaManager = karmaManager,
            onComplete = {
                prefs.edit {
                    putBoolean("onboarding_done", true)
                    remove("onboarding_step")
                }
                shouldShowTimer = false
                navCtrl.navigate("default") {
                    popUpTo("onboarding") { inclusive = true }
                }
            },
        )
    }

    @Composable
    private fun TimerRoute(navCtrl: NavHostController) {
        // Timer is the only flow that needs backend auth (AI negotiation follows).
        LaunchedEffect(Unit) { maybePreflightBackendAuth() }
        TimerScreen(
            onTimerSet = { durationMinutes, reason, hardDeadlineMinutes, mostUsedAppsToday, mostUsedAppsCapturedAtMs ->
                applyTimerSet(
                    navCtrl = navCtrl,
                    durationMinutes = durationMinutes,
                    reason = reason,
                    hardDeadlineMinutes = hardDeadlineMinutes,
                    mostUsedAppsToday = mostUsedAppsToday,
                    mostUsedAppsCapturedAtMs = mostUsedAppsCapturedAtMs,
                )
            },
            onBackToDefault = {
                clearPendingPrefill()
                shouldShowTimer = false
                navCtrl.navigate("default") {
                    popUpTo("timer") { inclusive = true }
                    launchSingleTop = true
                }
            },
            initialMinutes = pendingPrefillMinutes,
            initialReason = pendingPrefillReason,
            prefillToken = pendingPrefillToken,
            onPrefillApplied = {
                pendingPrefillMinutes = null
                pendingPrefillReason = null
            },
        )
    }

    private fun applyTimerSet(
        navCtrl: NavHostController,
        durationMinutes: Int,
        reason: String,
        hardDeadlineMinutes: Int?,
        mostUsedAppsToday: List<com.mindfulhome.service.UsageTracker.DailyAppUsage>,
        mostUsedAppsCapturedAtMs: Long?,
    ) {
        Log.d(
            "MainActivity",
            "onTimerSet: duration=$durationMinutes reason='$reason' hardDeadlineMinutes=$hardDeadlineMinutes",
        )
        shouldShowTimer = false
        lastDurationMinutes = durationMinutes
        unlockReason = reason
        val handle = ensureSessionHandle()
        SettingsManager.saveLastDeclaredIntent(this, durationMinutes, reason)
        if (mostUsedAppsToday.isNotEmpty() && mostUsedAppsCapturedAtMs != null) {
            SettingsManager.saveLastTimerUsageSnapshot(
                context = this,
                capturedAtMs = mostUsedAppsCapturedAtMs,
                topApps = mostUsedAppsToday,
            )
        } else {
            SettingsManager.clearLastTimerUsageSnapshot(this)
        }
        val normalizedReason = reason.ifBlank { "_(not provided)_" }
        SessionLogger.log(
            handle,
            "Timer + intention set: **$durationMinutes min** - $normalizedReason",
        )
        TimerService.start(
            context = this,
            durationMinutes = durationMinutes,
            packageName = "",
            sessionHandle = handle,
            hardDeadlineMinutes = hardDeadlineMinutes,
        )
        val targetRoute = postTimerTargetRoute()
        Log.d("MainActivity", "TimerService.start called, navigating to $targetRoute")
        navCtrl.navigate(targetRoute)
        Log.d("MainActivity", "Navigation to $targetRoute completed")
    }

    private fun resolveAppLabelOrNull(pkg: String): String? {
        if (pkg.isBlank()) return null
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    @Composable
    private fun ExtendGateRoute(navCtrl: NavHostController, packageName: String) {
        NegotiationScreen(
            packageName = packageName,
            unlockReason = unlockReason,
            durationMinutes = lastDurationMinutes,
            sessionHandle = sessionHandle,
            repository = repository,
            karmaManager = karmaManager,
            extendGate = true,
            onTimerClick = { navigateToTimerFromRoot(navCtrl) },
            onOpenDefault = { navigateToDefaultFromRoot(navCtrl) },
            onOpenLogs = { navCtrl.navigate("logs") },
            onOpenKarma = { navCtrl.navigate("karma") },
            onOpenSettings = { navCtrl.navigate("settings") },
            onAppGranted = {
                shouldShowTimer = false
                wentToBackground = false
                PackageManagerHelper.launchApp(this@MainActivity, packageName)
            },
            onDismiss = { navigateToDefaultFromRoot(navCtrl) },
        )
    }

    @Composable
    private fun DefaultRoute(navCtrl: NavHostController) {
        val savedSession = SettingsManager.getLastSession(this@MainActivity)
        val savedAppLabel = savedSession?.let { resolveAppLabelOrNull(it.packageName) }
        DefaultPageScreen(
            repository = repository,
            onQuickLaunchApp = { packageName, quickLaunchPackages, limitMinutes ->
                launchQuickStart(packageName, quickLaunchPackages, limitMinutes)
            },
            resumeSessionLabel = savedAppLabel,
            resumeSessionMinutes = savedSession?.remainingMinutes ?: 0,
            onResumeSession = savedSession?.let { session ->
                { resumeSavedSession(session) }
            },
            onOpenTimerPlain = {
                clearPendingPrefill()
                SettingsManager.clearQuickLaunchSession(this@MainActivity)
                shouldShowTimer = true
                navCtrl.navigate("timer") { popUpTo("default") { inclusive = false } }
            },
            onOpenLogs = { navCtrl.navigate("logs") },
            onOpenKarma = { navCtrl.navigate("karma") },
            onOpenSettings = { navCtrl.navigate("settings") },
            onStartTodo = { minutes, intentText ->
                pendingPrefillMinutes = minutes
                pendingPrefillReason = intentText
                pendingPrefillToken = System.currentTimeMillis()
                SettingsManager.clearQuickLaunchSession(this@MainActivity)
                shouldShowTimer = true
                navCtrl.navigate("timer") { popUpTo("default") { inclusive = false } }
            },
            onScreenShown = { ensureQuickLaunchMonitoringAtHome() },
        )
    }

    private fun resumeSavedSession(session: SettingsManager.SavedSession) {
        shouldShowTimer = false
        lastDurationMinutes = session.remainingMinutes
        val handle = ensureSessionHandle()
        SettingsManager.saveLastDeclaredIntent(this, session.remainingMinutes, "")
        SettingsManager.clearLastSession(this)
        SessionLogger.log(
            handle,
            "Resumed previous session: **${session.remainingMinutes} min**",
        )
        TimerService.startWithDurationMs(
            this,
            session.remainingMs,
            session.packageName,
            handle,
        )
        packageManager.getLaunchIntentForPackage(session.packageName)?.let { startActivity(it) }
    }

    @Composable
    private fun HomeRoute(navCtrl: NavHostController) {
        HomeScreen(
            durationMinutes = lastDurationMinutes,
            unlockReason = unlockReason,
            sessionHandle = sessionHandle,
            repository = repository,
            karmaManager = karmaManager,
            onRequestAi = { packageName ->
                if (packageName.isBlank()) navCtrl.navigate("assistant")
                else navCtrl.navigate("negotiate/$packageName")
            },
            onTimerClick = {
                shouldShowTimer = true
                navCtrl.navigate("timer") { popUpTo("home") { inclusive = true } }
            },
            onOpenDefault = { navigateToDefaultFromRoot(navCtrl) },
            onOpenSettings = { navCtrl.navigate("settings") },
            onOpenLogs = { navCtrl.navigate("logs") },
            onOpenKarma = { navCtrl.navigate("karma") },
        )
    }

    @Composable
    private fun NegotiateRoute(navCtrl: NavHostController, packageName: String) {
        val reason = remember { unlockReason }
        NegotiationScreen(
            packageName = packageName,
            unlockReason = reason,
            durationMinutes = lastDurationMinutes,
            sessionHandle = sessionHandle,
            repository = repository,
            karmaManager = karmaManager,
            onTimerClick = { navigateToTimerFromRoot(navCtrl) },
            onOpenDefault = { navigateToDefaultFromRoot(navCtrl) },
            onOpenLogs = { navCtrl.navigate("logs") },
            onOpenKarma = { navCtrl.navigate("karma") },
            onOpenSettings = { navCtrl.navigate("settings") },
            onAppGranted = {
                shouldShowTimer = false
                wentToBackground = false
                PackageManagerHelper.launchApp(this@MainActivity, packageName)
            },
            onDismiss = { navCtrl.popBackStack() },
        )
    }

    @Composable
    private fun AssistantRoute(navCtrl: NavHostController) {
        NegotiationScreen(
            packageName = "",
            unlockReason = unlockReason,
            durationMinutes = lastDurationMinutes,
            sessionHandle = sessionHandle,
            repository = repository,
            karmaManager = karmaManager,
            onTimerClick = { navigateToTimerFromRoot(navCtrl) },
            onOpenDefault = { navigateToDefaultFromRoot(navCtrl) },
            onOpenLogs = { navCtrl.navigate("logs") },
            onOpenKarma = { navCtrl.navigate("karma") },
            onOpenSettings = { navCtrl.navigate("settings") },
            onAppGranted = {
                navCtrl.navigate("home") { popUpTo("root") { inclusive = true } }
            },
            onDismiss = {
                if (shouldShowAssistantAfterUnlock()) {
                    navigateToTimerFromRoot(navCtrl)
                } else {
                    navCtrl.popBackStack()
                }
            },
        )
    }

    private fun navigateToTimerFromRoot(navCtrl: NavHostController) {
        shouldShowTimer = true
        navCtrl.navigate("timer") { popUpTo("root") { inclusive = true } }
    }

    private fun navigateToDefaultFromRoot(navCtrl: NavHostController) {
        shouldShowTimer = false
        navCtrl.navigate("default") { popUpTo("root") { inclusive = true } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val openPrefill = intent.getBooleanExtra(EXTRA_OPEN_TIMER_PREFILL, false)
        val fromUnlock = intent.getBooleanExtra(
            com.mindfulhome.receiver.ScreenUnlockReceiver.EXTRA_FROM_UNLOCK, false
        )
        val forceTimer = intent.getBooleanExtra(EXTRA_FORCE_TIMER, false)
        val forceTimerReason = intent.getStringExtra(EXTRA_FORCE_TIMER_REASON)
        val isLauncherHome = MainActivityLogic.isLauncherHomeIntentFlags(
            actionIsMain = intent.action == Intent.ACTION_MAIN,
            hasHomeCategory = intent.hasCategory(Intent.CATEGORY_HOME),
            openPrefill = openPrefill,
            fromUnlock = fromUnlock,
            forceTimer = forceTimer,
        )
        Log.d(
            "MainActivity",
            "handleIncomingIntent: fromUnlock=$fromUnlock forceTimer=$forceTimer " +
                "reason=$forceTimerReason navController=${navController != null}",
        )
        applyIncomingIntentDecision(
            MainActivityLogic.decideIncomingIntent(
                openPrefill = openPrefill,
                prefillMinutes = intent.getIntExtra(EXTRA_PREFILL_MINUTES, -1),
                prefillReason = intent.getStringExtra(EXTRA_PREFILL_REASON),
                isLauncherHome = isLauncherHome,
                onboardingDone = getSharedPreferences("mindfulhome", MODE_PRIVATE)
                    .getBoolean("onboarding_done", false),
                fromUnlock = fromUnlock,
                forceTimer = forceTimer,
                forceTimerReason = forceTimerReason,
                timerIsExpired = TimerService.timerState.value is TimerState.Expired,
            ),
        )
    }

    private fun applyIncomingIntentDecision(decision: IncomingIntentDecision) {
        when (decision) {
            is IncomingIntentDecision.OpenTimerPrefill -> applyOpenTimerPrefill(decision)
            IncomingIntentDecision.NavigateDefaultFromLauncherHome -> applyNavigateDefaultFromLauncher()
            is IncomingIntentDecision.IgnoreExpiredForce -> logIgnoredExpiredForce(decision.reason)
            else -> applyPostExpireIntentDecision(decision)
        }
    }

    private fun logIgnoredExpiredForce(reason: String) {
        Log.w(
            "MainActivity",
            "Ignoring $reason open request because timer is not expired " +
                "(state=${TimerService.timerState.value})",
        )
    }

    private fun applyPostExpireIntentDecision(decision: IncomingIntentDecision) {
        when (decision) {
            is IncomingIntentDecision.NavigateUnlockOrForce -> applyNavigateUnlockOrForce(decision)
            else -> Unit
        }
    }

    private fun applyOpenTimerPrefill(decision: IncomingIntentDecision.OpenTimerPrefill) {
        pendingPrefillMinutes = decision.minutes
        pendingPrefillReason = decision.reason
        pendingPrefillToken = System.currentTimeMillis()
        shouldShowTimer = true
        wentToBackground = false
        lifecycleScope.launch {
            navController?.navigate("timer") {
                popUpTo("root") { inclusive = true }
            }
        }
    }

    private fun applyNavigateDefaultFromLauncher() {
        clearPendingPrefill()
        shouldShowTimer = false
        wentToBackground = false
        lifecycleScope.launch {
            navController?.navigate("default") {
                popUpTo("root") { inclusive = true }
            }
        }
    }

    private fun applyNavigateUnlockOrForce(decision: IncomingIntentDecision.NavigateUnlockOrForce) {
        if (decision.forceTimer) {
            TimerService.stop(this)
        }
        wentToBackground = false
        shouldShowTimer = false
        sessionHandle = if (decision.fromUnlock) {
            SessionLogger.startSession("Phone unlocked")
        } else {
            SessionLogger.startSession("Session resumed from timer alert")
        }
        lifecycleScope.launch {
            Log.d("MainActivity", "Navigating to ${decision.destination} from handleIncomingIntent")
            navController?.navigate(decision.destination) {
                popUpTo("root") { inclusive = true }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop: timerState=${TimerService.timerState.value}")
        maybeSaveRunningSessionOnStop()
        markBackgroundAfterOnboarding()
        shouldShowTimer = false
    }

    private fun maybeSaveRunningSessionOnStop() {
        val timerState = TimerService.timerState.value
        val currentPkg = TimerService.currentPackage.value
        if (timerState !is TimerState.Counting || currentPkg.isEmpty()) return
        val startedAtMs = TimerService.sessionStartedAtMs.value.takeIf { it > 0L }
            ?: (System.currentTimeMillis() - (timerState.totalMs - timerState.remainingMs).coerceAtLeast(0L))
        val totalDurationMs = timerState.totalMs.coerceAtLeast(1_000L)
        if (totalDurationMs < 1_000L) return
        SettingsManager.saveLastSession(
            context = this,
            packageName = currentPkg,
            totalDurationMs = totalDurationMs,
            startedAtMs = startedAtMs,
            suspendedAtMs = null,
        )
    }

    private fun markBackgroundAfterOnboarding() {
        val onboardingDone = getSharedPreferences("mindfulhome", MODE_PRIVATE)
            .getBoolean("onboarding_done", false)
        if (!onboardingDone) return
        wentToBackground = true
        backgroundTimestampMs = System.currentTimeMillis()
        if (SettingsManager.isQuickLaunchSessionActive(this)) {
            TimerService.probeQuickLaunchForeground(
                this,
                reason = "launcher-background",
                sessionHandle = ensureSessionHandle(),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume: wentToBackground=$wentToBackground navController=${navController != null}")
        val quickLaunchSessionActive = SettingsManager.isQuickLaunchSessionActive(this)
        if (MainActivityLogic.onResumeShouldClearTimerFlag(quickLaunchSessionActive, shouldShowTimer)) {
            shouldShowTimer = false
        }
        if (wentToBackground) {
            wentToBackground = false
            handlePostBackgroundResume(quickLaunchSessionActive)
        }
        maybePromptForMissingPermission()
    }

    private fun handlePostBackgroundResume(quickLaunchSessionActive: Boolean) {
        val onboardingDoneNow = getSharedPreferences("mindfulhome", MODE_PRIVATE)
            .getBoolean("onboarding_done", false)
        val awayMs = System.currentTimeMillis() - backgroundTimestampMs
        val destination = postBackgroundDestination(
            quickLaunchSessionActive = quickLaunchSessionActive,
            awayMs = awayMs,
            timerWasRunning = TimerService.timerState.value is TimerState.Counting,
            quickReturnMs = SettingsManager.getQuickReturnMinutes(this) * 60_000L,
        )
        when (val action = MainActivityLogic.decideOnResumeBackground(onboardingDoneNow, destination)) {
            MainActivityLogic.OnResumeBackgroundAction.SkipOnboarding ->
                Log.d("MainActivity", "onResume: skipping post-background navigation (onboarding in progress)")
            MainActivityLogic.OnResumeBackgroundAction.StayOnQuickLaunch -> {
                Log.d("MainActivity", "onResume: quick launch session active, leaving current app/screen unchanged")
                shouldShowTimer = false
            }
            is MainActivityLogic.OnResumeBackgroundAction.Navigate -> {
                shouldShowTimer = false
                if (action.logQuickReturn) {
                    SessionLogger.log(
                        ensureSessionHandle(),
                        "Quick return (${awayMs / 1000}s) — back to ${action.destination}",
                    )
                } else {
                    Log.d("MainActivity", "onResume: navigating to ${action.destination}")
                }
                lifecycleScope.launch {
                    navController?.navigate(action.destination) {
                        popUpTo("root") { inclusive = true }
                    }
                }
            }
        }
    }

    private fun maybePromptForMissingPermission() {
        val onboardingDone = getSharedPreferences("mindfulhome", MODE_PRIVATE)
            .getBoolean("onboarding_done", false)
        if (MainActivityLogic.shouldSkipPermissionPrompt(
                dialogShowing = permissionDialogShowing,
                finishing = isFinishing,
                destroyed = isDestroyed,
                onboardingDone = onboardingDone,
            )
        ) {
            return
        }
        val hasNotifications = hasNotificationPermission()
        val hasUsageAccess = UsageTracker.hasUsageStatsPermission(this)
        val hasOverlay = Settings.canDrawOverlays(this)
        clearGrantedPermissionSuppressions(hasNotifications, hasUsageAccess, hasOverlay)
        val missingPermission = MainActivityLogic.nextMissingPermission(
            hasNotifications = hasNotifications,
            hasUsageAccess = hasUsageAccess,
            hasOverlay = hasOverlay,
            notificationsSuppressed = SettingsManager.isPermissionPromptSuppressed(
                this, SettingsManager.PermissionPrompt.NOTIFICATIONS
            ),
            usageSuppressed = SettingsManager.isPermissionPromptSuppressed(
                this, SettingsManager.PermissionPrompt.USAGE_ACCESS
            ),
            overlaySuppressed = SettingsManager.isPermissionPromptSuppressed(
                this, SettingsManager.PermissionPrompt.OVERLAY
            ),
        ) ?: return
        showMissingPermissionDialog(missingPermission)
    }

    private fun clearGrantedPermissionSuppressions(
        hasNotifications: Boolean,
        hasUsageAccess: Boolean,
        hasOverlay: Boolean,
    ) {
        for (kind in MainActivityLogic.permissionSuppressionsToClear(
            hasNotifications, hasUsageAccess, hasOverlay,
        )) {
            val prompt = when (kind) {
                MissingPermissionKind.Notifications -> SettingsManager.PermissionPrompt.NOTIFICATIONS
                MissingPermissionKind.UsageAccess -> SettingsManager.PermissionPrompt.USAGE_ACCESS
                MissingPermissionKind.Overlay -> SettingsManager.PermissionPrompt.OVERLAY
            }
            SettingsManager.setPermissionPromptSuppressed(this, prompt, false)
        }
    }

    private fun showMissingPermissionDialog(missingPermission: MissingPermissionKind) {
        permissionDialogShowing = true
        val copy = MainActivityLogic.permissionDialogCopy(missingPermission)
        val promptKey = when (missingPermission) {
            MissingPermissionKind.Notifications -> SettingsManager.PermissionPrompt.NOTIFICATIONS
            MissingPermissionKind.UsageAccess -> SettingsManager.PermissionPrompt.USAGE_ACCESS
            MissingPermissionKind.Overlay -> SettingsManager.PermissionPrompt.OVERLAY
        }
        AlertDialog.Builder(this)
            .setTitle(copy.title)
            .setMessage(copy.message)
            .setCancelable(true)
            .setPositiveButton("Grant") { dialog, _ ->
                permissionDialogShowing = false
                dialog.dismiss()
                openPermissionSettings(missingPermission)
            }
            .setNegativeButton("Skip for now") { dialog, _ ->
                SettingsManager.setPermissionPromptSuppressed(this, promptKey, true)
                permissionDialogShowing = false
                dialog.dismiss()
            }
            .setOnCancelListener {
                permissionDialogShowing = false
            }
            .show()
    }

    private fun openPermissionSettings(missingPermission: MissingPermissionKind) {
        when (missingPermission) {
            MissingPermissionKind.Notifications -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            MissingPermissionKind.UsageAccess -> {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            MissingPermissionKind.Overlay -> {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri(),
                    ),
                )
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureSessionHandle(): SessionLogger.SessionHandle {
        val existing = sessionHandle ?: SessionLogger.getActiveSessionHandle()
        if (existing != null) {
            sessionHandle = existing
            return existing
        }
        val created = SessionLogger.startSession("Session resumed")
        sessionHandle = created
        return created
    }

    private fun clearPendingPrefill() {
        pendingPrefillMinutes = null
        pendingPrefillReason = null
        pendingPrefillToken = 0L
    }

    private fun launchQuickStart(
        packageName: String,
        quickLaunchPackages: Set<String>,
        limitMinutes: Int? = null,
    ) {
        if (limitMinutes != null) {
            launchTimedQuickStart(packageName, limitMinutes)
            return
        }
        shouldShowTimer = false
        unlockReason = ""
        val handle = ensureSessionHandle()
        val ownerPackage = QuickLaunchAppRef.ownerPackage(packageName)
        val quickStartLabel = try {
            val appInfo = packageManager.getApplicationInfo(ownerPackage, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
        SessionLogger.log(
            handle,
            "Quick Start launched: **$quickStartLabel** (`$packageName`) - no timer or intention set",
        )
        TimerService.startQuickLaunchSession(
            this,
            initialPackageName = packageName,
            allowedQuickLaunchPackages = quickLaunchPackages.toList(),
            sessionHandle = handle,
        )
        TimerService.trackApp(this, ownerPackage, handle)
        if (QuickLaunchAppRef.isShortcutKey(packageName)) {
            lifecycleScope.launch {
                val shortcut = repository.findPinnedShortcutByLaunchKey(packageName)
                PackageManagerHelper.launchApp(this@MainActivity, packageName, shortcut)
            }
        } else {
            PackageManagerHelper.launchApp(this, packageName)
        }
    }

    private fun launchTimedQuickStart(packageName: String, durationMinutes: Int) {
        shouldShowTimer = false
        unlockReason = ""
        val handle = ensureSessionHandle()
        val ownerPackage = QuickLaunchAppRef.ownerPackage(packageName)
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(ownerPackage, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
        SessionLogger.log(
            handle,
            "Timed Quick Start launched: **$appLabel** (`$packageName`) - **$durationMinutes min**",
        )
        TimerService.start(
            context = this,
            durationMinutes = durationMinutes,
            packageName = ownerPackage,
            sessionHandle = handle,
        )
        TimerService.trackApp(this, ownerPackage, handle)
        if (QuickLaunchAppRef.isShortcutKey(packageName)) {
            lifecycleScope.launch {
                val shortcut = repository.findPinnedShortcutByLaunchKey(packageName)
                PackageManagerHelper.launchApp(this@MainActivity, packageName, shortcut)
            }
        } else {
            PackageManagerHelper.launchApp(this, packageName)
        }
    }

    private fun postTimerTargetRoute(): String {
        return if (shouldShowAssistantAfterUnlock()) "assistant" else "home"
    }

    private fun shouldShowAssistantAfterUnlock(): Boolean {
        return SettingsManager.isFocusTimeActiveNow(this)
    }

    /**
     * Starts Quick Launch monitoring as soon as the launcher default page is shown, so usage
     * (e.g. recents → another app on unlock) is tracked even before tapping a Quick Launch tile.
     * Only when idle — never while Counting/Expired (Expired uses birds in-place).
     */
    private fun ensureQuickLaunchMonitoringAtHome() {
        if (TimerService.timerState.value !is TimerState.Idle) return
        lifecycleScope.launch {
            if (TimerService.timerState.value !is TimerState.Idle) return@launch
            val slots = repository.quickLaunchSlots().first()
            val allowed = slots.flatMap { it.flattenAllowedPackages() }.toSet()
            TimerService.startQuickLaunchSession(
                this@MainActivity,
                initialPackageName = packageName,
                allowedQuickLaunchPackages = allowed.toList(),
                sessionHandle = ensureSessionHandle(),
            )
        }
    }

    private fun maybePreflightBackendAuth() {
        val gate = MainActivityLogic.authPreflightGate(
            inProgress = backendAuthPreflightInProgress,
            isBackendMode = SettingsManager.getAIMode(this) == SettingsManager.AI_MODE_BACKEND,
            nowMs = System.currentTimeMillis(),
            lastAttemptMs = backendAuthPreflightLastAttemptMs,
        )
        when (gate) {
            is AuthPreflightGate.Skip -> return
            is AuthPreflightGate.Start -> {
                backendAuthPreflightLastAttemptMs = gate.updatedLastAttemptMs
                backendAuthPreflightInProgress = true
            }
        }
        lifecycleScope.launch {
            try {
                logAuthPreflightResult(runBackendAuthPreflight())
            } catch (_: Exception) {
                Log.d("MainActivity", "Backend preflight: silent sign-in failed, skipping")
            } finally {
                backendAuthPreflightInProgress = false
            }
        }
    }

    private suspend fun runBackendAuthPreflight(): AuthPreflightResult {
        val backendAuth = buildBackendAuthHelper()
        return MainActivityLogic.runAuthPreflight(
            hasSession = ApiKeyManager.getSessionToken(this@MainActivity) != null,
            sessionNearingExpiry = ApiKeyManager.isSessionExpiringSoon(this@MainActivity),
            signInSilent = {
                val signed = AuthManager.signInSilent(this@MainActivity)
                signed?.let { AuthPreflightSignIn(it.idToken, it.email) }
            },
            refreshIfNeeded = { backendAuth.refreshIfNeeded() },
            completeBackendSignIn = { token -> backendAuth.completeBackendSignIn(token) },
            saveSignedInEmail = { email ->
                ApiKeyManager.saveSignedInEmail(this@MainActivity, email)
            },
        )
    }

    private fun logAuthPreflightResult(result: AuthPreflightResult) {
        val message = MainActivityLogic.authPreflightLogMessage(result) ?: return
        if (MainActivityLogic.authPreflightLogIsWarning(result)) {
            Log.w("MainActivity", message)
        } else {
            Log.d("MainActivity", message)
        }
    }

    private fun buildBackendAuthHelper(): BackendAuthHelper = BackendAuthHelper(
        signInForExchange = {
            AuthManager.signInSilent(this@MainActivity)?.idToken
                ?: AuthManager.signIn(this@MainActivity)?.idToken
        },
        getSessionToken = { ApiKeyManager.getSessionToken(this@MainActivity) },
        saveSessionToken = { token, exp ->
            ApiKeyManager.saveSessionToken(this@MainActivity, token, exp)
        },
        clearSessionToken = { ApiKeyManager.clearSessionToken(this@MainActivity) },
        isSessionExpiringSoon = { ApiKeyManager.isSessionExpiringSoon(this@MainActivity) },
    )

    private fun logInstallAndPermissionState() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val installTime = packageInfo.firstInstallTime
            val updateTime = packageInfo.lastUpdateTime
            val installKind = if (installTime == updateTime) "fresh_install" else "update"
            Log.d(
                "MainActivity",
                "install_state kind=$installKind firstInstall=$installTime lastUpdate=$updateTime",
            )
        } catch (e: Exception) {
            Log.w("MainActivity", "install_state unavailable: ${e.message}")
        }

        Log.d(
            "MainActivity",
            "permission_state notifications=${hasNotificationPermission()} usage=${UsageTracker.hasUsageStatsPermission(this)} overlay=${Settings.canDrawOverlays(this)}",
        )
    }
}
