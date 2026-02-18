package com.mindfulhome

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.ui.home.HomeScreen
import com.mindfulhome.ui.logs.LogsScreen
import com.mindfulhome.ui.negotiation.NegotiationScreen
import com.mindfulhome.ui.onboarding.OnboardingScreen
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

    companion object {
        var shouldShowTimer by mutableStateOf(true)

        // Survives activity recreation (lives in the companion, not the instance).
        // Set in onStop, cleared in onResume.
        var wentToBackground = false
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Nothing to do -- we just needed the system dialog to show
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MindfulHomeApp
        repository = AppRepository(app.database)
        karmaManager = KarmaManager(repository)

        requestNotificationPermissionIfNeeded()

        // Ensure a session log exists (covers cold launch without unlock receiver)
        if (shouldShowTimer) {
            SessionLogger.startSession()
        }

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
                                shouldShowTimer = false
                                lastDurationMinutes = durationMinutes
                                unlockReason = reason
                                TimerService.start(
                                    this@MainActivity, durationMinutes, ""
                                )
                                navCtrl.navigate("home") {
                                    popUpTo("timer") { inclusive = true }
                                }
                            },
                            savedAppLabel = savedAppLabel,
                            savedMinutes = savedSession?.remainingMinutes ?: 0,
                            onResumeSession = savedSession?.let { session ->
                                {
                                    shouldShowTimer = false
                                    lastDurationMinutes = session.remainingMinutes
                                    SettingsManager.clearLastSession(this@MainActivity)
                                    TimerService.start(
                                        this@MainActivity,
                                        session.remainingMinutes,
                                        session.packageName,
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
                        )
                    }

                    composable("home") {
                        HomeScreen(
                            durationMinutes = lastDurationMinutes,
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
                            }
                        )
                    }

                    composable("negotiate/{packageName}") { backStackEntry ->
                        val packageName = backStackEntry.arguments
                            ?.getString("packageName") ?: ""

                        // Consume the unlock reason so it only fires once
                        val reason = remember {
                            val r = unlockReason
                            unlockReason = ""
                            r
                        }

                        NegotiationScreen(
                            packageName = packageName,
                            unlockReason = reason,
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
    }

    override fun onStop() {
        super.onStop()

        // Save the running session before the activity may be destroyed
        val timerState = TimerService.timerState.value
        val currentPkg = TimerService.currentPackage.value
        if (timerState is TimerState.Counting && currentPkg.isNotEmpty()) {
            val remainingMinutes = (timerState.remainingMs / 60_000).toInt()
            if (remainingMinutes >= 1) {
                SettingsManager.saveLastSession(this, currentPkg, remainingMinutes)
            }
        }

        wentToBackground = true
        shouldShowTimer = true
    }

    override fun onResume() {
        super.onResume()
        if (wentToBackground) {
            wentToBackground = false

            // Stop the running timer service (session was already saved in onStop)
            TimerService.stop(this)

            SessionLogger.startSession()
            lifecycleScope.launch {
                navController?.navigate("timer") {
                    popUpTo("root") { inclusive = true }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
