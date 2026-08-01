package com.mindfulhome.ai

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ToolSet

/**
 * Narrow LLM surface used by [NegotiationManager].
 *
 * Conversation handles are opaque ([Any]) so unit tests can fake the client
 * without depending on LiteRT types.
 */
interface LmClient {
    val modelReady: Boolean

    /** Returns an opaque conversation handle, or null if unavailable. */
    fun createConversation(
        systemInstruction: String,
        toolSets: List<*> = emptyList<Any>(),
    ): Any?

    suspend fun sendMessage(conversation: Any, message: String): String

    fun closeConversation(conversation: Any)
}

/** Adapts [LiteRtLmManager] to [LmClient]. */
class LiteRtLmClient(
    private val manager: LiteRtLmManager,
) : LmClient {
    override val modelReady: Boolean
        get() = manager.modelReady

    override fun createConversation(
        systemInstruction: String,
        toolSets: List<*>,
    ): Any? {
        val typed = toolSets.filterIsInstance<ToolSet>()
        return manager.createConversation(systemInstruction, toolSets = typed)
    }

    override suspend fun sendMessage(conversation: Any, message: String): String =
        manager.sendMessage(conversation as Conversation, message)

    override fun closeConversation(conversation: Any) {
        (conversation as Conversation).close()
    }
}
