package com.mindfulhome.ui.negotiation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.NoCredentialException
import com.mindfulhome.ai.LiteRtLmManager
import com.mindfulhome.ai.NegotiationManager
import com.mindfulhome.ai.PromptTemplates
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.AuthManager
import com.mindfulhome.ai.backend.BackendAuthHelper
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.launch

data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    val isLoading: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NegotiationScreen(
    packageName: String,
    unlockReason: String = "",
    durationMinutes: Int,
    sessionHandle: SessionLogger.SessionHandle?,
    repository: AppRepository,
    karmaManager: KarmaManager,
    onTimerClick: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenKarma: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAppGranted: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val appLabel = remember {
        if (packageName.isNotEmpty()) {
            PackageManagerHelper.getAppLabel(context, packageName)
        } else {
            "an app"
        }
    }
    val focusModeActive = remember {
        SettingsManager.isFocusTimeActiveNow(context)
    }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var userInput by remember { mutableStateOf("") }
    var isWaitingForAi by remember { mutableStateOf(false) }
    var accessGranted by remember { mutableStateOf(false) }
    var launchTarget by remember { mutableStateOf("") }
    var conversationNonce by remember { mutableStateOf(0) }

    val lmManager = remember { LiteRtLmManager(context) }
    val useBackend = remember { SettingsManager.getAIMode(context) == SettingsManager.AI_MODE_BACKEND }
    val selectedModel = remember { SettingsManager.getBackendModel(context) }
    var sessionUseBackend by remember { mutableStateOf(useBackend) }
    var sessionSelectedModel by remember { mutableStateOf(selectedModel) }
    var showModelPicker by remember { mutableStateOf(false) }
    var pickerUseBackend by remember { mutableStateOf(sessionUseBackend) }
    var pickerSelectedModel by remember { mutableStateOf(sessionSelectedModel) }
    var modelLabel by remember {
        mutableStateOf(
            if (sessionUseBackend) "$sessionSelectedModel (checking auth...)" else "On-device (LiteRT-LM)"
        )
    }
    val backendAuth = remember {
        BackendAuthHelper(
            signIn = {
                val result = AuthManager.signIn(context)
                result?.idToken
            },
            getAppToken = { ApiKeyManager.getAppToken(context) },
            saveAppToken = { token, expiresAtMs ->
                ApiKeyManager.saveAppToken(context, token, expiresAtMs)
            },
            clearAppToken = { ApiKeyManager.clearAppToken(context) },
            getGoogleIdToken = { ApiKeyManager.getGoogleIdToken(context) },
        )
    }
    var negotiationManager by remember {
        mutableStateOf(
            NegotiationManager(
                lmManager,
                repository,
                karmaManager,
                if (sessionUseBackend) backendAuth else null,
                sessionSelectedModel,
            )
        )
    }

    fun addMessage(text: String, isFromUser: Boolean) {
        messages.add(ChatMessage(text, isFromUser = isFromUser))
        val prefix = if (isFromUser) "User" else "AI"
        SessionLogger.log(sessionHandle, "$prefix: ${text.take(120)}")
    }

    // Initialize AI and start conversation
    LaunchedEffect(packageName, conversationNonce) {
        scope.launch {
            messages.clear()
            userInput = ""
            isWaitingForAi = false
            accessGranted = false
            launchTarget = ""

            // Auto sign-in + token exchange if backend mode is active and no app token
            var remoteAuthFailed = false
            if (sessionUseBackend && !backendAuth.hasToken) {
                try {
                    val signInResult = AuthManager.signIn(context)
                    if (signInResult != null) {
                        if (signInResult.email != null) {
                            ApiKeyManager.saveSignedInEmail(context, signInResult.email)
                        }
                        // Immediately exchange Google token for our own app token
                        val exchanged = backendAuth.exchangeGoogleToken(signInResult.idToken)
                        if (!exchanged) {
                            remoteAuthFailed = true
                            Toast.makeText(
                                context,
                                "Backend authentication failed. Using on-device model.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        remoteAuthFailed = true
                        Toast.makeText(
                            context,
                            "Google Sign-In failed. Using on-device model.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (_: NoCredentialException) {
                    remoteAuthFailed = true
                    Toast.makeText(
                        context,
                        "No Google account available. Using on-device model.",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {
                    remoteAuthFailed = true
                    Toast.makeText(
                        context,
                        "Remote auth failed. Using on-device model.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            if (remoteAuthFailed) {
                sessionUseBackend = false
                negotiationManager.endConversation()
                negotiationManager = NegotiationManager(
                    lmManager = lmManager,
                    repository = repository,
                    karmaManager = karmaManager,
                    backendAuth = null,
                    backendModel = sessionSelectedModel,
                )
            }

            // Determine which model will actually be used
            val usingRemote = sessionUseBackend && backendAuth.hasToken
            val signedInEmail = if (usingRemote) ApiKeyManager.getSignedInEmail(context) else null
            modelLabel = if (usingRemote) {
                val emailSuffix = if (signedInEmail != null) " · $signedInEmail" else ""
                "$sessionSelectedModel$emailSuffix"
            } else {
                "On-device (LiteRT-LM)"
            }

            // Only initialize on-device model if we actually need it
            if (!usingRemote) {
                lmManager.initialize()
            }

            if (packageName.isNotEmpty()) {
                // Gatekeeper flow
                SessionLogger.log(sessionHandle, "AI negotiation started for **$appLabel** via $modelLabel")
                isWaitingForAi = true
                val result = negotiationManager.startGatekeeperNegotiation(
                    packageName = packageName,
                    appName = appLabel,
                    focusModeActive = focusModeActive,
                )
                addMessage(result.responseText, isFromUser = false)
                isWaitingForAi = false
                if (result.accessGranted) {
                    accessGranted = true
                }
            } else {
                // General chat
                SessionLogger.log(sessionHandle, "AI assistant opened via $modelLabel")
                if (unlockReason.isNotEmpty()) {
                    // Reason provided at timer screen — skip the generic greeting
                    // and feed it as the first user message so the AI responds directly
                    addMessage(unlockReason, isFromUser = true)
                    negotiationManager.startGeneralChat(context)
                    isWaitingForAi = true
                    val result = negotiationManager.reply(unlockReason)
                    addMessage(result.responseText, isFromUser = false)
                    isWaitingForAi = false
                    if (result.launchedPackage.isNotEmpty()) {
                        val label = PackageManagerHelper.getAppLabel(
                            context, result.launchedPackage
                        )
                        SessionLogger.log(sessionHandle, "Launched **$label**")
                        launchTarget = result.launchedPackage
                    }
                } else {
                    addMessage(PromptTemplates.GENERAL_CHAT_GREETING, isFromUser = false)
                    negotiationManager.startGeneralChat(context)
                }
            }
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Launch the app when access is granted (gatekeeper flow)
    LaunchedEffect(accessGranted) {
        if (accessGranted && packageName.isNotEmpty()) {
            PackageManagerHelper.launchApp(context, packageName)
            negotiationManager.endConversation()
            lmManager.shutdown()
            onAppGranted()
        }
    }

    // Launch the app from general chat (launchApp tool)
    LaunchedEffect(launchTarget) {
        if (launchTarget.isNotEmpty()) {
            val targetPackage = launchTarget
            launchTarget = ""
            val launched = PackageManagerHelper.launchApp(context, targetPackage)
            if (launched) {
                negotiationManager.endConversation()
                lmManager.shutdown()
                onAppGranted()
            } else {
                addMessage(
                    "I couldn't launch $targetPackage. Let me search installed apps for a match.",
                    isFromUser = false
                )
                isWaitingForAi = true
                val result = negotiationManager.reply(
                    "SYSTEM: launchApp failed for package '$targetPackage'. " +
                        "Call searchApps to find installed matches and suggest one."
                )
                addMessage(result.responseText, isFromUser = false)
                isWaitingForAi = false
                if (result.launchedPackage.isNotEmpty()) {
                    launchTarget = result.launchedPackage
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$durationMinutes min",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onTimerClick)
            )

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onOpenLogs) {
                Icon(
                    Icons.AutoMirrored.Filled.Article,
                    contentDescription = "Session logs",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = onOpenKarma) {
                Icon(
                    Icons.Default.Stars,
                    contentDescription = "Karma",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // Top bar with model indicator
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = if (packageName.isNotEmpty()) "Opening $appLabel" else "AI Assistant",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            pickerUseBackend = sessionUseBackend
                            pickerSelectedModel = sessionSelectedModel
                            showModelPicker = true
                        }
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = {
                    negotiationManager.endConversation()
                    lmManager.shutdown()
                    onDismiss()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message)
            }

            if (isWaitingForAi) {
                item {
                    ChatBubble(ChatMessage("", isFromUser = false, isLoading = true))
                }
            }
        }

        // Input bar
        if (!accessGranted && launchTarget.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type your response...") },
                    singleLine = false,
                    maxLines = 3,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    enabled = !isWaitingForAi
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (userInput.isNotBlank() && !isWaitingForAi) {
                            val input = userInput.trim()
                            userInput = ""
                            addMessage(input, isFromUser = true)

                            scope.launch {
                                isWaitingForAi = true
                                val result = negotiationManager.reply(input)
                                addMessage(result.responseText, isFromUser = false)
                                isWaitingForAi = false

                                if (result.accessGranted) {
                                    SessionLogger.log(sessionHandle, "Access granted to **$appLabel**")
                                    accessGranted = true
                                }
                                if (result.launchedPackage.isNotEmpty()) {
                                    val label = PackageManagerHelper.getAppLabel(
                                        context, result.launchedPackage
                                    )
                                    SessionLogger.log(sessionHandle, "Launched **$label**")
                                    launchTarget = result.launchedPackage
                                }
                            }
                        }
                    },
                    enabled = userInput.isNotBlank() && !isWaitingForAi,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (userInput.isNotBlank() && !isWaitingForAi) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (userInput.isNotBlank() && !isWaitingForAi) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("Model for this session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !pickerUseBackend,
                            onClick = { pickerUseBackend = false }
                        )
                        Text(
                            text = "On-device (LiteRT-LM)",
                            modifier = Modifier.clickable { pickerUseBackend = false }
                        )
                    }

                    SettingsManager.AVAILABLE_MODELS.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = pickerUseBackend && pickerSelectedModel == option.id,
                                onClick = {
                                    pickerUseBackend = true
                                    pickerSelectedModel = option.id
                                }
                            )
                            Text(
                                text = option.label,
                                modifier = Modifier.clickable {
                                    pickerUseBackend = true
                                    pickerSelectedModel = option.id
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showModelPicker = false
                        val changed = pickerUseBackend != sessionUseBackend ||
                            pickerSelectedModel != sessionSelectedModel
                        if (!changed) return@TextButton

                        negotiationManager.endConversation()
                        sessionUseBackend = pickerUseBackend
                        sessionSelectedModel = pickerSelectedModel
                        modelLabel = if (sessionUseBackend) {
                            "$sessionSelectedModel (checking auth...)"
                        } else {
                            "On-device (LiteRT-LM)"
                        }
                        negotiationManager = NegotiationManager(
                            lmManager = lmManager,
                            repository = repository,
                            karmaManager = karmaManager,
                            backendAuth = if (sessionUseBackend) backendAuth else null,
                            backendModel = sessionSelectedModel,
                        )
                        conversationNonce += 1
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.isFromUser) Alignment.End else Alignment.Start
    val backgroundColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isFromUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isFromUser) 4.dp else 16.dp
                    )
                )
                .background(backgroundColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (message.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = textColor
                    )
                    Text(
                        text = "Thinking...",
                        color = textColor,
                        fontSize = 14.sp
                    )
                }
            } else {
                Text(
                    text = message.text,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}
