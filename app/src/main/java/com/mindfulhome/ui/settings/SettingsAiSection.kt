package com.mindfulhome.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mindfulhome.R
import com.mindfulhome.ai.AiMode
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.ai.backend.BackendSignInOutcome
import com.mindfulhome.ai.backend.backendSignInOutcomeMessage
import com.mindfulhome.ai.consumeCompletedGoogleAiSetup
import com.mindfulhome.ai.startGoogleAiSetup
import com.mindfulhome.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun AiModelSection(
    aiMode: AiMode,
    onAiModeChange: (AiMode) -> Unit,
    playgroundInstalled: Boolean,
    isSignedIn: Boolean,
    signedInEmail: String?,
    onSignedInChange: (Boolean, String?) -> Unit,
    backendModel: String,
    onBackendModelChange: (String) -> Unit,
    availableModels: List<BackendClient.ModelInfo>,
    headerModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var signInInProgress by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (signInInProgress) signInInProgress = false
    }

    SectionHeader(stringResource(R.string.ai_model), modifier = headerModifier)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.model_source),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.choose_where_ai_processing_runs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            AiModeOptionRow(
                selected = aiMode == AiMode.BACKEND,
                enabled = true,
                title = stringResource(R.string.ai_mode_google),
                description = stringResource(R.string.more_capable_requires_google_sign_in_and_interne),
                onClick = {
                    onAiModeChange(AiMode.BACKEND)
                    SettingsManager.setAIMode(context, AiMode.BACKEND)
                },
            )
            AiModeOptionRow(
                selected = aiMode == AiMode.ON_DEVICE,
                enabled = true,
                title = stringResource(R.string.on_device_litert_lm),
                description = stringResource(R.string.private_works_offline_requires_downloading_a_mod),
                onClick = {
                    onAiModeChange(AiMode.ON_DEVICE)
                    SettingsManager.setAIMode(context, AiMode.ON_DEVICE)
                },
            )
            AiModeOptionRow(
                selected = aiMode == AiMode.NONE,
                enabled = true,
                title = stringResource(R.string.ai_mode_none),
                description = stringResource(R.string.ai_mode_none_description),
                onClick = {
                    onAiModeChange(AiMode.NONE)
                    SettingsManager.setAIMode(context, AiMode.NONE)
                },
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    when (aiMode) {
        AiMode.ON_DEVICE -> OnDeviceModelCard(installed = playgroundInstalled)
        AiMode.BACKEND -> BackendAccountCard(
            isSignedIn = isSignedIn,
            signedInEmail = signedInEmail,
            onSignedInChange = onSignedInChange,
            backendModel = backendModel,
            onBackendModelChange = onBackendModelChange,
            availableModels = availableModels,
            signInInProgress = signInInProgress,
            onSignInInProgressChange = { signInInProgress = it },
        )
        AiMode.NONE -> Unit
    }
}

@Composable
private fun AiModeOptionRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun OnDeviceModelCard(installed: Boolean) {
    val context = LocalContext.current
    val descriptionColor = if (installed) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.lm_playground_server),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = onDeviceModelDescription(installed),
                style = MaterialTheme.typography.bodySmall,
                color = descriptionColor,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!installed) {
                TextButton(
                    onClick = { openLmPlaygroundInstall(context) },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.install_lm_playground))
                }
            }
        }
    }
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
    coroutineScope: CoroutineScope,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.google_account),
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
        text = signedInEmail ?: stringResource(R.string.signed_in_with_google),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.remote_ai_is_active_conversations_are_processed_),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
}

@Composable
private fun BackendSignedOutBody(
    signInInProgress: Boolean,
    onSignInClick: () -> Unit,
) {
    Text(
        text = stringResource(R.string.not_signed_in),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.backend_sign_in_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Button(onClick = onSignInClick, enabled = !signInInProgress) {
        Text(
            if (signInInProgress) {
                stringResource(R.string.signing_in)
            } else {
                stringResource(R.string.sign_in_with_google)
            },
        )
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
                text = stringResource(R.string.ai_model),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.choose_which_gemini_model_to_use),
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
    val outcome = startGoogleAiSetup(context)
    when (outcome) {
        is BackendSignInOutcome.Success -> onSignedInChange(true, outcome.email)
        is BackendSignInOutcome.InteractiveStarted -> Unit
        else -> backendSignInOutcomeMessage(context, outcome)?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }
    if (outcome !is BackendSignInOutcome.InteractiveStarted) onDone()
    else if (consumeCompletedGoogleAiSetup(context)) {
        onSignedInChange(true, ApiKeyManager.getSignedInEmail(context))
        onDone()
    }
}
