package com.mindfulhome.ui.negotiation

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
    repository: AppRepository,
    karmaManager: KarmaManager,
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

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var userInput by remember { mutableStateOf("") }
    var isWaitingForAi by remember { mutableStateOf(false) }
    var accessGranted by remember { mutableStateOf(false) }
    var launchTarget by remember { mutableStateOf("") }
    var modelLabel by remember { mutableStateOf("") }

    val lmManager = remember { LiteRtLmManager(context) }
    val useBackend = remember { SettingsManager.getAIMode(context) == SettingsManager.AI_MODE_BACKEND }
    val selectedModel = remember { SettingsManager.getBackendModel(context) }
    val backendAuth = remember {
        if (useBackend) {
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
        } else {
            null
        }
    }
    val negotiationManager = remember {
        NegotiationManager(lmManager, repository, karmaManager, backendAuth, selectedModel)
    }

    fun addMessage(text: String, isFromUser: Boolean) {
        messages.add(ChatMessage(text, isFromUser = isFromUser))
        val prefix = if (isFromUser) "User" else "AI"
        SessionLogger.log("$prefix: ${text.take(120)}")
    }

    // Initialize AI and start conversation
    LaunchedEffect(packageName) {
        scope.launch {
            // Auto sign-in + token exchange if backend mode is active and no app token
            if (useBackend && backendAuth != null && !backendAuth.hasToken) {
                try {
                    val signInResult = AuthManager.signIn(context)
                    if (signInResult != null) {
                        if (signInResult.email != null) {
                            ApiKeyManager.saveSignedInEmail(context, signInResult.email)
                        }
                        // Immediately exchange Google token for our own app token
                        val exchanged = backendAuth.exchangeGoogleToken(signInResult.idToken)
                        if (!exchanged) {
                            Toast.makeText(
                                context,
                                "Backend authentication failed. Using on-device model.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            "Google Sign-In failed. Using on-device model.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (_: NoCredentialException) {
                    Toast.makeText(
                        context,
                        "No Google account available. Using on-device model.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // Determine which model will actually be used
            val usingRemote = useBackend && backendAuth != null && backendAuth.hasToken
            val signedInEmail = if (usingRemote) ApiKeyManager.getSignedInEmail(context) else null
            modelLabel = if (usingRemote) {
                val emailSuffix = if (signedInEmail != null) " · $signedInEmail" else ""
                "$selectedModel$emailSuffix"
            } else {
                "On-device (LiteRT-LM)"
            }

            // Only initialize on-device model if we actually need it
            if (!usingRemote) {
                lmManager.initialize()
            }

            if (packageName.isNotEmpty()) {
                // Gatekeeper flow
                SessionLogger.log("AI negotiation started for **$appLabel** via $modelLabel")
                isWaitingForAi = true
                val result = negotiationManager.startGatekeeperNegotiation(packageName, appLabel)
                addMessage(result.responseText, isFromUser = false)
                isWaitingForAi = false
                if (result.accessGranted) {
                    accessGranted = true
                }
            } else {
                // General chat
                SessionLogger.log("AI assistant opened via $modelLabel")
                addMessage(PromptTemplates.GENERAL_CHAT_GREETING, isFromUser = false)
                negotiationManager.startGeneralChat(context)
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
            PackageManagerHelper.launchApp(context, launchTarget)
            negotiationManager.endConversation()
            lmManager.shutdown()
            onAppGranted()
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
        // Top bar with model indicator
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = if (packageName.isNotEmpty()) "Opening $appLabel" else "AI Assistant",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (modelLabel.isNotEmpty()) {
                        Text(
                            text = modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                                    SessionLogger.log("Access granted to **$appLabel**")
                                    accessGranted = true
                                }
                                if (result.launchedPackage.isNotEmpty()) {
                                    val label = PackageManagerHelper.getAppLabel(
                                        context, result.launchedPackage
                                    )
                                    SessionLogger.log("Launched **$label**")
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
