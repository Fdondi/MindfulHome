package com.mindfulhome.ai.backend

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mindfulhome.GoogleSignInActivity
import com.mindfulhome.ai.AuthManagerLogic
import com.mindfulhome.ai.AuthManagerLogic.InteractiveGoogleSignInStep

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
     * Attempts a silent sign-in using a previously authorized account.
     * Returns null without showing any UI if no authorized account is available.
     */
    suspend fun signInSilent(context: Context): SignInResult? {
        if (AuthManagerLogic.shouldSkipSilentCredentialManager(Build.VERSION.SDK_INT)) {
            Log.d(TAG, "Skipping silent Credential Manager on API ${Build.VERSION.SDK_INT}")
            return null
        }
        return try {
            requestSignIn(
                context,
                googleIdOption(filterByAuthorizedAccounts = true, autoSelect = true),
            )
        } catch (e: NoCredentialException) {
            Log.d(TAG, "No pre-authorized account for silent sign-in")
            null
        } catch (e: Exception) {
            Log.d(TAG, "Silent sign-in failed: ${e.message}")
            null
        }
    }

    /**
     * Triggers an interactive Google Sign-In and returns a [SignInResult], or null on failure.
     * Throws [NoCredentialException] only after One Tap and Sign in with Google have both
     * found no usable credential. Use [signInSilent] first for background token refresh.
     */
    suspend fun signIn(
        context: Context,
        forceAccountPicker: Boolean = false,
    ): SignInResult? {
        if (context is GoogleSignInActivity) {
            return signInWithCredentialManager(context, forceAccountPicker)
        }
        return GoogleSignInActivity.awaitSignIn(context, forceAccountPicker)
    }

    suspend fun signInWithCredentialManager(
        context: Context,
        forceAccountPicker: Boolean = false,
    ): SignInResult? {
        val first = AuthManagerLogic.initialInteractiveStep(forceAccountPicker)
        return try {
            requestSignIn(context, optionFor(first, forceAccountPicker))
        } catch (e: NoCredentialException) {
            retryInteractiveAfterNoCredential(context, first, forceAccountPicker, e)
        } catch (e: GetCredentialProviderConfigurationException) {
            throw e
        } catch (e: Exception) {
            if (AuthManagerLogic.isProviderConfigurationFailure(e)) throw e
            logInteractiveFailure(e)
            null
        }
    }

    private suspend fun retryInteractiveAfterNoCredential(
        context: Context,
        failedStep: InteractiveGoogleSignInStep,
        forceAccountPicker: Boolean,
        original: NoCredentialException,
    ): SignInResult? {
        val fallback = AuthManagerLogic.fallbackInteractiveStep(failedStep)
        if (fallback == null) {
            Log.w(TAG, "No usable Google account for this app")
            throw original
        }
        Log.d(TAG, "No credential for $failedStep; trying $fallback")
        return try {
            requestSignIn(context, optionFor(fallback, forceAccountPicker))
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No usable Google account for this app")
            throw e
        } catch (e: GetCredentialProviderConfigurationException) {
            throw e
        } catch (e: Exception) {
            if (AuthManagerLogic.isProviderConfigurationFailure(e)) throw e
            logInteractiveFailure(e)
            null
        }
    }

    /**
     * Credential Manager turns every non-OK result from the Play Services sign-in activity into
     * `GetCredentialCancellationException("activity is cancelled by the user")`, so a genuine
     * dismissal and a setup failure are indistinguishable here. Only the GMS log tells them apart
     * — `adb logcat -s Auth Auth.Api.Credentials` while reproducing. An unregistered package name
     * or signing SHA-1 shows up there as `UNREGISTERED_ON_API_CONSOLE`.
     */
    private fun logInteractiveFailure(e: Exception) {
        if (AuthManagerLogic.isGetCredentialCancellation(e)) {
            Log.w(TAG, "Sign-In ended without a credential; may be dismissal or setup failure", e)
            return
        }
        Log.e(TAG, "Google Sign-In failed", e)
    }

    private fun optionFor(
        step: InteractiveGoogleSignInStep,
        forceAccountPicker: Boolean,
    ): CredentialOption = when (step) {
        InteractiveGoogleSignInStep.OneTapAnyAccount ->
            googleIdOption(
                filterByAuthorizedAccounts = false,
                autoSelect = !forceAccountPicker,
            )
        InteractiveGoogleSignInStep.SignInWithGoogleButton ->
            GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build()
    }

    private fun googleIdOption(
        filterByAuthorizedAccounts: Boolean,
        autoSelect: Boolean,
    ): GetGoogleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
        .setServerClientId(WEB_CLIENT_ID)
        .setAutoSelectEnabled(autoSelect)
        .build()

    private suspend fun requestSignIn(
        context: Context,
        option: CredentialOption,
    ): SignInResult? {
        val activity = context.findActivity()
            ?: throw IllegalStateException("Google Sign-In needs an Activity")
        val credentialManager = CredentialManager.create(activity)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return handleSignIn(credentialManager.getCredential(activity, request))
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

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
