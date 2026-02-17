package com.mindfulhome.ai.backend

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Manages authentication tokens and provides auto-refresh on 401.
 *
 * Ported from distraction-linter with the same contract:
 * - Store an app token obtained by exchanging a Google ID token
 * - On 401, clear the token, re-exchange, and retry once
 */
class BackendAuthHelper(
    private val signIn: suspend () -> String?,
    private val getAppToken: () -> String?,
    private val saveAppToken: (token: String, expiresAtMs: Long) -> Unit,
    private val clearAppToken: () -> Unit,
    private val getGoogleIdToken: () -> String?,
    private val checkAuthStatus: suspend (String) -> Unit = { token ->
        withContext(Dispatchers.IO) { BackendClient.checkAuthStatus(token) }
    },
) {

    companion object {
        private const val TAG = "BackendAuthHelper"
    }

    /** Whether we have a stored app token (may still be expired server-side). */
    val hasToken: Boolean get() = getAppToken() != null

    /**
     * Exchanges a Google ID token for a long-lived app token via the backend.
     * The Google ID token is NOT stored — only the resulting app token is kept.
     * Returns `true` on success, `false` on failure.
     */
    suspend fun exchangeGoogleToken(googleIdToken: String): Boolean {
        return try {
            val response = withContext(Dispatchers.IO) {
                BackendClient.exchangeToken(googleIdToken)
            }
            val expiresAtMs = BackendClient.parseExpiresAt(response.expiresAt)
            saveAppToken(response.token, expiresAtMs)
            Log.i(TAG, "Google token exchanged for app token successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            false
        }
    }

    /**
     * Calls the backend's generate endpoint with automatic token refresh.
     * Throws [BackendAuthException] if authentication is impossible.
     */
    suspend fun generateWithAutoRefresh(
        model: String,
        contents: List<BackendClient.BackendContent>,
        tools: List<Map<String, JsonElement>>? = null,
    ): BackendClient.GenerateResponse = withContext(Dispatchers.IO) {
        val token = ensureValidAppToken()
            ?: throw BackendAuthException("Unable to obtain authentication token")

        try {
            BackendClient.generate(token, model, contents, tools)
        } catch (e: BackendHttpException) {
            if (e.statusCode == 401) {
                Log.i(TAG, "Got 401, refreshing token and retrying")
                clearAppToken()
                val newToken = ensureValidAppToken()
                    ?: throw BackendAuthException("Unable to refresh authentication token")
                BackendClient.generate(newToken, model, contents, tools)
            } else {
                throw e
            }
        }
    }

    /**
     * Validates the stored token with the backend.
     * Returns `true` if valid, `false` if expired / rejected (clears token).
     */
    suspend fun checkTokenWithBackend(): Boolean = withContext(Dispatchers.IO) {
        val token = getAppToken() ?: return@withContext false
        try {
            checkAuthStatus(token)
            true
        } catch (e: BackendHttpException) {
            if (e.statusCode == 401) {
                clearAppToken()
                false
            } else {
                Log.w(TAG, "Auth status check failed with ${e.statusCode}, assuming valid", e)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auth status check failed, assuming valid", e)
            true
        }
    }

    /**
     * Returns a valid app token, exchanging if necessary.
     */
    private suspend fun ensureValidAppToken(): String? {
        getAppToken()?.let { return it }
        return exchangeForAppToken()
    }

    /**
     * Gets a Google ID token (triggering sign-in if needed) and exchanges
     * it for an app token via the backend.
     */
    private suspend fun exchangeForAppToken(): String? {
        val googleIdToken = getGoogleIdToken() ?: signIn()
        if (googleIdToken == null) {
            Log.w(TAG, "No Google ID token available")
            return null
        }

        return try {
            val response = withContext(Dispatchers.IO) {
                BackendClient.exchangeToken(googleIdToken)
            }
            val expiresAtMs = BackendClient.parseExpiresAt(response.expiresAt)
            saveAppToken(response.token, expiresAtMs)
            response.token
        } catch (e: BackendHttpException) {
            if (e.statusCode == 401) {
                // Google ID token rejected — try signing in fresh
                Log.i(TAG, "Google ID token rejected, triggering fresh sign-in")
                val freshToken = signIn() ?: return null
                try {
                    val response = withContext(Dispatchers.IO) {
                        BackendClient.exchangeToken(freshToken)
                    }
                    val expiresAtMs = BackendClient.parseExpiresAt(response.expiresAt)
                    saveAppToken(response.token, expiresAtMs)
                    response.token
                } catch (e2: Exception) {
                    Log.e(TAG, "Token exchange failed after fresh sign-in", e2)
                    null
                }
            } else {
                Log.e(TAG, "Token exchange failed: ${e.statusCode} ${e.message}", e)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            null
        }
    }
}

class BackendAuthException(message: String) : Exception(message)
