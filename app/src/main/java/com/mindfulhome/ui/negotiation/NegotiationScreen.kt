package com.mindfulhome.ui.negotiation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.exceptions.NoCredentialException
import com.mindfulhome.ai.EmbeddingManager
import com.mindfulhome.ai.GatekeeperUsageConfrontation
import com.mindfulhome.ai.LmPlaygroundManager
import com.mindfulhome.ai.NegotiationManager
import com.mindfulhome.ui.settings.onDeviceModelLabel
import com.mindfulhome.ai.NegotiationResult
import com.mindfulhome.ai.PromptTemplates
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.AuthManager
import com.mindfulhome.ai.backend.BackendAuthHelper
import com.mindfulhome.data.AppIntent
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.model.TimerState
import com.mindfulhome.service.TimerService
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

/**
 * Chat UI for AI gates (focus time + app gatekeeper), expire→extend (nudge script), and general assistant.
 *
 * @param extendGate When true, uses the timer-expired nudge script in this same chat UI
 *  (not the notification). [packageName] is the app being extended.
 *
 * @see docs.gates.md Goals, Proceed button, round rules.
 * @see docs.navigation-map.md Entry: `assistant`, `negotiate/{packageName}`, or `extend/{packageName}`.
 */
@Composable
fun NegotiationScreen(
    packageName: String,
    unlockReason: String = "",
    durationMinutes: Int,
    sessionHandle: SessionLogger.SessionHandle?,
    repository: AppRepository,
    karmaManager: KarmaManager,
    extendGate: Boolean = false,
    onTimerClick: () -> Unit = {},
    onOpenDefault: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenKarma: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAppGranted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val host = rememberNegotiationHost(
        packageName = packageName,
        unlockReason = unlockReason,
        durationMinutes = durationMinutes,
        sessionHandle = sessionHandle,
        repository = repository,
        karmaManager = karmaManager,
        extendGate = extendGate,
        onTimerClick = onTimerClick,
        onOpenDefault = onOpenDefault,
        onOpenLogs = onOpenLogs,
        onOpenKarma = onOpenKarma,
        onOpenSettings = onOpenSettings,
        onAppGranted = onAppGranted,
        onDismiss = onDismiss,
    )
    NegotiationScreenEffects(host)
    NegotiationScreenContent(host)
    NegotiationScreenOverlays(host)
}

private class NegotiationHost(
    val context: Context,
    val scope: kotlinx.coroutines.CoroutineScope,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val packageName: String,
    val unlockReason: String,
    val durationMinutes: Int,
    val sessionHandle: SessionLogger.SessionHandle?,
    val repository: AppRepository,
    val karmaManager: KarmaManager,
    val onTimerClick: () -> Unit,
    val onOpenDefault: () -> Unit,
    val onOpenLogs: () -> Unit,
    val onOpenKarma: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onAppGranted: () -> Unit,
    val onDismiss: () -> Unit,
    val appLabel: String,
    val focusModeActive: Boolean,
    val negotiationMode: NegotiationMode,
    val session: NegotiationSession,
    val hiddenPackages: Set<String>,
    val notesByPackage: Map<String, String>,
    val visibleApps: List<AppInfo>,
    val allIntents: List<AppIntent>,
)

@Composable
private fun rememberNegotiationHost(
    packageName: String,
    unlockReason: String,
    durationMinutes: Int,
    sessionHandle: SessionLogger.SessionHandle?,
    repository: AppRepository,
    karmaManager: KarmaManager,
    extendGate: Boolean,
    onTimerClick: () -> Unit,
    onOpenDefault: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenKarma: () -> Unit,
    onOpenSettings: () -> Unit,
    onAppGranted: () -> Unit,
    onDismiss: () -> Unit,
): NegotiationHost {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hiddenApps by repository.hiddenApps().collectAsState(initial = emptyList())
    val allKarma by repository.allKarma().collectAsState(initial = emptyList())
    val allIntents by repository.allIntents().collectAsState(initial = emptyList())
    val appLabel = remember(packageName) { resolveAppLabel(context, packageName) }
    val focusModeActive = remember { SettingsManager.isFocusTimeActiveNow(context) }
    val negotiationMode = remember(packageName, extendGate, focusModeActive) {
        classifyNegotiationMode(packageName, extendGate, focusModeActive)
    }
    val session = rememberNegotiationSession(context, repository, karmaManager, unlockReason)
    val hiddenPackages = remember(hiddenApps) { hiddenApps.map { it.packageName }.toSet() }
    val notesByPackage = remember(allKarma) {
        allKarma.associate { karma -> karma.packageName to karma.appNote?.trim().orEmpty() }
    }
    val visibleApps = remember(session.allApps, hiddenPackages) {
        session.allApps.filter { it.packageName !in hiddenPackages }
    }
    return NegotiationHost(
        context = context,
        scope = scope,
        listState = listState,
        packageName = packageName,
        unlockReason = unlockReason,
        durationMinutes = durationMinutes,
        sessionHandle = sessionHandle,
        repository = repository,
        karmaManager = karmaManager,
        onTimerClick = onTimerClick,
        onOpenDefault = onOpenDefault,
        onOpenLogs = onOpenLogs,
        onOpenKarma = onOpenKarma,
        onOpenSettings = onOpenSettings,
        onAppGranted = onAppGranted,
        onDismiss = onDismiss,
        appLabel = appLabel,
        focusModeActive = focusModeActive,
        negotiationMode = negotiationMode,
        session = session,
        hiddenPackages = hiddenPackages,
        notesByPackage = notesByPackage,
        visibleApps = visibleApps,
        allIntents = allIntents,
    )
}

