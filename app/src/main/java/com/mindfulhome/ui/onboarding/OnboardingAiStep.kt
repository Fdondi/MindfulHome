package com.mindfulhome.ui.onboarding

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mindfulhome.R
import com.mindfulhome.ai.AiMode
import com.mindfulhome.ai.LmPlaygroundManager
import com.mindfulhome.ai.backend.BackendSignInOutcome
import com.mindfulhome.ai.backend.backendSignInOutcomeMessage
import com.mindfulhome.ai.consumeCompletedGoogleAiSetup
import com.mindfulhome.ai.startGoogleAiSetup
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.ui.settings.openLmPlaygroundInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ModelStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playgroundInstalled by remember { mutableStateOf(LmPlaygroundManager.isInstalled(context)) }
    var busy by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        playgroundInstalled = LmPlaygroundManager.isInstalled(context)
        if (!SettingsManager.isPendingGoogleAiSetup(context)) return@LifecycleEventEffect
        scope.launch {
            if (consumeCompletedGoogleAiSetup(context)) onNext()
            else busy = false
        }
    }

    ModelStepHeader()
    GoogleAiChoiceButton(
        enabled = !busy,
        onClick = {
            startGoogleSignIn(
                scope = scope,
                context = context,
                onBusy = { busy = it },
                onSuccess = onNext,
            )
        },
    )
    Spacer(modifier = Modifier.height(12.dp))
    LocalAiChoiceButton(
        enabled = !busy,
        playgroundInstalled = playgroundInstalled,
        onChooseInstalled = {
            SettingsManager.setAIMode(context, AiMode.ON_DEVICE)
            onNext()
        },
        onInstall = { openLmPlaygroundInstall(context) },
    )
    Spacer(modifier = Modifier.height(12.dp))
    NoneAiChoiceButton(
        enabled = !busy,
        onClick = {
            SettingsManager.setAIMode(context, AiMode.NONE)
            onNext()
        },
    )
}

@Composable
private fun ModelStepHeader() {
    Text(
        text = stringResource(R.string.ai_model_options),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.onboarding_ai_model_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun GoogleAiChoiceButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_ai_google_title))
    }
    ChoiceCaption(stringResource(R.string.onboarding_ai_google_body))
}

private fun startGoogleSignIn(
    scope: CoroutineScope,
    context: Context,
    onBusy: (Boolean) -> Unit,
    onSuccess: () -> Unit,
) {
    onBusy(true)
    scope.launch {
        val outcome = startGoogleAiSetup(context)
        when (outcome) {
            is BackendSignInOutcome.Success -> onSuccess()
            is BackendSignInOutcome.InteractiveStarted -> Unit
            else -> {
                onBusy(false)
                toastSignInOutcome(context, outcome)
            }
        }
    }
}

@Composable
private fun LocalAiChoiceButton(
    enabled: Boolean,
    playgroundInstalled: Boolean,
    onChooseInstalled: () -> Unit,
    onInstall: () -> Unit,
) {
    OutlinedButton(
        onClick = { if (playgroundInstalled) onChooseInstalled() else onInstall() },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (playgroundInstalled) {
                stringResource(R.string.onboarding_ai_local_title)
            } else {
                stringResource(R.string.install_lm_playground)
            },
        )
    }
    ChoiceCaption(stringResource(R.string.onboarding_ai_local_body))
}

@Composable
private fun NoneAiChoiceButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_ai_none_title))
    }
    ChoiceCaption(stringResource(R.string.onboarding_ai_none_body))
}

@Composable
private fun ChoiceCaption(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun toastSignInOutcome(context: Context, outcome: BackendSignInOutcome) {
    val message = backendSignInOutcomeMessage(context, outcome) ?: return
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
