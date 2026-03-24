package com.mindfulhome.ui.onboarding

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mindfulhome.service.UsageTracker
import com.mindfulhome.settings.SettingsManager
import kotlinx.coroutines.delay

private const val PREF_NAME = "mindfulhome"
private const val ONBOARDING_STEP_KEY = "onboarding_step"

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
    }
    var step by remember {
        mutableIntStateOf(prefs.getInt(ONBOARDING_STEP_KEY, 0).coerceIn(0, 6))
    }

    fun goToStep(nextStep: Int) {
        val clamped = nextStep.coerceIn(0, 6)
        step = clamped
        prefs.edit().putInt(ONBOARDING_STEP_KEY, clamped).apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (step) {
            0 -> WelcomeStep(onNext = { goToStep(1) })
            1 -> PhilosophyStep(onNext = { goToStep(2) })
            2 -> DefaultHomeStep(onNext = { goToStep(3) })
            3 -> NotificationPermissionStep(onNext = { goToStep(4) })
            4 -> UsageAccessStep(
                onGrantUsageAccess = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                onNext = { goToStep(5) }
            )
            5 -> OverlayPermissionStep(onNext = { goToStep(6) })
            6 -> ModelStep(onNext = { onComplete() })
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Icon(
        Icons.Default.Favorite,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Welcome to MindfulHome",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "A home launcher that helps you use your phone more intentionally.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text("Get Started")
    }
}

@Composable
private fun PhilosophyStep(onNext: () -> Unit) {
    Text(
        text = "How it works",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    val points = listOf(
        "Set a timer each time you unlock your phone",
        "Apps earn karma based on whether you stick to your timer",
        "Low-karma apps get hidden (but never blocked)",
        "Talk to the AI to access hidden apps -- it will ask, but always relent",
        "No app is ever closed or force-stopped by MindfulHome"
    )

    points.forEach { point ->
        Text(
            text = "- $point",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
    }

    Spacer(modifier = Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text("Makes sense")
    }
}

@Composable
private fun DefaultHomeStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(isDefaultHome(context)) }
    var showGrantedFallbackButton by remember { mutableStateOf(false) }

    // Launcher for the RoleManager request result
    val roleRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = isDefaultHome(context)
    }

    // Re-check when returning from the chooser / settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isDefault = isDefaultHome(context)
    }
    LaunchedEffect(isDefault) {
        if (isDefault) {
            showGrantedFallbackButton = false
            delay(300)
            onNext()
            delay(700)
            showGrantedFallbackButton = true
        } else {
            showGrantedFallbackButton = false
        }
    }

    Text(
        text = "Set as home launcher",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = if (isDefault) {
            "MindfulHome is your default launcher."
        } else {
            "MindfulHome needs to be your default home app to work. " +
                    "Tap the button below and select MindfulHome."
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    if (!isDefault) {
        Button(
            onClick = {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
                ) {
                    roleRequestLauncher.launch(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text("Set as default")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (!isDefault || showGrantedFallbackButton) {
        OutlinedButton(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text(if (isDefault) "Continue (if stuck)" else "Skip for now")
        }
    }
}

@Composable
private fun NotificationPermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var showGrantedFallbackButton by remember { mutableStateOf(false) }

    // On Android < 13, notification permission is granted at install -- skip this step
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        LaunchedEffect(Unit) { onNext() }
        return
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    // Re-check when returning
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            showGrantedFallbackButton = false
            delay(300)
            onNext()
            delay(700)
            showGrantedFallbackButton = true
        } else {
            showGrantedFallbackButton = false
        }
    }

    Text(
        text = "Allow notifications",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = if (hasPermission) {
            "Notifications enabled. You'll see your timer countdown and gentle nudges."
        } else {
            "MindfulHome uses notifications to show your timer countdown " +
                    "and send gentle nudges when time is up. No spam, promise."
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    if (!hasPermission) {
        Button(
            onClick = {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text("Allow notifications")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (!hasPermission || showGrantedFallbackButton) {
        OutlinedButton(
            onClick = {
                SettingsManager.setPermissionPromptSuppressed(
                    context,
                    SettingsManager.PermissionPrompt.NOTIFICATIONS,
                    !hasPermission,
                )
                onNext()
            },
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text(if (hasPermission) "Continue (if stuck)" else "Skip for now")
        }
    }
}

@Composable
private fun UsageAccessStep(
    onGrantUsageAccess: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(UsageTracker.hasUsageStatsPermission(context)) }
    var showGrantedFallbackButton by remember { mutableStateOf(false) }

    // Re-check every time the user comes back from Settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = UsageTracker.hasUsageStatsPermission(context)
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            showGrantedFallbackButton = false
            delay(300)
            onNext()
            delay(700)
            showGrantedFallbackButton = true
        } else {
            showGrantedFallbackButton = false
        }
    }

    Text(
        text = "Usage access",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = if (hasPermission) {
            "Usage access granted. Karma tracking will work."
        } else {
            "MindfulHome needs Usage Access to know which app is in the foreground " +
                    "when your timer expires. This is how karma tracking works."
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    if (!hasPermission) {
        Button(
            onClick = onGrantUsageAccess,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text("Grant Usage Access")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (!hasPermission || showGrantedFallbackButton) {
        OutlinedButton(
            onClick = {
                SettingsManager.setPermissionPromptSuppressed(
                    context,
                    SettingsManager.PermissionPrompt.USAGE_ACCESS,
                    !hasPermission,
                )
                onNext()
            },
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text(if (hasPermission) "Continue (if stuck)" else "Skip for now")
        }
    }
}

@Composable
private fun OverlayPermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showGrantedFallbackButton by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = Settings.canDrawOverlays(context)
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            showGrantedFallbackButton = false
            delay(300)
            onNext()
            delay(700)
            showGrantedFallbackButton = true
        } else {
            showGrantedFallbackButton = false
        }
    }

    Text(
        text = "Display over other apps",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = if (hasPermission) {
            "Overlay permission granted. Nudge reminders will appear over any app."
        } else {
            "MindfulHome can show a gentle reminder overlay when your session " +
                    "timer expires, even while you're inside another app. " +
                    "Without this, reminders will only appear as notifications " +
                    "(which Android may silence over time)."
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    if (!hasPermission) {
        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text("Grant overlay permission")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (!hasPermission || showGrantedFallbackButton) {
        OutlinedButton(
            onClick = {
                SettingsManager.setPermissionPromptSuppressed(
                    context,
                    SettingsManager.PermissionPrompt.OVERLAY,
                    !hasPermission,
                )
                onNext()
            },
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text(if (hasPermission) "Continue (if stuck)" else "Skip for now")
        }
    }
}

@Composable
private fun ModelStep(onNext: () -> Unit) {
    Text(
        text = "AI Model Options",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Private, free, offline use is possible with a local model, " +
                "such as a Gemma3-1B model (557 MB). Download it " +
                "from HuggingFace and place it in the app's models folder.\n" +
                "Be warned however that small models won't be as smart as you might expect.\n\n" +
                "For a solution more powerful and less taxing on your space and compute " +
                "we recommend signing in to use our AI service, powered by Gemini.\n\n" +
                "If neither is configured, a scripted fallback will be used.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text("Start using MindfulHome")
    }
}

private fun isDefaultHome(context: android.content.Context): Boolean {
    val roleManager = context.getSystemService(RoleManager::class.java)
    return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
}