private fun resolveAppLabel(context: Context, packageName: String): String {
    if (packageName.isEmpty()) return "an app"
    return PackageManagerHelper.getAppLabel(context, packageName)
}

private fun shouldShowLaunchSuggestions(session: NegotiationSession, mode: NegotiationMode): Boolean {
    return session.showLaunchSuggestions && !mode.isFocusGate && !mode.isExtendGate
}

private fun canProceedFromGate(session: NegotiationSession, mode: NegotiationMode): Boolean {
    return mode.isGateFlow && session.accessGranted
}

private fun isChatInputSendEnabled(session: NegotiationSession): Boolean {
    return session.userInput.isNotBlank() && !session.isWaitingForAi
}

@Composable
private fun NegotiationScreenContent(host: NegotiationHost) {
    val session = host.session
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        NegotiationTopBar(
            durationMinutes = host.durationMinutes,
            title = negotiationScreenTitle(host.packageName, host.appLabel, host.negotiationMode.isFocusGate),
            modelLabel = session.modelLabel,
            onTimerClick = host.onTimerClick,
            onOpenDefault = host.onOpenDefault,
            onOpenLogs = host.onOpenLogs,
            onOpenKarma = host.onOpenKarma,
            onOpenSettings = host.onOpenSettings,
            onModelLabelClick = {
                session.pickerUseBackend = session.sessionUseBackend
                session.pickerSelectedModel = session.sessionSelectedModel
                session.showModelPicker = true
            },
            onBack = {
                session.negotiationManager.endConversation()
                session.lmManager.shutdown()
                host.onDismiss()
            },
        )

        ChatMessageList(
            messages = session.messages,
            listState = host.listState,
            isWaitingForAi = session.isWaitingForAi,
            isLoadingApps = session.isLoadingApps,
            showLaunchSuggestions = shouldShowLaunchSuggestions(session, host.negotiationMode),
            suggestedLaunchApps = session.suggestedLaunchApps,
            onSuggestionClick = { app ->
                session.showLaunchSuggestions = false
                session.suggestedLaunchApps = emptyList()
                session.launchTarget = app.packageName
            },
            onSearchClick = { session.showSearchOverlay = true },
            modifier = Modifier.weight(1f),
        )

        NegotiationFooter(host)
    }
}

@Composable
private fun NegotiationFooter(host: NegotiationHost) {
    val session = host.session
    Column(modifier = Modifier.fillMaxWidth()) {
        if (canProceedFromGate(session, host.negotiationMode)) {
            GateProceedButton(
                label = gateProceedLabel(
                    isExtendGate = host.negotiationMode.isExtendGate,
                    grantedExtensionMinutes = session.grantedExtensionMinutes,
                    packageName = host.packageName,
                    appLabel = host.appLabel,
                ),
                onClick = {
                    proceedAfterGate(
                        session = session,
                        context = host.context,
                        packageName = host.packageName,
                        appLabel = host.appLabel,
                        sessionHandle = host.sessionHandle,
                        isExtendGate = host.negotiationMode.isExtendGate,
                        isFocusGate = host.negotiationMode.isFocusGate,
                        onAppGranted = host.onAppGranted,
                    )
                },
            )
        }

        if (session.launchTarget.isEmpty()) {
            ChatInputBar(
                userInput = session.userInput,
                onUserInputChange = { session.userInput = it },
                enabled = !session.isWaitingForAi,
                sendEnabled = isChatInputSendEnabled(session),
                onSend = { enqueueUserSend(host) },
            )
        }
    }
}

private fun enqueueUserSend(host: NegotiationHost) {
    val session = host.session
    val input = session.userInput.trim()
    if (input.isBlank() || session.isWaitingForAi) return
    session.userInput = ""
    session.showLaunchSuggestions = false
    session.suggestedLaunchApps = emptyList()
    session.lastLaunchRequestText = extractLaunchQuery(input)
    addChatMessage(session, host.sessionHandle, input, isFromUser = true)
    host.scope.launch {
        handleUserSend(
            session = session,
            context = host.context,
            packageName = host.packageName,
            appLabel = host.appLabel,
            sessionHandle = host.sessionHandle,
            negotiationMode = host.negotiationMode,
            visibleApps = host.visibleApps,
            hiddenPackages = host.hiddenPackages,
            notesByPackage = host.notesByPackage,
            allIntents = host.allIntents,
            input = input,
        )
    }
}

@Composable
private fun NegotiationScreenOverlays(host: NegotiationHost) {
    val session = host.session
    SearchOverlay(
        apps = host.visibleApps,
        visible = session.showSearchOverlay,
        onAppClick = { app ->
            session.showSearchOverlay = false
            session.showLaunchSuggestions = false
            session.suggestedLaunchApps = emptyList()
            session.launchTarget = app.packageName
        },
        onDismiss = { session.showSearchOverlay = false },
    )
    if (!session.showModelPicker) return
    SessionModelPickerDialog(
        pickerUseBackend = session.pickerUseBackend,
        pickerSelectedModel = session.pickerSelectedModel,
        onPickerUseBackendChange = { session.pickerUseBackend = it },
        onPickerSelectedModelChange = { session.pickerSelectedModel = it },
        onDismiss = { session.showModelPicker = false },
        onApply = {
            applySessionModelPicker(
                session = session,
                context = host.context,
                repository = host.repository,
                karmaManager = host.karmaManager,
            )
        },
    )
}

