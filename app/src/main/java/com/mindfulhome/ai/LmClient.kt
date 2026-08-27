package com.mindfulhome.ai

/**
 * On-device generate failed. [userNotice] is safe to show; callers should then
 * fall back to the scripted path instead of treating this as a model reply.
 */
class LocalLmFailure(
    val userNotice: String,
    cause: Throwable? = null,
) : Exception(userNotice, cause)

/**
 * Narrow LLM surface used by [NegotiationManager].
 *
 * Conversation handles are opaque ([Any]) so unit tests can fake the client
 * without depending on LM Playground types.
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

/** Adapts [LmPlaygroundManager] to [LmClient]. */
class LmPlaygroundLmClient(
    private val manager: LmPlaygroundManager,
) : LmClient {
    override val modelReady: Boolean
        get() = manager.modelReady

    override fun createConversation(
        systemInstruction: String,
        toolSets: List<*>,
    ): Any? {
        val typed = toolSets.filterIsInstance<LocalLmToolSet>()
        return manager.createConversation(systemInstruction, toolSets = typed)
    }

    override suspend fun sendMessage(conversation: Any, message: String): String =
        manager.sendMessage(conversation as LmPlaygroundSession, message)

    override fun closeConversation(conversation: Any) {
        // History lives in [LmPlaygroundSession]; dropping the handle is enough.
    }
}
