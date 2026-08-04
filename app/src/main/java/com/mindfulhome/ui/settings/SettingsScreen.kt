package com.mindfulhome.ui.settings
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mindfulhome.ai.LiteRtLmManager
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.service.ForegroundAppAccessibilityService
import com.mindfulhome.service.UsageTracker
import com.mindfulhome.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenIntervalSettings: () -> Unit,
) {
    val context = LocalContext.current
    var hasUsageStats by remember { mutableStateOf(UsageTracker.hasUsageStatsPermission(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibilityEnabled by remember {
        mutableStateOf(ForegroundAppAccessibilityService.isEnabled(context))
    }

    var skippedUsagePrompt by remember {
        mutableStateOf(
            SettingsManager.isPermissionPromptSuppressed(
                context, SettingsManager.PermissionPrompt.USAGE_ACCESS
            )
        )
    }
    var skippedNotificationPrompt by remember {
        mutableStateOf(
            SettingsManager.isPermissionPromptSuppressed(
                context, SettingsManager.PermissionPrompt.NOTIFICATIONS
            )
        )
    }
    var skippedOverlayPrompt by remember {
        mutableStateOf(
            SettingsManager.isPermissionPromptSuppressed(
                context, SettingsManager.PermissionPrompt.OVERLAY
            )
        )
    }

    var dailySummaryPromptText by remember {
        mutableStateOf(SettingsManager.getDailySummaryPromptTextForEditing(context))
    }
    var dailySummaryPromptVersion by remember {
        mutableStateOf(SettingsManager.getDailySummaryPromptVersion(context))
    }
    var gatekeeperSystemPrompt by remember {
        mutableStateOf(SettingsManager.getGatekeeperSystemPromptForEditing(context))
    }
    var gatekeeperContextTemplate by remember {
        mutableStateOf(SettingsManager.getGatekeeperContextTemplateForEditing(context))
    }
    var focusGateSystemPrompt by remember {
        mutableStateOf(SettingsManager.getFocusGateSystemPromptForEditing(context))
    }
    var focusGateContextTemplate by remember {
        mutableStateOf(SettingsManager.getFocusGateContextTemplateForEditing(context))
    }
    var gatekeeperPromptsCustom by remember {
        mutableStateOf(SettingsManager.hasCustomGatekeeperPrompts(context))
    }
    var focusGatePromptsCustom by remember {
        mutableStateOf(SettingsManager.hasCustomFocusGatePrompts(context))
    }

    val hasModel = remember { LiteRtLmManager.hasModel(context) }

    var aiMode by remember { mutableStateOf(SettingsManager.getAIMode(context)) }
    var backendModel by remember { mutableStateOf(SettingsManager.getBackendModel(context)) }
    var isSignedIn by remember { mutableStateOf(false) }
    var signedInEmail by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasUsageStats = UsageTracker.hasUsageStatsPermission(context)
        hasNotificationPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        hasOverlayPermission = Settings.canDrawOverlays(context)
        accessibilityEnabled = ForegroundAppAccessibilityService.isEnabled(context)

        skippedUsagePrompt = SettingsManager.isPermissionPromptSuppressed(
            context, SettingsManager.PermissionPrompt.USAGE_ACCESS
        )
        skippedNotificationPrompt = SettingsManager.isPermissionPromptSuppressed(
            context, SettingsManager.PermissionPrompt.NOTIFICATIONS
        )
        skippedOverlayPrompt = SettingsManager.isPermissionPromptSuppressed(
            context, SettingsManager.PermissionPrompt.OVERLAY
        )

        coroutineScope.launch {
            isSignedIn = ApiKeyManager.isSignedIn(context)
            signedInEmail = ApiKeyManager.getSignedInEmail(context)
        }

        dailySummaryPromptVersion = SettingsManager.getDailySummaryPromptVersion(context)
        dailySummaryPromptText = SettingsManager.getDailySummaryPromptTextForEditing(context)
        gatekeeperSystemPrompt = SettingsManager.getGatekeeperSystemPromptForEditing(context)
        gatekeeperContextTemplate = SettingsManager.getGatekeeperContextTemplateForEditing(context)
        focusGateSystemPrompt = SettingsManager.getFocusGateSystemPromptForEditing(context)
        focusGateContextTemplate = SettingsManager.getFocusGateContextTemplateForEditing(context)
        gatekeeperPromptsCustom = SettingsManager.hasCustomGatekeeperPrompts(context)
        focusGatePromptsCustom = SettingsManager.hasCustomFocusGatePrompts(context)
    }

    // Fetch available models from backend, fall back to hardcoded list
    var availableModels by remember {
        mutableStateOf(SettingsManager.AVAILABLE_MODELS.map {
            BackendClient.ModelInfo(it.id, it.label, it.description)
        })
    }
    LaunchedEffect(Unit) {
        val fetched = withContext(Dispatchers.IO) {
            try {
                BackendClient.getModels().models
            } catch (e: Exception) {
                android.util.Log.w("SettingsScreen", "Failed to fetch models, using defaults", e)
                null
            }
        }
        if (fetched != null) {
            availableModels = fetched
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            LanguageSection()

            PermissionsSection(
                hasUsageStats = hasUsageStats,
                skippedUsagePrompt = skippedUsagePrompt,
                onSkippedUsagePromptChange = { skippedUsagePrompt = it },
                hasNotificationPermission = hasNotificationPermission,
                skippedNotificationPrompt = skippedNotificationPrompt,
                onSkippedNotificationPromptChange = { skippedNotificationPrompt = it },
                hasOverlayPermission = hasOverlayPermission,
                skippedOverlayPrompt = skippedOverlayPrompt,
                onSkippedOverlayPromptChange = { skippedOverlayPrompt = it },
                accessibilityEnabled = accessibilityEnabled,
            )

            BehaviorSection(onOpenIntervalSettings = onOpenIntervalSettings)

            AiModelSection(
                aiMode = aiMode,
                onAiModeChange = { aiMode = it },
                hasModel = hasModel,
                isSignedIn = isSignedIn,
                signedInEmail = signedInEmail,
                onSignedInChange = { signedIn, email ->
                    isSignedIn = signedIn
                    signedInEmail = email
                },
                backendModel = backendModel,
                onBackendModelChange = { backendModel = it },
                availableModels = availableModels,
            )

            GatePromptsSection(
                gatekeeperPromptsCustom = gatekeeperPromptsCustom,
                gatekeeperSystemPrompt = gatekeeperSystemPrompt,
                onGatekeeperSystemPromptChange = { gatekeeperSystemPrompt = it },
                gatekeeperContextTemplate = gatekeeperContextTemplate,
                onGatekeeperContextTemplateChange = { gatekeeperContextTemplate = it },
                onGatekeeperPromptsCustomChange = { gatekeeperPromptsCustom = it },
                focusGatePromptsCustom = focusGatePromptsCustom,
                focusGateSystemPrompt = focusGateSystemPrompt,
                onFocusGateSystemPromptChange = { focusGateSystemPrompt = it },
                focusGateContextTemplate = focusGateContextTemplate,
                onFocusGateContextTemplateChange = { focusGateContextTemplate = it },
                onFocusGatePromptsCustomChange = { focusGatePromptsCustom = it },
            )

            DailyLogSummariesSection(
                dailySummaryPromptText = dailySummaryPromptText,
                onDailySummaryPromptTextChange = { dailySummaryPromptText = it },
                dailySummaryPromptVersion = dailySummaryPromptVersion,
                onDailySummaryPromptVersionChange = { dailySummaryPromptVersion = it },
            )

            AboutSection()
        }
    }
}