@Composable
private fun rememberNegotiationSession(
    context: Context,
    repository: AppRepository,
    karmaManager: KarmaManager,
    unlockReason: String,
): NegotiationSession {
    val lmManager = remember { LmPlaygroundManager(context) }
    val useBackend = remember { SettingsManager.getAIMode(context) == SettingsManager.AI_MODE_BACKEND }
    val selectedModel = remember { SettingsManager.getBackendModel(context) }
    val backendAuth = remember {
        BackendAuthHelper(
            signInForExchange = {
                try {
                    val result = AuthManager.signInSilent(context)
                        ?: AuthManager.signIn(context)
                    if (result?.email != null) {
                        ApiKeyManager.saveSignedInEmail(context, result.email)
                    }
                    result?.idToken
                } catch (_: NoCredentialException) {
                    null
                }
            },
            getSessionToken = { ApiKeyManager.getSessionToken(context) },
            saveSessionToken = { token, exp ->
                ApiKeyManager.saveSessionToken(context, token, exp)
            },
            clearSessionToken = { ApiKeyManager.clearSessionToken(context) },
            isSessionExpiringSoon = { ApiKeyManager.isSessionExpiringSoon(context) },
        )
    }
    val developerLogsEnabled = SettingsManager.isDeveloperLogsEnabled(context)
    return remember(lmManager, backendAuth, useBackend, selectedModel) {
        NegotiationSession(
            lmManager = lmManager,
            backendAuth = backendAuth,
            developerLogsEnabled = developerLogsEnabled,
            initialUseBackend = useBackend,
            initialSelectedModel = selectedModel,
            initialUnlockReason = unlockReason,
            repository = repository,
            karmaManager = karmaManager,
            context = context,
        )
    }
}

@Composable
private fun NegotiationScreenEffects(host: NegotiationHost) {
    val session = host.session
    LaunchedEffect(Unit) {
        session.allApps = PackageManagerHelper.getInstalledApps(host.context)
    }

    LaunchedEffect(host.packageName, session.conversationNonce) {
        runConversationStart(
            session = session,
            context = host.context,
            packageName = host.packageName,
            unlockReason = host.unlockReason,
            durationMinutes = host.durationMinutes,
            sessionHandle = host.sessionHandle,
            appLabel = host.appLabel,
            focusModeActive = host.focusModeActive,
            negotiationMode = host.negotiationMode,
            visibleApps = host.visibleApps,
            hiddenPackages = host.hiddenPackages,
            notesByPackage = host.notesByPackage,
            allIntents = host.allIntents,
        )
    }

    LaunchedEffect(session.messages.size) {
        if (session.messages.isNotEmpty()) {
            host.listState.animateScrollToItem(session.messages.size - 1)
        }
    }

    LaunchedEffect(session.launchTarget) {
        handleLaunchTargetEffect(
            session = session,
            context = host.context,
            onAppGranted = host.onAppGranted,
            visibleApps = host.visibleApps,
            hiddenPackages = host.hiddenPackages,
            allIntents = host.allIntents,
            sessionHandle = host.sessionHandle,
        )
    }
}

private class NegotiationSession(
    val lmManager: LmPlaygroundManager,
    val backendAuth: BackendAuthHelper,
    val developerLogsEnabled: Boolean,
    initialUseBackend: Boolean,
    initialSelectedModel: String,
    initialUnlockReason: String,
    repository: AppRepository,
    karmaManager: KarmaManager,
    context: Context,
) {
    val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()
    var userInput by mutableStateOf("")
    var isWaitingForAi by mutableStateOf(false)
    var accessGranted by mutableStateOf(false)
    var grantedExtensionMinutes by mutableStateOf(0)
    var launchTarget by mutableStateOf("")
    var allApps by mutableStateOf<List<AppInfo>>(emptyList())
    var showSearchOverlay by mutableStateOf(false)
    var suggestedLaunchApps by mutableStateOf<List<AppInfo>>(emptyList())
    var showLaunchSuggestions by mutableStateOf(false)
    var isLoadingApps by mutableStateOf(false)
    var lastLaunchRequestText by mutableStateOf(initialUnlockReason)
    var conversationNonce by mutableStateOf(0)
    var sessionUseBackend by mutableStateOf(initialUseBackend)
    var sessionSelectedModel by mutableStateOf(initialSelectedModel)
    var showModelPicker by mutableStateOf(false)
    var pickerUseBackend by mutableStateOf(initialUseBackend)
    var pickerSelectedModel by mutableStateOf(initialSelectedModel)
    var modelLabel by mutableStateOf(
        if (initialUseBackend) {
            "$initialSelectedModel (checking auth...)"
        } else {
            onDeviceModelLabel(LmPlaygroundManager.isInstalled(context))
        },
    )
    var negotiationManager by mutableStateOf(
        NegotiationManager(
            context = context,
            lmManager = lmManager,
            repository = repository,
            karmaManager = karmaManager,
            backendAuth = if (initialUseBackend) backendAuth else null,
            backendModel = initialSelectedModel,
        ),
    )
}

