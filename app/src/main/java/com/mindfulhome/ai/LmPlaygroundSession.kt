package com.mindfulhome.ai

import com.druk.lmplayground.api.LmPlaygroundClient
import com.druk.lmplayground.api.model.ApiException
import com.druk.lmplayground.api.model.ChatCompletion
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.LmpRequestOptions
import com.druk.lmplayground.api.model.Role
import com.druk.lmplayground.api.model.ToolCall
import com.druk.lmplayground.api.model.ToolDefinition
import kotlinx.coroutines.delay

/**
 * Stateful chat against LM Playground: full history on each request, client-side tools.
 */
class LmPlaygroundSession(
    private val client: LmPlaygroundClient,
    systemInstruction: String,
    private val toolSets: List<LocalLmToolSet>,
) {
    private val messages = mutableListOf(ChatMessage(Role.SYSTEM, systemInstruction))
    private var continuationToken: String? = null
    private var loadedSupportsTools: Boolean? = null

    suspend fun send(userMessage: String): String {
        messages += ChatMessage(Role.USER, userMessage)
        refreshToolCapability()
        var lastText = ""
        repeat(MAX_TOOL_ROUNDS) {
            val completion = awaitCompletion()
            continuationToken = completion.lmp.continuationToken
            messages += completion.message
            lastText = completion.message.textContent().ifBlank { lastText }
            val calls = completion.message.toolCalls
            if (!needsToolRound(completion, calls)) {
                return lastText
            }
            appendToolResults(calls)
        }
        return lastText
    }

    private suspend fun awaitCompletion(): ChatCompletion {
        val request = buildRequest()
        return try {
            client.chatCompletionAwait(request)
        } catch (e: ApiException) {
            if (e.error.type != ErrorType.ENGINE_BUSY) throw e
            val waitMs = e.error.retryAfterMs?.coerceIn(50L, 5_000L) ?: 500L
            delay(waitMs)
            client.chatCompletionAwait(request)
        }
    }

    private suspend fun refreshToolCapability() {
        if (loadedSupportsTools != null) return
        val list = runCatching { client.listModels() }.getOrNull() ?: return
        val loaded = list.models.firstOrNull { it.loaded } ?: return
        loadedSupportsTools = loaded.capabilities.tools
    }

    private fun needsToolRound(completion: ChatCompletion, calls: List<ToolCall>): Boolean =
        completion.finishReason == FINISH_TOOL_CALLS && calls.isNotEmpty()

    private suspend fun appendToolResults(calls: List<ToolCall>) {
        calls.forEach { call ->
            val result = invokeTool(call.name, call.arguments)
            messages += ChatMessage(
                role = Role.TOOL,
                content = result,
                toolCallId = call.id,
            )
        }
    }

    private suspend fun invokeTool(name: String, argumentsJson: String): String {
        val owner = toolSets.firstOrNull { set ->
            set.definitions().any { it.name == name }
        }
        return owner?.invoke(name, argumentsJson)
            ?: LocalLmToolLogic.unknownToolResult(name)
    }

    private fun buildRequest(): ChatCompletionRequest {
        val tools = LmPlaygroundSessionLogic.toolsToSend(
            definitions = toolDefinitions(),
            loadedSupportsTools = loadedSupportsTools,
        )
        return ChatCompletionRequest(
            messages = messages.toList(),
            model = null,
            stream = false,
            tools = tools,
            lmp = LmpRequestOptions(
                allowLoad = true,
                clientLabel = CLIENT_LABEL,
                continuationToken = continuationToken,
            ),
        )
    }

    private fun toolDefinitions(): List<ToolDefinition> =
        toolSets.flatMap { it.definitions() }

    companion object {
        private const val MAX_TOOL_ROUNDS = 6
        private const val FINISH_TOOL_CALLS = "tool_calls"
        private const val CLIENT_LABEL = "MindfulHome"
    }
}
