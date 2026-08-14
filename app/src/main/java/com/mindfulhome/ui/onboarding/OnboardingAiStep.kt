package com.mindfulhome.ui.onboarding

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
import com.mindfulhome.ai.AuthManagerLogic
import com.mindfulhome.ai.LmPlaygroundManager
import com.mindfulhome.ai.backend.BackendSignInOutcome
import com.mindfulhome.ai.backend.backendSignInOutcomeMessage
import com.mindfulhome.ai.backend.performBackendSignIn
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
    val advanceGoogle = rememberGoogleAdvance(context, onNext)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        playgroundInstalled = LmPlaygroundManager.isInstalled(context)
        if (AuthManagerLogic.InteractiveSignInHandoff.consumePersistedSuccess()) {
            advanceGoogle()
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
                onSuccess = advanceGoogle,
            )
        },
    )
    Spacer(modifier = Modifier.height(12.dp))
    LocalAiChoiceButton(
        enabled = !busy,
        playgroundInstalled = playgroundInstalled,
        onChooseInstalled = {
            SettingsManager.setAIMode(context, SettingsManager.AI_MODE_ON_DEVICE)
            onNext()
        },
        onInstall = { openLmPlaygroundInstall(context) },
    )
    Spacer(modifier = Modifier.height(12.dp))
    NoneAiChoiceButton(
        enabled = !busy,
        onClick = {
            SettingsManager.setAIMode(context, SettingsManager.AI_MODE_NONE)
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

@Composable
private fun rememberGoogleAdvance(context: android.content.Context, onNext: () -> Unit): () -> Unit {
    var advanced by remember { mutableStateOf(false) }
    return {
        if (!advanced) {
            advanced = true
            SettingsManager.setAIMode(context, SettingsManager.AI_MODE_BACKEND)
            onNext()
        }
    }
}

private fun startGoogleSignIn(
    scope: CoroutineScope,
    context: android.content.Context,
    onBusy: (Boolean) -> Unit,
    onSuccess: () -> Unit,
) {
    onBusy(true)
    scope.launch {
        val outcome = performBackendSignIn(context)
        onBusy(false)
        if (outcome is BackendSignInOutcome.Success) onSuccess()
        else toastSignInOutcome(context, outcome)
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

private fun toastSignInOutcome(context: android.content.Context, outcome: BackendSignInOutcome) {
    val message = backendSignInOutcomeMessage(context, outcome) ?: return
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