private fun NegotiationSession.logDevBoundary(sessionHandle: SessionLogger.SessionHandle?, message: String) {
    if (!developerLogsEnabled) return
    SessionLogger.log(sessionHandle, "[DEV][boundary] $message")
}

private fun NegotiationSession.logDevDecision(sessionHandle: SessionLogger.SessionHandle?, message: String) {
    if (!developerLogsEnabled) return
    SessionLogger.log(sessionHandle, "[DEV][decision] $message")
}

private fun summarizeResult(result: NegotiationResult): String {
    val parts = mutableListOf<String>()
    if (result.accessGranted) parts.add("accessGranted=true")
    if (result.extensionMinutes > 0) parts.add("extensionMinutes=${result.extensionMinutes}")
    if (result.launchedPackage.isNotEmpty()) parts.add("launchedPackage=${result.launchedPackage}")
    if (result.suggestedQuery.isNotEmpty()) parts.add("suggestedQuery=${result.suggestedQuery}")
    if (result.showSuggestions) parts.add("showSuggestions=true")
    return "text=\"${result.responseText.take(50)}\", actions=[${parts.joinToString(", ")}]"
}

private fun addChatMessage(
    session: NegotiationSession,
    sessionHandle: SessionLogger.SessionHandle?,
    text: String,
    isFromUser: Boolean,
) {
    session.messages.add(ChatMessage(text, isFromUser = isFromUser))
    val prefix = if (isFromUser) "User" else "AI"
    SessionLogger.log(sessionHandle, "$prefix: ${text.take(120)}")
}

private fun proceedAfterGate(
    session: NegotiationSession,
    context: Context,
    packageName: String,
    appLabel: String,
    sessionHandle: SessionLogger.SessionHandle?,
    isExtendGate: Boolean,
    isFocusGate: Boolean,
    onAppGranted: () -> Unit,
) {
    if (isExtendGate) {
        val minutes = session.grantedExtensionMinutes.coerceAtLeast(1)
        SessionLogger.log(
            sessionHandle,
            "Extend gate passed — **+$minutes min** for **$appLabel**",
        )
        TimerService.extend(context, minutes)
        session.negotiationManager.endConversation()
        session.lmManager.shutdown()
        onAppGranted()
        return
    }
    if (packageName.isNotEmpty()) {
        SessionLogger.log(sessionHandle, "User proceeded to **$appLabel**")
    } else if (isFocusGate) {
        SessionLogger.log(sessionHandle, "Focus time gate passed — proceeding to session")
    } else {
        return
    }
    session.negotiationManager.endConversation()
    session.lmManager.shutdown()
    onAppGranted()
}

private fun applyNegotiationOutcome(
    session: NegotiationSession,
    negotiationMode: NegotiationMode,
    result: NegotiationResult,
    packageName: String,
    appLabel: String,
    sessionHandle: SessionLogger.SessionHandle?,
) {
    when (val outcome = applyGateOutcome(negotiationMode, result)) {
        is GateOutcome.Granted -> applyGrantedOutcome(
            session = session,
            negotiationMode = negotiationMode,
            outcome = outcome,
            packageName = packageName,
            appLabel = appLabel,
            sessionHandle = sessionHandle,
        )
        GateOutcome.NoChange -> Unit
    }
}

private fun applyGrantedOutcome(
    session: NegotiationSession,
    negotiationMode: NegotiationMode,
    outcome: GateOutcome.Granted,
    packageName: String,
    appLabel: String,
    sessionHandle: SessionLogger.SessionHandle?,
) {
    if (negotiationMode.isExtendGate) {
        session.grantedExtensionMinutes = outcome.extensionMinutes
        session.accessGranted = true
        SessionLogger.log(
            sessionHandle,
            "Extend offered: **+${outcome.extensionMinutes} min** for **$appLabel**",
        )
        session.logDevDecision(sessionHandle, "extend_gate_result extensionMinutes=${outcome.extensionMinutes}")
        return
    }
    if (packageName.isNotEmpty()) {
        SessionLogger.log(sessionHandle, "Access granted to **$appLabel**")
        session.logDevDecision(sessionHandle, "gatekeeper_result access_granted=true")
    } else if (negotiationMode.isFocusGate) {
        SessionLogger.log(sessionHandle, "Focus time gate passed")
        session.logDevDecision(sessionHandle, "focus_gate_result access_granted=true")
    }
    session.accessGranted = true
}

private suspend fun showQuickLaunchBar(
    session: NegotiationSession,
    sessionHandle: SessionLogger.SessionHandle?,
    queryText: String,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
    allIntents: List<AppIntent>,
) {
    val effectiveQuery = queryText.ifBlank { session.lastLaunchRequestText }
    SessionLogger.log(
        sessionHandle,
        "App search invoked with query: \"$effectiveQuery\"",
    )
    val apps = resolveVisibleAppsForLaunch(
        session = session,
        sessionHandle = sessionHandle,
        visibleApps = visibleApps,
        hiddenPackages = hiddenPackages,
    )
    val fallbackSuggestions = apps.take(5)
    session.suggestedLaunchApps = fallbackSuggestions
    session.showLaunchSuggestions = true
    val rankedWithScores = rankLaunchSuggestionScores(
        requestText = effectiveQuery,
        visibleApps = apps,
        allIntents = allIntents,
    )
    val ranked = rankedWithScores.map { it.first }
    if (!session.showLaunchSuggestions) return
    session.suggestedLaunchApps = if (ranked.isNotEmpty()) ranked else fallbackSuggestions
    val topScore = rankedWithScores.firstOrNull()?.second
    val secondScore = rankedWithScores.getOrNull(1)?.second
    SessionLogger.log(
        sessionHandle,
        "Launch chooser shown by model tool decision for query \"$effectiveQuery\". " +
            "Top semantic score=${topScore ?: 0f}, second=${secondScore ?: 0f}.",
    )
    val rankingUsed = ranked.isNotEmpty()
    session.logDevDecision(
        sessionHandle,
        "launch_strategy=chooser_ui reason=model_called_presentSuggestions " +
            "(rankingUsed=$rankingUsed) " +
            "query=${effectiveQuery.take(80)} candidates=${session.suggestedLaunchApps.map { it.packageName }}",
    )
}

