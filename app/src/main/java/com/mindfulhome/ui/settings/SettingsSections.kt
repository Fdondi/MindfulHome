package com.mindfulhome.ui.settings

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.NoCredentialException
import com.mindfulhome.ai.LiteRtLmManager
import com.mindfulhome.ai.PromptTemplates
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.AuthManager
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.ai.backend.BackendHttpException
import com.mindfulhome.logging.DailyLogSummaryGenerator
import com.mindfulhome.service.ForegroundAppAccessibilityService
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.ui.common.VersionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PermissionsSection(
    hasUsageStats: Boolean,
    skippedUsagePrompt: Boolean,
    onSkippedUsagePromptChange: (Boolean) -> Unit,
    hasNotificationPermission: Boolean,
    skippedNotificationPrompt: Boolean,
    onSkippedNotificationPromptChange: (Boolean) -> Unit,
    hasOverlayPermission: Boolean,
    skippedOverlayPrompt: Boolean,
    onSkippedOverlayPromptChange: (Boolean) -> Unit,
    accessibilityEnabled: Boolean,
) {
    val context = LocalContext.current
    SectionHeader("Permissions")

    val usage = permissionCardCopy(
        SettingsPermissionKind.UsageAccess,
        granted = hasUsageStats,
        skippedPrompt = skippedUsagePrompt,
    )
    SettingsCard(
        title = usage.title,
        description = usage.description,
        actionLabel = usage.actionLabel,
        onAction = {
            SettingsManager.setPermissionPromptSuppressed(
                context, SettingsManager.PermissionPrompt.USAGE_ACCESS, false
            )
            onSkippedUsagePromptChange(false)
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
    )

    Spacer(modifier = Modifier.height(8.dp))

    val notification = permissionCardCopy(
        SettingsPermissionKind.Notification,
        granted = hasNotificationPermission,
        skippedPrompt = skippedNotificationPrompt,
    )
    SettingsCard(
        title = notification.title,
        description = notification.description,
        actionLabel = notification.actionLabel,
        onAction = {
            SettingsManager.setPermissionPromptSuppressed(
                context, SettingsManager.PermissionPrompt.NOTIFICATIONS, false
            )
            onSkippedNotificationPromptChange(false)
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        },
    )

    Spacer(modifier = Modifier.height(8.dp))

    val overlay = permissionCardCopy(
        SettingsPermissionKind.Overlay,
        granted = hasOverlayPermission,
        skippedPrompt = skippedOverlayPrompt,
    )
    SettingsCard(
        title = overlay.title,
        description = overlay.description,
        actionLabel = overlay.actionLabel,
        onAction = {
            SettingsManager.setPermissionPromptSuppressed(
                context, SettingsManager.PermissionPrompt.OVERLAY, false
            )
            onSkippedOverlayPromptChange(false)
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        },
    )

    Spacer(modifier = Modifier.height(8.dp))

    val accessibility = permissionCardCopy(
        SettingsPermissionKind.Accessibility,
        granted = accessibilityEnabled,
    )
    SettingsCard(
        title = accessibility.title,
        description = accessibility.description,
        actionLabel = accessibility.actionLabel,
        onAction = {
            try {
                context.startActivity(ForegroundAppAccessibilityService.settingsIntent())
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "Couldn't open Accessibility settings on this device.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )
}

@Composable
internal fun BehaviorSection(onOpenIntervalSettings: () -> Unit) {
    val context = LocalContext.current
    SectionHeader("Behavior")

    var focusTimeEnabled by remember {
        mutableStateOf(SettingsManager.isFocusTimeEnabled(context))
    }
    var focusTimeIntervals by remember {
        mutableStateOf(SettingsManager.getFocusTimeIntervals(context))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Focus Time (AI-first)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "During active intervals, launcher stays hidden after timer. " +
                            "Use AI to open non-Quick Launch apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(
                    checked = focusTimeEnabled,
                    onCheckedChange = { enabled ->
                        focusTimeEnabled = enabled
                        SettingsManager.setFocusTimeEnabled(context, enabled)
                    },
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (focusTimeIntervals.isEmpty()) {
                Text(
                    text = "No intervals configured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                focusTimeIntervals.forEachIndexed { index, interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                showTimePicker(
                                    context = context,
                                    initialMinutes = interval.startMinutes,
                                ) { pickedStart ->
                                    val updated = focusTimeIntervals.toMutableList()
                                    updated[index] = interval.copy(startMinutes = pickedStart)
                                    focusTimeIntervals = updated
                                    SettingsManager.setFocusTimeIntervals(context, updated)
                                }
                            }) {
                                Text(formatMinutesOfDay(interval.startMinutes))
                            }
                            Text(
                                text = " - ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                showTimePicker(
                                    context = context,
                                    initialMinutes = interval.endMinutes,
                                ) { pickedEnd ->
                                    val updated = focusTimeIntervals.toMutableList()
                                    updated[index] = interval.copy(endMinutes = pickedEnd)
                                    focusTimeIntervals = updated
                                    SettingsManager.setFocusTimeIntervals(context, updated)
                                }
                            }) {
                                Text(formatMinutesOfDay(interval.endMinutes))
                            }
                        }
                        TextButton(onClick = {
                            val updated = focusTimeIntervals.toMutableList()
                            updated.removeAt(index)
                            focusTimeIntervals = updated
                            SettingsManager.setFocusTimeIntervals(context, updated)
                        }) {
                            Text("Remove")
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    val next = suggestedNewInterval(focusTimeIntervals)
                    val updated = focusTimeIntervals + next
                    focusTimeIntervals = updated
                    SettingsManager.setFocusTimeIntervals(context, updated)
                },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Add interval")
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    var focusGateMinRounds by remember {
        mutableFloatStateOf(SettingsManager.getFocusGateMinRounds(context).toFloat())
    }
    var focusGateMaxRounds by remember {
        mutableFloatStateOf(SettingsManager.getFocusGateMaxRounds(context).toFloat())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Focus Gate Length",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Back-and-forths in the focus time gate chat: minimum before " +
                    "Proceed can appear, maximum before access is granted automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Slider(
                    value = focusGateMinRounds,
                    onValueChange = { focusGateMinRounds = it },
                    onValueChangeFinished = {
                        SettingsManager.setFocusGateMinRounds(
                            context, focusGateMinRounds.toInt()
                        )
                        focusGateMinRounds =
                            SettingsManager.getFocusGateMinRounds(context).toFloat()
                        focusGateMaxRounds =
                            SettingsManager.getFocusGateMaxRounds(context).toFloat()
                    },
                    valueRange = SettingsManager.MIN_FOCUS_GATE_ROUNDS.toFloat()..
                        SettingsManager.MAX_FOCUS_GATE_ROUNDS.toFloat(),
                    steps = SettingsManager.MAX_FOCUS_GATE_ROUNDS -
                        SettingsManager.MIN_FOCUS_GATE_ROUNDS - 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "min ${focusGateMinRounds.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Slider(
                    value = focusGateMaxRounds,
                    onValueChange = { focusGateMaxRounds = it },
                    onValueChangeFinished = {
                        SettingsManager.setFocusGateMaxRounds(
                            context, focusGateMaxRounds.toInt()
                        )
                        focusGateMaxRounds =
                            SettingsManager.getFocusGateMaxRounds(context).toFloat()
                    },
                    valueRange = SettingsManager.MIN_FOCUS_GATE_ROUNDS.toFloat()..
                        SettingsManager.MAX_FOCUS_GATE_ROUNDS.toFloat(),
                    steps = SettingsManager.MAX_FOCUS_GATE_ROUNDS -
                        SettingsManager.MIN_FOCUS_GATE_ROUNDS - 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "max ${focusGateMaxRounds.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    var developerLogsEnabled by remember {
        mutableStateOf(SettingsManager.isDeveloperLogsEnabled(context))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Developer Logs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "When enabled, chat logs include tool calls, parameters, responses, and fallback/override reasons.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(
                    checked = developerLogsEnabled,
                    onCheckedChange = { enabled ->
                        developerLogsEnabled = enabled
                        SettingsManager.setDeveloperLogsEnabled(context, enabled)
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    var quickReturnMinutes by remember {
        mutableFloatStateOf(SettingsManager.getQuickReturnMinutes(context).toFloat())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Quick Return Window",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "If you come back within this window and a timer is " +
                    "still running, skip the timer screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Slider(
                    value = quickReturnMinutes,
                    onValueChange = { quickReturnMinutes = it },
                    onValueChangeFinished = {
                        SettingsManager.setQuickReturnMinutes(
                            context, quickReturnMinutes.toInt()
                        )
                    },
                    valueRange = SettingsManager.MIN_QUICK_RETURN_MINUTES.toFloat()..
                        SettingsManager.MAX_QUICK_RETURN_MINUTES.toFloat(),
                    steps = SettingsManager.MAX_QUICK_RETURN_MINUTES -
                        SettingsManager.MIN_QUICK_RETURN_MINUTES - 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${quickReturnMinutes.toInt()} min",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Timing & intervals",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Polling (Quick Launch, usage cache, nudge loop), timer notification refresh, " +
                    "and all nudge timing intervals. Larger steps save battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = onOpenIntervalSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("Open timing & intervals")
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    var bubblesBeforeBanner by remember {
        mutableFloatStateOf(SettingsManager.getNudgeBubblesBeforeBanner(context).toFloat())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Bubbles Before Banners",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "After this many bubbles, switch to full-width banners.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Slider(
                    value = bubblesBeforeBanner,
                    onValueChange = { bubblesBeforeBanner = it },
                    onValueChangeFinished = {
                        SettingsManager.setNudgeBubblesBeforeBanner(
                            context, bubblesBeforeBanner.toInt()
                        )
                    },
                    valueRange = SettingsManager.MIN_NUDGE_BUBBLES_BEFORE_BANNER.toFloat()..
                        SettingsManager.MAX_NUDGE_BUBBLES_BEFORE_BANNER.toFloat(),
                    steps = SettingsManager.MAX_NUDGE_BUBBLES_BEFORE_BANNER -
                        SettingsManager.MIN_NUDGE_BUBBLES_BEFORE_BANNER,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${bubblesBeforeBanner.toInt()} bubbles",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    var hideThreshold by remember {
        mutableFloatStateOf(SettingsManager.getHideThreshold(context).toFloat())
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Strikes Before Hiding",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "How many bad-karma points an app accumulates before " +
                    "it is hidden from the home screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Slider(
                    value = hideThreshold,
                    onValueChange = { hideThreshold = it },
                    onValueChangeFinished = {
                        SettingsManager.setHideThreshold(context, hideThreshold.toInt())
                    },
                    valueRange = SettingsManager.MIN_HIDE_THRESHOLD.toFloat()..
                        SettingsManager.MAX_HIDE_THRESHOLD.toFloat(),
                    steps = SettingsManager.MAX_HIDE_THRESHOLD -
                        SettingsManager.MIN_HIDE_THRESHOLD - 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${hideThreshold.toInt()} strikes",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
internal fun AiModelSection(
    aiMode: String,
    onAiModeChange: (String) -> Unit,
    hasModel: Boolean,
    isSignedIn: Boolean,
    signedInEmail: String?,
    onSignedInChange: (Boolean, String?) -> Unit,
    backendModel: String,
    onBackendModelChange: (String) -> Unit,
    availableModels: List<BackendClient.ModelInfo>,
) {
    val context = LocalContext.current
    var signInInProgress by remember { mutableStateOf(false) }

    SectionHeader("AI Model")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Model Source",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Choose where AI processing runs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = aiMode == SettingsManager.AI_MODE_ON_DEVICE,
                    onClick = {
                        onAiModeChange(SettingsManager.AI_MODE_ON_DEVICE)
                        SettingsManager.setAIMode(context, SettingsManager.AI_MODE_ON_DEVICE)
                    },
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "On-device (LiteRT-LM)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Private, works offline. Requires downloading a model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                RadioButton(
                    selected = aiMode == SettingsManager.AI_MODE_BACKEND,
                    onClick = {
                        onAiModeChange(SettingsManager.AI_MODE_BACKEND)
                        SettingsManager.setAIMode(context, SettingsManager.AI_MODE_BACKEND)
                    },
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Remote (Gemini)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "More capable. Requires Google sign-in and internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    if (aiMode == SettingsManager.AI_MODE_ON_DEVICE) {
        OnDeviceModelCard(hasModel = hasModel)
    } else {
        BackendAccountCard(
            isSignedIn = isSignedIn,
            signedInEmail = signedInEmail,
            onSignedInChange = onSignedInChange,
            backendModel = backendModel,
            onBackendModelChange = onBackendModelChange,
            availableModels = availableModels,
            signInInProgress = signInInProgress,
            onSignInInProgressChange = { signInInProgress = it },
        )
    }
}

@Composable
private fun OnDeviceModelCard(hasModel: Boolean) {
    val context = LocalContext.current
    val sharedDir = LiteRtLmManager.SHARED_MODEL_DIR
    SettingsCard(
        title = "LiteRT-LM Model",
        description = onDeviceModelDescription(hasModel, sharedDir.absolutePath),
        actionLabel = if (hasModel) null else "Copy adb Command",
        onAction = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val cmd = "adb push model.litertlm ${sharedDir.absolutePath}/"
            clipboard.setPrimaryClip(ClipData.newPlainText("adb push command", cmd))
            Toast.makeText(context, "adb command copied to clipboard", Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
private fun BackendAccountCard(
    isSignedIn: Boolean,
    signedInEmail: String?,
    onSignedInChange: (Boolean, String?) -> Unit,
    backendModel: String,
    onBackendModelChange: (String) -> Unit,
    availableModels: List<BackendClient.ModelInfo>,
    signInInProgress: Boolean,
    onSignInInProgressChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackendSignInCard(
        isSignedIn = isSignedIn,
        signedInEmail = signedInEmail,
        onSignedInChange = onSignedInChange,
        signInInProgress = signInInProgress,
        onSignInInProgressChange = onSignInInProgressChange,
        context = context,
        coroutineScope = coroutineScope,
    )
    Spacer(modifier = Modifier.height(12.dp))
    BackendModelPickerCard(
        backendModel = backendModel,
        onBackendModelChange = onBackendModelChange,
        availableModels = availableModels,
        context = context,
    )
}

@Composable
private fun BackendSignInCard(
    isSignedIn: Boolean,
    signedInEmail: String?,
    onSignedInChange: (Boolean, String?) -> Unit,
    signInInProgress: Boolean,
    onSignInInProgressChange: (Boolean) -> Unit,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Google Account",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isSignedIn) {
                BackendSignedInBody(
                    signedInEmail = signedInEmail,
                    onSignOut = {
                        coroutineScope.launch {
                            ApiKeyManager.signOut(context)
                            onSignedInChange(false, null)
                        }
                    },
                )
            } else {
                BackendSignedOutBody(
                    signInInProgress = signInInProgress,
                    onSignInClick = {
                        onSignInInProgressChange(true)
                        coroutineScope.launch {
                            runBackendSignIn(
                                context = context,
                                onSignedInChange = onSignedInChange,
                                onDone = { onSignInInProgressChange(false) },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BackendSignedInBody(
    signedInEmail: String?,
    onSignOut: () -> Unit,
) {
    Text(
        text = signedInEmail ?: "Signed in with Google",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Remote AI is active. Conversations are processed via the Gemini backend.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onSignOut) { Text("Sign out") }
}

@Composable
private fun BackendSignedOutBody(
    signInInProgress: Boolean,
    onSignInClick: () -> Unit,
) {
    Text(
        text = "Not signed in",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Sign in with your Google account to use the remote Gemini model. " +
            "Without signing in, the app will fall back to on-device responses.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Button(onClick = onSignInClick, enabled = !signInInProgress) {
        Text(if (signInInProgress) "Signing in..." else "Sign in with Google")
    }
}

@Composable
private fun BackendModelPickerCard(
    backendModel: String,
    onBackendModelChange: (String) -> Unit,
    availableModels: List<BackendClient.ModelInfo>,
    context: Context,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "AI Model",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Choose which Gemini model to use",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            availableModels.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    RadioButton(
                        selected = backendModel == option.id,
                        onClick = {
                            onBackendModelChange(option.id)
                            SettingsManager.setBackendModel(context, option.id)
                        },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun runBackendSignIn(
    context: Context,
    onSignedInChange: (Boolean, String?) -> Unit,
    onDone: () -> Unit,
) {
    try {
        val result = AuthManager.signIn(context)
        if (result == null) {
            Toast.makeText(context, "Google Sign-In was cancelled or failed", Toast.LENGTH_LONG).show()
            onDone()
            return
        }
        completeBackendSignInExchange(context, result, onSignedInChange)
    } catch (_: NoCredentialException) {
        Toast.makeText(context, "No Google account found. Opening account setup\u2026", Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
            },
        )
    }
    onDone()
}

private suspend fun completeBackendSignInExchange(
    context: Context,
    result: AuthManager.SignInResult,
    onSignedInChange: (Boolean, String?) -> Unit,
) {
    if (result.email != null) {
        ApiKeyManager.saveSignedInEmail(context, result.email)
    }
    try {
        val session = withContext(Dispatchers.IO) {
            BackendClient.exchange(result.idToken)
        }
        ApiKeyManager.saveSessionToken(context, session.session_token, session.expires_at)
        onSignedInChange(true, result.email)
    } catch (e: BackendHttpException) {
        Toast.makeText(context, backendSignInErrorMessage(e), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Backend sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun GatePromptsSection(
    gatekeeperPromptsCustom: Boolean,
    gatekeeperSystemPrompt: String,
    onGatekeeperSystemPromptChange: (String) -> Unit,
    gatekeeperContextTemplate: String,
    onGatekeeperContextTemplateChange: (String) -> Unit,
    onGatekeeperPromptsCustomChange: (Boolean) -> Unit,
    focusGatePromptsCustom: Boolean,
    focusGateSystemPrompt: String,
    onFocusGateSystemPromptChange: (String) -> Unit,
    focusGateContextTemplate: String,
    onFocusGateContextTemplateChange: (String) -> Unit,
    onFocusGatePromptsCustomChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    SectionHeader("Gate prompts")

    GatePromptEditorCard(
        title = "App gatekeeper",
        usingCustom = gatekeeperPromptsCustom,
        systemPrompt = gatekeeperSystemPrompt,
        onSystemPromptChange = onGatekeeperSystemPromptChange,
        contextTemplate = gatekeeperContextTemplate,
        onContextTemplateChange = onGatekeeperContextTemplateChange,
        contextPlaceholderHelp = PromptTemplates.CONTEXT_TEMPLATE_SYNTAX_HELP + "\n" +
            PromptTemplates.GATEKEEPER_CONTEXT_PLACEHOLDERS,
        onSave = {
            SettingsManager.saveGatekeeperPrompts(
                context,
                gatekeeperSystemPrompt,
                gatekeeperContextTemplate,
            )
            onGatekeeperPromptsCustomChange(SettingsManager.hasCustomGatekeeperPrompts(context))
            Toast.makeText(context, "Gatekeeper prompts saved", Toast.LENGTH_SHORT).show()
        },
        onReset = {
            SettingsManager.resetGatekeeperPrompts(context)
            onGatekeeperSystemPromptChange(PromptTemplates.DEFAULT_GATEKEEPER_SYSTEM_PROMPT)
            onGatekeeperContextTemplateChange(PromptTemplates.DEFAULT_GATEKEEPER_CONTEXT_TEMPLATE)
            onGatekeeperPromptsCustomChange(false)
            Toast.makeText(context, "Gatekeeper prompts reset to default", Toast.LENGTH_SHORT).show()
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    GatePromptEditorCard(
        title = "Focus time gate",
        usingCustom = focusGatePromptsCustom,
        systemPrompt = focusGateSystemPrompt,
        onSystemPromptChange = onFocusGateSystemPromptChange,
        contextTemplate = focusGateContextTemplate,
        onContextTemplateChange = onFocusGateContextTemplateChange,
        contextPlaceholderHelp = PromptTemplates.CONTEXT_TEMPLATE_SYNTAX_HELP + "\n" +
            PromptTemplates.FOCUS_GATE_CONTEXT_PLACEHOLDERS,
        onSave = {
            SettingsManager.saveFocusGatePrompts(
                context,
                focusGateSystemPrompt,
                focusGateContextTemplate,
            )
            onFocusGatePromptsCustomChange(SettingsManager.hasCustomFocusGatePrompts(context))
            Toast.makeText(context, "Focus gate prompts saved", Toast.LENGTH_SHORT).show()
        },
        onReset = {
            SettingsManager.resetFocusGatePrompts(context)
            onFocusGateSystemPromptChange(PromptTemplates.DEFAULT_FOCUS_GATE_SYSTEM_PROMPT)
            onFocusGateContextTemplateChange(PromptTemplates.DEFAULT_FOCUS_GATE_CONTEXT_TEMPLATE)
            onFocusGatePromptsCustomChange(false)
            Toast.makeText(context, "Focus gate prompts reset to default", Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
internal fun DailyLogSummariesSection(
    dailySummaryPromptText: String,
    onDailySummaryPromptTextChange: (String) -> Unit,
    dailySummaryPromptVersion: Int,
    onDailySummaryPromptVersionChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var dailySummaryRegenerateN by remember { mutableStateOf("0") }
    var dailySummarySaveBusy by remember { mutableStateOf(false) }

    SectionHeader("Daily log summaries")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Summarization prompt",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "End-of-day summaries are stored as JSON " +
                    "({\"summary\":\"…\",\"tagline\":\"…\"}). " +
                    "The tagline is the folded snippet and expanded title. " +
                    "Requires remote AI sign-in for generation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Text(
                text = dailySummaryPromptVersionLabel(dailySummaryPromptVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = dailySummaryPromptText,
                onValueChange = onDailySummaryPromptTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                label = { Text("Instructions") },
                minLines = 6,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = dailySummaryRegenerateN,
                onValueChange = { v ->
                    parseNonNegativeIntOrEmpty(v)?.let { dailySummaryRegenerateN = it }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Regenerate last N summaries with an older prompt") },
                supportingText = {
                    Text(
                        "0 skips. After save, re-runs the newest days that were produced " +
                            "with a lower prompt version (max " +
                            "${SettingsManager.MAX_DAILY_SUMMARY_REGENERATE}).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    saveDailySummaryPrompt(
                        dailySummarySaveBusy = dailySummarySaveBusy,
                        setBusy = { dailySummarySaveBusy = it },
                        coroutineScope = coroutineScope,
                        context = context,
                        dailySummaryRegenerateN = dailySummaryRegenerateN,
                        dailySummaryPromptText = dailySummaryPromptText,
                        onDailySummaryPromptVersionChange = onDailySummaryPromptVersionChange,
                    )
                },
                enabled = !dailySummarySaveBusy,
            ) {
                Text(if (dailySummarySaveBusy) "Saving…" else "Save instructions")
            }
        }
    }
}

private fun saveDailySummaryPrompt(
    dailySummarySaveBusy: Boolean,
    setBusy: (Boolean) -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    dailySummaryRegenerateN: String,
    dailySummaryPromptText: String,
    onDailySummaryPromptVersionChange: (Int) -> Unit,
) {
    if (dailySummarySaveBusy) return
    setBusy(true)
    coroutineScope.launch {
        try {
            val n = coerceDailySummaryRegenerateN(
                dailySummaryRegenerateN,
                SettingsManager.MIN_DAILY_SUMMARY_REGENERATE,
                SettingsManager.MAX_DAILY_SUMMARY_REGENERATE,
            )
            val message = withContext(Dispatchers.IO) {
                val newVersion = SettingsManager.saveDailySummaryPromptText(
                    context,
                    dailySummaryPromptText,
                )
                val token = ApiKeyManager.getSessionToken(context)
                val regen = if (n > 0 && !token.isNullOrBlank()) {
                    DailyLogSummaryGenerator.regenerateSummariesWithOlderPrompt(
                        context,
                        token,
                        newVersion,
                        n,
                    )
                } else {
                    null
                }
                val regenMsg = dailySummaryRegenerateToastSuffix(
                    regenerateN = n,
                    tokenBlank = token.isNullOrBlank(),
                    candidateDays = regen?.candidateDays ?: 0,
                    successCount = regen?.successCount ?: 0,
                )
                Pair(newVersion, regenMsg)
            }
            onDailySummaryPromptVersionChange(message.first)
            Toast.makeText(
                context,
                "Saved prompt version ${message.first}.${message.second}",
                Toast.LENGTH_LONG,
            ).show()
        } finally {
            setBusy(false)
        }
    }
}

@Composable
internal fun AboutSection() {
    SectionHeader("About")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "MindfulHome",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "A home launcher that nags, never blocks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            VersionLabel()
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
internal fun SettingsCard(
    title: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun GatePromptEditorCard(
    title: String,
    usingCustom: Boolean,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    contextTemplate: String,
    onContextTemplateChange: (String) -> Unit,
    contextPlaceholderHelp: String,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (usingCustom) {
                    "Using your saved prompts."
                } else {
                    "Showing defaults. Save to keep edits."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                label = { Text("System prompt") },
                minLines = 5,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = contextTemplate,
                onValueChange = onContextTemplateChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                label = { Text("Context template") },
                supportingText = {
                    Text(
                        text = contextPlaceholderHelp,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                minLines = 6,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSave) {
                    Text("Save")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onReset) {
                    Text("Reset to default")
                }
            }
        }
    }
}

private fun showTimePicker(
    context: Context,
    initialMinutes: Int,
    onPicked: (Int) -> Unit,
) {
    val clamped = initialMinutes.coerceIn(0, 1439)
    val initialHour = clamped / 60
    val initialMinute = clamped % 60
    TimePickerDialog(
        context,
        { _, hourOfDay, minute ->
            onPicked((hourOfDay * 60 + minute).coerceIn(0, 1439))
        },
        initialHour,
        initialMinute,
        true,
    ).show()
}
