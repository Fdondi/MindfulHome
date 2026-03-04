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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
import com.mindfulhome.ui.home.HomeScreen
import com.mindfulhome.ui.logs.LogsScreen
import com.mindfulhome.ui.negotiation.NegotiationScreen
import com.mindfulhome.ui.onboarding.OnboardingScreen
import com.mindfulhome.ui.karma.KarmaScreen
import com.mindfulhome.ui.settings.SettingsScreen
import com.mindfulhome.ui.theme.MindfulHomeTheme
import com.mindfulhome.ui.timer.TimerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: AppRepository
    private lateinit var karmaManager: KarmaManager
    private var navController: NavHostController? = null

    // The last timer duration set by the user (persists across navigation)
    private var lastDurationMinutes by mutableStateOf(5)

    // Optional reason provided when starting the timer; consumed by the chat screen
    private var unlockReason by mutableStateOf("")
    private var permissionDialogShowing = false
    private var sessionHandle: SessionLogger.SessionHandle? = null

    companion object {
        const val EXTRA_FORCE_TIMER = "force_timer"
        const val EXTRA_FORCE_TIMER_REASON = "force_timer_reason"
        const val FORCE_TIMER_REASON_EXPIRED = "expired_timer"
        const val FORCE_TIMER_REASON_QUICK_LAUNCH = "quick_launch_exit"

        var shouldShowTimer by mutableStateOf(true)

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

        // Handle intent on cold launch (onNewIntent is only called for warm launches)
        handleIncomingIntent(intent)

        val prefs = getSharedPreferences("mindfulhome", Context.MODE_PRIVATE)
        val onboardingDone = prefs.getBoolean("onboarding_done", false)

        setContent {
            MindfulHomeTheme {
                val navCtrl = rememberNavController()
                navController = navCtrl

                val startDestination = when {
                    !onboardingDone -> "onboarding"
                    shouldShowTimer -> "timer"
                    else -> "home"
                }

                NavHost(navController = navCtrl, startDestination = startDestination, route = "root") {

                    composable("onboarding") {
                        OnboardingScreen(
                            onComplete = {
                                prefs.edit().putBoolean("onboarding_done", true).apply()
                                shouldShowTimer = true
                                navCtrl.navigate("timer") {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("timer") {
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
                        TimerScreen(
                            onTimerSet = { durationMinutes, reason ->
                                Log.d("MainActivity", "onTimerSet: duration=$durationMinutes reason='$reason'")
                                shouldShowTimer = false
                                lastDurationMinutes = durationMinutes
                                unlockReason = reason
                                val handle = ensureSessionHandle()
                                SettingsManager.saveLastDeclaredIntent(
                                    this@MainActivity,
                                    durationMinutes,
                                    reason,
                                )
                                val normalizedReason = reason.ifBlank { "_(not provided)_" }
                                SessionLogger.log(
                                    handle,
                                    "Timer + intention set: **$durationMinutes min** - $normalizedReason",
                                )
                                TimerService.start(
                                    this@MainActivity, durationMinutes, "", handle
                                )
                                Log.d("MainActivity", "TimerService.start called, navigating to home")
                                navCtrl.navigate("home") {
                                    popUpTo("timer") { inclusive = true }
                                }
                                Log.d("MainActivity", "Navigation to home completed")
                            },
                            savedAppLabel = savedAppLabel,
                            savedMinutes = savedSession?.remainingMinutes ?: 0,
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
                                    navCtrl.navigate("home") {
                                        popUpTo("timer") { inclusive = true }
                                    }
                                    val launchIntent = packageManager
                                        .getLaunchIntentForPackage(session.packageName)
                                    if (launchIntent != null) {
                                        startActivity(launchIntent)
                                    }
                                }
                            },
                            repository = repository,
                            onShelfAppLaunch = {
                                durationMinutes,
                                reason,
                                packageName,
                                quickLaunchPackages,
                            ->
                                Log.d("MainActivity", "Shelf launch: pkg=$packageName duration=$durationMinutes")
                                shouldShowTimer = false
                                lastDurationMinutes = durationMinutes
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
                                    this@MainActivity,
                                    initialPackageName = packageName,
                                    allowedQuickLaunchPackages = quickLaunchPackages.toList(),
                                    sessionHandle = handle,
                                )
                                navCtrl.navigate("home") {
                                    popUpTo("timer") { inclusive = true }
                                }
                                val launchIntent = packageManager
                                    .getLaunchIntentForPackage(packageName)
                                if (launchIntent != null) {
                                    startActivity(launchIntent)
                                }
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
                                navCtrl.navigate("negotiate/$packageName")
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
                            sessionHandle = sessionHandle,
                            repository = repository,
                            karmaManager = karmaManager,
                            onAppGranted = {
                                navCtrl.popBackStack()
                            },
                            onDismiss = {
                                navCtrl.popBackStack()
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
            if (forceTimer) {
                TimerService.stop(this)
            }
            // Clear wentToBackground so onResume doesn't also navigate
            wentToBackground = false
            shouldShowTimer = true
            if (fromUnlock) {
                sessionHandle = SessionLogger.startSession("Phone unlocked")
            } else if (forceTimer) {
                sessionHandle = SessionLogger.startSession("Session resumed from timer alert")
            }
            lifecycleScope.launch {
                Log.d("MainActivity", "Navigating to timer from handleIncomingIntent")
                navController?.navigate("timer") {
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

        wentToBackground = true
        backgroundTimestampMs = System.currentTimeMillis()
        shouldShowTimer = true
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume: wentToBackground=$wentToBackground navController=${navController != null}")

        if (wentToBackground) {
            wentToBackground = false

            val awayMs = System.currentTimeMillis() - backgroundTimestampMs
            val timerWasRunning = TimerService.timerState.value !is TimerState.Idle
            val quickReturnMs =
                SettingsManager.getQuickReturnMinutes(this) * 60_000L

            Log.d("MainActivity", "onResume: awayMs=$awayMs timerWasRunning=$timerWasRunning quickReturnMs=$quickReturnMs")

            if (awayMs < quickReturnMs && timerWasRunning) {
                shouldShowTimer = false
                SessionLogger.log(
                    ensureSessionHandle(),
                    "Quick return (${awayMs / 1000}s) — back to app selection"
                )
                lifecycleScope.launch {
                    navController?.navigate("home") {
                        popUpTo("root") { inclusive = true }
                    }
                }
            } else {
                Log.d("MainActivity", "onResume: navigating to timer screen")
                shouldShowTimer = true
                lifecycleScope.launch {
                    navController?.navigate("timer") {
                        popUpTo("root") { inclusive = true }
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
}