private suspend fun resolveVisibleAppsForLaunch(
    session: NegotiationSession,
    sessionHandle: SessionLogger.SessionHandle?,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
): List<AppInfo> {
    if (visibleApps.isNotEmpty()) return visibleApps
    session.logDevDecision(
        sessionHandle,
        "launch_suggestions deferred: visibleApps empty; waiting for installed apps",
    )
    session.isLoadingApps = true
    val loaded = snapshotFlow { session.allApps }.first { it.isNotEmpty() }
    session.isLoadingApps = false
    session.logDevDecision(
        sessionHandle,
        "launch_suggestions candidates prepared: loadedApps=${loaded.size}, " +
            "hiddenFiltered=${loaded.count { it.packageName !in hiddenPackages }}",
    )
    return loaded.filter { it.packageName !in hiddenPackages }
}

private suspend fun resolveSuggestedAppsTool(
    session: NegotiationSession,
    sessionHandle: SessionLogger.SessionHandle?,
    result: NegotiationResult,
    visibleApps: List<AppInfo>,
    notesByPackage: Map<String, String>,
    allIntents: List<AppIntent>,
    depth: Int = 0,
): NegotiationResult {
    if (depth >= 3) {
        session.logDevDecision(sessionHandle, "tool_followup_skipped reason=max_depth_reached")
        return result.copy(
            responseText = "I couldn't find any matching apps. Could you be more specific?",
            suggestedQuery = "",
            showSuggestions = false,
        )
    }
    if (result.launchedPackage.isNotEmpty()) {
        session.logDevDecision(
            sessionHandle,
            "tool_followup_skipped reason=shortcut_already_selected_by_chat " +
                "launchedPackage=${result.launchedPackage}",
        )
        return result
    }
    val suggestedQuery = result.suggestedQuery.trim()
    if (suggestedQuery.isBlank()) {
        session.logDevDecision(sessionHandle, "tool_followup_skipped reason=no_suggested_query_from_chat")
        return result
    }

    return followUpSuggestedAppsTool(
        session = session,
        sessionHandle = sessionHandle,
        result = result,
        suggestedQuery = suggestedQuery,
        visibleApps = visibleApps,
        notesByPackage = notesByPackage,
        allIntents = allIntents,
        depth = depth,
    )
}

private suspend fun followUpSuggestedAppsTool(
    session: NegotiationSession,
    sessionHandle: SessionLogger.SessionHandle?,
    result: NegotiationResult,
    suggestedQuery: String,
    visibleApps: List<AppInfo>,
    notesByPackage: Map<String, String>,
    allIntents: List<AppIntent>,
    depth: Int,
): NegotiationResult {
    val ranked = rankLaunchSuggestions(
        requestText = suggestedQuery,
        visibleApps = visibleApps,
        allIntents = allIntents,
    )
    val toolResultMessage = buildSuggestAppsToolMessage(
        suggestedQuery = suggestedQuery,
        ranked = ranked,
        notesByPackage = notesByPackage,
    )
    logSuggestAppsRanking(session, sessionHandle, suggestedQuery, ranked, depth)

    session.logDevBoundary(
        sessionHandle,
        "app_to_chat payload reply toolResultMessageChars=${toolResultMessage.length}",
    )
    val followUp = session.negotiationManager.reply(toolResultMessage)
    session.logDevBoundary(sessionHandle, "chat_to_app payload reply_result ${summarizeResult(followUp)}")

    if (followUp.suggestedQuery.isNotBlank() && !followUp.showSuggestions) {
        return resolveSuggestedAppsTool(
            session = session,
            sessionHandle = sessionHandle,
            result = followUp,
            visibleApps = visibleApps,
            notesByPackage = notesByPackage,
            allIntents = allIntents,
            depth = depth + 1,
        )
    }

    val responseText = followUp.responseText.ifBlank { result.responseText }
    val queryForUi = followUp.suggestedQuery.ifBlank { suggestedQuery }
    return followUp.copy(
        responseText = responseText,
        suggestedQuery = queryForUi,
    )
}

private fun logSuggestAppsRanking(
    session: NegotiationSession,
    sessionHandle: SessionLogger.SessionHandle?,
    suggestedQuery: String,
    ranked: List<AppInfo>,
    depth: Int,
) {
    if (ranked.isEmpty()) {
        session.logDevDecision(sessionHandle, "tool_result empty for query=$suggestedQuery (depth=$depth)")
        return
    }
    session.logDevBoundary(
        sessionHandle,
        "app_to_chat tool_result suggestApps query=$suggestedQuery " +
            "candidates=${ranked.take(5).map { it.packageName }}",
    )
}

