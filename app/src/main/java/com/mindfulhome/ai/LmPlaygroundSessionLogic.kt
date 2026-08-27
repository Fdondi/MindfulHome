package com.mindfulhome.ai

import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.ToolDefinition

/**
 * Request/error decisions for the LM Playground on-device path.
 */
object LmPlaygroundSessionLogic {

    fun toolsToSend(
        definitions: List<ToolDefinition>,
        loadedSupportsTools: Boolean?,
    ): List<ToolDefinition> {
        if (definitions.isEmpty()) return emptyList()
        if (loadedSupportsTools == false) return emptyList()
        return definitions
    }

    fun userFacingError(error: ApiError): String {
        val detail = error.message.trim()
        return when (error.type) {
            ErrorType.PERMISSION_DENIED ->
                detail.ifBlank {
                    "LM Playground has blocked other apps. Enable Settings → Advanced → Allow other apps."
                }
            ErrorType.NO_MODEL_LOADED, ErrorType.NO_MODEL_AVAILABLE ->
                detail.ifBlank { "No on-device model is ready in LM Playground." }
            ErrorType.ENGINE_BUSY ->
                detail.ifBlank { "LM Playground is busy. Please try again in a moment." }
            ErrorType.ENGINE_UNAVAILABLE ->
                detail.ifBlank { "LM Playground disconnected." }
            ErrorType.CAPABILITY_UNAVAILABLE ->
                detail.ifBlank { "The on-device model cannot handle this request." }
            ErrorType.CANCELLED ->
                detail.ifBlank { GENERIC_FAILURE }
            else -> detail.ifBlank { GENERIC_FAILURE }
        }
    }

    fun isCannedThinkFailure(text: String): Boolean =
        text.trim() == GENERIC_FAILURE

    /**
     * True when the local path should be abandoned. Tool-only assistant
     * messages have null [com.druk.lmplayground.api.model.ChatMessage.content];
     * those are usable if a tool already applied.
     */
    fun isUnusableLocalReply(text: String, toolsApplied: Boolean = false): Boolean {
        if (isCannedThinkFailure(text)) return true
        if (toolsApplied) return false
        return text.isBlank()
    }

    fun announceLocalFailureThenScript(notice: String?, script: String): String {
        val n = notice?.trim().orEmpty()
        val s = script.trim()
        return when {
            n.isNotEmpty() && s.isNotEmpty() -> "$n\n\n$s"
            n.isNotEmpty() -> n
            else -> s
        }
    }

    const val GENERIC_FAILURE = "I'm having trouble thinking right now. Please try again."
}
