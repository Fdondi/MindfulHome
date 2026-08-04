package com.mindfulhome.ui.negotiation
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.model.AppInfo
import com.mindfulhome.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NegotiationTopBar(
    durationMinutes: Int,
    title: String,
    modelLabel: String,
    onTimerClick: () -> Unit,
    onOpenDefault: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenKarma: () -> Unit,
    onOpenSettings: () -> Unit,
    onModelLabelClick: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onTimerClick) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back_to_timer),
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Icon(
                Icons.Default.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$durationMinutes min",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OutlinedButton(onClick = onOpenDefault) {
            Icon(
                Icons.Default.Home,
                contentDescription = stringResource(R.string.home),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onOpenLogs) {
            Icon(
                Icons.AutoMirrored.Filled.Article,
                contentDescription = stringResource(R.string.session_logs_2),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(onClick = onOpenKarma) {
            Icon(
                Icons.Default.Stars,
                contentDescription = stringResource(R.string.karma),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }

    TopAppBar(
        title = {
            Column {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onModelLabelClick),
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
    )
}

@Composable
internal fun ChatMessageList(
    messages: List<ChatMessage>,
    listState: LazyListState,
    isWaitingForAi: Boolean,
    isLoadingApps: Boolean,
    showLaunchSuggestions: Boolean,
    suggestedLaunchApps: List<AppInfo>,
    onSuggestionClick: (AppInfo) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { message ->
            ChatBubble(message)
        }

        if (isWaitingForAi) {
            item {
                ChatBubble(
                    ChatMessage("", isFromUser = false, isLoading = true, loadingText = "Thinking..."),
                )
            }
        }

        if (isLoadingApps) {
            item {
                ChatBubble(
                    ChatMessage("", isFromUser = false, isLoading = true, loadingText = "Loading..."),
                )
            }
        }

        if (showLaunchSuggestions) {
            item {
                LaunchSuggestionsBubble(
                    apps = suggestedLaunchApps,
                    onAppClick = onSuggestionClick,
                    onSearchClick = onSearchClick,
                )
            }
        }
    }
}

@Composable
internal fun GateProceedButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ChatInputBar(
    userInput: String,
    onUserInputChange: (String) -> Unit,
    enabled: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = userInput,
            onValueChange = onUserInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.type_your_response)) },
            singleLine = false,
            maxLines = 3,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(24.dp),
            enabled = enabled,
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSend,
            enabled = sendEnabled,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (sendEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.send),
                tint = if (sendEnabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
internal fun SessionModelPickerDialog(
    pickerUseBackend: Boolean,
    pickerSelectedModel: String,
    onPickerUseBackendChange: (Boolean) -> Unit,
    onPickerSelectedModelChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_for_this_session)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = !pickerUseBackend,
                        onClick = { onPickerUseBackendChange(false) },
                    )
                    Text(
                        text = stringResource(R.string.on_device_litert_lm),
                        modifier = Modifier.clickable { onPickerUseBackendChange(false) },
                    )
                }

                SettingsManager.AVAILABLE_MODELS.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = pickerUseBackend && pickerSelectedModel == option.id,
                            onClick = {
                                onPickerUseBackendChange(true)
                                onPickerSelectedModelChange(option.id)
                            },
                        )
                        Text(
                            text = option.label,
                            modifier = Modifier.clickable {
                                onPickerUseBackendChange(true)
                                onPickerSelectedModelChange(option.id)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private data class ChatBubbleStyle(
    val alignment: Alignment.Horizontal,
    val backgroundColor: Color,
    val textColor: Color,
    val shape: RoundedCornerShape,
)

@Composable
private fun chatBubbleStyle(isFromUser: Boolean): ChatBubbleStyle {
    val scheme = MaterialTheme.colorScheme
    return if (isFromUser) {
        ChatBubbleStyle(
            alignment = Alignment.End,
            backgroundColor = scheme.primary,
            textColor = scheme.onPrimary,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 4.dp,
            ),
        )
    } else {
        ChatBubbleStyle(
            alignment = Alignment.Start,
            backgroundColor = scheme.secondaryContainer,
            textColor = scheme.onSecondaryContainer,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp,
            ),
        )
    }
}

@Composable
internal fun ChatBubble(message: ChatMessage) {
    val style = chatBubbleStyle(message.isFromUser)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = style.alignment,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(style.shape)
                .background(style.backgroundColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = style.textColor,
                    )
                    Text(
                        text = message.loadingText,
                        color = style.textColor,
                        fontSize = 14.sp,
                    )
                }
            } else {
                Text(
                    text = message.text,
                    color = style.textColor,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun LaunchSuggestionsBubble(
    apps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
    onSearchClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 16.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.pick_an_app_to_launch),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 14.sp,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .clickable(onClick = onSearchClick),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search_apps_2),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Text(
                                text = "Search",
                                maxLines = 1,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    items(apps, key = { it.packageName }) { app ->
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .clickable { onAppClick(app) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (app.icon != null) {
                                Image(
                                    painter = rememberDrawablePainter(drawable = app.icon),
                                    contentDescription = app.label,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                            Text(
                                text = app.label,
                                maxLines = 1,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun negotiationScreenTitle(
    packageName: String,
    appLabel: String,
    isFocusGate: Boolean,
): String = when {
    packageName.isNotEmpty() -> "Opening $appLabel"
    isFocusGate -> "Focus time check"
    else -> "AI Assistant"
}

internal fun gateProceedLabel(
    isExtendGate: Boolean,
    grantedExtensionMinutes: Int,
    packageName: String,
    appLabel: String,
): String = when {
    isExtendGate && grantedExtensionMinutes > 0 ->
        "Continue (+$grantedExtensionMinutes min)"
    packageName.isNotEmpty() && !isExtendGate ->
        "Proceed to $appLabel"
    else -> "Proceed"
}
