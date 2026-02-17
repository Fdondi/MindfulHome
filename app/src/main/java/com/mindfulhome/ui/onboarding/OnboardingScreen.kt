package com.mindfulhome.ui.onboarding

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (step) {
            0 -> WelcomeStep(onNext = { step = 1 })
            1 -> PhilosophyStep(onNext = { step = 2 })
            2 -> DefaultHomeStep(onNext = { step = 3 })
            3 -> NotificationPermissionStep(onNext = { step = 4 })
            4 -> UsageAccessStep(
                onGrantUsageAccess = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                onNext = { step = 5 }
            )
            5 -> ModelStep(onNext = { onComplete() })
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

    // Re-check when returning from the chooser
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isDefault = isDefaultHome(context)
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
                // Open the home app chooser
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text("Set as default")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    OutlinedButton(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text(if (isDefault) "Continue" else "Skip for now")
    }
}

@Composable
private fun NotificationPermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current

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

    OutlinedButton(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text(if (hasPermission) "Continue" else "Skip for now")
    }
}

@Composable
private fun UsageAccessStep(
    onGrantUsageAccess: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(UsageTracker.hasUsageStatsPermission(context)) }

    // Re-check every time the user comes back from Settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = UsageTracker.hasUsageStatsPermission(context)
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

    OutlinedButton(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text(if (hasPermission) "Continue" else "Skip for now")
    }
}

@Composable
private fun ModelStep(onNext: () -> Unit) {
    Text(
        text = "AI Model (Optional)",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "For the best experience, download a Gemma3-1B model (557 MB) " +
                "from HuggingFace and place it in the app's models folder.\n\n" +
                "Without a model, MindfulHome will use scripted responses " +
                "that still create the same reflective friction.",
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
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
    }
    val resolveInfo = context.packageManager.resolveActivity(
        intent, PackageManager.MATCH_DEFAULT_ONLY
    )
    return resolveInfo?.activityInfo?.packageName == context.packageName
}
