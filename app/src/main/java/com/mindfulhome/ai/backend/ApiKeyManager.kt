package com.mindfulhome.ai.backend

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Secure storage for backend authentication credentials using DataStore + Tink.
 */
object ApiKeyManager {

    private const val TAG = "ApiKeyManager"
    private const val DATASTORE_FILE_NAME = "auth_prefs.pb"
    private const val KEYSET_NAME = "auth_keyset"
    private const val PREFERENCE_NAME = "auth_master_key"
    private const val MASTER_KEY_URI = "android-keystore://auth_master_key"

    /** Ignore expiry this many ms early so we refresh before the server rejects the token. */
    private const val EXPIRY_SKEW_MS = 60_000L

    /**
     * When the session has this much life remaining (or less), proactively call
     * /api/auth/refresh on the next opportunity to extend it.
     */
    private const val SESSION_REFRESH_WINDOW_MS = 7L * 24L * 3600L * 1000L

    @Volatile
    private var dataStore: DataStore<AuthData>? = null

    private fun getDataStore(context: Context): DataStore<AuthData> {
        return dataStore ?: synchronized(this) {
            dataStore ?: createDataStore(context).also { dataStore = it }
        }
    }

    private fun createDataStore(context: Context): DataStore<AuthData> {
        AeadConfig.register()
        val aead = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREFERENCE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)

        return DataStoreFactory.create(
            serializer = AuthDataSerializer(aead),
            produceFile = { File(context.filesDir, "datastore/$DATASTORE_FILE_NAME") },
            corruptionHandler = ReplaceFileCorruptionHandler {
                Log.e(TAG, "DataStore corruption detected, falling back to default")
                AuthData()
            }
        )
    }

    private fun normalizeSessionExpiryMs(expiresAt: Long): Long {
        if (expiresAt <= 0L) return 0L
        val now = System.currentTimeMillis()
        return when {
            expiresAt >= 1_000_000_000_000L -> expiresAt
            expiresAt >= 1_000_000_000L -> expiresAt * 1000L
            else -> now + (expiresAt * 1000L)
        }
    }

    // ── Session token ────────────────────────────────────────────────

    suspend fun getSessionToken(context: Context): String? {
        val data = getDataStore(context).data.first()
        val token = data.sessionToken ?: return null
        val expiresMs = data.sessionExpiresMs
        val now = System.currentTimeMillis()
        if (expiresMs > 0L && now >= expiresMs - EXPIRY_SKEW_MS) {
            clearSessionToken(context)
            return null
        }
        return token
    }

    suspend fun saveSessionToken(context: Context, token: String, expiresAtUnixSeconds: Long) {
        val normalizedExpiryMs = normalizeSessionExpiryMs(expiresAtUnixSeconds)
        getDataStore(context).updateData { current ->
            current.copy(
                sessionToken = token,
                sessionExpiresMs = normalizedExpiryMs
            )
        }
    }

    suspend fun clearSessionToken(context: Context) {
        getDataStore(context).updateData { current ->
            current.copy(
                sessionToken = null,
                sessionExpiresMs = 0L
            )
        }
    }

    suspend fun isSessionExpiringSoon(context: Context): Boolean {
        val data = getDataStore(context).data.first()
        if (data.sessionToken == null) return false
        val expiresMs = data.sessionExpiresMs
        if (expiresMs <= 0L) return false
        return System.currentTimeMillis() >= expiresMs - SESSION_REFRESH_WINDOW_MS
    }

    // ── Google ID token ──────────────────────────────────────────────

    suspend fun getGoogleIdToken(context: Context): String? {
        val data = getDataStore(context).data.first()
        val token = data.googleIdToken ?: return null
        val expiresMs = data.googleIdTokenExpiresMs
        val now = System.currentTimeMillis()
        if (expiresMs > 0L && now >= expiresMs - EXPIRY_SKEW_MS) {
            clearGoogleIdToken(context)
            return null
        }
        return token
    }

    suspend fun saveGoogleIdToken(context: Context, token: String) {
        // Simple JWT expiry parsing can be added here if needed, 
        // but for now we rely on external expiry info or store 0.
        getDataStore(context).updateData { current ->
            current.copy(
                googleIdToken = token,
                googleIdTokenExpiresMs = 0L // Could be parsed from JWT
            )
        }
    }

    suspend fun clearGoogleIdToken(context: Context) {
        getDataStore(context).updateData { current ->
            current.copy(
                googleIdToken = null,
                googleIdTokenExpiresMs = 0L
            )
        }
    }

    // ── Identity / sign-out ──────────────────────────────────────────

    suspend fun getSignedInEmail(context: Context): String? =
        getDataStore(context).data.first().signedInEmail

    suspend fun saveSignedInEmail(context: Context, email: String) {
        getDataStore(context).updateData { current ->
            current.copy(signedInEmail = email)
        }
    }

    suspend fun isSignedIn(context: Context): Boolean = getSessionToken(context) != null

    suspend fun signOut(context: Context) {
        getDataStore(context).updateData { AuthData() }
    }
}