private suspend fun applyLaunchStrategyResult(
    session: NegotiationSession,
    context: Context,
    sessionHandle: SessionLogger.SessionHandle?,
    strategy: LaunchStrategy,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
    allIntents: List<AppIntent>,
) {
    when (strategy) {
        is LaunchStrategy.DirectLaunch -> {
            val label = PackageManagerHelper.getAppLabel(context, strategy.packageName)
            SessionLogger.log(sessionHandle, "Launched **$label**")
            session.launchTarget = strategy.packageName
            session.logDevDecision(
                sessionHandle,
                "launch_strategy=direct_from_chat_tool target=${strategy.packageName}",
            )
        }
        is LaunchStrategy.ShowChooser -> {
            session.logDevDecision(
                sessionHandle,
                "launch_strategy=chooser_ui_from_chat reason=model_called_presentSuggestions",
            )
            showQuickLaunchBar(
                session = session,
                sessionHandle = sessionHandle,
                queryText = strategy.query,
                visibleApps = visibleApps,
                hiddenPackages = hiddenPackages,
                allIntents = allIntents,
            )
        }
        LaunchStrategy.ContinueChat -> {
            session.logDevDecision(
                sessionHandle,
                "launch_strategy=continue_chat_from_model reason=no_action_tool_called",
            )
        }
        LaunchStrategy.None -> Unit
    }
}

private suspend fun handleUserSend(
    session: NegotiationSession,
    context: Context,
    packageName: String,
    appLabel: String,
    sessionHandle: SessionLogger.SessionHandle?,
    negotiationMode: NegotiationMode,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
    notesByPackage: Map<String, String>,
    allIntents: List<AppIntent>,
    input: String,
) {
    session.isWaitingForAi = true
    val firstResult = session.negotiationManager.reply(input)
    session.logDevBoundary(sessionHandle, "chat_to_app payload reply_result ${summarizeResult(firstResult)}")
    val result = resolveReplyForMode(
        session, sessionHandle, negotiationMode, firstResult, visibleApps, notesByPackage, allIntents,
    )
    addChatMessage(session, sessionHandle, result.responseText, isFromUser = false)
    session.isWaitingForAi = false
    if (result.suggestedQuery.isNotBlank() && !negotiationMode.isFocusGate && !negotiationMode.isExtendGate) {
        session.lastLaunchRequestText = result.suggestedQuery
    }

    applyNegotiationOutcome(
        session = session,
        negotiationMode = negotiationMode,
        result = result,
        packageName = packageName,
        appLabel = appLabel,
        sessionHandle = sessionHandle,
    )
    if (negotiationMode.isFocusGate || negotiationMode.isExtendGate) return

    val strategy = decideLaunchStrategy(
        result = result,
        mode = negotiationMode,
        packageName = packageName,
        fallbackQuery = session.lastLaunchRequestText,
    )
    applyLaunchStrategyResult(
        session = session,
        context = context,
        sessionHandle = sessionHandle,
        strategy = strategy,
        visibleApps = visibleApps,
        hiddenPackages = hiddenPackages,
        allIntents = allIntents,
    )
}

private suspend fun resolveReplyForMode(
    session: NegotiationSession,
    sessionHandle: SessionLogger.SessionHandle?,
    negotiationMode: NegotiationMode,
    firstResult: NegotiationResult,
    visibleApps: List<AppInfo>,
    notesByPackage: Map<String, String>,
    allIntents: List<AppIntent>,
): NegotiationResult {
    if (negotiationMode.isFocusGate || negotiationMode.isExtendGate) return firstResult
    return resolveSuggestedAppsTool(
        session = session,
        sessionHandle = sessionHandle,
        result = firstResult,
        visibleApps = visibleApps,
        notesByPackage = notesByPackage,
        allIntents = allIntents,
    )
}

private suspend fun runConversationStart(
    session: NegotiationSession,
    context: Context,
    packageName: String,
    unlockReason: String,
    durationMinutes: Int,
    sessionHandle: SessionLogger.SessionHandle?,
    appLabel: String,
    focusModeActive: Boolean,
    negotiationMode: NegotiationMode,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
    notesByPackage: Map<String, String>,
    allIntents: List<AppIntent>,
) {
    resetConversationUi(session, unlockReason)
    if (!ensureBackendAuthForSession(session, context, sessionHandle)) return
    updateModelLabelForSession(session, context)
    if (!(session.sessionUseBackend && session.backendAuth.hasToken())) {
        session.lmManager.initialize()
    }
    startConversationByMode(
        session, context, packageName, unlockReason, durationMinutes, sessionHandle,
        appLabel, focusModeActive, negotiationMode, visibleApps, hiddenPackages, notesByPackage, allIntents,
    )
}

private suspend fun startConversationByMode(
    session: NegotiationSession,
    context: Context,
    packageName: String,
    unlockReason: String,
    durationMinutes: Int,
    sessionHandle: SessionLogger.SessionHandle?,
    appLabel: String,
    focusModeActive: Boolean,
    negotiationMode: NegotiationMode,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
    notesByPackage: Map<String, String>,
    allIntents: List<AppIntent>,
) {
    when {
        negotiationMode.isExtendGate -> startExtendGateConversation(
            session, sessionHandle, packageName, appLabel, negotiationMode,
        )
        packageName.isNotEmpty() -> startGatekeeperConversation(
            session, context, sessionHandle, packageName, appLabel, focusModeActive, negotiationMode,
        )
        negotiationMode.isFocusGate -> startFocusGateConversation(
            session, context, sessionHandle, durationMinutes, unlockReason, negotiationMode, packageName, appLabel,
        )
        else -> startGeneralChatConversation(
            session, context, sessionHandle, unlockReason, packageName, negotiationMode,
            visibleApps, hiddenPackages, notesByPackage, allIntents,
        )
    }
}

