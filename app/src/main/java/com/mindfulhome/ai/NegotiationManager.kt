package com.mindfulhome.ai

import android.content.Context
import android.util.Log
import com.mindfulhome.R
import com.mindfulhome.ai.backend.BackendAuthHelper
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.ai.backend.BackendHttpException
import com.mindfulhome.ai.backend.BackendToolDeclarations
import com.mindfulhome.data.AppRepository
import com.mindfulhome.locale.LocaleHelper
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.settings.FocusTimeWindowLogic
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

enum class NegotiationType {
    GATEKEEPER,
    FOCUS_GATE,
    NUDGE,
    GENERAL,
}

data class NegotiationResult(
    val responseText: String,
    val accessGranted: Boolean = false,
    val extensionMinutes: Int = 0,
    val launchedPackage: String = "",
    val suggestedQuery: String = "",
    val showSuggestions: Boolean = false,
)

data class GatekeeperUsageConfrontation(
    val capturedAtMs: Long,
    val rankInTopApps: Int,
    val foregroundTimeMs: Long,
    val longestSessionsMsDesc: List<Long>,
)

/**
 * AI gate conversations (focus time + hidden apps): round limits, grant tools, prompt assembly.
 *
 * @see docs.gates.md Project doc: goals, round mechanics, Proceed UX, editable prompts.
 * @see docs.navigation-map.md Routes: `assistant` (focus gate), `negotiate/{packageName}` (gatekeeper).
 */
