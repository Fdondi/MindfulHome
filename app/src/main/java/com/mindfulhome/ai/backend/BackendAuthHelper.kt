package com.mindfulhome.ai.backend

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Session-token lifecycle for backend calls.
 *
 * Steady state: the app holds a backend session JWT (~30 day lifetime) and
 * uses it as the Bearer on every API call. Google is contacted only at:
 *   - first sign-in (new install / after sign-out),
 *   - after a session expires (fallback when refresh couldn't run in time).
 *
 * On every backend call the helper:
 *   1. Ensures a valid session token is stored, minting one via
 *      `/api/auth/exchange` if necessary (which may require an interactive
 *      Google sign-in to obtain a fresh ID token).
 *   2. Opportunistically calls `/api/auth/refresh` when the session is close
 *      to expiry.
 *   3. On a 401 from the API, clears the session, runs sign-in+exchange once,
 *      and retries a single time.
 */
class BackendAuthHelper(
    /**
     * Returns a fresh Google ID token usable by `/api/auth/exchange`. The caller
     * is responsible for showing any UI (silent sign-in first, interactive
     * fallback if needed). Returns null if the user cancels or no account is
     * available.
     */
    private val signInForExchange: suspend () -> String?,
    private val getSessionToken: () -> String?,
    private val saveSessionToken: (token: String, expiresAtUnixSeconds: Long) -> Unit,
    private val clearSessionToken: () -> Unit,
    private val isSessionExpiringSoon: () -> Boolean = { false },
    private val exchangeGoogleToken: suspend (String) -> BackendClient.SessionResponse = { idToken ->
        withContext(Dispatchers.IO) { BackendClient.exchange(idToken) }
    },
    private val refreshSession: suspend (String) -> BackendClient.SessionResponse = { token ->
        withContext(Dispatchers.IO) { BackendClient.refreshSession(token) }
    },
    private val checkAuthStatus: suspend (String) -> Unit = { token ->
        withContext(Dispatchers.IO) { BackendClient.checkAuthStatus(token) }
    },
) {

    companion object {
        private const val TAG = "BackendAuthHelper"
    }

    /** True if a non-expired session is currently stored. */
    val hasToken: Boolean get() = getSessionToken() != null

    /**
     * Exchanges a Google ID token (freshly obtained from Credential Manager)
     * for a backend session, validates the session with `/api/auth/status`,
     * and persists it. Returns `false` if the backend rejects (e.g. PENDING
     * approval, REFUSED access, or a transient failure).
     */
    suspend fun completeBackendSignIn(googleIdToken: String): Boolean {
        return try {
            val session = exchangeGoogleToken(googleIdToken)
            saveSessionToken(session.session_token, session.expires_at)
            Log.i(TAG, "Session established (expires_at=${session.expires_at})")
            true
        } catch (e: BackendHttpException) {
            Log.e(TAG, "Exchange rejected by backend: ${e.statusCode} ${e.code}", e)
            // On 401 the exchange itself failed (shouldn't happen for a valid
            // Google token), so clear any stale session just in case.
            if (e.statusCode == 401) clearSessionToken()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exchange call failed", e)
            false
        }
    }

    /**
     * Performs an `/api/generate` call using the stored session token,
     * transparently handling:
     *   - missing session ⇒ sign-in + exchange,
     *   - session nearing expiry ⇒ `/api/auth/refresh`,
     *   - 401 from generate ⇒ clear + sign-in + exchange + retry once.
     */
    suspend fun generateWithAutoRefresh(
        model: String,
        contents: List<BackendClient.BackendContent>,
        tools: List<Map<String, JsonElement>>? = null,
    ): BackendClient.GenerateResponse = withContext(Dispatchers.IO) {
        val token = ensureSessionToken()
            ?: throw BackendAuthException("Unable to obtain authentication token")

        try {
            BackendClient.generate(token, model, contents, tools)
        } catch (e: BackendHttpException) {
            if (e.statusCode == 401) {
                Log.i(TAG, "Got 401 on generate, clearing session and re-exchanging once")
                clearSessionToken()
                val newToken = ensureSessionToken()
                    ?: throw BackendAuthException("Unable to refresh authentication token")
                BackendClient.generate(newToken, model, contents, tools)
            } else {
                throw e
            }
        }
    }

    /**
     * Lightweight liveness check. Cheap on the backend (just dep + Firestore
     * for defense-in-depth), useful right after sign-in so the UI can fail
     * fast on PENDING_APPROVAL / ACCESS_REFUSED.
     */
    suspend fun checkTokenWithBackend(): Boolean = withContext(Dispatchers.IO) {
        val token = getSessionToken() ?: return@withContext false
        try {
            checkAuthStatus(token)
            true
        } catch (e: BackendHttpException) {
            if (e.statusCode == 401) {
                clearSessionToken()
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
     * Ensures [getSessionToken] will return a valid token, attempting in order:
     *   1. use the stored token (refreshing in the background if nearing exp),
     *   2. sign in with Google and call /api/auth/exchange.
     * Returns the active session token or null if sign-in was unavailable.
     */
    private suspend fun ensureSessionToken(): String? {
        getSessionToken()?.let { existing ->
            if (isSessionExpiringSoon()) {
                // Best-effort refresh; if it fails we keep using the still-valid
                // current token until it actually expires, then fall back to
                // full re-exchange.
                try {
                    val refreshed = refreshSession(existing)
                    saveSessionToken(refreshed.session_token, refreshed.expires_at)
                    return refreshed.session_token
                } catch (e: Exception) {
                    Log.w(TAG, "Session refresh failed; continuing with existing token", e)
                }
            }
            return existing
        }

        val googleIdToken = signInForExchange() ?: return null
        return try {
            val session = exchangeGoogleToken(googleIdToken)
            saveSessionToken(session.session_token, session.expires_at)
            session.session_token
        } catch (e: BackendHttpException) {
            Log.e(TAG, "Exchange failed: ${e.statusCode} ${e.code}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exchange transport error", e)
            null
        }
    }

    /**
     * Proactively refreshes the session if it's close to expiry and a valid
     * one is present. Safe to call at idle moments (e.g. when the Timer screen
     * mounts) to avoid refresh-during-send stalls.
     */
    suspend fun refreshIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val token = getSessionToken() ?: return@withContext false
        if (!isSessionExpiringSoon()) return@withContext false
        try {
            val refreshed = refreshSession(token)
            saveSessionToken(refreshed.session_token, refreshed.expires_at)
            true
        } catch (e: BackendHttpException) {
            Log.w(TAG, "Background refresh failed (${e.statusCode})", e)
            if (e.statusCode == 401) clearSessionToken()
            false
        } catch (e: Exception) {
            Log.w(TAG, "Background refresh failed transport-side", e)
            false
        }
    }
}

class BackendAuthException(message: String) : Exception(message)