private fun resetConversationUi(session: NegotiationSession, unlockReason: String) {
    session.messages.clear()
    session.userInput = ""
    session.isWaitingForAi = false
    session.accessGranted = false
    session.grantedExtensionMinutes = 0
    session.launchTarget = ""
    session.showSearchOverlay = false
    session.showLaunchSuggestions = false
    session.suggestedLaunchApps = emptyList()
    session.isLoadingApps = false
    session.lastLaunchRequestText = extractLaunchQuery(unlockReason)
}

private suspend fun ensureBackendAuthForSession(
    session: NegotiationSession,
    context: Context,
    sessionHandle: SessionLogger.SessionHandle?,
): Boolean {
    if (!session.sessionUseBackend || session.backendAuth.hasToken()) return true
    session.modelLabel = session.sessionSelectedModel
    if (!tryCompleteBackendSignIn(session, context, sessionHandle)) return false
    if (session.backendAuth.hasToken()) return true
    addChatMessage(
        session,
        sessionHandle,
        "Sign-in was cancelled or failed. You can retry from Settings.",
        isFromUser = false,
    )
    return false
}

private suspend fun tryCompleteBackendSignIn(
    session: NegotiationSession,
    context: Context,
    sessionHandle: SessionLogger.SessionHandle?,
): Boolean {
    try {
        val result = AuthManager.signInSilent(context) ?: AuthManager.signIn(context)
        if (result != null) {
            if (result.email != null) {
                ApiKeyManager.saveSignedInEmail(context, result.email)
            }
            session.backendAuth.completeBackendSignIn(result.idToken)
        }
        return true
    } catch (_: NoCredentialException) {
        addChatMessage(
            session,
            sessionHandle,
            "Sign-in failed: no Google account is available on this device.",
            isFromUser = false,
        )
        return false
    }
}

private suspend fun updateModelLabelForSession(session: NegotiationSession, context: Context) {
    val usingRemote = session.sessionUseBackend && session.backendAuth.hasToken()
    val signedInEmail = if (usingRemote) ApiKeyManager.getSignedInEmail(context) else null
    session.modelLabel = if (usingRemote) {
        val emailSuffix = if (signedInEmail != null) " · $signedInEmail" else ""
        "${session.sessionSelectedModel}$emailSuffix"
    } else {
        onDeviceModelLabel(LmPlaygroundManager.isInstalled(context))
    }
}

private suspend fun startExtendGateConversation(
    session: NegotiationSession,
    sessionHandle: SessionLogger.SessionHandle?,
    packageName: String,
    appLabel: String,
    negotiationMode: NegotiationMode,
) {
    SessionLogger.log(sessionHandle, "Extend gate started for **$appLabel** via ${session.modelLabel}")
    session.isWaitingForAi = true
    val overrunMinutes = when (val state = TimerService.timerState.value) {
        is TimerState.Expired ->
            ((state.overrunMs + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
        else -> 0
    }
    val result = session.negotiationManager.startNudgeNegotiation(
        packageName = packageName,
        appName = appLabel,
        overrunMinutes = overrunMinutes,
        nudgeCount = 0,
    )
    session.logDevBoundary(sessionHandle, "chat_to_app extend_start_result ${summarizeResult(result)}")
    addChatMessage(session, sessionHandle, result.responseText, isFromUser = false)
    session.isWaitingForAi = false
    applyNegotiationOutcome(session, negotiationMode, result, packageName, appLabel, sessionHandle)
}

private suspend fun startGatekeeperConversation(
    session: NegotiationSession,
    context: Context,
    sessionHandle: SessionLogger.SessionHandle?,
    packageName: String,
    appLabel: String,
    focusModeActive: Boolean,
    negotiationMode: NegotiationMode,
) {
    SessionLogger.log(sessionHandle, "AI negotiation started for **$appLabel** via ${session.modelLabel}")
    session.isWaitingForAi = true
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
            "Gatekeeper confrontation armed for **$appLabel** from last timer snapshot " +
                "(rank #${usageConfrontation.rankInTopApps})",
        )
    }
    val result = session.negotiationManager.startGatekeeperNegotiation(
        packageName = packageName,
        appName = appLabel,
        focusModeActive = focusModeActive,
        usageConfrontation = usageConfrontation,
    )
    session.logDevBoundary(sessionHandle, "chat_to_app gatekeeper_start_result ${summarizeResult(result)}")
    addChatMessage(session, sessionHandle, result.responseText, isFromUser = false)
    session.isWaitingForAi = false
    applyNegotiationOutcome(session, negotiationMode, result, packageName, appLabel, sessionHandle)
}

