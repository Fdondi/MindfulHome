package com.mindfulhome.ai.backend

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Handles Google Sign-In via the Credential Manager API.
 *
 * Returns a [SignInResult] containing the Google ID token and the user's
 * email, or null on failure. Throws [NoCredentialException] when the
 * device has no usable Google account for this app.
 */
object AuthManager {

    private const val TAG = "AuthManager"

    // Web Client ID from the GCP project that hosts the backend.
    // This must match the GOOGLE_CLIENT_ID configured on the backend.
    private const val WEB_CLIENT_ID =
        "834588824353-dmcktqcifmgaovhfr0b37bdejjdq7lbn.apps.googleusercontent.com"

    data class SignInResult(val idToken: String, val email: String?)

    /**
     * Triggers Google Sign-In and returns a [SignInResult], or null on failure.
     * Throws [NoCredentialException] when no Google account is available.
     */
    suspend fun signIn(
        context: Context,
        forceAccountPicker: Boolean = false,
    ): SignInResult? {
        return try {
            val credentialManager = CredentialManager.create(context)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(!forceAccountPicker)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            handleSignIn(result)
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No usable Google account for this app")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            null
        }
    }

    private fun handleSignIn(result: GetCredentialResponse): SignInResult? {
        val credential = result.credential

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential =
                GoogleIdTokenCredential.createFrom(credential.data)
            val idToken = googleIdTokenCredential.idToken
            val email = googleIdTokenCredential.id // email address
            Log.i(TAG, "Google Sign-In successful for $email")
            return SignInResult(idToken, email)
        }

        Log.w(TAG, "Unexpected credential type: ${credential.type}")
        return null
    }
}
