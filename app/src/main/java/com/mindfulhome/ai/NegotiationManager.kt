package com.mindfulhome.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.mindfulhome.ai.backend.BackendAuthHelper
import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.ai.backend.BackendHttpException
import com.mindfulhome.ai.backend.BackendToolDeclarations
import com.mindfulhome.data.AppRepository
import com.mindfulhome.model.KarmaManager
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
)

class NegotiationManager(
    private val lmManager: LiteRtLmManager,
    private val repository: AppRepository,
    private val karmaManager: KarmaManager,
    private val backendAuth: BackendAuthHelper? = null,
    private val backendModel: String = "gemini-2.5-flash",
) {
    private data class SearchCandidate(val label: String, val packageName: String)
    private data class SearchSelection(val index: Int, val candidate: SearchCandidate)

    private var currentType: NegotiationType? = null
    private var exchangeCount = 0
    private var currentAppPackage: String = ""
    private var gatekeeperMinRounds = 0
    private var gatekeeperMaxRounds = 0

    // Backend state: stateless API needs full history on each call
    private var usingBackend = false
    private val backendHistory = mutableListOf<BackendClient.BackendContent>()
    private var backendTools: List<Map<String, JsonElement>>? = null

    // On-device state (kept for offline fallback)
    private var currentConversation: Conversation? = null
    private var gatekeeperTools: GatekeeperTools? = null
    private var nudgeTools: NudgeTools? = null
    private var generalChatTools: GeneralChatTools? = null
    private var searchableApps: List<SearchCandidate> = emptyList()
    private var lastSearchResults: List<SearchCandidate> = emptyList()

    // ── Gatekeeper ───────────────────────────────────────────────────

    suspend fun startGatekeeperNegotiation(
        packageName: String,
        appName: String,
        focusModeActive: Boolean,
    ): NegotiationResult = withContext(Dispatchers.IO) {
        currentAppPackage = packageName
        currentType = NegotiationType.GATEKEEPER
        exchangeCount = 0

        val karma = repository.getKarma(packageName)
        val negativeKarma = (-karma.karmaScore).coerceAtLeast(0)
        val baseMinRounds = ceil(ln(1.0 + negativeKarma.toDouble())).toInt()
        val focusRoundsBonus = if (focusModeActive) 1 else 0
        gatekeeperMinRounds = (baseMinRounds + focusRoundsBonus).coerceAtLeast(0)
        gatekeeperMaxRounds = (gatekeeperMinRounds * 2).coerceAtLeast(gatekeeperMinRounds)

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
        )

        // Try backend first
        if (backendAuth != null && backendAuth.hasToken) {
            try {
                val result = startBackendConversation(
                    systemPrompt, userContext, BackendToolDeclarations.GATEKEEPER_TOOLS,
                )
                if (result != null) {
                    return@withContext applyGatekeeperRoundPolicy(result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Backend gatekeeper failed, falling back", e)
                if ((e as? BackendHttpException)?.code == "model_not_found") {
                    return@withContext NegotiationResult(
                        responseText = "Model '$backendModel' is not available. " +
                            "Please go to Settings and pick a different model.",
                    )
                }
            }
        }

        // Fallback: hardcoded responses (on-device LLM can't do tool calling)
        exchangeCount++
        val text = PromptTemplates.fallbackGatekeeperResponse(appName, exchangeCount - 1)
        val grant = exchangeCount >= gatekeeperMinRounds
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
                if (result != null) return@withContext result
            } catch (e: Exception) {
                Log.w(TAG, "Backend nudge failed, falling back", e)
                if ((e as? BackendHttpException)?.code == "model_not_found") {
                    return@withContext NegotiationResult(
                        responseText = "Model '$backendModel' is not available. " +
                            "Please go to Settings and pick a different model.",
                    )
                }
            }
        }

        exchangeCount++
        val text = PromptTemplates.fallbackNudgeResponse(appName, nudgeCount)
        NegotiationResult(responseText = text)
    }

    // ── General chat ─────────────────────────────────────────────────

    /**
     * Sets up the general chat conversation. Does NOT produce a response —
     * the greeting is hardcoded in the UI so it appears instantly.
     */
    suspend fun startGeneralChat(appContext: Context): Unit = withContext(Dispatchers.IO) {
        currentAppPackage = ""
        currentType = NegotiationType.GENERAL
        exchangeCount = 0
        lastSearchResults = emptyList()

        val hiddenApps = try {
            repository.hiddenApps().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading hidden apps", e)
            emptyList()
        }
        searchableApps = try {
            PackageManagerHelper.getInstalledApps(appContext)
                .map { SearchCandidate(label = it.label, packageName = it.packageName) }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading installed apps for search", e)
            emptyList()
        }
        val hiddenAppsBriefing = if (hiddenApps.isEmpty()) {
            "No apps are currently hidden."
        } else {
            "Currently hidden apps:\n" + hiddenApps.joinToString("\n") { karma ->
                val label = PackageManagerHelper.getAppLabel(appContext, karma.packageName)
                "- $label (${karma.packageName}), karma: ${karma.karmaScore}"
            }
        }

        val systemPrompt = PromptTemplates.generalChatSystemPrompt(hiddenAppsBriefing)

        if (backendAuth != null && backendAuth.hasToken) {
            usingBackend = true
            backendHistory.clear()
            backendTools = BackendToolDeclarations.GENERAL_CHAT_TOOLS

            // System prompt as first user message, greeting as first model message
            backendHistory.add(userContent(systemPrompt))
            backendHistory.add(modelContent(PromptTemplates.GENERAL_CHAT_GREETING))
            return@withContext
        }

        // On-device: set up conversation (model can chat but can't launch apps)
        if (lmManager.modelReady) {
            try {
                val tools = GeneralChatTools()
                tools.setSearchResolver { query ->
                    searchInstalledApps(query).map { it.label to it.packageName }
                }
                tools.setSearchObserver { query, _ ->
                    lastSearchResults = searchInstalledApps(query)
                }
                generalChatTools = tools
                val conversation = lmManager.createConversation(
                    systemPrompt,
                    toolSets = listOf(tools),
                    initialMessages = listOf(
                        Message.model(PromptTemplates.GENERAL_CHAT_GREETING)
                    ),
                )
                currentConversation = conversation
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up general chat", e)
            }
        }
    }

    // ── Reply (multi-turn) ───────────────────────────────────────────

    suspend fun reply(userMessage: String): NegotiationResult = withContext(Dispatchers.IO) {
        if (currentType == NegotiationType.GENERAL) {
            val selection = parseSearchSelection(userMessage)
            if (selection != null) {
                val chosen = selection.candidate
                return@withContext NegotiationResult(
                    responseText = "Opening option ${selection.index + 1}: ${chosen.label}.",
                    launchedPackage = chosen.packageName,
                )
            }
        }

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

                val text = response.result ?: ""
                backendHistory.add(modelContent(text))

                val result = parseBackendResult(text, response.function_calls)
                return@withContext applyGatekeeperRoundPolicy(result)
            } catch (e: Exception) {
                val httpEx = e as? BackendHttpException
                val detail = if (httpEx != null) "HTTP ${httpEx.statusCode}: ${httpEx.message}" else e.toString()
                Log.e(TAG, "Backend reply failed – $detail", e)
                // Remove the user message we added since the call failed
                if (backendHistory.isNotEmpty() &&
                    backendHistory.last().role == "user"
                ) {
                    backendHistory.removeAt(backendHistory.size - 1)
                }
                // Surface model_not_found directly to the user
                if (httpEx?.code == "model_not_found") {
                    return@withContext NegotiationResult(
                        responseText = "Model '$backendModel' is not available. " +
                            "Please go to Settings and pick a different model.",
                    )
                }
            }
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

                val result = when (currentType) {
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
                    )
                    null -> NegotiationResult(response)
                }
                return@withContext applyGatekeeperRoundPolicy(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in on-device reply", e)
            }
        }

        // ── Hardcoded fallback ───────────────────────────────────────
        exchangeCount++
        when (currentType) {
            NegotiationType.GATEKEEPER -> {
                val appName = currentAppPackage.substringAfterLast('.')
                val text = PromptTemplates.fallbackGatekeeperResponse(appName, exchangeCount - 1)
                val grant = exchangeCount >= gatekeeperMinRounds
                applyGatekeeperRoundPolicy(
                    NegotiationResult(responseText = text, accessGranted = grant)
                )
            }
            NegotiationType.NUDGE -> {
                val appName = currentAppPackage.substringAfterLast('.')
                val text = PromptTemplates.fallbackNudgeResponse(appName, exchangeCount - 1)
                NegotiationResult(responseText = text)
            }
            NegotiationType.GENERAL, null -> NegotiationResult(
                "I'm running without an AI backend right now, so I can't launch apps from here. " +
                    "Tell me the app name and I can search installed packages for you.",
            )
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
        searchableApps = emptyList()
        lastSearchResults = emptyList()
        gatekeeperMinRounds = 0
        gatekeeperMaxRounds = 0
        usingBackend = false
        backendHistory.clear()
        backendTools = null
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

        val response = auth.generateWithAutoRefresh(
            model = backendModel,
            contents = backendHistory,
            tools = tools,
        )
        exchangeCount++

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
            when (fc.name) {
                "grantAccess" -> return NegotiationResult(
                    responseText = text.ifBlank { "Opening the app for you." },
                    accessGranted = true,
                )
                "grantExtension" -> {
                    val minutes = fc.args["minutes"]?.jsonPrimitive?.int ?: 10
                    return NegotiationResult(
                        responseText = text.ifBlank { "Extending your time by $minutes minutes." },
                        extensionMinutes = minutes,
                        accessGranted = true,
                    )
                }
                "launchApp" -> {
                    val pkg = (fc.args["packageName"] as? JsonPrimitive)?.content ?: ""
                    return NegotiationResult(
                        responseText = text.ifBlank { "Launching the app." },
                        launchedPackage = pkg,
                    )
                }
                "searchApps" -> {
                    val query = (fc.args["query"] as? JsonPrimitive)?.content ?: ""
                    val matches = searchInstalledApps(query)
                    lastSearchResults = matches
                    val summary = formatSearchSummary(query, matches)
                    return NegotiationResult(
                        responseText = if (text.isBlank()) summary else "$text\n\n$summary",
                    )
                }
                "queryRecentUsageSessions" -> {
                    val limit = fc.args["limit"]?.jsonPrimitive?.int ?: 5
                    val summary = buildUsageHistorySummary(currentAppPackage, limit)
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

    private fun searchInstalledApps(query: String, limit: Int = 6): List<SearchCandidate> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return searchableApps
            .asSequence()
            .filter { candidate ->
                val label = candidate.label.lowercase()
                val pkg = candidate.packageName.lowercase()
                label.contains(normalized) || pkg.contains(normalized)
            }
            .sortedBy { it.label.lowercase() }
            .take(limit)
            .toList()
    }

    private fun formatSearchSummary(query: String, matches: List<SearchCandidate>): String {
        if (matches.isEmpty()) {
            return "I couldn't find installed apps matching \"$query\"."
        }
        return buildString {
            append("I found these installed matches:\n")
            matches.forEachIndexed { index, match ->
                append("${index + 1}) ${match.label} (${match.packageName})\n")
            }
            append("Reply with the number, like 1 or 2.")
        }.trimEnd()
    }

    private fun parseSearchSelection(userMessage: String): SearchSelection? {
        if (lastSearchResults.isEmpty()) return null
        val normalized = userMessage.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return null

        // Accept direct numeric choices: "2", "option 2", "the 2nd", etc.
        val number = Regex("\\b(\\d{1,2})\\b")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val indexFromNumber = number?.minus(1)
        if (indexFromNumber != null && indexFromNumber in lastSearchResults.indices) {
            return SearchSelection(indexFromNumber, lastSearchResults[indexFromNumber])
        }

        val ordinalMap = listOf(
            "first" to 0,
            "second" to 1,
            "third" to 2,
            "fourth" to 3,
            "fifth" to 4,
            "sixth" to 5,
        )
        val ordinalIndex = ordinalMap.firstOrNull { (word, _) ->
            normalized.contains(word)
        }?.second
        if (ordinalIndex != null && ordinalIndex in lastSearchResults.indices) {
            return SearchSelection(ordinalIndex, lastSearchResults[ordinalIndex])
        }

        return null
    }

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

    private fun applyGatekeeperRoundPolicy(result: NegotiationResult): NegotiationResult {
        if (currentType != NegotiationType.GATEKEEPER) return result

        val minRounds = gatekeeperMinRounds.coerceAtLeast(0)
        val maxRounds = gatekeeperMaxRounds.coerceAtLeast(minRounds)
        if (exchangeCount < minRounds) {
            if (!result.accessGranted) return result
            return result.copy(
                responseText = result.responseText +
                    "\n\nOne more quick reflection before I open it.",
                accessGranted = false,
            )
        }

        if (exchangeCount >= maxRounds && !result.accessGranted) {
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
}
