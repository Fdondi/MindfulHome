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
            else -> detail.ifBlank { GENERIC_FAILURE }
        }
    }

    const val GENERIC_FAILURE = "I'm having trouble thinking right now. Please try again."
}
