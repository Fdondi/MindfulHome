package com.mindfulhome.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simple fake [LmClient] for unit tests. */
class FakeLmClient(
    override var modelReady: Boolean = true,
    private val reply: String = "fake on-device reply",
) : LmClient {
    var lastSystemInstruction: String? = null
    var lastUserMessage: String? = null
    var closed = false
    private var conversation: Any? = Any()

    override fun createConversation(systemInstruction: String, toolSets: List<*>): Any? {
        lastSystemInstruction = systemInstruction
        return conversation
    }

    override suspend fun sendMessage(conversation: Any, message: String): String {
        lastUserMessage = message
        return reply
    }

    override fun closeConversation(conversation: Any) {
        closed = true
    }
}

class LmClientFakeTest {

    @Test
    fun fakeLmClient_createAndSend() = kotlinx.coroutines.runBlocking {
        val client = FakeLmClient(reply = "pong")
        assertTrue(client.modelReady)
        val handle = client.createConversation("system")
        assertTrue(handle != null)
        assertEquals("system", client.lastSystemInstruction)
        assertEquals("pong", client.sendMessage(handle!!, "ping"))
        assertEquals("ping", client.lastUserMessage)
        client.closeConversation(handle)
        assertTrue(client.closed)
    }

    @Test
    fun fakeLmClient_notReady_stillReturnsHandleIfCreated() {
        val client = FakeLmClient(modelReady = false)
        assertFalse(client.modelReady)
        // createConversation itself does not gate on modelReady in the interface
        assertNull(
            FakeLmClient(modelReady = false).let { fake ->
                // Override to return null when not ready — simulate LiteRT behavior via subclass
                object : LmClient by fake {
                    override fun createConversation(systemInstruction: String, toolSets: List<*>): Any? =
                        if (modelReady) fake.createConversation(systemInstruction, toolSets) else null
                }.createConversation("x")
            },
        )
    }
}
