package com.mindfulhome.ui.settings

import android.app.TimePickerDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mindfulhome.R
import com.mindfulhome.ai.PromptTemplates
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.locale.LocaleHelper
import com.mindfulhome.logging.DailyLogSummaryGenerator
import com.mindfulhome.service.ForegroundAppAccessibilityService
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.ui.common.LanguagePickerList
import com.mindfulhome.ui.common.VersionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LanguageSection(headerModifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(SettingsManager.getAppLanguage(context)) }

    SectionHeader(stringResource(R.string.settings_language), modifier = headerModifier)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_language_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LanguagePickerList(
                selected = selected,
                onSelect = { language ->
                    if (language == selected) return@LanguagePickerList
                    // setApplicationLocales recreates activities with the new configuration.
                    LocaleHelper.setLanguage(context, language)
                },
            )
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
}

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
    headerModifier: Modifier = Modifier,
    notificationCardModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.permissions), modifier = headerModifier)

    val usage = permissionCardCopy(
        context,
        SettingsPermissionKind.UsageAccess,
        granted = hasUsageStats,
        skippedPrompt = skippedUsagePrompt,
        permissionTitle = stringResource(R.string.usage_access),
        accessibilityEnabled = accessibilityEnabled,
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
        context,
        SettingsPermissionKind.Notification,
        granted = hasNotificationPermission,
        skippedPrompt = skippedNotificationPrompt,
        permissionTitle = stringResource(R.string.notification_permission),
    )
    SettingsCard(
        title = notification.title,
        description = notification.description,
        actionLabel = notification.actionLabel,
        modifier = notificationCardModifier,
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
        context,
        SettingsPermissionKind.Overlay,
        granted = hasOverlayPermission,
        skippedPrompt = skippedOverlayPrompt,
        permissionTitle = stringResource(R.string.overlay_permission),
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
        context,
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
                    context.getString(R.string.couldn_t_open_accessibility_settings_on_this_dev),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )
}

@Composable
internal fun BehaviorSection(
    onOpenIntervalSettings: () -> Unit,
    headerModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.behavior), modifier = headerModifier)

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
                        text = stringResource(R.string.focus_time_ai_first),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.focus_time_description),
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
                    text = stringResource(R.string.no_intervals_configured),
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
                            Text(stringResource(R.string.remove))
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
                Text(stringResource(R.string.add_interval))
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
                text = stringResource(R.string.focus_gate_length),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.focus_gate_length_description),
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
                    text = stringResource(R.string.min_rounds_label, focusGateMinRounds.toInt()),
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
                    text = stringResource(R.string.max_rounds_label, focusGateMaxRounds.toInt()),
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
                        text = stringResource(R.string.developer_logs),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.when_enabled_chat_logs_include_tool_calls_parame),
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
                text = stringResource(R.string.quick_return_window),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.quick_return_window_description),
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
                text = stringResource(R.string.timing_intervals),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.timing_intervals_description),
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
                Text(stringResource(R.string.open_timing_intervals))
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
                text = stringResource(R.string.bubbles_before_banners),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.after_this_many_bubbles_switch_to_full_width_ban),
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
                text = stringResource(R.string.strikes_before_hiding),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.strikes_before_hiding_description),
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
    headerModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.gate_prompts), modifier = headerModifier)

    GatePromptEditorCard(
        title = stringResource(R.string.app_gatekeeper),
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
            Toast.makeText(context, context.getString(R.string.gatekeeper_prompts_saved), Toast.LENGTH_SHORT).show()
        },
        onReset = {
            SettingsManager.resetGatekeeperPrompts(context)
            onGatekeeperSystemPromptChange(PromptTemplates.DEFAULT_GATEKEEPER_SYSTEM_PROMPT)
            onGatekeeperContextTemplateChange(PromptTemplates.DEFAULT_GATEKEEPER_CONTEXT_TEMPLATE)
            onGatekeeperPromptsCustomChange(false)
            Toast.makeText(context, context.getString(R.string.gatekeeper_prompts_reset_to_default), Toast.LENGTH_SHORT).show()
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    GatePromptEditorCard(
        title = stringResource(R.string.focus_time_gate),
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
            Toast.makeText(context, context.getString(R.string.focus_gate_prompts_saved), Toast.LENGTH_SHORT).show()
        },
        onReset = {
            SettingsManager.resetFocusGatePrompts(context)
            onFocusGateSystemPromptChange(PromptTemplates.DEFAULT_FOCUS_GATE_SYSTEM_PROMPT)
            onFocusGateContextTemplateChange(PromptTemplates.DEFAULT_FOCUS_GATE_CONTEXT_TEMPLATE)
            onFocusGatePromptsCustomChange(false)
            Toast.makeText(context, context.getString(R.string.focus_gate_prompts_reset_to_default), Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
internal fun DailyLogSummariesSection(
    dailySummaryPromptText: String,
    onDailySummaryPromptTextChange: (String) -> Unit,
    dailySummaryPromptVersion: Int,
    onDailySummaryPromptVersionChange: (Int) -> Unit,
    headerModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var dailySummaryRegenerateN by remember { mutableStateOf("0") }
    var dailySummarySaveBusy by remember { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.daily_log_summaries), modifier = headerModifier)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.summarization_prompt),
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
                label = { Text(stringResource(R.string.instructions)) },
                minLines = 6,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = dailySummaryRegenerateN,
                onValueChange = { v ->
                    parseNonNegativeIntOrEmpty(v)?.let { dailySummaryRegenerateN = it }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.regenerate_last_n_summaries_with_an_older_prompt)) },
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
internal fun AboutSection(onShowTour: () -> Unit) {
    SectionHeader(stringResource(R.string.about))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.mindfulhome),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.a_home_launcher_that_nags_never_blocks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            VersionLabel()
            TextButton(onClick = onShowTour, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.coachmark_show_tour))
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
internal fun SettingsCard(
    title: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
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
                    enabled = actionEnabled,
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
                label = { Text(stringResource(R.string.system_prompt)) },
                minLines = 5,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = contextTemplate,
                onValueChange = onContextTemplateChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                label = { Text(stringResource(R.string.context_template)) },
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
                    Text(stringResource(R.string.save))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.reset_to_default))
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
