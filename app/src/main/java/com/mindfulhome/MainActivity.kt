package com.mindfulhome

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.exceptions.NoCredentialException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
import com.mindfulhome.ui.settings.SettingsScreen
import com.mindfulhome.ui.theme.MindfulHomeTheme
import com.mindfulhome.ui.timer.TimerScreen
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: AppRepository
    private lateinit var karmaManager: KarmaManager
    private var navController: NavHostController? = null

    // The last timer duration set by the user (persists across navigation)
    private var lastDurationMinutes by mutableStateOf(5)

    // Optional reason provided when starting the timer; consumed by the chat screen
    private var unlockReason by mutableStateOf("")
    private var pendingPrefillMinutes by mutableStateOf<Int?>(null)
    private var pendingPrefillReason by mutableStateOf<String?>(null)
    private var pendingPrefillToken by mutableStateOf(0L)
    private var permissionDialogShowing = false
    private var sessionHandle: SessionLogger.SessionHandle? = null
    private var backendAuthPreflightInProgress = false
    private var backendAuthPreflightLastAttemptMs = 0L

    companion object {
        const val EXTRA_FORCE_TIMER = "force_timer"
        const val EXTRA_FORCE_TIMER_REASON = "force_timer_reason"
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

        Log.d("MainActivity", "onCreate: shouldShowTimer=$shouldShowTimer intent.extras=${intent?.extras}")

        val app = application as MindfulHomeApp
        repository = AppRepository(app.database)
        karmaManager = KarmaManager(this, repository)
        lifecycleScope.launch {
            karmaManager.runDailyRecoveryIfDue()
        }
        PackageManagerHelper.precomputeInstalledApps(this)

        // Handle intent on cold launch (onNewIntent is only called for warm launches)
        handleIncomingIntent(intent)

        val prefs = getSharedPreferences("mindfulhome", Context.MODE_PRIVATE)
        val onboardingDone = prefs.getBoolean("onboarding_done", false)

        setContent {
            MindfulHomeTheme {
                val navCtrl = rememberNavController()
                navController = navCtrl
                val startDestination = remember {
                    val quickLaunchSessionActive =
                        SettingsManager.isQuickLaunchSessionActive(this@MainActivity)
                    val timerIsRunning = TimerService.timerState.value is TimerState.Counting
                    when {
                        !onboardingDone -> "onboarding"
                        shouldShowTimer -> "timer"
                        quickLaunchSessionActive -> postTimerTargetRoute()
                        timerIsRunning -> postTimerTargetRoute()
                        else -> "default"
                    }
                }

                NavHost(navController = navCtrl, startDestination = startDestination, route = "root") {

                    composable("onboarding") {
                        OnboardingScreen(
                            onComplete = {
                                prefs.edit()
                                    .putBoolean("onboarding_done", true)
                                    .remove("onboarding_step")
                                    .apply()
                                shouldShowTimer = false
                                navCtrl.navigate("default") {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("timer") {
                        TimerScreen(
                            onTimerSet = { durationMinutes, reason, hardDeadlineMinutes, mostUsedAppsToday, mostUsedAppsCapturedAtMs ->
                                Log.d(
                                    "MainActivity",
                                    "onTimerSet: duration=$durationMinutes reason='$reason' hardDeadlineMinutes=$hardDeadlineMinutes",
                                )
                                shouldShowTimer = false
                                lastDurationMinutes = durationMinutes
                                unlockReason = reason
                                val handle = ensureSessionHandle()
                                SettingsManager.saveLastDeclaredIntent(
                                    this@MainActivity,
                                    durationMinutes,
                                    reason,
                                )
                                if (!mostUsedAppsToday.isNullOrEmpty() && mostUsedAppsCapturedAtMs != null) {
                                    SettingsManager.saveLastTimerUsageSnapshot(
                                        context = this@MainActivity,
                                        capturedAtMs = mostUsedAppsCapturedAtMs,
                                        topApps = mostUsedAppsToday,
                                    )
                                } else {
                                    SettingsManager.clearLastTimerUsageSnapshot(this@MainActivity)
                                }
                                val normalizedReason = reason.ifBlank { "_(not provided)_" }
                                SessionLogger.log(
                                    handle,
                                    "Timer + intention set: **$durationMinutes min** - $normalizedReason",
                                )
                                TimerService.start(
                                    context = this@MainActivity,
                                    durationMinutes = durationMinutes,
                                    packageName = "",
                                    sessionHandle = handle,
                                    hardDeadlineMinutes = hardDeadlineMinutes,
                                )
                                Log.d("MainActivity", "TimerService.start called, navigating to home")
                                val targetRoute = postTimerTargetRoute()
                                navCtrl.navigate(targetRoute)
                                Log.d("MainActivity", "Navigation to $targetRoute completed")
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

                    composable("default") {
                        val savedSession = SettingsManager.getLastSession(this@MainActivity)
                        val savedAppLabel = savedSession?.let { session ->
                            try {
                                val appInfo = packageManager.getApplicationInfo(
                                    session.packageName, 0
                                )
                                packageManager.getApplicationLabel(appInfo).toString()
                            } catch (_: Exception) {
                                null
                            }
                        }
                        DefaultPageScreen(
                            repository = repository,
                            onQuickLaunchApp = { packageName, quickLaunchPackages ->
                                launchQuickStart(packageName, quickLaunchPackages)
                            },
                            resumeSessionLabel = savedAppLabel,
                            resumeSessionMinutes = savedSession?.remainingMinutes ?: 0,
                            onResumeSession = savedSession?.let { session ->
                                {
                                    shouldShowTimer = false
                                    lastDurationMinutes = session.remainingMinutes
                                    val handle = ensureSessionHandle()
                                    SettingsManager.saveLastDeclaredIntent(
                                        this@MainActivity,
                                        session.remainingMinutes,
                                        "",
                                    )
                                    SettingsManager.clearLastSession(this@MainActivity)
                                    SessionLogger.log(
                                        handle,
                                        "Resumed previous session: **${session.remainingMinutes} min**"
                                    )
                                    TimerService.startWithDurationMs(
                                        this@MainActivity,
                                        session.remainingMs,
                                        session.packageName,
                                        handle,
                                    )
                                    val launchIntent = packageManager
                                        .getLaunchIntentForPackage(session.packageName)
                                    if (launchIntent != null) {
                                        startActivity(launchIntent)
                                    }
                                }
                            },
                            onOpenTimerPlain = {
                                clearPendingPrefill()
                                SettingsManager.clearQuickLaunchSession(this@MainActivity)
                                shouldShowTimer = true
                                navCtrl.navigate("timer") {
                                    popUpTo("default") { inclusive = false }
                                }
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
                                navCtrl.navigate("timer") {
                                    popUpTo("default") { inclusive = false }
                                }
                            },
                            onScreenShown = {
                                maybePreflightBackendAuth()
                            },
                        )
                    }

                    composable("home") {
                        HomeScreen(
                            durationMinutes = lastDurationMinutes,
                            unlockReason = unlockReason,
                            sessionHandle = sessionHandle,
                            repository = repository,
                            karmaManager = karmaManager,
                            onRequestAi = { packageName ->
                                if (packageName.isBlank()) {
                                    navCtrl.navigate("assistant")
                                } else {
                                    navCtrl.navigate("negotiate/$packageName")
                                }
                            },
                            onTimerClick = {
                                shouldShowTimer = true
                                navCtrl.navigate("timer") {
                                    popUpTo("home") { inclusive = true }
                                }
                            },
                            onOpenDefault = {
                                shouldShowTimer = false
                                navCtrl.navigate("default") {
                                    popUpTo("root") { inclusive = true }
                                }
                            },
                            onOpenSettings = {
                                navCtrl.navigate("settings")
                            },
                            onOpenLogs = {
                                navCtrl.navigate("logs")
                            },
                            onOpenKarma = {
                                navCtrl.navigate("karma")
                            }
                        )
                    }

                    composable("negotiate/{packageName}") { backStackEntry ->
                        val packageName = backStackEntry.arguments
                            ?.getString("packageName") ?: ""

                        // Read (but don't clear) the unlock reason; it persists
                        // for the whole session so HomeScreen can still use it.
                        val reason = remember { unlockReason }

                        NegotiationScreen(
                            packageName = packageName,
                            unlockReason = reason,
                            durationMinutes = lastDurationMinutes,
                            sessionHandle = sessionHandle,
                            repository = repository,
                            karmaManager = karmaManager,
                            onTimerClick = {
                                shouldShowTimer = true
                                navCtrl.navigate("timer") {
                                    popUpTo("root") { inclusive = true }
                                }
                            },
                            onOpenDefault = {
                                shouldShowTimer = false
                                navCtrl.navigate("default") {
                                    popUpTo("root") { inclusive = true }
                                }
                            },
                            onOpenLogs = { navCtrl.navigate("logs") },
                            onOpenKarma = { navCtrl.navigate("karma") },
                            onOpenSettings = { navCtrl.navigate("settings") },
                            onAppGranted = {
                                navCtrl.popBackStack()
                            },
                            onDismiss = {
                                navCtrl.popBackStack()
                            }
                        )
                    }

                    composable("assistant") {
                        NegotiationScreen(
                            packageName = "",
                            unlockReason = unlockReason,
                            durationMinutes = lastDurationMinutes,
                            sessionHandle = sessionHandle,
                            repository = repository,
                            karmaManager = karmaManager,
                            onTimerClick = {
                                shouldShowTimer = true
                                navCtrl.navigate("timer") {
                                    popUpTo("root") { inclusive = true }
                                }
                            },
                            onOpenDefault = {
                                shouldShowTimer = false
                                navCtrl.navigate("default") {
                                    popUpTo("root") { inclusive = true }
                                }
                            },
                            onOpenLogs = { navCtrl.navigate("logs") },
                            onOpenKarma = { navCtrl.navigate("karma") },
                            onOpenSettings = { navCtrl.navigate("settings") },
                            onAppGranted = {
                                navCtrl.navigate("home") {
                                    popUpTo("root") { inclusive = true }
                                }
                            },
                            onDismiss = {
                                if (shouldShowAssistantAfterUnlock()) {
                                    shouldShowTimer = true
                                    navCtrl.navigate("timer") {
                                        popUpTo("root") { inclusive = true }
                                    }
                                } else {
                                    navCtrl.popBackStack()
                                }
                            }
                        )
                    }

                    composable("karma") {
                        KarmaScreen(
                            repository = repository,
                            karmaManager = karmaManager,
                            onBack = { navCtrl.popBackStack() }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            onBack = { navCtrl.popBackStack() }
                        )
                    }

                    composable("logs") {
                        LogsScreen(
                            onBack = { navCtrl.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val openPrefill = intent.getBooleanExtra(EXTRA_OPEN_TIMER_PREFILL, false)
        if (openPrefill) {
            val minutes = intent.getIntExtra(EXTRA_PREFILL_MINUTES, -1)
            pendingPrefillMinutes = minutes.takeIf { it in 1..120 }
            pendingPrefillReason = intent.getStringExtra(EXTRA_PREFILL_REASON).orEmpty()
            pendingPrefillToken = System.currentTimeMillis()
            shouldShowTimer = true
            wentToBackground = false
            lifecycleScope.launch {
                navController?.navigate("timer") {
                    popUpTo("root") { inclusive = true }
                }
            }
            return
        }

        val fromUnlock = intent.getBooleanExtra(
            com.mindfulhome.receiver.ScreenUnlockReceiver.EXTRA_FROM_UNLOCK, false
        )
        val forceTimer = intent.getBooleanExtra(EXTRA_FORCE_TIMER, false)
        val forceTimerReason = intent.getStringExtra(EXTRA_FORCE_TIMER_REASON)

        Log.d(
            "MainActivity",
            "handleIncomingIntent: fromUnlock=$fromUnlock forceTimer=$forceTimer " +
                "reason=$forceTimerReason navController=${navController != null}",
        )

        if (forceTimer && forceTimerReason == FORCE_TIMER_REASON_EXPIRED) {
            val timerState = TimerService.timerState.value
            if (timerState !is TimerState.Expired) {
                Log.w(
                    "MainActivity",
                    "Ignoring expired-timer open request because timer is not expired (state=$timerState)",
                )
                return
            }
        }

        if (fromUnlock || forceTimer) {
            val forceDestination = "default"
            if (forceTimer) {
                TimerService.stop(this)
            }
            // Clear wentToBackground so onResume doesn't also navigate
            wentToBackground = false
            shouldShowTimer = false
            if (fromUnlock) {
                sessionHandle = SessionLogger.startSession("Phone unlocked")
            } else if (forceTimer) {
                sessionHandle = SessionLogger.startSession("Session resumed from timer alert")
            }
            lifecycleScope.launch {
                val destination = if (forceTimer) forceDestination else "default"
                Log.d("MainActivity", "Navigating to $destination from handleIncomingIntent")
                navController?.navigate(destination) {
                    popUpTo("root") { inclusive = true }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop: timerState=${TimerService.timerState.value}")

        // Save the running session before the activity may be destroyed
        val timerState = TimerService.timerState.value
        val currentPkg = TimerService.currentPackage.value
        if (timerState is TimerState.Counting && currentPkg.isNotEmpty()) {
            val startedAtMs = TimerService.sessionStartedAtMs.value.takeIf { it > 0L }
                ?: (System.currentTimeMillis() - (timerState.totalMs - timerState.remainingMs).coerceAtLeast(0L))
            val totalDurationMs = timerState.totalMs.coerceAtLeast(1_000L)
            if (totalDurationMs >= 1_000L) {
                SettingsManager.saveLastSession(
                    context = this,
                    packageName = currentPkg,
                    totalDurationMs = totalDurationMs,
                    startedAtMs = startedAtMs,
                    suspendedAtMs = null,
                )
            }
        }

        // Opening system Settings (usage access, overlay, default launcher, etc.) stops this
        // activity. If we mark that as "went to background", onResume navigates to default and
        // pops the graph — which aborts onboarding and loses step state while onboarding_done
        // is still false. Only track background after onboarding is finished.
        val onboardingDone = getSharedPreferences("mindfulhome", Context.MODE_PRIVATE)
            .getBoolean("onboarding_done", false)
        if (onboardingDone) {
            wentToBackground = true
            backgroundTimestampMs = System.currentTimeMillis()
        }
        shouldShowTimer = false
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume: wentToBackground=$wentToBackground navController=${navController != null}")

        val quickLaunchSessionActive = SettingsManager.isQuickLaunchSessionActive(this)

        if (quickLaunchSessionActive && shouldShowTimer) {
            shouldShowTimer = false
        }

        if (wentToBackground) {
            wentToBackground = false

            val onboardingDoneNow = getSharedPreferences("mindfulhome", Context.MODE_PRIVATE)
                .getBoolean("onboarding_done", false)
            if (!onboardingDoneNow) {
                Log.d("MainActivity", "onResume: skipping post-background navigation (onboarding in progress)")
            } else {
                val awayMs = System.currentTimeMillis() - backgroundTimestampMs
                val timerWasRunning = TimerService.timerState.value is TimerState.Counting
                val quickReturnMs =
                    SettingsManager.getQuickReturnMinutes(this) * 60_000L
                Log.d("MainActivity", "onResume: awayMs=$awayMs timerWasRunning=$timerWasRunning quickReturnMs=$quickReturnMs")

                if (quickLaunchSessionActive) {
                    val destination = "default"
                    Log.d("MainActivity", "onResume: quick launch session active, navigating to $destination")
                    shouldShowTimer = false
                    lifecycleScope.launch {
                        navController?.navigate(destination) {
                            popUpTo("root") { inclusive = true }
                        }
                    }
                } else if (awayMs < quickReturnMs && timerWasRunning) {
                    shouldShowTimer = false
                    val destination = "home"
                    SessionLogger.log(
                        ensureSessionHandle(),
                        "Quick return (${awayMs / 1000}s) — back to $destination"
                    )
                    lifecycleScope.launch {
                        navController?.navigate(destination) {
                            popUpTo("root") { inclusive = true }
                        }
                    }
                } else {
                    val destination = "default"
                    Log.d("MainActivity", "onResume: navigating to $destination")
                    shouldShowTimer = false
                    lifecycleScope.launch {
                        navController?.navigate(destination) {
                            popUpTo("root") { inclusive = true }
                        }
                    }
                }
            }
        }
        maybePromptForMissingPermission()
    }

    private enum class MissingPermission {
        Notifications,
        UsageAccess,
        Overlay,
    }

    private fun maybePromptForMissingPermission() {
        if (permissionDialogShowing || isFinishing || isDestroyed) return

        val onboardingDone = getSharedPreferences("mindfulhome", Context.MODE_PRIVATE)
            .getBoolean("onboarding_done", false)
        if (!onboardingDone) return

        val hasNotifications = hasNotificationPermission()
        val hasUsageAccess = UsageTracker.hasUsageStatsPermission(this)
        val hasOverlay = Settings.canDrawOverlays(this)

        if (hasNotifications) {
            SettingsManager.setPermissionPromptSuppressed(
                this,
                SettingsManager.PermissionPrompt.NOTIFICATIONS,
                false,
            )
        }
        if (hasUsageAccess) {
            SettingsManager.setPermissionPromptSuppressed(
                this,
                SettingsManager.PermissionPrompt.USAGE_ACCESS,
                false,
            )
        }
        if (hasOverlay) {
            SettingsManager.setPermissionPromptSuppressed(
                this,
                SettingsManager.PermissionPrompt.OVERLAY,
                false,
            )
        }

        val missingPermission = when {
            !hasNotifications && !SettingsManager.isPermissionPromptSuppressed(
                this, SettingsManager.PermissionPrompt.NOTIFICATIONS
            ) -> MissingPermission.Notifications
            !hasUsageAccess && !SettingsManager.isPermissionPromptSuppressed(
                this, SettingsManager.PermissionPrompt.USAGE_ACCESS
            ) -> MissingPermission.UsageAccess
            !hasOverlay && !SettingsManager.isPermissionPromptSuppressed(
                this, SettingsManager.PermissionPrompt.OVERLAY
            ) -> MissingPermission.Overlay
            else -> null
        } ?: return

        showMissingPermissionDialog(missingPermission)
    }

    private fun showMissingPermissionDialog(missingPermission: MissingPermission) {
        permissionDialogShowing = true

        val (title, message, promptKey) = when (missingPermission) {
            MissingPermission.Notifications -> Triple(
                "Allow notifications",
                "MindfulHome needs notification permission to show timer and nudge alerts.",
                SettingsManager.PermissionPrompt.NOTIFICATIONS,
            )
            MissingPermission.UsageAccess -> Triple(
                "Grant Usage Access",
                "MindfulHome needs Usage Access to detect your foreground app for timer and karma tracking.",
                SettingsManager.PermissionPrompt.USAGE_ACCESS,
            )
            MissingPermission.Overlay -> Triple(
                "Allow overlay permission",
                "MindfulHome can show nudge overlays and chat heads over apps. Without it, only notifications are used.",
                SettingsManager.PermissionPrompt.OVERLAY,
            )
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("Grant") { dialog, _ ->
                permissionDialogShowing = false
                dialog.dismiss()
                when (missingPermission) {
                    MissingPermission.Notifications -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    MissingPermission.UsageAccess -> {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    MissingPermission.Overlay -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName"),
                            )
                        )
                    }
                }
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
    ) {
        shouldShowTimer = false
        unlockReason = ""
        val handle = ensureSessionHandle()
        val quickStartLabel = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
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
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
    }

    private fun postTimerTargetRoute(): String {
        return if (shouldShowAssistantAfterUnlock()) "assistant" else "home"
    }

    private fun shouldShowAssistantAfterUnlock(): Boolean {
        return SettingsManager.isFocusTimeActiveNow(this)
    }

    private fun maybePreflightBackendAuth() {
        if (backendAuthPreflightInProgress) return
        if (SettingsManager.getAIMode(this) != SettingsManager.AI_MODE_BACKEND) return
        if (ApiKeyManager.getAppToken(this) != null) return

        val now = System.currentTimeMillis()
        if (now - backendAuthPreflightLastAttemptMs < 15_000L) return
        backendAuthPreflightLastAttemptMs = now
        backendAuthPreflightInProgress = true

        lifecycleScope.launch {
            try {
                val backendAuth = BackendAuthHelper(
                    signIn = {
                        val result = AuthManager.signIn(this@MainActivity)
                        result?.idToken
                    },
                    getAppToken = { ApiKeyManager.getAppToken(this@MainActivity) },
                    saveAppToken = { token, expiresAtMs ->
                        ApiKeyManager.saveAppToken(this@MainActivity, token, expiresAtMs)
                    },
                    clearAppToken = { ApiKeyManager.clearAppToken(this@MainActivity) },
                    getGoogleIdToken = { ApiKeyManager.getGoogleIdToken(this@MainActivity) },
                )

                val signInResult = AuthManager.signIn(this@MainActivity)
                if (signInResult != null) {
                    if (signInResult.email != null) {
                        ApiKeyManager.saveSignedInEmail(this@MainActivity, signInResult.email)
                    }
                    val exchanged = backendAuth.exchangeGoogleToken(signInResult.idToken)
                    if (!exchanged) {
                        Toast.makeText(
                            this@MainActivity,
                            "Backend authentication failed. Open Settings to retry sign-in.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Google Sign-In failed. Open Settings to retry sign-in.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (_: NoCredentialException) {
                Toast.makeText(
                    this@MainActivity,
                    "No Google account available. Add one in Android settings.",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (_: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Remote auth failed. Open Settings to retry sign-in.",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                backendAuthPreflightInProgress = false
            }
        }
    }
}
