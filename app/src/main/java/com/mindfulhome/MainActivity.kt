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
import com.mindfulhome.service.TimerService
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

    // Tracks whether the activity went to the background (another app was visible).
    // Used to show the timer when the user returns from any app.
    private var wasInBackground = false

    companion object {
        var shouldShowTimer by mutableStateOf(true)
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
                        TimerScreen(
                            onTimerSet = { durationMinutes ->
                                shouldShowTimer = false
                                lastDurationMinutes = durationMinutes
                                TimerService.start(
                                    this@MainActivity, durationMinutes, ""
                                )
                                navCtrl.navigate("home") {
                                    popUpTo("timer") { inclusive = true }
                                }
                            }
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

                        NegotiationScreen(
                            packageName = packageName,
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
        wasInBackground = true
    }

    override fun onResume() {
        super.onResume()
        if (wasInBackground) {
            wasInBackground = false
            // Returning from another app — start a new session with a fresh timer
            shouldShowTimer = true
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
