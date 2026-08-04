package com.mindfulhome.ui.onboarding

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mindfulhome.R
import com.mindfulhome.locale.AppLanguage
import com.mindfulhome.locale.LocaleHelper
import com.mindfulhome.service.ForegroundAppAccessibilityService
import com.mindfulhome.service.UsageTracker
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.ui.common.LanguagePickerStep
import kotlinx.coroutines.delay

private const val PREF_NAME = "mindfulhome"
private const val ONBOARDING_STEP_KEY = "onboarding_step"
private const val ONBOARDING_LAST_STEP = 9

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
    }
    var languageChosen by remember {
        mutableStateOf(SettingsManager.hasChosenAppLanguage(context))
    }
    var selectedLanguage by remember {
        mutableStateOf(
            if (SettingsManager.hasChosenAppLanguage(context)) {
                SettingsManager.getAppLanguage(context)
            } else {
                AppLanguage.SYSTEM
            }
        )
    }
    var step by remember {
        mutableIntStateOf(prefs.getInt(ONBOARDING_STEP_KEY, 0).coerceIn(0, ONBOARDING_LAST_STEP))
    }

    fun goToStep(nextStep: Int) {
        val clamped = nextStep.coerceIn(0, ONBOARDING_LAST_STEP)
        step = clamped
        prefs.edit().putInt(ONBOARDING_STEP_KEY, clamped).apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!languageChosen) {
            LanguagePickerStep(
                selected = selectedLanguage,
                onSelect = { selectedLanguage = it },
                onContinue = {
                    // Persist + apply only. Do not set languageChosen=true here — that would
                    // show Welcome with the old locale before AppCompat recreates the activity.
                    LocaleHelper.setLanguage(context, selectedLanguage)
                },
            )
        } else {
            OnboardingStepContent(
                step = step,
                onGoToStep = { goToStep(it) },
                onComplete = onComplete,
                onGrantUsageAccess = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
            )
        }
    }
}

@Composable
private fun OnboardingStepContent(
    step: Int,
    onGoToStep: (Int) -> Unit,
    onComplete: () -> Unit,
    onGrantUsageAccess: () -> Unit,
) {
    when (step) {
        0 -> WelcomeStep(onNext = { onGoToStep(1) })
        1 -> PhilosophyStep(onNext = { onGoToStep(2) })
        2 -> DefaultHomeStep(onNext = { onGoToStep(3) })
        else -> OnboardingPermissionSteps(
            step = step,
            onGoToStep = onGoToStep,
            onComplete = onComplete,
            onGrantUsageAccess = onGrantUsageAccess,
        )
    }
}

@Composable
private fun OnboardingPermissionSteps(
    step: Int,
    onGoToStep: (Int) -> Unit,
    onComplete: () -> Unit,
    onGrantUsageAccess: () -> Unit,
) {
    when (step) {
        3 -> NotificationPermissionStep(onNext = { onGoToStep(4) })
        4 -> UsageAccessStep(onGrantUsageAccess = onGrantUsageAccess, onNext = { onGoToStep(5) })
        5 -> OverlayPermissionStep(onNext = { onGoToStep(6) })
        6 -> AccessibilityPermissionStep(onNext = { onGoToStep(7) })
        7 -> ModelStep(onNext = { onGoToStep(8) })
        8 -> AppTiersStep(onNext = { onGoToStep(9) })
        else -> LayoutStep(onNext = onComplete)
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
        text = stringResource(R.string.welcome_to_mindfulhome),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.a_home_launcher_that_helps_you_use_your_phone_mo),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text(stringResource(R.string.get_started))
    }
}

@Composable
private fun PhilosophyStep(onNext: () -> Unit) {
    OnboardingBulletStep(
        title = stringResource(R.string.how_it_works),
        bulletArrayRes = R.array.onboarding_philosophy_bullets,
        buttonLabel = stringResource(R.string.makes_sense),
        onNext = onNext,
    )
}

@Composable
private fun AppTiersStep(onNext: () -> Unit) {
    OnboardingBulletStep(
        title = stringResource(R.string.onboarding_app_tiers_title),
        bulletArrayRes = R.array.onboarding_app_tiers_bullets,
        buttonLabel = stringResource(R.string.makes_sense),
        onNext = onNext,
    )
}

@Composable
private fun LayoutStep(onNext: () -> Unit) {
    OnboardingBulletStep(
        title = stringResource(R.string.onboarding_layout_title),
        bulletArrayRes = R.array.onboarding_layout_bullets,
        buttonLabel = stringResource(R.string.start_using_mindfulhome),
        onNext = onNext,
    )
}

@Composable
private fun OnboardingBulletStep(
    title: String,
    bulletArrayRes: Int,
    buttonLabel: String,
    onNext: () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(24.dp))

    stringArrayResource(bulletArrayRes).forEach { point ->
        Text(
            text = "- $point",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }

    Spacer(modifier = Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f),
    ) {
        Text(buttonLabel)
    }
}

@Composable
private fun DefaultHomeStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(isDefaultHome(context)) }
    var showGrantedFallbackButton by remember { mutableStateOf(false) }
    val roleRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        isDefault = isDefaultHome(context)
    }
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
    DefaultHomeStepBody(
        isDefault = isDefault,
        showGrantedFallbackButton = showGrantedFallbackButton,
        onRequestDefault = {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                roleRequestLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                )
            }
        },
        onNext = onNext,
    )
}

