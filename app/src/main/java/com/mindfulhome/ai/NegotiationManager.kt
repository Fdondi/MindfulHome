package com.mindfulhome.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.mindfulhome.ai.backend.BackendAuthHelper
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.ai.backend.BackendHttpException
import com.mindfulhome.ai.backend.BackendToolDeclarations
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln

enum class NegotiationType {
    GATEKEEPER,
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

class NegotiationManager(
    private val context: Context,
    private val lmManager: LiteRtLmManager,
    private val repository: AppRepository,
    private val karmaManager: KarmaManager,
    private val backendAuth: BackendAuthHelper? = null,
    private val backendModel: String = "gemini-2.5-flash",
) {
    private var currentType: NegotiationType? = null
    private var exchangeCount = 0
    private var currentAppPackage: String = ""
    private val riskConfirmationAskedForPackages = mutableSetOf<String>()
    private var gatekeeperMinRounds = 0
    private var gatekeeperMaxRounds = 0

    // Rate limiter: hard cap of 10 messages per 60 seconds
    private val replyTimestamps = ArrayDeque<Long>()
    private val rateLimitMessages = 10
    private val rateLimitWindowMs = 60_000L

    // Backend state: stateless API needs full history on each call
    private var usingBackend = false
    private val backendHistory = mutableListOf<BackendClient.BackendContent>()
    private var backendTools: List<Map<String, JsonElement>>? = null

    // On-device state (kept for offline fallback)
    private var currentConversation: Conversation? = null
    private var gatekeeperTools: GatekeeperTools? = null
    private var nudgeTools: NudgeTools? = null
    private var generalChatTools: GeneralChatTools? = null

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
        val baseMinRounds = ceil(ln(1.0 + negativeKarma.toDouble())).toInt()
        val riskBonus = if (extraRiskConfirmation) 1 else 0
        val focusRoundsBonus = if (focusModeActive) 1 else 0
        gatekeeperMinRounds = (baseMinRounds + focusRoundsBonus + riskBonus).coerceAtLeast(0)
        gatekeeperMaxRounds = (gatekeeperMinRounds * 2).coerceAtLeast(gatekeeperMinRounds)
        val confrontationBrief = usageConfrontation?.let { buildConfrontationBrief(it) }

        val systemPrompt = PromptTemplates.gatekeeperSystemPrompt()
        val userContext = PromptTemplates.buildGatekeeperUserContext(
            appName = appName,
            karmaScore = karma.karmaScore,
            totalOpens = karma.totalOpens,
            totalOverruns = karma.totalOverruns,
            timesRequestedToday = 0,
            minRoundsBeforeGrant = gatekeeperMinRounds,
            maxRoundsBeforeGrant = gatekeeperMaxRounds,
            focusModeActive = focusModeActive,
            appNote = appNote,
            requiresExtraConfirmation = extraRiskConfirmation,
            confrontationBrief = confrontationBrief,
        )

        // Try backend first
        if (backendAuth != null && backendAuth.hasToken) {
            try {
                val result = startBackendConversation(
                    systemPrompt, userContext, BackendToolDeclarations.GATEKEEPER_TOOLS,
                )
                if (result != null) {
                    logDeveloper("backend gatekeeper start succeeded")
                    return@withContext applyGatekeeperRoundPolicy(result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Backend gatekeeper failed, falling back", e)
                logDeveloper("fallback triggered: backend gatekeeper failed (${e.javaClass.simpleName}: ${e.message ?: "<no message>"})")
                if ((e as? BackendHttpException)?.code == "model_not_found") {
                    logDeveloper("block triggered: backend model not found ($backendModel)")
                    return@withContext NegotiationResult(
                        responseText = "Model '$backendModel' is not available. " +
                            "Please go to Settings and pick a different model.",
                    )
                }
            }
        } else {
            logDeveloper(
                "fallback reason: backend path unavailable " +
                    "(backendAuthPresent=${backendAuth != null}, hasToken=${backendAuth?.hasToken == true}); " +
                    "using scripted gatekeeper fallback"
            )
        }

        // Fallback: hardcoded responses (on-device LLM can't do tool calling)
        exchangeCount++
        val text = PromptTemplates.fallbackGatekeeperResponse(
            appName = appName,
            exchangeCount = exchangeCount - 1,
            confrontationBrief = confrontationBrief,
        )
        val grant = exchangeCount >= gatekeeperMinRounds
        logDeveloper("fallback response used: gatekeeper scripted response (grant=$grant, exchangeCount=$exchangeCount, minRounds=$gatekeeperMinRounds)")
        applyGatekeeperRoundPolicy(
            NegotiationResult(responseText = text, accessGranted = grant)
        )
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

        val systemPrompt = PromptTemplates.nudgeSystemPrompt()
        val userContext = PromptTemplates.buildNudgeContext(
            appName = appName,
            karmaScore = karma.karmaScore,
            overrunMinutes = overrunMinutes,
            nudgeCount = nudgeCount,
        )

        if (backendAuth != null && backendAuth.hasToken) {
            try {
                val result = startBackendConversation(
                    systemPrompt, userContext, BackendToolDeclarations.NUDGE_TOOLS,
                )
                if (result != null) {
                    logDeveloper("backend nudge start succeeded")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Backend nudge failed, falling back", e)
                logDeveloper("fallback triggered: backend nudge failed (${e.javaClass.simpleName}: ${e.message ?: "<no message>"})")
                if ((e as? BackendHttpException)?.code == "model_not_found") {
                    logDeveloper("block triggered: backend model not found ($backendModel)")
                    return@withContext NegotiationResult(
                        responseText = "Model '$backendModel' is not available. " +
                            "Please go to Settings and pick a different model.",
                    )
                }
            }
        } else {
            logDeveloper(
                "fallback reason: backend path unavailable " +
                    "(backendAuthPresent=${backendAuth != null}, hasToken=${backendAuth?.hasToken == true}); " +
                    "using scripted nudge fallback"
            )
        }

        exchangeCount++
        val text = PromptTemplates.fallbackNudgeResponse(appName, nudgeCount)
        logDeveloper("fallback response used: nudge scripted response (exchangeCount=$exchangeCount)")
        NegotiationResult(responseText = text)
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

        val hiddenApps = try {
            repository.hiddenApps().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading hidden apps", e)
            emptyList()
        }
        val hiddenAppsBriefing = if (hiddenApps.isEmpty()) {
            "No apps are currently hidden."
        } else {
            "Currently hidden apps:\n" + hiddenApps.joinToString("\n") { karma ->
                val label = PackageManagerHelper.getAppLabel(appContext, karma.packageName)
                val noteSuffix = karma.appNote?.trim()?.takeIf { it.isNotBlank() }?.let {
                    ", note: \"$it\""
                }.orEmpty()
                "- $label (${karma.packageName}), karma: ${karma.karmaScore}$noteSuffix"
            }
        }
        val allKarma = try {
            repository.allKarma().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app notes", e)
            emptyList()
        }
        val notesBriefing = allKarma
            .asSequence()
            .mapNotNull { karma ->
                val note = karma.appNote?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = PackageManagerHelper.getAppLabel(appContext, karma.packageName)
                val needsConfirm = PromptTemplates.requiresExtraConfirmation(note)
                "- $label (${karma.packageName}): \"$note\" (needs extra confirmation: $needsConfirm)"
            }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.let { notes -> "App notes:\n${notes.joinToString("\n")}" }

        val installedAppsBriefing = installedApps
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (label, pkg) -> "- $label ($pkg)" }
            ?.let { "Installed apps available to launch:\n$it" }
        val dailySummaries = try {
            repository.getLatestDailySummaries(5)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading daily summaries", e)
            emptyList()
        }
        val dailySummariesBriefing = dailySummaries
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n\n") { s ->
                "### ${s.day}\n${s.summary.trim()}"
            }
            ?.let { "Recent daily log summaries (most recent first):\n$it" }

        val basePrompt = PromptTemplates.generalChatSystemPrompt(hiddenAppsBriefing, notesBriefing, installedAppsBriefing)
        val systemPrompt = if (dailySummariesBriefing.isNullOrBlank()) {
            basePrompt
        } else {
            buildString {
                appendLine(basePrompt)
                appendLine()
                appendLine(dailySummariesBriefing)
            }.trim()
        }

        if (backendAuth != null && backendAuth.hasToken) {
            usingBackend = true
            backendHistory.clear()
            backendTools = BackendToolDeclarations.GENERAL_CHAT_TOOLS
            logDeveloper("general chat backend enabled with model=$backendModel, tools=${formatTools(backendTools)}")

            // System prompt as first user message, greeting as first model message
            backendHistory.add(userContent(systemPrompt))
            backendHistory.add(modelContent(PromptTemplates.GENERAL_CHAT_GREETING))
            return@withContext
        } else {
            logDeveloper(
                "fallback reason: general chat backend disabled " +
                    "(backendAuthPresent=${backendAuth != null}, hasToken=${backendAuth?.hasToken == true})"
            )
        }

        // On-device: set up conversation (model can chat but can't launch apps)
        if (lmManager.modelReady) {
            try {
                val tools = GeneralChatTools()
                generalChatTools = tools
                val conversation = lmManager.createConversation(
                    systemPrompt,
                    toolSets = listOf(tools),
                )
                currentConversation = conversation
                logDeveloper("general chat on-device conversation initialized (toolSupport=limited)")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up general chat", e)
                logDeveloper("general chat setup failed (${e.javaClass.simpleName}: ${e.message ?: "<no message>"})")
            }
        } else {
            logDeveloper("fallback reason: on-device model not ready; general chat may use scripted responses")
        }
    }

    // ── Reply (multi-turn) ───────────────────────────────────────────

    suspend fun reply(userMessage: String): NegotiationResult = withContext(Dispatchers.IO) {
        logDeveloper("chat user message(type=${currentType ?: "UNKNOWN"}, text=${quote(userMessage)})")
        // ── Rate limit ─────────────────────────────────────────────
        val now = System.currentTimeMillis()
        while (replyTimestamps.isNotEmpty() && now - replyTimestamps.first() > rateLimitWindowMs) {
            replyTimestamps.removeFirst()
        }
        if (replyTimestamps.size >= rateLimitMessages) {
            val waitSec = (rateLimitWindowMs - (now - replyTimestamps.first())) / 1000 + 1
            Log.w(TAG, "Rate limit hit: $rateLimitMessages messages in ${rateLimitWindowMs / 1000}s window")
            logDeveloper("block triggered: rate limit exceeded (limit=$rateLimitMessages, windowMs=$rateLimitWindowMs, waitSec=$waitSec)")
            return@withContext NegotiationResult(
                responseText = "Too many messages — please wait ${waitSec}s before trying again.",
            )
        }
        replyTimestamps.addLast(now)

        // ── Backend path ─────────────────────────────────────────────
        if (usingBackend && backendAuth != null) {
            try {
                backendHistory.add(userContent(userMessage))
                val response = backendAuth.generateWithAutoRefresh(
                    model = backendModel,
                    contents = backendHistory,
                    tools = backendTools,
                )
                exchangeCount++
                logDeveloper("backend chat response received(model=$backendModel, text=${quote(response.result ?: "")}, functionCalls=${formatFunctionCalls(response.function_calls)})")

                val text = response.result ?: ""
                val result = applyLaunchRiskConfirmation(
                    parseBackendResult(text, response.function_calls)
                )
                backendHistory.add(modelContent(result.responseText))
                logDeveloper("chat assistant response(type=backend, text=${quote(result.responseText)}, accessGranted=${result.accessGranted}, extensionMinutes=${result.extensionMinutes}, launchedPackage=${quote(result.launchedPackage)}, suggestedQuery=${quote(result.suggestedQuery)})")
                return@withContext applyGatekeeperRoundPolicy(result)
            } catch (e: Exception) {
                val httpEx = e as? BackendHttpException
                val detail = if (httpEx != null) "HTTP ${httpEx.statusCode}: ${httpEx.message}" else e.toString()
                Log.e(TAG, "Backend reply failed – $detail", e)
                logDeveloper("fallback triggered: backend reply failed ($detail)")
                // Remove the user message we added since the call failed
                if (backendHistory.isNotEmpty() &&
                    backendHistory.last().role == "user"
                ) {
                    backendHistory.removeAt(backendHistory.size - 1)
                }
                // Surface model_not_found directly to the user
                if (httpEx?.code == "model_not_found") {
                    logDeveloper("block triggered: backend model not found ($backendModel)")
                    return@withContext NegotiationResult(
                        responseText = "Model '$backendModel' is not available. " +
                            "Please go to Settings and pick a different model.",
                    )
                }
            }
        } else {
            logDeveloper(
                "fallback reason: skipping backend reply path " +
                    "(usingBackend=$usingBackend, backendAuthPresent=${backendAuth != null})"
            )
        }

        // ── On-device path ───────────────────────────────────────────
        val conversation = currentConversation
        if (conversation != null && lmManager.modelReady) {
            try {
                gatekeeperTools?.reset()
                nudgeTools?.reset()
                generalChatTools?.reset()

                val response = lmManager.sendMessage(conversation, userMessage)
                exchangeCount++
                logDeveloper("on-device chat response received(text=${quote(response)})")

                val parsed = when (currentType) {
                    NegotiationType.GATEKEEPER -> NegotiationResult(
                        responseText = response,
                        accessGranted = gatekeeperTools?.accessGranted == true,
                    )
                    NegotiationType.NUDGE -> {
                        val ext = nudgeTools?.extensionMinutes ?: 0
                        NegotiationResult(
                            responseText = response,
                            extensionMinutes = ext,
                            accessGranted = ext > 0,
                        )
                    }
                    NegotiationType.GENERAL -> NegotiationResult(
                        responseText = response,
                        launchedPackage = generalChatTools?.launchedPackage ?: "",
                        suggestedQuery = generalChatTools?.suggestedQuery ?: "",
                        showSuggestions = generalChatTools?.showSuggestions == true,
                    )
                    null -> NegotiationResult(response)
                }
                val result = applyLaunchRiskConfirmation(parsed)
                logDeveloper("chat assistant response(type=on-device, text=${quote(result.responseText)}, accessGranted=${result.accessGranted}, extensionMinutes=${result.extensionMinutes}, launchedPackage=${quote(result.launchedPackage)}, suggestedQuery=${quote(result.suggestedQuery)})")
                return@withContext applyGatekeeperRoundPolicy(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in on-device reply", e)
                logDeveloper("fallback triggered: on-device reply failed (${e.javaClass.simpleName}: ${e.message ?: "<no message>"})")
            }
        } else {
            logDeveloper(
                "fallback reason: skipping on-device reply path " +
                    "(conversationPresent=${conversation != null}, modelReady=${lmManager.modelReady})"
            )
        }

        // ── Hardcoded fallback ───────────────────────────────────────
        exchangeCount++
        when (currentType) {
            NegotiationType.GATEKEEPER -> {
                val appName = currentAppPackage.substringAfterLast('.')
                val text = PromptTemplates.fallbackGatekeeperResponse(appName, exchangeCount - 1)
                val grant = exchangeCount >= gatekeeperMinRounds
                logDeveloper("fallback response used: gatekeeper scripted reply in ongoing chat (grant=$grant, exchangeCount=$exchangeCount, minRounds=$gatekeeperMinRounds)")
                applyGatekeeperRoundPolicy(
                    NegotiationResult(responseText = text, accessGranted = grant)
                )
            }
            NegotiationType.NUDGE -> {
                val appName = currentAppPackage.substringAfterLast('.')
                val text = PromptTemplates.fallbackNudgeResponse(appName, exchangeCount - 1)
                logDeveloper("fallback response used: nudge scripted reply in ongoing chat (exchangeCount=$exchangeCount)")
                NegotiationResult(responseText = text)
            }
            NegotiationType.GENERAL, null -> NegotiationResult(
                "I'm running without an AI backend right now, so I can't launch apps from here. " +
                    "Tell me which app you want and I'll show quick launch suggestions.",
            ).also {
                logDeveloper("fallback response used: general/no-context scripted reply (reason=no backend/on-device conversation)")
            }
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    fun endConversation() {
        try {
            currentConversation?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing conversation", e)
        }
        currentConversation = null
        currentType = null
        exchangeCount = 0
        currentAppPackage = ""
        gatekeeperTools = null
        nudgeTools = null
        generalChatTools = null
        gatekeeperMinRounds = 0
        gatekeeperMaxRounds = 0
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
        exchangeCount++
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
        for (fc in functionCalls) {
            logDeveloper("tool call received(name=${fc.name}, args=${fc.args})")
            when (fc.name) {
                "grantAccess" -> return NegotiationResult(
                    responseText = text.ifBlank { "Opening the app for you." },
                    accessGranted = true,
                )
                "grantExtension" -> {
                    val minutes = fc.args["minutes"]?.jsonPrimitive?.int ?: 10
                    logDeveloper("tool params parsed: grantExtension(minutes=$minutes)")
                    return NegotiationResult(
                        responseText = text.ifBlank { "Extending your time by $minutes minutes." },
                        extensionMinutes = minutes,
                        accessGranted = true,
                    )
                }
                "launchApp" -> {
                    val pkg = (fc.args["packageName"] as? JsonPrimitive)?.content ?: ""
                    logDeveloper("tool params parsed: launchApp(packageName=${quote(pkg)})")
                    return NegotiationResult(
                        responseText = text.ifBlank { "Launching the app." },
                        launchedPackage = pkg,
                    )
                }
                "suggestApps" -> {
                    val query = (fc.args["query"] as? JsonPrimitive)?.content.orEmpty().trim()
                    logDeveloper("tool params parsed: suggestApps(query=${quote(query)})")
                    return NegotiationResult(
                        responseText = text.ifBlank { "Here are the closest app options." },
                        suggestedQuery = query,
                    )
                }
                "presentSuggestions" -> {
                    val query = (fc.args["query"] as? JsonPrimitive)?.content.orEmpty().trim()
                    logDeveloper("tool params parsed: presentSuggestions(query=${quote(query)})")
                    return NegotiationResult(
                        responseText = text.ifBlank { "Pick one of these options." },
                        suggestedQuery = query,
                        showSuggestions = true,
                    )
                }
                "queryRecentUsageSessions" -> {
                    val limit = fc.args["limit"]?.jsonPrimitive?.int ?: 5
                    logDeveloper("tool params parsed: queryRecentUsageSessions(limit=$limit)")
                    val summary = buildUsageHistorySummary(currentAppPackage, limit)
                    logDeveloper("tool response generated: queryRecentUsageSessions(summary=${quote(summary)})")
                    return NegotiationResult(
                        responseText = if (text.isBlank()) summary else "$text\n\n$summary",
                    )
                }
            }
        }

        return when (currentType) {
            NegotiationType.GATEKEEPER -> NegotiationResult(responseText = text)
            NegotiationType.NUDGE -> NegotiationResult(responseText = text)
            NegotiationType.GENERAL -> NegotiationResult(responseText = text)
            null -> NegotiationResult(responseText = text)
        }
    }

    private fun userContent(text: String) = BackendClient.BackendContent(
        role = "user",
        parts = listOf(BackendClient.BackendPart(text)),
    )

    private fun modelContent(text: String) = BackendClient.BackendContent(
        role = "model",
        parts = listOf(BackendClient.BackendPart(text)),
    )

    private suspend fun buildUsageHistorySummary(packageName: String, limit: Int): String {
        if (packageName.isBlank()) {
            return "I don't have a target app context yet, so I can't query usage history."
        }

        val safeLimit = limit.coerceIn(1, 20)
        val sessions = repository.getRecentSessions(packageName).take(safeLimit)
        if (sessions.isEmpty()) {
            return "No previous usage sessions were found for this app."
        }

        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return buildString {
            append("Most recent ")
            append(sessions.size)
            append(" usage sessions:\n")
            sessions.forEachIndexed { index, session ->
                val started = formatter.format(Date(session.startTimestamp))
                val ended = session.endTimestamp?.let { formatter.format(Date(it)) } ?: "ongoing"
                val timerMinutes = msToMinutes(session.timerDurationMs)
                val overrunMinutes = msToMinutes(session.overrunMs)
                val outcome = when {
                    session.endTimestamp == null -> "in progress"
                    session.closedOnTime -> "closed on time"
                    session.overrunMs > 0 -> "overran by ${overrunMinutes}m"
                    else -> "ended"
                }
                append("${index + 1}) $started -> $ended, timer ${timerMinutes}m, $outcome, karma ${session.karmaChange}\n")
            }
        }.trimEnd()
    }

    private fun msToMinutes(ms: Long): Long {
        if (ms <= 0L) return 0L
        return (ms + 59_999L) / 60_000L
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
        val totalMinutes = msToMinutes(durationMs)
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
        if (currentType != NegotiationType.GATEKEEPER) return result

        val minRounds = gatekeeperMinRounds.coerceAtLeast(0)
        val maxRounds = gatekeeperMaxRounds.coerceAtLeast(minRounds)
        if (exchangeCount < minRounds) {
            if (!result.accessGranted) return result
            logDeveloper("override triggered: gatekeeper grant blocked until min rounds(exchangeCount=$exchangeCount, minRounds=$minRounds)")
            return result.copy(
                responseText = result.responseText +
                    "\n\nOne more quick reflection before I open it.",
                accessGranted = false,
            )
        }

        if (exchangeCount >= maxRounds && !result.accessGranted) {
            logDeveloper("override triggered: gatekeeper auto-grant at max rounds(exchangeCount=$exchangeCount, maxRounds=$maxRounds)")
            return result.copy(
                responseText = result.responseText +
                    "\n\nAlright, you've stayed with this. Go ahead.",
                accessGranted = true,
            )
        }

        return result
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
        val names = mutableListOf<String>()
        try {
            tools.forEach { tool ->
                val decls = tool["functionDeclarations"] as? kotlinx.serialization.json.JsonArray
                decls?.forEach { decl ->
                    val name = (decl as? kotlinx.serialization.json.JsonObject)?.get("name")?.let { 
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content 
                    }
                    if (name != null) names.add(name)
                }
            }
        } catch (e: Exception) {
            return "[error parsing tools]"
        }
        return "[${names.joinToString(", ")}]"
    }

    private fun formatFunctionCalls(functionCalls: List<BackendClient.FunctionCall>): String {
        if (functionCalls.isEmpty()) return "[]"
        return functionCalls.joinToString(prefix = "[", postfix = "]") { call ->
            "{name=${call.name}, args=${call.args}}"
        }
    }
}
