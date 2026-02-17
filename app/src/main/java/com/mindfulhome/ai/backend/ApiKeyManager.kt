package com.mindfulhome.ai.backend

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Secure storage for authentication tokens using EncryptedSharedPreferences.
 */
object ApiKeyManager {

    private const val TAG = "ApiKeyManager"
    private const val PREFS_NAME = "mindfulhome_auth"
    private const val KEY_APP_TOKEN = "app_token"
    private const val KEY_APP_TOKEN_EXPIRES = "app_token_expires"
    private const val KEY_GOOGLE_ID_TOKEN = "google_id_token"
    private const val KEY_SIGNED_IN_EMAIL = "signed_in_email"

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

    // ── App token (long-lived, obtained from backend) ────────────────

    fun getAppToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(KEY_APP_TOKEN, null) ?: return null
        val expires = p.getLong(KEY_APP_TOKEN_EXPIRES, 0L)
        if (expires > 0 && System.currentTimeMillis() > expires) {
            clearAppToken(context)
            return null
        }
        return token
    }

    fun saveAppToken(context: Context, token: String, expiresAtMs: Long) {
        prefs(context).edit()
            .putString(KEY_APP_TOKEN, token)
            .putLong(KEY_APP_TOKEN_EXPIRES, expiresAtMs)
            .apply()
    }

    fun clearAppToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_APP_TOKEN)
            .remove(KEY_APP_TOKEN_EXPIRES)
            .apply()
    }

    // ── Google ID token (short-lived, from sign-in) ──────────────────

    fun getGoogleIdToken(context: Context): String? {
        return prefs(context).getString(KEY_GOOGLE_ID_TOKEN, null)
    }

    fun saveGoogleIdToken(context: Context, token: String) {
        prefs(context).edit()
            .putString(KEY_GOOGLE_ID_TOKEN, token)
            .apply()
    }

    fun clearGoogleIdToken(context: Context) {
        prefs(context).edit()
            .remove(KEY_GOOGLE_ID_TOKEN)
            .apply()
    }

    // ── Signed-in email ────────────────────────────────────────────────

    fun getSignedInEmail(context: Context): String? =
        prefs(context).getString(KEY_SIGNED_IN_EMAIL, null)

    fun saveSignedInEmail(context: Context, email: String) {
        prefs(context).edit()
            .putString(KEY_SIGNED_IN_EMAIL, email)
            .apply()
    }

    /** Whether the user has a valid app token from the backend. */
    fun isSignedIn(context: Context): Boolean {
        return getAppToken(context) != null
    }

    /** Clears all stored tokens. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
