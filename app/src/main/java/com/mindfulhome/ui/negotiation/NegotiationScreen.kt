package com.mindfulhome.ui.negotiation

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.ai.EmbeddingManager
import com.mindfulhome.ai.GatekeeperUsageConfrontation
import com.mindfulhome.ai.LiteRtLmManager
import com.mindfulhome.ai.NegotiationManager
import com.mindfulhome.ai.NegotiationResult
import com.mindfulhome.ai.PromptTemplates
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.AuthManager
import com.mindfulhome.ai.backend.BackendAuthHelper
import androidx.credentials.exceptions.NoCredentialException
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.AppIntent
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.ui.search.SearchOverlay
import com.mindfulhome.util.PackageManagerHelper
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    val isLoading: Boolean = false,
    val loadingText: String = "Thinking...",
)

private fun normalizeLookup(value: String): String {
    return value.lowercase().replace(Regex("[^a-z0-9]"), "")
}

private fun extractLaunchQuery(rawText: String): String {
    val trimmed = rawText.trim()
    if (trimmed.isBlank()) return ""
    val quoted = Regex("\"([^\"]+)\"|'([^']+)'").find(trimmed)
    if (quoted != null) {
        val capture = quoted.groupValues.drop(1).firstOrNull { it.isNotBlank() }
        if (!capture.isNullOrBlank()) return capture.trim()
    }
    val lowered = trimmed.lowercase()
    val markers = listOf("app name is", "app is", "open", "launch")
    for (marker in markers) {
        val idx = lowered.lastIndexOf(marker)
        if (idx >= 0) {
            val candidate = trimmed.substring(idx + marker.length).trim()
            if (candidate.isNotBlank()) return candidate
        }
    }
    return trimmed
}

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
    onOpenDefault: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenKarma: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAppGranted: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hiddenApps by repository.hiddenApps().collectAsState(initial = emptyList())
    val allKarma by repository.allKarma().collectAsState(initial = emptyList())
    val allIntents by repository.allIntents().collectAsState(initial = emptyList())

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
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showSearchOverlay by remember { mutableStateOf(false) }
    var suggestedLaunchApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showLaunchSuggestions by remember { mutableStateOf(false) }
    var isLoadingApps by remember { mutableStateOf(false) }
    var lastLaunchRequestText by remember { mutableStateOf(unlockReason) }
    var conversationNonce by remember { mutableStateOf(0) }
    val hiddenPackages = remember(hiddenApps) { hiddenApps.map { it.packageName }.toSet() }
    val notesByPackage = remember(allKarma) {
        allKarma.associate { karma -> karma.packageName to karma.appNote?.trim().orEmpty() }
    }
    val visibleApps = remember(allApps, hiddenPackages) {
        allApps.filter { it.packageName !in hiddenPackages }
    }

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
                try {
                    val result = AuthManager.signIn(context)
                    if (result?.email != null) {
                        ApiKeyManager.saveSignedInEmail(context, result.email)
                    }
                    result?.idToken
                } catch (e: NoCredentialException) {
                    null
                }
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
                context = context,
                lmManager = lmManager,
                repository = repository,
                karmaManager = karmaManager,
                backendAuth = if (sessionUseBackend) backendAuth else null,
                backendModel = sessionSelectedModel,
            )
        )
    }
    val developerLogsEnabled = SettingsManager.isDeveloperLogsEnabled(context)

    fun logDevBoundary(message: String) {
        if (!developerLogsEnabled) return
        SessionLogger.log(sessionHandle, "[DEV][boundary] $message")
    }

    fun logDevDecision(message: String) {
        if (!developerLogsEnabled) return
        SessionLogger.log(sessionHandle, "[DEV][decision] $message")
    }

    fun summarizeResult(result: NegotiationResult): String {
        val parts = mutableListOf<String>()
        if (result.accessGranted) parts.add("accessGranted=true")
        if (result.extensionMinutes > 0) parts.add("extensionMinutes=${result.extensionMinutes}")
        if (result.launchedPackage.isNotEmpty()) parts.add("launchedPackage=${result.launchedPackage}")
        if (result.suggestedQuery.isNotEmpty()) parts.add("suggestedQuery=${result.suggestedQuery}")
        if (result.showSuggestions) parts.add("showSuggestions=true")
        return "text=\"${result.responseText.take(50)}\", actions=[${parts.joinToString(", ")}]"
    }

    fun addMessage(text: String, isFromUser: Boolean) {
        messages.add(ChatMessage(text, isFromUser = isFromUser))
        val prefix = if (isFromUser) "User" else "AI"
        SessionLogger.log(sessionHandle, "$prefix: ${text.take(120)}")
    }

    suspend fun showQuickLaunchBar(queryText: String) {
        val effectiveQuery = queryText.ifBlank { lastLaunchRequestText }
        SessionLogger.log(
            sessionHandle,
            "App search invoked with query: \"$effectiveQuery\"",
        )
        val apps = if (visibleApps.isEmpty()) {
            logDevDecision("launch_suggestions deferred: visibleApps empty; waiting for installed apps")
            isLoadingApps = true
            val loaded = snapshotFlow { allApps }.first { it.isNotEmpty() }
            isLoadingApps = false
            logDevDecision("launch_suggestions candidates prepared: loadedApps=${loaded.size}, hiddenFiltered=${loaded.count { it.packageName !in hiddenPackages }}")
            loaded.filter { it.packageName !in hiddenPackages }
        } else {
            visibleApps
        }
        val fallbackSuggestions = apps.take(5)
        suggestedLaunchApps = fallbackSuggestions
        showLaunchSuggestions = true
        val rankedWithScores = rankLaunchSuggestionScores(
            requestText = effectiveQuery,
            visibleApps = apps,
            allIntents = allIntents,
        )
        val ranked = rankedWithScores.map { it.first }
        if (showLaunchSuggestions) {
            suggestedLaunchApps = if (ranked.isNotEmpty()) ranked else fallbackSuggestions
            val topScore = rankedWithScores.firstOrNull()?.second
            val secondScore = rankedWithScores.getOrNull(1)?.second
            SessionLogger.log(
                sessionHandle,
                "Launch chooser shown by model tool decision for query \"$effectiveQuery\". " +
                    "Top semantic score=${topScore ?: 0f}, second=${secondScore ?: 0f}.",
            )
            val rankingUsed = ranked.isNotEmpty()
            logDevDecision(
                "launch_strategy=chooser_ui reason=model_called_presentSuggestions " +
                    "(rankingUsed=$rankingUsed) " +
                    "query=${effectiveQuery.take(80)} candidates=${suggestedLaunchApps.map { it.packageName }}"
            )
        }
    }

    fun findExactMatchPackage(queryText: String): String? {
        val normalizedQuery = normalizeLookup(queryText)
        if (normalizedQuery.isBlank()) return null
        return visibleApps.firstOrNull { app ->
            val normalizedLabel = normalizeLookup(app.label)
            val normalizedPackage = normalizeLookup(app.packageName)
            val normalizedShortPackage = normalizeLookup(app.packageName.substringAfterLast('.'))
            normalizedLabel == normalizedQuery ||
                normalizedPackage == normalizedQuery ||
                normalizedShortPackage == normalizedQuery
        }?.packageName
    }

    suspend fun resolveSuggestedAppsTool(result: NegotiationResult, depth: Int = 0): NegotiationResult {
        if (depth >= 3) {
            logDevDecision("tool_followup_skipped reason=max_depth_reached")
            return result.copy(
                responseText = "I couldn't find any matching apps. Could you be more specific?",
                suggestedQuery = "",
                showSuggestions = false
            )
        }
        if (result.launchedPackage.isNotEmpty()) {
            logDevDecision("tool_followup_skipped reason=shortcut_already_selected_by_chat launchedPackage=${result.launchedPackage}")
            return result
        }
        val suggestedQuery = result.suggestedQuery.trim()
        if (suggestedQuery.isBlank()) {
            logDevDecision("tool_followup_skipped reason=no_suggested_query_from_chat")
            return result
        }

        val ranked = rankLaunchSuggestions(
            requestText = suggestedQuery,
            visibleApps = visibleApps,
            allIntents = allIntents,
        )

        val toolResultMessage = if (ranked.isEmpty()) {
            logDevDecision("tool_result empty for query=$suggestedQuery (depth=$depth)")
            "Tool result for suggestApps(query=\"$suggestedQuery\"):\nNo apps matched this query. Try a different search query with suggestApps, or ask the user for clarification."
        } else {
            val candidates = ranked.take(5)
            logDevBoundary("app_to_chat tool_result suggestApps query=$suggestedQuery candidates=${candidates.map { it.packageName }}")
            buildString {
                append("Tool result for suggestApps(query=\"")
                append(suggestedQuery)
                append("\"):\n")
                candidates.forEachIndexed { index, app ->
                    val appNote = notesByPackage[app.packageName].orEmpty()
                    val noteFlag = if (PromptTemplates.requiresExtraConfirmation(appNote)) "true" else "false"
                    append(index + 1)
                    append(". ")
                    append(app.label)
                    append(" (")
                    append(app.packageName)
                    append(")")
                    if (appNote.isNotBlank()) {
                        append(" note=\"")
                        append(appNote.replace("\"", "\\\""))
                        append("\"")
                    }
                    append(" needs_extra_confirmation=")
                    append(noteFlag)
                    append('\n')
                }
                append("Choose exactly one next action:\n")
                append("- launchApp(packageName) if confident.\n")
                append("- presentSuggestions(query) to show options.\n")
                append("- no tool call if you want to continue conversation.\n")
                append("You MUST consider app notes and needs_extra_confirmation flags before deciding. ")
                append("If a note indicates risk/avoidance, do not launch immediately; ask for confirmation or push back first.")
            }
        }

        logDevBoundary("app_to_chat payload reply toolResultMessageChars=${toolResultMessage.length}")
        val followUp = negotiationManager.reply(toolResultMessage)
        logDevBoundary("chat_to_app payload reply_result ${summarizeResult(followUp)}")
        
        if (followUp.suggestedQuery.isNotBlank() && !followUp.showSuggestions) {
            return resolveSuggestedAppsTool(followUp, depth + 1)
        }

        val responseText = followUp.responseText.ifBlank { result.responseText }
        val queryForUi = followUp.suggestedQuery.ifBlank { suggestedQuery }
        return followUp.copy(
            responseText = responseText,
            suggestedQuery = queryForUi,
        )
    }

    LaunchedEffect(Unit) {
        allApps = PackageManagerHelper.getInstalledApps(context)
    }

    // Initialize AI and start conversation
    LaunchedEffect(packageName, conversationNonce) {
        messages.clear()
        userInput = ""
        isWaitingForAi = false
        accessGranted = false
        launchTarget = ""
        showSearchOverlay = false
        showLaunchSuggestions = false
        suggestedLaunchApps = emptyList()
        isLoadingApps = false
        lastLaunchRequestText = extractLaunchQuery(unlockReason)

        // If remote is configured but no token is present, trigger sign-in now.
        // AuthManager uses the Credential Manager bottom sheet — no navigation side effects.
        if (sessionUseBackend && !backendAuth.hasToken) {
            modelLabel = sessionSelectedModel
            try {
                val result = AuthManager.signIn(context)
                if (result != null) {
                    if (result.email != null) {
                        ApiKeyManager.saveSignedInEmail(context, result.email)
                    }
                    backendAuth.exchangeGoogleToken(result.idToken)
                }
            } catch (e: NoCredentialException) {
                addMessage(
                    "Sign-in failed: no Google account is available on this device.",
                    isFromUser = false
                )
                return@LaunchedEffect
            }
            if (!backendAuth.hasToken) {
                addMessage(
                    "Sign-in was cancelled or failed. You can retry from Settings.",
                    isFromUser = false
                )
                return@LaunchedEffect
            }
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
            val usageConfrontation = SettingsManager
                .getLastTimerUsageSnapshot(context)
                ?.let { snapshot ->
                    val rankedMatch = snapshot.topApps
                        .withIndex()
                        .firstOrNull { (_, app) -> app.packageName == packageName }
                        ?: return@let null
                    GatekeeperUsageConfrontation(
                        capturedAtMs = snapshot.capturedAtMs,
                        rankInTopApps = rankedMatch.index + 1,
                        foregroundTimeMs = rankedMatch.value.foregroundTimeMs,
                        longestSessionsMsDesc = rankedMatch.value.longestSessionsMsDesc,
                    )
                }
            if (usageConfrontation != null) {
                SessionLogger.log(
                    sessionHandle,
                    "Gatekeeper confrontation armed for **$appLabel** from last timer snapshot (rank #${usageConfrontation.rankInTopApps})",
                )
            }
            val result = negotiationManager.startGatekeeperNegotiation(
                packageName = packageName,
                appName = appLabel,
                focusModeActive = focusModeActive,
                usageConfrontation = usageConfrontation,
            )
            logDevBoundary("chat_to_app gatekeeper_start_result ${summarizeResult(result)}")
            addMessage(result.responseText, isFromUser = false)
            isWaitingForAi = false
            if (result.accessGranted) {
                accessGranted = true
                logDevDecision("gatekeeper_result access_granted_immediately=true")
            }
        } else {
            // General chat
            SessionLogger.log(sessionHandle, "AI assistant opened via $modelLabel")
            if (unlockReason.isNotEmpty()) {
                // Reason provided at timer screen — skip the generic greeting
                // and feed it as the first user message so the AI responds directly
                addMessage(unlockReason, isFromUser = true)
                val allVisible = if (visibleApps.isEmpty()) {
                    snapshotFlow { allApps }.first { it.isNotEmpty() }
                        .filter { it.packageName !in hiddenPackages }
                } else visibleApps
                val appsForPrompt = rankLaunchSuggestions(
                    requestText = lastLaunchRequestText,
                    visibleApps = allVisible,
                    allIntents = allIntents,
                    limit = 20,
                ).map { it.label to it.packageName }
                negotiationManager.startGeneralChat(context, appsForPrompt)
                isWaitingForAi = true
                val firstResult = negotiationManager.reply(unlockReason)
                logDevBoundary("chat_to_app payload reply_result ${summarizeResult(firstResult)}")
                val result = resolveSuggestedAppsTool(firstResult)
                addMessage(result.responseText, isFromUser = false)
                isWaitingForAi = false
                if (result.suggestedQuery.isNotBlank()) {
                    lastLaunchRequestText = result.suggestedQuery
                }
                if (result.launchedPackage.isNotEmpty()) {
                    val label = PackageManagerHelper.getAppLabel(
                        context, result.launchedPackage
                    )
                    SessionLogger.log(sessionHandle, "Launched **$label**")
                    launchTarget = result.launchedPackage
                    logDevDecision("launch_strategy=direct_from_chat_tool target=${result.launchedPackage}")
                } else if (result.showSuggestions) {
                    logDevDecision("launch_strategy=chooser_ui_from_chat reason=model_called_presentSuggestions")
                    showQuickLaunchBar(
                        result.suggestedQuery.ifBlank { lastLaunchRequestText }
                    )
                } else {
                    logDevDecision("launch_strategy=continue_chat_from_model reason=no_action_tool_called")
                }
            } else {
                addMessage(PromptTemplates.GENERAL_CHAT_GREETING, isFromUser = false)
                val allVisible = if (visibleApps.isEmpty()) {
                    snapshotFlow { allApps }.first { it.isNotEmpty() }
                        .filter { it.packageName !in hiddenPackages }
                } else visibleApps
                val appsForPrompt = rankLaunchSuggestions(
                    requestText = lastLaunchRequestText,
                    visibleApps = allVisible,
                    allIntents = allIntents,
                    limit = 20,
                ).map { it.label to it.packageName }
                negotiationManager.startGeneralChat(context, appsForPrompt)
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
                    showLaunchSuggestions = false
                    suggestedLaunchApps = emptyList()
                negotiationManager.endConversation()
                lmManager.shutdown()
                onAppGranted()
            } else {
                showQuickLaunchBar(lastLaunchRequestText.ifBlank {
                    PackageManagerHelper.getAppLabel(context, targetPackage)
                })
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
            OutlinedButton(onClick = onTimerClick) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to timer",
                    tint = MaterialTheme.colorScheme.onBackground
                )
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground
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
                    contentDescription = "Home",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

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
                    ChatBubble(ChatMessage("", isFromUser = false, isLoading = true, loadingText = "Thinking..."))
                }
            }

            if (isLoadingApps) {
                item {
                    ChatBubble(ChatMessage("", isFromUser = false, isLoading = true, loadingText = "Loading..."))
                }
            }

            if (showLaunchSuggestions) {
                item {
                    LaunchSuggestionsBubble(
                        apps = suggestedLaunchApps,
                        onAppClick = { app ->
                            showLaunchSuggestions = false
                            suggestedLaunchApps = emptyList()
                            launchTarget = app.packageName
                        },
                        onSearchClick = { showSearchOverlay = true },
                    )
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
                            showLaunchSuggestions = false
                            suggestedLaunchApps = emptyList()
                            lastLaunchRequestText = extractLaunchQuery(input)
                            addMessage(input, isFromUser = true)

                            scope.launch {
                                isWaitingForAi = true
                                val firstResult = negotiationManager.reply(input)
                                logDevBoundary("chat_to_app payload reply_result ${summarizeResult(firstResult)}")
                                val result = resolveSuggestedAppsTool(firstResult)
                                addMessage(result.responseText, isFromUser = false)
                                isWaitingForAi = false
                                if (result.suggestedQuery.isNotBlank()) {
                                    lastLaunchRequestText = result.suggestedQuery
                                }

                                if (result.accessGranted) {
                                    SessionLogger.log(sessionHandle, "Access granted to **$appLabel**")
                                    accessGranted = true
                                    logDevDecision("gatekeeper_result access_granted=true")
                                }
                                if (result.launchedPackage.isNotEmpty()) {
                                    val label = PackageManagerHelper.getAppLabel(
                                        context, result.launchedPackage
                                    )
                                    SessionLogger.log(sessionHandle, "Launched **$label**")
                                    launchTarget = result.launchedPackage
                                    logDevDecision("launch_strategy=direct_from_chat_tool target=${result.launchedPackage}")
                                } else if (packageName.isEmpty() && result.showSuggestions) {
                                    logDevDecision("launch_strategy=chooser_ui_from_chat reason=model_called_presentSuggestions")
                                    showQuickLaunchBar(
                                        result.suggestedQuery.ifBlank { lastLaunchRequestText }
                                    )
                                } else if (packageName.isEmpty()) {
                                    logDevDecision("launch_strategy=continue_chat_from_model reason=no_action_tool_called")
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

    SearchOverlay(
        apps = visibleApps,
        visible = showSearchOverlay,
        onAppClick = { app ->
            showSearchOverlay = false
            showLaunchSuggestions = false
            suggestedLaunchApps = emptyList()
            launchTarget = app.packageName
        },
        onDismiss = { showSearchOverlay = false },
    )

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
                            context = context,
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

private suspend fun rankLaunchSuggestions(
    requestText: String,
    visibleApps: List<AppInfo>,
    allIntents: List<AppIntent>,
    limit: Int = 5,
): List<AppInfo> = rankLaunchSuggestionScores(
    requestText = requestText,
    visibleApps = visibleApps,
    allIntents = allIntents,
    limit = limit,
).map { it.first }

private suspend fun rankLaunchSuggestionScores(
    requestText: String,
    visibleApps: List<AppInfo>,
    allIntents: List<AppIntent>,
    limit: Int = 5,
): List<Pair<AppInfo, Float>> = withContext(Dispatchers.Default) {
    if (visibleApps.isEmpty()) return@withContext emptyList()
    if (requestText.isBlank()) {
        return@withContext visibleApps.take(limit).map { it to 0f }
    }
    val intentsByPkg = allIntents.groupBy { it.packageName }
    val appTexts = visibleApps.map { app ->
        val pastIntents = intentsByPkg[app.packageName]
            ?.joinToString(" ") { it.intentText } ?: ""
        val aliases = launchAliasesForApp(app)
        val pkgWords = app.packageName.split('.')
            .filter { it.length > 2 && it !in setOf("com", "org", "net", "android", "app") }
            .joinToString(" ")
        app.packageName to "${app.label} $aliases $pkgWords $pastIntents".trim()
    }
    val ranked = EmbeddingManager.rankApps(requestText, appTexts)
    ranked.take(limit).mapNotNull { (pkg, score) ->
        visibleApps.find { it.packageName == pkg }?.let { it to score }
    }
}

private fun launchAliasesForApp(app: AppInfo): String {
    val pkg = app.packageName.lowercase()
    val label = app.label.lowercase()
    val aliases = mutableListOf<String>()
    if (pkg == "com.twitter.android" || label == "x") {
        aliases += "x twitter everything app the everything app"
    }
    return aliases.joinToString(" ")
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
                        text = message.loadingText,
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
                    text = "Pick an app to launch:",
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
                                    contentDescription = "Search apps",
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
