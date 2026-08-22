package com.mindfulhome.ai

import android.content.Context
import android.util.Log
import com.druk.lmplayground.api.LmPlaygroundApi
import com.druk.lmplayground.api.LmPlaygroundClient
import com.druk.lmplayground.api.model.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device LLM via [LM Playground](https://github.com/Fdondi/LMPlayground-server)
 * OpenAI-shaped AIDL. Does not load a model in-process.
 */
class LmPlaygroundManager(context: Context) {

    private val appContext = context.applicationContext
    private val client = LmPlaygroundClient(appContext)

    val modelReady: Boolean
        get() = client.state.value is LmPlaygroundClient.State.Connected

    val unavailableReason: LmPlaygroundClient.State.Reason?
        get() = (client.state.value as? LmPlaygroundClient.State.Unavailable)?.reason

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val info = client.connect()
            if (info == null) {
                Log.w(TAG, "LM Playground unavailable: $unavailableReason")
                return@withContext false
            }
            Log.i(TAG, "Connected to LM Playground ${info.appVersionName} api=${info.apiVersion}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to LM Playground", e)
            false
        }
    }

    fun createConversation(
        systemInstruction: String,
        toolSets: List<LocalLmToolSet> = emptyList(),
    ): LmPlaygroundSession? {
        if (!modelReady) return null
        return LmPlaygroundSession(client, systemInstruction, toolSets)
    }

    suspend fun sendMessage(session: LmPlaygroundSession, message: String): String {
        return try {
            session.send(message)
        } catch (e: ApiException) {
            Log.e(TAG, "LM Playground request failed type=${e.error.type} code=${e.error.code}", e)
            LmPlaygroundSessionLogic.userFacingError(e.error)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to LM Playground", e)
            LmPlaygroundSessionLogic.GENERIC_FAILURE
        }
    }

    fun shutdown() {
        try {
            client.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from LM Playground", e)
        }
    }

    companion object {
        private const val TAG = "LmPlaygroundManager"

        const val SOURCE_URL = "https://github.com/Fdondi/LMPlayground-server"
        const val PLAY_STORE_PACKAGE = LmPlaygroundApi.PLAY_STORE_PACKAGE
        const val PLAY_STORE_MARKET_URI = "market://details?id=$PLAY_STORE_PACKAGE"
        const val PLAY_STORE_WEB_URI =
            "https://play.google.com/store/apps/details?id=$PLAY_STORE_PACKAGE"

        fun isInstalled(context: Context): Boolean =
            LmPlaygroundClient(context).discover().isNotEmpty()
    }
}
