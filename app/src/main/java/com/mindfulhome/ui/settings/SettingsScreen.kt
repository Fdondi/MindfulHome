package com.mindfulhome.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.NoCredentialException
import com.mindfulhome.ai.LiteRtLmManager
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.AuthManager
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.ai.backend.BackendHttpException
import com.mindfulhome.service.UsageTracker
import com.mindfulhome.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hasUsageStats = remember { UsageTracker.hasUsageStatsPermission(context) }
    val hasModel = remember { LiteRtLmManager.hasModel(context) }

    var aiMode by remember { mutableStateOf(SettingsManager.getAIMode(context)) }
    var backendModel by remember { mutableStateOf(SettingsManager.getBackendModel(context)) }
    var isSignedIn by remember { mutableStateOf(ApiKeyManager.isSignedIn(context)) }
    var signedInEmail by remember { mutableStateOf(ApiKeyManager.getSignedInEmail(context)) }
    var signInInProgress by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

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
            title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Permissions section
            SectionHeader("Permissions")

            SettingsCard(
                title = "Usage Access",
                description = if (hasUsageStats) {
                    "Granted. MindfulHome can track which app is in the foreground."
                } else {
                    "Required for karma tracking. Tap to grant."
                },
                actionLabel = if (hasUsageStats) null else "Grant",
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsCard(
                title = "Notification Permission",
                description = "Required for timer countdown and nudge notifications.",
                actionLabel = "Check",
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // AI Model section
            SectionHeader("AI Model")

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Model Source",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Choose where AI processing runs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )

                    // On-device option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = aiMode == SettingsManager.AI_MODE_ON_DEVICE,
                            onClick = {
                                aiMode = SettingsManager.AI_MODE_ON_DEVICE
                                SettingsManager.setAIMode(context, SettingsManager.AI_MODE_ON_DEVICE)
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "On-device (LiteRT-LM)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Private, works offline. Requires downloading a model.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Remote option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        RadioButton(
                            selected = aiMode == SettingsManager.AI_MODE_BACKEND,
                            onClick = {
                                aiMode = SettingsManager.AI_MODE_BACKEND
                                SettingsManager.setAIMode(context, SettingsManager.AI_MODE_BACKEND)
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Remote (Gemini)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "More capable. Requires Google sign-in and internet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Contextual detail card based on selected mode
            if (aiMode == SettingsManager.AI_MODE_ON_DEVICE) {
                val sharedDir = LiteRtLmManager.SHARED_MODEL_DIR
                SettingsCard(
                    title = "LiteRT-LM Model",
                    description = if (hasModel) {
                        "Model installed. AI features are active."
                    } else {
                        "No model found. Download Gemma3-1B-IT (.litertlm) from " +
                        "HuggingFace (557 MB) and push it via adb:\n\n" +
                        "adb push model.litertlm ${sharedDir.absolutePath}/\n\n" +
                        "The app checks both ${sharedDir.absolutePath}/ and the app-private models dir. " +
                        "Without a model, fallback scripted responses will be used."
                    },
                    actionLabel = if (hasModel) null else "Copy adb Command",
                    onAction = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val cmd = "adb push model.litertlm ${sharedDir.absolutePath}/"
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("adb push command", cmd)
                        )
                        Toast.makeText(context, "adb command copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                // Remote mode: Google sign-in card
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Google Account",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isSignedIn) {
                            Text(
                                text = signedInEmail ?: "Signed in with Google",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Remote AI is active. Conversations are processed via the Gemini backend.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = {
                                ApiKeyManager.signOut(context)
                                isSignedIn = false
                                signedInEmail = null
                            }) {
                                Text("Sign out")
                            }
                        } else {
                            Text(
                                text = "Not signed in",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Sign in with your Google account to use the remote Gemini model. " +
                                    "Without signing in, the app will fall back to on-device responses.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    signInInProgress = true
                                    coroutineScope.launch {
                                        try {
                                            val result = AuthManager.signIn(context)
                                            if (result != null) {
                                                if (result.email != null) {
                                                    ApiKeyManager.saveSignedInEmail(context, result.email)
                                                }
                                                // Exchange Google token for app token immediately
                                                try {
                                                    val exchangeResponse = withContext(Dispatchers.IO) {
                                                        BackendClient.exchangeToken(result.idToken)
                                                    }
                                                    val expiresAtMs = BackendClient.parseExpiresAt(
                                                        exchangeResponse.expiresAt
                                                    )
                                                    ApiKeyManager.saveAppToken(
                                                        context, exchangeResponse.token, expiresAtMs
                                                    )
                                                    isSignedIn = true
                                                    signedInEmail = result.email
                                                } catch (e: BackendHttpException) {
                                                    val message = when {
                                                        e.statusCode == 401 ->
                                                            "Sign-in rejected. Your account may not have credits."
                                                        else ->
                                                            "Token exchange failed: HTTP ${e.statusCode}"
                                                    }
                                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(
                                                        context,
                                                        "Token exchange failed: ${e.message}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Google Sign-In was cancelled or failed",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        } catch (_: NoCredentialException) {
                                            Toast.makeText(
                                                context,
                                                "No Google account found. Opening account setup\u2026",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            context.startActivity(
                                                Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                                                    putExtra(
                                                        Settings.EXTRA_ACCOUNT_TYPES,
                                                        arrayOf("com.google")
                                                    )
                                                }
                                            )
                                        }
                                        signInInProgress = false
                                    }
                                },
                                enabled = !signInInProgress
                            ) {
                                Text(
                                    if (signInInProgress) "Signing in..." else "Sign in with Google"
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Model picker
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "AI Model",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Choose which Gemini model to use",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                        )

                        availableModels.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                RadioButton(
                                    selected = backendModel == option.id,
                                    onClick = {
                                        backendModel = option.id
                                        SettingsManager.setBackendModel(context, option.id)
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // About section
            SectionHeader("About")

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "MindfulHome",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "A home launcher that nags, never blocks.\n" +
                                "Version 0.1.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(
    title: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