class NegotiationManager(
    private val context: Context,
    private val lmClient: LmClient,
    private val repository: AppRepository,
    private val karmaManager: KarmaManager,
    private val backendAuth: BackendAuthHelper? = null,
    private val backendModel: String = "gemini-2.5-flash",
) {
    /** Convenience for existing call sites that hold a [LmPlaygroundManager]. */
    constructor(
        context: Context,
        lmManager: LmPlaygroundManager,
        repository: AppRepository,
        karmaManager: KarmaManager,
        backendAuth: BackendAuthHelper? = null,
        backendModel: String = "gemini-2.5-flash",
    ) : this(
        context = context,
        lmClient = LmPlaygroundLmClient(lmManager),
        repository = repository,
        karmaManager = karmaManager,
        backendAuth = backendAuth,
        backendModel = backendModel,
    )

    private var currentType: NegotiationType? = null
    private var exchangeCount = 0
    private var currentAppPackage: String = ""
    private val riskConfirmationAskedForPackages = mutableSetOf<String>()
    private var gatekeeperMinRounds = 0
    private var gatekeeperMaxRounds = 0
    private var focusGateDurationMinutes = 0
    private var focusGateDeclaredIntent = ""
    private var focusGateRemainingFocusTime = ""

    // Rate limiter: hard cap of 10 messages per 60 seconds
    private val replyTimestamps = ArrayDeque<Long>()
    private val rateLimitMessages = 10
    private val rateLimitWindowMs = 60_000L

    // Backend state: stateless API needs full history on each call
    private var usingBackend = false
    private val backendHistory = mutableListOf<BackendClient.BackendContent>()
    private var backendTools: List<Map<String, JsonElement>>? = null

    // On-device state (kept for offline fallback)
    private var currentConversation: Any? = null
    private var gatekeeperTools: GatekeeperTools? = null
    private var focusGateTools: FocusGateTools? = null
    private var nudgeTools: NudgeTools? = null
    private var generalChatTools: GeneralChatTools? = null
    private var localFailureNotice: String? = null

    // ── Gatekeeper ───────────────────────────────────────────────────

    suspend fun startGatekeeperNegotiation(
        packageName: String,
        appName: String,
        focusModeActive: Boolean,
        usageConfrontation: GatekeeperUsageConfrontation? = null,
    ): NegotiationResult = withContext(Dispatchers.IO) {
        logDeveloper("startGatekeeperNegotiation(package=$packageName, appName=$appName, focusModeActive=$focusModeActive)")
        currentAppPackage = packageName
        currentType = NegotiationType.GATEKEEPER
        exchangeCount = 0

        val karma = repository.getKarma(packageName)
        val appNote = karma.appNote
        val extraRiskConfirmation = PromptTemplates.requiresExtraConfirmation(appNote)
        val negativeKarma = (-karma.karmaScore).coerceAtLeast(0)
        val budget = NegotiationManagerLogic.computeGatekeeperRoundBudget(
            negativeKarma = negativeKarma,
            extraRiskConfirmation = extraRiskConfirmation,
            focusModeActive = focusModeActive,
        )
        gatekeeperMinRounds = budget.minRounds
        gatekeeperMaxRounds = budget.maxRounds
        val confrontationBrief = usageConfrontation?.let { buildConfrontationBrief(it) }

        val systemPrompt = PromptTemplates.gatekeeperSystemPrompt(context)
        val userContext = PromptTemplates.buildGatekeeperUserContext(
            context = context,
            appName = appName,
            karmaScore = karma.karmaScore,
            totalOpens = karma.totalOpens,
            totalOverruns = karma.totalOverruns,
            timesRequestedToday = 0,
            minRoundsBeforeGrant = gatekeeperMinRounds,
            focusModeActive = focusModeActive,
            appNote = appNote,
            requiresExtraConfirmation = extraRiskConfirmation,
            confrontationBrief = confrontationBrief,
        )
        val opening = PromptTemplates.fallbackGatekeeperResponse(
            appName = appName,
            exchangeCount = 0,
            confrontationBrief = confrontationBrief,
        )
        prepareScriptedGateConversation(
            systemPrompt = systemPrompt,
            userContext = userContext,
            openingText = opening,
            backendTools = BackendToolDeclarations.GATEKEEPER_TOOLS,
            onDeviceTools = { makeGatekeeperTools() },
            onDeviceLogSuccess = "on-device gatekeeper conversation initialized",
        )
        logDeveloper("scripted gatekeeper opening used (no model generate)")
        NegotiationManagerLogic.scriptedGateOpeningResult(opening)
    }

    private suspend fun prepareScriptedGateConversation(
        systemPrompt: String,
        userContext: String,
        openingText: String,
        backendTools: List<Map<String, JsonElement>>,
        onDeviceTools: () -> LocalLmToolSet,
        onDeviceLogSuccess: String,
    ) {
        val backendPrompt = NegotiationManagerLogic.mergeSystemPromptWithOpening(
            systemPrompt = systemPrompt,
            opening = openingText,
        )
        if (seedBackendConversationIfAvailable(backendPrompt, userContext, backendTools, openingText)) {
            return
        }
        val onDevicePrompt = NegotiationManagerLogic.mergeSystemPromptWithOpening(
            systemPrompt = systemPrompt,
            opening = openingText,
            userContext = userContext,
        )
        initOnDeviceConversationIfAvailable(onDevicePrompt, onDeviceTools(), onDeviceLogSuccess)
    }

    private suspend fun seedBackendConversationIfAvailable(
        systemPrompt: String,
        userContext: String,
        tools: List<Map<String, JsonElement>>,
        openingText: String,
    ): Boolean {
        if (backendAuth == null || !backendAuth.hasToken()) {
            logBackendUnavailableFallback()
            return false
        }
        usingBackend = true
        backendHistory.clear()
        backendTools = tools
        backendHistory.add(userContent("$systemPrompt\n\n$userContext"))
        backendHistory.add(modelContent(openingText))
        logDeveloper(
            "backend conversation seeded without generate " +
                "(model=$backendModel, opening=${quote(openingText)})",
        )
        return true
    }

    private fun initOnDeviceConversationIfAvailable(
        systemPrompt: String,
        tools: LocalLmToolSet,
        logSuccess: String,
    ): Boolean {
        if (!lmClient.modelReady) {
            logDeveloper("fallback reason: LM Playground not ready for on-device start")
            return false
        }
        return try {
            usingBackend = false
            currentConversation = lmClient.createConversation(systemPrompt, toolSets = listOf(tools))
            if (currentConversation == null) return false
            logDeveloper(logSuccess)
            true
        } catch (e: Exception) {
            logOnDeviceStartFailure(e)
            false
        }
    }

    private suspend fun tryBackendStart(
        systemPrompt: String,
        userContext: String,
        tools: List<Map<String, JsonElement>>,
        logSuccess: String,
        logFailPrefix: String,
    ): NegotiationResult? {
        if (backendAuth == null || !backendAuth.hasToken()) {
            logBackendUnavailableFallback()
            return null
        }
        return try {
            val result = startBackendConversation(systemPrompt, userContext, tools)
            if (result != null) {
                logDeveloper(logSuccess)
            }
            result
        } catch (e: Exception) {
            handleBackendStartFailure(e, logFailPrefix)
        }
    }

    private fun logBackendUnavailableFallback() {
        logDeveloper(
            "fallback reason: backend path unavailable " +
                "(backendAuthPresent=${backendAuth != null}, hasToken=${if (backendAuth != null) "suspend-check" else "false"}); " +
                "using scripted fallback",
        )
    }

    private fun handleBackendStartFailure(e: Exception, logFailPrefix: String): NegotiationResult? {
        Log.w(TAG, "$logFailPrefix failed, falling back", e)
        logDeveloper(
            "fallback triggered: $logFailPrefix failed (${e.javaClass.simpleName}: ${e.message ?: "<no message>"})",
        )
        if (!NegotiationManagerLogic.isModelNotFoundCode((e as? BackendHttpException)?.code)) return null
        logDeveloper("block triggered: backend model not found ($backendModel)")
        return NegotiationResult(responseText = NegotiationManagerLogic.modelNotFoundMessage(backendModel))
    }

    private suspend fun tryOnDeviceStart(
        systemPrompt: String,
        userContext: String,
        tools: LocalLmToolSet,
        logSuccess: String,
    ): NegotiationResult? {
        if (!lmClient.modelReady) {
            logDeveloper("fallback reason: LM Playground not ready for on-device start")
            return null
        }
        return completeOnDeviceStart(systemPrompt, userContext, tools, logSuccess)
    }

    private suspend fun completeOnDeviceStart(
        systemPrompt: String,
        userContext: String,
        tools: LocalLmToolSet,
        logSuccess: String,
    ): NegotiationResult? {
        return try {
            sendOnDeviceStart(systemPrompt, userContext, tools, logSuccess)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalLmFailure) {
            rememberLocalFailure(e.userNotice)
            logOnDeviceStartFailure(e)
            null
        } catch (e: Exception) {
            rememberLocalFailure(LmPlaygroundSessionLogic.GENERIC_FAILURE)
            logOnDeviceStartFailure(e)
            null
        }
    }

    private suspend fun sendOnDeviceStart(
        systemPrompt: String,
        userContext: String,
        tools: LocalLmToolSet,
        logSuccess: String,
    ): NegotiationResult? {
        usingBackend = false
        currentConversation = lmClient.createConversation(systemPrompt, toolSets = listOf(tools))
        val conversation = currentConversation ?: return null
        val response = lmClient.sendMessage(conversation, userContext)
        if (LmPlaygroundSessionLogic.isUnusableLocalReply(response)) {
            rememberLocalFailure(
                response.trim().ifBlank { LmPlaygroundSessionLogic.GENERIC_FAILURE },
            )
            abandonOnDeviceConversation("unusable local start reply")
            return null
        }
        if (currentType == NegotiationType.NUDGE) {
            exchangeCount++
        }
        logDeveloper("$logSuccess (text=${quote(response)})")
        return parseOnDeviceResult(response)
    }

    private fun logOnDeviceStartFailure(e: Exception) {
        Log.w(TAG, "on-device start failed, falling back", e)
        logDeveloper(
            "fallback triggered: on-device start failed (${e.javaClass.simpleName}: ${e.message ?: "<no message>"})",
        )
        currentConversation = null
    }

    private fun makeGatekeeperTools(): GatekeeperTools {
        val tools = GatekeeperTools()
        tools.setUsageHistoryResolver { limit ->
            fetchUsageHistorySummary(currentAppPackage, limit)
        }
        gatekeeperTools = tools
        return tools
    }

    private fun scriptedGatekeeperFallback(
        appName: String,
        confrontationBrief: String?,
    ): NegotiationResult {
        val text = PromptTemplates.fallbackGatekeeperResponse(
            appName = appName,
            exchangeCount = exchangeCount,
            confrontationBrief = confrontationBrief,
        )
        val grant = PromptTemplates.fallbackShouldGrantAccess(exchangeCount)
        logDeveloper(
            "fallback response used: gatekeeper scripted response " +
                "(grant=$grant, exchangeCount=$exchangeCount, minRounds=$gatekeeperMinRounds)",
        )
        return applyGatekeeperRoundPolicy(
            NegotiationResult(responseText = text, accessGranted = grant),
        )
    }

    // ── Focus gate ───────────────────────────────────────────────────

    suspend fun startFocusGateNegotiation(
        durationMinutes: Int,
        declaredIntent: String,
        focusWindowDescription: String,
    ): NegotiationResult = withContext(Dispatchers.IO) {
        logDeveloper(
            "startFocusGateNegotiation(durationMinutes=$durationMinutes, " +
                "declaredIntent=${quote(declaredIntent)}, focusWindow=$focusWindowDescription)",
        )
        currentAppPackage = ""
        currentType = NegotiationType.FOCUS_GATE
        exchangeCount = 0
        focusGateDurationMinutes = durationMinutes
        focusGateDeclaredIntent = declaredIntent
        val remainingMinutes = SettingsManager.remainingFocusMinutesNow(context)
        focusGateRemainingFocusTime = remainingMinutes
            ?.let { PromptTemplates.formatRemainingFocusTime(context, it) }
            .orEmpty()

        val nowMs = System.currentTimeMillis()
        val activeInterval = SettingsManager.activeFocusIntervalNow(context, nowMs)
        val elapsed = if (activeInterval == null) {
            0
        } else {
            FocusTimeWindowLogic.elapsedMinutesSinceIntervalStart(
                minuteOfDay = FocusTimeWindowLogic.minuteOfDayFromEpochMs(nowMs),
                startMinutes = activeInterval.startMinutes,
                endMinutes = activeInterval.endMinutes,
            )
        }
        val budget = FocusTimeWindowLogic.focusGateRoundBudget(
            baseMin = SettingsManager.getFocusGateMinRounds(context),
            baseMax = SettingsManager.getFocusGateMaxRounds(context),
            elapsedMinutes = elapsed,
            extraRoundEveryMinutes = activeInterval?.extraRoundEveryMinutes
                ?: SettingsManager.DEFAULT_EXTRA_ROUND_EVERY_MINUTES,
            cap = SettingsManager.MAX_FOCUS_GATE_ROUNDS,
        )
        gatekeeperMinRounds = budget.first
        gatekeeperMaxRounds = budget.second

        val systemPrompt = PromptTemplates.focusGateSystemPrompt(context)
        val userContext = PromptTemplates.buildFocusGateUserContext(
            context = context,
            durationMinutes = durationMinutes,
            declaredIntent = declaredIntent,
            focusWindowDescription = focusWindowDescription,
            minRoundsBeforeGrant = gatekeeperMinRounds,
            remainingFocusTime = focusGateRemainingFocusTime,
        )
        val opening = PromptTemplates.focusGateOpening(context, focusGateRemainingFocusTime)
        prepareScriptedGateConversation(
            systemPrompt = systemPrompt,
            userContext = userContext,
            openingText = opening,
            backendTools = BackendToolDeclarations.FOCUS_GATE_TOOLS,
            onDeviceTools = {
                val focusTools = FocusGateTools()
                focusGateTools = focusTools
                focusTools
            },
            onDeviceLogSuccess = "on-device focus gate conversation initialized",
        )
        logDeveloper("scripted focus gate opening used (no model generate)")
        NegotiationManagerLogic.scriptedGateOpeningResult(opening)
    }

    // ── Nudge ────────────────────────────────────────────────────────

    suspend fun startNudgeNegotiation(
        packageName: String,
        appName: String,
        overrunMinutes: Int,
        nudgeCount: Int,
    ): NegotiationResult = withContext(Dispatchers.IO) {
        logDeveloper("startNudgeNegotiation(package=$packageName, appName=$appName, overrunMinutes=$overrunMinutes, nudgeCount=$nudgeCount)")
        currentAppPackage = packageName
        currentType = NegotiationType.NUDGE
        exchangeCount = 0

        val karma = repository.getKarma(packageName)

        val systemPrompt = PromptTemplates.nudgeSystemPrompt(context)
        val userContext = PromptTemplates.buildNudgeContext(
            appName = appName,
            karmaScore = karma.karmaScore,
            overrunMinutes = overrunMinutes,
            nudgeCount = nudgeCount,
        )

        tryBackendStart(
            systemPrompt = systemPrompt,
            userContext = userContext,
            tools = BackendToolDeclarations.NUDGE_TOOLS,
            logSuccess = "backend nudge start succeeded",
            logFailPrefix = "Backend nudge",
        )?.let { return@withContext it }

        val nudge = NudgeTools()
        nudgeTools = nudge
        tryOnDeviceStart(
            systemPrompt = systemPrompt,
            userContext = userContext,
            tools = nudge,
            logSuccess = "on-device nudge start succeeded",
        )?.let { return@withContext it }

        exchangeCount++
        val text = PromptTemplates.fallbackNudgeResponse(context, appName, nudgeCount)
        logDeveloper("fallback response used: nudge scripted response (exchangeCount=$exchangeCount)")
        finishWithScriptedLocalFallback(NegotiationResult(responseText = text), userMessage = "")
    }

    // ── General chat ─────────────────────────────────────────────────

    /**
     * Sets up the general chat conversation. Does NOT produce a response —
     * the greeting is hardcoded in the UI so it appears instantly.
     */
    suspend fun startGeneralChat(
        appContext: Context,
        installedApps: List<Pair<String, String>> = emptyList(), // label to packageName
    ): Unit = withContext(Dispatchers.IO) {
        logDeveloper("startGeneralChat(installedApps=${installedApps.size})")
        currentAppPackage = ""
        currentType = NegotiationType.GENERAL
        exchangeCount = 0
        val systemPrompt = buildGeneralChatSystemPrompt(appContext, installedApps)
        if (initGeneralChatBackend(systemPrompt)) return@withContext
        initGeneralChatOnDevice(systemPrompt)
    }

    private suspend fun buildGeneralChatSystemPrompt(
        appContext: Context,
        installedApps: List<Pair<String, String>>,
    ): String {
        val hiddenAppsBriefing = NegotiationManagerLogic.buildHiddenAppsBriefing(
            loadHiddenAppBriefingLines(appContext),
        )
        val notesBriefing = NegotiationManagerLogic.buildAppNotesBriefing(
            loadAppNoteBriefingLines(appContext),
        )
        val installedAppsBriefing = NegotiationManagerLogic.buildInstalledAppsBriefing(
            installedApps.map { (label, pkg) ->
                NegotiationManagerLogic.formatInstalledAppBriefingLine(label, pkg)
            },
        )
        val dailySummariesBriefing = NegotiationManagerLogic.formatDailySummariesBriefing(
            loadDailySummaryPairs(),
        )
        val basePrompt = PromptTemplates.generalChatSystemPrompt(
            appContext, hiddenAppsBriefing, notesBriefing, installedAppsBriefing,
        )
        return NegotiationManagerLogic.mergeSystemPromptWithDailySummaries(
            basePrompt, dailySummariesBriefing,
        )
    }

    private suspend fun loadHiddenAppBriefingLines(appContext: Context): List<String> {
        val hiddenApps = try {
            repository.hiddenApps().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading hidden apps", e)
            emptyList()
        }
        return hiddenApps.map { karma ->
            NegotiationManagerLogic.formatHiddenAppBriefingLine(
                label = PackageManagerHelper.getAppLabel(appContext, karma.packageName),
                packageName = karma.packageName,
                karmaScore = karma.karmaScore,
                note = karma.appNote,
            )
        }
    }

    private suspend fun loadAppNoteBriefingLines(appContext: Context): List<String> {
        val allKarma = try {
            repository.allKarma().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app notes", e)
            emptyList()
        }
        return allKarma.mapNotNull { karma ->
            val note = karma.appNote?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NegotiationManagerLogic.formatAppNoteBriefingLine(
                label = PackageManagerHelper.getAppLabel(appContext, karma.packageName),
                packageName = karma.packageName,
                note = note,
                needsExtraConfirmation = PromptTemplates.requiresExtraConfirmation(note),
            )
        }
    }

    private suspend fun loadDailySummaryPairs(): List<Pair<String, String>> {
        val dailySummaries = try {
            repository.getLatestDailySummaries(5)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading daily summaries", e)
            emptyList()
        }
        return dailySummaries.map { it.day to it.summary }
    }

    private suspend fun initGeneralChatBackend(systemPrompt: String): Boolean {
        if (backendAuth == null || !backendAuth.hasToken()) {
            logDeveloper(
                "fallback reason: general chat backend disabled " +
                    "(backendAuthPresent=${backendAuth != null}, " +
                    "hasToken=${if (backendAuth != null) "suspend-check" else "false"})",
            )
            return false
        }
        usingBackend = true
        backendHistory.clear()
        backendTools = BackendToolDeclarations.GENERAL_CHAT_TOOLS
        logDeveloper("general chat backend enabled with model=$backendModel, tools=${formatTools(backendTools)}")
        backendHistory.add(userContent(systemPrompt))
        backendHistory.add(modelContent(PromptTemplates.GENERAL_CHAT_GREETING))
        return true
    }

    private fun initGeneralChatOnDevice(systemPrompt: String) {
        if (!lmClient.modelReady) {
            logDeveloper(
                "fallback reason: LM Playground not ready; general chat may use scripted responses",
            )
            return
        }
        try {
            val tools = GeneralChatTools()
            generalChatTools = tools
            currentConversation = lmClient.createConversation(systemPrompt, toolSets = listOf(tools))
            logDeveloper("general chat on-device conversation initialized (toolSupport=limited)")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up general chat", e)
            logDeveloper(
                "general chat setup failed (${e.javaClass.simpleName}: ${e.message ?: "<no message>"})",
            )
        }
    }

    // ── Reply (multi-turn) ───────────────────────────────────────────

    suspend fun reply(userMessage: String): NegotiationResult = withContext(Dispatchers.IO) {
        logDeveloper("chat user message(type=${currentType ?: "UNKNOWN"}, text=${quote(userMessage)})")
        applyReplyRateLimit()?.let { return@withContext it }
        if (NegotiationManagerLogic.shouldIncrementExchangeBeforeReply(currentType)) {
            exchangeCount++
        }
        backendReply(userMessage)?.let { return@withContext it }
        onDeviceReply(userMessage)?.let { return@withContext it }
        scriptedFallback(userMessage)
    }

    private fun applyReplyRateLimit(): NegotiationResult? {
        val now = System.currentTimeMillis()
        val rateLimit = NegotiationManagerLogic.evaluateRateLimit(
            timestamps = replyTimestamps.toList(),
            nowMs = now,
            maxMessages = rateLimitMessages,
            windowMs = rateLimitWindowMs,
        )
        replyTimestamps.clear()
        replyTimestamps.addAll(rateLimit.timestamps)
        if (rateLimit.allowed) return null
        Log.w(TAG, "Rate limit hit: $rateLimitMessages messages in ${rateLimitWindowMs / 1000}s window")
        logDeveloper(
            "block triggered: rate limit exceeded (limit=$rateLimitMessages, " +
                "windowMs=$rateLimitWindowMs, waitSec=${rateLimit.waitSec})",
        )
        return NegotiationResult(
            responseText = "Too many messages — please wait ${rateLimit.waitSec}s before trying again.",
        )
    }

    private suspend fun backendReply(userMessage: String): NegotiationResult? {
        if (!usingBackend || backendAuth == null) {
            logDeveloper(
                "fallback reason: skipping backend reply path " +
                    "(usingBackend=$usingBackend, backendAuthPresent=${backendAuth != null})",
            )
            return null
        }
        return try {
            completeBackendReply(userMessage)
        } catch (e: Exception) {
            handleBackendReplyFailure(e)
        }
    }

    private suspend fun completeBackendReply(userMessage: String): NegotiationResult {
        backendHistory.add(userContent(userMessage))
        val response = backendAuth!!.generateWithAutoRefresh(
            model = backendModel,
            contents = backendHistory,
            tools = backendTools,
        )
        if (!NegotiationManagerLogic.shouldIncrementExchangeBeforeReply(currentType)) {
            exchangeCount++
        }
        logDeveloper(
            "backend chat response received(model=$backendModel, text=${quote(response.result ?: "")}, " +
                "functionCalls=${formatFunctionCalls(response.function_calls)})",
        )
        val result = applyLaunchRiskConfirmation(
            parseBackendResult(response.result ?: "", response.function_calls),
        )
        backendHistory.add(modelContent(result.responseText))
        logDeveloper(
            "chat assistant response(type=backend, text=${quote(result.responseText)}, " +
                "accessGranted=${result.accessGranted}, extensionMinutes=${result.extensionMinutes}, " +
                "launchedPackage=${quote(result.launchedPackage)}, suggestedQuery=${quote(result.suggestedQuery)})",
        )
        return applyGatekeeperRoundPolicy(result)
    }

    private fun handleBackendReplyFailure(e: Exception): NegotiationResult? {
        val httpEx = e as? BackendHttpException
        val detail = if (httpEx != null) "HTTP ${httpEx.statusCode}: ${httpEx.message}" else e.toString()
        Log.e(TAG, "Backend reply failed – $detail", e)
        logDeveloper("fallback triggered: backend reply failed ($detail)")
        if (backendHistory.isNotEmpty() && backendHistory.last().role == "user") {
            backendHistory.removeAt(backendHistory.size - 1)
        }
        if (!NegotiationManagerLogic.isModelNotFoundCode(httpEx?.code)) return null
        logDeveloper("block triggered: backend model not found ($backendModel)")
        return NegotiationResult(responseText = NegotiationManagerLogic.modelNotFoundMessage(backendModel))
    }

    private suspend fun onDeviceReply(userMessage: String): NegotiationResult? {
        val conversation = currentConversation
        if (conversation == null || !lmClient.modelReady) {
            logDeveloper(
                "fallback reason: skipping on-device reply path " +
                    "(conversationPresent=${conversation != null}, modelReady=${lmClient.modelReady})",
            )
            return null
        }
        return try {
            gatekeeperTools?.reset()
            focusGateTools?.reset()
            nudgeTools?.reset()
            generalChatTools?.reset()
            val response = lmClient.sendMessage(conversation, userMessage)
            if (LmPlaygroundSessionLogic.isUnusableLocalReply(response)) {
                return failOnDeviceReply(
                    response.trim().ifBlank { LmPlaygroundSessionLogic.GENERIC_FAILURE },
                    e = null,
                )
            }
            if (!NegotiationManagerLogic.shouldIncrementExchangeBeforeReply(currentType)) {
                exchangeCount++
            }
            logDeveloper("on-device chat response received(text=${quote(response)})")
            val result = applyLaunchRiskConfirmation(parseOnDeviceResult(response))
            logDeveloper(
                "chat assistant response(type=on-device, text=${quote(result.responseText)}, " +
                    "accessGranted=${result.accessGranted}, extensionMinutes=${result.extensionMinutes}, " +
                    "launchedPackage=${quote(result.launchedPackage)}, suggestedQuery=${quote(result.suggestedQuery)})",
            )
            applyGatekeeperRoundPolicy(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalLmFailure) {
            failOnDeviceReply(e.userNotice, e)
        } catch (e: Exception) {
            failOnDeviceReply(LmPlaygroundSessionLogic.GENERIC_FAILURE, e)
        }
    }

    private fun failOnDeviceReply(notice: String, e: Exception?): NegotiationResult? {
        if (e != null) {
            Log.e(TAG, "Error in on-device reply", e)
            logDeveloper(
                "fallback triggered: on-device reply failed (${e.javaClass.simpleName}: ${e.message ?: "<no message>"})",
            )
        }
        rememberLocalFailure(notice)
        abandonOnDeviceConversation("local failure")
        return null
    }

    private fun parseOnDeviceResult(response: String): NegotiationResult =
        NegotiationManagerLogic.parseOnDeviceResult(
            response = response,
            type = currentType,
            gatekeeperGranted = gatekeeperTools?.accessGranted == true,
            focusGateGranted = focusGateTools?.accessGranted == true,
            nudgeExtensionMinutes = nudgeTools?.extensionMinutes ?: 0,
            launchedPackage = generalChatTools?.launchedPackage.orEmpty(),
            suggestedQuery = generalChatTools?.suggestedQuery.orEmpty(),
            showSuggestions = generalChatTools?.showSuggestions == true,
        )

    private fun scriptedFallback(userMessage: String = ""): NegotiationResult {
        val base = when (currentType) {
            NegotiationType.GATEKEEPER -> {
                val appName = currentAppPackage.substringAfterLast('.')
                val text = PromptTemplates.fallbackGatekeeperResponse(appName, exchangeCount)
                val grant = PromptTemplates.fallbackShouldGrantAccess(exchangeCount)
                logDeveloper(
                    "fallback response used: gatekeeper scripted reply in ongoing chat " +
                        "(grant=$grant, exchangeCount=$exchangeCount, minRounds=$gatekeeperMinRounds)",
                )
                applyGatekeeperRoundPolicy(NegotiationResult(responseText = text, accessGranted = grant))
            }
            NegotiationType.FOCUS_GATE -> {
                val text = PromptTemplates.fallbackFocusGateResponse(
                    durationMinutes = focusGateDurationMinutes,
                    declaredIntent = focusGateDeclaredIntent,
                    exchangeCount = exchangeCount,
                    remainingFocusTime = focusGateRemainingFocusTime,
                )
                val grant = PromptTemplates.fallbackShouldGrantAccess(exchangeCount)
                logDeveloper(
                    "fallback response used: focus gate scripted reply in ongoing chat " +
                        "(grant=$grant, exchangeCount=$exchangeCount)",
                )
                applyGatekeeperRoundPolicy(NegotiationResult(responseText = text, accessGranted = grant))
            }
            NegotiationType.NUDGE -> {
                exchangeCount++
                val appName = currentAppPackage.substringAfterLast('.')
                val text = PromptTemplates.fallbackNudgeResponse(context, appName, exchangeCount - 1)
                logDeveloper(
                    "fallback response used: nudge scripted reply in ongoing chat (exchangeCount=$exchangeCount)",
                )
                NegotiationResult(responseText = text)
            }
            NegotiationType.GENERAL, null -> NegotiationResult(
                "I'm running without an AI backend right now, so I can't launch apps from here. " +
                    "Tell me which app you want and I'll show quick launch suggestions.",
            ).also {
                logDeveloper(
                    "fallback response used: general/no-context scripted reply (reason=no backend/on-device conversation)",
                )
            }
        }
        return finishWithScriptedLocalFallback(base, userMessage)
    }

    private fun finishWithScriptedLocalFallback(
        base: NegotiationResult,
        userMessage: String,
    ): NegotiationResult {
        val localized = LocaleHelper.wrap(context)
        return NegotiationManagerLogic.finishScriptedFallback(
            base = base,
            type = currentType,
            userMessage = userMessage,
            failureNotice = localizedLocalFailureNotice(consumeLocalFailureNotice()),
            emptyAck = localized.getString(R.string.nudge_scripted_extension_ack),
        )
    }

    private fun rememberLocalFailure(notice: String) {
        if (localFailureNotice.isNullOrBlank()) {
            localFailureNotice = notice
        }
    }

    private fun consumeLocalFailureNotice(): String? {
        val notice = localFailureNotice
        localFailureNotice = null
        return notice?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun localizedLocalFailureNotice(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed == LmPlaygroundSessionLogic.GENERIC_FAILURE) {
            return LocaleHelper.wrap(context).getString(R.string.local_ai_fallback_notice)
        }
        return trimmed
    }

    private fun abandonOnDeviceConversation(reason: String) {
        try {
            currentConversation?.let { lmClient.closeConversation(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing on-device conversation", e)
        }
        currentConversation = null
        logDeveloper("on-device conversation abandoned ($reason)")
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    fun endConversation() {
        try {
            currentConversation?.let { lmClient.closeConversation(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing conversation", e)
        }
        currentConversation = null
        localFailureNotice = null
        currentType = null
        exchangeCount = 0
        currentAppPackage = ""
        gatekeeperTools = null
        focusGateTools = null
        nudgeTools = null
        generalChatTools = null
        gatekeeperMinRounds = 0
        gatekeeperMaxRounds = 0
        focusGateDurationMinutes = 0
        focusGateDeclaredIntent = ""
        focusGateRemainingFocusTime = ""
        usingBackend = false
        backendHistory.clear()
        backendTools = null
        riskConfirmationAskedForPackages.clear()
    }

    // ── Backend helpers ──────────────────────────────────────────────

    /**
     * Starts a backend conversation with a system prompt + first user context,
     * returning a [NegotiationResult] or null if the backend call fails.
     */
    private suspend fun startBackendConversation(
        systemPrompt: String,
        userContext: String,
        tools: List<Map<String, JsonElement>>,
    ): NegotiationResult? {
        val auth = backendAuth ?: return null

        usingBackend = true
        backendHistory.clear()
        backendTools = tools

        backendHistory.add(userContent("$systemPrompt\n\n$userContext"))
        logDeveloper("backend request started(model=$backendModel, tools=${formatTools(tools)}, systemPrompt=${quote(systemPrompt)}, userContext=${quote(userContext)})")

        val response = auth.generateWithAutoRefresh(
            model = backendModel,
            contents = backendHistory,
            tools = tools,
        )
        if (currentType == NegotiationType.NUDGE) {
            exchangeCount++
        }
        logDeveloper("backend response for start conversation(model=$backendModel, text=${quote(response.result ?: "")}, functionCalls=${formatFunctionCalls(response.function_calls)})")

        val text = response.result ?: ""
        backendHistory.add(modelContent(text))

        return parseBackendResult(text, response.function_calls)
    }

    /**
     * Parses backend function calls into a [NegotiationResult].
     */
    private suspend fun parseBackendResult(
        text: String,
        functionCalls: List<BackendClient.FunctionCall>,
    ): NegotiationResult {
        functionCalls.forEach { fc ->
            logDeveloper("tool call received(name=${fc.name}, args=${fc.args})")
            logParsedToolParams(fc)
        }
        val usageSummary = resolveUsageSummaryFromToolCalls(functionCalls)
        return NegotiationManagerLogic.parseBackendResult(
            text = text,
            functionCalls = functionCalls,
            negotiationType = currentType,
            usageHistorySummary = { usageSummary ?: "No usage history available." },
        )
    }

    private fun logParsedToolParams(fc: BackendClient.FunctionCall) {
        val line = NegotiationManagerLogic.formatParsedToolParamsLine(fc.name, fc.args) ?: return
        logDeveloper(line)
    }

    private suspend fun resolveUsageSummaryFromToolCalls(
        functionCalls: List<BackendClient.FunctionCall>,
    ): String? {
        val usageCall = functionCalls.firstOrNull { it.name == "queryRecentUsageSessions" } ?: return null
        val limit = (usageCall.args["limit"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull() ?: 5
        val summary = fetchUsageHistorySummary(currentAppPackage, limit)
        logDeveloper("tool response generated: queryRecentUsageSessions(summary=${quote(summary)})")
        return summary
    }

    private fun userContent(text: String) = BackendClient.BackendContent(
        role = "user",
        parts = listOf(BackendClient.BackendPart(text)),
    )

    private fun modelContent(text: String) = BackendClient.BackendContent(
        role = "model",
        parts = listOf(BackendClient.BackendPart(text)),
    )

    private suspend fun fetchUsageHistorySummary(packageName: String, limit: Int): String {
        if (packageName.isBlank()) {
            return NegotiationManagerLogic.buildUsageHistorySummary(packageName, emptyList(), limit)
        }
        val sessions = repository.getRecentSessions(packageName)
        return NegotiationManagerLogic.buildUsageHistorySummary(packageName, sessions, limit)
    }

    private fun buildConfrontationBrief(confrontation: GatekeeperUsageConfrontation): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(confrontation.capturedAtMs))
        val longestSessions = confrontation.longestSessionsMsDesc
            .take(3)
            .filter { it > 0L }
            .map(::formatDurationCompact)
            .ifEmpty { listOf(formatDurationCompact(confrontation.foregroundTimeMs)) }
            .joinToString(", ")
        return "At $timestamp (last timer snapshot), this app ranked #${confrontation.rankInTopApps} " +
            "in top usage with total ${formatDurationCompact(confrontation.foregroundTimeMs)} foreground. " +
            "Longest sessions: $longestSessions. " +
            "Open with one explicit confrontation turn using this evidence before asking what they need."
    }

    private fun formatDurationCompact(durationMs: Long): String {
        val totalMinutes = NegotiationManagerLogic.msToMinutes(durationMs)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours <= 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    private suspend fun applyLaunchRiskConfirmation(result: NegotiationResult): NegotiationResult {
        if (currentType != NegotiationType.GENERAL) return result
        val targetPackage = result.launchedPackage.trim()
        if (targetPackage.isBlank()) return result

        val note = repository.getKarma(targetPackage).appNote
        val shouldDelayLaunch = PromptTemplates.requiresExtraConfirmation(note) &&
            targetPackage !in riskConfirmationAskedForPackages
        if (!shouldDelayLaunch) return result

        riskConfirmationAskedForPackages.add(targetPackage)
        logDeveloper("override triggered: launch blocked pending risk confirmation(package=${quote(targetPackage)}, note=${quote(note.orEmpty())})")
        return result.copy(
            responseText = PromptTemplates.riskConfirmationPrompt(note),
            launchedPackage = "",
        )
    }

    private fun applyGatekeeperRoundPolicy(result: NegotiationResult): NegotiationResult {
        val updated = NegotiationManagerLogic.applyGatekeeperRoundPolicy(
            result = result,
            negotiationType = currentType,
            exchangeCount = exchangeCount,
            minRounds = gatekeeperMinRounds,
            maxRounds = gatekeeperMaxRounds,
        )
        if (updated.accessGranted != result.accessGranted) {
            if (!updated.accessGranted) {
                logDeveloper(
                    "override triggered: gatekeeper grant blocked until min rounds" +
                        "(exchangeCount=$exchangeCount, minRounds=$gatekeeperMinRounds)",
                )
            } else {
                logDeveloper(
                    "override triggered: gatekeeper auto-grant at max rounds" +
                        "(exchangeCount=$exchangeCount, maxRounds=$gatekeeperMaxRounds)",
                )
            }
        }
        return updated
    }

    companion object {
        private const val TAG = "NegotiationManager"
    }

    private fun logDeveloper(event: String) {
        if (!SettingsManager.isDeveloperLogsEnabled(context)) return
        val entry = "[DEV][chat] $event"
        Log.d(TAG, entry)
        SessionLogger.log(entry)
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun formatTools(tools: List<Map<String, JsonElement>>?): String {
        if (tools.isNullOrEmpty()) return "[]"
        return try {
            NegotiationManagerLogic.formatToolDeclarationNames(tools)
        } catch (_: Exception) {
            "[error parsing tools]"
        }
    }

    private fun formatFunctionCalls(functionCalls: List<BackendClient.FunctionCall>): String {
        if (functionCalls.isEmpty()) return "[]"
        return functionCalls.joinToString(prefix = "[", postfix = "]") { call ->
            "{name=${call.name}, args=${call.args}}"
        }
    }
}
