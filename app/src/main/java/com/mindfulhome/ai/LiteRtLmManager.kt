package com.mindfulhome.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File

class LiteRtLmManager(private val context: Context) {

    private var engine: Engine? = null
    private var isInitialized = false

    val modelReady: Boolean
        get() = isInitialized && engine != null

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelFile = getModelFile()
            if (modelFile == null || !modelFile.exists()) {
                Log.w(TAG, "No model file found. AI features will use fallback responses.")
                return@withContext false
            }

            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU,
                cacheDir = context.cacheDir.path
            )

            engine = Engine(config).also { it.initialize() }
            isInitialized = true
            Log.i(TAG, "LiteRT-LM engine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LiteRT-LM engine", e)
            false
        }
    }

    fun createConversation(
        systemInstruction: String,
        toolSets: List<ToolSet> = emptyList(),
        initialMessages: List<Message> = emptyList()
    ): Conversation? {
        val eng = engine ?: return null

        val config = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction),
            samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.7),
            tools = toolSets.map { tool(it) },
            initialMessages = initialMessages
        )

        return eng.createConversation(config)
    }

    suspend fun sendMessage(conversation: Conversation, message: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val response = conversation.sendMessage(message)
                response.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message to LLM", e)
                "I'm having trouble thinking right now. Please try again."
            }
        }
    }

    fun sendMessageStreaming(conversation: Conversation, message: String): Flow<String> {
        return try {
            conversation.sendMessageAsync(message)
                .let { messageFlow ->
                    flow {
                        messageFlow.collect { msg ->
                            emit(msg.toString())
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming message from LLM", e)
            flowOf("I'm having trouble thinking right now. Please try again.")
        }
    }

    fun shutdown() {
        try {
            engine?.close()
            engine = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down LiteRT-LM engine", e)
        }
    }

    private fun getModelFile(): File? {
        for (dir in modelSearchDirs(context)) {
            if (!dir.exists()) continue
            val found = dir.listFiles()?.firstOrNull { it.name.endsWith(".litertlm") }
            if (found != null) {
                Log.i(TAG, "Found model at ${found.absolutePath}")
                return found
            }
        }
        // Ensure the app-private dir exists for future use
        File(context.filesDir, "models").mkdirs()
        return null
    }

    companion object {
        private const val TAG = "LiteRtLmManager"

        /** Shared location that can be written via `adb push` without root. */
        val SHARED_MODEL_DIR = File("/data/local/tmp/llm")

        /** All directories we search for a model, in priority order. */
        private fun modelSearchDirs(context: Context): List<File> = listOf(
            File(context.filesDir, "models"),
            SHARED_MODEL_DIR
        )

        fun getModelsDirectory(context: Context): File {
            return File(context.filesDir, "models").also { it.mkdirs() }
        }

        fun hasModel(context: Context): Boolean {
            return modelSearchDirs(context).any { dir ->
                dir.exists() &&
                    dir.listFiles()?.any { it.name.endsWith(".litertlm") } == true
            }
        }
    }
}