private suspend fun startFocusGateConversation(
    session: NegotiationSession,
    context: Context,
    sessionHandle: SessionLogger.SessionHandle?,
    durationMinutes: Int,
    unlockReason: String,
    negotiationMode: NegotiationMode,
    packageName: String,
    appLabel: String,
) {
    SessionLogger.log(sessionHandle, "Focus time gate started via ${session.modelLabel}")
    session.isWaitingForAi = true
    val result = session.negotiationManager.startFocusGateNegotiation(
        durationMinutes = durationMinutes,
        declaredIntent = unlockReason,
        focusWindowDescription = SettingsManager.describeFocusTimeWindows(context),
    )
    session.logDevBoundary(sessionHandle, "chat_to_app focus_gate_start_result ${summarizeResult(result)}")
    addChatMessage(session, sessionHandle, result.responseText, isFromUser = false)
    session.isWaitingForAi = false
    applyNegotiationOutcome(session, negotiationMode, result, packageName, appLabel, sessionHandle)
}

private suspend fun startGeneralChatConversation(
    session: NegotiationSession,
    context: Context,
    sessionHandle: SessionLogger.SessionHandle?,
    unlockReason: String,
    packageName: String,
    negotiationMode: NegotiationMode,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
    notesByPackage: Map<String, String>,
    allIntents: List<AppIntent>,
) {
    SessionLogger.log(sessionHandle, "AI assistant opened via ${session.modelLabel}")
    val allVisible = resolveVisibleAppsOrWait(session, visibleApps, hiddenPackages)
    val appsForPrompt = rankLaunchSuggestions(
        requestText = session.lastLaunchRequestText,
        visibleApps = allVisible,
        allIntents = allIntents,
        limit = 20,
    ).map { it.label to it.packageName }
    session.negotiationManager.startGeneralChat(context, appsForPrompt)

    if (unlockReason.isEmpty()) {
        addChatMessage(session, sessionHandle, PromptTemplates.GENERAL_CHAT_GREETING, isFromUser = false)
        return
    }

    addChatMessage(session, sessionHandle, unlockReason, isFromUser = true)
    session.isWaitingForAi = true
    val firstResult = session.negotiationManager.reply(unlockReason)
    session.logDevBoundary(sessionHandle, "chat_to_app payload reply_result ${summarizeResult(firstResult)}")
    val result = resolveSuggestedAppsTool(
        session = session,
        sessionHandle = sessionHandle,
        result = firstResult,
        visibleApps = allVisible,
        notesByPackage = notesByPackage,
        allIntents = allIntents,
    )
    addChatMessage(session, sessionHandle, result.responseText, isFromUser = false)
    session.isWaitingForAi = false
    if (result.suggestedQuery.isNotBlank()) {
        session.lastLaunchRequestText = result.suggestedQuery
    }
    val strategy = decideLaunchStrategy(
        result = result,
        mode = negotiationMode,
        packageName = packageName,
        fallbackQuery = session.lastLaunchRequestText,
    )
    applyLaunchStrategyResult(
        session = session,
        context = context,
        sessionHandle = sessionHandle,
        strategy = strategy,
        visibleApps = allVisible,
        hiddenPackages = hiddenPackages,
        allIntents = allIntents,
    )
}

private suspend fun resolveVisibleAppsOrWait(
    session: NegotiationSession,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
): List<AppInfo> {
    if (visibleApps.isNotEmpty()) return visibleApps
    return snapshotFlow { session.allApps }.first { it.isNotEmpty() }
        .filter { it.packageName !in hiddenPackages }
}

private suspend fun handleLaunchTargetEffect(
    session: NegotiationSession,
    context: Context,
    onAppGranted: () -> Unit,
    visibleApps: List<AppInfo>,
    hiddenPackages: Set<String>,
    allIntents: List<AppIntent>,
    sessionHandle: SessionLogger.SessionHandle?,
) {
    if (session.launchTarget.isEmpty()) return
    val targetPackage = session.launchTarget
    session.launchTarget = ""
    val launched = PackageManagerHelper.launchApp(context, targetPackage)
    if (launched) {
        session.showLaunchSuggestions = false
        session.suggestedLaunchApps = emptyList()
        session.negotiationManager.endConversation()
        session.lmManager.shutdown()
        onAppGranted()
        return
    }
    showQuickLaunchBar(
        session = session,
        sessionHandle = sessionHandle,
        queryText = session.lastLaunchRequestText.ifBlank {
            PackageManagerHelper.getAppLabel(context, targetPackage)
        },
        visibleApps = visibleApps,
        hiddenPackages = hiddenPackages,
        allIntents = allIntents,
    )
}

private fun applySessionModelPicker(
    session: NegotiationSession,
    context: Context,
    repository: AppRepository,
    karmaManager: KarmaManager,
) {
    session.showModelPicker = false
    val changed = session.pickerUseBackend != session.sessionUseBackend ||
        session.pickerSelectedModel != session.sessionSelectedModel
    if (!changed) return

    session.negotiationManager.endConversation()
    session.sessionUseBackend = session.pickerUseBackend
    session.sessionSelectedModel = session.pickerSelectedModel
    session.modelLabel = if (session.sessionUseBackend) {
        "${session.sessionSelectedModel} (checking auth...)"
    } else {
        onDeviceModelLabel(LmPlaygroundManager.isInstalled(context))
    }
    session.negotiationManager = NegotiationManager(
        context = context,
        lmManager = session.lmManager,
        repository = repository,
        karmaManager = karmaManager,
        backendAuth = if (session.sessionUseBackend) session.backendAuth else null,
        backendModel = session.sessionSelectedModel,
    )
    session.conversationNonce += 1
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
