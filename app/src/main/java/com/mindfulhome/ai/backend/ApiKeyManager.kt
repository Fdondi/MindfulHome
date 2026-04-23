package com.mindfulhome.ai.backend

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject

/**
 * Secure storage for backend authentication credentials.
 *
 * Two token kinds live here:
 *   - **Session token**: long-lived (30 days) backend-issued JWT. Used as the
 *     Bearer credential for every call to `/api/generate` and similar.
 *   - **Google ID token**: short-lived (1h) OIDC token. Only used transiently
 *     during `/api/auth/exchange` to bootstrap or re-establish a session;
 *     never sent to the `/api/generate` hot path.
 *
 * Storing the Google token is mostly vestigial (kept for migration), since in
 * steady state the app holds only a session token and talks to Google once per
 * ~30 days (or after sign-out).
 */
object ApiKeyManager {

    private const val TAG = "ApiKeyManager"
    private const val PREFS_NAME = "mindfulhome_auth"
    // Legacy Google ID token storage (pre-session architecture). Kept so we can
    // migrate existing installs — a stale ID token here will be used exactly
    // once to call /api/auth/exchange, then ignored forever after.
    private const val KEY_GOOGLE_ID_TOKEN = "google_id_token"
    private const val KEY_GOOGLE_ID_TOKEN_EXPIRES_MS = "google_id_token_expires_ms"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_SESSION_EXPIRES_MS = "session_expires_ms"
    private const val KEY_SIGNED_IN_EMAIL = "signed_in_email"

    /** Ignore expiry this many ms early so we refresh before the server rejects the token. */
    private const val EXPIRY_SKEW_MS = 60_000L

    /**
     * When the session has this much life remaining (or less), proactively call
     * /api/auth/refresh on the next opportunity to extend it. 7 days is aligned
     * with the backend's SESSION_REFRESH_WINDOW_DAYS default.
     */
    private const val SESSION_REFRESH_WINDOW_MS = 7L * 24L * 3600L * 1000L

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun parseJwtExpiryMs(idToken: String): Long? {
        val parts = idToken.split(".")
        if (parts.size < 2) return null
        val segment = parts[1]
        val padded = segment + "=".repeat((4 - segment.length % 4) % 4)
        return try {
            val json = String(
                Base64.decode(padded, Base64.URL_SAFE),
                Charsets.UTF_8,
            )
            val expSec = JSONObject(json).optLong("exp", 0L)
            if (expSec <= 0L) null else expSec * 1000L
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Normalizes backend expiry values to unix milliseconds.
     *
     * Backends may send:
     * - absolute unix seconds (preferred),
     * - absolute unix milliseconds,
     * - relative TTL seconds.
     *
     * We accept all three to avoid immediately invalidating fresh sessions.
     */
    private fun normalizeSessionExpiryMs(expiresAt: Long): Long {
        if (expiresAt <= 0L) return 0L
        val now = System.currentTimeMillis()
        return when {
            // Already unix milliseconds.
            expiresAt >= 1_000_000_000_000L -> expiresAt
            // Unix seconds (roughly year 2001+).
            expiresAt >= 1_000_000_000L -> expiresAt * 1000L
            // Small value likely means TTL seconds.
            else -> now + (expiresAt * 1000L)
        }
    }

    // ── Session token (preferred) ─────────────────────────────────────

    /**
     * Returns the backend session token if it's present and not past its expiry
     * (with skew). Returns null otherwise; callers should re-exchange.
     */
    fun getSessionToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(KEY_SESSION_TOKEN, null) ?: return null
        val expiresMs = p.getLong(KEY_SESSION_EXPIRES_MS, 0L)
        val now = System.currentTimeMillis()
        if (expiresMs > 0L && now >= expiresMs - EXPIRY_SKEW_MS) {
            clearSessionToken(context)
            return null
        }
        return token
    }

    /**
     * Persists a backend session token. [expiresAtUnixSeconds] comes from the
     * `/api/auth/exchange` response so we don't need to parse our own JWT here.
     */
    fun saveSessionToken(context: Context, token: String, expiresAtUnixSeconds: Long) {
        val normalizedExpiryMs = normalizeSessionExpiryMs(expiresAtUnixSeconds)
        prefs(context).edit()
            .putString(KEY_SESSION_TOKEN, token)
            .putLong(KEY_SESSION_EXPIRES_MS, normalizedExpiryMs)
            .apply()
    }

    fun clearSessionToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_SESSION_EXPIRES_MS)
            .apply()
    }

    /**
     * True if a session is stored and its remaining lifetime is under the refresh
     * window, meaning we should proactively call `/api/auth/refresh` on the next
     * backend round-trip. Returns false when no session exists (that case needs
     * a full exchange, not a refresh).
     */
    fun isSessionExpiringSoon(context: Context): Boolean {
        val p = prefs(context)
        if (p.getString(KEY_SESSION_TOKEN, null) == null) return false
        val expiresMs = p.getLong(KEY_SESSION_EXPIRES_MS, 0L)
        if (expiresMs <= 0L) return false
        return System.currentTimeMillis() >= expiresMs - SESSION_REFRESH_WINDOW_MS
    }

    // ── Google ID token (bootstrap only) ──────────────────────────────

    /**
     * Returns a non-expired Google ID token for bootstrapping a session via
     * `/api/auth/exchange`. Not for use as a Bearer on the hot path.
     */
    fun getGoogleIdToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(KEY_GOOGLE_ID_TOKEN, null) ?: return null
        val expiresMs = p.getLong(KEY_GOOGLE_ID_TOKEN_EXPIRES_MS, 0L)
        val now = System.currentTimeMillis()
        if (expiresMs > 0L && now >= expiresMs - EXPIRY_SKEW_MS) {
            clearGoogleIdToken(context)
            return null
        }
        if (expiresMs <= 0L) {
            val parsed = parseJwtExpiryMs(token)
            if (parsed != null) {
                p.edit().putLong(KEY_GOOGLE_ID_TOKEN_EXPIRES_MS, parsed).apply()
                if (now >= parsed - EXPIRY_SKEW_MS) {
                    clearGoogleIdToken(context)
                    return null
                }
            }
        }
        return token
    }

    fun saveGoogleIdToken(context: Context, token: String) {
        val expMs = parseJwtExpiryMs(token) ?: 0L
        prefs(context).edit()
            .putString(KEY_GOOGLE_ID_TOKEN, token)
            .putLong(KEY_GOOGLE_ID_TOKEN_EXPIRES_MS, expMs)
            .apply()
    }

    fun clearGoogleIdToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_GOOGLE_ID_TOKEN)
            .remove(KEY_GOOGLE_ID_TOKEN_EXPIRES_MS)
            .apply()
    }

    // ── Identity / sign-out ───────────────────────────────────────────

    fun getSignedInEmail(context: Context): String? =
        prefs(context).getString(KEY_SIGNED_IN_EMAIL, null)

    fun saveSignedInEmail(context: Context, email: String) {
        prefs(context).edit()
            .putString(KEY_SIGNED_IN_EMAIL, email)
            .apply()
    }

    /** Whether the app has a usable backend session (the only thing that matters for API calls). */
    fun isSignedIn(context: Context): Boolean = getSessionToken(context) != null

    /** Clears all stored credentials. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