@Composable
private fun DefaultHomeStepBody(
    isDefault: Boolean,
    showGrantedFallbackButton: Boolean,
    onRequestDefault: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = stringResource(R.string.set_as_home_launcher),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    val continueIfStuck = stringResource(R.string.onboarding_continue_if_stuck)
    val skipForNow = stringResource(R.string.onboarding_skip_for_now)
    Text(
        text = if (isDefault) {
            stringResource(R.string.onboarding_default_launcher_is_default)
        } else {
            stringResource(R.string.onboarding_default_launcher_instructions)
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(32.dp))
    if (!isDefault) {
        Button(onClick = onRequestDefault, modifier = Modifier.fillMaxWidth(0.6f)) {
            Text(stringResource(R.string.set_as_default))
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
    if (!isDefault || showGrantedFallbackButton) {
        OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth(0.6f)) {
            Text(if (isDefault) continueIfStuck else skipForNow)
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

    NotificationPermissionStepBody(
        context = context,
        showGrantedFallbackButton = showGrantedFallbackButton,
        onShowGrantedFallbackButtonChange = { showGrantedFallbackButton = it },
        onNext = onNext,
    )
}

@Composable
private fun NotificationPermissionStepBody(
    context: android.content.Context,
    showGrantedFallbackButton: Boolean,
    onShowGrantedFallbackButtonChange: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
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
            onShowGrantedFallbackButtonChange(false)
            delay(300)
            onNext()
            delay(700)
            onShowGrantedFallbackButtonChange(true)
        } else {
            onShowGrantedFallbackButtonChange(false)
        }
    }

    Text(
        text = stringResource(R.string.allow_notifications),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = if (hasPermission) {
            stringResource(R.string.onboarding_notifications_granted)
        } else {
            stringResource(R.string.onboarding_notifications_rationale)
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    PermissionGrantAndSkipButtons(
        hasPermission = hasPermission,
        showGrantedFallbackButton = showGrantedFallbackButton,
        grantLabel = stringResource(R.string.allow_notifications),
        onGrant = {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onSkipOrContinue = {
            SettingsManager.setPermissionPromptSuppressed(
                context,
                SettingsManager.PermissionPrompt.NOTIFICATIONS,
                !hasPermission,
            )
            onNext()
        },
    )
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
        text = stringResource(R.string.usage_access_2),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = if (hasPermission) {
            stringResource(R.string.onboarding_usage_granted)
        } else {
            stringResource(R.string.onboarding_usage_rationale)
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    PermissionGrantAndSkipButtons(
        hasPermission = hasPermission,
        showGrantedFallbackButton = showGrantedFallbackButton,
        grantLabel = stringResource(R.string.onboarding_grant_usage_access),
        onGrant = onGrantUsageAccess,
        onSkipOrContinue = {
            SettingsManager.setPermissionPromptSuppressed(
                context,
                SettingsManager.PermissionPrompt.USAGE_ACCESS,
                !hasPermission,
            )
            onNext()
        },
    )
}

@Composable
private fun PermissionGrantAndSkipButtons(
    hasPermission: Boolean,
    showGrantedFallbackButton: Boolean,
    grantLabel: String,
    onGrant: () -> Unit,
    onSkipOrContinue: () -> Unit,
) {
    val continueIfStuck = stringResource(R.string.onboarding_continue_if_stuck)
    val skipForNow = stringResource(R.string.onboarding_skip_for_now)
    if (!hasPermission) {
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth(0.6f)) {
            Text(grantLabel)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
    if (!hasPermission || showGrantedFallbackButton) {
        OutlinedButton(onClick = onSkipOrContinue, modifier = Modifier.fillMaxWidth(0.6f)) {
            Text(if (hasPermission) continueIfStuck else skipForNow)
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
        text = stringResource(R.string.display_over_other_apps),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = if (hasPermission) {
            stringResource(R.string.onboarding_overlay_granted)
        } else {
            stringResource(R.string.onboarding_overlay_rationale)
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    PermissionGrantAndSkipButtons(
        hasPermission = hasPermission,
        showGrantedFallbackButton = showGrantedFallbackButton,
        grantLabel = stringResource(R.string.onboarding_grant_overlay_permission),
        onGrant = {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        },
        onSkipOrContinue = {
            SettingsManager.setPermissionPromptSuppressed(
                context,
                SettingsManager.PermissionPrompt.OVERLAY,
                !hasPermission,
            )
            onNext()
        },
    )
}

@Composable
private fun AccessibilityPermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ForegroundAppAccessibilityService.isEnabled(context))
    }
    var showGrantedFallbackButton by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = ForegroundAppAccessibilityService.isEnabled(context)
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
        text = stringResource(R.string.onboarding_accessibility_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = if (hasPermission) {
            stringResource(R.string.onboarding_accessibility_granted)
        } else {
            stringResource(R.string.onboarding_accessibility_rationale)
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(32.dp))

    PermissionGrantAndSkipButtons(
        hasPermission = hasPermission,
        showGrantedFallbackButton = showGrantedFallbackButton,
        grantLabel = stringResource(R.string.onboarding_grant_accessibility),
        onGrant = {
            try {
                context.startActivity(ForegroundAppAccessibilityService.settingsIntent())
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.couldn_t_open_accessibility_settings_on_this_dev),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onSkipOrContinue = onNext,
    )
}

@Composable
private fun ModelStep(onNext: () -> Unit) {
    Text(
        text = stringResource(R.string.ai_model_options),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.onboarding_ai_model_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(48.dp))

    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(0.6f)
    ) {
        Text(stringResource(R.string.language_picker_continue))
    }
}

private fun isDefaultHome(context: android.content.Context): Boolean {
    val roleManager = context.getSystemService(RoleManager::class.java)
    return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
}
