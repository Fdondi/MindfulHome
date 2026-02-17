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
    private var currentType: NegotiationType? = null
    private var exchangeCount = 0
    private var currentAppPackage: String = ""

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
    ): NegotiationResult = withContext(Dispatchers.IO) {
        currentAppPackage = packageName
        currentType = NegotiationType.GATEKEEPER
        exchangeCount = 0

        val karma = repository.getKarma(packageName)

        val systemPrompt = PromptTemplates.gatekeeperSystemPrompt()
        val userContext = PromptTemplates.buildGatekeeperUserContext(
            appName = appName,
            karmaScore = karma.karmaScore,
            totalOpens = karma.totalOpens,
            totalOverruns = karma.totalOverruns,
            timesRequestedToday = 0,
        )

        // Try backend first
        if (backendAuth != null && backendAuth.hasToken) {
            try {
                val result = startBackendConversation(
                    systemPrompt, userContext, BackendToolDeclarations.GATEKEEPER_TOOLS,
                )
                if (result != null) return@withContext result
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
        val grant = PromptTemplates.fallbackShouldGrantAccess(exchangeCount - 1)
        NegotiationResult(responseText = text, accessGranted = grant)
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

                // Auto-relent for gatekeeper after enough exchanges
                if (currentType == NegotiationType.GATEKEEPER &&
                    exchangeCount >= 3 && !result.accessGranted
                ) {
                    return@withContext NegotiationResult(
                        responseText = "$text\n\nAlright, I can see you've made up your mind. Go ahead.",
                        accessGranted = true,
                    )
                }

                return@withContext result
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

                if (currentType == NegotiationType.GATEKEEPER && exchangeCount >= 3) {
                    val tools = gatekeeperTools
                    if (tools != null && !tools.accessGranted) {
                        tools.reset()
                        tools.grantAccess()
                        return@withContext NegotiationResult(
                            responseText = "$response\n\nAlright, I can see you've made up your mind. Go ahead.",
                            accessGranted = true,
                        )
                    }
                }

                return@withContext when (currentType) {
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
                val grant = PromptTemplates.fallbackShouldGrantAccess(exchangeCount - 1)
                NegotiationResult(responseText = text, accessGranted = grant)
            }
            NegotiationType.NUDGE -> {
                val appName = currentAppPackage.substringAfterLast('.')
                val text = PromptTemplates.fallbackNudgeResponse(appName, exchangeCount - 1)
                NegotiationResult(responseText = text)
            }
            NegotiationType.GENERAL, null -> NegotiationResult(
                "I'm running without an AI backend right now, so I can't launch apps from here. " +
                    "Press back and use the search button on the home screen to find any app.",
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
    private fun parseBackendResult(
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

    companion object {
        private const val TAG = "NegotiationManager"
    }
}
